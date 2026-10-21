# The hybrid resource economy (D-120, D-123)

Status: **implemented as data, an audit, a regional-exchange line set, one regenerating site and GameTests; the scarcity package has not yet been run in a real generated world, the quarry has not yet been dug by a person, and nobody has played an hour of it.** This document says what exists, what each number is for and where the evidence stops.

## What the owner asked for

A new world has **no natural ores**. Materials come from four places, each bounded: bought from the cities' finite shelves, salvaged, taken from one regenerating site that renews in active play time, and (where a recipe allows) crafted from something that has one of those routes. Nothing in an existing save is removed or replaced. No recipe or work order may need an item that has no route.

## The four routes

| Route | What exists | Where |
| --- | --- | --- |
| **Shelves** | Ten finite, shared raw-material lines in the regional exchange, sold in the cities only: iron (three cities, at 9, 11 and 12 credits), coal, copper, charcoal, redstone, lapis, gold, diamond. A shelf is a few units, refills a few at a time in active ticks and never stockpiles (a full shelf is worth at most 1,200 credits, enforced). | `content/gameplay/materials-supply.json`, `scripts/generate_survival_supply.py`, `data/underground_city/regional_stock/materials/` |
| **The quarry** | One built district, the old quarry (a stepped pit beside the arrival landing), with fourteen vein cells: six iron, five coal, three copper. A mined vein grows back after **20 minutes of active play time** and never while nobody is online. At most 42 ores an active hour, if a person mines every vein the moment it is back. | `scripts/generate_resource_site.py`, `districts/old_quarry.json`, `resource_sites/ironhaven_quarry.json`, `ResourceSites` |
| **Salvage and crafting** | Whatever the base game and the pack already give: smelting what the shelves and the quarry sell, the backpack tiers, vehicle parts, machined parts. Nothing was removed or added to recipes for this. | existing recipes |
| **Natural ore** | **None in a new scarcity world.** The opt-in package `underground_city_scarcity` is one Forge biome modifier (`forge:remove_features` for the ore step) that applies to chunks generated after it is installed. | `content/generated/world-scarcity/`, `scripts/scarcity_content.py` |

Gold, diamond, redstone and lapis are on the shelves only and are deliberately not in the quarry: the rare things stay rare and priced, and the cheap base metals are the ones a new player can earn their way to.

### Shelf prices

| Shelf | City | Price | Start | Capacity | Refill |
| --- | --- | --- | --- | --- | --- |
| iron ingot | Ironhaven | 9 | 24 | 32 | 4 per 20 min |
| iron ingot | Blackwater | 11 | 10 | 16 | 2 per 30 min |
| iron ingot | Valewood | 12 | 8 | 12 | 1 per 30 min |
| coal | Ironhaven | 3 | 32 | 48 | 8 per 20 min |
| copper ingot | Ironhaven | 7 | 16 | 24 | 4 per 30 min |
| charcoal | Valewood | 2 | 24 | 32 | 6 per 20 min |
| redstone | Blackwater | 6 | 20 | 32 | 4 per 20 min |
| lapis lazuli | Blackwater | 11 | 8 | 16 | 2 per 40 min |
| gold ingot | Blackwater | 38 | 6 | 10 | 1 per 45 min |
| diamond | Blackwater | 150 | 2 | 4 | 1 per 120 min |

These prices are first guesses. They are checked for one thing only: **an order is never made a loss by buying every raw material at the exchange** (`RegionalGameTests`: the specialist-commodity margin stays in its 1.3 to 2.1 band, and the all-in margin with the raw-material shelves stays at or above 1.1). That test found a real problem when the shelves were added (a recipe's margin fell to 1.19) and is why the band is now computed in two parts. The economic model of October 20 (`docs/technical/economy-model.md`) **does not yet include the shelves or the quarry**: its hands-on-minutes assumption for iron (0.9) was written for mining, and the model's income tables should be re-run with the quarry's 42-an-hour ceiling before anybody trusts a number for a new world.

## The quarry in detail (D-123)

* **Where.** In a *new* archipelago world only, placed by `DevBootstrap.placeQuarry` on level, dry natural ground 90 blocks east of the graded landing (within 60 of that point), never overlapping the landing. Where nothing fits it is skipped with a log line and the world works without it. An existing world is never given one; an operator may build it by hand with `/uc_district build old_quarry` and undo it with `/uc_district undo old_quarry confirm` (the district builder's own reversible build).
* **Shape.** A 19 by 19 block pit, six one-block terraces deep, so it can be walked in and out of without a ladder. The fourteen veins are the treads of the terraces, each with an open face.
* **Renewal.** `ResourceSites` runs every five seconds while somebody is online. A vein cell that is not its ore and is air is "depleted": the active clock (`LockService.clock`, which moves only while somebody plays) is written beside it. After `interval_minutes` of active time it grows back, unless something alive stands in the cell. A cell that holds any other block (somebody built there) is never overwritten and is forgotten until it is air again. A clock that goes backwards (a restored backup) restarts the wait and never pays out early. Nothing is placed in an unloaded chunk.
* **No offline gain.** The clock does not advance while nobody is on, so a world left alone for a week renews nothing, and a vein mined a minute before logging out is still waiting when its player returns.
* **Bounds.** At most 64 veins and no interval under five minutes are accepted by the loader; the generator refuses a layout whose ceiling is above 60 ores an hour, and the unit tests repeat the check.
* **Status.** `/uc_site status` (operators) lists each site, where it stands, how many veins wait and how many ores an hour it could give.

## The reachability audit

`scripts/audit_item_sources.py` (also run by `validate_repo.py` and the unit tests) reads every Core work order and recipe and asks, for each item the game itself does not mint: is there a deterministic source? It has three catalogues: monsters (D-095), a new kind of finding **`ORE ONLY`** (D-120), and the exchange's own lines. `progression/ore_resources.json` lists the items that in the base game come from mining an ore, and for each says what replaces mining in a scarcity world: an exchange line (checked to exist, to sell that item in a city, to be small and finite), a base-game recipe from another root, or, for iron, coal and copper, **also a vein of the quarry** (checked: the site file must exist and hold a vein of the right ore). A recipe or order that needs a root with no route fails the audit; a root that nothing needs says so explicitly. Result at the time of writing: 54 work orders, 25 recipes, 64 items with a deterministic source, **0 findings**. The unit tests in `tests/test_item_sources.py` (class `OreRoutes`) prove that the audit *notices* a deleted shelf, a stockpile, a wrong item, a missing site and a site with no vein of an ore.

What the audit cannot say: whether a third-party mod's own recipes (TaCZ, Modern Life) need an ore item that has no route. That is a manual review item (and the reason iron, copper and coal each have a second source).

## Evidence, and where it stops

* **Tested:** the shelves (`MaterialsSupplyGameTests`: they load, are finite, shared, priced inside the cap and refill in active time); the order margins (`RegionalGameTests`); the quarry (`ResourceSitesGameTests`: data bounds and every vein is a base-metal ore inside the district and placed by it; a vein grows back at exactly its interval and not a tick before; nothing grows back while the active clock stands still, with the real pass and the real ledger; a living thing in the cell holds it; a builder's block is never overwritten; a clock that went backwards pays nothing early; the saved record survives a round trip; undoing the district leaves nothing behind); the generator and the audit (Python unit tests, 287 in all).
* **Not run:** the scarcity package in a real generated world (`scripts/run_ore_audit.py` exists to count ore in a base world against a scarcity world and has not been run for this report); the quarry placed by a real new-world start and dug by a person; anything about how long a new player takes to earn a first backpack tier from these sources; the economic model with the new sources.
* **Not done:** salvage as a designed system (nothing yet turns city junk into metal); a second site; ore processing as a profession; the shelves' prices being tuned against the model.
