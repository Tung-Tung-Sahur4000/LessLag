package tk.bridgersilk.lesslag.performance;

import org.bukkit.Bukkit;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;

public class CommandBlockListener implements Listener {

    private final double tpsThreshold;

    public CommandBlockListener(Plugin plugin, double tpsThreshold) {
        this.tpsThreshold = tpsThreshold;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onCommandBlockExecute(ServerCommandEvent event) {
        // ServerCommandEvent also fires for console commands; filter to
        // command-block senders first (cheap) before reading the TPS. Only
        // registered while the feature is enabled, so no config lookup here.
        if (!(event.getSender() instanceof BlockCommandSender)) return;
        if (TPSUtil.getResponsiveTPS() > tpsThreshold) return;

        event.setCancelled(true);
    }

    public void unregister() {
        ServerCommandEvent.getHandlerList().unregister(this);
    }
}
