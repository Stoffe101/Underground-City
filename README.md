# Underground City

> **Status: pre-production | Private-play living-world RPG | Not a playable modpack yet**

Underground City is a handcrafted-feeling, semi-modern Minecraft co-op RPG set among rival cities. The world is designed for **two human players** but should feel alive through citizens, businesses, faction leaders, patrols, quests, and simulated off-screen activity.

**Design influences, not bundled content:** Tale of Kingdoms (grow from citizen to influential leader), DeceasedCraft (dense modern-city atmosphere and exploration), life-simulation games (production and livelihoods), and faction RPGs (politics and consequences).

## What makes it different

- **A connected world:** a curated, finite playable region containing living cities, countryside, industrial areas, and contested territory. Existing licensed/permitted maps or configurable city generation may form its physical foundation.
- **A real civilian life:** farms, vineyards, smithies, factories, shops, property, deliveries, employment contracts, and taxation that contributes to city development.
- **Specialized careers:** Farmer/Vintner, Smith/Engineer, Gunsmith/Arms Dealer, and Hacker/Technician. Each has levels, branching skills, research/blueprints, workstations, and meaningful contract work.
- **General skills:** driving, fitness, shooting, trading, social influence, and related transferable capabilities; separate from career levels.
- **Consequential factions:** leaders, reputation, police, investigations, smuggling, guarded borders, escalation, and controlled conflicts.
- **An evolving story:** shared co-op campaign; citizenship and meaningful player choices change the world.
- **Small-party first:** NPC economies provide missing professions; no progression gates require seven humans; no bankruptcy because everyone was offline.

## Getting oriented

| Document | Purpose |
| --- | --- |
| [Documentation index](docs/README.md) | All specifications and technical plans |
| [Project roadmap](docs/production/roadmap.md) | Development phases and exit gates |
| [First playable slice](docs/production/mvp-v0.1.md) | What prototype 0.1 must prove |
| [Decision log](docs/production/decisions.md) | Agreed direction versus open questions |
| [Mod audit](docs/technical/mod-audit.md) | Candidate dependencies, not verified installations |
| [Third-party policy](docs/legal/third-party-policy.md) | Attribution, permissions, assets, and mod distribution |

## Proposed technology (not yet validated)

Minecraft **Java 1.20.1, Forge 47.x, Java 17** is the *working hypothesis*, not a locked platform. Candidate mods include The Lost Cities, CustomNPCs-Unofficial, Lootr, TaCZ, Pufferfish's Skills, KubeJS, FTB Quests, Lightman's Currency, Create, Farmer's Delight, and Vinery. All need exact-version dependency audits, client and dedicated-server smoke tests, and licensing checks before adoption.

Our custom **RPG Core** will make the authoritative decisions about players, professions, city economies, crime, contracts, and story progress. Content should be data-driven and tested; never bake fixed world coordinates or third-party item IDs into gameplay logic without adapters.

## Start here

1. Review [the design pillars](docs/design/vision.md) and [the first playable slice](docs/production/mvp-v0.1.md).
2. Validate versions and licensing through the [mod audit](docs/technical/mod-audit.md), and use the [asset register](docs/legal/asset-register.csv).
3. Test a clean client and dedicated server before importing maps or locking world generation.
4. Build one district, two careers, NPC buyers, a short quest, and a small legitimate gameplay loop before scaling.

**Development status:** The repository now includes an experimental Forge RPG Core source module and automated short-lived CI build artifacts, including local launcher-import starter archives. It is **not** a finished modpack and contains no selected city world, NPC population, third-party mod set or complete gameplay.

## Ownership and license

**Copyright © 2026 Stoffe101. All rights reserved.** See [LICENSE](LICENSE). The license governs **original Underground City material only**. Minecraft, third-party mods, map downloads, artworks, and their trademarks remain subject to their respective owners' terms. Public repository visibility does **not** mean the project is open source. This project is intended for private play, not for sale or public modpack publication.

## Experimental build and automated tooling

The [RPG Core scaffold](rpg-core/README.md) compiles against Forge 1.20.1 and has an in-engine persistence GameTest. The [Forge Core workflow](.github/workflows/forge-core.yml) builds the mod and produces short-lived artifacts: an original-mod JAR and minimal importable starter archives for Modrinth/CurseForge-compatible launchers. The starter archives contain **only our original mod**, with no third-party code/assets or world.

Our [offline modpack auditor](scripts/audit_modpack.py) reads CurseForge/Modrinth pack exports or installed Forge/Fabric JAR metadata to identify package contents, duplicate mod IDs, and suspected missing dependencies. The [reuse-first policy](docs/technical/reuse-first.md) guides decisions about using existing mods and packs instead of expanding custom code prematurely.
