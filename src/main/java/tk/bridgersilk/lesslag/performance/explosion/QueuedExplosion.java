package tk.bridgersilk.lesslag.performance.explosion;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;

public class QueuedExplosion {

	private final UUID worldId;

	private final double x;
	private final double y;
	private final double z;

	private final float power;
	private final boolean setFire;
	private final boolean breakBlocks;

	private final QueuedExplosionType type;
	private final long queuedAt;

	public QueuedExplosion(
		Location location,
		float power,
		boolean setFire,
		boolean breakBlocks,
		QueuedExplosionType type
	) {
		this.worldId = location.getWorld().getUID();

		this.x = location.getX();
		this.y = location.getY();
		this.z = location.getZ();

		this.power = power;
		this.setFire = setFire;
		this.breakBlocks = breakBlocks;

		this.type = type;
		this.queuedAt = System.currentTimeMillis();
	}

	public UUID getWorldId() {
		return worldId;
	}

	public Location createLocation(World world) {
		return new Location(world, x, y, z);
	}

	public float getPower() {
		return power;
	}

	public boolean shouldSetFire() {
		return setFire;
	}

	public boolean shouldBreakBlocks() {
		return breakBlocks;
	}

	public QueuedExplosionType getType() {
		return type;
	}

	public long getQueuedAt() {
		return queuedAt;
	}
}