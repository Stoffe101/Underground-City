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
- `TerminalNetwork` protocol 3 declares packet directions. Requests carry a block position, bounded job ID/action and the **server-generated accepted-order UUID**. The server checks registration, living/non-spectator player, loaded block, matching terminal and ≤8-block distance. Requests are rate limited; stale completion/cancel UUIDs reject.
- Data definitions are vanilla datapack resources `data/underground_city/work_orders/*.json`. Schema 1 has title, career, level, real item registry ID, count, integer credits/XP, capacity and refill ticks. `/reload` atomically replaces the validated catalog. Accepted terms are snapshotted so an edit cannot silently change an accepted delivery.
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
