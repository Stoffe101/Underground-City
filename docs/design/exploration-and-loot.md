# Exploration, Lootr, world rewards, and scarcity

## Location-based loot

Loot pools should fit the building/region and its economy:

| Place | Typical rewards |
| --- | --- |
| Apartment | Food, minor cash, clothes, household goods, notes |
| Workshop/factory | Industrial parts, scrap, repair tools, component schematics |
| Police/security site | Supplies, documents, restricted evidence and low-odds rare equipment |
| Tech lab | Electronic parts, data, research leads, in-game terminal access |
| Farm/vineyard | Seeds, produce, fertilizer, specialized agricultural items |
| Criminal hideout | Risky contraband, cash, rare weapons components |
| Wilderness/resource outpost | Ores/salvage, survival supplies, hidden narratives |

Loot should reward exploration without making paid crafting, farm production, or market trading worthless. Story-critical progress should have reliable alternatives to RNG.

## Per-player versus shared sources

**Lootr candidate:** Personal loot in supported chests for each player; no competition over ordinary exploratory finds. Not every chest needs Lootr: business inventories, municipal supply depots, contracts, purchased stock, communal cargo, and finite ores are shared and need synchronized authoritative state.

Manually placed or imported chests require explicit loot-table/config tests. Test whether inventories refill, reset, clone, or become ordinary chests after map conversion.

## Blueprint scarcity

An exciting blueprint can be discovered early and kept until requirements are met. Ensure it can be earned through at least one deterministic non-grind path if required for main progression. Avoid duplicating blueprint systems across competing TaCZ addons.

## Resource loop

City outskirts, mountains, quarries and contested zones expose ores and salvage; major progression is workstations, research and profession expertise rather than vanilla enchanting/diamond rush. Document renewal, extraction rights and player/NPC conflict at each resource site.

## Acceptance

Two players each get eligible personal loot; shared goods cannot double-withdraw; table IDs remain valid after reload; loot respects tier progression; no infinite cash/XP exploit via relog or chunk reload.
