# The people of the cities (D-108)

Status: **implemented; the fifth pass (D-162) replaced the handful of street bodies with a population sized by area and hour; GameTested as arithmetic and bodies; measured through real clients before and after (crowd runs, below); nobody has met one.** This document says what exists, what it costs, what it deliberately is not, and what was evaluated and not adopted.

## What the owner's first playtest found

The generated cities were empty: Lost Cities builds streets and buildings and no one to walk them. The starter slice had a few named people (the district's registrar, employers, residents and guard, as vanilla villagers placed by an operator) and nothing in the streets.

## Design: a roster that costs nothing, and a handful of bodies

*This section is the design as first built (D-108). The fifth pass (D-162, the next section) kept the roster, the schedule, speaking and the crime rules. It replaced the numbers below (the rosters, the skins, the leash, the spawn rate and the budget), and the rule that nobody goes in: a person still never opens a door, but the service opens one a moment for a person going in or out, never with a player close enough to follow.*

* **Roster** (`PopulationPlan`): a few dozen people per city (Ironhaven 54, Valewood 42, Blackwater 48; `data/underground_city/population/<city>.json`). Each has an id, a name that is unique within the city, one of 48 original generated skins whose *outfit says their trade* (a hard hat and a hi-vis vest on the docks, a straw hat in the fields, a badge on the gate), a role (resident, worker, courier or guard) and a district: a chunk within a few chunks of the city centre where they spend the day. The roster is pure arithmetic from the city and the world seed: the same person is the same person every time, and nothing about it is saved.
* **Schedule**: for any hour, a person is `STREET` (out walking), `POST` (a guard standing), or `HOME` (indoors, not an entity at all). Residents are out for a morning and an afternoon walk (whose start shifts by an hour or two, by person and by day), workers from early morning to early evening, couriers most of the day, guards round the clock but thinner at night. Nobody but a guard is out between ten at night and six in the morning. This is the "off-screen simulation": it is the clock, and it costs nothing.
* **Bodies** (`TownspersonEntity`, `PopulationService`): once a second, for at most four players per pass, the service takes the people whose district is within six chunks of a player and who are out right now, and puts **one** on the ground at an outdoor street spot that is already loaded (never loads a chunk), at the street level Lost Cities gave that chunk, with open sky above, between 14 and 64 blocks from every player. It removes anybody who has nobody within 96 blocks and anybody whose time outdoors is over once nobody is looking closely. A person walks a short leash (14 blocks, 3 for a guard), looks at players who come near, floats, flees if struck, **never opens a door** (so a person can never be inside a building that is locked) and is **never saved**: on a restart the roster, which is arithmetic, decides afresh who is out.
* **Budget**: at most 12 people around any one player and 40 in the world, one added per second. The service counts passes, the mean and worst cost of a pass in microseconds, bodies spawned and removed, and spots it could not find: `/uc_population status` (operators).
* **Speaking** (`PopulationService.talk`): right-click opens the same dialogue screen as the district's named people, with one choice, Goodbye. What they say is by role and time of day (`common.json`), now and then a line about their city, and a different greeting by name for a player they have spoken to before (the list of who has met whom is in memory and bounded; it is not saved).
* **Crime**: striking a civilian makes them run, adds 2 heat in their city for the striker (existing heat rules: it decays with active time and raises checkpoint search chances) and shows a notice. They cannot be hurt. **Striking does not call the police; nothing else happens** (a *break-in* can: see [police response](police-response.md)).

## The fifth pass: a populated city by area and hour (D-162)

The owner's biggest playtest complaint was empty cities: about six people in sight after a minute, at most twelve around a player and forty in the world, one added a second. Raising the caps alone would only have scattered more people on the same leashes. The street population is now sized by **where** and **when**:

* **Footfall by area and hour** (`PopulationDensity`): every chunk of a city is an area by what stands in it (open street, residential, commercial, office, industrial, nightlife or public: from the furnished plan's use or the Lost Cities building's category). Each area has a day curve, 0 to 1 by hour: shops fill late morning to evening, offices at nine and five, nightlife from evening to two, industry round the clock with a dip at night. Each city leans its own way: Ironhaven's industry runs shifts, Blackwater's streets and nightlife are the busiest (1.25 to 1.7), Valewood goes to bed early.
* **The target round a player** is the sum of the weights of the 7 × 7 chunks round them, at 0.9 people a chunk of a fully busy area, times the density setting, within its budget.
* **Density setting** (server config `population.density`): **QUIET** (half as busy, 24 a player, 64 in the world), **NORMAL** (the default: 48 a player, 120 in the world) or **BUSY** (1.4 times, 72 and 180).
* **Errands:** a person out has a purpose (work, shop, home, a venue, a delivery, a stroll) and walks to a door that serves it.
  * **New people** come out of a door (60%) or appear out of every player's sight, never in view. Two are added a second for each player at most.
  * **Going in:** a person reaching their door goes in. The door opens a moment and only when no player is within six blocks, so nobody can follow a person in.
  * **Customers:** a shopper at an open shop goes in and browses its public floor (the same floor `StaffZones` reads) for 15 to 45 seconds, then leaves by the door. One in two buys a unit off a supplied shelf on the way out (D-164).
  * **Talk:** a stroll that ends near somebody idle becomes a conversation of two people facing each other.
  * **Thinning:** when the hour quietens, the surplus goes in at a door, two a pass and only people no player can see (one with no door near is simply gone, unseen).
* **Roster and identity:** 540 people in Ironhaven, 300 in Valewood and 560 in Blackwater (data), with a visitor role for the evening.
  * Schedules by role: residents out a longer stretch, Ironhaven's night shift (one worker in five), visitors from late afternoon until three.
  * **200 original skins** (20 outfits in 10 colourings, `tools/art/gen_citizens.py`) chosen by role and city, and a build per person (height and width vary a little).
  * The same person is the same person every time: arithmetic of the world seed, nothing saved.
* **Open streets first (a follow-up after the first measurement):** the first crowd run of D-162 counted about forty people within 64 blocks of a player in an Ironhaven street but only two to eleven in the player's line of sight, and its pictures showed the street nearly empty. A stroll had ended at any open-sky spot at street level within two chunks, which includes the yards, courts and lawns inside building plots behind the street. Now:
  * Lost Cities' answer for a chunk (asked once, as before) also records whether the chunk has a building. Chunks without one are **open streets**: streets, squares and parks.
  * A stroll ends on an open street when one is within reach (40 blocks, inside a walker's path range).
  * A new person placed on the street, out of every player's sight, is put on an open street of the 7 × 7 window three times in four.
  * That alone did not help (crowd run 3, below). **People go where a player can see them:** seven errands in ten by somebody within 64 blocks of a player go to a stroll's end, or a door, that the player has a clear line of sight to (within 48 blocks, whichever way the player faces). A new person is placed, when it can be, out of every player's view but on a street the nearest player has a line to, so they walk into sight instead of appearing in it.
  * Doors, errands, the budget and the rule that nobody appears in view are unchanged.
* **Witnesses:** more people in sight still count at most three in the witness roll (`PoliceResponse.witnessed`). A crowded street makes a break-in riskier up to a point, not certain.
* **Bounded work:**
  * A pass runs every ten ticks over at most four players.
  * Lost Cities is asked about at most three new chunks a pass (the area and the street level are cached), and at most four doors are scanned a pass (cached, 4,096).
  * A walker re-plans its path every two seconds once its last path is done; an officer re-plans every half second.
  * Nobody is saved, and nobody is placed in a chunk that is not loaded.
* `/uc_population status` (operators) and `PopulationService.status` count everything a pass does: people out and the peak, passes with their mean and worst cost, Lost Cities lookups, spawns (and how many at a door), removals, people who went in at a door, shoppers and what they bought, conversations and walkers given up.

## What it is not

* They have **no jobs that change anything**: no deliveries, no trade, no wages, no effect on the exchange or the orders.
* They have **no homes the player can enter** and no memory beyond a greeting.
* A person in sight of a break-in is a **witness** by a roll and officers can then arrive ([police response](police-response.md), D-122), but they have no memory and run to no phone; there are no gate guards at the checkpoint booths (the existing `/uc_checkpoints place` operator step still places a booth and a guard per zone; the ambient guards patrol the streets).
* Nobody walks between cities, rides a vehicle or uses a bridge.
* The streets Lost Cities builds are not all walkable for a person (stairs, plazas and fenced lawns are); a person stays on their leash and does not path across the city.
* Not measured: how many people look right in a real street, the cost of a pass under a real Lost Cities city (the harness numbers are in the sprint report), anything on a connected client.

## Evaluated and not adopted

| Candidate | Why not (facts, not taste) |
| --- | --- |
| **Easy NPC 7.16.0** (MIT, 1.20.1 Forge, bundle 3 MB, optional Armourer's Workshop and Easy Model Entities) | A good NPC body and editor with its own dialogue, trading and action-event systems. Using it for a few hundred ambient citizens would add a **second authority over dialogue, trade and persistence** beside Core's `Dialogue` and Q Shop (the single-authority rule), and its per-NPC persistence is the opposite of this design (nothing saved, bodies come and go). Kept as an optional future **body provider** for scripted story characters, not wired in. |
| **CustomNPCs-Unofficial** | Rejected: heavy scripting engine, licence and size not compatible with a lightweight, data-driven, private-play pack. |
| Villagers with names (what the district uses) | Fine for a handful placed by an operator (they keep beds and bells), wrong for a street: vanilla villager brains are expensive per entity and their model is not a modern citizen. |

## Evidence

* `PopulationGameTests`: a roster is the same every time, names unique, outfits match roles, the mix is followed, a bad mix is refused; a city is busy by day and quiet at night with only guards at three in the morning; the catalogue loads all three cities and every role has lines for every period; a body is never saved, cannot be hurt by an ordinary blow or a fall, but dies to `/kill` and the void, and cannot open doors; striking raises heat by exactly 2 in the right city and not for creative mode; a returning player is greeted by name and one person says many different things; bodies with nobody near are removed.
* The 48 skins: `tools/art/gen_citizens.py` (`build_all.py --check` in CI); looked at in a contact sheet during this work.
* A real street (October 21, scripted client, a minute of play in an Ironhaven street of a brand-new archipelago world; `PopulationService.status`): before the fix below, a pass cost a mean 25.3 ms and a worst 465 ms, 98 percent of it in 50 Lost Cities street lookups of a mean 37 ms, 55 of 74 passes found no spot, and 2 people were about; after it, a mean 0.49 ms and a worst 6.6 ms, 11 lookups, 21 misses in 75 passes and 6 people. The fix: the street level of a chunk is asked of Lost Cities once (`streetOf`, bounded to 4,096 chunks), and a person whose spot search failed waits 15 seconds while the next is tried. These are single samples of one process holding the client (under a software renderer) and the server; they say what dominated the cost, not what a dedicated server pays. One GameTest covers the cache.
* **Before and after, through real clients (fifth pass, `-PucPlaytest=crowd`).** A new furnished archipelago world each run; the player is teleported to eight stops (a street of a kind near each city's centre, the clock set), waits 20 seconds, counts six times ten seconds apart, takes four pictures, walks forty blocks and counts twice more. "In sight" is the server's line-of-sight test from the player to each person within 64 blocks, all round; "on screen" is the client's count in the camera's view, the best of four directions. The counts include the household residents and shopkeepers inside the furnished buildings (6 to 16 of them) unless the column says street people. Means per stop:

  | Stop | Baseline: within 64 / in sight / on screen | After (crowd run 4): street people within 64 / in sight / on screen |
  | --- | --- | --- |
  | Ironhaven, commercial, 12:00 | 12 / 4.5 / 3.8 | 34 / 14.4 / 8.5 |
  | Ironhaven, residential, 15:00 | 13 / 1.0 / 0.9 | 33 / 4.8 / 4.2 |
  | Valewood, commercial, 11:00 | 19 / 3.0 / 1.8 | 28 / 5.2 / 3.4 |
  | Valewood, residential, 17:00 | 12 / 0.6 / 0.5 | 30 / 1.2 / 1.0 |
  | Blackwater, commercial, 13:00 | 15 / 1.5 / 1.5 | 43 / 10.0 / 8.4 |
  | Blackwater, nightlife, 22:00 | 8 / 0.0 / 0.0 | 18 / 1.6 / 1.1 |
  | Ironhaven, industrial, 03:00 | 8 / 0.2 / 0.2 | 4 / 2.0 / 1.0 |
  | Valewood, residential, 23:00 | 23 / 2.8 / 1.6 | 2 / 0.5 / 0.5 |

  * **Baseline** (crowd run 1): the tree before this pass (`baa2809`), 12 people a player and 40 in the world.
  * **Crowd run 3** (open streets first, without the line-of-sight preference): 25 to 56 within 64 blocks, but in sight no better than the baseline (Ironhaven commercial 5.5, Blackwater commercial 7.0, the rest 0.5 to 4.4).
  * **Crowd run 4** (with it): the busiest streets show three times as many people in sight as before. The census (`PopulationService.census`) at Ironhaven commercial: 35 street people, 30 walking, 23 on an open street (14 of them in sight), 4 on building plots (none in sight).
  * **Where it is still thin:** Valewood is hilly, and up to 20 of its people stood more than two blocks below or above the player, behind the slope. Residential streets at five in the afternoon and nightlife at ten at night show one or two people. At night the streets empty by design (the hour curve): at Valewood at 23:00 run 4 had 2 street people out (and 16 household bodies), where the baseline counted 23 bodies of every kind.
  * **The pictures** (`uc-crowd-<city>-<kind>-<hour>-<n|e|s|w>.png` in each run's screenshots, looked at): in run 4, four to six people down Ironhaven's and Blackwater's commercial streets; in the baseline, Ironhaven's street at noon was empty to the north. It is busier, not a crowd.
  * **Cost:** the server averaged 22 to 27 ms a tick in run 4 against 19 to 24 in the baseline (one process with the client under a software renderer; not a dedicated server). A street pass averaged 1.8 to 4.4 ms against 0.25 to 0.6, every ten ticks. The worst pass in run 4, 612 ms, was the first pass after the teleport into a new city: one Lost Cities lookup for a chunk not yet loaded. The service no longer asks about a chunk that is not loaded, and a pass's lookups now share one budget of three. Crowd run 5 checks this (see the pass report).
* Not shown: two players in one street, the cost on a dedicated server, a walk between cities without a teleport, how many people look right to a person.
