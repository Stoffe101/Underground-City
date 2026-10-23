# Sprint report, October 23, 2026 (second pass): living world, roadside exploration, SecurityCraft and gameplay expansion

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Living World, Roadside Exploration, SecurityCraft & Gameplay Expansion". Decisions D-138 to D-141 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, a Python model, the game's own generator (the road check, a dedicated server in a disposable world) and scripted real clients (software-rendered, in a virtual display, driven by synthetic key and mouse events). "Passed" means a script passed. Where a step was set up by a command or by reading the world instead of by input, it says so.

## What changed for a player

* **The roads are no longer empty (D-138).** Six original roadside places, two per road, each with a shopkeeper and something to do: the Crossroads Diner, Harrow Farm (its farmhouse is for sale or rent), Kessler Salvage (scrap heaps that refill in active time), Northgate Depot, the Saltmarsh Motel (four rooms to rent) and the Old Cannery (a hidden den that sells lockpicks and a bypass kit). New archipelago worlds only; built the first time a player comes near. See [roadside.md](../technical/roadside.md).
* **Things happen on the road (D-139).** A stranded driver, a courier whose letter pays in the city ahead, goods off a lorry (stolen, sold cheap, risky), a spilled load. One or two to a thirteen-minute crossing, beside the road, declinable, settled once.
* **SecurityCraft, trialled under Core's authority (D-140).** Opt-in, not adopted: see [securitycraft-trial.md](../technical/securitycraft-trial.md).
* **Furnished buildings (D-141):** beds that render, tills that are tills, a stocked corner store.
* **Roads:** a reopened world keeps its road plan (bridges, water, deck heights) for everything that needs it; the wayside shelters and waymarkers never read a chunk that is still generating; a road-end ramp looks up to 112 blocks in for the city's built ground.

<!-- results:begin -->
## Results

### Checks

| Check | Result |
| --- | --- |
| Forge GameTests (the economy profile, every test) | **All 342 required tests passed** (run 68). Runs 64 to 67 failed one or two: the ramp test's chunk bound (raised with the ramp's new reach, below) and a witness test whose "in plain sight" street had blocks left on it by an earlier batch's test at the same grid slot (GameTest clears only three blocks round a one-block template); the test now clears its own street. |
| Python suite (`python -m unittest discover -s tests`) | **316 tests, OK** (the pinned-jar scan now lists SecurityCraft's nether-star recipes as allowed, since the trial removes every SecurityCraft recipe) |
| `python scripts/validate_repo.py` | passed |
| SecurityCraft trial, real client (`-PucPlaytest=security`, run 11) | **`SECURITY PASS`, `RESULT pass`**; runs 1 to 10 and what each found are in [securitycraft-trial.md](../technical/securitycraft-trial.md#results) |
| Road check, a brand-new archipelago world (default seed, `world,furnished,scarcity`) | **6 of 6 roadside places built**, each on its first candidate lot, 87 to 448 ms each ([roadside.md](../technical/roadside.md)); 4 of 6 wayside shelters (two found no level, dry ground, as before); **6 of 6 road ends walkable** once their ramps were laid, no step up of more than one block and 0 pits ([archipelago-world.md](../technical/archipelago-world.md#where-the-roads-meet-the-cities-d-137-october-23)). The fresh pass ran out of memory in the road check's own path search at the last road end (a test tool, on a server generating every chunk of three islands); the world was reopened to finish, which is when the ramps were laid. |

### Photographs (photo tours 24 and 25, a new world each, 1920 x 1080, looked at)

| Frame | What it shows |
| --- | --- |
| Crossroads Diner | the diner (white walls, a red band, booths in the windows), the forecourt's canopy and pump, an asphalt car park with white lines, in a forest clearing on graded ground |
| Harrow Farm | the farmhouse with its porch and chimney, fenced wheat and crop fields, the farm stand under a striped awning, a worn path; a natural lava pool about ten blocks off the lot's west side (the land's, not ours) |
| Kessler Salvage | a fenced yard on a stone footing with the yellow crane and its chain, wrecks, scrap heaps, the workbench shelter and the office; its front stands on a two-block stone edge where the ground falls away |
| Northgate Depot | the grey warehouse with its loading doors, an asphalt forecourt, the dispatch board, a van |
| Saltmarsh Motel | the row of rooms under a porch, the office, a striped car park, and an asphalt driveway stepping down to the road |
| Old Cannery | the roofless brick ruin in a dark forest with a path to it; a giant red mushroom stood in its lot (giant mushrooms now count as natural growth, so a lot's levelling clears them; not re-photographed) |
| Tenement bedroom, from above and close | the bed drawn: a red blanket and a white pillow (D-141); the low frame's "grey bed" was the alarm clock on the nightstand ([furnished-cities.md](../technical/furnished-cities.md)) |
| Bakery counter | cakes and the baker's things on the counter, no monitor (D-141) |
| Blackwater corner store | the till with a receipt printer, a mug and a plant; the aisles with stocked shelves, crates and a produce stand (D-141) |

The first tour's diner frame was lost to a fault of the tour (its stage clock started when the class loaded, minutes before the world existed); fixed, and the diner was photographed in the second tour.

<!-- journey:begin -->
<!-- journey:end -->

### What a person still has to judge

* Whether the six places look like they belong where they stand, and whether stopping at one is worth it.
* Whether an encounter every five minutes or so of road travel feels like a living road or an interruption, and whether the offers are worth stopping for.
* Whether SecurityCraft's keypad, laser and siren feel like part of the game (the trial is not adopted).
* Nobody has played any of it; no second player has met an encounter or rented a motel room beside a partner.
<!-- results:end -->
