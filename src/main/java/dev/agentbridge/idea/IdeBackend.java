package dev.agentbridge.idea;

import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.ui.*;
import com.intellij.execution.*;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.*;
import com.intellij.execution.runners.*;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.javaee.appServers.run.configuration.CommonModel;
import com.intellij.javaee.appServers.run.execution.J2EEProcess;
import com.intellij.javaee.appServers.run.execution.update.*;
import com.intellij.javaee.appServers.serverInstances.DefaultServerInstance;
import com.intellij.packaging.impl.compiler.ArtifactsCompiler;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.task.*;
import dev.agentbridge.core.*;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** All project/run-model access is confined to EDT. No toolbar selection is used. */
public final class IdeBackend implements BridgeBackend {
    private final Disposable lifetime=Disposer.newDisposable("Agent Bridge backend");
    private final Set<CompletableFuture<?>> active=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    @Override public List<Map<String,Object>> configurations() throws Exception {
        return edt(() -> {
            var result=new ArrayList<Map<String,Object>>();
            for(var project:ProjectManager.getInstance().getOpenProjects()) {
                if(project.isDisposed()) continue;
                var manager=ExecutionManagerImpl.getInstance(project);
                for(var settings:RunManager.getInstance(project).getAllSettings()) {
                    var instances=new ArrayList<Map<String,Object>>();
                    for(var descriptor:manager.getRunningDescriptors(s -> s==settings)) {
                        var process=descriptor.getProcessHandler();
                        if(process==null || process.isProcessTerminated()) continue;
                        var instance=new LinkedHashMap<String,Object>();
                        instance.put("executionId",descriptor.getExecutionId());
                        instance.put("executors",manager.getExecutors(descriptor).stream().map(com.intellij.execution.Executor::getId).toList());
                        instance.put("terminating",process.isProcessTerminating());
                        if(process instanceof J2EEProcess j2ee) {
                            var server=j2ee.getServerInstance();
                            instance.put("connected",server.isConnected()); instance.put("starting",server.isStarting());
                        }
                        instances.add(instance);
                    }
                    var item=new LinkedHashMap<String,Object>();
                    item.put("project",project.getBasePath()); item.put("configuration",settings.getName());
                    item.put("type",settings.getType().getId()); item.put("instances",instances);
                    item.put("indexing",DumbService.isDumb(project));
                    if(settings.getConfiguration() instanceof CommonModel model) {
                        item.put("port",model.getPort());
                        item.put("artifacts",model.getDeployedArtifacts().stream().map(a -> a.getName()).toList());
                    }
                    result.add(item);
                }
            }
            return result;
        }).get(15,TimeUnit.SECONDS);
    }
    @Override public CompletionStage<Map<String,Object>> execute(BridgeRequest request) {
        var future=new CompletableFuture<Map<String,Object>>();
        active.add(future);
        future.whenComplete((v,e) -> active.remove(future));
        onEdt(future,() -> {
            var configured=resolveConfiguration(request);
            if(DumbService.isDumb(configured.project)) throw new IllegalStateException("IDEA is indexing; retry later");
            if(CompilerManager.getInstance(configured.project).isCompilationActive())
                throw new IllegalStateException("Another IDEA compilation is active; retry later");
            for(var document:FileDocumentManager.getInstance().getUnsavedDocuments()) {
                var file=FileDocumentManager.getInstance().getFile(document);
                if(file!=null && ProjectFileIndex.getInstance(configured.project).isInContent(file))
                    throw new IllegalStateException("Project has unsaved IDEA documents; save or resolve them before agent operations");
            }
            if(request.operation().equals("start")) { start(configured,request.executor(),future); return; }
            var target=resolve(request);
            if(request.operation().equals("restart")) restart(target,future);
            else update(target,request.operation(),future);
        });
        return future;
    }
    private Configured resolveConfiguration(BridgeRequest request) {
        if(!Path.of(request.project()).isAbsolute()) throw new IllegalArgumentException("Absolute project path required");
        var projects=Arrays.stream(ProjectManager.getInstance().getOpenProjects())
            .filter(p -> !p.isDisposed() && p.getBasePath()!=null && samePath(p.getBasePath(),request.project())).toList();
        if(projects.size()!=1) throw new IllegalArgumentException("Open project not uniquely identified");
        var project=projects.getFirst();
        var settings=RunManager.getInstance(project).getAllSettings().stream()
            .filter(s->s.getName().equals(request.configuration())).toList();
        if(settings.size()!=1) throw new IllegalArgumentException("Run configuration not uniquely identified");
        return new Configured(project,settings.getFirst());
    }
    private Target resolve(BridgeRequest request) {
        var configured=resolveConfiguration(request);
        var project=configured.project;
        var selected=configured.settings;
        var manager=ExecutionManagerImpl.getInstance(project);
        var running=manager.getRunningDescriptors(s -> s==selected).stream()
            .filter(d->d.getProcessHandler()!=null && !d.getProcessHandler().isProcessTerminated()).toList();
        // IDEA's rerun machinery may stop every instance of one configuration. Require a single instance.
        if(running.size()!=1 || running.getFirst().getExecutionId()!=request.executionId())
            throw new IllegalStateException("Running instance changed or is ambiguous; query status again");
        var descriptor=running.getFirst(); var process=descriptor.getProcessHandler();
        if(process.isProcessTerminating()) throw new IllegalStateException("Target is terminating");
        var owners=manager.getConfigurations(descriptor);
        if(owners.size()!=1 || !owners.contains(selected))
            throw new IllegalStateException("Shared run descriptor is not supported");
        var environments=manager.getExecutionEnvironments(descriptor).stream()
            .filter(e->e.getRunnerAndConfigurationSettings()==selected).toList();
        if(environments.size()!=1) throw new IllegalStateException("Execution environment is ambiguous");
        return new Target(project,selected,descriptor,process,environments.getFirst());
    }
    private void start(Configured configured,String requestedExecutor,CompletableFuture<Map<String,Object>> future) throws Exception {
        var manager=ExecutionManagerImpl.getInstance(configured.project);
        if(manager.getRunningDescriptors(s->s==configured.settings).stream()
            .anyMatch(d->d.getProcessHandler()!=null && !d.getProcessHandler().isProcessTerminated()))
            throw new IllegalStateException("Configuration already running; query status instead of starting another instance");
        var executor="Run".equals(requestedExecutor)
            ? com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance()
            : com.intellij.execution.executors.DefaultDebugExecutor.getDebugExecutorInstance();
        configured.settings.checkSettings(executor);
        rejectChainedLaunch(configured.settings);
        var environment=ExecutionEnvironmentBuilder.create(executor,configured.settings).build();
        for(String mode:List.of("Run","Debug")) {
            var runner=ProgramRunner.getRunner(mode,configured.settings.getConfiguration());
            if(runner!=null && manager.isStarting(mode,runner.getRunnerId()))
                throw new IllegalStateException("An IDEA Run/Debug launch with this runner is already in progress; retry later");
        }
        environment.assignNewExecutionId(); environment.setHeadless();
        var operationLifetime=Disposer.newDisposable(lifetime,"Agent Bridge start");
        future.whenComplete((v,e)->Disposer.dispose(operationLifetime));
        watchLaunch(configured.project,environment,future,operationLifetime);
        environment.getRunner().execute(environment);
    }
    private void update(Target target,String operation,CompletableFuture<Map<String,Object>> future) {
        if(!(target.process instanceof J2EEProcess process)) throw new IllegalArgumentException("Update requires a running application-server configuration");
        var server=process.getServerInstance();
        var model=server.getCommonModel();
        if(!server.isConnected() || server.isStarting()) throw new IllegalStateException("Tomcat is not ready");
        if(!model.isLocal() || !UpdatingRunningApplicationUtil.isResourcesReloadingSupported(model))
            throw new IllegalArgumentException("Only local exploded deployment updates are supported");
        boolean classes=operation.equals("update-classes-and-resources");
        boolean debug=target.environment.getExecutor().getId().equals("Debug");
        // Run-mode .class replacement can trigger Tomcat context reload; fail rather than silently interrupt it.
        if(classes && !debug) throw new IllegalStateException("Class update requires Debug mode; explicitly request restart in Run mode");
        DebuggerSession session=null;
        if(classes) {
            var debugger=DebuggerManagerEx.getInstanceEx(target.project);
            var debugProcess=debugger.getDebugProcess(target.process);
            var selected=debugProcess==null ? null : debugger.getSession(debugProcess);
            if(selected==null || !HotSwapUIImpl.canHotSwap(selected))
                throw new IllegalStateException("Target debugger cannot HotSwap");
            session=selected;
        }
        final DebuggerSession selectedSession=session;
        var diagnostics=Collections.synchronizedList(new ArrayList<String>());
        var operationLifetime=Disposer.newDisposable(lifetime,"Agent Bridge compilation");
        future.whenComplete((v,e)->Disposer.dispose(operationLifetime));
        target.project.getMessageBus().connect(operationLifetime).subscribe(CompilerTopics.COMPILATION_STATUS,new CompilationStatusListener() {
            @Override public void compilationFinished(boolean aborted,int errors,int warnings,@NotNull CompileContext context) {
                for(var message:context.getMessages(CompilerMessageCategory.ERROR)) {
                    if(diagnostics.size()<20) diagnostics.add(message.getMessage());
                }
            }
        });
        // Refresh disk changes before compilation. Never save or overwrite another agent's dirty editor buffers.
        VirtualFileManager.getInstance().asyncRefresh(() -> onEdt(future,() -> {
            ensureRunning(target);
            compileUpdate(target,model,selectedSession,diagnostics,future);
        }));
    }
    private void compileUpdate(Target target,CommonModel model,DebuggerSession session,List<String> diagnostics,
                               CompletableFuture<Map<String,Object>> future) {
        ensureRunning(target);
        Promise<ProjectTaskManager.Result> compilation = session==null
            ? UpdatingRunningApplicationUtil.startCompilation(target.project,ReloadResourcesUpdatingPolicy.createCompileTask(target.project,model),false)
            : UpdatingRunningApplicationUtil.makeClassesAndResources(target.project,model,false);
        // These SDK helpers set SKIP_HOT_SWAP_KEY, so other Debug sessions are not automatically reloaded.
        compilation.onError(future::completeExceptionally).onSuccess(result -> onEdt(future,()-> {
            if(result.isAborted() || result.hasErrors())
                throw new IllegalStateException("Compilation "+(result.isAborted()?"cancelled":"failed")+": "+String.join("; ",diagnostics));
            ensureRunning(target);
            var server=((J2EEProcess)target.process).getServerInstance();
            var written=ArtifactsCompiler.getWrittenPaths(result.getContext());
            long resourceCount=written==null ? 0 : written.stream().filter(path->!path.endsWith(".class")).count();
            new ReloadResourcesUpdatingPolicy.UpdateResourceOnServerListener(server,false).accept(result);
            var outcome=new LinkedHashMap<String,Object>();
            outcome.put("compiled",true); outcome.put("resourceBuildCompleted",true);
            outcome.put("resourcePathsWritten",resourceCount);
            outcome.put("deploymentNotificationSubmitted",written!=null && server instanceof DefaultServerInstance);
            outcome.put("executionId",target.descriptor.getExecutionId()); outcome.put("applicationHealthVerified",false);
            outcome.put("note","IDE build completed; resource adoption and application health require an HTTP check");
            if(session==null) future.complete(outcome);
            else TargetHotSwap.reload(target.project,target.process).whenComplete((status,error)-> {
                if(error!=null) future.completeExceptionally(error);
                else { outcome.put("hotSwap",status); future.complete(outcome); }
            });
        }));
    }
    private void restart(Target target,CompletableFuture<Map<String,Object>> future) throws Exception {
        target.settings.getConfiguration().checkConfiguration();
        rejectChainedLaunch(target.settings);
        var environment=new ExecutionEnvironmentBuilder(target.environment).contentToReuse(target.descriptor).build();
        environment.assignNewExecutionId(); environment.setHeadless();
        var operationLifetime=Disposer.newDisposable(lifetime,"Agent Bridge restart");
        future.whenComplete((v,e)->Disposer.dispose(operationLifetime));
        watchLaunch(target.project,environment,future,operationLifetime);
        var listener=new ProcessAdapter() {
            @Override public void processTerminated(@NotNull ProcessEvent event) {
                onEdt(future,()-> {
                    if(target.project.isDisposed()) throw new IllegalStateException("Project closed during restart");
                    environment.getRunner().execute(environment);
                });
            }
        };
        target.process.addProcessListener(listener);
        Disposer.register(operationLifetime,()->target.process.removeProcessListener(listener));
        // Destroy only the exact requested process. Do not use restartRunProfile's multi-process stop logic.
        target.process.destroyProcess();
    }
    private static void rejectChainedLaunch(RunnerAndConfigurationSettings settings) {
        for(var task:settings.getConfiguration().getBeforeRunTasks()) {
            if(task.isEnabled() && task.getProviderId().toString().contains("RunConfiguration"))
                throw new IllegalStateException("Chained run-configuration before-launch tasks are not supported");
        }
    }
    private void watchLaunch(Project project,ExecutionEnvironment environment,CompletableFuture<Map<String,Object>> future,Disposable operationLifetime) {
        project.getMessageBus().connect(operationLifetime).subscribe(ExecutionManager.EXECUTION_TOPIC,new ExecutionListener() {
            @Override public void processNotStarted(@NotNull String executorId,@NotNull ExecutionEnvironment env) {
                if(env==environment) future.completeExceptionally(new IllegalStateException("Process did not start"));
            }
            @Override public void processNotStarted(@NotNull String executorId,@NotNull ExecutionEnvironment env,Throwable error) {
                if(env==environment) future.completeExceptionally(error==null ? new IllegalStateException("Process did not start") : error);
            }
            @Override public void processStarted(@NotNull String executorId,@NotNull ExecutionEnvironment env,@NotNull ProcessHandler handler) {
                if(env==environment) future.complete(Map.of("processStarted",true,"executionId",env.getExecutionId(),
                    "executor",executorId,"applicationHealthVerified",false,
                    "note","Process started; application readiness must be checked separately"));
            }
        });
    }
    private static void ensureRunning(Target target) {
        if(target.project.isDisposed() || target.process.isProcessTerminated() || target.process.isProcessTerminating())
            throw new IllegalStateException("Target stopped during operation");
    }
    private static boolean samePath(String a,String b) {
        return Path.of(a).toAbsolutePath().normalize().toString().equalsIgnoreCase(Path.of(b).toAbsolutePath().normalize().toString());
    }
    private <T> CompletableFuture<T> edt(Supplier<T> supplier) {
        var result=new CompletableFuture<T>(); onEdt(result,()->result.complete(supplier.get())); return result;
    }
    private void onEdt(CompletableFuture<?> future,ThrowingRunnable action) {
        Runnable guarded=()-> {
            if(future.isDone()) return;
            if(closed) { future.completeExceptionally(new CancellationException("Plugin unloaded")); return; }
            try { action.run(); } catch(Exception e) { future.completeExceptionally(e); }
        };
        if(ApplicationManager.getApplication().isDispatchThread()) guarded.run();
        else ApplicationManager.getApplication().invokeLater(guarded,ModalityState.nonModal());
    }
    @Override public void close() {
        closed=true; active.forEach(f->f.cancel(false)); Disposer.dispose(lifetime);
    }
    private record Target(Project project,RunnerAndConfigurationSettings settings,RunContentDescriptor descriptor,
                          ProcessHandler process,ExecutionEnvironment environment) {}
    private record Configured(Project project,RunnerAndConfigurationSettings settings) {}
    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
