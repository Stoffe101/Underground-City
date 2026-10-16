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
| Immersive Vehicles | 24.0.0 | All rights reserved | boots with Core, Q Shop and the others | dev-environment runs do not register content-pack items (23 recipe parse errors "Unknown item mts:..."); driving untested |
| Official Content Pack | V29 | All rights reserved | 470 `mts:mtsofficialpack.*` items registered on the production-style server | no vehicle placed |
| Official Automobile Pack | V3 | All rights reserved | 137 `mts:oamp.*` items registered | no vehicle placed |

Pufferfish's Skills was **not** selected: Core owns skill trees (D-050). This does not retire it as a possible display layer.
