package tk.bridgersilk.lesslag.performance.explosion;

public enum QueuedExplosionType {

	TNT("tnt"),
	CREEPER("creeper"),
	END_CRYSTAL("end_crystal"),
	BED("bed"),
	RESPAWN_ANCHOR("respawn_anchor"),
	GHAST_FIREBALL("ghast_fireball"),
	WITHER_SKULL("wither_skull");

	private final String configName;

	QueuedExplosionType(String configName) {
		this.configName = configName;
	}

	public String getConfigName() {
		return configName;
	}
}