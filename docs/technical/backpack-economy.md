# Backpacks: Sophisticated Backpacks as the one provider (D-092, D-093)

**Status:** implemented as data, code and Forge GameTests; tested in the cloud with scripted players, and by **one scripted real client** in the combined journey (it buys the pack at the exchange screen, wears it with a shift-click, opens it with the mod's key **B** and moves items and an upgrade in; screenshots at 720p, 1080p and 1440p; see [testing.md](testing.md)). **No person has opened the backpack screen, dragged an item into an upgrade slot or worn the pack in a session of play.** Owner direction of October 19, 2026 replaced Traveler's Backpack with Sophisticated Backpacks.

## Provider and versions

| Mod | Release (pinned, hash-verified) | Licence | Role |
| --- | --- | --- | --- |
| Sophisticated Backpacks | 1.20.1-3.26.12.2206 (Modrinth `X7q2xTaV`, 2026-10-13) | **All Rights Reserved** | the backpack, its tiers and upgrades |
| Sophisticated Core | 1.20.1-1.5.12.2412 (Modrinth `YxgjJy68`) | **All Rights Reserved** | required library (storage, upgrades, GUI) |

Both are in the opt-in **`survival`** set (with Thirst Was Taken 2, Farmer's Delight and Comforts), as **runtime-only, hash-pinned downloads from the author's page**. Nothing of them is committed, repackaged or redistributed; `scripts/fetch_dev_pack.py` and Gradle download them and refuse a file whose SHA-512 differs from `content/modpacks/dev-pack.json`. Underground City ships only configuration it wrote itself and two small mixins that gate the mod's server paths. The mod needs Forge 47.1+ and Core 1.5.8+; its Create hook is optional and inert: **Create is not used anywhere** (D-080).

**Why this one.** It has a real configuration surface (slot counts and upgrade slots per tier, a switch for every item and its recipe, a limit per upgrade type, a nerf for carrying many packs, a switch for mobs wearing backpacks), a placed pack that is a block with an item handler (so the home guard recognises it), and an active 2026 release line. The retired provider crashed one server start in eight in the cloud before the world existed (see [first-start-stability.md](first-start-stability.md)) and had no per-item switch. **Traveler's Backpack is retired and never loaded together with Sophisticated Backpacks.**

## What a player gets

* **A basic pack early.** The leather backpack (27 slots, one upgrade slot, the size of a chest) is **sold in every city for 70 credits** (about three first work orders; at most four on the shelf, one every 45 minutes) and is craftable from 4 string, 4 leather and a wooden chest. String is a trade good in the simulation world ([monster-resources.md](monster-resources.md)).
* **More room costs materials, not a trade-off menu.** Each tier is the previous pack ringed with the metal. Sizes are **at or below the mod's own defaults** and the generator refuses to exceed them:

| Tier | Item | Slots | Upgrade slots | Made from | Mod default |
| --- | --- | --- | --- | --- | --- |
| 1 | `backpack` | 27 | 1 | 4 string, 4 leather, a chest (or 70 credits) | 27 / 1 |
| 2 | `copper_backpack` | 36 | 1 | the basic pack and 8 copper ingots | 45 / 1 |
| 3 | `iron_backpack` | 45 | 2 | the basic pack and 8 iron ingots | 54 / 2 |
| 4 | `gold_backpack` | 54 | 2 | the iron pack and 8 gold ingots | 81 / 3 |
| 5 | `diamond_backpack` | 72 | 3 | the gold pack and 8 diamonds | 108 / 5 |
| 6 | `netherite_backpack` | 90 | 4 | the diamond pack, a netherite ingot and an upgrade template | 120 / 7 |

* **Convenient, not overpowered.** Sixteen items are enabled, fifty-five are switched off, each with a stated reason in `content/gameplay/backpack-policy.json` (the single source). Summary:

| Enabled | Why it is safe |
| --- | --- |
| pickup, filter, restock, deposit, crafting grid, jukebox | convenience; they move what the player already has, create nothing, and the crafting grid is the plain table everyone owns |
| magnet (range 3, worn pack only) | picks up what you just dropped, not the street |
| feeding | eats real food from the pack when hungry; works **only in a worn pack** (see the gate below); creates nothing |
| stack upgrade, first tier only, **one per pack** | doubles stacks inside the pack for 72 copper ingots; the x4 and higher tiers are off |

| Switched off (55 items) | Reason |
| --- | --- |
| stack tiers above x2, conversions, downgrades | multiplying storage until supply and trips stop mattering |
| smelting, smoking, blasting and their automatic versions, compacting, anvil, smithing, stonecutter, alchemy, tool swapper | portable workstations and automation: production belongs to workstations and places (the vision's crafting pillar) |
| void, infinity, survival infinity, everlasting, inception, XP pump, battery, mob catcher | deletes items, creates supplies, nests packs, or has no place in the economy |
| tank and pump | fluids beside Thirst Was Taken 2's water purity are untested: deferred, not rejected |
| every "advanced" variant | larger range and more filter slots are a later, deliberate decision |

* **Server settings (new worlds start from them):** no other player can open the pack you wear; carrying more than two packs slows you (slowness, one level per extra pack); only the worn pack runs automatic upgrades; no container items (shulker boxes) and no packs inside packs; **no mobs wearing backpacks** (a backpack is made or bought, never farmed); no backpack loot in vanilla chests; the mod's duplicate-contents check stays on; the item fluid handler stays off (the mod itself warns that it can duplicate).

## Backpacks and homes (D-093): what the tests found and closed

A placed pack is a block with an item handler. `ResidenceGuard` already protected every such block by capability, so the rules of [crew-economy.md](crew-economy.md) apply to it with no special case: a Crew home's shared pack is opened, broken and picked up by the members who have the storage right; a builder without it, an outsider and a guest cannot; a pack a member **claimed** is theirs alone; a hopper cannot be built beside a pack by someone who may not open it. These are GameTests with a control each.

Two further holes were **found by reading the mod's bytecode and then shown by a failing test**, and are closed by pseudo mixins (the same pattern as the Gun Smith Table gate; `required:false`, a no-op when the mod is absent):

1. **The inventory-interaction packet** (`InventoryInteractionMessage`) carries a block position chosen by the client. Its handler calls `InventoryInteractionHelper.tryInventoryInteraction` with no reach check, no right-click event and no rights check, so a modified client wearing a pack with a **Deposit or Restock** upgrade could put into or take from any loaded container, including a private chest in somebody's home. `InventoryInteractionHelperMixin` asks `BackpackGate`: within 8 blocks, and the guard's container right at that position (creative operators pass). Without the mixin the test `theInventoryInteractionPacketCannotReachIntoAChestTheWearerMayNotOpen` fails (shown by disabling it).
2. **A placed pack with the Feeding upgrade feeds every player within three blocks** (its tick has no wearer). In a Crew home that is an outsider eating the Crew's stored food. `FeedingUpgradeWrapperMixin` makes the Feeding upgrade work only in a worn pack. Without it the test fails: a bystander's food level went from 4 to 19.

Known limits: a placed pack's **magnet** still collects dropped items within three blocks, which can reach across a thin wall into a neighbouring home (items on the floor, not container contents).

## Existing worlds (do not lose anything silently)

* The tier sizes and nerfs live in `world/serverconfig/sophisticatedbackpacks-server.toml`, which Forge creates from `defaultconfigs/` **only for a new world**. An older world keeps the file it has. At every server start `BackpackPolicy` compares the configuration the mods are **actually running with** against the policy and logs `[UC-BACKPACKS] ... matches` or one warning per difference. To bring an old world to the policy, stop the server, copy `content/generated/survival-config/defaultconfigs/sophisticatedbackpacks-server.toml` over the world's file and start again. The item switches (`sophisticatedcore-common.toml`) are per installation and take effect everywhere.
* A world that contains **Traveler's Backpack** items or blocks: see [backpack-migration.md](backpack-migration.md). Nothing is converted automatically; the document says exactly what happens and how to avoid losing items.

## Tests (cloud, Forge GameTests; `BackpackGameTests`, `ProgressionAuditGameTests`)

The running configuration is the policy; every item of the mod is classified; recipes follow the policy (enabled items craftable, disabled items have no recipe); a worn pack keeps items and upgrades through a save and a load; upgrade-slot counts and the stack-upgrade limit are enforced by the mod's own placement check; a death drops the pack and a pick-up returns the same contents; a placed pack keeps its contents and comes back when broken; two players' packs are independent and the duplicate check is on; the Crew-home rules above; the packet gate and the feeding gate, each with a control and each shown to fail without its mixin; the shops sell the plain pack; the backpack recipes need exactly the monster drops the policy declares.

**Not tested:** a person using the screens (sorting buttons, dragging with a mouse, the upgrade tab's settings), two clients opening the same pack and seeing each other's changes, an upgrade doing its work through the client, and playing with the packs for more than a few minutes. The real-client journey clicks through the screens' own handlers; it has no mouse pointer and no held shift key. Note for anyone writing a client script: the mod's upgrade slots are **not** among the menu slots a client can read (`AbstractContainerMenu.slots`); the screen draws them in the tab at its left edge, and only the server can read them.
