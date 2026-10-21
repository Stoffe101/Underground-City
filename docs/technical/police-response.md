# Alarms, witnesses, police and stolen goods (D-122)

Status: **implemented; arithmetic, the rules and the settlement are GameTested headlessly (`PoliceResponseGameTests`, 14 cases); one scripted real client has run the whole sequence once in a generated street (a pick on the real lock screen, an alarm, a forced safe, an officer who walked to the thief and arrested them); no person has played it and nobody has been chased by it.** This document says what exists, what it deliberately does not do, and what was evaluated and not adopted. It replaces the sentence "no police or witness responds to a break-in" in [building protection](building-protection.md) and [population](population.md): since this change one does, in the ways below and no others.

## The rule: the police are not psychic

A response starts only from an **observed event**. There are exactly two ways a break-in is observed.

1. **An alarm.** A building class may carry a `response` profile in `data/underground_city/building_security/classes.json` (and in the furnished package's own classes). Whether *this* building's alarm is armed is a hash of the building and the world seed against the class's `alarm_chance` (`BuildingStash.roll`, a fixed salt), so it is the same answer every time, for every player, and a thief who has learned a building keeps what they learned. A pick that snaps in a lock and rings the lock's own bell (the existing `alarm_chance` of the lock profile) also raises the alarm of any building, with or without a class profile.
2. **A witness.** At the moment a door is picked open (or a pick snaps) any person of the city (`TownspersonEntity`, not a police officer) within 20 blocks of the door and with a line of sight to the thief is a witness. One roll is made for the group, using the city's own `witness_chance` from `city_laws/<city>.json`, three points more for each point of the thief's heat there, less for concealment skills, and a little more for a second and third witness. A person is only out in the street at the hours the roster says (nobody but guards between ten at night and six in the morning), so **a night break-in is seen less often than one at noon**: that is a real choice and it falls out of the existing schedule, not from a special rule.

Nothing else raises a response. A creative or spectating player is never policed (the same rule as checkpoint searches).

### Entering quietly, and what a thief can choose

* **A clean pick is a quiet one.** `LockpickSession` now records the most strain the pick was ever under (`peakStrainPercent`). A door picked without the pick ever passing **40 percent** of its strain limit (`LockService.QUIET_STRAIN_PERCENT`) is a *quiet* entry: it does not trip a building's armed alarm, unless that class's alarm chance is 0.9 or more (the high-value premises have one in every building and it cannot be slipped past). The thief is told when it worked ("Something in here is wired to an alarm. Your pick was clean and it did not hear you"), which is also how they learn which buildings are wired. A pick that snaps and rings the lock's own bell is never quiet. A *witness* is not affected by a clean pick. This makes the lockpicking skill of the player, not a number, the thing that decides how loud a burglary is: a careful hand's picks are mostly quiet, a careless hand's rarely (`LockpickSessionGameTests`, 40 seeds each).
* **Choose when:** a witness needs a person in sight, and the people of a city are in the street by day, so a night break-in is seen less often.
* **Choose how long:** the arrival time is the class's, so a thief decides whether the rooms of an alarmed building are worth the minutes they cost; the stash's vigilance (existing) rises with each forcing.
* **Choose where to be:** officers wait at the door and chase on foot at a sprinting pace; the response ends when the thief is out of reach for the class's window.
* **Not available yet:** disabling an alarm by a skill, a tool or hacking a panel; cameras and motion sensors; cutting power; a fence who buys marked goods; a disguise. They are the obvious next steps and none exists.

## What a response does

| Step | What happens | Where it lives |
| --- | --- | --- |
| Raised | One `Dispatch` per thief and building (a second noise only brings the officers sooner, never later). Capped at 24 in the world. Held in memory only. | `PoliceResponse.noticed` |
| Alarm | The bell rings at the door every two seconds for twenty seconds. | `PoliceResponse.step` |
| Arrival | After the class's `dispatch_seconds` (a neighbour's call is never faster than 150 s) in active time, `officers` bodies appear at street spots near the building (the population service's own spot finder: loaded, street level, open to the sky, 14 to 64 blocks from the thief), wearing the guard outfit, named "Officer <surname>" from the city's guard roster. A wider ring of chunks is tried on each of five attempts; if no street is found the response is dropped and nobody is arrested. | `PoliceResponse.arrive` |
| Approach | Officers walk to the building's door, and once the thief is within 40 blocks, after the thief at 1.25 times a stroller's speed (about a sprinting player's pace; a walking thief is caught, a running one is not). They never open doors, never enter a building, never strike anyone and cannot be hurt. At 14 blocks the first says "Police! Stay where you are." | `TownspersonEntity.ErrandGoal` |
| Arrest | An officer within three blocks arrests the thief (`CheckpointService.arrest`, the same crash-safe prepared record as a checkpoint search): what the thief carries (pockets, hands, armour and the packs and boxes in them; never a chest, a house or another player's pack) that is marked stolen or forbidden by the city's law is taken, the city's prohibited-goods fine is charged up to what the wallet holds (never below zero), the city's heat rises by 3, and the arrest is written into the search history. | `CheckpointService.arrest` |
| End | The officers stand down and leave after eight seconds. A response also ends when the class's `window_seconds` pass ("The police have gone"), when the thief is more than 110 blocks from every officer and the door ("You got away"), when they die or leave the dimension, or when the server stops. | `PoliceResponse.pass` |

**Nobody is punished for being away.** A dispatch is held in memory and is dropped the moment its thief logs out (`PlayerLoggedOutEvent`), dies or goes to creative; on a restart nothing is pending. Arrest, search and fine happen only to an online, living survival player standing within three blocks of an officer, and only for a break-in that player made.

### Security levels

The levels are data, in the order the owner asked for: the more valuable the premises, the likelier an alarm, the quicker the arrival and the more officers. `PoliceResponseGameTests` checks that the order holds for the stock classes.

| Class | Alarm armed | Police arrive after | Officers | They look for |
| --- | --- | --- | --- | --- |
| cottage, public | none of its own (a neighbour's call: 150 s) | | 1 | 120 s |
| flat block | 25 % of buildings | 120 s | 1 | 120 s |
| workshop | 35 % | 100 s | 1 | 120 s |
| storefront (furnished set) | 50 % | 90 s | 1 | 120 s |
| warehouse (furnished set) | 40 % | 100 s | 1 | 120 s |
| flat tower | 60 % | 90 s | 2 | 150 s |
| office | 70 % | 75 s | 2 | 150 s |
| club (furnished set) | 80 % | 60 s | 2 | 150 s |
| vault (city centre) | every building | 45 s | 3 | 180 s |

These numbers are first guesses chosen for a ten-minute burglary being possible and a careless one costly. They are **not play-balanced**: nobody has played one.

## Stolen goods: evidence, not a currency

When a Household Safe's lock gives way (`PropertyCacheBlockEntity.forcedOpenBy`), every stack in it is marked with an NBT string `uc_stolen` naming the city (`StolenGoods`) and a red tooltip line "Stolen goods (Valewood)". The mark is the evidence that makes the rest of the system consistent:

* a checkpoint search and an arrest treat a marked stack as **prohibited in every city**, whatever the city's law says about the item (`CheckpointService.categoryOf`); honest stacks of the same item beside it are left alone (tested for gold and emeralds);
* a marked stack stacks only with other marked stacks of the same city;
* stock that arrives in the safe later is not marked until the safe is forced again, and nothing marks goods anywhere else.

What marking does not do: it does not stop a person using the goods, and **no fence yet buys or launders them**: the underground buyers of the work-order system do not know about the mark, and whether Q Shop's counter accepts or refuses an item that carries extra NBT has **not been tested**. How a marked stack is sold at a discount is future work. The honest statement is: stolen goods can be lost to an arrest or a checkpoint; there is no designed, tested way yet to turn them into money.

## Evidence

`PoliceResponseGameTests` (14 cases; the whole `runGameTestServer` suite passed at 297 tests in a copy of the checkout with the quiet-entry case and the households in, and every GameTest job of the hosted run on the head of this branch passed):

* the stock classes carry profiles and the order of levels holds; an out-of-range profile is refused;
* an alarm is a fixed function of the building (certain for chance 1, never for 0, 200 to 250 of 400 for an even chance, the same every time);
* an armed alarm raises one response, a second noise never adds one and only brings it sooner; no officer is there before the arrival time;
* no alarm and nobody watching raises nothing in 300 tries; a class with no profile raises nothing by itself;
* a resident in sight raises a witness response, slower than any class's own; a police officer in sight does not count as a witness;
* a clean pick slips past an alarm that can be slipped past (and tells the thief so), a noisy one does not, a snapped pick that rang the bell is never forgiven, and the certain alarm of the high-value class cannot be slipped past;
* creative and spectator players are never policed;
* officers appear at the street spot, two as the class says, dressed and named as officers, unsaved, invulnerable, sent to the door, unable to open doors;
* an officer beside the thief arrests them: the marked gold goes, the honest gold of the same item and the bread stay, heat rises by exactly 3 (never above the ceiling), the arrest is in the search history, the officers leave;
* a thief 400 blocks away, and a thief who is merely out of reach when the window ends, are not caught, searched or heated by the police;
* a response ends when its thief logs out; a dead player is not arrested;
* the arrest survives a simulated crash after the decision is on disk and is settled exactly once by login recovery, even if recovery runs twice;
* a forced safe marks every stack in it, none before; marking is idempotent across cities; a checkpoint seizes the marked emeralds and not the honest ones.
* officers are put at a street spot from which a path to the thief exists, not inside a wall (a sealed spot and an open one: the officers appear at the open one).

### One scripted real-client burglary (October 21)

`-PucPlaytest=burglary` (`BurglaryScenario`): a real client at 1280 by 720, GUI scale 2, a fresh archipelago world in a software-rendered virtual display, a new character in Survival with five picks and five bread. The script registers, finds an Ironhaven flat block with an armed alarm and a stash, walks at the door holding W and right-clicking, and works the **real lock screen with the pointer**, pin by pin (a sloppy press first, to strain the pick to about 48 percent and trip the alarm). It then forces the stash lock the same way, takes the first stack through the real container screen, steps into the street, lets the shared clock run to the dispatch time, and waits for the arrest. Result of the third run: **pass.** The door opened and an alarm raised one response (alarm 1, witness 0); the stash gave 3 bread, marked stolen; one officer arrived 18 blocks away and walked to the thief; the arrest took the 3 marked bread and left the 5 honest ones; heat in Ironhaven went from 0 to 4 (1 for the forced door and 3 for the arrest). The fine was 0 credits because the new character's wallet was empty (a fine is capped at the wallet).

What the run found, and what was fixed because of it:

* **The first two runs did not reach an arrest** (the first timed out after 2400 ticks; the second, with a log of each officer's position and path, was stopped after 600 once the state was clear). The nearest street spot to the building was behind it; the officer's path to the thief standing in front of it ended at a wall ("path 7 nodes, does not reach"), and the officer stood there until the window closed. No headless test could have shown this, because the tests use a spot in an open arena. Fix: an officer appears at the first of up to six street spots from which a full path to the thief exists (`PoliceResponse.reachableSpot`), and falls back to the nearest when none does (then the officers may stand there until the window closes, as before, with nobody arrested).
* **Two notices were cut off** at this resolution ("...before the..." and "...marked as stolen goo..."); both were shortened. **The lock screen's hint line was cut** ("Press: hold l...") and now takes the first of four wordings that fits.
* The side tracker is partly covered by the stacked notices at the top right at this size, and the officer is behind the camera in the first frames; the script now turns to face the nearest officer before its photographs.

What the run does not show: a thief who moves (the script stands still), two officers, a witness-raised response, a quiet entry, a clean pick through a real screen, a second dispatch, anything at 1920 by 1080 or 3440 by 1440, and any human reaction to being arrested.

## What is not done

* **Little real-client evidence.** One scripted run (above) has seen an officer find a way through a real Lost Cities street and make an arrest, after a path bug it exposed was fixed. Nobody has seen whether officers arrive from somewhere believable in other streets, whether a *moving* thief is caught or escapes, or whether being chased is fun or merely irritating. `PoliceResponse.status()` (`/uc_police status`, operators) counts raised responses, arrivals, arrests, escapes and "no street found" so a real run can report them.
* No jail, warrant, bail, wanted level, vehicle pursuit, SWAT or lethal force. A response is bounded and reversible: a fine up to the wallet, goods lost, heat that decays.
* Officers do not enter buildings. A thief who stays inside is not arrested; the officers wait at the door until the window ends. Whether that reads as "a standoff" or "the AI is stuck" needs a person.
* Alarms are a hash, not blocks: there is no alarm panel, camera or siren a thief can find, disable or avoid by seeing it. (Modern Life ships `warning_alarm` and `cctv_camera` blocks which a later pass could place in the furnished buildings as visible tells; nothing here depends on them.)
* A shop owner or crew mate does not react to a break-in; a witness is a roll, not a person who runs to a phone. A resident who is at home in a furnished building counts as a witness (see [households](households.md)) and remembers the thief for three active hours, but does not chase, shout or phone anybody.
* Striking a civilian still raises 2 heat and nothing else (see [population](population.md)).

## Evaluated and not adopted: SecurityCraft

What was checked (all from the 1.20.1 Forge file `[1.20.1] SecurityCraft v1.10.2.1.jar`, Modrinth project `v8jzRtAt`, read from its `META-INF/mods.toml` and its language file on October 21, 2026; **it was not loaded in our pack**):

* licence **MIT** (stated in `mods.toml`, pointing at the project's repository); Forge 47.3.30 and later; Minecraft 1.20.x; no dependency on Create or on any other mod; both client and server required;
* **608 blocks, 54 items and 4 entities** (a Sentry, its bullet, an I.M.S. bomb and a boat). Its own security features that overlap ours: Keypad Door and Reinforced Door, Keycard Lock, Codebreaker, Alarm, Camera Monitor, Inventory Scanner, Laser, Sentry, Taser, Mines (including one disguised as each ore).

Why it is not part of this work (facts and consequences, not taste):

* **Single authority.** A lock and an owner list are already Core's (`LockService`, `BuildingGuard`, `PropertyLocks`, the lockpick screen) and every rule above, including the fine and the heat, runs through them and through the one prepared-record ledger. SecurityCraft's keypad doors and reinforced blocks would be a second, independent owner/lock system whose events Core cannot see, so a break-in of one would raise no heat and no police.
* **Its automation is violent and unauditable by us.** A Sentry shoots at the players it is not told to spare, mines explode; neither respects "nobody is punished for being offline" or Core's bounded, reversible consequences, and neither could be proved safe by the tests we can run in the cloud.
* **Its ore mines** are crafted from ore blocks and would need an entry in the item-source audit of a world that has no natural ore (D-120).

Still true and worth knowing: it would probably give a player a good way to defend a **player-owned** home in a later opt-in set, since it is MIT, mature and needs no Create. That has not been tested and is not decided; the register row is `candidate`.

## Files

`PoliceResponse`, `StolenGoods`, `CheckpointService.arrest`, `TownspersonEntity` (officer mode), `BuildingBreachEvent` (the `alarm` flag and the live player), `CityBuildings.Response`, `data/underground_city/building_security/classes.json`, `scripts/city_content.py` (the furnished classes), `PoliceResponseGameTests`.
