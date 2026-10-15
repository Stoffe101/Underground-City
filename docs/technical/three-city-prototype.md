# Three-city experimental world generator

**Status: original source/profile, native building generation at all three anchors, and original road/bridge generation along two inter-city corridors (measured on a dedicated server and photographed by a native client). No populated, furnished or inhabited city claim.**

The world uses The Lost Cities' [1.20.1 predefined-city registry](https://mcjty.eu/docs/mods/lost-cities/asset_structure), packaged as an original Forge low-code mod. This avoids building thousands of structures by hand. Version-targeted semantics matter: city centers are chunk coordinates, radii are in **blocks**.

## Proposed city anchors (all provisional)

| City | Center (block X, Z) | Radius | Generated massing / intention |
| --- | --- | --- | --- |
| Ironhaven | 0, 0 | 600 blocks | Dense industrial skyline, 9-floor cap |
| Valewood | 2016, 704 | 410 blocks | Lower-rise, more parks, 3-floor cap |
| Blackwater | -2016, 1072 | 570 blocks | Dense commercial towers, 11-floor cap |

These starting locations, skyline caps, terrain seed, and styles are **test values**, not approved canonical geography. No city actually has its own hand-made factory, vineyard, residence interior or functioning NPC population yet. Lost Cities' own highway planner never connects these fixed centers (measured, see below); Core's corridor road feature does, for the two main roads. Furnished interiors and NPC homes still require asset work.

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
4. Create a fresh world, using seed `579` to reproduce the hosted survey (see [seed choice](#seed-choice-water-between-the-cities-is-a-feature) for why; the earlier `20261014` put Ironhaven in the sea). Confirm all three centers, terrain transitions, safe spawn, roads and chunk performance.
5. Do not move prototype coordinates or apply this profile to an existing save. The installer rejects existing saves/configs/packages; generation changes affect new chunks only.

A passing ZIP/unit test proves **only source validation**. A separate next-stage in-engine Lost Cities test must prove the mod reads all three assets and generates them at the specified centers with no random extra city center. The dedicated native-block survey now provides generation evidence at all three anchors; it does not prove no extra city centers across the entire world.

## Loot tables, currently NOT attached to actual homes

The generated world mod also adds **five original vanilla-item loot tables**: ordinary apartment, affluent apartment, office, industrial site and criminal cache. They have intentionally low-value prototype contents. These tables become useful only when linked to Lost Cities' conditions/palettes, structure containers, or a tested loot assignment tool. **Generating the tables alone does not place chest inventories in buildings.**

- `Lootr` is the recommended choice for **per-player exploration finds**, not for shared property stock or burglary.
- Occupied properties need **single/shared authoritative household assets** so two players cannot clone an NPC's valuables.
- `Locks Reforged` is a strong 1.20.1 Forge lock/lockpicking candidate; verify its interactions with Lost Cities, NPC doors, and quest-managed items.
- Nothing in this prototype presently implements ownership, theft, alarm, evidence, arrest, witnesses, or robbery inventory behavior. See [burglary design](../design/burglary-and-property.md).

## Next milestones

Three-anchor building generation and two roads with bridges are now proven in a dedicated server. Next: gate houses, classify buildings by use, apply legal versus explore/rob loot policies, insert NPCs with homes/workplaces, and implement robbery events through **existing lock and economy integrations**. The technical constraint is not the raw number of buildings, but producing believable lived-in locations without spending months furnishing every room.

For reference: [world pipeline](world-pipeline.md), [mod compatibility](mod-audit.md), [modpack reuse](reuse-first.md).

## Rival city corridors and gradual restock extension

[Corridor blueprint](../../content/region-blueprints/corridors.json) now describes a three-edge network: Ironhaven–Valewood, Ironhaven–Blackwater and Valewood–Blackwater. Two route segments are marked **proposed bridges** with guarded gates; the third is a longer frontier bypass. The [route preview SVG generator](../../scripts/render_world_plan.py) visualizes that **schematic**, and the [intercity validator](../../scripts/validate_intercity.py) checks endpoints, neighboring-city avoidance, crossings, gates, war-state pairs, cross-city recipe prerequisites and stock-recovery rules. CI packages an SVG and machine-readable report next to our original low-code world data.

Our [inter-city progression design](../design/intercity-progression.md) requires visiting at least one rival city for tier 2 and all three cities for tier 3, with both a legitimate supplier route and a riskier alternative. The underlying commodities are currently logical design identifiers, **not actual registered Minecraft items or crafting recipes**.

Our [property restocking design](../design/gradual-property-restock.md) describes slow replenishment of **shared** household inventories, with partial refills and caps. Pure Python tests exercise that logic. No chests are filled or refilled in Minecraft yet.

### Native Lost Cities highways

Forge **Lost Cities 7.5.5** includes the versioned 7.5 inter-city highway network. Its planner builds hubs only from approximate **random** city potential, never from predefined cities, so with random cities off (our profile) no highway exists. Measured on two seeds: zero highway chunks among 3,097 sampled chunk plans (city rings, corridor lines, a 12-chunk lattice) while 1,968 city chunks lay inside the declared radii. We therefore do not rely on it, and build our own roads (next section).

## Roads and bridges (original, generated with new chunks)

`underground_city:corridor_roads` is a Forge worldgen **Feature** in Core. A datapack, generated from [corridors.json](../../content/region-blueprints/corridors.json) and [cities.json](../../content/region-blueprints/cities.json) by `scripts/build_world_prototype.py`, places it in every Overworld biome (`forge:add_features`, last decoration step), so:

- It runs only while **new chunks** are generated in a fresh world. It cannot rewrite an existing save, and nothing is pasted or edited afterwards.
- Coordinates are data, not Java: route waypoints and the Lost Cities circles come from the blueprint. Two routes are built (`guarded_highway`, `militarized_highway`); the long frontier route stays unpaved. The road stops at each city circle, where Lost Cities owns the street grid.
- Geometry is a pure function of the world seed and the route data ([`CorridorPlan`](../../rpg-core/src/main/java/io/github/stoffe101/undergroundcity/CorridorPlan.java)), so every chunk agrees with its neighbours without storing anything. The deck profile is sampled every 4 blocks from the chunk generator's own surface and ocean-floor heights, smoothed, and slope-limited to half a block per two blocks (slabs make the half steps, so a walker or horse needs no jumping).
- The deck is 7 blocks of polished andesite. **Where water lies below, the deck rides four free blocks above it so a boat can sail beneath**; connected parapet walls, a lantern every 16 blocks and stone-brick supports (piers every 24 blocks) make a bridge. Viaducts cross dry ravines the same way, shallow dips are filled, hills are cut by at most 8 blocks, and trees or leaves on the road are removed.
- Every chunk repairs the road across its eight neighbours, so a tree or vanilla structure placed after a neighbour's run cannot leave a leaf block in the road whatever order chunks generate in.
- Only vanilla blocks are used; no third-party structure, schematic or texture is copied.

Water between the cities is **a feature, not a defect**: a bridge is the safe way over, and a boat is the alternative (rivers and straits stay open under every bridge).

### Verification (seed 579, native dedicated server in the cloud container)

`WorldRoadVerifier` forces the road chunks to generate on worker threads (a chunk ticket; `ServerLevel.setChunkForced` generates on the server thread and trips the 60 s watchdog), then reads the **real blocks** on the centreline every block: deck present, three free blocks of headroom, no step above half a block between neighbours, a parapet on the bridge sides, and water with free blocks under the deck.

| Road | Length | Bridge/viaduct deck | Longest bridge | Longest open water under deck | Deck missing | Headroom blocked | Steps > half block | Parapets missing |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Ironhaven – Valewood | 1,400 | 343 blocks | 120 | 38 | 0 of 1,252 | 0 | 0 | 0 of 654 |
| Ironhaven – Blackwater | 1,295 | 422 blocks | 184 | 129 | 0 of 1,117 | 0 | 0 | 0 of 772 |

The hosted run [38676037902](https://github.com/Stoffe101/Underground-City/actions/runs/38676037902) (head `1718ee3`) reproduced the same numbers on GitHub's runners (1,252 and 1,117 centreline samples, longest bridges 120 and 184 blocks), which also shows the result does not depend on chunk generation order or hardware. Both reports say `continuous: true`; 284 chunks generated in 323 s on two software cores. Plan construction took about 3 s. The smallest measured free height between water and the bridge underside was 3 blocks (at shoreline ramps); the design value over water is 4. This proves the blocks exist and form a gap-free walkable line. It does **not** prove: that vehicles from a vehicle mod fit, that mobs behave on the road, that the roads look good (that is the separate frame review below), or that anything lives along them. Vanilla structures generated on top of the road centreline are repaired by the neighbour pass but a rare conflict could still appear; it would show as `deck_missing` in the next survey.

### Frame review (native client in the cloud container, seed 579)

Twelve real frames were viewed (six city, six road). What the numbers did not show and the frames did: the parapet walls were unconnected posts (fixed: a second pass joins them), and one oblique bridge frame was nearly all sky or aimed at a few unmeshed blocks because the camera was high and steep or the deck was not loaded yet (fixed: a low, farther camera and a wait until the client holds the deck block). After the fixes the bridge frames show a long stone deck on piers over open water and over a river, connected parapets with lanterns, 4 free blocks under the deck for a boat, and a ground road cut through taiga and birch forest with slab ramps. Known rough edges: slab ramps and diagonal edges look stepped, trees overhang the parapet in places, and the Ironhaven oblique city frame still has a nearby rooftop in the foreground (the camera is higher and the render distance is now 8, which shows a much wider skyline). These are local container frames, not hosted artifacts, so nothing is recorded in `content/qa/reviewed-runs.json` for them.

### Seed choice: water between the cities is a feature

Design intent (user, October 15): ocean or large rivers between the cities make the bridges pivotal, and a boat should be a valid alternative. Seed `20261014` put Ironhaven itself in the sea and the Ironhaven–Blackwater road 100% under water, so it was replaced. `WorldSeedScanHarness` ranks seeds in two stages (a cheap climate-sampler pass, then the chunk generator's real surface and ocean-floor heights along the real road centrelines) for: dry city sites, 6-35% water per built road in gaps of 48-320 blocks, and land elsewhere. Of 10,000 seeds, 302 finalists were re-scored with real heights. Seed `3130` (best of an earlier climate-only scan) was generated first and rejected: about half of its Ironhaven–Valewood road lay on water. Seed `579` (fifth of the real-height ranking) was then generated and measured. It has dry cities, two roads with long bridges over rivers and braided shoals, and an open **sea** on the unpaved Valewood–Blackwater edge (944-block longest water run), so travel there is by boat or around through Ironhaven. This ranking is a tool, not proof of quality: a seed's other terrain was not reviewed.

### Travel time (D-048)

Accepted constraint: routine inter-city travel should take only a few minutes. A static estimate at walking speed (4.317 blocks per second, no sprinting, no obstacles) gives about 4.8 minutes along the Ironhaven–Valewood road (1,251 blocks between city edges) and about 4.3 minutes along Ironhaven–Blackwater (1,116 blocks), inside the 3-5 minute aim. That is arithmetic, not a measurement: a real traversal benchmark (with the slab ramps, doors, a horse or boat, and two players) is still owed before a release. The Valewood–Blackwater edge is open sea in seed 579, so it must stay optional until a ferry or boat service exists.

### What is still not built

Gate houses at the checkpoints, a built road on the Valewood–Blackwater edge, rest stops, road signs, NPC patrols and vehicle-mod compatibility. The checkpoint, `travel_modes` and `security` fields in `corridors.json` remain design data.

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
