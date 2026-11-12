package dev.agentbridge.idea;

import com.google.gson.*;
import com.intellij.ide.plugins.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import dev.agentbridge.core.BridgeServer;
import dev.agentbridge.core.BridgeHelp;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Lazy application service, disposed automatically on dynamic plugin unload. */
public final class BridgeService implements Disposable {
    public static final String PLUGIN_ID="dev.agentbridge.idea";
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path directory=Path.of(PathManager.getConfigPath(),"agent-service-bridge");
    private BridgeServer server;

    public BridgeService() {
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(DynamicPluginListener.TOPIC,new DynamicPluginListener() {
            @Override public void checkUnloadPlugin(IdeaPluginDescriptor descriptor) throws CannotUnloadPluginException {
                if(descriptor.getPluginId().getIdString().equals(PLUGIN_ID)) {
                    synchronized(BridgeService.this) {
                        if(server!=null && !server.quiesceIfIdle())
                            throw new CannotUnloadPluginException("Agent Bridge has an active task. Wait for completion before updating the plugin.");
                    }
                }
            }
        });
    }
    public static BridgeService getInstance() { return ApplicationManager.getApplication().getService(BridgeService.class); }
    public synchronized Path start() throws IOException {
        if(server!=null) { server.resume(); return directory.resolve("connection.json"); }
        Files.createDirectories(directory); restrictToOwner(directory);
        var settingsFile=directory.resolve("settings.json");
        Settings settings;
        if(Files.exists(settingsFile)) {
            settings=JSON.fromJson(Files.readString(settingsFile),Settings.class);
        } else {
            settings=new Settings();
        }
        if(settings==null || settings.port<1024 || settings.port>65535 ||
           settings.allowedConfigurations==null || settings.allowedConfigurations.isEmpty())
            throw new IOException("Invalid settings.json: require port 1024..65535 and a nonempty allowlist");
        // Rewrite the known settings to migrate legacy versions and remove their unused token field.
        writePrivate(settingsFile,JSON.toJson(settings));
        var candidate=new BridgeServer(new IdeBackend(),Set.copyOf(settings.allowedConfigurations));
        try {
            int port=candidate.start(settings.port);
            writePrivate(directory.resolve("connection.json"),JSON.toJson(Map.of("baseUrl","http://127.0.0.1:"+port,
                "authenticationRequired",false,"pid",ProcessHandle.current().pid(),"pluginVersion",BridgeHelp.VERSION)));
            server=candidate;
        } catch(Exception e) {
            candidate.close(); Files.deleteIfExists(directory.resolve("connection.json"));
            throw new IOException("Cannot start Agent Bridge: "+e.getMessage(),e);
        }
        return directory.resolve("connection.json");
    }
    public synchronized void stopIfIdle() throws IOException {
        if(server!=null && !server.quiesceIfIdle()) throw new IOException("仍有任务执行中，请完成后再停止 Agent 接口。");
        dispose();
    }
    public synchronized boolean isRunning() { return server!=null; }
    public synchronized String connectionDescription() {
        if(server==null) throw new IllegalStateException("Agent 接口尚未启动");
        return BridgeHelp.dialog("http://127.0.0.1:"+server.port(),directory.resolve("connection.json").toString());
    }
    @Override public synchronized void dispose() {
        if(server!=null) { server.close(); server=null; }
        try { Files.deleteIfExists(directory.resolve("connection.json")); } catch(IOException ignored) { }
    }
    private static void writePrivate(Path path,String content) throws IOException {
        // Secure the directory first, then create a file that inherits its ACL.
        Files.writeString(path,content,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        restrictToOwner(path);
    }
    private static void restrictToOwner(Path path) throws IOException {
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
        if(acl!=null) {
            var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(Files.isDirectory(path)) entry.setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(entry.build()));
        } else if(Files.getFileAttributeView(path,PosixFileAttributeView.class)!=null) {
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));
        }
    }
    private static final class Settings {
        int port=17891;
        List<String> allowedConfigurations=new ArrayList<>(List.of("portal","file-agent"));
    }
}
