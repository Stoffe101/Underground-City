#!/usr/bin/env python3
"""Blackwater: the port and tech city (D-117). Dark concrete and glass: apartment blocks of four storeys with roof terraces, offices with open-plan floors, and clubs with blacked-out
windows, where the underground scene of the city meets. Plazas of polished stone with hedge planters and street lights, a harbour on the bay side (not drawn here; the harbour is terrain).
"""
from __future__ import annotations

import citykit as k
from citykit import Building, Palette, Part, Spot, ml, FLOOR_H
from city_blocks import TenementLook, tenement

CITY = "blackwater"

CONCRETE = "minecraft:gray_concrete"
BLACK = "minecraft:black_concrete"
WHITE = "minecraft:white_concrete"
PLAZA = [(120, "minecraft:andesite"), (0, "minecraft:polished_andesite")]
GLASS = "minecraft:light_blue_stained_glass_pane"
CLEAR = "minecraft:glass_pane"
LAMP = "minecraft:sea_lantern"


def plaza(part: Part, gaps: tuple[int, ...] = (7, 8)) -> None:
    """A paved plaza over the lot, hedge planters along the edges with a gap in the middle of every side, a street light in two corners."""
    part.floor(0, 0, 15, 15, PLAZA)
    for i in range(1, 15):
        for (x, z, facing) in ((i, 0, "south"), (i, 15, "north"), (0, i, "east"), (15, i, "west")):
            if i not in gaps and i % 4 != 0:
                part.put(x, 1, z, ml("hedge", facing=facing))
    for (x, z) in ((0, 0), (15, 15)):
        part.put(x, 1, z, ml("street_light", facing="south", part="bottom"))
        part.put(x, 2, z, ml("street_light", facing="south", part="mid"))
        part.put(x, 3, z, ml("street_light", facing="south", part="top"))


def roof_terrace(t: Part, x0: int, z0: int, x1: int, z1: int) -> None:
    """A roof with a glass balustrade, air-conditioning units, an aerial."""
    t.ring(x0, z0, x1, z1, 0, 0, BLACK)
    t.ring(x0, z0, x1, z1, 1, 1, CLEAR)
    t.put(x0 + 2, 0, z0 + 2, ml("air_conditioner", facing="south")); t.put(x0 + 3, 0, z0 + 2, ml("air_conditioner", facing="south"))
    t.put(x1 - 2, 0, z1 - 2, ml("air_conditioner", facing="north"))
    t.fill(x1 - 3, 0, z0 + 3, x1 - 3, 3, z0 + 3, "minecraft:lightning_rod[facing=up,powered=false,waterlogged=false]")


def desk(part: Part, x: int, z: int, facing: str, y: int = 1) -> None:
    """A desk with a screen and a chair: the screen looks toward the chair, which looks back at it."""
    part.put(x, y, z, ml("dark_oak_small_table", type="single"))
    part.put(x, y + 1, z, ml("monitor", facing=facing))
    dx, dz = k.DIRS[facing]
    part.put(x + dx, y, z + dz, k.seat("dark_oak", k.OPPOSITE[facing]))


# ---------------------------------------------------------------------------------------------------- the apartment block

def apartments() -> Building:
    look = TenementLook(wall=CONCRETE, plinth=BLACK, floor="minecraft:birch_planks", ceil_low=WHITE, ceil_high=CONCRETE, partition=WHITE, pane=GLASS, pane_height=3,
                        flat_door="modernlife:modern_white_door", street_door="modernlife:iron_glass_door", wood="dark_oak", sofa="white", tv=True,
                        colours=("white", "light_gray", "cyan", "black", "blue", "gray", "red", "purple"))

    def roof(t: Part) -> None:
        roof_terrace(t, 1, 1, 14, 14)

    return tenement("bw_apartments", CITY, 4, look, plaza, roof, "flat_tower", "A four-storey apartment block in dark concrete: two flats a floor either side of a central corridor, a roof terrace.")


# ---------------------------------------------------------------------------------------------------- the office

def office() -> Building:
    """A three-storey office: reception and a waiting area below, open-plan floors with desks, a glazed meeting room and a server room above."""
    p = Palette("bw_office")
    b = Building("bw_office", p, use="office", city=CITY, min_floors=3, max_floors=3, filler=BLACK, security="office",
                 notes="A three-storey office: reception, open-plan floors, a meeting room and a server room.")
    X0, Z0, X1, Z1 = 1, 1, 14, 14

    def shell(part: Part, glass_front: bool = False) -> None:
        part.floor(X0, Z0, X1, Z1, "minecraft:gray_concrete")
        part.ring(X0, Z0, X1, Z1, 1, 5, CONCRETE)
        part.ring(X0, Z0, X1, Z1, 1, 1, BLACK)
        for (x, z) in ((X0, Z0), (X1, Z0), (X0, Z1), (X1, Z1)):
            part.fill(x, 1, z, x, 5, z, BLACK)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, WHITE, y=4)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, CONCRETE, y=5)
        # a continuous ribbon of glass on the north and south walls and two big windows on the sides
        for x in range(X0 + 2, X1 - 1):
            for z in (Z0, Z1):
                part.put(x, 2, z, GLASS); part.put(x, 3, z, GLASS)
        for z in (4, 5, 10, 11):
            part.put(X0, 2, z, GLASS); part.put(X0, 3, z, GLASS); part.put(X1, 2, z, GLASS); part.put(X1, 3, z, GLASS)

    g = b.new_part("ground", ground=True)
    plaza(g)
    shell(g)
    g.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, "minecraft:polished_andesite")
    for (wx, wz, facing) in ((7, Z1, "south"), (7, Z0, "north")):                # the glass doors, front and back
        for dx, hinge in ((0, "left"), (1, "right")):
            for y in (1, 2):
                g.put(wx + dx, y, wz, f"modernlife:iron_glass_door[facing={facing},half={'lower' if y == 1 else 'upper'},hinge={hinge},open=false,powered=false]")
    for z in (7, 8):
        for (wx, facing) in ((X0, "west"), (X1, "east")):
            for y in (1, 2):
                g.put(wx, y, z, f"modernlife:iron_glass_door[facing={facing},half={'lower' if y == 1 else 'upper'},hinge={'left' if z == 7 else 'right'},open=false,powered=false]")
    # reception: a long desk with a screen, two sofas and a coffee table, a plant corner, a wall of cameras and screens behind the desk
    for x in range(5, 11):
        g.put(x, 1, 10, ml("dark_oak_kitchen_cabinet", facing="north"))
    g.put(6, 2, 10, ml("dual_monitor", facing="north")); g.put(9, 2, 10, ml("printer", facing="north"))
    g.put(7, 1, 11, k.seat("dark_oak", "north"))
    k.sofa(g, 4, 1, 4, "east", "south", "light_gray", 3)
    k.sofa(g, 9, 1, 4, "east", "south", "light_gray", 3)
    k.table(g, 6, 1, 5, 3, 1, "dark_oak", "coffee")
    for (x, z) in ((3, 12), (12, 12), (3, 3), (12, 3)):
        g.put(x, 1, z, "minecraft:flower_pot")
    g.put(12, 1, 8, ml("cctv_camera", facing="west")); g.put(3, 3, 3, ml("cctv_camera", facing="south"))
    for (x, z) in ((5, 7), (10, 7), (7, 12)):
        k.lamp_ceiling(g, x, z)
    f1 = b.new_part("floor1", floor=1)
    f1.floor(0, 0, 15, 15, "air"); shell(f1)
    f2 = b.new_part("floor2", floor=2)
    f2.floor(0, 0, 15, 15, "air"); shell(f2)
    for part in (f1, f2):
        for x in (5, 8, 11):                                                   # open-plan desks, rows facing each other
            for z in (4, 10):
                desk(part, x, z, "south" if z == 4 else "north")
        part.put(13, 1, 6, ml("printer", facing="west")); part.put(13, 1, 7, ml("photocopier", facing="west"))
        part.put(3, 1, 13, ml("modern_oak_bookshelf", facing="east")); part.put(4, 1, 13, ml("modern_oak_bookshelf", facing="north"))
        for (x, z) in ((6, 7), (10, 7), (8, 12)):
            k.lamp_ceiling(part, x, z)
    # the first floor has a glazed meeting room in the north-east corner (z 2..3 is the north wall's own row)
    f1.fill(9, 1, 6, 13, 3, 6, CLEAR)
    f1.fill(9, 1, 3, 9, 3, 6, CLEAR)
    for (x, z, facing) in ((11, 3, "south"), (11, 5, "north"), (10, 4, "east"), (12, 4, "west")):
        f1.put(x, 1, z, k.seat("dark_oak", facing))
    f1.put(11, 1, 4, ml("dark_oak_round_table", facing="north"))
    # the second floor has a server room in the north-east corner behind a locked glass door, racks of receivers and a screen
    f2.fill(9, 1, 6, 13, 3, 6, CONCRETE)
    f2.fill(9, 1, 3, 9, 3, 6, CONCRETE)
    f2.door(9, 1, 5, "west", "iron_door")
    for x in (10, 11, 12):
        f2.put(x, 1, 3, ml("power_receiver", facing="south", powered=False))
    f2.put(12, 1, 5, ml("dual_monitor", facing="south")); f2.put(12, 1, 4, ml("dark_oak_small_table", type="single"))
    # stairs: ground -> floor 1 at the west wall climbing north; floor 1 -> floor 2 at the east wall climbing south
    k.stair_run(g, f1, 2, 12, "north")
    k.stair_run(f1, f2, 13, 3, "south")
    t = b.new_part("top", top=True)
    roof_terrace(t, X0, Z0, X1, Z1)
    b.spots += [Spot("door", 7, 1, Z1, "south", "front door"), Spot("door", 7, 1, Z0, "north", "back door"), Spot("counter", 7, 1, 10, "north", "reception"),
                Spot("desk", 5, 7, 4, "south", "desk"), Spot("desk", 8, 7, 4, "south", "desk"), Spot("desk", 11, 7, 10, "north", "desk"), Spot("stash", 11, 13, 3, "south", "server room")]
    return b


# ---------------------------------------------------------------------------------------------------- the club

def club() -> Building:
    """A club: blacked-out walls, a dance floor, a bar and a DJ booth below; a VIP lounge and a back office above, the office behind a bookcase that is a door in all but name."""
    p = Palette("bw_club")
    b = Building("bw_club", p, use="club", city=CITY, min_floors=2, max_floors=2, filler=BLACK, security="club",
                 notes="A club: dance floor, bar and DJ booth below, a VIP lounge and a hidden back office above.")
    X0, Z0, X1, Z1 = 1, 1, 14, 14

    def shell(part: Part) -> None:
        part.floor(X0, Z0, X1, Z1, BLACK)
        part.ring(X0, Z0, X1, Z1, 1, 5, BLACK)
        part.ring(X0, Z0, X1, Z1, 1, 1, "minecraft:polished_blackstone_bricks")
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, BLACK, y=4)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, BLACK, y=5)

    g = b.new_part("ground", ground=True)
    plaza(g)
    shell(g)
    # the door: a steel double door in the south wall under a sea-lantern lintel, a second one at the north wall
    for (wx, wz, facing) in ((7, Z1, "south"), (7, Z0, "north")):
        for dx, hinge in ((0, "left"), (1, "right")):
            g.door(wx + dx, 1, wz, facing, "iron_door", hinge)
        g.put(wx, 3, wz, LAMP); g.put(wx + 1, 3, wz, LAMP)
    # the dance floor: a checkerboard of lit and dark tiles in the middle (x 4..11, z 4..9)
    for x in range(4, 12):
        for z in range(4, 10):
            g.put(x, 0, z, LAMP if (x + z) % 2 == 0 else "minecraft:cyan_concrete")
    # the DJ booth a step in from the north wall: a turntable, two amplifiers, a screen. It stood against the wall, right behind the back door, and a person who came in by that door
    # (an advanced burglary run through a real client) was stopped in the doorway by the turntable; the row behind it is now a way round to the room
    g.put(7, 1, 3, ml("turntable", facing="south", powered=False)); g.put(8, 1, 3, ml("oak_small_table", type="single"))
    g.put(5, 1, 3, ml("guitar_amplifier", facing="south")); g.put(10, 1, 3, ml("guitar_amplifier", facing="south"))
    g.put(7, 2, 3, ml("dual_monitor", facing="south"))
    # the bar along the east wall, bottles on shelves behind it, seats in front of it
    for z in range(4, 12):
        g.put(12, 1, z, ml("dark_oak_kitchen_cabinet", facing="west"))
        if z in (5, 8, 10):
            g.put(13, 1, z, ml("dark_oak_wall_shelf", facing="west"))
    g.put(12, 2, 6, ml("coffee_mug", facing="west")); g.put(12, 2, 9, ml("coffee_mug", facing="west"))
    for z in (5, 7, 9, 11):
        g.put(11, 1, z, k.seat("dark_oak", "east"))
    # booths in the south-west: sofas round a table
    k.sofa(g, 3, 1, 12, "east", "north", "red", 3)
    g.put(4, 1, 11, ml("dark_oak_small_table", type="single"))
    k.sofa(g, 3, 1, 4, "south", "east", "red", 3)
    # lights: froglights set flush into the ceiling over the floor, bar and booths (a redstone lamp marked lit has no power here and a photograph found the room almost black),
    # and lit rods as neon tubes in the corners and either side of the DJ booth
    for (x, z) in ((3, 3), (6, 3), (9, 3), (12, 3), (4, 6), (7, 6), (10, 6), (13, 6), (4, 9), (7, 9), (10, 9), (13, 9), (3, 12), (6, 12), (9, 12), (12, 12)):
        g.put(x, 4, z, "minecraft:pearlescent_froglight[axis=y]" if (x + z) % 2 else "minecraft:verdant_froglight[axis=y]")
    for (x, z) in ((2, 2), (13, 2), (2, 13), (13, 13), (6, 3), (9, 3)):
        if (x, z) == (2, 13):
            continue                                                            # the foot of the stair
        for y in (1, 2, 3):
            g.put(x, y, z, "minecraft:end_rod[facing=up]")
    # the stair up to the VIP lounge in the north-west corner
    f1 = b.new_part("floor1", floor=1)
    f1.floor(0, 0, 15, 15, "air")
    shell(f1)
    k.stair_run(g, f1, 2, 12, "north")
    # the VIP lounge: sofas, low tables, a smaller bar, a view down onto the dance floor through a glass front (z 7)
    k.sofa(f1, 4, 1, 3, "east", "south", "black", 4)
    k.sofa(f1, 4, 1, 8, "east", "north", "black", 4)
    k.table(f1, 5, 1, 5, 2, 1, "dark_oak", "coffee")
    k.counter_run(f1, 12, 1, 3, "south", "west", ["cabinet", "cabinet", "cabinet", "sink"], "dark_oak")
    for z in (3, 4, 5, 6):
        f1.put(11, 1, z, k.seat("dark_oak", "east"))
    # the back office behind a bookcase wall (x 8..13, z 9..13): a desk and a safe-like barrel stash
    f1.fill(8, 1, 9, 13, 3, 9, BLACK)
    f1.fill(8, 1, 9, 8, 3, 13, BLACK)
    f1.put(10, 1, 9, ml("modern_oak_bookshelf", facing="north")); f1.put(10, 2, 9, ml("modern_oak_bookshelf", facing="north"))
    f1.door(11, 1, 9, "north", "iron_door")
    f1.put(12, 1, 12, ml("dark_oak_small_table", type="single")); f1.put(12, 2, 12, ml("dual_monitor", facing="west")); f1.put(11, 1, 12, k.seat("dark_oak", "east"))
    for (x, z) in ((13, 11), (13, 12), (13, 13), (9, 13)):
        f1.put(x, 1, z, "minecraft:barrel[facing=up,open=false]")
    f1.put(9, 1, 11, ml("modern_oak_bookshelf", facing="east"))
    for (x, z) in ((5, 4), (8, 4), (5, 7), (8, 7), (12, 5), (11, 11), (13, 13)):
        f1.put(x, 4, z, "minecraft:pearlescent_froglight[axis=y]" if (x + z) % 2 else "minecraft:ochre_froglight[axis=y]")
    t = b.new_part("top", top=True)
    t.ring(X0, Z0, X1, Z1, 0, 0, BLACK)
    t.put(7, 0, 14, LAMP); t.put(8, 0, 14, LAMP)
    t.put(3, 0, 3, ml("air_conditioner", facing="south")); t.put(12, 0, 12, ml("air_conditioner", facing="north"))
    b.spots += [Spot("door", 7, 1, Z1, "south", "front door"), Spot("door", 7, 1, Z0, "north", "back door"), Spot("counter", 12, 1, 8, "west", "bar"), Spot("desk", 12, 7, 12, "west", "back office desk"),
                Spot("stash", 13, 7, 12, "west", "back office"), Spot("seat", 3, 1, 12, "north", "booth"),
                Spot("fence", 12, 7, 12, "west", "after hours the back office buys stolen goods (D-125)")]
    return b


# ---------------------------------------------------------------------------------------------------- the corner store

def store() -> Building:
    """A corner store: a glass front, two aisles of shelving, chilled cabinets on the back wall and the till by the door; a modern flat to let above, up a stair with its own door."""
    p = Palette("bw_store")
    b = Building("bw_store", p, use="shop", city=CITY, min_floors=2, max_floors=2, filler=BLACK, security="storefront",
                 notes="A corner store: aisles, chilled cabinets and a till below, a flat to let above.")
    X0, Z0, X1, Z1 = 1, 1, 14, 14

    def shell(part: Part) -> None:
        part.floor(X0, Z0, X1, Z1, CONCRETE)
        part.ring(X0, Z0, X1, Z1, 1, 5, CONCRETE)
        part.ring(X0, Z0, X1, Z1, 1, 1, BLACK)
        for (x, z) in ((X0, Z0), (X1, Z0), (X0, Z1), (X1, Z1)):
            part.fill(x, 1, z, x, 5, z, BLACK)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, WHITE, y=4)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, CONCRETE, y=5)

    g = b.new_part("ground", ground=True)
    plaza(g)
    shell(g)
    g.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, "minecraft:smooth_quartz")
    for x in range(X0 + 2, X1 - 1):                                            # the glass front
        if x not in (7, 8):
            g.put(x, 2, Z1, GLASS); g.put(x, 3, Z1, GLASS)
    for dx, hinge in ((0, "left"), (1, "right")):
        for y in (1, 2):
            g.put(7 + dx, y, Z1, f"modernlife:iron_glass_door[facing=south,half={'lower' if y == 1 else 'upper'},hinge={hinge},open=false,powered=false]")
    # chilled cabinets along the back wall, two aisles of shelving, a magazine rack
    for x in range(4, 12):
        k.fridge(g, x, 1, Z0 + 1, "south")
    # the aisles read bare in the October 23 photographs (a shelf over every other cabinet): now a shelf over each, a second shelf above, goods on the ends
    for z in (6, 9):
        for x in range(4, 9):
            g.put(x, 1, z, ml("dark_oak_kitchen_cabinet", facing="north" if z == 6 else "south"))
            g.put(x, 2, z, ml("double_dark_oak_wall_shelf", facing="north" if z == 6 else "south"))
            g.put(x, 3, z, ml("dark_oak_wall_shelf", facing="north" if z == 6 else "south"))
        g.put(3, 1, z, "minecraft:barrel[facing=up,open=true]"); g.put(9, 1, z, "minecraft:barrel[facing=up,open=true]")
    # a produce stand in the window by the door: open crates, melons and pumpkins
    for x in (4, 5):
        g.put(x, 1, 12, "minecraft:barrel[facing=up,open=true]")
    g.put(6, 1, 12, "minecraft:melon"); g.put(4, 1, 13, "minecraft:pumpkin"); g.put(5, 1, 13, "minecraft:melon")
    g.put(9, 1, 13, ml("trash_can", facing="north")); g.put(7, 1, 13, ml("floor_mat", facing="north")); g.put(8, 1, 13, ml("floor_mat", facing="north"))
    # the till by the door: a counter along x = 11 from z 9 to 12, the clerk behind it at x = 12; a receipt printer, a mug, a plant, and the camera screen behind
    for z in range(9, 13):
        g.put(11, 1, z, ml("dark_oak_kitchen_cabinet", facing="west"))
    g.put(11, 2, 10, ml("printer", facing="west")); g.put(11, 2, 11, ml("coffee_mug", facing="west")); g.put(11, 2, 12, "minecraft:flower_pot")
    g.put(13, 2, 10, ml("cctv_screen", facing="west"))
    g.put(12, 1, 8, k.seat("dark_oak", "south"))
    g.put(12, 3, 3, ml("cctv_camera", facing="south"))
    for (x, z) in ((5, 4), (9, 4), (6, 8), (6, 11), (10, 11)):
        g.put(x, 4, z, LAMP)                                                   # lights flush in the ceiling
    # the stair at the west wall, climbing north from z 12 to the landing at z 7 on the floor above
    u = b.new_part("floor", floor=1)
    u.floor(0, 0, 15, 15, "air")
    shell(u)
    for x in range(X0 + 2, X1 - 1, 3):
        u.put(x, 2, Z1, GLASS); u.put(x, 3, Z1, GLASS); u.put(x, 2, Z0, GLASS); u.put(x, 3, Z0, GLASS)
    k.stair_run(g, u, 2, 12, "north")
    for (x, z) in ((3, 8), (3, 9), (2, 10)):
        u.put(x, 1, z, "minecraft:iron_bars")
    u.fill(4, 1, Z0 + 1, 4, 3, Z1 - 1, WHITE)                                  # the flat's west wall; the landing (x 2..3, z 2..7) is outside it
    u.put(4, 1, 5, "modernlife:modern_white_door[facing=west,half=lower,hinge=left,open=false,powered=false]")
    u.put(4, 2, 5, "modernlife:modern_white_door[facing=west,half=upper,hinge=left,open=false,powered=false]")
    # the flat (x 5..13, z 2..13): a bedroom to the north, an open kitchen and living room to the south
    u.fill(5, 1, 6, 13, 3, 6, WHITE)
    u.put(9, 1, 6, "modernlife:modern_white_door[facing=south,half=lower,hinge=left,open=false,powered=false]")
    u.put(9, 2, 6, "modernlife:modern_white_door[facing=south,half=upper,hinge=left,open=false,powered=false]")
    k.bed(u, 11, 1, 3, "east", "cyan"); u.put(13, 1, 4, ml("dark_oak_night_stand", facing="west")); u.put(7, 1, 2, ml("modern_dark_oak_bookshelf", facing="south"))
    u.put(6, 1, 4, "minecraft:potted_bamboo")
    k.counter_run(u, 13, 1, 8, "south", "west", ["fridge", "drawers", "stove", "sink", "microwave"], "dark_oak")
    k.table(u, 9, 1, 9, 2, 1, "dark_oak", "small"); k.chair(u, 9, 1, 8, "south", "dark_oak"); k.chair(u, 10, 1, 10, "north", "dark_oak")
    k.sofa(u, 6, 1, 12, "east", "north", "white", 3); u.put(7, 1, 10, ml("dark_oak_small_table", type="single")); u.put(7, 2, 10, ml("flatscreen_tv", facing="south"))
    for (x, z) in ((9, 4), (9, 11)):
        u.put(x, 4, z, LAMP)
    t = b.new_part("top", top=True)
    roof_terrace(t, X0, Z0, X1, Z1)
    b.spots += [Spot("door", 7, 1, Z1, "south", "the shop door"), Spot("counter", 12, 1, 11, "west", "the till"), Spot("shelf", 6, 1, 7, "north", "the aisles"),
                Spot("bed", 11, 7, 4, "north", "flat bed"), Spot("stove", 12, 7, 10, "east", "flat kitchen"), Spot("seat", 9, 7, 8, "south", "flat table"),
                Spot("stash", 12, 1, 13, "west", "under the till")]
    b.units.append(k.Unit("flat", "Flat", (5, 7, 2, 13, 9, 13)))
    return b


def buildings() -> list[Building]:
    return [apartments(), office(), club(), store()]


def zones() -> list[tuple[float, list[tuple[str, int]]]]:
    """Offices and clubs in the dense core by the water, apartment blocks toward the edge."""
    return [
        (0.35, [("bw_office", 4), ("bw_club", 2), ("bw_apartments", 2)]),
        (0.70, [("bw_apartments", 4), ("bw_office", 2), ("bw_club", 1), ("bw_store", 2)]),
        (1.00, [("bw_apartments", 3), ("bw_office", 1), ("bw_club", 1), ("bw_store", 1)]),
    ]


def citystyle(all_buildings: list[Building]) -> dict:
    return {
        "inherit": "lostcities:citystyle_standard",
        "buildingsettings": {"buildingchance": 0.84, "maxfloors": 5},
        "parkblocks": {"parkchance": 0.03},
        "streetblocks": {"frontchance": 0.0},
    }
