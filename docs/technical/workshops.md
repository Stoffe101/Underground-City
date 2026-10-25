# Workshops of the trades: stations, tiers, grades and who supplies whom (D-163, D-164)

**Status (October 24, 2026, fifth pass): in Core and GameTested; one scripted real client went through a Vintner's first batch, its second tier and a sale to the pub (workshop runs, see Evidence). Nobody has played it. Times, prices and grades are first numbers checked by the economy model, not balanced against play.**

Before this pass the Farmer, Vintner, Smith and Gunsmith earned mostly by carrying vanilla or third-party goods to a work-order board. The Vintner had nothing to do at all without the optional Vinery set. Each of these trades now has **workshop stations** in Core. A station makes goods from real inputs over minutes of active play, works better once it has real parts fitted, and makes goods that other trades and the city's shops want.

## The stations

Each station is one block in the world and one data file, `data/underground_city/workshops/<station>.json`, read by `Workshops`. `WorkshopBlock` is the block and holds the batch on the server. `WorkshopScreen` is the screen: recipes on the left, the selected recipe on the right, the batch's progress, and Start, ×batches, Take and Fit tier.

| Station | Trade | Tier 1 → 2 → 3 (batches at once, speed) | What it makes |
| --- | --- | --- | --- |
| **Grading Table** | Farmer | Plain Table (1, 100%) → Sorting Rails (2, 135%) → Scales and Grader (4, 175%) | Produce Crate (6 carrots, 6 potatoes), Fruit Crate (6 apples, 6 berries), and at tier 2 a root crate with beetroot. Graded. |
| **Mill** | Farmer | Hand Quern → Iron-Banded Stones → Geared Crank (fitted from a Smith's gearbox since the eighth pass) | Flour (3 wheat → 2), beet sugar (4 beetroot → 3 sugar, for a Vintner), and at tier 3 bulk flour (12 → 9). |
| **Fermentation Vat** | Vintner | Open Vat → Iron Hoops and Tap → Cooling Jacket | Berry Wine (8 berries, 2 sugar, 3 bottles → 3, six minutes), Apple Cider, and at tier 2 with Cellar Craft glow-berry wine (never below Fine). Graded. |
| **Oak Cask** | Vintner | Single Cask → Rack and Hoops → Cellar Rack | Cask-Aged Wine (3 berry wine → 2, fifteen minutes; never below Fine, and keeps the grade of the wine that went in). With Vinery installed and Estate Vintages, Vinery's red wine rested in oak and graded. |
| **Pot Still** | Vintner | Pot and Worm → Riveted Pot → Column and Condenser | Grain Spirit (licensed: needs the Distiller's Licence) and **Moonshine** (underground: needs Home Distilling). Both are restricted spirits at a checkpoint without the licence. Graded. |
| **Machinist's Bench** | Smith | Vice and Files → Drill Press → Lathe | Fasteners, Steel Brackets, Barrel Hoops, Coil Springs, a Gear Set (Structural Works), Machined Parts (tier 2), Precision Machinery Parts (tier 3, Precision Machining), and **Unstamped Plate** (underground, tier 2, Unlisted Fabrication). |
| **Gunsmith's Bench** | Gunsmith | Felt Mat and Tools → Vice and Jigs → Test Fixture | Firearm Parts Kit (springs, a bracket, fasteners, a machined part), graded; **Unmarked Parts Kit** (underground, tier 2, Unregistered Builds: a kit rebuilt around unstamped plate). |

* **A batch** takes its inputs from the player's inventory when it starts. It runs for its recipe's time on the **active clock** (`LockService.clock`), which stops while nobody plays. When it is done, Take hands the goods over.
  * The station shows on the block that it is at work.
  * A batch that is ready waits in the station until somebody takes it.
  * Breaking the station gives back the station, at its tier, and whatever was in it: a finished batch's goods, or a running batch's inputs.
  * Career XP for a batch is paid when its goods are taken, and only when the station is of the player's own trade.
  * **Whose a batch is (D-180, D-184).** A batch is its maker's. Only the maker, or a member of the maker's Crew, takes it, starts over it or breaks the station while it is in it; the career XP is always the maker's.
    * **Inside a home or trade premises somebody holds**, a batch never lapses to anybody else. The property's guard decides who reaches the station at all, and the batch stays its maker's however long it waits.
    * **Outside any property** (a station on a street or in the wild), the batch is held for its run and then for two hours of the **maker's own play time** (`PlayClock`, which runs only while that player plays, so nothing lapses while they are away). After that it is salvage: anybody may take it. The maker is told who took it and where, at once or when they next log in, and keeps the career XP.
    * A station saved before D-184 keeps the older rule (two hours on the world's active clock) outside a property, and never lapses inside one. A station saved before D-180 names nobody and hands over to anybody, as it always did.
* **Tiers** are fitted from real parts the player carries, never from credits. Most parts are a Smith's: fasteners, brackets, hoops, springs and gear sets. The Oak Cask takes a Cellar Cooperage Set, the Pot Still a Still Column Kit, and the Gunsmith's test fixture an Optical Component, a Technician's goods. Every top tier needs a skill.
  * The block shows its tier: iron bands and brackets at tier 2, brass fittings, a gauge or a lamp at tier 3.
  * A better tier runs more batches at once, works faster and opens more recipes.
  * A station carried away keeps its tier: the dropped item remembers it, and its tooltip says so.
* **Grades:** Standard, Fine or Reserve, carried as an item tag (`uc_quality`; Standard carries none, so it stacks with ordinary goods).
  * A graded recipe adds its base, a step for tier 2 or 3, and a step for each listed skill the player holds, up to Reserve.
  * Ageing adds the grade of the wine that went in. The lowest grade among the stacks taken counts, so mixing in one good bottle does not lift a batch.
  * The tooltip says "Fine grade: buyers pay 25% more" or "Reserve grade: buyers pay 60% more".
* **Starter kits:** a new Farmer gets a Mill, a Vintner a Fermentation Vat with berries, sugar and bottles, a Smith a Machinist's Bench with iron, and a Gunsmith a Gunsmith's Bench. Every station is also crafted from vanilla materials (recipes in `data/underground_city/recipes/<station>.json`). An existing character keeps their kit and crafts a station.
* **Homes:** every station is a residence workstation (`tags/blocks/residence_workstations.json`), so the home guard treats it as furniture of a workshop.

## Who supplies whom

```
Farmer --flour----------------------> bakery, Valewood (shop and contract)
Farmer --produce and fruit crates---> corner stores (shop and contract)
Farmer --wheat, beet sugar----------> Vintner's vat and still
Vintner --wine, cider, aged wine----> pub and club bar (shop and contracts)
Vintner --grain spirit (licensed)---> Blackwater taproom and bar
Vintner --moonshine (underground)---> Ironhaven back bar (contract)
Smith --fasteners, brackets, hoops,
        springs, gear sets----------> every station's tiers; builders' yard (shop and contract); Northgate depot (contract)
Smith --springs, brackets, parts----> Gunsmith's parts kits
Smith --unstamped plate (underground)-> Gunsmith's unmarked kits; Hollis
Gunsmith --parts kits (licensed)----> city armoury (contract)
Gunsmith --unmarked kits (underground)-> Dray (contract)
Technician --optical components-----> Gunsmith's test fixture
```

**Nobody needs a partner.** The builders' yard sells a few fasteners, brackets, hoops, springs and gear sets at a mark-up (D-164), so a solo Vintner or Gunsmith can fit tiers without a Smith. A Smith partner is simply cheaper and quicker.

## Selling the goods

* **Contracts** (`content/gameplay/contracts.json`, generated into work orders) take workshop goods:

  | Contract | Trade, level, skill | Goods | Credits |
  | --- | --- | --- | --- |
  | Flour for the Valewood bakery | Farmer 2, Field Fundamentals | 16 flour | 80 |
  | Produce crates for the corner stores | Farmer 3, Greenhouse Management | 3 produce crates, 2 fruit crates | 200 |
  | Berry wine for the pub | Vintner 2, Vine and Cellar Fundamentals | 6 berry wine | 120 |
  | Cider on tap | Vintner 3, Cellar Craft | 8 apple cider | 135 |
  | Aged wine for an estate dinner | Vintner 6, Estate Vintages | 4 aged wine | 230 |
  | Licensed grain spirit for the taproom | Vintner 10, Distiller's Licence, in Blackwater | 6 grain spirit | 150 |
  | Moonshine for the back bar (underground) | Vintner 7, Home Distilling, Valewood to Ironhaven | 6 moonshine | 200 |
  | Fasteners and brackets for the builders' yard | Smith 2, Forge Fundamentals | 32 fasteners, 6 brackets | 140 |
  | Hoops for the Valewood cooperage | Smith 3, Structural Works | 12 barrel hoops | 80 |
  | Gear sets for the Northgate depot | Smith 6, Precision Machining | 3 gear sets, 8 springs | 160 |
  | Parts kits for the city armoury | Gunsmith 3, Licensed Repairs | 2 firearm parts kits | 210 |
  | Unmarked kits for Dray (underground) | Gunsmith 8, Unregistered Builds, Valewood to Blackwater | 2 unmarked kits | 400 |

* **Each contract's margin** sits inside the legal band (1.3 to 2.1 times the goods' reference cost, `reference_values.workshop_units`) or the underground band (1.9 to 2.2). The two underground contracts pass `scripts/validate_underground_balance.py`: careless smuggling loses to legal work, a trained smuggler earns modestly more, and nothing doubles legal work.
* **A contract pays more for graded goods.** A delivery now accepts a stack whose only tag is its grade. A named, enchanted, damaged or stolen stack is still never taken. It pays the order's credits, plus its skill bonus, times the average price weight of the units taken (1.0, 1.25 or 1.6). The delivery message says how much was for the grade.
* **Shops buy them (D-164):** see [shop-supply.md](shop-supply.md). The pub, the club bar, the bakery, the corner stores and the builders' yard buy the matching workshop goods over the counter while their shelf has room. The city's customers empty those shelves again.

## Evidence

* **GameTests (`WorkshopGameTests`, 8):**
  * Every station loads with three tiers, real parts and real items, for four trades.
  * A batch takes exactly its inputs, refuses a second batch while busy, hands over three bottles once its time has passed, and grades as its tier and skills say (ageing keeps and lifts the grade). It pays the recipe's career XP even when the station's own clock finished the batch before the player took it (a bug the real-client run found: the XP was lost).
  * A tier is refused without parts, fitted from them (the parts used, the block showing tier 2), refused at tier 3 without the skill, and kept by the dropped station.
  * A skill gates its recipe without taking the inputs, and the still's recipes are marked legal and underground.
  * A contract pays for the grade in proportion and takes no named or stolen goods.
  * A shop buys a supplier's goods once per sale onto its shelf, never above the shelf, and its customers draw the shelf down to empty and no further. No line buys back for more than it sells.
  * An empty back-room barrel of a business fills with its stock once, and a full one is left alone.
  * A quietened alarm line (D-166) does not call, and back-channel work is hidden from a Technician without Bypass Tooling.
* **The audits:**
  * `scripts/audit_item_sources.py` counts a workshop recipe as a source at the level of its skill, its inputs and its tier's parts. It finds no dead end among the 69 work orders.
  * The economy model (`scripts/economy_model.py`) knows each workshop good's hands-on minutes and waiting time, and all its invariants hold (`docs/technical/economy-model.md`).
* **Real client (`-PucPlaytest=workshop`, a new furnished archipelago world).** Workshop run 3 passed all 8 steps, `RESULT pass`:
  1. A new citizen registered as a Vintner through the registration screen.
  2. The starter kit held a Fermentation Vat, berries, sugar and bottles.
  3. The vat was placed with a real right click on open ground near the Ironhaven pub that no building owns.
  4. Its screen listed berry wine as ready and glow-berry wine as needing a better tier and Cellar Craft.
  5. Start (a real click) took the inputs and set six minutes of play running.
  6. After six minutes of play, Take (a real click) handed over three bottles.
  7. Fit tier 2 (a real click) used the hoops and fasteners; the vat then ran two batches at once and showed its iron hoops.
  8. At the pub's front door the player opened it with a right click, walked across the public floor to the barkeeper, spoke to her, chose "What do you sell?" and sold the three bottles with a real click on Sell: +42 credits, the wine shelf 4 → 7. On the way across the floor the shop said "Staff only. Back out front, please." once: the walk brushed the zone behind the bar.
  * **6 scripted shortcuts, each logged:**
    * the clock set to 13:00;
    * the travel to Ironhaven (a teleport);
    * the player put on the street outside the pub;
    * the walk to the open ground for the vat, which stopped 2.5 blocks short;
    * the four hoops and four fasteners for tier 2 (normally a Smith's, or a few from the builders' yard);
    * **the walk back to the pub's front door, which wandered round the block and was finished by a teleport for the last 22 blocks.**
  * Runs 1 and 2 stopped earlier: the vat was placed on a building's protected plot, and the walk to the counter looped behind the bar. Both were scenario or placement fixes.
  * The run paid no Vintner XP for the batch the clock had finished; that bug is fixed and GameTested, but no real client has rerun it.

## Limits

* Nobody has played it. The times, prices, tiers and grades are first numbers.
* A station's batch runs only while somebody is online, like every Core clock.
* TaCZ guns are not made at the Gunsmith's Bench. Its licensed kits feed the armoury contract, and the existing gun contracts and blueprints are unchanged.
* Vinery's own barrels, presses and wines are untouched. The cask only rests and grades Vinery's red when Vinery is installed.
* No NPC staff run a station. A station never works while its owner is away beyond the active clock.
