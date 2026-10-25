# Sprint report, October 25, 2026 (eighth pass): the living sandbox alpha

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "The Living Sandbox Alpha, Interactive Engineering, Profession Depth, Nightlife, Co-op & Final Gameplay Readiness". Decision rows from D-182 in [decisions.md](decisions.md). It continues the [seventh pass](seventh-pass-alpha-readiness.md).

**The current branch and its recent gameplay systems have not received human playtesting.** Everything below was run by scripts, Forge GameTests, the Python suite, the economic model and scripted real clients (software-rendered in a virtual display, driven by synthetic key and mouse events). Every command, teleport or grant in a client run is logged as `SCRIPTED` or `PREPARED`. Evidence levels:

| Level | Meaning |
| --- | --- |
| L1 | unit and data checks |
| L2 | GameTests and headless runs |
| L3 | one scripted real client |
| L4 | two scripted real clients |
| L5 | a person playing |

Nothing here is at L5.

**Status: in progress.** Sections are filled in as the work and the runs finish.

## What changed for a player

* **The Smith is an engineer (D-182).**
  * **Turning by hand.** Precision parts (machined parts, drive shafts, precision machinery parts) are turned to a drawing the first time: choose a stretch of the bar, the tool (roughing deep and marked, finishing one step and clean), the line, and cut. The fewer passes, the better the grade; the shop's standard is the true minimum (an exact search). A section cut too far can be sleeved back with a nugget, at Fine at best. Closing a drawing takes nothing.
  * **Jigs.** The bench keeps the best grade turned for each recipe and, from the Drill Press on, runs it as a batch (Reserve only with Production Tooling), so the hands-on work is for new or better parts, not every one.
  * **The cities' machinery.** Industrial buildings and four shops run on a machine (a drive housing beside the street door in a new world). It breaks down only in a city somebody is in. The Smith hears the call, opens the toolkit's list, tracks it, strips the drive, replaces the worn parts with their own (or bought) bearing sets, gear sets, brackets and springs, gears the open stages to the plate and runs it. A works standing idle stops its city's metal lines restocking; a shop on a stopped machine charges a fifth more; a repair pays once through the ledger and runs a catch-up shift.
  * **Assemblies and uses.** Bearing sets, drive shafts, gearboxes and pump assemblies; the Mill's top tier (the Farmer's Geared Crank) is fitted from a gearbox; the Northgate depot orders drive shafts and the Ironhaven works gearboxes. Every assembly is also sold at the yard or the hardware desk, so no trade waits for a Smith.
  * **Skills.** Millwright, Production Tooling, Heavy Assembly and Master Engineer join the tree (29 points in all); no existing node changed.
* **Batches inside and outside property (D-184).** Inside a home or trade premises somebody holds, a batch is never anybody else's. Outside, it is held on its maker's own play time (two hours past its run), then it is salvage, and the maker is told.
* **Business hours (D-185).** Shops 08:00 to 18:00, cafes 07:00 to 20:00, restaurants 11:00 to 23:00, pubs and bars 12:00 to 02:00, clubs 18:00 to 03:00, industrial suppliers 07:00 to 19:00; the glance card at a street door says when a place is open, and the evening crowd makes for the venues that are serving.
* **Exploration finds (D-186).** A battered crate in each roadside place and wayside shelter of a new world gives each player something once, rolled for them and never stored in the crate.
* **Leaving a single-player world can no longer hang the game (D-183).**
* **A shop whose doorway has a floor mat has a public floor again.**

## Evidence

In progress.

## Bugs found and fixed in this pass

| Bug | Found by | Fix |
| --- | --- | --- |
| **Save and Quit could hang the game for good.** Core's city listener asks Lost Cities for its information from inside Lost Cities' own chunk generation. When the player leaves a single-player world, Lost Cities marks that information stale. A worker then halfway through a city chunk asked again, waited for a write lock its own read lock blocked, and never finished; the integrated server waited for it forever ("Saving worlds"). | homes client run 3 (step 7, save and reopen), diagnosed from a thread dump of the hung server | the listener uses the information Core resolved on the server thread when the level loaded (Lost Cities' building and style registries are static, so it answers the same); it asks Lost Cities directly only if that is missing, and counts those times in the street service's status line (D-183) |
| **A camera called the police on a customer at the corner store's public terminal.** Modern Life's floor mat copies bamboo's block properties, which count it as solid although it is a sixteenth of a block high. The only two cells inside the store's door hold mats, so the staff-zone flood had no seed and the whole shop was "staff only" in opening hours. | unmarked client run 2 | one rule for "a person can stand here": a block is in the way only if its collision is higher than a mat or a carpet. Used by the staff zones, the street-door check and the scripted walkers; GameTest with real Modern Life mats inside a street door |

## Not done, and why

In progress.

## Next

In progress.
