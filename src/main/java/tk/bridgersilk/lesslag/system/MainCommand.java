package tk.bridgersilk.lesslag.system;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;

import tk.bridgersilk.lesslag.LessLag;
import tk.bridgersilk.lesslag.performance.VillagerOptimizer;

public class MainCommand
	implements CommandExecutor, TabCompleter {

	private final LessLag plugin;

	public MainCommand(LessLag plugin) {
		this.plugin = plugin;

		if (plugin.getCommand("lesslag") != null) {
			plugin.getCommand("lesslag").setExecutor(this);
			plugin.getCommand("lesslag").setTabCompleter(this);
		}
	}

	@Override
	public boolean onCommand(
		CommandSender sender,
		Command command,
		String label,
		String[] args
	) {
		FileConfiguration config =
			plugin.getConfigManager().getConfig();

		String prefix = config.getString(
			"settings.prefix",
			"§7[§cLessLag§7] §r"
		);

		if (args.length == 0) {
			sender.sendMessage(
				prefix
					+ "§eUse /lesslag "
					+ "<reload|info|profiler|worlds|villagers>"
			);

			return true;
		}

		switch (args[0].toLowerCase()) {
			case "reload":
				plugin.reloadPlugin();

				sender.sendMessage(
					prefix
						+ "§aConfig reloaded successfully!"
				);

				break;

			case "info":
				sender.sendMessage(
					prefix
						+ "§eLessLag v"
						+ plugin.getDescription().getVersion()
						+ " by "
						+ plugin.getDescription().getAuthors()
				);

				break;

			case "profiler":
				plugin.getProfiler().toggleProfiler(sender);
				break;

			case "worlds":
				WorldInfo worldInfo = new WorldInfo(plugin);
				worldInfo.sendWorldInfo(sender);
				break;

			case "villagers":
				handleVillagersCommand(sender, prefix);
				break;

			default:
				sender.sendMessage(
					prefix
						+ "§cUnknown subcommand. Use /lesslag "
						+ "<reload|info|profiler|worlds|villagers>"
				);
		}

		return true;
	}

	private void handleVillagersCommand(
		CommandSender sender,
		String prefix
	) {
		if (!sender.hasPermission("lesslag.admin")) {
			sender.sendMessage(
				prefix + "§cYou do not have permission for this."
			);
			return;
		}

		VillagerOptimizer opt =
			plugin.getPerformanceManager() != null
				? plugin.getPerformanceManager().getVillagerOptimizer()
				: null;

		if (opt == null) {
			sender.sendMessage(
				prefix
					+ "§cVillager optimizer is disabled "
					+ "(villager_optimization.enabled: false)."
			);
			return;
		}

		sender.sendMessage(prefix + "§e§lVillager Optimizer §7— live");
		sender.sendMessage(
			"§7 Villagers (loaded): §f" + opt.getStatTotal()
		);
		sender.sendMessage(
			"§7  Full AI §8(player near)§7: §a" + opt.getStatNear()
		);

		if (opt.isThrottleActive()) {
			sender.sendMessage(
				"§7  Throttled §8(~" + opt.getThrottleEfficiencyPercent()
					+ "%)§7: §6" + opt.getStatThrottled()
					+ " §8outside " + opt.getThrottleRadius() + " blocks"
			);
		} else {
			sender.sendMessage(
				"§7  Throttled: §8off (efficiency 100% or disabled)"
			);
		}

		if (opt.getCollisionThreshold() > 0) {
			sender.sendMessage(
				"§7  Collision off §8(>" + opt.getCollisionThreshold()
					+ "/chunk)§7: §b" + opt.getStatCollisionOff()
			);
		} else {
			sender.sendMessage("§7  Collision lever: §8off");
		}

		sender.sendMessage(
			"§7 Breeding cap: §f"
				+ (opt.getMaxPerChunk() > 0
					? opt.getMaxPerChunk() + " §8/chunk"
					: "§8off")
		);
		sender.sendMessage(
			String.format(
				"§7 Last scan cost: §f%.3f ms §8(every %ds) §7— this is the "
					+ "plugin's own tick cost",
				opt.getLastClassifyMillis(),
				opt.getCheckIntervalTicks() / 20
			)
		);
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (args.length == 1) {
			return filterCompletions(
				Arrays.asList(
					"reload",
					"info",
					"profiler",
					"worlds",
					"villagers"
				),
				args[0]
			);
		}

		return new ArrayList<>();
	}

	private List<String> filterCompletions(
		List<String> options,
		String input
	) {
		String lowerInput = input.toLowerCase();

		return options.stream()
			.filter(
				option -> option
					.toLowerCase()
					.startsWith(lowerInput)
			)
			.toList();
	}
}