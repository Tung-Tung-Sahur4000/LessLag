# LessLag — Session Handoff Notes

> Purpose: let a fresh Claude Code session (or a new contributor) pick up
> where the last one left off without re-explaining everything.
> Working branch: `claude/plugin-performance-audit-560zph`

## What this project is
LessLag is a Minecraft **Paper/Spigot** anti-lag plugin (Java 17, Maven,
hard-depends on ProtocolLib). Current version `0.0.6`.

## Build / CI
- Local: `mvn -B clean package` produces `target/lesslag-0.0.6.jar` (shaded).
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
- World entity **soft-cap** (pauses spawning over the limit; never kills)
- Random-tick-speed reduction under lag
- Explosion queue (spreads explosions across ticks; `low_tps_only`)
- Emergency event-cancels: redstone / fluids / falling-blocks / explosions
  / ender-pearls / command-blocks (trigger below their TPS threshold)
- Packet-spam protection, chat-spam protection
- In-game profiler bossbar (`/lesslag profiler`)

### Broken / fake / unimplemented (OFF at the plugin level, cannot run)
- `smart_entity_removal` — deleted the most common entity type across all
  worlds every 5 min with no TPS check (farm killer). Disabled in code.
- `mob_ai` freeze — inverted invulnerability + costly per-mob scan. Disabled.
- `chunk_loading` throttle — fought the engine. Disabled.
- `web_interface` — static placeholder pages, SSL unimplemented. Ships OFF.
- `lag_reports`, `task_profiler`, `performance_history` — no code reads these
  sections at all. Marked `[TODO]` in config, ship OFF.
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
- **Item-stacking spatial pass** (`ItemManagement` stacking task) is the one
  remaining O(N) cost: `getNearbyEntities()` per item entity once per second.
  It self-limits in practice (stacking collapses items into few entities), but
  a pathological farm (many non-mergeable, spread-out item types) could make N
  large. Clean fix if needed: spread the pass across ticks with a per-tick
  budget + round-robin cursor. Left undone deliberately — the amount/hologram
  bookkeeping is easy to break and needs an in-game verify.
- No changes have been **live-tested on a running server** — verified by
  compile + inspection + static analysis only. Worth an in-game check of:
  stacked-item count surviving `/reload` + restart; a simulated spike tripping
  the reactive gates; `/lesslag reload` not killing other features.

## Where we stopped
All four requested deliverables are done (handoff, CI artifact workflow,
improved config, re-audit). Next natural step is the in-game verification pass
above, and/or the budgeted item-stacking rewrite.
