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

Run 2 clicked the flat door in spectator mode, which a travel step had left on. Run 3 forces survival mode and checks the mode at every click. Its result is recorded under "Runs in progress" below.

## 3. Policing and witnesses (D-189)

**Implemented. GameTested. A real client heard a witness report.**

* `CrimeGameTests` covers:
  * a passer-by who reports once, however many blows follow, after the city's report time;
  * a victim who reports alone;
  * a patrol's warning, then a pursuit;
  * striking an officer;
  * officers who start out of the player's sight;
  * a motion sensor;
  * a neighbour who hears damage.
* Wreck run 2: a picked lock with nobody in sight called nobody. A window broken inside the house was heard by a neighbour, and the police were called.
* The assault, alarmed-burglary, pursuit and arrest scenario is `-PucPlaytest=police`. Its result is recorded under "Runs in progress".

## 4. Burglary damage and restoration (D-190)

**Implemented, and verified through a real client:** wreck run 2, `RESULT pass`, 9 steps.

* A new citizen picked a Valewood house's deadbolt on the real lock screen. Nobody saw it, and nobody called the police.
* They broke a glass pane, a bed and a dark oak plank inner wall with real held clicks. Each was gone, nothing dropped, and 4 cells were recorded: the bed is two.
* They searched a piece of furniture.
* The world was saved, quit and reopened. The record of 4 cells was still there.
* After the wait (shortened to one minute with `-PucPtRestoreminutes=1`; the game's own wait is 30 minutes of play), the house was put back. All 4 cells were exactly as before, the door was shut, and the loot carried was unchanged.

Fixed during the pass: the scenario's search for things to break called the server from the server thread, deadlocking for 20 seconds (wreck run 1). The police scenario had the same nested call.

Not yet shown through a client: the outer wall holding. Run 2 found no wall block in reach; the search is widened.

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

The private buildings' port, generation, screenshots and integration: see "Runs in progress".

## Runs in progress

This section lists only runs that have finished, with their result. A run that has only started is not recorded here.
