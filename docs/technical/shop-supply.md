# Selling to a shop: supply, shelves and the city's customers (D-164)

**Status (October 24, 2026, fifth pass): in Core and GameTested; one scripted real client sold three bottles of berry wine to the Ironhaven pub over its counter (workshop runs, see the pass report). Nobody has played it.**

Before this pass a shopkeeper's counter (D-126) only sold, from a finite city shelf that refilled on its own in active play. Now a shop whose counter sells something a trade makes **also buys it** from whoever brings it. What it buys goes onto the same shared shelf it sells from, and the city's customers buy from that shelf.

## Which shops buy what

The lines are in `content/gameplay/shop-supply.json`. A line that buys carries a `supply` block, and `scripts/generate_survival_supply.py` writes it into `data/underground_city/regional_stock/shops`.

| Shop (outlet) | City | Buys | It pays a unit | It sells at | XP a unit for |
| --- | --- | --- | --- | --- | --- |
| Builders' Yard (`shop:builders`) | Ironhaven | Fasteners, Steel Brackets, Barrel Hoops, Coil Springs, Gear Set | 2, 8, 5, 4, 30 | 3, 12, 8, 6, 45 | a Smith |
| The Anvil (`shop:pub`) | Ironhaven | Berry Wine, Apple Cider | 14, 12 | 22, 18 | a Vintner |
| The Bar (`shop:bar`) | Blackwater | Berry Wine, Cask-Aged Wine, Grain Spirit | 15, 36, 17 | 24, 55, 26 | a Vintner |
| The Bakery (`shop:bakery`) | Valewood | Flour | 4 | 6 | a Farmer |
| General Store (`shop:general`) | Valewood | Produce Crate | 26 | 40 | a Farmer |
| Corner Store (`shop:store`) | Blackwater | Fruit Crate | 26 | 40 | a Farmer |

* **A shop never pays more than it charges.** `pays` is always below the line's price, which the generator and `RegionalCatalog` both refuse to break. Buying from a counter and selling back is therefore always a loss.
* **The shelf is finite.** A shop buys only while its shelf has room (its capacity is 4 to 32 units). These shelves start low (1 to 8 units), so a new world's shops want goods.
* **The everyday lines** (stew, bread, bricks) buy nothing. They refill on their own as before.

* **A shopkeeper asks.** While the emptiest of a counter's supplied shelves is below a third full, its shopkeeper's line is "We're nearly out of Berry Wine. Bring some to the counter and I'll pay 14 a unit." The opportunity comes from the shop, not from a board (`HouseholdService.wanted`).

## Selling

* **The counter:** speak to the shopkeeper while they are in and ask what they sell. The Trade page lists the counter's lines.
  * A line the shop buys shows "buys at N" beside its stock, in green when the player carries some.
  * Its second button becomes **Sell N**, where N is what the player carries, capped by the order limit and the shelf's room.
  * The tooltip says what the shop pays and how many the player carries.
* **What is accepted:** the line's own item, undamaged, with no tag except a workshop grade. Named, enchanted, damaged or **stolen** goods are never bought at a counter (stolen goods go to a fence).
* **Pay:** the shop's price a unit times the grade's price weight (Standard 1.0, Fine 1.25, Reserve 1.6), rounded. A seller of the line's own trade also earns its career XP.
* **Server-authoritative and once:** `ShopSupply` judges the sale against the open counter (the shopkeeper at it, the player in reach). It then commits through the work-order ledger's single fsynced settlement, `WorkOrderLedger.prepareSupply`, with these together:
  * the shelf's units raised (never above capacity);
  * the sale's nonce;
  * the inventory without the goods, the wallet and the career XP.

  It does all of this before the live player or the Q Shop wallet changes. Recovery replays the targets after a crash. The same nonce is refused a second time, so a repeated packet never pays twice or fills the shelf twice.
* **A till that is down** (a Technician's shop fault, D-165) stops the shop paying suppliers until a Technician comes: "The till and card network are down, so the shop cannot pay a supplier." The shop still sells.

## The city's customers

* The street people of the population service (D-162) go into open shops and browse their public floor. Since D-164, one customer in two who browsed a shop buys **one unit** off its shelf on the way out (`PopulationService.buyFromShelf`, `WorkOrderLedger.drawDown`), from a line a supplier can fill.
  * This never takes a shelf below empty, and never happens while the shop's till is down.
  * The shelf a Vintner filled empties again in active play, so the shop buys again: a demand made by the city's people, not a timer.
* `/uc_population status` counts what the customers bought off a shelf.

## Evidence

* **GameTests:** `WorkshopGameTests.aShopBuysASuppliersGoodsOnceOntoAShelfItsCustomersEmpty` (the pub's wine line and its terms; a sale raises the shelf and settles; the same nonce refused; never above the shelf; customers draw down to empty and no further; no line buys back for more than it sells). `TechGameTests.aMonitoredAlarmIsAlwaysArmedAndAShopDownCannotSell` (a shop with its till down refuses to pay a supplier).
* **Supply data:** `python scripts/generate_survival_supply.py --check` (the supply terms and the shelf limits).
* **Real client:** the workshop scenario sells three bottles to the pub with a real click on Sell and reads the wallet and the shelf back from the server (see `docs/production/living-population-professions-pass.md`).

## Limits

* Nobody has played it. The prices and shelf sizes are first numbers.
* Customers buy only while somebody is in the city to make them walk. A shelf does not empty while nobody plays.
* Only the six shops above buy. The hardware store and the roadside places buy nothing yet.
* A shelf that stays short for five minutes of active play posts a timed **shortage contract** for its trade (D-207), and a player's own counter sells to the same street people (D-208): see [player-business.md](player-business.md).
