# Progression without monsters: where the drops come from (D-095)

**Status:** implemented as data and tests; verified against the **recipes that are actually loaded** in the cloud with Forge GameTests and, for Immersive Vehicles (which keeps its materials outside recipes), by a static scan of the pinned jars. **No person has bought, crafted or used any of it.** Prices and stock are first guesses.

The simulation world ([simulation-world.md](simulation-world.md), D-094) has no spiders, slimes, endermen or blazes. Anything the game makes from their drops would be a dead end unless it has another way in. The owner's rules: every item that approved gameplay needs has a **verified** route; respect rarity and balance (gunpowder is neither unlimited nor a creeper hunt); no progression through luck (D-053).

## The catalogue

`rpg-core/src/main/resources/data/underground_city/progression/monster_resources.json` lists the **roots** (items whose only reliable base-game source is a hostile creature), who drops them, what the game makes from them, and the **route** that replaces the creature:

| Root | Needed by (approved gameplay) | Route | Kind |
| --- | --- | --- | --- |
| `minecraft:gunpowder` | every TaCZ cartridge and rocket (25 recipes), Modern Life bullets, vehicle munitions, two Vinery/Kaleidoscope drinks | **TaCZ's own recipe `tacz:gunpowder`: 1 flint, 2 sugar, 3 charcoal make 3 gunpowder** | recipe |
| `minecraft:string` | all backpacks and upgrades, copper canteen, 17 Comforts hammocks and the rope, guitar, road marker, vehicle seat harnesses | exchange lines `string_valewood` (3 credits, 48 on the shelf, 4 per 12 minutes) and `string_ironhaven` (4 credits) | exchange |
| `minecraft:slime_ball` | sticky pistons (pickup and restock backpack upgrades), vehicle plastic, leads | `slime_ball_ironhaven` (14 credits, 16 on the shelf, one per 20 minutes) | exchange |
| `minecraft:ender_pearl` | the magnet and feeding backpack upgrades (and a painting) | `ender_pearl_blackwater` (**120 credits, 4 on the shelf, one per 90 minutes, one per order**) | exchange |
| `minecraft:blaze_rod` | **15 heavier TaCZ firearms and attachments** (Desert Eagle, AWP, M249, M107, Barrett M95, minigun and others; the minigun takes ten rods), blaze powder, two drinks | `blaze_rod_blackwater` (**200 credits, 3 on the shelf, one per 3 hours, one per order**) | exchange |
| `minecraft:blaze_powder` | vehicle engines, explosives, solid fuel, a cocktail | two powder from one rod by the base game's recipe | derived |
| `minecraft:magma_cream` | vehicle flares and smoke cans | slime ball and blaze powder, base-game recipe | derived |
| `minecraft:spider_eye` | Vinery's chenet wine, jellie wine and special mixture | `spider_eye_valewood` (18 credits, 6 on the shelf, one per hour) | exchange |
| `minecraft:bone` | Farmer's Delight bone broth; a few vehicle ornaments | cutting a ham with a knife (Farmer's Delight); hams come from livestock | by-product |
| rotten flesh, ghast tear, phantom membrane, nether star, shulker shell, wither skull, prismarine, nautilus shell, trident, totem | nothing the game offers as progression (dog food, decoration, brewing potions no design asks for) | none; the catalogue says so | none |

**The exchange lines are the existing finite, shared, slowly refilling regional exchange (D-091)**, not a second shop: the stock is shared by everyone, refills only while a human is online, is priced in Q Shop credits, and `capacity x price` is at most 1,200. They are sold at Operations Terminals in the cities. The six new lines sit beside 23 survival lines and 9 commodities, 38 of the 64 the game loads.

**What this does to the game.** A newcomer can buy the first pack (which needs string) the first hour. The magnet and feeding upgrades need a rare import, so they are mid-game purchases. The ordinary pistols, rifles and shotguns that Core's gunsmith orders ask for need no blaze rod (checked against the contract list), so the Gunsmith's early career is unaffected; the **heavy weapons are scarce on purpose**: a rod costs 200 credits and three are on the shelf. Gunpowder stays a production chain (sugar cane, a kiln for charcoal, gravel), so ammunition is neither unlimited nor a hunt.

## How it is enforced

1. **`ProgressionAuditGameTests`** (Forge GameTests, runs in every hosted pack job): reads every loaded recipe (vanilla, Forge, Core, every pinned mod; TaCZ's gun table through its own recipe type; smithing recipes through their ingredient fields; items made from each other, such as a gem and its block, taken as natural), follows each item back through the cheapest recipe to the roots, and fails if
   * a promised route is not real (the exchange line is not on sale, does not sell that item, is not in a city or is not a small finite shelf; the named recipe is not loaded);
   * an item of the approved mods (`underground_city`, `tacz`, `taczblueprints`, `sophisticatedbackpacks`, `thirstwastaken2`, `comforts`, `farmersdelight`, `vinery`, `farm_and_charm`, `kaleidoscope_tavern`, `modernlife`) needs a root that has no route and is not in the catalogue's exclusions (two exclusions: Farmer's Delight dog food, which needs rotten flesh, and Modern Life's prismarine furniture variants);
   * a backpack item needs different drops than `content/gameplay/backpack-policy.json` declares (`monster_inputs`), which makes a mod update that changes a recipe visible.
2. **`scripts/audit_item_sources.py`** (Python, in the repository checks; the existing audit extended): `MONSTER ONLY` and `BROKEN ROUTE` findings for a work order, a Core recipe or a backpack item that needs an unrouted root, a route whose exchange line is missing, sells something else or is a stockpile, and a derived root whose source has none. `tests/test_item_sources.py` proves each finding fires (mutation tests on a copy of the data).
3. **`scripts/scan_pack_for_monster_drops.py`** (static scan of the pinned jars): needed because **Immersive Vehicles keeps its materials in pack files, not recipes**, so the game test cannot see them. It found, in the official content pack: gunpowder in 19 definitions, blaze powder in 6 (engines), slime balls in 4 (plastic), string in 7, magma cream in 2, bones in 2, a nether star in 1 (a cosmetic mirror ornament, listed as allowed with its reason). Every other root has a route. The experimental SecurityCraft set (D-140) names a nether star in two recipes (the codebreaker and the level-3 reinforcer); it is listed as allowed because the trial removes every SecurityCraft recipe while the set is loaded.
4. **The production-style boot check** prints `monster_routes=ok` from the real jars; the server log of a run carries the profile line.

## What it does not show

* That a human finds the lines in time, or that the prices feel right. They are first guesses against wages of 24 to 32 credits for a first work order.
* Vehicle crafting itself (the pack files were scanned, not played).
* Items made from each other (a gem and its block, cobblestone and its slabs, hammocks of every colour) form cycles. The audit first works out every recipe that can be resolved from the world and only then takes the members that are still stuck as natural, so a member with a second recipe from outside the cycle (a hammock dyed from another colour, one of which is made from string) keeps what that recipe needs. A cycle whose only way in is a root would still be called natural; the jar scan and the route list are the second line of defence.
* A mod update that adds a new hostile-drop dependency fails `ProgressionAuditGameTests` and the jar scan on the next run, not before.
