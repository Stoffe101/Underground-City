#!/usr/bin/env python3
"""Ironhaven: the industrial city (D-117). Brick workers' flats, workshops with a high bay and a mezzanine office, and warehouses; a grey, working skyline of three and four storeys,
not a downtown of towers. Every lot is a paved yard with an iron fence; chimneys, water tanks and sawtooth roofs make the silhouette.
"""
from __future__ import annotations

import citykit as k
from citykit import Building, Palette, Part, Spot, ml, FLOOR_H
from city_blocks import TenementLook, tenement

CITY = "ironhaven"

BRICK = "minecraft:bricks"
STONE = "minecraft:stone_bricks"
CONCRETE = "minecraft:gray_concrete"
DARK = "minecraft:polished_blackstone_bricks"
YARD = [(180, "minecraft:cracked_stone_bricks"), (0, "minecraft:gray_concrete")]
BARS = "minecraft:iron_bars"
GLASS = "minecraft:glass_pane"


def yard(part: Part, gaps: tuple[int, ...] = (7, 8)) -> None:
    """A paved yard over the whole lot, an iron fence round it with a gap in the middle of every side, a street light in two corners."""
    part.floor(0, 0, 15, 15, YARD)
    for i in range(16):
        for (x, z) in ((i, 0), (i, 15), (0, i), (15, i)):
            if i % 3 == 0 or i in (1, 14):
                part.put(x, 1, z, BARS)
    for i in gaps:
        for (x, z) in ((i, 0), (i, 15), (0, i), (15, i)):
            part.put(x, 1, z, "air")
    for (x, z) in ((0, 0), (15, 15)):
        part.put(x, 1, z, ml("street_light", facing="south", part="bottom"))
        part.put(x, 2, z, ml("street_light", facing="south", part="mid"))
        part.put(x, 3, z, ml("street_light", facing="south", part="top"))


def walls(part: Part, x0: int, z0: int, x1: int, z1: int, wall: str, plinth: str = STONE, y1: int = 5) -> None:
    part.ring(x0, z0, x1, z1, 1, y1, wall)
    part.ring(x0, z0, x1, z1, 1, 1, plinth)
    for (x, z) in ((x0, z0), (x1, z0), (x0, z1), (x1, z1)):
        part.fill(x, 1, z, x, y1, z, plinth)


def parapet_roof(t: Part, x0: int, z0: int, x1: int, z1: int, wall: str) -> None:
    """A flat roof behind a two-block parapet; the slab itself belongs to the floor below."""
    t.ring(x0, z0, x1, z1, 0, 1, wall)


def chimney(t: Part, x: int, z: int, height: int = 5) -> None:
    t.fill(x, 0, z, x, height, z, DARK)
    t.put(x, height + 1 if height + 1 < FLOOR_H else height, z, "minecraft:campfire[facing=north,lit=false,signal_fire=false,waterlogged=false]")


def water_tank(t: Part, x: int, z: int) -> None:
    """A wooden water tank on legs: four posts, a barrel-built drum, a cap."""
    for dx in (0, 2):
        for dz in (0, 2):
            t.put(x + dx, 0, z + dz, "minecraft:dark_oak_fence")
            t.put(x + dx, 1, z + dz, "minecraft:dark_oak_fence")
    t.fill(x, 2, z, x + 2, 3, z + 2, "minecraft:dark_oak_planks")
    t.fill(x + 1, 4, z + 1, x + 1, 4, z + 1, "minecraft:dark_oak_slab[type=bottom]")


# ---------------------------------------------------------------------------------------------------- the workers' flats

def flats() -> Building:
    """A three-storey brick tenement: a corridor down the middle of every floor, a flat on each side of it (a bedroom, a kitchen and a living room), the stair in the corridor."""
    look = TenementLook(wall=BRICK, plinth=STONE, floor="minecraft:spruce_planks", ceil_low="minecraft:oak_planks", ceil_high=STONE, partition=BRICK, pane=GLASS, pane_height=2,
                        flat_door="spruce_door", street_door="iron_door", wood="oak", sofa="gray", tv=False, colours=("red", "blue", "green", "yellow", "orange", "purple"))

    def roof(t: Part) -> None:
        parapet_roof(t, 1, 1, 14, 14, BRICK)
        chimney(t, 3, 3); chimney(t, 12, 12)
        water_tank(t, 6, 6)

    return tenement("ih_flats", CITY, 3, look, yard, roof, "flat_block", "A three-storey brick tenement: two flats on every floor either side of a central corridor with the stair.")


# ---------------------------------------------------------------------------------------------------- the workshop

def workshop() -> Building:
    """A workshop with a high bay: benches, an anvil, a grindstone and two blast furnaces on the shop floor; an office mezzanine over the north half, reached by a stair."""
    p = Palette("ih_workshop")
    b = Building("ih_workshop", p, use="workshop", city=CITY, min_floors=2, max_floors=2, filler=STONE, security="workshop",
                 notes="A high-bay workshop: tools and furnaces below, an office mezzanine above the north half.")
    X0, Z0, X1, Z1 = 1, 2, 14, 14
    g = b.new_part("ground", ground=True)
    yard(g)
    g.floor(X0, Z0, X1, Z1, "minecraft:smooth_stone")
    walls(g, X0, Z0, X1, Z1, CONCRETE, STONE)
    # roller doors on the south and north walls (a double door each), high windows
    for x in (7, 8):
        g.door(x, 1, Z1, "south", "iron_door", hinge="left" if x == 7 else "right")
        g.door(x, 1, Z0, "north", "iron_door", hinge="left" if x == 7 else "right")
    for x in (3, 5, 10, 12):
        g.put(x, 3, Z1, BARS); g.put(x, 4, Z1, BARS); g.put(x, 3, Z0, BARS); g.put(x, 4, Z0, BARS)
    for z in (5, 8, 11):
        g.put(X0, 3, z, BARS); g.put(X0, 4, z, BARS); g.put(X1, 3, z, BARS); g.put(X1, 4, z, BARS)
    # the shop floor: west wall benches, east wall furnaces, the middle a long work table, tool storage in the south-west
    g.put(2, 1, 5, "minecraft:crafting_table"); g.put(2, 1, 6, "minecraft:smithing_table"); g.put(2, 1, 7, "minecraft:anvil[facing=east]"); g.put(2, 1, 8, "minecraft:grindstone[face=floor,facing=east]")
    g.put(2, 1, 9, "minecraft:stonecutter[facing=east]")
    g.put(13, 1, 6, "minecraft:blast_furnace[facing=west,lit=false]"); g.put(13, 1, 8, "minecraft:blast_furnace[facing=west,lit=false]"); g.put(13, 1, 10, "minecraft:furnace[facing=west,lit=false]")
    g.put(13, 1, 7, "minecraft:cauldron"); g.put(13, 1, 9, "minecraft:cauldron")
    for x in range(5, 11):
        g.put(x, 1, 9, "minecraft:smooth_stone_slab[type=top]")
        g.put(x, 0, 9, "minecraft:smooth_stone")
    for (x, z) in ((3, 12), (4, 12), (3, 13), (5, 13)):
        g.put(x, 1, z, "minecraft:barrel[facing=up,open=false]")
    g.put(12, 1, 13, "minecraft:chest[facing=north,type=single,waterlogged=false]"); g.put(11, 1, 13, "minecraft:chest[facing=north,type=single,waterlogged=false]")
    g.put(6, 1, 12, ml("metal_duct", axis="y")); g.put(6, 2, 12, ml("metal_duct", axis="y")); g.put(6, 3, 12, ml("metal_duct", axis="y"))
    g.put(9, 1, 13, "underground_city:operations_terminal[facing=north]")      # a public Operations Terminal by the bay door (D-175): the city's exchange
    for (x, z) in ((5, 7), (10, 7), (5, 11), (10, 11)):
        g.put(x, 5, z, ml("light_bulb", face="ceiling", facing="north", powered=True))
    # the mezzanine over the north half (z 3..7): its slab is the upper floor's own; the stair climbs along the east wall to it
    u = b.new_part("floor", floor=1)
    u.floor(0, 0, 15, 15, "air")
    u.floor(X0, Z0, X1, 7, "minecraft:spruce_planks")                         # the slab: north half only, the south half stays open to the roof
    walls(u, X0, Z0, X1, Z1, CONCRETE, STONE)
    for x in (3, 5, 10, 12):
        u.put(x, 2, Z0, BARS); u.put(x, 3, Z0, BARS); u.put(x, 2, Z1, BARS); u.put(x, 3, Z1, BARS)
    for z in (4, 10):
        u.put(X0, 2, z, BARS); u.put(X0, 3, z, BARS); u.put(X1, 2, z, BARS); u.put(X1, 3, z, BARS)
    k.stair_run(g, u, 12, 11, "north")
    # an iron rail along the mezzanine edge (z 7) and an office on it
    for x in range(X0 + 1, X1):
        if x != 12:
            u.put(x, 1, 7, "minecraft:iron_bars")
    u.fill(X0 + 1, 1, 5, X0 + 6, 3, 5, "minecraft:glass_pane")                  # a glazed partition: the office is the north-west corner
    u.put(3, 1, 3, ml("oak_small_table", type="w_end")); u.put(4, 1, 3, ml("oak_small_table", type="e_end"))
    u.put(3, 2, 3, ml("dual_monitor", facing="south")); u.put(4, 2, 3, ml("printer", facing="south"))
    u.put(3, 1, 4, k.seat("oak", "north"))
    u.put(6, 1, 3, ml("photocopier", facing="south")); u.put(2, 1, 3, ml("modern_oak_bookshelf", facing="east")); u.put(2, 1, 4, ml("modern_oak_bookshelf", facing="east"))
    u.put(9, 1, 3, ml("oak_small_table", type="single")); u.put(9, 2, 3, ml("old_tv", facing="south"))
    u.put(10, 1, 3, k.seat("oak", "west"))
    for (x, z) in ((4, 4), (10, 4)):
        u.put(x, 3, z, ml("light_bulb", face="ceiling", facing="north", powered=True))
    t = b.new_part("top", top=True)
    # a flat roof over everything, a sawtooth of skylights over the south half, a stack
    t.floor(X0, Z0, X1, Z1, CONCRETE, y=0)
    for x in range(X0 + 1, X1, 3):
        t.fill(x, 1, 9, x + 1, 1, 12, "minecraft:glass")
    parapet_roof(t, X0, Z0, X1, Z1, CONCRETE)
    chimney(t, 12, 3, 4)
    b.spots += [Spot("door", 7, 1, Z1, "south", "bay door"), Spot("door", 7, 1, Z0, "north", "bay door"),
                Spot("bench", 2, 1, 5, "east", "workbench"), Spot("anvil", 2, 1, 7, "east"), Spot("stove", 13, 1, 8, "west", "furnace"),
                Spot("desk", 7, 1, 8, "south", "the hardware counter at the work table"), Spot("stash", 12, 1, 13, "north", "tool chests")]
    b.units.append(k.Unit("premises", "Workshop", (X0 + 1, 1, Z0 + 1, X1 - 1, 10, Z1 - 1)))
    return b


# ---------------------------------------------------------------------------------------------------- the warehouse

def warehouse() -> Building:
    """A warehouse: rows of racks and pallets, a loading bay at each end, a small office with a desk in the corner."""
    p = Palette("ih_warehouse")
    b = Building("ih_warehouse", p, use="warehouse", city=CITY, min_floors=1, max_floors=1, filler=STONE, security="warehouse",
                 notes="A warehouse: racks of crates and barrels, loading bays, a corner office.")
    X0, Z0, X1, Z1 = 1, 1, 14, 14
    g = b.new_part("ground", ground=True)
    yard(g)
    g.floor(X0, Z0, X1, Z1, "minecraft:smooth_stone")
    walls(g, X0, Z0, X1, Z1, STONE, DARK)
    g.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, CONCRETE, y=5)
    for x in range(X0 + 3, X1 - 2, 3):                                         # skylights in the roof layer
        g.fill(x, 5, 6, x + 1, 5, 9, "minecraft:glass")
    # loading bays: a big double door in the middle of the north and south walls and in the east wall
    for (wx, wz, facing) in ((7, Z1, "south"), (7, Z0, "north")):
        for dx in (0, 1):
            g.door(wx + dx, 1, wz, facing, "iron_door", hinge="left" if dx == 0 else "right")
    for z in (7, 8):
        g.door(X1, 1, z, "east", "iron_door", hinge="left" if z == 7 else "right")
        g.door(X0, 1, z, "west", "iron_door", hinge="left" if z == 8 else "right")
    for x in (3, 5, 10, 12):
        g.put(x, 4, Z0, BARS); g.put(x, 4, Z1, BARS)
    # racks: four rows of two-high shelving with barrels and chests, aisles between them
    for z in (3, 11):
        for x in range(3, 13):
            if x in (7, 8):
                continue
            g.put(x, 1, z, "minecraft:barrel[facing=up,open=false]" if (x + z) % 3 else "minecraft:chest[facing=south,type=single,waterlogged=false]")
            g.put(x, 2, z, ml("oak_wall_shelf", facing="south") if (x * z) % 4 else "minecraft:barrel[facing=up,open=false]")
    for z in (5, 9):
        for x in (3, 4, 5, 10, 11, 12):
            g.put(x, 1, z, "minecraft:hay_block[axis=y]" if (x + z) % 2 else "minecraft:barrel[facing=up,open=false]")
            g.put(x, 2, z, "minecraft:barrel[facing=up,open=false]" if (x * z) % 3 == 0 else "air")
    for (x, z) in ((6, 6), (9, 6), (6, 10), (9, 10), (4, 7), (11, 7)):
        g.put(x, 4, z, ml("light_bulb", face="ceiling", facing="north", powered=True))
    # the office in the north-east corner: a glazed box with a desk
    g.put(13, 1, 2, ml("oak_small_table", type="single")); g.put(13, 2, 2, ml("monitor", facing="west")); g.put(12, 1, 2, k.seat("oak", "east"))
    g.put(13, 1, 4, ml("modern_oak_bookshelf", facing="west")); g.put(13, 1, 3, ml("printer", facing="west"))
    t = b.new_part("top", top=True)
    parapet_roof(t, X0, Z0, X1, Z1, STONE)
    chimney(t, 2, 2, 3)
    b.spots += [Spot("door", 7, 1, Z1, "south", "loading bay"), Spot("door", 7, 1, Z0, "north", "loading bay"), Spot("desk", 13, 1, 2, "west", "office"),
                Spot("shelf", 5, 1, 3, "south", "racking"), Spot("stash", 12, 1, 3, "south", "racking"),
                Spot("fence", 13, 1, 2, "west", "the night man in the yard office buys what is not asked about (D-125)")]
    b.units.append(k.Unit("premises", "Warehouse", (X0 + 1, 1, Z0 + 1, X1 - 1, 5, Z1 - 1)))
    return b


# ---------------------------------------------------------------------------------------------------- the pub

def pub() -> Building:
    """The pub: a bar along the north wall with stools, tables and a fire on the ground floor; two rooms to let and the landlord's flat above, off a corridor from the stair."""
    p = Palette("ih_pub")
    b = Building("ih_pub", p, use="pub", city=CITY, min_floors=2, max_floors=2, filler=STONE, security="storefront",
                 notes="A pub: the bar, tables and a fire below; two rooms to let and the landlord's flat above.")
    X0, Z0, X1, Z1 = 1, 1, 14, 14
    LANTERN = "minecraft:lantern[hanging=true,waterlogged=false]"
    g = b.new_part("ground", ground=True)
    yard(g)
    g.floor(X0, Z0, X1, Z1, "minecraft:dark_oak_planks")
    walls(g, X0, Z0, X1, Z1, BRICK, STONE)
    g.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, "minecraft:spruce_planks", y=4)
    g.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, STONE, y=5)
    g.door(7, 1, Z1, "south", "dark_oak_door", hinge="left"); g.door(8, 1, Z1, "south", "dark_oak_door", hinge="right")
    for x in (3, 4, 11, 12):
        g.window(x, 2, Z1, GLASS, 2)
    for z in (4, 10):
        g.window(X0, 2, z, GLASS, 2)
    # the bar: a counter along z = 4 from x 3 to 10, the way behind it at x 11; bottles on shelves and casks along the north wall
    for x in range(3, 11):
        g.put(x, 1, 4, ml("dark_oak_kitchen_cabinet", facing="south"))
    for x in (3, 5, 8, 10):
        g.put(x, 2, Z0 + 1, ml("dark_oak_wall_shelf", facing="south"))
    for x in (2, 4, 9):
        g.put(x, 1, 2, "minecraft:barrel[facing=up,open=false]")
    g.put(5, 2, 4, ml("coffee_mug", facing="south")); g.put(9, 2, 4, ml("coffee_mug", facing="south"))
    for x in (4, 6, 8, 10):
        g.put(x, 1, 5, ml("dark_oak_mini_stool", extended=False))
    # four tables of two, and a fire in a brick hearth on the west wall
    for (tx, tz) in ((4, 8), (4, 11), (9, 8), (9, 11)):
        k.table(g, tx, 1, tz, 1, 1, "dark_oak", "small")
        k.chair(g, tx - 1, 1, tz, "east", "dark_oak"); k.chair(g, tx + 1, 1, tz, "west", "dark_oak")
    g.put(2, 1, 7, "minecraft:campfire[facing=east,lit=true,signal_fire=false,waterlogged=false]")
    for (z, y) in ((6, 1), (8, 1), (6, 2), (8, 2), (7, 2), (7, 3)):
        g.put(2, y, z, BRICK)
    for (x, z) in ((5, 6), (9, 6), (5, 10), (9, 10), (7, 3)):
        g.put(x, 3, z, LANTERN)
    # the stair along the east wall, from z = 12 north to the floor above
    u = b.new_part("floor", floor=1)
    u.floor(0, 0, 15, 15, "air")
    u.floor(X0, Z0, X1, Z1, "minecraft:spruce_planks")
    walls(u, X0, Z0, X1, Z1, BRICK, BRICK)
    u.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, "minecraft:oak_planks", y=4)
    u.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, STONE, y=5)
    k.stair_run(g, u, 13, 12, "north")
    for (x, z) in ((12, 8), (12, 9), (13, 10)):
        u.put(x, 1, z, "minecraft:dark_oak_fence")                            # a rail round the stairwell
    u.fill(2, 1, 6, 13, 3, 6, BRICK)                                           # the rooms' wall on the north side of the corridor (z 7)
    u.fill(7, 1, 2, 7, 3, 5, BRICK)                                            # between the two rooms
    u.fill(2, 1, 8, 11, 3, 8, BRICK)                                           # the landlord's flat south of the corridor
    u.fill(12, 1, 10, 12, 3, 13, BRICK)
    u.door(4, 1, 6, "south", "spruce_door"); u.door(10, 1, 6, "south", "spruce_door", hinge="right")
    u.door(6, 1, 8, "north", "spruce_door")
    u.fill(2, 1, 7, 2, 5, 7, BRICK)                                            # the hearth's chimney passes the corridor's west end
    # room 1 (x 2..6, z 2..5) and room 2 (x 8..13, z 2..5): a bed, a stand, a wardrobe, a table and a chair, a window
    for (bx, stand, shelf, tx, cx) in ((3, 4, 6, 5, 6), (9, 10, 13, 12, 11)):
        k.bed(u, bx, 1, 3, "north", "brown")
        u.put(stand, 1, 2, ml("oak_night_stand", facing="south"))
        u.put(shelf, 1, 2, ml("modern_oak_bookshelf", facing="south"))
        u.put(tx, 1, 4, ml("oak_small_table", type="single")); u.put(cx, 1, 5, k.seat("oak", "north"))
        u.put(bx + 1, 1, 4, "minecraft:brown_carpet")
        u.put(bx + 1, 3, 4, LANTERN)
    for x in (3, 5, 9, 12):
        u.window(x, 2, Z0, GLASS, 2)
    # the landlord's flat (x 2..11, z 9..13): a bed, a kitchen run, a table, a sofa; the takings in a corner
    k.bed(u, 3, 1, 12, "west", "red")
    u.put(2, 1, 11, ml("oak_night_stand", facing="east"))
    k.counter_run(u, 7, 1, 13, "east", "north", ["fridge", "drawers", "stove", "sink"], "oak")
    k.table(u, 8, 1, 10, 1, 1, "oak", "small"); k.chair(u, 7, 1, 10, "east"); k.chair(u, 9, 1, 10, "west")
    k.sofa(u, 4, 1, 9, "east", "south", "brown", 2)
    for x in (4, 9):
        u.window(x, 2, Z1, GLASS, 2)
    k.lamp_ceiling(u, 5, 11); k.lamp_ceiling(u, 9, 11); u.put(8, 3, 7, LANTERN)
    t = b.new_part("top", top=True)
    parapet_roof(t, X0, Z0, X1, Z1, BRICK)
    chimney(t, 2, 7)
    water_tank(t, 9, 9)
    b.spots += [Spot("door", 7, 1, Z1, "south", "front door"), Spot("counter", 7, 1, 3, "south", "the bar"),
                Spot("seat", 3, 1, 8, "east", "a table by the fire"), Spot("seat", 8, 1, 11, "east", "a table by the window"),
                Spot("bed", 4, 7, 12, "west", "the landlord's bed"), Spot("stove", 9, 7, 12, "north", "the landlord's kitchen"),
                Spot("seat", 7, 7, 10, "east", "the landlord's table"), Spot("stash", 11, 7, 12, "west", "the takings")]
    b.units.append(k.Unit("room_1", "Room 1", (2, 7, 2, 6, 9, 5)))
    b.units.append(k.Unit("room_2", "Room 2", (8, 7, 2, 13, 9, 5)))
    return b


def buildings() -> list[Building]:
    return [flats(), workshop(), warehouse(), pub()]


def zones() -> list[tuple[float, list[tuple[str, int]]]]:
    """Tenements and workshops in the dense core, workshops and warehouses toward the edge."""
    return [
        (0.35, [("ih_flats", 4), ("ih_workshop", 2), ("ih_warehouse", 1), ("ih_pub", 1)]),
        (0.70, [("ih_flats", 2), ("ih_workshop", 3), ("ih_warehouse", 2), ("ih_pub", 1)]),
        (1.00, [("ih_workshop", 2), ("ih_warehouse", 4), ("ih_flats", 1)]),
    ]


def citystyle(all_buildings: list[Building]) -> dict:
    return {
        "inherit": "lostcities:citystyle_standard",
        "buildingsettings": {"buildingchance": 0.78, "maxfloors": 4},
        "parkblocks": {"parkchance": 0.05},
        "streetblocks": {"frontchance": 0.0},
    }
