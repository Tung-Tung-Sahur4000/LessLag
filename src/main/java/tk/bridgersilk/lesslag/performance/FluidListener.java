package tk.bridgersilk.lesslag.performance;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.plugin.Plugin;

public class FluidListener implements Listener {

    private final double tpsThreshold;

    public FluidListener(Plugin plugin, double tpsThreshold) {
        this.tpsThreshold = tpsThreshold;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onFluidFlow(BlockFromToEvent event) {
        // BlockFromToEvent is a hot path (a lava pyramid or drained monument
        // fires it thousands of times per tick). This listener is only
        // registered while the feature is enabled, so there is no per-event
        // config lookup; the responsive-TPS gate short-circuits when healthy,
        // and the type check is an enum compare with no String allocation.
        if (TPSUtil.getResponsiveTPS() > tpsThreshold) return;

        Material type = event.getBlock().getType();
        if (type == Material.WATER || type == Material.LAVA) {
            event.setCancelled(true);
        }
    }

    public void unregister() {
        BlockFromToEvent.getHandlerList().unregister(this);
    }
}
