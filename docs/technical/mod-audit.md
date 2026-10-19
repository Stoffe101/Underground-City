# Mod and loader audit

**October 14, 2026:** Q Shop 1.8.4 plus original Core now have actual transaction/GameTest/client/restart evidence. Other listed mods remain candidates or isolated smoke-tested providers; no complete combined city modpack is certified.

The current verified starter baseline is **Minecraft Java 1.20.1 + Forge 47.4.10 + Java 17**. All releases need exact filename, checksum, dependency and rights review before selection.

| Candidate | Goal | Priority | Status |
| --- | --- | --- | --- |
| [The Lost Cities](https://www.curseforge.com/minecraft/mc-mods/the-lost-cities) | Modern city terrain and profiles | Critical | Testing: 1.20-7.5.5 (MIT; also Modrinth 8VDCUS3b) generates buildings at all three anchors; native highways do not connect fixed cities |
| [CustomNPCs-Unofficial](https://www.curseforge.com/minecraft/mc-mods/customnpcs-unofficial) | NPCs, dialogue, schedules and patrols | Critical | Forge 1.20.1 available; untested |
| [Lootr](https://www.curseforge.com/minecraft/mc-mods/lootr) | Per-player building loot | Critical | Candidate |
| [Timeless and Classics Zero (TaCZ)](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero) | Firearms, parts, attachments | Critical | Candidate |
| [TaCZ Weapon Research & Blueprints](https://www.curseforge.com/minecraft/mc-mods/tacz-weapon-research-blueprints) | Weapon research and unlocks | High | Not selected: the CurseForge-only file could not be located or verified from the cloud container; TaCZ Blueprints 0.1.3 (Modrinth) was selected instead |
| [Pufferfish's Skills](https://www.curseforge.com/minecraft/mc-mods/puffish-skills) | Profession/general skill UI | High | 1.20.1 Forge build listed; untested |
| [Kuro's Economy](https://www.curseforge.com/minecraft/mc-mods/kuros-economy) | Banking, jobs, tax and contracts | Very high | Existing Forge 1.20.1 release; compare with Lightman's as *alternatives* |
| [MCA Reborn](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn) | Townspeople, relationships, settlement influence | Very high | Forge 1.20.1 exists; vanilla-village model may not populate Lost Cities |
| [Villager Comfort Updated](https://www.curseforge.com/minecraft/mc-mods/villager-comfort-updated) | Villager satisfaction/housing needs | High | Forge 1.20.1 exists, requires separate MCA compatibility test |
| [Immersive Vehicles](https://www.curseforge.com/minecraft/mc-mods/minecraft-transport-simulator) | Transport infrastructure, cargo, vehicles | High | Forge 1.20.1 exists but requires vehicle content pack |
| [Lightman's Currency](https://www.curseforge.com/minecraft/mc-mods/lightmans-currency) | Currency, trading and shops | High | Candidate |
| [KubeJS](https://www.curseforge.com/minecraft/mc-mods/kubejs) | Custom recipes and scripts | High | Candidate |
| [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-forge) | Objectives and quest display | High | Candidate |
| ~~Create~~ | Machinery and industry | - | **Rejected by the owner (October 18, 2026, D-080).** Not a candidate, not a dependency, not a rebinding target; any mod that requires it is rejected too |
| [Farmer's Delight](https://www.curseforge.com/minecraft/mc-mods/farmers-delight) | Food/farming | Medium | Candidate |
| [Let's Do Vinery](https://www.curseforge.com/minecraft/mc-mods/lets-do-vinery) | Grapes and vineyards | Medium | Candidate |
| [RoleCraft](https://www.curseforge.com/minecraft/mc-mods/role-craft) | Built-in careers, first-login role UI and police tools | Very high | 1.20.1 Forge, limited default role selection; needs extension test |
| [Pueblo Vivo](https://www.curseforge.com/minecraft/mc-mods/pueblo-vivo) | Village citizenship, contracts, building projects | High | Beta; may not support imported city regions |
| [C'Npc](https://www.curseforge.com/minecraft/mc-mods/cnpc) | Alternative quest NPC editor and factions | Research | New 1.20.1 Forge mod, untested |
| [MineColonies](https://www.curseforge.com/minecraft/mc-mods/minecolonies) | NPC settlement alternative | Research | Evaluate separately |
| [WorldEdit](https://www.curseforge.com/minecraft/mc-mods/worldedit) | World-editing tool | Medium | Candidate; may remain developer-only |

## Confirmed published metadata to revalidate at install time

- TaCZ Weapon Research & Blueprints currently documents Forge 47.x, Java 17, TaCZ **1.1.8+ in the 1.1 series**, Fzzy Config **0.7.6+ in the 0.7 series**, and Kotlin for Forge.
- Pufferfish's Skills lists a Forge 1.20.1 build; we have **not** validated its integration hooks.
- CustomNPCs-Unofficial lists Forge 1.20.1 builds; scripts, serialization, NPC behavior and compatibility remain untested.
- The Lost Cities warns that changing selected world profile after terrain exists can produce seams. Choose, save and reproduce a profile before creating a persistent world.
- Two TaCZ blueprint/research addons exist. Select **one** after testing, never assume they interoperate.

## Candidate review gate

For each mod record exact artifact and source, dependency tree, game/loader/client/server version, license, allowed use, checksum, known conflicts, dedicated-server boot, two-client connect/reconnect, save-reload behavior, CPU/tick/performance impact and rollback strategy. Mark the result **approved_for_local_use** only with test evidence; this does not grant redistribution rights.

The machine-readable [candidate register](mod-candidates.csv) is for tracking, not a download manifest. Third-party binaries belong in local launchers, not Git.

## Design caution: role ownership

Do not ship both RoleCraft-owned profession XP and RPG Core-owned profession XP without an explicit adapter. The first-login interface should be supplied by one provider. Test whether RoleCraft can support custom hacker and gunsmith roles before committing to it, and test Pufferfish's Skills as a complementary UI only if progression data can be synchronized safely. See [reuse-first policy](reuse-first.md).

## Selected starter provider

[Q Shop 1.8.4](economy-integration.md) by QLNPLUS owns live credits and civilian trades. Its official Java API/release/checksum were reviewed and exercised with Core, including two actual cloud clients and dedicated restart. ARR source/assets/binaries are not redistributed. This does not certify Q Shop together with Lost Cities, TaCZ, an NPC framework and every other candidate. Lost Cities 7.5.5, Lootchest Refill and Faction Friction still have isolated Core smoke evidence, not actual city/refill/war playthroughs.

## Lost Cities 1.20-7.5.5 findings (October 15, 2026)

- **License:** MIT (repository LICENSE on branch 1.20; project page). Distribution channels checked: CurseForge file 8862717 (pinned in CI) and Modrinth artifact Ec9sXB06 (same version number; sha1 7119a9e3b1b83037e618eb98e0331684a19e2b4a). The two channels' bytes were not compared.
- **API:** the documented IMC route (`getLostCities`) provides `ILostCities.getLostInfo(Level)` and per-chunk `ILostChunkInfo` (`isCity`, `getBuildingType`, `getMaxHighwayLevel`). Core reads it by reflection in `LostCitiesLink` for diagnostics only. A chunk query costs about 16 ms, so full-region scans must be sampled.
- **Highways:** `INTERCITY_NETWORK_V1` hubs come from `ApproximateCityPotential`, which counts random city centres or the noise rarity map and **never predefined cities**. With `cityChance` 0 the potential is zero, hubs are absent and no highway exists. Measured: no highway among 1,222 sampled chunks on the first seed and 3,097 on seed 579 (city rings, corridor lines, a 12-chunk lattice), with all city chunks inside the declared radii. Core therefore builds its own roads (`corridor_roads` worldgen feature, vanilla blocks only) instead of depending on the planner.
- **Heightmaps:** chunks generated by Lost Cities can read -64 for `WORLD_SURFACE` when queried later; scan blocks for height.

## October 15-16 sprint selections (exact versions, evidence, limits)

Hash-pinned Modrinth releases used as **runtime-only development dependencies**; none is bundled, committed or redistributed. Exact file names, sizes, SHA-1 and SHA-512 are in `content/modpacks/dev-pack.json`; [dev pack](dev-pack.md) lists the licences and what each CI job proves.

| Mod | Version | Licence | Evidence | Not proven |
| --- | --- | --- | --- | --- |
| TaCZ | 1.1.8-hotfix2 | GPL-3.0-only code; default gun pack CC BY-NC-ND 4.0 | Core GameTests with it pass (hosted); production-style boot | firing a gun in a client; any multiplayer behaviour |
| TaCZ Blueprints | 0.1.3 | All rights reserved | Core GameTests pass; boot; Core's blueprint rewards name its items | starter-gun configuration was not written; the accepted `enabledGuns` spelling was not exercised |
| Modern Life | 1.75 | All rights reserved | production-style boot; the Motorboat keeps 27 slots of named, damaged and NBT-tagged items through a save and load | cannot boot under ForgeGradle (reads its config in its constructor); no one drove or sailed it |
| Immersive Vehicles | 24.0.0 | All rights reserved | boots with Core, Q Shop and the others | dev-environment runs do not register content-pack items (23 recipe parse errors "Unknown item mts:..."); a scripted-player drive passed (October 16); a vehicle's crate parts are not a `Container` and are read by reflection for checkpoint searches (D-077, GameTested with a real Quad and crate on October 17); no human has driven it |
| Official Content Pack | V29 | All rights reserved | 470 `mts:mtsofficialpack.*` items registered on the production-style server | no vehicle placed |
| Official Automobile Pack | V3 | All rights reserved | 137 `mts:oamp.*` items registered | no vehicle placed |

Pufferfish's Skills was **not** selected: Core owns skill trees (D-050). This does not retire it as a possible display layer.

## October 17 candidate review: wine, crops, spirits, cannabis and interface libraries

Requested by the owner: prefer finished, high-quality mods and art over making our own, and consider a Farmer / Vintner split (D-067). Everything here is a **candidate**: metadata read from the Modrinth API and the jar files themselves in the cloud container, textures rendered to contact sheets and looked at, then the crops, machines and bottles photographed in a real client and a dedicated server booted with the pack (the sprint report, [sprint-2026-10-17.md](../production/sprint-2026-10-17.md), holds the frames and logs). The default `runClient` pack is unchanged; the candidates are the opt-in sets `vintner` (Vinery, Kaleidoscope Tavern) and `farming` (Farm & Charm), e.g. `-PucPack=world,firearms,vehicles,boats,vintner,farming`.

| Candidate | Exact release (Forge 1.20.1) | Licence as published | Needs | What the jar shows | Verdict so far |
| --- | --- | --- | --- | --- | --- |
| [Let's Do] Vinery | 1.4.41 (2025-09-05), 2.0 M downloads on this build | "Custom" (project page links the authors' own text; **not retrievable from the cloud container**, so no redistribution right is assumed) | Architectury >= 9.0.8 | 297 textures of consistent, detailed pixel art: grapes, wine bottles, barrels, presses, racks, aprons and hats; 26 wine recipes (several are fantasy effect wines), `vinery:wine_fermentation` recipes | Strong candidate for the Vintner's legal path (grapes, press, cellar, bottles) |
| [Let's Do] Farm & Charm | 1.0.14 (2025-10-09) | "Custom" (same authors' text) | Architectury | 324 textures; crops with real growth stages (barley, corn, oats, tomato, strawberry, lettuce, onion), stove, cooking pot, roaster, silos, scarecrow, sprinkler, food items; every crop has a `_stage` ladder | Strong candidate for the Farmer's legal crops and kitchen |
| Kaleidoscope Tavern | 1.2.0 (2026-07-01), 1.7 M downloads | BSD-3-Clause code and CC BY-NC-SA 4.0 assets (its `mods.toml`) | none (Curios optional) | barrel brewing: water and potato, wheat or sugar cane become vodka, whiskey and rum in two minutes; pressing tub, tap, shaker, grape trellises; placeable bottles | **Candidate for the Vintner's spirits.** A dedicated server boots with it (`Done (45.9s)`, no mod errors) and its barrel, tub, tap, shaker and bottles were photographed in the real client. It is a crude home brew, not a still |
| Tadacko's Drinks | 1.0.0 to 1.2.0 (1.2.0 published 2026-10-15), ~130 downloads | MIT | GeckoLib >= 4.7 | the real processes (crusher, press, fermenting barrel, **pot still, column still, condenser**; wash, low wine, high-proof spirit; vodka, whisky, gin, rum, tequila, brandy) with readable copper stills in the real client | **Not adopted: a dedicated server crashes at mod construction.** Its auto-registered subscribers (`CrosshairOverlay` and the hangover renderers) reference client-only classes without a dist filter (`Failed to complete lifecycle event CONSTRUCT, 1 errors found`), in all three 1.x builds. Revisit if the author fixes it; the integrated (LAN) host would work |
| Easy NPC | 7.16.0 (core, config UI and bundle, 2026-10-17), 1.2 M downloads | MIT | Easy NPC Core, Config UI | custom NPC entities with skins, dialogs, trading and actions, an in-game editor, server-side | Not adopted yet: the leading candidate for a later spike on skinned story characters; our villager-based citizens keep their routines meanwhile |
| Handcrafted, Macaw's Furniture / Roofs / Doors, Supplementaries | current Forge 1.20.1 builds | Terrarium licence / all rights reserved / Supplementaries Team licence | AddonsLib for Macaw's | furniture, roofs and decoration blocks that would make Founders Row interiors richer | Not evaluated beyond metadata; they would save building time for later districts, each needs its own boot and licence check |
| Smokeleaf Industries | 0.7-1.20.1 (CurseForge only; the uploader calls the 1.20.1 build a backport with likely issues) | MIT (template text; art licence not stated) | **Forge >= 47.4.23** (our pin is 47.4.10, D-007) | 25 tinted strains of one flat 16x16 sprite set at 12 stages, grow pots, lights, machines, joints, dealer villagers | **Not adopted**: not realistic (no palmate leaf), would own a second trading and effects system, needs a Forge bump |
| Weeder Continued / original Weeder | NeoForge 1.21.x only / last Forge build 1.19.4 | All rights reserved | - | no 1.20.1 build exists | Not usable |
| HempFarmer, Liert's CannaCraft, a "Pot Leaves" cannabis mod, Mystical Herbs | unverified | unverified | - | the pages could not be read from the cloud container (hosts refused) | Unverified; not evaluated |
| Tobacconist | 4.1.0 (2026-10-12) | MIT | optional only | realistic tobacco farming, curing and blending; 6.4 MB | Not cannabis; kept as a possible later "licensed tobacco" idea only |
| JEI / EMI | JEI 1.20.1-forge-15.56.0.205 (pinned) / EMI 1.1.24 | MIT / MIT | none for the pinned JEI | recipe viewers; client-optional | JEI is in the opt-in dev-pack set `qol`; it loads in a real client (its plugins and recipes register, the October 17 gallery run passed with it) and, in the October 18 `qol` scenario, its item list drew beside the creative inventory and a search for "sable" found exactly our two items (the Sable Leaf cutting and the bale); EMI not evaluated. Not part of the default pack |
| Patchouli | 1.20.1-85 | CC BY-NC-SA 3.0 | none | data-driven guide book | Possible later for a Founders' Handbook; our own Guide tab already exists |
| Jade | 11.13.3 (pinned) | CC BY-NC-SA 4.0 | none | block tooltips (crop growth, machine state) | In the opt-in dev-pack set `qol`; it loads in a real client (plugins load, config syncs to the player); in the October 18 `qol` scenario its tooltip was photographed over our blocks and names them correctly with their mod ("Operations Terminal", "Public Ledger", "Underground City RPG Core"); over the Sable Leaf in the greenhouse it shows "Sable Leaf Plant", "Growth: 67%" (age 2 of 3) and the mod name, taken from our crop block with no integration code of ours. Not part of the default pack |
| Modern UI | 3.12.0.1 | LGPL-3.0-or-later | client only, 25 MB | replaces the whole game's UI rendering | **Not adopted**: heavy and global; our own toolkit (`UcUi`, D-068) is verified at three resolutions |
| owo-lib | - | MIT | - | Fabric / Quilt only (no Forge 1.20.1 build) | Not usable |

Notes on the evidence. The Modrinth search has **no** cannabis, hemp, marijuana or moonshine project for Forge 1.20.1 at all; the only cannabis mod with a 1.20.1 Forge file is on CurseForge. The CurseForge file CDN (`mediafilez.forgecdn.net`) is reachable from the cloud container, so a CurseForge-only jar can be fetched for inspection by file id and file name, but it cannot be pinned the way Modrinth releases are (no Modrinth maven coordinate), which is another reason not to depend on one. Tadacko's Drinks' mod id, items and blocks were read from its lang file and blockstates; Vinery's and Farm & Charm's from their blockstates and recipes.

## October 18 candidate review: Crews, parties, shared money, and the survival set

Requested by the owner (Crew system; backpack, thirst, food and basic furnishing providers; **no Create**, D-080). Read from the exact Forge 1.20.1 jars in the cloud container (downloaded to a scratch folder, never committed), their `mods.toml`, class lists and public APIs. Everything is a **candidate** until its own row says what was run.

### Groups, parties, claims and shared money (the Crew authority is Core's, D-081)

| Candidate | Exact release | Licence | What the jar shows | Verdict |
| --- | --- | --- | --- | --- |
| Q Shop | 1.8.4 | see the register above (pinned release, SHA-256 verified) | a per-player wallet capability and `QShopAddonApi.currency()` (`getBalance`, `deposit`, `withdraw`, `set`, offline by UUID); **no** account, team, group or shared balance anywhere in `api`, `wallet`, `currency`, `shop`, `trade`, `net`, `ftb`, `kubejs` | **Stays the only currency** (D-037, D-039). A shared balance is a Core ledger of credits that have left a wallet ([crew-economy.md](crew-economy.md)) |
| Open Parties and Claims | 0.33.0 (2026-10-17) | LGPL-3.0 | `OpenPACServerAPI.getPartyManager()` (create, invite, members, four fixed ranks) and chunk claims with protection hooks; Create is an *optional* compat hook only | **Not the membership authority** (fixed ranks, no finance mode or home ownership, parties can change outside Core's flow and expire). The bridge route is documented: Core's Crew registers as OPAC's party system (`IPlayerPartySystemRegisterAPI`) so OPAC reads Core, for shared chunk claims later. **Not built** |
| FTB Teams / FTB Chunks | not on Modrinth | unverified | the standard team provider of the FTB mods, which Q Shop's optional FTB Quests integration would use | **Unverified**: the jars could not be fetched and read from the container. Not a candidate until they are |
| Lightman's Currency | 2.3.0.6 | Apache-2.0 | a complete second economy with team bank accounts (`TeamAPI`, `BankAPI`) | **Rejected as an installation** (a second authoritative currency, D-037); used as a **design reference** for named bank access levels and a transfer log |

### The survival set (pinned, opt-in set `survival`, D-085)

| Role | Selected | Why | Rejected or deferred, and why |
| --- | --- | --- | --- |
| Backpack | **Sophisticated Backpacks** 1.20.1-3.26.12.2206 + **Sophisticated Core** 1.20.1-1.5.12.2412 (both all rights reserved; runtime-only, hash-pinned downloads) | **Revised October 19, 2026 (D-092): replaces Traveler's Backpack.** A real configuration surface (slot counts and upgrade slots per tier, a switch for every item and its recipe, a limit per upgrade type, a nerf for carrying many packs), a placed pack that is a block with an item handler (the home guard sees it), an active 2026 release line. Two server paths needed a gate (see [backpack-economy.md](backpack-economy.md)) | Traveler's Backpack 1.20.1-9.1.57 (LGPL-3.0-only): **retired**; it crashed one first start in eight in the cloud and had no per-item switch. It stays reachable as the `legacy_travelers` set so that an old world can be opened once ([backpack-migration.md](backpack-migration.md)); it is never loaded with Sophisticated Backpacks |
| Thirst | **Thirst Was Taken 2** 1.9.0 (GPL-3.0-only, published 2026-10-15) | thirst, water purity, canteens and flasks (waterskin, copper canteen, iron, diamond and netherite flasks), a sand filter and a copper distiller; its Create hook is **not used** | Thirst Was Taken 1.4.0: MIT but stale (mid-2025), five items, its canteen add-on is all rights reserved. Tough As Nails: beta, all rights reserved, needs Glitchcore, bundles a harsh temperature model |
| Kitchen | **Farmer's Delight** 1.20.1-1.3.4 (MIT) | food variety, cooking pot, cutting board; no mandatory dependency | [Let's Do] Farm & Charm overlaps it (stove, pot, roaster): the two are **not combined by default** (`farming` and `survival` are separate opt-in sets) until a production-chain check says otherwise |
| Rest | **Comforts** 6.4.0+1.20.1 (LGPL-3.0-or-later) | sleeping bags and hammocks; the project says they do not set a spawn point, which `SurvivalGameTests` checks with a vanilla bed as the control (the result is in the sprint report) | Sophisticated Backpacks has no sleeping bag; the retired provider's built-in one is no longer part of the pack |
| Furnishing | **none selected** | the October 17 candidates (Handcrafted, Macaw's, Supplementaries) are all-rights-reserved or custom-licensed; the Row Flats are furnished with vanilla blocks and Core's terminals | revisit when the district art pass wants a style |

What each row has been through (boot, GameTests, real client) is in the sprint report. The reobfuscated Core jar plus the four pinned jars boot a real Forge server (`scripts/run_prod_boot_check.py --sets survival`: `RESULT: pass`, run locally; the hosted `production-boot` job repeats it with the set alone). Nothing in this set is certified with Lost Cities, Q Shop and the vehicle mods all loaded together beyond the boot records; the two-player and long-session behaviour of the set is untested.

**Follow-up evidence (October 18, PR #23 playtest-readiness pass; D-091).**

* **Mechanics, driven through each mod's own code** (`SurvivalMechanicsGameTests`, reflection through `ModReflect` because the APIs are optional at compile time): Thirst Was Taken 2 (`ThirstManager`, `ThirstApi`) drains with effort and is replenished by drinking; clean, fresh and boiled water are safe and dirty water makes the player ill; a canteen holds servings and each drink takes one; a furnace boils dirty water; a flask filled from a pond holds that pond's water and its quality. The backpack (originally Traveler's Backpack's `CapabilityUtils`, since October 19 Sophisticated Backpacks through `BackpackSupport`): worn, holds items, keeps them through a save and load. Farmer's Delight: a cooking pot turns three beetroots and a bowl into a meal that feeds.
* **Goods on sale** (`SurvivalSupplyGameTests`, `tests/test_survival_supply.py`): 21 finite stock lines in the regional exchange; a line is left out while its mod is not installed; the first kit can be bought with one career's credits and used; a wayside desk sells only its own lines from the shared stock.
* **All mods together, on a generated Lost Cities world with two real clients and an abrupt server kill:** passed (see [testing.md](testing.md#the-combined-journey)). `run_prod_boot_check.py --sets world firearms vehicles boats vintner farming survival` (the **reobfuscated** Core jar and every pinned jar on a real server) passed locally with `boat_cargo = ok`; the hosted `production-boot` job now repeats it.
* **Farm & Charm (set `farming`) with Farmer's Delight (set `survival`):** loaded together in the combined journey without an error; the earlier design advice not to combine them by default stands (two kitchens), but no technical conflict was found.
* **Findings about the mods themselves (not yet reported upstream, and none will be posted without the owner's approval):** Traveler's Backpack 9.1.57 could crash a server's first start while loading its newly created `travelersbackpack-common.toml` (an `ArrayIndexOutOfBoundsException` in `TravelersBackpackConfig$Server.loadItemsFromConfig`; one start in eight, the next start succeeded); the mod is retired (D-092), see [first-start-stability.md](first-start-stability.md) for the measured replacement. The Lost Cities 1.20-7.5.5 world-tick hook can hold the server thread for seconds to more than a minute after bulk chunk generation in a city; **root-caused and reproduced** in [lost-cities-stalls.md](lost-cities-stalls.md) (D-096). **Not tested at all:** a medical or bandage item (no mod is selected, see below), furnishing, Thirst Was Taken 2's filter and distiller, and the interaction of any of these with the vehicle mods in play.

### Medical supplies: researched, nothing adopted (October 19, 2026)

The owner asked, as a lower priority, for at most one simple medical-supply integration with no unrestricted instant healing and no injury system. Candidates were read from their Modrinth listings only (no jar was read and nothing was run): **Bandagez** is an injury-and-bandage system, which is the complicated injury model the owner excluded (rejected); the small medkit mods found are built with a mod generator whose healing values are fixed in the item and not configurable (rejected as unrestricted and unvetted). The base game already offers restricted healing through food (saturation and regeneration), golden apples and potions. **Nothing was selected;** a medical item is still a gap (backlog), and no document claims one.
