#!/usr/bin/env python3
"""Valewood: the greener, smaller-scale residential city (D-117). Detached houses with gardens, terraced cottages, local shops.

Every building is one 16 x 16 Lost Cities chunk. The lot is a lawn with a low fence; the fence has a gap in the middle of every side, so that a person can come from whichever
street the lot happens to face, and a stone walkway runs round the house to its front door (Lost Cities does not rotate buildings, so the side that meets a street is not known).
"""
from __future__ import annotations

import citykit as k
from citykit import Building, Palette, Part, Spot, ml, block

CITY = "valewood"
NS = "underground_city"

WALL = "minecraft:white_terracotta"
PLINTH = "minecraft:stone_bricks"
FLOOR = "minecraft:oak_planks"
CEIL_LOW = "minecraft:oak_planks"
CEIL_HIGH = "minecraft:stone_bricks"
BEAM = "minecraft:spruce_planks"
GLASS = "minecraft:glass_pane"
GRASS = "minecraft:grass_block"
PATH = "minecraft:stone_bricks"
FENCE = "minecraft:oak_fence"
ROOF = "minecraft:brick_stairs"
ROOF_BLOCK = "minecraft:bricks"
TILE = "minecraft:white_concrete"
LAWN_PLANTS = [(70, "minecraft:poppy"), (70, "minecraft:dandelion"), (70, "minecraft:oxeye_daisy"), (200, "minecraft:grass"), (0, "air")]


def stairs_block(material: str, facing: str, half: str = "bottom") -> str:
    return f"minecraft:{material}[facing={facing},half={half},shape=straight]"


def lawn(part: Part, lot: tuple[int, int, int, int] = (0, 0, 15, 15)) -> None:
    """Grass over the whole lot with scattered flowers one layer above."""
    x0, z0, x1, z1 = lot
    part.floor(x0, z0, x1, z1, GRASS)
    for z in range(z0, z1 + 1):
        for x in range(x0, x1 + 1):
            if (x * 7 + z * 13) % 5 == 0:
                part.put(x, 1, z, LAWN_PLANTS)


def fence_with_gaps(part: Part, gap: tuple[int, int] = (7, 8)) -> None:
    for i in range(16):
        for (x, z) in ((i, 0), (i, 15), (0, i), (15, i)):
            part.put(x, 1, z, FENCE)
    for i in gap:
        for (x, z) in ((i, 0), (i, 15), (0, i), (15, i)):
            part.put(x, 1, z, "air")
        # the gap is a path
        part.put(i, 0, 0, PATH); part.put(i, 0, 15, PATH); part.put(0, 0, i, PATH); part.put(15, 0, i, PATH)


def walkway(part: Part, x0: int, z0: int, x1: int, z1: int) -> None:
    """A one-block stone walkway round the house footprint (x0..x1, z0..z1 is the footprint), joined to the four gaps."""
    for x in range(x0 - 1, x1 + 2):
        part.put(x, 0, z0 - 1, PATH); part.put(x, 0, z1 + 1, PATH)
    for z in range(z0 - 1, z1 + 2):
        part.put(x0 - 1, 0, z, PATH); part.put(x1 + 1, 0, z, PATH)
    for i in (7, 8):
        for x in range(1, x0 - 1):
            part.put(x, 0, i, PATH)
        for x in range(x1 + 2, 15):
            part.put(x, 0, i, PATH)
        for z in range(1, z0 - 1):
            part.put(i, 0, z, PATH)
        for z in range(z1 + 2, 15):
            part.put(i, 0, z, PATH)


def shell(part: Part, x0: int, z0: int, x1: int, z1: int, floor_spec: str = FLOOR, top_layers: bool = True) -> None:
    """Floor slab, four walls up to the ceiling, plinth course, ceiling layers; the interior is left empty."""
    part.floor(x0, z0, x1, z1, floor_spec)
    part.ring(x0, z0, x1, z1, 1, 5, WALL)
    part.ring(x0, z0, x1, z1, 1, 1, PLINTH)
    for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        part.fill(x, 1, z, x, 5, z, BEAM)
    if top_layers:
        part.floor(x0 + 1, z0 + 1, x1 - 1, z1 - 1, CEIL_LOW, y=4)
        part.floor(x0 + 1, z0 + 1, x1 - 1, z1 - 1, CEIL_HIGH, y=5)


def stair_run(lower: Part, upper: Part, x: int, z_bottom: int, material: str = "oak_stairs") -> None:
    """A straight stair going north along column x from z_bottom (the first step, one block above the floor) to the landing six blocks higher on the upper floor.

    Six steps: the first five are stair blocks in the lower floor (y 1 to 5), the last is the upper floor's own slab at y 6. The head room above every step is cleared, through the lower
    floor's ceiling layers and the upper floor's slab where they are in the way.
    """
    for step in range(6):
        z = z_bottom - step
        y = 1 + step
        if y <= 5:
            lower.put(x, y, z, stairs_block(material, "north"))
        for head in (y + 1, y + 2):
            if head <= 5:
                lower.put(x, head, z, "air")
            else:
                upper.put(x, head - 6, z, "air")
    # the landing's slab stays; the cells beside the run in the ceiling are untouched


def house_a() -> Building:
    p = Palette("vw_house_a")
    b = Building("vw_house_a", p, use="house", city=CITY, min_floors=2, max_floors=2, filler="minecraft:stone_bricks",
                 notes="A detached two-storey house: living room and kitchen below, two bedrooms and a bathroom above, a garden all round.")
    X0, Z0, X1, Z1 = 3, 4, 12, 11    # the footprint, walls included
    # ------------------------------------------------------------------ ground floor
    g = b.new_part("ground", ground=True)
    lawn(g)
    fence_with_gaps(g)
    walkway(g, X0, Z0, X1, Z1)
    shell(g, X0, Z0, X1, Z1)
    g.door(7, 1, Z1, "south", "spruce_door")                                   # the front door, on the south side of the house
    for x in (5, 9, 10):
        g.window(x, 2, Z1, GLASS, 2)                                            # the south windows
    for x in (6, 9):
        g.window(x, 2, Z0, GLASS, 2)
    g.window(X0, 2, 7, GLASS, 2); g.window(X1, 2, 7, GLASS, 2); g.window(X0, 2, 9, GLASS, 2); g.window(X1, 2, 9, GLASS, 2)
    # a partition between the living room and the kitchen with a wide doorway
    g.fill(8, 1, 5, 8, 3, 10, WALL)
    g.clear(8, 1, 7, 8, 2, 8)
    # living room (x 5..7, z 5..10; the stair is in column 4)
    k.sofa(g, 5, 1, 5, "east", "south", "gray", 3)
    k.table(g, 5, 1, 7, 2, 1, "oak", "coffee")
    g.put(7, 1, 9, ml("oak_small_table", type="single")); k.tv(g, 7, 2, 9, "west")
    g.put(5, 1, 10, ml("modern_oak_bookshelf", facing="north"))
    g.put(5, 1, 9, ml("floor_mat", facing="east")); g.put(6, 0, 8, ml("floor_mat", facing="north"))
    # kitchen and dining (x 9..11, z 5..10)
    k.counter_run(g, 11, 1, 5, "south", "west", ["fridge", "drawers", "stove", "sink", "cabinet"], "oak")
    k.table(g, 9, 1, 7, 1, 1, "oak", "small")
    k.chair(g, 9, 1, 6, "south"); k.chair(g, 9, 1, 8, "north"); k.chair(g, 10, 1, 7, "west")
    g.put(10, 1, 10, ml("trash_can"))
    for (x, z) in ((6, 7), (10, 8)):
        k.lamp_ceiling(g, x, z)
    # the stair
    u = b.new_part("floor", floor=1)
    # ------------------------------------------------------------------ upper floor
    shell(u, X0, Z0, X1, Z1)
    u.floor(0, 0, 15, 15, "air")                                               # nothing outside the walls up here ...
    shell(u, X0, Z0, X1, Z1)                                                    # ... then the shell again
    stair_run(g, u, 4, 10)
    # the opening in the upper floor slab beside the stair (the stair's own cells) is cut by stair_run through the head room; here the slab cells the stair rises through
    for z in range(6, 10):
        u.put(4, 0, z, "air")
    u.fill(4, 1, 6, 4, 1, 10, "air")
    # a railing along the opening
    for z in range(6, 10):
        u.put(5, 1, z, FENCE)
    # windows
    for x in (6, 9):
        u.window(x, 2, Z1, GLASS, 2); u.window(x, 2, Z0, GLASS, 2)
    u.window(X0, 2, 8, GLASS, 2); u.window(X1, 2, 8, GLASS, 2); u.window(X1, 2, 6, GLASS, 2)
    # partition x = 8, a door into each east room
    u.fill(8, 1, 5, 8, 3, 10, WALL)
    u.door(8, 1, 6, "west", "spruce_door"); u.door(8, 1, 9, "west", "spruce_door")
    # the east side: a bathroom to the north (z 5..7) and a bedroom to the south (z 9..10), a wall at z 8
    u.fill(9, 1, 8, 11, 3, 8, WALL)
    k.toilet(u, 11, 1, 5, "west"); k.sink(u, 11, 1, 7, "west"); k.shower(u, 10, 1, 5, "south", "minecraft:white_concrete")
    u.floor(9, 5, 11, 7, TILE)
    # bedroom 2 (x 9..11, z 9..10)
    k.bed(u, 11, 1, 9, "west", "blue")
    u.put(9, 1, 10, ml("night_stand", facing="north"))
    # bedroom 1 (the west half, x 5..7)
    k.bed(u, 7, 1, 9, "north", "red")
    u.put(6, 1, 10, ml("night_stand", facing="north"))
    u.put(5, 1, 10, ml("oak_night_stand", facing="north"))
    u.put(7, 1, 5, ml("modern_oak_bookshelf", facing="south"))
    for (x, z) in ((6, 7), (10, 9)):
        k.lamp_ceiling(u, x, z)
    # ------------------------------------------------------------------ roof
    t = b.new_part("top", top=True)
    # a gable roof, the ridge along x: the north slope faces south, the south slope faces north
    for step in range(5):
        for x in range(X0 - 1, X1 + 2):
            t.put(x, step, Z0 - 1 + step, stairs_block("brick_stairs", "south"))
            t.put(x, step, Z1 + 1 - step, stairs_block("brick_stairs", "north"))
    for x in (X0, X1):                                   # the gable ends
        for step in range(5):
            for z in range(Z0 + step, Z1 - step + 1):
                t.put(x, step, z, BEAM)
    t.fill(10, 0, 6, 10, 5, 6, "minecraft:bricks")      # a chimney
    b.spots += [
        Spot("door", 7, 1, Z1, "south", "front door"), Spot("bed", 7, 1, 9, "north", "main bedroom"), Spot("bed", 11, 1, 9, "west", "second bedroom"),
        Spot("stove", 11, 1, 7, "west"), Spot("seat", 5, 1, 6, "south", "sofa"), Spot("seat", 9, 1, 6, "south", "dining"),
    ]
    return b


def buildings() -> list[Building]:
    return [house_a()]


def citystyle(all_buildings: list[Building]) -> dict:
    ids = [b.id for b in all_buildings if b.city == CITY]
    style = {
        "inherit": "lostcities:citystyle_standard",
        "buildingsettings": {"buildingchance": 0.44, "maxfloors": 3},
        "parkblocks": {"parkchance": 0.26},
        "selectors": {"buildings": [{"factor": 1.0, "value": f"{NS}:{i}"} for i in ids]},
    }
    return style
