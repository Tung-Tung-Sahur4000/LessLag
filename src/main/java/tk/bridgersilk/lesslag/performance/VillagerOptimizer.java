package tk.bridgersilk.lesslag.performance;

import java.util.HashSet;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Targets the single most common "why is my server lagging" build: the packed
 * villager breeder / trading hall where dozens of villagers sit in a small pen.
 *
 * <p>Three independent, config-gated levers, each aimed at a distinct cost:
 *
 * <ul>
 *   <li><b>Collision.</b> Entity push physics is the dominant cost in a packed
 *       pen — every villager tests-and-resolves a push against every other
 *       villager it overlaps, every tick, which grows with the square of the
 *       count. Once a chunk holds more than {@code collisionThreshold}
 *       villagers we clear their collidable flag: they still breed, trade and
 *       restock, they just stop shoving each other. This is the big win and has
 *       essentially no gameplay downside for a breeder.
 *   <li><b>AI freeze when no player is near.</b> Villager AI (pathfinding, the
 *       wander/look goals, and the water-avoidance loop that makes a penned
 *       villager jump against the wall forever) ticks whether or not anyone is
 *       using the hall. When no player is within {@code freezeAiRadius} blocks
 *       we turn the AI off; it comes straight back the moment a player
 *       approaches, so an in-use trading hall behaves normally and only idle
 *       breeders stop burning tick time. NOTE: this also pauses villager-driven
 *       farms (e.g. iron farms) while nobody is nearby — see the config note.
 *   <li><b>Breeding soft-cap.</b> Above {@code maxPerChunk} villagers in a
 *       chunk, new breeding is cancelled so a pen can't grow without bound and
 *       waste entity/memory budget. Existing villagers are NEVER removed — this
 *       matches the plugin's pause-don't-wipe policy, so no farm is ever
 *       destroyed.
 * </ul>
 *
 * <p>All of the periodic work runs on a timer (not per-tick and not per-mob),
 * so its cost is independent of how many villagers exist, and state is only
 * written when it actually changes (no redundant metadata packets).
 */
public class VillagerOptimizer implements Listener {

	private final Plugin plugin;

	private final int checkIntervalTicks;
	private final int collisionThreshold;
	private final boolean freezeAiEnabled;
	private final int freezeAiRadius;
	private final int maxPerChunk;

	private BukkitTask task;

	public VillagerOptimizer(
		Plugin plugin,
		int checkIntervalTicks,
		int collisionThreshold,
		boolean freezeAiEnabled,
		int freezeAiRadius,
		int maxPerChunk
	) {
		this.plugin = plugin;
		this.checkIntervalTicks = Math.max(20, checkIntervalTicks);
		this.collisionThreshold = collisionThreshold;
		this.freezeAiEnabled = freezeAiEnabled;
		this.freezeAiRadius = Math.max(1, freezeAiRadius);
		this.maxPerChunk = maxPerChunk;

		// Only the breeding soft-cap needs the event hook; skip registering it
		// (and the per-spawn guard cost) entirely when the cap is off.
		if (maxPerChunk > 0) {
			Bukkit.getPluginManager().registerEvents(this, plugin);
		}

		startTask();
	}

	private void startTask() {
		// The collision lever and the AI-freeze lever are the only reasons to
		// run the periodic scan. If both are off, don't schedule anything.
		if (collisionThreshold <= 0 && !freezeAiEnabled) return;

		task = Bukkit.getScheduler().runTaskTimer(
			plugin,
			this::scan,
			checkIntervalTicks,
			checkIntervalTicks
		);
	}

	private void scan() {
		// Radius expressed in chunks, rounded up, so a villager whose chunk is
		// within this many chunks of any player's chunk counts as "near a
		// player". Chunk granularity slightly over-estimates the radius, which
		// errs toward keeping AI on — the safe direction.
		int chunkRadius = (freezeAiRadius + 15) / 16;

		for (World world : Bukkit.getWorlds()) {
			Set<Long> nearPlayerChunks =
				freezeAiEnabled ? buildNearPlayerChunks(world, chunkRadius) : null;

			for (Chunk chunk : world.getLoadedChunks()) {
				int villagerCount = 0;
				for (Entity entity : chunk.getEntities()) {
					if (entity instanceof Villager) villagerCount++;
				}
				if (villagerCount == 0) continue;

				boolean crowded =
					collisionThreshold > 0 && villagerCount > collisionThreshold;

				boolean playerNear = !freezeAiEnabled
					|| nearPlayerChunks.contains(chunkKey(chunk.getX(), chunk.getZ()));

				for (Entity entity : chunk.getEntities()) {
					if (!(entity instanceof Villager)) continue;
					Villager villager = (Villager) entity;

					if (collisionThreshold > 0) {
						boolean shouldCollide = !crowded;
						if (villager.isCollidable() != shouldCollide) {
							villager.setCollidable(shouldCollide);
						}
					}

					if (freezeAiEnabled) {
						if (villager.hasAI() != playerNear) {
							villager.setAI(playerNear);
						}
					}
				}
			}
		}
	}

	private Set<Long> buildNearPlayerChunks(World world, int chunkRadius) {
		Set<Long> keys = new HashSet<>();
		for (Player player : world.getPlayers()) {
			Chunk c = player.getLocation().getChunk();
			for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
				for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
					keys.add(chunkKey(c.getX() + dx, c.getZ() + dz));
				}
			}
		}
		return keys;
	}

	private static long chunkKey(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	@EventHandler(ignoreCancelled = true)
	public void onVillagerBreed(CreatureSpawnEvent event) {
		// Cheapest guards first: the breeding reason narrows this to the rare
		// baby-spawn path before we touch the entity or count the chunk.
		if (event.getSpawnReason() != SpawnReason.BREEDING) return;
		if (!(event.getEntity() instanceof Villager)) return;

		Chunk chunk = event.getLocation().getChunk();
		int villagerCount = 0;
		for (Entity entity : chunk.getEntities()) {
			if (entity instanceof Villager) villagerCount++;
		}

		if (villagerCount >= maxPerChunk) {
			event.setCancelled(true);
		}
	}

	public void unregister() {
		CreatureSpawnEvent.getHandlerList().unregister(this);
		if (task != null) {
			task.cancel();
			task = null;
		}
	}
}
