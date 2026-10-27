# Development pack: real world, firearm and vehicle mods

Status (October 16, 2026): **every pinned mod boots together, in a production-style dedicated server and now also in the
ForgeGradle development environment, and `gradlew runClient` launches the whole pack.** Booting is not playing: what has and
has not been driven through a real client is in the verification table below and in
[the sprint report](../production/sprint-2026-10-15.md). The pack is a *development and private-play* aid, not a
distributable modpack, and nothing in it is committed. For the one-command launch see [dev client](dev-client.md).

`content/modpacks/dev-pack.json` is a machine-written manifest of exact Modrinth releases (project, version id, file
name, size, SHA-1, SHA-512, licence and, per entry, where the engine expects the file: `classpath` or `mods_folder`).
`scripts/resolve_dev_pack.py` rebuilds it from the Modrinth API (`--check` fails when the committed manifest and the live
data differ; CI runs it). `content/modpacks/dev-pack.pins.json` is the small hand-edited input: which version number of
which project.

| Set | Mods (pinned) | What it is for |
| --- | --- | --- |
| `world` | The Lost Cities 1.20-7.5.5 | the three fixed cities (MIT) |
| `firearms` | TaCZ 1.1.8-hotfix2, TaCZ Blueprints 0.1.3 | real guns, ammo, attachments; per-gun blueprint items |
| `vehicles` | Immersive Vehicles 24.0.0, Official Content Pack V29, Official Automobile Pack V3 | cars, trucks and a quad bike (the packs go in the game's `mods` folder) |
| `boats` | Modern Life 1.75 | a motorboat with a persistent 27-slot hold and fuel (needs `-Dproduction=true` in a ForgeGradle run) |
| `farming` | [Let's Do] Farm & Charm 1.0.14, Architectury 9.2.14 | **candidate** (opt-in, not in the default pack): crops with real growth stages, a stove, pots, silos and a scarecrow for the Farmer |
| `vintner` | [Let's Do] Vinery 1.4.41, Kaleidoscope Tavern 1.2.0, Architectury 9.2.14 | **candidate** (opt-in): vineyards, a press, barrels and racks (Vinery) and barrel-brewed spirits and cocktails (Kaleidoscope) for the Vintner |
| `qol` | Just Enough Items 15.56.0.205, Jade 11.13.3, AppleSkin 2.5.1, Mouse Tweaks 2.25.1, Controlling 12.0.2 (+ Searchables 1.0.3) | **candidate** (opt-in) interface helpers: a recipe viewer, look-at tooltips, food values on the hunger bar, drag and scroll item moving, a searchable key-binding screen; Core does not depend on them |
| `navigation` | Xaero's Minimap 26.6.0, Xaero's World Map 1.47.0 | **candidate** (opt-in, D-113): a minimap and a full-screen map for a three-island world; the Underground City status display starts below the minimap's default corner and can be moved (D-109) |
| `performance` | Embeddium 0.3.31, ModernFix 5.27.88, FerriteCore 6.0.1 | **candidate** (opt-in, D-113): a renderer replacement, faster start and lower memory; client first, ModernFix and FerriteCore also run on a server |
| `citytweaks_deferred` | Lost Cities Modern Tweaks 1.1.2 | **rejected for now** (D-113): in a fresh archipelago world Lost Cities logs a chunk generation error for 249 of the 507 city chunks probed with it loaded and for none without it; no launch, test or workflow names the set |
| `performance_deferred` | Entity Culling 1.11.3, ImmediatelyFast 1.5.5 | **parked** (D-113): both crash the ForgeGradle development client at load; no launch, test or workflow names this set, so nothing loads them. They stay pinned so the exact release is on record |

## Using it

- **Everything at once (private development):** `gradlew runClient` or `gradlew runServer` from the repository root; see
  [dev client](dev-client.md). Jars arrive as `maven.modrinth:<slug>:<versionId>` dependencies, `verifyDevPack` compares each
  to the manifest first, and nothing is bundled into the Core jar or committed.
- **CI and local tests (runtime-only, hash checked):**
  `gradle -p rpg-core runGameTestServer -PucPack=firearms -PucEconomyTest=true -PucRunDir=run-pack-firearms`
  (sets combine: `-PucPack=firearms,vehicles,boats`; add `vintner,farming` for the candidates, e.g. `-PucPack=world,firearms,vehicles,boats,vintner,farming`). `-PucPack=none` loads Core alone.
- **Private play outside Gradle:** `python scripts/fetch_dev_pack.py --sets world firearms vehicles boats --output build/dev-pack/mods`
  downloads the same files into an untracked folder (size and SHA-512 verified) so the owner can put them in a
  launcher. The owner is responsible for each author's licence terms.
- The `dev-pack-smoke` workflow runs the Core GameTests once per set, so optional-mod cases (real TaCZ gun NBT in
  loot, firearm contracts and blueprint rewards, the motorboat's cargo round trip) actually execute instead of
  skipping; `dev-client-smoke` runs `./gradlew runClient` itself.

## The client helper sets (October 21, 2026, D-113)

What was run, and what that does and does not show. All of it was run in Claude's cloud container with the ForgeGradle development client under a software OpenGL
renderer (`LIBGL_ALWAYS_SOFTWARE`, 1920x1080 virtual screen), so **timings and memory below are single samples for comparison between two runs on the same machine, not
a performance benchmark** and say nothing about a player's graphics card.

| Run | Sets | Result |
| --- | --- | --- |
| `runClient` launch smoke (world, firearms, vehicles, boats) | none of the helpers | reached the Operations terminal; 181 s from JVM start to the memory sample taken as the terminal opened, heap in use then 2,756 MB of 4,096 MB (a point sample, not a post-collection figure) |
| the same smoke with `-PucExtraSets=navigation,performance,qol,citytweaks` | all twelve helper mods (Lost Cities Modern Tweaks among them; see below) | reached the Operations terminal on a freshly generated archipelago world; 137 s to the same sample, heap in use 1,311 MB (also a point sample); ModernFix reported its own start at 129.9 s; `RESULT pass` (the screenshot was looked at: the terminal draws normally with the helpers loaded) |
| first attempts with Entity Culling and ImmediatelyFast in `performance` | | **both crashed the client at load** (see below), which is why they are parked |

**That boot did not generate a city, and a boot is all it showed:** the same set, run in a world where the player arrived next to Ironhaven, made Lost Cities log `Error generating chunk` (a `NullPointerException` in `ChunkDriver.correct`) 26 times in the first minutes, and the city probe with `world,citytweaks` logged it for 249 of 507 city chunks, against none for `world` alone. Lost Cities Modern Tweaks is therefore parked (D-113).

The two runs differ in more than the helpers (a shared machine, one run each), so the lower numbers are **not** attributed to ModernFix or FerriteCore.

- **Entity Culling 1.11.3** bundles its TRansition library as a nested jar; the ForgeGradle environment does not remap nested jars, so the client dies with `NoSuchMethodError`
  on a Minecraft method that exists only under its obfuscated name. It may run fine in a production-style client, which this project cannot launch in the cloud.
- **ImmediatelyFast 1.5.5** is a build labelled 1.20.4 that was published for 1.20.1 Forge; its `hud_batching` mixin cannot find `GuiGraphics.fillIntoBuffer` and the mixin transformer aborts.
- A populated city is exactly where entity culling matters, so both stay on the list for a retry when a production-style client can be run; until then the pack makes no performance claim about them.

**Not shown by any of this:** that a helper works while a person uses it (the minimap was not opened, the world map was not explored, JEI was not searched, Jade was not looked
through), that Xaero and the Underground City status display never overlap in a real world (only the default corners were read from the jar and the display was made movable), a
dedicated server booting with these sets (the manifest marks Embeddium, Controlling, Searchables and Mouse Tweaks as unsupported on a server; ModernFix, FerriteCore and Lost Cities
Modern Tweaks are meant to run on one, and no boot with them was run in this pass), or the frame rate.

**Single authority.** None of these mods owns a game mechanic: no second map or minimap beside Xaero, no second recipe viewer, no second HUD. Create is not used and none of them needs it.
Terrain and biome mods (Terralith, Biomes O' Plenty and similar) are deliberately not added in this pass: the archipelago is derived from vanilla noise and its islands, cities and
climate were measured on that terrain (D-104, D-112), so such a mod would invalidate those measurements. Not evaluated, not rejected.

## Licences, honestly

| Mod | Licence | Consequence |
| --- | --- | --- |
| The Lost Cities | MIT | no restriction on the runtime test |
| TaCZ (code) | GPL-3.0-only | runtime dependency only; nothing of its code is copied |
| TaCZ default gun pack assets | CC BY-NC-ND 4.0 | private, non-commercial use; no redistribution or modification |
| TaCZ Blueprints, Modern Life, Immersive Vehicles and both official packs | All rights reserved | private runtime tests only; no copy, no derived config, no redistribution |

The register in [`docs/legal/asset-register.csv`](../legal/asset-register.csv) and
[`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md) lists them. Core's own contract and loot data names these
mods' items by registry id only.

## What is and is not verified

Two different environments matter. The **ForgeGradle development environment** (`-PucPack=...`, `gradlew runClient`) runs the
game with official names and Core's classes directory; the **production-style boot check**
(`scripts/run_prod_boot_check.py`) installs a real Forge 1.20.1-47.4.10 server, puts the reobfuscated Core jar, Q Shop and
the pinned mods in an ordinary `mods` folder and boots it. The development environment used to differ in two ways, both
fixed without touching a third-party file: Modern Life's constructor config read (Forge throws only when
`FMLEnvironment.production` is false; the run sets `-Dproduction=true`) and Immersive Vehicles' content packs (found by
scanning the `mods` folder, so the manifest places them there).

| Claim | Status |
| --- | --- |
| Every pin resolves on Modrinth with the recorded hashes | implemented and tested (`resolve_dev_pack.py --check`, hosted job "Pins still resolve and match") |
| Core GameTests pass with the `firearms` set (real TaCZ and TaCZ Blueprints) | hosted and local runs: **All 54 required tests passed** (at that time) |
| Core GameTests pass with `vehicles,boats` in the development environment, Modern Life loaded | local run: 54 of 54 passed with Q Shop templates staged; Immersive Vehicles registers its pack items |
| Production-style boot with Core, Q Shop, TaCZ, TaCZ Blueprints, Modern Life, Immersive Vehicles and both packs | hosted and local: every mod loaded; Core loaded 43 work orders and the Founders Row district; TaCZ 11 items, TaCZ Blueprints 8, Modern Life 477 items and 4 entities, `mts` 634 items of which the Official Content Pack registered 470 and the Official Automobile Pack 137 |
| Development-environment server boots the same pack (`gradlew runServer -PucBootSmoke=true`) | local run: same counts, `boat_cargo = ok` |
| `gradlew runClient` reaches an in-game Operations Terminal with the complete pack | cloud container, 4 min 2 s from a cold directory; hosted repeat in `dev-client-smoke.yml` |
| The Motorboat keeps 27 slots of cargo (named, damaged and NBT-tagged items) through a save and load | production-style and development-environment boot: `boat_cargo = ok` |
| A player can drive a boat or a car, steer, refuel, or load cargo through the real UI | see the sprint report: automated scripted-player checks only where listed there; no human has driven anything |
| TaCZ firing, reloading, aiming in a client | see the sprint report; scripted input only |
| Q Shop, TaCZ and the vehicle mods work together in a client, or in two clients | one scripted client only; two clients **not verified** |
| TaCZ Blueprints default config for this world | partial: the gunsmith starter kit and contracts name its blueprint items; its own `enabledGuns` starter entries remain unwritten |
| Boat travel is a valid alternative route on the new map | design only unless the sprint report records a sailing check |
