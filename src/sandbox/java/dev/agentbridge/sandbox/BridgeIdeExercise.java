package dev.agentbridge.sandbox;

import com.google.gson.*;
import com.intellij.execution.*;
import com.intellij.execution.application.*;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.*;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real IDEA configuration/runner/process exercise. All children belong to the disposable test project. */
final class BridgeIdeExercise {
    static void run(String base,List<String> report,ClassLoader pluginLoader) throws Exception {
        Path root=Path.of(System.getProperty("agentbridge.root"));
        Path content=root.resolve(".sandbox/probe-content"); Files.createDirectories(content.resolve("src"));
        Files.copy(root.resolve("src/fixture/ProbeMain.java"),content.resolve("src/ProbeMain.java"),StandardCopyOption.REPLACE_EXISTING);
        var builder=JavaTestFixtureFactory.createFixtureBuilder("AgentBridgeProcesses");
        JavaModuleFixtureBuilder<?> module=builder.addModule(JavaModuleFixtureBuilder.class);
        module.addContentRoot(content.toString()).addSourceRoot("src");
        module.addJdk(System.getProperty("java.home"));
        module.setOutputPath(root.resolve(".sandbox/probe-classes").toString());
        var fixture=builder.getFixture();
        edt(()-> { fixture.setUp(); return null; });
        Project project=fixture.getProject();
        var sdk=com.intellij.openapi.projectRoots.JavaSdk.getInstance().createJdk("Agent Bridge Real JBR",System.getProperty("java.home"),false);
        edt(()-> {
            ApplicationManager.getApplication().runWriteAction(()-> {
                com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().addJdk(sdk);
                com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(module.getFixture().getModule(),sdk);
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).setProjectSdk(sdk);
            });
            return null;
        });
        try {
            DumbService.getInstance(project).waitForSmartMode();
            Path a=root.resolve(".sandbox/probe-a.pid"),b=root.resolve(".sandbox/probe-b.pid");
            Files.deleteIfExists(a); Files.deleteIfExists(b);
            Files.deleteIfExists(Path.of(a+".port")); Files.deleteIfExists(Path.of(b+".port"));
            edt(()-> {
                createAndRun(project,module.getFixture().getModule(),"portal",a);
                createAndRun(project,module.getFixture().getModule(),"file-agent",b);
                return null;
            });
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            check(task(client,base,project.getBasePath(),"portal",0,"start").get("status").getAsString().equals("SUCCEEDED"),"Start portal via HTTP");
            check(task(client,base,project.getBasePath(),"file-agent",0,"start").get("status").getAsString().equals("SUCCEEDED"),"Start file-agent via HTTP");
            long oldPid=waitPid(a,-1),otherPid=waitPid(b,-1);
            checkConsole(project,"portal");
            checkConsole(project,"file-agent");
            check(task(client,base,project.getBasePath(),"portal",0,"start").get("status").getAsString().equals("FAILED"),"Duplicate start rejected");
            check(ProcessHandle.of(oldPid).orElseThrow().isAlive(),"Duplicate start preserves target");
            report.add("Real HTTP start: portal and file-agent launched in Debug; duplicate start rejected");
            long executionId=-1;
            JsonObject status=null;
            for(int attempt=0;attempt<100 && executionId<0;attempt++) {
                status=get(client,base+"/v1/status");
                for(var entry:status.getAsJsonArray("configurations")) {
                    var configuration=entry.getAsJsonObject();
                    if(configuration.get("configuration").getAsString().equals("portal") && !configuration.getAsJsonArray("instances").isEmpty())
                        executionId=configuration.getAsJsonArray("instances").get(0).getAsJsonObject().get("executionId").getAsLong();
                }
                if(executionId<0) Thread.sleep(100);
            }
            report.add("Real project status: "+status);
            check(executionId>0,"Real run descriptor is exposed");
            var stale=task(client,base,project.getBasePath(),executionId+100000,"restart");
            check(stale.get("status").getAsString().equals("FAILED"),"Stale execution ID rejected");
            check(ProcessHandle.of(oldPid).orElseThrow().isAlive(),"Stale ID did not stop target");
            var unsupported=task(client,base,project.getBasePath(),executionId,"update-resources");
            check(unsupported.get("status").getAsString().equals("FAILED"),"Non-server resource update rejected");
            var restarted=task(client,base,project.getBasePath(),executionId,"restart");
            check(restarted.get("status").getAsString().equals("SUCCEEDED"),"Real restart succeeded: "+restarted);
            long newPid=waitPid(a,oldPid);
            checkConsole(project,"portal");
            report.add("Start and restart retain non-headless run environments and non-hidden console descriptors; desktop UI attachment requires a full IDE");
            check(!ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false),"Old target process exited");
            check(ProcessHandle.of(otherPid).orElseThrow().isAlive(),"Other process is still alive");
            check(Files.readString(b).trim().equals(Long.toString(otherPid)),"Other process was not restarted");
            report.add("Real runner restart: portal PID "+oldPid+" -> "+newPid+"; file-agent PID "+otherPid+" unchanged");
            report.add("Real IDE guards: stale execution ID and non-server update rejected without stopping either process");
            check(probe(client,a).equals("v1:"+newPid),"Target starts with v1");
            check(probe(client,b).equals("v1:"+otherPid),"Other debugger starts with v1");
            Path source=content.resolve("src/ProbeMain.java");
            Files.writeString(source,Files.readString(source).replace("return \"v1\"","return \"v2\""));
            var compiler=new ProcessBuilder(System.getProperty("agentbridge.javac"),"--release","21","-encoding","UTF-8",
                "-d",root.resolve(".sandbox/probe-classes").toString(),source.toString()).redirectErrorStream(true).start();
            String compileOutput=new String(compiler.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            check(compiler.waitFor()==0,"Updated test fixture compiled: "+compileOutput);
            var handler=edt(()->ExecutionManagerImpl.getInstance(project).getRunningDescriptors(s->s.getName().equals("portal")).getFirst().getProcessHandler());
            var hotSwap=pluginLoader.loadClass("dev.agentbridge.idea.TargetHotSwap").getMethod("reload",Project.class,ProcessHandler.class);
            var completion=edt(()->(java.util.concurrent.CompletableFuture<?>)hotSwap.invoke(null,project,handler));
            completion.get(30,java.util.concurrent.TimeUnit.SECONDS);
            check(probe(client,a).equals("v2:"+newPid),"Target class updated without process restart");
            check(probe(client,b).equals("v1:"+otherPid),"Other debugger class unchanged");
            // No-change reload must also invoke its completion callback.
            edt(()->(java.util.concurrent.CompletableFuture<?>)hotSwap.invoke(null,project,handler)).get(30,java.util.concurrent.TimeUnit.SECONDS);
            report.add("Real targeted HotSwap: portal v1 -> v2, PID unchanged; file-agent stays v1 with unchanged PID; no-change callback completed");
        } catch(Throwable failure) {
            report.add(edt(()-> {
                var details=new ArrayList<String>();
                for(var d:ExecutionManagerImpl.getInstance(project).getRunningDescriptors(s->true)) {
                    var console=d.getExecutionConsole();
                    details.add(d.getDisplayName()+": "+(console instanceof com.intellij.execution.impl.ConsoleViewImpl view ? view.getText() : String.valueOf(console)));
                }
                return "Test console diagnostics: "+details;
            }));
            report.add("Process exercise failure: "+failure); throw failure;
        } finally {
            List<ProcessHandler> children=edt(()->Arrays.asList(ExecutionManagerImpl.getInstance(project).getRunningProcesses()));
            edt(()-> { children.forEach(ProcessHandler::destroyProcess); return null; });
            for(var child:children) check(child.waitFor(10_000),"Test child terminated");
            // Clean up even a runner that failed before publishing its RunContentDescriptor.
            for(String file:List.of("probe-a.pid","probe-b.pid")) {
                Path pidFile=root.resolve(".sandbox/"+file);
                if(Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
                    var process=ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()));
                    if(process.isPresent() && process.get().parent().map(p->p.pid()==ProcessHandle.current().pid()).orElse(false)) {
                        process.get().destroy(); process.get().onExit().get(10,java.util.concurrent.TimeUnit.SECONDS);
                    }
                }
            }
            Thread.sleep(500);
            edt(()-> { ApplicationManager.getApplication().runWriteAction(()->com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().removeJdk(sdk)); return null; });
            try { edt(()-> { fixture.tearDown(); return null; }); }
            catch(Exception cleanup) { report.add("Fixture cleanup failure: "+cleanup.getMessage().lines().findFirst().orElse("")); throw cleanup; }
        }
    }
    private static void createAndRun(Project project,com.intellij.openapi.module.Module module,String name,Path pid) throws Exception {
        var manager=RunManager.getInstance(project);
        var settings=manager.createConfiguration(name,ApplicationConfigurationType.getInstance().getConfigurationFactories()[0]);
        var config=(ApplicationConfiguration)settings.getConfiguration();
        config.setModule(module); config.setMainClassName("ProbeMain");
        config.setProgramParameters('"'+pid.toString()+'"');
        config.setWorkingDirectory(pid.getParent().toString());
        config.setBeforeRunTasks(List.of()); config.setShortenCommandLine(ShortenCommandLine.ARGS_FILE);
        settings.setActivateToolWindowBeforeRun(false); settings.setFocusToolWindowBeforeRun(false);
        manager.addConfiguration(settings);

    }
    private static void checkConsole(Project project,String configuration) throws Exception {
        edt(()-> {
            var descriptor=ExecutionManagerImpl.getInstance(project)
                .getRunningDescriptors(s->s.getName().equals(configuration)).getFirst();
            var environment=ExecutionManagerImpl.getInstance(project).getExecutionEnvironments(descriptor).iterator().next();
            check(!environment.isHeadless(),configuration+" must use normal IDEA run content, not headless launch");
            check(descriptor.getExecutionConsole()!=null,configuration+" has a console");
            check(!descriptor.isHiddenContent(),configuration+" run content must not be hidden");
            // RunContentManagerImpl.showRunContent returns immediately in SDK unit-test mode.
            // Actual tool-window attachment must be verified in the user's full IDEA.
            return null;
        });
    }
    private static String probe(HttpClient client,Path pidFile) throws Exception {
        Path port=Path.of(pidFile+".port");
        for(int i=0;i<100 && !Files.exists(port);i++) Thread.sleep(20);
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+Files.readString(port).trim()+"/probe"))
            .timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString()).body();
    }
    private static JsonObject task(HttpClient client,String base,String project,long id,String op) throws Exception {
        return task(client,base,project,"portal",id,op);
    }
    private static JsonObject task(HttpClient client,String base,String project,String configuration,long id,String op) throws Exception {
        var body=new Gson().toJson(Map.of("project",project,"configuration",configuration,"executionId",id,"operation",op,"caller","isolated-sdk-test","executor","Debug"));
        var request=HttpRequest.newBuilder(URI.create(base+"/v1/tasks"))
            .header("Content-Type","application/json").timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==202,"Task accepted: "+response.body());
        var task=JsonParser.parseString(response.body()).getAsJsonObject();
        for(int i=0;i<200 && task.get("status").getAsString().equals("RUNNING");i++) {
            Thread.sleep(100); task=get(client,base+"/v1/tasks/"+task.get("id").getAsString());
        }
        return task;
    }
    private static JsonObject get(HttpClient client,String url) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .build(),HttpResponse.BodyHandlers.ofString());
        check(response.statusCode()==200,"Query succeeded: "+response.body()); return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static long waitPid(Path path,long old) throws Exception {
        for(int i=0;i<300;i++) {
            if(Files.exists(path)) {
                String content=Files.readString(path).trim();
                if(!content.isEmpty() && Long.parseLong(content)!=old) return Long.parseLong(content);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Test process did not start: "+path);
    }
    private static void check(boolean b,String m) { if(!b) throw new AssertionError(m); }
    private static <T> T edt(CheckedSupplier<T> action) throws Exception {
        var result=new AtomicReference<T>(); var error=new AtomicReference<Throwable>();
        ApplicationManager.getApplication().invokeAndWait(()-> {
            try { result.set(action.get()); } catch(Throwable e) { error.set(e); }
        },ModalityState.nonModal());
        if(error.get()!=null) throw new Exception(error.get()); return result.get();
    }
    @FunctionalInterface private interface CheckedSupplier<T> { T get() throws Exception; }
}
