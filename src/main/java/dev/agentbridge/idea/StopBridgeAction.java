package dev.agentbridge.idea;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

public final class StopBridgeAction extends DumbAwareAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        try { BridgeService.getInstance().stopIfIdle(); }
        catch(Exception e) { Messages.showErrorDialog(event.getProject(),e.getMessage(),"Agent Service Bridge"); }
    }
}
