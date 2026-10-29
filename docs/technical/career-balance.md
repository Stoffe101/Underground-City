# Career balance: legal, underground and hybrid (twelfth pass, D-210)

**Status:** arithmetic over the game's own data plus named assumptions (`scripts/career_balance.py`). It is **not** a measurement of play. No person has played any of it, and no scripted client has yet run a full legal or underground hour to measure the hands-on share the figures rest on. The current branch and its recent gameplay systems have not received human playtesting.

The owner's question (October 29): does a legal career stay worth playing once its underground path opens, or does the best strategy become "unlock the illegal path and never do honest work again"? The principle the answer is held to: *a player should never feel forced into crime to make meaningful financial progress, and a player who chooses crime should never feel that the police are irrelevant.*

```
python scripts/career_balance.py                 the report below
python scripts/career_balance.py --check         exit 1 when a rule breaks
python scripts/career_balance.py --update-doc docs/technical/career-balance.md
python -m unittest tests.test_career_balance     the rules hold, each can fail, the service fees match the Java
```

## What it computes

For each of the five careers at levels 1, 3, 6, 10, 15, 20 and 30, the best **active hour** of six builds:

| build | what it does |
|---|---|
| A legal | legal orders and the career's service calls only; skill points on the legal, core, utility and crossover branches |
| B underground | underground orders first wherever the character can take them, then burglary through the fences (the Thief track), legal work only to fill the hour; underground skills first |
| C hybrid | both; the best of three ways of spending points (cheapest first, its legal trade first, its underground first) |
| D new player | the affordability timeline: from level 1 with no capital, levelling as it earns |
| E with premises | legal, with trade premises rented (the orders that ask a trade facility) |
| F Crew of two | two careers in one household sharing employers' demand and the exchange shelves (`Model.household_income`'s rules) |

The hour is filled greedily, best risk-adjusted credits per hands-on minute first, within: the hands-on minutes of the hour, each order's employer demand (a burst of its capacity over the supply horizon plus one slot per refill), the shared regional exchange shelves, and each fence's hourly budget.

**Underground orders** are risk-adjusted along their real corridor, gate by gate, with the same arithmetic as `LawMath.inspectionChance` and `validate_underground_balance.py`: `value = P(not searched) × paid − cost − E[fine]`. **Burglary and raids** are risk-adjusted with `police_risk` in `content/gameplay/economy-model.json`: `P(noticed)` by building tier, `P(arrest | noticed)`, the fine escalated by priors as `LawMath.escalatedFine` does, the haul lost. The **spread** of an underground hour is a seeded Monte Carlo of the same events over 1,000 hours (tenth and ninetieth percentile, share of losing hours).

## Every number's source

| input | kind | where |
|---|---|---|
| work orders, credits, capacity, refill, skills, buyers | code/data | `work_orders/*.json` (generated from `content/gameplay/contracts.json`) |
| skill effects (payout percent, licences, concealment) | code/data | `skill_trees/*.json`, capped by `SkillRules` |
| graded goods (Fine 1.25×, Reserve 1.6×, at most 1.6× an order) | code/data | `workshops/*.json`, `Workshops.gradeFor`, `WorkOrderService.gradedPayout` |
| inspection chance, fines, gates | code/data | `city_laws/*.json`, `corridors.json`, `LawMath` |
| service-call fees, gaps, skill reach, parts used | code | `TechService`, `TechWork`, `MachineService`, `SmithWork` (the test reads the fees from the Java) |
| exchange prices and shelves | code/data | `regional_trade` source via `generate_regional_content.py` |
| burglary haul by tier | data | `burglary_audit.py` (`search_spots/furniture.json`, `fences/values.json`) |
| **hands-on share of an active hour: 0.5** | **assumption** | `efficiency` in `economy-model.json`; not yet measured (see "What is not proven") |
| **station tier by level: I to 7, II from 8, III from 14** | **assumption** | `station_tiers` |
| **mean building tier of a service call: 3.5** | **assumption** | `service_work.building_tier` |
| **minutes a service call takes (6 to 8)** | **assumption** (tech run 5 and engineer run 7 took about that) | `service_work.jobs[].minutes` |
| **P(arrest \| noticed) = 0.4** | **assumption** until the police acceptance runs give a rate | `police_risk.p_arrest_given_noticed` |
| **P(noticed) by burglary tier, 0.15 to 0.6** | **assumption** built from code-defined chances (alarm, witnesses, household) | `police_risk.burglary.p_noticed_by_tier` |
| **a run to a buyer carries up to three of that buyer's open orders** | **assumption** (each order is delivered to the same counter) | `TRIP_SHARE_MAX` |
| **the burglary tier a thief works by level** | **assumption** | `police_risk.burglary.tier_by_level` |

## What the audit found, and what changed

Run with the same model on the data before this pass (the twelfth pass's police commit) and after:

| career | legal L6 before → after | legal L20 before → after | what was wrong |
|---|---|---|---|
| farmer | 383 → 383 | 541 → 741 | nothing new to do after level 10: every hour from 10 to 30 was the same crate order |
| vintner | 794 → 542 | 839 → 856 | aged wine was Reserve at level 6 (Estate Vintages added the grade), so the level-6 hour was the best of any career's and nothing later beat it |
| smith | 478 → 478 | 929 → 893 | the new advanced engineering orders were all hand-turned parts and paid less per hands-on hour than the entry orders (the economy model's invariant) |
| gunsmith | 698 → 424 | 1190 → 1004 | the armoury parts kit order was Fine at level 3 and Reserve with upgraded benches: one cash-flip order filled every hour from level 3 to 30 |
| technician | 521 → 521 | 619 → 808 | an **install lost money**: it uses up a Security Array (140 credits of goods) and paid 45 + 18·tier (102 at tier III in tech run 5); the advanced orders all drew on the one circuit shelf the level-5 order already emptied |

Changes (data, plus two fee formulas in Java; no flat multiplier anywhere):

* **21 advanced contracts** (levels 14 to 21; one legal pair and one trade-premises order per career, and one underground order per career), each gated on the top legal or underground skill and inside the D-052 margins (legal 1.3 to 2.1, underground 1.9 to 2.2 times the goods' cost).
* **Grades later in a career:** aged wine's skill grade moved from Estate Vintages (level 6) to Cellar Master's Label (level 11); the parts kit's from Licensed Repairs (level 3) to Security Contracts (level 8). Reserve still needs a tier III station or the later skill.
* **Technician fees:** an install pays 150 + 25·tier (it now covers the array at every tier), a network upgrade 90 + 30·tier (the trade's most skilled legal work, Systems Integrator).
* **The technician's advanced orders** draw on skilled hands (observers, comparators) and one array or controller, not four of the shared circuit boards.
* **Demand:** the armoury parts kit order and the security-firm contract refill every 20 minutes, the estate reserve cellar is one smaller lot an hour, so no single order fills a level-20 hour.
* **Two underground orders** (unmarked kits 370, ghost kit crates 730) were lowered because the new legal gunsmith work lowered the career's legal baseline under D-052's careless-smuggler rule.

The model gained what it left out: the Technician's and Smith's **service calls** (code-defined fees and gaps), **station tiers** by level, a **run that carries several orders** for one buyer, and hybrids that **choose** how to spend their points.

## Rules (`--check`; each shown to fail on broken data in the test)

| rule | holds when |
|---|---|
| legal_after_unlock | from level 10, every career's best legal hour is at least 75% of its best risk-adjusted underground hour |
| underground_risk_max | the risk-adjusted underground hour is at most 1.6 times the legal hour |
| legal_growth | the legal hour at level 20 is at least 1.25 times the hour at level 6 |
| hybrid_uses_legal | a hybrid character at level 15 spends at least 25% of its hour on legal work |
| hybrid never worse | a hybrid hour is never under the legal hour |
| single_activity | no single order makes more than 80% of a level-20 legal hour |
| career_spread | at level 20 the best career's legal hour is at most 1.5 times the worst's |
| affordability | a legal character of every career rents a house within 15 active hours and buys one within 60; a legal Crew of two buys the villa within 120 |

The economy model's own invariants (`economy_model.py --check`: no break-even loops, entry orders under production, shortage contracts, the business counter) and D-052's corridor rules (`validate_underground_balance.py`) also hold.

## The crime question

**Legal work stays worth doing.** At every level and for every career, the best legal hour is above the best underground one, nominal and risk-adjusted, and a hybrid character's best hour is its legal trade's (100% legal at level 15 for all five careers). Nobody is forced into crime to progress: every property milestone up to the villa is reached on legal income alone (the affordability table).

**Crime pays more per job, not per hour.** An underground order pays 1.9 to 2.2 times the cost of its goods against 1.3 to 2.1 for legal work (D-052), up to 1,000 credits a delivery, but it carries hand-made goods across checkpoints to one buyer in another city: a smuggler without concealment skills loses 22% to 44% of runs to a search (code-defined gates), and the round trip is 12 minutes. Burglary pays 21 to 208 credits an active hour by tier. Per hour, from level 10, crime is about 30% to 75% of the same character's legal work (the Gunsmith highest, whose arms errands carry no contraband).

**It is a knife edge.** The test doubles underground pay in memory: a level-15 Technician's hybrid hour then has *no* legal work in it at all, and a Gunsmith's underground hour leaves legal work far behind. Because an hour is filled with the best work per minute and the underground buyers between them take more than an hour's goods, a small premium flips a hybrid from all-legal to all-crime: there is no middle ground in today's rules. What-if (in memory, not shipped):

| underground pay | refill of an underground order | technician L15 hybrid legal share | gunsmith L15 underground ÷ legal |
|---|---|---|---|
| as shipped | 15 to 20 min | 100% | 0.70 |
| ×1.5 | as shipped | 100% | 1.37 |
| ×1.6 | 40 min | 0% | 1.50 |
| ×1.8 | 60 min | 0% | 1.69 |

**Proposed for the owner (D-211, open):** if crime should also pay more *per hour*, raise the underground band above D-052's 2.2 **and** cap what one underground buyer takes from one player per hour of active play across all its orders (a per-buyer limit in `WorkOrderService`), so a hybrid's hour holds some crime and some honest work instead of flipping. Neither is done in this pass: D-052 is an accepted decision, and a per-buyer limit without the higher band changes nothing today.

## What is not proven

* **The hands-on share (0.5).** Every income figure is multiplied by it. The scripted runs log their steps but not the share of an hour spent producing; economy telemetry that records it per player, so a playtest can replace the assumption, is the next step (P2 of the brief).
* **The arrest rate (0.4 once noticed).** To be replaced by the police acceptance runs' arrest share.
* **Station tiers by level and the service-call minutes** are assumptions.
* Nobody has played an hour of any build.

<!-- career-balance:begin -->

### Net credits per active hour by career and level: legal, underground and hybrid (risk-adjusted; nominal in brackets)

| career     | L  | legal | underground | hybrid      | hybrid on legal | with trade premises | underground p10..p90 | losing hours |
|------------|----|-------|-------------|-------------|-----------------|---------------------|----------------------|--------------|
| farmer     | 1  | 146   | -           | 146 (146)   | 100%            | 146                 | 120..180             | 0%           |
| farmer     | 3  | 348   | 33 (50)     | 348 (348)   | 100%            | 348                 | -73..60              | 14%          |
| farmer     | 6  | 383   | 63 (89)     | 383 (383)   | 100%            | 383                 | -50..106             | 17%          |
| farmer     | 10 | 516   | 178 (235)   | 516 (516)   | 100%            | 516                 | 38..282              | 7%           |
| farmer     | 15 | 541   | 202 (283)   | 541 (541)   | 100%            | 541                 | 67..340              | 9%           |
| farmer     | 20 | 741   | 208 (327)   | 741 (741)   | 100%            | 741                 | -103..393            | 12%          |
| farmer     | 30 | 741   | 245 (300)   | 741 (741)   | 100%            | 741                 | 0..595               | 5%           |
| vintner    | 1  | 147   | -           | 147 (147)   | 100%            | 147                 | 140..168             | 0%           |
| vintner    | 3  | 363   | 33 (50)     | 363 (363)   | 100%            | 363                 | -73..60              | 14%          |
| vintner    | 6  | 542   | 63 (89)     | 542 (542)   | 100%            | 542                 | -50..106             | 17%          |
| vintner    | 10 | 542   | 185 (317)   | 542 (542)   | 100%            | 542                 | -55..412             | 19%          |
| vintner    | 15 | 839   | 295 (461)   | 839 (839)   | 100%            | 839                 | -55..546             | 26%          |
| vintner    | 20 | 856   | 351 (681)   | 856 (856)   | 100%            | 856                 | -247..1040           | 23%          |
| vintner    | 30 | 856   | 536 (706)   | 856 (856)   | 100%            | 856                 | -272..1078           | 11%          |
| smith      | 1  | 282   | -           | 282 (282)   | 100%            | 282                 | 264..288             | 0%           |
| smith      | 3  | 442   | 33 (50)     | 442 (442)   | 100%            | 442                 | -73..60              | 14%          |
| smith      | 6  | 478   | 63 (89)     | 478 (478)   | 100%            | 478                 | -50..106             | 17%          |
| smith      | 10 | 628   | 178 (235)   | 628 (628)   | 100%            | 628                 | 38..282              | 7%           |
| smith      | 15 | 840   | 250 (479)   | 840 (840)   | 100%            | 840                 | -167..510            | 27%          |
| smith      | 20 | 893   | 291 (553)   | 893 (893)   | 100%            | 893                 | -288..706            | 26%          |
| smith      | 30 | 893   | 452 (571)   | 893 (893)   | 100%            | 893                 | -103..733            | 13%          |
| gunsmith   | 1  | 146   | -           | 146 (146)   | 100%            | 146                 | 120..180             | 0%           |
| gunsmith   | 3  | 380   | 33 (50)     | 380 (380)   | 100%            | 380                 | -73..60              | 14%          |
| gunsmith   | 6  | 424   | 63 (89)     | 424 (424)   | 100%            | 424                 | -50..106             | 17%          |
| gunsmith   | 10 | 582   | 442 (482)   | 582 (582)   | 100%            | 582                 | 248..804             | 0%           |
| gunsmith   | 15 | 915   | 643 (687)   | 915 (915)   | 100%            | 915                 | 442..1176            | 0%           |
| gunsmith   | 20 | 1004  | 756 (756)   | 1004 (1004) | 100%            | 1004                | 498..1261            | 0%           |
| gunsmith   | 30 | 1004  | 788 (788)   | 1004 (1004) | 100%            | 1004                | 524..1308            | 0%           |
| technician | 1  | 306   | -           | 306 (306)   | 100%            | 306                 | 260..312             | 0%           |
| technician | 3  | 453   | 33 (50)     | 453 (453)   | 100%            | 453                 | -73..60              | 14%          |
| technician | 6  | 521   | 63 (89)     | 521 (521)   | 100%            | 521                 | -50..106             | 17%          |
| technician | 10 | 521   | 200 (311)   | 521 (521)   | 100%            | 521                 | -15..420             | 26%          |
| technician | 15 | 808   | 364 (539)   | 808 (808)   | 100%            | 808                 | -415..916            | 15%          |
| technician | 20 | 808   | 395 (585)   | 808 (808)   | 100%            | 808                 | -415..916            | 14%          |
| technician | 30 | 808   | 615 (615)   | 815 (815)   | 70%             | 821                 | 482..964             | 0%           |

### What each build takes at level 15 (orders and burglaries an hour)

| career     | build       | picks                                             |
|------------|-------------|---------------------------------------------------|
| farmer     | legal       | crew_fair_produce 2.27                            |
| farmer     | underground | burglary_t5 1.67                                  |
| farmer     | hybrid      | crew_fair_produce 2.27                            |
| vintner    | legal       | estate_aged_wine 3.03                             |
| vintner    | underground | reserve_run 0.85                                  |
| vintner    | hybrid      | estate_aged_wine 3.03                             |
| smith      | legal       | foundry_parts 2.0, crew_canteen_fittings 2.08     |
| smith      | underground | armoury_order 1.0, burglary_t5 0.82               |
| smith      | hybrid      | foundry_parts 2.0, crew_canteen_fittings 2.08     |
| gunsmith   | legal       | armoury_parts_kits 3.67, guard_armoury_refit 0.33 |
| gunsmith   | underground | dray_rifle_run 1.89, arms_brokerage 0.32          |
| gunsmith   | hybrid      | armoury_parts_kits 3.67, guard_armoury_refit 0.33 |
| technician | legal       | tech_upgrade 3.0, security_array_install 1.65     |
| technician | underground | ledger_brokerage 1.18                             |
| technician | hybrid      | tech_upgrade 3.0, security_array_install 1.65     |

### Two-player Crews: net credits per active hour of the household (risk-adjusted)

| crew               | L  | legal | hybrid | hybrid on legal |
|--------------------|----|-------|--------|-----------------|
| farmer + vintner   | 6  | 925   | 925    | 100%            |
| farmer + vintner   | 15 | 1380  | 1380   | 100%            |
| farmer + vintner   | 30 | 1597  | 1597   | 100%            |
| smith + gunsmith   | 6  | 878   | 878    | 100%            |
| smith + gunsmith   | 15 | 1755  | 1755   | 100%            |
| smith + gunsmith   | 30 | 1897  | 1897   | 100%            |
| technician + smith | 6  | 952   | 952    | 100%            |
| technician + smith | 15 | 1623  | 1623   | 100%            |
| technician + smith | 30 | 1678  | 1708   | 85%             |
| farmer + gunsmith  | 6  | 771   | 771    | 100%            |
| farmer + gunsmith  | 15 | 1456  | 1456   | 100%            |
| farmer + gunsmith  | 30 | 1745  | 1745   | 100%            |

### Affordability: active hours until each milestone (risk-adjusted income, living costs and rent paid; '-' not within 200 hours)

| household              | starter rent | vehicle bicycle | apartment rent | house rent | workshop rent | business counter | apartment buy | vehicle small car | house buy | villa buy |
|------------------------|--------------|-----------------|----------------|------------|---------------|------------------|---------------|-------------------|-----------|-----------|
| farmer legal           | 0.5          | 0.5             | 2.0            | 3.5        | 5.5           | 6.2              | 11.0          | 18.8              | 30.8      | 72.2      |
| vintner legal          | 0.5          | 0.5             | 1.5            | 3.0        | 5.0           | 6.0              | 10.8          | 16.5              | 23.8      | 56.5      |
| smith legal            | 0.2          | 0.2             | 1.2            | 2.2        | 3.8           | 4.5              | 8.5           | 14.8              | 22.8      | 53.8      |
| gunsmith legal         | 0.5          | 0.5             | 1.5            | 2.8        | 4.2           | 5.2              | 9.5           | 14.8              | 21.2      | 48.5      |
| technician legal       | 0.2          | 0.2             | 1.2            | 2.2        | 3.8           | 4.5              | 9.0           | 14.8              | 22.2      | 56.5      |
| gunsmith hybrid        | 0.5          | 0.5             | 1.5            | 2.8        | 4.2           | 5.2              | 9.5           | 14.8              | 21.5      | 48.8      |
| gunsmith underground   | 0.5          | 0.5             | 1.5            | 2.8        | 7.0           | 8.2              | 14.5          | 22.8              | 32.5      | 70.5      |
| technician hybrid      | 0.2          | 0.2             | 1.2            | 2.2        | 3.8           | 4.5              | 9.0           | 14.8              | 22.2      | 56.5      |
| technician underground | 0.2          | 0.2             | 1.2            | 2.2        | 3.8           | 4.5              | 19.0          | 31.8              | 50.0      | 107.2     |
| farmer + smith legal   | 0.2          | 0.2             | 1.0            | 1.8        | 2.5           | 3.0              | 5.5           | 8.8               | 14.0      | 33.5      |

Milestone costs: starter rent 60, vehicle bicycle 60, apartment rent 320, house rent 680, workshop rent 1080, business counter 1100, apartment buy 2000, vehicle small car 3400, house buy 5400, villa buy 25000.

### Burglary by tier (one visit; the fences' hourly budget caps it)

| tier | haul to a fence | minutes | P(arrested) | nominal | risk-adjusted | per active hour (risk) |
|------|-----------------|---------|-------------|---------|---------------|------------------------|
| 1    | 23              | 18.0    | 6%          | 20      | 12            | 21                     |
| 2    | 33              | 18.0    | 8%          | 30      | 20            | 33                     |
| 3    | 56              | 18.0    | 10%         | 53      | 38            | 63                     |
| 4    | 144             | 18.0    | 14%         | 141     | 107           | 178                    |
| 5    | 173             | 18.0    | 18%         | 170     | 121           | 202                    |
| 6    | 199             | 18.0    | 24%         | 196     | 125           | 208                    |

### Rules

all hold

<!-- career-balance:end -->
