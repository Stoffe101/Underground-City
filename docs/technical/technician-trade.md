# The Technician's trade: panels, service calls, the bench and the computer (D-158)

**Status (October 24, 2026, fifth pass): the legal trade is in Core and GameTested; scripted real clients went through it (tech runs, see below), the latest with Xaero's Minimap installed and once without. Nobody has played it. The fifth pass rebalanced the faults (D-165: a shop stays open, an alarm stays armed) and added an abstract underground side, back-channel work at a building's panel (D-166, GameTested only; no real client has done one).**

Before this pass a Technician had contracts on the work-order board and one jam at an alarm panel, but no work in the city itself. Now every city building has electrics and a network, reached at a **utility panel** beside its street door, and people pay to have them repaired, fitted and upgraded.

## Panels

* **New archipelago world:** the first time a player in survival comes within two chunks of a city building, Core sets a utility panel into the wall beside its street door. The panel is a full block, so nothing falls. The service places at most two panels per player every five seconds. A panel goes only where the door's upper half has a solid, plain wall block beside it, inside the building's own chunk, with air in front of it on the street side.
* **Any other world:** the street door itself is the access point, so nothing is placed in an existing world.
* The panel's lamp is green while the systems are well and red while a fault is open.
* Every building's access point is recorded once (`Systems`, saved as `underground_city_tech`): its key, panel position, street door, city, tier, category, label and address. The record holds every building that has a panel.

## Faults and service calls

* Faults come only in a city somebody is in, so nothing breaks while nobody plays. Each city gets one every **8 to 15 minutes** of active play (it was 5 to 10 before D-165), with at most **2 open per city**, and at most **one** of them a building's cameras. A fault never strikes a building whose network was upgraded lately.
* **What a fault does depends on the building (D-165):**
  * In a **shop** (a commercial building), the till and card network go down. The shop **stays open** and its shopkeeper still sells over the counter, but it cannot pay a supplier (`ShopSupply` refuses: "The till and card network are down, so the shop cannot pay a supplier.") and its customers buy nothing off the shelf until a Technician comes. A lost day's trade, never a shut door.
  * In **any other building**, the **cameras** go off (the camera loop skips it). The **alarm stays armed**, so a fault is a gap in the watching and never an open house.
* Technicians in the city get a **Service call** notice with the address and the fee.
* **If nobody comes,** somebody else puts it right after **20 minutes** of active play (it was 40 for a building's systems before D-165). Nobody is punished for not coming.
* **Why the rebalance (owner brief, fifth pass):** shops shut too often, and a fault turned a building's alarm off for up to 40 minutes, which made burglary trivial wherever a fault stood.

## The work

| Job | At the panel, holding | Puzzle | Who may | Pays (before contract-rate skills) | Effect |
| --- | --- | --- | --- | --- | --- |
| Repair | Technician's Toolkit | **Trace**: find the dead part (120 s) | any Technician up to tier II; **Bench Electronics** up to III; **Municipal Repairs** any tier, with a third try | 30 + 12·tier credits, 80 + 30·tier XP | the fault is cleared, the lamp turns green |
| Install | Municipal Security Array (used up) | **Patch**: feed every device (180 s; a bigger grid from tier IV) | **Automation Installer** | 150 + 25·tier credits (45 + 18·tier until the twelfth pass, which lost money against the array it uses up: D-210), 110 + 35·tier XP | a **monitored alarm**: always armed for 8 hours of active play (16 with **Security Systems**) |
| Upgrade | Technician's Toolkit | **Patch**, large grid | **Systems Integrator** | 90 + 30·tier credits (60 + 22·tier until the twelfth pass: D-210), 140 + 40·tier XP | the network does not fault for 4 hours of active play |

* A repair of a tier III building or above uses one **circuit board**.
* **The trace puzzle:**
  * The circuit runs from the supply along a trunk. From tier III it splits into branch A and branch B, each to its own load lamp. One part is dead.
  * The lamps narrow the search: both dark means the trunk; one dark means its branch.
  * A **probe** reads the test point just after a part: the signal gets there or it does not.
  * **Replace** swaps a part. Swapping a sound part costs a try, and the last try ends the repair with the fault still open.
  * A **Diagnostic Scanner** carried in the inventory outlines, from the start, the half of the line that holds the dead part.
  * The ending tells how many probes it took, and "neatly done" when that was within one of the best possible.
* **The patch puzzle:** a grid of cable tiles that turn a quarter with each click. The feed comes in on the left, and every device on the right edge must be reached through matching connections. A tile lights when it carries the feed.
* **Server-authoritative:**
  * The answers stay on the server. The client is sent what a person at the panel can see, and sends moves (probe, replace, turn, close).
  * Escape closes the panel and changes nothing.
  * Running out of time ends the job with nothing changed.
* **Paid once:**
  * A job pays through the work-order ledger's single commit (`WorkOrderLedger.prepareEncounter` then `recover`): the parts it used, the credits and the career XP land together and once per fault or job.
  * The first Technician to finish is paid. Anybody else working the same fault is told that somebody put it right first.
  * A crash between the commit and its application is recovered at the next login, as for every other encounter.
* **Install and upgrade jobs:** a city somebody is in keeps up to **2 install** jobs and **1 upgrade** job open. Each stays open for 90 minutes of active play.
  * Installs go to residential and other non-shop buildings of tier II or above that have no monitored alarm.
  * Upgrades go to offices, industrial buildings and shops of tier III or above.

## Back-channel work (D-166, the abstract underground side)

Now and then somebody wants a building's camera feeds looped and its alarm line quiet for a while, and pays off the books. It is game fiction made of the same abstract puzzle as an install, with nothing in it that resembles a real system.

* **Who sees it:** a back-channel job is listed on the computer's service board (as "Back channel: loop the feeds", in the underground colour) **only to a Technician with Bypass Tooling**. Nobody else is ever shown it, and the panel says nothing about it to anybody else. At most one is open per city, only on a tier III building or above, never one with a monitored alarm, for 90 minutes of active play.
* **The work:** crouched at the building's utility panel with a Technician's Toolkit in hand and a **Bypass Kit** in the inventory, the Technician works a **patch grid** (a bigger grid from tier V) within **150 seconds**. Tier V and VI buildings need **Signal Scrubbing**.
* **Being seen:** when the work starts, an officer in sight comes at once (the beat's own response), and anybody else in sight may call it in (the same witness roll as a break-in). Either way the work does not start.
* **Running out of time** trips the panel's **tamper alarm**: the police come to the building as for a ringing alarm.
* **Done:** the Bypass Kit is used, the job is paid once through the work-order ledger's single commit at the **underground** rate (70 + 30·tier credits, 60 + 20·tier XP, before underground payout skills), and for **10 minutes** of active play the building's **alarm does not call anybody** (`PoliceResponse.armed` is false) and its **cameras show an empty room for 20 minutes**.
* **Why:** the owner asked for the Technician's underground side as fictional puzzles. It ties the Technician to the Thief track (a Crew's thief walks in while the line is quiet) without any real intrusion technique, and its risk (witnesses, officers, the tamper alarm) is the same kind of risk as a break-in.
* **Tested:** `WorkshopGameTests.aQuietenedLineDoesNotCallAndBackChannelWorkIsHidden` (a quietened line does not call and comes back; the loop; the job hidden from a Technician without Bypass Tooling and shown, asking for a kit, to one with it; no start without a kit). **No real client has done one.**

## The Electronics Bench and the personal computer

* **Electronics Bench:** crafted from planks, iron, copper and redstone (`recipes/electronics_bench.json`). It makes the designs in `data/underground_city/tech_designs/electronics.json`, only from parts the player carries, and says what is missing or which skill is needed.

| Design | Makes | Parts | Skill |
| --- | --- | --- | --- |
| Circuit boards | 2 Control Circuit Boards | 3 Electronic Scrap, 1 copper, 1 redstone | Bench Electronics |
| Technician's toolkit | 1 toolkit (40 uses) | 2 iron, 2 copper, 1 board | none |
| Personal computer | 1 computer | 3 iron, 2 glass panes, 2 boards, 1 redstone | none |
| Diagnostic scanner | 1 scanner | 2 boards, 2 copper, 1 glass pane, 2 redstone | Municipal Repairs |
| Municipal security array | 1 array (also an existing crafting recipe) | 2 boards, 2 iron, 1 glass pane, 2 redstone | Automation Installer |

* **Personal computer:** anybody may place one, and in a home it counts as a workstation of the home. It has two apps:
  * **Service**, the city's board of service calls and jobs: where, what, the pay, how long it has been open, and whether this player may take it (and if not, why). **Track** points the status display's arrow at the building's panel.
  * **Designs**, what the bench can make and what the player still lacks.
* **Electronic Scrap** (new) has three steady sources:
  * Kessler Salvage's scrap heaps (about 11 an hour from the yard, in the economy model's table).
  * The salvage yard's counter (5 credits each).
  * Office desk equipment.
* **A new Technician** starts with a toolkit, a personal computer and two circuit boards.

## The skill tree

The ids, costs and levels of the Technician's tree are unchanged; the legal nodes now say what they do in this trade:

* **Bench Electronics:** repairs up to tier III and circuit boards at the bench.
* **Municipal Repairs:** any tier, a third try and the scanner; it still opens the municipal terminal contracts.
* **Automation Installer:** monitored alarms, and still the automation contracts and +4% legal pay.
* **Security Systems:** the restricted electronics licence; alarms it fits last twice as long.
* **Systems Integrator:** network upgrades, +4% legal pay.

## Evidence

* **GameTests (`TechGameTests`, 7):**
  * A trace has exactly one dead part, and the readings, loads and scanner agree with it (60 seeds × 6 tiers).
  * A patch grid starts unsolved, its own answer solves it, and four turns bring a tile back (40 seeds × 3 sizes).
  * A repair refuses a Farmer, costs a try for a wrong part, pays the fee and XP once, clears the fault and puts the cameras back; a second settle pays nothing.
  * Two Technicians on one call: only the first is paid.
  * The tier, circuit-board and skill gates for repairs, installs and upgrades.
  * Since D-165: a monitored alarm is always armed, even while the building's cameras are down; a shop whose till is down still sells but cannot pay a supplier.
  * The bench refuses a design without its skill or its parts, and takes the parts and gives two boards with them.
* **GameTest (`WorkshopGameTests.aQuietenedLineDoesNotCallAndBackChannelWorkIsHidden`, D-166):** a quietened line does not call the police and comes back when its time runs out; a back-channel job is hidden from a Technician without Bypass Tooling and shown to one with it.
* **Real client (`-PucPlaytest=tech`, a new furnished archipelago world each run).**
  * **Tech run 3** passed all 11 of its steps:
    1. A new Technician registered through the registration screen, with a toolkit, a computer and two boards.
    2. Twelve panels were set beside street doors near the middle of Valewood.
    3. A service call came to 3 Willow Way (tier III): the notice arrived and the lamp turned red.
    4. The computer's board listed the call with its pay, and Track pointed the status display at it.
    5. The toolkit at the panel opened the repair. Two real probes and one replacement found the dead part; the repair paid 66 credits and 170 XP once, cleared the fault and turned the lamp green.
    6. The bench made a toolkit with a real click on Assemble, and showed the security array as needing a skill.
    7. An install on the patch grid, every turn a real click, paid 102 credits and fitted a monitored alarm.
    8. After a save, quit and reopen, the panels, the repair count and the monitored alarm were as they were.
  * The run's one failure was the scenario's own: the security array was not in the hotbar.
  * **Tech run 4** passed the same steps up to the install. The scenario had set the bench down between the player and the panel, so its aim never reached the panel; the scenario now places it behind the player.
  * **Tech run 5**, on the final tree of the fourth pass, passed all 11 steps: `RESULT pass`. Its repair found the dead part in one probe.
  * **Fifth pass, with Xaero's Minimap and World Map (`-PucPack=world,furnished,scarcity,navigation`).** The scenario added waypoint steps: the waypoint on the minimap, in Xaero's list and on the world map, gone after each job, and restored once after a reopen. Run 4 stopped at the bench, which the scenario had set down out of the player's sight; the scenario now picks a cell in line of sight. Run 5 passed every step, `RESULT pass` (see [navigation](navigation.md)).
  * **Fifth pass, without Xaero.** Run 6 stopped with "tracking ended" at the install: the scenario had given the building a second install job, and the tracked one went with the first. It now gives a job only to a building with none. Run 7 passed all 18 steps, `RESULT pass`, 12 logged shortcuts, on the status display's arrow alone.
  * **Scripted shortcuts, each logged:**
    * the clock and the travel to Valewood;
    * standing at the panel;
    * the service call raised at once (the service raises one every 8 to 15 minutes since D-165, 5 to 10 before);
    * the computer and the bench set down (a home holds them), and two iron ingots;
    * Bench Electronics and Municipal Repairs for a tier III panel;
    * the Installer's level and skills, a security array and the install job;
    * the patch grid's answer read from the server (the turns were real clicks).

## Limits

* Nobody has played it.
* The fault rate, the pay and the timers are first numbers, not balanced against play.
* An Electronics Bench and a computer are crafted or come with the kit; no shop sells them.
* A panel is placed only where the wall beside the street door is a plain solid block, so some buildings use their door instead.
* The underground side is one abstract job (D-166): back-channel work on the patch grid. It is GameTested only; no real client has done one, and its pay, quiet time and risk are first numbers.
* Fault rates after D-165 were not measured in a long run; they rest on the constants and the GameTests.
