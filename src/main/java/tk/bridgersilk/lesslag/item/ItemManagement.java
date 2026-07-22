package tk.bridgersilk.lesslag.item;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.ChatColor;

public class ItemManagement implements Listener {

	private final Plugin plugin;
	private BukkitTask autoClearTask;
	private BukkitTask autoClearWarnTask;
	private FileConfiguration config;

    private BukkitTask stackTask;

	private boolean autoClearEnabled;
	private int autoClearInterval;
	private Set<Material> whitelist;

	private boolean stackingEnabled;
	private double stackRadius;
	private String hologramFormat;
	private String pickupBehavior;

	// Per-item despawn countdown (replaces clearing every drop at once).
	private boolean dropTimerEnabled;
	private int despawnTicks;
	private boolean showTimerInHologram;
	private int countdownSeconds;
	private String countdownFormat;
	private Set<Material> dropTimerWhitelist;

	// Hologram throttling for drop-heavy situations (mining, farms).
	private boolean hologramEnabled;
	private int hologramThrottleAbove;
	private int hologramHideAbove;
	private int throttledRefreshSeconds;

	// Increments once per maintenance pass (~1s); used for throttled refresh.
	private long passCount = 0;

	private enum HologramMode { FULL, THROTTLED, HIDDEN }

	private final Map<UUID, Integer> stackedAmounts = new HashMap<>();

	public ItemManagement(Plugin plugin) {
		this.plugin = plugin;
		this.config = plugin.getConfig();
		loadConfigValues();

		Bukkit.getPluginManager().registerEvents(this, plugin);
		startAutoClearTask();
        startStackingTask();
	}

	private void loadConfigValues() {
		autoClearEnabled = config.getBoolean("item_management.auto_clear_drops.enabled", false);
		autoClearInterval = config.getInt("item_management.auto_clear_drops.interval_seconds", 60);
		whitelist = parseMaterials(config.getStringList("item_management.auto_clear_drops.whitelist"));

		stackingEnabled = config.getBoolean("item_management.item_stacking.enabled", true);
		stackRadius = config.getDouble("item_management.item_stacking.stack_radius", 3.0);
		hologramFormat = ChatColor.translateAlternateColorCodes('&',
				config.getString("item_management.item_stacking.hologram_format", "&e{item_name}&f x{amount}"));
		pickupBehavior = config.getString("item_management.item_stacking.pickup_behavior", "partial").toLowerCase();

		dropTimerEnabled = config.getBoolean("item_management.drop_timer.enabled", true);
		despawnTicks = Math.max(1, config.getInt("item_management.drop_timer.despawn_seconds", 30)) * 20;
		showTimerInHologram = config.getBoolean("item_management.drop_timer.show_in_hologram", true);
		countdownSeconds = Math.max(0, config.getInt("item_management.drop_timer.countdown_seconds", 10));
		// Bold + red by default so the final-seconds countdown stands out from
		// the item name instead of blending in. {seconds} = live number.
		countdownFormat = ChatColor.translateAlternateColorCodes('&',
				config.getString("item_management.drop_timer.countdown_format", "&c&l[{seconds}s]"));
		dropTimerWhitelist = parseMaterials(config.getStringList("item_management.drop_timer.whitelist"));

		hologramEnabled = config.getBoolean("item_management.hologram.enabled", true);
		hologramThrottleAbove = config.getInt("item_management.hologram.throttle_above", 150);
		hologramHideAbove = config.getInt("item_management.hologram.hide_above", 400);
		throttledRefreshSeconds = Math.max(1, config.getInt("item_management.hologram.throttled_refresh_seconds", 5));
	}

	private Set<Material> parseMaterials(List<String> names) {
		return names.stream()
				.map(name -> {
					try {
						return Material.valueOf(name.toUpperCase());
					} catch (IllegalArgumentException e) {
						plugin.getLogger().warning("Invalid item name in config: " + name);
						return null;
					}
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
	}

	public void reload() {
		this.config = plugin.getConfig();
		loadConfigValues();

		stopAutoClearTask();
		startAutoClearTask();
        stopStackingTask();
        // Clear only the in-memory map; the amounts persisted on the item
        // entities survive and are recovered when the task restarts below.
        stackedAmounts.clear();
        startStackingTask();
	}

	private void startAutoClearTask() {
        if (!autoClearEnabled) return;

        long intervalTicks = autoClearInterval * 20L;
        long warningTicks = intervalTicks - (10 * 20L);

        String prefix = plugin.getConfig().getString("settings.prefix");

        autoClearTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (entity instanceof Item item && !whitelist.contains(item.getItemStack().getType())) {
                        despawnItem(item);
                    }
                }
            }
            Bukkit.broadcastMessage(prefix + ChatColor.RED + "All dropped items have been cleared!");
        }, intervalTicks, intervalTicks);

        autoClearWarnTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Bukkit.broadcastMessage(prefix + ChatColor.YELLOW + "All dropped items will be cleared in 10 seconds!");
        }, warningTicks, intervalTicks);
    }

    private void startStackingTask() {
        // The single per-second item maintenance pass drives stacking, the
        // drop-timer countdown/despawn, hologram refresh, and ghost cleanup.
        // Start it if any of those features are active.
        if (!stackingEnabled && !dropTimerEnabled && !hologramEnabled) return;

        stackTask = Bukkit.getScheduler().runTaskTimer(plugin, this::itemMaintenanceTick, 0L, 20L);
    }

    // Runs every 20 ticks (1s). Only item entities are queried
    // (getEntitiesByClass) instead of scanning every entity in the world.
    private void itemMaintenanceTick() {
        passCount++;

        // Collect the UUIDs of the live items we actually see this pass so we
        // can prune stale/ghost entries from the tracking map afterwards.
        Set<UUID> seen = stackedAmounts.isEmpty() ? null : new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            var items = world.getEntitiesByClass(Item.class);
            HologramMode mode = hologramModeFor(items.size());

            for (Item item : items) {
                if (item.isDead()) continue;

                // Drop timer: remove the item once its own countdown expires,
                // instead of wiping every drop at once on a fixed interval.
                if (dropTimerEnabled && !dropTimerWhitelist.contains(item.getItemStack().getType())
                        && item.getTicksLived() >= despawnTicks) {
                    despawnItem(item);
                    continue;
                }

                if (stackingEnabled) {
                    if (!stackedAmounts.containsKey(item.getUniqueId())) {
                        adoptItem(item, mode);
                    }
                    stackNearbyItems(item, mode);
                }

                if (seen != null) seen.add(item.getUniqueId());

                refreshHologram(item, mode);
            }
        }

        // Ghost cleanup: drop map entries for items we no longer see (picked
        // up, despawned, killed by another plugin, or in an unloaded chunk).
        // This is safe because the real count lives on the entity's PDC and is
        // re-adopted if the item ever comes back.
        if (seen != null) {
            stackedAmounts.keySet().retainAll(seen);
        }
    }

    // First time we see an item: recover its persisted count after a
    // restart/reload, or tag it and record its amount if it is brand new.
    private void adoptItem(Item item, HologramMode mode) {
        Integer persisted = item.getPersistentDataContainer()
                .get(AMOUNT_KEY, PersistentDataType.INTEGER);

        if (persisted != null) {
            // Recovered after a restart/reload: the entity is already tagged
            // and its ItemStack already holds a single item, so just restore
            // the real count.
            stackedAmounts.put(item.getUniqueId(), persisted);
        } else {
            int totalAmount = item.getItemStack().getAmount();

            ItemStack newStack = item.getItemStack().clone();
            newStack.setAmount(1);
            newStack = tagStackedItem(newStack);

            item.setItemStack(newStack);
            setStackedAmount(item, totalAmount);
        }

        if (mode != HologramMode.HIDDEN) {
            updateHologram(item);
        }
    }

    private HologramMode hologramModeFor(int itemCount) {
        if (!hologramEnabled || itemCount > hologramHideAbove) return HologramMode.HIDDEN;
        if (itemCount > hologramThrottleAbove) return HologramMode.THROTTLED;
        return HologramMode.FULL;
    }

    // Refreshes an item's hologram according to the current load mode. In
    // HIDDEN mode holograms are turned off; in THROTTLED mode they refresh
    // only every few seconds so drop-heavy scenes don't spam entity-metadata
    // packets every tick.
    private void refreshHologram(Item item, HologramMode mode) {
        if (mode == HologramMode.HIDDEN) {
            if (item.isCustomNameVisible()) {
                item.setCustomName(null);
                item.setCustomNameVisible(false);
            }
            return;
        }

        boolean timerActive = dropTimerEnabled && showTimerInHologram
                && !dropTimerWhitelist.contains(item.getItemStack().getType());

        // The only thing that changes every second is the live countdown, and
        // we only run that during the final countdownSeconds before despawn.
        // Outside that window the hologram is static (already set on
        // adopt/merge/pickup), so we skip the per-second repaint entirely —
        // that's the bulk of the packet savings while mining or at a farm.
        if (!timerActive || secondsLeft(item) > countdownSeconds) return;

        if (mode == HologramMode.THROTTLED && (passCount % throttledRefreshSeconds) != 0) return;

        updateHologram(item);
    }

    private void stopStackingTask() {
        if (stackTask != null) {
            stackTask.cancel();
            stackTask = null;
        }
    }

	private void stopAutoClearTask() {
		if (autoClearTask != null) {
			autoClearTask.cancel();
			autoClearTask = null;
		}
		if (autoClearWarnTask != null) {
			autoClearWarnTask.cancel();
			autoClearWarnTask = null;
		}
	}

	public void disable() {
		stopAutoClearTask();
		stopStackingTask();
		stackedAmounts.clear();
	}

    private final NamespacedKey STACK_KEY = new NamespacedKey("lesslag", "stack_id");

    // Persisted on the item ENTITY (not the ItemStack). Item entities are
    // saved to disk with their chunk, so mirroring the stacked amount here
    // lets us recover it after a restart/reload instead of losing the count
    // that otherwise lived only in the in-memory stackedAmounts map.
    private final NamespacedKey AMOUNT_KEY = new NamespacedKey("lesslag", "stack_amount");

    // Records a stacked amount in both the in-memory map and the item
    // entity's persistent storage so the two never drift apart.
    private void setStackedAmount(Item item, int amount) {
        stackedAmounts.put(item.getUniqueId(), amount);
        item.getPersistentDataContainer().set(AMOUNT_KEY, PersistentDataType.INTEGER, amount);
    }

    // Clears a stacked amount from the map and the item entity's storage.
    private void clearStackedAmount(Item item) {
        stackedAmounts.remove(item.getUniqueId());
        item.getPersistentDataContainer().remove(AMOUNT_KEY);
    }

    // Removes an item cleanly: drops it from tracking and clears its hologram
    // first so no lingering "ghost" name is left behind on the client.
    private void despawnItem(Item item) {
        clearStackedAmount(item);
        item.setCustomName(null);
        item.setCustomNameVisible(false);
        item.remove();
    }

    private ItemStack tagStackedItem(ItemStack stack) {
        ItemStack tagged = stack.clone();
        ItemMeta meta = tagged.getItemMeta();

        meta.getPersistentDataContainer().set(STACK_KEY, PersistentDataType.STRING, "disable_stack");

        tagged.setItemMeta(meta);
        return tagged;
    }

	private void stackNearbyItems(Item baseItem, HologramMode mode) {
        if (!stackingEnabled || baseItem.isDead()) return;

        ItemStack baseStack = baseItem.getItemStack();
        int baseAmount = stackedAmounts.getOrDefault(baseItem.getUniqueId(), baseStack.getAmount());

        List<Item> toStack = baseItem.getNearbyEntities(stackRadius, stackRadius, stackRadius).stream()
                .filter(e -> e instanceof Item)
                .map(e -> (Item) e)
                .filter(i -> !i.equals(baseItem))
                .filter(i -> canStack(baseStack, i.getItemStack()))
                .collect(Collectors.toList());

        if (toStack.isEmpty()) return;

        // Track the youngest age in the merged set so the combined stack
        // adopts the freshest despawn timer (see setTicksLived below).
        int minAge = baseItem.getTicksLived();

        for (Item other : toStack) {
            int otherAmount = stackedAmounts.getOrDefault(other.getUniqueId(), other.getItemStack().getAmount());
            baseAmount += otherAmount;
            minAge = Math.min(minAge, other.getTicksLived());

            clearStackedAmount(other);
            other.remove();
        }

        ItemStack newStack = baseStack.clone();
        newStack.setAmount(1);
        newStack = tagStackedItem(newStack);

        baseItem.setItemStack(newStack);
        setStackedAmount(baseItem, baseAmount);

        // Refresh the drop timer to the freshest item so newly dropped items
        // aren't instantly cut short by an older pile's countdown. setTicksLived
        // requires >= 1. An idle pile (no new drops) still expires normally.
        if (dropTimerEnabled) {
            baseItem.setTicksLived(Math.max(1, minAge));
        }

        if (mode != HologramMode.HIDDEN) {
            updateHologram(baseItem);
        }
    }

	private boolean canStack(ItemStack a, ItemStack b) {
        if (a.getType() != b.getType()) return false;

        ItemMeta metaA = a.getItemMeta();
        ItemMeta metaB = b.getItemMeta();

        if (metaA == null || metaB == null) return false;

        String tagA = metaA.getPersistentDataContainer().get(STACK_KEY, PersistentDataType.STRING);
        String tagB = metaB.getPersistentDataContainer().get(STACK_KEY, PersistentDataType.STRING);

        if (Objects.equals(tagA, "disable_stack") && Objects.equals(tagB, "disable_stack")) {
            ItemMeta cloneA = Bukkit.getItemFactory().getItemMeta(a.getType());
            ItemMeta cloneB = Bukkit.getItemFactory().getItemMeta(b.getType());

            if (cloneA != null) cloneA = metaA.clone();
            if (cloneB != null) cloneB = metaB.clone();

            if (cloneA != null) {
                cloneA.getPersistentDataContainer().remove(STACK_KEY);
            }
            if (cloneB != null) {
                cloneB.getPersistentDataContainer().remove(STACK_KEY);
            }

            return Objects.equals(cloneA, cloneB);
        }

        return Objects.equals(metaA, metaB);
    }

    private String itemName;

	private void updateHologram(Item item) {
		if (!hologramEnabled) {
			if (item.isCustomNameVisible()) {
				item.setCustomName(null);
				item.setCustomNameVisible(false);
			}
			return;
		}

		int amount = stackedAmounts.getOrDefault(item.getUniqueId(), item.getItemStack().getAmount());
        if (item.getItemStack().getItemMeta().hasDisplayName()) {
            this.itemName = item.getItemStack().getItemMeta().getDisplayName();
        } else {
            this.itemName = formatItemName(item.getItemStack().getType());
        }
		String displayName = hologramFormat
				.replace("{item_type}", formatItemName(item.getItemStack().getType()))
				.replace("{amount}", String.valueOf(amount))
				.replace("{item_name}", this.itemName);

		// Append the live countdown (e.g. "Dirt x64 §7(10s)") only in the
		// final stretch before despawn (countdownSeconds); before that the
		// hologram stays static so we're not repainting every item's name
		// every second. Set countdown_seconds >= despawn_seconds to always
		// show it.
		if (dropTimerEnabled && showTimerInHologram
				&& !dropTimerWhitelist.contains(item.getItemStack().getType())) {
			int secs = secondsLeft(item);
			if (secs <= countdownSeconds) {
				// Reset any trailing colour from the item name so the bold-red
				// countdown always renders in its own configured style.
				displayName += " " + ChatColor.RESET
						+ countdownFormat.replace("{seconds}", String.valueOf(secs));
			}
		}

		item.setCustomName(displayName);
		item.setCustomNameVisible(true);
	}

	// Whole seconds remaining before this item despawns (rounded up, >= 0).
	private int secondsLeft(Item item) {
		int remaining = despawnTicks - item.getTicksLived();
		if (remaining < 0) remaining = 0;
		return (remaining + 19) / 20;
	}

	private String formatItemName(Material material) {
		String name = material.name().toLowerCase().replace("_", " ");
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

    private HashMap<Integer, ItemStack> simulateAddItem(Inventory inv, ItemStack stack) {
        ItemStack[] contents = inv.getContents().clone();
        HashMap<Integer, ItemStack> leftovers = new HashMap<>();
        ItemStack clone = stack.clone();

        for (int i = 0; i < contents.length && clone.getAmount() > 0; i++) {
            ItemStack current = contents[i];

            if (current == null || current.getType() == Material.AIR) {
                int max = clone.getMaxStackSize();
                if (clone.getAmount() <= max) {
                    clone.setAmount(0);
                } else {
                    clone.setAmount(clone.getAmount() - max);
                }
            } else if (current.isSimilar(clone)) {
                int space = current.getMaxStackSize() - current.getAmount();
                if (space > 0) {
                    if (clone.getAmount() <= space) {
                        clone.setAmount(0);
                    } else {
                        clone.setAmount(clone.getAmount() - space);
                    }
                }
            }
        }

        if (clone.getAmount() > 0) {
            leftovers.put(0, clone);
        }
        return leftovers;
    }

    @EventHandler
    public void onInventoryItemPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        UUID id = item.getUniqueId();
        Inventory inv = event.getInventory();

        if (!stackedAmounts.containsKey(id)) return;

        int totalAmount = stackedAmounts.get(id);

        ItemStack cleanedItem = item.getItemStack().clone();
        ItemMeta meta = cleanedItem.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(STACK_KEY);
            cleanedItem.setItemMeta(meta);
        }

        if (pickupBehavior.equals("partial")) {
            HashMap<Integer, ItemStack> leftovers = inv.addItem(createItemWithAmount(cleanedItem, totalAmount));
            int pickedUp = totalAmount - leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();

            if (pickedUp >= totalAmount) {
                despawnItem(item);
            } else {
                setStackedAmount(item, totalAmount - pickedUp);
                updateHologram(item);
            }
        } else if (pickupBehavior.equals("full")) {
            ItemStack testStack = createItemWithAmount(cleanedItem, totalAmount);
            HashMap<Integer, ItemStack> simulatedLeftovers = simulateAddItem(inv, testStack);

            if (simulatedLeftovers.isEmpty()) {
                inv.addItem(testStack);
                despawnItem(item);
            } else {
                event.setCancelled(true);
            }
        }

        event.setCancelled(true);
    }

	@EventHandler
    public void onItemPickup(EntityPickupItemEvent event) { // fix: non player entities not handled yet (zombies, villagers, etc)
        if (!(event.getEntity() instanceof Player player)) return;

        Item item = event.getItem();
        UUID id = item.getUniqueId();

        if (!stackedAmounts.containsKey(id)) return;

        int totalAmount = stackedAmounts.get(id);

        ItemStack cleanedItem = item.getItemStack().clone();
        ItemMeta meta = cleanedItem.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(STACK_KEY);
            cleanedItem.setItemMeta(meta);
        }

        if (pickupBehavior.equals("partial")) {
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(createItemWithAmount(cleanedItem, totalAmount));
            int pickedUp = totalAmount - leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();

            if (pickedUp > 0) {
                // The vanilla pickup event is cancelled below, which
                // suppresses the normal "pop" sound. Replay it manually
                // so stacked pickups aren't silent.
                playPickupSound(player);
            }

            if (pickedUp >= totalAmount) {
                despawnItem(item);
            } else {
                setStackedAmount(item, totalAmount - pickedUp);
                updateHologram(item);
            }
        } else if (pickupBehavior.equals("full")) {
            ItemStack testStack = createItemWithAmount(cleanedItem, totalAmount);
            HashMap<Integer, ItemStack> simulatedLeftovers = simulateAddItem(player.getInventory(), testStack);

            if (simulatedLeftovers.isEmpty()) {
                player.getInventory().addItem(testStack);
                playPickupSound(player);
                despawnItem(item);
            } else {
                event.setCancelled(true);
            }
        }

        event.setCancelled(true);
    }

    // Reproduces the vanilla item-pickup sound. Needed because stacked
    // pickups cancel the original event (which normally plays this).
    private void playPickupSound(Player player) {
        float pitch = (float) ((Math.random() - Math.random()) * 0.7 + 2.0);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f, pitch);
    }

	private ItemStack createItemWithAmount(ItemStack itemStack, int amount) {
        ItemStack stack = itemStack.clone();
        stack.setAmount(amount);
        return stack;
    }

}
