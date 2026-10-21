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
* The cities' own look (Lost Cities styles: Ironhaven's industrial blocks, Blackwater's glass core, Valewood's low town) is what makes them distinct from one another in a photograph; see the frames below.
* The ground inside each city circle is at Lost Cities' level 71 (median 71 to 72 on every seed).

### The countryside is not flat: the landing had to be levelled (found on a fresh world)

D-107 said Founders Row would be built at the arrival landing of a new archipelago world. The first fresh-world run of this overhaul found two defects in that claim, neither visible to the unit tests:

1. **The site search never finished.** `DistrictSites.find` evaluated every candidate site in a 160-block radius before choosing one; with the archipelago's flat plateau every candidate fits, each is a few hundred columns of the chunk generator's noise, and the server held its first start for more than nine minutes. It is now nearest-first with an exact early exit (a candidate whose distance already exceeds the best cost found cannot win) and a per-column cache: the same answer (a test compares it with an exhaustive search), in seconds.
2. **There was no level site to find.** The survey of the landing (`terrain/landing`, 8-block grid around the anchor at -361, -223) shows rolling plains: 70 to 85 blocks high, relief of 10 to 15 across any 100 blocks. A plateau in the island plan holds the terrain functions still, but only Lost Cities' own levelling makes ground flat, and it stops at a city's edge. Founders Row (33 x 49 blocks) with the Row Flats, the Row Motel and the firing range beside it (127 blocks wide in all) was **silently not built**: `no level, dry ground within 160 blocks of the spawn point`.

`LandingGrader` levels the whole footprint once, when a new archipelago world is made: it picks the flattest ground near the anchor, sets one level (the median) inside the footprint, eases back to the natural ground over a 28-block ramp (a smoothstep, at most two blocks of rise per block of run, tested), clears trees and plants on it and leaves any column with water in it as the generator made it. Measured on a fresh seed-5908 world: footprint relief 19 blocks, 20,113 columns, 74,613 blocks changed, at most 11 cut and 21 filled, **302 ms**, then the range, the Row, the flats and the motel were built and the spawn moved to the gate (log lines `[UC-DEV] landing levelled ...` and `Founders Row built at -368, 77, -339`). It never runs in an existing world (game time above zero) and never in the legacy layout.

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
