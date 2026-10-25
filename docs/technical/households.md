# The people who live and work in the furnished buildings (D-124)

Status: **implemented; the arithmetic, the bodies, the witness link and the memory are GameTested headlessly (`HouseholdGameTests`, 8 cases, in a run of 297); the real furnished buildings have not been entered by a person, and no resident has been seen in a real house.** This document says what exists, what it costs and what it is not.

## What the owner asked for

Residents with homes, workplaces and schedules for one lived-in neighbourhood and one commercial area, persisting, reacting to a break-in, and visibly part of a burglary's risk: "breaking into a home while a resident should be there must have meaningful consequences" (`docs/design/burglary-and-property.md`).

## Design: arithmetic first, a few bodies second

It is the indoor twin of the street people of [population](population.md) and keeps the same discipline.

* **Who.** `HouseholdPlan` (no Minecraft types): a building has a **resident for each bed** (at most 4) and a **worker for each counter or desk** (at most 2). Names are drawn from the city's own pool, skins from the outfits that say the role (residents the civilian outfits, workers their city's trades), identity is a hash of the world seed, the city, the building and the slot. The same building has the same people in every world with the same seed and in every run. **Nothing is saved.** Residents are not the street roster's people.
* **Where, and when.** The building's own spots come from the sidecar the generator wrote beside each blueprint (`data/underground_city/city_buildings/<id>.json`: door, bed, stove, seat, counter, desk, bench, anvil, shelf, stash, with the building's own coordinates), read by `BuildingSpots`. The clock decides: residents sleep at their bed from ten at night to seven (one night in ten they are away), eat at the stove until nine, are out for most of the working day (about a third stay in), and sit at home in the evening; workers are at their counter or desk in the building's **business hours** (D-185, `data/underground_city/business_hours/hours.json`): shops 08:00 to 18:00, cafes (the Valewood bakery) 07:00 to 20:00, restaurants 11:00 to 23:00, pubs and bars 12:00 to 02:00, clubs 18:00 to 03:00, industrial suppliers (the builders' yard, the hardware desk) 07:00 to 19:00, offices 08:00 to 18:00. A span may cross midnight. A shop's door, public floor, counter and cameras follow its staff, so **a shop is staffed in its hours and empty outside them**, and the glance card at its street door says its hours. A fence keeps its own hours (21:00 to 03:00) whatever the building's. The day number shifts each person's hash, so one person is not always home on the same days.
* **Bodies.** `HouseholdService`, once a second, for at most four players per pass: the buildings within two chunks of a player, in loaded chunks, that have a plan; the people who are in *now*; at most one body added per second, standing on the free floor beside their spot (a seat, or the cell next to a bed, a stove, a counter). They are the same entity as a street person (`TownspersonEntity`) with a leash of two blocks, cannot open doors, are never saved, cannot be hurt, and are removed when nobody is within 80 blocks, or when their person has gone out for the day and nobody is within 12 blocks (nobody leaves in front of a player's nose). Caps: 8 around any player, 24 in the world. Each pass asks Lost Cities about at most three chunks it has not been asked about before (a first answer costs tens of milliseconds). `/uc_household status` (operators) counts passes, mean and worst cost, bodies spawned and removed, spots with no standing room and Lost Cities lookups.

## What changes in the world

* **A resident at home is a witness.** `PoliceResponse` counts any person of the city in sight of a forced door, and a resident inside the building is one (with its own message to the thief: "Somebody is at home, and has seen you"). A night break-in of an occupied house is therefore likelier to bring officers than a midday one of an empty house, with no special rule: it falls out of the schedule. `HouseholdGameTests` shows both sides: a house with nobody in it, forced 200 times, raises nothing; the same house with a resident at home raises a witness response.
* **The household remembers a forced door** (`HouseholdService.Memory`, a saved record, at most 512 buildings, most recent last): who forced it, when (in active ticks) and whether anybody at home saw them. For three active hours a resident or worker of that building says so when spoken to. To the thief, if somebody saw them: "You. I know your face. You were in my house." To anybody else: a remark that the lock was forced. The record survives a save (tested) and is bounded (tested).
* **Dialogue** otherwise follows the person's day (a morning, day or evening line; a worker's line at the counter). The lines are a short fixed list in the code, not data: a known gap.

## Coming and going

**D-129. Implemented; GameTested (`theShopkeeperWalksInThroughTheStreetDoorAtEightAndOutAgainAtSix`); seen only as part of scripted journey runs, not looked for.**

The schedule moves people in and out on the hour (a shopkeeper comes at eight and goes at six; two thirds of residents go out after breakfast and come home at five). When that happens while a player is near, the person is not simply removed or added:

* **A leaver** appears just inside the building's street door (its first `door` spot), the door opens for them as it would for anybody inside (`BuildingGuard`'s exit window), and they walk to a street spot in front of the building (Lost Cities' street, else a floor two to six steps out) and stroll there until nobody is within 24 blocks.
* **Somebody coming home** appears on the street in front and walks up to the door; at the door it opens and they go in. Their indoor body appears at their spot afterwards, as before.
* **Each person has their own minute** of the hour (spread over the first forty minutes). Until it they are where they were: a leaver is still at home to be seen leaving, and somebody coming home is not already in. They are seen to commute only in the ten minutes after their minute, once; a player who arrives later finds them simply in or out.
* **Bounds:** a commuter counts towards the same caps as the household bodies, is gone within a minute and a half whatever happens, never serves at a counter (a shopkeeper on the way to work does not open the shop in the street), and nobody commutes past a building nobody is near. Only the street door is used; nobody opens an interior door or walks further than the street in front.

## What it is not

* They do not walk about the city beyond the street in front of their door, open doors other than their street door on the way out or in, own anything the Crew ledger knows, or respond beyond a line of speech and the witness link. A shopkeeper sells over the counter (D-126) and a fence buys at night (D-125); see [police-response.md](police-response.md#fences).
* A building that was not furnished by a package with spots (every stock Lost Cities building) has nobody in it.
* There are no children, no pets, no visitors, no neighbours who call on each other, no guests at a club, no one who is angry for longer than three hours.
* Nobody is simulated while their building is not loaded.
* **Not measured:** the cost of a pass under a real furnished city with many buildings in range; how many people look right in a house; whether a resident standing beside a bed reads as asleep (there is no sleeping pose); whether the schedule is visible to a person at all.
* **Not tested:** the furnished package's real spot coordinates against the real built rooms. The sidecar is generated from the same description as the building, and the plan-versus-world diff proves the blocks are where the plan says, but nobody has asked the service to find standing room at every real spot (the GameTests use a room of their own). `HouseholdService.spotMisses` counts the failures in a real run.

## Files

`BuildingSpots`, `HouseholdPlan`, `HouseholdService` (the household, the commuters), `TownspersonEntity` (resident, commuter and the building key), `PopulationService` (dialogue and subtitle), `PoliceResponse` (the resident witness message), `CityBuildings.known`, `HouseholdGameTests`.
