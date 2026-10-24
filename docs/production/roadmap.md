# Development roadmap

## Phase gates

| Phase | Scope | Exit criteria |
| --- | --- | --- |
| P0: Foundations | Design, legal, repo hygiene, compatibility and architecture audit | Documents consistent; no unlicensed files; CI docs check green; loader/mod candidates tracked |
| P1: Runtime baseline | Forge 1.20.1 hypothesis, Java 17, empty mod scaffold, mod integration spikes | Exact versions pinned; reproducible client and dedicated server boots; 2 clients connect; logs clean |
| P2: City sandbox | Small explorable district + farmland + one road; generated/imported building test | Reliable chunk loading, no broken terrain/seams, playable travel, acceptable performance |
| P3: Identity and skills | First-login onboarding, city/career assignment, one general skill, persistence | Rejoin/restart retained; alternate account isolated; no exploitable XP |
| P4: Economy loop | Wallet, stock, NPC buyers, a contract, fair tax timing, first production | Earn-spend-produce-deliver-tax cycle complete; ledger reconciles after restart |
| P5: Career depth | Farmer and Gunsmith early tree, blueprints, workstation gates, recipes | 2 career loops reachable without other humans; no recipe dead ends |
| P6: City life and loot | Named NPCs, schedules, contextual scavenging and loot (Lootr was reviewed and not adopted) | Shops work; personalized loot tested; background sim doesn't duplicate goods |
| P7: Conflict | Limited faction patrols, witness-based crime, fine/arrest rules | Co-op safety, no arbitrary arrest, encounter balance, rollback/recovery |
| P8: Expansion | Multiple full cities, connected transport, civics, business growth | World consistency and performance proven; no forced grind |
| P9: Campaign and polish | Story acts, meaningful city changes, visuals, balance | Player journeys and save migrations tested over long-term co-op sessions |

## Where the work stands (October 24, 2026, sixth pass)

This replaces the status lines of October 16 to 21 below, which are kept as history. It was reconciled against the code, the data and the sprint reports on October 24 (sixth pass, owner brief "First Fully Playable Sandbox Alpha"). It changes no decision; decision statuses the owner should settle are listed at the end of this file.

### Evidence levels

Every claim names one level. Levels are never added together: forty GameTests are not a client run, and a scripted client is not a person.

| Level | Meaning | Examples |
| --- | --- | --- |
| L1 | Unit and data checks | `python -m unittest`, `validate_repo.py`, the item-source audit, the economy model's invariants, the burglary audit |
| L2 | Forge GameTests and other headless server runs | the GameTest suites; the road check, city probes and burglary check on the game's own generator |
| L3 | One scripted real client | `-PucPlaytest=<scenario>`: software rendering, synthetic keys and mouse, every shortcut logged |
| L4 | Two scripted real clients at once | `scripts/run_cloud_journey.py`: hosted jobs `journey`, `journey-crew`, `journey-combined`, `journey-full-pack`, `journey-every-pack` |
| L5 | A person played it | the owner's two playtests, both on builds older than most of what is listed here |

**What a person has played.** The owner played twice: the first playtest on the legacy seed-5908 world before the October 20 to 21 overhaul (snow, merged-looking cities, vegetation on buildings, an unreadable Trade page, mineable walls, nothing to do in the cities), and a second on October 21 (a locked street door opened to somebody holding W; a jittery lock screen; D-114, D-115). Both were defect reports, and every fix since was checked by scripts only. **No feature below has L5 evidence in its current form**, and no session of the [Windows 11 playtest guide](playtest-guide-windows11.md) has been recorded.

**Two facts shape every row.** (1) Most city content is opt-in: the furnished buildings and the scarcity package load only with `-PucExtraSets=furnished,scarcity` on a new archipelago world, because the generation guard (D-118) refuses to change how an existing world generates. Without them a new world has stock Lost Cities buildings: no shopkeepers, residents, city homes, fences, cameras by tier or staff rooms. (2) Two-client evidence (L4) covers registration, work orders, Q Shop trades, the civilian counter, Crews and their bank, leases, rent, a Crew-funded purchase, a backpack and an abrupt server kill, and from the sixth pass a Crew contract (server-driven). Nothing else built from October 21 onward has run with two clients.

### Phases P0 to P9

| Phase | State | Highest evidence | L4 | L5 |
| --- | --- | --- | --- | --- |
| P0 Foundations | partly met: documents drift; this reconciliation fixes the named stale lines | L1 (`validate_repo.py`, the asset register, hash-pinned runtime downloads, the private-content check) | n/a | n/a |
| P1 Runtime baseline | met in the cloud on Linux; not on Windows or with a real launcher account | L4 (`journey` and its pack variants; `run_prod_boot_check.py`) | yes | no |
| P2 City sandbox | partly met: roads walkable end to end (6 of 6 road ends), arrival hitch removed at its cause (D-167); no dedicated-server measurement with two players | L3 (journey runs 57 and 65 walked Ironhaven to Valewood both ways; hitch runs 1 to 4) | legacy world only | legacy world once |
| P3 Identity and skills | met for registration and persistence; partly for skills (three general skills earned in play: the Thief track, Trading and Social, D-174; no panel) | L4 (reconnect, restart), L3 (thief run 12; Trading and Social XP seen in workshop run 4 and civic run 3), L2 (their effects) | yes | no |
| P4 Economy loop | partly met: earn, spend, produce, deliver and reconcile after a restart; **no tax** (rent on active time stands in) | L4 (Crew and combined journeys), L3 (Cannabis run 6, workshop run 3) | yes | no |
| P5 Career depth | five careers with a legal loop each; the Gunsmith now builds real TaCZ firearms (D-168) | L3 for the Farmer, Vintner and Technician; L2 for the Gunsmith and Smith | first vanilla orders only | no |
| P6 City life and loot | substantial but opt-in; contextual loot by room and tier; **per-player loot absent** (Lootr reviewed, not adopted) | L3 (crowd runs, thief run 12, journey run 65, workshop run 3) | civilian counter only | no |
| P7 Conflict | burglary, witnesses, alarms, police beat, arrest and fines, one raid (D-173, L3); no factions or jail | L3 (burglary, alarm, thief, heist, patrol and organic runs) | none | no |
| P8 Expansion | three cities, roads, roadside places, freight on foot, trade premises (D-169), one civic project (D-171, L3); no vehicle economy | L3 (freight run 5, the October 16 drives) | none | no |
| P9 Campaign and polish | not started (no story acts); save migrations piecemeal; UI captured at four viewports | L2 (migration GameTests), L3 (captures) | none | no |

### Work packages WP1 to WP12

The [expansion plan](expansion-plan.md) is still a proposal (D-088). Since October 21 the work followed owner briefs, so its "recommended next three sprints" were overtaken.

| WP | Plan | State on October 24 | Highest evidence |
| --- | --- | --- | --- |
| WP1 | Crew contracts | **built** (D-170): two contracts, parts by career, parts pay their deliverers, bonus in equal shares owed and paid once, a supplier fills a missing part | L2; a step of the L4 Crew journey (server-driven, two clients connected) |
| WP2 | Skill growth | partly: real effects (D-098), skill gates (D-158, D-163), the Thief track (D-143), Trading and Social earned in play with capped effects (D-174); no perk table, no general-skills panel, nothing past level 30 | L3 (thief run 12; Trading and Social XP in workshop run 4 and civic run 3), L2 (their effects) |
| WP3 | Property | largely built: rent on active time, grace and lapse, upgrades, Crew bills; 8 of 10 categories have a building; trade premises (D-169); the larger apartment and premium residence have none; no property tax | L4 (rent in the Crew journey) |
| WP4 | Illegal production | partly: Cannabis with drying, moonshine, unmarked kits and unmarked TaCZ builds (D-168); no hidden facility kind | L3 (Cannabis runs 6 and 9, law run 2) |
| WP5 | Advanced manufacturing | partly: seven Core stations, the Electronics Bench, kit-to-firearm builds at the Gun Smith Table | L3 (workshop run 3, tech run 5), L2 (TaCZ builds) |
| WP6 | Contextual loot | partly: furniture by room and tier, salvage, quarry and pit; beginner loot rebalanced (D-172); no per-player rolls | L3 (thief run 12) |
| WP7 | Burglary | largely built; free-form, no mission kind | L3 (heist run 8, organic run 5) |
| WP8 | Law and raids | response, arrest, patrols; **one raid** (the bonded store, D-173) | L3 (patrol runs, raid run 3) |
| WP9 | Survival | partly: goods on sale; no fuel, no medical item | L4 (combined journey, server-driven) |
| WP10 | Populated districts | partly: routines by clock, density by area and hour, people within sight; no off-screen simulation | L3 (crowd runs 4, 5) |
| WP11 | Transport | barely: bicycle and motorboat lines, freight on foot, orders tracked on the status display and Xaero | L3 (October 16 drives, freight run 5) |
| WP12 | Human playtest | two defect-finding owner sessions on older builds; no guide session | L5 (older builds only) |

Per-phase and per-package detail, with what remains as a player experience, is in [Reconciliation detail](#reconciliation-detail-october-24-2026) at the end of this file.

## Milestone: First Fully Playable Sandbox Alpha

The next milestone is not a feature count. It is met when **the owner and one friend can start a new world, play for hours, and keep finding something worth doing**, without a developer at hand. It replaces nothing in [mvp-v0.1.md](mvp-v0.1.md); it is the milestone after it, and the MVP's own missing items (personal loot, a tax, quests) are listed below where they apply.

**The alpha world.** A new archipelago world started with the furnished cities and the scarcity package (`gradlew runClient -PucExtraSets=furnished,scarcity -PucDevWorldName=<new name>`, or the server pack with the same sets), with Q Shop, TaCZ, TaCZ Blueprints, Modern Life, Immersive Vehicles and Sophisticated Backpacks. The owner's existing world is never converted.

**Exit criteria.** Each needs the evidence named; the alpha is **not proven until criterion 12 is met by a person**.

| # | Criterion | Evidence needed | Where it stands (October 24) |
| --- | --- | --- | --- |
| 1 | A new player registers, chooses a city and a career, and in the first 30 minutes earns credits through legal work, buys something useful, and gains XP visibly, without a free skill or resource | L3, then L5 | L3: journey run 66 (28 steps with real input, 3 logged shortcuts, the road walked both ways); L5 none |
| 2 | Each of the five careers has a solo loop from its starter kit to a level-10 contract with no dead end | L1 item-source audit + L3 per career | L1 holds; L3 for Farmer, Vintner, Technician; Smith and Gunsmith L2 |
| 3 | Each career has an underground option on the same character | L2 + L3 for at least two | L3 for the Farmer (Cannabis grown, dried and policed, sixth pass) and any career's raid; back channel and unmarked builds L2 |
| 4 | A home: rent a first place, upgrade it, move up at least two rungs, with no offline charge | L2 + L4 | L4 for the Row's lease and rent; 8 of 10 rungs physical |
| 5 | A business: premises that make the work better, never idle income | L2 + L3 | L2 (D-169) |
| 6 | Two players cooperate on something neither does alone and the credit is fair | L4, then L5 | L4 server-driven Crew contract step (sixth pass); L5 none |
| 7 | Crime has preparation, risk and consequence: a burglary, a fence, a witness, an arrest, one raid | L3 | L3 for burglary, fence, arrest and the raid (raid run 3) |
| 8 | The world changes because of the players: one civic project with a persistent consequence | L2 + L3 | L3 (civic run 3: the stalls built and photographed); the restart is L2 |
| 9 | Performance: no Core-caused tick over 250 ms walking or arriving in a city; 30 minutes of two players on a dedicated server with p99 under 100 ms | L3 (hitch scenario) + a dedicated-server measurement | hitch fixed at its cause (D-167, L3); no dedicated-server measurement |
| 10 | Save safety: an abrupt kill and a restart lose nothing and pay nothing twice | L2 + L4 | L4 (combined journey); every new journal kind GameTested |
| 11 | Every screen a player meets is legible at 1280x720, 1080p GUI 2 and 3, and 3440x1440 GUI 2 | L3 captures, inspected | captured each pass; the sixth pass's two new cards at all four, looked at, three defects fixed |
| 12 | **A person plays two sessions of at least two hours** following the playtest guide, files the questionnaire, and wants to play a third | L5 | **not done** |

Out of scope for the alpha: story acts, factions and warfare, a vehicle economy (fuel, repair, insurance), property tax, a market between players, per-player loot, the larger apartment and premium residence buildings.

## Status history (October 16 to 21, kept as written)

Superseded by the October 24 reconciliation above; ordered by date.

### October 16, 2026


Evidence, not intent. P0 and P1 are met for the starter slice. P2 is **partly** met: three roads and bridges generate, have been surveyed block by block on seed 5908, and **a scripted player has walked one of them (Valewood - Blackwater, 3.75 minutes of walking) and driven and sailed others**; Founders Row is a populated lot (nine named citizens) built by an operator or by the dev launch, not a district of a city, and nobody human has walked any of it. P3 is met for registration and persistence (a first-login screen, command-driven persistence). P4 is met in miniature (wallet, stock-bound exchange, contracts) with no tax cycle. P5 has **early loops for all four careers** with a legal and an underground path each, skill trees, a blueprint-to-gun Gunsmith loop and the Technician's trace. P6 to P9 have not started beyond the rare-loot rule, the evidence-based law model (a seeded die at checkpoint zones, now with posts and a guard) and vehicles that a script can drive. The sprint reports list the details and the limits: [October 15](sprint-2026-10-15.md) and [October 16](sprint-2026-10-16.md).


### October 18, 2026

Added since October 16 (evidence in the [October 18 sprint report](sprint-2026-10-18.md)): **Crews** (membership, roles, a Core-owned crash-safe bank over Q Shop credits, three finance modes), **leasable homes** with a server-side guard (the Row Flats), a Crew screen on the native toolkit, and a two-client journey with an abrupt server kill and a restart; a **recovery policy for wayside shelter placement** (a failed or lost shelter is retried, rebuilt or reported, never hidden); an **item-source audit** that found and closed a real dead end in the Gunsmith's top contract (the rifle blueprint now comes from a component run); and an **opt-in survival set** (backpack, thirst, kitchen, sleeping bags) with boot and GameTest evidence. P5 is therefore deeper for the Gunsmith and gains a cooperative layer; **P6 to P9 are unchanged**: no populated routines, no police response, no raids, no burglary, no rent, no vehicle fuel, no boat customs. Same-day follow-up ([PR #23 report](pr23-playtest-readiness.md)): a **critical bug in Crew-funded purchases was fixed** (D-089), the server pack authenticates (D-090), the home guard covers pistons, hoppers, frames, stands, fluids, fire and mobs, survival goods are **on sale** in cities and at wayside provisions desks (D-091), and a **combined journey** runs on a generated Lost Cities world with every pack set and two real clients through an abrupt server kill. **Nobody has played any of it**: the next gate is a person, using the [Windows 11 playtest guide](playtest-guide-windows11.md). The ordered plan for the rest is the [expansion plan](expansion-plan.md) (a proposal for the owner's decision).

**Update, October 19, 2026** ([sprint report](sprint-2026-10-19.md)): the world is now a **simulation with no ordinary monsters** (D-094) and every item that needed a monster's drop has a route (D-095); the backpack provider is **Sophisticated Backpacks + Core** under one policy file and the Crew's home rules (D-092, D-093); the Lost Cities long-tick stall was root-caused and mostly removed (D-096); first starts of the new mods were measured (D-097); and the combined journey now has a real client buying, wearing and filling a backpack on the real screens. **P6 to P9 are unchanged and nobody has played any of it.**

**Update, October 20, 2026** ([sprint report](sprint-2026-10-20.md); owner brief "Economy, Housing, Rent, Professions and Meaningful Progression"): a reproducible **economic model** ([economy-model.md](../technical/economy-model.md); D-098) with invariants that fail on a buy-sell loop, an idle profit or a vanilla delivery that beats real production; a **property economy** (rent, purchase, deposits, active-time billing, grace and lapse that never take belongings, Crew-owned bills, upgrades, migration; D-099); **skill trees with real effects** (multi-prerequisite, capped, consumed by the pay, yield, exchange, upgrade and checkpoint systems; scrolling page; D-098); **vehicle and boat price tiers** with a bicycle and a motorboat on sale (D-101; no fuel, no repair); a **checkpoint that searches what a person carries, including a Sophisticated Backpack**, and nothing else (D-100); a re-evaluated backpack price and five more enabled upgrades (D-102); and a fail-closed guard against loading a world with Traveler's Backpack items (D-103). **P6 to P9 are unchanged and nobody has played any of it.**

**Update, October 21, 2026** ([sprint report](world-playability-overhaul.md); owner brief "World, Playability and Modpack Overhaul"): an **archipelago world** with a levelled landing, protected and lockable buildings, named people, a status display, a civilian counter and helper mod sets, all scripted, none played; the next steps, in order, are a scripted and then a human first half hour on the archipelago, entrances for sealed buildings with a police and witness response, gate guards and a harbour, and people with a purpose.

Phases are **outcome gates, not calendar promises**. The order may change when research uncovers technical constraints.

## Critical path

Legal review → exact-version server compatibility → reproducible pack → world prototype → persistent player identity → a working civilian economy → one career loop → NPCs → crime/war → city expansion.

## No-go conditions

- Mod candidates crash on a clean dedicated server or conflict without an acceptable workaround.
- Map material lacks applicable permissions.
- World generation can corrupt a live save due to changed profiles.
- A feature requires absent human players with no NPC alternative.
- Data-driven gameplay has duplication, money creation, or migration loss.

Track each spike with test evidence before declaring the component selected.

## Reconciliation detail (October 24, 2026)

Read against commit `78e672c` (the fifth pass) by a separate read of the code, data and reports, then amended for what the sixth pass added. "Not verified" means the claim could not be checked against code or a named run.

### Phases

#### P0 Foundations

* **Goal (roadmap):** design, legal, repository hygiene, compatibility and architecture audit.
* **Exists:** `validate_repo.py` and the `repository-checks` workflow; `docs/legal/asset-register.csv`; `docs/technical/mod-candidates.csv` and `mod-audit.md`; the private-content workflow with a check that no private path is tracked (D-116); hash-pinned runtime downloads in `content/modpacks/dev-pack.json`, nothing third-party committed.
* **Evidence:** L1.
* **Missing exit criterion:** "Documents consistent." Section 3 below lists stale statements in four production documents, and several decision statuses no longer match the code (see "Decision statuses" at the end).
* **Risks:** documents drift faster than they are reconciled. Five passes in two days each added a report; the roadmap's own status stops at October 21.
* **Next step:** apply section 3 and ask the owner to settle the decision statuses listed at the end.

#### P1 Runtime baseline

* **Goal:** exact versions, reproducible client and dedicated-server boots, two clients connect, clean logs.
* **Exists:** Minecraft 1.20.1, Forge 47.4.10, Java 17 (D-007); `gradlew runClient` and `runServer` with the pinned pack (D-059); `run_prod_boot_check.py` (every set, reobfuscated jar); `make_server_pack.py` with `online-mode=true` (D-090); a repeated first-start check (D-097: 38 starts, no failure).
* **Evidence:** L4. The hosted journeys connect two real clients to a dedicated server and restart it.
* **Missing:** a real Microsoft account joining the authenticated server; the normal launcher with the pinned jars; Windows; "logs clean" (the journey server still runs with `max-tick-time=-1`, set in `scripts/run_cloud_journey.py` because of Lost Cities stalls (PR #23)). A production-style client cannot be launched in the cloud, so Entity Culling and ImmediatelyFast stay parked (D-113).
* **Depends on:** the owner's machine for Windows and a real account.
* **Next step:** guide section 3b on Windows with two real accounts.

#### P2 City sandbox

* **Goal:** a small explorable district, farmland and a road; reliable chunk loading, no broken terrain, playable travel, acceptable performance.
* **Exists:** the archipelago world type (`IslandField`, `IslandPlan`, `WorldLayout`; D-104, D-111, D-112), three generated roads with bridges (`CorridorRoadFeature`, planned on a background worker), walkable road ends (`RoadApproaches`, D-137), waymarkers and wayside shelters (D-078, D-079, D-086), six roadside places built a slice per tick (`RoadsidePlaces`, D-138, D-148), road encounters (D-139), a levelled landing with Founders Row (D-107, D-111), protected and lockable buildings (`BuildingGuard`, D-105, D-114), thirteen furnished building types (opt-in; D-117, D-133, D-141, D-153), the generation guard (D-118), two regrowing resource sites (D-123, D-134). The legacy seed-5908 triangle still opens with `-PucWorldLayout=legacy`.
* **Evidence:** L3. Journey run 57 walked the first hour and the road both ways with real keys (return 241 s); run 65 added the courier and the diner. The road check (L2) found all three roads continuous and 6 of 6 road ends walkable in a new world.
* **Two clients:** the combined journey ran on a generated Lost Cities world, but the legacy layout. None on the archipelago or the furnished package.
* **Person:** the owner's first playtest was on the legacy world. Nobody has walked the archipelago.
* **Missing exit criteria:** "acceptable performance" is not measured on a dedicated server with players. "No broken terrain" holds only for what was probed: a ring of chunks at each city's edge was not re-probed after the climate change, and a cave cut ten cells out of one apartment block.
* **Known bugs and risks:** Lost Cities long ticks are reduced, not removed (D-096; `forceSaplingGrowth = false`); the arrival hitch of about 600 ms (crowd runs 4 and 5), found in the sixth pass to be a wait on Lost Cities' plan lock and removed at its cause (D-167); a 44 s "can't keep up" stall in the latest road check (one run); Founders Row is about 720 blocks from the nearest road end; two of six wayside shelters near Blackwater are never built (no level dry ground); two thirds of stock buildings are sealed scenery; Blackwater has no harbour or swamp, and the registration screen no longer promises one (sixth pass).
* **Depends on:** Lost Cities 1.20-7.5.5 and our styles; Modern Life 1.75 (furnished, all rights reserved, runtime only); D-008 is still "needs spike".
* **Next step:** a person plays guide section 6f on a new furnished archipelago world and times the walk from the landing to the road.

#### P3 Identity and skills

* **Goal:** first-login onboarding, city and career, one general skill, persistence; rejoin and restart kept, alternate account isolated, no exploitable XP.
* **Exists:** `RegistrationScreen` and `RegistrationService` (D-058); `CitizenState`; the guided journey (`Journey.STEPS`, nine steps; D-076); five career trees in `skill_trees/` (12 to 14 skills, 25 to 29 points each) with capped effects (D-098); the Thief track in `specialization_trees/thief.json` (16 skills, level 25; D-143); identity by account UUID (D-090).
* **Evidence:** L4 for registration and persistence (economy journey: reconnect without a second registration, restart); L3 for the Thief track (thief run 12) and for save, quit and reopen (journey step 13, through the client's own calls rather than the pause menu); L2 for name versus account (`crewMembershipMoneyHomesAndPayoutsBelongToTheAccountNotTheName`).
* **Missing:** a real second account; respawn through a client (not verified); a UUID migration (deliberately absent, D-090).
* **General skills:** `ProgressionRules.GENERAL_SKILLS` stores driving, fitness, shooting, trading, social and thievery. Thievery (`Thievery.java`), Trading and Social (`GeneralSkills.java`, D-174) are earned in play: Trading lowers exchange prices inside the shared 10% cap, Social lets legal buyers offer trusted work one step sooner. Driving, fitness and shooting are raised only by the operator command `/uc_dev general_xp`. No screen shows them (notices and `/uc skills` only).
* **Also missing:** nothing past level 30, no respec rule (still open in `general-skills.md`).
* **Next step:** a panel for the general skills on the terminal; a consumer for driving once a vehicle economy exists (D-004).

#### P4 Economy loop

* **Goal:** wallet, stock, NPC buyers, a contract, fair tax timing, first production; earn-spend-produce-deliver-tax cycle complete, ledger reconciles after restart.
* **Exists:** Q Shop 1.8.4 as the only wallet (D-037, D-039); `WorkOrderLedger` with a prepared-settlement journal (D-040); 88 work-order files; finite shared regional stock with active-time refill (`RegionalMarket`, `RegionalStock`); buyers with finite demand and trust; the economy model with invariants (D-098, D-130); rent and purchase on active household time (D-099); shops that buy from suppliers (D-164); fences (D-125); ore scarcity, shelves and regrowing sites (D-120, D-123, D-134).
* **Evidence:** L4 for "ledger reconciles after restart" (Crew journey and combined journey, each with the server JVM halted mid-transaction). L3 for production to payment (Cannabis run 6, workshop run 3).
* **Missing exit criterion:** **tax**. No tax, treasury or city budget exists in code (no match for the word in Java or data). Rent is the only recurring charge.
* **Risks:** every price and minute is the model's (D-098). The model says the Farmer's first profit takes 31 minutes and a lone Vintner never rents the house within 400 hours. A crash can lose up to five minutes of a home's rent clock (in the tenant's favour).
* **Depends on:** D-003 (taxes as intent) and D-012 (no offline taxation, still "proposed").
* **Next step:** an owner decision on what a tax is (on sales, on property, on income) and when it runs; then one tax cycle on active time with a crash GameTest and an L4 journey.

#### P5 Career depth

* **Goal:** early Farmer and Gunsmith trees, blueprints, workstation gates, recipes; two career loops reachable without other humans; no recipe dead ends.
* **Exists:** five careers (D-067); skill effects (D-098); seven workshop stations with tiers fitted from real parts (`Workshops`, `workshops/*.json`; D-163); the Technician's legal trade, Electronics Bench and computer (D-158, D-165); Cannabis growing and drying (D-160); the server-side TaCZ Blueprints gate (`BlueprintGate`, D-073); the rifle blueprint route (D-087); the item-source audit (0 findings).
* **Evidence per career:**
  * Farmer: L3, Cannabis run 6 (seed to licensed delivery; 12 shortcuts, including random tick speed 120). Grading Table and Mill: L2 only.
  * Vintner: L3, workshop run 3 (batch, tier 2, sale to the pub; 6 shortcuts; last 22 blocks a teleport). The XP fix found in that run was not re-run in a client.
  * Smith: L3 for the first order only (journey steps 5 to 8). Machinist's Bench: L2.
  * Gunsmith: L2, `theRestrictedRifleChainRunsFromExchangePartsThroughTheBlueprintAndTheTableToPaymentAndTrust` on real TaCZ, and from the sixth pass kit builds of real TaCZ firearms with a serial or unmarked (D-168, two GameTests with TaCZ); L3 for firing a Glock (October 16 `gun` scenario). The table's screen was never used with a mouse. Gunsmith's Bench: L2.
  * Technician: L3, tech run 5 (11 steps), technav run 5 and tech run 7 (18 steps each). The back channel (D-166): L2 only.
* **"No recipe dead ends":** L1 audit plus an L2 test that reads every loaded recipe (D-095).
* **Missing:** a Gunsmith loop through a client; the Farmer's, Smith's and Gunsmith's stations through a client; anything past level 30. The underground "hacking" side was not built beyond the abstract back channel (`professions-business-pass.md`).
* **Risks:** until the sixth pass the Gunsmith's Bench made parts kits that no TaCZ recipe used; D-168 joins them (kits build fourteen TaCZ firearms at the Gun Smith Table), at L2 only.
* **Next step:** one L3 run per remaining station, then a Gunsmith run from bench to a delivered gun.

#### P6 City life and loot

* **Goal:** named NPCs, schedules, Lootr content, contextual scavenging; shops work, personalised loot tested, background simulation does not duplicate goods.
* **Exists:** Founders Row's twelve named citizens (D-061, D-075; no mayor, no researcher); street people by area and hour within a density setting (`PopulationService`, D-108, D-162); residents and workers by the clock who walk in and out of street doors (`HouseholdService`, D-124, D-129); shopkeepers who sell (`ShopCounters`, D-126) and shops that buy (`ShopSupply`, D-164); searchable furniture by tier (`BurglarySearch`, D-144); the salvage yard's scrap heaps (D-138); exceptional finds (D-053); four road encounters (D-139).
* **Evidence:** L3. Crowd runs 4 and 5 counted 14 to 16 people in sight on an Ironhaven commercial street at noon (4.5 before). The journey bought at a city counter and at the diner. Workshop run 3 sold wine to the pub.
* **Two clients:** only the civilian counter (economy journey).
* **Missing exit criteria:**
  * "Personalised loot tested": **not met.** Search spots are keyed by position and shared between players (`BurglarySearch.Records`, saved as `underground_city_searched`). Lootr was reviewed and not adopted (October 22: it would be a second owner of stashes and stolen marks).
  * "Background simulation does not duplicate goods": there is no off-screen simulation to test (D-014 still "proposed"). People are never saved. Shelves move only in active time.
* **Also missing:** NPC memory of the player (a household remembers a forced door for three active hours, nothing more); relationships; story characters; NPC-originated work beyond a shopkeeper asking for stock.
* **Risks:** residential and night streets show one or two people; Valewood's hills hid people (the sixth pass prefers streets near the player's height; not yet measured); the population pass costs 22 to 27 ms a tick in one process with a software-rendered client (not a dedicated-server figure). All of it needs the opt-in furnished package except the street people.
* **Next step:** a decision on per-player loot (instanced or shared by design), then two clients in one street (population budgets, shop sales).

#### P7 Conflict

* **Goal:** limited faction patrols, witness-based crime, fine and arrest rules; co-op safety, no arbitrary arrest, encounter balance, rollback and recovery.
* **Exists:** city laws and checkpoint zones (D-051); posts with a guard, placed by an operator (D-062); searches of everything carried, backpacks included (D-100); heat and trust; alarms and witnesses (`PoliceResponse`, D-122); arrests with fines through a crash-safe seizure; police who look (D-146) and walk a beat (`PolicePatrols`, D-155); cameras by tier (D-149); alarm panels and a Technician's jam (D-128); stolen-goods marking and fences (D-125, D-147); the Thief track, tiers, casing and staff-only rooms (D-143 to D-145, D-157, D-159); the law on growing Cannabis in public view (D-160).
* **Evidence:** L3. Burglary run 3 (October 21: an officer walked to the thief and arrested them); alarm run 2; thief run 12 (22 shortcuts); heist runs 8 and 10 (10 shortcuts); patrol runs 2, 6 and 7; organic run 5 (no spatial shortcut inside). The growing law: L2 only.
* **Two clients:** none. "A partner beside an officer with marked goods is not arrested" is a GameTest.
* **Missing:**
  * factions: no code at all;
  * raids (D-033): one, the bonded store job (D-173, sixth pass; raid run 3 through a real client);
  * jail or holding: none (officers never enter buildings and cannot be hurt, `PoliceResponse.java` line 40);
  * gate guards at the bridge ends, posts placed at world creation, boat customs;
  * "encounter balance": not measured.
* **Known gaps:** checkpoint evasion (a bundle in a shulker box, the cursor and crafting grid); a passenger answers for the driver's cargo (open design question); a seizure from a vehicle hold is not journaled (`checkpoint-search.md`); no crowbar or window entry; no pursuit across a city edge or after a disconnect.
* **Depends on:** P6 people (witnesses) and the furnished package (fences, cameras, staff rooms).
* **Next step:** a two-client burglary (one thief, one partner without evidence) at L4, then a person plays guide section 6h.

#### P8 Expansion

* **Goal:** multiple full cities, connected transport, civics, business growth; world consistency and performance proven, no forced grind.
* **Exists:** three cities with their own styles and furnished buildings (opt-in); city homes on the Row's terms (D-127); roadside places with a motel and a farmhouse to let (D-138); freight from the Northgate depot (`Freight`, D-156); bicycle and motorboat dealer lines (D-101); vehicle crates searched at checkpoints (D-077).
* **Evidence:** L3. Freight run 5 (paid 64 credits in Valewood; travel by teleport). October 16: a Quad drove 200 blocks and a Motorboat sailed about 130 blocks (creative mode, so the car burned no fuel).
* **Missing:**
  * civics: one public project, the Valewood market (D-171, sixth pass; civic run 3 through a real client); no tax, treasury, city leaders, elections or diplomacy;
  * business growth: trade premises (D-169, sixth pass: a better workshop and bulk orders for whoever works there, at the home's own active-time cost); no hiring or business revenue;
  * transport: no fuel economy, repair, insurance, car dealer, boat post, customs, harbour or port, boat taxi, or contract that needs a vehicle;
  * performance with players on a dedicated server.
* **Next step:** owner decisions on civics and on vehicle fuel (D-101 leaves cars as a priced plan).

#### P9 Campaign and polish

* **Goal:** story acts, meaningful city changes, visuals, balance; journeys and save migrations tested over long co-op sessions.
* **Exists:** no story or campaign code (`docs/design/story.md` is design only). Save migrations exist piecemeal: `CrewLedger` schema 2, `PropertyLocks` schema 2, registry aliases for the renamed crop (`RenamedIds`, D-142), the refusal of a Traveler's Backpack world (D-103), the generation guard (D-118). Screens are captured at four viewports and looked at (L3).
* **Missing:** everything in the exit criterion. No long co-op session exists at any level.
* **Next step:** none before P4 to P7 have L5 evidence.


### Work packages

#### WP1 Crew contracts

* **Plan:** a `crew_contract` data kind inside `CrewLedger`'s file; parts by career; idempotent delivery; an NPC supplier for a missing part; rewards split by rule, XP only to whoever delivered.
* **Now (sixth pass, D-170):** `CrewContracts` with two contracts (the depot canteen, the Valewood harvest fair). Each part is an ordinary work order a member takes and delivers through the journal's single settlement; the part is recorded against the Crew in the same write; when the last part lands the bonus is owed in equal shares to the deliverers and paid once each, now or at the next login; a supplier fills a part nobody can make at a stated price, and a filled part earns no share. The Crew bank is not touched. L2 (two GameTests, a restart replay), and a step of the hosted two-client Crew journey that is server-driven.
* **Stale in the plan:** its "Today" line ("nothing") and the `crew_contract` data kind inside `CrewLedger` (the parts live in the work-order journal instead).
* **Remaining, as a player experience:** two players in one Crew (say a Smith and a Gunsmith) accept one Crew contract on the terminal, each delivers their own part, and see the reward land in the bank or their wallets by the rule shown before accepting. With one career missing, an NPC supplier fills that part at a stated price. Proof: crash GameTests per part, an L4 journey with a kill between parts, then a person judges the split.

#### WP2 Skill growth

* **Plan:** general skills (driving, negotiating, lockpicking, first aid, survival) as a second tree; effects only where a system consumes them; one Crew perk table.
* **Now:** four capped percentage effects consumed by real systems (D-098); skill gates for workshop recipes and tiers (D-163) and the Technician's jobs (D-158); the Thief track as the first general skill with its own level, points and tree (D-143); Trading and Social earned in play with one capped effect each (D-174). Driving, fitness and shooting are stored but nothing awards them (see P3). No Crew perk table exists. No tier past level 30.
* **Stale in the plan:**
  * line 50: "effects are limited to a capped inspection modifier, exchange discount and licences" (D-098 added four effects; D-158 and D-163 added gates);
  * line 50: "General skills have a design ([general-skills.md]), **no implementation**" (the Thief track is implemented; six general skills are stored).
* **Remaining:** a level-30 player can choose a second specialism or a mastery tier and see one new capability per tier in play. A second general skill rising from a real activity and changing one system, capped, with a stacking test, now exists (Trading and Social, D-174); a screen that shows them does not.

#### WP3 Property: rent, upkeep, upgrades

* **Plan:** guard extensions, rent on active play, upgrade levels as data, transfer between player and Crew, a property page.
* **Now:** all five build items exist: the guard gaps of D-090; rent on active household time with grace and lapse that never take belongings, three upgrades, transfer and Crew bills (D-099); a Home page; homes in the furnished cities (D-127); a motel and a farmhouse at roadside places (D-138). **Eight of the ten categories have a building** in a new furnished archipelago world (`properties/ladder.json`: listings and city listings). Only the larger apartment and the premium residence have none. From the sixth pass the commercial workshop and the warehouse are **trade premises** (D-169).
* **Stale in the plan:**
  * line 59: "seven of the ten property categories exist only as prices" and "*Not built:* ... buildings for seven categories" (now two categories lack a building);
  * line 62: "Decide: rent versus one-off price" (decided by D-099).
* **Still missing:** property tax, price movement, a market between players, burglary of a lapsed player home, the guard gaps of fire from outside, lightning, fluid dispensers and other mods' vehicles.
* **Remaining:** a person rents a city flat on Find a home, plays an hour, logs out and back in, and sees exactly one active hour billed; a Crew pair upgrades a home and sees both clocks run once. L4 with a city home (not only the Row) and then guide section 6d by a person.

#### WP4 Illegal production

* **Plan:** a hidden plot, a still and unregistered builds, each profitable and discoverable; a `facility` data kind with heat and discovery by evidence.
* **Now:** the Cannabis loop with a drying rack (D-142, D-160); officers on the beat pull up a plant grown in public view in Ironhaven or Valewood, only while its grower is online (L2); the Vintner's Pot Still makes moonshine, restricted everywhere (`workshops/pot_still.json`, `contraband/restricted_spirits.json`); the Gunsmith's Bench makes Unmarked Parts Kits (D-163); the Old Cannery den sells lockpicks and kits (D-138).
* **Not built:** a `facility` data kind; a hidden indoor plot with discoverability by evidence (a witness or a tip); curing, packaging or quality grades for Cannabis.
* **Stale in the plan:** line 68 lists "spirits through Vinery/Kaleidoscope Tavern where installed" as the still; Core's own Pot Still now does this without those mods.
* **Remaining:** a Farmer grows Cannabis indoors in a rented home, sells dried flower to Marrow, and is found only if a witness sees the room or a search finds the goods. Proof: L2 for "no discovery without evidence", L3 through the drying rack (not yet run), then a balance session.

#### WP5 Advanced manufacturing

* **Plan:** multi-step chains with a link each for the Smith, Gunsmith and Technician; machines only from licensed non-Create mods, only where the audit names a gap.
* **Now:** Core built its own stations instead of adopting a mod (D-163: seven stations, three tiers fitted from real parts, graded goods, fifteen goods and twelve contracts); the Electronics Bench (D-158). The audit stays at 0 findings and follows the bench's designs.
* **Stale in the plan:**
  * line 77: "recipes exist for 25 products ... as ordinary crafting recipes" (seven stations and a bench now exist);
  * line 78: "machines come from licensed mods that are not Create" (the stations are Core's own).
* **Remaining:** each station runs through a client once (only the Vintner's has), and one chain (Smith's springs to a Gunsmith's kit to the armoury contract) is played by two people, one per link, at L4 and then L5.

#### WP6 Contextual loot and exploration

* **Plan:** places hold what fits them; two players find different things in the same room; salvage nodes that regrow.
* **Now:** searchable furniture by room and tier (D-144); stashes per building class (D-105); scrap heaps at Kessler Salvage that refill in active time (D-138); the quarry and the Valewood pit (D-123, D-134); electronic scrap from heaps and desks (D-158).
* **Sixth pass:** a savings tin and silver cutlery from the shabbiest tier, so a tier 1 house visit is worth about 23 credits to a fence, not 2 (D-172).
* **Not built:** per-player results (search spots are shared by position); wilderness caches; police-site tables.
* **Stale in the plan:**
  * line 85: "*Not built:* ... salvage nodes" (Kessler Salvage, the quarry and the pit exist);
  * line 85: "Lootr is only a candidate" (reviewed and not adopted on October 22, `gameplay-completion-pass.md`).
* **Remaining:** an owner decision whether two players should see different loot. If yes, two clients search the same drawer and each gets their own roll, with no double withdrawal across a relog (L4).

#### WP7 Burglary

* **Plan:** a planned break-in with a lockpick, a time window, noise, a witness check and a way out, in a house with an NPC occupant.
* **Now:** locked street doors and a lock game (D-105, D-114, D-115, D-145); casing and property intelligence (D-144, D-157); households who see (D-124) and tell a trespasser to leave (D-159); a quiet pick that slips past an alarm (D-122); footsteps when an officer is near; alarms, panels, cameras, fences; the Thief track. There is no `break_in` contract kind; burglary is free-form.
* **Stale in the plan:** line 93: "*Not built:* a mission, noise, NPC occupants that react" (occupants react; noise exists as pick quality and footsteps; only the mission kind is missing).
* **Remaining:** two players break into one furnished house at night, one keeping watch; the household's witness, an officer on the beat and the fence behave the same for both, and the Thief XP is attributed correctly (L4). Then a person plays guide section 6h and says whether it is worth doing.

#### WP8 Law enforcement and raids

* **Plan:** witness reports into heat, a bounded response, arrests with a fine and no loss of honest gear, one raid per high-tier contract.
* **Now:** all of it, with one raid from the sixth pass (the bonded store job, D-173: raid run 3 through a real client). Witnessed and alarmed break-ins bring officers (D-122); an arrest takes stolen or forbidden goods and a fine up to the wallet, keeps honest goods, and raises heat; officers walk a beat and chase only what they see (D-146, D-155). Police cannot be fought (they cannot be hurt).
* **Stale in the plan:** line 101: "*Not built:* patrols, response to a witnessed crime, arrest flow, raids" (only raids remain).
* **Remaining:** the plan's own two-client proof (one player with evidence, one without, beside one officer) at L4; more raids than the one bonded store; a measured tick cost with a response active on a dedicated server.

#### WP9 Survival integration

* **Plan:** shelter wardens and city shops sell food, water and bandages; vehicle fuel with a production chain.
* **Now:** survival goods on sale at city exchanges and wayside provisions desks (D-091); Sophisticated Backpacks replaced Traveler's Backpack (D-092, D-093, D-102); the monster-free simulation world (D-094); thirst, kitchen and sleeping bags through the opt-in `survival` set. The survival half of the combined journey is driven by the server, not by clicks, except the backpack (one client, D-092).
* **Not built:** a medical item (none chosen); fuel; a paid "rest".
* **Stale in the plan:**
  * line 109: "the `survival` set is pinned (Traveler's Backpack, ...)" (Sophisticated Backpacks since D-092; the banner at line 3 covers the name only);
  * line 109: wayside shelters "**sell nothing yet**" (they sell since D-091).
* **Remaining:** a person crosses a road with thirst on, buys water and food at a wayside desk and treats an injury with a bought item, never with free healing. The medical item needs a mod choice or a Core item first.

#### WP10 Populated districts

* **Plan:** twelve citizens per district with homes, jobs, a daily routine and memory; cheap off-screen simulation; a hard cap per player.
* **Now:** a roster per city (Ironhaven 54, Valewood 42, Blackwater 48 at first build) with street routines by area and hour and a density setting (D-108, D-162); a resident for each bed and a worker for each counter, by the clock, walking in and out of doors (D-124, D-129); caps per player and in all. The schedules are arithmetic in code from `population/*.json` (counts, mix, names), not one schedule file per citizen. People are never saved.
* **Not built:** memory of a player beyond a forced door; relationships; off-screen simulation of positions or stock (D-014 "proposed").
* **Stale in the plan:** line 117: "*Not built:* routines beyond vanilla's, schedules in data, memory, off-screen simulation" (Core's routines exist; memory exists in its smallest form).
* **Remaining:** a person spends ten minutes on a commercial street and a residential one at two hours of the day and says whether it feels lived in; the tick cost is measured on a dedicated server with two players in one city.

#### WP11 Vehicles, boats and transport contracts

* **Plan:** a `transport` contract with cargo, a boat post, fuel.
* **Now:** bicycle and motorboat on sale (D-101); vehicle crates searched (D-077); freight carried on foot (D-156). Cars are a priced plan. No fuel, repair, insurance, boat post, customs or vehicle-bound contract.
* **Stale in the plan:** none, except that it should mention D-101 and D-156.
* **Remaining:** a player buys a vehicle, fuels it at a price, loads crates, drives a timed delivery through a checkpoint and is paid once; a person judges the handling (the October 16 run found Immersive Vehicles' keyboard steering skittish on the road's slabs).

#### WP12 Human playtest and real measurements

* **Plan:** the Windows guide, a bug template, a questionnaire; the owner's notes as proof.
* **Now:** the guide has grown to sections 6b to 6h and a five-question questionnaire. The owner played twice and reported defects (see "What a person has played"). No completed guide section, questionnaire or session log is recorded. Measurements are still scripted: road times (journey run 57: 241 s back), street counts (crowd runs), stalls.
* **Stale in the plan:** line 133: "no human has played any of it" (the owner played twice, on older builds).
* **Remaining:** a person plays one guide section on the current build, fills in section 8 and files the logs. This is still the single most useful item.


### Decision statuses that no longer match the evidence

For the owner to settle; nothing here changes a decision.

For the owner to settle. Nothing here changes a decision.

* **D-008** (Lost Cities versus a map, "needs spike"): the project runs on Lost Cities with the archipelago (D-104) and furnished packages (D-117).
* **D-009** (a custom RPG Core, "proposed"): Core exists and owns careers, law, Crews and homes.
* **D-010** (NPC approach, "needs spike"): vanilla villagers (D-061) and Core's own townspeople (D-108); Easy NPC and CustomNPCs not adopted.
* **D-011** (TaCZ blueprint addon, "needs spike"): TaCZ Blueprints 0.1.3 selected for the dev pack (D-057) and enforced on the server (D-073).
* **D-012** and **D-013** ("proposed"): implemented throughout (active-time rent, idempotent ledgers, migrations).
* **D-014** ("proposed"): the visible half is implemented; the off-screen half is not.
* **D-021** ("proposed"): three fixed Lost Cities cities exist.
* **D-026** ("needs spike"): native highways measured and replaced by our roads (D-046, D-047); the restock and warfare mods were not adopted.
* **D-028** ("proposed"): bounded shared supplier stock with active-time refill is implemented.
* **D-067** and **D-075**: "real-client pass still to be recorded" and "hosted GameTests pending for the Vintner": the Vintner has since passed workshop run 3, and the GameTest suites run in every configuration.
* **D-079**: "proposed and implemented" is two statuses at once.
* **D-088** (the expansion plan, "proposed"): still not accepted or retired, while the work has followed other briefs.
* **D-114** calls the door finding "the first human playtest"; `building-protection.md` calls it "the second playtest".

