package tk.bridgersilk.lesslag.item;

import java.util.HashMap;
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
	private FileConfiguration config;

    private BukkitTask stackTask;

	private boolean autoClearEnabled;
	private int autoClearInterval;
	private Set<Material> whitelist;

	private boolean stackingEnabled;
	private double stackRadius;
	private String hologramFormat;
	private String pickupBehavior;

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
		autoClearEnabled = config.getBoolean("item_management.auto_clear_drops.enabled", true);
		autoClearInterval = config.getInt("item_management.auto_clear_drops.interval_seconds", 60);
		whitelist = config.getStringList("item_management.auto_clear_drops.whitelist")
				.stream()
				.map(name -> {
					try {
						return Material.valueOf(name.toUpperCase());
					} catch (IllegalArgumentException e) {
						plugin.getLogger().warning("Invalid whitelist item: " + name);
						return null;
					}
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());

		stackingEnabled = config.getBoolean("item_management.item_stacking.enabled", true);
		stackRadius = config.getDouble("item_management.item_stacking.stack_radius", 3.0);
		hologramFormat = ChatColor.translateAlternateColorCodes('&',
				config.getString("item_management.item_stacking.hologram_format", "&e{item}&f x{amount}"));
		pickupBehavior = config.getString("item_management.item_stacking.pickup_behavior", "partial").toLowerCase();
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
                        item.remove();
                    }
                }
            }
            Bukkit.broadcastMessage(prefix + ChatColor.RED + "All dropped items have been cleared!");
        }, intervalTicks, intervalTicks);

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Bukkit.broadcastMessage(prefix + ChatColor.YELLOW + "All dropped items will be cleared in 10 seconds!");
        }, warningTicks, intervalTicks);
    }

    private void startStackingTask() {
        if (!stackingEnabled) return;

        // Runs every 20 ticks (1s). Only item entities are queried
        // (getEntitiesByClass) instead of scanning every entity in the
        // world, to keep this feature's own overhead low on drop-heavy
        // servers.
        stackTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Item item : world.getEntitiesByClass(Item.class)) {
                    if (item.isDead()) continue;

                    if (!stackedAmounts.containsKey(item.getUniqueId())) {
                        Integer persisted = item.getPersistentDataContainer()
                                .get(AMOUNT_KEY, PersistentDataType.INTEGER);

                        if (persisted != null) {
                            // Recovered after a restart/reload: the entity is
                            // already tagged and its ItemStack already holds a
                            // single item, so just restore the real count.
                            stackedAmounts.put(item.getUniqueId(), persisted);
                            updateHologram(item);
                        } else {
                            int totalAmount = item.getItemStack().getAmount();

                            ItemStack newStack = item.getItemStack().clone();
                            newStack.setAmount(1);
                            newStack = tagStackedItem(newStack);

                            item.setItemStack(newStack);

                            setStackedAmount(item, totalAmount);
                            updateHologram(item);
                        }
                    }

                    stackNearbyItems(item);
                }
            }
        }, 0L, 20L);
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

    private ItemStack tagStackedItem(ItemStack stack) {
        ItemStack tagged = stack.clone();
        ItemMeta meta = tagged.getItemMeta();

        meta.getPersistentDataContainer().set(STACK_KEY, PersistentDataType.STRING, "disable_stack");

        tagged.setItemMeta(meta);
        return tagged;
    }

	public void stackNearbyItems(Item baseItem) {
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

        for (Item other : toStack) {
            int otherAmount = stackedAmounts.getOrDefault(other.getUniqueId(), other.getItemStack().getAmount());
            baseAmount += otherAmount;

            clearStackedAmount(other);
            other.remove();
        }

        ItemStack newStack = baseStack.clone();
        newStack.setAmount(1);
        newStack = tagStackedItem(newStack);

        baseItem.setItemStack(newStack);
        setStackedAmount(baseItem, baseAmount);
        updateHologram(baseItem);
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
		item.setCustomName(displayName);
		item.setCustomNameVisible(true);
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
                clearStackedAmount(item);
                item.remove();
            } else {
                setStackedAmount(item, totalAmount - pickedUp);
                updateHologram(item);
            }
        } else if (pickupBehavior.equals("full")) {
            ItemStack testStack = createItemWithAmount(cleanedItem, totalAmount);
            HashMap<Integer, ItemStack> simulatedLeftovers = simulateAddItem(inv, testStack);

            if (simulatedLeftovers.isEmpty()) {
                inv.addItem(testStack);
                clearStackedAmount(item);
                item.remove();
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
                clearStackedAmount(item);
                item.remove();
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
                clearStackedAmount(item);
                item.remove();
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
