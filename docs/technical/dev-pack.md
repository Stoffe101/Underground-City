# Development pack: real world, firearm and vehicle mods

Status: **pins verified, firearms and world sets resolve in Gradle; vehicles not yet booted together.** The pack is
a *development and private-play* aid. It is not a distributable modpack and nothing in it is committed.

`content/modpacks/dev-pack.json` is a machine-written manifest of exact Modrinth releases (project, version id, file
name, size, SHA-1, SHA-512 and licence). `scripts/resolve_dev_pack.py` rebuilds it from the Modrinth API
(`--check` fails when the committed manifest and the live data differ; CI runs it). `content/modpacks/dev-pack.pins.json`
is the small hand-edited input: which version number of which project.

| Set | Mods (pinned) | What it is for |
| --- | --- | --- |
| `world` | The Lost Cities 1.20-7.5.5 | the three fixed cities (MIT) |
| `firearms` | TaCZ 1.1.8-hotfix2, TaCZ Blueprints 0.1.3 | real guns, ammo, attachments; per-gun blueprint items |
| `vehicles` | Modern Life 1.75, Immersive Vehicles 24.0.0, Official Content Pack V29, Official Automobile Pack V3 | a motorboat with a persistent 27-slot hold and fuel; cars and trucks |

## Using it

- **CI and local tests (runtime-only, hash checked):**
  `gradle -p rpg-core runGameTestServer -PucPack=firearms -PucEconomyTest=true -PucRunDir=run-pack-firearms`
  (sets combine: `-PucPack=firearms,vehicles`). Jars arrive as `maven.modrinth:<slug>:<versionId>` dependencies and
  the `verifyDevPack` task compares each to the manifest first. They are never bundled into the Core jar.
- **Private play:** `python scripts/fetch_dev_pack.py --sets world firearms vehicles --output build/dev-pack/mods`
  downloads the same files into an untracked folder (size and SHA-512 verified) so the owner can put them in a
  launcher. The owner is responsible for each author's licence terms.
- The `dev-pack-smoke` workflow runs the Core GameTests once per set, so optional-mod cases (real TaCZ gun NBT in
  loot, firearm contracts and blueprint rewards, the motorboat's cargo round trip) actually execute instead of
  skipping.

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

Two different environments matter. The **ForgeGradle development environment** (`-PucPack=...`) runs the Core GameTests with the real mods on the classpath; it is stricter than a real server in one way (Forge throws, only in development, when a mod reads its config in its constructor) and different in another (it does not register Immersive Vehicles content-pack items). The **production-style boot check** (`scripts/run_prod_boot_check.py`) installs a real Forge 1.20.1-47.4.10 server, puts the reobfuscated Core jar, Q Shop and the pinned mods in an ordinary `mods` folder and boots it, so it exercises the same jars a launcher would.

| Claim | Status |
| --- | --- |
| Every pin resolves on Modrinth with the recorded hashes | implemented and tested (`resolve_dev_pack.py --check`, hosted job "Pins still resolve and match") |
| Core GameTests pass with the `firearms` set (real TaCZ and TaCZ Blueprints) | hosted run: **All 54 required tests passed**; TaCZ's mixins need `mixin.env.remapRefMap` in this environment (fixed in `build.gradle`) |
| Core GameTests pass with the `vehicles` set | hosted run passed, but Immersive Vehicles content-pack items did **not** register in the dev environment (23 recipe parse errors "Unknown item mts:..."), so this proves only that the engine loads beside Core |
| Production-style boot with Core, Q Shop, TaCZ, TaCZ Blueprints, Modern Life, Immersive Vehicles, the Official Content Pack and the Official Automobile Pack together | hosted run **passed**: every mod loaded; Core loaded 43 work orders with TaCZ present, 10 Farmer skills and the Founders Row district; registries held TaCZ 11 items, TaCZ Blueprints 8, Modern Life 477 items and 4 entities, `mts` 634 items |
| The Motorboat keeps 27 slots of cargo (named, damaged and NBT-tagged items) through a save and load | hosted production-style run: `boat_cargo = ok`. Modern Life cannot boot in the dev environment, so the GameTest for it skips there |
| A player can drive the boat or a car, steer, refuel, or load cargo through the real UI | **not tested**; no client driving test exists |
| Q Shop, TaCZ and the vehicle mods work together in a client, or in two clients | **not verified**; only a dedicated-server boot is |
| TaCZ Blueprints default config for this world | **not written**: its `world/serverconfig/taczblueprints-server.toml` has `enabledGuns` (documented as `tacz:gun/<name>`), `enabledAttachments`, `enabledAmmo` and a 5% structure-chest blueprint chance, but the accepted spelling and effect of a starter-gun entry was not exercised |
| Boat travel is a valid alternative route on the new map | design only; the survey proves real water under the bridges (see the three-city notes), but no one has sailed it |
