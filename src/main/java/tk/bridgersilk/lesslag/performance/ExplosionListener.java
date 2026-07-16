package tk.bridgersilk.lesslag.performance;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ExplosionPrimeEvent;

import tk.bridgersilk.lesslag.LessLag;
import tk.bridgersilk.lesslag.performance.explosion.ExplosionQueueManager;

public class ExplosionListener implements Listener {

	private final LessLag plugin;
	private final double tpsThreshold;

	public ExplosionListener(
		LessLag plugin,
		double tpsThreshold
	) {
		this.plugin = plugin;
		this.tpsThreshold = tpsThreshold;

		Bukkit.getPluginManager().registerEvents(
			this,
			plugin
		);
	}

	@EventHandler(
		priority = EventPriority.HIGHEST,
		ignoreCancelled = true
	)
	public void onExplosionPrime(
		ExplosionPrimeEvent event
	) {
		FileConfiguration config = plugin
			.getConfigManager()
			.getConfig();

		if (
			!config.getBoolean(
				"performance_controls.disable_explosions.enabled",
				false
			)
		) {
			return;
		}

		ExplosionQueueManager queueManager =
			plugin.getExplosionQueueManager();

		/*
		 * Do not cancel explosions recreated by the queue.
		 */
		if (
			queueManager != null
				&& queueManager.isReplayingExplosion()
		) {
			return;
		}

		double currentTps = TPSUtil.getResponsiveTPS();

		if (currentTps > tpsThreshold) {
			return;
		}

		event.setCancelled(true);
	}

	public void unregister() {
		ExplosionPrimeEvent
			.getHandlerList()
			.unregister(this);
	}
}