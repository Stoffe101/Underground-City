# Real starter economy and work orders

Minecraft 1.20.1 / Forge 47.4.10 / Java 17. Q Shop **1.8.4** is the sole live wallet provider for this slice. Core owns citizenship, career XP and world-specific work-order reservations, not a second balance. No RoleCraft or FTB quest progression provider is installed alongside Core.

## Install into a new development instance

1. Obtain the original author's [Q Shop release](https://github.com/QLNPLUS/Q-shop/releases/tag/v1.8.4-forge-1.20.1). The Forge JAR's SHA-256 is `ab32e33a7190bbcb7b4b10d9fe6ae4a38f3e65eba5ea1ac9f315d3bd798c5199`. Install the same Q Shop/Core versions on server and clients.
2. Build Core with Gradle 8.8: `gradle -p rpg-core build`. Compilation verifies that author release's checksum and uses its supported API as a compile-only dependency. Core's distributable JAR contains no Q Shop classes or textures.
3. Run `python scripts/install_qshop_templates.py --server-root <new-instance>` before world initialization. The installer rejects any existing `serverconfig/qshop`, including renamed dedicated worlds and client saves. Existing saves require backups and a deliberate migration; never overwrite them with this installer.
4. Register `/uc register valewood farmer`, craft/place `underground_city:operations_terminal`, then right-click it.
5. Contracts → accept a harvest order → obtain 16 wheat → return and deliver. The server removes goods, pays 24 Q Shop credits and awards 125 career XP, unlocking level 2. Bake eight bread with ordinary Minecraft crafting, deliver the bakery order for 36 credits/180 XP. Level 3 opens pumpkin-pie orders.
6. Imports → civilian shop → spend those credits on seeds, rations, cobblestone or a crafting table, or sell surplus wheat/logs/stone. Currency appears as **Cr** in compact Q Shop controls. The internal ID remains `credits`.

Other initial careers have three working vanilla delivery orders each: Smith (pickaxes/hoppers), Gunsmith (training arrows/crossbow/range targets), Technician (redstone torches/repeaters/daylight detectors). Gunsmith orders are explicitly archery/range preparation, **not TaCZ firearms**. These are starter levels, not the advanced regional tiers. Existing vanilla crafting supplies production; Core does not replace crafting or prove who originally produced a tradeable item.

## APIs, authority and persistence

- `QShopAddonApi.currency().getBalance` and `.set` are the only wallet reads/mutations in production Core. Absolute `.set` targets replay an interrupted prepared payment once. Transactions never take a client-supplied price, reward, item quantity or wallet target. Currency configuration must actually contain `credits`.
- `/qshop open uc_starter_supply` remains the supported shop-opening command, executed as the player. Only this fixed starter ID can be opened by our adapter.
- `TerminalNetwork` protocol 4 declares packet directions (protocol 4 adds the regional purchase packet and the multi-item snapshot). Requests carry a block position, bounded job ID/action and the **server-generated accepted-order UUID**. The server checks registration, living/non-spectator player, loaded block, matching terminal and ≤8-block distance. Requests are rate limited; stale completion/cancel UUIDs reject.
- Data definitions are vanilla datapack resources `data/underground_city/work_orders/*.json`. Schema 1 has title, career, level, one real item registry ID, count, integer credits/XP, capacity and refill ticks; schema 2 (see below) lists up to four requirements and a rival-city visit gate. `/reload` atomically replaces the validated catalog. Accepted terms are snapshotted so an edit cannot silently change an accepted delivery.
- Each player can reserve one order. Both players share four demand slots per job; one slot replenishes per **6000 active server ticks** (five minutes). No clock advances without human players. Full demand cannot bank a hidden refill backlog. Cancel returns the reservation; replay cannot pay.
- Named, enchanted, damaged or other custom-NBT/capability-bearing goods are protected from automatic consumption. Fresh vanilla tools with only `Damage: 0` qualify; other custom tags remain protected. Only matching plain items in main inventory qualify; armor/offhand inventory is preserved.
- `data/underground_city_work_orders.dat` is an atomic, fsynced schema-1 journal. It records reservations, shared demand, active ticks, completion count, XP recovery target and any prepared absolute inventory/wallet/XP snapshot. It is recovery metadata, not another spendable wallet.
- A delivery validates everything on copies, fsyncs its prepared record, applies wallet/inventory/XP, saves playerdata, verifies saved inventory and Forge capabilities, fsyncs playerdata, then retires the pending record. Login replays pending targets before interaction. Storage failure stops contracts and disconnects the affected player for recovery. Restore missing Q Shop/configuration before reconnecting; preserve the entire world when backing up.
- Career data retains its existing schema-1 citizen store. The journal's earned-XP target repairs a lost citizen reward without incrementing XP twice.

Q Shop itself owns normal shop trade validation, inventory, wallet capabilities and saved limits. Original shop entries have finite global/personal **DAILY** world-time limits. Sleeping or administrative time changes can affect the provider's daily reset; work-order demand uses its separate active-play clock. NPC payments are bounded faucets, not a complete treasury/tax/production simulation. Bakery rewards are below the shop's bread purchase cost, preventing a buy → immediate delivery profit loop.

## One physical supplier

OP-only `/uc_clerk`, while looking at a nearby PC, places **Morgan - Supply Clerk** in the two clear blocks immediately east of it over a solid floor. It changes no terrain and refuses a second registered clerk. The clerk is a real persistent vanilla librarian, with a saved UUID/dimension/terminal binding. Right-click opens the same authoritative terminal only for registered citizens near the bound PC. Normal emerald trading is intercepted for this clerk; there is no new currency backend.

Provide a protected room, bed and lectern for vanilla routines. The clerk is not invulnerable: protect it from mobs. No automatic replacement/removal command, city population, police, faction allegiance, custom dialogue tree or bespoke AI has been implemented. Its fixed UUID binding rejects cloned unregistered villagers. Recover a lost clerk via backup/operator maintenance, not repeated spawning.

## Verification limits

Forge GameTests exercise real registry loading, accepted demand, disk-journal reload, stale receipts, missing/NBT-protected materials, actual wallet payment, vanilla recipe lookup, actual Q Shop buy/sell, entity-capability NBT restore, terminal distance and named-clerk identity persistence. Provider-only tests skip on a Core-only server and are separately required with `-PucEconomyTest=true` in the economy workflow.

These are server entities and actual APIs; they are not two connected human clients. Full process-kill/power-loss recovery, long-term balance, clerk pathfinding/death recovery and two-human hands-on play remain acceptance tasks. Two actual cloud clients now exercised PC/Q Shop mouse actions and retained 28 credits/125 XP/four seeds through dedicated restart. No city taxes, skill trees, advanced mod recipes, foreign suppliers, imports, burglary, escrow or raids are claimed complete.

The journal uses file fsync and atomic replacement; POSIX servers additionally fsync parent directories. Java cannot open Windows directories as FileChannels, so Windows uses file sync/atomic replacement without that extra metadata flush. Windows runtime and abrupt-power-loss behavior remain unverified; all recorded runs used cloud/hosted Linux.

## Regional exchange, recurring orders and rival-city gates (October 15, 2026)

**Status:** implemented in Core and exercised by dedicated-server GameTests, a two-real-client journey (including a dedicated restart) and native GUI captures. It is a *playable prototype loop*, not a finished economy: stock numbers are the provisional planning values, there is no NPC production behind the shelves, and TaCZ item rebinding is designed but untested (the Create mod is excluded, D-080).

### What a player can do

1. Place an Operations Terminal **inside a city circle** (centre chunk x16+8, radius from [cities.json](../../content/region-blueprints/cities.json): Ironhaven 600 blocks at 8,8; Valewood 410 at 2024,712; Blackwater 570 at -2008,1080). The server decides the city from the placed block's position; the Imports tab then shows that city's **exchange**: three finite specialist lines with server-owned prices, `BUY 1` and `BUY 4` buttons, shared stock and an affordability hint.
2. Buying consumes real Q Shop credits and delivers real items. Stock is **shared by both players**, drops by what was bought and replenishes only while at least one human is online, one batch per interval (for example machined parts: +4 every 45 active minutes, capped at 36). A full shelf banks no hidden backlog.
3. Using a terminal inside a rival city records a **visit** on the server (idempotent, durable). Recurring orders at career levels 3-5 require one rival-city visit for the first two orders per career and two for the third (D-023). The Contracts tab shows `LOCKED - Use a terminal inside N more rival cities first`.
4. Specialist goods are crafted with an ordinary crafting table (shapeless recipes in the datapack, each with at least one ungated vanilla ingredient) into **products** that the twelve recurring orders ask for. Every batch consumes regional goods, so the next order needs another purchase.

| Career | Order (level, visits) | Consumes (per delivery) |
| --- | --- | --- |
| Farmer | Greenhouse service (3, 1), vineyard climate module (4, 1), precision greenhouse unit (5, 2) | machined parts + rare seeds; agro catalyst + control circuits; catalyst + parts + circuits |
| Smith | Foundry parts (3, 1), depot repairs (4, 1), composite housings (5, 2) | refined alloy + circuits; machined parts + biopolymer; alloy + biopolymer + circuits |
| Gunsmith | Optic mounts (3, 1), licensed service (4, 1), equipment platform (5, 2) | machined parts + optical components; alloy + circuits; parts + biopolymer + encrypted design |
| Technician | Controller (3, 1), sensor repairs (4, 1), security array (5, 2) | circuits + parts; optics + catalyst; circuits + alloy + biopolymer |

Gunsmith recurring orders use **optics, mounts and platforms, not firearms**; TaCZ is still not installed.

### Where each rule lives

- **Source of truth:** [content/gameplay/regional-content.json](../../content/gameplay/regional-content.json). `python scripts/generate_regional_content.py` writes the 22 item models/textures/names, nine `regional_stock`, twelve recipes, twelve schema-2 `work_orders`, three `regions` and the item list; `--check` (run in CI) fails if committed output is stale. Icons are original, programmatically drawn 16x16 pixel art.
- **Economy guard:** the generator, a Python test and a GameTest over the *actually loaded* datapack all require each order's credits to be 1.3x-2.1x the shared-stock price of the consumed goods, so recurring jobs earn but cannot print money from free goods.
- **Authority:** `RegionalMarket` validates registration, a loaded placed terminal within eight blocks (same rule as every terminal action), the city containing that terminal, the catalog line for that city, quantity 1..max, a replay nonce, inventory room (planned on a copy), available stock and credits. The client only chooses a catalog ID, quantity and a random nonce.
- **Durability:** the purchase uses the same fsynced prepared-settlement journal as deliveries. The stock decrement, the nonce (last eight kept per player) and absolute inventory/wallet targets are written *before* the live inventory or Q Shop wallet change; login or the next action replays them exactly once. New ledger keys: `regional`, per player `visited`, `recent_purchases`, `purchases`, and `pending.kind`. Older journals load unchanged; **do not downgrade Core while a purchase is pending** (an older build would settle it as a delivery).
- **Rebinding:** `regional_stock/*.json` and work-order requirements name registry IDs. Replacing `underground_city:machined_parts` with a verified TaCZ or other non-Create ID is a datapack change, but no such rebinding has been tested and no third-party item IDs are asserted.

### Known limitations

- The gate is geographic evidence (a placed terminal inside a data-defined circle), **not** proof that a player travelled by road. Circles apply to any overworld: near (0,0) in an ordinary vanilla world also counts as Ironhaven. Terminals are craftable and portable, so a determined player can carry one into a city; that *is* the intended travel, but supplier NPCs/warehouses do not exist yet.
- Recipes are open to every player (no career-gated workstation). Employers only pay the right career at the right level.
- Supplier stock is not funded by an NPC production economy; it is a bounded faucet with sinks only through order consumption and price.
- Unloadable accepted orders (item removed) can be cancelled without minting demand; the terminal shows an "unavailable order" row.
- Not covered: shipment incidents, imports to the home city, black-market escrow, licences/permits, regional supplier NPCs, taxes.

### Evidence

Twenty-six dedicated-server GameTests pass locally and in the hosted economy workflow, including: real loaded-datapack margin and recipe checks, shared stock draw-down by two players, replay and out-of-range/foreign-line/unknown-line/low-funds/full-inventory/sold-out rejections, exact stock replenishment arithmetic, a prepared purchase replayed once after a journal reload, the full buy-craft-deliver farmer loop (net +58 credits, 300 XP), visit gating, malformed/legacy definitions, stale-receipt replay and interleaved two-player settlements. The two-client journey additionally clicks the real `BUY 1` button in both clients and asserts wallet 10, one part each, stock initial-2, receipts and visits before and after a dedicated restart.
