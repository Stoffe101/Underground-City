# Skill trees: depth, effects and what each skill is for (D-098)

**Status (October 20, 2026): implemented in Core; the validator, the effects and the saves are tested by Python tests and Forge GameTests; the scrolling tree page was photographed by the scripted client at three resolutions. No person has played a character through a tree.** This extends the career design of [legal-and-underground-careers.md](legal-and-underground-careers.md) (D-049) and [professions.md](professions.md). It does not change the rule that every tree is fully affordable at level 30 and that no choice locks anything out.

## What changed from October 19

| Before | Now |
| --- | --- |
| 8 to 10 skills per career, the last row a contract gate | 12 to 14 skills per career, 25 to 29 of the 29 points a level-30 character earns |
| Most skills only **opened a contract**; the only numeric effects were a search modifier and a 5% exchange discount | Four new **effects**, each consumed by one real system (below), on top of contracts, licences, search modifiers and the exchange discount |
| `legal` skills could depend on an underground one (the Controlled-Crop Licence and the Distiller's Licence did) | The legal line, the foundation and the utility skills **never need a crime**. A new **crossover** branch joins a legal skill to an underground one and is the only place the two lines meet |
| Every skill had at most one prerequisite | Prerequisites are *all-of* and 2 is common: three or more skills per tree need two |
| Trees were squeezed to fit five rows | Trees are up to **six rows deep** and the page **scrolls** (mouse wheel, drag, arrow keys) with a scroll bar |
| No ceiling on stacking | A **per-node and a whole-tree ceiling** for every percentage, enforced by the validator (a tree that breaks one does not load) |

## The effects, and the system that consumes each

| Effect (in a skill's `effects`) | Consumer | Ceiling (node / tree) | Rounding |
| --- | --- | --- | --- |
| `legal_payout_percent` | `WorkOrderService.complete` through `SkillService.payout`: the credits a **legal** contract pays, fixed at delivery and written into the prepared record (so a crash replays the same amount) | 6 / 12 | whole credits, bonus **rounded down** |
| `underground_payout_percent` | the same, for an **underground** contract | 6 / 12 | the same |
| `yield_percent` | `RestrictedCropBlock.playerDestroy`: extra Sable Leaf bales when a **player** harvests a **mature** plant (the average harvest is 2.5; 30% adds 0.75 bales on average, as whole bales plus a chance of one more). Pistons, water and creative mode earn nothing extra | 30 / 40 | `extraBales` is a pure function pinned by a test |
| `upgrade_discount_percent` | `PropertyService.upgrade`: the **acting** member's price of a home upgrade, from their wallet or the Crew bank; the Home page shows the price they pay | 15 / 20 | discount rounded down (the price rounds up) |
| `exchange_discount` | `RegionalMarket.unitPrice` (unchanged) | 0.05 / 0.10 | unchanged |
| `inspection_modifier` | `CheckpointService` and the witness roll (unchanged) | 0.25 / 0.35 | unchanged |
| `licences` | `CheckpointService` permit check (unchanged) | - | - |

Every effect is also shown on the skill's detail line in the terminal (the "effects" line under the description). The model (`economy_model.py`) pays the bonus on the orders a household takes and **checks the exploit rules against the highest payment an order can ever make at the levels where it is offered**, so a payout skill cannot quietly make a loop profitable (it caught two courier errands and one flat 12% mutation while this was being written).

Nothing here is a flat 5% menu discount any more: the utility branch holds the exchange discount *and* a home-trade skill (the Vintner's Cellar Fit-Out, the Smith's Fit-Out Trade, the Gunsmith's Bench Discipline, the Technician's Home Wiring). The Farmer's utility branch leads to **Selective Grafting**, a crossover, because the plant it improves is restricted.

## Rules the validator enforces (`SkillRules.validateTree`, mirrored in `tests/test_underground_content.py`)

* 1 to 16 skills per tree; ids are `<career>.<name>`; a root; both a legal and an underground branch; the whole tree costs at most 29 points; a node costs 1 to 4; a prerequisite's level is never higher than the skill's.
* Prerequisites exist and are acyclic. A **legal** or **foundation** skill has no underground ancestor. A **crossover** needs at least two prerequisites, **one legal and one underground** (directly or through its lineage).
* Every percentage is in range per node and per tree; titles are at most 40 characters and descriptions at most 170; a short title is at most 20.
* **No dead leaf**: a skill nothing builds on must carry an effect, open a work order or be one of the two skills Core code checks directly. The only stepping stones with no effect of their own are the five foundations and the Vintner's Home Distilling.

## The trees as shipped

#### Farmer (14 skills, 29 of 29 points)

| Skill | Branch | Cost | Level | Needs | Does |
| --- | --- | --- | --- | --- | --- |
| Field Fundamentals | core | 1 | 2 | - | opens the tree |
| Greenhouse Management | legal | 2 | 3 | Field Fundamentals | opens greenhouse_wholesale |
| Cultivar Broker | legal | 2 | 5 | Greenhouse Management | opens heirloom_seeds |
| Estate Wholesale | legal | 2 | 8 | Cultivar Broker | +4% legal contracts; opens estate_pantry |
| Farm Cooperative | legal | 2 | 11 | Estate Wholesale | +4% legal contracts |
| Field Logistics | utility | 1 | 4 | Field Fundamentals | 5% off exchange goods |
| Selective Grafting | crossover | 2 | 9 | Greenhouse Management, Restricted Cultivation | +30% Sable Leaf bales |
| Controlled-Crop Licence | crossover | 2 | 10 | Restricted Cultivation, Greenhouse Management | licence: restricted_cultivar; opens licensed_cultivar_supply |
| Two Sets of Books | crossover | 2 | 13 | Estate Wholesale, Underground Trade | +3% underground contracts; -5 points of search chance |
| Restricted Cultivation | underground | 3 | 6 | Field Fundamentals | **needed to plant a Sable Leaf cutting** (`RestrictedCuttingItem`) |
| Concealed Stores | underground | 2 | 8 | Restricted Cultivation | -12 points of search chance |
| Underground Trade | underground | 3 | 9 | Restricted Cultivation | opens quiet_bales |
| Grey Market Contacts | underground | 3 | 12 | Underground Trade | opens reserve_load |
| Back-Lot Network | underground | 2 | 14 | Grey Market Contacts, Concealed Stores | +6% underground contracts |

#### Vintner (13 skills, 27 of 29 points)

| Skill | Branch | Cost | Level | Needs | Does |
| --- | --- | --- | --- | --- | --- |
| Vine and Cellar Fundamentals | core | 1 | 2 | - | opens the tree |
| Cellar Craft | legal | 2 | 3 | Vine and Cellar Fundamentals | opens tavern_table_wine |
| Estate Vintages | legal | 2 | 6 | Cellar Craft | +4% legal contracts; opens estate_cellar |
| Cellar Master's Label | legal | 2 | 11 | Estate Vintages | +4% legal contracts |
| Cellar Logistics | utility | 1 | 4 | Vine and Cellar Fundamentals | 5% off exchange goods |
| Cellar Fit-Out | utility | 2 | 7 | Cellar Logistics, Cellar Craft | -10% home upgrades |
| Distiller's Licence | crossover | 2 | 10 | Home Distilling, Cellar Craft | licence: restricted_spirits; opens licensed_spirits_supply |
| House Label | crossover | 2 | 13 | Estate Vintages, Bootleg Trade | +3% underground contracts; -5 points of search chance |
| Home Distilling | underground | 3 | 6 | Vine and Cellar Fundamentals | a stepping stone: Concealed Stores, Bootleg Trade and the Distiller's Licence build on it |
| Concealed Stores | underground | 2 | 8 | Home Distilling | -12 points of search chance |
| Bootleg Trade | underground | 3 | 9 | Home Distilling | opens quiet_bottles |
| Grey Market Contacts | underground | 3 | 12 | Bootleg Trade | opens reserve_run |
| Quiet Cellar | underground | 2 | 13 | Grey Market Contacts, Concealed Stores | +6% underground contracts |

#### Smith (12 skills, 25 of 29 points)

| Skill | Branch | Cost | Level | Needs | Does |
| --- | --- | --- | --- | --- | --- |
| Forge Fundamentals | core | 1 | 2 | - | opens the tree |
| Structural Works | legal | 2 | 3 | Forge Fundamentals | opens municipal_structural |
| Armoury Contracts | legal | 2 | 5 | Structural Works | +4% legal contracts; opens guard_plating |
| Industrial Licence | legal | 2 | 8 | Armoury Contracts | licence: restricted_components |
| Foundry Partnerships | legal | 2 | 11 | Industrial Licence | +4% legal contracts |
| Precision Machining | utility | 2 | 6 | Forge Fundamentals | 5% off exchange goods |
| Fit-Out Trade | utility | 2 | 9 | Precision Machining, Structural Works | -15% home upgrades |
| A Real Maker's Stamp | crossover | 2 | 12 | Industrial Licence, Unlisted Fabrication | +3% underground contracts; -6 points of search chance |
| Unlisted Fabrication | underground | 3 | 7 | Structural Works | opens unstamped_plate_run |
| Unstamped Production | underground | 2 | 9 | Unlisted Fabrication | -15 points of search chance |
| Contraband Armoury | underground | 3 | 11 | Unlisted Fabrication | opens armoury_order |
| Foundry Fence | underground | 2 | 13 | Contraband Armoury, Unstamped Production | +6% underground contracts |

#### Gunsmith (12 skills, 26 of 29 points)

| Skill | Branch | Cost | Level | Needs | Does |
| --- | --- | --- | --- | --- | --- |
| Bench Fundamentals | core | 1 | 2 | - | opens the tree |
| Licensed Repairs | legal | 2 | 3 | Bench Fundamentals | opens licensed_sidearm |
| Arms Dealer Licence | legal | 3 | 6 | Licensed Repairs | licence: firearms_licensed |
| Security Contracts | legal | 2 | 8 | Arms Dealer Licence | +4% legal contracts; opens licensed_shotgun |
| Range Master | legal | 2 | 11 | Security Contracts, Ballistics Lab | +4% legal contracts |
| Ballistics Lab | utility | 2 | 5 | Bench Fundamentals | 5% off exchange goods |
| Bench Discipline | utility | 2 | 9 | Ballistics Lab, Licensed Repairs | -10% home upgrades |
| A Dealer's Front | crossover | 2 | 12 | Arms Dealer Licence, Unregistered Builds | +3% underground contracts; -6 points of search chance |
| Unregistered Builds | underground | 3 | 7 | Licensed Repairs | opens unregistered_smg, dray_parts_run |
| Filed Serials | underground | 2 | 9 | Unregistered Builds | -10 points of search chance; opens dray_rifle_run |
| Arms Brokerage | underground | 3 | 11 | Unregistered Builds | opens arms_brokerage |
| Ghost Guns | underground | 2 | 13 | Arms Brokerage, Filed Serials | +6% underground contracts |

#### Technician (12 skills, 26 of 29 points)

| Skill | Branch | Cost | Level | Needs | Does |
| --- | --- | --- | --- | --- | --- |
| Bench Electronics | core | 1 | 2 | - | opens the tree |
| Municipal Repairs | legal | 2 | 3 | Bench Electronics | opens municipal_terminals |
| Automation Installer | legal | 2 | 5 | Municipal Repairs | +4% legal contracts; opens commercial_automation |
| Security Systems | legal | 3 | 8 | Automation Installer | licence: restricted_electronics |
| Systems Integrator | legal | 2 | 11 | Security Systems | +4% legal contracts |
| Signal Analysis | utility | 2 | 6 | Bench Electronics | 5% off exchange goods |
| Home Wiring | utility | 2 | 8 | Signal Analysis, Municipal Repairs | -15% home upgrades |
| Compliance Consultant | crossover | 2 | 12 | Security Systems, Bypass Tooling | +3% underground contracts; -6 points of search chance |
| Bypass Tooling | underground | 3 | 7 | Municipal Repairs | opens ledger_recovery; **needed to bypass a secured terminal** (`SecureTerminalService`) |
| Signal Scrubbing | underground | 2 | 9 | Bypass Tooling | -10 points of search chance |
| Data Brokerage | underground | 3 | 11 | Bypass Tooling | opens ledger_brokerage |
| Ghost Traffic | underground | 2 | 13 | Data Brokerage, Signal Scrubbing | +6% underground contracts |

## Save compatibility

A save keeps the **ids** of the skills a character owns; the points spent are recomputed from the catalog at every load. So the redesign keeps **every previous id**, keeps each one in its career, and **never raises a cost** (two were lowered: Estate Wholesale and Estate Vintages 3 → 2, so a character who owns them has a point back). `tests/test_skill_tree_compat.py` pins the October 19 table. Prerequisites of owned skills were tightened (the Controlled-Crop Licence now also needs Greenhouse Management) but `SkillRules.blocker` answers "Already unlocked" before it looks at prerequisites, so an owned skill is never lost or locked; a test pins that order. Nothing about the XP curve (`125 x (level - 1)^2`) or the one point per level changed.

## The page

The tree is drawn in three columns (legal, trade, underground) with each skill a full-height card: icon, the **whole** name on up to two lines, and the state (OWNED, READY with its cost, LOCKED with its level). Links are thin and muted, brighter when the parent is owned, amber on the way to the selected skill. A crossover is drawn in the middle column in the blend of the two colours with the scales icon. A tree deeper than the page **scrolls** (wheel, drag, up/down arrows), keeps the selected skill in view, draws a scroll bar, moves one whole row per wheel notch or arrow press (so no card is cut in half at the top) and says "More below" or "More above" in the middle heading; the headings and the detail panel stay put. The detail panel shows the description, the effect line, the cost and level, and the reason a skill is locked.

## Limits

* **The effects are balanced on paper.** The payout ceilings (8% legal and 9% underground in every shipped tree) were chosen so that the margin rules and the risk-adjusted underground table still hold; whether they feel like a reward is a playtest question.
* **Selective Grafting only matters to a Farmer who raises the controlled crop.** A Farmer who never learns Restricted Cultivation cannot use it. That is intended (the plant is the restricted thing) but it means the Farmer's legal-only path has less depth than the other four careers'.
* **Nothing in the Gunsmith's tree gates a recipe or a blueprint**: the Gun Smith Table is gated by TaCZ Blueprints items, not by skills (D-057, `BlueprintGate`). A skill-gated recipe layer is a roadmap item, not built.
* The detail panel shows two lines of description and one line of effect on the narrowest window. The first 720p capture showed descriptions of 80 characters and more cut off with an ellipsis, so every shipped description is now at most **76 characters** and **may not restate a number** (the effect line is generated from the data and cannot go stale); `tests/test_skill_tree_layout.py` enforces both. The validator's own limit of 170 characters is unchanged, so a mod author's longer text still loads and is cut with an ellipsis, never silently.
