# Ninth pass: critical gameplay fixes and private building integration (October 26, 2026)

Owner brief: "Underground City: C70 Integration + Critical Gameplay Fixes". Decisions D-187 to D-192.

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
| Carpets on attic beams missing | most C70 houses (8 to 10 a house, 0.2% of their blocks); in 4 of 66 houses none are missing | not found. Lost Cities has no code that touches carpets; the support under them is there | **open** (known issue) |
| Mushrooms on the paving round one General Store | one chunk | the game's own vegetation | none needed |

Our own furnished buildings in the same Valewood probe: the bakery (14 copies) and the villa (3) match their data exactly; the General Store (13) has only the mushrooms outside.

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

## 9. The new screens at the owner's sizes

The ninth pass's notices and the glance cards were captured by the capture harness at 1920x1080 with GUI scale 3 and at 3440x1440 with GUI scale 2 (the owner's screen), and looked at.

The notices were Welcome, two "Police searching" variants and "The police stood down". The glance cards included the new "To let, locked: K, Home to lease".

Results:
* No text is cut and nothing leaves its panel.
* At 1080p GUI 3, four notices stacked at once (the worst case) partly cover the right edge of the Operations screen. That screen is not a timed one, which the notices are allowed to cover.
* At 3440x1440 GUI 2 they sit clear of it.

## Runs in progress

This section lists only runs that have finished, with their result. A run that has only started is not recorded here.
