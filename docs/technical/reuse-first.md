# Reuse-first implementation policy

**Accepted technical direction (2026-10-14):** Prefer an existing mod or modpack that already provides the required behavior. Try configuration, datapacks, scripting and integration before writing a replacement. The pack is for two people and not intended for public release, but license, access and compatibility obligations still apply.

## Decision ladder

1. **Use an existing mod unchanged**, if server/client compatibility, rights, performance, persistence and UX pass real tests.
2. **Use supported configuration, recipes, datapacks and scripting** to adapt it to our world.
3. **Write a narrow bridge** (e.g. city reputation impacts shop stock) instead of duplicating its economy or roles.
4. **Fork when license and permission explicitly permit,** keeping attribution and maintenance obligations.
5. **Write a new implementation** only when there is no suitable stable alternative, or integration becomes more fragile than the new code.

Every proposed custom module gets an explicit build-versus-buy justification in an issue. **Do not assume RoleCraft or any other mod exposes a public API simply because it has admin commands.**

## First-login screen: reuse priority

See [first-join onboarding spike](onboarding-reuse-spike.md). Cisco's layered origin/blessing/class UI demonstrates a reusable *pattern*, while [Class Selection Mod](https://www.curseforge.com/minecraft/mc-mods/class-selection-mod) already has a potentially ideal **faction → class** experience, plus announced TaCZ and Epic Fight compatibility. Validate the published artifact and its APIs before replacing our prototype `/uc register`. If the two-layer mod is unsuitable, assess [Class Enhancement](https://www.curseforge.com/minecraft/mc-mods/class-enhancement) and [RoleCraft](https://www.curseforge.com/minecraft/mc-mods/role-craft), then [Origins Classes Forge](https://www.curseforge.com/minecraft/mc-mods/origins-classes-forge).

The provider-neutral [city/profession catalog](../../content/onboarding/catalog.json) can be adapted to one chosen provider, and CI checks it matches fallback Core IDs. **Do not install two overlapping first-login UIs**.

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

**Superseded in part (October 16, 2026):** the paragraph below was written before the starter slice. Core now owns career XP, contracts, skill trees and the evidence-based law model (D-050, D-051), and Q Shop 1.8.4 owns the wallet; see the sprint decisions at the end of this file. It is kept for the reasoning, not as a current instruction.

`rpg-core/` is a **standalone experimental fallback** to prove Java build, simple persistence and multiplayer. **Do not add career trees or an economy implementation** until RoleCraft/Pufferfish/Lightman's Currency have been separately tested. If an existing mod meets the requirements, RPG Core should pivot to a thin integration layer for city/story-specific behavior. The prototype's `/uc register` command is not intended to compete with RoleCraft's first-login UI.

## Private-play installation

Use official downloads/launchers locally and preserve creator notices. Git contains only our authored code/configuration and references. No mirrored JARs, pack ZIPs, borrowed city structures or proprietary config sets are committed. If no API credentials or direct-download rights are available, automation should produce a manifest/instructions and clearly label downloads as manual rather than silently scraping a site.

## Current starter-slice ownership

Q Shop 1.8.4 was selected for the live starter wallet/trades after official API/source review and real Forge transaction tests. Core's work orders are narrow world-specific glue for shared bounded employer demand, accepted-order UUIDs, actual-item delivery and coordinated recovery. They reuse vanilla crafting and Q Shop currency APIs rather than building a bank, new crafting framework or a full quest UI. FTB Quests remains a candidate for story/team objectives; its static reward model does not by itself coordinate these three persisted stores. No overlapping quest/role/XP provider was added. Vanilla villager AI supplies the first real named clerk; a larger NPC framework still needs separate evaluation.

## Sprint decisions (October 15-16, 2026): what we reused, what we built, what we rejected

| Need | Chosen | Why, and the reuse-first trail |
| --- | --- | --- |
| Wallet and civilian shops | Q Shop 1.8.4 (sole provider) | unchanged; no second currency or ledger |
| Career skill trees | **Core-owned datapack catalog** (D-050) | Core already owns career XP and the journal-backed settlement; Pufferfish's Skills would be a second owner of the same truth. Pufferfish's stays a possible *display* layer if progression data can be synchronised safely. The trees are data (`data/underground_city/skill_trees/<career>.json`), not hard-coded |
| Firearms | TaCZ 1.1.8-hotfix2 + TaCZ Blueprints 0.1.3 | reuse a real gun mod; Core only names its items and gun ids; hash-pinned runtime-only dependencies, nothing redistributed |
| TaCZ research addon | **Rejected for now**: "TaCZ Weapon Research & Blueprints" | only a CurseForge file; it could not be located and verified from the cloud container. Select one blueprint addon, never assume two interoperate |
| Boats | Modern Life 1.75 Motorboat | has a persistent 27-slot hold and fuel; its cargo round trip was tested on a production-style server |
| Road vehicles | Immersive Vehicles 24.0.0 + Official Content Pack V29 + Official Automobile Pack V3 | engine plus content packs; boots and registers content; driving untested |
| Law, contraband, buyers, heat, trust | Core (new) | no candidate model fits the city-law and evidence design; the rules are small, data-driven and GameTested |
| Technician challenge | Core (new) | a hacking mini-game in a candidate mod would not follow our server-owned sequence, kit and lockout rules; the screen is original |
| Operator district | Core (new, data-defined) | a structure format already exists in vanilla; the builder is a small command, not a schematic mod |

The machine-readable pins live in `content/modpacks/dev-pack.pins.json` and `dev-pack.json`; see [dev pack](dev-pack.md).
