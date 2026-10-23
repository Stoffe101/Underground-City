# Sprint report, October 22 to 23, 2026: gameplay completion, living world and integration pass

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Autonomous Gameplay Completion, Living World & Integration Pass". Decisions D-125 to D-133 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, a Python model, probes of the game's own generator and a scripted real client (software-rendered, in a virtual display, driven by synthetic key and mouse events). "Passed" means a script passed. Where a step was set up by a command or by reading the world instead of by input, it says so.

<!-- journey:begin -->
## The first-player journey (D-132): interim state

**This section is replaced by the final evidence run's results.** `-PucPlaytest=journey` plays the brief's first hour in a brand-new furnished archipelago world through a real client with real key and mouse input (synthetic X11 events, software rendering, 1280x720). Every step a command or a direct server call set up is logged `SCRIPTED`. Runs 15 to 29 (October 22 to 23) found and fixed, in order: the conversation check that counted the person spoken to as their own obstacle (no speaking spot was ever found), a path planner that treated a rug as a wall, a crafting-table placement clicked before the software-rendered crosshair had caught up, the home list that put the free motel rooms on its third page, the journey reading the road's unbuilt plan, and a cross-country approach to the road that got stuck in Ironhaven's sealed blocks (now finished by a logged teleport).

Passed so far with real input in at least one run: 1 to 9a in full run 23 (arrival, registration, the arrow, the employer and her terminal, the refused order, the accepted order, the other trade's employer, a tree, the quarry, the crafting screens, delivery and payment, the counter and its refusal); 10 to 13 in development run 25 started at the delivery (the motel room on the Home page, its door, its barrel, a skill, a save-quit-reopen). The bridge, Valewood and the return are being run now.
<!-- journey:end -->

## What was added, by the brief's priorities

### P1: living neighbourhoods

* **Shopkeepers sell (D-126).** A furnished shop's worker opens the city's stock lines for that kind of shop on the Trade page: the same finite, shared, active-time stock and the same journalled purchase as the exchange. Seven counters now: Valewood's general store and bakery, Ironhaven's hardware desk, builders' yard and pub, Blackwater's bar and corner store.
* **Three more buildings (D-133):** a pub (Ironhaven), a corner store (Blackwater) and a bakery (Valewood), each with a counter, its own four stock lines and rooms or a flat to let. Thirteen furnished building types in all ([furnished-cities.md](../technical/furnished-cities.md)).
* **Homes in the cities (D-127):** the tenements' flats, the houses and cottages, the bakery's and the store's flats, the pub's rooms and the workshop's and warehouse's premises can be rented or bought on the same terms and rules as the Row's homes ([property-economy.md](../technical/property-economy.md#homes-in-the-furnished-cities)). Seven of the ten ladder categories now have a building.
* **People who come and go (D-129):** at the hour that moves them out or home, a resident or a shopkeeper walks out of the street door and down the street, or up the street and in ([households.md](../technical/households.md#coming-and-going)).
* **Four visual flaws fixed in the generator:** the almost-black club (froglights flush in the ceiling and lit rods as neon), the bare tenement bedrooms (a rug, a mirror, a clock, a plant), the Valewood kitchen's way in (the table moved out of the doorway) and the cottage garden's "trenches" (a planted bed behind a border).

### P2: burglary, security and police

* **A fence for stolen goods (D-125):** two fences, at night, paying 45 or 50 percent of an item's cheapest honest price (less for goods stolen in their own city, which also raises heat), with a refilling budget and a crash-safe sale ([police-response.md](../technical/police-response.md#fences)).
* **Alarm panels and a bypass (D-128):** a panel inside the street door of ten building types shows the alarm's state; an armed building gives a 25-second entry delay, and a Technician with Bypass Tooling and a Bypass Kit can jam it on the trace screen for ten active minutes; a wrong or late answer rings it and raises heat ([police-response.md](../technical/police-response.md#alarm-panels)). SecurityCraft stays not adopted (D-121).
* **The alarm panel through a real client** (`-PucPlaytest=alarm`, run 2 passed): in a new furnished archipelago world, two real generated Ironhaven tenements with armed alarms. In each, the door's lock was picked on the real lock screen with the real pointer (a strained pick, as a clean one slips past an alarm that can be slipped past), the panel started its entry delay and **nobody was called**; the panel was right-clicked with the real mouse and the signal watched on the real trace screen. In the first, the five symbols were keyed back with real key presses: the light went blue, the building called nobody even after the 25 seconds had run out. In the second, a wrong code was keyed on purpose: the alarm rang, one police dispatch was raised for that building and heat rose. Scripted, and logged as such: the registration and the skill (a command and a server-side unlock), the kits, the choice of building and the step from the doorway to the panel (a teleport). Screenshots reviewed: the panel's red, amber and blue lights read clearly on the brick wall.
* **Police tests:** officers turn after a thief who moves; a partner beside an officer with marked goods is not arrested for the thief's break-in; separate responses for two thieves and for two buildings; a witness call is slower than an alarm.

### P2: the hybrid resource economy

* **The economic model prices a scarcity world (D-130)** with shelves and the quarry, and the fences. It found a real problem (a new Technician could only lose money without ore) and a level-1 order that needs no ore fixes it ([economy-model.md](../technical/economy-model.md)).
* **The quarry was mined by a real client** (scripted: real left button, the welcome pickaxe) in the first-player journey. Its regrowth was not watched in a client.
* **A second regrowing site, the Valewood pit (D-134):** ten base-metal veins that grow back in 25 active minutes, placed in a new archipelago world beside the Ironhaven road where it leaves Valewood (the island with the fewest metal shelves). The model's renewable supply rises from 42 to 66 ores an active hour for everybody; every invariant holds ([hybrid-economy.md](../technical/hybrid-economy.md#the-valewood-pit-d-134)). Nobody has dug it.

### P3: interface

* **The side status display no longer covers the hotbar** on a small window (found in a journey screenshot at 1280x720, GUI scale 3).
* **Corner notices no longer cover the status display or a timed screen (D-135):** the alarm scenario's screenshots showed two notices over the status card and two over the trace's pads. The game's toasts now move below the stack, or beside it where there is no room below; nothing is drawn over the lock or trace screen and notices that arrive meanwhile wait until it closes; the stack is drawn above the chat, whose long lines had crossed the next-step card at GUI scale 3 ([hud.md](../technical/hud.md#corner-notices-d-135)). The side display and the employer arrow are unchanged.
* **New screens captured:** the fence, the alarm panel's trace and a shop counter are in the GUI capture harness at every supported viewport.

### P4: private content

* **A porter for private 1.18.2 Lost Cities packs (D-131)**, proven on an invented fixture; **no private content was available or ported** ([private-content-workflow.md](../technical/private-content-workflow.md#the-porter)).

## Still to be written

The test and CI results, the screenshot review at four viewports, and the lists of what is tested, partial, proposed and blocked are added when the final runs finish.
