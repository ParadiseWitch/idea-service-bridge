package dev.agentbridge.idea;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** Stateless lifecycle hooks; the application service owns the interface and its lifetime. */
public final class BridgeLifecycleListener implements AppLifecycleListener, DynamicPluginListener {
    @Override public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        BridgeService.getInstance().autoStartOnce();
    }
    @Override public void pluginLoaded(@NotNull IdeaPluginDescriptor descriptor) {
        if(BridgeService.PLUGIN_ID.equals(descriptor.getPluginId().getIdString()))
            BridgeService.getInstance().autoStartOnce();
    }
}
