# Three open decisions: a tax, per-player loot, vehicles (recommendations, October 25)

**Status: proposals for the owner. Nothing here is decided or built.** The roadmap's P4 (money and tax) and P6 (city life and loot) phases each wait on one of these, and the alpha's scope leaves vehicles as a priced plan. Each section says what exists, the options, what each costs and risks, and a recommendation. The figures come from the economic model (`scripts/economy_model.py`, [economy-model.md](../technical/economy-model.md)) and the code as it stands on the seventh pass; they are estimates from stated assumptions about how long a task takes, not measurements of people playing.

## 1. A tax

**What exists.** No tax of any kind. Money leaves the economy through materials, rent and upkeep (on the household's active clock), deposits held, supplier fills of Crew parts, fences' discount on stolen goods, and the exchanges' finite stock. City treasuries do not exist; the civic project (the Valewood market, D-171) is funded by deliveries, not by money. [economy.md](economy.md) proposes a low, capped levy on active playtime or a share of paid business income, never offline, with a grace period and no eviction.

**Options.**

| Option | What the player sees | Cost to build | Risk |
| --- | --- | --- | --- |
| A. No tax for the alpha | nothing | none | the P4 criterion stays open; a mid-game money surplus has no sink beyond property and vehicles |
| B. A sales tax on legal deliveries, taken at the ledger's commit point | "Paid 420, city tax 21" on each order | small: one line in the work-order settlement, a rate per city in data | a hidden-feeling cut on every order unless the terminal shows it before acceptance; underground pay becomes relatively better |
| C. A property tax on owned homes, on the household's active clock like upkeep | the Home page's bill gains a "city tax" line | small: the upkeep engine already exists | double-counts upkeep; owning stays cheaper than renting either way |
| D. A tax that funds something visible (a city treasury the civic projects draw on) | a city page with the treasury and what it paid for | large: a treasury ledger, projects that spend it, UI | the most meaningful, and the most to balance |

**Recommendation: B at a low rate (3 to 5%), shown before an order is accepted, with the revenue recorded per city (not yet spent), and D later.** It is the smallest change that makes the tax legible, it is taken only from money actually earned in active play (so it can never accrue offline), and recording the revenue per city lets a later civic system spend it without a migration. Rates and the cities they apply to belong in data, not code. The economic model should get a tax parameter first so the invariants (rent at most 30% of the income it is paid from, hours to a first flat) are checked with it.

## 2. Per-player loot

**What exists.** Searchable furniture in the furnished buildings is **shared**: a spot is searched once and refills after four hours of the world's active play (`BurglarySearch`); what is found is marked stolen with the thief's id. Exploration finds in the roadside salvage yard and the quarry are shared and regrow on the active clock. Lootr was reviewed and not adopted.

**Options.**

| Option | Two players in one world | Cost | Risk |
| --- | --- | --- | --- |
| A. Shared (as now) | whoever searches first takes it; the other finds "somebody has been through this already" | none | in co-op, one player can empty a street before the other gets there; it is realistic and pushes the pair to split targets |
| B. Instanced per player (each player searches each spot once per refill) | both find something in every spot | small: the searched record keys on spot and player; the save grows with players | two players earn twice the loot from one building, which doubles burglary's yield per hour against the legal careers the model balances it with |
| C. Per Crew (a Crew shares one search per spot; solo players their own) | a pair in one Crew shares; two strangers do not | small, as B, keyed on the Crew or the player | strangers on one server each get their own loot (fine for private play) |
| D. Instanced only for exploration finds (roadside, abandoned buildings), shared for burglary | both explore fully; burglary stays a race | moderate: two kinds of spot | needs a clear rule players can read |

**Recommendation: C, per Crew.** It keeps a cooperative pair from competing for the same drawer (the brief's two-player-first rule), it does not double a pair's yield (they share one search, as they share a home), and a solo player loses nothing. The economic model already balances burglary per person; under C a pair splits one building's loot, which is what it assumes today. If the owner prefers that each player always finds something, D is the next best: exploration is where a "somebody was here first" feels worst.

## 3. Vehicles

**What exists.** A priced table of six tiers ([vehicle-tiers.md](../technical/vehicle-tiers.md)); two are on sale: a bicycle (60 credits, every city) and a motorboat (600, Valewood). Immersive Vehicles' cars exist in the development pack but are acquired only through its own Vehicle Bench, which is not priced or gated. No fuel, repair, insurance, theft or parking exists. Routine travel between cities is a few minutes on foot by design.

**Options.**

| Option | Cost | Risk |
| --- | --- | --- |
| A. Keep vehicles a priced plan for the alpha (bicycle and boat only) | none | the GTA side of the vision (cars, theft, chases) stays absent |
| B. Sell one small car per city at the table's price through the exchange, no fuel or repair | small: a dealer line per car | a car makes the roads' optional roadside places easy to skip; the Vehicle Bench still bypasses the price unless its recipes are gated |
| C. B plus gate the Vehicle Bench's recipes behind a Smith or Technician skill | moderate: recipe data for Immersive Vehicles' bench | needs a real-client test of the bench with the gate; a mod update can change the recipes |
| D. A full vehicle economy (fuel from a Farmer's or a refinery chain, repair by a Smith, insurance, theft) | large | the most fun and the most balance work; out of the alpha's scope |

**Recommendation: A for the alpha, then C.** The alpha's journeys (careers, homes, co-op, crime) do not need a car, and a car sold before the Vehicle Bench is gated is a price anyone can step around. When cars come, they should come with the bench gated behind a career so that a vehicle is something one of the five trades makes, which is how the vision ties production to everything else; fuel and repair (D) are the natural later work for the Farmer and the Smith.

## What the owner is asked

1. A tax: none for the alpha, or B (rate, and which cities)?
2. Loot: shared (as now), per Crew (C), per player (B), or instanced only for exploration (D)?
3. Vehicles: bicycle and boat only for the alpha (A), or a dealer car now (B)?

Each answer becomes a `D-###` row in [decisions.md](../production/decisions.md) before anything is built.
