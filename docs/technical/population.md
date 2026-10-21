# The people of the cities (D-108)

Status: **implemented; GameTested as arithmetic and bodies; its cost measured once in a real street (below); nobody has met one.** This document says what exists, what it costs, what it deliberately is not, and what was evaluated and not adopted.

## What the owner's first playtest found

The generated cities were empty: Lost Cities builds streets and buildings and no one to walk them. The starter slice had a few named people (the district's registrar, employers, residents and guard, as vanilla villagers placed by an operator) and nothing in the streets.

## Design: a roster that costs nothing, and a handful of bodies

* **Roster** (`PopulationPlan`): a few dozen people per city (Ironhaven 54, Valewood 42, Blackwater 48; `data/underground_city/population/<city>.json`). Each has an id, a name that is unique within the city, one of 48 original generated skins whose *outfit says their trade* (a hard hat and a hi-vis vest on the docks, a straw hat in the fields, a badge on the gate), a role (resident, worker, courier or guard) and a district: a chunk within a few chunks of the city centre where they spend the day. The roster is pure arithmetic from the city and the world seed: the same person is the same person every time, and nothing about it is saved.
* **Schedule**: for any hour, a person is `STREET` (out walking), `POST` (a guard standing), or `HOME` (indoors, not an entity at all). Residents are out for a morning and an afternoon walk (whose start shifts by an hour or two, by person and by day), workers from early morning to early evening, couriers most of the day, guards round the clock but thinner at night. Nobody but a guard is out between ten at night and six in the morning. This is the "off-screen simulation": it is the clock, and it costs nothing.
* **Bodies** (`TownspersonEntity`, `PopulationService`): once a second, for at most four players per pass, the service takes the people whose district is within six chunks of a player and who are out right now, and puts **one** on the ground at an outdoor street spot that is already loaded (never loads a chunk), at the street level Lost Cities gave that chunk, with open sky above, between 14 and 64 blocks from every player. It removes anybody who has nobody within 96 blocks and anybody whose time outdoors is over once nobody is looking closely. A person walks a short leash (14 blocks, 3 for a guard), looks at players who come near, floats, flees if struck, **never opens a door** (so a person can never be inside a building that is locked) and is **never saved**: on a restart the roster, which is arithmetic, decides afresh who is out.
* **Budget**: at most 12 people around any one player and 40 in the world, one added per second. The service counts passes, the mean and worst cost of a pass in microseconds, bodies spawned and removed, and spots it could not find: `/uc_population status` (operators).
* **Speaking** (`PopulationService.talk`): right-click opens the same dialogue screen as the district's named people, with one choice, Goodbye. What they say is by role and time of day (`common.json`), now and then a line about their city, and a different greeting by name for a player they have spoken to before (the list of who has met whom is in memory and bounded; it is not saved).
* **Crime**: striking a civilian makes them run, adds 2 heat in their city for the striker (existing heat rules: it decays with active time and raises checkpoint search chances) and shows a notice. They cannot be hurt. **Striking does not call the police; nothing else happens** (a *break-in* can: see [police response](police-response.md)).

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
* Not shown: a person in a photograph of a real street beyond the scripted frame, two players in one street, the cost on a dedicated server, how many people look right.
