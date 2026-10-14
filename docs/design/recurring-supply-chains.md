# Recurring regional supply chains

**Status (October 2026):** original procurement data, reproducible planning model and unit tests, **not installed Minecraft recipes, NPC inventories, actual wallets or in-game travel detection**.

## The key gameplay rule

Visiting a rival city once to unlock a profession tier is not enough. **Specialist components get used up** in repeatable work orders and crafting. Your home city can always supply basic survival materials such as normal iron, cobblestone and wood. But high-grade alloys, advanced optics, exotic agricultural inputs and control modules have specific production hubs.

You can buy more than one recipe's worth at once, bring a vehicle or hire a convoy, and prepare for multiple jobs. We want a compelling expedition with meaningful rewards, **not a forced commute for every crafting click**.

## Three source economies

| Origin | Specialty goods | Recurring demand |
| --- | --- | --- |
| **Ironhaven** | Refined alloy, machined parts, security fabrics | Machinery, weapon mechanisms, farms' control infrastructure |
| **Valewood** | Rare seeds, biopolymer, agricultural catalysts | Advanced crops, composite materials, ecological engineering |
| **Blackwater** | Control circuits, optical components, encrypted designs | Security, precision equipment, electronics, research |

The nine items are **provisional logical commodity IDs**, not registered item IDs or final recipe ingredients. Source: [recurring supply data](../../content/progression/recurring-supply.json). Before adding items, we will cross-reference the chosen installed mods and prefer actual existing components where appropriate.

## Why all careers repeatedly cross borders

The model includes **12 repeatable recipes and work orders**, three for each initial profession. Examples:

- **Farmer:** greenhouse irrigation servicing consumes Ironhaven machined parts and Valewood rare seeds. Advanced climate modules require Valewood catalysts plus Blackwater electronics.
- **Smith:** precision machinery needs Ironhaven alloys and Blackwater circuitry. Composite equipment consumes Ironhaven, Valewood and Blackwater inputs.
- **Gunsmith:** upgraded optical mounts require Ironhaven machine parts and Blackwater optics. Later specialist equipment also uses Valewood biopolymer.
- **Technician:** industrial controllers consume Blackwater circuits and Ironhaven machining. Security and environmental engineering use resources from every city.

Each craft/work order deducts its inputs. Completing its first copy grants no magic permanent component supply. More production means another real supplier order, a legal merchant shipment, a cooperative trade with the other player, or an optional dangerous raid.

## A useful expedition cycle

1. Your job board or crafting preview groups missing supplies by **city and supplier**, not by individual recipe.
2. Pick **several jobs** and request a shopping list covering 2–4 planned batches.
3. Choose a legal convoy permit or a riskier border crossing. Travel itself offers escort contracts, ambushes, scavenging, smuggling and regional side quests.
4. Reach a real supplier/warehouse. Buy limited stock using actual in-game currency, or investigate illegal alternatives with their own consequences.
5. Take the resulting materials home, craft multiple batches, sell/complete work orders, and gradually deplete your local inventory.
6. Return later. Suppliers rebuild their inventory over **active server time**, so the city remains worth revisiting rather than being permanently looted.

The current model caps each direct order at **8 units**, allows multiple orders while available stock lasts, and replenishes each supplier at a different bounded rate. Rare encrypted designs replenish slower than common machinery parts. Both players draw from the **same supplier supply**, not duplicated personal copies.

## Balance, travel access, and co-op

- Low-tier common materials are widely available. There is **no monopoly on vanilla iron**.
- **Tier 2** unlocks require visiting a rival city, and **Tier 3** requires visiting all three (separate first-discovery rules).
- **Tier 2/3 production** repeatedly consumes inputs from at least two or three origins. Even an expert needs future shipments or trips.
- Legal supplier access remains possible despite war using reputation, escorts, trade permits or neutral merchants.
- A proposed **licensed import service**, enabled only after visiting its city, could bring tiny batches at more than twice the unit price, with delays. This is **not yet implemented**. It is intended as a safety valve for occasional missing components, not infinite free delivery.
- Illegal acquisition and smuggling can provide higher-risk alternatives, once theft/faction systems work; they must use scarce shared stock and actual consequences.
- A partner can bring parts back or specialize in logistics. There is **no third-player requirement**.
- Suppliers and apartments recover instead of becoming permanently empty. Player absence doesn't bankrupt a business or reset a shop's stock; the simulated clock only advances during active play.
- Difficulty should mostly come from hostile territory, access costs, limited stock, rare sources and good logistics, rather than arbitrary teleport bans or extremely grindy recipes.

## Implemented *model*, not live gameplay

[Supply simulation](../../scripts/simulate_regional_supply.py) is dependency-free Python. It models live-clock stock recovery, shared supplier stock, location- and permit-gated purchases, player inventories, credit deductions, atomic ingredient consumption, and durable idempotent transaction IDs. It also groups deficits by originating city.

[Test cases](../../tests/test_regional_supply.py) cover repeated batch crafting, buying in another city, supplier contention between two players, absent offline progression, credit/stock depletion, save/restore and rejection of duplicated transactions.

**Server integration remains entirely to be done.** These calculations will need a Forge/KubeJS or existing mod adapter to enforce actual coordinates, citizenship, NPC budgets, inventories, database/world save durability and game crafting registration. We must inspect existing compatible economy and item mods before choosing the narrowest path. The simulation presently creates starter stock and supplier replenishment without an actual production workforce and therefore must **not** be used to mint live economic currency.

## Final acceptance

Two players can work in different careers, pick useful cross-city work orders, visit foreign suppliers (legally or illegally), bring home and consume a few batches of imported goods, see the next orders require additional supplies, return later to partially restored supplier stock, and use the map and faction controls throughout. NPC production, markets, guards and traffic must be visible enough to make the cities feel inhabited.
