package tk.bridgersilk.lesslag.performance;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

public class EnderPearlListener implements Listener {

    private final Plugin plugin;
    private final double tpsThreshold;

    public EnderPearlListener(Plugin plugin, double tpsThreshold) {
        this.plugin = plugin;
        this.tpsThreshold = tpsThreshold;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        // Fires for every projectile; filter to ender pearls first so arrows,
        // snowballs, etc. never trigger a TPS read.
        if (!(event.getEntity() instanceof EnderPearl)) return;
        if (TPSUtil.getResponsiveTPS() > tpsThreshold) return;

        event.getEntity().remove();
        event.setCancelled(true);
    }

    @EventHandler
    public void onPearlUse(PlayerInteractEvent event) {
        // PlayerInteractEvent fires constantly. Do the cheap in-hand check
        // first and only touch the config (for the prefix) when we actually
        // cancel — the old code fetched the plugin + prefix on every single
        // interaction, for every player.
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.ENDER_PEARL) return;
        if (TPSUtil.getResponsiveTPS() > tpsThreshold) return;

        event.setCancelled(true);
        String prefix = plugin.getConfig().getString("settings.prefix");
        player.sendMessage(prefix + "§cEnder pearls are disabled due to low TPS.");
    }

    public void unregister() {
        PlayerInteractEvent.getHandlerList().unregister(this);
        ProjectileLaunchEvent.getHandlerList().unregister(this);
    }
}
