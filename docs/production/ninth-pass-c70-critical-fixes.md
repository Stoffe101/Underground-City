# Ninth pass: critical gameplay fixes and private building integration (October 26, 2026)

Owner brief: "Underground City: C70 Integration + Critical Gameplay Fixes", and the owner's message that followed it. Decisions D-187 to D-195.

**The current branch and its recent gameplay systems have not received human playtesting.** Every result below comes from scripts, Forge GameTests, the Python suite, a dedicated server or scripted real clients. None of it was played by a person. Each scripted run logs every shortcut it takes, marked `SCRIPTED` or `PREPARED`.

The owner's playtest findings come first and override any older test that called these systems working.

## 1. First-login arrival in the chosen city (D-187)

**Fixed, and verified through real clients.** Details: [city-arrival.md](../technical/city-arrival.md).

| Check | Evidence |
| --- | --- |
| The move is written once, never for a FakePlayer or a refused registration | `CityArrivalGameTests` |
| Only safe ground: water, a wall, a roof, air, leaves, magma, fire and a pond are refused | `CityArrivalGameTests` |
| A respawn is set only when the player has none; the world spawn is untouched | `CityArrivalGameTests` |
| Valewood | citystart run 4, 5 of 5: landed at 1623, 72, 224 in front of the General Store |
| Ironhaven | citystart run 6, 5 of 5: already at Founders Row, so not moved; respawn set |
| Blackwater | citystart run 7, 5 of 5: landed at 711, 72, 1568 at the Corner Store |
| Death and respawn | each run: `/kill`, then a real Respawn click, returned to the citizen's own respawn |
| No repeat after reconnect | each run: save, quit and reopen; not moved again, not asked to register again |
| In the C70-first world (private) | citystart runs 8 (Valewood) and 9 (Blackwater), 5 of 5 each, on copies of the pre-generated template from pregen run 8 with the private pack loaded: moved about 2,000 blocks onto dry open ground in front of the General Store and the Corner Store, which became the respawn; the same death, respawn and reopen checks passed |

Bugs these runs found and fixed:
* An objective hint longer than 160 characters crashed the client's packet decoder.
* "Already there" in Ironhaven set no respawn.

## 2. Doors and rentals (D-188)

**Fixed, and verified through a real client:** doors run 2, `RESULT pass`, 8 steps.

| Door | What happened |
| --- | --- |
| A Valewood house to let | Stays shut. The player is told "To let: 35 Church Lane. The door stays locked until you hold the lease: press K, open Home and choose Find a home to read the listing and lease it from here." |
| The card under the crosshair at that house | Reads "Home · 35 Church Lane · To let, locked: K, Home to lease". Run 1 found it showing a burglar's card until the door had been clicked; fixed in run 2, so a glance now registers the listing too. |
| A lived-in house | Stays shut. "A high-security lock. Lockpicks are no use on it: it needs a Pro Pick Set." (a wealthy tier) |
| A shop in its hours | Opens |
| The same shop at 23:00 | Stays shut. "Locked. A lockpick would open it." |
| The house to let, after a lease | Opens for its tenant |
| A household's own flat door in an Ironhaven block of flats, clicked from the stairwell | Stays shut. "Locked. Somebody lives here: Flat 1." |

Run 2 clicked the flat door in spectator mode, which a travel step had left on. **Doors run 3**, which forces survival mode and checks the mode at every click, passed all 8 steps again.

## 3. Policing and witnesses (D-189)

**Fixed, and verified through a real client:** police run 3, `RESULT pass`, 8 steps.

| Step | What happened |
| --- | --- |
| `/uc admin credits` from the client | 200 credits added through Q Shop and audited |
| A blow in sight of five passers-by (13:00, Valewood) | "Somebody saw you strike Fenn Greenhill and runs off to call the police." Officers were dispatched for the city's report time of 25 s and walked in. The citizen, standing still, was arrested for assault and fined Valewood's 40 credits (wallet 200 → 160, heat 4) |
| A blow nobody else saw (23:30, the person struck alone) | The victim went to the police themselves, later (50 s, twice the city's time). Officers arrived and arrested the citizen again (heat 8) |
| A quiet pick of an alarmed house's deadbolt, nobody in sight | Nobody called the police |
| A window broken inside that house | The armed alarm called the police (a broken window trips it for certain) |
| Back on the street | An officer arrived and arrested the citizen for break-in (fined 80) before the scripted run away began |

Not shown through a client in this pass:
* an escape;
* a patrol officer's warning for a first minor offence;
* a logout while wanted.

`CrimeGameTests` and `PoliceResponseGameTests` cover these.

Runs 1 and 2 found two faults, both in the scenario: the player had been left in spectator mode high above the street, and at night it picked a person indoors who could not be struck. Both are fixed, and every strike now logs whether it landed.

UI fixed after reading the run's photographs: the "Police searching" notice for a building was cut off at 1280×720 and is shorter now.

## 4. Burglary damage and restoration (D-190)

**Implemented, and verified through a real client:** wreck run 5, `RESULT pass`, 10 steps.

* A new citizen picked a Valewood house's deadbolt on the real lock screen.
* They broke a glass pane, a bed and a birch-plank inner wall with real held clicks. Each was gone, nothing dropped, and 4 cells were recorded: the bed is two.
* A held attack on the outer wall left it standing.
* They searched a piece of furniture and carried 1 stolen unit.
* The world was saved, quit and reopened. The record of 4 cells was still there.
* After the wait (shortened to one minute with `-PucPtRestoreminutes=1`; the game's own wait is 30 minutes of play), the city put back all 4 cells exactly. It shut and relocked the picked door ("1 door(s) shut and locked again"). The loot carried was unchanged.

Faults the earlier runs found, all fixed:

| Run | Fault | Fix |
| --- | --- | --- |
| 1 | The scenario called the server from the server thread and deadlocked for 20 s. The police scenario had the same nested call. | Both now make the call outside the server thread |
| 2 and 3 | A dark-oak plank wall was broken as an "inner wall". It was the outer wall under the eaves: the shell test asked for open sky right beside the wall | Air outside a wall now counts as outside when open sky is a few steps further out (a GameTest covers it) |
| 3 | The picked door stood open after the house was put back | A building put back now has its picked doors shut and relocked (a GameTest covers it) |

Run 4 failed once to break the upstairs bed and did not do so again. That step now logs, before it gives up, what the crosshair rests on and the game's verdict.

## 5. Operator commands (D-191)

**Implemented.** Exact commands: [admin-commands.md](../technical/admin-commands.md).

* `AdminGameTests` cover the point rules and the commands through the real command tree.
* Doors run 2 added credits with `/uc admin credits` through a real client.
* The two-player dedicated-server console session: see "Runs in progress".

## 6. Private buildings and the furniture set (D-192)

* **The furniture set** (`-PucPack=...,furniture`; all runtime-only, hash-pinned downloads, nothing committed):

  | Mod | Version | Source | Licence |
  | --- | --- | --- | --- |
  | EmbellishCraft | 1.20.1-7.0.0.0 | Modrinth | MIT |
  | Mapper Base (needed by EmbellishCraft) | 1.20.1-6.0.0.0 | Modrinth | MIT |
  | Paladin's Furniture | 1.20-v1.5.0 | Modrinth | LGPL-3.0 code, PolyForm Shield assets |
  | Another Furniture | 3.0.4 | Modrinth | MIT code, all rights reserved resources; Create is an optional hook only and is never installed |
  | Builder's Delight | 1.2.1 | Modrinth | all rights reserved |
  | Simply Light | 1.20.1-1.4.6-build.50 | CurseForge only | MIT |
  | MrCrayfish's Furniture (legacy) | 7.0.0-pre36 | CurseForge only | GPL-3.0 |
  | Farmer's Delight | already pinned | Modrinth | MIT |

  * MrCrayfish's legacy mod is a beta from 2023 and unmaintained.
  * The CurseForge files' hashes were computed from the CDN download, because CurseForge's API needs a key.
  * `fetch_dev_pack.py --sets furniture` downloaded all eight files and verified every hash.
* **Not adopted:**
  * Quark and Zeta: they add world generation, creatures and a second backpack, and replace vanilla recipes.
  * This Rocks!: it scatters rocks through the world.
  * Biomes O' Plenty: about 50 biomes, through TerraBlender.

  Their blocks in a private building become their nearest vanilla look.
* **Guards:**
  * EmbellishCraft's natural stone generation is switched off by a low-code package, `furniture-quiet`.
  * MrCrayfish's mail boxes deliver nothing (`FurnitureGuard`).
  * A furniture mod's full cube is judged as a wall by burglary damage, so the outer shell holds.
* **The porter:**
  * A block of a mod that is not loaded becomes its nearest look. Public rules (`content/integrations/private-import/block-lookalikes.json`) come first, then the block's shape in its material, checked against the real registry. Air is the last resort.
  * A block that a kept mod dropped by 1.20.1 becomes its sibling in the same mod.
  * Properties that 1.20.1 does not accept are dropped and listed.
  * Unit-tested on invented fixtures only.
* **The private wiring tool** (`scripts/private_wiring.py`), which writes only inside the private pack:
  * derives each chosen building's plan (doors, beds, stoves, seats, a home's box) from its own parts;
  * adds the building to a city's ring;
  * adds its security class and its listing.

### The owner's private buildings (C70), tested in the cloud

The archive was validated and extracted outside the repository, and the original is kept untouched. Everything below was produced and kept in private folders: the ported pack, the selections, the registry dump, the probes and the photographs. None of it is committed, uploaded or shown on a public page.

1. **The registry.** A disposable dedicated server booted the furniture set with Core, Lost Cities and the furnished cities. First start took 106 s. It dumped the real registry: 6,105 blocks, of which Paladin's Furniture has 1,809, EmbellishCraft 1,114 and Builder's Delight 903.
2. **The port**, from 1.18.2 to 1.20.1. All 1,174 palettes and 30,373 entries were checked against that registry, and the report says loadable.

   | Block | Count |
   | --- | --- |
   | Create blocks replaced by their look or by air | 3,063 |
   | Blocks of mods not loaded, total | 6,817 |
   | of those, a rule's look-alike | 3,416 |
   | of those, their shape in their material | 2,764 |
   | of those, air (machinery, cables, cars, rocks, bodies) | 806 |
   | Blocks a kept mod dropped by 1.20.1, given a sibling or a look | 373 |

   Also removed: 1 spawner, 27 creature markers and 1,339 loot tables. The chests stay, empty.
3. **Two faults found in a generated world, and fixed in the porter:**
   * The pack's world style named another mod's biomes and stopped the world from loading at all. World styles are now left out; ours chooses the cities.
   * The houses generated as a bare ground storey: the 1.18.2 floor counts never reach Lost Cities 7's roof parts. All 218 buildings' floors are now explicit, and 150 were raised. The next probe showed the roof.
4. **Wiring, inside the private pack** (`private_wiring.py`). One house was put in Valewood's middle ring, then one restaurant in its centre, with:
   * plans derived from their own parts;
   * a security class;
   * the house's listing;
   * the restaurant's counter, selling the pub's goods.
5. **A city probe in a new disposable world.** The private pack loaded (2,594 files), and its Valewood building file sat above the furnished package's. Seven copies of the house generated in 121 Valewood chunks, with no Lost Cities error.
6. **The house through a real client** (building runs 3 and 4, `RESULT pass`, 4 steps each). The game knows it:
   * class house, tier 3, locked;
   * a plan with 14 spots and one home;
   * a household of two.

   Its street door stayed locked to a stranger ("Locked. A lockpick would open it."). The real lock screen picked it for 75 Thief XP. In run 3 a resident at home saw the stranger and called the police. Photographs of the outside, from above and of every floor were inspected privately: the house is whole, with its pitched roof, its garden and its furnished rooms.
7. **A commercial building through a real client** (building run 8, `RESULT pass`, 4 steps). A restaurant: class storefront, tier 4, a shop plan with 39 spots, two workers. Its street door opened to a customer at noon. At midnight it was locked, and the real lock screen picked it for 100 Thief XP. Photographs were inspected privately.
8. **A first choice was rejected.** It was an open-fronted restaurant with no street door. The wiring tool had guessed its kitchen door as the street door, so its doors stayed unlocked (runs 5 to 7). The tool no longer guesses, and the guard now takes a plan's street door when a canopy hides the sky.

At that point no Underground City building had been replaced: the C70 buildings stood beside ours in one ring of one city of a private test world. The owner then asked for C70's buildings first (below). Not done: a comparison with the original 1.18.2 look.

### The owner's preference: C70 first (D-193)

The owner's second message asked for C70's buildings instead of ours. That is now the private pack's selection, wired by `private_wiring.py`. Counts only are given here, as the buildings are private.

| City | C70 share of the picks in its rings (centre, middle, edge) | Ours kept beside them |
| --- | --- | --- |
| Valewood | 55%, 75%, 89% | the General Store (arrival), the bakery, the villa |
| Ironhaven | 75%, 75%, 67% | the flats, the workshop, the warehouse, the pub |
| Blackwater | 78%, 74%, 67% | the Corner Store (arrival), the club, the apartments, the lofts |

* Our generic houses and offices are left out.
* 64 buildings are wired: 16 families with their rotations.
* 36 of them are listed to let or buy.
* Each building belongs to one city. A family that two cities use is split between them by its rotations.

The wiring tool gained:
* flats with a unit per storey that has a bed;
* trade premises;
* street doors under a canopy within the guard's covered step;
* `public_weights`.

Left out of the selection:
* buildings with no door at all (two warehouses, a car factory, a lab, a bunker, a petrol station): the guard could not lock them;
* the multi-chunk buildings.

## 7. Overlaps and missing blocks (owner request)

**Tool:** `scripts/building_audit.py`. It renders each generated building from its Lost Cities data for the floors the game chose, and compares it cell by cell with a `CityProbe` dump of the same chunk. It counts:
* blocks missing;
* blocks foreign to the building;
* blocks above its roof.

It is unit-tested on invented fixtures. Its reports give counts and block ids only, so they can be shared for a private pack.

What it found, and what was done:

| Finding | Where | Cause | Fix |
| --- | --- | --- | --- |
| Lost Cities draws its own retaining walls, streets, park ground, corridors and bridge supports around a building with the building's furniture | every private palette that took a style character: all ten characters, in 1,018 palettes | a building's palette is merged over the city style's in its chunk | the porter renames the style characters a private palette takes (993 parts renamed); a part that relies on the style's own meaning keeps it. Unit-tested |
| A railway ramp through a house: rails, iron bars and a stone-brick bed across its ground floor, its walls gone | one Valewood chunk of 225 probed, a C70 house | Lost Cities lets a building share a chunk with a railway one storey below the street, and draws the railway after the building | Core leaves such a lot empty (a railway within a storey of the street). A multi-chunk building flattened into single lots also gets Lost Cities' own single-lot rule (surface stations, highways). Verified by the next probe of the same world seed: that lot is empty, the ramp runs in the open, and that house's family audits clean apart from the carpets below |
| Carpets on attic beams missing, and flower boxes, a toilet-paper holder | most C70 houses: 374 of their 430 carpets, 32 flower boxes, 4 holders (410 of the 434 blocks missing in 68 building chunks) | Lost Cities' chunk writer keeps the blocks it writes in a cache and puts them into the chunk at the end. Each block it places updates its four neighbours' shapes, but asks the chunk itself, which does not hold the cached beam under a carpet yet. The carpet sees air below and becomes air (`ChunkDriver.correct` and `updateAdjacent`, read from the 7.5.5 jar) | Core keeps a block Lost Cities placed when that update would turn it into air; any other change of shape stands (D-195). Verified by the next probe of the same seed: all 430 carpets stand |
| A lava lake in a house's garden: lava in place of the paving, the fence, a terrace's steps and glass above it gone | one Valewood house (the other 24 missing blocks) | the game's lakes are placed after Lost Cities' buildings, and a lake digs its bowl wherever its spot falls; lava beside a wooden house is also a fire | on the archipelago, no lake touching a city's ring is placed (D-195). Verified by the next probe of the same seed: no lava, and the garden as its data says |
| Mushrooms on paving and roofs | 10 round the General Stores, 13 on C70 roofs, in 98 building chunks | the game's own vegetation, placed after the buildings | none (cosmetic; known issue) |
| A factory's lots empty: no building at all, the hillside or bare ground | all 68 lots of one C70 factory family, in Ironhaven and Blackwater (found by the three-city audit of the pre-generated world, below) | a 1.18.2 pack's city styles merge a shared palette under every building. The factory's ground part takes `H` from it, and this game's city styles replace the pack's, so `H` was defined nowhere: Lost Cities threw ("Could not find entry 'H' in the palette for part ...") and left the lot empty | the porter gives each building the characters its parts take from the pack's own style palettes when nothing else in its palettes defines them (618 characters to 218 buildings in the owner's pack). Unit-tested on invented fixtures |
| Diamond, gold and emerald blocks inside C70 buildings | 175 diamond, 224 gold and 158 emerald blocks in about 150 of the 622 C70 building chunks of the same world | the same fall-through: the pack drew spawners with `!` (air after the port), and Lost Cities' own `!` is its vault block (iron, sometimes diamond, gold or emerald). Likewise `z` and `=` became stone-brick stairs and smooth-stone slabs instead of stone and concrete | the same porter fix: each building now has the pack's own meaning of those characters |
| Shiny iron floors where the pack drew asphalt, and iron where it drew coke bricks, hempcrete, slag glass, ores and fuel | the owner's factory courtyards and 38 other blocks of two machinery mods the game does not load | the look-alike rules' catch-all for those two mods is an iron block, and it came before the more fitting rules (one coke-brick rule also had the wrong spelling) | specific rules before the catch-alls: asphalt is black concrete (slabs and stairs polished blackstone), coke brick bricks, hempcrete white concrete, slag glass grey stained glass, ores stone or deepslate, a metal ladder a ladder, and a fuel or other fluid air. Unit-tested |
| The audit itself: an inline palette in Lost Cities' object form was skipped, and a storey Lost Cities chose among several was compared with the first one | every audit before October 27 | `"palette": {"palette": [...]}` read as a list; Lost Cities picks one of the parts that fit a floor at random | both fixed: inline palettes are read in either form, and the storey that fits the generated blocks best is compared. A real defect still shows, because no candidate matches it. Unit-tested |
| Missing blocks that were never missing: one kind of block per chunk read as air (an apartment roof's 16 slabs, its 8 floor plates, a vent, two iron bars), a different kind in each world of the same seed | 248 of the 263 "missing" C70 blocks of the three-city audit below, and all 96 of an Ironhaven probe | the probe's dump gives each kind of block a glyph in the order it meets them, and its alphabet held the dot that stands for air (and the `=` of its legend): the 71st kind in a chunk was written as air | the alphabet holds neither, and the audit reads a dot that an older dump lists in its legend as that block or air, counted as neither. Unit-tested, with a guard on the alphabet |
| A short grass placed as a grass block, an empty flower pot as a potted cactus, a pumpkin as a carved one; a cottage's three floor ferns left out | every such block in every generated city, ours and C70: the factories' 22 grass tufts (a stand-in for another mod's dead grass) were raised grass blocks round each lot, half the cells of Valewood's lawns were grass blocks, 92 C70 and 43 of our flower pots held cacti; the cottages' ferns were missing | Lost Cities reads a palette block written without properties through Minecraft's 1.13 name fixer (`Tools.stringToState`, read from the 7.5.5 jar), and `grass`, `flower_pot` and `pumpkin` were the 1.12 names of those other blocks (the fixer run over all 499 names our packs write changes only these three). The ferns had been swapped for cornflowers to survive Lost Cities' writing order, and a cornflower is a small flower, which the foliage setting (D-106) leaves out | our pack generator and the porter write these three with an empty property list, which Lost Cities parses as written; the ferns stay ferns, now that Lost Cities' placed blocks are kept. An earlier audit had counted the cactus and the carved pumpkin as Lost Cities' own choice; it now reports them. Unit-tested, with a guard that no generated palette writes one of the three bare |

**The three cities of the pre-generated C70-first world** (pregen run 8, the port with the style characters and the asphalt rules fixed, made before the three names above; a probe of a copy reads every chunk within 15 chunks of each centre, 2,883 chunks; 829 building chunks):

| Buildings | Building chunks | Cells compared | Missing | Foreign | Of the missing |
| --- | --- | --- | --- | --- | --- |
| Ours (the bakery, stores, villa, flats, workshop, warehouse, pub, club, lofts, apartments) | 207 | 1,032,192 | **0** | 46 | none |
| C70, factories included | 622 | 3,360,192 | 15 | 3,164 | 15 lawn flowers in one office family's garden (tulips, bluets, lilies of the valley), which Lost Cities leaves out on purpose: the archipelago profile avoids foliage (D-106; Lost Cities' park saplings had grown into trees over streets and roofs), and that drops a building's own small flowers and saplings too |
| of which the factories | 142 | 654,336 | **0** | 282 | none (in run 6's world every factory lot was empty) |

The foreign blocks are the game's grass (2,690), tall grass, flowers, 94 mushrooms and 90 leaves of a tree in gardens and on lots; 14 mushrooms stand on roofs. Of the cells that hold another block than drawn (4,282 in C70 buildings), 3,124 are the factories' grass tufts placed as grass blocks and 92 the flower pots holding cacti (the row above), and most of the rest are the game's own underground blobs of dirt, gravel, granite, diorite and andesite reaching into the stone of a ground floor (a known issue). In run 6's world the same audit, as first read, gave 263 missing in the C70 buildings other than the factory: 248 were the dump's own fault (the row about the dot above) and 15 these flowers.

**Ironhaven on the fixed port** (probe r6b, a new disposable world, 441 chunks; the factory fix and the asphalt rules in): our 35 building chunks and the 122 C70 building chunks, factories included (649,894 cells), have **0 missing blocks**. The 444 foreign blocks are the game's grass, tall grass, flowers and 12 mushrooms.

**After the three names were fixed** (new disposable worlds): Ironhaven with port r8 (probe r8, 441 chunks): 0 missing in our 35 and the 122 C70 building chunks (846,336 cells); 968 grass tufts and 9 empty flower pots stand as drawn, and no palette error is logged. Valewood with our public pack alone (98 building chunks, 428,544 cells): **0 missing**, where the same probe before the fern fix had 39 (the cottages' cornflowers); 15 empty flower pots, 30 pumpkins and the lawns' short grass stand as drawn.

**Before and after, the same 225 Valewood chunks of one seed with the C70-first pack** (`building_audit.py`; every building chunk, its cells compared with its data):

| Probe | Building chunks | Cells compared | Missing | Foreign | Of the foreign |
| --- | --- | --- | --- | --- | --- |
| r5d (railway fix only) | 68 C70 | 308,583 | 434 | 386 | grass, tall grass and flowers in gardens; 13 mushrooms |
| r5e (+ blocks Lost Cities placed are kept) | 68 C70 | 308,583 | 24 | 389 | the same |
| r5f (+ no lakes in a city) | 68 C70, plus 30 of ours | 308,583 (C70) | **0** | 386 | the same |

Our own furnished buildings in probe r5f: the bakery (14 copies), the General Store (13) and the villa (3) have no block missing; the stores have the mushrooms outside. A probe is a measurement of one seed's Valewood, not of every chunk of every city; the three cities' audit is the next table.

## 8. The islands generated ahead of play (D-194)

`/uc admin pregen` and `-PucPregen=islands`. Details: [test-worlds.md](../technical/test-worlds.md). Islands mode is every city, then the rest of the islands' land, then the roads and bridges: 13,461 chunks with a 32-block margin.

**Pregen run 1** (a real client, a new public world, `world,furnished,scarcity`):
* It generated 10,073 chunks in about 36 minutes, at 4.8 to 6.9 chunks a second on four cores, with none let go unfinished.
* The process was then killed by the system for memory. Two probe servers of mine were still running beside it, each holding 3.4 to 3.8 GB after its work was done.

The run found two faults, both fixed:

1. **"Failed to store chunk" (4 times).**
   * A chunk was unloaded as soon as it was done, then loaded again a moment later as the next ring's neighbour.
   * In 1.20.1 that load hands back the very data still waiting to be written, and the write then fails.
   * One chunk could not be read afterwards and was generated again.
   * Pre-generation now keeps a finished chunk loaded for half a minute (at most 384 at once).
2. **A dedicated server that never left after `stop`.**
   * Paladin's Furniture 1.5.0 starts a file writer whose loop never ends, on a thread that keeps the process open.
   * That is why the probe servers held their memory.
   * Core now names such threads in the log 20 seconds after a dedicated server has stopped and saved, and the process leaves. This never applies to a client leaving a world.
   * Measured on the next probe: "1 thread(s) of other mods keep the process open: pool-10-thread-1 (com.unlikepaladin.pfm.runtime.PFMProvider...). Leaving." The build then finished normally.

**Pregen run 4** (a real client, a new private world with the C70-first pack, `world,furnished,scarcity,furniture`, a 4 GB heap):
* It ran out of Java memory at 10,770 of 13,461 chunks.
* Resumed from its saved progress (run 4b), it finished: 13,461 chunks, none let go unfinished, no failed chunk write. The rest took 713 s; the first 10,000 chunks were read from disk.
* The world is kept outside the repository as the template for the private runs below. It is never committed.

Class histograms of run 4b showed the live heap growing from 1.9 GB to 2.8 GB in three minutes. That is the third fault:

3. **Memory held by the generation itself.**
   * **The lingering chunks.** Each ticket keeps loaded the neighbours Lost Cities needs round it, eleven chunks each way. The old order, by distance alone, puts the next chunk anywhere round its ring. So the 384 lingering chunks were scattered, and together they held about 24,000 chunks in memory: 580,000 chunk sections and 1.7 million scheduled ticks.
   * **Lost Cities' building information.** Once a chunk's record has been asked for its four neighbours, it keeps them. Entries that expire from Lost Cities' cache therefore stay reachable through the entries that have not.
     * After 13,000 chunks there were 102,264 records, each with its own palette map: about 330 MB.
     * Lost Cities lets go of them only when the server stops.
     * The same happens in ordinary play, more slowly. It is listed under the known issues.
   * **Fix.**
     * The plan goes a tile of 8 by 8 chunks at a time, nearest the centre first. The tiles go ring by ring, and round each ring in turn, so what is loaded at once lies together.
     * A finished chunk lingers for 10 seconds, and at most 64 do.
     * Lost Cities' plans are let go every 500 chunks, as Lost Cities itself does when a server stops; it works them out again, the same, when asked.
     * A plan saved in the old order starts again from the beginning (the chunks already made load at once).

**Pregen run 6** (the same: a real client, a new private world with the C70-first pack, a 4 GB heap, with the fix): `RESULT pass`.
* 13,461 chunks in 3,044 s (51 minutes), none let go unfinished, no failed chunk write, no running out of memory.
* The old generation's use, sampled every 30 s: 1.7 to 2.1 GB throughout (median 1.85 GB, the most 2.13 GB). Run 4 had passed 3.5 GB of its 4 GB before it ran out.
* A first attempt (run 5) was stopped at about 1,000 chunks when the cloud machine's worker restarted; it was started again new, as run 6.
* The world was then audited (section 7). The style-palette fault it showed meant the template had to be made again from the fixed port (run 8).

**Pregen run 8** (the same, from the port with the style characters and the asphalt rules fixed, and with Lost Cities' blocks kept and no lakes in a city): `RESULT pass`.
* 13,461 chunks in 1,957 s (33 minutes), none let go unfinished, no failed chunk write, no running out of memory, and no "Could not find entry" palette error in its log (in run 6, 71 chunks had failed with it, all of them the factory's missing `H`).
* A first attempt was killed by a restart of the cloud container; it was started again new.
* The world is the C70-first template the private runs in sections 7 and 10 use. Its heap was not sampled: the sampler attached to a GameTest server that started beside it.

## 9. The new screens at the owner's sizes

The ninth pass's notices and the glance cards were captured by the capture harness at 1920x1080 with GUI scale 3 and at 3440x1440 with GUI scale 2 (the owner's screen), and looked at.

The notices were Welcome, two "Police searching" variants and "The police stood down". The glance cards included the new "To let, locked: K, Home to lease".

Results:
* No text is cut and nothing leaves its panel.
* At 1080p GUI 3, four notices stacked at once (the worst case) partly cover the right edge of the Operations screen. That screen is not a timed one, which the notices are allowed to cover.
* At 3440x1440 GUI 2 they sit clear of it.

## 10. The C70-first world through a real client (private)

Every frame and world below is private and kept outside the repository. Only what was seen is written here.

**Photo run 9** (a real client at 1920x1080; 24 frames: from the air on four sides, three street-level views and one from overhead, for each city). It used the C70-first template from pregen run 4, made before the D-195 fixes. Every frame was looked at:
* All three cities read as C70 cities. Valewood is a suburb of pitched dark-oak roofs, gardens, fences and stepped streets. Ironhaven and Blackwater are blocks of modern flats and offices with roof terraces. Streets, parks and roofs are whole.
* Furnished rooms render with the furniture set: sofas, tables and kitchens behind the glass.
* **The overhead frame of Valewood showed a lava pool beside a house.** This is the lake fault that the audit found (D-195), seen from the air.
* Two street-level cameras stood inside a building's balcony and lounge. The tour's fixed points are to blame, not the world.

**Photo run 11** (the same tour, on the template from pregen run 8: the fixed port, Lost Cities' placed blocks kept, no lakes in a city). Every one of the 24 frames was looked at:
* No lava anywhere in the three cities; the Valewood overhead frame shows the gardens whole where run 9 showed the lava pool. The only water seen in a city is a few small garden ponds.
* Ironhaven's and Blackwater's factories stand, with their walls, roofs and railings, where run 6's world had bare lots.
* All three cities read as C70 cities, as in run 9: Valewood's pitched dark-oak roofs, shop awnings, fences and stepped streets; the modern flats, offices and factories of Ironhaven and Blackwater.
* Again two street-level cameras stood inside a building (a lounge in Blackwater, a balcony in Ironhaven): the tour's fixed points, not the world.

A scan of the template's region files for lava above y 55: 144 chunks hold some, 98 of them inside a city's circle, and every one of those holds a single block (the same chunks and counts as the template before the fixes, so they are part of the buildings' own design). The lava lakes with dozens of blocks are all 180 to 304 blocks outside a city's edge, out on the islands, beyond the 160 blocks round a city where lakes are now refused.

## 11. Test evidence

* **Forge GameTests:** 444 of 444 in each local run from 160 to 165 (the last on eed98f3's tests), and in hosted CI on 0c3c01d, where every check passed.
* **Python:** 373 tests, `validate_repo.py`, the generated-pack checks (`emit_dev_world_pack.py --check` for every layout) and the private-content guard.
* **Hosted CI found, and this branch fixed:**
  * result checks that grepped `latest.log` after Forge had rolled it over at midnight (`scripts/ci_run_log.py`);
  * a back-room stock test and a plant-in-the-open test that depended on other tests' blocks in their chunk or column;
  * a failure step that printed only the plain run's log;
  * two crime scenes that struck before their passer-by could be found by the police's lookups (they now wait until every person they placed is found);
  * a Cannabis loop test whose floor for a flower's cost no rule of the game sets (a lucky grafting harvest cost 7.5; the loop now checks the harvest against the crop's own rules).
* **Hosted CI failures that were not this branch's,** each commented on the PR:
  * a Forge installer download and a TaCZ start-up crash, which passed on one re-run;
  * a native abort of the development client while it created its world, which passed on the next commit with the same change.

## Known issues

* **Lost Cities keeps every chunk's building information it has linked** (section 8). It is let go only when a server stops. Pre-generation lets go of it every 500 chunks; ordinary play does not, and in a long session that explores far, memory grows. It was not measured in play.
* **Mushrooms on paving and roofs** (section 7): the game's own vegetation, 23 in 98 building chunks.
* **The game's underground blobs reach into some ground floors.** Its dirt, gravel, granite, diorite and andesite blobs are placed into stone after the buildings, so a few cells of a building's stone ground floor or foundation are one of those instead (198 cells in 157 Ironhaven building chunks). Not fixed: it is cosmetic, and a gravel cell with nothing under it could fall.
* **Small flowers a building draws are left out** under the archipelago's foliage setting (D-106), and the photo tour's street cameras can stand inside a building.
* **Chunks generated before a fix keep their old blocks.** Carpets, flower boxes and lakes are fixed only in chunks generated afterwards. A world made before this pass keeps its lava pools and bare beams until those chunks are generated again.

## Runs in progress

This section lists only runs that have finished, with their result. A run that has only started is not recorded here.
