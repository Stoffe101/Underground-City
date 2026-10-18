# Three-city experimental world generator

**Status: original source/profile, native building generation at all three anchors, and original road/bridge generation along three inter-city corridors (measured on a dedicated server; frames in the sprint report). No populated, furnished or inhabited city claim.**

The world uses The Lost Cities' [1.20.1 predefined-city registry](https://mcjty.eu/docs/mods/lost-cities/asset_structure), packaged as an original Forge low-code mod. This avoids building thousands of structures by hand. Version-targeted semantics matter: city centers are chunk coordinates, radii are in **blocks**.

## Proposed city anchors (all provisional)

| City | Center (block X, Z) | Radius | Generated massing / intention |
| --- | --- | --- | --- |
| Ironhaven | 8, 8 (chunk 0, 0) | 600 blocks | Dense industrial skyline, 9-floor cap |
| Valewood | 1992, 280 (chunk 124, 17) | 410 blocks | Lower-rise, more parks, 3-floor cap |
| Blackwater | 1048, 2024 (chunk 65, 126) | 570 blocks | Dense commercial towers, 11-floor cap |

These locations (a **close triangle**, see [the rework below](#october-15-rework-a-close-triangle-with-a-road-on-every-edge)), skyline caps, terrain seed, and styles are **test values**, not approved canonical geography. No city actually has its own hand-made factory, vineyard, residence interior or functioning NPC population yet. Lost Cities' own highway planner never connects these fixed centers (measured, see below); Core's corridor road feature does, for the two main roads. Furnished interiors and NPC homes still require asset work.

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
4. Create a fresh world, using seed `5908`, the reference seed in `corridors.json` (see [the rework](#october-15-rework-a-close-triangle-with-a-road-on-every-edge) for why; the earlier `579` and `20261014` do not suit the new layout). Confirm all three centers, terrain transitions, safe spawn, roads and chunk performance.
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

## October 15 rework: a close triangle with a road on every edge

The first prototype put the cities on a line (Blackwater - Ironhaven - Valewood): Valewood to Blackwater was about 3,000 blocks (12 minutes on foot) with no road. D-048 and the October 16 brief want a close triangle with three practical bridges, so `scripts/plan_triangle_layout.py` now derives the three city centres and corridors from the radii and the **edge gaps** (the road length between two city circles). Nothing is hand-placed: `cities.json` and `corridors.json` are generated from that plan, and `--check` fails when they drift.

| Edge | Edge gap | Road length | On foot (4.317 blocks/s, static) | City centre to city centre |
| --- | ---: | ---: | ---: | ---: |
| Ironhaven - Valewood | 1,000 | 1,016 | 3.9 min | about 7.8 min |
| Ironhaven - Blackwater | 1,100 | 1,122 | 4.3 min | about 8.8 min |
| Valewood - Blackwater | 1,000 | 1,027 | 4.0 min | about 7.6 min |

Outskirt to outskirt is inside the accepted 3-5 minutes; centre to centre is 7.6-8.8 minutes, so the Ironhaven - Blackwater leg sits just above the 6-8 minute aim and below the 10-minute hard maximum. This is a walking estimate, not a measured player trip; sprinting and the roadside content that should make the trip worth taking are not built. A gate on each road (`<from>_<to>_gate_<city>`) carries the checkpoint zone used by the underground-career law system.

### Seed choice, re-done

Moving the cities invalidated seed 579 (it now has a city underwater). `WorldSeedScanHarness` ranks seeds in two stages (a cheap climate-sampler pass over 30,000 seeds, then the chunk generator's real surface and ocean-floor heights for the best 503) and now scores: dry city sites, **water on a ring 150 blocks outside each city** (25-75%, so a city reads as an island or peninsula), per-road water in bridgeable gaps of 48-320 blocks, steep steps, and the **height and relief of each city site**. Seed 166 ranked first on water alone; a hosted survey then showed Valewood on `frozen_peaks`, and the scan (`list:` mode) measured its centre at height 219 with 126 blocks of relief, so the metric was added. Re-scored candidates:

| Seed | Score (lower is better) | Ring water I / V / B | Centre height I / V / B | Site relief I / V / B |
| --- | ---: | --- | --- | --- |
| **5908** | 222 | 25% / 50% / 50% | 63 / 70 / 65 | 10 / 7 / 21 |
| 5455 | 295 | 17% / 33% / 33% | 63 / 66 / 63 | 26 / 12 / 5 |
| 3936 | 490 | 33% / 33% / 17% | 78 / 89 / 88 | 31 / 26 / 28 |
| 166 | 7,699 | 25% / 33% / 58% | 63 / **219** / 79 | 37 / **126** / 11 |
| 579 | 1,001,230 (a city underwater) | 25% / 17% / 33% | 68 / 69 / 70 | 13 / 17 / 40 |

### Verification of seed 5908 (native dedicated server, cloud container)

`WorldRoadVerifier` generated the 362 road chunks and read the real blocks along every centreline (the same procedure as before):

| Road | Length | Samples read | Bridge/viaduct deck | Longest bridge | Longest open water | Deck missing | Headroom blocked | Steps > half block | Parapets missing |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Ironhaven - Valewood | 1,016 | 993 | 117 blocks | 45 | 40 | 0 | 0 | 0 | 0 |
| Ironhaven - Blackwater | 1,122 | 1,098 | 355 blocks | 181 | 85 | 0 | 0 | 0 | 0 |
| Valewood - Blackwater | 1,027 | 1,003 | 543 blocks | 181 | 151 | 0 | 0 | 0 | 0 |

All three roads report `continuous: true`, with at least 3 free blocks over water. Native city generation at the anchors: Ironhaven (taiga) 789 window blocks, Valewood (old-growth birch forest) 1,535, Blackwater (plains) 1,133. The same procedure on seed 166 gave **identical numbers locally and on the hosted runner**, which is the determinism evidence for this method. Limits: the numbers say the geometry is continuous and over real water; whether it looks good is decided by looking at the frames, and nobody has sailed under or driven along these bridges.

### Hosted reproduction and frame review of seed 5908

The hosted job "Forge smoke / lostcities" ([run 38827928567](https://github.com/Stoffe101/Underground-City/actions/runs/38827928567), head `3f4385d`) generated the same world on GitHub's runner and reproduced the local survey **exactly**: 993 / 1,098 / 1,003 centreline samples, bridge decks of 117 / 355 / 543 blocks, longest bridges 45 / 181 / 181, longest open water 40 / 85 / 151, no deck missing, no blocked headroom, no step above half a block, no missing parapet (222, 647 and 1,003 expected), at least 3 free blocks over water, and the window counts of the earlier table (789, 1,535, 1,133). The job asserts all of it, then a native client photographs the generated world.

All 15 downscaled review copies printed in that job's log were decoded (SHA-256 verified) and viewed: a top-down and an oblique frame of each city, and for each road a bridge from the side, a walker's view from the deck and a walker's view along the plain road. What the frames show: real Lost Cities buildings in all three cities (Ironhaven among snow-dusted taiga, Valewood around a fountain park, Blackwater with tall blocks); stone bridges on piers with connected parapets and lanterns over open water on the Ironhaven - Blackwater, Ironhaven - Valewood (over a small lake) and Valewood - Blackwater (over a wide stretch of water, with a beach and birch forest on the shore); and a ground road of stone slabs cut through taiga and birch forest. No floating building appears in the six city frames, which is a sample, not a proof. Known rough edges: the oblique city frames still spend about half the picture on sky, the road is a plain grey strip with raised step edges where the terrain rises, and trees touch the parapets in places.

Worth a human look on foot: the Valewood - Blackwater deck ranges from y 65 to y 122, so that road climbs a ridge somewhere along its length (the steepest step is two half blocks, so it is a slope and not a cliff, but nobody has walked it).

### Verification of the earlier line layout (seed 579; superseded by the rework below)

`WorldRoadVerifier` forces the road chunks to generate on worker threads (a chunk ticket; `ServerLevel.setChunkForced` generates on the server thread and trips the 60 s watchdog), then reads the **real blocks** on the centreline every block: deck present, three free blocks of headroom, no step above half a block between neighbours, a parapet on the bridge sides, and water with free blocks under the deck.

| Road | Length | Bridge/viaduct deck | Longest bridge | Longest open water under deck | Deck missing | Headroom blocked | Steps > half block | Parapets missing |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Ironhaven – Valewood | 1,400 | 343 blocks | 120 | 38 | 0 of 1,252 | 0 | 0 | 0 of 654 |
| Ironhaven – Blackwater | 1,295 | 422 blocks | 184 | 129 | 0 of 1,117 | 0 | 0 | 0 of 772 |

The hosted run [38676037902](https://github.com/Stoffe101/Underground-City/actions/runs/38676037902) (head `1718ee3`) reproduced the same numbers on GitHub's runners (1,252 and 1,117 centreline samples, longest bridges 120 and 184 blocks), which also shows the result does not depend on chunk generation order or hardware. Both reports say `continuous: true`; 284 chunks generated in 323 s on two software cores. Plan construction took about 3 s. The smallest measured free height between water and the bridge underside was 3 blocks (at shoreline ramps); the design value over water is 4. This proves the blocks exist and form a gap-free walkable line. It does **not** prove: that vehicles from a vehicle mod fit, that mobs behave on the road, that the roads look good (that is the separate frame review below), or that anything lives along them. Vanilla structures generated on top of the road centreline are repaired by the neighbour pass but a rare conflict could still appear; it would show as `deck_missing` in the next survey.

### Frame review of the earlier line layout (seed 579; superseded)

Twelve real frames were viewed (six city, six road). What the numbers did not show and the frames did: the parapet walls were unconnected posts (fixed: a second pass joins them), and one oblique bridge frame was nearly all sky or aimed at a few unmeshed blocks because the camera was high and steep or the deck was not loaded yet (fixed: a low, farther camera and a wait until the client holds the deck block). After the fixes the bridge frames show a long stone deck on piers over open water and over a river, connected parapets with lanterns, 4 free blocks under the deck for a boat, and a ground road cut through taiga and birch forest with slab ramps. Known rough edges: slab ramps and diagonal edges look stepped, trees overhang the parapet in places, and the Ironhaven oblique city frame still has a nearby rooftop in the foreground (the camera is higher and the render distance is now 8, which shows a much wider skyline). These are local container frames, not hosted artifacts, so nothing is recorded in `content/qa/reviewed-runs.json` for them.

### Seed choice for the earlier layout: water between the cities is a feature

Design intent (user, October 15): ocean or large rivers between the cities make the bridges pivotal, and a boat should be a valid alternative. Seed `20261014` put Ironhaven itself in the sea and the Ironhaven–Blackwater road 100% under water, so it was replaced. `WorldSeedScanHarness` ranks seeds in two stages (a cheap climate-sampler pass, then the chunk generator's real surface and ocean-floor heights along the real road centrelines) for: dry city sites, 6-35% water per built road in gaps of 48-320 blocks, and land elsewhere. Of 10,000 seeds, 302 finalists were re-scored with real heights. Seed `3130` (best of an earlier climate-only scan) was generated first and rejected: about half of its Ironhaven–Valewood road lay on water. Seed `579` (fifth of the real-height ranking) was then generated and measured. It has dry cities, two roads with long bridges over rivers and braided shoals, and an open **sea** on the unpaved Valewood–Blackwater edge (944-block longest water run), so travel there is by boat or around through Ironhaven. This ranking is a tool, not proof of quality: a seed's other terrain was not reviewed.

### Travel time for the earlier layout (D-048; see the rework for the current numbers)

Accepted constraint: routine inter-city travel should take only a few minutes. A static estimate at walking speed (4.317 blocks per second, no sprinting, no obstacles) gives about 4.8 minutes along the Ironhaven–Valewood road (1,251 blocks between city edges) and about 4.3 minutes along Ironhaven–Blackwater (1,116 blocks), inside the 3-5 minute aim. That is arithmetic, not a measurement: a real traversal benchmark (with the slab ramps, doors, a horse or boat, and two players) is still owed before a release. The Valewood–Blackwater edge is open sea in seed 579, so it must stay optional until a ferry or boat service exists.

### Waymarkers (October 18, D-078)

Every road has a stone-brick pillar with a lantern and a wall sign on each of its two faces every 250 blocks of open road: where it leads, how far and how long on foot. They are placed at run time beside the road when a player comes within 112 blocks (`RoadMarkers`, once each and remembered by the world), so world generation and seed 5908 are unchanged and worlds that already exist get them. A GameTest builds a real pillar beside a synthetic road and reads both signs, and a scripted-player run on a generated road read them as the client received them.

### Wayside shelters (October 18, D-079)

Each road also gets open-air rest stops, two kinds that alternate along a road (an 11 x 9 pavilion with a campfire, four seats, water and a cartography table, or a stone well under a small roof with two benches, a barrel and hay), each with a **road warden** who answers from the real data (where the road leads and how far, the nearest checkpoint, and the city law at each end). They are spaced so that no stretch of open road is longer than 450 blocks between one shelter and the next (or a city), never within 150 blocks of a city circle, and are placed at run time like the waymarkers (`WaysideShelters`, the `wayside_shelter` and `wayside_well` districts, `PlacedSites`) on level, dry, unbuilt ground beside the road, sliding up to 64 blocks along it and trying the other verge before giving up. `/uc_shelters list` (operator) says where each should stand and whether it was built. A shelter has no economy: no terminal, safe, stock or contract. GameTests cover the spacing, the site search on synthetic ground, the warden's answers and a real shelter with its tethered warden built and removed in a test world. A scripted player on the generated seed-5908 world hopped to every planned spot: Ironhaven - Valewood 2 of 2 shelters built, Ironhaven - Blackwater 2 of 2, Valewood - Blackwater 1 of 2 (the other found no level, dry ground within 64 blocks and was given up), each decided in under 100 ms; it then photographed some from the road and from under their roofs and talked to a warden. The first survey's photographs showed a well on a frozen sea (the ground scan treats ice as solid); sites over water or ice are now refused and the survey, repeated, gave the same counts with that stop moved onto land.

### What is still not built

Gate houses at the checkpoints (checkpoints are search zones, see [legal and underground careers](../design/legal-and-underground-careers.md)), NPC patrols and vehicle-mod compatibility on the roads. The checkpoint, `travel_modes` and `security` fields in `corridors.json` remain design data.

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
