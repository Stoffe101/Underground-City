# Opening an old world that holds Traveler's Backpack items (D-092)

**Status:** the behaviour below was **measured** on a production-style Forge 1.20.1-47.4.10 dedicated server in the cloud (`scripts/run_backpack_migration_check.py`, October 19 and October 20, 2026). **Nothing converts Traveler's Backpack items, and this repository does not claim that anything does.** Since D-103 (October 20) a dedicated server **refuses to start** on such a world unless the operator overrides on purpose, and the override makes a backup first (section "The guard"). Before that: a loud warning, a way to keep the items, and a way to get the world back from a backup. The owner's rule: Traveler's Backpack items in an existing world must not be deleted silently.

## What happens without the guard (the October 19 measurement)

The retired provider is **Traveler's Backpack 9.1.57**. The pack now uses Sophisticated Backpacks ([backpack-economy.md](backpack-economy.md)). A world that was saved with Traveler's Backpack installed (the opt-in `survival` set of the October 18 pack) and is opened with the current pack behaves like this on a dedicated server:

| Step | What was measured |
| --- | --- |
| 1. The old world, made with the retired provider (a placed pack, a chest holding a pack and 5 diamonds, a dropped pack) | everything present; the world's `level.dat`, one chunk and one entity chunk name the mod |
| 2. The same world opened with the current pack and **without** Traveler's Backpack | the server **starts without asking anything**. Forge logs `Forge Mod Loader detected missing registry entries` and, at error level, `There are unidentified mappings in this world - we are going to attempt to process anyway`. The pack in the chest is **gone at once** (the chest holds only the 5 diamonds), the placed pack is **air**, the dropped pack is **not in the world**. After the server stops (a save) the world files **no longer name the mod anywhere** |
| 3. That world opened again, this time with Traveler's Backpack | the chest still holds only the diamonds, the block is still air: **the loss is permanent once the world has been saved** |
| 4. A copy of the world made after step 1, opened with Traveler's Backpack | the pack with its marker tag is back in the chest, and the placed pack is there: **a backup restores everything** |

The dedicated server never asks for confirmation (no `/fml confirm` prompt appeared). What happens in a single-player world on a client was **not** tested; do not assume the client's confirmation screen protects you.

## The guard (D-103, measured October 20, 2026)

Core now refuses to load a world whose registry list names Traveler's Backpack while the mod is not installed. **How it was measured, and what the first try got wrong.** The first version threw an exception from the `MissingMappingsEvent` handler. The measurement showed that this does **not** stop anything: vanilla's reading of `level.dat` catches any `Exception`, logs it and carries on, so that server printed the refusal, then reached `Done`, ran, and saved the world **without the packs**: the guard failed open. The refusal now ends the process itself (a dedicated server calls `System.exit(1)` before it has read a chunk or a player file; its own shutdown hook does not exist yet, so nothing is saved). Re-measured on the final code with the same six steps:

| Step | Result |
| --- | --- |
| 1. The old world, made with the retired provider | a placed pack, a chest holding a pack and 5 diamonds, a dropped pack; `level.dat`, one chunk and one entity chunk name the mod |
| 2. Opened with the current pack, **default settings** | the log says `REFUSING TO LOAD THIS WORLD`, the server **never reaches Done and exits with code 1**, **every file of the world is byte for byte as it was** and no backup is made |
| 3. Opened with **`-Duc.acceptLegacyBackpackLoss=true`** | a copy of the whole world appears first in `uc-backups/world-before-legacy-backpack-loss-<time>` (its `level.dat` and its chunk still name the mod), then Forge's `missing registry entries` warning, then the server starts and the loss happens as before (the chest holds only the diamonds, the placed pack is air) |
| 4. That world opened again with Traveler's Backpack | nothing came back: the loss is permanent once saved |
| 5. The **automatic backup** from step 3 opened with Traveler's Backpack | the pack with its marker tag is back in the chest and the placed pack is there |
| 6. Control: the manual copy from step 1 | the same as step 5 |

**To get past the guard on purpose** (after reading the next section and copying the world yourself): add `-Duc.acceptLegacyBackpackLoss=true` to the server's JVM arguments (`user_jvm_args.txt` of a server pack), or set the environment variable `UC_ACCEPT_LEGACY_BACKPACK_LOSS=true`, or `-PucAcceptLegacyBackpackLoss=true` on a development Gradle launch. The override first copies the world and **refuses (exits) if the copy fails**. The backup leaves out `session.lock`, which the running server holds locked and which holds no world data.

**Not covered by the measurement:** a client (single-player) world: there the guard throws an `Error`, which vanilla's reading does not catch, so the game should stop with a crash report, but this was **not run**; a server started with `--world` or `--universe` (the override cannot find the folder to copy and refuses; the refusal without the override is unaffected); the backup copy on **Windows** (the lock file is skipped for the reason above, not tested there); a world that was already saved without the mod (its `level.dat` no longer names it, so the guard has nothing to detect, by design).

## What Core does about the warning

`LegacyBackpackNotice` listens to Forge's `MissingMappingsEvent` (which Forge posts before it rewrites anything), does not change any mapping, and, when an item or block of `travelersbackpack` is missing, logs three error lines at the start of the server and shows a red message to every operator (and the owner of a single-player world) who logs in:

```
[UC-BACKPACKS] THIS WORLD WAS SAVED WITH TRAVELER'S BACKPACK, WHICH IS NOT INSTALLED.
[UC-BACKPACKS] If it holds any Traveler's Backpack (in a chest or an inventory, placed in the world, or dropped), Forge removes it permanently at the next save
[UC-BACKPACKS] (about five minutes after the start, or when the server stops). Nothing is converted. Stop the server now, copy the world folder, and read docs/technical/backpack-migration.md.
```

The world may hold **no** Traveler's item at all: Forge's list names every item of a mod that was ever installed, so the notice also appears for a world that merely had the mod loaded. The experiment confirms the notice appears in step 2 and not in step 1 (where the mod is installed). `LegacyBackpackNoticeGameTests` covers which names count; the rest is the script's evidence. Core also logs an error if **both** backpack mods are installed at once, which is unsupported.

## How to keep the contents (manual; tested only as far as the steps above)

1. **Stop the server and copy the whole world folder** (and the `run-dev` folder if it is a development launch). This is the only thing that restores anything after step 3.
2. Start the world **once with the retired provider and without Sophisticated Backpacks**: the set `legacy_travelers` (`-PucExtraSets=legacy_travelers` on the Gradle commands; it loads Traveler's Backpack, Thirst Was Taken 2, Farmer's Delight and Comforts and never Sophisticated Backpacks). Log in as the player who owns the packs.
3. Take **everything out of every Traveler's Backpack** (worn, in chests, in inventories, placed in the world) into chests or your inventory, and pick up placed packs. The packs themselves cannot be kept: they are the item that disappears.
4. Stop the server normally (this saves), keep a copy, and start it again **with the current pack** (`survival` set). The log shows `[UC-BACKPACKS] the running Sophisticated Backpacks configuration matches the Underground City policy`, and the error notice above once more (the saved registry list still names the old mod, but there is nothing of it left to remove; after this save the notice stops).
5. Buy a new backpack (70 credits at any city terminal) or craft one, and move your things in.

What is **not** preserved: Traveler's Backpack's own items and blocks (the packs, their sleeping bags, tanks and upgrades), the contents of any pack you did not empty, and the fluid in its tanks. There is no tool that moves a Traveler's pack's contents into a Sophisticated pack.

## Why there is no automatic migration

Forge can remap a missing item name to another item (`MissingMappingsEvent.Mapping.remap`). That would turn a Traveler's pack into an *empty* Sophisticated pack: the contents of a Traveler's pack live in that mod's own item data, which nothing here reads. A silent remap would delete the contents while looking like a conversion, which is worse than the warning. If the owner wants a converter, it needs to read the old mod's data format and be tested against real packs with contents; it is not built.

## Evidence

* `build/migration/migration-report.json` of the runs (October 19: steps 1 to 4 of the first table; October 20: the six steps of the guard table), produced by `scripts/run_backpack_migration_check.py --work build/migration`; the script downloads the two pinned mod sets from their authors' pages, hash-verified, and commits nothing.
* A single scripted dedicated server in the cloud, one chunk area, one pack of each kind. **Not tested:** a world with many packs, a pack with real contents, a player's worn pack in `playerdata`, a client's single-player flow, a different Forge version.
