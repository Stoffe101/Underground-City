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

### 2a. Real-client acceptance (police cases runs 1 to 7)

`-PucPlaytest=policecases` (`PoliceCasesScenario`): a real client in a fresh archipelago world with the furnished cities. A new Farmer registers through the real screens, then:

* hits a passer-by with real left clicks until the server counts the blow;
* waits to be arrested;
* picks a building's lock on the real lock screen at night and searches its furniture with real right clicks;
* strikes again carrying the marked goods;
* runs from the officers;
* saves, quits and reopens;
* walks to an officer on the beat.

The operator command gives 300 credits so fines can be paid. The clock is set by command. The scenario chooses the building from the game's own data (nobody in, alarm not armed, a lock plain picks can try) and travels between cities by teleport. Each of these is logged as `SCRIPTED`.

Seven runs in three cities on two seeds (the case-by-case table and the trace are in [police-response.md](../technical/police-response.md#real-client-acceptance-twelfth-pass-police-cases-runs-1-to-8)):

| Run | City, seed | Outcome |
| --- | --- | --- |
| 1 | Valewood, 5908 | a witnessed blow, a noticed burglary of a house and a second blow each ended in an arrest by officers who walked in (fines 40, 120 and 80, priors escalating); the chase found no officer looking; the reopen showed nothing pending |
| 2, 3 | Blackwater, 20261029 | the blow passed (fine 30) both times; the burglary found no house near the middle (run 2), then no storefront or club both empty and unarmed at 03:00 (run 3) |
| 4 | Blackwater | stopped by the scenario: the first-login landing moved the player back after its teleport. Fixed in the scenario |
| 5 | Blackwater | the blow passed (fine 30; the officers arrived in view); the burglary chose a club, whose high-security lock a new thief's picks cannot try. The scenario now chooses a lock plain picks can try |
| 6 | Ironhaven, 7741 | the blow passed (fine 60); no house near the middle |
| **7** | **Ironhaven, 7741** | **every case passed** (8 of 8, 19 scripted shortcuts). See below |

Run 7, in order:

1. **The blow:** arrested for assault by two officers who walked in from 18 blocks out of sight; fine 60, bread kept.
2. **The burglary:** a workshop's commercial lock picked pin by pin at 03:00 (75 Thief XP) and seven pieces of furniture searched (13 stolen units, each marked with the thief's name).
   * A passer-by had seen the thief at the lock; the response took 150 s.
   * One officer walked to the door; his route fell 13 blocks short, and he arrested the thief anyway.
   * Fine 150 (100 raised by one prior), all 5 stacks of marked goods taken, 8 bread kept.
3. **The chase:** a second blow, then a sprint away. The officer saw the player at 4 blocks and challenged them, then lost sight and searched where they were last seen. The player was out of sight 30 s and 107 blocks away: **an escape, with the warrant still standing.**
4. **Save, quit, reopen:** the warrant still stood, nobody had been sent while the player was away, and the screen said "You are wanted".
5. **The beat:** an officer on the beat recognised the wanted player at 18 blocks, walked to them and arrested them on the warrant (fine 120 by two priors, capped at the 90 left).

**What the runs did not show:**

* an unnoticed crime in this pass (both burglaries were seen; Thief run 12 of October 24 was unnoticed);
* two real clients (GameTests only);
* a shop robbed after closing, a window, an officer struck, hiding inside, leaving the city while wanted.

A scripted thief mostly stands still to be arrested. The runs show that arrests happen and are settled correctly, not how often a real player is caught.

**A design question for the owner:** at night, near Blackwater's middle, every occupied storefront has a resident in and every club needs a Pro Pick Set, so a new thief there has nothing to pick. Ironhaven's middle has no houses but plenty of workshops and storefronts with commercial locks.

**Run 8, with the owner's buildings (case 10, private: a new Valewood world, seed 5908; no private name or picture is published), failed and found a fault in the police:**

* The blow was witnessed and reported, and two officers walked in from 15 blocks out of sight.
* The nearest officer saw and challenged the player at a block or two.
* That officer's route had stopped on the far side of a two-high bush beside the player. An arrest needed the nearest officer's own line of sight, and the leaves blocked it.
* The officer stood there for two minutes while the player stood still, and the response ended as an escape.

Two fixes, each covered by a GameTest:

* An officer within arrest range now needs a clear line at the eyes, the chest or the knees, and steps round to a free cell beside the suspect when something is in between. A wall or a window still blocks all three.
* The route check now compares the end of a path with the suspect, not its target. The target is the suspect's own block, so the check had counted every short route as reaching.

*Run 9, the private rerun with both fixes, is queued; its result is reported here.*

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

A click opens the page it names. GameTested (`OpportunitiesGameTests`). Captures at 3440×1440 GUI 2 and 1920×1080 GUI 2 and 3 are queued ([opportunities.md](../technical/opportunities.md)).

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

*Queued: the five careers' scripted journeys are rerun against the rebalanced data, after the police runs.*

## 9. Test evidence

*Completed when the runs finish.* So far: the Python suite (461 tests) passed after the coverage manifest was fixed; `career_balance.py --check`, `economy_model.py --check`, `city_economies.py --check` and `validate_underground_balance.py` hold; the Windows workflow is green on GitHub's runners.

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

*Completed at the end of the pass.*

## 12. What to develop next

*Completed at the end of the pass.*

The current branch and its recent gameplay systems have not received human playtesting.
