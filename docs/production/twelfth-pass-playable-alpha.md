# Twelfth pass: playable RPG alpha, the economy, the police and the cities (October 29, 2026)

Owner brief: "Playable RPG Alpha, Complete Economic Rebalance, Reliable Policing & Living-City Progression". Decisions D-209 to D-214. Branch `feature/twelfth-pass-playable-alpha`, draft PR #27 (stacked on `feature/tenth-pass-living-world`, PR #26; not merged).

**The current branch and its recent gameplay systems have not received human playtesting.** Every result below comes from one of these:

* arithmetic over the game's data with named assumptions (L1);
* Forge GameTests on a headless server (L2);
* scripted real clients (L3): a real Minecraft client driven by a script in a disposable world, every shortcut logged as `SCRIPTED`;
* GitHub's Windows runners for the Windows scripts.

None of it was played by a person. The owner's world `UC-Alpha-Test-01` was never opened or changed. No C70 asset, converted structure, private selection, private screenshot or private world is in the repository, the PR or any CI artifact.

The principle the pass was held to: *a player should never feel forced into crime to make meaningful financial progress, and a player who chooses crime should never feel that the police are irrelevant.*

## 1. The economy: legal, underground and hybrid (D-210, D-211)

`scripts/career_balance.py` computes, for each of the five careers at levels 1 to 30, the best **active hour** of a legal, an underground and a hybrid character, nominal and risk-adjusted. A new player's affordability timeline and a Crew of two are computed the same way. Every input is named as code, data or assumption ([career-balance.md](../technical/career-balance.md)). The underground hour is priced with the real checkpoint arithmetic, and burglary with the police's chances and escalated fines.

**What it found.** Four careers stopped growing after the middle levels:

* the Vintner's aged wine was Reserve at level 6;
* the Gunsmith's parts kit filled every hour from level 3;
* the Smith's advanced orders paid less per hour than its entry orders;
* the Technician lost money on an install.

**What changed** (data, and two fee formulas; no flat multiplier):

* 21 advanced contracts;
* grades later in a career;
* the Technician's install and upgrade fees cover the parts at every tier;
* demand limits so no single order fills a level-20 hour;
* two underground orders lowered to keep D-052's careless-smuggler rule.

Credits per active hour (risk-adjusted; model):

| career | legal L6 before → after | legal L20 before → after |
| --- | --- | --- |
| Farmer | 383 → 383 | 541 → 741 |
| Vintner | 794 → 542 | 839 → 856 |
| Smith | 478 → 478 | 929 → 893 |
| Gunsmith | 698 → 424 | 1190 → 1004 |
| Technician | 521 → 521 | 619 → 808 |

**Legal against illegal.** At every level and in every career the best legal hour beats the best underground one, nominal and risk-adjusted. A hybrid's best hour is its legal trade's (100% legal at level 15 in all five careers). From level 10, crime is about 30% to 75% of the same character's legal hour: it pays more per job (1.9 to 2.2 times the goods' cost, up to 1,000 credits) but loses 22% to 44% of careless runs to a search and costs a 12-minute round trip.

Every property milestone up to the villa is reached on legal income alone:

* a house rented in 2.2 to 3.5 active hours;
* a house bought in 21 to 31;
* the villa in 49 to 72.

Seven rules are checked from now on, each shown to fail on broken data in its test.

**A knife edge, for the owner (D-211, proposed).** If underground pay is raised a little, a hybrid's hour flips from all-legal to all-crime: there is no middle ground in today's rules. The owner's brief also asks crime to "pay more"; D-052 says careless crime loses. The proposal is a higher underground band **and** a per-buyer limit an hour. It was not made: it is the owner's decision.

**Assumptions not yet measured:**

* half of an active hour is hands-on;
* an arrest follows 40% of noticed crimes;
* station tiers by level;
* service-call minutes.

Telemetry (section 7) now records the first two in play.

## 2. The police (D-209): detect, dispatch, walk in, pursue, settle

The owner saw a house robbed and civilians punched with no police and no consequence. The pass traced every case from the crime to the settlement. It added the following (GameTested, `PoliceWarrantGameTests` and `CrimeGameTests`):

* an **opt-in trace** (`/uc_police trace on`, bounded to 200 lines in memory) that names each step;
* **warrants** for a witnessed crime, 20 active minutes, surviving logout, restart and escape;
* **recognition** by officers on the beat;
* **priors** that raise the fine by half each (at most double, never over 1,000) and remove the warning;
* a wanted person's higher chance of a checkpoint search, and an arrest there.

The real-client acceptance runs and what each case did are in section 2a.

### 2a. Real-client acceptance (police cases runs 1 to 9)

`-PucPlaytest=policecases` (`PoliceCasesScenario`): a real client in a fresh archipelago world with the furnished cities. A new Farmer registers through the real screens, then:

* hits a passer-by with real left clicks until the server counts the blow;
* waits to be arrested;
* picks a building's lock on the real lock screen at night and searches its furniture with real right clicks;
* strikes again carrying the marked goods;
* runs from the officers;
* saves, quits and reopens;
* walks to an officer on the beat.

The operator command gives 300 credits so fines can be paid. The clock is set by command. The scenario chooses the building from the game's own data (nobody in, alarm not armed, a lock plain picks can try) and travels between cities by teleport. Each of these is logged as `SCRIPTED`.

Nine runs in three cities on two seeds, two of them with the owner's buildings (the case-by-case table and the trace are in [police-response.md](../technical/police-response.md#real-client-acceptance-twelfth-pass-police-cases-runs-1-to-9-duo-run-12)):

| Run | City, seed | Outcome |
| --- | --- | --- |
| 1 | Valewood, 5908 | a witnessed blow, a noticed burglary of a house and a second blow each ended in an arrest by officers who walked in (fines 40, 120 and 80, priors escalating); the chase found no officer looking; the reopen showed nothing pending |
| 2, 3 | Blackwater, 20261029 | the blow passed (fine 30) both times; the burglary found no house near the middle (run 2), then no storefront or club both empty and unarmed at 03:00 (run 3) |
| 4 | Blackwater | stopped by the scenario: the first-login landing moved the player back after its teleport. Fixed in the scenario |
| 5 | Blackwater | the blow passed (fine 30; the officers arrived in view); the burglary chose a club, whose high-security lock a new thief's picks cannot try. The scenario now chooses a lock plain picks can try |
| 6 | Ironhaven, 7741 | the blow passed (fine 60); no house near the middle |
| **7** | **Ironhaven, 7741** | **every case passed** (8 of 8, 19 scripted shortcuts). See below |
| 8, 9 | Valewood, 5908, the owner's buildings (private) | run 8 found the bush fault; run 9, with the fixes, passed every case it reached (see below) |

Run 7, in order:

1. **The blow:** arrested for assault by two officers who walked in from 18 blocks out of sight; fine 60, bread kept.
2. **The burglary:** a workshop's commercial lock picked pin by pin at 03:00 (75 Thief XP) and seven pieces of furniture searched (13 stolen units, each marked with the thief's name).
   * A passer-by had seen the thief at the lock; the response took 150 s.
   * One officer walked to the door; his route fell 13 blocks short, and he arrested the thief anyway.
   * Fine 150 (100 raised by one prior), all 5 stacks of marked goods taken, 8 bread kept.
3. **The chase:** a second blow, then a sprint away. The officer saw the player at 4 blocks and challenged them, then lost sight and searched where they were last seen. The player was out of sight 30 s and 107 blocks away: **an escape, with the warrant still standing.**
4. **Save, quit, reopen:** the warrant still stood, nobody had been sent while the player was away, and the screen said "You are wanted".
5. **The beat:** an officer on the beat recognised the wanted player at 18 blocks, walked to them and arrested them on the warrant (fine 120 by two priors, capped at the 90 left).

**A design question for the owner:** at night, near Blackwater's middle, every occupied storefront has a resident in and every club needs a Pro Pick Set, so a new thief there has nothing to pick. Ironhaven's middle has no houses but plenty of workshops and storefronts with commercial locks.

**Run 8, with the owner's buildings (case 10, private: a new Valewood world, seed 5908; no private name or picture is published), failed and found a fault in the police:**

* The blow was witnessed and reported, and two officers walked in from 15 blocks out of sight.
* The nearest officer saw and challenged the player at a block or two.
* That officer's route had stopped on the far side of a two-high bush beside the player. An arrest needed the nearest officer's own line of sight, and the leaves blocked it.
* The officer stood there for two minutes while the player stood still, and the response ended as an escape.

Two fixes, each covered by a GameTest:

* An officer within arrest range now needs a clear line at the eyes, the chest or the knees, and steps round to a free cell beside the suspect when something is in between. A wall or a window still blocks all three.
* The route check now compares the end of a path with the suspect, not its target. The target is the suspect's own block, so the check had counted every short route as reaching.

**Run 9, the private rerun with both fixes (same world settings and the same spot):**

* the witnessed blow ended in an arrest; the officers came from another street this time, so the run does not show the step-round itself, which a GameTest covers;
* **a burglary nobody saw stayed unnoticed** (case 7: no report, no dispatch, no warrant);
* a second offence carrying the 7 stolen units ended in an arrest that took them all, fine 60 (40 raised by one prior);
* a chase and an escape with the warrant standing; **a second unit was sent for the first time in a real run**, because the first officer's route fell short (the corrected route check);
* the reopen kept the warrant;
* no beat officer came close enough to recognise the player in the time given.

The run's two reported failures were the scenario's own checks, fixed after it: stolen bread was counted as honest bread, and marked goods from an unseen burglary were taken to mean the police were out.

**Case 8 with two real clients (duo run 12).** This is the two-client session on a dedicated server (`scripts/run_duo_session.py`), where the server drives each action through its own path for what a client sends.

* The Valewood Farmer struck a passer-by. The Blackwater Vintner, their Crew partner, stood two blocks away with two gold ingots marked stolen in their pockets (scripted).
* The response was for the Farmer alone.
* Nobody else saw the blow, so the person struck reported it: two officers came in 50 seconds and arrested the Farmer 5 seconds later (fine 40, a first prior).
* The Vintner's wallet, record and gold were untouched.
* After the server was stopped and started again, the Farmer's conviction and the Vintner's clean record were as before.
* All 63 of the session's checks passed, including the supply chain, the Crew bank and the house put back after a burglary.

**What runs 1 to 9 and duo run 12 did not show:**

* the step round a bush itself (run 9's officers came from another street; a GameTest covers it);
* two players each driving their own client through a crime (duo run 12 is driven by the server);
* a shop robbed after closing, a window, an officer struck, hiding inside, leaving the city while wanted.

A scripted thief mostly stands still to be arrested. The runs show that arrests happen and are settled correctly, not how often a real player is caught.

## 3. Windows install, launch and multiplayer

* **Real Windows runners.** `.github/workflows/windows-scripts.yml` runs the script tests on `windows-latest` under PowerShell 7 and under Windows PowerShell 5.1 (the one Windows 11 ships with), with a backup and a rollback through the real `robocopy`. It also runs the doctor on a fresh machine and the real `gradlew.bat` building Core and fetching the default pack's mods against their pinned hashes. All green.
  * The first run found that **every launcher would have stopped with "Java was not found" under Windows PowerShell 5.1**. Java writes its version to the error stream, and 5.1 turns that into a stopping error. That would have been the owner's first experience. Fixed (`Invoke-UcNative`).
  * Another run found a comma list in a documented command that PowerShell parses as an array. Fixed in the workflow and in two guide commands.
* **`uc-doctor.ps1`** reads a PC and says what to fix: Java 17, Git, Python, the branch and local changes, disk, memory, the port, offline mode, the backups made. It changes nothing.
* **`uc-restore.ps1`** lists backups and rolls back to one, backing up the current folder first.
* **Not tested:** a game started on Windows (the runners have no screen and no account), two PCs on one network, a person.

The exact steps for the next playtest are in section 10 and in [the Windows guide, 6m](playtest-guide-windows11.md#6m-twelfth-pass-what-to-do-next-the-police-a-growing-business-and-the-cities-about-60-minutes-a-new-world).

## 4. What to do next: the Guide page's Opportunities (D-213)

The terminal's Guide page has an **Opportunities** tab, opened by default once the first half hour is done. It holds at most eight lines built from the server's state when the terminal opens:

* an order the player can deliver now;
* a shop's shortage contract;
* the best open legal orders;
* the Technician's or Smith's calls in this city;
* unspent skill points;
* no home yet;
* the best underground order, in its own colour with what can go wrong, only once the trade's underground skill is held;
* the next order a skill would open, with a lock and the skill;
* a buyer in another city;
* the city's good name under 50.

A click opens the page it names, and the pointer shows a line's whole text. GameTested (`OpportunitiesGameTests`).

Captured and inspected at 3440×1440 GUI 2 and 1920×1080 GUI 2 and 3. The first captures found two faults, fixed and captured again:

* at GUI 3 the last two lines (the locked order and the city's good name) were dropped without a word; the list now scrolls and says how many lines are below;
* at 3440×1440 the underground line's warning was cut off; it now comes first. ([opportunities.md](../technical/opportunities.md)).

## 5. City economies (D-214)

An audit of the three cities' exchanges, shops, buyers, laws, home prices and street people (`scripts/city_economies.py`, [city-economies.md](../technical/city-economies.md)) found most of the owner's division already in the data:

* Ironhaven sells parts and alloy and buys the Smith's components;
* Valewood sells seeds and flour and buys farm crates;
* Blackwater sells circuits, optics and aged wine, holds most underground buyers, and is the most lenient (witness chance 10% against Valewood's 25% and Ironhaven's 35%);
* homes cost 15% more in Ironhaven and 10% less in Valewood.

One gap: the Technician had no buyer in Blackwater, the city of offices. A new legal buyer, **Blackwater Tower Management**, takes the office-tower security and data-centre contracts, delivered only there.

Five rules are checked from now on:

* a legal buyer for each of a city's careers;
* no shop buys a good for as much as any exchange sells it with every discount;
* bread, the First Aid Kit and torches in every city;
* laws ordered as their summaries say;
* every buyer has an order.

## 6. Business growth (D-212)

A Business Counter grows from things the player does:

| Growth | Needs | Gives |
| --- | --- | --- |
| Tier 2 | career level 14; 4 machined parts and 2 refined alloy | room for 128, +1 sale an hour |
| Tier 3 | level 20; 4 machined parts, 2 control circuits and 2 optical components | room for 192 |
| Price | 85% to 100% of the city's price, never above | +1 sale an hour at ten points below |
| Regulars | 200 lifetime sales | +1 sale an hour |

At its best a counter takes 517 credits an hour against 566 for the best legal hour at level 14 (`economy_model.py --check`). A business adds to a trade and never replaces it.

GameTested. Not tried through a real client. Staff, advertising and supplier contracts were not built ([player-business.md](../technical/player-business.md)).

## 7. Economy telemetry (P2)

Core writes one JSON line per economic event to `underground_city_telemetry.jsonl` in the world folder:

* sessions;
* orders accepted and delivered;
* service calls;
* purchases;
* shop and fence sales;
* encounters;
* business takings;
* arrests;
* checkpoint searches.

Players are pseudonyms (a hash of the seed and the id); nothing leaves the world folder. It rolls at 4 MB and is off with `-Duc.economyTelemetry=false`.

`scripts/economy_report.py` turns one or more worlds into a report:

* net credits per active hour by source;
* each order's active minutes against the model's;
* travel times;
* police outcomes.

It read the police runs' worlds. On police run 3 it reported one arrest, a fine of 30 asked and paid, and a teleport between cities. ([economy-telemetry.md](../technical/economy-telemetry.md)).

## 8. Organic progression

Each career's scripted journey was run again on this pass's data: the rebalanced orders, grades and fees, and the new buyer. Each ran through a real client in a new world (L3).

* **Track A** is a new citizen's first steps from the real registration screen.
* **Track B** starts further on, with a level and credits granted by command, logged `PREPARED`.

Every other shortcut (the clock, a teleport, an item moved to the hotbar) is logged `SCRIPTED` and counted.

| Career | Run | Steps | Shortcuts | What it showed |
| --- | --- | --- | --- | --- |
| Farmer | 11 | 14 of 14 | 7 | **A:** registration, the kit's Mill placed and grinding flour. **B** (level 4, 120 credits): two skills learned at a public terminal; a Grading Table crafted from the recipe book; a Fine produce crate sold to the General Store (+41); flour sold to the bakery (+20); a save and reopen with nothing doubled |
| Vintner | 9 | 12 of 12 | 8 | **B** (level 10, 200 credits, materials): four skills; wine fermented; a cask and a still crafted; aged wine and grain spirit made in active time and sold to Blackwater's club bar (+144 and +42); reopen |
| Smith (engineer) | 8 | 13 of 13 | 5 | **A:** registration, the kit; a drawing spoiled on purpose and sleeved, then one turned to the standard for a Reserve drive shaft (+45 XP). **B** (level 5, 300 credits): two skills; a breakdown at a tier III power hammer (raised at once, scripted); parts bought over the Builders' Yard's counter; a test run refused with its reason; the repair (+77 credits and +180 XP, once); reopen |
| Gunsmith | 10 | 10 of 10 | 6 | **A:** registration, the kit; the bench and TaCZ's Gun Smith Table placed; a parts kit started on the bench (four minutes of play); the blueprint read; a Glock 17 built on TaCZ's own screen, registered to the maker. The bench's kit made a second. **B** (level 4 and Licensed Repairs given, as the armoury asks; Smith's parts and iron given): Accept and Deliver hand both pistols to the city armoury (+400). Run 9 had built both pistols and stopped at Deliver: the scenario looked for the button before the terminal had the server's answer, fixed in the shared click |
| Technician | 9 | 18 of 18 | 12 | **A:** registration, the kit; panels beside six street doors; a service call (raised at once, scripted) on the computer's board; Track to the panel (the status display's arrow; Xaero's Minimap was not installed); the repair puzzle worked with real clicks (+66 credits and 170 XP, once). **B** (level 5 and three skills given): a toolkit made on the Electronics Bench; an install worked on the patch grid (+234, a monitored alarm; the grid's answer was read from the server, the turns were real clicks); a second call tracked across a save and reopen with the repair and the alarm kept. Run 8 had passed the same steps up to the second call, which its scenario looked for only at tier I or II panels |

What the journeys do not show:

* the hours between the steps (Track B is a prepared start, so they show that each loop works and pays, not how long a person takes to reach it);
* a person's choices;
* the underground side of each trade on the rebalanced data (the Thief, Cannabis and unmarked-firearm runs of earlier passes were not rerun);
* two players each working their own client through a career (the two-client session drives both from the server).

## 9. Test evidence

By level (section 1's terms):

| Level | Evidence | Result |
| --- | --- | --- |
| L1, arithmetic | `career_balance.py --check` (seven rules), `economy_model.py --check`, `city_economies.py --check` (five rules), `validate_underground_balance.py`; each rule's test shows it failing on broken data | all hold |
| L2, GameTests | 480 of 480 locally (run 123), including the bush and the closed-room police tests; CI's GameTest job on every push | all passed; CI green on the pass's pushes since the telemetry fix (9ee9ee2) |
| L2, Python | `python -m unittest discover -s tests` | 461 tests, OK, 14 skipped |
| L3, one real client | police cases runs 1 to 9; Farmer 11, Vintner 9, Smith 8, Gunsmith 10, Technician 9, business 13 | see sections 2a, 6 and 8 |
| L3, two real clients | duo run 12, a dedicated server driving both clients' actions through its own paths | 63 of 63 checks, including police case 8 and a restart |
| Windows | `windows-scripts.yml` on `windows-latest`, PowerShell 7 and 5.1 | green; scripts only, no game |
| Screens | the Guide page at 3440×1440 GUI 2 and 1920×1080 GUI 2 and 3 | captured and inspected; two faults found and fixed |

**Runs that failed, and why.** A failed run is kept in the count, not dropped.

| Run | Cause | Fix |
| --- | --- | --- |
| Police case run 8 | **a fault in the game**: an officer stood two minutes behind a bush | fixed, GameTested, run 9 passed |
| Police runs 2 to 6 | the scenario's choice of building and time (section 2a) | scenario |
| Gunsmith 9 | the scenario looked for Deliver before the terminal had the server's answer; the same race was in five other scenarios | the shared click waits for its button |
| Technician 8 | the scenario asked for a tier I or II panel | any tier |
| Business 11 | the scenario read the chat too early, and `/uc_prepare` stops at level 10 | waits for the reply; level 14 by the operator's command |
| Business 12 | the same chat race in the upgrade step (tier 2 had been fitted) | waits for the reply |

**Performance.** No performance session was run in this pass. The latest measurement is the eleventh pass's 60-minute two-client session: no Core frame in any tick over a second. This pass adds little work per tick, all of it bounded by the officers on duty:

* the arrest check of a response;
* the beat's look-up of a warrant for each player near a patrol.

No run measured it.

## 10. For the owner's next playtest (Windows 11, two players)

1. On both PCs, in PowerShell in the repository folder:

   ```
   git fetch origin
   git checkout feature/twelfth-pass-playable-alpha
   Set-ExecutionPolicy -Scope Process Bypass
   .\scripts\windows\uc-doctor.ps1
   .\scripts\windows\uc-update.ps1
   ```

   The doctor must show no `FIX` line. Both PCs must print the same commit.
2. On the host PC, a NEW world, on your own PC or a home network with people you trust only (offline mode believes any name; never behind a port forward):

   ```
   .\scripts\windows\uc-server.ps1 -Sets furnished,scarcity -World UC-Twelfth-1 -LanOfflineMode -AcceptEula -Pregen
   ```

3. Each player:

   ```
   .\scripts\windows\uc-client.ps1 -Name Alice -Sets furnished,scarcity -Join
   ```

   Use your own name and checkout, and the host's address in *Multiplayer*. The pack fingerprint must match on all three.
4. Play section 6m of the guide (about 60 minutes): the Guide page, a witnessed blow and an arrest, priors, an escape and a warrant, a quiet burglary, a counter's tier and price, Blackwater Tower Management, a backup and a rollback.
5. After the session:

   ```
   .\scripts\windows\uc-logs.ps1
   python scripts\economy_report.py rpg-core\run-dev\UC-Twelfth-1 --out reports\economy.md
   ```

   Send the zip and the report. They hold no world and no names.

For the owner's buildings, add `-C70` to the server and client commands (guide 6k) after `uc-private-stage.ps1`.

## 11. Not done, not proven, and known problems

**The current branch and its recent gameplay systems have not received human playtesting.**

Not proven:

* **How often a real thief is caught.** The career balance assumes an arrest after 40% of noticed crimes. The scripted thief stands still to be arrested, so the runs show that arrests happen and are settled correctly, not how often. A human session's telemetry should replace the figure.
* **How much of an active hour is hands-on.** The model assumes half. Telemetry records it once a person plays.
* **Two players each driving their own client** through a career or a crime. Duo run 12 drives both clients' actions from the server.
* **Some police cases through a real client:**
  * a shop robbed after closing with its alarm;
  * a window broken;
  * an officer struck;
  * hiding inside;
  * leaving the city while wanted;
  * the step round a bush itself (GameTested).
* **The underground side of each trade on the rebalanced data:** the Thief, Cannabis and unmarked-firearm runs were not repeated, and the Technician's back channel is still GameTested only.
* **The Guide page through a person's eyes.** It was captured at three viewports, not at 1280×720, and not used by a person.
* **Windows:** scripts on GitHub's runners, not a game on Windows and not two PCs.

Not built:

* staff, advertising and supplier contracts for businesses;
* tier 3 of a counter through a real client (tier 2 was);
* a counter's customers on an upper floor, through the owner's buildings' entrances, or in premises over several chunks (business runs used the ground floor of Ironhaven's workshop);
* city demand that differs by city beyond the shops' own lines.

For the owner to decide:

* **D-211:** a small raise to underground pay flips a hybrid from all-legal to all-crime. The proposal is a higher underground band plus a per-buyer limit.
* **Beginner thieves in Blackwater:** near the city's middle at night, a new thief has no lock to try.
* **Buyers:** whether Ironhaven needs a Technician buyer, and whether Vesper Holdings belongs in Valewood.

Known problems:

* The scripted runs needed a teleport between cities, a set clock and a prepared level for their second half. Each is logged, and none is how a player gets there.
* The Technician's panels near Valewood's Willow Way were all tier III or above in run 8's world, where the October 24 world had a tier I or II nearby. It was not traced: building tiers come from the generated city and its neighbourhood grade, and nothing in this pass changed them.

## 12. What to develop next

In order:

1. **A human session.** The owner's two-player session of section 10 and guide 6m. Its telemetry, read with `economy_report.py`, should then replace the model's two biggest assumptions:
   * the arrest rate (40% of noticed crimes);
   * the hands-on share of an active hour (one half).
2. **The owner's decisions:**
   * D-211, the underground band and a per-buyer limit;
   * whether a beginner thief should have something to pick near Blackwater's middle at night;
   * whether Ironhaven needs a Technician buyer and whether Vesper Holdings belongs in Valewood.
3. **The rest of the police cases through a real client:**
   * a shop robbed after closing with its alarm;
   * a window broken;
   * an officer struck;
   * hiding inside a building;
   * leaving the city while wanted.

   A human session should also measure how often a real thief is caught.
4. **Businesses:**
   * staff (townspeople who mind a counter, closing a labour gap for two players);
   * supplier contracts (a counter that buys a partner's goods);
   * advertising;
   * tier 3 through a real client.
5. **Cities that differ in demand:** shortages that come faster where a city's own trades are weak (food in Ironhaven, parts in Valewood), within the five audit rules.
6. **The underground paths, rerun on the rebalanced data through real clients:**
   * the Thief track;
   * Cannabis;
   * the unmarked firearm;
   * the Technician's back channel (still GameTested only).

The current branch and its recent gameplay systems have not received human playtesting.
