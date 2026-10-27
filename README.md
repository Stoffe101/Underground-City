# Underground City

> **Status: pre-production | Private-play living-world RPG | Tested starter slice, not a finished modpack**

Underground City is a handcrafted-feeling, semi-modern Minecraft co-op RPG set among rival cities. The world is designed for **two human players** but should feel alive through citizens, businesses, faction leaders, patrols, quests, and simulated off-screen activity.

**Design influences, not bundled content:** Tale of Kingdoms (grow from citizen to influential leader), DeceasedCraft (dense modern-city atmosphere and exploration), life-simulation games (production and livelihoods), and faction RPGs (politics and consequences).

## What makes it different

- **A connected world:** a curated, finite playable region containing living cities, countryside, industrial areas, and contested territory. Existing licensed/permitted maps or configurable city generation may form its physical foundation.
- **A real civilian life:** farms, vineyards, smithies, factories, shops, property, deliveries, employment contracts, and taxation that contributes to city development.
- **Specialized careers:** Farmer, Vintner, Smith/Engineer, Gunsmith/Arms Dealer, and Hacker/Technician (Farmer and Vintner are separate careers since D-067). Each has levels, branching skills, research/blueprints, workstations, and meaningful contract work.
- **General skills:** driving, fitness, shooting, trading, social influence, and related transferable capabilities; separate from career levels.
- **Consequential factions:** leaders, reputation, police, investigations, smuggling, guarded borders, escalation, and controlled conflicts.
- **An evolving story:** shared co-op campaign; citizenship and meaningful player choices change the world.
- **Small-party first:** NPC economies provide missing professions; no progression gates require seven humans; no bankruptcy because everyone was offline.

## Getting oriented

| Document | Purpose |
| --- | --- |
| [Documentation index](docs/README.md) | All specifications and technical plans |
| [Project roadmap](docs/production/roadmap.md) | Development phases and exit gates |
| [First playable slice](docs/production/mvp-v0.1.md) | What prototype 0.1 must prove |
| [Decision log](docs/production/decisions.md) | Agreed direction versus open questions |
| [Mod audit](docs/technical/mod-audit.md) | Candidate dependencies, not verified installations |
| [Third-party policy](docs/legal/third-party-policy.md) | Attribution, permissions, assets, and mod distribution |

## Runtime baseline and candidates

This development slice targets **Minecraft Java 1.20.1, Forge 47.4.10, Java 17**, as requested and compiled/runtime-tested. Candidate mods include The Lost Cities, CustomNPCs-Unofficial, Lootr, TaCZ, Pufferfish's Skills, KubeJS, FTB Quests, Farmer's Delight, and Vinery. **The Create mod and its add-ons are excluded by owner direction (D-080).** Lightman's Currency was evaluated only as a design reference for shared accounts (D-081). All need exact-version dependency audits, client and dedicated-server smoke tests, and licensing checks before adoption.

Our custom **RPG Core** will make the authoritative decisions about players, professions, city economies, crime, contracts, and story progress. Content should be data-driven and tested; never bake fixed world coordinates or third-party item IDs into gameplay logic without adapters.

## Start here

1. Review [the design pillars](docs/design/vision.md) and [the first playable slice](docs/production/mvp-v0.1.md).
2. Validate versions and licensing through the [mod audit](docs/technical/mod-audit.md), and use the [asset register](docs/legal/asset-register.csv).
3. Test a clean client and dedicated server before importing maps or locking world generation.
4. Build one district, two careers, NPC buyers, a short quest, and a small legitimate gameplay loop before scaling.

**Development status:** The repository now includes an experimental Forge RPG Core source module and automated short-lived CI build artifacts, including local launcher-import starter archives. It is **not** a finished modpack and contains no selected city world, NPC population, third-party mod set or complete gameplay.

## October 15 sprint: legal and underground careers, a closer triangle, real guns and vehicles in the dev pack

Every career now has a **legal path and an underground path on the same character** (no alignment, no lockout): data-driven skill trees, 19 contracts, city laws, contraband classified by item and NBT, checkpoint searches with a seeded no-reroll die, heat and buyer trust, finite rare loot, a restricted fictional crop, and the Technician's interactive trace ([design and honest status](docs/design/legal-and-underground-careers.md), decisions D-049 to D-057). The three cities are laid out as a **close triangle with a built road and bridge on every edge** (about 4 minutes on foot between city edges), with **waymarker signs** every 250 blocks and **wayside shelters** (a pavilion or a well, and a road warden who answers from the real road and law data) at most 450 blocks apart, both placed at run time so the seed is unchanged (D-078, D-079; only a scripted player has seen them). A hash-pinned [development pack](docs/technical/dev-pack.md) adds real TaCZ firearms and blueprints, a motorboat with a persistent hold and Immersive Vehicles as **runtime-only** test dependencies; nothing third-party is committed. Not done: guard NPCs, vehicle driving tests, a populated district, a playtested economy. See the sprint report in [docs/production](docs/production/sprint-2026-10-15.md).

## October 16 sprint: the first playable experience (scripted, not yet human-tested)

`./gradlew runClient` (JDK 17, from the repository root) now launches **the complete development pack**: Core, Q Shop 1.8.4, The Lost Cities, TaCZ and TaCZ Blueprints, Modern Life, Immersive Vehicles and both official content packs, into a seed-5908 development world, with a matching `runServer`. It was verified on Linux in the cloud and on a hosted runner; **Windows and IntelliJ are not verified**. A new world gets **Founders Row** built near the spawn, with nine named citizens (a registrar, an employer for each career, three residents and a gate guard), and a **first-login screen** (home city, then profession) opens before anything else. An accepted work order shows on screen in a small tracker. Road checkpoints can have a post with a guard, and a vehicle's hold is searched together with its rider. Actual Minecraft clients were driven by a script with genuine keyboard and mouse input (a Quad driven along a road and across a bridge, a Motorboat sailed under a bridge, a pistol reloaded and fired, a checkpoint search, the Technician's trace, the registrar and an employer's terminal); no human has played any of it, survival fuel for a car is untested, and the road walk is reported in the sprint report. See the [sprint report](docs/production/sprint-2026-10-16.md) and the [development client guide](docs/technical/dev-client.md).

## October 18 sprint: Crews, shared homes, survival (scripted, not yet human-tested)

The owner's direction of October 18 widened the vision to a **modern open-world RPG sandbox** (GTA-style cities and crime, Schedule I-style production and growth, DeceasedCraft-style atmosphere and survival) and **excluded the Create mod and everything that needs it** ([D-080](docs/production/decisions.md)). New and tested by scripts only: optional **Crews** (press **K**): a roster with roles and permissions, a shared bank over Q Shop credits that survives crashes without losing or duplicating a credit, three finance modes, leasable **homes** with a server-side guard (the Row Flats, east of Founders Row), private containers, and a two-client journey in which the dedicated server is killed without a stop sequence and restarted; a recovery policy for wayside shelters; an item-source audit that closed a real dead end in the Gunsmith's top contract; and an opt-in `survival` mod set. **No human has played any of it.** See the [sprint report](docs/production/sprint-2026-10-18.md), the [Crew design](docs/design/crews.md), the [expansion plan](docs/production/expansion-plan.md) and the [Windows 11 playtest guide](docs/production/playtest-guide-windows11.md).

**Follow-up (PR #23, same day): a critical shared-finance bug fixed, and the foundation made testable end to end.** A Crew-funded exchange purchase used to move the bank's credits into the buyer's wallet before it knew the purchase would happen, so a refused purchase could leave a member holding the Crew's money; the bank's share is now a durable hold that never passes through a wallet and is spent only at the purchase's single commit point (regression test written first, nine deliberate mutations caught, crash-at-every-boundary tests). Also new: an **authenticated server pack** (`online-mode=true`, white-list) with a protocol-level check, a home guard that now covers pistons, hoppers, modded containers, frames, stands, placed entities, fluids, fire and mobs, survival goods **on sale** in cities and at **wayside provisions desks**, survival mechanics tested through the mods' own code, and a **combined journey** on a generated Lost Cities world with every pack set and two real clients through an abrupt server kill. Report: [pr23-playtest-readiness.md](docs/production/pr23-playtest-readiness.md). **Still no human has played; no real launcher account has joined.**

## October 19 pass: a simulation world, Sophisticated Backpacks, and a stall root-caused (scripted, not yet human-tested)

The owner's direction of October 19 made Underground City a **modern-life simulation, not a monster-survival pack** ([D-094](docs/production/decisions.md)). New and tested by scripts only: **no ordinary hostile creatures by default** in every launch (Core's `SimulationWorld`; the game is not on Peaceful, hunger, thirst and hazards remain, and `CLASSIC` is the documented opt-in), a verified route for every item that used to need a monster's drop ([monster-resources.md](docs/technical/monster-resources.md)), the backpack provider replaced by **Sophisticated Backpacks + Sophisticated Core** (all rights reserved, runtime-only hash-pinned downloads, a smaller and costlier tier ladder, only 16 of the mod's 71 switchable items enabled (21 since the October 20 re-evaluation, D-102), placed packs under the Crew's home rules; [backpack-economy.md](docs/technical/backpack-economy.md)), the Lost Cities long-tick stall **root-caused, measured and partly fixed** ([lost-cities-stalls.md](docs/technical/lost-cities-stalls.md)), and first-start stability of the new mods measured ([first-start-stability.md](docs/technical/first-start-stability.md)). The combined journey now has a real client buying, wearing and filling a backpack on the real screens, with screenshots at 720p, 1080p and 1440p. Report: [sprint-2026-10-19.md](docs/production/sprint-2026-10-19.md). **No person has played any of it.**

## October 20 pass: economy, housing, rent and progression with real effects (scripted, not yet human-tested)

The owner's brief of October 20 asked for an economy that holds together and progression that means something. New, and tested by scripts, Forge GameTests, a Python model and photographed screens only (**no person has played any of it**): a reproducible **economic model** that reads the real orders, prices, recipes, skill trees and property ladder and fails on a buy-sell loop, an idle profit or a vanilla delivery that out-earns real production ([economy-model.md](docs/technical/economy-model.md), D-098); a **property economy** where renting and buying are different things, rent runs on active household time only, an unpaid home loses comforts and never belongings, a Crew home has one bill, and an owner can fit three upgrades (Row Motel, Row Flats and Row House exist; seven more categories are priced only; [property-economy.md](docs/technical/property-economy.md), D-099); **skill trees with real effects** (four percentage effects each consumed by one system, capped, a crossover branch, trees up to six rows deep on a scrolling page; [skill-trees.md](docs/design/skill-trees.md)); the Farmer's **Cannabis production loop** run end to end by a test; **vehicle and boat price tiers** with a bicycle and a motorboat on sale (no fuel, repair or insurance exists; [vehicle-tiers.md](docs/technical/vehicle-tiers.md), D-101); a **checkpoint that searches what a person carries, including a Sophisticated Backpack**, and nothing else ([checkpoint-search.md](docs/technical/checkpoint-search.md), D-100); and a **fail-closed guard** against loading a world that holds Traveler's Backpack items ([backpack-migration.md](docs/technical/backpack-migration.md), D-103). The playtest guide has a new 40-minute section (6d) for rent, ownership, skills, the checkpoint and the vehicle shop. Report: [sprint-2026-10-20.md](docs/production/sprint-2026-10-20.md).

## October 20 to 21 pass: world, playability and modpack overhaul (scripted, not yet human-tested)

The owner's first human playtest found snow, merged cities, vegetation on buildings, an unreadable Trade page, mining through every wall and a world with nothing in it. New, and tested by scripts, Forge GameTests and a scripted client in a software-rendered display only (**no person has played any of it**): an **archipelago world type** (three islands, three bridges, no snow, the old world untouched, D-104), cities without vanilla structures or vegetation clutter (D-106), a **levelled landing in front of Founders Row** for a new world (D-107, D-111), **protected buildings with lockpicking** proven on two generated apartments across a restart (D-105), **named people on a roster** with a bounded cost (D-108), a **persistent status display**, a **civilian counter over Q Shop's own trades** and a Trade page that no longer overlaps (D-109, D-110), opt-in client helper sets and three parked mods (D-113). Not true yet: no police or witness response, two thirds of city buildings are sealed, no ports, harbours or gate guards, no cultivation depth, no human has played a first half hour. Start a new world with `gradlew runClient`; the old one opens with `-PucWorldLayout=legacy`. See [the sprint report](docs/production/world-playability-overhaul.md).

## October 21 pass: furnished cities, security, police, a hybrid economy and residents (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, probes of the game's own generator, photographs taken by a real client and one scripted real-client burglary (**no person has played any of it**): ten original furnished buildings across the three cities as an opt-in package; alarms, witnesses and police officers who really walk to a thief and arrest them, with goods from a forced safe marked as stolen and a clean lockpick that slips past an alarm; people who live and work in the furnished buildings by the clock; a world with no natural ore, ten finite raw-material shelves and one quarry that renews in active play time. The owner's privately licensed content could not be tested (it is not available to the cloud); SecurityCraft was evaluated from its jar and not adopted. Read [the sprint report](docs/production/living-cities-overhaul.md) and [the Windows playtest section](docs/production/playtest-guide-windows11.md) before believing any line of this.

## October 22 to 23 pass: gameplay completion, living world and integration (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, a Python model, probes of the game's own generator and scripted real clients (**no person has played any of it**): a first-player journey driven through a real client with real key and mouse input on a brand-new furnished archipelago world (every step from arrival to the return across the bridge passed in one fresh world on the final code, run 57, walked the whole way with real keys, including the 721 blocks from Founders Row to the road; two scripted shortcuts logged: a tree's position and an emptied shelf for a failure case; see the report); walkable ramps where the roads meet the cities (four of six road ends were dead ends on foot); retaining walls under raised furnished buildings that were drawn with their own furniture (sofas, beds, toilets, bookshelves up to 47 blocks tall) fixed, with an audit that reads a generated world back to find it; shopkeepers who sell over their counters, three more furnished buildings (a pub, a corner store, a bakery) with rooms and flats to let, homes in the cities on the Row's terms, residents and shopkeepers who walk to and from their street doors; a fence for stolen goods; alarm panels with an entry delay that a Technician can jam on the trace screen (worked through a real client: jammed, and a wrong code that rang); police tests for a moving thief, partners and several responses; a scarcity world in the economic model and a second regrowing resource site near Valewood; corner notices that no longer cover the status display or a timed screen; a porter for private 1.18.2 Lost Cities packs, proven on an invented fixture only (**no private content was available or ported**). Read [the sprint report](docs/production/gameplay-completion-pass.md) before believing any line of this.

## October 23 second pass: roadside places, road encounters and a SecurityCraft trial (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, the Python suite, the game's own generator and scripted real clients (**no person has played any of it**): six original **roadside places** beside the three roads (a diner, a farm with a farmhouse for sale, a salvage yard whose scrap heaps refill in active time, a freight depot, a motel with rooms to rent, a ruined cannery over a hidden den), built at run time in new archipelago worlds the first time a player comes near, on dry land beside the road, levelled and graded into the land with a driveway, each with a shopkeeper and something to do (D-138); **road encounters**, one or two to a crossing and always optional (a stranded driver, a courier whose letter pays in the city ahead, goods off a lorry, a spilled load), settled once through the work-order ledger (D-139); an opt-in **SecurityCraft trial** under Core's authority (keypad door, laser, siren, camera; recipes removed, its weapons and traps refused), worked through a real client and **not adopted** (D-140); beds that render in the furnished buildings, tills that are not televisions and a stocked corner store (D-141); the two road ends that were still dead ends on foot now have ramps that reach the city's ground (all six ends walkable in a new world's road check). See [docs/production/living-world-roadside.md](docs/production/living-world-roadside.md), [roadside.md](docs/technical/roadside.md) and [securitycraft-trial.md](docs/technical/securitycraft-trial.md).

## October 23 to 24 third pass: a Thief track, burglary, security and the crop's real name (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests (368), the Python suite and scripted real clients (**no person has played any of it**): the controlled crop is called **Cannabis** everywhere, with older saves loading the renamed items (D-142); a **Thief track** every career can follow, with its own level, points and a 16-skill tree on the terminal's Thief page (D-143); building **tiers**, **casing** a door, **searching** furniture for room-fitting loot marked stolen with the thief's id, and thirteen valuables only fences buy (D-144); **spool pins, raking, a Pro Pick Set and safecracking** (D-145); **police who chase only what they can see** and give up, cameras that Core watches, shops that open while their shopkeeper is in (D-146); a fence's door that opens at the fence's hours (D-147); roadside places built a slice per tick instead of in one long tick (D-148); cameras by tier in the furnished buildings (D-149); front doors under the eaves that are locked at last (D-150) and iron street doors that open for whoever the guard lets through (D-151). **SecurityCraft is not adopted (D-152):** viewing one of its cameras froze the server for two minutes in a measurement. Read back from generated worlds, the furnished buildings had lost their cameras, some carpets, shelves and garden plants to the order Lost Cities writes a part in; the generator now places only what survives (D-153). The archipelago's terrain function leaked memory (every chunk kept copies of it) and no longer does. Roadside places are of no city in what a player reads (D-154). **Police walk a beat** (D-155): an officer on the beat who sees a thief at a lock or breaking in is the response at once, for that thief alone (GameTested, and one scripted thief who picked a lock in an officer's sight was arrested, patrol run 2). The Northgate depot sends freight to the other cities (D-156; one scripted client was handed a manifest and paid 64 credits for it in Valewood, freight run 5). A lock now runs by the clock, so a stalled server cannot snap a pick. One scripted burglar went from casing a house to the first Thief skill through a real client (Thief run 12, `RESULT pass`, 22 logged shortcuts), and one scripted seasoned thief robbed the Blackwater club (heist run 8, `RESULT pass`: a Pro Pick Set on its high-security door, a camera that called the police, the wall safe cracked, the loot marked, an escape; 10 logged shortcuts), and one scripted Farmer grew Cannabis from bought inputs to a licensed delivery in Blackwater (Cannabis run 6, `RESULT pass`, 12 logged shortcuts). See [docs/production/thief-progression-pass.md](docs/production/thief-progression-pass.md) and [the Thief track and the burglary loop](docs/technical/thief-and-burglary.md).

## October 24 fourth pass: property intelligence, the Technician's trade, staff-only rooms and Cannabis processing (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients (**no person has played any of it**):

* **Property intelligence (D-157).**
  * Every city has easy, middling and hard targets: a building's tier moves within its class's grade by neighbourhood and city, and carries that tier's lock, stash and alarm.
  * A small card under the crosshair at a street door says what the place is and its tier.
  * Casing reads in layers by Thief skill, with the new Inspect key (I).
  * The Places screen (J) remembers what was seen and tracks a place with the status display's arrow.
* **The Technician's legal trade (D-158).**
  * Utility panels beside city buildings' street doors. Service calls (a shop's till down, a building's alarm off) come only to a city somebody is in.
  * A repair is a probing puzzle, and installs and upgrades a cable grid. Each is paid once to the first Technician.
  * An Electronics Bench makes the trade's parts and tools. A personal computer shows the city's service board.
* **Staff-only rooms (D-159).** While a shop is open, only its public floor is off-limits to a thief. Back rooms behind a door, and other floors, are staff only, at the household's risk. The door into them is locked to the shop floor.
* **Cannabis processing and the law on growing (D-160).** A Drying Rack turns two flower into one Dried Cannabis, which two new orders buy. Growing it in the open in Ironhaven or Valewood is pulled up by an officer who sees it, and only while its grower is online.

The underground side of the Technician was not built, and the Vintner, Smith, Gunsmith and business parts of the brief were not started. See [docs/production/professions-business-pass.md](docs/production/professions-business-pass.md).

## October 24 fifth pass: living population, workshops of the trades, shop supply and Xaero waypoints (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients (**no person has played any of it**):

* **Tracked destinations on Xaero's Minimap (D-161).** A tracked service call or place is one personal waypoint on Xaero's minimap, waypoint list and world map when Xaero is installed, gone when the work is done and restored once after a restart. The player's own waypoints are never touched, and the status display's arrow works for everybody (one scripted client with Xaero passed every step; no two-client run).
* **Populated cities (D-162).** The street population follows each area's hour and each city's lean, within a density setting (QUIET, NORMAL, BUSY). People come out of doors, go in at doors, browse open shops, talk in pairs and wear 200 original skins. People near a player go where that player can see them. Measured through real clients: 14 people in sight on an Ironhaven commercial street at noon against 4.5 before the pass, and 10 against 1.5 in Blackwater. Valewood's hills and the night streets are still thin.
* **Workshops of the trades (D-163).** Seven Core stations give the Farmer, Vintner, Smith and Gunsmith physical production on the active clock, with three tiers fitted from real parts and graded goods that pay more. Fifteen new goods and twelve contracts tie the trades together.
* **Shops buy from suppliers (D-164).** Six shops buy the matching workshop goods over the counter onto the shelf they sell from, and the city's customers empty it again.
* **The Technician (D-165, D-166).** A fault no longer shuts a shop or switches off an alarm, and faults come less often. An abstract back-channel job at a building's panel loops its cameras and quiets its alarm line for a while, at the risk of witnesses and officers (GameTested only).
* **Burglary fixes.** Redstone no longer holds a staff door open, a business's back-room barrels hold its stock, and a loot-value audit by tier was added.

One scripted Vintner made berry wine at a vat, fitted its second tier and sold three bottles over the Ironhaven pub's counter (workshop run 3, `RESULT pass`, 6 logged shortcuts). The Gunsmith's bench makes parts kits, not TaCZ firearms, and the business, hiring and property parts of the brief were not built. See [docs/production/living-population-professions-pass.md](docs/production/living-population-professions-pass.md).

## October 24 sixth pass: toward the First Fully Playable Sandbox Alpha (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients (**no person has played any of it**). The roadmap now defines the **First Fully Playable Sandbox Alpha** with twelve exit criteria and reconciles every phase and work package with what exists ([roadmap](docs/production/roadmap.md#milestone-first-fully-playable-sandbox-alpha)); the alpha is not proven until a person plays it.

* **The arrival hitch is fixed at its cause (D-167).** The server waited on Lost Cities' plan lock while its generation workers built new chunks; Core now records each chunk's plan as it is generated. Measured through a real client walking and teleporting into each city: no tick over 250 ms after the fix (1,150 ms before).
* **The Gunsmith builds real TaCZ firearms (D-168)** from parts kits at TaCZ's Gun Smith Table, registered with a serial or unmarked and restricted. One scripted real client went from the starter kit through the bench and TaCZ's own table screen to two registered pistols delivered to the armoury (gunsmith run 6); the runs before it found that TaCZ Blueprints never showed a learned gun on the table, which Core now works around.
* **Trade premises (D-169):** Ironhaven's commercial workshop and warehouse make work faster and open bulk orders, on the home's active-time rent; 8 of 10 property categories now have a building.
* **Crew contracts (D-170):** parts by career, each paid to whoever delivers it, and a bonus split equally among those who did, once (run with two real clients connected, server-driven).
* **A civic project (D-171):** the Valewood market is reopened by contributions and two stalls are built once in the world (one scripted real client, civic run 3).
* **The bonded store job (D-173),** the first raid, tracked on the status display and Xaero (one scripted real client, raid run 3); **beginner burglary pays something (D-172)**; **Trading and Social are earned in play (D-174)**; after dark people stand outside the bars.

See [docs/production/sixth-pass-alpha.md](docs/production/sixth-pass-alpha.md).

## October 25 seventh pass: first playable alpha readiness (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients. **The current branch and its recent gameplay systems have not received human playtesting.**

* **The first hour has no dead ends we could find (D-175):** goods nobody sold, a level-3 skill a level-3 player could not afford, missing recipe-book entries and a Gunsmith whose own registered pistol counted as unlicensed were each fixed where they start; stations and orders say where a missing part is sold or made.
* **Every rung of the property ladder has a building (D-176):** the Blackwater lofts and the Valewood villa.
* **Kit guns are named at TaCZ's table (D-177)** ("Registered Glock 17", "Unmarked Glock 17").
* **Shops behave (D-178):** a camera in hours says *Staff only* first, cameras ignore the pavement, keepers stand behind their own counters, and counter staff spawn first.
* **A batch is its maker's (D-180)**, a station's countdown runs on screen, Crew members see every part of a Crew contract, and the Skills page has a General tab.
* **Owner tools:** `/uc_prepare` (D-179) and PowerShell scripts for a Windows workflow, which have **not** been run on Windows.

Scripted real-client runs passed for the Smith, the Farmer, the Vintner and trade premises; the homes run hung at save-and-quit (fixed in the eighth pass) and the unmarked firearm run failed its last step. See [docs/production/seventh-pass-alpha-readiness.md](docs/production/seventh-pass-alpha-readiness.md).

## October 25 to 26 eighth pass: the living sandbox alpha (scripted, not yet human-tested)

New, and tested by scripts, Forge GameTests, the Python suite, the economy model and scripted real clients. **The current branch and its recent gameplay systems have not received human playtesting**, and the alpha is not complete until a person has played it.

* **The Smith is an engineer (D-182).** Precision parts are turned by hand to a drawing the first time (graded against the true minimum of passes; a section cut too far can be sleeved); the bench keeps a jig of the best grade and repeats it in batches; the cities' works and four shops run on machines that break down in a city somebody is in, and the Smith strips the drive, replaces worn parts and gears it to its plate for a one-time fee. A works standing idle stops its city's metal lines; a shop on a stopped machine charges more. Four new skills, assemblies other trades use (the Farmer's Geared Crank, depot and works orders), and every part also sold by a supplier. One scripted real client passed all 13 steps (engineer run 7), with a deliberate mistake, a sleeve, a cancel, a refused test run and a save and reopen.
* **Batches inside property are never anybody else's (D-184)**; outside, a batch is held on its maker's own play time, then is salvage and the maker is told.
* **Business hours by kind (D-185):** shops 08 to 18, cafes 07 to 20, restaurants 11 to 23, pubs 12 to 02, clubs 18 to 03, industrial suppliers 07 to 19; the glance card at a street door says when a place is open, and the bonded store raid still finds the warehouse shut at 04:00.
* **Exploration finds (D-186):** a battered crate at each roadside place and shelter gives each player their own find once (finds runs 1 and 2 through a real client); burglary loot stays shared and finite.
* **Leaving a single-player world can no longer hang the game (D-183)**, found by the homes run's save and reopen; homes run 4 then passed all seven steps.
* **The unmarked firearm run passed end to end** (unmarked run 3): unmarked pistols built on TaCZ's own table, restricted at a checkpoint, kept unmarked across a save, and sold to Dray.
* **Two players on the bonded store** are GameTested and ran server-driven with two real clients connected in the performance session.

See [docs/production/eighth-pass-living-sandbox.md](docs/production/eighth-pass-living-sandbox.md).

## October 26 to 27 ninth pass: critical fixes and the owner's private buildings (scripted, not yet human-tested)

Owner brief "C70 Integration + Critical Gameplay Fixes" and the owner's follow-up (D-187 to D-195). Tested by scripts, Forge GameTests, the Python suite, dedicated-server probes of the game's own generator and scripted real clients. **The current branch and its recent gameplay systems have not received human playtesting.**

* **First login lands the citizen in the city they chose (D-187)**, on safe ground, as their respawn, once (citystart runs 4, 6, 7, 8 and 9, the last two in the private C70-first world).
* **Doors follow the lease (D-188)**, **witnesses and the police answer a blow or an alarm (D-189)**, and **a breached house can be wrecked inside and is put back after 30 minutes of play (D-190)**, each through a real client.
* **`/uc admin` (D-191)**: credits through Q Shop, career and Thief levels, XP, operator points kept apart, general skills, relocation, an audit and pre-generation. Exact commands: [docs/technical/admin-commands.md](docs/technical/admin-commands.md).
* **The owner's private C70 buildings with an eight-mod furniture set (D-192, D-193)**, ported and wired C70-first in all three cities, tested in the cloud in private folders only. Nothing private is in the repository.
* **The islands generated ahead of play for test worlds (D-194):** `-PucPregen=islands`, `/uc admin pregen`, or `-Pregen` in the Windows scripts.
* **No missing blocks in the generated buildings (D-195):** an audit of every building against its data found and fixed blocks Lost Cities dropped, lakes dug into cities, a railway through a house, style characters the private pack relied on, and three block names Lost Cities read by their 1.12 meaning. The pre-generated C70-first world has 0 missing in our buildings and only 15 foliage-setting flowers in the C70 ones.
* **Two players on a dedicated server** registered, ran the console's commands, burgled a house and came back after a restart (duo run 3, 32 checks).

See [docs/production/ninth-pass-c70-critical-fixes.md](docs/production/ninth-pass-c70-critical-fixes.md).

## Ownership and license

**Copyright © 2026 Stoffe101. All rights reserved.** See [LICENSE](LICENSE). The license governs **original Underground City material only**. Minecraft, third-party mods, map downloads, artworks, and their trademarks remain subject to their respective owners' terms. Public repository visibility does **not** mean the project is open source. This project is intended for private play, not for sale or public modpack publication.

## Experimental build and automated tooling

The [RPG Core scaffold](rpg-core/README.md) compiles against Forge 1.20.1 and has an in-engine persistence GameTest. The [Forge Core workflow](.github/workflows/forge-core.yml) builds the mod and produces short-lived artifacts: an original-mod JAR and minimal importable starter archives for Modrinth/CurseForge-compatible launchers. The starter archives contain **only our original mod**, with no third-party code/assets or world.

Our [offline modpack auditor](scripts/audit_modpack.py) reads CurseForge/Modrinth pack exports or installed Forge/Fabric JAR metadata to identify package contents, duplicate mod IDs, and suspected missing dependencies. The [reuse-first policy](docs/technical/reuse-first.md) guides decisions about using existing mods and packs instead of expanding custom code prematurely.

## First-join mod candidate automation

The project now has two additional testing tiers before deciding whether to build a custom registration screen:

- [Original-author pinned CurseForge ZIP test profiles with original city/profession configuration](docs/technical/onboarding-screening-packs.md) for Class Selection Mod, Class Enhancement and Role Selection.
- [Isolated Forge GameTestServer smoke checks](.github/workflows/onboarding-compatibility.yml), one candidate at a time, using temporary third-party dependencies without shipping their binaries.

This is an **evaluation**, not a selected or confirmed compatible onboarding mod. City and career entries come from [our own catalog](content/onboarding/catalog.json), and we will use only one provider to own initial identity and rewards.

## Experimental three-city world content

We have original, data-driven city definitions for **Ironhaven, Valewood and Blackwater**, a [world-prototype generator](scripts/build_world_prototype.py), and a [CI workflow](.github/workflows/world-prototype.yml) that packages three Lost Cities predefined-city assets and three style variants as a reversible original-content low-code mod. A dedicated Forge survey generates real buildings at all three anchors, **builds two real roads with bridges between the cities** (an original Core worldgen feature; the decks ride four blocks above water so a boat can sail under, and water between the cities is deliberate: bridge or boat), and a native Minecraft client renders real frames of the cities and the bridges that we have actually looked at. **Measured limits:** Lost Cities' highway planner ignores predefined cities, so we build our own roads (see [world notes](docs/technical/three-city-prototype.md)); roads appear only in newly generated chunks of a fresh world; no inhabitants, furnished interiors, gate houses or placed suppliers exist. See also [burglary/property design](docs/design/burglary-and-property.md).

## City connections, rival supply chains, and respawning home loot

The [inter-city progression plan](docs/design/intercity-progression.md) ties advanced equipment/career tiers to trips between all three rival cities. A [three-corridor network](content/region-blueprints/corridors.json) is real for its two highways (generated roads and bridges, measured in a dedicated server); the third edge is open sea in the reference seed, so it is a boat trip or a detour through Ironhaven. Two slices are live: tier-2/3 recurring orders require rival-city exchange visits recorded from a terminal placed inside a city circle, and [property restocking](docs/design/gradual-property-restock.md) runs in one real container, the **Household Safe** (shared stock, lockpick-forced, vigilance), which is not yet placed in generated buildings.

## Recurring regional supplies, not just one-time city unlocks

Nine specialist commodities, twelve crafted products and twelve recurring work orders are **live in Core** as original items and datapack data ([design](docs/design/recurring-supply-chains.md), [integration guide](docs/technical/economy-integration.md#regional-exchange-recurring-orders-and-rival-city-gates-october-15-2026)). Each city's terminal exchange sells only its own three goods from **finite shared stock** that replenishes during active play; every batch consumes goods, so the next order needs another purchase. Prices, stock and city are server-owned, purchases use the single Q Shop wallet and the prepared-settlement journal, and order margins are guarded at 1.3x-2.1x. Not yet: supplier NPCs/warehouses, permits and convoys, NPC production behind the shelves, and TaCZ item rebinding (untested). The Python [supply simulator](scripts/simulate_regional_supply.py) remains a planning model only.

## One criminal/civilian computer terminal

The planned [in-world PC](docs/design/terminal-and-underworld.md) is a common interface for profession contracts, costly regional imports, time-limited crash recovery events, price-guided black-market sales with physical single-use dropboxes, and later gang/warehouse/police raids. Five source-validated property loot profiles allow rare firearm **components** in suitable locked residential safes and risky buildings alongside items for the other professions. Source-level transaction tests prevent duplicate sale/payout, illegitimate pickup, overgenerous loot and basic shipment retry abuses. **Advanced underworld operations, TaCZ weapon drops, burglary chests, crash locations and raids remain unimplemented**; the working artifact is a Python transaction model with future Forge integration to be selected reuse-first.

## Placeable PC and screenshot-based UI QA

The Forge RPG Core now registers an original compact **Operations Terminal** block (`underground_city:operations_terminal`) with a single-block static model, crafting recipe, item drop, and native five-tab GUI with server-backed work orders, the civilian shop and a city-gated regional exchange. It is opened by server-originated packet on right click. Contracts accept and consume real vanilla goods, pay Q Shop credits, award career XP, and unlock later work. Advanced shipment/market/raid actions remain unavailable. Rather than modify protected furniture/PC mod assets, the code keeps the model replaceable and can work alongside optional decor/programmable computer mods.

The [visual QA pipeline](docs/technical/visual-qa.md) starts an actual Forge client in a virtual Linux display, captures 36 integrated surfaces (the three steps of the first-login registration screen, terminal tabs, the skills page, the contract filter, the Technician's trace, installed Q Shop screens) at two GUI scales, validates dimensions and missing/duplicate screenshots, and uploads short-lived screenshot galleries. **Other mod interfaces are not yet installed or covered**, listed in `content/qa/gui-coverage.json`. Gallery artifacts expire in one day; a dedicated cleanup workflow removes them immediately **after review is confirmed**. Real aesthetic/interaction judgement still requires inspection of actual PNGs. The visual build isn't claimed passing unless its real-client jobs finish successfully.

## First real starter shop integration (trial-only, October 2026)

A Q Shop **1.8.4** optional dependency is now pinned and integrated with the original one-block Operations Terminal's Imports tab. If Q Shop is installed with the authored starter template, the PC can open a real **civilian** trading window. The server verifies a nearby placed terminal and a registered citizen; the request cannot select an arbitrary shop or remote city supplier. The shop uses Q Shop credits and low-tier **vanilla** buy/sell goods only. **It is not yet an integrated regional trade/black-market/raid system**, and regional permissions remain unimplemented. Two actual cloud clients have bought/sold through the native UI and retained their values after dedicated restart. Original templates are installed safely with `python scripts/install_qshop_templates.py --server-root <new-server-root>`, with no overwrite of existing saved shops. Read [October 19 test readiness](docs/production/oct19-test-readiness.md).

## October 14 integration pass

The [real starter economy](docs/technical/economy-integration.md) now connects twelve data-driven vanilla profession orders, persistent reservations, shared bounded employer demand, actual item consumption, Q Shop payments and career-level unlocks to the PC. A prepared-transaction journal supports recovery and rejects repeated turn-ins. Q Shop is the sole live wallet; no second economy has been added. An OP can place one real named vanilla supply clerk beside a PC with `/uc_clerk`. The clerk opens the same validated terminal and uses vanilla routines when supplied a bed/workstation.

The visual client captures 36 surfaces per viewport, including the first-login registration screen, contracts, the regional exchange, skills, the trace screen and installed Q Shop screens. Actual screenshot review identified and corrected clipped shop labels. A [fresh-world installer](scripts/install_world_prototype.py) stages original city assets and a source-checked Lost Cities 7.5.5 profile without overwriting saves; native buildings are verified at all three anchors; road connectivity and inhabited-city quality remain unverified. See [October 19 readiness](docs/production/oct19-test-readiness.md) for measured evidence and remaining tests. This is a working starter slice, **not a finished three-city modpack**.

## October 15 continuation pass

Fixed the one red hosted check (the native city-tour client), added the regional exchange with recurring multi-item orders and rival-city gates, the Household Safe, a Lost Cities highway survey (finding: no native road links the fixed cities), original generated roads and bridges between the cities, a seed ranking that treats water as a feature, five new GUI states, and a regional purchase click in the two-real-client journey. Twenty-six dedicated GameTests, 18 GUI states per viewport and the journey pass. Details and honest limits: [October 19 readiness](docs/production/oct19-test-readiness.md), [testing](docs/technical/testing.md) and [Claude project instructions](CLAUDE.md).

