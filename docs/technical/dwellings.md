# Dwellings: a property follows the dwelling, not the chunk (D-206)

**Status (October 28, eleventh pass): implemented. L1: `tests/test_private_dwellings.py` (12 cases), `tests/test_private_wiring.py` (20). L2: `DwellingGameTests` (5 cases on invented two-chunk buildings). L3: private probes of new disposable worlds with the owner's pack (below). No home over several chunks has been leased through a real client yet, and no human has played any of it.** The current branch and its recent gameplay systems have not received human playtesting.

## The owner's rule

The owner's direction (October 28):

* A property follows the actual dwelling, not the chunk boundary.
* A detached house that occupies several chunks is one property.
* Two independently accessible terraced houses sharing a wall are two properties.
* An apartment building holds independently rentable units, with shared entrances and communal corridors.
* Larger commercial premises and warehouses can span several chunks under one lease.
* Uncertain ownership boundaries are never guessed silently.

## In Core

**A home of several boxes.** A registered home (`CrewLedger.Residence`) has one interior box or several, up to 160 (`MAX_BOXES`):

* the rooms of a house over two chunks;
* the rooms of a flat that is not one rectangle.

Every check goes through the list of boxes: inside, shell, distance, the chunks a home lies in, and the overlap rule at registration (no box of one home may reach into the one-block shell of another's). The first box is the old `min` and `max`. A save made before keeps one box. A save with several writes them all in `boxes`, and an older Core still reads the first.

**Which chunks are one building.** Lost Cities 7.5.5 says, for every chunk, which multi-chunk building it is part of and where (`getMultiBuildingInfo`): its id, the chunk's column and row in it, and its size. That is after our city's own keep-or-swap (D-198). Core records it with each chunk's plan; the stored plans are schema 2, and a plan recorded before reads as a single lot. `CityBuildings.Building` carries it as `multi`, with the building's corner chunk and a key for the whole building.

**What a multi-chunk building lets.** `data/<ns>/city_dwellings/<multi>.json` (`CityDwellings`) holds three things:

* the building's size;
* the part Lost Cities builds at each place (its own grid, `parts[x][z]`);
* each dwelling, with its rooms. A room is an interior box in a part's own coordinates, tagged with that part's place.

The dwellings are let on the multi-chunk building's own city listing. Nothing is guessed:

* A multi-chunk building with no file lets nothing, whatever its parts' own plans say. A part is never let one chunk at a time.
* A file whose dwellings reach into each other's shell, or whose rooms lie outside the building, is refused whole and logged.
* A chunk whose part is not the one the file names for its place lets nothing.
* A dwelling is registered only when every chunk of the building Core knows agrees on the building, its corner and its parts. A chunk that cannot be asked yet defers the registration.

**One id from every chunk.** A dwelling's id is `city:<multi>:<cornerX>_<cornerZ>:<dwelling>`. Which dwellings are to let is rolled from the building's corner, so every chunk agrees.

**Doors.** A door belongs to the home whose interior lies just inside it and not just outside it (`CityHomes.placeOfDoor`), and only that home's door rights open it:

* a house's front door;
* a terraced house's own door, which a neighbour's key never opens;
* a flat's door off the hall.

Any other door of the building opens for every tenant of the building: a shared street door, or a corridor's. Every public building was checked against this rule before it was applied: in every public multi-unit building the street door opens on a hall, and in every single-unit one on that unit. So nothing changed for them.

**Burglary.** A burglar's permit, from picking a lock, covers the whole multi-chunk building. Any home's interior or shell, let or held, stays out of reach: the walls between flats are their shells. A breached building is put back without touching a home, as before (D-190).

**Single lots.** A unit of a single lot can list several boxes too (`"boxes"`, up to 64). The wiring now lets a single block's flats one by one, each behind its own door, where it used to let a whole storey.

## In the private wiring (`scripts/private_dwellings.py`)

The owner's buildings are read in three dimensions, as a person moves through them. Their storeys do not keep to six-block steps (beds were found two and four blocks above a floor's base), so nothing assumes where a storey starts.

1. **Space** is every cell of air, furniture or stairs with something over it in the building. A character only a city style defines (its walls, its glass) is building material.
2. **The margin** is roofed space within two blocks of open air or the edge of the plot, on its own level: the strip under the eaves, a porch, an arcade, the roof void over the walls. It is nobody's. Read without it, one doorless opening onto a terrace made every room behind it "outside".
3. **A room** is a connected stretch of what space remains. Walls, floors and ceilings part rooms, doors are their links, and a stairwell's opening joins the storeys it serves.
4. **An entrance** is either:
   * a door between a room and the margin, the open air or the plot's edge;
   * a doorless opening onto the margin, where a person walks (a floor below, headroom above).
5. **The rule for each use:**
   * **A house:** each group of rooms joined by doors that has an entrance and a bed is one home. A group behind a street door with no bed (a garage, a shed) is no home and is reported.
   * **Premises** (a shop, a workshop, a warehouse, an office, a club): every entered group together is one lease.
   * **Flats:** the rooms with an entrance and no bed are the common hall. Each group of other rooms joined by doors, with a bed, opening on the hall or with a door of its own, is one flat. A room with a street door and a bed that other flats open on (a flat, or the hall?) is reported, and the building gets nothing.
6. **Boxes.** Every dwelling becomes boxes, per chunk, grown greedily in three dimensions. A dwelling's boxes may take in its own walls, furniture, inner doors and the floors between its storeys, but only where nothing of anybody else lies within a block: another room, the margin, the open air or the plot's edge. So its outer walls, its front door and every wall it shares stay the city's shell. Where two dwellings would still meet inside a thick wall, both are boxed more tightly, and then exactly. A dwelling that would need more boxes than Core takes is reported.

**Overrides.** The owner's selection may name a building's dwellings itself (`"dwellings": {"<building>": [ ... ]}`), or `"none"` to let nothing. Staging takes the same as `--dwellings <file>` (Windows: `-Dwellings`). An override is checked as Core will check it.

## Evidence

* **L1, invented buildings.** The fixtures cover:
  * a detached house over two chunks is one home with rooms in both;
  * terraced houses with a wall between are two;
  * a part with no way in is nobody's;
  * furniture against a wall is inside the room;
  * premises with two street doors are one lease;
  * a garage with no bed is no home and the house beside it is still one;
  * flats over two chunks: two downstairs and one upstairs over both parts, the hall in none;
  * a hall with a street door, a bed and a stair is reported;
  * a stair under a whole ceiling is a seat;
  * the owner's override wins, and `"none"` lets nothing.
* **L2, `DwellingGameTests`.** On invented two-chunk buildings with Lost Cities' answer given by hand:
  * a detached house is one home of two boxes, and a restart keeps both and the tenancy;
  * a terrace is two homes: one tenant's key never opens the other's door, and a Crew member holds the key of the Crew's house and not the neighbour's;
  * a block of flats over two chunks: the shared street door opens for every tenant and nobody else, a flat's own door for that flat alone, and a burglar's permit covers both chunks and reaches no flat;
  * a multi-chunk building with no file, or with another layout, lets nothing;
  * a file with no wall between two dwellings, a room outside the building or a short parts grid is refused;
  * a save from before loads as one box.
* **L3, private, in the cloud: the owner's pack staged and generated in a new disposable world** (`run_city_probe.py --homes --let-all`, radius 10, every place with a template let):
  * 453 buildings, 13 of them multi-chunk over 43 chunks; 12 of the 13 have dwellings;
  * 480 city homes registered, none refused;
  * 95 homes of several boxes, 14 over several chunks, at most 62 boxes in one home;
  * no home reaches a chunk of another building, and every multi-chunk building's chunks agreed on one corner and one layout.

  Of the 19 multi-chunk buildings the automatic selection chose, the analysis gave 16 their dwellings, 23 in all. Three were left out:
  * a condominium with no bed anywhere;
  * a tower whose upper corridors connect to nothing (lifts, not stairs);
  * a clubhouse with no rooms.

  All 36 single-chunk blocks of flats got per-flat units.

## Limits, said plainly

* No home over several chunks has been leased, entered or burgled through a real client. The GameTests and the probe are server-side.
* **The reading is heuristic and conservative.** It can under-split, never cross a wall:
  * two flats joined by an inner door are one flat;
  * a covered walkway that joins two terraced houses' fronts makes them one home.
* A building whose halls open doorless onto a courtyard or an arcade gets nothing, because there is no physical boundary to read. A tower served only by lifts gets nothing.
* A trapdoor is read as a wall, so a loft reached only through a hatch is nobody's.
* A home's own floors, inner walls and inner doors are inside it: its tenant may change them, as the single-lot house's one box always allowed.
* Lost Cities' plans recorded by an older Core have no multi-chunk information, so in a world generated before this pass a multi-chunk building lets nothing. Use a new world (the same rule as every generation change).
