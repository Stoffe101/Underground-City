# Alarms, witnesses, police and stolen goods (D-122)

Status: **implemented; arithmetic, the rules and the settlement are GameTested headlessly (`PoliceResponseGameTests`, 21 cases, four of them the police on the beat of D-155; one scripted real client was caught by a patrol, patrol run 2); one scripted real client has run the whole sequence once in a generated street (a pick on the real lock screen, an alarm, a forced safe, an officer who walked to the thief and arrested them); no person has played it and nobody has been chased by it.** This document says what exists, what it deliberately does not do, and what was evaluated and not adopted. It replaces the sentence "no police or witness responds to a break-in" in [building protection](building-protection.md) and [population](population.md): since this change one does, in the ways below and no others.

## The rule: the police are not psychic

A response starts only from an **observed event**. A break-in is observed in three ways (a camera, D-146, calls through the building's alarm).

1. **An alarm.** A building class may carry a `response` profile in `data/underground_city/building_security/classes.json` (and in the furnished package's own classes). Whether *this* building's alarm is armed is a hash of the building and the world seed against the class's `alarm_chance` (`BuildingStash.roll`, a fixed salt), so it is the same answer every time, for every player, and a thief who has learned a building keeps what they learned. A pick that snaps in a lock and rings the lock's own bell (the existing `alarm_chance` of the lock profile) also raises the alarm of any building, with or without a class profile.
2. **A witness.** At the moment a door is picked open (or a pick snaps) any person of the city (`TownspersonEntity`, not a police officer) within 20 blocks of the door and with a line of sight to the thief is a witness. One roll is made for the group, using the city's own `witness_chance` from `city_laws/<city>.json`, three points more for each point of the thief's heat there, less for concealment skills, and a little more for a second and third witness. A person is only out in the street at the hours the roster says (nobody but guards between ten at night and six in the morning), so **a night break-in is seen less often than one at noon**: that is a real choice and it falls out of the existing schedule, not from a special rule.

3. **An officer on the beat** (D-155, [below](#police-on-the-beat-d-155)) who sees a thief at work on a city building's lock or safe, or sees the door give way.

Nothing else raises a response. A creative or spectating player is never policed (the same rule as checkpoint searches).

## Police on the beat (D-155)

While somebody is in a city, now and then the police walk a beat there: a patrol of the city's `patrol.officers` (Ironhaven two, Valewood and Blackwater one) comes out every `patrol.every_seconds` of active play (180, 240 and 360 seconds; the first one half that long after somebody arrives) and walks a beat of up to five street spots, laid out round one of the people in the city by the same street-spot finder the arrivals use, for `patrol.beat_seconds` (150, 150, 120), then walks off (`city_laws/<city>.json`, `PolicePatrols`).

* **What an officer on the beat sees.** A person within 24 blocks (12 for a crouched thief with Soft Steps) with a line of sight: a wall, a shut door or a building's corner hides them. An officer on the beat is a person in the street with a name over their head, visible from as far as any other.
* **What they stop.** Somebody working the lock of a city building's door or safe, or searching its furniture, in their sight; or a door giving way in their sight (the breach itself). That officer leaves the beat and *is* the response: it starts at once, arrived, with the thief seen there, and runs as any other response does (chase what they see, search where they last saw, give up, arrest within three blocks). The arrest is for "trying to break in" when they were caught at the lock. A response already raised for the same thief and building (an alarm that rang) is joined, not doubled.
* **Fair warning.** The lock screen hides the street, so a thief at a lock is told once per patrol when an officer on the beat comes within 40 blocks and cannot see them ("Footsteps"); when an officer does see them the lock is put away with nothing lost, as letting go does, so the thief sees who is coming.
* **An arrest empties the hands.** A lock or a search the thief still has going is put away when an officer arrests them (patrol runs 3 and 4: a scripted hand clicked again after an officer on the beat had put the first lock away, and the arrest left the new one open).
* **Only the person seen.** A partner beside the thief is nobody's business; each patrol watches each person by what they are doing.
* **Bounded.** One patrol per city and only while somebody is in it; it goes when nobody is within 96 blocks of it, and a city nobody is in forgets its next patrol. Officers are unsaved bodies with a backstop timer, nothing is held over a restart and no chunk is loaded. `/uc_police patrols` (operators) counts patrols, catches and warnings.

### Entering quietly, and what a thief can choose

* **A clean pick is a quiet one.** `LockpickSession` now records the most strain the pick was ever under (`peakStrainPercent`). A door picked without the pick ever passing **40 percent** of its strain limit (`LockService.QUIET_STRAIN_PERCENT`) is a *quiet* entry: it does not trip a building's armed alarm, unless that class's alarm chance is 0.9 or more (the high-value premises have one in every building and it cannot be slipped past). The thief is told when it worked ("Something in here is wired to an alarm. Your pick was clean and it did not hear you"), which is also how they learn which buildings are wired. A pick that snaps and rings the lock's own bell is never quiet. A *witness* is not affected by a clean pick. This makes the lockpicking skill of the player, not a number, the thing that decides how loud a burglary is: a careful hand's picks are mostly quiet, a careless hand's rarely (`LockpickSessionGameTests`, 40 seeds each).
* **Choose when:** a witness needs a person in sight, and the people of a city are in the street by day, so a night break-in is seen less often.
* **Choose how long:** the arrival time is the class's, so a thief decides whether the rooms of an alarmed building are worth the minutes they cost; the stash's vigilance (existing) rises with each forcing.
* **Choose where to be:** officers wait at the door and chase on foot at a sprinting pace; the response ends when the thief is out of reach for the class's window. (Since October 23, D-146: officers chase only a thief one of them can see, otherwise search the last place they saw them, and give up after 30 seconds out of sight; see [the Thief track and burglary](thief-and-burglary.md#police-who-look-d-146).)
* **Work the panel (D-128):** an alarmed furnished building has a panel by its street door; a Technician can jam it inside the entry delay, or before anybody comes in (see [Alarm panels](#alarm-panels)).
* **Sell to a fence (D-125):** marked goods can be sold at night, at a discount, to a fence (see [Fences](#fences)).
* **Not available yet:** cameras and motion sensors as Core devices; cutting power; a disguise. The SecurityCraft trial (D-140, opt-in, not adopted) shows a keypad door, a laser and a siren under Core's rules: an entry by a code that is not yours is a quiet break-in here, and a laser crossed by somebody who does not belong rings the building's alarm ([securitycraft-trial.md](securitycraft-trial.md)).

## What a response does

| Step | What happens | Where it lives |
| --- | --- | --- |
| Raised | One `Dispatch` per thief and building (a second noise only brings the officers sooner, never later). Capped at 24 in the world. Held in memory only. | `PoliceResponse.noticed` |
| Alarm | The bell rings at the door every two seconds for twenty seconds. | `PoliceResponse.step` |
| Arrival | After the class's `dispatch_seconds` (a neighbour's call is never faster than 150 s) in active time, `officers` bodies appear at street spots near the building (the population service's own spot finder: loaded, street level, open to the sky, 14 to 64 blocks from the thief), wearing the guard outfit, named "Officer <surname>" from the city's guard roster. A wider ring of chunks is tried on each of five attempts; if no street is found the response is dropped and nobody is arrested. | `PoliceResponse.arrive` |
| Approach | Officers walk to the building's door, and once the thief is within 40 blocks and in sight, after the thief at about 5 blocks a second (`PoliceResponse.CHASE_SPEED`; measured 4.96 in `PoliceWarrantGameTests`): faster than a walk (4.3), slower than a sprint (5.6), so a sprinting thief who breaks the line of sight can get away and one who stops is caught. Since D-189 they open doors and go into buildings; they never strike anyone and cannot be hurt. At 14 blocks the first says "Police! Stay where you are." A route checked every 3 seconds that ends short of the suspect three times running brings one second unit from a reachable street spot near the suspect (never on top of them; D-209). | `TownspersonEntity.ErrandGoal`, `PoliceResponse.checkRoute` |
| Arrest | An officer within three blocks arrests the thief (`CheckpointService.arrest`, the same crash-safe prepared record as a checkpoint search): what the thief carries (pockets, hands, armour and the packs and boxes in them; never a chest, a house or another player's pack) that is marked stolen or forbidden by the city's law is taken, the city's prohibited-goods fine is charged up to what the wallet holds (never below zero), the city's heat rises by 3, and the arrest is written into the search history. | `CheckpointService.arrest` |
| End | The officers stand down and leave after eight seconds. A response also ends when the class's `window_seconds` pass ("The police have gone"), when the thief is more than 110 blocks from every officer and the door ("You got away"), when they die or leave the dimension, or when the server stops. | `PoliceResponse.pass` |

**Nobody is punished for being away.** A dispatch is held in memory and is dropped the moment its thief logs out (`PlayerLoggedOutEvent`), dies or goes to creative; on a restart no dispatch is pending. Arrest, search and fine happen only to an online, living survival player standing within three blocks of an officer, and only for a crime that player made. **But a crime somebody saw is not forgotten (D-209):** when a witness, the victim or an officer could name the offender, the city holds a **warrant** in the work-order journal for 20 minutes of active play (the shared active clock: it does not run while everybody is away). A logout, a restart or an escape leaves it standing; at the next login the player is told "You are wanted in ..." with the minutes left; an officer on the beat who sees them stops them, and a checkpoint searches them more often and arrests them. An alarm alone leaves no warrant: nobody saw who.

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

What marking does not do: it does not stop a person using the goods. The work-order buyers do not know about the mark, and whether Q Shop's counter accepts or refuses an item that carries extra NBT has **not been tested**. The designed way to turn marked goods into money is a fence (below).

## Fences

**D-125. Implemented; GameTested (`FenceGameTests`, 8 cases); checked by the economic model; nobody has sold to one.**

A fence is a person of a furnished building's back office who keeps the night: from 21:00 to 03:00 they are at their spot (`fence` in the building's plan) and speaking to them offers "I have something to sell", which opens the fence's own screen. Two fences exist (`data/underground_city/fences/`):

| City | Fence | Where | Rate | Same city | Budget | Refill |
| --- | --- | --- | --- | --- | --- | --- |
| Ironhaven | The Yard | the warehouse's office | 45% | 30% and +1 heat in Ironhaven | 240 | 40 credits every 10 active minutes |
| Blackwater | The Back Room | the club's back office | 50% | 32% and +1 heat in Blackwater | 320 | 45 credits every 10 active minutes |

* **Only stolen goods, only from the pockets.** A fence lists the stacks of the seller's inventory that carry the stolen mark and an item worth (`fences/values.json`); honest goods and anything in a backpack are not offered.
* **Never a way to launder.** An item's worth is its cheapest honest price (shelves, shops, exchange); a fence pays its rate plus up to ten points of the seller's underground payout skill, never above 60 percent of the worth, so nothing that can be bought can be fenced for what it cost. The economic model fails if a worth is set above an honest price, or an item fences for as much as it costs (`fence_problems`).
* **A demand cap.** A fence has a budget; a sale larger than what is left is partial (the rest stays in the pockets) and the budget refills in active time only. The model measures the hourly take after the budget at 51 percent (The Yard) and 57 percent (The Back Room) of the best legal hour at level 5 and fails above 150 percent.
* **Crash-safe.** The sale is one prepared journal record (`WorkOrderLedger` kind SALE: the goods' removal, the credits, the fence's budget and the heat) written before anything changes and settled once on recovery.
* **Not shown:** a person selling to a fence in a generated city at night; whether the rates feel worth the risk.

## Witnessed crimes in the street (D-189, ninth pass)

The owner's playtest found that striking a person in front of the police had no consequence. Since D-189 `CrimeWatch` notices the crimes below and reports them only through somebody who saw or heard them.

| Crime | Who notices | What follows |
| --- | --- | --- |
| **Assault** on a townsperson, a resident, a shopkeeper or a district citizen | An officer on the beat who sees it. A passer-by in sight (20 blocks, line of sight). With nobody else about, the person struck reports it 60% of the time, later. | **First minor blow in an officer's sight:** a warning. **A second:** a pursuit. **A passer-by:** officers come after the city's report time. **The victim:** officers come after twice that. Blows in quick succession are one incident and one call. |
| **Assault on an officer** | That officer | A pursuit at once (a serious offence) |
| **A lock worked in sight** | A person in the street who can see the door | A share of the city's witness chance on each check |
| **An intruder inside** | The household that lives there (by day 16 blocks, at night when close), or a shopkeeper after closing | A call |
| **A search heard, or a sensor tripped** | The household at home; a wired building's motion sensor (more likely the better the building) | A call |
| **Damage** ([burglary damage](thief-and-burglary.md#breaking-things-and-the-city-putting-them-back-d-190)) | An officer who sees it; an armed alarm (a broken window for certain); neighbours within 16 blocks | A call or a pursuit |

How the response works:

* **Offences are data:** each city's `offences` in its law file (`data/underground_city/city_laws/<city>.json`). Each has a charge, a fine, heat, a report time, and whether a first offence is only a warning. Ironhaven fines more and reports faster; Blackwater lets a first trespass off with a warning.
* **Officers arrive from out of the player's sight.** They are dispatched to street cells the player cannot see, open the doors on their path, and arrest only the wanted person.
* **The arrest** charges the offence, fines it from Q Shop's wallet, adds its heat and takes stolen and forbidden goods.
* **Logging out while wanted** ends the response; on return the player gets the notice "The police stood down".
* **Restrained feedback:** one chat line from the witness, and the notices "Police" and "Police searching".

Evidence: `CrimeGameTests`, and police run 3 through a real client (8 steps; see [the ninth-pass report](../production/ninth-pass-c70-critical-fixes.md#3-policing-and-witnesses-d-189)).

## Alarm panels

**D-128. Implemented; GameTested (`AlarmPanelGameTests`, 8 cases); not seen in a real client yet.**

An alarmed furnished building (a class with a response: the flats, the workshop, the warehouse, the office, the club, the shops, the pub, the corner store and the bakery; 10 of the 13 buildings) has an **alarm panel** on a plain wall a few steps inside its street door, placed by the generator (`scripts/city_content.py`, an `alarm` spot in the building's plan). The panel's light is set by the server for the panels round each player every two seconds: **dark** when this building's alarm is not wired, **red** when it is armed, **amber** (flashing) while an entry delay runs, **blue** while it is jammed. Anybody can see the light through a window or an open door, which is how a thief cases a building.

* **The entry delay.** A break-in of an armed building that has a panel in the world does not ring at once: the panel beeps for **25 seconds** and then rings, and the response is the class's own, exactly as an alarm without a panel. A snapped pick still rings at once. A clean pick still slips past an alarm that can be slipped past (above).
* **Working the panel.** A Technician with Bypass Tooling right-clicks the panel: a Bypass Kit is spent the moment the trace starts, the server picks a hidden signal (five symbols of four; six in a high-security building), the client shows it on the trace screen and sends back one answer. **Signal Analysis** slows the signal (16 ticks a symbol instead of 12) and **Security Systems** gives 12 seconds to key it in instead of 8.
* **The right answer** jams the building's alarm for **ten active minutes**: the delay ends, and for those minutes nobody's entry rings it. **A wrong, early or late answer, or one from more than six blocks away,** rings the alarm at once and raises the thief's heat in the city by one.
* Anybody else who clicks the panel learns only what its light says. A panel can be worked before anybody comes in by somebody already inside.
* **Limits:** delays, traces and jams are kept in memory only (a restart forgets them, and a jammed panel is armed again); a delay is dropped when its thief leaves the game; a stock Lost Cities building has no panel and its alarm rings at once as before.

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

## Warrants, priors and the trace (D-209)

| What | Rule | Where |
| --- | --- | --- |
| Warrant | Issued when a crime is witnessed and the offender is a registered citizen (a passer-by, the victim, a household, an officer). One a city per player, the worse charge kept, renewed by a new crime; 20 minutes of active play. Survives logout, restart and escape. | `WorkOrderLedger.issueWarrant`, `PoliceResponse.identify` |
| Recognised | An officer on the beat who sees a wanted person (not working, in survival, in the officer's city) stops the beat and the response begins at once ("wanted for ..."). A second player standing beside them is not wanted and is never arrested for it. | `PolicePatrols.step`, `PoliceResponse.wantedSeen` |
| Checkpoint | A wanted person's search chance rises by 0.4 (never above the law's maximum); a wanted person who is searched is arrested on the warrant. | `CheckpointService.approach`, `arrestWanted` |
| Priors | An arrest records a conviction in the same journal settlement as its fine (kept two hours of play, at most six): each prior raises the fine by half (at most double, never over 1,000) and adds a point of heat; a person with priors or a warrant gets no warning. | `LawMath.escalatedFine`, `WorkOrderLedger.convict` |
| Trace | `/uc_police trace on` (operators), the system property `uc.policeTrace`, or a scenario's `-PucPtPolicetrace=true` writes every step of every case to the log with `[UC-POLICE-TRACE]`: crime, refused (outside a city's law), undetected, witnessed and by whom, reported, dispatch, arrival and where from, route checks that fall short, pursuit, search, arrest and its fine, escape and why, warrant. The last 200 lines are kept in memory either way (`/uc_police trace`). Names, cities and short reasons only. | `PoliceTrace` |

## Real-client acceptance (twelfth pass, police cases runs 1 to 8)

`-PucPlaytest=policecases` (`PoliceCasesScenario`) drives a real client in a new archipelago world with the furnished cities (`-PucPtCity`, `-PucPtSeed`). It covers the owner's ten acceptance cases where one client can. The scenario uses real input for:

* the registration screens;
* the blows (left clicks until the server counts one);
* the lock (pin by pin on the lock screen);
* the search (right clicks on furniture);
* the sprint away.

It logs every shortcut as `SCRIPTED`: the clock, 300 credits by operator command (so fines can be paid), the trace switched on, a teleport to the city's middle, four bread put in the pockets (honest goods an arrest must not take), five lockpicks, the choice of building and the steps beside each piece of furniture. The police trace below is the game's own log of each case.

| Case | Evidence | Result |
| --- | --- | --- |
| 1 a witnessed crime | runs 1 (Valewood, seed 5908), 3 and 5 (Blackwater, seed 20261029), 6 and 7 (Ironhaven, seed 7741) | **passed in all three cities.** A blow with passers-by in sight: one calls the police, a warrant is issued |
| 2 a real dispatch | the same runs | **passed:** officers in 20 s (Ironhaven), 25 s (Valewood), 35 s (Blackwater) for an assault; 150 s for a witnessed break-in in Ironhaven |
| 3 officers reach the suspect | the same runs | **passed:** two officers walked in from 16 to 19 blocks away and arrested a player standing still. In Valewood and Ironhaven they came from out of the player's sight; in Blackwater (run 5) they arrived in view, because no street spot out of sight was found among the candidates near a tower block (the trace now says which of the two reasons applied, D-209) |
| 4 a chase of a moving player | run 7 | **passed:** an officer saw the sprinting player 4 blocks away and challenged them, lost sight, searched where they were last seen, and gave up when they were out of sight for 30 s and 107 blocks from the nearest officer |
| 5 arrest, fine and confiscation | runs 1 and 7 | **passed:** after a noticed burglary, an officer walked to the door and arrested the thief: fine 150 in Ironhaven (100 raised by one prior conviction), all 5 stacks of marked goods taken, the 8 bread kept. Every fine matched `LawMath.escalatedFine` and was taken from Q Shop's wallet |
| 6 an escape | run 7 | **passed:** the escape left the warrant for assault standing |
| 7 an unnoticed crime stays unnoticed | run 9 (private world, a Core storefront) | **passed:** a storefront picked and searched with nobody in sight: no report, no dispatch, no warrant, 7 marked units carried away (in runs 1 and 7 a passer-by saw the thief at the lock) |
| 8 two players, only the offender punished | GameTests only (`PoliceWarrantGameTests`, `PoliceResponseGameTests`) | not run through two real clients |
| 9 restart and reconnect | runs 1 and 7 | **passed:** saved, quit and reopened while wanted; the warrant stood, nobody was sent while the player was away, and the player was told "You are wanted" |
| 10 the owner's buildings | runs 8 and 9 (private: a new Valewood world with the owner's buildings, seed 5908; nothing private is published) | **run 8 failed and found a fault:** the blow was witnessed and two officers walked in from 15 blocks out of sight, but the nearest officer stopped two blocks away on the far side of a two-high bush and could not see the suspect, who stood still for two minutes; the response ended as an escape. Fixed (below). **Run 9, with the fixes, at the same spot:** the officers (from another street this time) challenged at 3 blocks and arrested, fine 40; an unnoticed burglary (case 7); a second offence carrying the 7 marked units, arrested, all taken, fine 60 escalated by one prior; a chase and an escape with the warrant standing, during which a second unit was sent because the first officer's route fell short; the reopen kept the warrant. No beat officer came close enough to recognise the player in the time given. Its two reported failures were the scenario's own checks (honest bread counted with stolen bread; marked goods from an unseen burglary taken for something out), fixed after the run |
| recognised on the beat | run 7 | **passed:** after the reopen, an officer on the beat recognised the wanted player at 18 blocks, walked to them and arrested them on the warrant (fine 120 escalated by two priors, capped at the 90 left in the wallet) |

What the runs found, and what changed:

* **The first-login landing undid a scripted teleport** when the server was slow (run 4). This was the scenario's fault, not the game's: registration now waits for the landing.
* **A city's middle may have no houses.** Blackwater's has towers, offices, clubs and storefronts; Ironhaven's has flat blocks, storefronts, warehouses and workshops (runs 2 and 6).
  * At 03:00 and 05:00 every occupied Blackwater storefront had a resident inside.
  * Its clubs need a Pro Pick Set: a new thief's plain picks are refused, as the lock rule says (run 5).
  * So a beginner in Blackwater has no empty building to pick near the middle at night. Whether that is the right difficulty for Blackwater is a design question, listed in the report.
  * The scenario now burgles any class whose lock plain picks can try.
* **Officers' routes fell short** once (run 7: "the route ends 13 blocks short") and the officer still made the arrest.
* **A bush beside the suspect stopped an arrest (run 8).** An arrest needed the nearest officer's line of sight from eye to eye. An officer whose route stopped on the far side of a two-high bush stood there, two blocks from a suspect who did not move, until the window closed.
  * Now any officer within arrest range arrests when there is a clear line at the eyes, the chest or the knees.
  * One with something in between (a bush, a post, a lamp) steps round to a free cell beside the suspect.
  * A wall or a window still blocks all three, so nobody is arrested through one (`PoliceResponseGameTests`: a bush, and a closed room).
* **The route check could not see a route that fell short (run 8's diagnosis).** It compared the suspect with the path's target, which is the suspect's own block, so a route ending outside a closed room counted as reaching it and no second unit was sent. It now compares the end of the path.

Not shown through a real client: a shop robbed after closing with an alarm, a window broken, an officer assaulted, a hiding place inside a building, leaving the city while wanted, two real clients. The scripted thief usually stands still to be arrested, so these runs show that arrests happen, not how often a real player is caught: the career balance's assumption (an arrest after 40% of noticed crimes) stays an assumption.

## What is not done

* **Real-client evidence is scripted.** The twelfth-pass acceptance runs (above) are scripted clients, not people. Before them, one scripted run had seen an officer find a way through a real Lost Cities street and make an arrest, after a path bug it exposed was fixed. Nobody has seen whether officers arrive from somewhere believable in other streets, whether a *moving* thief is caught or escapes in a real street, or whether being chased is fun or merely irritating. GameTests (October 22) prove that officers turn after a thief who moves, that a partner standing beside an officer with marked goods is never arrested for the thief's break-in, that two thieves at one building and one thief at two buildings get separate responses, and that a witness's call is slower than an alarm. `PoliceResponse.status()` (`/uc_police status`, operators) counts raised responses, arrivals, arrests, escapes and "no street found" so a real run can report them.
* No jail, bail, vehicle pursuit, SWAT or lethal force. A response is bounded and reversible: a fine up to the wallet, goods lost, heat that decays, a warrant that runs out after 20 minutes of active play and a record of convictions that runs out after two hours.
* Officers have gone through doors since D-189; whether a chase through a furnished building's rooms reads well to a person has not been seen.
* A stock Lost Cities building's alarm is still a hash with nothing to see; only the furnished buildings have a panel (D-128) and cameras (D-146, D-149, D-153). There are no sirens.
* **Patrols (D-155): GameTests and one scripted client.** In patrol run 2 a thief at a Valewood door with an officer on the beat in sight started to pick the lock: the officer saw it, the lock was put away and the thief, standing still, was arrested for trying to break in. The first patrol came out after 119 s of active play, as the pace says, and its beat never brought the officer into sight of the door; the second's did. Whether a beat looks like a beat to a person, and whether the footsteps warning is enough, has not been seen: nobody has tried to slip a patrol.
* A shop owner or crew mate does not react to a break-in; a witness is a roll, not a person who runs to a phone. A resident who is at home in a furnished building counts as a witness (see [households](households.md)) and remembers the thief for three active hours, but does not chase, shout or phone anybody.
* Striking a civilian (D-189, D-209): a passer-by in sight calls the police; with nobody else about, the person struck reports it themselves, always, a little later; an officer who sees a blow pursues at once; a first minor offence with a clean record may be let off with a warning, never one with priors or a warrant.

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

`PoliceResponse`, `AlarmPanels`, `AlarmPanelBlock`, `FenceService`, `FenceCatalog`, `FenceScreen`, `StolenGoods`, `CheckpointService.arrest`, `TownspersonEntity` (officer mode), `BuildingBreachEvent` (the `alarm` flag and the live player), `CityBuildings.Response`, `data/underground_city/building_security/classes.json`, `scripts/city_content.py` (the furnished classes and the panels), `data/underground_city/fences/`, `PoliceResponseGameTests`, `AlarmPanelGameTests`, `FenceGameTests`.
