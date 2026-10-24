# Roadside places and road encounters (D-138, D-139)

Status: **implemented; GameTested headlessly (5 roadside-place and 6 encounter cases) and in the Python suite (`tests/test_roadside.py`); placement measured in a generated archipelago world by the road check; the diner stop, a courier encounter and a letter delivery are in the scripted real-client journey, which passed in a brand-new world (run 65).** Nobody has played any of it. See the sprint report (`docs/production/living-world-roadside.md`) for the measurements and runs.

The owner's correction of October 23: thirteen-minute crossings are fine; empty roads are not. Cities, Founders Row and the roads stay where they are. What changes is what stands beside the roads and what can happen on them, and only in **new** worlds.

## Roadside places (D-138)

Six original places, two per road, each a generated district (`scripts/roadside_places.py`, drawn with `scripts/roadside_kit.py`), with something to do in it:

| Place | Road, share of the open road | Kind | What is there |
| --- | --- | --- | --- |
| Crossroads Diner | Ironhaven-Valewood, 0.50 | service | A diner with booths and a counter (Dot Mallory, cook: hot food from Valewood's lines), a dry fuel forecourt and a supply counter (Wes Tanner: torches, lanterns, a lead, a compass, a flask when the survival set is in). |
| Harrow Farm | Ironhaven-Valewood, 0.82 | farm | A farm stand (Hester Harrow: produce and seed), fields, a barn with two barrels of farm stores (one-time loot) and a safe, and the **farmhouse for sale or rent** (`rs_harrow_farm:farmhouse`, the ladder's farmhouse category: 115 an hour, 8,500 to buy). |
| Kessler Salvage | Ironhaven-Blackwater, 0.22 | industry | A salvage yard: an office (Rudi Kessler: nuggets, chain, bars, copper, machined parts) and a safe, wrecks, a crane, public workbenches, scrap bins (one-time loot), and **54 scrap heaps that refill in active play time** (a resource site, 60 minutes; see below). |
| Northgate Depot | Ironhaven-Blackwater, 0.80 | industry | A freight depot: racks, freight barrels (one-time loot), an office (Mara Quint, dispatch: barrels, chests, leads, paper) with a safe, alarm and camera as decoration, a guard on the forecourt. |
| Saltmarsh Motel | Valewood-Blackwater, 0.20 | lodging | An office (June Rourke: snacks, honey, apples, maps, a sleeping bag when the survival set is in) with a safe, and **four rooms to rent** (`rs_saltmarsh_motel:room_1..4`, motel rooms at the ladder's 10 an hour). |
| Old Cannery | Valewood-Blackwater, 0.82 | ruin | A ruin with cannery barrels (one-time loot); a trapdoor in the back office down to **a hidden den** (Silas Crane: lockpicks and a bypass kit), a smuggler's cache and a safe. |

### Where a place stands

* **Only in a world that a new archipelago start enabled** (`RoadsidePlaces.enable`, called beside the road-end ramps in `DevBootstrap`), or one an operator enabled (`/uc_roadside enable confirm`). An existing world is never given one by itself.
* **Built at run time, once, when a player first comes near its spot**, like the wayside shelters: never during world generation, never over anything somebody built, and never by making the server generate or wait for a chunk (every read checks `getChunkNow`; a column not in the world counts as reserved).
* **Candidate spots are worked out from the road plan alone**: the nearest stretches of land (no bridge, no water under the deck) to the place's share of the road, clear of the city circles (110 blocks) and of each city's civic region (its law and exchange begin there, and it is wider than the road's city circle: the first road check found half the spots near a road's ends inside one), at least 20 blocks from the shelters' spots, and clear of the other place on the road; up to twelve per place. They are tried in order; one is given up only when no lot fits there, and the next only after; so the same terrain gives the same answer whichever way a player comes.
* **A lot** is beside the road, wholly clear of it (three blocks beyond its reach), dry, not in a city, a checkpoint zone or another built lot; with no more than 16 blocks between its lowest and highest natural ground; and standing within 5 blocks of the deck's height, so that the driveway is a gentle slope. Before the place is built, the lot's natural ground is **levelled** to its standing level (a hill cut away to the sky, a dip filled with earth; what people built and water are left alone), since a building's own clearing reaches only its roof and its foundation only a few blocks down. The limits and the levelling came from the October 23 road checks: at the earlier limits (4 blocks of relief, 3 to the deck) the diner, the farm and the salvage yard found no lot that fit.
* **Facing:** each place is drawn with its front to the north and generated in four rotations (`rs_<place>_<n|e|s|w>`, sharing one home prefix so a home's id does not depend on the rotation); the rotation whose front looks at the road is built.
* **Into the land:** a band of ground eight blocks wide round the lot is graded toward the lot's ground (natural ground only; never what people built, water, or the road), so a place neither stands on a plinth nor sits in a pit; a three-block **driveway** of the place's own surface (asphalt, gravel or a worn path) runs from the middle of its front to the deck at a straight slope.
* **People:** shopkeepers stay behind their counters (a one-block tether); a place's people are put back if lost, only when every one of their spots has its entities in the world (so nobody is doubled).
* **Of no city (October 24, D-154).** A roadside shop's stock is booked to a city (the cook's lines to Valewood, the clerk's at the same diner to Ironhaven), but the place stands on the road. Before, that bookkeeping leaked into what a player read: a purchase said "from Crossroads Diner in Valewood" and, at the next counter, "in Ironhaven"; the card of a roadside person or a road warden showed the *player's own* home city, so the same cook was "Blackwater" to one player and "Valewood" to another; and a purchase at a roadside counter counted as a visit to the city its stock is booked to. Now a purchase names the shop alone, a person on the road has no city on their card, only a terminal inside a city counts as a visit, and a city's exchange is "the Valewood exchange" rather than "the valewood exchange" (`Exchanges.Exchange.seller`, `Dialogue.chipCity`, `CityNpcs.onTheRoad`; checked in the diner's and the shop counter's GameTests).
* Operators: `/uc_roadside list` (where each place stands or why not), `/uc_roadside retry <place>`.

### Measured in a generated world (October 23 road check, a new furnished archipelago world, the default seed)

All six places were built, each on its first candidate lot, as a player's arrival would build them (the road check serves each place once its chunks are in the world):

| Place | Where | Faces | Relief of the lot | Graded round it | Driveway | Build time |
| --- | --- | --- | --- | --- | --- | --- |
| Crossroads Diner | 976, 67, 160 | north | 11 | 405 columns | 12 blocks, steepest step 1 | 148 ms |
| Harrow Farm | 647, 74, 51 | south | 9 | 696 | 14, step 1 | 448 ms |
| Kessler Salvage | 253, 73, 506 | west | 10 | 1,042 | 14, step 1 | 225 ms |
| Northgate Depot | 466, 70, 993 | west | 14 | 774 | 14, step 1 | 380 ms |
| Saltmarsh Motel | 971, 65, 970 | east | 8 | 788 | 17, step 1 | 136 ms |
| Old Cannery | 961, 64, 1076 | west | 11 | 99 | 14, step 1 | 87 ms |

Each build is one server tick of up to half a second, once per world, the first time somebody comes within 144 blocks. An earlier pass of the same check (before the lot was levelled and the relief limit raised from 14 to 16) built five and gave Kessler Salvage up. Four of the six wayside shelters were built in the same world; two found no level, dry ground within 64 blocks of their spots and were given up, as the shelters' own rules say ([three-city-prototype.md](three-city-prototype.md), the rest stops). The lots and their driveways have not been looked at by a person; the photo tour's shots of them are the only visual check.

### Built a slice per tick (October 24 road check, a new world, the same seed; D-148)

Since D-148 a place goes up a slice per tick, and the road check now builds it that way, waiting for it as a player's arrival would:

| Place | Slices | Work | Longest slice | Ticks to build |
| --- | --- | --- | --- | --- |
| Crossroads Diner | 8 | 129 ms | 33 ms | 7 |
| Harrow Farm | 8 | 159 ms | 36 ms | 7 |
| Kessler Salvage | 8 | 133 ms | 19 ms | 7 |
| Northgate Depot | 8 | 146 ms | 26 ms | 7 |
| Saltmarsh Motel | 9 | 190 ms | 53 ms | 8 |
| Old Cannery | 7 | 189 ms | 58 ms | 6 |

Every place on the same lot as the day before but the Old Cannery, which stood a few blocks away (966, 64, 1069). Slices aim at 15 ms; the longest are the steps that are not divided (handing the place its people and the build's record, and the driveway), which one of them was not measured. The worst tick fell from 448 ms to 58 ms.

### Freight from the Northgate depot (D-156)

The depot's dispatcher, Mara Quint, answers "Any freight going out?" with a **sealed manifest** for the depot office of another city: the cities but the depot's own end of the road, the farthest first, then in turn. Any terminal or counter in that city pays it on delivery, **once**, through the courier letter's own path (one ledger commit, one once-only record; a copy pays nothing and is taken back). The fee grows with the way to go as a letter's does (20 to 75 credits), plus 40 career XP. One manifest at a time; the next after ten minutes of active play. The dispatcher says so when asked too early or while one is still carried. Nothing is lost when a manifest is not delivered: it waits in the pack. `Freight`, the depot's book in the road encounters' saved data; GameTest `theDepotHandsOneManifestAtATimeAndItPaysOnceInItsCity`. Through a real client (freight run 5, a new world, the depot at 466, 70, 993): a manifest for Valewood worth 64 credits, refused a second while carrying it, and paid at a terminal in a Valewood street.

### Salvage

The Kessler yard's scrap heaps (`underground_city:scrap_heap`, a new block with its own sprite) are a resource site of 54 veins bound to the yard's district, refilling after 60 minutes of active play (never offline), never over a block somebody placed. A heap drops 1 to 4 iron nuggets and, three times in ten, copper, redstone, chain or (rarely) a machined part. The economy model counts the yard (one rotation stands): at most 15 iron, 8 copper and 1 machined part an active hour if every heap is stripped, under the regrowing sites' iron (an invariant).

## Road encounters (D-139)

A framework (`RoadEncounters`, data in `data/underground_city/road_encounters`) and four complete encounters:

| Encounter | Kind | Roads | What happens |
| --- | --- | --- | --- |
| Stranded driver | legit | all | A car boiled over at the verge (a small car of blocks, a smoking campfire). Hand over a drink (12 credits, 20 XP) or patch the hose with a machined part (40 credits, 45 XP). |
| Courier with a letter | legit | all | A courier's chain snapped: take a **sealed envelope** addressed to the city ahead. It pays its fee (20 to 75 credits by the way still to go, and 30 XP) once, at any terminal or shopkeeper's counter in that city. A delivery along the route. |
| Goods off a lorry | underground | the two roads to Blackwater | A van with its back open: buy the lot for 35 credits; the goods are **marked stolen** in one of the other cities, so a fence pays more than they cost and a checkpoint search can find them; one hand-off in five is seen (heat in the city ahead). Ask, and he points at the cannery. |
| Spilled load | environmental | all | Barrels shed from a lorry, free to whoever stops; an unopened barrel is taken back when the encounter ends, an opened one stays (what is in it may be somebody's now). |

Rules:

* **How often:** a player earns the next encounter by **five minutes of travelling on the open road** (moving at least 18 blocks along it every 10 seconds), then has a 15 percent chance at each 10-second check. A thirteen-minute crossing meets one or two (two most often), never three; a short trip often none. Never the same encounter twice running.
* **Where:** 56 to 72 blocks up the road in the direction of travel, beside it (beyond its reach, never on the deck), on loaded, dry, natural ground with room for its props, never within 120 blocks (along the road) of a city circle, within 40 blocks of a shelter or a checkpoint's edge, or within 70 blocks of a roadside place's corner. (The clearance was 80 and 110 until journey run 58 found no verge for a courier 150 blocks out of Ironhaven: the Ironhaven-Valewood road is two thirds bridges, and its two stretches of land each hold a shelter and a place.)
* **Two players:** a partner within 96 blocks shares the encounter and the quiet time after it. Either can settle it; it settles once.
* **Declinable:** every encounter asks; walking on loses nothing. One that nobody is near ends on its own (8 minutes, or at once when everybody is 192 blocks away); its person leaves and its props are taken back.
* **No farming, no duplication:** every outcome goes through the work-order ledger's single commit point (`WorkOrderLedger.prepareEncounter`): what is handed over, what is given, credits either way, XP and heat are written durably before the live inventory or the wallet changes, once per encounter per player; a letter is paid under its own id, once, to whoever brings it, and a copy is taken back for nothing.
* **Persistence:** standing encounters, travellers' road time and delivered letters are saved; after a restart a person of an encounter that is over is not let back into the world.
* Operators and the scripted journey: `/uc_encounters status`, `/uc_encounters meet <player> <encounter>` (sets one up the road from the player at the first fitting spot), `/uc_encounters clear`.

## What is not true yet

* Nobody has stopped at a place or met an encounter with their own hands. The journey's stop at the diner and its courier are scripted (the courier is called with a command rather than waited for).
* No police patrol, roadblock, breakdown of the player's own vehicle, weather or night-only encounter exists. No encounter uses the vehicles.
* The places are one each; a road's character comes from two places and the encounters allowed on it, not from scenery along its whole length.
* **Where an encounter can stand is scarce on the Ironhaven-Valewood road.** It is two thirds bridges, and its stretches of land hold a shelter and a place each; with the clearances above, a verge for an encounter is found only on part of its first stretch out of Ironhaven (about s 170 to 270 of 1,069 in the default world) and a short stretch before Valewood. An encounter that is due and finds no verge waits for the next check, so on that road they come where the land is. `/uc_encounters meet` lists why spots were refused (not loaded, on a bridge or over water, too steep, something in the way, near a shelter, place, checkpoint or city).
