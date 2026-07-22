## v0.0.9
- fixed: item pickup skipping items that could partly fit. In `"full"` pickup mode a stacked ground item was skipped entirely whenever it couldn't ALL fit, unless the inventory already held a partial stack of the same item — the fallback only topped off existing same-item stacks and never used empty slots. So with, say, a full 64 dirt stack, a partial diamond stack and some free slots, walking over 100+ dirt and 29 diamond on the ground picked up the diamond but left the dirt behind, even though free slots could hold some of it. Pickup now always takes as much as fits — topping off same-item stacks AND filling empty slots — and leaves only the genuine overflow on the ground (with its count decremented). Applies to both player pickups and hopper/container pickups. Different items on the ground are still validated independently: an item with no room is left where it is and never disturbs unrelated stacks.
- removed: the `item_stacking.pickup_behavior` option and the `partial` / `full` code paths behind it. The v0.0.8 attempt at fixing full-inventory pickup added a hand-rolled fit simulation (`simulateAddItem`) and a partials-only top-off (`topOffPartialStacks`) that never touched empty slots — which is what left the `"full"` mode bug above unfixed. With pickup now uniformly "take what fits", both modes did the exact same thing, so the option, both helpers, and the branching were dead weight. Pickup is a single path built on Bukkit's `addItem` (which already does same-item validation, empty-slot filling and over-max-stack splitting). Any leftover `pickup_behavior` key in an existing config is simply ignored.

## v0.0.1
- first release

## v0.0.2
- fixed: teleportation in the same world getting cancelled when worlds are full (feature: world player limit)
- fixed: smart removal entity whitelist not working (due to case mismatch when reading it)
- added: config option for making mobs invulnerable while their AI is disabled due to no players nearby (`mob_ai.disable_ai_when_no_players_nearby.invulnerable`)
- added: `{item_name}` placeholder for the `hologram_format` config option
- changed: the previous `{item}` placeholder for the `hologram_format` config option to `{item_type}`

## v0.0.3
- added: bStats

## v0.0.4
- added: tick speed performance control (decrease tick speed in all worlds if tps are low)

## v0.0.5
- fixed: inventory item pickup

## v0.0.6
- added: Explosion Queue System
- added: (WIP) Web interface with login system for a web based performance data viewer (will be released in a future update)

## v0.0.8
- fixed: item pickup with a full inventory. In `"full"` pickup mode a stacked ground item (e.g. "Cobblestone x2000") was rejected outright whenever it couldn't ALL fit — so a full inventory holding a partial stack of the same item (say 30/64) couldn't top that stack off, even though vanilla would. It now falls back to filling existing partial stacks of the same item (never occupying an empty slot), leaving the remainder on the ground with its count decremented. Applies to both player pickups and hopper/container pickups.
- fixed: "does it all fit" was measured against a player's full inventory including armour and offhand slots, which `addItem` never fills — an empty helmet slot could look like free space. The fit check now uses only the storage slots that pickups actually go into.

## v0.0.7
- added: villager optimizer (`villager_optimization`) — targets the classic lag build (a packed breeder / trading hall with 10+ villagers all colliding and running AI). Three independent, individually-tunable levers, all driven by one timer pass (cost does not grow with villager count): (1) disable entity collision in chunks over a per-chunk threshold — the dominant O(n²) push cost — while breeding/trading continue; (2) throttle villager AI via a phase-staggered duty cycle when no player is within a radius (villagers still idle-wander instead of hard-freezing, so it doesn't look broken); (3) a per-chunk breeding soft-cap. Adds `/lesslag villagers` for live stats (counts, throttle state, per-scan cost).
- added: global breedable population caps (`entity_management.breedable_global_limits`) — server-wide per-type caps for farm/breedable mobs. Above a type's limit, further breeding and egg-hatching of that type is paused across the server until the population drops; existing mobs are never killed (pause-don't-wipe), and hostile mobs are never affected. Counts are of loaded entities, refreshed ~every 10s.
- removed: the broken / fake / unimplemented features that were only ever disabled-in-code — `smart_entity_removal`, `mob_ai`, `chunk_loading`, and the entire unimplemented web interface (`web_interface`, plus `lag_reports` / `task_profiler` / `performance_history`, which no code ever read). Deleted from both the code (13-file `web/` package, `MobAIListener`, `ChunkManager`, dead `smartEntityRemoval()`) and `config.yml`, so nothing dead ships in the jar. Every feature left in the config now actually runs.
- improved: item merge is now grid-based and O(N). The stacking pass no longer runs a per-item nearby-entity scan (which was O(N²) on a big drop — a TNT blast or mob-farm dump could make the merge itself the lag spike on a busy 10+ player SMP). Items are bucketed once into a spatial grid and merged per cell, in a single pass per second. Same merge behaviour and stacked-amount persistence as before; still merge-only, never deletes.
- fixed: within-cell merge is now O(m) instead of O(m²). A cell full of DISTINCT items (e.g. thousands of renamed/enchanted items dumped in one spot — a possible grief vector against a lag plugin) previously cost ~130 ms in a single tick; items are now bucketed by kind, so it costs ~1–2 ms.
- fixed: stacked-amount overflow. A merged stack past ~2.1 billion items (a long-running farm/stasis pile) wrapped the 32-bit amount negative and corrupted the count/hologram/pickup. Summing is now done in 64-bit and clamped to Integer.MAX_VALUE.
- fixed (plugin-wide audit): `decrease_tickspeed` leaked its scheduler task on every `/lesslag reload` (a new listener was created but the old task kept running), and reloading/disabling while the server was lagging could leave `RANDOM_TICK_SPEED` stuck at the reduced value (e.g. 0 — crops/trees/ice/fire stop growing). The task is now tracked, cancelled on disable/reload, and restores the tick speed it lowered.
- fixed (plugin-wide audit): `/lesslag worlds` and world auto-unload walked the entire world folder recursively (disk I/O over potentially thousands of region files) on the main thread just to show a "Size: X MB" figure — a self-inflicted freeze on a big SMP. The folder-size walk now runs off-thread and reports back on the main thread.
- fixed (plugin-wide audit): duplicate listeners on `/lesslag reload`. The pickup listeners (item stacking) and the join / teleport / chat / spawn-control / command-control listeners were never unregistered on reload, so each reload stacked another copy — chat was counted twice (muting at half the configured limit) and joins/teleports/summons fired duplicate messages. Reload now sweeps the plugin's Bukkit listeners before recreating exactly one of each.
- fixed (plugin-wide audit): the profiler's `disable()` removed EVERY ProtocolLib packet listener the plugin had registered, including the packet-spam protection's — only safe by reload ordering. It now removes only its own two listeners.
- fixed (plugin-wide audit): `entity_management.kill_excess_entities` and `smart_entity_removal.enabled` had a code-level default of `true` (used only when the key is absent from config.yml), contradicting the shipped `false` and the docs. A trimmed/older config could have silently enabled the farm-killing hard cap. Both now default to `false` in code too.
- added: water / bubble-column item stacking (`item_management.water_stacking`) — items caught in a bubble column (soul sand / magma) or flowing water oscillate up and down across many blocks and so resist normal stacking, leaving lots of separate, constantly-moving item entities (heavy move-packet + physics churn). A second, water-only merge pass runs over the SURVIVORS of the normal merge and collapses each water column (up to `vertical_radius` blocks tall) into one entity, once more than `min_items` (default 4) water entities remain — so its cost scales with what's left, never with the raw drop size. Horizontal reach stays at `stack_radius`; only ever merges (amounts preserved), never deletes. Requires `item_stacking.enabled`.