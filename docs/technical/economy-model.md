# The economy model (D-098)

**Status: a provisional planning model, written October 20, 2026.** It is arithmetic over the game's real data plus a short file of stated assumptions about how long a Minecraft task takes. **Nobody has timed a person.** Every number below is therefore a statement about what the *data* implies, not a measurement of play, and the tables say so. It is `scripts/economy_model.py`, its assumptions are `content/gameplay/economy-model.json`, and 23 unit tests (`tests/test_economy_model.py`) pin it: the shipped data must satisfy every invariant, and each invariant is also shown to **fail** on data made to break it.

```text
python scripts/economy_model.py                  the report
python scripts/economy_model.py --check          exit 1 if an invariant fails (also run by validate_repo.py)
python scripts/economy_model.py --underground    add the risk-adjusted underground table
python scripts/economy_model.py --update-doc docs/technical/economy-model.md    refresh the generated block below
python scripts/economy_model.py --check-doc  docs/technical/economy-model.md    fail if the block is stale (a unit test does)
```

## What it answers

The owner's questions of October 19: what does a delivery earn after its materials, per hour of play; how long to a first payout; how long to a first apartment, a house, a premium home; does rent fit the income it is paid from; can a Crew of two or three do better than the same people alone; can one sale skip a whole tier; can anybody buy low and sell high; does vanilla item delivery beat real production; do the skills pay what they cost.

## Inputs (nothing is copied; change the data and the answers change)

| Input | What the model reads from it |
| --- | --- |
| `data/underground_city/work_orders/*.json` (54 orders) | credits, XP, level, apprentice ceiling (`max_level`), demand (capacity and refill), the skill an order needs, the mod it needs, goods required |
| `content/gameplay/regional-content.json` | exchange prices, shelf sizes and refill rates, the products crafted from exchange goods (their inputs) |
| `content/gameplay/contracts.json` | reference values of goods the exchange does not price; the contracts that generate the underground and gun orders |
| `data/underground_city/skill_trees/*.json` | which orders a skill opens, what a skill pays on top (`legal_payout_percent`, `underground_payout_percent`), points per level |
| `data/underground_city/properties/ladder.json` | rent, deposit, purchase price, upkeep, billing rules, city factors |
| `content/integrations/qshop/.../uc_starter_supply.json` | what the starter shop buys and sells (for the buy-and-sell check) |
| `content/gameplay/economy-model.json` | **the assumptions**: hands-on minutes per item, waiting time per crop, overheads, the share of an active hour that is hands-on, living costs, the courier allowance, the targets |

## Method, in the order the code does it

1. **Net profit of an order** = credits − cash spent at an exchange or shop for the goods it asks for. Goods a player gathers or grows cost *time*, not credits. A product crafted from exchange goods costs its inputs' prices.
2. **Time** of an order = the hands-on minutes of every unit of goods (`hands_on_minutes_each`), plus a terminal visit, plus its share of an exchange trip (12 minutes for four orders' worth of purchases) or a trip to a buyer in another city (12 minutes). Waiting (a crop growing, wine ageing) is *parallel* and only delays the first delivery.
3. **Efficiency.** Only **half** of an active hour is hands-on (`hands_on_share_of_active_time` 0.5): the rest is walking, inventory, waiting, talking to a friend. Every per-hour income is multiplied by this; rent, upkeep and living costs are not.
4. **Shared pools.** Employer demand per order and every exchange commodity's shelf are shared by the whole world (D-023), so a household draws on one pool. A Crew of three of the same career does not triple its income: the model takes orders in order of net credits per hands-on minute across the whole household and spends each shelf once.
5. **Skills.** Points = level − 1. The model spends them in a sensible order (cheapest level-appropriate node first, prerequisites respected, **legal nodes only** for the legal income tables). It then adds what the held skills pay: `legal_payout_percent` of the order's credits, rounded down to whole credits exactly as `SkillRules.payout` does.
6. **Housing timeline** (`simulate`): a household takes the best hour it can, rents a flat when the rent is at most a fifth of its net income and the deposit plus six prepaid hours are in hand, buys it when the purchase price plus six hours of rent is in hand, and so on up the ladder.
7. **Exploit checks** (`exploit_checks`) and **invariants** (`invariants`): see below.

## What the data implied, and what was changed because of it

The first run of the model on the October 19 data found (all fixed in this pass, all pinned by tests):

* **The first orders paid too little to matter and too much to ignore at the same time.** `harvest` paid 24 credits for 16 wheat (the starter shop buys wheat at 1.5 each: 24); it now pays **30** so that delivering is never worse than selling to the shop. `bread` (36 against a shop value of 40) was flagged as a buy-and-deliver loop in the first draft of the fix and now pays **38**. `crossbow` paid 80 for a 5-minute craft (a higher hourly wage than most level-8 work) and now pays **60**; `iron_tools` 80 → **70**, `repeaters` 80 → **68**.
* **Apprentice orders never retired.** Level-1 orders paid the same at level 25 as at level 1, so a veteran could earn a living from wheat. Twelve entry orders now carry a ceiling (`max_level`, implemented in Java: they are offered up to it and not above; an order accepted earlier stays deliverable). **Vintner had no early order at all** (its first order needed Vinery); `cellar_bottles` and `sweet_must` give it a Core-only start.
* **Two courier errands out-earned crafting.** `dray_parts_run` and `dray_rifle_run` carry exchange goods unchanged to a buyer. With the new contract-rate skills they would have reached 1.71 times the exchange price and 280 credits an hour against a ceiling of 250: they now pay **205** and **310**.
* **The cannabis chain was the weakest "production" hour of the Farmer** (239 credits per hands-on hour at level 10, below an apprentice's wheat). The contract margins are bounded by the generator (legal 1.3 to 2.1 times the cost of the goods, underground 1.9 to 2.2), so only a small rise was available: `licensed_cultivar_supply` 200 → **225**, `quiet_bales` 220 → **237**, `reserve_load` 440 → **474**. The rest has to come from the **grafting skill** (about 30% more flower for the same nutrient), which lowers the cost of a flower instead of raising the price: see [skill-trees.md](../design/skill-trees.md).
* **Drying (D-160) is the crop's second lever.**
  * A Drying Rack turns two flower into one Dried Cannabis in six minutes of active play. The model prices a dried unit at 44 credits: two flower at 18 and 8 for the rack's time and the loss. It counts 5.4 hands-on minutes and a 30-minute wait before the first delivery.
  * The two orders for it, `dried_for_marrow` (380 for 4) and `licensed_dried_supply` (360 for 4), sit inside the margin rules and pay **421 and 379 credits per hands-on hour**, against 304 to 346 for the flower orders. That is still under the Farmer's other legal work, and a played session has to say whether the wait is worth it.
* **A skill that pays more must not break a margin.** The model's checks now use the *highest* payment an order can ever make *at the levels where it is offered*: an apprentice order is offered up to level 5 or 6 and the first contract-rate skill is level 8, so bread can never exceed the shop price; and a 12% skill on a level-8 order would be caught by the same check (a unit test mutates the tree to prove it).

## The generated tables

Everything between the markers is written by `--update-doc` and checked by a unit test, so the numbers cannot drift from the data. Hands-on minutes and the half-hour efficiency are **assumptions**; the sensitivity table at the end repeats the key outcomes with every minute scaled by 0.7 and 1.4.

<!-- economy-model:begin -->

### Every work order: credits, cash spent at an exchange, net, hands-on minutes, net per hands-on hour

| career     | L  | order                    | path | credits | cash | net | min  | net/h | xp  | demand | skill                   | mod                 |
|------------|----|--------------------------|------|---------|------|-----|------|-------|-----|--------|-------------------------|---------------------|
| any        | 1  | civic_market_lanterns    | le   | 60      | 0    | 60  | 12.3 | 293   | 160 | 6/h    |                         |                     |
| any        | 1  | raid_bonded_store        | un   | 700     | 0    | 700 | 48.5 | 866   | 520 | 2/h    | pro_tools               |                     |
| farmer     | 1  | harvest                  | le   | 30      | 0    | 30  | 6.3  | 286   | 125 | 12/h   |                         |                     |
| farmer     | 2  | bakery_flour             | le   | 80      | 48   | 32  | 10.3 | 186   | 220 | 12/h   | fundamentals            |                     |
| farmer     | 2  | bread                    | le   | 38      | 0    | 38  | 9.5  | 240   | 180 | 12/h   |                         |                     |
| farmer     | 2  | crew_canteen_flour       | le   | 120     | 72   | 48  | 14.7 | 196   | 300 | 6/h    |                         |                     |
| farmer     | 3  | civic_market_produce     | le   | 110     | 48   | 62  | 15.3 | 243   | 240 | 6/h    |                         |                     |
| farmer     | 3  | council_stall_produce    | le   | 240     | 120  | 120 | 27.0 | 267   | 380 | 6/h    |                         |                     |
| farmer     | 3  | crew_fair_produce        | le   | 180     | 72   | 108 | 13.2 | 491   | 320 | 6/h    |                         |                     |
| farmer     | 3  | greenhouse_service       | le   | 150     | 92   | 58  | 10.7 | 325   | 300 | 6/h    |                         |                     |
| farmer     | 3  | greenhouse_wholesale     | le   | 100     | 0    | 100 | 15.9 | 377   | 260 | 12/h   | greenhouse_management   |                     |
| farmer     | 3  | market_produce           | le   | 200     | 120  | 80  | 21.0 | 229   | 340 | 8/h    | greenhouse_management   |                     |
| farmer     | 3  | pie                      | le   | 96      | 0    | 96  | 14.3 | 403   | 250 | 12/h   |                         |                     |
| farmer     | 4  | bulk_canteen_flour       | le   | 400     | 192  | 208 | 36.7 | 340   | 600 | 3/h    | fundamentals            |                     |
| farmer     | 4  | seed_drill_service       | le   | 140     | 84   | 56  | 6.9  | 487   | 320 | 6/h    |                         |                     |
| farmer     | 5  | greenhouse_unit          | le   | 210     | 128  | 82  | 6.0  | 820   | 600 | 6/h    |                         |                     |
| farmer     | 5  | heirloom_seeds           | le   | 150     | 112  | 38  | 4.5  | 507   | 330 | 6/h    | cultivar_broker         |                     |
| farmer     | 8  | estate_pantry            | le   | 260     | 0    | 260 | 31.9 | 489   | 420 | 6/h    | estate_wholesale        |                     |
| farmer     | 9  | quiet_bales              | un   | 237     | 108  | 129 | 23.1 | 335   | 380 | 4/h    | underground_trade       |                     |
| farmer     | 10 | dried_for_marrow         | un   | 380     | 176  | 204 | 29.1 | 421   | 420 | 4/h    | underground_trade       |                     |
| farmer     | 10 | licensed_cultivar_supply | le   | 225     | 108  | 117 | 23.1 | 304   | 400 | 6/h    | controlled_crop_licence |                     |
| farmer     | 11 | licensed_dried_supply    | le   | 360     | 176  | 184 | 29.1 | 379   | 440 | 6/h    | controlled_crop_licence |                     |
| farmer     | 12 | reserve_load             | un   | 474     | 216  | 258 | 44.7 | 346   | 600 | 3/h    | grey_market_contacts    |                     |
| gunsmith   | 1  | arrows                   | le   | 32      | 0    | 32  | 8.5  | 225   | 125 | 12/h   |                         |                     |
| gunsmith   | 2  | crossbow                 | le   | 60      | 0    | 60  | 5.0  | 720   | 180 | 12/h   |                         |                     |
| gunsmith   | 3  | armoury_parts_kits       | le   | 210     | 120  | 90  | 6.1  | 885   | 380 | 6/h    | licensed_repairs        |                     |
| gunsmith   | 3  | licensed_sidearm         | le   | 190     | 0    | 190 | 23.4 | 488   | 340 | 6/h    | licensed_repairs        | tacz                |
| gunsmith   | 3  | optic_mounts             | le   | 230     | 140  | 90  | 6.9  | 783   | 320 | 6/h    |                         |                     |
| gunsmith   | 3  | range_targets            | le   | 120     | 0    | 120 | 17.9 | 402   | 250 | 12/h   |                         |                     |
| gunsmith   | 4  | licensed_service         | le   | 200     | 120  | 80  | 5.6  | 857   | 330 | 6/h    |                         |                     |
| gunsmith   | 4  | registered_sidearms      | le   | 400     | 0    | 400 | 33.3 | 722   | 560 | 4/h    | licensed_repairs        | tacz                |
| gunsmith   | 5  | equipment_platform       | le   | 230     | 140  | 90  | 6.6  | 818   | 600 | 6/h    |                         |                     |
| gunsmith   | 7  | dray_parts_run           | un   | 205     | 140  | 65  | 16.5 | 236   | 300 | 3/h    | unregistered_builds     | taczblueprints      |
| gunsmith   | 8  | dray_clean_pistols       | un   | 720     | 0    | 720 | 45.3 | 955   | 600 | 3/h    | unregistered_builds     | tacz                |
| gunsmith   | 8  | licensed_shotgun         | le   | 170     | 0    | 170 | 22.8 | 448   | 420 | 6/h    | security_contracts      | taczblueprints      |
| gunsmith   | 8  | unmarked_kits            | un   | 400     | 190  | 210 | 21.1 | 597   | 420 | 3/h    | unregistered_builds     |                     |
| gunsmith   | 8  | unregistered_smg         | un   | 500     | 0    | 500 | 37.8 | 795   | 420 | 4/h    | unregistered_builds     | tacz                |
| gunsmith   | 10 | dray_rifle_run           | un   | 310     | 210  | 100 | 16.5 | 364   | 450 | 3/h    | filed_serials           | taczblueprints      |
| gunsmith   | 11 | arms_brokerage           | un   | 640     | 0    | 640 | 51.6 | 744   | 640 | 3/h    | arms_brokerage          | tacz                |
| smith      | 1  | stone_tools              | le   | 24      | 0    | 24  | 2.5  | 565   | 125 | 12/h   |                         |                     |
| smith      | 2  | iron_tools               | le   | 70      | 0    | 70  | 7.5  | 560   | 180 | 12/h   |                         |                     |
| smith      | 2  | yard_fasteners           | le   | 140     | 84   | 56  | 8.6  | 391   | 240 | 12/h   | fundamentals            |                     |
| smith      | 3  | civic_market_frames      | le   | 170     | 60   | 110 | 13.0 | 508   | 260 | 6/h    |                         |                     |
| smith      | 3  | cooperage_hoops          | le   | 80      | 48   | 32  | 7.5  | 256   | 220 | 8/h    | structural_works        |                     |
| smith      | 3  | crew_canteen_fittings    | le   | 175     | 72   | 103 | 7.8  | 792   | 280 | 6/h    |                         |                     |
| smith      | 3  | foundry_parts            | le   | 290     | 176  | 114 | 6.9  | 991   | 320 | 6/h    |                         |                     |
| smith      | 3  | hoppers                  | le   | 120     | 0    | 120 | 11.9 | 605   | 250 | 12/h   |                         |                     |
| smith      | 3  | municipal_structural     | le   | 150     | 0    | 150 | 13.6 | 663   | 280 | 12/h   | structural_works        |                     |
| smith      | 4  | bulk_depot_fittings      | le   | 420     | 216  | 204 | 21.2 | 578   | 560 | 3/h    | fundamentals            |                     |
| smith      | 4  | depot_repairs            | le   | 140     | 84   | 56  | 5.2  | 646   | 300 | 6/h    |                         |                     |
| smith      | 5  | composite_housings       | le   | 225     | 136  | 89  | 5.7  | 937   | 600 | 6/h    |                         |                     |
| smith      | 5  | guard_plating            | le   | 270     | 0    | 270 | 31.5 | 514   | 340 | 6/h    | armoury_contracts       |                     |
| smith      | 6  | depot_gear_sets          | le   | 160     | 90   | 70  | 15.2 | 277   | 360 | 6/h    | precision_machining     |                     |
| smith      | 7  | unstamped_plate_run      | un   | 300     | 148  | 152 | 12.9 | 707   | 360 | 4/h    | unlisted_fabrication    |                     |
| smith      | 11 | armoury_order            | un   | 600     | 296  | 304 | 21.3 | 856   | 600 | 3/h    | contraband_armoury      |                     |
| technician | 1  | switches                 | le   | 16      | 0    | 16  | 3.4  | 281   | 100 | 12/h   |                         |                     |
| technician | 1  | torches                  | le   | 52      | 0    | 52  | 5.1  | 612   | 125 | 12/h   |                         |                     |
| technician | 2  | civic_market_lights      | le   | 100     | 0    | 100 | 15.2 | 395   | 240 | 6/h    |                         |                     |
| technician | 2  | repeaters                | le   | 84      | 0    | 84  | 7.5  | 672   | 180 | 12/h   |                         |                     |
| technician | 3  | controller_install       | le   | 135     | 82   | 53  | 5.2  | 617   | 320 | 6/h    |                         |                     |
| technician | 3  | crew_fair_lighting       | le   | 150     | 0    | 150 | 13.9 | 647   | 300 | 6/h    |                         |                     |
| technician | 3  | daylight                 | le   | 120     | 0    | 120 | 14.5 | 497   | 250 | 12/h   |                         |                     |
| technician | 3  | municipal_terminals      | le   | 210     | 0    | 210 | 13.9 | 906   | 280 | 12/h   | municipal_repairs       |                     |
| technician | 4  | sensor_repairs           | le   | 210     | 128  | 82  | 5.8  | 848   | 330 | 6/h    |                         |                     |
| technician | 5  | commercial_automation    | le   | 290     | 0    | 290 | 29.1 | 598   | 340 | 6/h    | automation_install      |                     |
| technician | 5  | security_array_install   | le   | 230     | 140  | 90  | 5.5  | 991   | 600 | 6/h    |                         |                     |
| technician | 7  | ledger_recovery          | un   | 270     | 130  | 140 | 13.5 | 622   | 360 | 4/h    | bypass_tooling          |                     |
| technician | 11 | ledger_brokerage         | un   | 800     | 390  | 410 | 31.5 | 781   | 620 | 3/h    | data_brokerage          |                     |
| vintner    | 1  | cellar_bottles           | le   | 28      | 0    | 28  | 5.7  | 295   | 125 | 12/h   |                         |                     |
| vintner    | 2  | civic_market_wine        | le   | 90      | 48   | 42  | 12.5 | 202   | 220 | 6/h    |                         |                     |
| vintner    | 2  | crew_canteen_cider       | le   | 120     | 60   | 60  | 10.2 | 353   | 280 | 6/h    |                         |                     |
| vintner    | 2  | crew_fair_wine           | le   | 130     | 72   | 58  | 9.0  | 387   | 280 | 6/h    |                         |                     |
| vintner    | 2  | pub_berry_wine           | le   | 120     | 72   | 48  | 9.0  | 320   | 260 | 8/h    | fundamentals            |                     |
| vintner    | 2  | sweet_must               | le   | 44      | 0    | 44  | 7.5  | 352   | 180 | 12/h   |                         |                     |
| vintner    | 3  | council_stall_wine       | le   | 210     | 96   | 114 | 17.5 | 391   | 340 | 6/h    |                         |                     |
| vintner    | 3  | tavern_cider             | le   | 135     | 80   | 55  | 13.1 | 252   | 300 | 8/h    | cellar_craft            |                     |
| vintner    | 3  | tavern_table_wine        | le   | 185     | 0    | 185 | 19.1 | 581   | 280 | 12/h   | cellar_craft            | vinery              |
| vintner    | 3  | vineyard_climate         | le   | 150     | 92   | 58  | 5.1  | 682   | 350 | 6/h    |                         |                     |
| vintner    | 4  | bulk_club_wine           | le   | 560     | 288  | 272 | 31.5 | 518   | 620 | 3/h    | fundamentals            |                     |
| vintner    | 4  | cellar_cooperage         | le   | 140     | 104  | 36  | 5.3  | 408   | 330 | 6/h    |                         |                     |
| vintner    | 5  | still_column             | le   | 150     | 112  | 38  | 6.4  | 356   | 600 | 6/h    |                         |                     |
| vintner    | 6  | estate_aged_wine         | le   | 230     | 120  | 110 | 9.9  | 667   | 440 | 6/h    | estate_vintages         |                     |
| vintner    | 6  | estate_cellar            | le   | 220     | 0    | 220 | 21.5 | 614   | 430 | 6/h    | estate_vintages         | vinery              |
| vintner    | 7  | quiet_moonshine          | un   | 200     | 96   | 104 | 16.2 | 385   | 340 | 4/h    | still_craft             |                     |
| vintner    | 9  | quiet_bottles            | un   | 206     | 0    | 206 | 19.5 | 634   | 360 | 4/h    | bootleg_trade           | kaleidoscope_tavern |
| vintner    | 10 | licensed_grain_spirit    | le   | 150     | 84   | 66  | 17.7 | 224   | 420 | 6/h    | distillers_licence      |                     |
| vintner    | 10 | licensed_spirits_supply  | le   | 140     | 0    | 140 | 19.5 | 431   | 400 | 6/h    | distillers_licence      | kaleidoscope_tavern |
| vintner    | 12 | reserve_run              | un   | 516     | 0    | 516 | 43.5 | 712   | 560 | 3/h    | grey_market_contacts    | kaleidoscope_tavern |

### Minutes to a new citizen's first credits (hands-on + waiting)

| career     | Core only                 | every mod                 |
|------------|---------------------------|---------------------------|
| farmer     | 31 (harvest, 30 cr)       | 31 (harvest, 30 cr)       |
| vintner    | 6 (cellar_bottles, 28 cr) | 6 (cellar_bottles, 28 cr) |
| smith      | 3 (stone_tools, 24 cr)    | 3 (stone_tools, 24 cr)    |
| gunsmith   | 9 (arrows, 32 cr)         | 9 (arrows, 32 cr)         |
| technician | 3 (switches, 16 cr)       | 3 (switches, 16 cr)       |

### Legal net credits per active hour / XP per active hour, by career level (every mod installed)

| level | farmer     | vintner    | smith      | gunsmith   | technician |
|-------|------------|------------|------------|------------|------------|
| 1     | 143 / 595  | 147 / 658  | 282 / 1471 | 112 / 439  | 306 / 735  |
| 2     | 143 / 595  | 193 / 933  | 282 / 1471 | 360 / 1080 | 336 / 720  |
| 3     | 245 / 727  | 315 / 1210 | 442 / 1222 | 443 / 1869 | 453 / 604  |
| 4     | 245 / 727  | 315 / 1210 | 442 / 1222 | 443 / 1869 | 453 / 604  |
| 5     | 338 / 2000 | 315 / 1210 | 478 / 1222 | 443 / 1869 | 521 / 2427 |
| 6     | 339 / 2289 | 368 / 1679 | 478 / 1222 | 443 / 1869 | 521 / 2427 |
| 8     | 374 / 2451 | 368 / 1679 | 478 / 1222 | 443 / 1869 | 521 / 2427 |
| 10    | 374 / 2451 | 368 / 1679 | 478 / 1222 | 482 / 1869 | 521 / 2427 |
| 12    | 411 / 2451 | 399 / 1679 | 478 / 1222 | 482 / 1869 | 521 / 2427 |

### The same with Core only (no TaCZ, Vinery, Kaleidoscope Tavern)

| level | farmer     | vintner    | smith      | gunsmith   | technician |
|-------|------------|------------|------------|------------|------------|
| 1     | 143 / 595  | 147 / 658  | 282 / 1471 | 112 / 439  | 306 / 735  |
| 2     | 143 / 595  | 193 / 933  | 282 / 1471 | 360 / 1080 | 336 / 720  |
| 3     | 245 / 727  | 265 / 1285 | 442 / 1222 | 443 / 1869 | 453 / 604  |
| 4     | 245 / 727  | 298 / 1289 | 442 / 1222 | 443 / 1869 | 453 / 604  |
| 5     | 338 / 2000 | 298 / 1289 | 478 / 1222 | 443 / 1869 | 521 / 2427 |
| 6     | 339 / 2289 | 368 / 1679 | 478 / 1222 | 443 / 1869 | 521 / 2427 |
| 8     | 374 / 2451 | 368 / 1679 | 478 / 1222 | 443 / 1869 | 521 / 2427 |
| 10    | 374 / 2451 | 368 / 1679 | 478 / 1222 | 482 / 1869 | 521 / 2427 |
| 12    | 411 / 2451 | 399 / 1679 | 478 / 1222 | 482 / 1869 | 521 / 2427 |

### Entry (apprentice) orders against the rest: mean net credits per hands-on hour

| career     | entry orders | entry net/h | other legal orders | other net/h | entry as a share of other |
|------------|--------------|-------------|--------------------|-------------|---------------------------|
| farmer     | 3            | 310         | 15                 | 376         | 82%                       |
| vintner    | 2            | 323         | 15                 | 426         | 76%                       |
| smith      | 3            | 577         | 11                 | 596         | 97%                       |
| gunsmith   | 3            | 449         | 7                  | 714         | 63%                       |
| technician | 4            | 515         | 7                  | 715         | 72%                       |

### Rent against income (a household of one)

| home                      | rent/h | at level | mean net/h | share | weakest career | share |
|---------------------------|--------|----------|------------|-------|----------------|-------|
| row_motel:motel_1         | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| row_motel:motel_2         | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| row_motel:motel_3         | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| row_motel:motel_4         | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| row_flats:flat_1          | 40     | 4        | 380        | 11%   | farmer 245     | 16%   |
| row_flats:flat_2          | 40     | 4        | 380        | 11%   | farmer 245     | 16%   |
| row_flats:row_house       | 100    | 8        | 437        | 23%   | vintner 368    | 27%   |
| rs_saltmarsh_motel:room_1 | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| rs_saltmarsh_motel:room_2 | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| rs_saltmarsh_motel:room_3 | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| rs_saltmarsh_motel:room_4 | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| rs_harrow_farm:farmhouse  | 115    | 6        | 430        | 27%   | farmer 339     | 34%   |
| city:ih_flats             | 40     | 4        | 380        | 11%   | farmer 245     | 16%   |
| city:bw_apartments        | 55     | 5        | 419        | 13%   | vintner 315    | 17%   |
| city:vw_cottage           | 85     | 7        | 430        | 20%   | farmer 339     | 25%   |
| city:vw_house_a           | 85     | 7        | 430        | 20%   | farmer 339     | 25%   |
| city:vw_house_b           | 85     | 7        | 430        | 20%   | farmer 339     | 25%   |
| city:ih_workshop          | 135    | 12       | 917        | 15%   | vintner 399    | 34%   |
| city:ih_warehouse         | 170    | 15       | 966        | 18%   | vintner 399    | 43%   |
| city:ih_pub               | 10     | 1        | 198        | 5%    | gunsmith 112   | 9%    |
| city:vw_bakery            | 40     | 4        | 380        | 11%   | farmer 245     | 16%   |
| city:bw_store             | 55     | 5        | 419        | 13%   | vintner 315    | 17%   |
| city:bw_lofts             | 70     | 6        | 430        | 16%   | farmer 339     | 21%   |
| city:vw_villa             | 260    | 15       | 966        | 27%   | vintner 399    | 65%   |

### Active hours until each housing milestone (household policy in `simulate`)

| household                     | rent flat | buy flat | rent house | buy house | levels at 400 h | net/h at 400 h |
|-------------------------------|-----------|----------|------------|-----------|-----------------|----------------|
| farmer                        | 2         | 9        | 8          | 32        | 30              | 411            |
| vintner                       | 1         | 8        | -          | 26        | 30              | 399            |
| smith                         | 1         | 7        | 2          | 24        | 30              | 517            |
| gunsmith                      | 1         | 8        | 2          | 25        | 30              | 521            |
| technician                    | 1         | 6        | 2          | 22        | 30              | 566            |
| farmer + gunsmith             | 1         | 4        | 2          | 13        | 30/30           | 932            |
| smith + technician            | 0         | 3        | 1          | 11        | 30/30           | 1015           |
| farmer + smith + gunsmith     | 0         | 2        | 1          | 8         | 30/30/30        | 1449           |
| vintner + technician + farmer | 0         | 3        | 1          | 10        | 30/30/30        | 1266           |

### Vehicle and boat tiers: active hours of the five careers' mean wage to afford one (prices are the planning table of D-101)

| tier | vehicle   | acquired by      | price | materials value | at level | mean net/h | hours | meant to be |
|------|-----------|------------------|-------|-----------------|----------|------------|-------|-------------|
| 1    | bicycle   | dealer           | 60    | 30              | 2        | 263        | 0.2   | 0.25        |
| 2    | motorboat | dealer           | 600   | 240             | 5        | 419        | 1.4   | 1.5         |
| 3    | quad      | bench (proposed) | 1450  | not priced      | 8        | 437        | 3.3   | 3.5         |
| 4    | small_car | bench (proposed) | 3400  | not priced      | 10       | 445        | 7.6   | 8           |
| 5    | large_car | bench (proposed) | 8800  | not priced      | 12       | 458        | 19.2  | 20          |
| 6    | truck     | bench (proposed) | 13000 | not priced      | 12       | 458        | 28.4  | 30          |

### A scarcity world (no ore to mine, D-120): legal net credits per active hour with metals from the shelves only, or with the regrowing sites' base metals (every mod)

| career     | level | ore world | shelves | sites + shelves | best as a share of the ore world |
|------------|-------|-----------|---------|-----------------|----------------------------------|
| farmer     | 1     | 143       | 143     | 143             | 100%                             |
| vintner    | 1     | 147       | 147     | 147             | 100%                             |
| smith      | 1     | 282       | 282     | 282             | 100%                             |
| gunsmith   | 1     | 112       | 112     | 112             | 100%                             |
| technician | 1     | 306       | 140     | 140             | 46%                              |
| farmer     | 3     | 245       | 245     | 245             | 100%                             |
| vintner    | 3     | 315       | 315     | 315             | 100%                             |
| smith      | 3     | 442       | 453     | 456             | 103%                             |
| gunsmith   | 3     | 443       | 443     | 443             | 100%                             |
| technician | 3     | 453       | 281     | 281             | 62%                              |
| farmer     | 5     | 338       | 333     | 348             | 103%                             |
| vintner    | 5     | 315       | 315     | 315             | 100%                             |
| smith      | 5     | 478       | 493     | 490             | 103%                             |
| gunsmith   | 5     | 443       | 447     | 446             | 101%                             |
| technician | 5     | 521       | 515     | 515             | 99%                              |
| farmer     | 8     | 374       | 370     | 383             | 102%                             |
| vintner    | 8     | 368       | 368     | 368             | 100%                             |
| smith      | 8     | 478       | 493     | 490             | 103%                             |
| gunsmith   | 8     | 443       | 447     | 446             | 101%                             |
| technician | 8     | 521       | 515     | 515             | 99%                              |

### Orders that cost more than they pay when every ore unit is bought from a shelf (a scarcity world; the quarry's base metals do not help these)

none

### Where a scarcity world's ore comes from: cheapest shelf price, units an hour the shelves sustain (all cities), units an hour the regrowing sites give back (the old quarry and the Valewood pit, shared by everybody)

| ore          | cheapest shelf | shelves/h | sites/h |
|--------------|----------------|-----------|---------|
| charcoal     | 2              | 26.0      | 0       |
| coal         | 3              | 34.7      | 22      |
| copper_ingot | 7              | 13.3      | 16      |
| diamond      | 150            | 1.2       | 0       |
| gold_ingot   | 38             | 3.3       | 0       |
| iron_ingot   | 9              | 32.0      | 28      |
| lapis_lazuli | 11             | 5.7       | 0       |
| quartz       | 8              | 10.0      | 0       |
| redstone     | 6              | 36.0      | 0       |
| e_scrap      | 5              | 17.3      | 0       |

### Salvage yards (D-138): heaps that refill in active time, and the mean items an active hour if every heap is stripped (one rotation of each yard stands in a world)

| yard               | heaps | minutes | heaps/h | items/h                                                                                        |
|--------------------|-------|---------|---------|------------------------------------------------------------------------------------------------|
| rs_kessler_salvage | 54    | 60      | 54      | chain 2.2, copper_ingot 8.1, iron_nugget 135.0, redstone 7.3, e_scrap 11.3, machined_parts 1.1 |

### Fences (D-125): rate, best rate with every payout skill, opening budget, the most they pay an hour after it, against the best legal hour at level 5

| city       | fence         | rate              | best rate | budget | per hour | best legal/h (L5) | share |
|------------|---------------|-------------------|-----------|--------|----------|-------------------|-------|
| blackwater | The Back Room | 50% (32% at home) | 60%       | 600    | 420      | 521               | 81%   |
| ironhaven  | The Yard      | 45% (30% at home) | 55%       | 450    | 360      | 521               | 69%   |

### Loops the data allows

none found

### Sensitivity: every hands-on minute scaled

| hands-on minutes | farmer net/h at L5 | vintner net/h at L5 | smith net/h at L5 | gunsmith net/h at L5 | technician net/h at L5 | smith rents flat (h) | smith buys flat (h) |
|------------------|--------------------|---------------------|-------------------|----------------------|------------------------|----------------------|---------------------|
| x0.7             | 388                | 402                 | 572               | 572                  | 642                    | 1                    | 6                   |
| x1.0             | 338                | 315                 | 478               | 443                  | 521                    | 1                    | 7                   |
| x1.4             | 317                | 271                 | 422               | 404                  | 498                    | 1                    | 8                   |

<!-- economy-model:end -->

## How to read the results against the owner's targets

| Target | Result | Reading |
| --- | --- | --- |
| First payout within an accessible early session | 3 to 31 minutes to the first credits, by career | Met on paper. The Farmer's 31 minutes is the wheat growing time (25 of the 31), the rest is two terminal visits; the other four careers can be paid inside ten minutes. |
| First profitable production in about 15 to 30 minutes | Farmer 31, Vintner 6, Smith 3, Gunsmith 9, Technician 9 | Met except for the Farmer, whose first profit is bounded by how fast a crop grows. A Farmer who buys seed stock at the starter shop is not faster; this is a design question (a quicker first crop, or a first order that is not a crop), not a balance one. |
| Apartment upgrade after several work cycles | A flat is rentable after **1 to 2 active hours** of one person's income (0 to 1 as a household), bought after **7 to 10** (3 to 5 as a household) | Rent is quick on purpose (the deposit and six prepaid hours are about one productive hour); buying takes a day of casual play. |
| House after consistent earning | Rented after **2 to 8** active hours (the Vintner does not rent it inside 400 hours under the household policy: the rent would be over a quarter of its income), bought after **22 to 32** hours alone and **10 to 16** as a household of two or three | Consistent with "after consistent earning"; the Vintner's tree is the weakest on the ladder and wants a better mid-game order. |
| Premium as a long-term goal | **No premium listing exists yet**: the ladder has ten categories, three have listings | The model can only price what is listed. Listing a villa or a penthouse would be an easy datapack change; the numbers it needs are in `ladder.json` (`rent_hint`, `buy_hint`). |
| No buy-sell loop | none found | The starter shop sells nothing for more than it buys; no order pays more than the shop charges for its goods; the exchange goods an order asks for are worth less than the order pays only for three declared courier errands, whose margin and hourly profit are capped by the shelf. |
| No AFK profit | structural, not modelled | Rent, exchange restocking and employer demand run on **active** time only; there is no payout without a delivery. |
| Vanilla delivery must not beat real production | Entry orders pay **63% to 77%** per hands-on hour of what the same career's other legal orders pay (table above); an invariant fails if that ever reverses | Met on paper. The exception is the Farmer's cannabis chain, which sits at 304 to 346 credits per hands-on hour against 473 for the rest of its legal work: the margin rules cap its price, so the **grafting** skill (about 30% more flower for the same nutrient, about 24% more profit) is the lever, and a played session has to say whether it is enough. |
| Regional scarcity and supplier stock stay meaningful | Every exchange commodity's shelf is spent once per household in the model | Met. |

## What the model cannot say

* **Time.** Every hands-on minute is an assumption. The sensitivity table shows that none of the findings above reverses between 0.7 and 1.4 times those minutes, which is weak comfort: a person's time at a Gun Smith Table, in a crop field or in a queue at a terminal has not been measured, and only a played session can. The first thing to measure is the real hands-on minutes of: a wheat order, a bread order, a Cannabis cycle with three plants, and a walk to the Valewood exchange.
* **Variance, theft and failure.** The model has no death, no raid, no checkpoint seizure (the underground table adds searches, nothing else), no price movement and no player who plays badly.
* **Crew coordination.** A household takes the globally best hour; real people do not. The Crew columns are an upper bound on cooperation, not a forecast.
* **Fuel, vehicles, factories.** None of these exist yet; see [the vehicle tiers](vehicle-tiers.md) and the roadmap.
