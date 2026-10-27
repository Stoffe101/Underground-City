# The Thief track and the burglary loop (D-143 to D-147, D-149 to D-151, D-159)

Status: **implemented; GameTested (`ThiefGameTests`, 16 cases, and the police, building and roadside suites); one scripted real-client scenario of a new burglar (`-PucPlaytest=thief`, see [Evidence](#evidence)); nobody has played any of it.** It extends [building protection](building-protection.md) and [police response](police-response.md), which describe the locks, stashes, alarms and police it builds on.

## The loop

1. **Find a target and read it.** (D-157: a glance at any street door shows its tier, and casing reads in layers; see [property intelligence](property-intelligence.md).) Crouch with an empty hand and use a building's street door: a card (`CasingScreen`) shows the place's **tier** (six pips and a word: Shabby, Modest, Comfortable, Well-off, Wealthy, Exceptional) and its **lock**, and whether the thief could try it. With Thief skills it also reads the alarm, the cameras, who is in now, when it will be empty, the safe and whether it was broken into lately; what the thief cannot read yet names the skill that would tell them (`Casing`).
2. **Get in.** The lock screen of [building protection](building-protection.md), now with **spool pins** (a pin that false-sets: held halfway, it turns the pick back and its sweet spot jumps), **raking** for cheap locks (a latch gives 70 percent of the time, a deadbolt 35; a failed rake jolts the pick and makes a noise), a **Pro Pick Set** (a crafted tool of 40 uses that high-security doors and vault safes ask for) and **safecracking** (a skill wall and vault safes ask for). Getting into a building the first time pays Thief XP by its tier.
3. **Search the rooms.** Right-click a piece of Modern Life furniture in somebody else's city building (night stand, kitchen cabinet or drawers, fridge, microwave, bookshelf, wall shelf, office machine, till, turntable, workbench, bin): a short bar, then what was found. Each piece refills after four hours of active play; a two-block piece is one spot. Loot fits the room and the tier (`data/underground_city/search_spots/furniture.json`): a shabby drawer is mostly empty, a wealthy one rarely; tier 4 and above draws twice. A person of the household in the room may see it (a witness for the police).
4. **Get away.** Alarms, witnesses, cameras and police as before, except that officers now look (see [Police](#police-who-look)), and an officer on the beat who sees a thief at a lock comes at once (D-155).
5. **Sell.** Finds are **marked stolen with the thief's id**. Thirteen original valuables (wristwatch, gold ring, pearl necklace, smartphone, tablet, digital camera, vintage wine, rare vinyl, designer handbag, cash box, power drill, small painting, bearer bonds) have no honest price and sell only to **fences**, who now have budgets, specialties and better rates for Thief skills (`FenceService`). A fence's building opens its door at the fence's hours to anybody carrying stolen goods (D-147).
6. **Grow.** Thief XP raises a Thief level; each level is a Thief point to spend on the Thief tree at a terminal's **Thief** page.

## The Thief track (D-143)

A general skill on every character, separate from the career. **Level** = 1 + floor(sqrt(XP / 80)), at most 25; each level after the first is one **Thief point**, which never mixes with career points. An older save loads with an empty track.

| Thief XP for | Amount | Paid |
| --- | --- | --- |
| Getting into a building | 25 per tier | once per thief and building |
| Cracking a safe | 40 per tier | once per thief and safe |
| Slipping a police response | 20 per tier | once per response |
| Beating an alarm panel or a camera | 15 | once per thief, building and device |
| A search | an eighth of what was found is worth, at least 1, at most 30 | once per refill of a spot |
| A fence sale | a credit in three of what goods the seller took themselves fetched, at most 400 | goods somebody else took, or handed over, pay nothing |

The records are saved per player and bounded. Repeated failure, a second visit, handing goods between players and the same safe again pay nothing, so careful success is the only way up.

The tree (`data/underground_city/specialization_trees/thief.json`, 16 skills, 24 points):

| Branch | Skills (Thief level, points) |
| --- | --- |
| Core | Casing (2, 1) |
| Locks | Steady Hands (3, 1): half the tremor, less strain. Raking (4, 1). Feel for the Pins (5, 2): the exact reading, spools announced. Pro Tools (8, 2). Wire Work (9, 2): jam an alarm panel's entry delay, loop a camera. Safecracking (10, 2): wall and vault safes, one pick fewer. |
| Stealth | Soft Steps (3, 1): crouched, a witness hears less. Quick Hands (4, 1): searches in half the time, silently. Know Their Routines (6, 1): when a place empties. Camera Sense (7, 1): a warning before a camera holds you, and its cone shown. Clean Getaway (9, 2): police give up in 15 seconds, not 30. Ghost (14, 2; needs Clean Getaway and Camera Sense): slip alarms, be seen less. |
| Fencing | Fence Contacts (3, 1): the nearest fence pointed out. Haggler (6, 2): 8 points better at every fence. Specialist Buyers (10, 2): 15 points better on a fence's specialties. |

## Property tiers (D-144)

Every building class has a tier and a safe profile (`data/underground_city/building_security/classes.json`, and `building_security/furnished.json` for the furnished cities): cottage 1, apartment block 2, house, workshop and shop 3, apartment tower, office and warehouse 4, club 5, vault 6, a public building 0 (not a target). A household safe stands to tier 3, a wall safe at tiers 4 and 5, a vault safe at 6. Towers, offices and warehouses have the new `secured` lock (4 pins, 1 spool).

## Cameras (D-146, D-149)

Core watches camera blocks in a building (Modern Life's CCTV camera, ours, or SecurityCraft's when its trial set is loaded): a 12-block cone of 50 degrees either side of where it looks, with a line of sight; a camera that sees a thief calls the police (once per camera and thief every two minutes). Camera Sense warns first; Wire Work, or a Technician's Bypass Tooling, with a Bypass Kit loops every camera of the building for ten minutes. **Nothing force-loads chunks or shows a remote feed.** The furnished generator fits cameras by tier: one at tier 4 and two at tiers 5 and 6, inside near a street door, high on a wall (or under the ceiling) and aimed at the first cells inside it with a line of sight (`scripts/city_content.py`, `place_cameras`). It hangs a camera only where Lost Cities keeps it (D-153): the first cameras by tier hung over the club's and the warehouse's south doors looking north, at a wall Lost Cities writes after them, and no generated world ever had them, until a world was read back after the advanced burglary run found the club without one. Nobody is reported in a shop while it is open, at a fence's at their hours, or in a building where they hold a key (a tenant, their Crew, their guests).

## Police who look (D-146)

Officers chase a thief only while one of them can see them (32 blocks, line of sight); otherwise they go to the last place the thief was seen (the door, until somebody has) and search round it. Out of sight for 30 seconds (15 with Clean Getaway) and more than 20 blocks from the nearest officer, the thief has got away, which pays Thief XP; an arrest needs line of sight.

## Doors and staff-only rooms (D-147, D-150, D-151, D-159)

* A **shop** is open while its shopkeeper is in: its street door opens for customers. A **fence's** building opens its street door at the fence's hours (21:00 to 03:00, and only on nights the fence is in) to anybody carrying stolen goods.
* **While a building is open (D-159),** its **public floor** is what a customer reaches from the street doors, on the floor they open onto, without passing another door. Core reads it from the building's own walls and doors (`StaffZones`: a bounded flood over one floor of one chunk, cached for a minute). Its alarm is off while its people are in.
  * **Out on the floor:** nothing is searched and no safe is cracked ("Not out on the floor while it is open: somebody is watching"). Its cameras do not report a customer.
  * **Everything else is staff only:** a back room behind a door, the floors above and below. There, furniture, containers and safes may be searched and cracked while the place is open, under the usual witnesses (anybody of the household who sees it calls the police), and the cameras watch as at night.
  * **A staff door** is an inside door between the public floor and a back room. It is shut and locked to the public side like the building's street door, so a lockpick opens it on the lock screen, and it opens by hand from behind.
  * **A person in a staff-only area** whom somebody of the household can see is told "Staff only" and has five seconds to get out of their sight before the police are called.
  * The rule before D-159 was that nobody searched an open building at all. It made the back of a shop unreachable by day for no reason a player could see.
  * The club's back office is upstairs, so it is a staff-only area, reached past the bar staff. Valewood's bakery has a **stock room** behind a staff door, out of the counter's sight, with flour barrels and the day's takings (a desk screen, searched as the till; new worlds only).
  * **Evidence:** a GameTest reads the zones of a small shop built for it (floor, back room, staff door, the pavement under an awning, a doorway). **Organic run 5** (`-PucPlaytest=organic`) passed all seven steps through a real client, with no spatial shortcut once the thief stood on the street:
    1. In by day at an open bakery's front door, on foot.
    2. Refused at the counter ("Not out on the floor").
    3. The stock room's door locked ("Staff only").
    4. That door picked on the lock screen.
    5. The barrel and the till searched (one stolen unit, marked with the thief).
    6. Told "Staff only" once by the household, and out of sight in time.
    7. Out to the street on foot.
  * **Defects found on the way:** organic run 3 found that a person in a doorway or on the pavement under an awning counted as "in the back" (fixed: the ground outside the street doors is read too). The roof test now uses the block heightmap, because the sky light lags behind a change and passes through glass.
* **Fifth pass fixes (D-164 and its follow-ups):**
  * **A staff door is opened by its key, a pick or the household, never by redstone.** Five times a second, the staff doors of the buildings around each player that stand open while powered (a pressure plate or a button left beside one) are shut again. The power stays, so the door stays shut until the signal goes off and on again. Nothing is removed from the world. The fourth pass had noted that redstone could open a staff door. `StaffZones.status()` counts the doors shut this way. GameTested (`StaffZoneGameTests`: a staff door held open by redstone is shut once, its power left on, and a street door is not touched); no real-client run covers it.
  * **A back room holds the business's stock.** An empty chest or barrel off the public floor of a shop, pub, club, workshop or warehouse is filled once, the first time it is searched: three draws of the business's own goods (a bakery's flour, wheat, sugar and bread; a pub's berry wine and cider; a club's spirits; a workshop's fasteners and brackets), marked stolen like any find. A container that holds anything already is left alone. Data: the `stock` kind in `search_spots/furniture.json`. GameTested (`WorkshopGameTests.anEmptyBackRoomBarrelHoldsTheBusinesssStockAndAFullOneIsLeftAlone`). The fourth pass had found the bakery's flour barrels empty.
  * **Fences value the workshop goods** at what the cheapest shop counter charges for them, so stolen back-room stock fences for less than an honest sale.
  * **Cameras and alarm panels against the graded tier (investigated, no change):** the cameras and alarm panels of a furnished building follow its class's own tier, because they are part of how its chunks generate. A graded building may therefore have more or fewer devices than its tier label suggests. What a thief meets is consistent with what they read: the casing card counts the cameras actually in the building (`SecurityDevices.cameras`) and says whether the alarm has a panel by the door. So the inconsistency is only in the label, and changing the generated buildings of existing worlds was not justified.
  * **Loot value by tier:** `python scripts/burglary_audit.py` follows the search roll exactly and prints the expected fence credits of one search of each kind and of one visit to a typical building, by tier. Fence credits a visit, at a fence's usual 48% rate:

    | use | tier 1 | tier 2 | tier 3 | tier 4 | tier 5 | tier 6 |
    | --- | --- | --- | --- | --- | --- | --- |
    | house | 23 | 33 | 56 | 144 | 173 | 199 |
    | flats | 13 | 19 | 31 | 80 | 92 | 98 |
    | shop | 38 | 50 | 63 | 88 | 102 | 118 |
    | pub | 41 | 54 | 69 | 91 | 95 | 108 |
    | club | 49 | 70 | 91 | 132 | 149 | 169 |
    | office | 3 | 11 | 34 | 74 | 100 | 158 |
    | workshop | 30 | 39 | 46 | 72 | 83 | 88 |
    | warehouse | 51 | 55 | 56 | 62 | 63 | 64 |

    The rules it checks hold: a richer tier is never worth less, and no single search averages more than 120 credits. (Rerun on October 25, eighth pass: the sixth pass's beginner loot lifted a tier I home from 2 to 23 credits a visit and a tier II from 18 to 33.)

    **Earnings by stage (eighth-pass review, from these numbers):**
    * **Beginner** (tier I and II homes and shops, a lockpick, no skill): 13 to 70 credits a visit at a fence.
    * **Intermediate** (tier III and IV, a Pro Pick Set): 31 to 144.
    * **Advanced** (tier V and VI, safes, the bonded store raid on top): 64 to 199 a visit.

    A careful visit takes roughly five to ten minutes of casing, entry, searching and the walk to a fence. That puts a beginner on a few hundred credits an hour before losses, around or under a legal career's 315 to 521 net an hour at level 5 in the economic model, and an advanced burglar above it, with the risk of arrest, confiscation and heat. A fence's budget caps what it pays at 360 (Ironhaven) to 420 (Blackwater) credits an hour, so burglary cannot outrun the best legal work for long. This is an estimate from expected values, not a measured session, and no person has played it.

* A **street door** is one with a side that leads outside: the cell beside it open to the sky, or open floor leading within three blocks to a cell that is (a front step under the eaves, a porch). Before D-150 only the first counted, and a Valewood house's front door, its step under the eaves and its hall roofed, was an "inside door" that was never locked.
* A house **to let** (D-127) is a vacant listing: its door belongs to the home's own guard, and it is neither cased nor searched.
* An **iron** street door (the club, the office, the warehouse, the workshop, the Ironhaven flats) opens by no hand; where the guard lets a person through one, Core opens or shuts it as a click would a wooden door (D-151). Before, a tenant of an Ironhaven flat could not get in, and a thief was shut in the club once its door closed.

## Breaking things, and the city putting them back (D-190)

**The permit.** Picking a lock gives the thief a permit for that building for 5 minutes of active play (`data/underground_city/burglary_damage/rules.json`).

**What may be broken.** With the permit the thief may break:
* furniture and decoration (`underground_city:burglary_breakable`, and the furniture mods' blocks that are not whole cubes);
* windows;
* light inner walls (`underground_city:burglary_breakable_walls`, and a furniture mod's whole cube).

**What never breaks.** These hold whatever the permit:
* the shell, meaning the floors and ceilings and any block beside the outside air (open sky, or open sky a few steps out under eaves);
* doors, trapdoors and gates;
* anything that holds items;
* Core's blocks and anything tagged `underground_city:burglary_protected`;
* a player's home.

**How a break works.** A break drops nothing. A bed or a door takes its other half with it, and a torch goes with the wall it stood on. Every broken cell is recorded with its block entity: `underground_city_burglary_damage`, kept across restarts.

**Putting it back.** After 30 minutes of active play (`restore_active_minutes`; an operator may change it with `-Duc.burglary.restoreMinutes=N`), the city puts the building back. The building must be loaded and unoccupied, though after twice the wait it is restored even if occupied. No chunk is loaded for it.

When it restores a building:
* it never overwrites a cell somebody has filled;
* it never touches a player's home;
* it shuts and relocks the building's picked doors.

Nothing a thief carried away is copied, because nothing breaking a block dropped exists to be copied.

**Who notices.** A broken window trips an armed alarm. Neighbours may hear damage, and officers on the beat may see it ([police response](police-response.md)).

Evidence:
* `BurglaryDamageGameTests`.
* Wreck run 5 through a real client, 10 steps: a window, a bed and an inner wall broken; the outer wall held; the record kept across a save, quit and reopen; the house put back and its door relocked.
* Wreck runs 2 and 3 broke a plank wall the game then took for an inner wall. It was the outer wall, under the eaves. That shell test is fixed.

## What it does not do

* Nobody has played it. The real-client scenarios are scripts with logged shortcuts.
* There is no crowbar, no window entry and no climbing in; a door is the only way in.
* Police on the beat (D-155) see a thief at a lock or a door giving way; they do not watch windows; GameTests and a scripted real client caught at a lock (patrol runs 2 and 6) have seen them (see [police response](police-response.md#police-on-the-beat-d-155)). There is no pursuit across a city boundary or after a disconnect beyond what [police response](police-response.md) says.
* The thirteen valuables have sprites but no 3D models of their own.

## Evidence

**GameTests** (`ThiefGameTests`): the tree loads and is affordable; Thief points never become career points; an old save loads with an empty track; getting in pays once per thief by tier; search loot by tier and its refill; a two-block piece is one spot; fence rates with Haggler and Specialist Buyers within their caps; own goods pay XP and handed goods do not; a solver opens spool locks (at least 21 of 30); Steady Hands and Feel for the Pins; the tool and skill a lock asks for; raking; the camera cone and line of sight; casing for a novice and an expert; the shipped tiers. `BuildingSecurityGameTests` checks a door under the eaves and a closed porch; `PoliceResponseGameTests` the line-of-sight chase on a floor of its own.

**The new burglar, through a real client** (`-PucPlaytest=thief`, `ThiefScenario`): see the sprint report for the run, its screenshots and every scripted shortcut.

**The advanced burglary, through a real client** (`-PucPlaytest=heist`, `HeistScenario`, heist runs 8 and 10): a seasoned thief cases the Blackwater club, is refused with lockpicks, picks its high-security door with a Pro Pick Set, is held by a camera that calls the police, cracks the wall safe, takes its goods marked with the thief's name and stays out of sight until the officers give up. Ten logged shortcuts; see the sprint report.

## Files

`Thievery`, `Casing`, `CasingScreen`, `BurglarySearch`, `BurglaryNetwork`, `ClientBurglary`, `SecurityDevices`, `LockpickSession`, `LockService`, `LockProfiles`, `FenceService`, `FenceCatalog`, `PoliceResponse`, `BuildingGuard`, `TerminalScreen` (the Thief page), `ThiefGameTests`, `ThiefScenario`; data in `specialization_trees/thief.json`, `search_spots/furniture.json`, `lock_profiles/`, `fences/`, `building_security/`.
