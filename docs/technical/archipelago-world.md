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

The map: `docs/images/world/legacy-5908.png` (white circles are the Lost Cities circles; white and pale blue is snow and ice; the gold lines are the planned roads).

## The decision (D-104)

Three real landmasses, separated by water, joined by three bridges, each with its own landscape, with the climate held out of snow and desert:

| | Ironhaven | Valewood | Blackwater |
| --- | --- | --- | --- |
| City circle (Lost Cities) | 330 blocks | 240 blocks | 300 blocks |
| Countryside kept around it | 230 blocks | 330 blocks | 200 blocks |
| Island radius | 560 | 570 | 500 (a harbour bay notched into its coast) |
| Character | industrial: a dense grey skyline of factories, warehouses and working-class blocks; the arrival landing | green: a low-rise market town in farmland and woods; the warmest and gentlest | port: a tall glassy commercial core around a harbour; dark, wet woods and a swampy shore |
| Biomes (climate numbers) | plains and forest (temperature 0.02, humidity -0.10) | forest and birch forest (0.07, 0.05) | dark forest and swamp (0.05, 0.46) |

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

(Terrain surveys, the calibration of the flat ground under the cities, the real client frames and what they showed are in the sections below as they were taken.)
