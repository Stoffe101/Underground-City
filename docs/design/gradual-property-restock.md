# Progressive property and apartment restocking

**Direction:** houses and apartments should not remain permanently empty after a robbery, but also should not instantly reset to full value on opening, leaving the area, or relogging. **Status: policy and pure simulation (tests) plus a first runtime container, the Household Safe (see the end of this document). It is not placed in generated buildings, has no NPC funding and no evidence/police integration.**

## Core model

Every inhabitable apartment or business has a single shared **property ID**, a small stock capacity, saved current stock, a last-restocked clock position, and a security/household profile. A server-authoritative clock advances only while at least one player is active. When the relevant property is loaded, it checks elapsed active playtime and adds a small number of items for elapsed intervals, up to its capacity.

Example for a normal apartment, starting with five stored items:

| Situation | Remaining property stock |
| --- | ---: |
| You take all five items | 0 |
| Return after 15 active minutes | 0 |
| Return after 40 active minutes | 1 |
| Return after 120 active minutes | 3 |
| Return after 240 active minutes | 6 (capacity) |

The restock policy might later weigh food, basic tools and clothes toward faster recovery, and jewelry, weapon parts and special documents toward much slower recovery.

These numbers are **balancing prototypes**, not real Minecraft loot items being automatically spawned. See [restock policies](../../content/property/restock-policies.json).

## Scarcity and anti-grind rules

- Refill **1 item per interval** initially, not the entire chest. Property types have different schedules, maximum capacity and security tier.
- Progress is **per property, not per player**. Two players opening the same occupied apartment see the same physical goods.
- No instant refills from logout/restart, reloading chunks or opening a second chest in the same home.
- A full house cannot accumulate an invisible backlog of stock to award after immediate theft. Long absences are capped.
- Items never overwrite objects a player deliberately placed in a container. An occupied slot cannot be overwritten.
- Shops/households must fund or produce restocked goods from the local economy. The current model does not implement the underlying NPC budget and therefore **does not yet mint items or money**.
- Important blueprints and story rewards must have reliable quest/contract sources rather than depending on repeatedly robbing the same apartment.
- Robbing the same household twice in a short time should increase resident vigilance, insurance/alarm response and the probability of finding an already empty room, rather than encourage spam.

## Use existing mods if possible

[**[TACZ] Lootchest Refill**](https://www.curseforge.com/minecraft/mc-mods/lootchest-refill) for **Forge 1.20.1** supports refillable containers, configurable category timers and minimum/maximum item counts per refill. This is the leading existing tool to test first, not a proven match for all requirements. Its timers may use real seconds and its placement may be container-specific; we must check whether it honors shared stock, no duplication, loaded-chunk semantics and the active-player clock. If it cannot handle NPC ownership and fiscal budgets, use it for *ordinary world containers* and keep only a narrow household-inventory adapter in RPG Core.

[**Lootr**](https://www.curseforge.com/minecraft/mc-mods/lootr) remains the candidate for per-player co-op exploration chests in abandoned buildings, not lived-in residences. Two copies of the same NPC's belongings are economically incorrect.

## Model already under test

The dependency-free code in [validate_intercity.py](../../scripts/validate_intercity.py) includes a `Stock` state and `reconcile(...)` + `withdraw(...)` functions. Tests cover immediate re-robbery, one-item gradual recovery, maximum capacity, preventing refills banked while full, long inactive/backlog capping, save-data roundtrip, and invalid time/stock rejection. This demonstrates the intended arithmetic, **not** a working Forge burglary feature.

## Runtime acceptance

Connect a test house to a chosen inventory/refill mod, pick a real locked door, remove a few items, let active server time elapse, return to see partial stock, verify persistence through restart, and confirm a second player cannot duplicate the items. Add NPC witness/report and actual home property keys, then enforce per-home economic stock and regional visitor heat. Iterate timers based on 30–60-minute real co-op playtests.

Importantly, a household can become attractive again later. You are never forced to abandon an entire city because every building was looted once.

## Implemented prototype: the Household Safe (October 15, 2026)

`underground_city:property_cache` (display name **Household Safe**) is a Core block entity holding **one shared physical inventory** of nine slots. It is the first real "one authoritative inventory per property" implementation, built in Core because neither Lootchest Refill nor Lootr models active-time clocks, shared stock and consequences together (Locks Reforged remains the planned lock UI).

**Using it**

- An operator places the block (creative OP tab) and looks at it: `/uc_property set <policy>` registers it with one of five generated policies (`apartment_common`, `apartment_affluent`, `office`, `industrial`, `illegal_cache`) and places the initial stock; `/uc_property status` and `/uc_property clear` inspect and unregister it. Players cannot craft, place a restocking safe, or break it in survival.
- A registered citizen right-clicks. A locked safe costs **lockpicks** (craftable: 2 iron nuggets + 1 stick -> 2 lockpicks): 1 for low, 2 medium, 3 high, 4 faction security; a short supply consumes nothing. A forced safe stays open for one active minute for any citizen (the inventory is shared by both players) and then locks again.
- Opening restocks lazily from the policy's context loot table using elapsed **active** time: one stack per interval (apartment: 40 minutes; affluent: 70; office 60; industrial 55; cache 90), into **empty slots only**, never banking a backlog while full and never overwriting player-placed items. Seeds are deterministic per position and restock number, so reopening cannot re-roll.
- Each forced entry raises resident **vigilance** (maximum 3), which lengthens the interval by 25% per level; it falls one level per four quiet intervals. Every forced entry posts a Forge `PropertyForcedEntryEvent` for future witness/evidence systems (nothing listens yet) and records the intruder and break-in count in the block entity.
- No hopper, pipe or item-handler automation can read or write the safe.

**Contextual loot** ([content/property/loot-tables.json](../../content/property/loot-tables.json)): ordinary homes yield only vanilla goods; affluent homes, offices, industrial premises and criminal caches add rare Core regional goods as "imports" (security fabric, control circuits, machined parts/alloy, optics, encrypted designs). The gun-part stand-ins (machined parts, optical components, encrypted designs) never drop in civilian homes. TaCZ parts are not installed, so no firearms or attachments appear.

**Evidence:** six dedicated-server GameTests (loot variety/scarcity sampling, the design's 15/40/120/240-minute table, protection and NBT persistence, lockpick economics and event, vigilance, shared inventory/automation/unbreakability).

**Not implemented:** automatic placement in Lost Cities buildings, ownership or household NPCs, witnesses, evidence/police, alarms, Locks Reforged/Lootr integration, fences, an in-world client capture of the vanilla chest screen, and NPC budgets that fund restocked goods. The five `chests/*` loot tables in the generated world prototype remain unassigned placeholders and are superseded by `property/*`.
