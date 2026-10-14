# Progressive property and apartment restocking

**Direction:** houses and apartments should not remain permanently empty after a robbery, but also should not instantly reset to full value on opening, leaving the area, or relogging. **Status: policy, pure simulation, tests; no Minecraft container runtime integration.**

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
