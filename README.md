# Underground City

> **Status: pre-production | Private-play living-world RPG | Tested starter slice, not a finished modpack**

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

## Runtime baseline and candidates

This development slice targets **Minecraft Java 1.20.1, Forge 47.4.10, Java 17**, as requested and compiled/runtime-tested. Candidate mods include The Lost Cities, CustomNPCs-Unofficial, Lootr, TaCZ, Pufferfish's Skills, KubeJS, FTB Quests, Lightman's Currency, Create, Farmer's Delight, and Vinery. All need exact-version dependency audits, client and dedicated-server smoke tests, and licensing checks before adoption.

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

## First-join mod candidate automation

The project now has two additional testing tiers before deciding whether to build a custom registration screen:

- [Original-author pinned CurseForge ZIP test profiles with original city/profession configuration](docs/technical/onboarding-screening-packs.md) for Class Selection Mod, Class Enhancement and Role Selection.
- [Isolated Forge GameTestServer smoke checks](.github/workflows/onboarding-compatibility.yml), one candidate at a time, using temporary third-party dependencies without shipping their binaries.

This is an **evaluation**, not a selected or confirmed compatible onboarding mod. City and career entries come from [our own catalog](content/onboarding/catalog.json), and we will use only one provider to own initial identity and rewards.

## Experimental three-city world content

We have original, data-driven city definitions for **Ironhaven, Valewood and Blackwater**, a [world-prototype generator](scripts/build_world_prototype.py), and a [CI workflow](.github/workflows/world-prototype.yml) that packages three Lost Cities predefined-city assets and three style variants as a reversible original-content low-code mod. A dedicated Forge survey generates real buildings at all three anchors, and a native Minecraft client renders six real frames (top-down and oblique per city) that we have actually looked at. **Measured limits:** Lost Cities' highway planner ignores predefined cities, so no native road connects the three cities (see [world notes](docs/technical/three-city-prototype.md)); no inhabitants, furnished interiors or placed suppliers exist. See also [burglary/property design](docs/design/burglary-and-property.md).

## City connections, rival supply chains, and respawning home loot

The [inter-city progression plan](docs/design/intercity-progression.md) ties advanced equipment/career tiers to trips between all three rival cities. A [three-corridor network](content/region-blueprints/corridors.json) with two prospective bridge spans is still **planning data** (terrain unverified, no placed roads or bridges). Two slices are live: tier-2/3 recurring orders require rival-city exchange visits recorded from a terminal placed inside a city circle, and [property restocking](docs/design/gradual-property-restock.md) runs in one real container, the **Household Safe** (shared stock, lockpick-forced, vigilance), which is not yet placed in generated buildings.

## Recurring regional supplies, not just one-time city unlocks

Nine specialist commodities, twelve crafted products and twelve recurring work orders are **live in Core** as original items and datapack data ([design](docs/design/recurring-supply-chains.md), [integration guide](docs/technical/economy-integration.md#regional-exchange-recurring-orders-and-rival-city-gates-october-15-2026)). Each city's terminal exchange sells only its own three goods from **finite shared stock** that replenishes during active play; every batch consumes goods, so the next order needs another purchase. Prices, stock and city are server-owned, purchases use the single Q Shop wallet and the prepared-settlement journal, and order margins are guarded at 1.3x-2.1x. Not yet: supplier NPCs/warehouses, permits and convoys, NPC production behind the shelves, and Create/TaCZ item rebinding (untested). The Python [supply simulator](scripts/simulate_regional_supply.py) remains a planning model only.

## One criminal/civilian computer terminal

The planned [in-world PC](docs/design/terminal-and-underworld.md) is a common interface for profession contracts, costly regional imports, time-limited crash recovery events, price-guided black-market sales with physical single-use dropboxes, and later gang/warehouse/police raids. Five source-validated property loot profiles allow rare firearm **components** in suitable locked residential safes and risky buildings alongside items for the other professions. Source-level transaction tests prevent duplicate sale/payout, illegitimate pickup, overgenerous loot and basic shipment retry abuses. **Advanced underworld operations, TaCZ weapon drops, burglary chests, crash locations and raids remain unimplemented**; the working artifact is a Python transaction model with future Forge integration to be selected reuse-first.

## Placeable PC and screenshot-based UI QA

The Forge RPG Core now registers an original compact **Operations Terminal** block (`underground_city:operations_terminal`) with a single-block static model, crafting recipe, item drop, and native five-tab GUI with server-backed work orders, the civilian shop and a city-gated regional exchange. It is opened by server-originated packet on right click. Contracts accept and consume real vanilla goods, pay Q Shop credits, award career XP, and unlock later work. Advanced shipment/market/raid actions remain unavailable. Rather than modify protected furniture/PC mod assets, the code keeps the model replaceable and can work alongside optional decor/programmable computer mods.

The [visual QA pipeline](docs/technical/visual-qa.md) starts an actual Forge client in a virtual Linux display, captures eighteen integrated PC states at two GUI scales, validates dimensions and missing/duplicate screenshots, and uploads short-lived screenshot galleries. **Other mod interfaces are not yet installed or covered**, listed in `content/qa/gui-coverage.json`. Gallery artifacts expire in one day; a dedicated cleanup workflow removes them immediately **after review is confirmed**. Real aesthetic/interaction judgement still requires inspection of actual PNGs. The visual build isn't claimed passing unless its real-client jobs finish successfully.

## First real starter shop integration (trial-only, October 2026)

A Q Shop **1.8.4** optional dependency is now pinned and integrated with the original one-block Operations Terminal's Imports tab. If Q Shop is installed with the authored starter template, the PC can open a real **civilian** trading window. The server verifies a nearby placed terminal and a registered citizen; the request cannot select an arbitrary shop or remote city supplier. The shop uses Q Shop credits and low-tier **vanilla** buy/sell goods only. **It is not yet an integrated regional trade/black-market/raid system**, and regional permissions remain unimplemented. Two actual cloud clients have bought/sold through the native UI and retained their values after dedicated restart. Original templates are installed safely with `python scripts/install_qshop_templates.py --server-root <new-server-root>`, with no overwrite of existing saved shops. Read [October 19 test readiness](docs/production/oct19-test-readiness.md).

## October 14 integration pass

The [real starter economy](docs/technical/economy-integration.md) now connects twelve data-driven vanilla profession orders, persistent reservations, shared bounded employer demand, actual item consumption, Q Shop payments and career-level unlocks to the PC. A prepared-transaction journal supports recovery and rejects repeated turn-ins. Q Shop is the sole live wallet; no second economy has been added. An OP can place one real named vanilla supply clerk beside a PC with `/uc_clerk`. The clerk opens the same validated terminal and uses vanilla routines when supplied a bed/workstation.

The visual client captures eighteen states per viewport, including contracts, the regional exchange and installed Q Shop screens. Actual screenshot review identified and corrected clipped shop labels. A [fresh-world installer](scripts/install_world_prototype.py) stages original city assets and a source-checked Lost Cities 7.5.5 profile without overwriting saves; native buildings are verified at all three anchors; road connectivity and inhabited-city quality remain unverified. See [October 19 readiness](docs/production/oct19-test-readiness.md) for measured evidence and remaining tests. This is a working starter slice, **not a finished three-city modpack**.

## October 15 continuation pass

Fixed the one red hosted check (the native city-tour client), added the regional exchange with recurring multi-item orders and rival-city gates, the Household Safe, a Lost Cities highway survey (finding: no native road links the fixed cities), five new GUI states, and a regional purchase click in the two-real-client journey. Twenty-six dedicated GameTests, 18 GUI states per viewport and the journey pass. Details and honest limits: [October 19 readiness](docs/production/oct19-test-readiness.md), [testing](docs/technical/testing.md) and [Claude project instructions](CLAUDE.md).

