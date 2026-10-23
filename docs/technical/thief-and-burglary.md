# The Thief track and the burglary loop (D-143 to D-147, D-149 to D-151)

Status: **implemented; GameTested (`ThiefGameTests`, 16 cases, and the police, building and roadside suites); one scripted real-client scenario of a new burglar (`-PucPlaytest=thief`, see [Evidence](#evidence)); nobody has played any of it.** It extends [building protection](building-protection.md) and [police response](police-response.md), which describe the locks, stashes, alarms and police it builds on.

## The loop

1. **Find a target and read it.** Crouch with an empty hand and use a building's street door: a card (`CasingScreen`) shows the place's **tier** (six pips and a word: Shabby, Modest, Comfortable, Well-off, Wealthy, Exceptional) and its **lock**, and whether the thief could try it. With Thief skills it also reads the alarm, the cameras, who is in now, when it will be empty, the safe and whether it was broken into lately; what the thief cannot read yet names the skill that would tell them (`Casing`).
2. **Get in.** The lock screen of [building protection](building-protection.md), now with **spool pins** (a pin that false-sets: held halfway, it turns the pick back and its sweet spot jumps), **raking** for cheap locks (a latch gives 70 percent of the time, a deadbolt 35; a failed rake jolts the pick and makes a noise), a **Pro Pick Set** (a crafted tool of 40 uses that high-security doors and vault safes ask for) and **safecracking** (a skill wall and vault safes ask for). Getting into a building the first time pays Thief XP by its tier.
3. **Search the rooms.** Right-click a piece of Modern Life furniture in somebody else's city building (night stand, kitchen cabinet or drawers, fridge, microwave, bookshelf, wall shelf, office machine, till, turntable, workbench, bin): a short bar, then what was found. Each piece refills after four hours of active play; a two-block piece is one spot. Loot fits the room and the tier (`data/underground_city/search_spots/furniture.json`): a shabby drawer is mostly empty, a wealthy one rarely; tier 4 and above draws twice. A person of the household in the room may see it (a witness for the police).
4. **Get away.** Alarms, witnesses, cameras and police as before, except that officers now look (see [Police](#police-who-look)).
5. **Sell.** Finds are **marked stolen with the thief's id**. Thirteen original valuables (wristwatch, gold ring, pearl necklace, smartphone, tablet, digital camera, vintage wine, rare vinyl, designer handbag, cash box, power drill, small painting, bearer bonds) have no honest price and sell only to **fences**, who now have budgets, specialties and better rates for Thief skills (`FenceService`). A fence's building opens its door at the fence's hours to anybody carrying stolen goods (D-147).
6. **Grow.** Thief XP raises a Thief level; each level is a Thief point to spend on the Thief tree at a terminal's **Thief** page.

## The Thief track (D-143)

A general skill on every character, separate from the career. **Level** = 1 + floor(sqrt(XP / 90)), at most 25; each level after the first is one **Thief point**, which never mixes with career points. An older save loads with an empty track.

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

Core watches camera blocks in a building (Modern Life's CCTV camera, ours, or SecurityCraft's when its trial set is loaded): a 12-block cone of 50 degrees either side of where it looks, with a line of sight; a camera that sees a thief calls the police (once per camera and thief every two minutes). Camera Sense warns first; Wire Work, or a Technician's Bypass Tooling, with a Bypass Kit loops every camera of the building for ten minutes. **Nothing force-loads chunks or shows a remote feed.** The furnished generator fits cameras by tier: one at tier 4 and two at tiers 5 and 6, inside above a street door, looking into the room (`scripts/city_content.py`, `place_cameras`). Nobody is reported in a shop while it is open, at a fence's at their hours, or in a building where they hold a key (a tenant, their Crew, their guests).

## Police who look (D-146)

Officers chase a thief only while one of them can see them (32 blocks, line of sight); otherwise they go to the last place the thief was seen (the door, until somebody has) and search round it. Out of sight for 30 seconds (15 with Clean Getaway) and more than 20 blocks from the nearest officer, the thief has got away, which pays Thief XP; an arrest needs line of sight.

## Doors (D-147, D-150, D-151)

* A **shop** is open while its shopkeeper is in: its street door opens for customers and its cameras do not report them. A **fence's** building opens its street door at the fence's hours (21:00 to 03:00, and only on nights the fence is in) to anybody carrying stolen goods. **Nobody searches** a building while it is open.
* A **street door** is one with a side that leads outside: the cell beside it open to the sky, or open floor leading within three blocks to a cell that is (a front step under the eaves, a porch). Before D-150 only the first counted, and a Valewood house's front door, its step under the eaves and its hall roofed, was an "inside door" that was never locked.
* A house **to let** (D-127) is a vacant listing: its door belongs to the home's own guard, and it is neither cased nor searched.
* An **iron** street door (the club, the office, the warehouse, the workshop, the Ironhaven flats) opens by no hand; where the guard lets a person through one, Core opens or shuts it as a click would a wooden door (D-151). Before, a tenant of an Ironhaven flat could not get in, and a thief was shut in the club once its door closed.

## What it does not do

* Nobody has played it. The real-client scenario is a script with logged shortcuts.
* There is no crowbar, no window entry and no climbing in; a door is the only way in.
* Police do not patrol; they come only when called. There is no pursuit across a city boundary or after a disconnect beyond what [police response](police-response.md) says.
* The thirteen valuables have sprites but no 3D models of their own.

## Evidence

**GameTests** (`ThiefGameTests`): the tree loads and is affordable; Thief points never become career points; an old save loads with an empty track; getting in pays once per thief by tier; search loot by tier and its refill; a two-block piece is one spot; fence rates with Haggler and Specialist Buyers within their caps; own goods pay XP and handed goods do not; a solver opens spool locks (at least 21 of 30); Steady Hands and Feel for the Pins; the tool and skill a lock asks for; raking; the camera cone and line of sight; casing for a novice and an expert; the shipped tiers. `BuildingSecurityGameTests` checks a door under the eaves and a closed porch; `PoliceResponseGameTests` the line-of-sight chase on a floor of its own.

**The new burglar, through a real client** (`-PucPlaytest=thief`, `ThiefScenario`): see the sprint report for the run, its screenshots and every scripted shortcut.

## Files

`Thievery`, `Casing`, `CasingScreen`, `BurglarySearch`, `BurglaryNetwork`, `ClientBurglary`, `SecurityDevices`, `LockpickSession`, `LockService`, `LockProfiles`, `FenceService`, `FenceCatalog`, `PoliceResponse`, `BuildingGuard`, `TerminalScreen` (the Thief page), `ThiefGameTests`, `ThiefScenario`; data in `specialization_trees/thief.json`, `search_spots/furniture.json`, `lock_profiles/`, `fences/`, `building_security/`.
