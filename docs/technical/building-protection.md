# City building protection, locks and burglary (D-105)

Status: **implemented and tested by GameTests and by a scripted run on a generated Lost Cities apartment block; nobody has played it.** Each section says what the evidence is and what it is not.

The Thief track, property tiers, casing, searchable rooms, spool pins, raking and the Pro Pick Set (D-143 to D-150) are in [the Thief track and the burglary loop](thief-and-burglary.md).

## What the owner's first playtest found

Nothing stopped a player from walking up to a generated city building and digging in: Lost Cities' walls, windows and furniture are ordinary blocks, and the property systems of the starter slice
(registered homes, the Household Safe) covered only what an operator had placed and registered. "Burglary" did not exist: a safe cost lockpicks, a click and no skill.

## What exists now

| Piece | What it does | Evidence |
| --- | --- | --- |
| `CityBuildings` | Asks Lost Cities' own plan (not a scan) what stands in a chunk: building type, floors, cellars, city level, and so the street height. A building's **protected volume** is its chunk from under its deepest cellar (`street - 6 x (cellars + 1) - 2`) to over its roof (`street + 6 x floors + 8`). Open lots, streets, parks and the countryside are not buildings and are not protected. | GameTests (volume arithmetic, classes and rules); the real plan was probed on 507 generated chunks (`scripts/run_city_probe.py`) |
| `data/underground_city/building_security/classes.json` | A **heuristic**, said so plainly: Lost Cities does not know what a building is for, so a building's class comes from its city, its type prefix and its floor count. Seven classes: cottage (latch), apartment block (deadbolt), apartment tower (commercial), workshop (commercial), office (commercial), high-value premises (high-security; the downtown centrepiece), public building (unlocked). | GameTest checks every class loads, every rule names a class, the tiers climb in difficulty, every stash policy exists |
| `BuildingGuard` | Inside a building's volume a survival player cannot break, place, pour, push (piston), blast (explosion) or let a creature grief. Creative mode and operators are never stopped. A registered home keeps `ResidenceGuard`'s authority. | GameTests with a control outside the volume for each action |
| Exterior doors | (Corrected October 21, 2026, D-114: the side is measured against the door's leaf, an exit opens for five seconds only, and a watch shuts a locked door found open; see [the survival bypass](#the-survival-bypass-found-by-the-second-playtest).) A door with sky on exactly one side (or both) is an exterior door and is locked **from outside**; from the inside it always opens; a door with a roof on both sides is an inside door and never locked. (Corrected October 23, 2026, D-150: a side also leads outside when open floor reaches the sky within three blocks, so a front door whose step is under the eaves is locked; see [the Thief track and burglary](thief-and-burglary.md#doors-d-147-d-150-d-151).) "Sky" is read from the blocks above the door within 64 blocks (not the light engine or the height map: both were wrong in the test world). | GameTests; on 507 real chunks the rule found 115 exterior doors and 181 inside doors |
| `LockpickSession` + `LockService` | The lock is a skill game that runs on the server: hidden sweet spots per pin, a coarse "feel" that is sometimes wrong by one step, strain that snaps picks, a hand tremor, a time limit. The client reports where the pick is and whether the button is down. A snapped pick is lost, jams the lock for a lockout and may ring an alarm; time running out or walking away loses nothing; an opened lock wears the pick now and then (35 percent). The same lock gives the same pins every time (seeded by position and world seed): retrying is learning, not a reroll. | 7 arithmetic GameTests with an honest solver that never reads a sweet spot; measured: a careful player opens an ordinary lock 99 to 100 percent of the time and a high-security one about 79 percent; a careless one almost never |
| `LockpickScreen` | Slide the pick with the mouse or the arrow keys, hold the left button or Space to press; Esc stops. The feel is shown as words ("Nothing yet", "A faint catch", "Close", "It gives") and brightness, never colour alone. | Seven poses captured on the real client (see Evidence, below) |
| `PropertyLocks` | The durable state of doors: which a lockpicker has opened and until when (90 seconds of **active** time, so nothing re-locks while the world is empty), which are jammed, which buildings have already been furnished. It grows with burglary, not with the city. Schema-versioned; a newer save is refused, not half read. | GameTest round-trip, pruning, cap, version refusal |
| `LockService.sweep` | Once a second, shuts every door whose window has lapsed (unless somebody is standing in it) so it is locked again. | GameTest; `BurglaryHarness` after a server restart |
| `BuildingStash` | The first time anybody gets into a building it is **furnished once** with a stash (a Household Safe of the class's policy, at the class's chance) on a sound spot of its first three floors. Decided once per building, ever, as a pure function of the building and the world seed. The safe is the existing one: one shared finite inventory, slow restock in active time, vigilance after a break-in, never free. | GameTest: placed, registered, once only, inside the room |
| The safe's own lock | Opening a locked safe is the same game on the server (profile from the safe's policy: 3 to 5 pins), needs the policy's number of lockpicks in the inventory and costs one when the pick snaps. | GameTest |
| Heat | A picked door adds 1 heat in the building's city, a snapped pick that rang an alarm adds 2 (existing heat rules: decays with active time, search chances). A `BuildingBreachEvent` is posted; `PoliceResponse` listens (D-122). | GameTest checks heat rose |

## The survival bypass found by the second playtest

**What the owner found:** in Survival, walking toward a locked exterior door of a generated apartment building with W held and right-clicking it opened it and let the player walk in, with and without a lockpick in the inventory.

**Reproduced first, in a real client** (`-PucPlaytest=doorwalk`: a fresh archipelago world, real X11 key and mouse events, a survival player standing three blocks from a real generated `flat_block` door, W held and a right click every four ticks for 3.5 seconds). With the playtest-day classification put back for the run: **the player ended 9.01 blocks inside the building and the door stood open**, for the door that faces the street. The GameTests that existed did not find it because their visitor always stood one and a half blocks from the door, never against it.

**Cause.** `BuildingGuard.outsideSide` decided which side of the door a player stood on by comparing the feet with the door *block's centre*. A closed door's leaf is 3/16 thick and fills the edge of its block opposite the door's facing. A player who walks into a door and stops stands 0.3 from the leaf's face, which is 0.0125 blocks past the centre, so for every door whose open side faces the street the street side read as the room side ("from the inside a door always opens") and the click opened it. W makes this happen on its own (it walks the player flush against the leaf); a stationary player at a distance never triggered it. The lockpick was irrelevant because the guard returned before it looked at the inventory. The same arithmetic locked a person standing against the leaf on the room side *out* of their own door for doors facing the room.

**What changed (the rule is now about the door, not about one click):**

| Part | Rule |
| --- | --- |
| Side of the door | Measured against the middle of the leaf (`LEAF_PLANE` = 0.40625 from the block's centre toward the edge opposite the facing), which is at least 0.39 blocks from where any player can stand on either side. |
| Leaving | A person inside opens the door by hand; it stays open for **five active seconds** (`EXIT_TICKS`), after which the sweep shuts it. During those seconds it is not unlocked for the street: a click from outside on the open door shuts it, a second click does not open it. (Somebody can still slip in behind a person who is leaving: that is a door held open, not a lock defeated.) |
| An open door that nobody opened on purpose | `BuildingGuard.enforce` shuts any locked exterior door of a building that stands open without a picked window, an exit window or a creative-mode builder within eight blocks. It runs the tick after any block update next to a door (redstone, a mod, a command) and every half second for every door within six blocks of a player. Cost: one scan of 13 x 13 x 6 blocks per player per half second, only in a chunk that holds a building. |
| Rigging a signal | A visitor cannot place a button, a pressure plate, a lever, a torch, redstone dust or a block of redstone within two blocks of a locked exterior door (`BuildingGuard.nearLockedDoor`). The street in front of a door is not part of the building, so the building guard does not cover it; a plate in front of a wooden door opens it. |
| Table | `PropertyLocks` schema 2 gives every remembered door its own close time (`closeAt`); schema 1 saves load with the picked window as the close time. |

**Evidence.**

* GameTests (all pass, 261 of 261 with the lock tests): `aVisitorWalkingIntoALockedDoorThatFacesTheStreetCannotOpenIt`, `...ThatFacesTheRoomCannotOpenIt` (both failed before the fix, with and without a lockpick, and for the person inside), `aLockedDoorThatRedstoneOpensIsShutAgainWithinATick`, `aVisitorCannotRigAPlateOrABlockOfRedstoneAgainstALockedDoor`, `aDoorOpenedFromInsideToLeaveShutsItselfAndStaysLockedToTheStreet` (two players), `aDoorFoundOpenWithoutAReasonIsShutExceptWhereABuilderWorks`, `theDoorTableOfAnOlderSaveGetsItsCloseTimeFromItsWindow`.
* The real client after the fix, the same scenario on a fresh world, four tries (street-facing and room-facing door, with and without five lockpicks, the pick tries clicking only once the player is against the door still holding W): **the player stayed outside, the door stayed shut, and with picks the lock screen opened** (the player 0.01, -0.80, -0.09 and -0.80 blocks from the door's centre, i.e. flush against it on the street side).

**Not covered, said plainly:** a modified client that forges its position or the hit location of its click; a mod that changes a door's state without a block update and without a player nearby; doors of buildings Lost Cities did not generate; the doors between the flats of one building (only the exterior doors are locked, so a flat's own door is the open question of the property ladder); trap doors, fence gates and garage-style doors; the exit real-client run (a person inside walking out through their own door) is scripted (`walkOutOfDoor`) but its result is recorded in the sprint report, not here; a two-client real run of a door.

## The lockpick screen no longer jitters

**What the owner found:** the lockpick screen felt jittery and buggy.

**Measured, not assumed.** A Python replica of the old model (checked against the documented success rates: 80 percent for a careful solver on high security) said that with the pick held still at the edge of a band the reading changed **27.5 times in four seconds (deadbolt), 32.0 (commercial), 35.5 (high security) and 32.0 (safe)**. Two causes: the "feel noise" flipped the near and close readings with a fresh random draw every tick, and the tremor moved the effective position by a sine of the tick count even while the button was up, so a pick resting near a band edge flipped about three and a half times a second as well. Those are intended difficulty implemented as flicker. The keyboard moved the pick 0.012 of the bar per tick, so with the arrow keys it advanced in steps of about four GUI pixels twenty times a second.

**What changed:**

| Part | Now |
| --- | --- |
| The reading | A function of where the pick is: the distance to the spot plus a smooth error that is fixed for the lock (`readError`: two sine swings 0.2 and 0.09 of the bar long, scaled by the lock's feel noise and tolerance, never steeper than the bar, so **sliding toward the spot never makes the reading worse and sliding away never makes it better**). A pick held still reads the same every tick. The press itself (the spot) is still exact. |
| The tremor | A slow wobble (`TREMOR_RATE`, one swing in 30 ticks) applied only while the button is down. The server sends its tick count and the lock's phase (independent of every sweet spot), and the screen draws the needle with the same function, so what is drawn is what the server uses. |
| The screen | A better reading shows at once, a worse one only after three ticks; the glow fades by ticks (about a tenth of a second whatever the frame rate); the keys move the pick between ticks (partial tick); the needle is drawn at a fraction of a GUI pixel; `-Duc.lock.latency=<ticks>` holds the client's reports back to play it against a slow connection. |
| Protocol | Version 2: the Open message carries the tremor amplitude and phase, the Progress message the server tick. The position and the press are still one message, so a late input is late as a whole and never presses on an old position. |
| A stalled server (October 24) | A lock under the hand runs by the clock: two of its ticks are at least 40 ms apart (`LockService.MIN_TICK_NANOS`). A server that stalls catches up by running its ticks back to back; in the sixth advanced burglary run a 2.3-second stall was followed by 45 ticks at once under a press whose pin had already set, the next pin's spot was elsewhere, and the pick snapped before the screen could show it. GameTest `aLockRunsByTheClockWhenTheServerCatchesUp`. |
| Difficulty | By the band, the pins, the tremor and the clock. A solver that reads the bar at a person's pace opens every lock; one that reads it slowly and carefully (ten seconds a pass) opens a deadbolt and runs out of time on a high-security lock (`aSlowCarefulHandOpensADeadboltButRunsOutOfTimeOnAHighSecurityLock`). |

**Evidence.** GameTests: `aPickHeldStillReadsTheSameFeelEveryTick` (five lock kinds, twelve seeds, sixteen positions each: zero changes in six seconds), `slidingTheBarTowardTheSpotNeverMakesTheReadingWorse...`, `theTremorIsSlowVisibleAndOnlyWhileThePickIsPressed`, `aLateInputIsLateAsAWholeAndNeverPressesOnAnOldPosition` (0, 2, 5 and 10 ticks late). The real client (`-PucPlaytest=lockjitter`, a real mouse slid across the bar, held still at a band edge, then pressed on the spot):

| Window, GUI scale, latency | Pick vs pointer after a slide | Reversals while sliding forward | Reading changes in a six-second slide | Reading changes held still at a band edge (4 s) | Pin set after the press began (hold takes 8 ticks) | Needle wobble while pressed |
| --- | --- | --- | --- | --- | --- | --- |
| 1280x720, GUI 2, none | 0.0 device px apart | 0 (one counted by the first run came from the harness warping the pointer, fixed) | 2 | 0 | 10 ticks | -1.8 to 0.6 GUI px |
| 3440x1440, GUI 2, 6 ticks (300 ms) | 0.0 device px apart | 0 | 2 | 0 | 19 ticks (8 + 6 + the way back) | -1.5 to 2.0 GUI px |

At 3440x1440 GUI 2 the bar is 680 device pixels wide inside a 3440-pixel window, one device pixel of the mouse moves the pick by 0.0015 of the bar and the band it must hold is 122 device pixels wide. The screenshot was looked at: the panel is small in the middle of the screen and legible.

**Not shown:** a person's hand on a mouse (the pointer in the scripted run moves along a smooth path), a real graphics card's frame rate (the run is software-rendered, where several ticks pass between frames), and the lock at 1920x1080 GUI 3 in this scenario (the static poses were captured at four viewports earlier).

## Doors follow who has the right to them (D-188, ninth pass)

The owner's playtest found two problems: an available rental let anybody in, and flats inside blocks stood open. Since D-188 the rules are these.

* **A home to let is locked to everybody.** Its street door and its card say "To let" and how to lease it from the Home page. A glance at the door registers the listing, so the card is right before anybody has tried the handle.
* **A lease, a Crew's permissions or ownership open it without a pick.** A lockpick works the lock as on any locked door.
* **A household's own flat or room door in a block is locked to everybody else, at any hour.** It says "Locked. Somebody lives here: <flat>", a lockpick works it, and it always opens from inside.
* **A shop's street door opens in its business hours and is locked outside them.** Staff doors stay locked to the public side. Daytime never makes a home public.
* **A street door can be named by the plan.** If neither side of a door has sky within a covered step (a deep canopy or a furnished terrace), a building's plan may name its street door as its first `door` spot, whose facing is the way out (`BuildingGuard.planStreetDoor`). The furnished buildings' first door spots are their street doors already. The private wiring tool names one only when a ground-floor door has open sky on one side, and otherwise names none.

Evidence: doors runs 2 and 3 through a real client (8 steps each), `ResidenceGameTests` and `BuildingSecurityGameTests`.

## What it does not do (read this before telling anyone it works)

* **Police respond only to an observed break-in** (an armed alarm or a witness in sight; [alarms, witnesses, police and stolen goods](police-response.md), D-122). A break-in nobody observed raises heat and posts the event and nothing more. The response is tested headlessly with fake players and has not been seen in a real street; say "an alarm or a witness can bring officers", never "the police stop burglars".
* **About two thirds of Lost Cities' buildings have no exterior door at all** (the probe found a ground-floor exterior door in 30 to 50 percent of the chunks of each building type, and 41 of 123 building chunks had no door of any kind). Those are sealed boxes: with the guard on, a player cannot get into them. They are not burglary targets and they are not public buildings; they are scenery until an entrance generator exists. The playable interiors of this version are the buildings Core places itself (Founders Row, the Row Flats and Motel, wayside shelters) and the buildings that do have a door.
* A roof reached by a stair of placed blocks from an adjacent open lot is inside the volume but is not guarded; a roof stair door is locked like any other exterior door, and an open stairwell without a door is a way in that this version does not close.
* A mod that changes blocks without firing Forge's events (a gun that shatters glass by its own rule, a vehicle that breaks a wall) is not covered until measured. TaCZ and Immersive Vehicles are in the development pack; neither has been tested against the guard.
* Fluids spreading from outside, fire and growing plants are not guarded.
* No building has a lease, a key or a resident the player can join: the legitimate way in is a Core-placed building or a door that is open. The Row Flats and Motel keep `ResidenceGuard`.
* The loot is the existing property loot (bread, paper, nuggets, redstone and so on): small and item-valued. No money is created by a break-in; items are worth what the buyer catalogue pays, and the stash restocks slowly and slower after a break-in (vigilance). The number of stashes in a city is a function of how many doors anybody has picked, not of the size of the city.
* Lost Cities' own loot chests are switched off in the archipelago (`generateLoot: false`): they were free, infinite-per-chunk vanilla loot.

## Why not Locks Reforged, Simple Locks or another lock mod

Locks Reforged 1.7.3 (Attribution-NonCommercial, 15 mixins, locks stored in chunk capabilities, not aware of buildings, no hook for `ResidenceGuard` or for a shared finite stock) would add a second authority over doors and containers and a licence this project cannot ship under; Simple Locks has no minigame. Reuse was checked first (`docs/technical/reuse-first.md`); the single-authority rule decided it.

## Evidence

* GameTests: `BuildingSecurityGameTests` (guard with controls, door by hand, with a pick, honest solver, careless snap and jam, the lockout lapsing, inside doors, the stash, persistence round trip, seeded locks) and `LockpickSessionGameTests`.
* A scripted run on a **real generated** apartment block, across a server restart: `scripts/run_burglary_check.py`, on a brand-new disposable archipelago world each time. **October 21, 2026, two seeds, both passed in full:**
  seed 5908, `building1` of Ironhaven (a six-floor `flat_block`, deadbolt lock with three pins, door at -25 72 0) and seed 777, `building5` of Ironhaven (a six-floor `flat_block`, door at 16 78 23).
  In each: the door is locked from the street and does not open by hand; a survival visitor breaks none of four wall blocks around it, while the same player breaks a control block that belongs to no building; a lockpick starts the lock and the honest solver opens it; the door stands open for its window and the heat rises by one; the building is furnished. On seed 777 the
  building rolled a stash (an `apartment_common` safe at 21 84 29): the safe asks for a pick, the solver opens it and one take moves the stock from 5 to 4 (3 bread). After a server stop and start: the door state, the furnished flag, the safe and its stock of 4, the
  break-in (vigilance 1) are all remembered, the lapsed door is locked again and a stranger cannot open it by hand. On seed 5908 the apartment rolled no stash (chance 0.7), so the stash steps ran only on seed 777.
  The earlier run on a workshop (`bg1`) is superseded. **The "player" in these runs is a scripted survival player and a solver, not a hand on a mouse.**
* Screens: `uc-lock-*` captures at the four supported viewports, listed in `content/qa/gui-coverage.json`.
* Not shown: a person picking a lock, two players at one door, a vehicle or a gun against the guard, the real client picking a real door (the screen is photographed in poses and the server logic is driven by a solver, not by the mouse).
