# City building protection, locks and burglary (D-105)

Status: **implemented and tested by GameTests and by a scripted run on a generated Lost Cities apartment block; nobody has played it.** Each section says what the evidence is and what it is not.

## What the owner's first playtest found

Nothing stopped a player from walking up to a generated city building and digging in: Lost Cities' walls, windows and furniture are ordinary blocks, and the property systems of the starter slice
(registered homes, the Household Safe) covered only what an operator had placed and registered. "Burglary" did not exist: a safe cost lockpicks, a click and no skill.

## What exists now

| Piece | What it does | Evidence |
| --- | --- | --- |
| `CityBuildings` | Asks Lost Cities' own plan (not a scan) what stands in a chunk: building type, floors, cellars, city level, and so the street height. A building's **protected volume** is its chunk from under its deepest cellar (`street - 6 x (cellars + 1) - 2`) to over its roof (`street + 6 x floors + 8`). Open lots, streets, parks and the countryside are not buildings and are not protected. | GameTests (volume arithmetic, classes and rules); the real plan was probed on 507 generated chunks (`scripts/run_city_probe.py`) |
| `data/underground_city/building_security/classes.json` | A **heuristic**, said so plainly: Lost Cities does not know what a building is for, so a building's class comes from its city, its type prefix and its floor count. Seven classes: cottage (latch), apartment block (deadbolt), apartment tower (commercial), workshop (commercial), office (commercial), high-value premises (high-security; the downtown centrepiece), public building (unlocked). | GameTest checks every class loads, every rule names a class, the tiers climb in difficulty, every stash policy exists |
| `BuildingGuard` | Inside a building's volume a survival player cannot break, place, pour, push (piston), blast (explosion) or let a creature grief. Creative mode and operators are never stopped. A registered home keeps `ResidenceGuard`'s authority. | GameTests with a control outside the volume for each action |
| Exterior doors | A door with sky on exactly one side (or both) is an exterior door and is locked **from outside**; from the inside it always opens; a door with a roof on both sides is an inside door and never locked. "Sky" is read from the blocks above the door within 64 blocks (not the light engine or the height map: both were wrong in the test world). | GameTests; on 507 real chunks the rule found 115 exterior doors and 181 inside doors |
| `LockpickSession` + `LockService` | The lock is a skill game that runs on the server: hidden sweet spots per pin, a coarse "feel" that is sometimes wrong by one step, strain that snaps picks, a hand tremor, a time limit. The client reports where the pick is and whether the button is down. A snapped pick is lost, jams the lock for a lockout and may ring an alarm; time running out or walking away loses nothing; an opened lock wears the pick now and then (35 percent). The same lock gives the same pins every time (seeded by position and world seed): retrying is learning, not a reroll. | 7 arithmetic GameTests with an honest solver that never reads a sweet spot; measured: a careful player opens an ordinary lock 99 to 100 percent of the time and a high-security one about 79 percent; a careless one almost never |
| `LockpickScreen` | Slide the pick with the mouse or the arrow keys, hold the left button or Space to press; Esc stops. The feel is shown as words ("Nothing yet", "A faint catch", "Close", "It gives") and brightness, never colour alone. | Seven poses captured on the real client (see Evidence, below) |
| `PropertyLocks` | The durable state of doors: which a lockpicker has opened and until when (90 seconds of **active** time, so nothing re-locks while the world is empty), which are jammed, which buildings have already been furnished. It grows with burglary, not with the city. Schema-versioned; a newer save is refused, not half read. | GameTest round-trip, pruning, cap, version refusal |
| `LockService.sweep` | Once a second, shuts every door whose window has lapsed (unless somebody is standing in it) so it is locked again. | GameTest; `BurglaryHarness` after a server restart |
| `BuildingStash` | The first time anybody gets into a building it is **furnished once** with a stash (a Household Safe of the class's policy, at the class's chance) on a sound spot of its first three floors. Decided once per building, ever, as a pure function of the building and the world seed. The safe is the existing one: one shared finite inventory, slow restock in active time, vigilance after a break-in, never free. | GameTest: placed, registered, once only, inside the room |
| The safe's own lock | Opening a locked safe is the same game on the server (profile from the safe's policy: 3 to 5 pins), needs the policy's number of lockpicks in the inventory and costs one when the pick snaps. | GameTest |
| Heat | A picked door adds 1 heat in the building's city, a snapped pick that rang an alarm adds 2 (existing heat rules: decays with active time, search chances). A `BuildingBreachEvent` is posted for later witness, evidence and police systems. | GameTest checks heat rose |

## What it does not do (read this before telling anyone it works)

* **No police or witness responds to a break-in.** Heat is recorded and an event is posted; nobody reacts to it yet. Say "burglary raises your heat", not "police respond".
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
* A scripted run on a **real generated** apartment block, across a server restart: `scripts/run_burglary_check.py` (results in [archipelago-world.md](archipelago-world.md) once run).
* Screens: `uc-lock-*` captures at the four supported viewports, listed in `content/qa/gui-coverage.json`.
* Not shown: a person picking a lock, two players at one door, a vehicle or a gun against the guard, the real client picking a real door (the screen is photographed in poses and the server logic is driven by a solver, not by the mouse).
