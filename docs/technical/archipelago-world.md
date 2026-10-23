# The archipelago world: three islands, three bridges (D-104)

Status: **implemented and measured on generated terrain in Claude's cloud container; no person has walked it.** Everything below says which evidence it rests on. Where a number came from a script that
asks the game's own chunk generator and biome source, it says so; where it came from a photograph taken by a real client, it says that; and where something is only a plan, it says that too.

## What the first human playtest found

The owner's first playtest of the seed-5908 world found that Ironhaven, Valewood and Blackwater "appeared almost merged together, with nearly everything surrounded by snow". The earlier
documentation had measured *geometry* (circle radii, edge gaps) and *whether blocks exist*, never what the land and the weather between the cities look like. Reproduced on the game's own generator
(`scripts/run_terrain_map.py` on a fresh server world, `scripts/render_terrain_map.py` to read it; 42,813 columns, 16-block grid, seed 5908, vanilla Normal world type):

| Measurement (legacy seed 5908) | Result |
| --- | --- |
| Land that is snowy or frozen biomes, within 260 blocks of Ironhaven's circle | **44.3 %** (taiga 35 %, snowy taiga 30 %, snowy plains 7 %, frozen river 7 %) |
| Water | 41 % of the surveyed area; the three cities share **one connected landmass** with rivers and lakes, not separate islands |
| City circles | 600, 410 and 570 blocks of radius: a skyline of about 1,100 blocks across for Ironhaven alone |
| Where the spawn was | the middle of that snowy landmass, a few hundred blocks from Ironhaven's centre |

The map: `docs/images/terrain-maps/legacy-5908.png` (white circles are the Lost Cities circles; white and pale blue is snow and ice; the gold lines are the planned roads).

## The decision (D-104)

Three real landmasses, separated by water, joined by three bridges, each with its own landscape, with the climate held out of snow and desert:

| | Ironhaven | Valewood | Blackwater |
| --- | --- | --- | --- |
| City circle (Lost Cities) | 330 blocks | 240 blocks | 300 blocks |
| Countryside kept around it | 230 blocks | 330 blocks | 200 blocks |
| Island radius | 560 | 570 | 500 (a harbour bay notched into its coast) |
| Character | industrial: a dense grey skyline of factories, warehouses and working-class blocks; the arrival landing | green: a low-rise market town in farmland and woods; the warmest and gentlest | port: a tall glassy commercial core around a harbour bay, in dark woods |
| Biomes (climate numbers) | plains and forest (temperature 0.02, humidity -0.10) | forest and birch forest (0.07, 0.05) | dark forest (0.05, 0.46) |

Distances (planner output, `scripts/plan_archipelago.py`): between city circles 960 to 1,046 blocks by road (3.8 to 4.1 minutes at 4.317 blocks per second), between city centres 1,562 to 1,616 blocks
(about 6.1 to 6.3 minutes), inside the accepted 3-5 and 6-8 minute aims and the ten-minute hard limit. Each strait between two islands is 300 to 340 blocks of open water between the tips of two capes; a road
runs along a cape on each island and crosses by a bridge whose deck stays four free blocks above the water (the road feature of D-047, unchanged).

## How the terrain is made

Lost Cities places cities as a *feature* on whatever terrain the world's generator produced (it is not a chunk generator of its own; measured by reading the jar: its feature runs in the `raw_generation`
step), so the land can be shaped without touching Lost Cities. Minecraft 1.20.1 builds the overworld from a few noise *density functions*: continentalness (land or sea), erosion (how flat), a
peaks-and-valleys "ridge" value (how high, inside the middle bands) and temperature and humidity (which biome). The splines that turn them into height refer to those functions by name. So:

1. `IslandPlan` (pure arithmetic, no Minecraft types) describes the world as data: discs for islands, capsules for capes, a carved bay, plateaus under the cities, an arrival site, one climate per island.
   It is generated from `content/region-blueprints/archipelago/` by `scripts/plan_archipelago.py` (`--check` in CI), so the geometry cannot drift from its stated numbers and no coordinate lives in Java.
   The shore is moved by a fixed-seed noise: every new world has the same three islands (the world seed only changes the hills, rivers and trees on them).
2. `IslandField` is a density function type, `underground_city:island`, with a channel each for continents, erosion, ridges, temperature and humidity. Each one modifies the *vanilla* function it names
   (referenced by name, never copied): land and sea from the plan with a little vanilla noise for natural coasts; gentle erosion everywhere and dead flat, at a chosen height, under a city; each island's climate.
3. `RegionalWorldgen` registers the function and a data pack made **at load time** from the game's own overworld functions: it reads the vanilla `offset`, `factor`, `jaggedness`, `depth`, `sloped_cheese`,
   `ridges_folded` and the overworld noise settings through the game's vanilla pack and serves copies whose references point at `underground_city:regional/*`. About 110 KB of Mojang's data is therefore
   **not** in this repository, and the result follows whatever the game's own functions are. The noise settings are `underground_city:regional`; the world type is `underground_city:archipelago`
   (World Type list at world creation, and `level-type=underground_city:archipelago` for a server). An existing world never reads any of it.

A first version of the pack listed directories by string prefix, so `worldgen/noise` also matched `worldgen/noise_settings`, the game tried to parse the noise settings as noise parameters and **no world
loaded at all, legacy ones included**. It was found by the first survey server and fixed (a directory is `path + "/"`); the regression test is `RegionalWorldgenGameTests`.

## Two layouts, one launch, and the guard (the old world is preserved)

`WorldLayout` knows which layout the running game carries. The legacy layout (the seed-5908 triangle, world package `underground_city_world`, byte-identical to before: `emit_dev_world_pack.py --check`) and the
archipelago (world package `underground_city_archipelago`, `content/generated/world-archipelago/`) cannot share a launch, because Lost Cities applies every predefined city it is given to every world.
A plain `gradlew runClient` or `runServer` now carries the **archipelago** and opens or creates the dev world `UC-Archipelago`; `-PucWorldLayout=legacy` carries the legacy layout and the saved dev world
`UC-Dev-5908` exactly as before; every scripted run (playtest, launch smoke, CI harness) keeps the legacy layout unless it asks otherwise. If a launch and a world disagree (an old world opened by the
archipelago package, or an archipelago world without its package) the game **refuses to start before a chunk is read** and says which launch to use, in the way of the Traveler's guard (D-103);
`-Duc.layoutGuard=off` exists only for disposable survey servers. Nothing is ever migrated: the legacy world keeps its own cities, roads and chunks.

## Evidence

Every number below was read from the game's own generator, the game's own Lost Cities plan or real generated blocks, in Claude's cloud container, by a script that is in the repository. **Nobody has walked this world.**

### Terrain and biomes: three seeds, and the old layout beside them

`scripts/run_terrain_map.py` samples the world's chunk generator and biome source on a 16-block grid (42,813 columns) in a brand-new disposable world; `scripts/render_terrain_map.py` draws it (white circles are the Lost Cities circles, gold lines the planned roads; the pictures are plots of the survey, not game screenshots).

| Layout and seed | Water | Snowy or frozen land | Ironhaven's island | Valewood's island | Blackwater's island |
| --- | --- | --- | --- | --- | --- |
| legacy, seed 5908 (the owner's first playtest world) | 41 % | **44.3 % within 260 blocks of Ironhaven**; 16.1 % of all land | one landmass with the others, taiga 35 %, snowy taiga 30 % | forest and birch, no snow | forest, plains, taiga |
| archipelago, seed 5908 | 78 % | **0.0 %** | plains 64 %, forest 35 % | forest 62 %, plains 37 % | plains 54 %, dark forest 34 %, forest and birch 10 % |
| archipelago, seed 20261020 | 77 % | **0.0 %** | plains 73 %, forest 25 % | forest 61 %, plains 39 % | plains 53 %, dark forest 36 %, forest and birch 10 % |
| archipelago, seed 777 | 79 % | **0.0 %** | plains 77 %, forest 22 % | forest 61 %, plains 39 % | plains 54 %, dark forest 35 %, forest and birch 10 % |

Pictures: [legacy-5908](../images/terrain-maps/legacy-5908.png), [archipelago-5908](../images/terrain-maps/archipelago-5908.png), [archipelago-20261020](../images/terrain-maps/archipelago-20261020.png), [archipelago-777](../images/terrain-maps/archipelago-777.png).

What this says and what it does not:

* The shape of the world does not depend on the seed (the plan is fixed; the seed moves hills, rivers and trees), and the three seeds read the same: no snow, three separate islands, 77 to 79 percent water.
* The three islands differ in their **countryside**, not dramatically: Ironhaven is the plainest, Valewood the greenest, Blackwater has the dark forest. The ground under and for about 20 blocks around every city is held at plains (so that no forest grows over streets), and the island's own climate takes over beyond that (`IslandField.HUMIDITY_SHRINK`). The first version held the whole island at plains (Valewood 49 percent forest, Blackwater 18 percent dark forest); the change above roughly doubled the dark forest and raised Valewood's forest to 61 percent. **Blackwater has no swamp**: the early swamp shore came from a low-erosion band the flat calibration removed, and it is not restored. Say "three islands with their own woods", not "three biomes".
* The cities are three Lost Cities layouts from the same building set. In the October 21 frames (a private page linked from the pull request) they read as one town three times: Valewood is not a green low-rise town and Blackwater shows no harbour. Distinct looks per city are not built; the registration cards promise more than the world shows.
* The ground inside each city circle is at Lost Cities' level 71 (median 71 to 72 on every seed).

### The countryside is not flat: the landing had to be levelled (found on a fresh world)

D-107 said Founders Row would be built at the arrival landing of a new archipelago world. The first fresh-world run of this overhaul found two defects in that claim, neither visible to the unit tests:

1. **The site search never finished.** `DistrictSites.find` evaluated every candidate site in a 160-block radius before choosing one; with the archipelago's flat plateau every candidate fits, each is a few hundred columns of the chunk generator's noise, and the server held its first start for more than nine minutes. It is now nearest-first with an exact early exit (a candidate whose distance already exceeds the best cost found cannot win), a per-column cache and an 8-block sampling grid, and for the landing the relief weight is zero (the ground is going to be graded, so the nearest dry site that is not too steep wins and the search stops at the first lot): the same answer as an exhaustive search at weight 0 (a test compares them), in 4 to 6 seconds on this machine. The search runs in the server-started event, after the 60 second watchdog has begun, so a slow runner had killed the first start once at a 58 second search (CI "runServer boots the complete pack"); that is why the cost, not the event, was changed (D-096 forbids disabling the watchdog).
2. **There was no level site to find.** The survey of the landing (`terrain/landing`, 8-block grid around the anchor at -361, -223) shows rolling plains: 70 to 85 blocks high, relief of 10 to 15 across any 100 blocks. A plateau in the island plan holds the terrain functions still, but only Lost Cities' own levelling makes ground flat, and it stops at a city's edge. Founders Row (33 x 49 blocks) with the Row Flats, the Row Motel and the firing range beside it (127 blocks wide in all) was **silently not built**: `no level, dry ground within 160 blocks of the spawn point`.

`LandingGrader` levels the whole footprint once, when a new archipelago world is made: it picks the flattest ground near the anchor, sets one level (the median) inside the footprint, eases back to the natural ground over a 28-block ramp (a smoothstep, at most two blocks of rise per block of run, tested), clears trees and plants on it and leaves any column with water in it as the generator made it. Measured on a fresh seed-5908 world (final build): site found at -392, -315, relief 18, 72 blocks from the anchor at -361, -223; 20,622 columns, 77,392 blocks changed, at most 11 cut and 16 filled, 57 water columns left alone. Levelling took **288 ms on a dedicated server** (its start region already holds the chunks) and **33.8 s in a single-player client world** (the footprint's chunks are generated by the grader; 32 to 37 s in twelve runs). Then the range, the Row, the flats and the motel were built and the spawn moved to the gate (log lines `[UC-DEV] landing levelled ...` and `Founders Row built at -352, 77, -315`, gate -336, 77, -264). Frame c03 of the photographs shows the 28-block ramp as visible terraces: it is levelled, not natural. It never runs in an existing world (game time above zero) and never in the legacy layout.

### Cities: structures, holes and vegetation (Lost Cities plan read before generation, blocks read after)

`scripts/run_city_probe.py` asks Lost Cities what each chunk is before it is generated, generates it and reads the blocks (507 chunks around the three city centres per run).

| What | Before | After |
| --- | --- | --- |
| Chunks the plan says are city and that generated as city | 413 of 507 (the others were holes where a village, igloo, ruined portal or mansion start sat inside a city) | **507 of 507**, plan equals result in every chunk (a mixin on `ChunkGenerator.createStructures` generates no structure start within a city's circle plus 160 blocks, on this terrain only) |
| Log blocks over streets, roofs and buildings | 14,806 (first survey: forest humidity over the whole plateau) | 45 |
| Chunks with a tree on a structure | not counted | 2 of 507 |
| Leaf blocks | 82,283 (first survey) | 3,962 (hedges and park borders Lost Cities draws by design) |
| Grass, fern and mushroom blocks | not counted | 4,227 (about 8 per chunk, almost all lawn in parks); not removed |

(The "before" column is two surveys: the vegetation figures are the first, the chunk counts the second, taken after the vegetation fix and before the structure exclusion.)

The two chunks with a tree are one `building4` chunk and one `center11` chunk; the cause was not traced. "No trees or vegetation on buildings or roads" is **nearly** true, not true. Not measured: a ring of chunks at the edge of each city after the climate change (the probe above samples six chunks around each centre).

### Roads and bridges (block by block)

`scripts/run_road_check.py` (new; the world-integration job only ever surveyed the legacy prototype) forces the 354 chunks along the three corridors to generate and reads the real blocks along each centreline every block: deck present, three free blocks of headroom, no step above half a block, a parapet on both sides of every bridge, water with free blocks under the deck, growth over the carriageway.

| Route | Length | Bridge share | Longest bridge | Open water under the deck | Deck missing | Headroom blocked | Steps over half a block | Vegetation over the road | Parapets missing |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Ironhaven to Valewood | 1,069 | 387 samples | 236 | 224 blocks | 0 | 0 | 0 | 0 | 0 of 749 |
| Ironhaven to Blackwater | 984 | 364 | 296 | 289 | 0 | 0 | 0 | 0 | 0 of 673 |
| Valewood to Blackwater | 1,046 | 366 | 167 | 158 | 0 | 0 | 0 | 0 | 0 of 668 |

All three routes are continuous (`continuous: true`), on both the plains-only and the retuned climate (the same numbers; the corridor builder clears its own band). At 4.317 blocks per second the routes are 3.8 to 4.1 minutes on foot. **No person has driven, ridden or boated them**, and boats under the bridges (three free blocks over the water) are not tested.

### Where the roads meet the cities (D-137, October 23)

**Found by a scripted real client, measured, then fixed.** The journey's player walked the Ironhaven–Valewood road and its bridges with the W key and reached the road's end at Valewood's circle facing a two-block bank of earth: a dead end on foot (screenshot `uc-journey-26-valewood-arrival`, run 31). The road check above reads only the open road, so it never saw it. It now also reads each end: the height a person would stand at along the road's line, from four blocks out on the deck until the city's own built ground (or 72 blocks in), and the largest step between neighbouring columns (`ends` in the report; a step up of more than one block cannot be walked).

**The fix (`RoadApproaches`).** Once per end, when somebody is within 160 blocks of it and every chunk the ramp touches has been generated (see below), the road's line is read on for up to 64 blocks into the city: the deck's height at the end and the ground along the centre until the first thing the city built (a street, a wall, a building's lot). A straight ramp is laid from the deck to that height, from the road's last column (where the first measurement found a four-block hole), seven blocks wide with a path down the middle: natural ground (earth, stone, sand, gravel, clay, snow, plants, leaves, logs) above the ramp is cut away and a dip below it is filled with earth. Anything the city built is never touched, nor any column of a building's lot (garden included); a column with something built (or water) in the way is left as it is and counted. It is built only in a world that a new archipelago start enabled, never in an existing world, and each end is remembered in the world's saved data once built.

Measured by the road check in a new world (seed 5908, `world,furnished,scarcity`), along each road's line from four blocks out on the deck until the city's own built ground (a street, a wall, a building's lot) or 72 blocks in; "walkable" means no step up of more than one block. The same world, before and after its ramps were built:

| Road end | Deck (stand) | Before: steps up / down | Ramp built | After: steps up / down | What is there |
| --- | --- | --- | --- | --- | --- |
| Ironhaven–Valewood, Ironhaven end | 74 | 3 / 4, **no** | 64 blocks, 73 to 72 | 0 / 1, yes | a four-block drop off the deck into a ditch, then a bank up to the city |
| Ironhaven–Valewood, Valewood end | 73 | 3 / 2, **no** | 55 blocks, 72 to 78 | 1 / 0, yes | a bank where the city begins, 55 blocks in (the journey's own world had a two-block bank at the end) |
| Ironhaven–Blackwater, Ironhaven end | 71 | 1 / 1, yes | 64 blocks, level | 1 / 0, yes | level ground |
| Ironhaven–Blackwater, Blackwater end | 72 | 8 / 5, **no** | 64 blocks, 71 to 72 | 6 / 0, **no** | an eight-block pit 17 blocks in, filled; 65 blocks in, just past the ramp, the line meets an office's raised terrace, six blocks up its retaining wall |
| Valewood–Blackwater, Valewood end | 69 | 7 / 3, **no** | 48 blocks, 68 to 78 | 1 / 0, yes | a seven-block bank right at the edge |
| Valewood–Blackwater, Blackwater end | 71 | 2 / 1, **no** | 64 blocks, level | 2 / 0, **no** | a two-block step 68 blocks in, where the city's ground begins |

**1 of 6 ends walkable before, 4 of 6 after.** The two that are not end in open ground inside the city circle; along the road's straight line the next thing is a building's lot (the office terrace) or a two-block step, both more than 64 blocks in, and a person walks round them on open ground. A longer ramp would only lead onto a building's terrace, so it was not made longer. Every ramp's steepest step is one block; 287 to 1,554 blocks were changed per end, and the columns left alone (4 to 9 per end) are the road's own deck at the end column. A building's lot is never changed (the ramp stops at it; `RoadApproaches.cityOwned`).

**Walked by a scripted real client.** Journey runs 36 and 37 walked up the ramp at the Valewood end of the Ironhaven road to a street door with no teleport (run 37: 19 s), and development run 40 walked from Founders Row through Ironhaven to the ramp at the Ironhaven end and onto the road (721 blocks, 433 s, no teleport). The road check measures the line only; no person has walked any of them.

**The ramps stalled the server, and no longer generate anything themselves (October 23).** Laid the first time somebody came within 96 blocks, a ramp read and changed city chunks that a player arriving along the road had not loaded yet, and asked for them on the server thread: the journey runs' logs show a stall of 4 to 43 seconds within seconds of each ramp, the Valewood ones worst. A ramp now waits until every chunk it touches is loaded; a player within 160 blocks keeps a ticket on them so the world generates them on its workers, as it does for any player. Measured in [lost-cities-stalls.md](lost-cities-stalls.md#the-road-end-ramps-october-23).

### Roadside stops (the existing wayside shelters, run on the new corridors)

`WaysideShelters` places a shelter or a well every 300 to 450 blocks of open road, when a player comes near; it had only ever been run on the legacy roads. `RoadCheck` now serves every planned stop of the three corridors as a player's approach would (the same `serve` the level tick runs, in a disposable world, after the chunks around each stop are generated):

| Stop | Kind | Result |
| --- | --- | --- |
| Ironhaven to Valewood, stop 0 (680, 102) | shelter | built at 672, 70, 112 |
| Ironhaven to Valewood, stop 1 (1025, 150) | well | built at 1021, 70, 130 |
| Ironhaven to Blackwater, stop 0 (271, 605) | well | built at 257, 69, 621 |
| Ironhaven to Blackwater, stop 1 (399, 897) | shelter | **given up**: no level, dry, unbuilt ground within 64 blocks |
| Valewood to Blackwater, stop 0 (1252, 689) | shelter | built at 1244, 63, 662 |
| Valewood to Blackwater, stop 1 (1043, 957) | well | **given up**: no level, dry, unbuilt ground within 64 blocks |

Four of six stops were built, at least one on every corridor; each build took 9 to 30 ms once its chunks existed. The two that were given up lie on or beside the water near the Blackwater end, where the bridges run; the code reports them as given up and does not retry them by itself. **There are no ports, marinas or harbour landings**: nothing in this version stands at a bridge's shore end, and a boat has nowhere to be bought or tied up. A person has not walked to any of these stops, and the warden at each was not talked to.

### Starting a new world, and keeping the old one

* A plain `gradlew runClient` or `runServer` now carries the archipelago and creates the dev world `UC-Archipelago`; a dedicated server uses `level-type=underground_city:archipelago` in `server.properties` (the world type is also in the World Type list of the Create World screen). The legacy dev world `UC-Dev-5908` is untouched and opened with `-PucWorldLayout=legacy`. The two cannot share a world: a mismatch stops the game before a chunk is read (D-104).
* Nothing migrates an old world and no generation change is applied to a save. The first start of a new archipelago world takes about two minutes on the cloud container (spawn preparation 60 s, the landing's chunks and levelling about a minute).
