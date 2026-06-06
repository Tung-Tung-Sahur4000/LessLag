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
					+ "<reload|info|profiler|worlds|web>"
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

			case "web":
				handleWebCommand(sender, args, prefix);
				break;

			default:
				sender.sendMessage(
					prefix
						+ "§cUnknown subcommand. Use /lesslag "
						+ "<reload|info|profiler|worlds|web>"
				);
		}

		return true;
	}

	private void handleWebCommand(
		CommandSender sender,
		String[] args,
		String prefix
	) {
		if (!sender.hasPermission("lesslag.admin")) {
			sender.sendMessage(
				prefix
					+ "§cYou do not have permission to "
					+ "use the web interface."
			);

			return;
		}

		if (args.length < 2) {
			sender.sendMessage(
				prefix
					+ "§cUsage: /lesslag web "
					+ "<profiler|history|reports>"
			);

			return;
		}

		String page = args[1].toLowerCase();

		if (
			!page.equals("profiler")
				&& !page.equals("history")
				&& !page.equals("reports")
		) {
			sender.sendMessage(
				prefix
					+ "§cUnknown web page. Use "
					+ "<profiler|history|reports>."
			);

			return;
		}

		FileConfiguration config =
			plugin.getConfigManager().getConfig();

		if (
			!config.getBoolean(
				"web_interface.enabled",
				true
			)
		) {
			sender.sendMessage(
				prefix
					+ "§cThe web interface is disabled "
					+ "in config.yml."
			);

			return;
		}

		if (
			plugin.getWebServer() == null
				|| !plugin.getWebServer().isRunning()
		) {
			sender.sendMessage(
				prefix
					+ "§cThe web interface is not running. "
					+ "Check the server console for errors."
			);

			return;
		}

		String link =
			plugin.getWebServer().generateAccessLink(page);

		if (link == null) {
			sender.sendMessage(
				prefix
					+ "§cCould not generate the web link."
			);

			return;
		}

		boolean generatedAccessLink = config.getBoolean(
			"web_interface.generate_access_links",
			true
		);

		if (generatedAccessLink) {
			sender.sendMessage(
				prefix
					+ "§aYour temporary "
					+ page
					+ " access link:"
			);
		} else {
			sender.sendMessage(
				prefix
					+ "§a"
					+ capitalize(page)
					+ " web interface:"
			);
		}

		sender.sendMessage("§b§n" + link);
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
					"web"
				),
				args[0]
			);
		}

		if (
			args.length == 2
				&& args[0].equalsIgnoreCase("web")
				&& sender.hasPermission("lesslag.admin")
		) {
			return filterCompletions(
				Arrays.asList(
					"profiler",
					"history",
					"reports"
				),
				args[1]
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

	private String capitalize(String value) {
		if (value == null || value.isBlank()) {
			return value;
		}

		return Character.toUpperCase(value.charAt(0))
			+ value.substring(1);
	}
}