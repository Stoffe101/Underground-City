# Reuse-first implementation policy

**Accepted technical direction (2026-10-14):** Prefer an existing mod or modpack that already provides the required behavior. Try configuration, datapacks, scripting and integration before writing a replacement. The pack is for two people and not intended for public release, but license, access and compatibility obligations still apply.

## Decision ladder

1. **Use an existing mod unchanged**, if server/client compatibility, rights, performance, persistence and UX pass real tests.
2. **Use supported configuration, recipes, datapacks and scripting** to adapt it to our world.
3. **Write a narrow bridge** (e.g. city reputation impacts shop stock) instead of duplicating its economy or roles.
4. **Fork when license and permission explicitly permit,** keeping attribution and maintenance obligations.
5. **Write a new implementation** only when there is no suitable stable alternative, or integration becomes more fragile than the new code.

Every proposed custom module gets an explicit build-versus-buy justification in an issue. **Do not assume RoleCraft or any other mod exposes a public API simply because it has admin commands.**

## Existing packs worth inspecting locally

| Pack | MC / Loader | Existing strengths | Important drawbacks | Priority |
| --- | --- | --- | --- | --- |
| [GCA](https://www.curseforge.com/minecraft/modpacks/gca) | 1.20.1 Forge | Modern city RP, economy, firearms, crime | Small project; contents and maintenance require investigation; ARR license | High |
| [DeceasedCraft](https://www.curseforge.com/minecraft/modpacks/deceasedcraft) | 1.20.1 Forge | City generation, equipment, tech, custom quests, loot, vehicles, skills | Zombie infection, wasteland, hordes and survival rules are fundamental; major removal work; ARR | High as reference and **mod source audit**, low as direct base |
| [Crusthaven](https://www.curseforge.com/minecraft/modpacks/crusthaven) | 1.20.1 Forge | Urban RP, economy, voice chat | Smaller/experimental pack; verify exact contents and quality | Medium |
| [ModernCraft Real Life RP](https://www.curseforge.com/minecraft/modpacks/moderncraft-real-life-rp) | 1.20.1 Forge | Vehicles, tech, modern-life and crime concept | Broad scope including space and dimensions, likely needs stripping; limited installation history | Medium |

These are **candidate packs for locally installed A/B experiments**, not included in Git. A CurseForge pack may contain mod references and its author's original configs; availability for download does not grant general reuse/republication rights.

## Existing individual mod candidates

| Candidate | Can potentially replace | Limitation / test |
| --- | --- | --- |
| [RoleCraft](https://www.curseforge.com/minecraft/mc-mods/role-craft) | Career choice, early career XP and role perks, player management, crafting restrictions, police cuff/search tools | Published roles are predefined; test whether custom Hacker/Gunsmith careers can be configured. ARR; cannot assume an integration API |
| [Pufferfish's Skills](https://www.curseforge.com/minecraft/mc-mods/puffish-skills) | Branching profession/general skill UI and perks | Verify compatibility with career provider; choose one source of truth for XP |
| [Kuro's Economy](https://www.curseforge.com/minecraft/mc-mods/kuros-economy) | Money, banking, shops, jobs, tax, recurring payments and contracts | Strong first alternative to a bespoke civic economy; inspect actual 1.20.1 API and NPC buyer support. Do **not** install concurrently with another major economy unless reconciled |
| [MCA Reborn](https://www.curseforge.com/minecraft/mc-mods/minecraft-comes-alive-reborn) | Interactive villagers, relationships, personalities, village ranks | Strong ambient townspeople candidate; confirm interactions in custom urban buildings |
| [Villager Comfort Updated](https://www.curseforge.com/minecraft/mc-mods/villager-comfort-updated) | Social/environmental needs for village residents | Can make population happiness meaningful if compatible with MCA |
| [Immersive Vehicles](https://www.curseforge.com/minecraft/mc-mods/minecraft-transport-simulator) | Vehicle, fuel, transport and cargo features | Requires compatible additional vehicle content pack; review separately |
| [Lightman's Currency](https://www.curseforge.com/minecraft/mc-mods/lightmans-currency) | Currency, bank/shop functions | Validate transaction hooks for city taxes/ledger before coding own bank |
| [Pueblo Vivo](https://www.curseforge.com/minecraft/mc-mods/pueblo-vivo) | Village citizenship, stock/warehouse, projects, relationships and settlements | Primarily vanilla village system; may not work with custom generated city regions. Investigate separately |
| [C'Npc](https://www.curseforge.com/minecraft/mc-mods/cnpc) | NPC dialogue, quests, factions, patrol paths and trading | Very new/low-usage; compare stability and compatibility with CustomNPCs before selection |
| [CustomNPCs-Unofficial](https://www.curseforge.com/minecraft/mc-mods/customnpcs-unofficial) | Quest NPC creation, shops, patrol routes and scripting | Performance, import and compatibility test required |
| [Lootr](https://www.curseforge.com/minecraft/mc-mods/lootr) | Per-player loot synchronization | Imported-world chest assignment needs actual test |
| [TaCZ + research addon](https://www.curseforge.com/minecraft/mc-mods/tacz-weapon-research-blueprints) | Weapons, parts, research, blueprints | Exact TaCZ, Kotlin and Fzzy dependencies; competing blueprint addons |
| [The Lost Cities](https://www.curseforge.com/minecraft/mc-mods/the-lost-cities) | Procedurally assembled urban terrain | Inhabited cities and special buildings need extra work |

**Conflicts to resolve deliberately:** Kuro's Economy versus Lightman's Currency as the main currency authority; MCA Reborn versus any other villager replacement; police functionality in RoleCraft versus other law mods; vehicle content pack compatibility;  RoleCraft vs custom registration/career XP; RoleCraft XP vs Pufferfish skills; NPC framework vs an autonomous settlement mod; multiple TaCZ research implementations; currency mod vs custom financial ledger.

## Immediate impact on RPG Core scaffold

`rpg-core/` is a **standalone experimental fallback** to prove Java build, simple persistence and multiplayer. **Do not add career trees or an economy implementation** until RoleCraft/Pufferfish/Lightman's Currency have been separately tested. If an existing mod meets the requirements, RPG Core should pivot to a thin integration layer for city/story-specific behavior. The prototype's `/uc register` command is not intended to compete with RoleCraft's first-login UI.

## Private-play installation

Use official downloads/launchers locally and preserve creator notices. Git contains only our authored code/configuration and references. No mirrored JARs, pack ZIPs, borrowed city structures or proprietary config sets are committed. If no API credentials or direct-download rights are available, automation should produce a manifest/instructions and clearly label downloads as manual rather than silently scraping a site.
