# Eleventh pass: tenth-pass follow-up and integration completion (October 28, 2026)

Owner brief: "Underground City: Tenth-Pass Follow-up and Integration Completion". Decisions D-205 to D-208. Branch `feature/tenth-pass-living-world`, draft PR #26 (stacked on `feature/world-playability-overhaul`; not merged).

**The current branch and its recent gameplay systems have not received human playtesting.** Every result below comes from scripts, Forge GameTests, the Python suite, the economy model, generator probes of new disposable worlds, a dedicated server or scripted real clients. None of it was played by a person, and none of the Windows scripts has run on Windows. Each scripted run logs every shortcut it takes, marked `SCRIPTED` or `PREPARED`. The owner's own world `UC-Alpha-Test-01` was never opened or changed. No C70 asset, converted structure, private selection, private screenshot or private world is in the repository, the PR or any CI artifact. The owner's buildings were ported, wired, probed and photographed in private folders outside the checkout only.

## 1. The Windows playtest workflow (D-205)

* **The sets the scripts know come from the manifest.** `furniture` was missing from the scripts' list; `build.gradle` now derives the sets it accepts from `dev-pack.json` and a test checks the scripts accept exactly those.
* **Client and server mods are separated.** A dedicated server leaves out the mods marked client-only (Embeddium, Xaero's maps, the client helpers) and says so.
* **Both sides run the same pack.** Every start prints a fingerprint of Core's commit and every mod both sides need with its pinned version. A joining client whose fingerprint differs is told in the game, and the server logs both.
* **One command stages the owner's buildings:** `uc-private-stage.ps1 -Source <folder or zip>` (`scripts/private_c70.py stage`).
  * It finds the Lost Cities data, ports it with the latest porter and boots a disposable server once to read the furniture set's blocks.
  * It chooses the buildings, wires them into the three cities with their homes, seals the result and installs it outside the repository (`%USERPROFILE%\UndergroundCityPrivate\runtime`).
  * `-C70` on the server, client and new-world scripts then launches C70-first.
  * A pack staged by an older porter or wiring, a folder inside a checkout, a pack in place of a folder, an unported original or missing sets each stop the launch with the reason.
* **Evidence:** `test_windows_scripts.py` runs the scripts under PowerShell 7 on Linux against stand-ins for gradle and Python; `test_private_c70.py` covers staging on invented packs. The real staging ran in the cloud on the owner's pack. **Not tested on Windows.** See the [playtest guide, sections 3c and 6k](playtest-guide-windows11.md).

## 2. A property follows the dwelling, not the chunk (D-206)

The owner's rule:

* a detached house over several chunks is one property;
* terraced houses sharing a wall are two;
* a block of flats lets each flat, with its hall and stairs shared;
* premises over several chunks are one lease;
* an uncertain boundary is never guessed.

A home now has one interior box or several (up to 160). Core reads from Lost Cities which chunks form one multi-chunk building, and lets what `city_dwellings` says that building holds, only when its chunks agree. The private wiring reads each of the owner's buildings in three dimensions (rooms parted by walls, linked by doors) and leaves anything uncertain unlet and reported, with owner overrides.

* **GameTests** (`DwellingGameTests`, on invented two-chunk buildings):
  * a house over two chunks is one home, kept with its tenancy over a restart;
  * a terrace is two homes, and one key never opens the other, a Crew member's included;
  * flats share their street door and never each other's doors;
  * a burglar's permit covers the whole building and reaches no flat;
  * **added this pass:** a tenant may change their own flat but never the wall it shares with the next flat, the floor under the flat above, or the next flat itself;
  * nothing is let where the file is missing, wrong or overlapping;
  * a save from before loads as one box.
* **Private, in the cloud:** the owner's pack in a new disposable world registered 478 city homes (93 of several boxes, 12 over several chunks, none reaching another building's chunk) in the latest probe.
* **Not done:** no home over several chunks has been leased through a real client. See [dwellings.md](../technical/dwellings.md).

## 3. Visual verification of the owner's buildings (private)

* **The photo tour's camera finds a clear view.** In private tour 13, seven of twenty frames showed a wall or the inside of a block. Now every shot looks for a place in open space with room round it, under a roof for a spot inside, with nothing solid, no pane and no glass between it and the spot. `scripts/photo_tour_plan.py` writes a private tour from a staged pack's own plans (a street door, then a bed, a stove, a bathroom, a seat and a counter of each use).
* **Private tour 14:** 29 of 34 frames taken, every one with a clear view, each looked at in the cloud: bedrooms, a bathroom, kitchens, shop and office interiors, a corridor. One bathroom frame was taken from the garden through a window, which gave the roof and room rules.
* **A real defect found and fixed: buildings buried in themselves.** A cabin had four of its five bedrooms full of dirt. Lost Cities 7 builds storeys 0 to `floors` and puts a part with no conditions up on every one; porter 12 had also raised such buildings to two floors. The owner's one-part cabin (a cabin on its own mound) was put up five times, six blocks apart, and each mound buried the bedroom below. **Porter 13 and wiring 13** build such a building once (storey 0, no cellars unless named). 25 of the owner's 218 buildings are of that kind. The building audit now counts rooms a storey above buries.
  * Probe of a new private world after the fix: 358 building chunks of 113 types, 1.89 million cells compared, **0 buried**, 262 missing (0.014%). The same cabin is now built with floors 0 and cellars 0, its beds in place and no dirt above its ground.
* **Pending:** a repeat tour with the tightened camera and porter 13 (section 9).

## 4. Reliability

### Multi-second ticks while new ground generates

The tenth pass's exploring session had 16 ticks over a second (the longest 16.4 s). Its sampler showed where the server thread waited:

* **vanilla's wait** (77 s): a player stands in a chunk still generating, and the player's own tick waits for it;
* **Core's door watch** (6.6 s) and **exploration crates** (0.6 s): `Level.isLoaded` is true once a chunk is *scheduled* to be full, and reading a block there waits for the generation workers;
* **the street population's area lookup** (0.7 s): it waited on Lost Cities' lock, because Core's "Lost Cities is generating" clock started only when a chunk was finished.

**Fixed:** every timed check round players asks `Chunks.ready` (the chunk is generated and loaded) and tries again on its next pass; Lost Cities' quiet clock starts when a worker begins planning a chunk. See [lost-cities-stalls.md](../technical/lost-cities-stalls.md).

**Measured: a 60-minute exploring session with two real clients** (twice the tenth pass's 30 minutes, the same seed and routes, 240 hops):

* 17 ticks over a second (16 in the first third), the longest 13.1 s.
* Every one was vanilla waiting for a chunk: the player's own tick or the spawners'. There was **no Core frame in any tick over a second**; the tenth pass had 7.2 s of Core in them.
* **A second round of Core waits** was found in shorter ticks (0.25 to 0.9 s) and fixed: the staff-area watch, the camera watch and a mob's griefing check, 4.3 s in all. A shorter session to confirm the fix is in section 9.

What remains is a hop landing in a chunk still generating, on a four-core cloud machine.

### Lost Cities' memory on longer travel

The same 60-minute session counted the live plans after a full collection at the end of each third: **14,328, 14,828 and 1,313** (the 30-minute session: 32,266, 9,559 and 18,432; with unchaining off, up to 491,528). The heap in use averaged 1,643 to 1,784 MB in every third, as low as or lower than the 30-minute session's, with more chunks loaded. After a full collection it was 632 to 775 MB. The restart passed with both citizens kept and 448 MB after a full collection. See [lost-cities-memory.md](../technical/lost-cities-memory.md).

### The general skills' hourly caps

A cap's hour was kept in memory, so a restart or a relogin gave a fresh hour. The windows now live in saved data (`HourlyCaps`) on the work-order ledger's active clock, which moves only while a real player is online: time away neither fills nor empties a cap, so nothing punishes being offline. GameTested (`aCapReachedBeforeARestartOrAReloginIsStillReachedAfterIt`). The same applies to a city's standing.

### Driving at the controls

Preparing the real-client drive found that Driving paid only when the *view* turned five degrees a minute, and a driver steers with keys while the camera may stay put. At the controls the vehicle's own turning now counts too. GameTested: a steering driver with a still camera earns; a boat drifting straight with a still view does not.

### Real-client checks

**The first aid kit and Shooting through a real client (skills run 3: 7 of 7).** A new Gunsmith registered through the screen. Hurt to 10 health, a real right click with a First Aid Kit gave 16, used the kit and started its cooldown. Inside the cooldown a second kit did nothing and was not used. After the cooldown it gave 16 again. Then a TaCZ Glock 17, loaded with a real R and fired with real mouse buttons at a target block 12 blocks away:

* the first hit paid 7 Shooting XP;
* a second hit on the same block inside two seconds paid nothing;
* a hit after the pause paid 7 again.

Scripted, each logged: the kits, the pistol and its rounds given by command, the damage, a firing lane cleared and the target set, and natural regeneration turned off. Run 2 had shown a full stomach healing alongside the kit, which made the cooldown check misread; the kit itself was not used inside its cooldown.

**A shortage through a real client (workshop run 6: every step).** A new Vintner made three bottles of berry wine at a Fermentation Vat.

* Scripted and logged: the pub's wine shelf was set one bottle above its short mark, and one customer's purchase was taken off it on the server.
* The Vintner in the game was told by a notice: "The Anvil is short".
* The barkeeper asked for the wine.
* The pub's counter showed it pays 18 a bottle while short (14 otherwise).
* A real click on Sell paid 54 for the three bottles and put them on the shelf (3 to 6).

The run found one fault: the barkeeper quoted the usual 14, not the 18 the counter paid. The line now quotes what the counter pays now, and the scenario checks for it. **Workshop run 7 passed every step**, the barkeeper saying "I'll pay 18 a unit".

Pending (section 9): Driving XP with an Immersive Vehicles quad.

## 5. Player business v1 and shortage contracts (D-207, D-208)

See [player-business.md](../technical/player-business.md).

* **A Business Counter** set down in trade premises the player works in opens the premises' business.
  * **What it sells:** plain everyday goods a city's shop also sells (3 to 60 credits) at the city's lowest price.
  * **Who buys:** the city's own street people walk in through the premises' doors during a shop's hours, one at a time, and buy one thing at the counter.
  * **What it costs:** every sale pays 12% sales tax (at least 2 credits, more than every discount a buyer can hold) before the till.
  * **How much:** at most 8 sales an hour of active play, so a counter's best hour stays under the best legal hour at level 12.
  * **The money:** stock and till live in the work-order journal. Stocking, taking back and collecting are prepared settlements, so goods and credits move once.
  * **No passive income:** no second wallet; the premises' rent runs on the household's active clock; nothing sells while everybody is away.
* **Shortage contracts.** A shop's supplier line that stays short for five minutes of active play posts a work order for its trade.
  * One taker, delivered at a terminal of the shop's city within thirty minutes of active play.
  * Paid once at half as much again as the shop pays (never as much as it charges), the goods put onto the shelf.
  * One a line, two a city, four in all; a line rests twenty minutes after.
* **The economy model checks both** (`business_problems`). Its first run found:
  * counters breaking even on cheap goods (the tax rounded to nothing);
  * fasteners whose contract would have paid only what the shop pays;
  * a counter's best hour of 636 against 521 at twelve sales an hour.

  The tax, its minimum, the price ceiling, the eight sales an hour and the contract floor come from those findings.
* **NPC routines:** the customers who walk to a counter are the new routine that affects a business. They ask first for what their city's own shops have run short of, so a counter takes up a shortage's slack. They run within a strict budget: one customer at a counter at a time, chosen from the handful of businesses in the journal, and only near a player.
* **Evidence:** `PlayerBusinessGameTests` (2), `ShortageContractGameTests` (3), the economy model's four new tests, and a counter through a real client.
* **A counter through a real client: business run 10 passed every step**, after runs 1 to 9 found what kept the city's people from a counter in a real building.
  * A Smith rented the Ironhaven workshop's premises with real clicks, set a counter down, stocked it with twelve bottles and opened it.
  * The first street person who set out came in through the premises' iron street door and bought a bottle after 56 s of play: 22, 3 of it tax.
  * An empty-handed right click paid the till of 19 into the wallet once.
* **What runs 1 to 9 found (each fixed):**
  * the scenario read the business inside another server read (runs 1 and 2: a 20 s stall that was the test's own);
  * no path goes through a closed iron door, and every premises' street door is iron (run 3: twelve customers set out, none arrived);
  * customers were chosen from people still inside buildings (runs 4 and 5);
  * **a townsperson's step through a doorway was applied one tick in ten**, because a move lasts one tick and the population passes every ten (runs 6 to 8). Every step through a door had this fault: shops' customers, people leaving, a counter's customers. A step is now a goal that moves the body every tick. The 60-minute session had counted almost nobody going in at a door;
  * the step was aimed at the doorway's centre, a hundredth of a block from an open door's panel (run 9). It is now aimed at the middle of the free part.

## 6. Test evidence

* **GameTests:** 469 passed (run 117) before the last changes; the final count is in section 9.
* **Python suite:** 429 tests at the start of this pass's last checks; see section 9 for the final run.
* **Economy model:** every invariant holds, including the new business and contract checks.
* **Private probes:** stage 13 (porter 13) in a new disposable world, as in section 3.

## 7. Not done, not proven, and known problems

* **Nothing here was played by a person.** The Windows scripts have not run on Windows.
* **No real client has:**
  * leased a home over several chunks;
  * taken a shortage contract (its notice, the ask and the short price did pass, workshop runs 6 and 7);
  * watched a customer walk into premises up stairs or behind several doors (one ground-floor workshop passed, business run 10).
* **Vanilla's own generation wait** remains when a player outruns the generation workers (a 96-block hop every 15 seconds in the exploring session). A walker or a vehicle at the generation's pace does not meet it, and test worlds can generate their islands ahead.
* **A player business v1 has no prices of its own, no staff and no advertising.** Shortage contracts are delivered at a terminal, not over the shop's counter.
* **The owner's buildings:** a building whose rooms are uncertain (a courtyard of open halls, a tower with lifts) is not let.

## 8. What to develop next

1. A human session of the business, contracts and homes over several chunks (playtest guide 6k and 6l).
2. Prices a player sets for their counter, within the city's range, and a customer's choice between counters.
3. Shortage contracts delivered over the shop's counter, and contracts from a city's civic needs.
4. Homes over several chunks leased through a real client with two players.

## 9. Runs in progress (this draft is filled in as they finish)

* The 60-minute exploring session with two real clients (long ticks, live plans per third, the heap), and a shorter one after the watch fixes.
* The GameTests and the Python suite on the final head.
* Real-client runs: Driving XP at the wheel of an Immersive Vehicles quad. (Passed and reported above: the first aid kit and Shooting, skills run 3; a shortage, workshop runs 6 and 7; a counter with a customer, business run 10.)
* A repeat private photo tour with porter 13 and the tightened camera.
