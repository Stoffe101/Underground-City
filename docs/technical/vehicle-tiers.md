# Vehicle and boat tiers and the one acquisition path (D-101)

**Status (October 20, 2026): a planning table plus two implemented dealer lines.** The table is `content/gameplay/vehicle-tiers.json`; the dealer lines are generated from it by `scripts/generate_vehicle_supply.py` and sold by the existing regional exchange (finite shared stock, active-play restock). The two lines are GameTested with the **real Modern Life items** (`VehicleDealerGameTests`, run in the `vehicles,boats` configuration: 206 of 206). **Nothing here was driven, ridden, refuelled or repaired by a person, and fuel, repair, insurance and parking are not implemented and not claimed.**

## What the audit found (`VehicleAuditGameTests`, printed from the running game)

| Mod | What it registers | How a vehicle is acquired in the mod itself |
| --- | --- | --- |
| Modern Life 1.20.1 | `bicycle_item`, `motorboat_item` (the only vehicles), 436 furniture blocks | **vanilla crafting recipes**: bicycle = 1 iron ingot, 3 iron nuggets, 2 tires (4 black wool and a nugget each); motorboat = 3 iron blocks, 1 iron ingot, 1 redstone block, 1 glass pane (28 iron ingots' worth) |
| Immersive Vehicles | 634 items in one builder class: 244 whole vehicles, 91 generic parts, 37 seats, 34 instruments, 30 crates and barrels, 30 pole components, 29 decor, 28 wheels, 16 engines and more | the vanilla recipes make the **benches** (the Vehicle Bench is planks, a lectern and a writable book) and the tools (wrench, key, jerrycan, fuel pump and hose); a **vehicle is built at the bench from parts defined inside the content pack**, not in the recipe manager |
| Official Content Pack / Automobile Pack | 17 + 8 vehicle families (ground and air) under the `mts:` namespace (`oamp.` for the automobile pack; the namespace `oamp` itself holds no items) | the same bench |

Ground vehicle families found, by the pack's own names (colour variants counted): quad (8), fordmustang69 (13), merc230 (17), gmcbrig (11), e500 (7), scout (14), firetruck (1), and in the Automobile Pack cagouille (19), escargot (16), camaro (14), vwbus (1), highwayman (9), luxorama (18), stationmerc (14), ecoline (1). **Also in the pack, listed as not offered:** nine aircraft families (including an attack helicopter, a bomber and a fighter), a tank, and 24 gun-type parts and 24 kinds of bullet, bomb, rocket, flare and paint. Whether the Vehicle Bench may build the weapons is an **open owner decision**; this pass does not block them.

## The tiers

Price = the larger of **1.8 times the raw materials** (iron at 7.7 credits an ingot, from the gun-cost reference) and the **active hours of a level-appropriate wage** the tier is meant to take, taken from the economy model's mean of the five careers' net credits per hour at that level.

| Tier | Vehicle | Acquired by | Price | Materials | Level | Meant to be | The model's hours |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Bicycle | **dealer, every city** | **60** | 30 | 2 | 0.25 h | 0.2 |
| 2 | Motorboat | **dealer, Valewood** | **600** | 240 | 5 | 1.5 h | 1.5 |
| 3 | Quad | Vehicle Bench (proposed price) | 1,450 | not priced | 8 | 3.5 h | 3.5 |
| 4 | Small car | Vehicle Bench (proposed) | 3,400 | not priced | 10 | 8 h | 8.1 |
| 5 | Large car or van | Vehicle Bench (proposed) | 8,800 | not priced | 12 | 20 h | 20.0 |
| 6 | Truck or bus | Vehicle Bench (proposed) | 13,000 | not priced | 12 | 30 h | 29.5 |

Reading: after a backpack (70) the first purchase is a bicycle (a quarter of an hour of the mid-game wage); a motorboat is an hour and a half of a skilled wage, scarce (one in stock, a second after four active hours for the whole world) and craftable by anyone who will mine 28 iron; a car costs about a bought flat; the large vehicles are longer goals than the workshop house (7,000). `economy_model.py` fails if a tier is cheaper than the one below it, cheaper than 1.8 times its materials (buying must never beat making), or takes less than 0.7 or more than 1.4 times its planned hours; `tests/test_vehicle_tiers.py` pins the table, the generated lines and the words.

## The acquisition path that exists

The Valewood terminal sells one **Motorboat** for 600 and every city's terminal a **Bicycle** for 60, from a shared shelf (`outlets: city`). A test buys both with real credits, checks the real items arrive, the wallet moves by exactly the tier prices, the shelf goes down by one, a **second boat is refused from a shelf that held one**, and a refused sale costs nothing. A pack without Modern Life leaves the lines out at load time (also tested). Boats matter because the Valewood to Blackwater crossing has a short water leg (D-048); the 27-slot hold is searched at a post (D-077).

## What is not built

Fuel and how a survival player gets it; repair and maintenance costs; parking in a home garage (the property ladder has the category but no garage building); a dealer for the Immersive Vehicles cars; a used market; theft and recovery; the price of the pack-defined parts. The tiers above the motorboat are proposals the owner should read as such.
