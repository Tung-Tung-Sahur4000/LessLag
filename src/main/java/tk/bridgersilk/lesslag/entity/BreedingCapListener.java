package tk.bridgersilk.lesslag.entity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

/**
 * Enforces the global per-type population caps for farm / breedable mobs
 * (see {@code entity_management.breedable_global_limits}). When a capped type
 * is already at or over its server-wide limit, further growth is cancelled at
 * the source -- existing mobs are never removed (pause-don't-wipe).
 *
 * <p>Only the two spawn reasons that actually grow a farm are gated: BREEDING
 * (animals and villagers producing a baby) and EGG (thrown/hatched chicken
 * eggs). Because hostile mobs never spawn from either reason, and because only
 * types explicitly listed in the config are capped, hostile mobs are never
 * affected. The over-limit check reads a count cached every ~10s by
 * {@link EntityManager}, so it costs O(1) per event.
 */
public class BreedingCapListener implements Listener {

	private final EntityManager entityManager;

	public BreedingCapListener(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@EventHandler(ignoreCancelled = true)
	public void onFarmGrowth(CreatureSpawnEvent event) {
		SpawnReason reason = event.getSpawnReason();
		if (reason != SpawnReason.BREEDING && reason != SpawnReason.EGG) return;

		String type = event.getEntityType().name().toLowerCase();
		if (entityManager.isOverBreedableCap(type)) {
			event.setCancelled(true);
		}
	}
}
