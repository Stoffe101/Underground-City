# Three-city experimental world generator

**Status: original world-content source + CI packaging, not yet a generated Minecraft save or a visually reviewed world.**

The world uses The Lost Cities' [1.20.1 predefined-city registry](https://mcjty.eu/docs/mods/lost-cities/asset_structure), packaged as an original Forge low-code mod. This avoids building thousands of structures by hand. Version-targeted semantics matter: city centers are chunk coordinates, radii are in **blocks**.

## Proposed city anchors (all provisional)

| City | Center (block X, Z) | Radius | Generated massing / intention |
| --- | --- | --- | --- |
| Ironhaven | 0, 0 | 600 blocks | Dense industrial skyline, 9-floor cap |
| Valewood | 2016, 704 | 410 blocks | Lower-rise, more parks, 3-floor cap |
| Blackwater | -2016, 1072 | 570 blocks | Dense commercial towers, 11-floor cap |

These starting locations, skyline caps, terrain seed, and styles are **test values**, not approved canonical geography. No city actually has its own hand-made factory, vineyard, residence interior or functioning NPC population yet. The generator does not automatically connect the three centers via vehicle-ready highway. Those features require later map inspection and asset work.

All three styles inherit the mod's standard city style instead of copying third-party structures. A unique city style primarily changes **building density, floor cap and park frequency**. Proper unique architecture, landmarks, NPC home/job markers, civic services and apartment interiors follow after the preliminary world is viewed in-engine.

## Reproducible original-content build

```sh
python scripts/build_world_prototype.py --output build/world/underground-city-world-prototype.jar
```

The [world-prototype workflow](../../.github/workflows/world-prototype.yml) does this automatically, runs unit/schema tests, and uploads the generated original low-code mod JAR. It contains **only new original data authored for Underground City** and references the existing Lost Cities engine at runtime.

## IMPORTANT: profile must match

The three fixed-city assets **do not select a Lost Cities profile by themselves**.

When evaluating in a **new disposable world** with matching Forge/Lost Cities versions:

1. Install The Lost Cities and our generated `underground-city-world-prototype.jar`.
2. In Lost Cities' world-creation UI, select a new customized profile based on a minimally damaged profile (`nodamage` is a candidate). Turn off spawners, minimize damage, keep loot generation on.
3. Set **cityChance = 0** to stop the mod's ordinary random city centers from adding unbounded other cities.
4. Set **cityMaxRadius to at least 600** so the city-center scan can cover our largest predefined radius.
5. Create a fresh world. For a dedicated server, follow the official procedure: copy that world's `serverconfig/lostcities-server.toml` to the new server's `defaultconfigs/lostcities-server.toml` before creating the server's world. Check selected custom JSON/profile and logs.
6. Confirm each city produces valid structures. Visit or pre-generate all three areas; check terrain seams, heightmap, building density, public roads, chunk performance and loaded assets.
7. Do not move prototype coordinates in an existing save. Changes affect **newly generated chunks only** and can corrupt world continuity without a new world or a controlled schematic operation.

A passing ZIP/unit test proves **only source validation**. A separate next-stage in-engine Lost Cities test must prove the mod reads all three assets and generates them at the specified centers with no random extra city center. That is not claimed here.

## Loot tables, currently NOT attached to actual homes

The generated world mod also adds **five original vanilla-item loot tables**: ordinary apartment, affluent apartment, office, industrial site and criminal cache. They have intentionally low-value prototype contents. These tables become useful only when linked to Lost Cities' conditions/palettes, structure containers, or a tested loot assignment tool. **Generating the tables alone does not place chest inventories in buildings.**

- `Lootr` is the recommended choice for **per-player exploration finds**, not for shared property stock or burglary.
- Occupied properties need **single/shared authoritative household assets** so two players cannot clone an NPC's valuables.
- `Locks Reforged` is a strong 1.20.1 Forge lock/lockpicking candidate; verify its interactions with Lost Cities, NPC doors, and quest-managed items.
- Nothing in this prototype presently implements ownership, theft, alarm, evidence, arrest, witnesses, or robbery inventory behavior. See [burglary design](../design/burglary-and-property.md).

## Next milestones

First prove the three cities generate. Then screenshot-review actual environments, create/connect travel routes, classify buildings by use, apply legal versus explore/rob loot policies, insert NPCs with homes/workplaces, and implement robbery events through **existing lock and economy integrations**. The technical constraint is not the raw number of buildings, but producing believable lived-in locations without spending months furnishing every room.

For reference: [world pipeline](world-pipeline.md), [mod compatibility](mod-audit.md), [modpack reuse](reuse-first.md).
