package tk.bridgersilk.lesslag.performance.explosion;

import java.util.ArrayDeque;
import java.util.Queue;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import tk.bridgersilk.lesslag.LessLag;

public class ExplosionQueueManager {

	private static final String CONFIG_PATH = "explosion_queue.";

	private final LessLag plugin;
	private final Queue<QueuedExplosion> explosionQueue;

	private BukkitTask processingTask;

	private boolean active;
	private boolean replayingExplosion;

	public ExplosionQueueManager(LessLag plugin) {
		this.plugin = plugin;
		this.explosionQueue = new ArrayDeque<>();

		start();
	}

	public void start() {
		stopTaskOnly();
		explosionQueue.clear();
		replayingExplosion = false;

		if (!isConfiguredEnabled()) {
			active = false;

			plugin.getLogger().info(
				"Explosion queue is disabled."
			);

			return;
		}

		active = true;

		processingTask = Bukkit.getScheduler().runTaskTimer(
			plugin,
			this::processQueue,
			1L,
			1L
		);

		plugin.getLogger().info(
			"Explosion queue started."
		);
	}

	public void disable() {
		active = false;
		replayingExplosion = false;

		stopTaskOnly();
		explosionQueue.clear();
	}

	private void stopTaskOnly() {
		if (processingTask != null) {
			processingTask.cancel();
			processingTask = null;
		}
	}

	public QueueResult queueExplosion(
		QueuedExplosion explosion
	) {
		if (!isActive()) {
			return QueueResult.PROCESS_NORMALLY;
		}

		if (replayingExplosion) {
			return QueueResult.PROCESS_NORMALLY;
		}

		if (!shouldQueueAtCurrentTps()) {
			return QueueResult.PROCESS_NORMALLY;
		}

		int maximumQueueSize = Math.max(
			1,
			getConfig().getInt(
				CONFIG_PATH + "limits.max_queue_size",
				500
			)
		);

		if (explosionQueue.size() >= maximumQueueSize) {
			boolean cancelWhenFull = getConfig().getBoolean(
				CONFIG_PATH
					+ "limits.cancel_new_explosions_when_full",
				true
			);

			if (cancelWhenFull) {
				return QueueResult.CANCELLED_QUEUE_FULL;
			}

			return QueueResult.PROCESS_NORMALLY;
		}

		explosionQueue.offer(explosion);

		return QueueResult.QUEUED;
	}

	public boolean shouldQueueType(
		QueuedExplosionType type
	) {
		if (!isActive() || type == null) {
			return false;
		}

		return getConfig().getBoolean(
			CONFIG_PATH
				+ "queue_types."
				+ type.getConfigName(),
			false
		);
	}

	public boolean isWorldBypassed(World world) {
		if (world == null) {
			return true;
		}

		return getConfig()
			.getStringList(CONFIG_PATH + "bypass_worlds")
			.stream()
			.anyMatch(
				worldName -> worldName.equalsIgnoreCase(
					world.getName()
				)
			);
	}

	public boolean isActive() {
		return active && isConfiguredEnabled();
	}

	public boolean isReplayingExplosion() {
		return replayingExplosion;
	}

	public int getQueueSize() {
		return explosionQueue.size();
	}

	private void processQueue() {
		if (!isActive()) {
			explosionQueue.clear();
			return;
		}

		if (explosionQueue.isEmpty()) {
			return;
		}

		int explosionsThisTick =
			calculateExplosionsPerTick();

		for (
			int processed = 0;
			processed < explosionsThisTick;
			processed++
		) {
			QueuedExplosion explosion =
				explosionQueue.poll();

			if (explosion == null) {
				break;
			}

			processExplosion(explosion);
		}
	}

	private void processExplosion(
		QueuedExplosion explosion
	) {
		World world = Bukkit.getWorld(
			explosion.getWorldId()
		);

		if (world == null) {
			return;
		}

		Location location =
			explosion.createLocation(world);

		int chunkX = location.getBlockX() >> 4;
		int chunkZ = location.getBlockZ() >> 4;

		/*
		 * Avoid force-loading unloaded chunks just to process
		 * an old queued explosion.
		 */
		if (!world.isChunkLoaded(chunkX, chunkZ)) {
			return;
		}

		try {
			replayingExplosion = true;

			world.createExplosion(
				location,
				explosion.getPower(),
				explosion.shouldSetFire(),
				explosion.shouldBreakBlocks()
			);
		} catch (Exception exception) {
			plugin.getLogger().warning(
				"Failed to process queued "
					+ explosion.getType().getConfigName()
					+ " explosion in world "
					+ world.getName()
					+ ": "
					+ exception.getMessage()
			);
		} finally {
			replayingExplosion = false;
		}
	}

	private int calculateExplosionsPerTick() {
		int configuredMaximum = Math.max(
			1,
			getConfig().getInt(
				CONFIG_PATH + "explosions_per_tick",
				5
			)
		);

		boolean dynamicScaling = getConfig().getBoolean(
			CONFIG_PATH + "dynamic_scaling",
			true
		);

		if (!dynamicScaling) {
			return configuredMaximum;
		}

		double currentTps = getCurrentTps();

		double scale = currentTps / 20.0;
		scale = Math.max(0.20, Math.min(1.0, scale));

		return Math.max(
			1,
			(int) Math.floor(configuredMaximum * scale)
		);
	}

	private boolean shouldQueueAtCurrentTps() {
		boolean lowTpsOnly = getConfig().getBoolean(
			CONFIG_PATH + "low_tps_only.enabled",
			false
		);

		if (!lowTpsOnly) {
			return true;
		}

		double threshold = getConfig().getDouble(
			CONFIG_PATH + "low_tps_only.tps_below",
			15.0
		);

		return getCurrentTps() < threshold;
	}

	private double getCurrentTps() {
		double[] recentTps = Bukkit.getTPS();

		if (recentTps.length == 0) {
			return 20.0;
		}

		return Math.max(
			0.0,
			Math.min(20.0, recentTps[0])
		);
	}

	private boolean isConfiguredEnabled() {
		return getConfig().getBoolean(
			CONFIG_PATH + "enabled",
			true
		);
	}

	private FileConfiguration getConfig() {
		return plugin
			.getConfigManager()
			.getConfig();
	}

	public enum QueueResult {

		QUEUED,
		CANCELLED_QUEUE_FULL,
		PROCESS_NORMALLY
	}
}