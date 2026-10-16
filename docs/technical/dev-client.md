# Development client and server: `gradlew runClient` with the complete pack

Status: **works in the cloud (Linux, virtual display, software OpenGL), verified end to end; not yet run on Windows 11
or in IntelliJ IDEA** (no such machine is available to the cloud session). Section "Verify it on your own PC" says how to
confirm it in five minutes. This is a private development aid: nothing from a third party is committed.

## The short version

1. Install **JDK 17** (for example Temurin 17). IntelliJ: *Settings > Build, Execution, Deployment > Build Tools > Gradle >
   Gradle JVM* = 17.
2. Open the **repository root folder** (`Underground-City`) in IntelliJ IDEA as a Gradle project and wait for the sync.
3. Run the shared configuration **"Underground City - runClient (full dev pack)"** (top-right run dropdown), or in the
   Gradle tool window: *rpg-core > Tasks > forgegradle runs > runClient*, or from a terminal in the repository root:

   ```
   .\gradlew.bat runClient
   ```

That is all. The first run needs the internet and takes a while (Minecraft and Forge are prepared by ForgeGradle, about
170 MB of mods are downloaded); later runs reuse the Gradle cache.

What you get: Minecraft 1.20.1 with Forge 47.4.10 and **Underground City Core 0.1.0, Q Shop 1.8.4, The Lost Cities
1.20-7.5.5, TaCZ 1.1.8-hotfix2, TaCZ Blueprints 0.1.3, Modern Life 1.20.1-1.75, Immersive Vehicles 24.0.0, the Official
Content Pack V29 and the Official Automobile Pack V3**, and the client opens straight into a development world named
`UC-Dev-5908` (seed 5908, survival, cheats allowed).

## What happens when you press runClient

| Step | Where it is implemented | Notes |
| --- | --- | --- |
| Gradle finds out that this is a developer launch | `rpg-core/build.gradle` (`userLaunch`) | A plain `runClient` or `runServer` with none of the `uc*` harness flags loads the whole pack. Every CI harness flag keeps its old, narrower meaning |
| Jars are resolved from the original authors' pages | Modrinth Maven (`maven.modrinth:<slug>:<version id>`), Q Shop from its GitHub release | Exact versions are in `content/modpacks/dev-pack.json` |
| Every jar is verified | task `verifyDevPack` (SHA-512 from the manifest), `verifyQShopRelease` (SHA-256) | A mismatch stops the build with the file name; nothing is silently replaced |
| The game directory is prepared | task `prepareDevRun` | See below |
| Minecraft starts, the dev world opens | `DevWorldLauncher` (client), `-Duc.devWorld` | The world is created once and reopened afterwards |

`prepareDevRun` writes `rpg-core/run-dev/`: the two Immersive Vehicles content-pack jars into `mods/` (unmodified,
exactly as the engine expects), the original three-city world package (`underground-city-world-prototype.jar`, built
from `content/generated/world-prototype/pack`), the Lost Cities configuration that selects the three-city profile
(only when the files are absent, so your own edits survive) and a first-start `options.txt` that skips the
accessibility prompt. Saved worlds, `options.txt` and configuration are never overwritten.

## The two ForgeGradle problems, and how they were solved

PR #21 reported that Modern Life could not initialise and Immersive Vehicles registered no content-pack items in the
ForgeGradle development environment. Both are fixed without touching a third-party binary:

* **Modern Life** reads its configuration in its mod constructor. Forge's `ForgeConfigSpec.ConfigValue.get()` throws
  "Cannot get config value before config is loaded" only when `FMLEnvironment.production` is false, and FML also treats
  the system property `production` as production (read from the bytecode of `FMLEnvironment.<clinit>`). The run
  configuration sets `-Dproduction=true` when Modern Life is in the pack. The read is harmless in a real client.
* **Immersive Vehicles content packs** are found by the engine scanning the game directory's `mods` folder, not the
  classpath, so they were invisible as ForgeGradle runtime dependencies (23 "Unknown item mts:..." recipe errors).
  The manifest now marks them `"placement": "mods_folder"` and `prepareDevRun` copies the verified jars there.

Result in the development environment: all nine mods load; Immersive Vehicles registers 470 (Official Content Pack) and
137 (Official Automobile Pack) vehicle and part items; Modern Life registers 477 items and 4 entities; the Motorboat's
27-slot cargo round trip passes (`[UC-BOOT] boat_cargo=ok`).

## Choosing a different pack

| Command | Loads |
| --- | --- |
| `gradlew runClient` | the complete pack (sets `world`, `firearms`, `vehicles`, `boats`) |
| `gradlew runClient -PucPack=none` | Core alone |
| `gradlew runClient -PucPack=firearms,vehicles` | only those sets (add `-PucDevWorld=true` for the world package and the dev world) |
| `gradlew runClient -PucAutoWorld=false` | the full pack but stay on the title screen |
| `gradlew runClient -PucUsername=Alice` | another offline player name (two developers need different names) |
| `gradlew runClient -PucRunDir=run-other` | another game directory, for example a clean experiment |

## The dev world

* Location: `rpg-core/run-dev/saves/UC-Dev-5908` (a normal save; delete the folder to regenerate it).
* Seed **5908** is the accepted *development* seed (D-056). It is provisional: the three cities are positioned by data
  (`content/region-blueprints/cities.json`, `corridors.json`), the world package is generated from that data
  (`python scripts/emit_dev_world_pack.py`), and no gameplay rule contains a coordinate, so a future seed or layout is a
  data change plus a fresh world. Existing worlds are never migrated or rewritten (roads exist only in newly generated
  chunks).
* Operator helpers (cheats are on in this world): `/uc_dev tp ironhaven|valewood|blackwater`, `/uc_dev terminal`,
  `/uc_district build founders_row`, `/uc register <city> <career>`.
* The disposable world only affects new worlds that you create from this game directory; the Lost Cities profile
  selection lives in this run directory's configuration, not in your other Minecraft instances.

## A matching dedicated server

```
.\gradlew.bat runServer -PucAcceptEula=true
```

* `-PucAcceptEula=true` writes `eula.txt` for you. That is your decision to accept the Minecraft EULA; without the flag
  the server stops and asks you to edit `rpg-core/run-dev/eula.txt`.
* It loads the same pack (same pinned jars, same manifest), creates `rpg-core/run-dev/world` with seed 5908 and
  `server.properties` defaults (view distance 8, `online-mode` left at its default `true`).
* Gradle-launched dev clients use an **offline** profile, which an online-mode server refuses. To join a dev server from
  dev clients on a trusted LAN or VPN, set `online-mode=false` in `rpg-core/run-dev/server.properties`; to let normal
  launcher accounts join, keep it `true` and install the same pinned mods in that launcher
  (`python scripts/fetch_dev_pack.py --sets world firearms vehicles boats --output build/dev-pack/mods`, plus Q Shop and
  the Core jar from `gradlew build`).
* Not tested: two real clients on this dev server. The hosted `journey` job tests two clients against a Core-only
  dedicated server, not this pack.

## Verification so far

| Claim | Evidence |
| --- | --- |
| The wrapper (Gradle 8.14.3, checksum pinned) starts the build | cloud container; hosted job `Gradle wrapper is the official one` validates the wrapper jar |
| `./gradlew runClient -PucLaunchSmoke=true` launches the complete pack, creates the seed-5908 world, Lost Cities plans a city at Ironhaven's centre chunk, an Operations Terminal placed with `/uc_dev terminal` opens its screen | cloud container (Xvfb + llvmpipe), 4 min 2 s from a cold game directory: `[UC-LAUNCH] RESULT pass`, nine mods with versions, 470 and 137 content-pack items, seed 5908, screenshot reviewed |
| `./gradlew runServer -PucBootSmoke=true` boots the pack and the Motorboat's cargo round trip passes | cloud container, `[UC-BOOT]` facts |
| Hosted repeat of both | workflow `dev-client-smoke.yml` |
| Windows 11 / IntelliJ | **not tested** |
| Driving a car, sailing the boat, firing a gun, two players | **not covered by this launch smoke** (see the sprint report for what the gameplay tests prove) |

## Verify it on your own PC (five minutes after the first download)

1. `.\gradlew.bat runClient` (or the IntelliJ configuration). Expected: the game window opens and loads into the dev world.
2. In the world: `/uc_dev tp ironhaven` puts you in the city centre; `/uc_dev terminal` places a terminal two blocks
   in front of you; right-click it. The "UNDERGROUND // OPERATIONS" screen should open.
3. In the main menu's *Mods* list you should see Underground City, Q Shop, The Lost Cities, TaCZ, TaCZ Blueprints, Modern
   Life, Immersive Vehicles and both packs. Creative inventory search for `mts:` finds vehicles.
4. Report what differs: the first lines of `rpg-core/run-dev/logs/latest.log` and the Gradle output.

## Known limitations

* The first Gradle sync after cloning downloads and remaps Minecraft (several minutes) and needs about 4 GB of free RAM
  for the client (`-Xmx4G`).
* Modern Life prints a harmless "File not found config/modernlife-common.toml" on its first start (it creates the file).
* Immersive Vehicles prints "Invalid path in pack" for two pack-internal model names; they are in the author's pack.
* The development environment is not identical to a production client: `-Dproduction=true` is set and Core runs from
  its classes directory. `scripts/run_prod_boot_check.py` still boots the reobfuscated jars in a real server for that reason.
* Licences: most pack mods are All Rights Reserved and TaCZ's default gun pack is CC BY-NC-ND 4.0. Private development
  use only; see [dev pack](dev-pack.md).
