# Sprint report, October 25, 2026 (seventh pass): first playable alpha readiness

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "First Playable Alpha Readiness, Complete Career Journeys, Real Co-op, Living Cities & Gameplay Quality Overhaul". Decisions D-175 to D-180 in [decisions.md](decisions.md). The alpha milestone and its twelve exit criteria are in the [roadmap](roadmap.md#milestone-first-fully-playable-sandbox-alpha).

**The current branch and its recent gameplay systems have not received human playtesting.** Everything below was run by scripts, Forge GameTests, the Python suite, the economic model and scripted real clients (software-rendered in a virtual display, driven by synthetic key and mouse events). Evidence levels: L1 unit and data checks, L2 GameTests and headless runs, L3 one scripted real client, L4 two scripted real clients, L5 a person. They are never added together. Two kinds of scripted play are kept apart: **Track A, organic** (no commands, teleports or time skips beyond the ones logged as SCRIPTED) and **Track B, prepared** (a documented setup, logged as PREPARED). **The alpha milestone is not complete: its last criterion is a person playing it.**

## What changed for a player

* **The first hour has no dead ends we could find (D-175).** A per-career audit of the first one to two hours, without commands, found goods nobody sold (potatoes and sugar in Valewood, quartz, redstone and electronic scrap where the recipes needed them), a level-3 legal skill that cost more points than a level-3 player has, station recipes missing from the recipe book, and a Gunsmith whose own registered pistol counted as an unlicensed firearm at a checkpoint. Each was fixed where it starts: shop and exchange lines (never undercutting the exchange, never letting a fence pay more than the honest price), the first legal node of each tree at one point, recipes taught at registration and login, and a maker carrying their own registered gun is legal. Stations and work orders now say **where a missing part is sold or made**, and city shops have public terminals.
* **Every rung of the property ladder has a building (D-176).** Blackwater's lofts (the larger apartment) and the Valewood villa (the premium residence, with a workroom that takes a trade's stations).
* **Kit guns are named at TaCZ's table (D-177):** where three entries read "Glock 17", the table now lists "Registered Glock 17" and "Unmarked Glock 17"; TaCZ's own iron recipe for a kit gun is taken off the list (the server refused it already). The gun handed over keeps TaCZ's own name.
* **Shops behave (D-178).** A second right click no longer closes a shopkeeper's conversation; a camera in a shop's hours says *Staff only* and reports only somebody who stays in the back; cameras ignore people on the lawn or pavement outside a building; a keeper never stands outside their own wall; and the people who serve at counters are spawned first, so the shop a player walks into is staffed.
* **A batch is its maker's (D-180).** A station remembers who started its batch; only they or their Crew take it, the XP is theirs, and nobody can break the station to get at it. A pile of two recipes pays each recipe's XP once (it used to pay the last recipe's for every batch). A batch left two hours of play is anybody's.
* **A station's countdown runs on screen and Take lights by itself.**
* **Crew members see every part of a Crew contract**, the other trades' parts with whose they are and the Supplier button.
* **The Skills page has a General tab** (Trading, Social, the Thief track): level, XP, what each gives now and next, and how it is earned.
* **Owner tools.** `/uc_prepare` sets up a prepared session (D-179); PowerShell scripts for the Windows workflow; the playtest guide's section 6i is three sessions (organic solo, organic co-op on a server, prepared advanced).

## Evidence

**In progress.** This report is being written during the pass; the evidence table, what is not done and the bugs list are filled in as the client runs finish.

## Not done, and why

## Bugs found and fixed in this pass

Each was found by a scripted client, a GameTest, CI or a code review in this pass, and fixed where it starts.

| Bug | Found by | Fix |
| --- | --- | --- |
| A second right click on a shopkeeper closed the conversation: the replaced screen told the server it had ended, and the server answered a choice it did not know by closing it | Smith client run | a replaced screen for the same person continues the conversation; the server ignores "end" and anything said from more than 10 blocks away (D-178) |
| A shop's camera called the police on a customer at the counter in opening hours | Farmer client run | in hours a camera warns first (*Staff only*) and reports only somebody who stays in the back (D-178) |
| Cameras watched people on the lawn or the pavement outside a building | Smith client run | a camera watches only somebody indoors: under a roof, and not in the open cells by an exterior door (D-178) |
| A shopkeeper could stand outside their own wall, unreachable across it | Smith client run | a keeper's stand cell must be open to the counter at head height, and diagonally through one open side (D-178; GameTest) |
| The hardware desk's foreman never appeared: counter staff were spawned in the same order as everybody else and the cap was reached first | Smith client run | the people who serve at counters are spawned first (D-178) |
| A request the server dropped because another was in flight was lost silently, and the screen waited for an answer that never came | Smith and premises client runs | the server answers a dropped request with a fresh view (at most once every half second) |
| **The Crew and Home screen (K) would not open** near trade premises, a comfortable apartment or the villa, or anywhere for somebody who held one: a home's kind is its property category, three category ids are longer than the 16 letters the screen's packet allowed, encoding failed, and the server's work queue kept the error to itself | premises client runs 2 to 4 (after D-181 put the premises on the list), found by logging the queue's errors | the kind may be 40 letters, every string in the packet is cut to its field instead of failing, the Crew protocol is version 2, and a failed Crew request is written to the log (GameTest: every category round-trips) |
| **The tenant of the commercial workshop could not open its iron street door**: the door lies inside the premises, where the building's guard stands aside for the home's own, and the home's guard let the tenant through without anything opening an iron door | premises client run 5 | the home's guard opens an iron door as a click opens a wooden one, for those it lets through only (GameTest: the tenant opens and shuts it, a stranger does not) |
| **Nobody could walk into the Blackwater lofts** (D-176): the plaza's hedge planters left gaps in the middle of each side and the lofts' four street doors are not there, so a hedge stood before every door; and the Valewood cottage's sofa ended right behind its front door | homes client run 1 (it found the lofts and no usable door) | the planters' gaps are in front of the doors and the sofa has two seats; a test walks every street door of every furnished building (24 doors) and fails on anything blocking the cell before or behind it; chunks already generated keep their hedges |
| A station's countdown on screen fell behind real time when the client's frame rate dropped, so Take stayed grey long after the batch was done | Vintner client run 7 | the countdown keeps real time and the screen asks the station again every ten seconds while a batch runs |
| Find a home showed only the eight easiest homes within 64 blocks, so the workshop premises a player stood at could not be rented | premises client run 1 | the homes of the building the player stands at come first, and the list holds sixteen (D-181) |
| Anybody could take the batch another player had started, and break the station to get at it; a pile of two recipes paid the last recipe's XP for every batch | code review | a batch is its maker's (D-180; GameTest) |
| A station's countdown stood still on screen and Take did not light until the screen was reopened | Farmer client run | the station refreshes the open screen when the batch is done, and the seconds left round up |
| The new batch-ownership clock wrote a negative time for a batch finished at tick zero, and the GameTest caught it in CI | CI | a finished batch's time is at least 1 |
| A station screen sent a stale view over a fresh one when the server's refresh and the screen's own request crossed | code review | a view that arrives within 1.5 s of a refresh, with no matching screen open, is ignored |
| `/uc_prepare` read item registries while its class loaded, before they existed | local client run | the kits are built when first used |
| A missing part's "where to get it" line could overflow a station's tooltip | Farmer client run | the line is cut to 110 characters |

The scripted clients themselves were made more robust where a person would simply try again (a lost key tap, a buy click that raced the screen's refresh, a hotbar tap that missed, a recipe search that stopped short of the book's last page); those are test changes, not game fixes, and each is noted in the run that needed it.

## Next
