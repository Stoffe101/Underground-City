# Sprint report, October 24, 2026 (fifth pass): living population, workshops of the trades, shop supply, Xaero waypoints, the Technician's rebalance and back channel

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Living Population, Profession Depth, Xaero Navigation & Integrated Gameplay Overhaul". Decisions D-161 to D-166 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients (software-rendered, in a virtual display, driven by synthetic key and mouse events). "Passed" means a script passed. Where a step was set up by a command or a teleport instead of by input, it says so.

**This report is being written while the pass is still running.** Sections marked *in progress* are filled in as the runs finish.

## What changed for a player

* **Tracked destinations on Xaero's Minimap (D-161).** See [navigation](../technical/navigation.md). Tracking a service call or a place puts one personal waypoint on Xaero's minimap, waypoint list and world map when Xaero is installed. It goes when the work is done, comes back after a restart without a duplicate, and leaves the player's own waypoints alone. The status display's arrow is still there for everybody.
* **Populated cities (D-162).** See [population](../technical/population.md). The street population follows each area's hour and each city's lean, within a density setting (QUIET, NORMAL, BUSY). People have errands, come out of doors, go in at doors nobody can follow them through, browse open shops, talk in pairs, and wear 200 original skins.
* **Workshops of the trades (D-163).** See [workshops](../technical/workshops.md). Seven Core stations give the Farmer, Vintner, Smith and Gunsmith physical production on the active clock. They have three tiers fitted from real parts (mostly a Smith's) and graded goods that pay more. Fifteen new goods and twelve contracts tie the trades together.
* **Shops buy from suppliers (D-164).** See [shop supply](../technical/shop-supply.md). Six shops buy the matching workshop goods over the counter, through the ledger's single settlement. The city's customers empty those shelves again, and a shopkeeper whose shelf is low asks for more.
* **Technician faults rebalanced (D-165).** See [the Technician's trade](../technical/technician-trade.md). A shop's fault no longer shuts it, a building's fault turns off only its cameras, and faults come less often and clear sooner.
* **The Technician's back channel (D-166).** An abstract underground job, shown only to a Technician with Bypass Tooling: a patch grid at a building's panel loops its cameras and quiets its alarm line for a while, at the risk of witnesses, officers and the tamper alarm.
* **Burglary fixes.** See [the burglary loop](../technical/thief-and-burglary.md). Redstone no longer holds a staff door open. A business's back-room barrels hold its stock. A loot-value audit by tier (`scripts/burglary_audit.py`) was added.

## Evidence so far

* **GameTests:** all 397 required tests passed (run 114), including `TrackingGameTests` (3), `WorkshopGameTests` (8) and the updated Technician and population tests.
* **Python:** 329 unit tests pass, and `validate_repo.py`, the content generators' `--check`, the art check, the item-source audit (0 findings), the underground balance validator and the economy model's invariants all hold.
* **Real clients:**
  * Technician run 5 with Xaero installed passed every step, `RESULT pass`.
  * The Vintner workshop runs are *in progress*: run 2 passed seven of eight steps and stopped walking into the pub, and run 3 is running.

## In progress

The crowd measurements before and after, the captures of the new screens at four viewports, and the final 46-item report.
