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

## Next
