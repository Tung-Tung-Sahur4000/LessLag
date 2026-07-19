package tk.bridgersilk.lesslag.performance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
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
 *   <li><b>AI throttle when no player is near.</b> Villager AI (pathfinding, the
 *       wander/look goals, and the water-avoidance loop that makes a penned
 *       villager jump against the wall forever) ticks whether or not anyone is
 *       using the hall. Rather than hard-freezing it — which leaves villagers
 *       standing dead-still and reads as a broken/lagging server — we THROTTLE
 *       it with a duty cycle: while no player is within {@code throttleRadius}
 *       blocks, each villager's AI goals run only {@code throttleOnTicks} out of
 *       every {@code throttlePeriodTicks} ticks (via {@link Villager#setAware},
 *       which pauses goals without touching gravity/physics). Villagers still
 *       wander and turn, just sluggishly, so an idle pen looks normal while
 *       shedding most of the AI cost. The duty cycle is phase-staggered per
 *       villager, so a pen never blinks on/off in unison — at any tick a
 *       fraction of the herd is moving. Full-speed AI resumes when a player
 *       comes near.
 *   <li><b>Breeding soft-cap.</b> Above {@code maxPerChunk} villagers in a
 *       chunk, new breeding is cancelled so a pen can't grow without bound and
 *       waste entity/memory budget. Existing villagers are NEVER removed — this
 *       matches the plugin's pause-don't-wipe policy, so no farm is ever
 *       destroyed.
 * </ul>
 *
 * <p>The heavy classification pass (group villagers by chunk, apply collision,
 * decide near/far) runs only every {@code checkIntervalTicks}. The per-tick
 * work is limited to the small cached set of throttled (far) villagers and is
 * just an integer compare plus an occasional {@link Villager#setAware} at a
 * duty-cycle transition — far cheaper than the villager AI it saves. State is
 * only written when it actually changes (no redundant metadata packets).
 */
public class VillagerOptimizer implements Listener {

	private final Plugin plugin;

	private final int checkIntervalTicks;
	private final int collisionThreshold;
	private final boolean throttleEnabled;
	private final int throttleRadius;
	private final int throttlePeriodTicks;
	private final int throttleOnTicks;
	private final int throttleEfficiencyPercent;
	private final int maxPerChunk;

	// Snapshot of the last classify pass, for the /lesslag villagers readout.
	// Written and read only on the main thread.
	private int statTotal;
	private int statNear;
	private int statThrottled;
	private int statCollisionOff;
	private long statClassifyNanos;

	// Whether the AI throttle actually does anything: it must be enabled AND
	// leave villagers off for at least one tick per cycle (on < period). At
	// 100% efficiency (on == period) there is nothing to throttle.
	private final boolean throttleActive;

	// The far-from-players villagers currently subject to the duty cycle.
	// Rebuilt each classification pass; walked (cheaply) every tick.
	private final List<Villager> throttled = new ArrayList<>();

	private long tickCounter;
	private BukkitTask task;

	public VillagerOptimizer(
		Plugin plugin,
		int checkIntervalTicks,
		int collisionThreshold,
		boolean throttleEnabled,
		int throttleRadius,
		int throttlePeriodTicks,
		int throttleEfficiencyPercent,
		int maxPerChunk
	) {
		this.plugin = plugin;
		this.checkIntervalTicks = Math.max(20, checkIntervalTicks);
		this.collisionThreshold = collisionThreshold;
		this.throttleEnabled = throttleEnabled;
		this.throttleRadius = Math.max(1, throttleRadius);
		this.throttlePeriodTicks = Math.max(2, throttlePeriodTicks);
		this.maxPerChunk = maxPerChunk;

		// Convert the "efficiency %" into an on-window length, clamped so AI is
		// never fully off (>= 1 tick per cycle: villagers always keep a pulse
		// of AI, never a dead freeze) and never over 100%.
		int pct = Math.max(1, Math.min(100, throttleEfficiencyPercent));
		this.throttleEfficiencyPercent = pct;
		int on = Math.round(this.throttlePeriodTicks * pct / 100.0f);
		this.throttleOnTicks = Math.max(1, Math.min(this.throttlePeriodTicks, on));
		this.throttleActive = throttleEnabled && throttleOnTicks < throttlePeriodTicks;

		// Only the breeding soft-cap needs the event hook; skip registering it
		// (and the per-spawn guard cost) entirely when the cap is off.
		if (maxPerChunk > 0) {
			Bukkit.getPluginManager().registerEvents(this, plugin);
		}

		startTask();
	}

	private void startTask() {
		// Nothing to do if none of the periodic levers are on.
		if (collisionThreshold <= 0 && !throttleEnabled) return;

		// Runs every tick, but the expensive classification only fires every
		// checkIntervalTicks; the rest is the cheap duty-cycle pass.
		task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 1L);
	}

	private void tick() {
		if (tickCounter % checkIntervalTicks == 0) {
			classify();
		}
		if (throttleActive) {
			applyDutyCycle();
		}
		tickCounter++;
	}

	/**
	 * Heavy pass (every {@code checkIntervalTicks}): group villagers by chunk,
	 * apply the collision lever, and split them into near/far. Near villagers
	 * get full-speed AI pinned on; far villagers are collected for the duty
	 * cycle (or, if the throttle isn't active, also pinned on so nothing is
	 * left throttled).
	 */
	private void classify() {
		long start = System.nanoTime();
		throttled.clear();

		int total = 0;
		int collisionOff = 0;

		int chunkRadius = (throttleRadius + 15) / 16;

		for (World world : Bukkit.getWorlds()) {
			// One entity pass per world, villagers only. Grouping by chunk here
			// avoids calling getEntities() on every (mostly villager-free)
			// loaded chunk -- on a big server that was thousands of snapshot
			// allocations packed into a single tick every scan.
			Collection<Villager> villagers = world.getEntitiesByClass(Villager.class);
			if (villagers.isEmpty()) continue;

			Map<Long, List<Villager>> byChunk = new HashMap<>();
			for (Villager villager : villagers) {
				Location loc = villager.getLocation();
				long key = chunkKey(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
				byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(villager);
			}

			Set<Long> nearPlayerChunks =
				throttleEnabled ? buildNearPlayerChunks(world, chunkRadius) : null;

			for (Map.Entry<Long, List<Villager>> entry : byChunk.entrySet()) {
				List<Villager> group = entry.getValue();

				boolean crowded =
					collisionThreshold > 0 && group.size() > collisionThreshold;

				boolean playerNear = !throttleEnabled
					|| nearPlayerChunks.contains(entry.getKey());

				total += group.size();
				if (crowded) collisionOff += group.size();

				for (Villager villager : group) {
					if (collisionThreshold > 0) {
						boolean shouldCollide = !crowded;
						if (villager.isCollidable() != shouldCollide) {
							villager.setCollidable(shouldCollide);
						}
					}

					if (throttleEnabled) {
						if (playerNear || !throttleActive) {
							// Player nearby (or throttling is a no-op): full AI.
							setAware(villager, true);
						} else {
							// Far from players: hand to the duty cycle.
							throttled.add(villager);
						}
					}
				}
			}
		}

		statTotal = total;
		statCollisionOff = collisionOff;
		statThrottled = throttled.size();
		statNear = total - statThrottled;
		statClassifyNanos = System.nanoTime() - start;
	}

	/* ---- Debug readout for /lesslag villagers ---- */

	public boolean isThrottleActive() { return throttleActive; }
	public int getThrottleEfficiencyPercent() { return throttleEfficiencyPercent; }
	public int getThrottleRadius() { return throttleRadius; }
	public int getCheckIntervalTicks() { return checkIntervalTicks; }
	public int getCollisionThreshold() { return collisionThreshold; }
	public int getMaxPerChunk() { return maxPerChunk; }
	public int getStatTotal() { return statTotal; }
	public int getStatNear() { return statNear; }
	public int getStatThrottled() { return statThrottled; }
	public int getStatCollisionOff() { return statCollisionOff; }
	public double getLastClassifyMillis() { return statClassifyNanos / 1_000_000.0; }

	/**
	 * Light pass (every tick): drive the awareness duty cycle over the cached
	 * far villagers. Each villager's on-window is phase-shifted by its entity id
	 * so the herd never toggles in unison. {@code setAware} only fires on a
	 * transition, so this is ~2 writes per villager per cycle, not per tick.
	 */
	private void applyDutyCycle() {
		if (throttled.isEmpty()) return;

		for (int i = throttled.size() - 1; i >= 0; i--) {
			Villager villager = throttled.get(i);
			if (!villager.isValid()) {
				// Removed/unloaded since the last classify -- drop it.
				throttled.remove(i);
				continue;
			}
			int phase = villager.getEntityId() % throttlePeriodTicks;
			boolean aware =
				((tickCounter + phase) % throttlePeriodTicks) < throttleOnTicks;
			setAware(villager, aware);
		}
	}

	private static void setAware(Villager villager, boolean aware) {
		if (villager.isAware() != aware) {
			villager.setAware(aware);
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
		// Leave no villager stuck mid-throttle after a disable/reload: restore
		// full AI awareness on anything we were cycling.
		for (Villager villager : throttled) {
			if (villager.isValid()) {
				setAware(villager, true);
			}
		}
		throttled.clear();
	}
}
