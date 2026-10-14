# One PC for contracts, cargo, criminal commerce and raids

**October 2026 status:** source-validated feature catalog and Python-only transactional simulation. None of these five screens, NPC buyers, crash scenes, dropboxes, or raids has been integrated into a Minecraft client/world yet. The only runnable artifact is the design simulation and its tests.

## The in-game computer experience

Interact with a physical desktop PC in your apartment, workshop, or business. Later a laptop can show a restricted version. Five tabs share your **one server-authoritative identity and wallet**:

| Screen | What you see | What it starts |
| --- | --- | --- |
| **Contracts** | Career job board, city-specific procurement tasks, optional illegal work | Existing quest framework / bounded employer jobs |
| **Imports** | Suppliers by city, scarce stock, order quantities, delivery options, premium pricing | An actual regional shipment and its risk ledger |
| **Deliveries** | Tracking, current carrier/route status, incidents, deadline, recovery/insurance choices | Optional timed physical cargo recovery |
| **Black Market** | Your real item + attachments/durability, price guide, posted listing, offers, marked dropbox | Escrowed physical goods-for-money handoff |
| **Raids** | Level/gear requirements, objective briefing, city, map route, cooldown, heat and estimated rewards | A real location-based scenario with guards and objectives |

A terminal is an interaction surface, **not an economy engine**. We should reuse FTB Quests for actual contracts/quest flags, a chosen economy mod for prices and wallets, vanilla/LootJS for structure loot modifications, TaCZ for eligible firearms and weapon components, existing lockpicking for door/safe access, and an NPC framework for patrols. A **small custom terminal UI** is justified only for the workflow/transaction glue that existing mod menus cannot express. We should not force a text-editor-only computer mod to behave like a financial and mission operating system.

## Burglary and profession-specific equipment

The original [loot profiles](../../content/gameplay/terminal-operations.json) correspond to existing property categories and use different weighted themes. Every profession should find some relevant loot, but civilian apartments must also contain ordinary possessions, trade goods and components suitable for selling or exchanging.

| Site | Character of loot | Weapon access |
| --- | --- | --- |
| Ordinary home | Food, basic supplies, small machine/technical parts and farming consumables | Very rare/usually absent |
| Wealthy apartment and locked safe | Jewelry, electronics, precision optics, high-quality tools | Low-to-moderate chance of specialized gun components in a **verified locked safe** |
| Office | Commercial records, circuit boards, technical documents | Mostly information and electronics |
| Industrial workshop | Alloys, machine parts, repair supplies and secure lockers | Better chance of specialized weapon parts |
| Gang house / illegal cache | Black-market stock, contraband equipment, specialist attachments | Highest risk and chance of weapon components or actual gear |

The logical item `licensed_weapon_component` is **not** a validated TaCZ item ID. We need to survey the selected TaCZ content packs and define real loot weights and restrictions in actual chests using datapack tables or LootJS. Getting an applicable item is not guaranteed on every visit, and it remains shared household stock subject to slow, budgeted restocking. A safe's contents must not duplicate for the other human player.

## Imported goods and shipment incidents

A foreign-city component may be acquired directly through dangerous travel or bought through an expensive legal or illegal supplier after establishing the appropriate contact. The initial experimental remote import cost is **225% of the ordinary supplier unit price**, and imported batches are intentionally limited. Pricing, timers and incident likelihood remain provisional balancing values.

A remote cargo order has a single authoritative ID and proceeds through:

`in_transit → delivered` (ordinary) or `in_transit → recovery_open → recovered | insured | confiscated` (incident).

A shipment may experience a road collision or gang interception. The PC sends a notification containing a real, surveyed route/scene location and a **25-minute owner-online recovery window**. You can leave home with a vehicle, fight or sneak past looters, and collect cargo in a physical encounter. **If you fail to recover a legal insured shipment**, the buyer receives a refund/claim instead of losing everything to arbitrary police seizure. For illegal/contraband cargo, expiry can trigger confiscation, no insurance and an increase in law-enforcement suspicion.

Shipment incidents should be **uncommon**, not on every purchase. Multiple orders shouldn't use the same scene without explicit event allocation and chunk/position checks. A crash must not appear inside player houses, protected terrain, or unloaded chunks with invalid Y coordinates. It must be an inspectable world event, **not a UI-only percentage dice roll**.

**Two-player fairness:** a shipment and black-market dropbox countdown pauses while its owner is offline, even if the other human is playing. Regional supplier production continues according to shared active-server time. This policy is covered by source-level regression tests.

## Black-market sale through a physical dropbox

The simplest believable sale avoids cumbersome one-off NPC barter conversations:

1. Add a real, owned gun or attachment to a listing; the PC shows a market-price estimate. You choose a price within an allowed band. Price should later account for wear, attachments, demand and local heat.
2. After time passes, an NPC buyer may accept **only if it has enough real economic budget**. Buyer funds are put in escrow immediately. The item remains in your inventory but is reserved against multiple listings.
3. The PC designates a **validated dropbox marker** behind an existing building in your selected city. It cannot create a random chest in an ungenerated block, occupied property or player's protected home.
4. Go to the box and deposit the **exact same item instance, including TaCZ NBT and attachments**. The buyer's money is already escrowed there for the agreed exchange.
5. Retrieve the cash, then the box becomes spent and is cleaned up. The simulation marks the item deposited and pays the seller **exactly once**, even after relogging.
6. The game may subsequently present a witness, sting or increased police heat; not every sale is discovered automatically.

If the buyer or seller expires before deposit, return buyer escrow and leave the gun with its owner. If the gun is **already deposited**, the payout remains claimable rather than disappearing when a timer expires. Persistent states and actual coordinates must be validated server-side so a client cannot spoof delivery or clone a weapon. We will only use physical chest entities when their inventory and escrow permissions can be properly guarded.

## Late-game raids: computer issues a contract, player performs it

Early gang hideouts lead to mid-game hostile warehouses and eventually **police station raids**. The latter must be high-risk, rare and expensive, with a strong response and meaningful negative reputation consequences. All require real buildings, a selectable city/target, gear score, qualifying tier, entry route, objective, timeout and cooldown.

- Tier 2: gang safehouse, with covert recovery or armed entry.
- Tier 2+: rival warehouse, requiring access intelligence or security response.
- Tier 3: police station/evidence-locker raid, major heat and security risk, not a source of endlessly refillable free guns.

Success requires objective verification from the real game server. Picking a job on the terminal does not spawn unclaimed rewards or teleport the player into the building. The game should support **two-player co-op**, but never require a missing third participant, and defeated players need a recovery route rather than a permanent progression lock.

## Reuse-first candidate research

- [FTB Quests 1.20.1 Forge](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-forge): quest progression, chapters, tasks and rewards. Evaluate API/command integration before building separate job tracking.
- [LootJS 2.13.1 for Forge 1.20.1](https://www.curseforge.com/minecraft/mc-mods/lootjs): datapack/KubeJS-facing loot modification so gun components can be assigned to suitable safe/chest types. Verify actual generated loot table IDs.
- [Lightman's Currency 1.20.1 Forge](https://www.curseforge.com/minecraft/mc-mods/lightmans-currency): shops and trading interfaces, with currencies; evaluate its escrow and price APIs but **choose only one authoritative economy owner** versus the earlier Kuro's Economy candidate.
- [Computer Terminal](https://www.curseforge.com/minecraft/mc-mods/computer-terminal): supports a physical in-game computer used for writing/printing; it does **not** advertise a programmable mission/economy GUI and therefore isn't assumed to solve our request.
- Existing Lost Cities fixed structures, Locks Reforged, TaCZ, Lootchest Refill, and chosen NPC/city faction framework supply the physical world/loot/combat building blocks.

These are **candidates**, not installed combinations. A headless mod boot isn't UI or in-world compatibility proof.

## Simulated proof and implementation gap

`scripts/simulate_terminal_operations.py` and its tests cover a single-source catalog, secure price ranges, optional rare safe loot, shipment state/time, legal insurance versus illegal seizure, shared supplier reservations, buyer budget escrow, exact item-instance deposits, idempotent settlements, player-offline recovery clocks and raid tier/gear/objective gates.

Everything is a **logical simulation**. The actual game needs verified coordinates, inventory NBT, physically spawned containers, real NPC buyers/guards, quest progress, controller UI, saved state and multi-user server locks. All of this remains tracked on [the integration issue](https://github.com/Stoffe101/Underground-City/issues/14) and [the property issue](https://github.com/Stoffe101/Underground-City/issues/13).
