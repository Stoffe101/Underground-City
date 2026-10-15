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

| Claim | Status |
| --- | --- |
| Every pin resolves on Modrinth with the recorded hashes | implemented and tested (`resolve_dev_pack.py --check`) |
| `firearms` set resolves and downloads with hash verification | verified in the cloud container (`fetch_dev_pack.py`) |
| Core GameTests pass with the `firearms` set loaded | see the PR's CI evidence; the real TaCZ/Blueprint cases run only here |
| The motorboat keeps 27 slots of cargo (named, damaged, NBT items) through a save and load | GameTest in the `vehicles` job; read from jar bytecode first |
| A player can drive the boat or a car | **not tested**; no real-client driving test exists |
| Immersive Vehicles and Modern Life boot together with Q Shop and TaCZ | **not verified** |
| The vehicles and TaCZ Blueprints default config for this world | **not written**; TaCZ Blueprints needs a shipped config to default-unlock a starter gun and its file name has not been confirmed |
| Boat travel is a valid alternative route on the new map | design only; the new water layout is surveyed by the seed scan, but no one has sailed it |
