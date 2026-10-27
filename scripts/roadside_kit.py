#!/usr/bin/env python3
"""A kit for writing ORIGINAL roadside places as districts (D-138).

A roadside place is a district (``data/underground_city/districts``): a list of operations relative to a lot's north-west corner. Writing those by hand is slow, so a place here is a
few lines of Python on a canvas: walls, floors, roofs, doors, windows and furniture by block coordinates, plus the district's special operations (a shopkeeper, a rentable room, a
safe, a loot container, a sign). The canvas is drawn with the place's front (the side that faces the road) to the NORTH; the kit writes four pre-rotated variants of it, one per
direction the front can face, because a road runs every which way and the engine (``RoadsidePlaces``) builds the variant whose front looks at the road. A world builds one variant of
a place, so the variants share their home ids (``home_prefix``).

Coordinates: x grows to the east (0 to w-1), z to the south (0 to d-1, z = 0 is the road side), y upward from the standing level (y = -1 is the ground, -4 to -2 a foundation the
district lays itself). Furniture from Modern Life (a runtime-only download, never redistributed) is written as optional operations (``requires_mod``): without the mod the room is
still furnished with vanilla blocks, just less.

Nothing here is anybody's content: every plan is drawn for this project.
"""
from __future__ import annotations

import json
import re

DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}
CW = {"north": "east", "east": "south", "south": "west", "west": "north"}          # a quarter turn clockwise, seen from above
TURNS = {"north": 0, "east": 1, "south": 2, "west": 3}                                # quarter turns that take a north-facing front to this facing
SUFFIX = {"north": "n", "east": "e", "south": "s", "west": "w"}
END_DIR = {"n_end": "north", "e_end": "east", "s_end": "south", "w_end": "west"}
DIR_END = {v: k for k, v in END_DIR.items()}


def block(name: str, **props) -> str:
    ident = name if ":" in name else "minecraft:" + name
    if props:
        ident += "[" + ",".join(f"{k}={str(v).lower() if isinstance(v, bool) else v}" for k, v in props.items()) + "]"
    return ident


def ml(name: str, **props) -> str:
    """A Modern Life block (an optional operation: skipped where the mod is absent)."""
    return block("modernlife:" + name, **props)


def split(spec: str) -> tuple[str, dict]:
    m = re.fullmatch(r"([a-z0-9_.-]+:[a-z0-9_/.-]+)(?:\[(.*)\])?", spec)
    if not m:
        raise ValueError("bad block " + spec)
    props = {}
    if m.group(2):
        for pair in m.group(2).split(","):
            k, v = pair.split("=")
            props[k] = v
    return m.group(1), props


def join(name: str, props: dict) -> str:
    return name + ("[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]" if props else "")


def turn_dir(d: str, k: int) -> str:
    for _ in range(k):
        d = CW[d]
    return d


def turn_spec(spec: str, k: int) -> str:
    """The block state of ``spec`` after the place is turned ``k`` quarter turns clockwise."""
    if k % 4 == 0:
        return spec
    name, props = split(spec)
    out = {}
    for key, value in props.items():
        if key == "facing" and value in DIRS:
            out[key] = turn_dir(value, k)
        elif key == "axis" and value in ("x", "z") and k % 2 == 1:
            out[key] = "z" if value == "x" else "x"
        elif key == "rotation":
            out[key] = str((int(value) + 4 * k) % 16)
        elif key == "type" and value in END_DIR:
            out[key] = DIR_END[turn_dir(END_DIR[value], k)]
        elif key in DIRS:                      # connections (walls, vines): the key itself is a direction
            out[turn_dir(key, k)] = value
        else:
            out[key] = value
    return join(name, out)


class Place:
    """A canvas for one roadside place. ``h`` is the highest y the place uses above the standing level."""

    def __init__(self, pid: str, title: str, w: int, d: int, h: int, ground: str = "minecraft:grass_block", note: str = ""):
        self.id, self.title, self.w, self.d, self.h, self.note = pid, title, w, d, h, note
        self.ground = ground
        self.blocks: dict[tuple[int, int, int], str] = {}
        self.specials: list[dict] = []
        self.depth = 4           # foundation depth below the standing level (y -4 .. -2); a basement asks for more

    # ---------------------------------------------------------------------------------------------- blocks
    def put(self, x: int, y: int, z: int, spec: str | None) -> None:
        if spec is None:
            return
        if not (0 <= x < self.w and 0 <= z < self.d and -self.depth - 4 <= y <= self.h):
            raise ValueError(f"{self.id}: {x},{y},{z} is outside the lot")
        self.blocks[(x, y, z)] = spec if spec != "air" else "minecraft:air"

    def fill(self, x0, y0, z0, x1, y1, z1, spec) -> None:
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.put(x, y, z, spec)

    def floor(self, x0, z0, x1, z1, spec, y=-1) -> None:
        self.fill(x0, y, z0, x1, y, z1, spec)

    def walls(self, x0, z0, x1, z1, y0, y1, spec) -> None:
        """The four walls of a box (corners included), y0 to y1."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, z0, spec); self.put(x, y, z1, spec)
            for z in range(z0, z1 + 1):
                self.put(x0, y, z, spec); self.put(x1, y, z, spec)

    def clear(self, x0, y0, z0, x1, y1, z1) -> None:
        self.fill(x0, y0, z0, x1, y1, z1, "minecraft:air")

    def door(self, x, y, z, facing, material="oak_door", hinge="left") -> None:
        """A door whose front looks at ``facing`` (a person walks in by walking the other way)."""
        name = material if ":" in material else "minecraft:" + material
        self.put(x, y, z, f"{name}[facing={facing},half=lower,hinge={hinge},open=false]")
        self.put(x, y + 1, z, f"{name}[facing={facing},half=upper,hinge={hinge},open=false]")

    def window(self, x, y, z, spec="minecraft:glass_pane", height=1) -> None:
        for dy in range(height):
            self.put(x, y + dy, z, spec)

    def gable_roof(self, x0, z0, x1, z1, y, stair, slab, along="x", overhang=1, fill_block=None) -> int:
        """A pitched roof over the box x0..x1, z0..z1 whose ridge runs along x (or z), starting at height y; returns the ridge height."""
        if along == "x":
            lo, hi = z0 - overhang, z1 + overhang
            a0, a1 = x0 - overhang, x1 + overhang
        else:
            lo, hi = x0 - overhang, x1 + overhang
            a0, a1 = z0 - overhang, z1 + overhang
        level = 0
        while lo + level <= hi - level:
            near, far = lo + level, hi - level
            yy = y + level
            for a in range(a0, a1 + 1):
                if near == far:
                    spec = slab
                    self._roof_put(along, a, yy, near, slab)
                    continue
                if along == "x":
                    self._roof_put(along, a, yy, near, f"{stair}[facing=south,half=bottom]")
                    self._roof_put(along, a, yy, far, f"{stair}[facing=north,half=bottom]")
                else:
                    self._roof_put(along, a, yy, near, f"{stair}[facing=east,half=bottom]")
                    self._roof_put(along, a, yy, far, f"{stair}[facing=west,half=bottom]")
            # the gable ends: wall above the box under this course
            if fill_block and level > 0:
                for c in range(near, far + 1):
                    if along == "x":
                        if z0 <= c <= z1:
                            self.put(x0, yy - 1, c, fill_block); self.put(x1, yy - 1, c, fill_block)
                    else:
                        if x0 <= c <= x1:
                            self.put(c, yy - 1, z0, fill_block); self.put(c, yy - 1, z1, fill_block)
            level += 1
        return y + level - 1

    def _roof_put(self, along, a, y, c, spec):
        x, z = (a, c) if along == "x" else (c, a)
        if 0 <= x < self.w and 0 <= z < self.d:
            self.put(x, y, z, spec)

    def flat_roof(self, x0, z0, x1, z1, y, spec, parapet=None) -> None:
        self.fill(x0, y, z0, x1, y, z1, spec)
        if parapet:
            self.walls(x0, z0, x1, z1, y + 1, y + 1, parapet)

    def lamp_post(self, x, z, y=0) -> None:
        """A street light: Modern Life's three-part lamp where the mod is present, a lantern on a fence post always."""
        self.put(x, y, z, "minecraft:spruce_fence")
        self.put(x, y + 1, z, "minecraft:spruce_fence")
        self.put(x, y + 2, z, "minecraft:lantern[hanging=false]")

    def bed(self, x, y, z, head_toward, colour="red") -> None:
        dx, dz = DIRS[head_toward]
        self.put(x, y, z, f"minecraft:{colour}_bed[facing={head_toward},part=foot]")
        self.put(x + dx, y, z + dz, f"minecraft:{colour}_bed[facing={head_toward},part=head]")

    def seat(self, x, y, z, looks, wood="oak") -> None:
        """A seat a person sits on looking toward ``looks``: a Modern Life chair, or a stair whose back is behind the sitter."""
        self.put(x, y, z, ml(f"{wood}_chair", facing=OPPOSITE[looks]))

    def stair_seat(self, x, y, z, looks, material="spruce_stairs") -> None:
        self.put(x, y, z, block(material, facing=OPPOSITE[looks], half="bottom"))

    def post_table(self, x, y, z, wood="oak") -> None:
        """A vanilla table: a fence post under a pressure plate."""
        self.put(x, y, z, f"minecraft:{wood}_fence")
        self.put(x, y + 1, z, f"minecraft:{wood}_pressure_plate")

    # ---------------------------------------------------------------------------------------------- the district's own operations
    def npc(self, x, y, z, role, name, profession="none", lines=(), shop=None, career=None) -> None:
        op = {"op": "npc", "at": [x, y, z], "role": role, "name": name, "profession": profession, "lines": list(lines)}
        if shop:
            op["shop"] = shop
        if career:
            op["career"] = career
        self.specials.append(op)

    def terminal(self, x, y, z, facing) -> None:
        self.specials.append({"op": "terminal", "at": [x, y, z], "facing": facing})

    def safe(self, x, y, z, facing, policy) -> None:
        self.specials.append({"op": "safe", "at": [x, y, z], "facing": facing, "policy": policy})

    def loot(self, x, y, z, spec, table) -> None:
        self.specials.append({"op": "loot", "at": [x, y, z], "block": spec, "table": table})

    def sign(self, x, y, z, spec, lines, color="black", glow=False) -> None:
        for line in lines:
            if len(line) > 15:
                raise ValueError(f"{self.id}: sign line longer than 15 characters: {line}")
        self.specials.append({"op": "sign", "at": [x, y, z], "block": spec, "lines": list(lines), "color": color, "glow": glow})

    def residence(self, hid, title, kind, price, frm, to) -> None:
        self.specials.append({"op": "residence", "id": hid, "title": title, "kind": kind, "price": price, "from": list(frm), "to": list(to)})

    # ---------------------------------------------------------------------------------------------- output
    def boxes(self) -> list[tuple[tuple[int, int, int], tuple[int, int, int], str]]:
        """The blocks merged into boxes of one block each (greedy: along x, then z, then y)."""
        left = dict(self.blocks)
        out = []
        for key in sorted(left, key=lambda p: (p[1], p[2], p[0])):
            if key not in left:
                continue
            spec = left[key]
            x0, y0, z0 = key
            x1 = x0
            while (x1 + 1, y0, z0) in left and left[(x1 + 1, y0, z0)] == spec:
                x1 += 1
            z1 = z0
            while all(left.get((x, y0, z1 + 1)) == spec for x in range(x0, x1 + 1)):
                z1 += 1
            y1 = y0
            while all(left.get((x, y1 + 1, z)) == spec for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)):
                y1 += 1
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    for z in range(z0, z1 + 1):
                        del left[(x, y, z)]
            out.append(((x0, y0, z0), (x1, y1, z1), spec))
        return out

    def variant(self, facing: str, namespace_prefix: str = "rs_") -> dict:
        """The district whose front faces ``facing`` (four of them per place)."""
        k = TURNS[facing]

        def turn(p):
            x, z = p[0], p[2]
            ww, dd = self.w, self.d
            for _ in range(k):
                x, z = dd - 1 - z, x
                ww, dd = dd, ww
            return [x, p[1], z]

        size_w, size_d = (self.w, self.d) if k % 2 == 0 else (self.d, self.w)
        ops = [
            {"op": "fill", "from": [0, -self.depth, 0], "to": [size_w - 1, -2, size_d - 1], "block": "minecraft:stone"},
            {"op": "fill", "from": [0, -1, 0], "to": [size_w - 1, -1, size_d - 1], "block": self.ground},
            {"op": "fill", "from": [0, 0, 0], "to": [size_w - 1, self.h, size_d - 1], "block": "minecraft:air"},
        ]
        for (a, b, spec) in self.boxes():
            ta, tb = turn(a), turn(b)
            op = {"op": "fill" if a != b else "block"}
            if a == b:
                op["at"] = ta
            else:
                op["from"] = [min(ta[0], tb[0]), min(ta[1], tb[1]), min(ta[2], tb[2])]
                op["to"] = [max(ta[0], tb[0]), max(ta[1], tb[1]), max(ta[2], tb[2])]
            op["block"] = turn_spec(spec, k)
            if spec.startswith("modernlife:"):
                op["requires_mod"] = "modernlife"
            ops.append(op)
        for special in self.specials:
            op = json.loads(json.dumps(special))
            if "at" in op:
                op["at"] = turn(op["at"])
            if "from" in op:
                op["from"], op["to"] = turn(op["from"]), turn(op["to"])
            if "facing" in op:
                op["facing"] = turn_dir(op["facing"], k)
            if "block" in op:
                op["block"] = turn_spec(op["block"], k)
                if op["block"].startswith("modernlife:"):
                    op["requires_mod"] = "modernlife"
            if "terminal" in op:
                op["terminal"] = turn(op["terminal"])
            ops.append(op)
        did = f"{namespace_prefix}{self.id}_{SUFFIX[facing]}"
        return {
            "schema_version": 1,
            "id": did,
            "title": self.title,
            "home_prefix": f"{namespace_prefix}{self.id}",
            "note": f"Generated by scripts/roadside_places.py (D-138); do not edit by hand. {self.note} This variant's front, the side that faces the road, looks {facing}; origin is the north-west corner, extending east and south.",
            "size": [size_w, self.h + self.depth + 1, size_d],
            "ops": ops,
        }
