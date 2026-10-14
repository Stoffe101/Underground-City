# Burglary, property, and multi-path progression

**Accepted design direction requested by player:** peaceful professions and optional burglary are equally interesting paths through the same living economy. **Not implemented.**

## Property archetypes

| Property | Legal interaction | Illegal opportunity | World response |
| --- | --- | --- | --- |
| Civilian home / apartment | Visit, rent/buy, meet NPCs, deliver contracts | Pick door or enter through unlocked route, search drawers, steal shared goods | Resident witnesses, reports, stolen-goods tag, heat |
| Wealthy residence | Deliver premium craft goods, work as contractor | Higher-skill lock/minigame and valuables, expensive jewelry/electronics | Better alarm/security response, bigger financial risk |
| Workshop / industrial premises | Employment, repairs, legitimate resource order | Take blueprints, parts, high-value materials | Limited shared stock loss, shop owner report |
| Office / bank archive | Information/tech support missions | Fictional digital puzzle, restricted documents | Surveillance/warrant, faction reputation |
| Criminal stash | Black-market contract, faction trade | Competing gang's supplies or evidence | Hostile faction retaliation, criminal influence |

## Example mission: apartment robbery

1. Research a wealthy block and discover its ownership, entry points and residents' schedule.
2. Obtain ordinary equipment. **Locks Reforged** can supply the actual door/chest locking and lockpicking interaction.
3. Enter through an unlocked door, authorized invitation, or risky forced/picked entry; the building's server-side property state records access mode.
4. Search logical containers: closet, desk, safe, workbench. **Shared household stock**, not per-player Lootr copies.
5. Convert items to dirty/unlicensed inventory where relevant; choose to keep, return, sell to a fence, or complete a story investigation.
6. NPCs report witnessed entry/theft; police evidence and city heat respond based on observed events, not psychic detection.
7. Respect **gradual recovery**: the robbed apartment doesn't reset instantly. At a common household's 40-minute prototype interval it could have 1 item after 40 active minutes, 3 after 120, and up to a bounded maximum later. See [restock policy](gradual-property-restock.md). Existing NPC economic sources must fund those future items.

## Exploit prevention and cooperative fairness

- **No unrestricted harvesting of player homes.** Player-owned buildings and essential storyline homes default protected; access/raid mode is opt-in for private pair.
- One household ledger and one robbery reward transaction, regardless of how many real players open the same chest.
- Breaking ordinary glass or walls must not bypass lock/ownership events silently; use region protection and a server-side burglary predicate that watches entry, blocks, and inventories.
- Generated loot is assigned by physical building classification and income level; not every room has a jackpot.
- Success does not magically spawn cash forever. Fence buyers have budget, risk and reputation limits.
- Violence is optional. Quiet robbery, social disguise, legit contract access and hacking are different routes.
- You can be caught; penalties are bounded, reversible and don't destroy the entire co-op story.
- Off-screen NPC schedules can be abstracted, but breaking into a home while a resident should be there must have meaningful consequences.

## Loot policy: two fundamentally different systems

**Exploration** (abandoned buildings, wilderness caches, dangerous ruins) can use **Lootr per-player rewards**. Both players should get a chance to find useful items when exploring together.

**Theft** (inhabited apartments, shops, offices, private safes) uses **shared physical inventories** and server-authoritative property stock. It cannot use normal per-player Lootr behavior without duplicating stolen NPC assets and ruining the economy.

## Reuse-first responsibilities

| Mechanic | Candidate existing tool | Integration still required |
| --- | --- | --- |
| City architecture | Lost Cities fixed cities + custom city styles | Functional building type, civic geography, long roads |
| Lock and minigame | [Locks Reforged](https://www.curseforge.com/minecraft/mc-mods/locks-reforged) | Ownership policy, trespass event, alarms |
| Time-based apartment restocking | [[TACZ] Lootchest Refill](https://www.curseforge.com/minecraft/mc-mods/lootchest-refill) | Test partially filled households, per-property shared stock, game clock and funds before selecting |
| Multiplayer exploration loot | [Lootr](https://www.curseforge.com/minecraft/mc-mods/lootr) | Verify Lost Cities generation and injected tables |
| Chest/item loot tables | Vanilla datapacks or [LootrConf](https://github.com/103841-ops/lootr-config) | Assign tables to appropriate building type/context |
| NPCs and witnesses | Existing NPC framework / MCA Reborn | Resident presence, report/witness events |
| Shops, tax, law/heat | Existing economy mods + narrow RPG Core adapter | Unified ledger, evidence/fence services, city ownership |

Actual mod compatibility and features must be checked on the **exact 1.20.1 Forge versions** before treating these connections as implemented.

## Acceptance goals

A player can be a farmer and progress legally. A second player can choose criminal activity and progress through different equipment, contacts and risk. Either can help the other's career by trading supplies and contacts. A successful robbery is visible to the game systems, appropriately scarce, shared when appropriate, and creates real but non-ruinous social/legal consequences.

## Criminal progression in a physical world

The [terminal / underworld design](terminal-and-underworld.md) links stolen gun parts and electronics to real potential buyers and raid contracts without assuming every apartment contains a weapon. Rarer parts belong chiefly to locked safes, industrial facilities and gang caches. The same terminal can arrange high-cost foreign deliveries, black-market listings, real dropbox exchanges and later raids against gang hideouts and police stations. The terminal and all world interactions remain source-level designs and a small transaction simulation, not functional Minecraft systems.
