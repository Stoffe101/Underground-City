# Sprint report, October 22 to 23, 2026: gameplay completion, living world and integration pass

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Autonomous Gameplay Completion, Living World & Integration Pass". Decisions D-125 to D-133 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, a Python model, probes of the game's own generator and a scripted real client (software-rendered, in a virtual display, driven by synthetic key and mouse events). "Passed" means a script passed. Where a step was set up by a command or by reading the world instead of by input, it says so.

<!-- journey:begin -->
## The first-player journey (D-132)

`-PucPlaytest=journey` plays the brief's first hour in a brand-new furnished archipelago world (`world,furnished,scarcity`, seed 5908) through a real client: synthetic X11 key and mouse events, software rendering, 1280x720, GUI scale 2. Every step that a command or a direct server call set up is logged `SCRIPTED`. Development runs may start later in the journey (`-PucPtFrom=deliver` or `travel`); they log what the server set up and are never counted below.

**Final evidence run 37 (October 23), one fresh world from arrival to return: `RESULT pass`, every step.** 32 screenshots, all looked at.

| Step | What happened | Input |
| --- | --- | --- |
| 1 | arrived on the landing in front of Founders Row, in Survival | the new world's start |
| 2 | registered (Ironhaven, Smith) on the registration screen | real clicks |
| 3 | followed the employer arrow on foot (9 s) | real W key, aim by the planner |
| 4 | spoke to the employer; her orders opened on her terminal | real right click |
| 5a | an order above the player's level was refused | real click |
| 5 | accepted the introductory order (3 stone pickaxes, 24 credits) | real click |
| 5b | the farmers' employer offered a Smith no orders | real right click |
| 6a | chopped a tree by hand | real left button; **the tree's position was looked up** |
| 6b | mined stone in the old quarry with the welcome pickaxe | real left button |
| 7 | planks, a table, sticks and six stone pickaxes on the crafting screens | real clicks and drags |
| 8 | delivered at the employer's terminal: credits and career XP | real clicks |
| 9, 9a | bought rations at the civilian counter; a purchase the wallet could not cover changed nothing | real clicks |
| 10 | rented a motel room on Find a home (first card, free welcome hours) | real clicks |
| 11a, 11 | walked in through its door; put bread into its barrel | real keys and clicks |
| 12 | unlocked Forge Fundamentals on the Skills page | real clicks |
| 13 | saved, quit to the title screen and reopened: wallet, XP, the accepted order and the home unchanged, no second registration | the client's own save-and-quit and load calls (what the pause menu's button and the world list call), not clicks on those menus |
| 14a | walked the road and its bridges to Valewood: 1,031 of 1,037 blocks at 4.26 blocks a second while moving, 91 s on bridges | real W key; **the cross-country approach from the Row to the road's start walked 496 of 721 blocks, then stuck in a trench and teleported the last 225** |
| 16c | a locked Valewood building stayed shut to a player with no lockpicks | real right click |
| 14b | walked up the new ramp from the road's end to a Valewood street door (19 s) and used a terminal there: a rival-city visit recorded | real keys and clicks, no teleport |
| 15 | delivered a second order in Valewood | real clicks |
| 16a | bought charcoal on the Valewood exchange (finite shared shelf) | real click |
| 16b | a shelf another buyer had emptied sold nothing and took nothing | real click; **the shelf was emptied on the server to stand in for the other buyer** |
| 17 | walked the road and bridges back to Ironhaven: 241 s, about four minutes | real W key |

**What was scripted in run 37:** three things only, each logged: the tree's position, the last 225 blocks of the Row-to-road approach, and the emptied shelf for the failure case. The first crossing took 410 s of game time, of which 168 s was the software-rendered client waiting for chunks; the return took 241 s with no wait, which matches the 3.8 to 4.1 minutes the road check computes.

**Why the approach stuck, and what changed after run 37.** The world was read back from run 37's save: the player had dropped three blocks into a one-block gap between an Ironhaven building's wall and a natural stone bank, closed at both ends by the building's iron bars. The scripted walker's planner, asked for a point it could not reach within its search budget, chose the nearest cell it had explored, and the bottom of a dead end is exactly that; it then offered the thin top of the bars as a step out, which the walker tried fifteen times. Both are fixed in the test planner (it now heads for the nearest cell on its unexplored frontier, and a floor must cover a quarter of the block). A person who fell in could dig out of the bank (it is not part of the protected building), but would not have walked into it.

**After run 37: the walk from the Row to the road, on foot.** Development runs start at the travel step, with the earlier steps set up by the server (logged); they are not evidence for steps 1 to 13. Run 38 stuck beside an iron-bar railing outside an Ironhaven building (the planner started on top of the bars' block instead of where the player's body was; fixed). Run 39 walked all 721 blocks from the Row in 443 s, then failed: it had "arrived" by flat distance in a gully under the road's first stretch, four blocks below the deck, because it aimed at the road at the player's own height. It now aims at the ramp just inside the city at the deck's height and counts arrival only at that height. **Run 40 passed from the travel step with no teleport**: the Row to the ramp on foot (433 s), the road and its bridges, Valewood, its failure cases and the return.

**Two full runs after that failed early, on the walker, not the game.** Run 41 stopped on the walk to the quarry: each 40-block leg of a long walk was asked to reach the quarry stone's height, 20 blocks down, and the planner's new frontier rule followed a valley nearer that height into a pit the walker could not climb out of; a leg on the way now only has to reach its column, at whatever height the ground is. Run 42 stopped beside the employer's desk, pushing for forty seconds against something on the second waypoint that run 37 had got past after two re-plans; creatures near the walker now close their cells for the planner (a person walks round somebody standing in the way) and a walker stuck in one place steps to the side. A final full run from a brand-new world with every fix is below.

**A design finding from those runs:** Founders Row is about 720 blocks from the nearest road end, on the far side of Ironhaven, so a new player's first trip to a rival city begins with several minutes of walking through Ironhaven's blocks (the scripted walker took over seven minutes, with jumps and detours; a person would be quicker, and nobody has timed one). Until now nothing on the screen said where the roads were; the travel step's arrow now points at the road out ("Road to Valewood"), but the distance itself is the landing's place in the world and is the next milestone's question.

Runs 15 to 36 found and fixed, in order: the conversation check that counted the person spoken to as their own obstacle, a planner that treated a rug as a wall, a table placed before the crosshair had caught up, the home list's free motel rooms on its third page (D-136), the journey reading the road's unbuilt plan, quarrying the floor under the player, a Valewood door search defeated by front gardens, a terminal refused on a building's lot, a lost Escape key, and the road's dead ends at the city circles (D-137, below).
<!-- journey:end -->

## What was added, by the brief's priorities

### P1: living neighbourhoods

* **Shopkeepers sell (D-126).** A furnished shop's worker opens the city's stock lines for that kind of shop on the Trade page: the same finite, shared, active-time stock and the same journalled purchase as the exchange. Seven counters now: Valewood's general store and bakery, Ironhaven's hardware desk, builders' yard and pub, Blackwater's bar and corner store.
* **Three more buildings (D-133):** a pub (Ironhaven), a corner store (Blackwater) and a bakery (Valewood), each with a counter, its own four stock lines and rooms or a flat to let. Thirteen furnished building types in all ([furnished-cities.md](../technical/furnished-cities.md)).
* **Homes in the cities (D-127):** the tenements' flats, the houses and cottages, the bakery's and the store's flats, the pub's rooms and the workshop's and warehouse's premises can be rented or bought on the same terms and rules as the Row's homes ([property-economy.md](../technical/property-economy.md#homes-in-the-furnished-cities)). Seven of the ten ladder categories now have a building.
* **People who come and go (D-129):** at the hour that moves them out or home, a resident or a shopkeeper walks out of the street door and down the street, or up the street and in ([households.md](../technical/households.md#coming-and-going)).
* **Retaining walls of furniture fixed in every furnished building:** where a lot stood above the ground around it, Lost Cities drew its retaining wall with whatever the building's palette had put on the style's characters (a Blackwater office's was three courses of sofas). Found by reading back a generated world, not by a photograph ([furnished-cities.md](../technical/furnished-cities.md#how-it-is-checked-d-119)).
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

* **Notices never cover a screen's buttons:** the final journey run's frames showed three notices over the civilian counter's buy buttons right after a delivery, and over the exchange's rows and the terminal's header. While they would cover an open screen's panel they now wait and appear when it closes; development run 40's frames show the exchange clear ([hud.md](../technical/hud.md#corner-notices-d-135)).
* **The travel step points the way:** its arrow and a named line ("Road to Valewood") lead to the nearest road out of the city, where before the card only said a road existed ([hud.md](../technical/hud.md)).
* **The terminal's footer** no longer runs the skill points into the location (it shortens to the city's name), and **registration** names the city and the trade instead of raw ids.

* **The side status display no longer covers the hotbar** on a small window (found in a journey screenshot at 1280x720, GUI scale 3).
* **Corner notices no longer cover the status display or a timed screen (D-135):** the alarm scenario's screenshots showed two notices over the status card and two over the trace's pads. The game's toasts now move below the stack, or beside it where there is no room below; nothing is drawn over the lock or trace screen and notices that arrive meanwhile wait until it closes; the stack is drawn above the chat, whose long lines had crossed the next-step card at GUI scale 3 ([hud.md](../technical/hud.md#corner-notices-d-135)). The side display and the employer arrow are unchanged.
* **New screens captured:** the fence, the alarm panel's trace and a shop counter are in the GUI capture harness at every supported viewport.

### P3: where the roads meet the cities

* **A walkable ramp at each road end (D-137):** the journey's player reached the Valewood end of the Ironhaven road facing a bank of earth, and the road check found four of six ends that a person could not walk off (a ditch, banks, a pit). The first time somebody comes near an end, a ramp up to 64 blocks long is cut and filled from the road's last column to the city's first built ground, never touching anything the city built or a building's lot. Measured in a new world: 1 of 6 ends walkable before, 4 of 6 after; walked up by scripted real clients ([archipelago-world.md](../technical/archipelago-world.md#where-the-roads-meet-the-cities-d-137-october-23)).

### P4: private content

* **A porter for private 1.18.2 Lost Cities packs (D-131)**, proven on an invented fixture; **no private content was available or ported** ([private-content-workflow.md](../technical/private-content-workflow.md#the-porter)).

### P3: modpack research

* **Nothing added, removed or re-pinned.** Lootr (MIT) was reviewed and not adopted: per-player containers would be a second owner of the stashes, stolen marks and home loot that Core owns. Street-lamp and furniture mods were reviewed from their listings only and none is needed (licences and verdicts in [mod-audit.md](../technical/mod-audit.md#october-22-candidate-review-loot-containers-street-lighting-and-furnishing-nothing-adopted)). Create is not used.

## Bugs that testing found and fixed in this pass

Each was found by a real-client run, a screenshot or a GameTest, not by reading code:

| Found by | What was wrong | Fix |
| --- | --- | --- |
| Journey screenshot, 1280x720 GUI 3 | the side status display covered the hunger bar and the hotbar | the stack keeps a hotbar reserve over the hotbar's column |
| GameTest | the pub's room deposit was not two rents | the data says 20 |
| City probe (registry) | `modernlife:stool` is not a block | the pub uses Modern Life's mini stools |
| Economy model | a new Technician could only lose money without ore | a level-1 order that needs no ore |
| Alarm-panel screenshots | two notices over the status card and over the trace's pads | toasts move below or beside the stack; nothing over a timed screen (D-135) |
| Alarm-panel screenshot, GUI 3 | chat lines across the next-step card | the stack draws above the chat |
| CI gallery check | the fence's short-budget screen looked the same as the full one, and both showed it closed | each row says "only N now" or "not now"; the capture shows it open |
| Journey run 23 | the free motel rooms were on page 3 of 4; the page arrows sat under the first card's buttons | the list leads with what you can move into now; the arrows are in the tab row (D-136) |
| Journey runs 19 to 21 | the "clear view" check counted the person spoken to as their own obstacle, so the player pushed into crowds | the client's copy of that person is excluded |
| Journey run 22 | the path planner treated a rug as a wall, so a player on a rug could not be routed anywhere | thin blocks are walked over |
| Journey run 26 | the crafting table was placed before the software-rendered crosshair had caught up | place and open only when aimed |
| Journey run 27 | the journey read the road's unbuilt plan and crashed on the bridge check | it uses the plan the world built |
| Journey run 28 | quarrying dug the floor under the player four blocks down into a shaft | only the pit's walls at foot level and above |
| Journey runs 25, 27 | the 700-block cross-country approach to the road got stuck in Ironhaven's sealed blocks | the rest of the approach is a logged teleport; the road and bridge are still walked |
| Journey run 29 | no street door near Valewood's centre passed the generic search (front gardens, porches) | the furnished buildings' own street doors, near where the road enters the city |
| A local run that ran three clients | the machine's memory killer stopped a journey | at most two clients at a time |
| Journey run 31, then the road check | four of the six road ends met their city with a bank, a ditch or a pit a person cannot walk (the Valewood end of the Ironhaven road was a two-block bank) | a ramp from each road end into the city, built when somebody first comes near (D-137) |
| The road check's world, read back where a road's line met Blackwater | every furnished building on a lot raised above the ground around it had its retaining wall drawn with its own furniture (sofas, beds, a toilet, doors), because the kit gave Lost Cities' style characters to furniture | those characters are never given out; a unit test fails on any palette that takes one; the porter warns |
| Journey runs 41 and 42 | a long walk's legs were asked for the final target's height (a pit followed instead of the road), and the planner ignored people standing in the way | legs reach their column at any height; creatures' cells are closed to the planner; a stuck walker sidesteps |
| Journey run 37 (its save read back) | the test planner sent the walker into a dead-end trench, the nearest explored cell to a road 225 blocks away, then offered the top of iron bars as a step | the planner heads for its unexplored frontier, and a floor must be half a block wide each way |

## Evidence

* **Forge GameTests:** **328 of 328** locally on the final code (run 11, October 23: the road ends, the travel arrow, the notice rule, the home list and the Valewood pit's data included).
* **Python:** 309 unit tests (the palette's reserved characters, the furniture audit and the porter's warning included); the economy model's invariants hold with both regrowing sites; `validate_repo.py` passes.
* **Generated worlds read back:** the road check (seed 5908) before and after the road ramps; the furniture audit on three worlds (13,286 stacks in the final journey's world and 1,404 in a road check's with the old palettes, 0 with the new).
* **Hosted CI:** on the pushed heads of this pass every job passed except two capture jobs (the fence screens, fixed in the next push and green at 1080p GUI 3 and 1440p) and the documentation check (a link to this report before it was committed, fixed).
* **Real-client scenarios:** the first-player journey (above), the alarm panel (run 2 passed both halves), and the burglary of the previous pass.
* **The Valewood pit** placed by a real new-world start (log line in [hybrid-economy.md](../technical/hybrid-economy.md#the-valewood-pit-d-134)).

## Tested, partial, proposed, blocked

* **Tested by scripts and real clients (not by a person):** the journey steps listed above as passed; the alarm panel's entry delay, jam and wrong code; the corner notices' new places (screenshots); the counter, fence, alarm and Find-a-Home screens at the CI viewports.
* **Tested by GameTests only:** fences (budget, refill, rates, crash-safe sale), commuting at the hour, city homes on the Row's terms, police with a moving thief and several responses, the alarm panel's timings and skills, the home list order, the notice placement arithmetic.
* **Partial:** in run 37 the last 225 blocks of the walk from the Row to the road were a logged teleport (walked in full by development run 40, from the travel step); the quarry's and the pit's regrowth was not watched in a client; the new buildings were not walked through; two of six road ends still meet a step 65 or more blocks in, along the road's straight line.
* **Proposed, not built:** more resource sites on Blackwater's island; jail and cameras; ports and gate guards.
* **Blocked:** the owner's private C70 content (not available to the cloud; the porter is tested on an invented fixture only).

## What a person must still do

Play the first hour on the Windows guide's settings ([playtest-guide-windows11.md](playtest-guide-windows11.md)): arrive, talk to the employer, gather and craft, deliver, rent the motel room, walk to the road and across the bridge, and note what was confusing, slow or ugly. Work an alarm panel. Walk through the pub, the store and the bakery. Nothing in the prices should be tuned before that.

## Next milestone

A person's first hour, and the fixes it asks for. Then, from what the scripts found: **the landing's distance from the roads** (Founders Row is about 720 blocks from the nearest road end, on the far side of Ironhaven; a signed street route from the Row to the road, or a landing nearer the road, would make the first trip as short as the travel aims intend; the arrow now points the way but cannot shorten it); a Blackwater resource site; and the jail or another consequence for an arrest beyond the fine.
