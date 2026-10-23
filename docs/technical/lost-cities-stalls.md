# Lost Cities and long server ticks: cause, measurements, what helps and what does not (D-096)

**Status:** root-caused to the blocking calls, **reproduced and measured** in the cloud with a repeatable probe. Two changes are in the repository: a Core mixin that removes the vanilla villager wait (the 30 to 38 s ticks; shown by a GameTest that fails without it and by 0 of 8 probe runs against 2 of 10 before) and a **new-world default `forceSaplingGrowth = false`** that removes the Lost Cities queue wait (the 5 to 12 s ticks; **it visibly changes the number of grown city trees, so it is flagged for the owner**). A Core-side guard on tree growth was tried and **shown not to work** and was removed. **Nothing is fixed upstream and nothing has been posted** (a draft report is at the end, for the owner to approve). No human has walked into a freshly generated city on a server; the numbers are from a scripted server with no client unless a row says otherwise.

## What was seen

During the combined journey (two clients, a generated Lost Cities world, seed 5908) the server logged `Can't keep up! ... Running 75822ms or 1516 ticks behind`, and five earlier runs were ended by the production watchdog (`A single server tick took 60.00 seconds`). Two crash reports showed the server thread inside Lost Cities' world-tick hook. The first notes are in [testing.md](testing.md); this document replaces their open question ("whether a player can trigger it") with measurements.

## How it was measured (repeatable)

`scripts/run_stall_probe.py` builds a disposable copy of the module whose development-pack manifest names a Lost Cities release (metadata from the Modrinth API, the jar hash-verified like every pinned mod, nothing committed), starts a dedicated server on a **generated Lost Cities world** (seed 5908, the world set's three-city profile, no client), and `StallProbe` (an opt-in class of Core, `-Duc.stallProbe=true`) asks the chunk system for a square of chunks the way a pregenerator or a large `/forceload` does. It reports, from the server thread's own clock, the longest tick, the number of ticks over 1, 5, 30 and 60 seconds, and, from a sampler thread that takes the server thread's stack every 100 ms while a tick is running long, **where the time went**.

* The probe server runs with `max-tick-time=-1` **only so that a long tick can be measured instead of ending the run.** The report states how many ticks would have been killed (over 60 s). **No server of the pack, and no hosted production-style check, has its watchdog disabled**; the journey fixture's own `-1` is listed in [testing.md](testing.md) as a fixture setting, not a fix.
* Environment: Claude's cloud container (4 cores, 15 GB), Minecraft 1.20.1, Forge 47.4.10, Java 17.0.20, `view-distance=4`, difficulty normal.
* Request: 17 x 17 = 289 chunks (radius 8) around chunk (124, 17), which is inside the Valewood city region, nearest chunks first, ticket level 31 (forced), then 1,200 ticks of settling. Other options: `--mode paced --per-tick N --every M` (N chunks every M ticks), `--lost-cities-config key=value`, `--versions` (a list of releases).

## Where it happens

| Request | Longest tick | Reading |
| --- | --- | --- |
| chunks around the spawn (0, 0), already generated | 838 ms | nothing to generate; not informative |
| chunks around (60, 60), open country outside every city | 705 ms (generation took 120 s) | no stall: terrain alone does not do it |
| chunks around (124, 17), a Valewood city region | **4.9 to 10.9 s in seven runs** | the stall needs generated city content |

## The three places the server thread waits

Across the runs the sampler found the server thread **parked, not computing**: more than nine tenths of the long-tick samples are `LockSupport.parkNanos < BlockableEventLoop.waitForTasks < managedBlock < ServerChunkCache.getChunk`. A read of a block in a chunk that is not generated yet makes the main thread wait for the chunk workers, and in a bulk request the chunk it needs is behind the whole queue. Three different callers were seen:

| | The caller (innermost frames) | Seen |
| --- | --- | --- |
| A | Lost Cities `ForgeEventHandlers.onWorldTick` -> `GlobalTodo.executeAndClearTodo` -> the sapling task -> `SaplingBlock.advanceTree` -> `TreeFeature.doPlace / validTreePos / set` -> `getChunk` | the largest share of the long-tick samples in most runs |
| B | the same `GlobalTodo` task itself (`LostCityTerrainFeature.lambda$handleTodo$10`: `isAreaLoaded(pos, 1)`, then `setBlock`, then `advanceTree`) reading a block | the second largest share in most runs |
| C | **vanilla**: `Villager.tick` -> `Brain` -> `InteractWithDoor` -> `DoorBlock.setOpen` -> `Level.setBlock` -> `ServerLevel.sendBlockUpdated` -> `PathNavigation.recomputePath` -> `GroundPathNavigation.createPath` -> `Level.getBlockState` -> `getChunk` | **2 of 14** runs with sapling growth on (39.8 s with Lost Cities 7.5.6, 36.5 s in a run with the Core guard below), each a single tick of 30 to 40 s |

What Lost Cities' own code does (read from the bytecode of the pinned 7.5.5 jar with `javap`; the source is MIT-licensed on GitHub but was not read from there): with `forceSaplingGrowth = true` (the default) the saplings of a generated city are not left to grow later; each one is queued as a task in `GlobalTodo`, which `ForgeEventHandlers.onWorldTick` runs at the end of every level tick on the **server thread**, up to `todoQueueSize` (20) per chunk. A task checks `isAreaLoaded(pos, 1)`, which is **one block**, and then grows a tree, whose feature reads blocks up to eight blocks away. With `forceSaplingGrowth = false` the sapling is placed by the chunk's own post-generation list (no global task). C is not Lost Cities' code at all; it is the base game's villager pathing reading a target in a chunk that is still waiting, and it occurs at the edge of a bulk request, where villagers are ticking beside chunks that nobody has finished (where those villagers come from was not traced).

## Versions

Bulk request, (124, 17), radius 8, the same world and machine:

| Lost Cities | Runs: longest tick (s) | Reading |
| --- | --- | --- |
| 1.20-7.5.5 (the pinned release) | 4.9, 6.2, 7.1, 6.9, 8.0, 10.9, 5.2 | A and B |
| 1.20-7.5.6 | 7.1, **39.8** | the 39.8 s one is C |
| 1.20-7.5.7 | 12.0, 5.2 | A and B |

**No release is better.** The world set's `mods.toml` still demands exactly 7.5.5 (the release the surveys were measured on); the probe's copy relaxes that and nothing else.

## What helps

All rows: bulk request unless stated, (124, 17), radius 8, Lost Cities 7.5.5, longest single tick per run.

| Change | Longest ticks | Cost |
| --- | --- | --- |
| nothing (baseline) | 4.9, 6.2, 7.1, 6.9, 8.0, 10.9, 5.2 s (7 runs); 7 to 10 ticks over 1 s, 40 to 45 s over budget in total | |
| `forceSaplingGrowth = false` only | 0.66, 0.69, 0.73, 0.77, 0.80, 0.74, 0.69, 0.85 s (8 runs) **and 30.0 s and 38.0 s (2 runs; stack C)** | the trees of a generated city park are saplings until they grow by the ordinary random tick. In the area measured: **leaf blocks 34.0k -> 15.1k, log blocks 4.0k -> 1.8k, saplings 90 -> 514**. Terrain and buildings are not touched |
| the navigation mixin only (sapling growth on) | 6.1, 9.5, 8.3 s (3 runs); the stacks are all A and B, none C | none that a player could see: a creature gets "no path" for a target in an unloaded chunk, as the parent class already does |
| **`forceSaplingGrowth = false` + the navigation mixin** (what the repository now ships for new worlds) | **0.60 to 0.80 s (8 runs); 0 ticks over 1 s; 1 to 2 s over budget in total; a "Can't keep up" line (2.0 s behind) in 2 of the 8** | the tree count above |
| paced requests, 1 chunk per tick (sapling growth on) | 5.4, 5.2 s | does not help: the queue is shorter but each wait is still for generation |
| walking pace, 1 chunk per 7 ticks (about what a walking player needs), sapling growth on | 3.2, 4.2 s; 3 ticks over 1 s; 11 to 14 s over budget in 107 s | a few visible hitches per minute of walking into new city |
| walking pace, sapling growth off | 0.71, 0.83, 0.83 s; no tick over 1 s | the tree count above |
| a Core handler for Forge's `SaplingGrowTreeEvent` that says "not now" when a chunk within eight blocks is not full (tried, **removed**) | 36.5 (C), 4.5, 6.7 s | **does not work**: the stacks of the guarded runs still show the same waits and the vegetation counts were identical to the baseline, so this event was not what the queued growth was waiting on. 122 of 1,214 growth requests were deferred and nothing changed. The code and its tests were dropped rather than shipped as a guess |

Other Lost Cities releases (bulk, sapling growth on): 7.5.6 gave 7.1 and 39.8 s (C), 7.5.7 gave 12.0 and 5.2 s. No release is better.

The vegetation counts also show that the amount of forced growth is **timing dependent** (a walking-pace run grew about 19k leaf blocks, a bulk run 34k): with the option on, the trees of a city are not deterministic from the seed either.

## The fixes in the repository (D-096)

1. **`GroundPathNavigationMixin`** (a mixin on vanilla `GroundPathNavigation.createPath(BlockPos, int)`): when the chunk of the target is not loaded (`getChunkNow` is null) the answer is "no path", exactly as the parent class's other `createPath` already answers. The method is named by both its official and its Searge name because the build does not produce a refmap for vanilla targets. Proof: `NavigationGameTests` (a villager asks for a path into a chunk seven million blocks away: no path, the chunk still unloaded, under 250 ms; **without the mixin the test fails with "asking for the path loaded (or generated) the far chunk"**, shown by removing it from the mixin configuration; a control shows ordinary pathing over a loaded floor is unchanged) and `BootSmokeHarness`' `nav_unloaded_chunk=ok` fact, which the production-style boot check requires **under the production (Searge) names** (see [testing.md](testing.md)).
2. **`forceSaplingGrowth = false` in the generated defaults for a NEW world** (`scripts/install_world_prototype.py`, emitted to `content/generated/world-prototype/game-config/defaultconfigs/lostcities-server.toml`, staged by the development launch, the journey and `run_prod_boot_check.py`). An existing world keeps `world/serverconfig/lostcities-server.toml` (change the line there while the server is stopped). **This is an engineering default with a visible world effect, not an owner decision:** if the owner prefers grown city trees, delete the line and regenerate; the price is back to 5 to 12 s ticks when new city is generated.

### Our own road planning (October 23)

Not Lost Cities, but the same symptom: the first time a road chunk generated after a server started, `CorridorRoadFeature` planned the three roads from the terrain noise (about 9.5 s, measured in journey runs 37 and 47) on the worldgen worker that needed them, and the server thread, waiting for that chunk, fell 9 seconds behind ("Can't keep up! ... 9281ms"). It came mid-play: as the scripted player walked toward the road, and again after a save and reopen had started a new integrated server. The plan depends only on the seed and the route data, so it is now started on a background worker when the overworld loads (`CorridorRoadFeature.prepare`, from `LevelEvent.Load`), and the first road chunk takes the finished plan or waits only for what is left. Checked on a new world: "Planned 3 road(s) ... in the background as the world loaded", with the same profile and bridge sample counts as before (269/83, 248/85, 263/82); 33 s on a machine shared with two clients. Not yet measured in a journey run: whether a player who reaches road chunks within the first half minute still waits for the rest.

## Operating rules

* **The production watchdog stays on.** A server that cannot finish a tick in 60 seconds is better stopped than frozen; the journey fixture's `max-tick-time=-1` and the probe's are for one disposable test server each, and each document that uses it says so. **No server of the pack, and no production-style check, has its watchdog disabled.**
* **Do not pregenerate a Lost Cities area in bulk on a production server** (`/forceload add` over many chunks, a pregenerator) even with these changes: nothing here makes generation of a city cheap, it only removes the server thread's waiting. If a large area must be generated, do it on a disposable copy and keep the result.
* Not established: whether two players far apart can still produce a long tick; whether other mods' mobs with their own pathing (a mod that overrides `createPath` again) wait the same way.

## What this does not show

* It is **not a measurement of a person walking**: the walking-pace rows are a script asking for chunks at a walking player's rate; no client was connected, so no keep-alive, no light updates for a player, no entity tracking.
* The 60-second kill itself was **not reproduced** by the probe (the longest tick was 39.8 s); the 75.8 s tick was seen once on a hosted runner during the journey, whose stack had the signature of A; the hosted combined journey also logged a 57.7 s "Can't keep up" after its fixture sites were generated on the head before these changes (its journey server runs with `max-tick-time=-1`, which is why it survived).
* Run counts are small and listed with every row; the spread between runs is large (4.9 to 10.9 s with the same inputs, and a 30 to 38 s outlier in 2 of 10), so a single run proves little either way. The mixin's effect on the outlier rests on the GameTest, the stack classification (no villager stack in any of the 11 runs with it) and 0 of 8 against 2 of 10 runs, not on a large sample.
* Only seed 5908 and one city region were probed.

## Draft report for the Lost Cities maintainers (**not posted; needs the owner's approval**)

> **Server thread blocks in `ServerChunkCache.getChunk` while `GlobalTodo` runs sapling growth after bulk chunk generation (`forceSaplingGrowth`)**
>
> Lost Cities 1.20-7.5.5 (also 7.5.6, 7.5.7), Minecraft 1.20.1, Forge 47.4.10, dedicated server, no other world-gen mod.
>
> With the default `forceSaplingGrowth = true`, `LostCityTerrainFeature.handleTodo` queues each sapling in `GlobalTodo`, and `ForgeEventHandlers.onWorldTick` runs the queue on the server thread. The task checks `ServerLevel.isAreaLoaded(pos, 1)` and then calls `SaplingBlock.advanceTree`, whose tree feature reads and writes blocks up to eight blocks away. When a neighbouring chunk is not generated yet the read is a blocking `ServerChunkCache.getChunk`, and with many chunks requested at once (a pregenerator, a large `/forceload`) that chunk waits behind the whole queue. Sampled stacks (100 ms) show the server thread parked in `BlockableEventLoop.managedBlock` for the whole tick.
>
> Reproduction: seed 5908, a three-city profile, request a 17 x 17 chunk square inside a city with forced-chunk tickets; the longest tick was 4.9 to 10.9 s in seven runs of 7.5.5 (and 12.0 s with 7.5.7), once 39.8 s; with `forceSaplingGrowth = false` the longest tick was 0.6 to 0.85 s in eight runs (and 30 and 38 s in two, which are vanilla villager pathing, see below).
>
> Suggested change: before running a growth task, check with a non-blocking lookup (`ServerChunkCache.getChunkNow`) that every chunk within eight blocks is loaded, and re-queue the task otherwise (or run it from the chunk's post-generation step as the non-forced path does). A second, vanilla, source of the same wait (`GroundPathNavigation.createPath(BlockPos, int)` reads `getBlockState(target)` before the loaded-chunk check its parent makes; a villager that opens a door recomputes its remembered path) is not Lost Cities' to fix; Underground City works around it with a mixin.
>
> The attached `StallProbe` and `scripts/run_stall_probe.py` of Underground City reproduce the numbers; they are ours and would need to be rewritten as a mod-free test for an upstream issue.
