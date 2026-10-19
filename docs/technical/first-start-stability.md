# First-start and restart stability of the pack (D-097)

**Status:** measured in the cloud container with `scripts/run_first_start_check.py` on October 19, 2026. 38 server starts, **none failed**. That is evidence that the current mods start; it is **not** proof that they can never fail on a first start (see the arithmetic below). No player's computer, no Windows machine and no real launcher was involved.

## Why this exists

The retired backpack provider, Traveler's Backpack 9.1.57, crashed one first server start in eight in the cloud: an `ArrayIndexOutOfBoundsException` in `TravelersBackpackConfig$Server.loadItemsFromConfig` while Forge reloaded the mod's freshly created config file from another thread (the second start succeeded). A server whose first start fails is the worst failure a player can meet, and Sophisticated Core was seen to rewrite its own configuration file during a first start, so the replacement was measured instead of assumed. The brief also said not to assume the crash is solved because the mod changed.

## What was run

`scripts/run_first_start_check.py` installs a real Forge 1.20.1-47.4.10 server, stages the reobfuscated Core jar, Q Shop 1.8.4 and the pinned mods of a set, and starts the server from a **clean, disposable folder** (new world, new `config`; `libraries` and `mods` shared read-only by link) with Core's boot smoke harness (`-Duc.bootSmoke=true`, which prints `[UC-BOOT] done` and stops the server). Each clean start is followed by populated restarts of the same folder. It records, per start, whether the server reached `done`, any crash report, how many configuration files Forge had to "correct", errors in the log, long ticks the watchdog would have counted, the Core profile line and the backpack policy line. A failed start is a failed row; nothing is retried.

| Series | Mods | Clean first starts | Restarts | Failed | First start | Restart |
| --- | --- | --- | --- | --- | --- | --- |
| `survival-seeded` | Sophisticated Backpacks + Core, Thirst Was Taken 2, Farmer's Delight, Comforts, Q Shop, Core, with the generated backpack settings in `defaultconfigs` (how the pack ships) | 8 | 16 | **0** | 49 to 51 s | 25 to 27 s |
| `survival-unseeded` (control) | the same, **without** the generated settings, so that the mods write every file themselves | 4 | 4 | **0** | 52 to 54 s | 25 to 28 s |
| `all-sets` | the world (Lost Cities, three-city profile), firearms, vehicles, boats, vintner, farming and survival sets together | 3 | 3 | **0** | 89 to 94 s | 42 to 43 s |

Every start: reached `done`; no crash report; no watchdog hit; the Core profile line `[UC-SIMULATION] hostile creatures: SIMULATION`; the backpack policy line `matches the Underground City policy` (survival series). The one error line in each survival start is Minecraft's own "Failed to request yggdrasil public key" (the container cannot reach Mojang's session servers); in the all-sets series the error lines are Immersive Vehicles' content-pack `MTSERROR` messages and Modern Life's "File not found", which are present in every start of those mods and do not stop anything.

**"Configuration file ... is not correct. Correcting"** appears 8 to 11 times in a survival first start and 19 times with every set, and **never in a restart**. Forge prints it for every configuration file it creates (its own `fml.toml` and `forge-server.toml`, Core's `underground_city-server.toml`, Q Shop's, Farmer's Delight's, `sophisticatedcore-common.toml`, `sophisticatedbackpacks-common.toml`, the world's `sophisticatedbackpacks-server.toml`, and so on); it is not specific to the backpack mods and it is not the Traveler's Backpack crash. The series counts it so that a change is visible.

## What it does and does not show

* **15 clean first starts of the new mods (8 + 4 + 3) and 23 restarts, no failure.** If the failure rate were Traveler's Backpack's observed one in eight, the probability of 15 clean first starts in a row would still be about 13 percent. So this does **not** prove the new pair is more reliable; it shows that no failure of that kind appeared in the same environment, and the one-sided 95 percent bound on the rate is about 18 percent. More starts are cheap, and the hosted `production-boot`, GameTest and journey jobs start the same mods again on every push; they are the continuing check (the failures of the last hosted run that were read in this pass were a log marker, a missing config copy and a halt race, none of them a mod crash; see [testing.md](testing.md)).
* The Traveler's crash happened under load (a Gradle build, other processes) and with the config reload racing the first load. The series ran one server at a time on an otherwise idle container; **it does not reproduce load**. The journey runner keeps its list of tolerated first-start races (`FIRST_START_RACES`) **empty** and counts every config correction, so a crash of this kind in a hosted run fails the run.
* Restarts here are clean stops. The abrupt-kill restart (no stop sequence) is covered by the combined journey ([testing.md](testing.md)).
* Pre-seeding the generated backpack settings made no difference to stability in this environment (both series: 0 failures); it matters for the *values* (a new world starts with the policy) and for the log line `[UC-BACKPACKS] ... matches ...`. An older world keeps the settings it was made with ([backpack-economy.md](backpack-economy.md)).

## Reproduce

```
gradle -p rpg-core build
python scripts/run_first_start_check.py --sets survival --clean 8 --restarts 2 --work build/first-start --tag survival-seeded
python scripts/run_first_start_check.py --sets survival --clean 4 --restarts 1 --work build/first-start --tag survival-unseeded --without-survival-config
python scripts/run_first_start_check.py --sets world firearms vehicles boats vintner farming survival --clean 3 --restarts 1 --work build/first-start --tag all-sets
```

Reports: `build/first-start/first-start-report-<tag>.json`; logs under `build/first-start/logs/<tag>/`. Nothing third-party is committed; the jars are downloaded from the authors' pages and hash-verified.
