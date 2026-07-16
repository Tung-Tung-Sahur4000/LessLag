package tk.bridgersilk.lesslag.performance;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import tk.bridgersilk.lesslag.LessLag;

public class PerformanceManager {

	private final LessLag plugin;

	private boolean redstoneEnabled;
	private boolean redstoneClocksOnly;
	private double redstoneTpsThreshold;

	private boolean fallingBlocksEnabled;
	private double fallingBlocksTpsThreshold;

	private boolean fluidsEnabled;
	private double fluidsTpsThreshold;

	private boolean explosionsEnabled;
	private double explosionsTpsThreshold;

	private boolean enderPearlsEnabled;
	private double enderPearlsTpsThreshold;

	private boolean commandBlocksEnabled;
	private double commandBlocksTpsThreshold;

	private boolean mobAiDisableWhenNoPlayers;
	private int mobAiRadius;

	private boolean decreaseTickSpeed;
	private double tickSpeedThreshold;
	private int decreaseTickSpeedTo;

	private RedstoneListener redstoneListener;
	private FallingBlockListener fallingBlockListener;
	private FluidListener fluidListener;
	private ExplosionListener explosionListener;
	private EnderPearlListener enderPearlListener;
	private CommandBlockListener commandBlockListener;
	private MobAIListener mobAIListener;
	private TickSpeedListener tickSpeedListener;

	private BukkitTask aiCheckTask;

	public PerformanceManager(LessLag plugin) {
		this.plugin = plugin;

		reloadConfig();
		registerListeners();
	}

	public void reloadConfig() {
		FileConfiguration config = plugin
			.getConfigManager()
			.getConfig();

		redstoneEnabled = config.getBoolean(
			"performance_controls.disable_redstone.enabled"
		);

		redstoneClocksOnly = config.getBoolean(
			"performance_controls.disable_redstone.clocks_only"
		);

		redstoneTpsThreshold = config.getDouble(
			"performance_controls.disable_redstone.disable_below_tps"
		);

		fallingBlocksEnabled = config.getBoolean(
			"performance_controls.disable_falling_blocks.enabled"
		);

		fallingBlocksTpsThreshold = config.getDouble(
			"performance_controls.disable_falling_blocks.disable_below_tps"
		);

		fluidsEnabled = config.getBoolean(
			"performance_controls.disable_fluids.enabled"
		);

		fluidsTpsThreshold = config.getDouble(
			"performance_controls.disable_fluids.disable_below_tps"
		);

		explosionsEnabled = config.getBoolean(
			"performance_controls.disable_explosions.enabled"
		);

		explosionsTpsThreshold = config.getDouble(
			"performance_controls.disable_explosions.disable_below_tps"
		);

		enderPearlsEnabled = config.getBoolean(
			"performance_controls.disable_ender_pearls.enabled"
		);

		enderPearlsTpsThreshold = config.getDouble(
			"performance_controls.disable_ender_pearls.disable_below_tps"
		);

		commandBlocksEnabled = config.getBoolean(
			"performance_controls.disable_command_blocks.enabled"
		);

		commandBlocksTpsThreshold = config.getDouble(
			"performance_controls.disable_command_blocks.disable_below_tps"
		);

		mobAiDisableWhenNoPlayers = config.getBoolean(
			"mob_ai.disable_ai_when_no_players_nearby.enabled"
		);

		mobAiRadius = config.getInt(
			"mob_ai.disable_ai_when_no_players_nearby.radius"
		);

		decreaseTickSpeed = config.getBoolean(
			"performance_controls.decrease_tickspeed.enabled"
		);

		decreaseTickSpeedTo = config.getInt(
			"performance_controls.decrease_tickspeed.decrease_to"
		);

		tickSpeedThreshold = config.getDouble(
			"performance_controls.decrease_tickspeed.decrease_below_tps"
		);
	}

	private void registerListeners() {
		if (redstoneEnabled) {
			redstoneListener = new RedstoneListener(
				plugin,
				redstoneClocksOnly,
				redstoneTpsThreshold
			);
		}

		if (fallingBlocksEnabled) {
			fallingBlockListener = new FallingBlockListener(
				plugin,
				fallingBlocksTpsThreshold
			);
		}

		if (fluidsEnabled) {
			fluidListener = new FluidListener(
				plugin,
				fluidsTpsThreshold
			);
		}

		if (explosionsEnabled) {
			explosionListener = new ExplosionListener(
				plugin,
				explosionsTpsThreshold
			);
		}

		if (enderPearlsEnabled) {
			enderPearlListener = new EnderPearlListener(
				plugin,
				enderPearlsTpsThreshold
			);
		}

		if (commandBlocksEnabled) {
			commandBlockListener = new CommandBlockListener(
				plugin,
				commandBlocksTpsThreshold
			);
		}

		// mob_ai (disable_ai_when_no_players_nearby) is DISABLED at the
		// plugin level: its invulnerability logic was inverted and its
		// per-mob proximity scan added more tick cost than it saved.
		// Intentionally not registered regardless of config.
		if (false && mobAiDisableWhenNoPlayers) {
			mobAIListener = new MobAIListener(
				plugin,
				mobAiRadius
			);
		}

		if (decreaseTickSpeed) {
			tickSpeedListener = new TickSpeedListener(
				plugin,
				decreaseTickSpeedTo,
				tickSpeedThreshold
			);
		}
	}

	public void disable() {
		if (redstoneListener != null) {
			redstoneListener.unregister();
			redstoneListener = null;
		}

		if (fallingBlockListener != null) {
			fallingBlockListener.unregister();
			fallingBlockListener = null;
		}

		if (fluidListener != null) {
			fluidListener.unregister();
			fluidListener = null;
		}

		if (explosionListener != null) {
			explosionListener.unregister();
			explosionListener = null;
		}

		if (enderPearlListener != null) {
			enderPearlListener.unregister();
			enderPearlListener = null;
		}

		if (commandBlockListener != null) {
			commandBlockListener.unregister();
			commandBlockListener = null;
		}

		if (mobAIListener != null) {
			mobAIListener.unregister();
			mobAIListener = null;
		}

		if (aiCheckTask != null) {
			aiCheckTask.cancel();
			aiCheckTask = null;
		}
	}
}