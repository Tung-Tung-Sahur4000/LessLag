package tk.bridgersilk.lesslag.performance;

import java.util.EnumSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;

public class FallingBlockListener implements Listener {

    // BlockPhysicsEvent is one of the most frequently fired events in the
    // game. Precompute the set of gravity-affected blocks once so the hot
    // path is an O(1) EnumSet lookup instead of allocating a String via
    // Material.name() and scanning it on every event.
    private static final Set<Material> GRAVITY_BLOCKS;
    static {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (Material m : Material.values()) {
            if (m == Material.SAND || m == Material.GRAVEL
                    || m.name().endsWith("CONCRETE_POWDER")) {
                set.add(m);
            }
        }
        GRAVITY_BLOCKS = set;
    }

    private final double tpsThreshold;

    public FallingBlockListener(Plugin plugin, double tpsThreshold) {
        this.tpsThreshold = tpsThreshold;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        // Only registered while enabled; gate on responsive TPS first so the
        // healthy case returns after a single cached read.
        if (TPSUtil.getResponsiveTPS() > tpsThreshold) return;

        if (GRAVITY_BLOCKS.contains(event.getBlock().getType())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        // EntitySpawnEvent fires for every entity (mob/item/xp) — check the
        // cheap type first so mob and item farms don't even read the TPS.
        if (event.getEntityType() != EntityType.FALLING_BLOCK) return;
        if (TPSUtil.getResponsiveTPS() > tpsThreshold) return;

        event.setCancelled(true);
    }

    public void unregister() {
        BlockPhysicsEvent.getHandlerList().unregister(this);
        EntitySpawnEvent.getHandlerList().unregister(this);
    }
}
