# Checkpoint searches and carried storage (D-100)

**Status (October 20, 2026): implemented and tested by Forge GameTests, with and without the real Sophisticated Backpacks mod. No person has walked through a post carrying a backpack.** It extends D-051 (evidence, not omniscience) and D-077 (vehicle cargo). The law, the city rules, the inspection odds and the seeded die are unchanged.

## What the audit found

`CheckpointService.inspect` (October 19) built a copy of the traveller's inventory and read **its 36 item slots and the off hand**, plus the hold of a ridden vehicle. It did not read:

| Where contraband could be | Before | Now |
| --- | --- | --- |
| armour slots (a backpack is **worn in the chest slot**) | not searched | searched |
| a Sophisticated Backpack worn or carried in the pockets | **not searched: a worn pack hid anything from every post** | searched, slot by slot |
| a shulker box or a bundle in the pockets | not searched | searched, only the matching items taken |
| a shulker box or bundle inside a pack | not searched | searched (but the shipped configuration forbids boxes in packs, see below) |
| a backpack lying in a ridden vehicle's hold | not searched | searched; the pack is changed at once, like the hold itself |
| a chest in a house, a placed pack, another player's pack, a Crew-mate's pack, a container within reach | not searched | **still not searched, on purpose** |

The bypass was found by reading the code and then **shown by a failing test** (the new tests run against the old search would put the contraband through).

## How a search works now

1. A checkpoint **closes any open container** of the traveller first: an open backpack screen holds its own view of the pack and could write it back over a seizure.
2. `CarriedStorage.scan` lists every stack the person carries, with where it was found ("your pockets", "what you wear", "your hands", "your backpack", "a shulker box"...). Packs are read through the mod's own item handler by reflection; with the mod absent, or if its internals change, packs are skipped with one log line and the search carries on.
3. Stacks that the law classifies as contraband in that city are taken. A stack counts once, in the place it was found; legal goods beside it are never touched (tests: wheat, iron and bread stay in the pack that held the contraband).
4. **One prepared record** carries everything: the inventory after the seizure, the changes to carried packs, the fine, the new heat and the **record of the search**. Pack changes are `(pack contents id, slot, the stack expected there, the replacement)`. The record is written to disk *before* any live state changes.
5. Settlement (`WorkOrderService.recover`, the same function login recovery runs) applies the inventory, then the pack changes, **idempotently**: a slot that no longer holds the expected stack is left alone. A crash right after the decision therefore takes the goods **exactly once** when the player logs back in, a second recovery changes nothing, and nothing is duplicated (a test crashes after the prepared record and replays recovery twice).
6. The traveller is told where things were found and why they were searched: "this was a routine inspection: the post searches about 26 in 100 travellers with your record in Valewood (heat 2)". The last 12 searches (clean or not) are kept in the person's ledger record with the post, the chance and heat, the outcome, the stacks, the fine and the places.

## What is deliberately not searched

Only what the person **carries**. The server can read any loaded chest, a house, another player's backpack or a Crew-mate's inventory; the search does not, and a test puts a Crew-mate's pack full of contraband next to a clean traveller: the traveller is **not** a suspect. Crew membership implicates nobody. A placed pack is a block; a pack in a house is storage, not carried storage.

## Tests (`CheckpointSearchGameTests`)

Armour slots; a shulker box and a bundle in the pockets (only the contraband in them is taken, the box keeps its legal goods); storage the traveller does not carry is never searched; every search is recorded with its reason and outcome; and, **when Sophisticated Backpacks is loaded (the `survival` set)**: a worn pack and a pocketed pack both searched with the legal goods untouched, a crash after the prepared record replayed twice, another player's worn pack untouched. In the `survival` configuration this ran **199 of 199**; in the other configurations the pack tests pass trivially.

## Limits

* The shipped pack configuration (D-092) forbids shulker boxes and other packs **inside** a Sophisticated Backpack, so a shulker box inside a pack cannot occur in play. The code path exists and the test records `[UC-SEARCH-AUDIT] a shulker box can be put into a Sophisticated Backpack ...: false`; the nested-in-a-pack case is therefore **not exercised**, only the nested-in-pockets case is.
* Settlement replaces the player's stacks from the journaled copy, so any other holder of an old item object has a stale view. The search closes the player's container for that reason; nothing else holds one.
* A backpack inside a vehicle's hold with the vehicle set loaded was not tested together.
* The checkpoint guard still stands beside the road (D-062): the zone decides, nothing pursues. There is no police AI, no pursuit and no response to a seizure beyond the heat.
