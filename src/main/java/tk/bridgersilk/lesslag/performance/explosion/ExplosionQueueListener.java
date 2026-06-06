package tk.bridgersilk.lesslag.performance.explosion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;

import tk.bridgersilk.lesslag.performance.explosion.ExplosionQueueManager.QueueResult;

public class ExplosionQueueListener implements Listener {

	private final ExplosionQueueManager queueManager;

	public ExplosionQueueListener(
		ExplosionQueueManager queueManager
	) {
		this.queueManager = queueManager;
	}

	@EventHandler(
		priority = EventPriority.LOWEST,
		ignoreCancelled = true
	)
	public void onEntityExplosionPrime(
		ExplosionPrimeEvent event
	) {
		if (!queueManager.isActive()) {
			return;
		}

		if (queueManager.isReplayingExplosion()) {
			return;
		}

		Entity entity = event.getEntity();

		if (
			queueManager.isWorldBypassed(
				entity.getWorld()
			)
		) {
			return;
		}

		QueuedExplosionType type =
			getEntityExplosionType(entity);

		if (type == null) {
			return;
		}

		if (!queueManager.shouldQueueType(type)) {
			return;
		}

		Location location =
			entity.getLocation().clone();

		QueuedExplosion explosion = new QueuedExplosion(
			location,
			event.getRadius(),
			event.getFire(),
			true,
			type
		);

		QueueResult result =
			queueManager.queueExplosion(explosion);

		if (result == QueueResult.PROCESS_NORMALLY) {
			return;
		}

		event.setCancelled(true);
		removeExplosiveEntity(entity);
	}

	@EventHandler(
		priority = EventPriority.LOWEST,
		ignoreCancelled = true
	)
	public void onBlockExplosion(
		BlockExplodeEvent event
	) {
		if (!queueManager.isActive()) {
			return;
		}

		if (queueManager.isReplayingExplosion()) {
			return;
		}

		if (
			queueManager.isWorldBypassed(
				event.getBlock().getWorld()
			)
		) {
			return;
		}

		BlockState explodedState =
			event.getExplodedBlockState();

		Material material =
			explodedState.getType();

		QueuedExplosionType type =
			getBlockExplosionType(material);

		if (type == null) {
			return;
		}

		if (!queueManager.shouldQueueType(type)) {
			return;
		}

		Location location = event
			.getBlock()
			.getLocation()
			.add(0.5, 0.5, 0.5);

		QueuedExplosion explosion = new QueuedExplosion(
			location,
			5.0F,
			true,
			true,
			type
		);

		QueueResult result =
			queueManager.queueExplosion(explosion);

		if (result == QueueResult.PROCESS_NORMALLY) {
			return;
		}

		event.setCancelled(true);
	}

	private QueuedExplosionType getEntityExplosionType(
		Entity entity
	) {
		if (entity instanceof TNTPrimed) {
			return QueuedExplosionType.TNT;
		}

		if (entity instanceof Creeper) {
			return QueuedExplosionType.CREEPER;
		}

		if (entity instanceof EnderCrystal) {
			return QueuedExplosionType.END_CRYSTAL;
		}

		if (entity instanceof WitherSkull) {
			return QueuedExplosionType.WITHER_SKULL;
		}

		if (entity instanceof LargeFireball) {
			return QueuedExplosionType.GHAST_FIREBALL;
		}

		return null;
	}

	private QueuedExplosionType getBlockExplosionType(
		Material material
	) {
		if (material == Material.RESPAWN_ANCHOR) {
			return QueuedExplosionType.RESPAWN_ANCHOR;
		}

		if (isBed(material)) {
			return QueuedExplosionType.BED;
		}

		return null;
	}

	private boolean isBed(Material material) {
		return material != null
			&& material.name().endsWith("_BED");
	}

	private void removeExplosiveEntity(Entity entity) {
		if (entity == null) {
			return;
		}

		if (!entity.isValid()) {
			return;
		}

		entity.remove();
	}
}