#!/usr/bin/env python3
"""Generate the district definitions: Founders Row, the firing range, the checkpoint post and the wayside shelter (D-055, D-061, D-066, D-079).

A district is data: a list of block and citizen operations relative to an origin chosen by an operator at build time
(the footprint's north-west corner, extending east and south), so no world coordinate is hard-coded. ``y = 0`` is the
walkable level; the paved platform is ``y = -1``. Core reads ``data/underground_city/districts/*.json``.

Founders Row is one lot that makes the first playable loop usable end to end and gives it people and a place:

* the north terrace: the supply stall (Operations Terminal and the supply clerk), a records office with three registered
  Household Safes, a well, the network office (Secured Data Terminal, the Technician's employer) and a greenhouse for the
  controlled crop;
* a paved lane south from the terrace to a gate, with a market plaza halfway: the village bell under a gazebo, the Public Ledger
  (the board of legal postings), two market stalls with named vendors and benches;
* west side: the gunsmith's workshop (with its back door to the firing range), the foundry and two cottages; east side: the farm
  co-op, a cottage, a storehouse with registered safes and the registry where the registrar works;
* the gate, with its booth and its guard.

Every building is a timber-and-stone house with a pitched roof, a door, shutters and a sign. Every citizen is a named villager with a bed, a
workstation or both, so the vanilla routines (sleep, work, gather at the bell) apply. An employer is bound to an Operations Terminal in their
office. Lines of dialogue live here, in data. It is an operator tool for a fresh world, not generated city content.

The firing range is a second, small district built beside the Row (the dev launch places it): three firing lanes behind a line, targets at 10,
20 and 30 blocks, a bench and an instructor.

The checkpoint post (a booth and a guard) and the wayside shelter (an open pavilion with a campfire and a road warden) are small repeatable districts
that stand beside a road, one per checkpoint zone or per stretch of road.

``python scripts/generate_district.py`` writes the files; ``--check`` fails when a committed file differs.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "rpg-core/src/main/resources/data/underground_city/districts"
WIDTH, DEPTH, HEIGHT = 33, 49, 12
CENTRE = 16                       # x of the lane's middle: the lane is x 13..19
LANE = (13, 19)
LANE_START = 7
SPRUCE = "minecraft:spruce_planks"
FLIP = {"east": "west", "west": "east", "north": "north", "south": "south"}


def fill(a, b, block):
    return {"op": "fill", "from": list(a), "to": list(b), "block": block}


def put(at, block, requires_mod: str | None = None):
    op = {"op": "block", "at": list(at), "block": block}
    if requires_mod:
        op["requires_mod"] = requires_mod
    return op


def sign(at, facing, lines, color="black", wood="spruce", glow=False):
    assert all(len(line) <= 15 for line in lines), f"a sign line is longer than the 15 characters a sign can show: {lines}"
    return {"op": "sign", "at": list(at), "block": f"minecraft:{wood}_wall_sign[facing={facing}]", "lines": lines, "color": color, "glow": glow}


def npc(at, role, name, profession, lines, career=None, terminal=None):
    op = {"op": "npc", "at": list(at), "role": role, "name": name, "profession": profession, "lines": lines}
    if career:
        op["career"] = career
    if terminal:
        op["terminal"] = list(terminal)
    return op


class Building:
    """A 7-wide room (6 or 7 deep) with a pitched roof whose door is on its east side; ``mirror`` builds the same room facing west.

    Local coordinates: ``lx`` runs from the back wall (0) to the door wall (6), ``lz`` from north to south, ``ly`` is the world y. The walls
    are four high, the ceiling is at y 4 and the gable roof rises to a ridge at y 7 along the north-south axis.
    """

    def __init__(self, x0: int, z0: int, mirror: bool = False, depth: int = 7, width: int = 7):
        self.x0, self.z0, self.mirror, self.d, self.w = x0, z0, mirror, depth, width

    def p(self, lx: int, ly: int, lz: int) -> tuple[int, int, int]:
        return (self.x0 + (self.w - 1 - lx if self.mirror else lx), ly, self.z0 + lz)

    def face(self, direction: str) -> str:
        return FLIP[direction] if self.mirror else direction

    def shell(self, *, wall: str, trim: str, base: str, floor: str, roof: str, ridge: str, ceiling: str = SPRUCE, door: str = "spruce",
              shutter: str = "spruce", sign_wood: str = "spruce", title: list[str] | None = None, banners: str | None = None,
              chimney: int | None = None) -> list[dict]:
        w, d = self.w, self.d
        east, west = self.face("east"), self.face("west")
        ops = [
            fill(self.p(0, -1, 0), self.p(w - 1, -1, d - 1), floor),
            fill(self.p(0, 0, 0), self.p(w - 1, 3, 0), wall), fill(self.p(0, 0, d - 1), self.p(w - 1, 3, d - 1), wall),
            fill(self.p(0, 0, 0), self.p(0, 3, d - 1), wall), fill(self.p(w - 1, 0, 0), self.p(w - 1, 3, d - 1), wall),
            # a stone base course, then timber corner posts
            fill(self.p(0, 0, 0), self.p(w - 1, 0, 0), base), fill(self.p(0, 0, d - 1), self.p(w - 1, 0, d - 1), base),
            fill(self.p(0, 0, 0), self.p(0, 0, d - 1), base), fill(self.p(w - 1, 0, 0), self.p(w - 1, 0, d - 1), base),
        ]
        for lx, lz in ((0, 0), (w - 1, 0), (0, d - 1), (w - 1, d - 1)):
            ops.append(fill(self.p(lx, 0, lz), self.p(lx, 3, lz), trim))
        ops += [
            fill(self.p(1, 0, 1), self.p(w - 2, 3, d - 2), "minecraft:air"),
            fill(self.p(0, 4, 0), self.p(w - 1, 4, d - 1), ceiling),
            fill(self.p(0, 4, 0), self.p(w - 1, 4, 0), wall), fill(self.p(0, 4, d - 1), self.p(w - 1, 4, d - 1), wall),
        ]
        for i in range(1, 4):   # the gable ends step in towards the ridge
            ops.append(fill(self.p(i, 4 + i, 0), self.p(w - 1 - i, 4 + i, 0), wall))
            ops.append(fill(self.p(i, 4 + i, d - 1), self.p(w - 1 - i, 4 + i, d - 1), wall))
        for i in range(4):      # stairs climbing from both eaves to the ridge, overhanging the gables by a block
            ops.append(fill(self.p(i - 1, 4 + i, -1), self.p(i - 1, 4 + i, d), f"{roof}[facing={east},half=bottom]"))
            ops.append(fill(self.p(w - i, 4 + i, -1), self.p(w - i, 4 + i, d), f"{roof}[facing={west},half=bottom]"))
        ops.append(fill(self.p(3, 7, -1), self.p(3, 7, d), ridge))
        # the doorway with a door, the lamp over the step and the planters either side of it
        ops += [
            fill(self.p(w - 1, 0, 3), self.p(w - 1, 1, 3), "minecraft:air"),
            put(self.p(w - 1, 0, 3), f"minecraft:{door}_door[facing={east},half=lower,hinge=left,open=false]"),
            put(self.p(w - 1, 1, 3), f"minecraft:{door}_door[facing={east},half=upper,hinge=left,open=false]"),
            put(self.p(3, 3, 3), "minecraft:lantern[hanging=true]"),
            put(self.p(w, 0, 2), "minecraft:potted_azalea_bush"), put(self.p(w, 0, 4), "minecraft:potted_fern"),
        ]
        # windows: two on the lane side with shutters (never in front of the door), a pair on the back wall, one on each side wall
        shutter_block = f"minecraft:{shutter}_trapdoor[facing={east},half=bottom,open=true]"
        for lz in (1, d - 2):
            ops.append(put(self.p(w - 1, 1, lz), "minecraft:glass_pane"))
            for dz in (-1, 1):
                if lz + dz != 3:
                    ops.append(put(self.p(w, 1, lz + dz), shutter_block))
        for lz in (2, d - 3):
            ops.append(put(self.p(0, 1, lz), "minecraft:glass_pane"))
        for lx in (2, 4):
            ops += [put(self.p(lx, 1, 0), "minecraft:glass_pane"), put(self.p(lx, 1, d - 1), "minecraft:glass_pane")]
        if chimney is not None:
            ops += [fill(self.p(1, 0, chimney), self.p(1, 9, chimney), "minecraft:stone_bricks"),
                    put(self.p(1, 10, chimney), "minecraft:campfire[lit=true,signal_fire=false,facing=north]")]
        if title:
            ops.append(sign(self.p(w, 2, 3), east, title, wood=sign_wood))
        if banners:
            ops += [put(self.p(w, 2, 1), f"minecraft:{banners}_wall_banner[facing={east}]"), put(self.p(w, 2, d - 2), f"minecraft:{banners}_wall_banner[facing={east}]")]
        return ops

    def bed(self, lx: int, lz: int, color: str = "red") -> list[dict]:
        """A bed whose foot is at ``(lx, lz)`` and head one block north."""
        return [put(self.p(lx, 0, lz), f"minecraft:{color}_bed[facing=north,part=foot]"),
                put(self.p(lx, 0, lz - 1), f"minecraft:{color}_bed[facing=north,part=head]")]

    def rug(self, a, b, color) -> list[dict]:
        return [fill(self.p(a[0], 0, a[1]), self.p(b[0], 0, b[1]), f"minecraft:{color}_carpet")]

    def table(self, lx, lz) -> list[dict]:
        return [put(self.p(lx, 0, lz), "minecraft:spruce_fence"), put(self.p(lx, 1, lz), "minecraft:spruce_slab[type=bottom]")]

    def chair(self, lx, lz, toward: str) -> list[dict]:
        return [put(self.p(lx, 0, lz), f"minecraft:spruce_stairs[facing={self.face(toward)},half=bottom]")]

    def shelves(self, lx, lz) -> list[dict]:
        return [fill(self.p(lx, 0, lz), self.p(lx, 1, lz), "minecraft:bookshelf")]

    def plant(self, lx, lz, pot="minecraft:potted_fern") -> list[dict]:
        return [put(self.p(lx, 0, lz), pot)]


def lamp_post(x, z):
    return [fill((x, 0, z), (x, 2, z), "minecraft:stone_brick_wall"), put((x, 3, z), "minecraft:lantern[hanging=false]")]


def tree(x, z, height=4, radius=1):
    """A small oak: a trunk and a rounded crown of persistent leaves (so it never decays), radius 1 or 2."""
    return [fill((x, 0, z), (x, height - 1, z), "minecraft:oak_log"),
            fill((x - radius, height - 1, z - radius), (x + radius, height, z + radius), "minecraft:oak_leaves[persistent=true]"),
            fill((x - radius + 1, height + 1, z - radius + 1), (x + radius - 1, height + 1, z + radius - 1), "minecraft:oak_leaves[persistent=true]") if radius > 1
            else put((x, height + 1, z), "minecraft:oak_leaves[persistent=true]"),
            put((x, height - 1, z), "minecraft:oak_log")]


def stall_frame(x0: int, z0: int, width: int, depth: int, *, awning: tuple[str, str], floor: str, back: str = SPRUCE) -> list[dict]:
    """An open-front stall: a floor, a back wall on the north side (z0), fence posts at the front corners, a striped wool awning and a lantern."""
    ops = [
        fill((x0, -1, z0), (x0 + width - 1, -1, z0 + depth - 1), floor),
        fill((x0, 0, z0), (x0 + width - 1, 2, z0), back),
        fill((x0, 0, z0 + depth - 1), (x0, 2, z0 + depth - 1), "minecraft:spruce_fence"),
        fill((x0 + width - 1, 0, z0 + depth - 1), (x0 + width - 1, 2, z0 + depth - 1), "minecraft:spruce_fence"),
    ]
    for x in range(x0, x0 + width):
        ops.append(fill((x, 3, z0), (x, 3, z0 + depth), f"minecraft:{awning[(x - x0) % 2]}_wool"))
    ops.append(put((x0 + width // 2, 2, z0 + 2), "minecraft:lantern[hanging=true]"))
    return ops


def market_stall(x0: int, z0: int, *, awning, goods, vendor) -> list[dict]:
    """A stall on the east side of the plaza, open to the west (3 x 3): back wall, striped awning, a counter at the front and the vendor behind."""
    ops = [
        fill((x0, -1, z0), (x0 + 2, -1, z0 + 2), SPRUCE),
        fill((x0 + 2, 0, z0), (x0 + 2, 2, z0 + 2), SPRUCE),                       # back wall
        fill((x0, 0, z0), (x0, 2, z0), "minecraft:spruce_fence"), fill((x0, 0, z0 + 2), (x0, 2, z0 + 2), "minecraft:spruce_fence"),
        put((x0, 0, z0 + 1), "minecraft:barrel[facing=west]"),                      # the counter between the posts
        put((x0, 1, z0 + 1), "minecraft:oak_slab[type=bottom]"),
    ]
    for z in range(z0, z0 + 3):
        ops.append(fill((x0 - 1, 3, z), (x0 + 2, 3, z), f"minecraft:{awning[(z - z0) % 2]}_wool"))
    ops.append(put((x0 + 1, 2, z0 + 1), "minecraft:lantern[hanging=true]"))
    for dx, dz, block in goods:
        ops.append(put((x0 + dx, 0, z0 + dz), block))
    name, profession, lines = vendor
    ops.append(npc((x0 + 1, 0, z0 + 1), "vendor", name, profession, lines))
    return ops


def office(b: Building, *, career: str, name: str, profession: str, workstation: str, extras: list[dict], lines: list[str], bed: str = "red",
           rug: str = "gray", **shell) -> list[dict]:
    """A career employer's office: an Operations Terminal bound to the employer, their workstation, a desk corner and a bed."""
    ops = b.shell(**shell)
    terminal = b.p(1, 0, 2)
    ops += [
        {"op": "terminal", "at": list(terminal), "facing": b.face("east")},
        put(b.p(3, 0, 1), workstation),
        npc(b.p(2, 0, 2), "employer", name, profession, lines, career=career, terminal=terminal),
    ]
    ops += b.rug((2, 3), (5, 3), rug) + b.shelves(2, 1) + b.table(4, 5) + b.chair(3, 5, "east") + b.plant(5, 5)
    ops += extras
    ops += b.bed(1, b.d - 2, bed)
    return ops


def cottage(b: Building, *, name: str, profession: str, workstation: str, lines: list[str], bed: str, rug: str = "brown", **shell) -> list[dict]:
    """A home: a bed, a registered apartment-grade safe (a burglary target with a lockpick) and the resident's workstation."""
    ops = b.shell(**shell)
    table_z = 4
    ops += [
        put(b.p(3, 0, 1), workstation),
        {"op": "safe", "at": list(b.p(1, 0, 2)), "policy": "apartment_common"},
        put(b.p(5, 0, 1), "minecraft:crafting_table"),
        npc(b.p(3, 0, 3), "resident", name, profession, lines),
    ]
    ops += b.rug((2, 2), (5, 2), rug) + b.shelves(5, b.d - 2) + b.table(4, table_z) + b.chair(3, table_z, "east") + b.plant(2, 1)
    ops += b.bed(1, b.d - 2, bed)
    return ops


def assert_citizens_have_room(ops: list[dict]) -> None:
    """Replays the block operations in a dictionary and fails when a citizen's cell is not two clear blocks over a solid floor (a carpet or a chair there
    makes Core refuse to place them, which only a build in a real world would otherwise reveal)."""
    cells: dict[tuple, str] = {}
    for op in ops:
        if op["op"] == "fill":
            (x0, y0, z0), (x1, y1, z1) = op["from"], op["to"]
            for x in range(min(x0, x1), max(x0, x1) + 1):
                for y in range(min(y0, y1), max(y0, y1) + 1):
                    for z in range(min(z0, z1), max(z0, z1) + 1):
                        cells[(x, y, z)] = op["block"]
        elif op["op"] in ("block", "sign"):
            cells[tuple(op["at"])] = op["block"]
        elif op["op"] in ("terminal", "safe", "secure_terminal", "ledger", "clerk"):
            cells[tuple(op["at"])] = "solid:" + op["op"]
    for op in ops:
        if op["op"] != "npc":
            continue
        x, y, z = op["at"]
        clear = lambda p: cells.get(p, "minecraft:air") == "minecraft:air"
        floor = cells.get((x, y - 1, z), "minecraft:air")
        if not (clear((x, y, z)) and clear((x, y + 1, z)) and floor != "minecraft:air" and "carpet" not in floor and "water" not in floor):
            raise SystemExit(f"{op['name']} has no room at {op['at']}: {cells.get((x, y, z))} / {cells.get((x, y + 1, z))} over {floor}")


def founders_row() -> dict:
    ops = [
        fill((0, -4, 0), (WIDTH - 1, -2, DEPTH - 1), "minecraft:stone"),
        fill((0, -1, 0), (WIDTH - 1, -1, DEPTH - 1), "minecraft:grass_block"),
        fill((0, 0, 0), (WIDTH - 1, HEIGHT - 1, DEPTH - 1), "minecraft:air"),
        # the paved heart of the lot: the terrace, the pavements, the lane and its centre line
        fill((2, -1, 0), (30, -1, DEPTH - 3), "minecraft:stone_bricks"),
        fill((LANE[0], -1, LANE_START), (LANE[1], -1, DEPTH - 1), "minecraft:polished_andesite"),
        fill((CENTRE, -1, LANE_START), (CENTRE, -1, DEPTH - 1), "minecraft:smooth_stone"),
    ]
    # ------------------------------------------------------------------ the north terrace
    # A: supply stall (Operations Terminal and the supply clerk)
    ops += stall_frame(2, 1, 7, 5, awning=("lime", "white"), floor=SPRUCE)
    ops += [{"op": "terminal", "at": [4, 0, 2], "facing": "south"}, {"op": "clerk", "at": [5, 0, 2], "terminal": [4, 0, 2]},
            put((7, 0, 2), "minecraft:barrel[facing=up]"), put((8, 0, 2), "minecraft:barrel[facing=up]"), put((7, 1, 2), "minecraft:hay_block"),
            put((3, 0, 2), "minecraft:potted_fern"), sign((6, 2, 2), "south", ["SUPPLY STALL", "Civilian goods"])]
    # B: records office with three registered Household Safes of different security
    ops += stall_frame(10, 1, 5, 5, awning=("light_gray", "white"), floor="minecraft:smooth_stone", back="minecraft:stone_bricks")
    for x, policy in ((11, "apartment_common"), (12, "office"), (13, "industrial")):
        ops.append({"op": "safe", "at": [x, 0, 2], "policy": policy})
    ops.append(sign((12, 2, 2), "south", ["RECORDS", "Safe deposit"]))
    # the well at the head of the lane
    ops += [fill((15, 0, 2), (17, 0, 4), "minecraft:stone_bricks"), put((16, 0, 3), "minecraft:water"),
            fill((15, 1, 2), (15, 2, 2), "minecraft:stone_brick_wall"), fill((17, 1, 2), (17, 2, 2), "minecraft:stone_brick_wall"),
            fill((15, 1, 4), (15, 2, 4), "minecraft:stone_brick_wall"), fill((17, 1, 4), (17, 2, 4), "minecraft:stone_brick_wall"),
            fill((15, 3, 2), (17, 3, 4), "minecraft:spruce_slab[type=bottom]"), put((16, 2, 3), "minecraft:lantern[hanging=true]")]
    # C: the network office, walled in deepslate, holding the Secured Data Terminal and the Technician's employer
    ops += [
        fill((18, -1, 1), (22, -1, 5), "minecraft:polished_deepslate"),
        fill((18, 0, 1), (22, 3, 1), "minecraft:deepslate_bricks"),
        fill((18, 0, 2), (18, 3, 5), "minecraft:deepslate_bricks"),
        fill((22, 0, 2), (22, 3, 5), "minecraft:deepslate_bricks"),
        fill((18, 4, 1), (22, 4, 5), "minecraft:deepslate_brick_slab[type=bottom]"),
        fill((19, 3, 5), (21, 3, 5), "minecraft:deepslate_bricks"),
        fill((18, 1, 3), (18, 2, 3), "minecraft:iron_bars"), fill((22, 1, 3), (22, 2, 3), "minecraft:iron_bars"),
        put((20, 3, 3), "minecraft:lantern[hanging=true]"),
        fill((19, 3, 2), (21, 3, 2), "minecraft:sea_lantern"),
        {"op": "secure_terminal", "at": [20, 0, 2], "facing": "south"},
        {"op": "terminal", "at": [19, 0, 3], "facing": "east"},
        put((21, 0, 2), "minecraft:lectern[facing=south]"),
        fill((19, 0, 4), (19, 1, 4), "minecraft:iron_block"), put((19, 2, 4), "minecraft:redstone_lamp[lit=true]"),
        sign((20, 2, 6), "south", ["NETWORK OFFICE", "Kit Arden"], color="white", wood="warped"),
        npc([21, 0, 3], "employer", "Kit Arden - Network Office", "librarian", [
            "Municipal terminals, sensor repairs, timing circuits: dull work that keeps the lights on. The orders are on the terminal.",
            "The secured terminal on the wall runs a trace: watch the sequence once, then enter it from memory. The terminal, not you, checks the answer.",
            "Ledger work for Vesper pays well and leaves tracks. Learn Signal Scrubbing before you try it.",
        ], career="technician", terminal=[19, 0, 3]),
    ]
    # D: glass greenhouse with irrigated soil for the controlled crop
    ops += [
        fill((25, -1, 1), (29, -1, 6), "minecraft:stone_bricks"),
        fill((26, -1, 2), (28, -1, 5), "minecraft:farmland[moisture=7]"),
        put((27, -1, 3), "minecraft:water"), put((27, -1, 4), "minecraft:water"),
        fill((25, 0, 1), (29, 3, 1), "minecraft:glass"), fill((25, 0, 1), (25, 3, 6), "minecraft:glass"),
        fill((29, 0, 1), (29, 3, 6), "minecraft:glass"), fill((25, 0, 6), (29, 3, 6), "minecraft:glass"),
        fill((25, 4, 1), (29, 4, 6), "minecraft:glass"),
        fill((27, 0, 6), (27, 1, 6), "minecraft:air"),
        put((26, 3, 2), "minecraft:lantern[hanging=true]"), put((28, 3, 5), "minecraft:lantern[hanging=true]"),
        put((26, 0, 7), "minecraft:potted_fern"), put((28, 0, 7), "minecraft:potted_azalea_bush"),
    ]

    # ------------------------------------------------------------------ the plaza
    ops += [
        fill((10, -1, 20), (22, -1, 30), "minecraft:polished_andesite"),
        fill((10, -1, 20), (22, -1, 20), "minecraft:stone_bricks"), fill((10, -1, 30), (22, -1, 30), "minecraft:stone_bricks"),
        fill((10, -1, 20), (10, -1, 30), "minecraft:stone_bricks"), fill((22, -1, 20), (22, -1, 30), "minecraft:stone_bricks"),
        fill((14, -1, 23), (18, -1, 27), "minecraft:stone_bricks"), fill((15, -1, 24), (17, -1, 26), "minecraft:polished_andesite"),
        # the village bell under a gazebo
        put((16, 0, 25), "minecraft:stone_bricks"), put((16, 1, 25), "minecraft:bell[attachment=floor,facing=north]"),
        fill((15, 0, 24), (15, 2, 24), "minecraft:spruce_fence"), fill((17, 0, 24), (17, 2, 24), "minecraft:spruce_fence"),
        fill((15, 0, 26), (15, 2, 26), "minecraft:spruce_fence"), fill((17, 0, 26), (17, 2, 26), "minecraft:spruce_fence"),
        fill((14, 3, 23), (18, 3, 27), "minecraft:spruce_slab[type=bottom]"), fill((15, 3, 24), (17, 3, 26), "minecraft:spruce_planks"),
        put((16, 4, 25), "minecraft:lantern[hanging=false]"),
        # the Public Ledger on the west side, with benches either side of it
        {"op": "ledger", "at": [11, 0, 25], "facing": "east"},
        put((11, 0, 23), "minecraft:spruce_stairs[facing=east,half=bottom]"), put((11, 0, 27), "minecraft:spruce_stairs[facing=east,half=bottom]"),
        put((10, 0, 23), "minecraft:potted_poppy"), put((10, 0, 27), "minecraft:potted_cornflower"),
        put((11, 0, 21), "minecraft:potted_oxeye_daisy"), put((11, 0, 29), "minecraft:potted_azalea_bush"),
    ]
    ops += market_stall(20, 21, awning=("red", "white"), goods=[(0, 2, "minecraft:hay_block"), (1, 0, "minecraft:barrel[facing=up]")],
        vendor=("Pell Dorsey - Baker", "farmer", [
            "Bread is the one honest thing in this city that gets cheaper the more of it there is. Ask the exchange what is on today.",
            "The farm co-op buys flour and eggs by the order. I buy what the co-op will not take, at a fair price.",
            "Everyone passes the bell on their way somewhere. I hear a good deal and I repeat very little.",
        ]))
    ops += market_stall(20, 27, awning=("blue", "white"), goods=[(0, 2, "minecraft:barrel[facing=up]"), (1, 0, "minecraft:melon")],
        vendor=("Juno Ketterman - Fishmonger", "fisherman", [
            "Fresh from the Blackwater docks, or fresh enough. The river is wider than it looks, so the boats keep me honest.",
            "I carry crates for Hollis and Sons when the stall is slow. The checkpoints look in the hold as well as the cab.",
            "If you want to know what a city is worth, watch what its market sells on a Monday.",
        ]))

    # ------------------------------------------------------------------ the streetscape
    for z in (9, 17, 33, 41):
        ops += lamp_post(12, z) + lamp_post(20, z)
    for x, z in ((1, 15), (31, 15), (1, 23), (31, 23), (1, 31), (31, 31), (1, 40), (31, 40), (1, 44), (31, 44)):
        ops += tree(x, z)

    # ------------------------------------------------------------------ west side (doors on the lane side, the east wall)
    gun = Building(2, 8)
    ops += office(gun, career="gunsmith", name="Ines Calloway - Gunsmith", profession="weaponsmith", workstation="minecraft:grindstone[face=floor,facing=north]",
                  wall="minecraft:stone_bricks", trim="minecraft:stripped_dark_oak_log", base="minecraft:cobblestone", floor="minecraft:smooth_stone",
                  roof="minecraft:deepslate_tile_stairs", ridge="minecraft:deepslate_tiles", ceiling="minecraft:dark_oak_planks", door="dark_oak",
                  shutter="dark_oak", sign_wood="dark_oak", title=["GUNSMITH", "Ines Calloway", "Licensed arms"], rug="red",
                  extras=[put(gun.p(4, 0, 1), "tacz:gun_smith_table", "tacz"), put(gun.p(5, 0, 1), "minecraft:anvil[facing=north]"),
                          # the back door to the firing range
                          fill(gun.p(0, 0, 3), gun.p(0, 1, 3), "minecraft:air"),
                          put(gun.p(0, 0, 3), "minecraft:dark_oak_door[facing=west,half=lower,hinge=left,open=false]"),
                          put(gun.p(0, 1, 3), "minecraft:dark_oak_door[facing=west,half=upper,hinge=left,open=false]"),
                          sign(gun.p(-1, 2, 3), "west", ["RANGE", "out the back"], wood="dark_oak")],
                  lines=[
                      "Licensed sidearms and shotguns for the Valewood co-op, range targets, optic mounts: the orders are on the terminal.",
                      "With the gun smith table in the corner and a blueprint, parts become a real gun. Without the parts, a blueprint is only paper.",
                      "The range is out the back door and along the path. Instructor Vale keeps the score. Arms brokerage is not something I offer; Dray in Blackwater might.",
                  ])
    foundry = Building(2, 16)
    ops += office(foundry, career="smith", name="Brannock Voss - Foundry", profession="toolsmith", workstation="minecraft:smithing_table",
                  wall="minecraft:bricks", trim="minecraft:stripped_dark_oak_log", base="minecraft:stone_bricks", floor="minecraft:smooth_stone",
                  roof="minecraft:stone_brick_stairs", ridge="minecraft:stone_bricks", ceiling="minecraft:dark_oak_planks", door="dark_oak",
                  shutter="dark_oak", sign_wood="dark_oak", title=["FOUNDRY", "Brannock Voss", "Parts and plate"], rug="gray", chimney=5,
                  extras=[put(foundry.p(4, 0, 1), "minecraft:anvil[facing=north]"), put(foundry.p(5, 0, 1), "minecraft:blast_furnace[facing=east,lit=true]"),
                          put(foundry.p(5, 0, 5), "minecraft:iron_block"), put(foundry.p(5, 1, 5), "minecraft:iron_block"),
                          put(foundry.p(4, 0, 2), "minecraft:cauldron")],
                  lines=[
                      "Iron tools for the public workshop, parts for the foundry, plating for the Ironhaven guard. The terminal lists what is open.",
                      "Structural steel and housings pay steady money. Armoury orders for trusted buyers pay more, and ask more of you.",
                      "Unstamped work is a different trade with a different buyer. I do not discuss it where the guard can hear.",
                  ])
    ops += cottage(Building(2, 24), name="Tomas Reyes - Haulier", profession="fisherman", workstation="minecraft:barrel[facing=up]", bed="blue",
                   wall="minecraft:birch_planks", trim="minecraft:stripped_oak_log", base="minecraft:cobblestone", floor="minecraft:oak_planks",
                   roof="minecraft:spruce_stairs", ridge="minecraft:spruce_planks", ceiling="minecraft:oak_planks", door="birch", shutter="birch",
                   sign_wood="birch", title=["HAULIER", "Tomas Reyes", "Hollis and Sons"], lines=[
        "I drive for Hollis and Sons when there is a road worth driving. All three cities are joined now, and every road has its bridge.",
        "Mind the bridges; the river is wider than it looks from the bank. A car is quick, a boat is stranger, your own legs are free.",
        "I keep my pay in the safe by the bed. The apartment lock stops a curious neighbour. It does not stop a lockpick.",
    ])
    ops += cottage(Building(2, 32, depth=6), name="Mira Voss - Retired Courier", profession="cleric", workstation="minecraft:brewing_stand", bed="purple",
                   wall="minecraft:stone_bricks", trim="minecraft:stripped_oak_log", base="minecraft:cobblestone", floor="minecraft:oak_planks",
                   roof="minecraft:stone_brick_stairs", ridge="minecraft:stone_bricks", ceiling="minecraft:oak_planks", door="oak", shutter="oak",
                   sign_wood="oak", title=["MIRA VOSS", "Retired courier"], lines=[
        "Buyers remember. Deliver what you promised and the next order is bigger. Short them and nobody picks up the phone.",
        "Heat fades if you keep your head down and your hands clean. A fine is cheaper than a reputation.",
        "Some doors on this row look locked and are not, and some are locked and look open. Learn which before dark.",
    ])

    # ------------------------------------------------------------------ east side (doors on the lane side, the west wall)
    farm = Building(24, 8, mirror=True)
    ops += office(farm, career="farmer", name="Odette Marsh - Farm Co-op", profession="farmer", workstation="minecraft:composter[level=0]",
                  wall="minecraft:spruce_planks", trim="minecraft:stripped_spruce_log", base="minecraft:cobblestone", floor="minecraft:oak_planks",
                  roof="minecraft:dark_oak_stairs", ridge="minecraft:dark_oak_planks", ceiling="minecraft:oak_planks", door="spruce", shutter="spruce",
                  sign_wood="spruce", title=["FARM CO-OP", "Odette Marsh", "Orders daily"], rug="green",
                  extras=[put(farm.p(4, 0, 1), "minecraft:barrel[facing=up]"), put(farm.p(5, 0, 1), "minecraft:hay_block"), put(farm.p(5, 0, 5), "minecraft:hay_block"),
                          put(farm.p(5, 1, 5), "minecraft:hay_block"), put(farm.p(4, 0, 2), "minecraft:barrel[facing=up]")],
                  lines=[
                      "Orders go up on the terminal: bread, pies, wholesale carrots and potatoes. The co-op pays by the sack, not the story.",
                      "The greenhouse next door keeps its own soil wet. Seeds are cheap at the supply stall, and what you grow is yours to sell.",
                      "A licence lets you grow the controlled crop for the Blackwater dispensary. Without one, do not carry it through a gate.",
                  ])
    ops += cottage(Building(24, 16, mirror=True), name="Ada Brightwater - Market Gardener", profession="farmer", workstation="minecraft:composter[level=0]", bed="green",
                   wall="minecraft:mud_bricks", trim="minecraft:stripped_oak_log", base="minecraft:cobblestone", floor="minecraft:oak_planks",
                   roof="minecraft:oak_stairs", ridge="minecraft:oak_planks", ceiling="minecraft:oak_planks", door="oak", shutter="oak", sign_wood="oak",
                   title=["ADA BRIGHTWATER", "Market gardener"], lines=[
        "I sell to the co-op and the bakery. Anyone who says a carrot is not a business has not counted mine.",
        "There is a safe by my bed. Apartment grade: fine against a neighbour, hopeless against a lockpick. Mind where you keep things.",
        "The guard at the gate searches on evidence, not on suspicion. Honest cargo has never cost me more than a minute.",
    ])
    store = Building(24, 24, mirror=True)
    ops += store.shell(wall="minecraft:stone_bricks", trim="minecraft:stripped_dark_oak_log", base="minecraft:stone_bricks", floor="minecraft:smooth_stone",
                       roof="minecraft:stone_brick_stairs", ridge="minecraft:stone_bricks", ceiling=SPRUCE, door="dark_oak", shutter="dark_oak",
                       sign_wood="dark_oak", title=["STOREHOUSE", "Locked stores"])
    ops += [
        {"op": "safe", "at": list(store.p(1, 0, 2)), "policy": "industrial"},
        {"op": "safe", "at": list(store.p(1, 0, 4)), "policy": "office"},
        put(store.p(5, 0, 1), "minecraft:barrel[facing=up]"), put(store.p(5, 0, 2), "minecraft:barrel[facing=up]"),
        put(store.p(5, 0, 4), "minecraft:barrel[facing=up]"), put(store.p(5, 0, 5), "minecraft:barrel[facing=up]"),
        put(store.p(4, 0, 1), "minecraft:hay_block"), put(store.p(3, 0, 1), "minecraft:barrel[facing=up]"), put(store.p(3, 1, 1), "minecraft:barrel[facing=up]"),
        put(store.p(3, 0, 5), "minecraft:barrel[facing=up]"),
    ]
    registry = Building(24, 32, mirror=True, depth=6)
    ops += registry.shell(wall="minecraft:stone_bricks", trim="minecraft:stripped_spruce_log", base="minecraft:polished_andesite", floor="minecraft:polished_andesite",
                          roof="minecraft:spruce_stairs", ridge="minecraft:spruce_planks", ceiling=SPRUCE, door="spruce", shutter="spruce", sign_wood="spruce",
                          title=["REGISTRY", "Register here", "Imani Okafor"], banners="blue")
    ops += [
        {"op": "terminal", "at": list(registry.p(1, 0, 2)), "facing": registry.face("east")},
        put(registry.p(3, 0, 1), "minecraft:cartography_table"),
        put(registry.p(5, 0, 1), "minecraft:bookshelf"), put(registry.p(5, 0, 4), "minecraft:bookshelf"),
        put(registry.p(5, 1, 1), "minecraft:bookshelf"), put(registry.p(5, 1, 4), "minecraft:bookshelf"),
        npc(registry.p(2, 0, 3), "registrar", "Imani Okafor - Registrar", "cartographer", [
            "Welcome to the register. Choose a home city and a trade: the clerk gives you a welcome pack and an Operations Terminal.",
            "The register keeps one entry for each citizen. Your home city is where your name is on file, and your trade decides what you can make.",
            "Nobody here asks about your past. Do the work, keep your papers straight, and the register has no reason to ask.",
        ]),
    ]
    ops += registry.rug((3, 2), (4, 3), "blue") + registry.bed(1, 4, "yellow")

    # the vintner's cellar office (east side, south of the registry) and a small vineyard opposite it. Vinery and Kaleidoscope Tavern blocks are placed only
    # when those mods are installed; the vanilla barrels and the cauldron stand in for them so the rooms are furnished either way.
    vintner = Building(24, 40, mirror=True)
    ops += office(vintner, career="vintner", name="Lucien Faure - Cellar Master", profession="leatherworker", workstation="minecraft:cauldron",
                  wall="minecraft:mud_bricks", trim="minecraft:stripped_dark_oak_log", base="minecraft:stone_bricks", floor="minecraft:spruce_planks",
                  roof="minecraft:brick_stairs", ridge="minecraft:bricks", ceiling="minecraft:dark_oak_planks", door="dark_oak",
                  shutter="dark_oak", sign_wood="dark_oak", title=["WINE CELLAR", "Lucien Faure", "Orders daily"], rug="purple",
                  extras=[put(vintner.p(4, 0, 1), "vinery:fermentation_barrel[facing=south]", "vinery"), put(vintner.p(5, 0, 1), "vinery:grapevine_pot", "vinery"),
                          put(vintner.p(4, 0, 2), "minecraft:barrel[facing=up]"), put(vintner.p(5, 0, 2), "minecraft:barrel[facing=up]"),
                          put(vintner.p(5, 0, 4), "vinery:oak_wine_rack_big[facing=west]", "vinery"), put(vintner.p(5, 0, 3), "vinery:basket", "vinery"),
                          put(vintner.p(5, 1, 4), "minecraft:barrel[facing=up]"),
                          put(vintner.p(1, 1, 5), "kaleidoscope_tavern:barrel", "kaleidoscope_tavern"), put(vintner.p(1, 0, 5), "minecraft:barrel[facing=up]")],
                  lines=[
                      "Table wine for the tavern, estate cellars, a still column for the co-op: the orders are on the terminal. Wine is patience in a bottle.",
                      "Strong spirit is another matter. Valewood licenses distillers and Ironhaven allows none. A back bar there pays well for bottles with no stamp.",
                      "Keep your head down and the heat fades. A fine is cheaper than a reputation, and a seizure is neither.",
                  ])
    ops += [
        fill((2, -1, 40), (8, -1, 45), "minecraft:grass_block"),
        fill((3, -1, 41), (7, -1, 44), "minecraft:farmland[moisture=7]"), put((5, -1, 42), "minecraft:water"), put((5, -1, 43), "minecraft:water"),
        fill((2, 0, 40), (8, 0, 40), "minecraft:spruce_fence"), fill((2, 0, 45), (8, 0, 45), "minecraft:spruce_fence"),
        fill((2, 0, 41), (2, 0, 44), "minecraft:spruce_fence"), fill((8, 0, 41), (8, 0, 44), "minecraft:spruce_fence"),
        fill((8, 0, 42), (8, 0, 42), "minecraft:air"),
        put((3, 0, 41), "vinery:red_grape_bush[age=3]", "vinery"), put((3, 0, 43), "vinery:red_grape_bush[age=2]", "vinery"),
        put((7, 0, 41), "vinery:white_grape_bush[age=3]", "vinery"), put((7, 0, 43), "vinery:white_grape_bush[age=1]", "vinery"),
        put((4, 0, 41), "minecraft:sweet_berry_bush[age=3]"), put((6, 0, 44), "minecraft:sweet_berry_bush[age=2]"),
        put((4, 0, 44), "minecraft:potted_fern"), put((6, 0, 41), "minecraft:potted_fern"),
        standing_sign((5, 0, 39), 8, ["VINEYARD", "Cellar's own"], wood="dark_oak"),
    ]

    # ------------------------------------------------------------------ the gate, its booth and its guard
    gate = DEPTH - 3
    ops += [
        fill((12, 0, gate), (12, 5, gate), "minecraft:stone_bricks"), fill((20, 0, gate), (20, 5, gate), "minecraft:stone_bricks"),
        fill((12, 5, gate), (20, 5, gate), "minecraft:stone_bricks"),
        fill((13, 4, gate), (19, 4, gate), "minecraft:stone_brick_slab[type=top]"),
        put((13, 3, gate), "minecraft:lantern[hanging=true]"), put((19, 3, gate), "minecraft:lantern[hanging=true]"),
        put((12, 6, gate), "minecraft:stone_brick_wall"), put((20, 6, gate), "minecraft:stone_brick_wall"),
        put((12, 7, gate), "minecraft:lantern[hanging=false]"), put((20, 7, gate), "minecraft:lantern[hanging=false]"),
        sign((16, 5, gate - 1), "north", ["FOUNDERS ROW", "Welcome"], color="white", wood="dark_oak"),
        sign((16, 5, gate + 1), "south", ["FOUNDERS ROW", "Mind the gate"], color="white", wood="dark_oak"),
        # the guard's booth, west of the gate: a roofed corner open to the lane, a post (the blast furnace) and a lantern
        fill((9, -1, gate - 2), (11, -1, gate), "minecraft:smooth_stone"),
        fill((9, 0, gate - 2), (9, 2, gate - 2), "minecraft:spruce_fence"), fill((9, 0, gate), (9, 2, gate), "minecraft:spruce_fence"),
        fill((11, 0, gate - 2), (11, 2, gate - 2), "minecraft:spruce_fence"),
        fill((9, 3, gate - 2), (11, 3, gate), "minecraft:spruce_slab[type=bottom]"), put((10, 2, gate - 1), "minecraft:lantern[hanging=true]"),
        put((10, 0, gate - 2), "minecraft:blast_furnace[facing=south]"), put((10, 1, gate - 2), "minecraft:lantern[hanging=false]"),
        npc([10, 0, gate], "guard", "Sergeant Halloran - Gate Guard", "armorer", [
            "I keep the lane clear. The real checkpoints are out on the roads between the cities, and they search on evidence, not suspicion.",
            "Carry what your licence covers and you will never see the same inspector twice. Carry what it does not, and that is your trouble to carry.",
            "Ironhaven wants plating, Valewood wants guns for the hunt, Blackwater wants quiet. The gates know the difference.",
        ]),
    ]
    assert_citizens_have_room(ops)
    return {
        "schema_version": 1, "id": "founders_row", "title": "Founders Row",
        "note": "Operator-built lot for a fresh world; origin is the north-west corner, extending east and south. The south end of the lane meets a road.",
        "size": [WIDTH, HEIGHT + 4, DEPTH], "ops": ops,
    }


def standing_sign(at, rotation, lines, wood="spruce", color="black"):
    assert all(len(line) <= 15 for line in lines), f"a sign line is longer than the 15 characters a sign can show: {lines}"
    return {"op": "sign", "at": list(at), "block": f"minecraft:{wood}_sign[rotation={rotation}]", "lines": lines, "color": color, "glow": False}


def firing_range() -> dict:
    """An outdoor range built west of the Row: a firing line with three booths, grass lanes between hedges, an earth bank at the far end and a
    target at 10, 20 and 30 blocks (one per lane). The line is at the east end, where the gunsmith's back door and path arrive.

    Local x runs from the earth bank (0) to the firing line (39); the booths stand at x 36..38; lane centres are z 3, 7 and 11.
    """
    width, depth = 40, 15
    line = 37
    ops = [
        fill((0, -4, 0), (width - 1, -2, depth - 1), "minecraft:stone"),
        fill((0, -1, 0), (width - 1, -1, depth - 1), "minecraft:grass_block"),
        fill((0, 0, 0), (width - 1, 6, depth - 1), "minecraft:air"),
        fill((34, -1, 0), (width - 1, -1, depth - 1), "minecraft:stone_bricks"),          # the apron behind the line
        fill((width - 5, -1, 6), (width - 1, -1, 8), "minecraft:polished_andesite"),       # the path from the gunsmith's door
        # the earth bank that stops everything
        fill((0, 0, 0), (2, 4, depth - 1), "minecraft:dirt"), fill((0, 5, 0), (2, 5, depth - 1), "minecraft:grass_block"),
        # hedges along both sides, and between the lanes at the line
        fill((3, 0, 0), (33, 1, 0), "minecraft:oak_leaves[persistent=true]"), fill((3, 0, depth - 1), (33, 1, depth - 1), "minecraft:oak_leaves[persistent=true]"),
    ]
    for z, distance in ((3, 10), (7, 20), (11, 30)):
        x = line - distance
        ops += [
            fill((x, 0, z), (x, 0, z), "minecraft:hay_block"), put((x, 1, z), "minecraft:target"), put((x, 2, z), "minecraft:target"),
            fill((x, 0, z - 1), (x, 2, z - 1), "minecraft:spruce_fence"), fill((x, 0, z + 1), (x, 2, z + 1), "minecraft:spruce_fence"),
            put((x, 3, z - 1), "minecraft:lantern[hanging=false]"),
            standing_sign((x + 2, 0, z + 1), 4, [f"{distance} blocks", f"Lane {(z - 3) // 4 + 1}"]),
            # the booth: a counter, posts and a roof
            put((line, 0, z), "minecraft:barrel[facing=west]"),
            put((line, 1, z), "minecraft:oak_slab[type=bottom]"),
            fill((line, 0, z - 1), (line, 2, z - 1), "minecraft:spruce_fence"), fill((line, 0, z + 1), (line, 2, z + 1), "minecraft:spruce_fence"),
            fill((line, 3, z - 1), (line + 2, 3, z + 1), "minecraft:spruce_slab[type=bottom]"), put((line + 1, 2, z), "minecraft:lantern[hanging=true]"),
        ]
    ops += [
        put((line + 1, 0, 5), "minecraft:spruce_stairs[facing=west,half=bottom]"), put((line + 1, 0, 9), "minecraft:spruce_stairs[facing=west,half=bottom]"),
        put((line + 2, 0, 5), "minecraft:barrel[facing=up]"), put((line + 2, 0, 9), "minecraft:barrel[facing=up]"),
        standing_sign((line - 1, 0, 5), 4, ["RANGE RULES", "Stand at line", "Hits are scored", "Best is kept"]),
        npc([line + 1, 0, 7], "instructor", "Instructor Vale", "weaponsmith", [
            "Stand at the line, face down the range and fire at the targets: 10, 20 and 30 blocks. Hits are scored by how near the centre they land.",
            "A steady hand beats a fast one. Take the shot, breathe out, and count the hole.",
            "Nobody pays for the range. It is practice, and the licence office likes to see that you have had some.",
        ]),
    ]
    assert_citizens_have_room(ops)
    return {"schema_version": 1, "id": "firing_range", "title": "Firing range",
            "note": "Built west of Founders Row by the dev launch (or by hand); origin is the north-west corner, extending east and south. The firing line is at the east end.",
            "size": [width, 8, depth], "ops": ops}


def checkpoint_post() -> dict:
    """A roadside inspection post, built beside a road at each checkpoint zone by ``/uc_checkpoints place``.

    A 9 x 9 paved lot with a small booth, lanterns and one guard (a villager armorer with a blast furnace as their post). It is repeatable:
    one copy per checkpoint, each reversible on its own. It stands beside the road rather than across it, so it never blocks a traveller; the
    zone, not the building, decides who is searched (a traveller may leave the road and walk around, which is the smuggler's trail).
    """
    ops = [
        fill((0, -4, 0), (8, -2, 8), "minecraft:stone"),
        fill((0, -1, 0), (8, -1, 8), "minecraft:stone_bricks"),
        fill((0, 0, 0), (8, 7, 8), "minecraft:air"),
        # the booth: 3 x 3 with a window on each side and a doorway to the south
        fill((3, -1, 2), (5, -1, 4), "minecraft:polished_andesite"),
        fill((3, 0, 2), (5, 2, 4), "minecraft:stone_bricks"),
        fill((4, 0, 3), (4, 2, 3), "minecraft:air"),
        fill((4, 0, 4), (4, 1, 4), "minecraft:air"),
        put((3, 1, 3), "minecraft:glass_pane"), put((5, 1, 3), "minecraft:glass_pane"), put((4, 1, 2), "minecraft:glass_pane"),
        fill((2, 3, 1), (6, 3, 5), "minecraft:stone_brick_slab[type=bottom]"),
        put((4, 2, 3), "minecraft:lantern[hanging=true]"),
        put((5, 0, 6), "minecraft:barrel[facing=up]"),
        put((3, 0, 6), "minecraft:blast_furnace[facing=south]"),
        put((3, 1, 6), "minecraft:lantern"),
    ]
    for x, z in ((0, 0), (8, 0), (0, 8), (8, 8)):
        ops += [fill((x, 0, z), (x, 2, z), "minecraft:stone_brick_wall"), put((x, 3, z), "minecraft:lantern")]
    ops.append(npc((4, 0, 6), "guard", "Checkpoint Guard", "armorer", [
        "Routine inspection post. We search on what we see and what we are told, not on what we guess. Carry what your papers cover.",
        "A permit makes a restricted load legal here. Without one, I confiscate it and the fine comes out of your wallet.",
        "The post covers the road, not the countryside. Past the last milestone, the hills are nobody's business, and the hills are not kind.",
        "Searches cool off. The more you are caught, the sooner we look again. Keep your bags in order and the gate stays easy.",
        "Ride in on a boat or a cart and I search its hold along with you. A boat left outside my zone is not my business; what I cannot see, I do not know.",
    ]))
    return {
        "schema_version": 1, "id": "checkpoint_post", "title": "Checkpoint post", "repeatable": True,
        "note": "A guard post built beside the road at a checkpoint zone; origin is the north-west corner, extending east and south.",
        "size": [9, 12, 9], "ops": ops,
    }


def wayside_shelter() -> dict:
    """A small open pavilion beside a road, built at run time by ``WaysideShelters`` (D-079) a few hundred blocks from the nearest city.

    An 11 x 9 lot: a stone-brick floor on a coarse-dirt apron, four log posts under a low spruce roof, a campfire with a seat on each side, a rain
    barrel, a cauldron of water, a cartography table and a road warden. It has no front: it is open on all four sides and is the same from every
    direction, because a road can run any way and the lot cannot be turned. It is repeatable (one copy per stretch of road, each reversible).
    """
    ops = [
        fill((0, -4, 0), (10, -2, 8), "minecraft:stone"),
        fill((0, -1, 0), (10, -1, 8), "minecraft:coarse_dirt"),
        fill((0, 0, 0), (10, 7, 8), "minecraft:air"),
        fill((2, -1, 1), (8, -1, 7), "minecraft:stone_bricks"),
        # the roof: planks on the posts, a ring of stairs rising to the middle, a slab cap
        fill((2, 4, 1), (8, 4, 7), "minecraft:spruce_planks"),
        fill((3, 5, 2), (7, 5, 6), "minecraft:spruce_slab[type=bottom]"),
        fill((1, 4, 1), (1, 4, 7), "minecraft:spruce_stairs[facing=east,half=bottom]"),
        fill((9, 4, 1), (9, 4, 7), "minecraft:spruce_stairs[facing=west,half=bottom]"),
        fill((2, 4, 0), (8, 4, 0), "minecraft:spruce_stairs[facing=south,half=bottom]"),
        fill((2, 4, 8), (8, 4, 8), "minecraft:spruce_stairs[facing=north,half=bottom]"),
    ]
    for x, z in ((1, 0), (9, 0), (1, 8), (9, 8)):
        ops.append(put((x, 4, z), "minecraft:spruce_slab[type=bottom]"))
    for x, z in ((2, 1), (8, 1), (2, 7), (8, 7)):
        ops.append(fill((x, 0, z), (x, 3, z), "minecraft:stripped_spruce_log"))
    for x, z in ((5, 1), (5, 7), (2, 4), (8, 4)):
        ops.append(put((x, 3, z), "minecraft:lantern[hanging=true]"))
    ops += [
        put((5, 0, 4), "minecraft:campfire[lit=true]"),
        # four seats facing the fire: a stair's back is on the side it faces, so each one faces away from the fire
        put((3, 0, 4), "minecraft:spruce_stairs[facing=west,half=bottom]"), put((7, 0, 4), "minecraft:spruce_stairs[facing=east,half=bottom]"),
        put((5, 0, 2), "minecraft:spruce_stairs[facing=north,half=bottom]"), put((5, 0, 6), "minecraft:spruce_stairs[facing=south,half=bottom]"),
        put((3, 0, 2), "minecraft:barrel[facing=up]"), put((3, 0, 6), "minecraft:water_cauldron[level=3]"), put((7, 0, 6), "minecraft:cartography_table"),
        sign((2, 2, 2), "south", ["WAYSIDE", "REST STOP", "Fire and water", "Free to all"]),
        sign((8, 2, 6), "north", ["ROAD WARDEN", "on duty", "Ask about the", "road ahead"]),
    ]
    ops.append(npc((6, 0, 5), "warden", "Road Warden", "cartographer", [
        "Water is free and the fire is never out. Rest, then move on: the road is safer between the posts than anywhere off it.",
        "Every post stops what its city's law says to stop, and not an ounce more. Read the law before you load the cart, not after.",
        "A bridge is the safe way over and a boat is the quiet one. Neither beats good boots and a clear head.",
        "I keep this shelter for whoever comes by. A fire is a fire, honest haulier or not.",
        "Heat fades while you keep to honest work. Walk the long road a few times and the guards stop looking twice.",
    ]))
    assert_citizens_have_room(ops)
    return {
        "schema_version": 1, "id": "wayside_shelter", "title": "Wayside shelter", "repeatable": True,
        "note": "An open pavilion with a campfire and a road warden, built beside a road a few hundred blocks from the nearest city; origin is the north-west corner, extending east and south.",
        "size": [11, 8, 9], "ops": ops,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = {"founders_row": founders_row(), "firing_range": firing_range(), "checkpoint_post": checkpoint_post(), "wayside_shelter": wayside_shelter()}
    status = 0
    for name, data in outputs.items():
        out = DIR / f"{name}.json"
        text = json.dumps(data, indent=1, ensure_ascii=False) + "\n"
        if args.check:
            if not out.exists() or out.read_text(encoding="utf-8") != text:
                print(f"Committed district {name} differs; run scripts/generate_district.py")
                status = 1
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        citizens = sum(1 for op in data["ops"] if op["op"] == "npc")
        print(f"Wrote {out.relative_to(ROOT)} with {len(data['ops'])} operations and {citizens} citizens")
    if args.check and status == 0:
        print("Districts match the generator")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
