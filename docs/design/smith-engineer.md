# Smith / Engineer: fabrication, engineering and industry (eighth pass)

**Status: design accepted by the lead designer under the owner's eighth-pass brief (decision rows in [decisions.md](../production/decisions.md)); what is built and how it was tested is in [workshops.md](../technical/workshops.md) and [smith-engineering.md](../technical/smith-engineering.md). Nobody has played it.**

## Why the Smith changes

Until this pass a Smith picked a recipe at the Machinist's Bench, waited, and carried a stack of generic parts to a board. The parts mattered to other trades, but the Smith's own work was a countdown, and the skill tree was mostly small payout percentages. The owner asked for a profession somebody chooses because they enjoy making and fixing machines, and that is worth playing alone.

The Smith now has three strengths that do not overlap with the Technician (electronics, networks, security):

1. **Fabrication.** Precision work is done by hand at the bench: a part is turned to a drawing, and how well it is turned decides its grade. Once a part has been turned well, the bench keeps the setup (a jig) and repeats it, so the hands-on work is for new or better parts, never for every bolt.
2. **Engineering.** The cities run on machinery: hoists, pumps, mills, presses and conveyors in their works, depots, yards and bakeries. Machines break down; a Smith takes the call, strips the drive, replaces the worn parts with parts of their own making, sets the gearing to the machine's speed and runs it. A machine that runs again changes what the building can do.
3. **Industry.** Better tiers, better jigs and assemblies (gearboxes, pumps, sprinklers) make the Smith's workshop into a small factory that supplies the other trades and the cities.

## The hands-on work: turning to a drawing

A precision recipe (a drive shaft, machined parts, precision machinery parts) is turned by hand the first time:

* The **drawing** shows a bar in sections, each with the line it must be turned down to. The stock starts thicker than every line.
* A **pass** runs the tool along a stretch of neighbouring sections at one depth: every section in the stretch above the tool is cut down to it.
* Two tools. **Roughing** cuts deep (two steps at a Vice and Files, three on a Drill Press, four on a Lathe) but leaves tool marks. **Finishing** cuts one step and leaves a clean surface. Every section must end on its line and clean, so the craft is to rough down to one step above the line and finish the last step.
* **Mistakes are recoverable.** A section cut below its line is undersize; a **sleeve** (one iron nugget) builds it back up a step, and the part is still made, at most Fine.
* **The grade** follows the number of passes against the shop's standard (the fewest passes the drawing needs): at the standard or better, Reserve; two over, Fine; more, Standard. A sleeve caps it at Fine.
* **Nothing is taken until the part is finished;** closing the drawing changes nothing.

**The jig.** A finished drawing leaves its setup in the bench. From the Drill Press on, the same recipe can then be run as an ordinary batch at the grade the jig holds (a Drill Press jig holds at most Fine; a Lathe jig with Production Tooling holds Reserve). A Smith turns each new part once, and turns again only to make a better jig.

Why this mechanic: it is physical (material comes off a bar, the profile changes), it is legible at a glance, it has a skill curve (planning passes) and a mastery target (the standard), a mistake has a cost and a way back, and it is not the Technician's probing or cable-turning puzzle.

## Engineering in the world: machinery and service calls

* **Machines.** In a new archipelago world, Core sets a **drive housing** into the wall of the city's industrial buildings (works, warehouses, workshops, the Northgate depot) and of the shops that run on machinery (the builders' yard, the hardware desk, the bakery), beside the street door. In an older world the street door stands in for it, as the Technician's panel does. A running machine shows it; a stopped one smokes.
* **Breakdowns.** Only in a city somebody is in: one every 10 to 16 minutes of active play, at most two open per city. Smiths in the city get a **Service call** notice; the call is listed on the board and can be tracked to the machine (Xaero or Core's own arrow). Nobody is punished for not coming: after 25 minutes of active play the building's own fitter puts it right.
* **What a breakdown does.** A works, warehouse or depot stands idle: the city's exchange lines of the goods it makes gain nothing until it runs again (what they had gained is kept; the idle time is lost). A shop on machinery runs short: its shop lines cost a fifth more. A machine a player puts right runs well for two hours (no breakdown comes to it), and a works runs a catch-up shift that tops up each of those lines by a quarter of its capacity at once.
* **The repair: strip, replace, gear, run.** At the machine with an **Engineer's Toolkit**:
  1. The drive is stripped and shown: the motor, the couplings, the bearings and the gear stages to the output. One or more parts are worn (a scored bearing, a chipped gear, a cracked coupling, a tired spring); a Smith replaces each with the matching part from their own stock (a bearing set, a gear set, a steel bracket, coil springs).
  2. The machine's plate gives its speed and direction ("Hoist: 60 to 70 rpm, clockwise"). The Smith chooses the driving and driven gears of each stage; the output speed and direction are worked out on screen as they choose, and an idler gear turns a stage's direction round.
  3. **Run** tests it. A wrong ratio, a backwards output or a worn part left in is told plainly ("too fast: the load would slip"); three test runs (four with Millwright), then the drive needs a second look and can be started again. Nothing is used up until the machine runs.
  4. It pays once, the first Smith to finish, through the work-order ledger's single commit: the parts used, the credits and the career XP together.
* **Reach.** Any Smith works tier I and II machinery; **Millwright** to tier IV; **Master Engineer** any.

## Assemblies and who uses them

| Assembly | Made from | Used by |
| --- | --- | --- |
| Bearing Set | iron nuggets and a spring, at the bench | drive repairs; gearboxes |
| Drive Shaft | iron ingots, turned by hand | gearboxes; pumps; the depot buys them |
| Gearbox | two gear sets, a drive shaft, a bearing set, fasteners | the Mill's Geared Crank (Farmer); pumps; the depot and yard buy them |
| Pump Assembly | a gearbox, a drive shaft, brackets, springs (Heavy Assembly) | an Irrigation Sprinkler; the city's great machines |
| Irrigation Sprinkler | a pump assembly, copper, a bucket | a Farmer's crops grow faster around it |

Every one of them is also sold at the hardware desk or the builders' yard at a mark-up, so no trade waits for a Smith (D-164's rule).

## The skill tree

Node ids are kept, so no invested point is lost; four capability nodes are added (the tree's sixteen):

* **Forge Fundamentals**: turning by hand and tier I-II machinery.
* **Structural Works**: gear sets and gearboxes.
* **Millwright** (new, level 5, 1 point): machinery to tier IV, a fourth test run.
* **Precision Machining**: the Lathe and its precision parts.
* **Production Tooling** (new, level 7, 1 point): a jig batch can hold Reserve (on a Lathe; a Drill Press jig holds Fine without it).
* **Heavy Assembly** (new, level 8, 1 point): pump assemblies (and the sprinkler, when it is built).
* **Master Engineer** (new, level 12, 2 points): any machinery, and roughing a step deeper.

The tree costs 29 points, the most a level-30 career earns, so a Smith who plays long enough can have all of it.
* The contract, licence and underground nodes are unchanged.

## What is deliberately not here

* No automation that runs while nobody plays; batches run on the active clock as before.
* No Create, and nothing that needs it.
* No dependency on a human Smith: NPC sellers stock every assembly at a mark-up.
* The city's great machines (one-time restorations with a permanent change) are designed but are only built if the service calls are finished and tested first; see the technical page for what was built.
* The Mill's Geared Crank and the Irrigation Sprinkler in the table above are designed and not built in this pass: a gearbox and a pump assembly are made and sold, and repairs and the depot use them, but no Farmer's tool uses them yet.
