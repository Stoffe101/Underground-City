# Sprint report, October 24, 2026 (fifth pass): living population, workshops of the trades, shop supply, Xaero waypoints, the Technician's rebalance and back channel

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Living Population, Profession Depth, Xaero Navigation & Integrated Gameplay Overhaul". Decisions D-161 to D-166 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients. The clients were software-rendered in a virtual display and driven by synthetic key and mouse events. "Passed" means a script passed. Where a step was set up by a command or a teleport instead of by input, it says so.

## What changed for a player

* **Tracked destinations on Xaero's Minimap (D-161).** See [navigation](../technical/navigation.md). Tracking a service call or a place puts one personal waypoint on Xaero's minimap, waypoint list and world map when Xaero is installed. It goes when the work is done, comes back after a restart without a duplicate, and leaves the player's own waypoints alone. The status display's arrow is still there for everybody.
* **Populated cities (D-162).** See [population](../technical/population.md). The street population follows each area's hour and each city's lean, within a density setting (QUIET, NORMAL, BUSY). People have errands, come out of doors, go in at doors nobody can follow them through, browse open shops, talk in pairs, and wear 200 original skins. A follow-up in this pass keeps their strolls and new arrivals on open streets near a player, after the first measurement showed the streets in view still nearly empty (see below).
* **Workshops of the trades (D-163).** See [workshops](../technical/workshops.md). Seven Core stations give the Farmer, Vintner, Smith and Gunsmith physical production on the active clock. They have three tiers fitted from real parts (mostly a Smith's) and graded goods that pay more. Fifteen new goods and twelve contracts tie the trades together.
* **Shops buy from suppliers (D-164).** See [shop supply](../technical/shop-supply.md). Six shops buy the matching workshop goods over the counter, through the ledger's single settlement. The city's customers empty those shelves again, and a shopkeeper whose shelf is low asks for more.
* **Technician faults rebalanced (D-165).** See [the Technician's trade](../technical/technician-trade.md). A shop's fault no longer shuts it, a building's fault turns off only its cameras, and faults come less often and clear sooner.
* **The Technician's back channel (D-166).** An abstract underground job, shown only to a Technician with Bypass Tooling: a patch grid at a building's panel loops its cameras and quiets its alarm line for a while, at the risk of witnesses, officers and the tamper alarm.
* **Burglary fixes.** See [the burglary loop](../technical/thief-and-burglary.md). Redstone no longer holds a staff door open, a business's back-room barrels hold its stock, and a loot-value audit by tier (`scripts/burglary_audit.py`) was added.

## Evidence

* **GameTests:** all 397 required tests passed on the head before the population follow-up (run 115). They include `TrackingGameTests` (3), `WorkshopGameTests` (8) and the updated Technician and population tests. The follow-up adds one population test; its run is recorded under "Population, measured" below.
* **Python:** 334 unit tests pass. `validate_repo.py`, the content generators' `--check`, the art check, the item-source audit (0 findings), the underground balance validator, the burglary audit and the economy model's invariants all hold.
* **Real clients** (a new furnished archipelago world each run):
  * **Technician with Xaero (technav run 5):** all 18 steps passed, `RESULT pass`, 12 scripted shortcuts. A service call's waypoint appeared on the minimap, in Xaero's waypoint list and on its world map. Arriving did not end it, and the repair did. The install job's waypoint came and went the same way. A second call was tracked, and after a save, quit and reopen it was tracked again, one waypoint, no duplicate. The player's own waypoints were counted before and after each step (0 → 0). The walks to the panels were walked; the shortcuts were the clock, the travel to Valewood, the faults raised at once, the computer and bench set down, the skills and parts, and the patch grid's answer (its turns were real clicks).
  * **Technician without Xaero:** run 6 stopped at the install, because the scenario had given the building a second install job. The scenario was fixed; run 7 is recorded below.
  * **Vintner workshop (workshop run 3):** all 8 steps passed, `RESULT pass`, 6 scripted shortcuts. The kit's vat was placed with a right click, a batch started and taken with real clicks after six minutes of play, tier 2 fitted, and three bottles sold over the Ironhaven pub's counter with a real click on Sell (+42 credits, the wine shelf 4 → 7). **The walk back to the pub's front door wandered round the block and was finished by a teleport for its last 22 blocks**; from the door on, it was walked. The run also found that no Vintner XP was paid for a batch the station's own clock had finished: fixed and GameTested, not rerun in a client.
* **Captures** of the new screens at 1280x720, 1920x1080 at GUI scale 2 and 3, and 3440x1440 at GUI scale 2: the three workshop stations, the pub's counter with Sell, the Vintner, Smith and Technician skill trees, the computer's service board, the back-channel job and its patch grid. 17 of the 40 were looked at: every surface at least once, and every size at least once. Three defects were fixed: the job detail's "Open for" label ran into its value ("Left" now); the back-channel description was cut off after two lines; and a workshop recipe without its inputs said "Missing parts", a word the same screen uses for the parts a tier needs ("Missing inputs" now). The fixes were not captured again.

## Population, measured

*Filled in from the crowd runs when they finish.*

## Not done, and why

* **Two real clients at once** (the brief's verification 31, and two players in one city for the population and the waypoints): not run. Personal waypoints rest on the code path (a packet to one player) and `TrackingGameTests`.
* **Connected all-profession scenarios** (verification 30): only the Vintner went through a real client. The Farmer, Smith and Gunsmith stations are GameTested, not played through a client.
* **The Gunsmith and TaCZ:** the Gunsmith's Bench makes licensed and unmarked parts kits, not TaCZ firearms. TaCZ guns, blueprints and their contracts are unchanged.
* **Business and property (part VI):** renting workplaces, commercial property, hiring, operating costs and Crew businesses were not built. A station stands wherever the player places it on ground no building owns, or at home.
* **Kaleidoscope Tavern and Vinery:** not re-evaluated in this pass. Both stay opt-in candidates in the `vintner` set (an earlier mod audit read their licences and content). The Oak Cask rests Vinery's red when Vinery is installed.
* **NPC-originated work:** a shopkeeper whose supplied shelf is low asks for more. No resident yet reports broken electronics, and no restaurant asks a Farmer for produce through a person.
* **The back channel (D-166)** is GameTested only. No real client has done one.

## Bugs found and fixed in this pass

* The client's navigation tick never ran in a real client: a nested `@Mod.EventBusSubscriber` class was not public (tech runs 1 to 3). It now runs from `ClientPlaces.Ticks`.
* A workshop batch that the station's clock finished before the player came paid no career XP.
* With every optional mod loaded the catalogue held 69 work orders, over its cap of 64, and the full-pack journeys in CI failed. The cap is 128.
* The ledger could not hold a Vintner's postings (the cap is 80).
* Recipe notes stated tier-1 times that a faster tier contradicted.
* The display font has no "·", so the workshop title used one it could not draw.
* Redstone could hold a staff door open; an empty back room had nothing to steal.
* The computer's job detail overlapped a label and its value, and cut a description short.
* Street people strolled into yards and courts behind the buildings, out of sight from the street (see "Population, measured").
* Scenario fixes: the bench was set down out of the player's sight, and the Technician run without Xaero gave a building a second install job.

## Next

1. A person plays the first half hour on each trade, with Xaero installed and without.
2. Two real clients in one city and in different cities: the population budgets, personal waypoints, concurrent service calls and shop sales.
3. The Farmer's, Smith's and Gunsmith's station loops through a real client, and a back-channel job.
4. The business and property layer: a workshop, cellar or farm you rent or buy, with capacity, storage and running costs.
5. People who bring work: a resident's broken electronics, a café's produce order.
