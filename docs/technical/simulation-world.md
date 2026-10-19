# The simulation world: no ordinary monsters (D-094)

**Status:** implemented in Core (`SimulationWorld`) and tested with Forge GameTests in the cloud; **no person has played a night in it yet**. Owner direction of October 19, 2026: Underground City is a modern-life simulation (GTA, Schedule I, DeceasedCraft), not a monster-survival pack. The people of the world are civilians, shopkeepers, farmers, guards and criminals; livestock, pets, wildlife and fish live beside them.

## What the player gets

| In the simulation world (default) | Still there |
| --- | --- |
| No zombies, skeletons, creepers, spiders, endermen, witches, phantoms, drowned, slimes, Nether or End monsters, illagers, wardens, or hostile creatures of any mod, at night, in caves, in buildings, in structures, from spawner blocks, from raids, patrols, reinforcements or jockeys | Difficulty is the server's own (the game is **not** on Peaceful): hunger and thirst drain, environmental hazards (fall damage, drowning, fire, lava, cold, poison) hurt, food and water must be bought or made |
| Lightning does not turn a villager into a witch or a pig into a zombified piglin; they stay what they are | `doMobSpawning` is **not** switched off: livestock, wildlife, fish, bats and ambient creatures, villagers and wandering traders spawn, breed and persist normally |
| Hostile creatures that an older world saved are removed as their chunk loads | Pets, wolves, bees, iron golems, polar bears and other neutral creatures are not "hostile" and are untouched |
| | **Scripted enemies still work:** armed criminals, guards, and the raids of a later release are spawned by Core code with the tag `uc_permitted_hostile`, or are of a type in the data tag `underground_city:permitted_hostiles` |
| | Combat with **people** (the security, gangs, a robbery) is untouched; firearms, ammunition, ranges and checkpoints are unaffected |

## How (one rule, three events)

A hostile creature is any `Mob` that is an `Enemy` or is in the `MONSTER` category. It is refused unless it is *permitted*:

1. `MobSpawnEvent.PositionCheck`: natural spawning and spawner blocks ask here before anything is created.
2. `MobSpawnEvent.FinalizeSpawn`: every spawn that carries a reason (structures, chunk generation, patrols, raids, reinforcements, jockeys, triggered spawns such as a warden, spawners) is cancelled.
3. `EntityJoinLevelEvent`: the catch-all for anything else that puts a hostile creature into a level, including a chunk that loads one a world saved earlier.
4. `LivingConversionEvent.Pre`: a conversion to a monster is cancelled, so the villager or pig survives.

**Permitted:** an entity with the scoreboard tag `uc_permitted_hostile`; an entity type in the data tag `underground_city:permitted_hostiles` (empty by default; a datapack or an operator can add, for example, `minecraft:pillager` for a scripted raid); and a deliberate spawn by an operator (spawn egg, dispenser, `/summon` without NBT), which is tagged so that it also survives the next restart. `/summon` **with** its own NBT carries no spawn reason, so add `Tags:["uc_permitted_hostile"]` to it. Core code that wants a scripted enemy calls `SimulationWorld.permit(entity)` before the entity joins the level.

## The profile

`world/serverconfig/underground_city-server.toml`, key `world.hostileMobs`:

```toml
[world]
	hostileMobs = "SIMULATION"   # the default; "CLASSIC" restores ordinary hostile spawning
```

* **The default is in the code**, not in a file. A developer launch, a dedicated-server pack, a single-player world, a LAN world and a world that existed before this release all behave the same without any file being present. A missing, reset or deleted file cannot bring the monsters back.
* Changing the value takes effect at once (no restart). Changing to `SIMULATION` removes the hostile creatures that exist in the loaded chunks and logs how many.
* `CLASSIC` is for an operator who wants the base game's monsters. It changes nothing else.
* Operators: `/uc_simulation status` (profile, what was kept out since the start, hostile creatures in loaded chunks now) and `/uc_simulation purge` (permission level 2).
* The server log carries one line at every start: `[UC-SIMULATION] hostile creatures: SIMULATION (...); difficulty ...; doMobSpawning true`. The production boot check and the combined journey read it after the restart.

## Why not In Control! (researched, not run)

[In Control!](https://modrinth.com/mod/in-control) (McJty, MIT) is the maintained, configurable way to do this with rules. Version 9.5.0 (September 2026; Minecraft 1.20.1, Forge 47+) was read from its jar: 372 KB, **no required dependency** (Lost Cities and KubeJS are optional), rule file `config/incontrol/spawn.json` with the phases `POSITION`, `ONJOIN`, `FINALIZE` and `DESPAWN` and the conditions `mob` (including `hostile`), `spawntype`, `spawner`, `structure`, `incity`, `inbuilding`, `scoreboardtags_any/all`, `dimension`, `biome` and others. It could express the rule above. It was **not adopted** because:

* the rule file must exist in **every game directory**, and a single-player or LAN world reads the *client's*; a deleted or reset file silently brings the monsters back (the owner's requirement is that the default is the same everywhere);
* it cannot see which of our own code spawned a mob except through scoreboard tags, which the Core rule uses as well, so it would add a second authority for the same decision;
* the rule is about 150 lines, testable with the game's own events and spawner (below), and needs no third-party binary.

It is **not excluded** for an operator who wants rule-level control: both only refuse, so installing it beside Core does no harm. Nothing here has been tested with In Control! installed.

## Evidence (cloud container, Forge GameTests, October 19, 2026)

`SimulationGameTests`, run with Core alone and with `firearms,vintner,farming,survival,boats` loaded (164 of 164 tests of that configuration passed; the survival set alone passed 161 of 161):

| Claim | How it is shown | Result |
| --- | --- | --- |
| Every hostile entity type of every loaded mod is refused for eleven automatic spawn reasons; no other type is | each registered `Mob` type is created and put through the position check, the finalise step and a level join | **37 hostile types, all of vanilla; no pinned mod registers one.** 42 non-hostile types checked: none refused |
| No monsters at night in a cave or a building | the base game's own `NaturalSpawner.spawnCategoryForPosition`, 400 attempts per dark room at midnight, with a player in the level | **simulation 0 / 0** (cave / building); the CLASSIC control produced **93 / 70** (96 / 67 in other runs), so the harness can see monsters |
| Spawner blocks | a zombie spawner block ticked 1,500 times with a player nearby | none in the simulation; some in CLASSIC |
| Livestock still spawns | the same spawner on a lit meadow in a biome that has livestock (found through the biome source) | **476** creatures in the simulation against 447 in CLASSIC |
| Villagers, pets, wildlife unaffected; breeding and persistence | created, saved to NBT and loaded back; a piglet bred through the game's own path | passed |
| Scripted enemies still possible and persistent | tagged pillager, vindicator, zombie and skeleton join, are saved and loaded back with the tag | passed |
| Existing hostile creatures handled | a zombie created in CLASSIC, the profile switched, `purge`: the plain one is removed, the tagged one stays | passed |
| Conversions | lightning on a pig and a villager: both survive; the CLASSIC control converts the pig | passed |
| Hunger is not disabled; not Peaceful | the difficulty is not Peaceful and exhaustion drains food in the test | passed |
| The default persists | the profile with no override is `SIMULATION`; the specification declares the key; the server log line is checked by the boot check after a restart (see [first-start-stability.md](first-start-stability.md)) | passed |

The existing home-guard test of hostile spawns was kept and now runs under the CLASSIC profile, because in the simulation the world refuses the spawn before the guard is asked.

## Known limits and consequences

* **Only a person can say whether the world feels alive without monsters.** The tests show absence; they do not show that the day is interesting. Night has no threat except what the survival rules and, later, people provide.
* Dungeon, mineshaft and fortress **spawner blocks and structures still generate** (cages, cobwebs, empty fortresses); the spawners simply produce nothing. Mineshaft cobwebs and chests remain as exploration finds.
* **The End dragon, the Wither, wardens, raids and piglin bartering do not exist** in the simulation: the End and the Nether remain places, without their monsters. Building the Wither pattern consumes its blocks and produces nothing. Netherite remains reachable by mining ancient debris.
* Progression that depended on monster drops has routes: see [monster-resources.md](monster-resources.md) (D-095).
* Modded hostile creatures are caught by category, not by name, but a mod that registers a hostile creature as `CREATURE` and implements no `Enemy` would not be seen; none of the pinned mods does.
* `Tags:["uc_permitted_hostile"]` is required for a `/summon` with NBT; this is a documented operator step, not a bug.
