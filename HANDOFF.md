# LessLag — Session Handoff Notes

> Purpose: let a fresh Claude Code session (or a new contributor) pick up
> where the last one left off without re-explaining everything.
> State: everything through change 17 is merged into `main` (PR #7). Start new
> work from the latest `main` on a fresh feature branch.

## What this project is
LessLag is a Minecraft **Paper/Spigot** anti-lag plugin (Java 17, Maven,
hard-depends on ProtocolLib). Current version `0.0.7`.

## Build / CI
- Local: `mvn -B clean package` produces `target/lesslag-0.0.7.jar` (shaded).
  Building in-sandbox now works (Paper/ProtocolLib deps resolve); only the
  offline `surefire` test plugin is missing, so use `-DskipTests` offline.
  `PluginTest` is a trivial `assertTrue(true)` and passes in CI.
- **CI: `.github/workflows/build.yml`** builds on every push/PR (and manual
  `workflow_dispatch`) with Temurin JDK 17 and uploads the plugin jar as the
  **`LessLag-plugin`** artifact (download from the run's Artifacts section).

## Spark profiling result (the "real lag source" question)
Decoded a real spark sampler dump directly (bytebin → protobuf; the viewer
share links are network-blocked but `https://bytebin.lucko.me/<code>` is
fetchable). Verdict for that capture: **server was healthy — 20.0 TPS, main
thread 85% idle/parked between ticks.** No plugin is a lag source: LessLag
used ~0.5% of tick time; all 39 plugins combined ~2.5%. Real work is vanilla
entity/mob-AI/block-entity ticking. To catch an actual spike, profile while
lagging with `/spark profiler --thread "Server thread" --only-ticks-over 50`.

## Audit verdict
Some features genuinely reduce lag; several were **broken, fake, or
unimplemented** and are now disabled in code. The original key design flaw —
reactive throttling gated on the **1-minute-average TPS** (reacts to
sustained lag, misses spikes) — has been **fixed** (see below).

### Genuinely works (ON)
- Item stacking (fewer item entities; now persists amounts across restart)
- Water / bubble-column item stacking (collapses an oscillating column of
  soul-sand/magma/water items into one stacked entity; merge-only, never kills)
- World entity **soft-cap** (pauses spawning over the limit; never kills)
- **Villager optimizer** (`villager_optimization`): collision-off in packed
  chunks, phase-staggered AI throttle when no player is near, per-chunk breeding
  soft-cap; one timer pass, cost independent of villager count. `/lesslag
  villagers` shows live stats.
- **Global breedable caps** (`entity_management.breedable_global_limits`):
  server-wide per-type population caps for farm mobs; pauses breeding/egg-hatch
  over the cap, never kills.
- Random-tick-speed reduction under lag
- Explosion queue (spreads explosions across ticks; `low_tps_only`)
- Emergency event-cancels: redstone / fluids / falling-blocks / explosions
  / ender-pearls / command-blocks (trigger below their TPS threshold)
- Packet-spam protection, chat-spam protection
- In-game profiler bossbar (`/lesslag profiler`)

### Broken / fake / unimplemented — now REMOVED (change 16)
These were previously disabled-in-code but still shipped as dead code + config.
As of change 16 they are deleted from both the source and `config.yml`:
- `smart_entity_removal` — deleted the most common entity type across all
  worlds every 5 min with no TPS check (farm killer).
- `mob_ai` freeze — inverted invulnerability + costly per-mob scan.
- `chunk_loading` throttle — fought the engine.
- `web_interface` — static placeholder pages, SSL unimplemented (whole `web/`
  package, 13 files).
- `lag_reports`, `task_profiler`, `performance_history` — no code ever read
  these sections.

Still present (a real, documented quirk, not removed):
- `disable_redstone.clocks_only` — **fake** toggle; below threshold ALL
  redstone is disabled regardless. Ships `false` to match real behavior.

## Changes made & pushed on this branch (in order)
1. **Silent item pickups fixed** + lighter stacking (scan only `Item.class`,
   every 20 ticks). Broken features disabled at the plugin level.
2. **Stacked amounts persist** (`item/ItemManagement.java`): mirrored onto the
   item **entity** PDC (`lesslag:stack_amount`, INTEGER) via
   `setStackedAmount`/`clearStackedAmount`; recovered on restart/reload.
   `reload()` now restarts the stacking task (it used to die); `disable()`
   stops it.
3. **Spike-aware reactive signal** (`performance/TPSUtil.java`): derives an
   instantaneous TPS from MSPT (`getAverageTickTime`) and returns
   `min(instant, 1-min-avg)` — trips on **either** signal (strict superset of
   old behaviour). All reactive listeners route through
   `getResponsiveTPS()`; the value is **memoised for one tick (50ms)** so hot
   paths don't recompute it. Drastic world force-unload deliberately stays on
   the **sustained** average (`getAverageTPS()`).
4. **`cancelTasks(plugin)` scoped** in `WorldManager`/`Profiler`: each keeps
   its own `BukkitTask` handles and cancels only those, so `/lesslag reload`
   no longer tears down unrelated features.
5. **Hot-path listener trimming** (the anti-lag-shouldn't-add-lag pass): each
   perf listener is only registered while enabled, so the per-event
   `config.getBoolean(...enabled)` reads were removed; cheap guards reordered
   before the TPS read; `Material.name()` String scans replaced with enum /
   `EnumSet` lookups (`FluidListener`, `FallingBlockListener`); chat-prefix
   fetches moved into the cancel branch (`EnderPearl`, `SpawnControl`,
   `CommandControl`, `PlayerTeleport`) so they don't run on every interaction.
6. **CI workflow** `.github/workflows/build.yml` (artifact upload).
7. **Config overhaul** `src/main/resources/config.yml`: `[OK]/[OFF]/[TODO]`
   status legend per feature; safer defaults (`kill_excess_entities: false`,
   `clocks_only: false`, `web_interface.enabled: false`, explosion
   `low_tps_only.enabled: true`, `force_unload_on_low_tps.enabled: false`);
   documents that every `disable_below_tps` is now spike-aware.
8. **CI JDK 21** — the workflow built on JDK 17, but Paper 1.21.x is Java-21
   bytecode, so every `org.bukkit.*` symbol was "not found". Bumped the
   workflow to Temurin 21. CI is now green and uploads `LessLag-plugin`.
9. **Per-item drop timer + hologram load control** (`item/ItemManagement.java`,
   config `item_management.drop_timer` + `item_management.hologram`):
   - Each dropped item gets its own countdown (default 30s) and is removed
     when it expires — replacing the all-at-once `auto_clear_drops` (now off
     by default). The timer uses the entity's own age (`getTicksLived`), so it
     survives restart/reload; a per-item whitelist never expires.
   - The ticking number only shows in the final `countdown_seconds` (default
     10) before despawn — above that the hologram is static (`Dirt x64`), then
     `Dirt x64 §7(10s)` counts down as an about-to-expire warning. This avoids
     repainting every item's hologram every second for its whole life; set
     `countdown_seconds >= despawn_seconds` to always show it.
   - Merging refreshes the stack to the freshest item's age, so fresh drops
     aren't cut short by an old pile; an idle pile still expires.
   - Hologram throttle: per world, `> throttle_above` items → refresh
     holograms every `throttled_refresh_seconds` instead of every second;
     `> hide_above` → hide holograms entirely. Cuts entity-metadata packet
     spam while mining / at farms.
   - Ghost cleanup: `despawnItem()` clears the hologram before removing so no
     stale name lingers, and each pass prunes tracking-map entries for items
     no longer seen (safe — the count is re-adopted from the entity PDC).

10. **Water / bubble-column item stacking** (`item/ItemManagement.java`, config
    `item_management.water_stacking`; version bumped 0.0.6 → 0.0.7). Items in a
    bubble column (soul sand up / magma down) or flowing water get shot up and
    fall repeatedly, oscillating across many blocks so they sit just outside the
    normal symmetric `stack_radius` window and never merge — a soul-sand item
    elevator or a drop chute over water leaves dozens of separate,
    constantly-moving item entities (move-packet + physics churn = the reported
    "massive lag"). The per-second item maintenance pass now, per world, counts
    items suspended in water (`isSuspendedInWater`: WATER / BUBBLE_COLUMN /
    waterlogged block) and, once that count exceeds `min_items` (default 20),
    merges those items with a taller **vertical** reach (`vertical_radius`,
    default 12) while keeping horizontal reach at `stack_radius` — so a whole
    column collapses into one stacked entity without over-merging a wide area.
    Reuses the existing stacking/amount bookkeeping, so it **only merges (amounts
    preserved), never deletes**. Requires `item_stacking.enabled`. Implemented as
    a 2nd radius argument to `stackNearbyItems`; the old symmetric call still
    delegates to it. (Scope note: the same paste also mentioned making ender
    pearls "teleport only / not keep chunks or mobs loaded" — deliberately NOT
    implemented; controlling chunk tickets / entity ticking needs NMS and a
    plugin-level version would be a fake toggle, which this project's config
    culture explicitly forbids.)

11. **Grid-based item merging** (`item/ItemManagement.java`) — the merge no
    longer does a per-item `getNearbyEntities()` spatial query. On a big drop
    (TNT blast, mob-farm dump) that was O(N^2): each of N items rescanned all N
    items in its chunk, so the anti-lag merge became the lag spike on a busy
    10+ player SMP. Now every item is bucketed once into a spatial cell
    (`cellKey`, a packed-long grid keyed off `stack_radius`) in a single O(N)
    pass, then `mergeGroup` collapses each cell. Same amount/tag/timer/hologram
    bookkeeping as before — still merge-only, never deletes. The water pass
    (`mergeWaterColumns`) now also runs on survivors only (tall vertical cell =
    `water_stacking.vertical_radius`), so its cost scales with entities left,
    not with the raw drop, and never re-introduces the O(N^2) path. This
    supersedes the old `stackNearbyItems` (removed) and the extra full-scan
    water pre-count that change 10 originally added.

12. **Merge hardening after a stress-test pass** (`item/ItemManagement.java`).
    A Bukkit-free model of the exact algorithm (grid bucketing, `cellKey` bit
    math, merge, water pass) was run over 12 adversarial scenarios, checking
    amount conservation + cost. It surfaced three real defects, now fixed:
    - **In-cell O(m^2):** `mergeGroup` did a pairwise `canStack` scan, so a cell
      full of DISTINCT items (thousands of renamed items dumped in one spot — a
      grief vector against a lag plugin) cost ~130 ms in one tick. Rewritten to
      bucket by normalised `ItemStack` (equal key ⇔ stackable) in O(m), then
      collapse each kind linearly → ~1–2 ms. `canStack` removed (the key
      equality is exactly equivalent for the tagged, non-null-meta items the
      maintenance path produces).
    - **Amount overflow:** a merged stack past ~2.1 B wrapped the 32-bit amount
      negative (corrupting count/hologram/pickup). Now summed in `long` and
      clamped to `Integer.MAX_VALUE`.
    - **Water pass never engaged:** its `min_items` gate is checked on the
      post-merge survivor count, but the default (20) was higher than the
      survivor count almost ever is, so the feature did nothing. Default lowered
      to 4.
    Confirmed by the same harness: TNT/mergeable clustering is O(N) (5 000 items
    → 5 000 ops), amount is conserved across all 12 scenarios + a 30-pass
    stability run, and far-apart / negative / world-border coords never collide.
    **Known tradeoff (documented, not a bug):** grid merging is per-cell, so
    mergeable items straddling a cell boundary don't combine in one pass — on a
    wide static spread the grid leaves ~72 stacks where the old radius scan left
    ~25. No data is lost (amounts conserved), the count stays bounded (≤ one per
    cell per kind), and on a live server items drift so boundary pairs
    eventually share a cell and merge. If perfect completeness is ever wanted,
    add a phase-2 reconcile over the (few) survivors — cheap now that survivors
    are few. Still NOT live-tested on a running server (algorithm verified by
    the harness + compile; the Bukkit glue is unchanged from earlier commits).

13. **Plugin-wide re-audit (every source file re-read).** Two real bugs fixed,
    plus findings logged:
    - **`TickSpeedListener` task leak + stuck tick speed** (fixed): the listener
      never stored/cancelled its scheduler task, so every `/lesslag reload`
      leaked another task, and reloading/disabling while lagging left
      `RANDOM_TICK_SPEED` pinned at the reduced value (0 = no crop/tree/ice/fire
      growth). Now stores the task, and `unregister()` cancels it and restores
      the tick speed; wired into `PerformanceManager.disable()`.
    - **`EntityManager` destructive code defaults** (fixed): `kill_excess_entities`
      and `smart_entity_removal.enabled` defaulted to `true` in code (the value
      used only when the key is missing), contradicting the shipped `false`.
      A trimmed/older config could silently enable the farm-killing hard cap.
      Both now default `false`.
    - **Hologram throttle/hide verified WORKING**: `hologramModeFor` returns
      HIDDEN above `hide_above` (400) — `refreshHologram` clears every item's
      name and adopt/merge skip labelling — and THROTTLED above `throttle_above`
      (150), which slows only the per-second countdown repaint (static labels
      still show). Merging still runs in all modes; only the label work is gated.
    - **Findings NOT changed (lower severity / would add risk):**
      (a) `ChatListener` runs on the async chat thread and calls
      `PlayerManager.incrementMessageCount`, which does a non-atomic
      `totalMessagesPerSecond++` — minor undercount of spam under concurrent
      chat (never a crash). AtomicInteger would tidy it.
      (b) PlayerManager registers its packet listener even when packet-spam is
      disabled (returns early per packet — small constant overhead), and its
      per-packet increment isn't atomic (minor undercount under load).
      (c) After a HIDDEN→FULL transition, items that were hidden stay unlabelled
      until they next merge or enter the despawn countdown (cosmetic).
      (d) `WebServer` is correctly inert (`WEB_INTERFACE_IMPLEMENTED=false`,
      never opens a socket); `ChunkManager`/`MobAIListener`/`smartEntityRemoval`
      confirmed hard-disabled and unregistered.

14. **Reload listener leak fixed** (`LessLag.reloadPlugin`, `system/Profiler`).
    ItemManagement's pickup listeners and the five directly-registered
    listeners (PlayerJoin / PlayerTeleport / Chat / SpawnControl /
    CommandControl) were never unregistered on `/lesslag reload`, so every
    reload stacked another copy: chat double-counted (muted at half the limit),
    joins/teleports/summons sent duplicate messages, and old ItemManagement
    listeners leaked. `reloadPlugin()` now calls `HandlerList.unregisterAll(this)`
    after disabling and before recreating, so exactly one of each is left.
    Also `Profiler.disable()` now removes only its own two ProtocolLib packet
    adapters (it used to strip every plugin packet listener, including the
    packet-spam protection — previously masked by reload order). The
    per-manager task/state cleanup verified reload-safe: WorldManager,
    EntityManager, PlayerManager, PerformanceManager (incl. TickSpeed restore),
    ExplosionQueue, Profiler all cancel their own tasks and re-init cleanly.

15. **Main-thread disk I/O removed** (`system/WorldInfo`, `world/WorldManager`).
    `/lesslag worlds` and world auto-unload recursively walked the whole world
    folder (potentially thousands of region files, multiple GB) on the main
    thread just to print "Size: X MB" — a self-inflicted stall on a big SMP.
    The folder-size walk now runs via `runTaskAsynchronously`, with the
    resulting messages sent back on the main thread. The Bukkit-API stats
    (entities/chunks/players) are still snapshotted on the main thread first.
    Full re-audit of every source file is now complete; remaining items are the
    documented low-severity cosmetics only.

16. **Dead features deleted (code + config).** The features that were only ever
    disabled-in-code are now fully removed, so nothing dead ships in the jar:
    - Deleted files: the whole `web/` package (13 files: WebServer, the four
      handlers, four pages, HttpResponse, RouteHandler, AccessTokenManager,
      WebSessionManager), `performance/MobAIListener.java`,
      `chunk/ChunkManager.java`.
    - Deleted code: `EntityManager.smartEntityRemoval()` + its fields/imports;
      the MobAI wiring and `mob_ai` config reads in `PerformanceManager`; the
      `webServer`/`chunkManager` fields, wiring, and `getWebServer()` in
      `LessLag`; the `/lesslag web` subcommand + `handleWebCommand`/`capitalize`
      in `MainCommand`.
    - Deleted config: `smart_entity_removal`, `mob_ai`, `chunk_loading`,
      `web_interface`, `lag_reports`, `task_profiler`, `performance_history`;
      the `[OFF]`/`[TODO]` legend entries (nothing uses them now).
    - Verified: `mvn clean package` BUILD SUCCESS; `unzip -l` on the jar shows
      no `web/`/`MobAI`/`ChunkManager` classes; config.yml is valid YAML with
      only the 8 real top-level sections; no source reference to any removed
      symbol remains (grep clean). `/lesslag` commands are now
      `reload|info|profiler|worlds`. The in-game profiler bossbar is untouched
      (it was never part of the web interface). ProtocolLib is still required
      (profiler packet counting + packet-spam protection use it).

17. **Merged everything to `main` + integrated the villager optimizer.** All of
    the above (changes 1-16) plus the `server-mobfarm-perf-h5` villager branch
    were merged into `main` via PR #7. That branch had forked before an earlier
    merge and edited the same core files, so the merge had conflicts in
    `EntityManager`, `PerformanceManager`, `MainCommand`, `config.yml`, and
    `LessLag` — all resolved to **keep both** feature sets:
    - Kept (theirs): `VillagerOptimizer`, `BreedingCapListener`,
      `villager_optimization` + `entity_management.breedable_global_limits`
      config, `/lesslag villagers` command.
    - Kept (mine): the O(N)/O(m) item merge, all audit fixes, and the removal of
      the dead features (`smart_entity_removal` / `mob_ai` / `chunk_loading` /
      web interface stayed removed — their conflicting re-additions were
      dropped). `killExcessEntities` default stays `false`.
    - Deduped the two reload-listener sweeps into one `HandlerList.unregisterAll`.
    CI (`build`) was green and the PR merged clean into `main`.
    **Process going forward:** `main` is the stable, integrated base; new work
    branches off the latest `main`, one feature per branch → PR → merge, to
    avoid the long-lived-branch divergence that caused this merge's conflicts.

## Re-audit status (all changes verified)
- `mvn -o clean compile` / `package -DskipTests` → BUILD SUCCESS (only a
  pre-existing `NamespacedKey` deprecation warning).
- Static sweep: no stray reactive `getTPS()[0]` reads remain (Profiler line
  ~143 keeps it intentionally for the bossbar display). Every reactive
  listener references `TPSUtil`. Remaining `config.getBoolean` calls are in
  non-hot spots only (TickSpeed 5s timer, infrequent explosion event, dead
  MobAI code).
- config.yml validated as well-formed YAML.

## Known issues NOT yet fixed (optional future work)
- **Item-stacking spatial pass** — FIXED (change 11 below). The pass no longer
  calls `getNearbyEntities()` per item; it grid-buckets all items in one O(N)
  pass and merges within each cell, so a big TNT/mob-farm drop no longer makes
  the merge itself an O(N^2) spike. A per-tick budget + round-robin cursor is no
  longer needed for this; only revisit if `getEntitiesByClass` itself (the
  unavoidable O(N) entity fetch) ever shows up in a profile.
- No changes have been **live-tested on a running server** — verified by
  compile + inspection + static analysis only. Worth an in-game check of:
  stacked-item count surviving `/reload` + restart; a simulated spike tripping
  the reactive gates; `/lesslag reload` not killing other features.

## Where we stopped
Everything (item-merge optimization, full plugin audit, dead-feature removal,
and the integrated villager optimizer) is merged into `main` (PR #7, version
`0.0.7`); CI is green. The one thing still outstanding for ALL of it is an
**in-game verification pass on a running server** — none of these changes have
been live-tested. Worth checking: stacked-item counts surviving `/lesslag
reload` + restart; `/lesslag reload` run several times not muting chat early or
leaving `RANDOM_TICK_SPEED` stuck; `/lesslag worlds` not freezing on a large
world; a big TNT drop collapsing without a tick spike; and the villager
collision/AI-throttle/breeding-cap levers behaving in a packed hall.
