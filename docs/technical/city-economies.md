# City economies (twelfth pass)

**Status:** an audit of the game's own data, with rules checked by `scripts/city_economies.py --check` and `tests/test_city_economies.py` (L1: arithmetic and data, not play). One change was made: the Technician's two office and data-room contracts were bound to a new Blackwater buyer. No person has traded between the cities in this branch. The current branch and its recent gameplay systems have not received human playtesting.

The owner asked for each city to have its own economy:

* **Ironhaven:** industry, manufacturing, engineering, machinery, warehouses, security and logistics.
* **Valewood:** farming, wineries, homes, farm trade, food and small community businesses.
* **Blackwater:** commerce, electronics, offices, nightlife, modern homes, underground markets and high-value trade.

Trade between them should be worth a trip for a real reason, with no trivial arbitrage and no necessity sold in only one city.

## What the data already does

Read from what the game loads; the table below is generated and checked.

<!-- city-economies:begin -->

| | Ironhaven | Valewood | Blackwater |
| --- | --- | --- | --- |
| Exchange lines (default pack) | 44 | 47 | 35 |
| Goods only this city's exchange sells | apple_cider, barrel_hoops, bearing_set, drive_shaft, engineer_toolkit, fasteners and 8 more | agro_catalyst, biopolymer, flour, produce_crate, rare_seeds | aged_wine, bypass_kit, control_circuits, encrypted_design, fruit_crate, grain_spirit and 3 more |
| Shops buy from players | smith, vintner | farmer | farmer, vintner |
| Buyers | Ironhaven Guard Procurement, Rusk's back bar | Hollis & Sons Haulage, Valewood Hunting Co-op, Valewood Market Council, Vesper Holdings | Blackwater Harbour Taproom, Blackwater Licensed Dispensary, Blackwater Tower Management, Dray, the Quartermaster, Kessler & Daughters, Marrow, grey-market broker |
| Legal buyers' orders: career (orders, credits) | gunsmith (1, 540–540), smith (2, 270–700) | any (1, 60–60), farmer (3, 110–560), gunsmith (2, 170–190), smith (1, 170–170), technician (2, 100–640), vintner (2, 90–210) | farmer (3, 225–700), technician (2, 560–930), vintner (3, 140–500) |
| Underground buyers' orders | vintner (4, 200–516) | smith (3, 300–900), technician (3, 270–1000) | any (1, 700–700), farmer (4, 237–950), gunsmith (7, 205–730) |
| Witness chance | 35% | 25% | 10% |
| Homes cost (city price factor) | 1.15× | 0.90× | 1.00× |
| Street people (count; workers, visitors %) | 540; 34%, 6% | 300; 28%, 4% | 560; 32%, 14% |

<!-- city-economies:end -->

In plain terms:

* **Each city sells its own goods.**
  * Ironhaven: parts, alloy and the Smith's components.
  * Valewood: seeds, catalyst, biopolymer and flour.
  * Blackwater: circuits, designs, optics and its wine cellar's aged wine and spirits.
* **A trade's best buyers are in its city.** Most underground buyers are in Blackwater, the most lenient city. Ironhaven sells no spirits under its law, so its back bar buys them off the books.
* **The law differs.** Ironhaven sees and searches most and Blackwater least. Homes cost 15% more in Ironhaven and 10% less in Valewood.
* **The streets differ.** Blackwater has the most visitors (its nightlife) and Valewood the fewest people.

## Rules checked

| Rule | Why |
| --- | --- |
| Each city has a legal buyer for every career the owner gave it (Ironhaven: Smith, Gunsmith; Valewood: Farmer, Vintner; Blackwater: Technician, Vintner), and a shop that buys one of those trades' goods | A reason to travel with goods |
| Nothing a shop buys from players is sold by any exchange, with every discount a buyer can hold (10%), for the same or less | No buy-here-sell-there loop; with the player business rule ([player-business.md](player-business.md)) and the shortage contracts' price band, no shop goods turn into credits |
| Bread, the First Aid Kit and torches are sold in every city | No trip is forced for a necessity |
| Witness chances run Blackwater < Valewood < Ironhaven, and no two cities fine alike | The laws the summaries describe |
| Every buyer has at least one order | A contact that leads nowhere is a dead end |

The same item sold in two cities differs by at most a third in price (mushroom stew, string, iron, baked potatoes). Shops buy only through their supply lines, so a price difference is never a loop by itself.

## The change made (D-214)

The Technician's legal city work was all in Valewood: the Market Council's network upgrade, with Vesper Holdings underground. Nothing in Blackwater, the city of offices and electronics, paid a Technician. A new legal buyer, **Blackwater Tower Management**, now takes two contracts, which can be delivered only at a Blackwater terminal:

* **Security for a Blackwater office tower** (level 14, 560 credits);
* **Fit out a data centre** (level 21, 930 credits).

The career balance did not move: the Technician's best legal hour at levels 15 and 20 is the panel service work, not these contracts (`career_balance.py`). The economy model charges each contract a buyer trip, so its rate per hands-on minute fell, from 1,057 to 844 and from 1,151 to 990.

## Not done

* A city's shortages, demand for a player's counter and civic projects follow its own shops, but nothing yet makes, say, Ironhaven's shops run short of food faster than Valewood's.
* Ironhaven has no Technician buyer for its security and logistics, and Valewood's Vesper Holdings (underground data) is an odd fit for a farming town. Moving an underground buyer changes the checkpoint routes the risk model prices, so it was left for the owner.
* Prices of the same good differ little between cities on purpose: the reason to travel is a buyer, not a price gap.
