# City ground: what may write into a finished city chunk (D-196)

**Status (October 27, 2026): implemented and measured in newly generated disposable worlds; not played by a person.** It changes only chunks generated after the change. No existing world is rewritten, and the owner's `UC-Alpha-Test-01` was not opened.

## What was wrong

The owner reported grass and dirt blocks lying on the paved streets, and dirt and ore blobs in the floors of buildings. A traced probe (`-PucCpTrace`, `GenerationTrace`) followed every block that changed in a city chunk after Lost Cities had finished it, together with the feature that wrote it. It found five separate causes:

1. **Lost Cities' own levelling was one block short.** `LostCityTerrainFeature.doCityChunk` levels each street column with `moveDown(x, z, cityGround + 1, ...)`. That call returns "nothing to do" when the first solid block is at or below `cityGround + 1`. So a grass block standing exactly one above the street was left on the paving: 1,603 cells in Blackwater, 968 in Ironhaven and 1,520 in Valewood in one probe.
2. **Later decoration steps wrote into finished city chunks.** Lost Cities builds a city in the first decoration step (`raw_generation`). Ores, springs, vegetation and the top-layer steps all run afterwards, and a feature may write anywhere in its 3×3 chunk region. The ore features put dirt, gravel, granite, diorite and andesite blobs into foundations and ground floors (77 to 153 cells a city). Tree, grass, mushroom and flower patches put leaves, mushrooms and tufts onto the paving.
3. **Plants from a neighbour that generated first.** A neighbouring chunk decorated before Lost Cities built this one could leave a flower on what then became paving.
4. **Bare verges.** A street part's `b` character (structure void) keeps the terrain. Where the terrain was rock or dirt, the street had bare stone or dirt edges: 258 to 567 cells a city.
5. **The corridor roads** missed diagonal neighbours when repairing the deck, and their approaches into the cities were bare earth or dirt path.

## What Core does now

Core does not run any pass over finished chunks afterwards. Each rule acts at the step that caused the problem:

- **The levelling is corrected inside Lost Cities' own step** (`LostCitiesTerrainMixin`, `LostCitiesTerrainFix`). When `moveDown` answers "nothing to do" for a city column whose top is a solid block at street level + 1, that block is moved down onto the city ground and the column above is cleared. Foliage left above the street is also cleared. This runs only while Lost Cities is building a city chunk on the archipelago (`CityGround.decoratingRegional`).
- **The city ground guard** (`CityGround`; mixins on `ChunkGenerator.applyBiomeDecoration`, `PlacedFeature.placeWithContext`, `WorldGenRegion.setBlock` and `OreFeature.canPlaceOre`). While any later decoration feature runs, a write into a recorded city chunk inside the city envelope is refused. The envelope reaches from three blocks below the deepest cellar (street − 6 × cellars − 3) up to the sky. Only the outermost placed feature decides; Lost Cities' own feature and Core's own are exempt. Two kinds of write are let through:
  - **Garden plants:** a `BushBlock` on soil (not a mushroom, sapling or berry bush), so lawns and parks still get their flowers and tufts.
  - **Park trees:** leaves and logs grown into air above the street over soil, in a chunk with no building.

  Ore cells are refused in `canPlaceOre`, so an ore vein never starts inside a city.
- **Verges.** When Lost Cities' feature returns, the bare ground at the street layer of each street chunk is grassed (`finishVerges`).
- **Roads.** The corridor deck repair counts diagonal neighbours (`CorridorRoadFeature.NEIGHBOURS = 22.7`). The approaches are laid in polished andesite where they used to be earth, so the walkability check continues over the paving (`RoadApproaches.paving`, `RoadCheck`).

For a measurement, `-Duc.cityGround=off` (Gradle `-PucCpGuard=off`) switches the guard off on a disposable server.

## Evidence

Each probe below was run in a **new disposable world** with the public furnished pack, one seed, radius 10 around each city. Surface maps for each city are written by `scripts/city_surface_map.py` (`--out`), from the probe's per-cell street layer.

| | Guard off (t10-off) | Guard on (t10-final) |
| --- | --- | --- |
| Terrain blocks on the paving (Blackwater / Ironhaven / Valewood) | 1,603 / 968 / 1,520 | **0 / 0 / 0** |
| Vegetation on the paving | 167 / 132 / 187 | 0 / 0 / 1 (an oak leaf four above the street in Valewood) |
| Bare rock or dirt verges | 258 / 326 / 567 | **0 / 0 / 0** |
| Holes in the paving | 0 / 0 / 0 | 0 / 0 / 0 |
| Dirt, gravel or stone variants written into foundations, ground floors or buildings after Lost Cities | 77 / 153 / 103 | **0 / 0 / 0** |
| Guard counters | — | 2,143 writes and 11,412 ore cells refused; 12,900 garden plants let through; 17,631 street columns lowered onto the city ground |

What the guard still lets into the cities: grass, tufts and flowers on the verges, lawns and gardens (624 to 791 cells a city), park trees, and 7 red mushrooms on a square in Ironhaven (on soil, which the guard allows).

**Roads.** The road check (`run_road_check.py`, new world, run t10b) covered 7,320, 6,718 and 7,153 carriageway cells on the three roads. **None was anything but clear deck.** Nothing was missing, no headroom was blocked, there were no steps over half a block and no missing parapets, and **all six road ends are walkable** onto the city ground. Three leaf blocks of roadside trees hang 4 to 6 blocks over the deck, above the three blocks of headroom. They were left, and are recorded in the check's `vegetation_over_road`.

**The owner's private buildings** were tested in a private disposable world only; nothing of them is in the repository. With the guard on, the C70-first probe refused 2,993 writes and 53,817 ore cells. The private building audit compared 2,424,044 cells of 97 building types (86 of them C70) and found 218 missing blocks (0.009%): dirt, grass, flowers and saplings, none of them building fabric.

**GameTests:** `CityGroundGameTests` checks that a finished city chunk keeps its blocks and takes only garden plants (park trees included), and that the guard's hooks are in the game. `tests/test_city_surface_map.py` covers the audit (6 tests).

## Limits

- Only chunks generated after this change are clean. Repairing an existing world is not offered. If it ever is, it must be an explicit, backed-up, opt-in operation that leaves player changes alone (the owner's rule).
- The guard trusts Lost Cities' record of which chunks are city chunks (`LostCitiesLink`). A chunk Lost Cities did not record is not protected.
- A mod that writes blocks without going through `WorldGenRegion.setBlock` is not covered until measured.
- A dungeon (monster room) refused inside a city logs "Failed to fetch mob spawner entity". That message is harmless: the room was not built.
