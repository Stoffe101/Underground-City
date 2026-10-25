# The Smith's engineering: turning by hand, jigs and the cities' machinery (eighth pass)

**Status (October 25, 2026, eighth pass): in Core and GameTested; see Evidence for the scripted real-client run. Nobody has played it.** The design and the reasons for it are in [smith-engineer.md](../design/smith-engineer.md); the workshop stations the Smith shares with the other trades are in [workshops.md](workshops.md). No Create, and nothing that needs it.

Before this pass a Smith chose a recipe at the Machinist's Bench, waited for a countdown and carried generic parts to a board. Two things are new. Precision parts are **turned by hand to a drawing** the first time, and the bench keeps a **jig** for later batches. The cities' works and shops **run on machinery that breaks down**, and a Smith repairs it in the world for pay.

## Turning by hand (`SmithPuzzles.Turning`, `SmithWork`, `TurningScreen`)

* **Which recipes.** A recipe with a `hands_on` block in its station's data is turned by hand. `hands_on.sections` is the length of the drawing. At the Machinist's Bench these are:

  | Recipe | Sections | Bench tier |
  | --- | --- | --- |
  | machined parts | 7 | I |
  | drive shaft | 6 | I |
  | precision machinery parts | 9 | III (Precision Machining) |

  Any other recipe is an ordinary batch.
* **Starting a drawing.** A Smith clicks **Turn by hand** on the bench's recipe row (`WorkshopNetwork.Turn`, `SmithWork.startTurning`). It is refused if:
  * the player is not a registered Smith;
  * they are already turning or repairing something;
  * somebody else is turning at this bench;
  * the bench is running a batch.

  The materials are checked, not taken.
* **The drawing.**
  * The bar is eight steps thick (`STOCK`). Each section has a line between 2 and 7, and two neighbouring sections never share one.
  * A pass runs the tool along a stretch of neighbouring sections at one line, and cuts every section above that line down to it.
  * **Roughing** may take a section down by `1 + bench tier` steps at most (one more with Master Engineer) and leaves tool marks. **Finishing** takes one step at most and leaves the section clean.
  * A section is done when it is on its line and clean.
* **The shop's standard** is the fewest passes the drawing needs, from an exact interval search (`Turning.Planner`): a window of sections standing at one height is cut by one pass per block of neighbouring sections, and each block is worked out again from the line it was cut to. A greedy plan (rough every section to one step above its line, then finish) was the first version and was wrong: a finishing pass is also the one-step move between two stages, so {7, 4, 3, 2} at a roughing depth of four takes five passes, not seven. A GameTest found it by checking the standard against a breadth-first search of every pass; the search and the planner agreed on 294 drawings in a model check during development. `Turning.plan` gives the passes, and a GameTest replays the plan on 240 generated drawings and finishes each at Reserve.
* **The grade** comes from passes over the standard, and a sleeve caps it at Fine:

  | Passes | Grade |
  | --- | --- |
  | at the standard or fewer | Reserve |
  | up to two over | Fine |
  | more | Standard |
* **Mistakes.** A section spoils if it is cut below its line, or roughed onto its line and left with marks. **Sleeve** builds it back up one step with one iron nugget. The nugget is checked when sleeving and taken only when the part is finished.
* **Nothing is taken until the part is finished.** Closing the screen or walking more than 8 blocks from the bench drops the drawing and takes nothing. When the last section is done, `finishTurning`:
  * takes the inputs and one nugget per sleeve (or makes nothing if the player no longer carries them);
  * hands over the graded output;
  * adds the recipe's career XP, half again at Reserve;
  * sends a notice.
* **The jig.** The bench keeps the best grade turned for each recipe in its block entity (`jigs`, saved, and carried on the dropped bench item as `uc_jigs`). From tier II, a turned recipe can then be **run as a batch** (`Run jig`). The batch grade is the lowest of:
  * the jig's grade;
  * Fine on a tier III bench, Standard below;
  * Reserve only with **Production Tooling**.

  A turned recipe without a jig cannot be batched, and a tier I bench cannot run one (it records the grade for when it is upgraded).
* **Graded inputs.** `grading.from_item` names the input whose grade a batch inherits. A gearbox is as good as its drive shaft, and a pump assembly as good as its gearbox.

## The cities' machinery (`MachineService`, `MachineBlock`)

* **Which buildings run on a machine** (`data/underground_city/machinery/rules.json`):
  * every **industrial** building: a freight hoist, a conveyor, a stamping press, an extractor fan or a rolling mill, chosen from the building's key;
  * four shops in the furnished cities: the Ironhaven builders' yard's timber saw, the Ironhaven workshop's power hammer, the Valewood bakery's dough mixer and the Blackwater club's cellar pump.
* **Where the machine is.** The first time a player in survival comes within two chunks, the building's machine is recorded (`Works`, saved as `underground_city_machinery`).
  * **New archipelago world:** Core sets a **drive housing** (`machine_drive`, a full block, no item) into the wall beside the street door, at most two per player every five seconds. It shows `running` and smokes while stopped.
  * **Any other world:** the street door stands in for it, as it does for the Technician's panel, so nothing is placed.
* **Breakdowns.**
  * Only in a city somebody is in: one every 10 to 16 minutes of active play per city, at most two open per city.
  * Never at a machine that is running well after a player's repair (two hours of active play).
  * The Smiths in that city get a **Service call** notice with the address and the fee.
  * If nobody comes, the building's own fitter puts it right after 25 minutes of active play. Nobody is punished for not coming.
* **What a breakdown does.**
  * **A shop's machine:** the shop runs short. Its shop lines cost a fifth more (`shortage_percent` 20, applied in `RegionalMarket.unitPrice`) until the machine runs.
  * **An industrial machine:** the works stands idle. Its city's lines of the goods the works make (`restock_items`: iron, copper, nuggets, refined alloy, machined parts, gear sets, fasteners, brackets, springs, bearing sets, drive shafts, gearboxes) gain nothing in active time while it is down (`MachineService.stalled`, read by `WorkOrderLedger.regionalUnits`). What a line had gained before the breakdown is banked when the breakdown opens. The idle time is lost, never credited later: the line's clock restarts when the machine runs again.
* **The repair** (`SmithPuzzles.Drive`, `SmithWork`, `DriveScreen`):
  * **Opening the drive.** A right click on the housing (or the street door) with an **Engineer's Toolkit** opens it, and the toolkit takes one point of wear. It is refused if:
    * the player is not a Smith;
    * they are already at work;
    * the machine's tier is beyond their reach (tier II for any Smith, IV with **Millwright**, VI with **Master Engineer**);
    * another Smith has the drive open.
  * **The drive** is the same for every Smith who opens the same breakdown, so the board can say what it needs. From the motor (960 or 1,440 rpm, clockwise) it runs: a coupling; a bearing and a gear stage for each stage; a tensioner.
    * Stages: one up to tier II, two at tiers III and IV, three from tier V.
    * Worn parts: one, or two from tier IV.
  * **Replacing parts.** A worn part is replaced with a part from the player's own stock:

    | Worn part | Replaced with |
    | --- | --- |
    | coupling | steel bracket |
    | bearing | bearing set |
    | gear stage | gear set |
    | tensioner | coil springs |

    Each **open** stage (one that was worn, or one the owner wants geared again) also needs a gear set.
  * **Gearing.** The open stages' driving and driven gears are chosen from 12, 18, 24, 36 and 48 teeth. A worn stage is replaced before it is geared. Each stage turns the direction round unless it has an **idler gear**.
  * **The plate.** The plate's band is about five per cent either side of a gearing the Smith can reach. The drive as found misses it.
  * **Run** tests the drive. The player gets three test runs, four with Millwright. A wrong part or ratio is said plainly ("too fast", "turns the wrong way", "a worn part is still in"). Out of runs, the drive needs a second look: it can be opened again, and nothing was used.
* **Payment.** The first Smith whose drive meets the plate is paid once through the work-order ledger's single commit (`TechWork.settle`, which the Technician's repairs use): the parts are taken, then the credits and the career XP are paid, all together.
  * A tier pays `35 + 14·tier` credits (before the career's contract-rate skills) and `90 + 30·tier` XP.
  * A second settle of the same breakdown is refused.
  * The machine runs again and runs well for two hours. A **works** runs a catch-up shift that tops up each of its city's works lines by a quarter of its capacity (`restock_share`). A shop's lines cost their usual price again.
* **Service calls.** The Engineer's Toolkit, used in the air, lists every open breakdown, the city you are in first (`ServiceCallsScreen`), with:
  * the machine, the building, the tier, the address and the pay;
  * how long it has waited;
  * what to bring, or why you cannot take it.

  **Track** points the status display's arrow at the machine, and Xaero's Minimap too when it is installed (navigation kind `machine`, D-161).

## New items and where they come from

| Item | Made at | Sold at (no Smith needed, D-164) |
| --- | --- | --- |
| Bearing Set | Machinist's Bench, tier I | the Ironhaven builders' yard |
| Drive Shaft | Machinist's Bench, turned by hand | the Ironhaven builders' yard |
| Gearbox | Machinist's Bench, tier II, Structural Works; grade from its drive shaft | the Ironhaven builders' yard |
| Pump Assembly | Machinist's Bench, tier III, Heavy Assembly; grade from its gearbox | the Ironhaven hardware desk |
| Engineer's Toolkit | crafting table (two iron, a bracket, fasteners, red dye); durable | the Ironhaven hardware desk; one in the Smith's starter kit with two bearing sets |

## The skill tree

The Smith tree has sixteen nodes and costs 29 points, the most a level-30 career earns. The twelve older node ids and costs are unchanged, so no invested point is lost. Four nodes are new:

| Node | Level | Cost | Effect |
| --- | --- | --- | --- |
| `smith.millwright` | 5 | 1 | machinery to tier IV; a fourth test run |
| `smith.production_tooling` | 7 | 1 | a jig batch can hold Reserve |
| `smith.heavy_assembly` | 8 | 1 | pump assemblies |
| `smith.master_engineer` | 12 | 2 | any machinery; roughing a step deeper |

## Networking

`SmithNetwork` (version 1) carries:

* the drawing and the drive to the client (`TurnView`, `DriveView`);
* the player's moves to the server: cut, sleeve, replace, gear, idler, run, close;
* the list of calls and the end of a job.

The server checks every move against the player's own session. A handler's exception is logged, not swallowed. `WorkshopNetwork` is now version 3: its row carries `turned`, `jigSet` and `jigRun`.

## Evidence

* **GameTests** (`SmithGameTests`):
  * the standard is the fewest passes a breadth-first search finds;
  * a part is graded by its passes, and a sleeve saves a spoiled section at Fine;
  * a drive starts off its plate and can be geared onto it;
  * a turned part is made only when finished, and the bench keeps its jig;
  * a repair pays the first Smith once, restarts the machine and runs a catch-up shift. The works' iron line gains nothing while idle, and resumes from the repair without counting the idle time;
  * a shop runs short while its machine is down, and a tier beyond reach is refused.
* **Real client:** see the eighth-pass report ([eighth-pass-living-sandbox.md](../production/eighth-pass-living-sandbox.md)) for the engineer scenario's runs, what they did with real clicks and what was scripted.

## Limits

* Nobody has played it. The scripted client runs are not human playtesting.
* The Mill's Geared Crank (a Farmer using a gearbox) and the Irrigation Sprinkler are designed, not built. A pump assembly is sold and made, but nothing uses one yet.
* The city's great machines (one-time restorations) are not built.
* The breakdown cadence (10 to 16 minutes), the fees and the stall effect are first values from the economy model's ranges, not tuned by play.
