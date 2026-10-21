# The people who live and work in the furnished buildings (D-124)

Status: **implemented; the arithmetic, the bodies, the witness link and the memory are GameTested headlessly (`HouseholdGameTests`, 8 cases, in a run of 297); the real furnished buildings have not been entered by a person, and no resident has been seen in a real house.** This document says what exists, what it costs and what it is not.

## What the owner asked for

Residents with homes, workplaces and schedules for one lived-in neighbourhood and one commercial area, persisting, reacting to a break-in, and visibly part of a burglary's risk: "breaking into a home while a resident should be there must have meaningful consequences" (`docs/design/burglary-and-property.md`).

## Design: arithmetic first, a few bodies second

It is the indoor twin of the street people of [population](population.md) and keeps the same discipline.

* **Who.** `HouseholdPlan` (no Minecraft types): a building has a **resident for each bed** (at most 4) and a **worker for each counter or desk** (at most 2). Names are drawn from the city's own pool, skins from the outfits that say the role (residents the civilian outfits, workers their city's trades), identity is a hash of the world seed, the city, the building and the slot. The same building has the same people in every world with the same seed and in every run. **Nothing is saved.** Residents are not the street roster's people.
* **Where, and when.** The building's own spots come from the sidecar the generator wrote beside each blueprint (`data/underground_city/city_buildings/<id>.json`: door, bed, stove, seat, counter, desk, bench, anvil, shelf, stash, with the building's own coordinates), read by `BuildingSpots`. The clock decides: residents sleep at their bed from ten at night to seven (one night in ten they are away), eat at the stove until nine, are out for most of the working day (about a third stay in), and sit at home in the evening; workers are at their counter or desk from eight to six and away otherwise, so **a shop is empty at night and staffed by day**. The day number shifts each person's hash, so one person is not always home on the same days.
* **Bodies.** `HouseholdService`, once a second, for at most four players per pass: the buildings within two chunks of a player, in loaded chunks, that have a plan; the people who are in *now*; at most one body added per second, standing on the free floor beside their spot (a seat, or the cell next to a bed, a stove, a counter). They are the same entity as a street person (`TownspersonEntity`) with a leash of two blocks, cannot open doors, are never saved, cannot be hurt, and are removed when nobody is within 80 blocks, or when their person has gone out for the day and nobody is within 12 blocks (nobody leaves in front of a player's nose). Caps: 8 around any player, 24 in the world. Each pass asks Lost Cities about at most three chunks it has not been asked about before (a first answer costs tens of milliseconds). `/uc_household status` (operators) counts passes, mean and worst cost, bodies spawned and removed, spots with no standing room and Lost Cities lookups.

## What changes in the world

* **A resident at home is a witness.** `PoliceResponse` counts any person of the city in sight of a forced door, and a resident inside the building is one (with its own message to the thief: "Somebody is at home, and has seen you"). A night break-in of an occupied house is therefore likelier to bring officers than a midday one of an empty house, with no special rule: it falls out of the schedule. `HouseholdGameTests` shows both sides: a house with nobody in it, forced 200 times, raises nothing; the same house with a resident at home raises a witness response.
* **The household remembers a forced door** (`HouseholdService.Memory`, a saved record, at most 512 buildings, most recent last): who forced it, when (in active ticks) and whether anybody at home saw them. For three active hours a resident or worker of that building says so when spoken to. To the thief, if somebody saw them: "You. I know your face. You were in my house." To anybody else: a remark that the lock was forced. The record survives a save (tested) and is bounded (tested).
* **Dialogue** otherwise follows the person's day (a morning, day or evening line; a worker's line at the counter). The lines are a short fixed list in the code, not data: a known gap.

## What it is not

* They do not walk about the city, open doors, buy, sell, work a shop's economy, own anything the Crew ledger knows, or respond beyond a line of speech and the witness link. The shopkeeper does not sell: the exchange, the Q Shop counter and the civilian shop are what they were.
* A building that was not furnished by a package with spots (every stock Lost Cities building) has nobody in it.
* There are no children, no pets, no visitors, no neighbours who call on each other, no guests at a club, no one who is angry for longer than three hours.
* Nobody is simulated while their building is not loaded.
* **Not measured:** the cost of a pass under a real furnished city with many buildings in range; how many people look right in a house; whether a resident standing beside a bed reads as asleep (there is no sleeping pose); whether the schedule is visible to a person at all.
* **Not tested:** the furnished package's real spot coordinates against the real built rooms. The sidecar is generated from the same description as the building, and the plan-versus-world diff proves the blocks are where the plan says, but nobody has asked the service to find standing room at every real spot (the GameTests use a room of their own). `HouseholdService.spotMisses` counts the failures in a real run.

## Files

`BuildingSpots`, `HouseholdPlan`, `HouseholdService`, `TownspersonEntity` (resident mode and the building key), `PopulationService` (dialogue and subtitle), `PoliceResponse` (the resident witness message), `CityBuildings.known`, `HouseholdGameTests`.
