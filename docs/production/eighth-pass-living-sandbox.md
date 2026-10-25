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

* **The Smith is an engineer** (D-182). Precision parts are turned by hand to a drawing the first time. The fewer passes, the better the grade; a mistake can be sleeved and saved. The bench keeps a jig for later batches. The cities' works and machine-run shops break down in active play: a Smith takes the service call, strips the drive, replaces worn parts with parts of their own making, gears it to the machine's plate and runs it. While a works stands idle its city's metal lines stop restocking, and while a shop's machine is down its prices rise by a fifth. A repair pays once through the work-order ledger and runs a catch-up shift. Design: [smith-engineer.md](../design/smith-engineer.md). Build: [smith-engineering.md](../technical/smith-engineering.md).
* **Leaving a single-player world can no longer hang the game** (D-183). See the bug list.
* **A shop whose doorway has a floor mat has a public floor again.** The corner store's customers were treated as "in the back".

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
