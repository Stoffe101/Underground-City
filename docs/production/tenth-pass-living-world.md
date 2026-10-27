# Tenth pass: living world, C70 cities, gameplay expansion and world quality (October 27, 2026)

Owner brief: "Underground City: Tenth Pass — Living World, C70 City Overhaul, Gameplay Expansion & World Quality". Decisions D-196 to D-204. Branch `feature/tenth-pass-living-world`, a stacked draft PR on `feature/world-playability-overhaul` (PR #25 is not merged or changed).

**The current branch and its recent gameplay systems have not received human playtesting.** Every result below comes from scripts, Forge GameTests, the Python suite, generator probes of new disposable worlds, a dedicated server or scripted real clients. None of it was played by a person. Each scripted run logs every shortcut it takes, marked `SCRIPTED` or `PREPARED`. The owner's own world `UC-Alpha-Test-01` was never opened or changed. No C70 asset, converted structure, private selection, private screenshot or private world is in the repository, the PR or any CI artifact. The owner's buildings were ported, wired, probed and photographed in private folders outside the checkout only.

## 1. Roads and city ground: what caused the blocks, and the fix (D-196)

**What the owner saw:** grass and dirt blocks on paved streets, mushrooms and plants on the paving, and dirt, gravel, granite, diorite and andesite blobs in building floors.

**What caused it** (found with a traced probe that follows every block written into a city chunk after Lost Cities finished it, with the feature that wrote it):
1. **Lost Cities 7.5.5 levels a street one block short.** `doCityChunk` calls `moveDown(x, z, cityGround + 1, ...)`. That call answers "nothing to do" when the first solid block is at `cityGround + 1`, so a grass block standing exactly one above the street stayed on the paving: 1,603 / 968 / 1,520 cells in Blackwater, Ironhaven and Valewood in one probe.
2. **Every later decoration step may write into a finished city chunk.** Lost Cities builds a city in the first decoration step; ores, springs, plants and trees run after it and may write anywhere in their 3×3 chunk region. The ore features made the blobs in floors (77 / 153 / 103 cells); grass, tree and mushroom patches put plants and leaves on the paving.
3. **A neighbour decorated before the city was built** could leave a flower on what then became paving.
4. **Bare verges:** a street part's structure-void character keeps whatever terrain was there, so streets had raw stone and dirt edges (258 / 326 / 567 cells).
5. **The corridor roads' deck repair** ignored diagonal neighbours, and the road ends into the cities were bare earth.

**The fix is at the generation step that caused each problem; no pass is run over finished chunks afterwards.** Core corrects the levelling inside Lost Cities' own step. While a later feature runs, Core refuses its writes into a recorded city chunk from three blocks under its deepest cellar up to the sky. Two kinds are let through: garden plants on soil, and park trees over soil in a chunk with no building. Ore never starts there. Verges are grassed as Lost Cities' feature returns, the corridor deck counts diagonal neighbours, and the approaches are paved in polished andesite. Only chunks generated afterwards change. Details and every number: [city-ground.md](../technical/city-ground.md).

**Results** (new disposable worlds, the same seed, radius 10 round each city, guard off and then on):

| | Off | On |
| --- | --- | --- |
| Terrain on the paving (Blackwater / Ironhaven / Valewood) | 1,603 / 968 / 1,520 | **0 / 0 / 0** |
| Vegetation on the paving | 167 / 132 / 187 | 0 / 0 / 1 (an oak leaf four blocks over a Valewood street) |
| Bare verges | 258 / 326 / 567 | **0 / 0 / 0** |
| Stone and dirt blobs written into buildings | 77 / 153 / 103 | **0 / 0 / 0** |

**The intercity roads** (road check t10b, a new world) covered 21,191 carriageway cells on the three roads, and **every one was clear deck**. There was no missing deck, no blocked headroom, no step over half a block and no missing parapet. **All six road ends are walkable onto the city ground.** Three leaf blocks of roadside trees hang 4 to 6 blocks over the deck, above the three blocks of headroom; they were left.

**The acceptance in the brief** was "no unexplained terrain blocks obstructing or replacing paved road surfaces in the tested generated-world coverage". **It holds for the coverage tested here:** three cities at radius 10 and the three roads, in public and private worlds.

## 2. The owner's buildings (C70), residential first (D-198)

*(Sections 2 onwards are completed below as the runs finish.)*
