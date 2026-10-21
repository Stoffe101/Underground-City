#!/usr/bin/env python3
"""Valewood: the greener, smaller-scale residential city (D-117). Detached houses and cottages with gardens, and a few shops round the market.

Every building is one 16 x 16 Lost Cities chunk. The lot is a lawn with a low fence; the fence has a gap in the middle of every side, so that a person can come from whichever
street the lot happens to face, and a stone walkway runs round the house to its front door (Lost Cities does not rotate buildings, so the side that meets a street is not known).
The buildings are drawn once and written into the world as data; Core's CityCharacter chooses which one stands in which ring of the city (``zones``).
"""
from __future__ import annotations

from dataclasses import dataclass

import citykit as k
from citykit import Building, Palette, Part, Spot, ml

CITY = "valewood"
NS = "underground_city"

GLASS = "minecraft:glass_pane"
GRASS = "minecraft:grass_block"
PATH = "minecraft:stone_bricks"
FENCE = "minecraft:oak_fence"
LAWN_PLANTS = [(70, "minecraft:poppy"), (70, "minecraft:dandelion"), (70, "minecraft:oxeye_daisy"), (200, "minecraft:grass"), (0, "air")]


@dataclass(frozen=True)
class Look:
    """The materials and colours of one house design."""
    wall: str
    plinth: str
    floor: str
    ceil_low: str
    ceil_high: str
    beam: str
    roof_stairs: str       # e.g. "brick_stairs"
    roof_block: str
    door: str              # e.g. "spruce_door"
    sofa: str
    beds: tuple[str, str]
    tile: str = "minecraft:white_concrete"


WHITE_BRICK = Look("minecraft:white_terracotta", "minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:stone_bricks", "minecraft:spruce_planks",
                   "brick_stairs", "minecraft:bricks", "spruce_door", "gray", ("red", "blue"))
TIMBER = Look("minecraft:birch_planks", "minecraft:cobblestone", "minecraft:spruce_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:dark_oak_planks",
              "dark_oak_stairs", "minecraft:dark_oak_planks", "birch_door", "blue", ("green", "yellow"))
STONE_COTTAGE = Look("minecraft:smooth_sandstone", "minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:smooth_sandstone", "minecraft:oak_log",
                     "stone_brick_stairs", "minecraft:stone_bricks", "oak_door", "brown", ("pink", "light_blue"))


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


def shell(part: Part, look: Look, x0: int, z0: int, x1: int, z1: int, top_layers: bool = True) -> None:
    """Floor slab, four walls up to the ceiling, plinth course, ceiling layers; the interior is left empty."""
    part.floor(x0, z0, x1, z1, look.floor)
    part.ring(x0, z0, x1, z1, 1, 5, look.wall)
    part.ring(x0, z0, x1, z1, 1, 1, look.plinth)
    for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        part.fill(x, 1, z, x, 5, z, look.beam)
    if top_layers:
        part.floor(x0 + 1, z0 + 1, x1 - 1, z1 - 1, look.ceil_low, y=4)
        part.floor(x0 + 1, z0 + 1, x1 - 1, z1 - 1, look.ceil_high, y=5)


def gable_roof(t: Part, look: Look, x0: int, z0: int, x1: int, z1: int) -> None:
    """A gable roof over the footprint, the ridge along x, one block of overhang north and south; the gable ends are filled with the beam material."""
    depth = (z1 - z0 + 1) + 2
    steps = depth // 2
    for step in range(steps):
        for x in range(x0 - 1, x1 + 2):
            t.put(x, step, z0 - 1 + step, stairs_block(look.roof_stairs, "south"))
            t.put(x, step, z1 + 1 - step, stairs_block(look.roof_stairs, "north"))
    if depth % 2:                                                   # an odd depth leaves one ridge row
        for x in range(x0 - 1, x1 + 2):
            t.put(x, steps, z0 - 1 + steps, look.roof_block)
    for x in (x0, x1):
        for step in range(steps):
            for z in range(z0 + step, z1 - step + 1):
                t.put(x, step, z, look.beam)


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


# ---------------------------------------------------------------------------------------------------- the detached house

def house(bid: str, look: Look) -> Building:
    p = Palette(bid)
    b = Building(bid, p, use="house", city=CITY, min_floors=2, max_floors=2, filler="minecraft:stone_bricks", security="cottage",
                 notes="A detached two-storey house: living room and kitchen below, two bedrooms and a bathroom above, a garden all round.")
    X0, Z0, X1, Z1 = 3, 4, 12, 11    # the footprint, walls included
    g = b.new_part("ground", ground=True)
    lawn(g)
    fence_with_gaps(g)
    walkway(g, X0, Z0, X1, Z1)
    shell(g, look, X0, Z0, X1, Z1)
    g.door(7, 1, Z1, "south", look.door)                                       # the front door, on the south side of the house
    for x in (5, 9, 10):
        g.window(x, 2, Z1, GLASS, 2)
    for x in (6, 9):
        g.window(x, 2, Z0, GLASS, 2)
    g.window(X0, 2, 7, GLASS, 2); g.window(X1, 2, 7, GLASS, 2); g.window(X0, 2, 9, GLASS, 2); g.window(X1, 2, 9, GLASS, 2)
    # a partition between the living room and the kitchen with a wide doorway
    g.fill(8, 1, 5, 8, 3, 10, look.wall)
    g.clear(8, 1, 7, 8, 2, 8)
    # living room (x 5..7, z 5..10; the stair is in column 4)
    k.sofa(g, 5, 1, 5, "east", "south", look.sofa, 3)
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
    u = b.new_part("floor", floor=1)
    shell(u, look, X0, Z0, X1, Z1)
    u.floor(0, 0, 15, 15, "air")                                               # nothing outside the walls up here ...
    shell(u, look, X0, Z0, X1, Z1)                                             # ... then the shell again
    stair_run(g, u, 4, 10)
    for z in range(6, 10):
        u.put(4, 0, z, "air")
    u.fill(4, 1, 6, 4, 1, 10, "air")
    for z in range(6, 10):
        u.put(5, 1, z, FENCE)                                                  # a railing along the opening
    for x in (6, 9):
        u.window(x, 2, Z1, GLASS, 2); u.window(x, 2, Z0, GLASS, 2)
    u.window(X0, 2, 8, GLASS, 2); u.window(X1, 2, 8, GLASS, 2); u.window(X1, 2, 6, GLASS, 2)
    u.fill(8, 1, 5, 8, 3, 10, look.wall)                                       # partition x = 8, a door into each east room
    u.door(8, 1, 6, "west", look.door); u.door(8, 1, 9, "west", look.door)
    u.fill(9, 1, 8, 11, 3, 8, look.wall)                                       # the east side: a bathroom to the north (z 5..7) and a bedroom to the south, a wall at z 8
    k.toilet(u, 11, 1, 5, "west"); k.sink(u, 11, 1, 7, "west"); k.shower(u, 10, 1, 5, "south", look.tile)
    u.floor(9, 5, 11, 7, look.tile)
    k.bed(u, 11, 1, 9, "west", look.beds[1])
    u.put(9, 1, 10, ml("oak_night_stand", facing="north"))
    k.bed(u, 7, 1, 9, "north", look.beds[0])
    u.put(6, 1, 10, ml("oak_night_stand", facing="north")); u.put(5, 1, 10, ml("oak_night_stand", facing="north"))
    u.put(7, 1, 5, ml("modern_oak_bookshelf", facing="south"))
    for (x, z) in ((6, 7), (10, 9)):
        k.lamp_ceiling(u, x, z)
    t = b.new_part("top", top=True)
    gable_roof(t, look, X0, Z0, X1, Z1)
    t.fill(10, 0, 6, 10, 5, 6, look.roof_block)                                # a chimney
    b.spots += [
        Spot("door", 7, 1, Z1, "south", "front door"), Spot("bed", 7, 7, 9, "north", "main bedroom"), Spot("bed", 11, 7, 9, "west", "second bedroom"),
        Spot("stove", 11, 1, 7, "west"), Spot("seat", 5, 1, 6, "south", "sofa"), Spot("seat", 9, 1, 6, "south", "dining"),
    ]
    return b


# ---------------------------------------------------------------------------------------------------- the cottage

def cottage(bid: str, look: Look) -> Building:
    """A one-storey cottage on a big lot with two vegetable beds in front: living room and kitchen in one, a bedroom and a washroom."""
    p = Palette(bid)
    b = Building(bid, p, use="house", city=CITY, min_floors=1, max_floors=1, filler="minecraft:stone_bricks", security="cottage",
                 notes="A small one-storey cottage with a kitchen garden: one living room with a kitchen, a bedroom, a washroom.")
    X0, Z0, X1, Z1 = 4, 3, 11, 9        # 8 wide, 7 deep, walls included
    g = b.new_part("ground", ground=True)
    lawn(g)
    fence_with_gaps(g)
    walkway(g, X0, Z0, X1, Z1)
    # two vegetable beds south of the cottage: farmland round a water cell, wheat and carrots
    for bx, crop in ((2, "minecraft:wheat[age=7]"), (10, "minecraft:carrots[age=7]")):
        for z in range(12, 15):
            for x in range(bx, bx + 3):
                g.put(x, 0, z, "minecraft:farmland[moisture=7]")
                g.put(x, 1, z, crop)
        g.put(bx + 1, 0, 13, "minecraft:water[level=0]"); g.put(bx + 1, 1, 13, "air")
    for z in range(10, 15):                                                    # the path between the beds, from the door to the gap in the south fence
        g.put(7, 0, z, PATH); g.put(8, 0, z, PATH)
    shell(g, look, X0, Z0, X1, Z1)
    g.door(7, 1, Z1, "south", look.door)
    for x in (5, 9, 10):
        g.window(x, 2, Z1, GLASS, 2)
    g.window(6, 2, Z0, GLASS, 2); g.window(9, 2, Z0, GLASS, 2); g.window(X0, 2, 6, GLASS, 2); g.window(X1, 2, 6, GLASS, 2)
    # a partition at x = 8: the east part holds the bedroom (north) and the washroom (south)
    g.fill(8, 1, Z0 + 1, 8, 3, Z1 - 1, look.wall)
    g.door(8, 1, 7, "west", look.door)
    g.fill(9, 1, 6, 10, 3, 6, look.wall)
    # living room and kitchen (x 5..7, z 4..8)
    k.counter_run(g, 5, 1, 4, "east", "south", ["fridge", "drawers", "stove", "sink"], "oak")
    k.table(g, 6, 1, 6, 1, 1, "oak", "small")
    k.chair(g, 5, 1, 6, "east"); k.chair(g, 7, 1, 6, "west")
    k.sofa(g, 5, 1, 8, "east", "north", look.sofa, 3)
    g.put(6, 0, 7, ml("floor_mat", facing="north"))
    k.lamp_ceiling(g, 6, 5)
    # bedroom (x 9..10, z 4..5) and washroom (x 9..10, z 7..8)
    k.bed(g, 9, 1, 4, "south", look.beds[0])
    g.put(10, 1, 4, ml("oak_night_stand", facing="west"))
    k.toilet(g, 10, 1, 8, "west"); k.shower(g, 9, 1, 8, "east", look.tile)
    g.floor(9, 7, 10, 8, look.tile)
    k.lamp_ceiling(g, 10, 5)
    t = b.new_part("top", top=True)
    gable_roof(t, look, X0, Z0, X1, Z1)
    b.spots += [Spot("door", 7, 1, Z1, "south", "front door"), Spot("bed", 9, 1, 4, "south", "bedroom"), Spot("stove", 7, 1, 4, "south"), Spot("seat", 6, 1, 6, "east", "table")]
    return b


# ---------------------------------------------------------------------------------------------------- the shop

def shop(bid: str, look: Look) -> Building:
    """A market shop: the shop floor below (a counter, shelves, crates, a till), a small flat above, a flat roof behind a parapet."""
    p = Palette(bid)
    b = Building(bid, p, use="shop", city=CITY, min_floors=2, max_floors=2, filler="minecraft:stone_bricks", security="storefront",
                 notes="A market shop: counter, shelves and crates on the ground floor, a small flat above.")
    X0, Z0, X1, Z1 = 2, 3, 13, 12
    g = b.new_part("ground", ground=True)
    g.floor(0, 0, 15, 15, "minecraft:stone_bricks")                           # the forecourt is paved, not a lawn
    for i in (0, 15):
        for j in range(0, 16, 5):
            g.put(i, 1, j, FENCE); g.put(j, 1, i, FENCE)
    shell(g, look, X0, Z0, X1, Z1)
    g.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, "minecraft:spruce_planks")
    # the shop front on the south wall: a wide glazed front with a double door in the middle
    for x in range(4, 12):
        if x not in (7, 8):
            g.window(x, 2, Z1, "minecraft:glass", 2)
    g.door(7, 1, Z1, "south", "spruce_door"); g.door(8, 1, Z1, "south", "spruce_door", hinge="right")
    for x in range(3, 13):                                                     # an awning over the pavement
        g.put(x, 4, Z1 + 1, "minecraft:red_wool" if x % 2 else "minecraft:white_wool")
    g.window(X0, 2, 6, GLASS, 2); g.window(X1, 2, 6, GLASS, 2); g.window(X0, 2, 9, GLASS, 2); g.window(X1, 2, 9, GLASS, 2)
    # the counter (z 6) with the till on it, shelves on the back wall, crates on the floor
    for x in range(5, 11):
        g.put(x, 1, 6, ml("oak_kitchen_cabinet", facing="south"))
    g.put(9, 2, 6, ml("monitor", facing="south"))
    for x in range(4, 12):
        g.put(x, 1, Z0 + 1, ml("modern_oak_bookshelf", facing="south"))
    for x in (4, 6, 9, 11):
        g.put(x, 2, Z0 + 1, ml("oak_wall_shelf", facing="south"))
    for (x, z) in ((4, 8), (4, 9), (11, 8), (11, 9), (5, 10), (10, 10)):
        g.put(x, 1, z, "minecraft:barrel[facing=up,open=false]")
    for x in (6, 7, 8):
        g.put(x, 0, 9, ml("floor_mat", facing="north"))
    for (x, z) in ((6, 8), (9, 8)):
        k.lamp_ceiling(g, x, z)
    # the stair to the flat in the west of the shop floor
    u = b.new_part("floor", floor=1)
    shell(u, look, X0, Z0, X1, Z1)
    u.floor(0, 0, 15, 15, "air")
    shell(u, look, X0, Z0, X1, Z1)
    stair_run(g, u, 3, 11)
    for z in range(7, 11):
        u.put(3, 0, z, "air")
    u.fill(3, 1, 7, 3, 1, 11, "air")
    for z in range(7, 11):
        u.put(4, 1, z, FENCE)
    # the flat: a bedroom in the west, a living room and kitchen in the east
    u.fill(7, 1, Z0 + 1, 7, 3, Z1 - 1, look.wall)
    u.door(7, 1, 8, "west", look.door)
    for x in (4, 10):
        u.window(x, 2, Z1, GLASS, 2); u.window(x, 2, Z0, GLASS, 2)
    u.window(X1, 2, 5, GLASS, 2); u.window(X1, 2, 9, GLASS, 2)
    k.bed(u, 5, 1, 5, "south", look.beds[0]); u.put(4, 1, 5, ml("oak_night_stand", facing="east")); u.put(6, 1, 5, ml("oak_night_stand", facing="west"))
    u.put(5, 1, 11, ml("modern_oak_bookshelf", facing="north"))
    k.counter_run(u, 12, 1, 5, "south", "west", ["fridge", "drawers", "stove", "sink"], "oak")
    k.table(u, 9, 1, 7, 1, 2, "oak", "small"); k.chair(u, 8, 1, 7, "east"); k.chair(u, 10, 1, 8, "west")
    k.sofa(u, 9, 1, 11, "east", "north", look.sofa, 3)
    k.lamp_ceiling(u, 5, 8); k.lamp_ceiling(u, 10, 8)
    t = b.new_part("top", top=True)
    t.ring(X0, Z0, X1, Z1, 0, 1, look.plinth)                                  # a parapet round a flat roof
    b.spots += [Spot("door", 7, 1, Z1, "south", "shop door"), Spot("counter", 7, 1, 7, "south", "till"), Spot("shelf", 7, 1, Z0 + 2, "south", "display"),
                Spot("bed", 5, 7, 5, "south", "flat bed"), Spot("stove", 12, 7, 6, "west", "flat kitchen")]
    return b


def buildings() -> list[Building]:
    house_a = house("vw_house_a", WHITE_BRICK)
    house_b = k.mirror_building(house("vw_house_b_src", TIMBER), "vw_house_b")
    return [house_a, house_b, cottage("vw_cottage", STONE_COTTAGE), shop("vw_shop", WHITE_BRICK)]


def zones() -> list[tuple[float, list[tuple[str, int]]]]:
    """Which buildings stand how far from the centre (a fraction of the city radius): the market shops in the middle, houses round them, cottages and gardens at the edge."""
    return [
        (0.30, [("vw_shop", 3), ("vw_house_a", 1), ("vw_house_b", 1)]),
        (0.70, [("vw_house_a", 3), ("vw_house_b", 3), ("vw_cottage", 2), ("vw_shop", 1)]),
        (1.00, [("vw_cottage", 4), ("vw_house_a", 2), ("vw_house_b", 2)]),
    ]


def citystyle(all_buildings: list[Building]) -> dict:
    return {
        "inherit": "lostcities:citystyle_standard",
        "buildingsettings": {"buildingchance": 0.44, "maxfloors": 3},
        "parkblocks": {"parkchance": 0.26},
        "streetblocks": {"frontchance": 0.0},   # no stock "front" (entrance steps and doors on the street line): the lot is a garden with a gap in its fence
    }
