#!/usr/bin/env python3
"""The roadside places (D-138): original buildings beside the three inter-city roads, each with something to do there.

    python scripts/roadside_places.py           write every generated file
    python scripts/roadside_places.py --check   fail when a generated file is missing or differs (CI)

For every place this writes four district variants (``districts/rs_<id>_<n|e|s|w>.json``, one per direction its front can face; ``RoadsidePlaces`` builds the one whose front looks at
the road) and one catalog entry (``roadside_places/<id>.json``: which road, how far along it, which variants). It also writes the shops' counters and stock lines, the salvage yard's
regrowing site, the loot tables of the places' one-time containers, and the motel's and farmhouse's listings in the property ladder (by id, so a re-run replaces them).

Every plan is drawn for this project. See scripts/roadside_kit.py for the canvas and the conventions.
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from roadside_kit import Place, ml, block, OPPOSITE, SUFFIX  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core" / "src" / "main" / "resources" / "data" / "underground_city"

ASPHALT = "minecraft:gray_concrete"
STRIPE = "minecraft:white_concrete"


def roadside_sign(p: Place, x: int, z: int, lines_top: list[str], lines_low: list[str] | None = None, wood: str = "spruce") -> None:
    """A sign board on two posts at the road's edge, facing the road (north)."""
    p.put(x, 0, z, f"minecraft:{wood}_fence"); p.put(x + 2, 0, z, f"minecraft:{wood}_fence")
    p.fill(x, 1, z, x + 2, 2, z, f"minecraft:{wood}_planks")
    p.sign(x + 1, 2, z - 1, f"minecraft:{wood}_wall_sign[facing=north]", lines_top, color="white", glow=True)
    if lines_low:
        p.sign(x + 1, 1, z - 1, f"minecraft:{wood}_wall_sign[facing=north]", lines_low, color="black")


# ==================================================================================================== Ironhaven-Valewood
def crossroads_diner() -> Place:
    p = Place("crossroads_diner", "Crossroads Diner", 30, 26, 8,
              note="A roadside diner with a fuel forecourt (the pumps are dry) on the Ironhaven-Valewood road: a cook who sells hot food and a clerk who sells travel supplies, around the clock.")
    p.floor(0, 0, 29, 12, ASPHALT)
    for x in (16, 19, 22, 25, 28):
        p.floor(x, 2, x, 6, STRIPE)
    # the fuel canopy and its two pump islands
    for (x, z) in [(2, 3), (13, 3), (2, 9), (13, 9)]:
        p.fill(x, 0, z, x, 3, z, "minecraft:white_concrete")
    p.fill(2, 4, 3, 13, 4, 9, "minecraft:white_concrete")
    p.walls(2, 3, 13, 9, 4, 4, "minecraft:red_concrete")
    for (x, z) in [(5, 6), (10, 6), (7, 4), (7, 8)]:
        p.put(x, 3, z, "minecraft:lantern[hanging=true]")
    for x0 in (4, 9):
        p.fill(x0, 0, 6, x0 + 2, 0, 6, "minecraft:smooth_stone_slab[type=bottom]")
        p.put(x0 + 1, 0, 6, "minecraft:red_concrete")
        p.put(x0 + 1, 1, 6, "minecraft:white_concrete")
        p.put(x0 + 1, 1, 5, "minecraft:tripwire_hook[facing=north,attached=false,powered=false]")
    p.sign(5, 1, 7, "minecraft:spruce_wall_sign[facing=south]", ["PUMPS DRY", "Tanker due", "Tuesday"], color="red")
    p.sign(10, 1, 7, "minecraft:spruce_wall_sign[facing=south]", ["SORRY", "Diner open", "inside"], color="black")
    roadside_sign(p, 18, 1, ["CROSSROADS", "DINER", "OPEN ALL HOURS"], ["Hot food", "Travel goods"])

    # the diner: x 14..28, z 13..24
    x0, z0, x1, z1 = 14, 13, 28, 24
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            p.put(x, -1, z, "minecraft:white_concrete" if (x + z) % 2 == 0 else "minecraft:black_concrete")
    p.walls(x0, z0, x1, z1, 0, 3, "minecraft:white_concrete")
    p.walls(x0, z0, x1, z1, 4, 4, "minecraft:red_concrete")
    p.flat_roof(x0, z0, x1, z1, 5, "minecraft:smooth_stone", parapet="minecraft:smooth_stone_slab[type=bottom]")
    for x in list(range(15, 20)) + list(range(23, 28)):
        p.window(x, 1, z0, height=2)
    for z in range(15, 19):
        p.window(x0, 1, z, height=2)
        p.window(x1, 1, z, height=2)
    p.clear(21, 0, z0, 21, 1, z0)
    p.door(21, 0, z0, "north", "spruce_door")
    p.sign(21, 3, z0 - 1, "minecraft:spruce_wall_sign[facing=north]", ["DINER", "Come in"], color="red", glow=True)
    # booths along the front windows, the door in the middle
    for tx in (16, 26):
        p.post_table(tx, 0, 15, "spruce")
        p.stair_seat(tx - 1 if tx == 16 else tx + 1, 0, 15, "east" if tx == 16 else "west", "red_nether_brick_stairs")
        p.stair_seat(tx, 0, 16, "north", "red_nether_brick_stairs")
    for tx in (18, 24):
        p.post_table(tx, 0, 15, "spruce")
        p.stair_seat(tx, 0, 16, "north", "red_nether_brick_stairs")
    # the counter (z 19) with stools in front and two staff behind
    p.fill(16, 0, 19, 26, 0, 19, "minecraft:smooth_quartz")
    p.put(27, 0, 19, "minecraft:spruce_trapdoor[facing=north,half=top,open=true]")
    for x in (16, 18, 20, 22, 24, 26):
        p.put(x, 0, 18, "minecraft:spruce_fence")
        p.put(x, 1, 18, "minecraft:red_carpet")
    p.put(17, 1, 19, ml("coffee_mug", facing="north")); p.put(23, 1, 19, ml("coffee_mug", facing="south"))
    # kitchen at the back wall
    p.put(16, 0, 23, "minecraft:smoker[facing=north,lit=false]")
    p.put(17, 0, 23, "minecraft:furnace[facing=north,lit=false]")
    p.put(18, 0, 23, ml("stove", facing="north", open_door=False))
    p.put(19, 0, 23, ml("kitchen_sink", facing="north"))
    p.put(20, 0, 23, "minecraft:barrel[facing=up,open=false]")
    p.put(21, 0, 23, ml("refrigerator", facing="north", half="lower")); p.put(21, 1, 23, ml("refrigerator", facing="north", half="upper"))
    p.put(22, 0, 23, "minecraft:crafting_table")
    p.put(23, 0, 23, "minecraft:barrel[facing=up,open=false]")
    p.put(24, 0, 23, "minecraft:cauldron")
    # the restroom in the north-east corner of the back (x 25..27, z 21..23)
    p.fill(25, 0, 21, 25, 3, 23, "minecraft:white_concrete")
    p.fill(26, 0, 21, 27, 0, 21, "minecraft:white_concrete"); p.fill(26, 1, 21, 27, 3, 21, "minecraft:white_concrete")
    p.door(26, 0, 21, "north", "birch_door")
    p.put(27, 0, 23, ml("toilet", facing="north"))
    p.put(26, 0, 23, ml("kitchen_sink", facing="north"))
    p.put(26, 3, 22, "minecraft:lantern[hanging=true]")
    for x in (17, 21, 25):
        for z in (16, 21):
            p.put(x, 4, z, "minecraft:lantern[hanging=true]")
    p.put(27, 0, 14, "minecraft:jukebox")
    p.put(15, 0, 14, "minecraft:potted_azalea_bush"); p.put(15, 0, 18, "minecraft:potted_red_tulip")
    # picnic tables on the grass beside the forecourt
    p.put(1, 0, 18, ml("spruce_picnic_table", facing="east", half="back")); p.put(2, 0, 18, ml("spruce_picnic_table", facing="east", half="front"))
    p.put(1, 0, 22, ml("spruce_picnic_table", facing="east", half="back")); p.put(2, 0, 22, ml("spruce_picnic_table", facing="east", half="front"))
    p.put(5, 0, 24, ml("trash_can", facing="north"))
    for (x, z) in [(0, 12), (13, 12), (29, 12)]:
        p.lamp_post(x, z)
    p.npc(19, 0, 20, "shopkeeper", "Dot Mallory", "butcher", shop="roadside:crossroads_diner",
          lines=["Stew's hot, pie's yesterday's. Both are better than the road.",
                 "Truckers used to stop here every hour. Now it's walkers and the odd motorboat crowd.",
                 "If a tanker ever turns up, the pumps work. Until then, the coffee does."])
    p.npc(25, 0, 20, "shopkeeper", "Wes Tanner", "cartographer", shop="roadside:crossroads_supply",
          lines=["Torches, rope, a flask for the long bits. Valewood's four minutes that way, Ironhaven the other.",
                 "Walking the bridges at night? Take a lantern. The rail stops you falling, not tripping."])
    return p


def harrow_farm() -> Place:
    p = Place("harrow_farm", "Harrow Farm", 36, 32, 15,
              note="A working farm on the Ironhaven-Valewood road: a farm stand selling its produce and seed, fields, a red barn, and the old farmhouse, for sale.")
    # the farm stand at the road (x 2..9, z 1..4)
    for (x, z) in [(2, 1), (9, 1), (2, 4), (9, 4)]:
        p.fill(x, 0, z, x, 2, z, "minecraft:oak_fence")
    for x in range(2, 10):
        for z in range(1, 5):
            p.put(x, 3, z, "minecraft:red_wool" if x % 2 == 0 else "minecraft:white_wool")
    p.fill(3, 0, 2, 8, 0, 2, "minecraft:barrel[facing=up,open=false]")
    p.put(3, 1, 2, "minecraft:carved_pumpkin[facing=north]"); p.put(5, 1, 2, "minecraft:melon"); p.put(7, 1, 2, "minecraft:hay_block[axis=x]")
    p.put(8, 1, 2, "minecraft:composter[level=0]")
    roadside_sign(p, 11, 1, ["HARROW FARM", "FRESH PRODUCE"], ["Seed and feed", "House for sale"], wood="oak")
    # the lane from the road to the farmhouse
    p.floor(17, 0, 19, 18, "minecraft:dirt_path")
    p.floor(17, 0, 18, 5, "minecraft:dirt_path")
    # fields, fenced, irrigated (x 1..16, z 6..31)
    p.walls(1, 6, 16, 31, 0, 0, "minecraft:oak_fence")
    p.put(8, 0, 6, "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]")
    crops = {range(2, 6): "minecraft:wheat[age=7]", range(7, 11): "minecraft:carrots[age=7]", range(12, 16): "minecraft:potatoes[age=7]"}
    for xs, crop in crops.items():
        for x in xs:
            for z in range(7, 31):
                p.put(x, -1, z, "minecraft:farmland[moisture=7]")
                p.put(x, 0, z, crop)
    for x in (6, 11):
        p.fill(x, -1, 7, x, -1, 30, "minecraft:water")
        p.fill(x, -2, 7, x, -2, 30, "minecraft:clay")
    # a scarecrow at the field's corner
    p.put(17, 0, 28, "minecraft:oak_fence"); p.put(17, 1, 28, "minecraft:hay_block[axis=y]"); p.put(17, 2, 28, "minecraft:carved_pumpkin[facing=north]")

    # the farmhouse: x 20..32, z 6..16, two floors, a porch on the road side
    x0, z0, x1, z1 = 20, 6, 32, 16
    p.floor(x0, z0, x1, z1, "minecraft:cobblestone")
    p.walls(x0, z0, x1, z1, 0, 3, "minecraft:oak_planks")
    p.walls(x0, z0, x1, z1, 5, 7, "minecraft:white_terracotta")
    p.walls(x0, z0, x1, z1, 4, 4, "minecraft:stripped_oak_log[axis=x]")
    for (x, z) in [(x0, z0), (x1, z0), (x0, z1), (x1, z1)]:
        p.fill(x, 0, z, x, 7, z, "minecraft:stripped_oak_log[axis=y]")
    p.fill(x0 + 1, 4, z0 + 1, x1 - 1, 4, z1 - 1, "minecraft:spruce_planks")
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            p.put(x, -1, z, "minecraft:spruce_planks")
    ridge = p.gable_roof(x0, z0, x1, z1, 8, "minecraft:dark_oak_stairs", "minecraft:dark_oak_slab[type=bottom]", along="x", overhang=1, fill_block="minecraft:white_terracotta")
    p.fill(x0 + 1, 8, z0 + 1, x1 - 1, 8, z1 - 1, "minecraft:spruce_planks")      # the bedrooms' ceiling, under the roof
    # the chimney
    p.fill(31, 0, 11, 31, ridge + 1, 11, "minecraft:bricks")
    for (x, z) in [(23, z0), (29, z0), (23, z1), (29, z1)]:
        p.window(x, 1, z, height=2); p.window(x, 5, z, height=2)
    for z in (9, 13):
        p.window(x0, 1, z, height=2); p.window(x0, 5, z, height=2)
        p.window(x1, 5, z, height=2)
    p.door(26, 0, z0, "north", "spruce_door")
    # the porch
    p.fill(22, -1, 3, 30, -1, 5, "minecraft:spruce_planks")
    for x in (22, 30):
        p.fill(x, 0, 3, x, 3, 3, "minecraft:oak_fence")
    p.fill(22, 4, 3, 30, 4, 5, "minecraft:spruce_slab[type=bottom]")
    p.stair_seat(24, 0, 4, "north", "oak_stairs"); p.stair_seat(28, 0, 4, "north", "oak_stairs")
    p.put(27, 0, 3, "minecraft:potted_oxeye_daisy"); p.put(25, 0, 3, "minecraft:potted_poppy")
    # ground floor: kitchen west, living room east, stairs at the back
    p.put(21, 0, 7, "minecraft:smoker[facing=south,lit=false]"); p.put(22, 0, 7, "minecraft:furnace[facing=south,lit=false]")
    p.put(23, 0, 7, ml("oak_kitchen_cabinet", facing="south")); p.put(24, 0, 7, ml("kitchen_sink", facing="south"))
    p.put(21, 0, 8, "minecraft:barrel[facing=up,open=false]"); p.put(21, 0, 9, "minecraft:crafting_table")
    p.put(21, 0, 10, ml("refrigerator", facing="east", half="lower")); p.put(21, 1, 10, ml("refrigerator", facing="east", half="upper"))
    p.post_table(23, 0, 11, "oak"); p.post_table(24, 0, 11, "oak")
    p.stair_seat(22, 0, 11, "east", "oak_stairs"); p.stair_seat(25, 0, 11, "west", "oak_stairs")
    p.fill(30, 0, 10, 32, 0, 12, "minecraft:bricks"); p.put(31, 0, 11, "minecraft:campfire[facing=west,lit=true,signal_fire=false,waterlogged=false]")
    p.fill(30, 1, 10, 30, 3, 10, "minecraft:bricks"); p.fill(30, 1, 12, 30, 3, 12, "minecraft:bricks"); p.fill(30, 2, 11, 30, 3, 11, "minecraft:bricks")
    p.put(28, 0, 9, ml("brown_sofa", facing="south", type="left")); p.put(29, 0, 9, ml("brown_sofa", facing="south", type="right"))
    p.fill(27, -1, 10, 29, -1, 12, "minecraft:spruce_planks")
    for x in range(27, 30):
        for z in range(10, 13):
            p.put(x, 0, z, "minecraft:red_carpet")
    p.put(27, 0, 7, "minecraft:bookshelf"); p.put(28, 0, 7, "minecraft:bookshelf")
    for i, x in enumerate(range(31, 27, -1)):
        p.put(x, i, 15, "minecraft:oak_stairs[facing=west,half=bottom]")
        p.clear(x, 4, 15, x, 4, 15)
    p.put(23, 3, 9, "minecraft:lantern[hanging=true]"); p.put(28, 3, 11, "minecraft:lantern[hanging=true]")
    p.terminal(24, 0, 15, "north")
    # upstairs: two bedrooms
    p.fill(26, 5, 7, 26, 7, 13, "minecraft:oak_planks")
    p.door(26, 5, 10, "east", "oak_door")
    p.bed(22, 5, 9, "west", "white"); p.bed(22, 5, 12, "west", "white")
    p.put(21, 5, 7, "minecraft:barrel[facing=up,open=false]"); p.put(21, 5, 14, "minecraft:chest[facing=east,type=single]")
    p.bed(30, 5, 8, "east", "green")
    p.put(28, 5, 7, ml("oak_night_stand", facing="south"))
    p.put(31, 5, 13, "minecraft:barrel[facing=up,open=false]")
    p.put(23, 7, 10, "minecraft:lantern[hanging=true]"); p.put(29, 7, 10, "minecraft:lantern[hanging=true]")
    p.residence("farmhouse", "Harrow Farmhouse", "house", 1, (21, 0, 7), (31, 7, 15))

    # the barn: x 20..33, z 19..30
    bx0, bz0, bx1, bz1 = 20, 19, 33, 30
    p.floor(bx0, bz0, bx1, bz1, "minecraft:spruce_planks")
    p.walls(bx0, bz0, bx1, bz1, 0, 4, "minecraft:red_terracotta")
    for (x, z) in [(bx0, bz0), (bx1, bz0), (bx0, bz1), (bx1, bz1)]:
        p.fill(x, 0, z, x, 4, z, "minecraft:dark_oak_log[axis=y]")
    p.walls(bx0, bz0, bx1, bz1, 5, 5, "minecraft:dark_oak_planks")
    p.gable_roof(bx0, bz0, bx1, bz1, 6, "minecraft:dark_oak_stairs", "minecraft:dark_oak_slab[type=bottom]", along="z", overhang=1, fill_block="minecraft:red_terracotta")
    p.clear(25, 0, bz0, 28, 3, bz0)
    p.fill(24, 0, bz0, 24, 4, bz0, "minecraft:white_terracotta"); p.fill(29, 0, bz0, 29, 4, bz0, "minecraft:white_terracotta")
    p.fill(25, 4, bz0, 28, 4, bz0, "minecraft:white_terracotta")
    for (x, z) in [(22, 25), (31, 25)]:
        p.window(x, 2, bz1, height=1)
    p.walls(21, 26, 25, 29, 0, 0, "minecraft:oak_fence"); p.put(23, 0, 26, "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]")
    p.walls(28, 26, 32, 29, 0, 0, "minecraft:oak_fence"); p.put(30, 0, 26, "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]")
    for (x, z) in [(22, 27), (23, 28), (29, 27), (31, 28)]:
        p.put(x, 0, z, "minecraft:hay_block[axis=y]")
    p.fill(21, 0, 20, 23, 1, 21, "minecraft:hay_block[axis=x]")
    p.fill(30, 0, 20, 32, 2, 21, "minecraft:hay_block[axis=z]")
    p.fill(21, 3, 22, 32, 3, 25, "minecraft:spruce_slab[type=top]")   # the hayloft
    p.fill(22, 4, 23, 24, 4, 24, "minecraft:hay_block[axis=y]")
    p.put(27, 2, 24, "minecraft:lantern[hanging=true]")
    p.loot(26, 0, 29, "minecraft:barrel[facing=up,open=false]", "underground_city:roadside/farm_store")
    p.loot(27, 0, 29, "minecraft:barrel[facing=up,open=false]", "underground_city:roadside/farm_store")
    p.safe(32, 0, 22, "west", "apartment_common")
    p.npc(5, 0, 3, "shopkeeper", "Hester Harrow", "farmer", shop="roadside:harrow_farmstand",
          lines=["Carrots, potatoes, wheat. Seed if you're growing your own. Valewood pays me less than you will.",
                 "The farmhouse? My father's. Too big for one, so it's for sale. Ask at any terminal's Home page.",
                 "Mind the fields. Anything you trample, I replant."])
    return p


# ==================================================================================================== Ironhaven-Blackwater
SCRAP = "underground_city:scrap_heap"


def kessler_salvage() -> Place:
    p = Place("kessler_salvage", "Kessler Salvage", 34, 30, 12, ground="minecraft:coarse_dirt",
              note="A scrapyard on the Ironhaven-Blackwater road: scrap heaps a person can strip for metal and parts (they fill again in active play time), a foreman's counter, public workbenches, and a cash box.")
    rnd = random.Random(1701)
    for x in range(34):
        for z in range(30):
            if rnd.random() < 0.35:
                p.put(x, -1, z, "minecraft:gravel")
    # the fence, with the gate at the road
    for x in range(34):
        for z in (0, 29):
            p.put(x, 0, z, "minecraft:stone_bricks"); p.fill(x, 1, z, x, 2, z, "minecraft:iron_bars")
    for z in range(30):
        for x in (0, 33):
            p.put(x, 0, z, "minecraft:stone_bricks"); p.fill(x, 1, z, x, 2, z, "minecraft:iron_bars")
    p.clear(13, 0, 0, 19, 2, 0)
    p.floor(13, 0, 19, 4, "minecraft:gravel")
    roadside_sign(p, 21, 1, ["KESSLER", "SALVAGE"], ["Scrap bought", "Parts sold"])
    # the office (x 2..9, z 2..8)
    ox0, oz0, ox1, oz1 = 2, 2, 9, 8
    p.floor(ox0, oz0, ox1, oz1, "minecraft:spruce_planks")
    p.walls(ox0, oz0, ox1, oz1, 0, 2, "minecraft:light_gray_concrete")
    p.walls(ox0, oz0, ox1, oz1, 3, 3, "minecraft:gray_concrete")
    p.fill(ox0, 4, oz0, ox1, 4, oz1, "minecraft:smooth_stone_slab[type=bottom]")
    p.door(9, 0, 3, "east", "spruce_door")
    p.window(4, 1, oz0, height=1); p.window(7, 1, oz0, height=1); p.window(9, 1, 6, height=1)
    p.fill(3, 0, 5, 7, 0, 5, "minecraft:spruce_planks")        # the desk
    p.put(8, 0, 5, "minecraft:spruce_trapdoor[facing=east,half=top,open=true]")
    p.put(4, 1, 5, ml("old_tv", facing="south"))
    p.put(3, 0, 7, "minecraft:furnace[facing=east,lit=false]"); p.put(3, 0, 6, "minecraft:barrel[facing=up,open=false]")
    p.safe(7, 0, 7, "north", "industrial")
    p.put(5, 3, 4, "minecraft:lantern[hanging=true]")
    p.sign(6, 2, 3, "minecraft:spruce_wall_sign[facing=south]", ["SCRAP:", "strip a heap,", "keep the metal"], color="black")
    p.npc(5, 0, 6, "shopkeeper", "Rudi Kessler", "toolsmith", shop="roadside:kessler_salvage",
          lines=["Heaps are free to strip. Bring a pick, take what you find. They fill up again while the yard's busy.",
                 "I sell the clean stuff: reclaimed iron, copper wire, chain. Cheaper than Ironhaven and nobody asks.",
                 "Blackwater pays for circuit boards. Ironhaven pays for iron. I pay for nothing. That's why I'm still here."])
    # the scrap heaps (the regrowing veins)
    heaps = [(14, 10, 3, 2), (24, 8, 2, 2), (13, 20, 3, 2), (24, 17, 2, 2)]
    for (hx, hz, w, d) in heaps:
        for x in range(hx, hx + w + 1):
            for z in range(hz, hz + d + 1):
                p.put(x, 0, z, SCRAP)
        p.put(hx + 1, 1, hz + 1, SCRAP); p.put(hx + 2, 1, hz + 1, SCRAP); p.put(hx + 1, 1, hz + 2, SCRAP)
    # wrecks: a car, and two stacked
    def car(cx, cz, y, body, wheels=True):
        p.fill(cx, y, cz, cx + 3, y, cz + 1, body)
        p.fill(cx + 1, y + 1, cz, cx + 2, y + 1, cz + 1, "minecraft:glass")
        for (x, z) in ([(cx, cz - 1), (cx + 3, cz - 1), (cx, cz + 2), (cx + 3, cz + 2)] if wheels else []):
            if 0 < z < 29:
                p.put(x, y, z, "minecraft:black_concrete")
    car(20, 23, 0, "minecraft:orange_terracotta")
    car(26, 24, 0, "minecraft:light_blue_terracotta"); car(26, 24, 2, "minecraft:white_terracotta", wheels=False)
    # the crane over the yard
    p.fill(31, 0, 13, 31, 10, 13, "minecraft:yellow_concrete")
    p.fill(19, 11, 13, 31, 11, 13, "minecraft:yellow_concrete")
    p.fill(21, 5, 13, 21, 10, 13, "minecraft:chain[axis=y]")
    p.put(21, 4, 13, "minecraft:iron_block")
    # the workbench shelter (x 2..9, z 22..27): public tools
    for (x, z) in [(2, 22), (9, 22), (2, 27), (9, 27)]:
        p.fill(x, 0, z, x, 2, z, "minecraft:spruce_fence")
    p.fill(2, 3, 22, 9, 3, 27, "minecraft:spruce_slab[type=bottom]")
    p.floor(2, 22, 9, 27, "minecraft:smooth_stone")
    p.put(3, 0, 24, "minecraft:anvil[facing=north]"); p.put(4, 0, 24, "minecraft:grindstone[face=floor,facing=north]")
    p.put(5, 0, 24, "minecraft:smithing_table"); p.put(6, 0, 24, "minecraft:crafting_table"); p.put(7, 0, 24, "minecraft:stonecutter[facing=north]")
    p.put(8, 0, 24, "minecraft:blast_furnace[facing=north,lit=false]")
    p.put(5, 2, 25, "minecraft:lantern[hanging=true]")
    # junk piles and one-time bins
    for (x, z, spec) in [(28, 3, "minecraft:cauldron"), (29, 3, "minecraft:chain[axis=x]"), (28, 4, "minecraft:iron_bars"), (12, 15, "minecraft:anvil[facing=west]"),
                         (17, 26, "minecraft:weathered_cut_copper"), (18, 26, "minecraft:oxidized_cut_copper"), (17, 27, "minecraft:hopper[enabled=true,facing=down]")]:
        p.put(x, 0, z, spec)
    for (x, z) in [(29, 6), (11, 26), (30, 20)]:
        p.loot(x, 0, z, "minecraft:barrel[facing=up,open=false]", "underground_city:roadside/scrap_bin")
    for (x, z) in [(1, 12), (32, 12), (16, 1)]:
        p.lamp_post(x, z)
    return p


def northgate_depot() -> Place:
    p = Place("northgate_depot", "Northgate Freight Depot", 32, 28, 10, ground="minecraft:gray_concrete",
              note="A freight depot on the Ironhaven-Blackwater road: a loading dock, racks of crates, a dispatcher who sells packing gear, a night man at the door, and an office safe.")
    for x in (3, 9, 15):
        p.floor(x, 1, x, 6, STRIPE)
    roadside_sign(p, 24, 1, ["NORTHGATE", "FREIGHT"], ["Depot and", "dispatch"])
    # a box van at the dock
    def van(vx, vz, colour):
        p.fill(vx, 0, vz, vx + 2, 2, vz + 4, colour)
        p.fill(vx, 1, vz, vx + 2, 1, vz, "minecraft:glass")
        for (x, z) in [(vx - 1, vz + 1), (vx + 3, vz + 1), (vx - 1, vz + 3), (vx + 3, vz + 3)]:
            p.put(x, 0, z, "minecraft:black_concrete")
    van(5, 2, "minecraft:white_concrete")
    # the warehouse: x 3..28, z 10..26
    x0, z0, x1, z1 = 3, 10, 28, 26
    p.floor(x0, z0, x1, z1, "minecraft:smooth_stone")
    p.walls(x0, z0, x1, z1, 0, 0, "minecraft:gray_concrete")
    p.walls(x0, z0, x1, z1, 1, 6, "minecraft:light_gray_concrete")
    p.flat_roof(x0, z0, x1, z1, 7, "minecraft:smooth_stone", parapet="minecraft:light_gray_concrete")
    # the dock: a raised platform along the front and two roller doors (open: day shift)
    p.fill(x0, 0, 8, x1, 0, 9, "minecraft:smooth_stone_slab[type=bottom]")
    for dx0 in (7, 15):
        p.clear(dx0, 0, z0, dx0 + 3, 3, z0)
        p.fill(dx0, 4, z0, dx0 + 3, 4, z0, "minecraft:iron_trapdoor[facing=south,half=top,open=false]")
    p.clear(23, 0, z0, 23, 1, z0)
    p.door(23, 0, z0, "north", "spruce_door")
    p.sign(24, 2, z0 - 1, "minecraft:spruce_wall_sign[facing=north]", ["OFFICE", "Dispatch"], color="black")
    # racks of crates (x 6..20, three rows), some of them one-time loot
    for rz in (14, 18, 22):
        for x in range(6, 21):
            if x in (6, 13, 20):
                p.fill(x, 0, rz, x, 4, rz, "minecraft:spruce_fence")
            else:
                p.put(x, 0, rz, "minecraft:barrel[facing=up,open=false]" if x % 3 else "minecraft:spruce_planks")
                p.put(x, 1, rz, "minecraft:spruce_slab[type=bottom]")
                p.put(x, 2, rz, "minecraft:barrel[facing=up,open=false]" if x % 2 else "minecraft:chest[facing=north,type=single]")
                p.put(x, 3, rz, "minecraft:spruce_slab[type=bottom]")
    for (x, z) in [(9, 14), (16, 18), (11, 22), (18, 22)]:
        p.loot(x, 0, z, "minecraft:barrel[facing=up,open=false]", "underground_city:roadside/freight")
    # a forklift
    p.fill(10, 0, 25, 11, 0, 25, "minecraft:yellow_concrete"); p.put(10, 1, 25, "minecraft:yellow_concrete")
    p.fill(10, 0, 24, 11, 0, 24, "minecraft:iron_bars"); p.put(11, 1, 25, "minecraft:spruce_trapdoor[facing=north,half=top,open=false]")
    # the office (x 22..27, z 11..16), glazed toward the floor
    p.walls(22, 11, 27, 16, 0, 3, "minecraft:white_concrete")
    p.fill(22, 4, 11, 27, 4, 16, "minecraft:smooth_stone_slab[type=bottom]")
    for z in (13, 14):
        p.window(22, 1, z, height=2)
    p.clear(24, 0, 16, 24, 1, 16)
    p.door(24, 0, 16, "south", "birch_door")
    p.fill(24, 0, 13, 26, 0, 13, "minecraft:spruce_planks")          # the dispatcher's desk; she works behind it, on the north side
    p.put(25, 1, 13, ml("monitor", facing="north"))
    p.put(26, 0, 12, "minecraft:barrel[facing=up,open=false]")
    p.safe(23, 0, 12, "east", "office")
    p.put(25, 3, 14, "minecraft:lantern[hanging=true]")
    p.put(26, 3, 12, ml("warning_alarm", alarm_type=0, face="wall", facing="south", powered=False))
    p.put(26, 3, 15, ml("cctv_camera", facing="west"))
    for (x, z) in [(10, 16), (18, 16), (10, 20), (18, 20), (14, 24)]:
        p.put(x, 6, z, "minecraft:lantern[hanging=true]")
    p.npc(25, 0, 12, "shopkeeper", "Mara Quint", "cartographer", shop="roadside:northgate_dispatch",
          lines=["Crates, barrels, rope. If you're hauling, you're buying from me.",
                 "The night man's Bram. He doesn't miss much, and he doesn't take tips.",
                 "Ironhaven sends steel south, Blackwater sends boards north. Everything stops here a night.",
                 "Walking to a city anyway? Ask me for a manifest. The depot offices pay on delivery."])
    p.npc(25, 0, 6, "guard", "Bram Okoro", "armorer",
          lines=["Depot's open for business. The office isn't.", "I walk the racks every hour. Every hour."])
    for (x, z) in [(1, 9), (30, 9), (17, 1)]:
        p.lamp_post(x, z)
    return p


# ==================================================================================================== Valewood-Blackwater
def saltmarsh_motel() -> Place:
    p = Place("saltmarsh_motel", "Saltmarsh Motel", 34, 24, 11,
              note="A motel on the Valewood-Blackwater frontier road: four rooms to rent by the hour (bed, barrel, terminal) and a desk clerk who sells snacks and road supplies.")
    p.floor(0, 0, 33, 9, ASPHALT)
    for x in (12, 16, 20, 24, 28, 32):
        p.floor(x, 4, x, 8, STRIPE)
    # the tall sign at the road
    p.fill(1, 0, 1, 1, 4, 1, "minecraft:spruce_fence")
    p.fill(1, 5, 0, 3, 6, 0, "minecraft:spruce_planks")
    roadside_sign(p, 4, 1, ["SALTMARSH", "MOTEL"], ["VACANCY", "Rooms by hour"])
    # the office: x 2..9, z 10..19
    ox0, oz0, ox1, oz1 = 2, 10, 9, 19
    p.floor(ox0, oz0, ox1, oz1, "minecraft:birch_planks")
    p.walls(ox0, oz0, ox1, oz1, 0, 3, "minecraft:cyan_terracotta")
    p.walls(ox0, oz0, ox1, oz1, 4, 4, "minecraft:white_terracotta")
    p.flat_roof(ox0, oz0, ox1, oz1, 5, "minecraft:smooth_stone", parapet="minecraft:smooth_stone_slab[type=bottom]")
    p.window(3, 1, oz0, height=2); p.window(4, 1, oz0, height=2); p.window(7, 1, oz0, height=2); p.window(8, 1, oz0, height=2)
    p.clear(5, 0, oz0, 5, 1, oz0)
    p.door(5, 0, oz0, "north", "birch_door")
    p.sign(6, 2, oz0 - 1, "minecraft:birch_wall_sign[facing=north]", ["OFFICE", "Ring the bell"], color="black")
    p.fill(3, 0, 15, 8, 0, 15, "minecraft:birch_planks")       # the desk
    p.put(4, 1, 15, "minecraft:bell[attachment=floor,facing=north,powered=false]")
    p.put(7, 1, 15, ml("coffee_mug", facing="north"))
    p.seat(3, 0, 12, "east"); p.seat(3, 0, 13, "east")
    p.put(8, 0, 11, "minecraft:potted_fern"); p.put(3, 0, 11, "minecraft:bookshelf")
    p.put(8, 0, 13, "minecraft:red_concrete"); p.put(8, 1, 13, "minecraft:glass")      # a drinks cooler
    p.put(6, 0, 18, "minecraft:barrel[facing=up,open=false]"); p.put(3, 0, 18, "minecraft:barrel[facing=up,open=false]")
    p.safe(4, 0, 18, "north", "apartment_common")
    p.put(5, 4, 13, "minecraft:lantern[hanging=true]"); p.put(5, 4, 17, "minecraft:lantern[hanging=true]")
    p.npc(6, 0, 16, "shopkeeper", "June Rourke", "librarian", shop="roadside:saltmarsh_desk",
          lines=["Rooms are on the Home page of any terminal. Pay by the hour, keep the key while you're paid up.",
                 "Snacks, a sleeping bag, a map. The frontier road's long and the bridges are longer.",
                 "Nobody here asks where you're driving. Nobody here remembers either."])
    # the rooms: x 11..31, z 12..21, four of them
    rx0, rz0, rx1, rz1 = 11, 12, 31, 21
    p.floor(rx0, rz0, rx1, rz1, "minecraft:spruce_planks")
    p.walls(rx0, rz0, rx1, rz1, 0, 3, "minecraft:white_terracotta")
    for x in (16, 21, 26):
        p.fill(x, 0, rz0, x, 3, rz1, "minecraft:white_terracotta")
    p.fill(rx0 + 1, 4, rz0 + 1, rx1 - 1, 4, rz1 - 1, "minecraft:spruce_planks")      # the rooms' ceilings
    p.gable_roof(rx0, rz0, rx1, rz1, 5, "minecraft:spruce_stairs", "minecraft:spruce_slab[type=bottom]", along="x", overhang=1, fill_block="minecraft:white_terracotta")
    p.walls(rx0, rz0, rx1, rz1, 4, 4, "minecraft:white_terracotta")
    # the covered walk along the doors
    p.floor(rx0, 10, rx1, 11, "minecraft:smooth_stone")
    for x in (rx0, 16, 21, 26, rx1):
        p.fill(x, 0, 10, x, 2, 10, "minecraft:spruce_fence")
    p.fill(rx0, 3, 10, rx1, 3, 11, "minecraft:spruce_slab[type=bottom]")
    colours = ["red", "blue", "green", "yellow"]
    for i, ix in enumerate((12, 17, 22, 27)):
        n = i + 1
        p.clear(ix + 1, 0, rz0, ix + 1, 1, rz0)
        p.door(ix + 1, 0, rz0, "north", "spruce_door")
        p.window(ix + 3, 1, rz0, height=1)
        p.sign(ix + 2, 2, rz0 - 1, "minecraft:spruce_wall_sign[facing=north]", [f"ROOM {n}"], color="black")
        p.bed(ix, 0, 19, "south", colours[i])
        p.put(ix + 1, 0, 20, ml("oak_night_stand", facing="north"))
        p.put(ix + 3, 0, 20, "minecraft:barrel[facing=up,open=false]")
        p.put(ix + 3, 0, 16, ml("oak_small_table", type="single")); p.put(ix + 3, 1, 16, ml("old_tv", facing="west"))
        p.put(ix, 0, 14, ml("toilet", facing="east"))
        p.terminal(ix + 3, 0, 18, "west")
        p.put(ix + 1, 3, 17, "minecraft:lantern[hanging=true]")
        p.residence(f"room_{n}", f"Saltmarsh Motel Room {n}", "motel", 1, (ix, 0, rz0 + 1), (ix + 3, 2, rz1 - 1))
    p.put(32, 0, 22, ml("trash_can", facing="west"))
    for (x, z) in [(10, 9), (22, 9), (33, 9)]:
        p.lamp_post(x, z)
    return p


def old_cannery() -> Place:
    p = Place("old_cannery", "The Old Cannery", 28, 24, 9, ground="minecraft:coarse_dirt",
              note="A ruined cannery on the Valewood-Blackwater frontier road: rubble and machinery to search once, and under a trapdoor in the back office a smugglers' den whose keeper sells lockpicks and quiet tools.")
    p.depth = 9
    rnd = random.Random(1923)
    for x in range(28):
        for z in range(24):
            r = rnd.random()
            if r < 0.25:
                p.put(x, -1, z, "minecraft:gravel")
            elif r < 0.35:
                p.put(x, -1, z, "minecraft:grass_block")
    x0, z0, x1, z1 = 3, 5, 24, 20
    p.floor(x0, z0, x1, z1, "minecraft:cobblestone")
    bricks = ["minecraft:bricks", "minecraft:bricks", "minecraft:bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks"]
    for y in range(0, 7):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if x not in (x0, x1) and z not in (z0, z1):
                    continue
                missing = rnd.random() < (0.05 + 0.08 * y) or (x > 17 and z < 9 and y >= 2)
                if not missing:
                    p.put(x, y, z, rnd.choice(bricks))
    for x in range(x0 + 2, x1 - 1, 3):
        for z in (z0, z1):
            if rnd.random() < 0.6:
                p.put(x, 2, z, "minecraft:glass_pane" if rnd.random() < 0.3 else "minecraft:air")
                p.put(x, 3, z, "minecraft:air")
    # what is left of the roof
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x < 14 and rnd.random() < 0.8:
                p.put(x, 7, z, "minecraft:spruce_slab[type=bottom]" if rnd.random() < 0.7 else "minecraft:dark_oak_slab[type=bottom]")
    p.clear(13, 0, z0, 15, 3, z0)        # the broken loading door
    # machinery, rubble, cobwebs and vines
    for x in range(6, 18, 2):
        p.put(x, 0, 10, "minecraft:iron_trapdoor[facing=north,half=top,open=false]")
    for (x, z, spec) in [(6, 13, "minecraft:cauldron"), (8, 13, "minecraft:smoker[facing=north,lit=false]"), (10, 13, "minecraft:hopper[enabled=true,facing=down]"),
                         (12, 13, "minecraft:cauldron"), (6, 16, "minecraft:piston[extended=false,facing=up]"), (9, 17, "minecraft:anvil[facing=east]"),
                         (15, 15, "minecraft:gravel"), (16, 15, "minecraft:cobblestone"), (16, 16, "minecraft:gravel"), (20, 8, "minecraft:gravel"),
                         (21, 8, "minecraft:andesite"), (4, 6, "minecraft:cobweb"), (23, 19, "minecraft:cobweb"), (4, 19, "minecraft:cobweb")]:
        p.put(x, 0, z, spec)
    for y in range(1, 6):
        for z in range(z0 + 1, z1, 3):
            if rnd.random() < 0.5 and (x0, y, z) in p.blocks:
                p.put(x0 - 1, y, z, "minecraft:vine[east=true,north=false,south=false,up=false,west=false]")
    for (x, z) in [(5, 7), (11, 18), (19, 12)]:
        p.loot(x, 0, z, "minecraft:barrel[facing=up,open=false]", "underground_city:roadside/cannery")
    # the back office (x 18..23, z 14..19) and its trapdoor
    p.walls(18, 14, 23, 19, 0, 3, "minecraft:bricks")
    p.clear(18, 0, 16, 18, 1, 16)
    p.put(19, 0, 15, "minecraft:lectern[facing=east,has_book=false,powered=false]")
    p.put(20, 0, 15, "minecraft:spruce_planks"); p.put(21, 0, 15, "minecraft:spruce_stairs[facing=north,half=bottom]")
    p.put(22, 0, 18, "minecraft:barrel[facing=up,open=false]")          # the crate somebody pushed beside the hatch
    # the den under it: x 15..23, z 10..19, floor at y -8, room y -7..-4
    dx0, dz0, dx1, dz1 = 15, 10, 23, 19
    p.fill(dx0, -8, dz0, dx1, -3, dz1, "minecraft:stone_bricks")
    p.clear(dx0 + 1, -7, dz0 + 1, dx1 - 1, -4, dz1 - 1)
    for x in range(dx0 + 1, dx1):
        for z in range(dz0 + 1, dz1):
            p.put(x, -8, z, "minecraft:spruce_planks")
    # the shaft: a ladder from the hatch down to the den floor, on the south wall of the shaft
    p.clear(21, -7, 17, 21, -2, 17)
    for y in range(-7, -1):
        p.put(21, y, 17, "minecraft:ladder[facing=north,waterlogged=false]")
    p.put(21, -1, 17, "minecraft:spruce_trapdoor[facing=north,half=top,open=false,powered=false,waterlogged=false]")
    p.put(21, -1, 18, "minecraft:stone_bricks"); p.put(21, -2, 18, "minecraft:stone_bricks")
    for y in range(-7, -1):
        p.put(21, y, 18, "minecraft:stone_bricks")
    p.put(16, -7, 11, "minecraft:cartography_table"); p.put(17, -7, 11, "minecraft:barrel[facing=up,open=false]")
    p.bed(17, -7, 16, "west", "brown")
    p.put(16, -7, 18, "minecraft:chest[facing=east,type=single]")
    p.loot(22, -7, 11, "minecraft:barrel[facing=up,open=false]", "underground_city:roadside/smuggler_cache")
    p.safe(22, -7, 13, "west", "illegal_cache")
    for (x, z) in [(17, 13), (21, 13), (18, 17)]:
        p.put(x, -4, z, "minecraft:lantern[hanging=true]")
    p.npc(19, -7, 14, "shopkeeper", "Silas Crane", "nitwit", shop="roadside:cannery_den",
          lines=["You found the hatch. Good. People who find things are my customers.",
                 "Picks, a bypass kit, a bag that doesn't rattle. Cash, no names.",
                 "The guards at either end search what they can see. I sell what they can't."])
    roadside_sign(p, 1, 1, ["CANNERY", "CLOSED"], ["No entry", "Unsafe roof"], wood="dark_oak")
    return p


PLACES = [
    # id, builder, road, how far along the open road (0..1), kind, one line for the operator listing
    ("crossroads_diner", crossroads_diner, "ironhaven_valewood", 0.50, "service"),
    ("harrow_farm", harrow_farm, "ironhaven_valewood", 0.82, "farm"),
    ("kessler_salvage", kessler_salvage, "ironhaven_blackwater", 0.22, "industry"),
    ("northgate_depot", northgate_depot, "ironhaven_blackwater", 0.80, "industry"),
    ("saltmarsh_motel", saltmarsh_motel, "valewood_blackwater", 0.20, "lodging"),
    ("old_cannery", old_cannery, "valewood_blackwater", 0.82, "ruin"),
]

# The counters of the places' shopkeepers (shop_counters/roadside/*.json, a folder of their own beside the city shops' generated files): the outlet of which city's stock they sell.
SHOPS = {
    "crossroads_diner": ("shop:roadside_diner", "valewood", "Crossroads Diner"),
    "crossroads_supply": ("shop:roadside_supply", "ironhaven", "Crossroads Supply"),
    "harrow_farmstand": ("shop:farmstand", "valewood", "Harrow Farm Stand"),
    "kessler_salvage": ("shop:salvage", "ironhaven", "Kessler Salvage"),
    "northgate_dispatch": ("shop:dispatch", "blackwater", "Northgate Dispatch"),
    "saltmarsh_desk": ("shop:motel_desk", "valewood", "Saltmarsh Motel Desk"),
    "cannery_den": ("shop:den", "blackwater", "The Den"),
}

# Stock lines (regional_stock/roadside/<name>.json): item, unit price, initial, capacity, units per interval, interval ticks, max order [, requires_mod]
STOCK = {
    "shop:roadside_diner": [("minecraft:mushroom_stew", 9, 8, 12, 3, 24000, 4), ("minecraft:bread", 5, 16, 24, 6, 24000, 8),
                            ("minecraft:pumpkin_pie", 11, 6, 10, 2, 24000, 4), ("minecraft:cooked_chicken", 10, 8, 12, 3, 24000, 4),
                            ("minecraft:baked_potato", 4, 12, 20, 6, 24000, 8)],
    "shop:roadside_supply": [("minecraft:torch", 1, 32, 64, 16, 24000, 32), ("minecraft:lantern", 14, 4, 8, 2, 36000, 2),
                             ("minecraft:lead", 8, 4, 6, 2, 36000, 2), ("minecraft:compass", 40, 2, 3, 1, 72000, 1),
                             ("thirstwastaken2:iron_flask", 160, 2, 3, 1, 72000, 1, "thirstwastaken2")],
    "shop:farmstand": [("minecraft:carrot", 2, 24, 48, 12, 24000, 16), ("minecraft:potato", 2, 24, 48, 12, 24000, 16),
                       ("minecraft:wheat", 2, 24, 48, 12, 24000, 16), ("minecraft:wheat_seeds", 1, 32, 64, 16, 24000, 32),
                       ("minecraft:pumpkin_seeds", 3, 8, 16, 4, 36000, 8), ("minecraft:melon_seeds", 3, 8, 16, 4, 36000, 8),
                       ("minecraft:egg", 2, 12, 24, 6, 24000, 12), ("minecraft:hay_block", 15, 4, 8, 2, 36000, 4),
                       # berries, beet seed and cane: a Vintner's must and a Farmer's sugar had no source beyond the starter kit (the first-hour audit, D-175)
                       ("minecraft:sweet_berries", 2, 16, 32, 8, 24000, 16), ("minecraft:beetroot_seeds", 1, 16, 32, 8, 24000, 16),
                       ("minecraft:sugar_cane", 2, 12, 24, 6, 24000, 12)],
    "shop:salvage": [("minecraft:iron_nugget", 1, 36, 64, 12, 24000, 32), ("minecraft:chain", 6, 8, 16, 4, 36000, 8),
                     ("minecraft:iron_bars", 5, 12, 24, 6, 36000, 12), ("minecraft:copper_ingot", 8, 8, 16, 4, 36000, 8),
                     ("underground_city:machined_parts", 20, 2, 4, 1, 72000, 2), ("underground_city:e_scrap", 5, 24, 48, 8, 24000, 16),
                     ("minecraft:quartz", 9, 6, 12, 3, 36000, 6)],   # stripped from scrapped switchgear: the road's quartz (D-175)
    "shop:dispatch": [("minecraft:barrel", 6, 6, 12, 3, 36000, 6), ("minecraft:chest", 8, 6, 12, 3, 36000, 6),
                      ("minecraft:lead", 8, 4, 8, 2, 36000, 2), ("minecraft:paper", 2, 16, 32, 8, 24000, 16)],
    "shop:motel_desk": [("minecraft:cookie", 2, 16, 32, 8, 24000, 16), ("minecraft:honey_bottle", 6, 6, 12, 3, 36000, 4),
                        ("minecraft:apple", 3, 12, 24, 6, 24000, 8), ("minecraft:map", 8, 3, 6, 1, 36000, 2),
                        ("comforts:sleeping_bag_red", 60, 2, 3, 1, 72000, 1, "comforts")],
    "shop:den": [("underground_city:lockpick", 22, 4, 6, 1, 48000, 2), ("underground_city:bypass_kit", 75, 1, 2, 1, 96000, 1)],
}

# Listings in the property ladder (D-099) for the places' homes.
LISTINGS = [
    {"id": f"rs_saltmarsh_motel:room_{n}", "title": f"Saltmarsh Motel Room {n}", "category": "motel_room", "area": "Saltmarsh Motel (Valewood-Blackwater road)",
     "tenures": ["rent"], "rent": 10, "deposit": 0, "buy_price": 0, "upkeep": 0, "max_containers": 2, "private_claims": 1,
     "facilities": ["bed", "storage", "terminal"],
     "summary": "A roadside room halfway between Valewood and Blackwater: a bed, a barrel and the home terminal. The road's only roof that locks."} for n in range(1, 5)
] + [
    {"id": "rs_harrow_farm:farmhouse", "title": "Harrow Farmhouse", "category": "farmhouse", "area": "Harrow Farm (Ironhaven-Valewood road)",
     "tenures": ["rent", "buy"], "rent": 115, "deposit": 230, "buy_price": 8500, "upkeep": 20, "max_containers": 12, "private_claims": 3,
     "facilities": ["bed", "storage", "crafting", "furnace", "workshop"],
     "summary": "A two-storey farmhouse beside its fields on the Ironhaven-Valewood road: a kitchen with a hearth, two bedrooms, room for a workshop, and the farm stand at the gate."},
]


def loot_entry(item: str, weight: int, lo: int, hi: int) -> dict:
    entry = {"type": "minecraft:item", "name": item, "weight": weight}
    if hi > 1:
        entry["functions"] = [{"function": "minecraft:set_count", "count": {"min": lo, "max": hi}}]
    return entry


LOOT = {
    "farm_store": (2, 4, [("minecraft:wheat_seeds", 6, 2, 8), ("minecraft:carrot", 5, 1, 4), ("minecraft:potato", 5, 1, 4), ("minecraft:wheat", 4, 2, 6),
                          ("minecraft:bone_meal", 3, 1, 4), ("minecraft:leather", 2, 1, 2), ("minecraft:string", 2, 1, 3), ("underground_city:rare_seeds", 1, 1, 1)]),
    "scrap_bin": (2, 4, [("minecraft:iron_nugget", 8, 2, 7), ("minecraft:chain", 3, 1, 2), ("minecraft:copper_ingot", 3, 1, 2), ("minecraft:redstone", 2, 1, 3),
                         ("minecraft:glass_bottle", 2, 1, 3), ("underground_city:machined_parts", 1, 1, 1), ("underground_city:e_scrap", 3, 1, 3)]),
    "freight": (2, 4, [("minecraft:paper", 5, 2, 8), ("minecraft:white_wool", 4, 2, 6), ("minecraft:leather", 3, 1, 3), ("minecraft:iron_ingot", 3, 1, 3),
                       ("minecraft:copper_ingot", 3, 1, 4), ("minecraft:book", 2, 1, 2), ("underground_city:control_circuits", 1, 1, 1),
                       ("underground_city:refined_alloy", 1, 1, 1)]),
    "cannery": (1, 3, [("minecraft:iron_nugget", 6, 1, 5), ("minecraft:string", 4, 1, 3), ("minecraft:paper", 4, 1, 4), ("minecraft:glass_bottle", 4, 1, 2),
                       ("minecraft:bowl", 3, 1, 2), ("minecraft:clock", 1, 1, 1)]),
    "smuggler_cache": (2, 3, [("minecraft:gold_nugget", 4, 2, 6), ("minecraft:emerald", 1, 1, 1), ("underground_city:lockpick", 3, 1, 2),
                              ("minecraft:compass", 2, 1, 1), ("underground_city:pocket_watch", 1, 1, 1)]),
}


def loot_table(rolls_lo: int, rolls_hi: int, entries) -> dict:
    return {"type": "minecraft:chest", "pools": [{"rolls": {"min": rolls_lo, "max": rolls_hi}, "entries": [loot_entry(*e) for e in entries]}]}


def outputs() -> dict[Path, str]:
    files: dict[Path, str] = {}

    def put(rel: str, data) -> None:
        files[DATA / rel] = json.dumps(data, indent=2, ensure_ascii=False) + "\n"

    for pid, builder, road, at, kind in PLACES:
        place = builder()
        variants = {}
        for facing in ("north", "east", "south", "west"):
            district = place.variant(facing)
            put(f"districts/{district['id']}.json", district)
            variants[facing] = district["id"]
        size = [place.w, place.d]
        put(f"roadside_places/{pid}.json", {
            "schema_version": 1, "id": pid, "title": place.title, "road": road, "at": at, "kind": kind,
            "size": size, "variants": variants,
            "note": f"Generated by scripts/roadside_places.py; do not edit by hand. {place.note}",
        })
        if pid == "kessler_salvage":
            for facing, did in variants.items():
                district = json.loads(files[DATA / f"districts/{did}.json"])
                veins = [op["at"] for op in district["ops"] if op.get("op") == "block" and op.get("block") == SCRAP]
                veins += [p for op in district["ops"] if op.get("op") == "fill" and op.get("block") == SCRAP
                          for p in [[x, y, z] for x in range(op["from"][0], op["to"][0] + 1) for y in range(op["from"][1], op["to"][1] + 1)
                                    for z in range(op["from"][2], op["to"][2] + 1)]]
                put(f"resource_sites/{did}.json", {
                    "schema_version": 1, "id": did, "title": "Kessler Salvage scrap heaps", "district": did, "interval_minutes": 60,
                    "note": "Generated by scripts/roadside_places.py. Stripped scrap heaps fill again after interval_minutes of ACTIVE play time (an hour, so the yard yields at most one heap a minute, within the bound every regrowing site keeps) (never while nobody is online), unless somebody stands in the cell; a cell somebody built in is never overwritten. One file per rotated variant; only the variant a world built does anything.",
                    "veins": [{"at": v, "block": SCRAP} for v in sorted(veins)],
                })
    for key, (outlet, city, title) in SHOPS.items():
        put(f"shop_counters/roadside/{key}.json", {"schema_version": 1, "outlet": outlet, "building": f"roadside:{key}", "spot": "counter", "city": city, "title": title})
    for outlet, lines in STOCK.items():
        city = next(c for (o, c, _t) in SHOPS.values() if o == outlet)
        for line in lines:
            item, price, initial, capacity, per, interval, max_order = line[:7]
            data = {"schema_version": 1, "city": city, "item": item, "unit_price": price, "initial": initial, "capacity": capacity,
                    "units_per_interval": per, "interval_ticks": interval, "max_order": max_order, "outlets": [outlet]}
            if len(line) > 7:
                data["requires_mod"] = line[7]
            name = outlet.split(":")[1] + "_" + item.split(":")[1]
            put(f"regional_stock/roadside/{name}.json", data)
    for name, (lo, hi, entries) in LOOT.items():
        put(f"loot_tables/roadside/{name}.json", loot_table(lo, hi, entries))
    # the property ladder: replace this script's listings, keep everything else as it is
    ladder_path = DATA / "properties" / "ladder.json"
    ladder = json.loads(ladder_path.read_text(encoding="utf-8"))
    ladder["listings"] = [entry for entry in ladder["listings"] if not entry["id"].startswith("rs_")] + LISTINGS
    for category in ladder["categories"]:
        if category["id"] == "farmhouse":
            category["built"] = True
    files[ladder_path] = json.dumps(ladder, indent=2, ensure_ascii=False) + "\n"
    return files


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    files = outputs()
    stale = []
    for path, text in files.items():
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != text:
                stale.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    if args.check:
        for path in stale:
            print(f"stale or missing: {path.relative_to(ROOT)}")
        return 1 if stale else 0
    print(f"wrote {len(files)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
