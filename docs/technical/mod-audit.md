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
| [Create](https://www.curseforge.com/minecraft/mc-mods/create) | Machinery and industry | Medium | Candidate |
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
| JEI / EMI | JEI 1.20.1-forge-15.56.0.205 (pinned) / EMI 1.1.24 | MIT / MIT | none for the pinned JEI | recipe viewers; client-optional | JEI is in the opt-in dev-pack set `qol`; it loads in a real client (its plugins and recipes register, the October 17 gallery run passed with it); EMI not evaluated. Not part of the default pack |
| Patchouli | 1.20.1-85 | CC BY-NC-SA 3.0 | none | data-driven guide book | Possible later for a Founders' Handbook; our own Guide tab already exists |
| Jade | 11.13.3 (pinned) | CC BY-NC-SA 4.0 | none | block tooltips (crop growth, machine state) | In the opt-in dev-pack set `qol`; it loads in a real client (plugins load, config syncs to the player); its tooltip overlay was not photographed. Not part of the default pack |
| Modern UI | 3.12.0.1 | LGPL-3.0-or-later | client only, 25 MB | replaces the whole game's UI rendering | **Not adopted**: heavy and global; our own toolkit (`UcUi`, D-068) is verified at three resolutions |
| owo-lib | - | MIT | - | Fabric / Quilt only (no Forge 1.20.1 build) | Not usable |

Notes on the evidence. The Modrinth search has **no** cannabis, hemp, marijuana or moonshine project for Forge 1.20.1 at all; the only cannabis mod with a 1.20.1 Forge file is on CurseForge. The CurseForge file CDN (`mediafilez.forgecdn.net`) is reachable from the cloud container, so a CurseForge-only jar can be fetched for inspection by file id and file name, but it cannot be pinned the way Modrinth releases are (no Modrinth maven coordinate), which is another reason not to depend on one. Tadacko's Drinks' mod id, items and blocks were read from its lang file and blockstates; Vinery's and Farm & Charm's from their blockstates and recipes.
