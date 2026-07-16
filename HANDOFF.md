# LessLag — Session Handoff Notes

> Purpose: let a fresh Claude Code session (or a new contributor) pick up
> where the last one left off without re-explaining everything.
> Working branch: `claude/plugin-performance-audit-5lj2sz`

## What this project is
LessLag is a Minecraft **Paper/Spigot** anti-lag plugin (Java 17, Maven,
hard-depends on ProtocolLib). Current version `0.0.6`.

## Audit verdict (short version)
Mixed. Some features genuinely reduce lag; several were **broken, fake, or
unimplemented**. Key design flaw: reactive throttling is gated on the
**1-minute-average TPS**, so it reacts to sustained lag, not short spikes.

### Genuinely works (kept ON)
- Item stacking (fewer item entities)
- World entity **soft-cap** (pauses spawning over the limit; never kills)
- Random-tick-speed reduction under lag
- Explosion queue (spreads explosions across ticks, lag-only)
- Emergency event-cancels: redstone / fluids / falling-blocks / explosions
  / ender-pearls / command-blocks (only trigger below their TPS threshold)
- Packet-spam protection
- In-game profiler bossbar (`/ll profiler`)

### Broken / fake / unimplemented (turned OFF)
- `smart_entity_removal` — deleted **all** of the most common entity type
  across all worlds every 5 min with no TPS/overpopulation check (farm
  killer).
- `mob_ai` freeze — inverted invulnerability logic + a per-mob proximity
  scan that added more tick cost than it saved.
- `chunk_loading` throttle — force-unloaded chunks the server was actively
  loading, fighting the engine.
- `web_interface` — profiler/history/reports pages are static placeholders;
  SSL unimplemented.
- `lag_reports`, `task_profiler`, `performance_history` — no code reads
  these config sections at all.
- `disable_redstone.clocks_only` — a **fake** toggle; below the threshold
  it disables ALL redstone regardless of this value.

## Changes already made & pushed on this branch
Commit: *"Fix silent stacked-item pickups; disable broken lag features at
plugin level"*

1. **Fixed silent item pickups** (`item/ItemManagement.java`): stacking
   cancels the vanilla pickup event, which suppressed the "pop" sound.
   Now replays `Sound.ENTITY_ITEM_PICKUP` manually when items are handed
   to a player.
2. **Lighter item stacking**: scan only item entities
   (`getEntitiesByClass(Item.class)`), every 20 ticks instead of 10.
3. **Disabled at the plugin level** (not just config, so they can't run
   even if re-enabled): `smart_entity_removal`
   (`entity/EntityManager.java`), `mob_ai`
   (`performance/PerformanceManager.java`), `chunk_loading` throttle
   (`chunk/ChunkManager.java`), `web_interface` (`web/WebServer.java`).

> Note: could not `mvn compile` in the sandbox (proxy blocks the
> PaperMC/ProtocolLib Maven repos with 403). Changes were verified by
> inspection + brace-balance. Run `mvn package` locally to build the jar.

## Recommended server config
Running the "only what actually works" config: item stacking, world
entity soft-cap (`kill_excess_entities: false`), tickspeed reduction,
explosion queue (`low_tps_only`), the emergency event-cancels,
packet-spam protection, and the profiler ON — everything broken OFF.
(`clocks_only: false` to match real behavior; `web_interface: false`.)

## Known issues NOT yet fixed (optional future work)
- `Bukkit.getScheduler().cancelTasks(plugin)` in `WorldManager.disable()`
  and `Profiler.disable()` cancels **every** plugin task, not just their
  own — fragile on `/ll reload`. Prefer restart over reload for now.
- Stacked item amounts live in memory only → ground stacks can lose their
  count across a server restart. Should be persisted (e.g. PDC).
- Reactive features use 1-minute-average TPS; a shorter/MSPT-based signal
  would react to spikes.

## Current task / where we stopped
Learning to use the **spark** profiler to find the *real* lag source.
Plan: user runs `/spark tps` and `/spark profiler`, then shares a
**screenshot of the "Sources" breakdown** (per-plugin % of tick time).
The sandbox can't open spark.lucko.me share links (network-restricted),
so paste screenshots/text rather than the URL.
