package tk.bridgersilk.lesslag.system;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import tk.bridgersilk.lesslag.LessLag;

public class WorldInfo {
	private final DecimalFormat df = new DecimalFormat("#.##");

    private final LessLag plugin;
    private final FileConfiguration config;

    public WorldInfo(LessLag plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

	// One row of world stats. The cheap fields are read on the main thread; the
	// folder size (disk I/O) is filled in off-thread.
	private record WorldRow(String name, int entities, int chunks, int players, File folder) {}

	public void sendWorldInfo(CommandSender sender) {
        String prefix = config.getString("settings.prefix");

		sender.sendMessage(prefix + "§e--- World Information ---");

		// Snapshot the Bukkit-API stats now (must run on the main thread), but
		// defer the expensive recursive folder-size walk to an async task so
		// /lesslag worlds never freezes the server on a large world.
		List<WorldRow> rows = new ArrayList<>();
		for (World world : Bukkit.getWorlds()) {
			rows.add(new WorldRow(
					world.getName(),
					world.getEntities().size(),
					world.getLoadedChunks().length,
					world.getPlayers().size(),
					world.getWorldFolder()));
		}

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			List<String> lines = new ArrayList<>();
			for (WorldRow row : rows) {
				double fileSizeMB = getWorldFolderSize(row.folder()) / 1024.0 / 1024.0;
				lines.add(prefix + String.format(
						"§b%s §7| Entities: §f%d §7| Chunks: §f%d §7| Players: §f%d §7| Size: §f%s MB",
						row.name(), row.entities(), row.chunks(), row.players(), df.format(fileSizeMB)));
			}
			// Send back on the main thread.
			Bukkit.getScheduler().runTask(plugin, () -> lines.forEach(sender::sendMessage));
		});
	}

	private long getWorldFolderSize(File folder) {
		long length = 0;
		File[] files = folder.listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isFile()) {
					length += file.length();
				} else {
					length += getWorldFolderSize(file);
				}
			}
		}
		return length;
	}
}
