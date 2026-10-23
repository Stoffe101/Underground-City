# Sprint report, October 23, 2026 (third pass): Thief progression, burglary, security and the crop's real name

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Complete Gameplay Integration, Thief Progression, Living-World Expansion & Creative Overhaul". Decisions D-142 to D-152 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, the Python suite, the game's own generator and scripted real clients (software-rendered, in a virtual display, driven by synthetic key and mouse events). "Passed" means a script passed. Where a step was set up by a command or by a teleport instead of by input, it says so.

## What changed for a player

* **The crop is called Cannabis (D-142).** Cannabis Seeds, Cannabis Plant, Cannabis Flower, in every item, contract, skill, buyer, generator and current document; older saves load the renamed items and crops through registry aliases. A test fails if the former fictional name returns.
* **A Thief track for every career (D-143).** Thief XP from getting in, safes, escapes, bypasses, searches and fence sales, each paid once per thief and place; a Thief level (to 25) and Thief points that never mix with career points; a 16-skill Thief tree in three branches (Locks, Stealth, Fencing) on the terminal's new **Thief** page. See [the Thief track and the burglary loop](../technical/thief-and-burglary.md).
* **Tiers, casing and searchable rooms (D-144).** Every building class has a tier (Shabby to Exceptional) and a safe profile. Crouching at a street door with an empty hand shows a casing card; Modern Life furniture in somebody else's city building can be searched for room-fitting loot by tier, marked stolen with the thief's id; thirteen new valuables sell only to fences.
* **Lock variety and tools (D-145).** Spool pins, raking cheap locks, a wearing Pro Pick Set (crafted, or now and then in the cannery den), safecracking for wall and vault safes, and skills that change the lock screen.
* **Police who look, cameras that see, shops that open (D-146).** Officers chase only a thief one of them can see, search the last place they saw them and give up; Core watches camera blocks in a cone with a line of sight; a shop's door opens while its shopkeeper is in.
* **Fences and doors (D-147, D-150, D-151).** A fence's building opens at the fence's hours to anybody carrying stolen goods; nobody searches a building while it is open; a front door under the eaves is a locked street door; Core opens iron street doors for whoever the guard lets through.
* **Cameras by tier (D-149).** The furnished apartment tower, club and warehouse gained a camera over the entry; a tenant, their Crew and their guests pass their own building's cameras.
* **No hitch at the roadside places (D-148).** A roadside place goes up a slice per tick (at most 15 ms) instead of in one tick of up to 448 ms; the road check measures one thing a tick and no longer runs out of memory.
* **SecurityCraft is not adopted (D-152).** Viewing one of its cameras froze the server for two minutes in a measurement; its camera monitor is refused even in the opt-in trial.

## Found by testing, and fixed

These were found by the new real-client scenario, the hosted CI or the screenshots, not by reading the code:

| Found | By | Fix |
| --- | --- | --- |
| A Valewood house's front door, its step under the eaves and its hall roofed, was taken for an inside door and **never locked** | Thief run 3 (the scenario logs each door with what the guard makes of it) | D-150: a side leads out when open floor reaches the sky within three blocks |
| The fences sit behind a high-security iron door (the club) and a secured one (the warehouse): a thief could sell only by breaking in, and since D-145 only with a Pro Pick Set | Writing the scenario's fence step | D-147: the door opens at the fence's hours to somebody carrying stolen goods |
| **Iron street doors opened for nobody**: the guard let a tenant of an Ironhaven flat, somebody leaving, a shop's or a fence's customer through, and nothing opened the door; a thief was shut in the club once its door closed | Thief run 7 | D-151: Core opens or shuts an iron door for whoever the guard lets through |
| A city shop's door stayed locked, so no customer reached a counter | Writing the burglary loop | D-146: a shop is open while its shopkeeper is in |
| A tenant crossing their own building's lobby would be reported by its camera | Adding cameras by tier | D-149: key holders pass |
| Modern Life's refrigerator, two blocks tall, was two searchable spots | Writing the scenario's search step | searched once, by its lower half |
| The scenario's "nobody in" filter picked houses **to let** (vacant listings), whose doors are the home's, not a burglar's | Thief run 4 | the scenario excludes them; the rule itself was right |
| The police chase test failed in every hosted job and passed locally: its scene reached into neighbouring tests' cells, and whether an officer could see five blocks depended on what they had built | Hosted run 281 | the scene stands on a floor of its own high above the test grid |
| The diner door that shut on the walker in journey run 63 (and again in run 64) | Reading runs 63 to 65 | most likely the harness: a press and release sent as two commands can be four or more ticks apart on a slow frame, and the game repeats "use" while the button is down (the SecurityCraft trial saw the same); the helper now sends a click in one flush |
| The casing card's empty tier pips were nearly invisible; its rows and footer, the Thief page's effect lines and the fences' descriptions were cut off; the Thief page's scroll hint sat on a heading | Screenshots of the real client at 1280x720 | outlined pips, shorter wording (effect lines at most 40 characters, checked by a test), the hint placed where a heading leaves room |
| A Farmer skill still promised "quiet bale delivery contracts"; two design headings said the crop was "called Cannabis before D-142" | Rereading after the rename | fixed; the terminology test also catches camel-case old names |
| One house and one sale made 98 to 127 Thief XP against 90 for level 2: a weak haul could leave a new thief short of their first point | Thief runs 8 to 12 | level 2 at 80 XP |
| The cannery den was said to sell a Pro Pick Set and did not | Writing the playtest guide | it does now, one at a time, slowly restocked |

## Evidence

| What | Result |
| --- | --- |
| Forge GameTests (local, runs 90 and 91) | **361 of 361 passed** (342 at the start of this pass; new: 16 Thief, a door under the eaves, an iron door and the renamed crop's aliases; the diner test now builds the place in 2 ms slices) |
| Python suite | **321 tests OK**; `validate_repo.py`, the drawn-art check, the generated-content check and the economy model's invariants hold |
| **The new burglar through a real client** (`-PucPlaytest=thief`, Thief run 12, a brand-new furnished archipelago world) | **`RESULT pass`**, 8 steps: (1) a new Farmer starts at Thief level 1; (2) casing a lived-in Valewood house shows its tier and lock and names Casing for the rest; (3) the deadbolt picked pin by pin on the real lock screen, the door opens, 75 Thief XP; (4) all nine pieces of furniture searched with the real right click, every find marked with the thief's name; (5) at 22:00 the Blackwater club's iron door opens to the thief carrying stolen goods, without a pick, and a search inside is refused; (6) the fence's real screen: Sell all, 36 credits and 12 Thief XP; (7) Thief level 2 with one Thief point; Casing learned on the terminal's Thief page; (8) casing the house again reads the alarm (fitted, not armed), the cameras (none) and who is in (2 at 22:00). **22 logged shortcuts:** the time of day (13:00, then 22:00, with the daylight cycle stopped), the travel between the landing, Valewood, Blackwater and Founders Row (teleports; the roads were walked in the journey runs), the choice of house, five lockpicks put in the hotbar, the player put beside each of the nine pieces (the path walker does not cross a furnished hall reliably), on the street at the club, and in the club's back office beside the fence (the walker does not climb its stairs), one night skipped because the fence was not in |
| SecurityCraft camera viewing (`-PucPlaytest=sccamera`, run 2) | the mount call held the server thread **120,388 ms**; chunks held 3,137 → up to 5,532; 959 generated; released on stopping ([securitycraft-trial.md](../technical/securitycraft-trial.md#camera-viewing-measured-d-152-october-23)) |
| Screens at four viewports | see [Screens](#screens) |
| Hosted CI | see the PR's checks for the head commit |

## Screens

The GUI harness (`-PucVisualCapture`, filtered with the new `-PucVisualOnly`) captured twelve new screens at each of four viewports: 1280x720 at GUI scale 2, 1920x1080 at scales 2 and 3, and 3440x1440 at scale 2. The screens were the Thief page for a new thief and an expert, the terminal's Thief tab, the casing card for a novice, an expert and a lock the thief cannot try, the rake, spool and turned lock poses, and a fence with goods it can pay for in full, in part, and nothing to sell. All 48 were looked at, and so were the real client's own frames from Thief run 12. Besides the fixes in the table above:

* 1280x720 and 1920x1080 at GUI 3: the expert card's "Empty" value ran out of its column ("Empty from about 09:00 (in 3 ho..."). The label already says "Empty", so the value is now "About 09:00 (in 3 h)".
* The Thief page's unlock button read "Unlock: 2 Thief points" and overran the button at both sizes. It now reads "Unlock: 2 points"; the detail line beside it already names Thief points.
* The new-thief fixture showed "80 / 80 XP" at level 1 with a full bar, which is level 2 since the step became 80. The capture fixture was wrong, not the game; it now sits three quarters of the way through the level.
* 3440x1440 at GUI 2: every screen is legible and centred, small on the wide screen like the rest of the interface. Nothing overlaps.

The two corrected screens were captured again at 1280x720 and at 1920x1080 GUI 3 (see [Evidence](#evidence)).

## Not done in this pass

* **Scenarios B to G** of the brief (an advanced burglary with cameras, a Pro Pick Set, a safe and an escape; profession growth; Cannabis production; SecurityCraft devices; roadside contracts; two players) were **not run** through a real client. Their systems are GameTested, except where noted below.
* **No crowbar, no window entry, no police patrols**, no pursuit across a city boundary or after a disconnect.
* **Cannabis processing** (drying, curing, products and their buyers) was designed but **not built**: under the contract margin rules a one-to-one drying step adds about a tenth to the price, so it needs the economy model reworked with it, which this pass did not do.
* **Professions, the skill trees of the five careers and the economy** were not reworked beyond the Thief tree, the terminology and the Thief XP step.
* **Living world:** no new roadside contracts, no NPC changes, no new buildings. The furnished set stays opt-in for the default launch (making it the default would make the generation guard refuse the owner's existing world).
* **Two-player** play of the Thief track (attribution of a shared break-in, two thieves at one door) was not tried beyond the existing police GameTests.
* The road check was changed to measure one thing a tick but **was not rerun** in a new world.
