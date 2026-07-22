package tk.bridgersilk.lesslag;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;

import tk.bridgersilk.lesslag.entity.CommandControlListener;
import tk.bridgersilk.lesslag.entity.EntityManager;
import tk.bridgersilk.lesslag.entity.BreedingCapListener;
import tk.bridgersilk.lesslag.entity.SpawnControlListener;
import tk.bridgersilk.lesslag.item.ItemManagement;
import tk.bridgersilk.lesslag.performance.PerformanceManager;
import tk.bridgersilk.lesslag.performance.explosion.ExplosionQueueListener;
import tk.bridgersilk.lesslag.performance.explosion.ExplosionQueueManager;
import tk.bridgersilk.lesslag.player.ChatListener;
import tk.bridgersilk.lesslag.player.PlayerJoinListener;
import tk.bridgersilk.lesslag.player.PlayerManager;
import tk.bridgersilk.lesslag.player.PlayerTeleportListener;
import tk.bridgersilk.lesslag.system.ConfigManager;
import tk.bridgersilk.lesslag.system.MainCommand;
import tk.bridgersilk.lesslag.system.Profiler;
import tk.bridgersilk.lesslag.world.WorldManager;

public class LessLag extends JavaPlugin {

	private ConfigManager configManager;
	private Profiler profiler;
	private WorldManager worldManager;
	private ItemManagement itemManagement;
	private EntityManager entityManager;
	private PlayerManager playerManager;
	private PerformanceManager performanceManager;

	private ExplosionQueueManager explosionQueueManager;
	private ExplosionQueueListener explosionQueueListener;

	private ProtocolManager protocolManager;

	@Override
	public void onLoad() {
		protocolManager =
			ProtocolLibrary.getProtocolManager();
	}

	@Override
	public void onEnable() {
		saveDefaultConfig();

		configManager = new ConfigManager(this);

		profiler = new Profiler(this);
		worldManager = new WorldManager(this);

		int pluginId = 27003;
		new Metrics(this, pluginId);

		new MainCommand(this);

		itemManagement = new ItemManagement(this);
		entityManager = new EntityManager(this);
		performanceManager = new PerformanceManager(this);

		enableExplosionQueue();

		Bukkit.getPluginManager().registerEvents(
			new SpawnControlListener(entityManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new BreedingCapListener(entityManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new CommandControlListener(entityManager),
			this
		);

		playerManager = new PlayerManager(this);

		Bukkit.getPluginManager().registerEvents(
			new PlayerJoinListener(playerManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new PlayerTeleportListener(playerManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new ChatListener(playerManager),
			this
		);

		getLogger().info(
			"LessLag enabled! Config loaded and Profiler initialized."
		);
	}

	@Override
	public void onDisable() {
		disableExplosionQueue();

		if (worldManager != null) {
			worldManager.disable();
			worldManager = null;
		}

		if (itemManagement != null) {
			itemManagement.disable();
			itemManagement = null;
		}

		if (entityManager != null) {
			entityManager.stopTasks();
			entityManager = null;
		}

		if (performanceManager != null) {
			performanceManager.disable();
			performanceManager = null;
		}

		if (playerManager != null) {
			playerManager.disable();
			playerManager = null;
		}

		getLogger().info("LessLag disabled!");
	}

	public ConfigManager getConfigManager() {
		return configManager;
	}

	public Profiler getProfiler() {
		return profiler;
	}

	public PerformanceManager getPerformanceManager() {
		return performanceManager;
	}

	public EntityManager getEntityManager() {
		return entityManager;
	}

	public ExplosionQueueManager getExplosionQueueManager() {
		return explosionQueueManager;
	}

	public ProtocolManager getProtocolManager() {
		return protocolManager;
	}

	private void enableExplosionQueue() {
		/*
		 * Prevent accidental duplicate registration if this method
		 * gets called more than once.
		 */
		disableExplosionQueue();

		explosionQueueManager =
			new ExplosionQueueManager(this);

		explosionQueueListener =
			new ExplosionQueueListener(
				explosionQueueManager
			);

		Bukkit.getPluginManager().registerEvents(
			explosionQueueListener,
			this
		);
	}

	private void disableExplosionQueue() {
		if (explosionQueueListener != null) {
			HandlerList.unregisterAll(
				explosionQueueListener
			);

			explosionQueueListener = null;
		}

		if (explosionQueueManager != null) {
			explosionQueueManager.disable();
			explosionQueueManager = null;
		}
	}

	public void reloadPlugin() {
		/*
		 * Disable old listeners and tasks before creating any
		 * replacement instances.
		 */
		disableExplosionQueue();

		if (profiler != null) {
			profiler.disable();
		}

		if (worldManager != null) {
			worldManager.disable();
		}

		if (itemManagement != null) {
			itemManagement.disable();
		}

		if (entityManager != null) {
			entityManager.stopTasks();
		}

		if (playerManager != null) {
			playerManager.disable();
		}

		if (performanceManager != null) {
			performanceManager.disable();
		}

		/*
		 * Reload both Bukkit's configuration and the custom
		 * ConfigManager configuration.
		 */
		reloadConfig();
		configManager.reloadConfig();

		/*
		 * Clear every listener this plugin registered before rebuilding.
		 * Several listeners (PlayerJoin/PlayerTeleport/Chat/SpawnControl/
		 * CommandControl/BreedingCap) are registered as inline instances with
		 * no field reference, so nothing else can unregister them -- without
		 * this, each reload would stack a fresh copy on top of the old ones and
		 * fire their handlers twice (then three times, ...). Managers recreated
		 * below re-register their own listeners fresh.
		 */
		HandlerList.unregisterAll(this);

		/*
		 * Recreate all managers using the new configuration.
		 */
		profiler = new Profiler(this);
		worldManager = new WorldManager(this);
		itemManagement = new ItemManagement(this);
		entityManager = new EntityManager(this);
		playerManager = new PlayerManager(this);
		performanceManager = new PerformanceManager(this);

		enableExplosionQueue();

		Bukkit.getPluginManager().registerEvents(
			new PlayerJoinListener(playerManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new PlayerTeleportListener(playerManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new ChatListener(playerManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new SpawnControlListener(entityManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new BreedingCapListener(entityManager),
			this
		);

		Bukkit.getPluginManager().registerEvents(
			new CommandControlListener(entityManager),
			this
		);

		getLogger().info(
			"Plugin reloaded. Features updated with the latest config."
		);
	}
}