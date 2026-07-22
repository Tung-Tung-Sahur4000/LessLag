package tk.bridgersilk.lesslag.world;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import tk.bridgersilk.lesslag.LessLag;
import tk.bridgersilk.lesslag.performance.TPSUtil;

public class WorldManager {

	private final LessLag plugin;

	private final Map<String, Long> worldActivity = new ConcurrentHashMap<>();

	// Handles for the tasks this manager owns, so disable() cancels only
	// these and not every task belonging to the plugin (which used to break
	// unrelated features on /ll reload).
	private BukkitTask activityTask;
	private BukkitTask autoUnloadTask;
	private BukkitTask lowTpsUnloadTask;

	public WorldManager(LessLag plugin) {
		this.plugin = plugin;

		for (World world : Bukkit.getWorlds()) {
			worldActivity.put(world.getName(), System.currentTimeMillis());
		}

		startAutoUnloadTask();
		startLowTpsUnloadTask();
		startActivityTracker();
	}

	private void startActivityTracker() {
		activityTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			for (World world : Bukkit.getWorlds()) {
				if (!world.getPlayers().isEmpty()) {
					worldActivity.put(world.getName(), System.currentTimeMillis());
				}
			}
		}, 20L, 20L);
	}

	private void startAutoUnloadTask() {
		if (!plugin.getConfig().getBoolean("world_management.auto_unload.enabled")) return;

		long checkInterval = plugin.getConfig().getLong("settings.tps_check_interval", 20L);

		autoUnloadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			int inactivityMinutes = plugin.getConfig().getInt("world_management.auto_unload.inactivity_minutes", 5);
			long inactivityThreshold = inactivityMinutes * 60_000L;

			List<String> excluded = plugin.getConfig().getStringList("world_management.auto_unload.excluded_worlds");

			for (World world : Bukkit.getWorlds()) {
				if (excluded.contains(world.getName())) continue;

				Long lastActive = worldActivity.getOrDefault(world.getName(), System.currentTimeMillis());
				if (System.currentTimeMillis() - lastActive >= inactivityThreshold && world.getPlayers().isEmpty()) {
					unloadWorld(world, "auto-unload (inactive)");
				}
			}
		}, checkInterval, checkInterval);
	}

	private void startLowTpsUnloadTask() {
		if (!plugin.getConfig().getBoolean("world_management.force_unload_on_low_tps.enabled")) return;

		long checkInterval = plugin.getConfig().getLong("settings.tps_check_interval", 20L);
        String prefix = plugin.getConfig().getString("settings.prefix");

		lowTpsUnloadTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			double criticalTps = plugin.getConfig().getDouble("settings.critical_tps_threshold", 10.0);
			// Deliberately the SUSTAINED 1-minute average, not the spike
			// signal: unloading worlds and teleporting every player is
			// drastic and must not fire on a momentary tick spike.
			double currentTps = TPSUtil.getAverageTPS();

			if (currentTps < criticalTps) {
				String transferWorldName = plugin.getConfig().getString("world_management.force_unload_on_low_tps.player_transfer_world", "world");
				World transferWorld = Bukkit.getWorld(transferWorldName);

				if (transferWorld == null) {
					plugin.getLogger().warning("Transfer world '" + transferWorldName + "' not found. Skipping unload.");
					return;
				}

				for (World world : Bukkit.getWorlds()) {
					if (world.getName().equalsIgnoreCase(transferWorldName)) continue;

					for (Player p : world.getPlayers()) {
						p.teleport(transferWorld.getSpawnLocation());
						p.sendMessage(prefix + "§cServer lag detected. You have been moved to §b" + transferWorld.getName());
					}

					unloadWorld(world, "low TPS emergency");
				}
			}
		}, checkInterval, checkInterval);
	}

	private void unloadWorld(World world, String reason) {
		// Capture the cheap main-thread stats (and the folder path) before the
		// unload; the folder-size walk below is deferred off-thread.
		String name = world.getName();
		int entities = world.getEntities().size();
		int chunks = world.getLoadedChunks().length;
		int players = world.getPlayers().size();
		File folder = world.getWorldFolder();

        String prefix = plugin.getConfig().getString("settings.prefix");
        boolean saveWorld = plugin.getConfig().getBoolean("world_management.auto_unload.save_world");

		boolean success = Bukkit.unloadWorld(world, saveWorld);
		if (!success) return;

		// The recursive folder-size walk is disk I/O (potentially GBs of region
		// files); running it on the main thread stalled the server during an
		// unload. Do it async, then notify admins back on the main thread.
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			double sizeMb = bytesToMb(getFolderSize(folder));
			String message = String.format("§b%s §7| Entities: §f%d §7| Chunks: §f%d §7| Players: §f%d §7| Size: §f%.2f MB",
					name, entities, chunks, players, sizeMb);

			Bukkit.getScheduler().runTask(plugin, () -> {
				for (Player admin : Bukkit.getOnlinePlayers()) {
					if (admin.hasPermission("lesslag.admin")) {
						admin.sendMessage(prefix + "§eUnloaded world: " + message + " §7(Reason: " + reason + ")");
					}
				}
			});
		});
	}

	private long getFolderSize(File folder) {
		if (!folder.exists()) return 0L;
		if (folder.isFile()) return folder.length();

		long length = 0;
		File[] files = folder.listFiles();
		if (files != null) {
			for (File file : files) {
				length += getFolderSize(file);
			}
		}
		return length;
	}

	private double bytesToMb(long bytes) {
		return bytes / 1024.0 / 1024.0;
	}

	public void disable() {
		// Cancel only the tasks this manager started. The old
		// cancelTasks(plugin) call tore down EVERY LessLag task, killing
		// unrelated features (item stacking, profiler, etc.) on /ll reload.
		cancelTask(activityTask);
		cancelTask(autoUnloadTask);
		cancelTask(lowTpsUnloadTask);
		activityTask = null;
		autoUnloadTask = null;
		lowTpsUnloadTask = null;
		worldActivity.clear();
	}

	private void cancelTask(BukkitTask task) {
		if (task != null) {
			task.cancel();
		}
	}
}
