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
