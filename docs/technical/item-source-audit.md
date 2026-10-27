# Item-source audit: no work order may ask for something a player cannot get

Status: **implemented and run in CI-equivalent form locally** (October 18, 2026). `python scripts/audit_item_sources.py` reports; `--check` fails on any finding; `tests/test_item_sources.py` (9 tests) runs it against the real data and against deliberately broken copies of that data to prove it notices.

## Why

The roadmap's exit criterion for Phase P5 is "no recipe dead ends", and D-053 says no progression may depend on luck. Both were promises in prose. Fifty-one work orders and 25 recipes are too many to check by eye, and the first run of this audit found a **real** dead end that the earlier sprints and their tests had missed (below).

## What it checks

For every work order it takes each required item and finds the lowest career level at which a player can *deterministically* have it, using only Core's own data:

| Source | Counts as | Level |
| --- | --- | --- |
| a vanilla `minecraft:` item | the world supplies it | 1 |
| a starter kit | deterministic | 1 |
| the regional exchange (`regional_stock`) | deterministic (the order's `foreign_visits` is its own check) | 1 |
| a crop's or block's drops (`loot_tables/blocks`) | deterministic | 1 |
| a work order's `rewards` | deterministic | that order's level |
| a recipe whose every ingredient is obtainable (fixed point over all recipes) | deterministic | the highest ingredient level |
| a rolled loot table (`exceptional_loot`, `loot_tables/property`) | **luck** | never counts |
| code that pays an item (today only the Technician's trace paying a Data Ledger, D-054) | deterministic | 1; listed in `CODE_SOURCES`, and a test fails if the named code stops containing it |

A requirement is then one of: fine; **DEAD END** (nothing makes it); **LUCK ONLY** (only a rolled table does); **TOO LATE** (the only deterministic source is a reward or recipe at a higher level than the order that asks for it); **UNKNOWN GUN** (a gun requirement none of whose guns has a blueprint from a kit or from a reward at or below the order's level). An item that belongs to another mod which the order itself requires (`requires_mod`) is listed as *external* and is not judged: that mod's production chain is its source, and a data audit cannot see inside it.

## What it found, and what was done

| Finding | Cause | Fix |
| --- | --- | --- |
| **UNKNOWN GUN**, `arms_brokerage` (Gunsmith level 11, 640 credits, the restricted rifle for a trusted buyer) | The AK-47 and M4A1 need a blueprint to be built at the Gun Smith Table (D-073), and the only sources were rolled loot. The contract at the top of the underground gunsmith branch could not be completed without luck. | A new underground errand, **`dray_rifle_run`** (level 10, needs *Filed Serials*, trust 1): six machined parts and three optical components, bought at the regional exchange (which means a visit to another city), pay 330 credits, 450 XP and the **AK-47 blueprint** (not the M4A1's: see below). Margins, reward value (200 credits against the 90 percent cap) and trust ladder pass the existing generators and the balance validator. |
| **A second dead end, found by the rifle-chain GameTest while building the fix** (not by the data audit, which cannot see TaCZ's recipes) | TaCZ's own table recipe for the **M4A1 fits a stock** (`"attachments": {"stock": "tacz:stock_m4ss"}`), and a contract takes only *plain* factory guns (`WorkOrder.matchesFactoryGun`: no custom name, enchantment or fitted attachment, because delivery consumes the item). A crafted M4A1 could never be delivered, so a blueprint for it would have led nowhere | the new contract pays the **AK-47** blueprint (plain from the table); `firearmContractsExist...` now checks, with TaCZ loaded, that every gun requirement lists at least one gun that the table builds plain (`OptionalModProbes.plainTableGuns`); the AK-47 is the other gun `arms_brokerage` lists |
| Wine and vodka orders (Vinery, Kaleidoscope Tavern) | The goods come from those mods' own chains | none needed; listed as external. Whether the chain can actually be run on a dedicated server with the economy is a pack test (see the sprint report, farming and brewing) |
| Data Ledger, Cannabis Flower | A trace pays the ledger; the crop drops the flower | modelled (`CODE_SOURCES`, block loot tables) |

## Limits (stated so the audit is not mistaken for a playtest)

* It proves the *existence* of a path, not that the path is pleasant: prices, stock refill, travel time and luck-free time-to-first-contract are balance questions for a human session.
* It reads level as "career level at which the order opens", not the player's actual route; a player who skips an order still needs the reward's order done once.
* TaCZ's own gun recipes are not read: TaCZ crafts guns from vanilla materials (iron, copper, gunpowder in the Gunsmith's kit), so the only gate Core adds is the blueprint. A Core-owned TaCZ gun pack with component costs (research in the sprint report) is not built.
* Recipes in other mods are not read; an ingredient from another mod is treated as not obtainable unless Core data supplies it, so the audit errs toward reporting.

## Monster drops (D-095, added October 19, 2026)

The simulation world ([simulation-world.md](simulation-world.md)) has no hostile creatures, so a recipe that needs a creature's drop is a dead end unless something else supplies it. The same script now reads the **catalogue of monster-drop roots** (`progression/monster_resources.json`: gunpowder, string, slime balls, ender pearls, blaze rods and powder, magma cream, spider eyes, bones and the drops nothing needs) and reports two more findings:

| Finding | Meaning |
| --- | --- |
| **MONSTER ONLY** | a work order's item, a Core recipe's ingredient or a backpack item needs a root that has no route (no exchange line, no replacement recipe) |
| **BROKEN ROUTE** | a route names an exchange line that does not exist, sells another item, is not a small finite shelf, or a derived root (blaze powder from rods, magma cream) whose source has no route |

`tests/test_item_sources.py` (`MonsterDrops`) copies the data, breaks one route at a time and shows that each finding fires. The audit above cannot see recipes of other mods; the Forge GameTest `ProgressionAuditGameTests` and the jar scan `scripts/scan_pack_for_monster_drops.py` do, and are described in [monster-resources.md](monster-resources.md). Together they are the regression tests for D-095; none of them is a balance test.
