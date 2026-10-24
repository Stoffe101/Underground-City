# Sprint report, October 24, 2026 (sixth pass): toward the First Fully Playable Sandbox Alpha

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Sixth Major Pass — First Fully Playable Sandbox Alpha, Roadmap Reconciliation, Deep Progression & Living-World Integration". Decisions D-167 to D-174 in [decisions.md](decisions.md). The milestone and where each of its twelve exit criteria stands are in the [roadmap](roadmap.md#milestone-first-fully-playable-sandbox-alpha).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, the Python suite, the economic model and scripted real clients (software-rendered in a virtual display, driven by synthetic key and mouse events). Evidence levels are named as in the roadmap: L1 unit and data checks, L2 GameTests and headless runs, L3 one scripted real client, L4 two scripted real clients, L5 a person. They are never added together. **The alpha milestone is not proven: its last criterion is a person playing it.**

## What changed for a player

* **No more freeze arriving in a city (D-167).** The roughly 600 ms pause on the first street pass after arriving in a city was the server waiting on Lost Cities' plan lock while its world-generation workers built the plans of the chunks being generated round the player. Core now records each chunk's plan as Lost Cities generates it and answers from the record; periodic questions about an unrecorded chunk wait until generation is quiet. A second wait, the alarm panels reading a chunk still being generated, is gone too. See [lost-cities-stalls.md](../technical/lost-cities-stalls.md#the-city-arrival-hitch-core-waiting-on-lost-cities-plan-lock-october-24-d-167).
* **The Gunsmith builds real TaCZ firearms (D-168).** Fourteen Gun Smith Table recipes turn a Firearm Parts Kit (legal) or an Unmarked Parts Kit (underground), both from the Gunsmith's Bench, and a few ingots into TaCZ pistols, revolvers, shotguns and, unmarked, an Uzi, an MP5A5 and an AK-47. A kit build leaves the table with a serial from its city's register and the maker's name, or with no number at all, which a checkpoint treats as restricted. Two contracts ask for builds by provenance.
* **Trade premises (D-169).** Ironhaven's commercial workshop and warehouse make a workshop station inside them run one more batch a quarter faster for whoever may use them, and open three bulk orders. They cost what any home costs, on the household's active clock; a lapse withdraws the bonus and takes nothing. Eight of the ten property categories now have a building in a new furnished world.
* **Crew contracts (D-170).** A Crew takes on a contract of parts by career (the depot canteen; the Valewood harvest fair). Each member delivers their own part for their own pay; when the last part lands the bonus is split equally among those who delivered, paid once each, at once or at the next login. A supplier can fill a part nobody can make, at a stated price, earning nobody a share.
* **A civic project that changes the world (D-171).** The Valewood Market Council's contributions (one of them open to every career) reopen the market: two stalls with lanterns are built once on public ground near Valewood's centre, residents talk about it, and the council's better stall orders open.
* **Beginner burglary pays something (D-172).** A savings tin and silver cutlery turn up from the shabbiest homes; a tier 1 house is worth about 23 credits to a fence instead of 2.
* **The first raid (D-173).** Kessler in Blackwater hires a thief with Professional Tools (Thief level 8) to lift a seized shipment from Ironhaven's bonded store, behind the warehouse's own high-security lock (a Pro Pick Set), cameras and alarm and the police beat, after the yard's night trade closes at three. The order is tracked on the status display (and Xaero's Minimap): the store's door, then Blackwater. The shipment is stolen goods at any checkpoint on the way.
* **General skills that do something (D-174).** Trading rises with legal sales and makes the exchanges up to 5% cheaper (inside the 10% cap the career skills share); Social rises with work for the city and the Crew and lets legal buyers offer trusted work one step sooner.
* **Smaller things.** The registration card no longer promises Blackwater a harbour that does not exist; Valewood's street people prefer streets near the player's height, so the hills hide fewer of them (not yet measured).

## Evidence

| What | Level | Result |
| --- | --- | --- |
| Python suite | L1 | 334 of 334 pass |
| Repository validation, item-source audit, burglary audit | L1 | pass; the audit counts the seized shipment as a code source (the raid's search) |
| Economic model (`scripts/economy_model.py --check`) | L1 | every invariant holds with the new orders, the raid (35 hands-on minutes) and the bulk orders |
| GameTests, default set | L2 | 407 of 407 required pass (run 122, before D-174) |
| GameTests with TaCZ (firearms set) | L2 | 407 of 407 required pass (pack run 2), including the two kit-build tests |
| Hosted: the Crew journey, two real clients and a server killed without a stop sequence | L4 | passed on `b521ffc`, with the new Crew contract step (server-driven) |
| Hosted: the combined journey (generated Lost Cities world, every pack set, two real clients, a server killed), the plain journey and the every-set journey | L4 | passed on `b521ffc` |
| Hitch runs 1 to 4 (`-PucPlaytest=hitch`, one real client, a new furnished archipelago world each) | L3 | run 4, with every fix: no tick over 250 ms after the world was made; worst 235 ms on the teleport into Ironhaven (1,150 ms in run 1) and 137 ms walking the road (307 ms); details in [lost-cities-stalls.md](../technical/lost-cities-stalls.md#the-city-arrival-hitch-core-waiting-on-lost-cities-plan-lock-october-24-d-167) |

## Not done, and why

## Bugs found and fixed in this pass

* The arrival hitch (above), and the alarm panels' blocking read found while measuring it.
* The registration card's harbour.
* The rifle-chain GameTest counted only the contracts' XP after the build at the table began paying its own (found by the firearms-pack run 1).

## Next
