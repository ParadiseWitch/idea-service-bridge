package dev.agentbridge.sandbox;

import com.google.gson.*;
import com.intellij.ide.plugins.*;
import com.intellij.openapi.application.*;
import com.intellij.openapi.extensions.PluginId;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only startup extension; never packaged in the production plugin. */
public final class SandboxStarter implements ApplicationStarter {
    public static void main(String[] args) {
        try {
            com.intellij.testFramework.TestApplicationManager.getInstance();
            new SandboxStarter().main(List.of());
        } catch(Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    @Override public String getCommandName() { return "agent-bridge-test"; }
    @Override public boolean isHeadless() { return true; }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }
    @Override public void main(List<String> args) {
        var report=new ArrayList<String>();
        try {
            report.add("IDE build: "+ApplicationInfo.getInstance().getBuild());
            report.add("Config: "+PathManager.getConfigPath());
            edt(()->com.intellij.openapi.actionSystem.ActionManager.getInstance());
            for(int i=1;i<=2;i++) testCycle(i,report);
            report.add("PASS: dynamic install / start / no-token HTTP / stop / unload / reload");
            Files.write(Path.of(System.getProperty("agentbridge.report")),report);
            System.out.println(String.join("\n",report));
            System.exit(0);
        } catch(Throwable e) {
            e.printStackTrace(); report.add("FAILED: "+e);
            try { Files.write(Path.of(System.getProperty("agentbridge.report")),report); } catch(Exception ignored) { }
            System.exit(1);
        }
    }
    private void testCycle(int cycle,List<String> report) throws Exception {
        var pluginPath=Path.of(System.getProperty("agentbridge.plugin"));
        IdeaPluginDescriptorImpl descriptor;
        try(var context=new DescriptorListLoadingContext()) { descriptor=PluginDescriptorLoader.loadDescriptor(pluginPath,context); }
        check(descriptor!=null,"Descriptor loaded");
        if(cycle==1) {
            var installedState=InstalledPluginsState.getInstance();
            boolean wasRequired=installedState.isRestartRequired();
            try {
                installedState.setRestartRequired(true);
                String blocked=edt(()->DynamicPlugins.INSTANCE.checkCanUnloadWithoutRestart(descriptor));
                check(blocked!=null && blocked.contains("pending restart operation"),"Global pending restart must block dynamic install");
                report.add("Confirmed SDK limitation: global pending restart blocks dynamic install even for a dynamically compatible plugin");
            } finally { installedState.setRestartRequired(wasRequired); }
        }
        var previous=PluginManagerCore.getPlugin(PluginId.getId("dev.agentbridge.idea"));
        check(previous==null || previous.getPluginClassLoader()==null,"Plugin inactive before dynamic install");
        String reason=edt(()->DynamicPlugins.INSTANCE.checkCanUnloadWithoutRestart(descriptor));
        check(reason==null,"Dynamic compatibility: "+reason);
        check(edt(()->DynamicPlugins.INSTANCE.loadPlugin(descriptor)),"Plugin loaded dynamically");
        check(PluginManagerCore.getPlugin(PluginId.getId("dev.agentbridge.idea"))!=null,"Plugin registered");
        int port=exerciseService(descriptor,report);
        check(edt(()->DynamicPlugins.INSTANCE.unloadPlugin(descriptor,new DynamicPlugins.UnloadPluginOptions().withUpdate(true).withDisable(false))),"Plugin unloaded dynamically for update");
        check(descriptor.getPluginClassLoader()==null,"Plugin classloader detached");
        check(edt(()->com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("AgentBridge.Start"))==null,"Plugin actions unregistered");
        try(var socket=new java.net.ServerSocket()) {
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress("127.0.0.1",port));
        }
        for(int attempt=0;attempt<100;attempt++) {
            boolean alive=Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive() && t.getName().startsWith("idea-agent-bridge"));
            if(!alive) break;
            Thread.sleep(20);
        }
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive() && t.getName().startsWith("idea-agent-bridge")),"No plugin worker threads remain");
        report.add("Cycle "+cycle+": load/unload true, port released, plugin workers terminated");
    }
    private int exerciseService(IdeaPluginDescriptorImpl descriptor,List<String> report) throws Exception {
        var serviceClass=descriptor.getPluginClassLoader().loadClass("dev.agentbridge.idea.BridgeService");
        Object service=edt(()->serviceClass.getMethod("getInstance").invoke(null));
        Path connection=edt(()->(Path)serviceClass.getMethod("start").invoke(service));
        var json=JsonParser.parseString(Files.readString(connection)).getAsJsonObject();
        String base=json.get("baseUrl").getAsString();
        check(!json.has("token"),"Connection file contains no token");
        check(!JsonParser.parseString(Files.readString(connection.resolveSibling("settings.json"))).getAsJsonObject().has("token"),"Legacy token removed from settings");
        var settings=JsonParser.parseString(Files.readString(connection.resolveSibling("settings.json"))).getAsJsonObject();
        check(!settings.has("allowedConfigurations"),"Legacy configuration allowlist removed");
        check(settings.get("port").getAsInt()==URI.create(base).getPort(),"Migration preserves configured port");
        String dialog=edt(()->(String)serviceClass.getMethod("connectionDescription").invoke(service));
        check(dialog.contains(base+"/v1/status") && dialog.contains(base+"/v1/skill") && dialog.contains(base+"/help"),"Dialog includes actual interface addresses");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/v1/status")).build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==200,"No-token HTTP works: "+response.body());
        check(JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("configurations")!=null,"IDE backend executed successfully");
        report.add("HTTP status in real IDE: "+response.body());
        for(String path:List.of("/help","/v1/help","/v1/skill")) {
            var help=client.send(HttpRequest.newBuilder(URI.create(base+path)).build(),HttpResponse.BodyHandlers.ofString());
            check(help.statusCode()==200,"Help available without token: "+path);
        }
        report.add("Browser help, JSON help, agent skill and dialog addresses verified; legacy token removed");
        if(report.stream().noneMatch(s->s.startsWith("Real runner restart:"))) BridgeIdeExercise.run(base,report,descriptor.getPluginClassLoader());
        // Test stopping and restarting the bridge without stopping or restarting IDEA.
        edt(()-> { serviceClass.getMethod("stopIfIdle").invoke(service); return null; });
        check(!Files.exists(connection),"Stop removed connection discovery file");
        edt(()->serviceClass.getMethod("start").invoke(service));
        return URI.create(base).getPort();
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static <T> T edt(CheckedSupplier<T> action) throws Exception {
        var result=new AtomicReference<T>(); var error=new AtomicReference<Throwable>();
        ApplicationManager.getApplication().invokeAndWait(()-> {
            try { result.set(action.get()); } catch(Throwable e) { error.set(e); }
        },ModalityState.nonModal());
        if(error.get()!=null) throw new Exception(error.get());
        return result.get();
    }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
