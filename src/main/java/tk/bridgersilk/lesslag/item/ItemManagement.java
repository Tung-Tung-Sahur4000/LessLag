package tk.bridgersilk.lesslag.item;

import java.util.ArrayList;
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
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
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
	// Grid cell size (blocks) used to bucket items for merging, derived from
	// stackRadius. Items in the same cell merge in a single O(N) pass instead
	// of a per-item getNearbyEntities() spatial query (which was O(N^2)).
	private int cellSize;
	private String hologramFormat;

	// When true, every stacked-item pickup logs what it did (item, tracked
	// amount, how much was picked up, how much was left on the ground). Off by
	// default; turn it on to diagnose "it won't pick up" reports on a live
	// server -- the log line shows whether the inventory genuinely had no room
	// or something else is wrong.
	private boolean debugPickup;

	// Water / bubble-column optimisation. Items caught in a bubble column
	// (soul sand / magma) or flowing water get shot up and fall repeatedly, so
	// they oscillate across many blocks and often sit just outside the normal
	// vertical stack window -- leaving lots of separate, constantly-moving item
	// entities (heavy move-packet + physics churn). When a world holds more than
	// waterMinItems suspended-in-water items, the stacking search for those
	// items reaches waterVerticalRadius blocks up/down (instead of stackRadius)
	// so a whole column collapses into one stacked entity. It only ever merges
	// (amounts preserved), never deletes.
	private boolean waterStackingEnabled;
	private double waterVerticalRadius;
	private int waterMinItems;

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
		cellSize = Math.max(1, (int) Math.round(stackRadius));
		hologramFormat = ChatColor.translateAlternateColorCodes('&',
				config.getString("item_management.item_stacking.hologram_format", "&e{item_name}&f x{amount}"));
		debugPickup = config.getBoolean("item_management.item_stacking.debug", false);

		waterStackingEnabled = config.getBoolean("item_management.water_stacking.enabled", true);
		waterVerticalRadius = config.getDouble("item_management.water_stacking.vertical_radius", 12.0);
		waterMinItems = config.getInt("item_management.water_stacking.min_items", 4);

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
    //
    // Merging is grid-based: every item is bucketed once into a spatial cell
    // (a single O(N) pass), then items are merged WITHIN each cell. The old
    // approach called getNearbyEntities() for every item every second, which on
    // a big drop (a TNT blast, a mob-farm dump) is O(N^2) -- each of N items
    // rescans all N items in its chunk -- so the anti-lag merge became the lag
    // spike. Grid bucketing does the same collapsing in O(N) with no
    // per-item spatial query.
    private void itemMaintenanceTick() {
        passCount++;

        // Collect the UUIDs of the live items we actually see this pass so we
        // can prune stale/ghost entries from the tracking map afterwards.
        Set<UUID> seen = stackedAmounts.isEmpty() ? null : new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            var items = world.getEntitiesByClass(Item.class);
            HologramMode mode = hologramModeFor(items.size());

            // Grid of same-locality items, and the flat list of survivors (used
            // for hologram refresh and the optional water-column pass).
            Map<Long, List<Item>> grid = stackingEnabled ? new HashMap<>() : null;
            List<Item> live = new ArrayList<>();

            // Single pass: drop-timer despawn, adopt new items, bucket the rest.
            for (Item item : items) {
                if (item.isDead()) continue;

                // Drop timer: remove the item once its own countdown expires,
                // instead of wiping every drop at once on a fixed interval.
                if (dropTimerEnabled && !dropTimerWhitelist.contains(item.getItemStack().getType())
                        && item.getTicksLived() >= despawnTicks) {
                    despawnItem(item);
                    continue;
                }

                if (stackingEnabled && !stackedAmounts.containsKey(item.getUniqueId())) {
                    adoptItem(item, mode);
                }

                if (seen != null) seen.add(item.getUniqueId());
                live.add(item);

                if (grid != null) {
                    var loc = item.getLocation();
                    long key = cellKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                            cellSize, cellSize);
                    grid.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
                }
            }

            // Merge each cell's group (cheap: items are already grouped by
            // locality, so this is O(N) overall, not O(N^2)).
            if (grid != null) {
                for (List<Item> cell : grid.values()) {
                    if (cell.size() > 1) mergeGroup(cell, mode);
                }

                // Water/bubble-column columns: items shot up and down a bubble
                // column land in different vertical cells, so normal cell
                // merging can't collapse the whole elevator. Re-merge the
                // SURVIVING water items by column. This runs only on survivors,
                // so its cost scales with entities left after normal merging --
                // not with the raw drop size -- and it never touches the
                // O(N^2) path.
                if (waterStackingEnabled) {
                    mergeWaterColumns(live, mode);
                }
            }

            // Hologram refresh on survivors (merged-away items are now dead).
            for (Item item : live) {
                if (!item.isDead()) refreshHologram(item, mode);
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

    // Packs a cell coordinate into a single long map key: 26 bits X, 26 bits Z,
    // 12 bits Y (Y range is tiny). Exact within the world border, so distinct
    // cells never collide -- and even if they did, the per-kind bucketing in
    // mergeGroup still only ever combines items whose normalised ItemStacks are
    // equal, so a collision could never merge genuinely different items.
    private static long cellKey(int bx, int by, int bz, int horiz, int vert) {
        long cx = Math.floorDiv(bx, horiz);
        long cy = Math.floorDiv(by, vert);
        long cz = Math.floorDiv(bz, horiz);
        return ((cx & 0x3FFFFFFL) << 38) | ((cz & 0x3FFFFFFL) << 12) | (cy & 0xFFFL);
    }

    // Merges every mergeable item in a group into one surviving entity, summing
    // the stacked amounts. A group is a grid cell (or a water column) and may
    // hold many different item kinds. We bucket by the normalised ItemStack
    // (a single item, so amount can't split a kind) in ONE O(m) pass, then
    // collapse each kind linearly. Equal normalised stacks are exactly the
    // items that stack, so this needs no O(m^2) pairwise scan -- which is what
    // made a pile of many DISTINCT items (e.g. thousands of renamed items
    // dumped in one spot) an O(m^2) tick spike. Only ever merges (amounts
    // preserved) -- never deletes contents.
    private void mergeGroup(List<Item> group, HologramMode mode) {
        Map<ItemStack, List<Item>> byKind = new HashMap<>();
        for (Item item : group) {
            if (item.isDead()) continue;
            ItemStack key = item.getItemStack().clone();
            key.setAmount(1);
            byKind.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        for (List<Item> kind : byKind.values()) {
            if (kind.size() < 2) continue;

            Item base = kind.get(0);
            // Sum in a long so a runaway farm/stasis pile can't wrap the int.
            long total = stackedAmounts.getOrDefault(base.getUniqueId(), base.getItemStack().getAmount());
            // Track the youngest age in the merged set so the combined stack
            // adopts the freshest despawn timer (see setTicksLived below).
            int minAge = base.getTicksLived();

            for (int i = 1; i < kind.size(); i++) {
                Item other = kind.get(i);
                total += stackedAmounts.getOrDefault(other.getUniqueId(), other.getItemStack().getAmount());
                minAge = Math.min(minAge, other.getTicksLived());

                clearStackedAmount(other);
                other.remove();
            }

            // Clamp to the int range: the amount is stored as an int on the
            // entity PDC and in the map, so an un-clamped sum past 2.1B would
            // wrap negative and corrupt the count / hologram / pickup.
            int amount = (int) Math.min(total, Integer.MAX_VALUE);

            ItemStack newStack = base.getItemStack().clone();
            newStack.setAmount(1);
            newStack = tagStackedItem(newStack);

            base.setItemStack(newStack);
            setStackedAmount(base, amount);

            // Refresh the drop timer to the freshest item so newly dropped items
            // aren't instantly cut short by an older pile's countdown.
            // setTicksLived requires >= 1. An idle pile still expires normally.
            if (dropTimerEnabled) {
                base.setTicksLived(Math.max(1, minAge));
            }

            if (mode != HologramMode.HIDDEN) {
                updateHologram(base);
            }
        }
    }

    // Second, water-only merge pass over the survivors: buckets items that are
    // in water by a TALL cell (tight horizontally at cellSize, up to
    // waterVerticalRadius blocks vertically) so a whole bubble-column elevator
    // collapses into one entity. Skipped unless more than waterMinItems water
    // item entities remain, so a couple of casually-floating drops keep vanilla
    // behaviour. Cost scales with surviving entities, not with the raw drop.
    private void mergeWaterColumns(List<Item> live, HologramMode mode) {
        int vert = Math.max(1, (int) Math.ceil(waterVerticalRadius));

        Map<Long, List<Item>> columns = null;
        int waterCount = 0;

        for (Item item : live) {
            if (item.isDead() || !isSuspendedInWater(item)) continue;
            waterCount++;
            var loc = item.getLocation();
            long key = cellKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), cellSize, vert);
            if (columns == null) columns = new HashMap<>();
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        if (columns == null || waterCount <= waterMinItems) return;

        for (List<Item> column : columns.values()) {
            if (column.size() > 1) mergeGroup(column, mode);
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

	// True when the item is sitting in water: a full water block, a bubble
	// column (soul sand pushes items up / magma pulls them down -- the source of
	// the constant bobbing), or any waterlogged block (slabs, stairs, fences).
	// These are exactly the drops that oscillate and resist normal stacking.
	private boolean isSuspendedInWater(Item item) {
		Block block = item.getLocation().getBlock();
		Material type = block.getType();
		if (type == Material.WATER || type == Material.BUBBLE_COLUMN) return true;
		BlockData data = block.getBlockData();
		return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
	}

	private String formatItemName(Material material) {
		String name = material.name().toLowerCase().replace("_", " ");
		return Character.toUpperCase(name.charAt(0)) + name.substring(1);
	}

    // Resolves the true stacked amount for a ground item. Prefers the in-memory
    // tracking map, but falls back to the amount persisted on the item ENTITY
    // (PDC) when the map doesn't know it -- the brief window before the
    // maintenance task (re)adopts an item into the map: right after a reload,
    // when its chunk has just (re)loaded, or after the per-second ghost-cleanup
    // pruned it. A stacked item's VISIBLE stack was forced to 1 when it was
    // stacked, so without this fallback such an item would look untracked and
    // vanilla would pick up a single item, silently dropping the rest of the
    // pile. Re-adopts into the map so holograms/counts stay consistent. Returns
    // -1 for a genuinely unstacked item (no map entry, no PDC) -- vanilla should
    // handle that one normally.
    private int resolveStackedAmount(Item item) {
        Integer mapped = stackedAmounts.get(item.getUniqueId());
        if (mapped != null) return mapped;

        Integer persisted = item.getPersistentDataContainer()
                .get(AMOUNT_KEY, PersistentDataType.INTEGER);
        if (persisted != null) {
            stackedAmounts.put(item.getUniqueId(), persisted);
            return persisted;
        }
        return -1;
    }

    // Produces a clean, vanilla-stacking copy of a tagged ground item to hand
    // to a player/inventory. We tag every stacked ground item with STACK_KEY (to
    // stop vanilla from auto-merging the item ENTITIES and corrupting our own
    // amount tracking) and strip it here on pickup. The catch: on Minecraft
    // 1.20.5+ (the data-component era) removing our last PDC key can leave an
    // empty `custom_data` component behind, and such a stack fails isSimilar()
    // against a genuinely vanilla stack of the same item. With an EMPTY slot
    // that doesn't matter (addItem just uses the free slot), but in a FULL
    // inventory addItem must top off a matching stack -- which needs
    // isSimilar() -- so the pickup silently does nothing ("full inventory
    // skips", while the same item picks up fine when a slot is free). Fix: when
    // nothing but empty metadata is left after untagging, hand over a pristine
    // vanilla stack so it stacks normally. Items that still carry real meta
    // (names/enchants/potion data/...) are returned untouched -- and those are
    // unstackable anyway, so they always need a free slot regardless.
    private ItemStack cleanForPickup(ItemStack tagged) {
        ItemStack cleaned = tagged.clone();

        ItemMeta meta = cleaned.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(STACK_KEY);
            cleaned.setItemMeta(meta);
        }

        // Already indistinguishable from a vanilla stack -> nothing to fix.
        ItemStack vanilla = new ItemStack(cleaned.getType());
        if (cleaned.isSimilar(vanilla)) {
            return cleaned;
        }

        // Different from vanilla. If the only difference is leftover empty
        // metadata (the untagged meta equals a fresh default meta for this
        // type), use the pristine stack; otherwise the item has real meta worth
        // preserving, so keep it as-is.
        ItemMeta cleanedMeta = cleaned.getItemMeta();
        ItemMeta freshMeta = Bukkit.getItemFactory().getItemMeta(cleaned.getType());
        if (cleanedMeta == null || Bukkit.getItemFactory().equals(cleanedMeta, freshMeta)) {
            if (debugPickup) {
                plugin.getLogger().info("[pickup] rebuilt " + cleaned.getType()
                        + " to a vanilla stack (stripped empty residual component)");
            }
            return vanilla;
        }
        return cleaned;
    }

    // Picks up a stacked ground item into `inv`, taking as much as physically
    // fits and leaving the rest on the ground. addItem does exactly the vanilla
    // thing: it tops off existing partial stacks of the SAME item first, then
    // fills empty slots, and hands back whatever didn't fit. That gives us the
    // "only what fits" behaviour AND same-item validation for free -- a slot
    // holding a different item is never isSimilar(), so it's never touched, and
    // an item the inventory has no room for is simply left where it is. addItem
    // also splits a requested amount larger than one max stack across slots.
    //
    // Returns how many items were actually picked up (0 if there was no room).
    private int pickUpStacked(Item item, Inventory inv, int totalAmount) {
        ItemStack cleanedItem = cleanForPickup(item.getItemStack());

        // Guard against a corrupt/empty ground stack: addItem silently ignores
        // AIR (reporting no leftovers), which would otherwise despawn the item
        // as if it had all been picked up while the player received nothing.
        if (cleanedItem.getType() == Material.AIR || totalAmount <= 0) {
            if (debugPickup) {
                plugin.getLogger().warning("[pickup] skipped empty/invalid stack: type="
                        + cleanedItem.getType() + " amount=" + totalAmount);
            }
            return 0;
        }

        HashMap<Integer, ItemStack> leftovers = inv.addItem(createItemWithAmount(cleanedItem, totalAmount));
        int notFitted = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
        int pickedUp = totalAmount - notFitted;

        if (debugPickup) {
            plugin.getLogger().info(String.format(
                    "[pickup] %s into %s: tracked=%d pickedUp=%d leftOnGround=%d",
                    cleanedItem.getType(), inv.getType(), totalAmount, pickedUp,
                    totalAmount - pickedUp));
        }

        if (pickedUp >= totalAmount) {
            despawnItem(item);
        } else if (pickedUp > 0) {
            setStackedAmount(item, totalAmount - pickedUp);
            updateHologram(item);
        }
        return pickedUp;
    }

    @EventHandler
    public void onInventoryItemPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        int totalAmount = resolveStackedAmount(item);
        if (totalAmount < 0) return; // not a stacked item -> let vanilla handle it

        pickUpStacked(item, event.getInventory(), totalAmount);
        event.setCancelled(true);
    }

	@EventHandler
    public void onItemPickup(EntityPickupItemEvent event) { // fix: non player entities not handled yet (zombies, villagers, etc)
        if (!(event.getEntity() instanceof Player player)) return;

        Item item = event.getItem();
        int totalAmount = resolveStackedAmount(item);
        if (totalAmount < 0) return; // not a stacked item -> let vanilla handle it

        int pickedUp = pickUpStacked(item, player.getInventory(), totalAmount);
        if (pickedUp > 0) {
            // The vanilla pickup event is cancelled below, which suppresses the
            // normal "pop" sound. Replay it manually so stacked pickups aren't
            // silent.
            playPickupSound(player);
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
