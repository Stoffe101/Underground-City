# The Technician's trade: panels, service calls, the bench and the computer (D-158)

**Status (October 24, 2026): the legal trade is in Core and GameTested; one scripted real client went through it (tech run, see below). Nobody has played it. The underground side of the brief's "Hacker" (getting into a building's systems uninvited) was not built in this pass. The existing underground Technician skills (Bypass Tooling, Signal Scrubbing, Data Brokerage, Ghost Traffic) and the alarm-panel jam from D-131 are unchanged.**

Before this pass a Technician had contracts on the work-order board and one jam at an alarm panel, but no work in the city itself. Now every city building has electrics and a network, reached at a **utility panel** beside its street door, and people pay to have them repaired, fitted and upgraded.

## Panels

* **New archipelago world:** the first time a player in survival comes within two chunks of a city building, Core sets a utility panel into the wall beside its street door. The panel is a full block, so nothing falls. The service places at most two panels per player every five seconds. A panel goes only where the door's upper half has a solid, plain wall block beside it, inside the building's own chunk, with air in front of it on the street side.
* **Any other world:** the street door itself is the access point, so nothing is placed in an existing world.
* The panel's lamp is green while the systems are well and red while a fault is open.
* Every building's access point is recorded once (`Systems`, saved as `underground_city_tech`): its key, panel position, street door, city, tier, category, label and address. The record holds every building that has a panel.

## Faults and service calls

* Faults come only in a city somebody is in, so nothing breaks while nobody plays. Each city gets one every **5 to 10 minutes** of active play, with at most **2 open per city**. A fault never strikes a building whose network was upgraded lately.
* **What a fault does depends on the building:**
  * In a **shop** (a commercial building), the till and card network go down. Its street door stays shut in its hours, and a person who tries it is told why ("Closed: the till and card network are down. A Technician has been called."). The shopkeeper still serves anybody already inside.
  * In **any other building**, the alarm and cameras go off: `PoliceResponse.armed` is false and the camera loop skips it. A burglar who notices gains a window.
* Technicians in the city get a **Service call** notice with the address and the fee.
* **If nobody comes,** somebody else puts it right: after **40 minutes** of active play, or **20 minutes** for a shop, so no shop stays shut for long. Nobody is punished for not coming.

## The work

| Job | At the panel, holding | Puzzle | Who may | Pays (before contract-rate skills) | Effect |
| --- | --- | --- | --- | --- | --- |
| Repair | Technician's Toolkit | **Trace**: find the dead part (120 s) | any Technician up to tier II; **Bench Electronics** up to III; **Municipal Repairs** any tier, with a third try | 30 + 12·tier credits, 80 + 30·tier XP | the fault is cleared, the lamp turns green |
| Install | Municipal Security Array (used up) | **Patch**: feed every device (180 s; a bigger grid from tier IV) | **Automation Installer** | 45 + 18·tier credits, 110 + 35·tier XP | a **monitored alarm**: always armed for 8 hours of active play (16 with **Security Systems**) |
| Upgrade | Technician's Toolkit | **Patch**, large grid | **Systems Integrator** | 60 + 22·tier credits, 140 + 40·tier XP | the network does not fault for 4 hours of active play |

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

* **GameTests (`TechGameTests`, 6):**
  * A trace has exactly one dead part, and the readings, loads and scanner agree with it (60 seeds × 6 tiers).
  * A patch grid starts unsolved, its own answer solves it, and four turns bring a tile back (40 seeds × 3 sizes).
  * A repair refuses a Farmer, costs a try for a wrong part, pays the fee and XP once, clears the fault and puts the alarm back; a second settle pays nothing.
  * The tier, circuit-board and skill gates for repairs, installs and upgrades.
  * A monitored alarm is armed and runs out; a shop with its systems down is not open.
  * The bench refuses a design without its skill or its parts, and takes the parts and gives two boards with them.
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
  * **Tech run 5**, on the final tree, passed all 11 steps: `RESULT pass`. Its repair found the dead part in one probe.
  * **Scripted shortcuts, each logged:**
    * the clock and the travel to Valewood;
    * standing at the panel;
    * the service call raised at once (the service raises one every 5 to 10 minutes);
    * the computer and the bench set down (a home holds them), and two iron ingots;
    * Bench Electronics and Municipal Repairs for a tier III panel;
    * the Installer's level and skills, a security array and the install job;
    * the patch grid's answer read from the server (the turns were real clicks).

## Limits

* Nobody has played it.
* The fault rate, the pay and the timers are first numbers, not balanced against play.
* An Electronics Bench and a computer are crafted or come with the kit; no shop sells them.
* A panel is placed only where the wall beside the street door is a plain solid block, so some buildings use their door instead.
* The underground side is not built: nothing in this pass lets a player get into a building's systems uninvited.
