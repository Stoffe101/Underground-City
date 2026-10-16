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

## Using it

- **Everything at once (private development):** `gradlew runClient` or `gradlew runServer` from the repository root; see
  [dev client](dev-client.md). Jars arrive as `maven.modrinth:<slug>:<versionId>` dependencies, `verifyDevPack` compares each
  to the manifest first, and nothing is bundled into the Core jar or committed.
- **CI and local tests (runtime-only, hash checked):**
  `gradle -p rpg-core runGameTestServer -PucPack=firearms -PucEconomyTest=true -PucRunDir=run-pack-firearms`
  (sets combine: `-PucPack=firearms,vehicles,boats`). `-PucPack=none` loads Core alone.
- **Private play outside Gradle:** `python scripts/fetch_dev_pack.py --sets world firearms vehicles boats --output build/dev-pack/mods`
  downloads the same files into an untracked folder (size and SHA-512 verified) so the owner can put them in a
  launcher. The owner is responsible for each author's licence terms.
- The `dev-pack-smoke` workflow runs the Core GameTests once per set, so optional-mod cases (real TaCZ gun NBT in
  loot, firearm contracts and blueprint rewards, the motorboat's cargo round trip) actually execute instead of
  skipping; `dev-client-smoke` runs `./gradlew runClient` itself.

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
