# Sprint report, October 24, 2026 (fourth pass): property intelligence, the Technician's trade, staff-only rooms and Cannabis processing

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Professions, Hacking, Business Progression, Property Intelligence & Living-World Overhaul". Decisions D-157 to D-160 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients (software-rendered, in a virtual display, driven by synthetic key and mouse events). "Passed" means a script passed. Where a step was set up by a command or a teleport instead of by input, it says so.

## What changed for a player

* **Property intelligence (D-157).** See [property intelligence](../technical/property-intelligence.md).
  * Every city now has easy, middling and hard targets. A building's tier moves within its class's grade by neighbourhood and by city, and the tier carries the lock, stash and alarm of that tier.
  * Looking at a street door shows a small card under the crosshair: what the place is, its address, its tier, and whether this player could try it.
  * Casing (the new Inspect key, I) reads in layers by skill, from the tier and lock for anybody to the risk as a number for an expert.
  * Every place seen or cased goes into a book (the Places screen, J), with Track to point the status display's arrow at it.
* **The Technician's legal trade (D-158).** See [the Technician's trade](../technical/technician-trade.md).
  * Utility panels sit beside city buildings' street doors. Service calls come only to a city somebody is in: a shop's till goes down and its door stays shut, or another building's alarm and cameras go off.
  * A repair is a probing puzzle with real tools, paid once to the first Technician. Installs fit a monitored alarm and upgrades protect a network; both are a patch grid.
  * The new Electronics Bench makes boards, toolkits, the computer, the scanner and the security array from carried parts, gated by the tree. The personal computer has the city's service board, with Track, and the bench's designs.
  * Electronic Scrap comes from scrap heaps, the salvage yard and office desks.
  * The legal skill nodes now say what they do in this trade.
* **Staff-only rooms (D-159).** See [the burglary loop](../technical/thief-and-burglary.md#doors-and-staff-only-rooms-d-147-d-150-d-151-d-159).
  * While a shop is open, only its public floor is off-limits to a thief.
  * A back room behind a door, or a floor above or below, is staff only: searched at the household's risk, watched by its cameras. The door into it is locked to the shop floor and picked on the lock screen.
  * Anybody the household sees back there is told to leave, then reported.
  * Valewood's bakery has a stock room behind such a door (new worlds).
* **Cannabis processing and the law on growing (D-160).** See [legal and underground careers](../design/legal-and-underground-careers.md#the-cannabis-crop-farmer-restricted-crop-renamed-by-d-142).
  * A crafted Drying Rack turns two flower into one Dried Cannabis in six minutes of active play. Two new orders buy Dried Cannabis at a better rate per hands-on hour.
  * A plant growing in the open in Ironhaven or Valewood is pulled up by an officer on the beat who sees it. This happens only while its grower is online, and planting in public view warns at once.

## Found by testing, and fixed

| Found | By | Fix |
| --- | --- | --- |
| Valewood had no hard targets and Blackwater 3 easy ones | The property survey of a new world (places runs 1 and 2) | the neighbourhood grade, a lean per city and Valewood's townhouses (D-157): 47 easy and 16 hard in Valewood, 40 easy in Blackwater |
| The glance card overlapped the status display, and casing lines and Places values were cut off | Places run screenshots | shorter wording, the card kept left of the display, a wider detail column |
| A shop's fault would have closed it for 40 minutes with no word why | Writing the shop fault | the door says why ("Closed: the till and card network are down"), and a shop's fault lasts 20 minutes |
| A person in a shop's doorway, or on the pavement under its awning, was told "Staff only" | Organic run 3's screenshot | the ground outside the street doors is read with the floor; a doorway is never "in the back" (GameTested) |
| A plant under glass counted as in the open, and a freshly roofed room as open sky | GameTest run 105 | roofs are read from the block heightmap, not the sky light (which lags and passes through glass) |
| A broken drying rack lost its crop when block drops were off | GameTest run 105 | what was hung on it drops as a container's contents do |
| The computer's and the bench's text was cut off ("Repair: alarm and ...", "Needs Automation In...") | Tech run 3's and the captures' screenshots | shorter job names, wrapped values, a hint that fits above the footer |
| Electronic Scrap as a tenth exchange good broke the world prototype's rule of nine exclusive city exports | The Python suite | it is a salvage good instead (scrap heaps, the salvage yard's counter, office desks) |
| The two Technician tests that pay a repair failed in the seven hosted jobs that run the GameTests without Q Shop (the onboarding screens, the Forge smoke jobs, the Core compile) | Hosted CI on a8b773a, reproduced locally without Q Shop | they return early when no wallet is loaded, as every other payout test does; the jobs that load Q Shop still run them in full |
| The scenarios' own faults: a nested server read that deadlocked, a bench set down in front of the panel, an item outside the hotbar, a click on a counter whose till was on top | Tech runs 1 to 4, organic runs 1 to 4 | fixed in the scenarios; none was a game defect |

## Evidence

| What | Result |
| --- | --- |
| Forge GameTests (local) | **385 of 385 passed** with Q Shop (runs 108 and 109) and **385 of 385 without it**, as the screening jobs run them. The pass began at 368. New in this pass: 7 for property intelligence, 7 for the Technician (the two puzzles over many seeds, a repair paid once, two Technicians on one call with only the first paid, the gates, a monitored alarm and a shop that is down, the bench), 1 for staff-only zones, 1 for the law on growing and 1 for the drying rack. Runs 105 to 107 found the three defects in the table above |
| Python suite | **329 tests OK**; `validate_repo.py`, the drawn-art check, the item-source audit (0 findings; it now follows the bench's designs) and the economy model's invariants hold |
| **Property intelligence through a real client** (`-PucPlaytest=places`, places run 3, a new furnished archipelago world) | **`RESULT pass`**, 12 steps: the survey of every city (each has easy, middling and hard targets), the card at three street doors, casing at three layers with the Inspect key, ten seconds of looking round a street (the client asked about the doors it rested on only), the Places screen, Track, and the book after a save, quit and reopen. Its shortcuts are in [property intelligence](../technical/property-intelligence.md) |
| **The Technician's trade through a real client** (`-PucPlaytest=tech`, tech run 5, a new furnished archipelago world) | **`RESULT pass`**, 11 steps: a new Technician's kit; panels set beside street doors; a service call (notice, red lamp); the computer's board and Track; a repair worked with real probes and one replacement, paid 66 credits and 170 XP once, the lamp green; the bench making a toolkit with Assemble; an install on the patch grid, 102 credits and a monitored alarm; the record across a reopen. Shortcuts in [the Technician's trade](../technical/technician-trade.md#evidence) |
| **A burglary by day with no spatial shortcut** (`-PucPlaytest=organic`, organic run 5, a new furnished archipelago world) | **`RESULT pass`**, 7 steps, everything inside walked with the real keys: in at an open bakery's front door, refused at its counter, the stock room's staff door locked and then picked on the lock screen, the barrel and the till searched (1 stolen unit marked with the thief), told "Staff only" once and out of sight in time, out to the street on foot. **3 shortcuts:** the clock, the travel to Valewood and the start on the street outside, one lockpick |
| Two players | **GameTested only:** two Technicians on one service call, the first paid and the second given nothing and charged nothing. No two-client run was made in this pass |
| The new screens at four viewports (`-PucVisualCapture`, `-PucVisualOnly=uc-tech`) | the computer (service and designs), the bench, the repair and the install grid at 1280x720 GUI 2, 1920x1080 GUI 2 and 3, 3440x1440 GUI 2: 20 captures, all looked at; text fits after the fixes above; small and centred on the wide screen like the rest of the interface |
| Hosted CI | all checks green on the pass's first commit (8b38545); see the PR for the later commits |

## Not done in this pass

* **The underground side of the Technician ("Hacking")** was not built. A response that described getting into a building's systems uninvited was stopped by a safety filter, and that content was not produced again. The legal trade above, and the underground nodes and the alarm-panel jam that already existed, are what a Technician has.
* **The Vintner, the Smith, the Gunsmith and the Farmer's business** were not changed. No profession journey was run for them, and no career tree other than the Technician's legal nodes was reworded.
* **Business property** (renting a workspace, commercial property, business contracts) was not started, and the economy was not rebalanced beyond the two dried-cannabis orders.
* **Cannabis:**
  * No curing, packaging or quality grades.
  * The drying rack and the law on growing have **not been run through a real client**.
  * The Cannabis scenario of the last pass grew its plants in a Valewood street. Under D-160 an officer on the beat who saw them would now pull them up.
* **Two-client runs** of any of this were not made.
* **Staff-only areas** read one floor of one chunk. A staff door is not also held shut by the guard's door-state rule (as street doors are), so redstone could open one. The flour barrels in the bakery's stock room hold nothing.
* **Xaero's map** was not integrated (see [property intelligence](../technical/property-intelligence.md)).
* **What the owner should test first:**
  1. Casing a street with the Inspect key, and the Places screen.
  2. A Technician's first service call: the computer, Track, the panel, the repair.
  3. Walking into the Valewood bakery by day and trying its stock room.
  4. Hanging flower on a drying rack.
