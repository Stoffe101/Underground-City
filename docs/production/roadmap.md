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
| P6: City life and loot | Named NPCs, schedules, Lootr content, contextual scavenging | Shops work; personalized loot tested; background sim doesn't duplicate goods |
| P7: Conflict | Limited faction patrols, witness-based crime, fine/arrest rules | Co-op safety, no arbitrary arrest, encounter balance, rollback/recovery |
| P8: Expansion | Multiple full cities, connected transport, civics, business growth | World consistency and performance proven; no forced grind |
| P9: Campaign and polish | Story acts, meaningful city changes, visuals, balance | Player journeys and save migrations tested over long-term co-op sessions |

### Where the work stands (October 16, 2026)

Evidence, not intent. P0 and P1 are met for the starter slice. P2 is **partly** met: three roads and bridges generate, have been surveyed block by block on seed 5908, and **a scripted player has walked one of them (Valewood - Blackwater, 3.75 minutes of walking) and driven and sailed others**; Founders Row is a populated lot (nine named citizens) built by an operator or by the dev launch, not a district of a city, and nobody human has walked any of it. P3 is met for registration and persistence (a first-login screen, command-driven persistence). P4 is met in miniature (wallet, stock-bound exchange, contracts) with no tax cycle. P5 has **early loops for all four careers** with a legal and an underground path each, skill trees, a blueprint-to-gun Gunsmith loop and the Technician's trace. P6 to P9 have not started beyond the rare-loot rule, the evidence-based law model (a seeded die at checkpoint zones, now with posts and a guard) and vehicles that a script can drive. The sprint reports list the details and the limits: [October 15](sprint-2026-10-15.md) and [October 16](sprint-2026-10-16.md).

### Where the work stands (October 18, 2026)

Added since October 16 (evidence in the [October 18 sprint report](sprint-2026-10-18.md)): **Crews** (membership, roles, a Core-owned crash-safe bank over Q Shop credits, three finance modes), **leasable homes** with a server-side guard (the Row Flats), a Crew screen on the native toolkit, and a two-client journey with an abrupt server kill and a restart; a **recovery policy for wayside shelter placement** (a failed or lost shelter is retried, rebuilt or reported, never hidden); an **item-source audit** that found and closed a real dead end in the Gunsmith's top contract (the rifle blueprint now comes from a component run); and an **opt-in survival set** (backpack, thirst, kitchen, sleeping bags) with boot and GameTest evidence. P5 is therefore deeper for the Gunsmith and gains a cooperative layer; **P6 to P9 are unchanged**: no populated routines, no police response, no raids, no burglary, no rent, no vehicle fuel, no boat customs. Same-day follow-up ([PR #23 report](pr23-playtest-readiness.md)): a **critical bug in Crew-funded purchases was fixed** (D-089), the server pack authenticates (D-090), the home guard covers pistons, hoppers, frames, stands, fluids, fire and mobs, survival goods are **on sale** in cities and at wayside provisions desks (D-091), and a **combined journey** runs on a generated Lost Cities world with every pack set and two real clients through an abrupt server kill. **Nobody has played any of it**: the next gate is a person, using the [Windows 11 playtest guide](playtest-guide-windows11.md). The ordered plan for the rest is the [expansion plan](expansion-plan.md) (a proposal for the owner's decision).

**Update, October 19, 2026** ([sprint report](sprint-2026-10-19.md)): the world is now a **simulation with no ordinary monsters** (D-094) and every item that needed a monster's drop has a route (D-095); the backpack provider is **Sophisticated Backpacks + Core** under one policy file and the Crew's home rules (D-092, D-093); the Lost Cities long-tick stall was root-caused and mostly removed (D-096); first starts of the new mods were measured (D-097); and the combined journey now has a real client buying, wearing and filling a backpack on the real screens. **P6 to P9 are unchanged and nobody has played any of it.**

**Update, October 20, 2026** ([sprint report](sprint-2026-10-20.md); owner brief "Economy, Housing, Rent, Professions and Meaningful Progression"): a reproducible **economic model** ([economy-model.md](../technical/economy-model.md); D-098) with invariants that fail on a buy-sell loop, an idle profit or a vanilla delivery that beats real production; a **property economy** (rent, purchase, deposits, active-time billing, grace and lapse that never take belongings, Crew-owned bills, upgrades, migration; D-099); **skill trees with real effects** (multi-prerequisite, capped, consumed by the pay, yield, exchange, upgrade and checkpoint systems; scrolling page; D-098); **vehicle and boat price tiers** with a bicycle and a motorboat on sale (D-101; no fuel, no repair); a **checkpoint that searches what a person carries, including a Sophisticated Backpack**, and nothing else (D-100); a re-evaluated backpack price and five more enabled upgrades (D-102); and a fail-closed guard against loading a world with Traveler's Backpack items (D-103). **P6 to P9 are unchanged and nobody has played any of it.**

### Phase F: what the next passes build (not started)

| Next | What is missing | Why it matters |
| --- | --- | --- |
| Property buildings | seven of ten categories are priced but not built (comfortable and larger apartments, small house, farmhouse, commercial workshop, warehouse, premium residence) | the ladder has a middle and a top only on paper |
| Skill tiers | each tree has 12 to 14 skills and spends 25 to 29 of the 29 points a level-30 character earns; nothing exists past level 30 (no mastery tier, no respec rule) | long-term progression |
| Cooperative contracts | contracts that need two careers or a Crew's labour | the two-friends goal |
| Manufacturing and facilities | workstations, power, rented production space (warehouse) | Schedule I-style growth |
| Vehicles | a bench-built vehicle path, fuel, repair, insurance, a dealer for cars | the cars are priced as a plan, not for sale |
| Loot, burglary, police | per-player loot, a break-in that a lapsed or empty home can suffer, patrols and raids | risk on the underground path |
| Populated districts | daily routines, shops that restock from the economy | a living city |
| Real play | a person plays the 40-minute test in the playtest guide, section 6d | every number here is a model's |

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
