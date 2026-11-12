package dev.agentbridge.idea;

import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.ui.*;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.project.Project;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Reloads already compiled output into exactly one debugger; never starts a second/global build. */
public final class TargetHotSwap {
    private TargetHotSwap() { }
    public static CompletableFuture<String> reload(Project project,ProcessHandler process) {
        var result=new CompletableFuture<String>();
        var debugger=DebuggerManagerEx.getInstanceEx(project);
        var debugProcess=debugger.getDebugProcess(process);
        var session=debugProcess==null ? null : debugger.getSession(debugProcess);
        if(session==null || !HotSwapUIImpl.canHotSwap(session)) {
            result.completeExceptionally(new IllegalStateException("Target debugger cannot HotSwap")); return result;
        }
        // false = no compilation. true would build all modules and invoke other sessions' compile listeners.
        HotSwapUI.getInstance(project).reloadChangedClasses(session,false,new HotSwapStatusListener() {
            @Override public void onSuccess(List<DebuggerSession> sessions) { result.complete("SUCCEEDED_OR_NO_CHANGES"); }
            @Override public void onFailure(List<DebuggerSession> sessions) {
                result.completeExceptionally(new IllegalStateException("HotSwap failed; no restart was requested"));
            }
            @Override public void onCancel(List<DebuggerSession> sessions) {
                result.completeExceptionally(new IllegalStateException("HotSwap cancelled; no restart was requested"));
            }
        });
        return result;
    }
}
