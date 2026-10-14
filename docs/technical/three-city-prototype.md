# Three-city experimental world generator

**Status: original source/profile plus successful native dedicated-server building generation at all three anchors; visual tour under review. No connected/populated city claim.**

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

For a **new disposable world** with Minecraft 1.20.1, Forge 47.4.10 and Lost Cities **1.20-7.5.5**:

1. Obtain the original author mod separately; build/install our original files with `python scripts/install_world_prototype.py --server-root <fresh-instance>`.
2. The installer selects `uc_three_city` in server defaults and maps the Overworld in `config/lostcities/common.toml`. Its profile disables random city centers, spawners and damage, and scans the largest predefined radius.
3. Its original `underground_city:three_city` world style omits the standard ocean/river penalties, so fixed city anchors remain eligible across vanilla biomes. Height bounds cover the Overworld. This can produce significant terrain platforms/seams that need actual visual inspection.
4. Create a fresh world, using seed `20261014` to reproduce the hosted survey. Confirm all three centers, terrain transitions, safe spawn, roads and chunk performance.
5. Do not move prototype coordinates or apply this profile to an existing save. The installer rejects existing saves/configs/packages; generation changes affect new chunks only.

A passing ZIP/unit test proves **only source validation**. A separate next-stage in-engine Lost Cities test must prove the mod reads all three assets and generates them at the specified centers with no random extra city center. The dedicated native-block survey now provides generation evidence at all three anchors; it does not prove no extra city centers across the entire world.

## Loot tables, currently NOT attached to actual homes

The generated world mod also adds **five original vanilla-item loot tables**: ordinary apartment, affluent apartment, office, industrial site and criminal cache. They have intentionally low-value prototype contents. These tables become useful only when linked to Lost Cities' conditions/palettes, structure containers, or a tested loot assignment tool. **Generating the tables alone does not place chest inventories in buildings.**

- `Lootr` is the recommended choice for **per-player exploration finds**, not for shared property stock or burglary.
- Occupied properties need **single/shared authoritative household assets** so two players cannot clone an NPC's valuables.
- `Locks Reforged` is a strong 1.20.1 Forge lock/lockpicking candidate; verify its interactions with Lost Cities, NPC doors, and quest-managed items.
- Nothing in this prototype presently implements ownership, theft, alarm, evidence, arrest, witnesses, or robbery inventory behavior. See [burglary design](../design/burglary-and-property.md).

## Next milestones

Three-anchor building generation is now proven. Next screenshot-review actual environments, create/connect travel routes, classify buildings by use, apply legal versus explore/rob loot policies, insert NPCs with homes/workplaces, and implement robbery events through **existing lock and economy integrations**. The technical constraint is not the raw number of buildings, but producing believable lived-in locations without spending months furnishing every room.

For reference: [world pipeline](world-pipeline.md), [mod compatibility](mod-audit.md), [modpack reuse](reuse-first.md).

## Rival city corridors and gradual restock extension

[Corridor blueprint](../../content/region-blueprints/corridors.json) now describes a three-edge network: Ironhaven–Valewood, Ironhaven–Blackwater and Valewood–Blackwater. Two route segments are marked **proposed bridges** with guarded gates; the third is a longer frontier bypass. The [route preview SVG generator](../../scripts/render_world_plan.py) visualizes that **schematic**, and the [intercity validator](../../scripts/validate_intercity.py) checks endpoints, neighboring-city avoidance, crossings, gates, war-state pairs, cross-city recipe prerequisites and stock-recovery rules. CI packages an SVG and machine-readable report next to our original low-code world data.

Our [inter-city progression design](../design/intercity-progression.md) requires visiting at least one rival city for tier 2 and all three cities for tier 3, with both a legitimate supplier route and a riskier alternative. The underlying commodities are currently logical design identifiers, **not actual registered Minecraft items or crafting recipes**.

Our [property restocking design](../design/gradual-property-restock.md) describes slow replenishment of **shared** household inventories, with partial refills and caps. Pure Python tests exercise that logic. No chests are filled or refilled in Minecraft yet.

### Native Lost Cities highways

Forge **Lost Cities 7.5.5** includes the versioned 7.5 inter-city highway network, roads, intersections and highway bridge features. We will test [the pinned Forge 1.20.1 release](https://www.curseforge.com/minecraft/mc-mods/the-lost-cities/files/8862717) in the [world smoke workflow](../../.github/workflows/world-integration.yml). The highway planner targets approximate city concentrations and **does not promise** to connect our three fixed city anchors, especially when random-city chance is disabled. It will need explicit surveying and possible original structure/world-edit additions. The schematic route bridges are placeholders, not placed blocks.

## Source-checked fresh-instance setup

`python scripts/install_world_prototype.py --server-root <fresh-instance>` now stages our original world JAR, `config/lostcities/profiles/uc_three_city.json`, `config/lostcities/common.toml`, and `[profiles]` selection in `defaultconfigs/lostcities-server.toml`. Field names and codecs were checked against official Lost Cities **7.5.5 source commit 61ed3004**. The profile disables random city centers/spawners/damage and scans all predefined radii, with native `INTERCITY_NETWORK_V1` highway planning. It rejects existing saves, defaults, profiles and packages instead of overwriting them. The author mod must still be obtained separately.

This removes manual profile transcription; native building generation now passes at all three anchors, while highways and terrain quality need further verification. Real terrain, bridges, interiors, safe spawn and paths still require a disposable Lost Cities world survey. The recent two-client tests used a vanilla flat transaction fixture, not this city profile.

The dedicated-world check exposed an original dependency error: Lost Cities declares version `1.20-7.5.5`, which does not satisfy `[7,)`. The world package now requires the actual pinned `[1.20-7.5.5]`. The native survey generates nine chunks around each data-defined center and records real window-block counts/heights; its result is checked independently of the headless smoke tests. Roads, inhabitants and visual quality are separate gates.

## Native generation evidence, October 14

[Hosted run 38644583100](https://github.com/Stoffe101/Underground-City/actions/runs/38644583100), source `97205e8f8db6108cf5745d1b028a138cc5d08157`, generated nine actual chunks per center in seed `20261014`:

| Site | Native biome | Actual window blocks | Window Y span |
| --- | --- | ---: | --- |
| Ironhaven | ocean | 1,040 | 55–98 |
| Valewood | savanna | 1,505 | 69–110 |
| Blackwater | river | 1,492 | 61–105 |

The initial standard-style test found zero windows at ocean Ironhaven. Explicit Overworld mapping and an original world style without ocean/river city-factor penalties fixed the tested profile. This survey samples structures, not every chunk, architectural diversity, floor caps, roads, bridges, NPCs or survival spawn. Generation caused several seconds of tick delay in the probe and is not a multiplayer performance benchmark.

The opt-in `ucWorldTour` harness loads a disposable clone in `run-world-tour`, uses native spectator cameras and `Screenshot.grab`, and saves three actual environment images. Its hosted artifacts expire after one day and require explicit visual review before gallery cleanup. World saves and author binaries are never uploaded.
