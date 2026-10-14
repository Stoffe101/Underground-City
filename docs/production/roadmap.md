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
