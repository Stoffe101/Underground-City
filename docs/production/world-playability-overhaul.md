# Sprint report, October 20 to 21, 2026: world, playability and modpack overhaul after the first human playtest

Branch `feature/world-playability-overhaul`, stacked on `feature/economy-property-progression` (draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25), whose base is the PR [#24](https://github.com/Stoffe101/Underground-City/pull/24) branch). **Nothing was merged**, `main` and the other pull requests are untouched, no history was rewritten, and no existing world was opened, migrated or overwritten: every generation, burglary, road and street measurement ran in a brand-new disposable world.

Owner brief: "Underground City: World, Playability and Modpack Overhaul", after the first human playtest. Decisions D-104 to D-113 in [decisions.md](decisions.md) record what was chosen and what is still open.

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, a scripted client in a software-rendered virtual display, or probes of the game's own generator in Claude's cloud container. Where a line says "passed", it means the scripted input produced the observed result in that environment.

## 1. What changed, visibly and mechanically

| Area | What a player or an operator now sees | Decision, document |
| --- | --- | --- |
| **World** | A new world type, `Underground City: Archipelago`: three islands separated by water and joined by three bridges, no snow anywhere (0.0 percent on three seeds; the old world had 44.3 percent snow within 260 blocks of Ironhaven), 77 to 79 percent water. The old layout is untouched and still opens with `-PucWorldLayout=legacy`. | D-104, [archipelago-world.md](../technical/archipelago-world.md) |
| **Cities** | No vanilla village, igloo, ruin or mansion inside a city (507 of 507 probed city chunks generate as planned city, were 413); no free park chests, saplings or vines; logs on streets, roofs and buildings went from 14,806 to 45. | D-106 |
| **Arrival** | A new archipelago world starts at a levelled landing on Ironhaven's island, in front of Founders Row (the range, the Row, the flats and the motel), not at a random vanilla spawn point. The landing is graded once, when the world is made. | D-107, D-111 |
| **Countryside** | Each island keeps its own climate beyond about 20 blocks of its city: forest 61 to 62 percent of Valewood, dark forest 34 to 36 percent of Blackwater, on all three seeds. | D-112 |
| **Buildings** | Walls of Lost Cities buildings cannot be mined, placed against, poured on, pushed or blasted; the exterior door is locked from the street and is picked with a server-side lockpicking game; an opened door shuts again after 90 active seconds; the first entry furnishes a stash (the existing Household Safe); heat rises. | D-105, [building-protection.md](../technical/building-protection.md) |
| **People** | Named people on a roster walk the streets of the three cities by the clock, at most 12 around a player and 40 in all; they talk, run if struck and striking one raises heat. | D-108, [population.md](../technical/population.md) |
| **Status display** | A persistent, configurable stack on the screen (place, clock, wallet, Crew bank, home and next rent, trade level, heat, the accepted order and the next step of the first hours). | D-109, [hud.md](../technical/hud.md) |
| **Civilian counter** | Core's own screen in front of Q Shop's for Core's own shops; the press sends Q Shop's own trade request, so there is one transaction path. | D-110, [civilian-shop.md](../technical/civilian-shop.md) |
| **Trade page** | Rows laid out from non-overlapping spans, scrolling, a status sentence when the bar does not fit, the full item name on hover; `HOME <city>` in the header and where the player is now in the footer. | [visual-qa.md](../technical/visual-qa.md) |
| **First hours** | The guided journey has a "home" step and, after the last step, a direction instead of silence; "buy something" needs a real purchase. | `Journey.java` |
| **Mods** | Four opt-in client helper sets (navigation, performance, qol and the existing farming and survival sets); three mods parked after evidence. | D-113, [dev-pack.md](../technical/dev-pack.md) |
| **Tools** | Terrain map, city probe, road check (with wayside stops), burglary check, photo tour and a scripted arrival run; all write numbers or frames from the game's own generator. | section 8 |

## 2. World layout and biome distribution

Three islands, three bridges, from the game's own chunk generator (42,813 sampled columns per survey, a fixed plan that does not depend on the seed):

| Layout and seed | Water | Snowy or frozen land | Ironhaven's island | Valewood's island | Blackwater's island |
| --- | --- | --- | --- | --- | --- |
| legacy, seed 5908 (the playtest world) | 41 % | **44.3 % within 260 blocks of Ironhaven** | one landmass with the others, taiga 35 %, snowy taiga 30 % | forest and birch | forest, plains, taiga |
| archipelago, seed 5908 | 78 % | **0.0 %** | plains 64 %, forest 35 % | forest 62 %, plains 37 % | plains 54 %, dark forest 34 %, forest and birch 10 % |
| archipelago, seed 20261020 | 77 % | **0.0 %** | plains 73 %, forest 25 % | forest 61 %, plains 39 % | plains 53 %, dark forest 36 %, forest and birch 10 % |
| archipelago, seed 777 | 79 % | **0.0 %** | plains 77 %, forest 22 % | forest 61 %, plains 39 % | plains 54 %, dark forest 35 %, forest and birch 10 % |

The islands differ in **countryside**, not dramatically: say "three islands with their own woods", not "three biomes". **Blackwater has no swamp.** The three cities are what make the islands read apart in a photograph (Ironhaven's industrial blocks, Blackwater's glass core, Valewood's low town).

**Routes** (block by block, `scripts/run_road_check.py`): Ironhaven to Valewood 1,069 blocks (236-block longest bridge), Ironhaven to Blackwater 984 (296), Valewood to Blackwater 1,046 (167). All three are continuous: no missing deck, no blocked headroom, no step over half a block, a parapet on both sides of every bridge span (0 of 2,090 missing), no growth over the carriageway. At 4.317 blocks per second that is **3.8 to 4.1 minutes on foot** between the outskirts, inside the 3 to 5 minutes the owner asked for. Nobody has walked, driven or sailed them.

**Roadside stops** (the existing wayside shelters and wells, run on the new roads): 4 of 6 planned stops were built, at least one on every corridor; 2 near the Blackwater end were given up for want of level dry ground. **There are no ports, marinas or harbour landings and no gate guards or gate buildings.** See [archipelago-world.md](../technical/archipelago-world.md#roadside-stops-the-existing-wayside-shelters-run-on-the-new-corridors).

**Photographs.** The frames (a landing at 1920x1080, Founders Row, the three cities from the air and from the street, the three bridges, a street with a person in it) are in a private page linked from the pull request; screenshots are never committed. The terrain maps are plots of the survey, not screenshots, and are committed in `docs/images/terrain-maps/`.

**Starting a new world and keeping the old one:** a plain `gradlew runClient` creates `UC-Archipelago` (about two minutes the first time); `-PucWorldLayout=legacy` opens the saved `UC-Dev-5908` as before. A launch and a world that disagree stop the game before a chunk is read, with the launch to use in the message (D-104).

## 3. Building protection and burglary that work

Proven on **real generated apartments**, in a brand-new world each time, by a scripted survival player and a lock solver, across a server stop and start (`scripts/run_burglary_check.py`):

| | Seed 5908 | Seed 777 |
| --- | --- | --- |
| Building | `building1` of Ironhaven, six floors, `flat_block`, deadbolt, 3 pins | `building5` of Ironhaven, six floors, `flat_block` |
| Door locked from the street, not opened by hand | yes | yes |
| Walls: visitor breaks 0 of 4 blocks around the door; same player breaks a control block that belongs to no building | yes | yes |
| Honest solver opens the lock; door stands open for its window; heat +1 | yes | yes |
| Stash | none rolled (chance 0.7) | `apartment_common` safe: asks for a pick, opens, one take moves stock 5 to 4 (3 bread) |
| After restart | door state, furnished flag, break-in remembered; lapsed door locked again; stranger cannot open by hand | the same, and the safe with stock 4 |

Limits, said plainly: **about two thirds of Lost Cities buildings have no exterior door and are sealed scenery under this rule; no police or witness responds to a break-in; a roof reached from outside by placed blocks is not guarded; nobody has picked a door with a mouse.** Only one pass was made on a workshop and two on apartments; broader burglary (shops, offices, other classes) is a P2 item not done.

## 4. How the cities are populated

A roster (identity, name, face, trade, district) is arithmetic from the city and the world seed; a schedule says by hour who is in the street; a service puts **one** person on an already-loaded outdoor street spot per pass, between 14 and 64 blocks from every player, with at most 12 around a player and 40 in all; nothing is saved and nobody opens a door. Ironhaven has 54 people, Valewood 42, Blackwater 48, wearing 48 generated skins whose outfits say the trade. Easy NPC was evaluated and not adopted (a second dialogue and trade authority); CustomNPCs was rejected. Details and the "what it is not" list: [population.md](../technical/population.md).

**Measured in a real street** (the scripted client stood in an Ironhaven street for a minute of play; one process holding the client under software rendering and the server, so the numbers are inflated by the renderer and are not a dedicated-server figure):

| | Before this fix | After this fix |
| --- | --- | --- |
| Pass cost, mean / worst (one pass a second) | 25.3 ms / 465 ms (74 passes) | 0.49 ms / 6.6 ms (75 passes) |
| Lost Cities street lookups | 50, mean 37 ms: 98 percent of all pass time | 11, mean 0.47 ms |
| Passes that found no spot | 55 of 74 | 21 of 75 |
| People within 64 blocks after a minute | 2 | 6 |

The first measurement showed that nearly the whole cost of a pass was asking Lost Cities, every second, for the street level of the same few chunks, and that most passes found no spot and retried the same person at once. The answer is now asked once per chunk and a person whose search failed waits 15 seconds while the next candidate is tried (`PopulationService.streetOf`, `RETRY_TICKS`; one GameTest). **The two runs are single samples in different worlds of one process under a software renderer: the individual lookup times differ for reasons that are not understood, so the fall from 25 ms to 0.5 ms is not all the fix. What the fix does guarantee is that lookups are bounded by the number of distinct chunks, not by the number of passes.** No dedicated-server measurement exists.

**Not true yet:** the people have no jobs, homes, economy or memory beyond a greeting; no police respond; nobody walks between cities; the density in a street is low; nobody has met one of them.

## 5. Mods installed, rejected and deferred

All are hash-pinned runtime downloads fetched from the authors' Modrinth pages for private testing; none is committed or redistributed; Create is not used and nothing here needs it ([dev-pack.md](../technical/dev-pack.md), [asset-register.csv](../legal/asset-register.csv), D-113).

| Mod | Set | Result |
| --- | --- | --- |
| Xaero's Minimap 26.6.0, Xaero's World Map 1.47.0 | `navigation` | booted; the minimap seen in real frames at its default corner beside the status stack; never opened by a person |
| Embeddium 0.3.31, ModernFix 5.27.88, FerriteCore 6.0.1 | `performance` | booted; a single software-rendered comparison (181 s and 2,756 MB with none of them, 137 s and 1,311 MB with them, both point samples) which is **not** a benchmark and is not attributed to them |
| JEI 15.56.0.205, Jade 11.13.3, AppleSkin 2.5.1, Mouse Tweaks 2.25.1, Controlling 12.0.2 (+ Searchables 1.0.3) | `qol` | booted; Jade's tooltip seen in real frames; nothing else used |
| Entity Culling 1.11.3 | `performance_deferred` | **parked**: crashes the ForgeGradle development client (a nested library is not remapped); untested in a production-style client, which cannot be launched in the cloud |
| ImmediatelyFast 1.5.5 | `performance_deferred` | **parked**: a mixin target is missing in the development client |
| Lost Cities Modern Tweaks 1.1.2 | `citytweaks_deferred` | **rejected for now**: it boots, but in a fresh archipelago world Lost Cities logged a `NullPointerException` while generating **249 of the 507 city chunks** probed (26 in the first minutes of the scripted arrival), against none without it. The earlier pin note called it frame-judged; that was not true and was corrected. |
| Easy NPC, CustomNPCs, Locks Reforged | not adopted | D-108, D-105 (a second authority, licence or size, no building awareness) |
| Lootr, Terralith, Biomes O' Plenty | not evaluated, not rejected | nothing needs per-player chests yet; a biome mod would invalidate the measured archipelago |

An interactive launch with the helpers is `gradlew runClient -PucExtraSets=navigation,performance,qol` or the shared run configuration "full dev pack + client helpers". **Not done:** interaction checks of each mod by anything but a boot, a dedicated server boot with the new sets, any real benchmark.

## 6. Interface: before and after

Found by looking at real frames at 1280x720, 1920x1080 (scale 2 and 3) and 3440x1440 (scale 2), 81 surfaces each; the full list of defects and fixes is in [visual-qa.md](../technical/visual-qa.md#october-21-review-the-trade-page-the-civilian-counter-and-the-status-display).

| Screen | Before | After |
| --- | --- | --- |
| Operations terminal, Trade (the owner's defect) | names ran into bars and buttons at 3440x1440; header and footer disagreed about the city | disjoint spans, scrolling, a status sentence, whole name on hover; `HOME <city>` and "Now outside a city" |
| Civilian shop | Q Shop's native screen, with a confirmation dialog per trade | Core's counter for `uc_` shops: sections on a rail, what is left today, why a button is off, the wallet; anything unexpected leaves Q Shop's own screen up |
| Persistent status | none | a configurable stack and a settings screen (place, size, solidity, eight lines), seen in a real world beside Xaero's minimap and the survival bars |
| First login | recipe and advancement toasts stood over the third city's card | the screen drops toasts while it is open |

Not seen: any of these used with a mouse by a person; other aspect ratios; Xaero's minimap anywhere but its default corner.

## 7. The first-player journey, and what can actually be completed

The nine steps the game itself lists (`Journey.STEPS`) and the evidence for each. **No single scripted run completes all nine on the archipelago**, and no person has.

| Step | Evidence |
| --- | --- |
| 1 Register as a citizen | **Real client, real clicks, on a fresh archipelago world** (the scripted arrival run): the screen opens by itself, city and trade by mouse, citizen and welcome pack created, next step becomes "Meet your employer" |
| 2 Meet your employer | GameTest (the employer's dialogue marks it); the HUD card names the employer ("Odette Marsh, Farm Co-op"). The real-client attempt to speak to her reached a resident instead (a harness aiming problem, not shown to be a game defect); not demonstrated by a client |
| 3 Take your first order, 4 Deliver it | Two real clients on terminal buttons, then a server stop and start: the economy journey passed both phases locally after the counter fix; hosted result in the pull request |
| 5 Buy something | The two-client journey presses the civilian counter's Buy and Deliver with real clicks; the "real purchase" mark is a GameTest on the provider's event |
| 6 Find a place to sleep | GameTests; the Crew journey (two clients) rents the Row House |
| 7 Learn a skill | GameTests; the Crew journey learns one |
| 8 Travel to a rival city | The economy journey has a foreign visit; nobody has crossed a bridge |
| 9 Finish a second order | GameTests |

What a new player does see in a real client on the archipelago: arrival on dry, open ground 53 blocks from the Row's origin, **no water, not under cover**; registration by mouse; the status stack with the next step; the Row's lane walked in about five seconds with the real keys up to the registrar's booth (a fence stands between the lane and her, the walk ended 5.2 blocks from her; the registration screen opens by itself, so she is not needed for step 1); a street of Ironhaven with its buildings, verges and a minimap. A first 15 to 30 minutes with a person at the keys has **not** been run.

## 8. Test results and the limits of the evidence

| What | Result |
| --- | --- |
| Forge GameTests | 249 of 249 locally with the Q Shop provider (248 of 248 before the cache test was added, in three configurations: no provider, provider, and the survival set) |
| Offline Python suite (`unittest discover`) | 245 tests OK |
| Repository validation | passed |
| Scripted arrival run (real client, archipelago, helpers loaded) | pass, no chunk-generation errors |
| Burglary check, two seeds | all facts hold, both |
| Road check | three routes continuous; four of six wayside stops built |
| City probes | 507 of 507 city chunks, no generation errors without Lost Cities Modern Tweaks |
| Two-client economy journey | passed both phases locally; hosted results are in the pull request |
| Real-client captures | 81 surfaces at four viewports, looked at |
| Hosted CI on the pull request | see the pull request; three failures found by it were fixed here: the civilian counter's journey hook (it looked in one section only), a race in the Crew journey (a client acted before the second player was in) and a GameTest fixture that left blocks a stash could use |

**Evidence limits:** a headless GameTest is not proof of a client screen, two-player play or gameplay quality; a software-rendered client is not a graphics-card frame rate; a scripted player is not a person; the performance mods could not be benchmarked and two could not be run; the scripted arrival cheats in exactly two ways (it makes the world and changes the game mode by command, and it moves the player into a street for the population measurement); no run covers a first half hour.

## 9. Performance and known problems

* **World start:** about two minutes the first time on the cloud container (spawn preparation, the landing levelled in about 300 ms for 20,113 columns, the Row built). The first start used to exceed nine minutes (a site search that evaluated every candidate); found on a fresh world and fixed.
* **Population:** see section 4.
* **Lost Cities Modern Tweaks** breaks city generation in this world's styles (parked); the probable cause is a palette character that only its own styles define.
* **Two thirds of city buildings are sealed**; **no police response**; **no ports, harbours or gate guards**; **Blackwater has no swamp**; two chunks of 507 still had a tree on a structure (cause not traced); a ring of chunks at each city's edge was not probed after the climate change.
* **Registrar's booth** stands behind a fence the lane's walk could not pass; whether a player reaches her without a detour is unverified.
* **Chat** keeps showing "Teleported" lines in scripted runs (harness only).
* **Pinned jars in the Gradle cache** are what the monster-drop scan reads on a developer machine; CI skips it, so a developer who has the jars can see a failure CI does not (found when Lost Cities Modern Tweaks was added; its one rotten-flesh loot entry is recorded as allowed).

## 10. What needs the owner's hands

1. **Start a new world** (`gradlew runClient`) and judge the landing, the Row, the lane, the first screen and the first minutes. Nothing here replaces that.
2. **Walk or ride a bridge** and say whether three to four minutes between outskirts feels right, and whether the three islands read as three places.
3. **Pick a lock** on a real apartment door with the mouse and say whether the game is worth it; decide what the two thirds of sealed buildings should become (doors, curated interiors, or scenery).
4. **Try the status display and the civilian counter** and the helper set (`-PucExtraSets=navigation,performance,qol`), and decide the defaults (which lines, which corner, whether the helpers belong in the default launch).
5. **Decide** about Lost Cities Modern Tweaks (a palette of our own that makes it work, or drop it), Easy NPC as a body provider for story characters, and whether Lootr is wanted for exploration chests.

## 11. Next milestone

1. A scripted **two-client first-player journey on the archipelago** (arrive, register, employer, order, delivery, counter, home, skill, a bridge crossing), then a human half hour.
2. **Entrances for sealed buildings** or curated interiors, and a **witness and police response**, so burglary has a consequence.
3. **Gate guards, a harbour landing and a port** at the bridge ends; roadside variety.
4. **People with a purpose:** jobs, homes, routines and an effect on the economy, then a street with enough of them to feel lived in.
5. **Sable Leaf cultivation depth** and the rest of P2 (not started in this pass; nothing here claims it).
