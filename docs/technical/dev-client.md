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

## What the first minute looks like

1. The client opens straight into `UC-Dev-5908` (a brand-new world on the first launch). On that first start, and only in a world whose game
   time is still zero, the launch **builds Founders Row on level, dry ground near the spawn point and moves the spawn to its south gate**
   ([D-064](../production/decisions.md)); the log says `[UC-DEV] Founders Row built at ...`. An existing world is never touched.
2. The **first-login screen** opens (home city, then profession, then confirm). Confirm gives the career's welcome pack (a terminal, the
   starter tools and, for a Gunsmith, the TaCZ gun smith table and ammo workbench, a pistol blueprint and the materials for one pistol and its ammunition).
3. Walk up the lane. The **registrar** is in the building on the east side next to the gate; each career's **employer** has an office on the
   lane (the Technician's is in the dark-stone data office at the north end). Right-click an employer of your own career to open the terminal
   bound to their office; take a work order and the **tracker** at the top left shows what is still missing (press **O** to hide it).
4. The supply clerk, the exchange and the safes are in the stalls along the north wall.

To place the same district by hand in any other world: `/uc_district build founders_row`; remove it again with
`/uc_district undo founders_row confirm`; `/uc_district repopulate founders_row` puts back a citizen who was removed. Road checkpoint posts:
`/uc_checkpoints place` (one booth and one guard per zone, beside the road), `/uc_checkpoints remove confirm` for the nearest one.

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
  dev clients on a trusted LAN or VPN, set `online-mode=false` in `rpg-core/run-dev/server.properties`. **That turns
  authentication off**: anybody who can reach the port can type a Crew member's name and be that member (their Crew, bank,
  homes, wallet and progress), so it is for your own PC or a home network of people you trust, never for a server that is
  port-forwarded or public, and offline and authenticated identities are different people to the server (nothing migrates
  them: [identity.md](identity.md)). **For real launcher accounts use the authenticated server pack** instead
  (`python scripts/make_server_pack.py --sets world firearms vehicles boats --output build/server-pack --whitelist <names>`;
  `online-mode=true`, `enforce-secure-profile=true`, white-list; the [Windows guide](../production/playtest-guide-windows11.md#3b-authenticated-launch-for-real-accounts)
  lists the steps and what each player installs).
* Not tested: two real clients on this dev server. The hosted `journey` job tests two clients against a Core-only
  dedicated server, not this pack.

## Scripted-player scenarios (real client, real keys)

Several checks drive an actual Minecraft client with genuine keyboard and mouse events. Minecraft starts the JVM headless, so AWT's `Robot`
is unavailable; `scripts/xinput_helper.py` injects the events through the X server's XTEST extension instead (it needs `DISPLAY`, libX11 and
libXtst, both present with Xvfb). They are for the cloud container and for CI; they are not part of a normal launch.

```
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient -PucPlaytest=<scenario> -PucPtWorld=UC-PT-<scenario>
```

| Scenario | What it does |
| --- | --- |
| `input` | proves the harness: an injected W key moves the player |
| `register` | the first-login screen opens by itself; a mouse path through city, profession and confirm creates the citizen and delivers the pack |
| `walk` | walks each generated road from its first block with the real W key, steering only the heading; reports game time, waits for chunks, time on bridges, jumps and where it got stuck (`-PucPtRoute=<road>`, `-PucPtSprint=true`, `-PucPtLimitseconds=7200`) |
| `gun` | a TaCZ pistol: select, reload with R, aim with the right button, fire with the left, check the magazine, the carried ammunition and the damage to a target |
| `row` | builds Founders Row on a platform in the sky, switches the citizens' AI off so a click lands on the citizen aimed at, takes pictures, speaks to the registrar, an employer (whose terminal opens) and a resident with real right clicks |
| `posts` | places the checkpoint posts, stands at one and lets the guard search a pistol-carrying citizen |
| `tracker` | accepts a two-line order and follows the inventory on the tracker until it says the order is ready |
| `trace` | the Technician's trace on the real screen with the real keyboard: success, a wrong answer, walking away (Escape) and a timeout, each with its server-side consequence checked (ledger, kit, lock-down) |
| `car` | an Immersive Vehicles Quad on the generated road: its chunks are generated and held loaded first, then it is placed from its item with a right click, boarded with an empty hand from behind (right click), and driven with W held and A/D tapped (the scripted driver reads the vehicle's own orientation and cruises at 5 to 6 blocks per second) and braked with S (`-PucPtRoute`, `-PucPtMetres`, `-PucPtDrive`, `-PucPtBridge=true` to start before the road's first bridge and cross it). The vehicle is placed in creative mode, which makes it fuel-free |
| `boat` | a Modern Life Motorboat on the water beside the first bridge over water of a road: the chunks along the water are generated first, the boat is placed on the water beside the deck, boarded, and sailed under the deck steering from its own yaw (fuel and hold logged); the stall rule fails a run that stands still for 20 s (`-PucPtRoute`) |

Options are Gradle properties starting with `ucPt` (`-PucPtRoute=x` becomes `-Duc.playtest.route=x`). Reusing an existing playtest world keeps
its citizens, so use a world name per scenario. Reopening an existing development world makes Minecraft ask
"Worlds using Experimental Settings are not supported" (a mod-registered generator is not vanilla-stable); the launcher answers *Proceed*
for the development world only. (Until October 18 it only knew the title of the dialog shown when a world is *created*, so a second launch waited at
this one until somebody clicked; it now answers both, and a scripted run that reloaded a saved world passed through it.) A scenario result is one line, `[UC-PLAYTEST] RESULT pass` or the failures.

## Verification so far

| Claim | Evidence |
| --- | --- |
| The wrapper (Gradle 8.14.3, checksum pinned) starts the build | cloud container; hosted job `Gradle wrapper is the official one` validates the wrapper jar |
| `./gradlew runClient -PucLaunchSmoke=true` launches the complete pack, creates the seed-5908 world, Lost Cities plans a city at Ironhaven's centre chunk, an Operations Terminal placed with `/uc_dev terminal` opens its screen | cloud container (Xvfb + llvmpipe), 4 min 2 s from a cold game directory: `[UC-LAUNCH] RESULT pass`, nine mods with versions, 470 and 137 content-pack items, seed 5908, screenshot reviewed |
| `./gradlew runServer -PucBootSmoke=true` boots the pack and the Motorboat's cargo round trip passes | cloud container, `[UC-BOOT]` facts |
| Hosted repeat of both | workflow `dev-client-smoke.yml` |
| Windows 11 / IntelliJ | **not tested** |
| Driving a car, sailing the boat, firing a gun | scripted-player runs in the cloud container, see the scenario table above and the [sprint report](../production/sprint-2026-10-16.md); **not covered by this launch smoke** |
| Two players on this pack | **not tested** |

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
