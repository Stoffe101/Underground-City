# Decision log

Use `D-###` IDs. Status values: **accepted (product intent)**, **proposed**, **needs spike**, **superseded**. Accepted intent is not proof of an implemented feature.

| ID | Status | Decision | Why / validation |
| --- | --- | --- | --- |
| D-001 | accepted (product intent) | Balanced living-world RPG; civilian life and conflict share importance | Must support both styles in a single co-op world |
| D-002 | accepted (product intent) | Two-player-first private experience; NPCs fill labor and story gaps | No mandatory group-size gates |
| D-003 | accepted (product intent) | Semi-modern cities with leaders, taxes, professions, social simulation, and evolving political story | Narrative and economic agency |
| D-004 | accepted (product intent) | Career levels + branching profession trees + separate general skills | Multiple ways to progress and specialize |
| D-005 | accepted (product intent) | Curated connected finite region, assembled from legitimate existing maps, permitted structures, or customized generation | Entire cities cannot be handmade |
| D-006 | accepted (product intent) | Original project material proprietary/ARR; third-party rights respected | Private scope; provenance required |
| D-007 | proposed | Java 1.20.1 + Forge 47.x + Java 17 | Promising mod availability; confirm in compatibility tests |
| D-008 | needs spike | Lost Cities city generation versus adapting an existing connected modern-city world | Measure visuals, compatibility, pathfinding, customization, and region boundaries |
| D-009 | proposed | Dedicated custom RPG Core for server-authoritative persistence and event integration | Avoid fragile independent scripts as source of truth |
| D-010 | needs spike | CustomNPCs vs MineColonies vs other NPC approaches | AI, performance, licenses, map integration |
| D-011 | needs spike | TaCZ blueprint/research addon selected after one-at-a-time compatibility review | Avoid duplicated blueprint systems |
| D-012 | proposed | No passive offline taxation or world-economy punishment | Fair for intermittent private co-op |
| D-013 | proposed | Server-authoritative ledgers, idempotent quests and transactions, stable data IDs, migrations | Prevent exploits and save corruption |
| D-015 | accepted (product intent) | Prefer reusing compatible existing mods and modpacks instead of replicating their mechanics | Private two-player focus and minimal ongoing maintenance; validate first |
| D-016 | needs spike | RoleCraft might provide registration, career progression, player restrictions and police items | Test configurability for gunsmith/hacker and mod APIs before deciding |
| D-017 | needs spike | GCA/DeceasedCraft/Crusthaven/ModernCraft as candidate base packs | Audit content/rights/overlap and dedicated-server performance |
| D-018 | accepted (product intent) | First join should present home city then profession, inspired by Cisco's layered race/blessing/class selection | Avoid a fantasy race dependency; all residents remain humans by default |
| D-019 | needs spike | Prefer a proven faction/class selector or configuration-driven class UI over custom onboarding GUI | Evaluate Class Selection Mod first, then Class Enhancement/RoleCraft/Origins alternatives |
| D-020 | proposed | Portable city and profession design catalog defined once and adapter-owned by the selected mod | No duplicate onboarding screens, starter grants, or XP authorities |
| D-021 | proposed | Prototype three fixed cities with Lost Cities' original datapack registries | Validate actual generation, biome/seam quality and populated city realism before final world choice |
| D-022 | accepted (product intent) | Burglary and legal careers are parallel progression paths | Homes can be robbed with ownership, real scarcity and evidence-driven city policing; Lootr reserved for co-op exploration caches |
| D-023 | accepted (product intent) | All advanced professions require real journeys to other rival cities | Tier 2 at least one foreign city; tier 3 all three; lawful and high-risk supply options |
| D-024 | accepted (product intent) | All three cities connected through guarded bridges/highways or suitable wilderness bypasses | World geometry and crossing hazards must be tested before anything is pasted |
| D-025 | accepted (product intent) | Occupied homes slowly restock shared goods instead of becoming permanently depleted or instantly resetting | Provisional 1 item per elapsed active-play interval, caps, persistence and per-home economy funding; reuse an existing refill mod when possible |
| D-026 | needs spike | Lost Cities 7.5.5 highways, [TACZ] Lootchest Refill 1.1.2 and Faction Friction 1.8.0R could supply infrastructure, timed restock and warfare | Pin releases and run isolated tests, then a combined in-world test |
| D-014 | proposed | Visible active NPCs + bounded off-screen economic simulation | Plausible cities without thousands of ticking entities |

## Open decisions

- City geography and world source; legal approval for imported maps and schematics.
- Which character careers are available initially and whether starting careers can be changed.
- Difficulty, death penalties, fines, police evidence, PvP/friendly fire, and safe zones.
- The unit of tax time, prices, item tiers, production times, and viable sinks/sources.
- UI language, aesthetic, and accessibility options.
- Whether weapons are legal by class, municipality, license, or context; what is actually contraband.
- Resource regeneration and ownership in fixed exposed mining areas.

Every open decision should have an issue or spike before it becomes a hard implementation dependency.
