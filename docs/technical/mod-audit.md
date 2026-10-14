# Mod and loader audit

**Research snapshot: October 14, 2026. Candidates only. None has been installed or tested together in this repository.**

The proposed baseline is **Minecraft Java 1.20.1 + Forge 47.x + Java 17**, pending reproducible checks on a client and dedicated server. All releases need exact filename, checksum, dependency and rights review before selection.

| Candidate | Goal | Priority | Status |
| --- | --- | --- | --- |
| [The Lost Cities](https://www.curseforge.com/minecraft/mc-mods/the-lost-cities) | Modern city terrain and profiles | Critical | Candidate |
| [CustomNPCs-Unofficial](https://www.curseforge.com/minecraft/mc-mods/customnpcs-unofficial) | NPCs, dialogue, schedules and patrols | Critical | Forge 1.20.1 available; untested |
| [Lootr](https://www.curseforge.com/minecraft/mc-mods/lootr) | Per-player building loot | Critical | Candidate |
| [Timeless and Classics Zero (TaCZ)](https://www.curseforge.com/minecraft/mc-mods/timeless-and-classics-zero) | Firearms, parts, attachments | Critical | Candidate |
| [TaCZ Weapon Research & Blueprints](https://www.curseforge.com/minecraft/mc-mods/tacz-weapon-research-blueprints) | Weapon research and unlocks | High | Candidate |
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
