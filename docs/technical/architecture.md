# Runtime architecture and planned extensions

The current starter slice uses Minecraft 1.20.1, Forge 47.4.10 and Java 17. Core identity/progression, the PC, Q Shop-backed work orders and one vanilla supplier are implemented. City populations, taxes, crime, story, regional crafting and other proposed modules below remain future work.

## Ownership of behavior

Minecraft and existing mods provide blocks, items, rendering, gun handling, terrain, NPC entities, skills UI and per-player loot. A custom **Underground City RPG Core** becomes the server-side authority for citizenship, career/general XP, contracts, town economy, law, faction reputation and story progress. Integration adapters consume events from other mods. Gameplay definitions should be data-driven.

## Proposed modules

| Module | Responsibility |
| --- | --- |
| Identity | Player onboarding, city citizenship, career assignment |
| Progression | Branch points, career and general skill XP, gates and anti-farming |
| Economy | Q Shop owns the wallet/trades; Core journals bounded profession orders. City treasuries, taxes, escrow and projects remain planned |
| World registry | Cities, named regions, buildings, sites and import anchors |
| NPC simulation | Named characters, shop stock, schedules, aggregate off-screen workers |
| Factions & law | Territory, reputation, evidence, licensing, crime and arrest |
| Campaign | Quest state machines, story choices, world restoration |
| Compatibility | External mod adapters, registry translations and version guards |

## Design constraints

- **Server authoritative**: clients request actions; server checks permissions, prerequisites, funds, supplies and current state.
- **Idempotent**: each paid quest, item grant and purchase has a unique transaction/request ID; retries must not duplicate results.
- **Durable**: versioned player and world state, persistent across rejoin/restart; migrations and rollback tested before updates.
- **Replaceable geography**: gameplay names a `region_id` or `location_id` instead of hardcoded coordinates; bindings live in world data.
- **Economically consistent**: physical loaded businesses reconcile with off-screen aggregate stock/treasury records.
- **Small-party**: an absent third player never blocks core progression.
- **Performance aware**: active NPC budgets and bounded background simulation; benchmark real server TPS.

## Technical gate

Before implementing Forge-specific hooks/persistence, build a clean mod scaffold, run a dedicated server, connect two clients, and record actual API versions. `rpg-core/` is built and runtime-tested; source design data lives under `content/` and automation under `scripts/`. Paid work-order persistence and provider boundaries are described in [economy integration](economy-integration.md).
