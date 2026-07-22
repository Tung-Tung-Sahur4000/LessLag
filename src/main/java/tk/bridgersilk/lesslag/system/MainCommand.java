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
					+ "<reload|info|profiler|worlds>"
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

			default:
				sender.sendMessage(
					prefix
						+ "§cUnknown subcommand. Use /lesslag "
						+ "<reload|info|profiler|worlds>"
				);
		}

		return true;
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
					"worlds"
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