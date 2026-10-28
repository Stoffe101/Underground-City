# Player business v1 and shortage contracts (D-207, D-208)

**Status (October 28, eleventh pass): implemented. L2: `PlayerBusinessGameTests` (2 cases) and `ShortageContractGameTests` (3 cases) on the dedicated test server, with Q Shop's own wallet and a scratch work-order journal. No real client has set up a counter or taken a shortage contract yet, and no person has played either.** The current branch and its recent gameplay systems have not received human playtesting.

## Player business v1 (D-208)

**What it is.** A player who works in trade premises (a commercial workshop or a warehouse, rented or bought on the Home page; D-169) sets down a **Business Counter**. It opens the premises' business. The player stocks it with goods they made or bought, opens it, and the city's own street people walk in during a shop's hours and buy from it. The takings are paid into the player's Q Shop wallet when they collect them.

**Using the counter:**

| Action | What happens |
| --- | --- |
| Set it down in trade premises you work in | The premises' business is set up (one counter a premises). Anywhere else the block does nothing and says why. |
| Use it holding goods | The stack goes onto the counter, up to 64 goods in all. |
| Sneak and use it | Opens or closes the business. |
| Use it empty-handed | The statement, then the takings are paid into your wallet. |
| `/uc business` | The statement of every business you may run. |
| `/uc business collect`, `/uc business withdraw` | Collects the takings, or takes the stock back into your inventory, from anywhere. |

The counter's front, the side that faced you when you set it down, is where customers stand. A counter that is picked up closes the business. Its stock and its till stay in the journal, and a counter set down again in the same premises finds them: nothing is ever taken.

**What it sells, and for how much.**

* **What it takes:** plain goods (undamaged, no tag but a workshop grade, so nothing named, enchanted or stolen) that some city's shop also sells.
* **What it charges:** the lowest price any city's shop charges for that item, times the grade's weight (Standard 1.0, Fine 1.25, Reserve 1.6). A player does not set prices in v1.
* **Tax:** every sale pays the city 10% sales tax before the till sees it.

Buying from a shop to sell on therefore always loses the tax. A Vintner's own wine earns more over the counter than sold to the pub (22 less 2 tax, against the pub's 14 a bottle), but only as fast as customers come.

**The customers.** A street person going shopping (D-162) goes, one time in three, to an open counter within 40 blocks instead, one customer a counter at a time.

* They walk to the counter's front, through the premises' doors, opening them as an officer does (D-189). The building's door watch shuts each door again afterwards.
* They look round for 15 to 45 seconds, buy one thing at the counter, and walk back out to where they came from.
* A customer who cannot reach the counter buys nothing.
* `/uc_population status` counts visits and purchases.

A counter sells only when it is:

* opened;
* stocked;
* set on its block;
* in a shop's hours (`BusinessHours`, 08:00 to 18:00 by default);
* in premises in good standing whose founder still works there. Rent or upkeep owed, or a founder who left the premises, shuts it.

**Money, server-side and once.** Nothing here is a wallet. The work-order journal (the same fsynced journal as every order and sale) holds each business's stock, its till and the period's figures.

* **Stocking and taking back** are prepared settlements: the counter's stock and the player's absolute inventory are written together before the live inventory changes, so goods are never in both places nor in neither.
* **A sale** takes one unit, adds the price less tax to the till and counts it, persisted once.
* **Collecting** is a prepared settlement: the till is emptied with the wallet target before Q Shop's wallet changes, so the same takings are never paid twice.
* **A restart** keeps all of it.
* **Who collects:** whoever may use the premises' workstations (the holder, or a Crew member with that right), or the business's founder. Nobody else.

**No passive income.** A counter:

* sells only what was put on it;
* sells at most 12 units an hour of active play;
* sells only to a street person who walked in, and street people exist only while somebody is in the city.

The premises cost their rent or upkeep on the household's active clock as any home does; the statement shows it, and it is paid from a wallet or the Crew bank, never from the till. Nothing is charged, and nothing sells, while everybody is away.

**The statement** (empty-handed use, or `/uc business`):

* what sold since the takings were last collected, its gross and the city's tax;
* what is in the till;
* what the premises cost an hour and what is owed on them;
* what is on the counter at what price;
* when the counter is open but sells nothing, why.

## Shortage contracts (D-207)

A shop's line runs short (D-201) when its customers have bought its shelf down to a quarter. If it **stays short for five minutes of active play**, it posts a **shortage contract** on its city's terminals for the trade that makes it ("Shortage: 4 Berry Wine for The Anvil").

* **An ordinary work order with one taker:** taken at a terminal, delivered at a terminal in the shop's city, paid through the ledger's settlement once (the order's nonce).
* **Pay:** half as much again a unit as the shop pays its suppliers, and never as much as the shop charges, so buying from it to deliver never pays. Career XP is half as much again as selling to the shop.
* **The goods go onto the shop's shelf** (never above it), so the shortage is really filled. The keeper remembers the player (D-202) and the city's good name rises (D-204).
* **Limited:** one contract a line, two a city, four in all.
* **Time-bound:** open for thirty minutes of active play. An untaken contract lapses. One taken and not delivered by then can no longer be delivered (its holder cancels it), and its record goes ten minutes later.
* **Cooldown:** a line rests twenty minutes of active play after its contract is filled or lapses.
* **Anti-duplication:**
  * one taker;
  * a second delivery of the same order is refused by its nonce;
  * a filled contract cannot be taken again;
  * a second review never posts the same line twice.
* **Everything runs on the ledger's active clock**, which moves only while a real player is online: nothing is posted, lapses or rests while everybody is away. The contracts live in the work-order journal, so a restart keeps them.
* The players of the trade who are in the game are told once when a contract is posted.

## Evidence

* **`PlayerBusinessGameTests`** (batch `uc_player_business`):
  * a counter opens a business only in trade premises its holder works in, one per premises;
  * it refuses goods no shop sells and marked goods, and charges the city's lowest price;
  * stocking moves twenty bottles once;
  * twelve sales an hour of play, a thirteenth refused, the next hour selling again;
  * the till holds the sales less their tax, and a restart keeps stock and till;
  * a stranger may not run it; the till is paid into Q Shop's wallet once and the statement starts again;
  * the stock comes back;
  * a counter picked up closes the business and a counter set down again finds it;
  * customers find only an open, stocked counter within reach;
  * a customer buys only at the counter;
  * it is shut at midnight, while the rent is owed, and when closed.
* **`ShortageContractGameTests`:**
  * five minutes short posts one contract and not before;
  * it is offered to the trade and not another;
  * a unit pays more than the shop pays and less than it charges;
  * one taker, a second refused;
  * a delivery outside the city is refused with nothing taken; one in the city is paid once and fills the shelf;
  * a filled contract cannot be taken again, and the line rests until its cooldown;
  * a restart keeps an open contract;
  * an untaken contract lapses and the line rests;
  * a taken contract delivered late is refused with nothing paid, and can be cancelled;
  * a city never has more than two.

## Limits, said plainly

* No real client has set up a counter, watched a customer walk in, or taken a shortage contract. Customers' paths into real premises (stairs, several doors) are untested in a world.
* A player does not set prices, hire staff or advertise in v1. Customers buy one thing a visit at random from the counter.
* The counter's look uses vanilla textures by reference; it has no art of its own.
* Shortage contracts are delivered at a terminal, not over the shop's counter.
