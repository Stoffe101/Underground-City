# The property economy: rent, ownership, upkeep, storage and upgrades (D-099)

**Status (October 20, 2026): implemented in Core and tested by scripts and Forge GameTests (23 in `PropertyGameTests`, plus the Crew, home-guard and migration tests they sit beside). No person has rented, paid for, upgraded or been evicted from a home in a session of play.** The numbers are the model's, not a player's: see [economy-model.md](economy-model.md). This replaces the "rent is an open question" note of [crew-economy.md](crew-economy.md) and extends [crews.md](../design/crews.md).

## The player's view in one paragraph

You can **rent** a home for a small price per active hour or **buy** it. A rented home needs a deposit and is paid in advance in hours; an owned one has a small upkeep and can be improved. The motel is the cheap first step (the first two hours are the city's welcome gift, once per person); a flat is the first real home; the Row House has a workshop. Unpaid rent never costs you your things: it costs you the comforts of the home, in four clear steps, and paying it all back restores them at once. You can pay by hand from your own wallet, let a Crew pay from its bank, or switch on automatic payment. Everything is done at the home's own terminal on the **Home** page of the Crew screen, which also works for a player with no Crew.

**The list on Find a home (D-136)** puts what you can move into now first: the free welcome rooms, then the least cash on the day you move in (deposit and first hour, or the price of a home that can only be bought), then the nearest. It holds up to eight homes within 64 blocks, and those are the eight a new player can most easily take; the page count and arrows sit at the right end of the tab row.

## What exists (data in `data/underground_city/properties/ladder.json`, one datapack file)

| Category (tier) | Rent / h | Purchase | Built? | Listed homes |
| --- | --- | --- | --- | --- |
| Motel room (1) | 10 | rent only | **yes** | Row Motel rooms 1 to 4 (2 containers, 1 private) |
| Small apartment (2) | 40 | 2,000 | **yes** | Row Flats 1 and 2 (5 containers, 2 private, bed, crafting, furnace, terminal) |
| Comfortable apartment (3) | 55 | 3,000 | **yes** (furnished cities) | city listings: a comfortable apartment in a furnished tower or tenement |
| Larger apartment (4) | 70 | 4,200 | no | none |
| Small house (5) | 85 | 5,400 | **yes** (furnished cities) | city listings: three house types in the furnished cities |
| House with workshop (6) | 100 | 7,000 | **yes** | the Row House (10 containers, 3 private, adds the workshop) |
| Farmhouse (7) | 115 | 8,500 | **yes** | Harrow Farmhouse, a roadside place (D-138) |
| Commercial workshop (8) | 135 | 10,500 | **yes** (furnished cities) | Ironhaven's workshop, **trade premises** (D-169) |
| Warehouse or production facility (9) | 170 | 14,000 | **yes** (furnished cities) | Ironhaven's warehouse, **trade premises** (D-169) |
| Premium residence (10) | 260 | 28,000 | no | none |

Ten categories are *priced*; **eight have a building** in a new furnished archipelago world (October 24; three before the city homes of D-127 and the roadside places of D-138). The city listings exist only with the furnished package; a world without it has the Row's three categories and the roadside motel and farmhouse. The model can say whether the ladder is sensible; it cannot make the missing buildings exist. Adding one is a district JSON (`scripts/generate_district.py`) and a line in `ladder.json`. The model's invariants (monotonic ladder, purchase at least 20 and at most 100 rents, deposit two rents, no single sale buys a home outright) are checked on the whole table, built or not.

| Rule | Value |
| --- | --- |
| Billing cycle | **60 active minutes** |
| Prepay | up to 6 cycles ahead |
| Deposit | 2 cycles of rent, kept in escrow, returned on a clean move-out |
| Grace / final notice | 2 unpaid cycles of grace, 1 of final notice; the 4th unpaid cycle lapses the home |
| Sell-back | 60% of the price actually paid, less anything owed; never more than was paid |
| City price factor | Ironhaven 1.15, Blackwater 1.00, Valewood 0.90, applied at signing by the city circle that contains the home (a home outside every circle, like the Row, costs the listed price). It moves the price only, never what a home allows |
| Welcome | 2 free motel cycles, once per person, recorded in the ledger |
| Upgrades (owners only) | Locking racks 260 (3 more containers, 1 more private), Workshop fit-out 600 (heavy workstations), Running water 320 (water may be placed; lava never) |

## Homes in the furnished cities (D-127)

**Implemented; GameTested (`CityHomesGameTests`, 3 cases); nobody has rented one in a real client.**

The furnished cities' buildings carry units (a tenement's flats, a house, a workshop's or warehouse's premises), and `ladder.json` has **city templates** that tie a kind of building to a category and a share that is to let:

| Building | Category | To let | Rent / h | Purchase |
| --- | --- | --- | --- | --- |
| Ironhaven tenement flats | Small apartment | 34% | 40 | 2,000 |
| Blackwater apartment block flats | Comfortable apartment | 25% | 55 | 3,000 |
| Valewood cottage | Small house | 40% | 85 | 5,400 |
| Valewood houses (two plans) | Small house | 30% | 85 | 5,400 |
| Ironhaven workshop premises | Commercial workshop | 20% | 135 | 10,500 |
| Ironhaven warehouse premises | Warehouse or production facility | 15% | 170 | 14,000 |

Which units are to let is a pure function of the world seed, the building and the unit, so both players see the same street. A unit becomes an ordinary registered home (`city:<type>:<chunk>:<unit>`, titled with an address such as "Flat 3, 12 Rivet Lane") the first time it matters: when a player near it opens the Home page, or touches its building's door. From then on it is rented, bought, billed in active household time, guarded and moved out of exactly like the Row's homes; the city price factor applies as for any home in a city circle. A tenant of a flat holds a key to the building's street door; a house's own door is its door. Nobody of the household lives in a unit that is to let, and no stash is placed in one. Seven of the ten categories now have at least one building (motel room, small and comfortable apartment, small house, house with workshop, commercial workshop, warehouse); larger apartment, farmhouse and premium residence still have none. The economy model checks the templates like the Row's listings (premises are modelled as Crew-paid, at twice the mean household income).

## Time: rent runs on active household time (D-012, D-099)

* A home has one clock. It advances while **at least one resident is online** and **never while all are offline**, and by exactly the same amount whether one resident is online or four (a unit test with two residents shows the clock moved once). A resident is the owner or tenant, or **any member of the owning Crew** (`CrewLedger.residentsOf`), so a Crew home's clock runs while any member is online.
* A cycle that falls due is paid, in this order, from **prepaid hours**, then (if switched on) the **Crew bank**, then it becomes **arrears**. Automatic payment from a *wallet* is not a ledger action: it goes through the wallet-first money protocol (`CrewService.transact`), so a crash between the wallet and the ledger recovers to "paid" or "not paid", never both. Only a wallet that is short makes the hour unpaid; if the wallet has the credits but the money layer says not now (a recovery or another transaction of the same player is in flight) the hour stays due and is tried again at the next five-second step, so a transient refusal can never lapse a home.
* **No offline taxation or punishment**: rent, upkeep and every notice run only on active time. A player who is away loses nothing and owes nothing for the time they were away; an owner who is away while a roommate plays is billed for the time the roommate plays (the household is active).
* Reminders arrive as notices at login and at each step: due soon, overdue, final notice, lapsed.

## Standing, and what a lapsed home still gives you

| Standing | Unpaid cycles | What changes |
| --- | --- | --- |
| Current | 0 | nothing |
| Overdue | 1 to 2 | a reminder; everything works |
| Final notice | 3 | a loud warning; everything works |
| Lapsed | 4 and beyond | the home loses its **comfort**: you cannot build or break blocks or use workstations (the Home page and every payment option keep working, because that is how a lapsed home is put right). **You keep the door and every container, and nothing is emptied, re-let or deleted.** A furnace, smoker, blast furnace or brewing stand can still be opened to take things out of it (they are workstations that also hold things, and a move-out is refused while anything is stored: found by an independent review, tested). You may pay, or move out, at any time |

A move-out or a sale is **refused while the home still holds stored things** (containers, frames, stands with items): belongings are never thrown away with a home. The count and the reason are shown.

## Who pays, and what a Crew changes

* A **personal** home is billed to its tenant or owner from their wallet. A **Crew** home is billed to the home itself (the bill belongs to the property, not to a person). It is paid from the Crew bank (which needs both the "Spend Crew money" and the "Rent and buy homes" permission, and a Crew whose finances are not `SEPARATE`) or, by hand, from the wallet of any member who has "Rent and buy homes".
* Members with that permission (**Founder and Manager** by default) can rent, buy, pay, switch autopay, move out and sell; a plain **Member** can live there, use the storage rights they have, and nothing else.
* **Leaving a Crew** does not touch the member's own homes. **Disbanding** hands the Crew's homes to the founder with their debts, deposits and prepaid hours intact and switches automatic payment off, because the bank that paid it is gone. **Hand to Crew** / **Hand to founder** move a home between the two with a receipt; **nothing owed travels** (a debt cannot be passed on) and the Crew must have room.
* Credits stay Q Shop credits throughout. The Crew bank is the Core-owned escrow ledger of [crew-economy.md](crew-economy.md); a rent, a purchase, a payment or an upgrade paid from it is one atomic ledger action, and from a wallet one journaled wallet-first action (the same protocol as a withdrawal).

## Storage and upgrades are capabilities the guard enforces

`ResidenceGuard` is the single enforcement point (D-090), and it now reads a home's **terms**, not a constant:

* **Containers**: placing a container into a home that is at its cap is cancelled with the reason; the cap is the listing's plus the racks upgrade. The old fixed cap of four is gone. A **furnace** or a **hopper** is not a container; **frames and stands holding items** count as stored things (they must be emptied before a move-out) but not toward the cap.
* **Private claims**: a resident may keep a number of containers to themselves (the listing's plus the racks upgrade).
* **Heavy workstations** (anvil, smithing table, grindstone, brewing stand, gun tables) need the **workshop** facility; **water** needs **plumbing**; **lava never**.
* A **lapsed** home's rights are cut to access and storage only (no building, no workstations).

## Existing saves

`CrewLedger` schema is now **2** and reads 1 and 2; a newer file is refused loudly, not guessed at. A schema-1 home that was owned is migrated to **owned, legacy, no upkeep, no rent, no new charge**, with what it cost recorded so that sell-back is fair; an unowned one is untouched. **Nothing is billed retroactively.** A test builds a schema-1 file by hand, loads it and checks that no balance, bank, tenancy or charge changed.

## Tests (Forge GameTests in a scratch ledger, in-memory wallet, adjustable clock, a simulated crash)

The shipped ladder is coherent; rent and purchase are different things; the motel welcome is free once per person; the city factor scales the price; **the clock runs only while a resident is online and once for two residents**; unpaid rent escalates to lapsed and **never takes a person's things**; a payment goes to what is owed first and is capped at the prepay limit; Crew-bank autopay is atomic and stops when the bank is short; wallet autopay uses the money protocol, including a crash; move-out returns the deposit less what is owed and **refuses while items remain**; sell-back never returns more than was paid; Crew homes follow the Crew's rules and nothing is confiscated; disbanding moves a tenancy with its debts and deposit; a schema-1 save migrates without a new charge; a tenancy and its clock **survive a restart**; a wallet rent **crash recovers to rented-or-free at three stages and both wallet outcomes, never both**; a trade skill cheapens upgrades and the receipt records what was paid; containers, facilities and water are enforced by the guard; and a **randomised run of 240 steps on four homes and three people with crashes injected, three seeds, checks after every step that the credits in wallets, the Crew bank, escrow and the sink are exactly what they were**.

## Limits, said plainly

* Two of the ten categories exist only as prices: the larger apartment and the premium residence. Six of the eight built ones need the furnished package (D-117) or a new archipelago world (the roadside places).
* Under the household policy the Vintner alone does not rent the house inside 400 hours (its rent would be over a quarter of its income). That is a weak Vintner mid-game, not a property rule.
* The Home page was photographed at three resolutions by the scripted client and read by me; no person has used it. Its two-click confirmation for Move out and Sell back is untested on a real mouse.
* `ResidenceGuard` does not see fire spread from outside, lightning, fluid dispensers or vehicles of other mods (recorded in D-090 and unchanged).
* A home whose world is not loaded cannot report stored things, so it cannot be moved out of until it is (a clear message, not a silent success).
* Real-estate **price movement**, a property **tax** and a **burglary** response to a lapsed home are not built (see the roadmap).
