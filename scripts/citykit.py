#!/usr/bin/env python3
"""A small kit for writing ORIGINAL Lost Cities buildings as code (D-117).

A Lost Cities building is a list of *parts*; a part is one 16 x 16 floor of six block layers ("slices", bottom first; the rows of a slice run north to south, the
characters of a row west to east), and every character in it means a block through a *palette*. Writing those grids by hand is slow and error-prone, so a building here is
a few lines of Python: place walls, rooms, doors, windows and furniture by block coordinates, and the kit allocates the characters, builds the palette and writes the
JSON. Nothing here is anybody's content: every plan is drawn for this project.

Coordinates: x grows to the east, z to the south, y upward within the floor (0 is the floor slab, 1 to 3 the room, 4 and 5 the ceiling). A direction is a Minecraft
horizontal direction name; ``facing`` of furniture is the side its front looks at.

Furniture comes from vanilla Minecraft and from Modern Life (already in the development pack as a runtime-only download, licence all rights reserved, never
redistributed): the names below are Modern Life block ids with the block-state properties its blockstates declare. A Lost Cities palette that names a block that is not
installed is a problem the world-generation check reports; the buildings are therefore only added to the pack that is generated for a game that has Modern Life.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field

FLOOR_H = 6
SIZE = 16
CHAR_POOL = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!$%&*+,-./:;<=>?@[]^_`{|}~"
DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}
LEFT_OF = {"north": "west", "west": "south", "south": "east", "east": "north"}   # turning left from facing
RIGHT_OF = {v: k for k, v in LEFT_OF.items()}


def block(name: str, **props) -> str:
    """``block("oak_door", facing="north", half="lower")`` -> ``minecraft:oak_door[facing=north,half=lower]``; a name with a colon keeps its namespace."""
    ident = name if ":" in name else "minecraft:" + name
    if props:
        ident += "[" + ",".join(f"{k}={str(v).lower() if isinstance(v, bool) else v}" for k, v in props.items()) + "]"
    return ident


def ml(name: str, **props) -> str:
    """A Modern Life block."""
    return block("modernlife:" + name, **props)


@dataclass
class Palette:
    """The characters of one building: a block (or a weighted list of blocks) for each, allocated on demand."""
    name: str
    entries: dict[str, object] = field(default_factory=dict)       # char -> block string | list[(weight, block)]
    by_spec: dict[str, str] = field(default_factory=dict)

    def char(self, spec: "str | list") -> str:
        """The character for a block string, or for a list ``[(chance in thousandths, "block"), ..., (0, "default block")]`` (the last entry is what is left)."""
        key = spec if isinstance(spec, str) else json.dumps(spec)
        if key in self.by_spec:
            return self.by_spec[key]
        free = [c for c in CHAR_POOL if c not in self.entries]
        if not free:
            raise ValueError(f"palette {self.name} ran out of characters")
        c = free[0]
        self.entries[c] = spec
        self.by_spec[key] = c
        return c

    def to_json(self) -> dict:
        out = []
        for c, spec in self.entries.items():
            if isinstance(spec, str):
                out.append({"char": c, "block": spec})
            else:
                # Lost Cities tries the entries in order and takes the first whose "random" beats a draw from 0 to 999 (a chance in thousandths); the last entry (1000) is the default
                options = [{"random": chance, "block": b} for chance, b in spec[:-1]] + [{"random": 1000, "block": spec[-1][1]}]
                out.append({"char": c, "blocks": options})
        return {"palette": out}


class Part:
    """One floor: six layers of 16 x 16 characters. A space is air."""

    def __init__(self, name: str, palette: Palette, xsize: int = SIZE, zsize: int = SIZE):
        self.name, self.palette, self.xsize, self.zsize = name, palette, xsize, zsize
        self.layers = [[[" "] * xsize for _ in range(zsize)] for _ in range(FLOOR_H)]

    # ------------------------------------------------------------------ primitives
    def put(self, x: int, y: int, z: int, spec: "str | list | None") -> None:
        """One block; ``None`` or "air" clears it."""
        if not (0 <= x < self.xsize and 0 <= z < self.zsize and 0 <= y < FLOOR_H):
            raise ValueError(f"{self.name}: ({x},{y},{z}) is outside the part")
        self.layers[y][z][x] = " " if spec in (None, "air") else self.palette.char(spec)

    def get(self, x: int, y: int, z: int) -> str:
        return self.layers[y][z][x]

    def fill(self, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int, spec) -> None:
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for z in range(min(z0, z1), max(z0, z1) + 1):
                for x in range(min(x0, x1), max(x0, x1) + 1):
                    self.put(x, y, z, spec)

    def floor(self, x0: int, z0: int, x1: int, z1: int, spec, y: int = 0) -> None:
        self.fill(x0, y, z0, x1, y, z1, spec)

    def ring(self, x0: int, z0: int, x1: int, z1: int, y0: int, y1: int, spec) -> None:
        """The four walls of a rectangle, from layer y0 to y1."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, z0, spec); self.put(x, y, z1, spec)
            for z in range(z0, z1 + 1):
                self.put(x0, y, z, spec); self.put(x1, y, z, spec)

    def clear(self, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int) -> None:
        self.fill(x0, y0, z0, x1, y1, z1, None)

    # ------------------------------------------------------------------ openings
    def door(self, x: int, y: int, z: int, facing: str, material: str = "oak_door", hinge: str = "left") -> None:
        """A closed two-block door standing at (x, y, z) and the block above it. ``facing`` is the side it opens toward (its front)."""
        ns = material if ":" in material else "minecraft:" + material
        self.put(x, y, z, f"{ns}[facing={facing},half=lower,hinge={hinge},open=false]")
        self.put(x, y + 1, z, f"{ns}[facing={facing},half=upper,hinge={hinge},open=false]")

    def window(self, x: int, y: int, z: int, spec: str = "minecraft:glass_pane", height: int = 1) -> None:
        for k in range(height):
            self.put(x, y + k, z, spec)

    # ------------------------------------------------------------------ output
    def to_json(self, refpalette: str) -> dict:
        slices = ["".join(row) for layer in self.layers for row in layer]
        return {"xsize": self.xsize, "zsize": self.zsize, "refpalette": refpalette,
                "slices": [["".join(row) for row in layer] for layer in self.layers]}

    def render(self) -> str:
        """The six layers as text, for a quick look at what was drawn."""
        out = []
        for y, layer in enumerate(self.layers):
            out.append(f"-- {self.name} y={y}")
            out.extend("".join(row) for row in layer)
        return "\n".join(out)


# ---------------------------------------------------------------------------------------------------- furniture
# Every helper places furniture whose front looks at `facing`. The bounding blocks are the caller's business.

def bed(part: Part, x: int, y: int, z: int, head_toward: str, colour: str = "red") -> None:
    """A vanilla bed: the foot at (x, z), the head one block toward ``head_toward``."""
    dx, dz = DIRS[head_toward]
    name = f"minecraft:{colour}_bed"
    part.put(x, y, z, f"{name}[facing={head_toward},part=foot]")
    part.put(x + dx, y, z + dz, f"{name}[facing={head_toward},part=head]")


def fridge(part: Part, x: int, y: int, z: int, facing: str) -> None:
    part.put(x, y, z, ml("refrigerator", facing=facing, half="lower"))
    part.put(x, y + 1, z, ml("refrigerator", facing=facing, half="upper"))


def counter_run(part: Part, x: int, y: int, z: int, along: str, facing: str, items: list[str], wood: str = "oak") -> None:
    """A kitchen run: ``items`` laid out from (x, z) in direction ``along``; each is "cabinet", "drawers", "sink", "stove", "dishwasher", "microwave" (on a cabinet), "fridge" or "gap"."""
    dx, dz = DIRS[along]
    for i, item in enumerate(items):
        px, pz = x + dx * i, z + dz * i
        if item == "cabinet":
            part.put(px, y, pz, ml(f"{wood}_kitchen_cabinet", facing=facing))
        elif item == "drawers":
            part.put(px, y, pz, ml(f"{wood}_kitchen_drawer_cabinet", facing=facing))
        elif item == "sink":
            part.put(px, y, pz, ml("kitchen_sink", facing=facing))
        elif item == "stove":
            part.put(px, y, pz, ml("stove", facing=facing, open_door=False))
        elif item == "dishwasher":
            part.put(px, y, pz, ml("dishwasher", facing=facing))
        elif item == "microwave":
            part.put(px, y, pz, ml(f"{wood}_kitchen_cabinet", facing=facing))
            part.put(px, y + 1, pz, ml("microwave", facing=facing, open_door=False))
        elif item == "fridge":
            fridge(part, px, y, pz, facing)
        elif item == "gap":
            pass
        else:
            raise ValueError("unknown kitchen item " + item)


def sofa(part: Part, x: int, y: int, z: int, along: str, facing: str, colour: str = "gray", seats: int = 3) -> None:
    """A sofa of ``seats`` blocks starting at (x, z) in direction ``along``; its front looks at ``facing``."""
    dx, dz = DIRS[along]
    for i in range(seats):
        kind = "single" if seats == 1 else "left" if i == 0 else "right" if i == seats - 1 else "middle"
        part.put(x + dx * i, y, z + dz * i, ml(f"{colour}_sofa", facing=facing, type=kind))


def table(part: Part, x: int, y: int, z: int, w: int, d: int, wood: str = "oak", kind: str = "small") -> None:
    """A table of w x d blocks (connected tops); ``kind`` is "small" or "coffee"."""
    for i in range(w):
        for j in range(d):
            ew = "w_end" if i == 0 else "e_end" if i == w - 1 else "mid"
            ns = "n_end" if j == 0 else "s_end" if j == d - 1 else "mid"
            t = "single" if w == 1 and d == 1 else ew if d == 1 else ns if w == 1 else "mid"
            part.put(x + i, y, z + j, ml(f"{wood}_{kind}_table", type=t))


def chair(part: Part, x: int, y: int, z: int, facing: str, wood: str = "oak") -> None:
    part.put(x, y, z, ml(f"{wood}_chair", facing=facing))


def lamp_ceiling(part: Part, x: int, z: int) -> None:
    """A ceiling light in the top room layer (y 3) below a ceiling of layers 4 and 5."""
    part.put(x, 3, z, ml("light_bulb", face="ceiling", facing="north"))


def tv(part: Part, x: int, y: int, z: int, facing: str) -> None:
    part.put(x, y, z, ml("flatscreen_tv", facing=facing))


def toilet(part: Part, x: int, y: int, z: int, facing: str) -> None:
    part.put(x, y, z, ml("toilet", facing=facing))


def shower(part: Part, x: int, y: int, z: int, facing: str, tile: str = "minecraft:white_concrete") -> None:
    """A shower stall of one block: tiled floor, the head high on the wall behind it, a glass pane in front."""
    part.put(x, y - 1, z, tile)
    part.put(x, y + 2, z, ml("shower_head", facing=facing))


def sink(part: Part, x: int, y: int, z: int, facing: str) -> None:
    part.put(x, y, z, ml("kitchen_sink", facing=facing))


# ---------------------------------------------------------------------------------------------------- buildings

@dataclass
class Spot:
    """A place in a building that the game needs to know: where a person stands, sleeps, works or sells (blocks relative to the chunk's north-west corner, y above the street)."""
    kind: str          # "door", "bed", "counter", "desk", "stove", "seat", "stash", "shelf"
    x: int
    y: int             # blocks above the street surface (the ground floor's slab is 0)
    z: int
    facing: str = "south"
    note: str = ""


@dataclass
class Building:
    """One Lost Cities building: its parts, its palette and what the game may know about it."""
    id: str                                  # lowercase, e.g. "vw_house_a"
    palette: Palette
    use: str                                 # "house", "flats", "shop", "workshop", "warehouse", "office", "club", "terrace"
    city: str
    parts: list[tuple[Part, dict]] = field(default_factory=list)   # (part, flags)
    min_floors: int = 1
    max_floors: int = 1
    filler: str = "minecraft:stone_bricks"
    spots: list[Spot] = field(default_factory=list)
    notes: str = ""
    allow_doors: bool = False

    def add(self, part: Part, **flags) -> Part:
        self.parts.append((part, flags))
        return part

    def new_part(self, suffix: str, **flags) -> Part:
        return self.add(Part(f"{self.id}_{suffix}", self.palette), **flags)

    def files(self, namespace: str = "underground_city") -> dict[str, dict]:
        """The data files of this building, keyed by path under ``data/<namespace>/lostcities/``."""
        base = "lostcities/"
        ref = f"{namespace}:{self.id}"
        filler_char = self.palette.char(self.filler)
        files: dict[str, dict] = {}
        refs = []
        for part, flags in self.parts:
            files[f"{base}parts/{part.name}.json"] = part.to_json(ref)
            entry = {"part": f"{namespace}:{part.name}"}
            entry.update({"top": bool(flags.get("top", False)), "ground": bool(flags.get("ground", False)), "cellar": bool(flags.get("cellar", False))})
            if "floor" in flags:
                entry["floor"] = flags["floor"]
            refs.append(entry)
        files[f"{base}palettes/{self.id}.json"] = self.palette.to_json()
        files[f"{base}buildings/{self.id}.json"] = {
            "minfloors": self.min_floors, "maxfloors": self.max_floors, "mincellars": 0, "maxcellars": 0,
            "allowdoors": self.allow_doors, "allowfillers": False, "refpalette": ref,
            "filler": filler_char, "rubble": filler_char, "parts": refs,
        }
        return files

    def core_data(self) -> dict:
        """What the game itself reads about the building (data/underground_city/city_buildings/<id>.json)."""
        return {
            "schema_version": 1, "id": self.id, "city": self.city, "use": self.use,
            "floors": {"min": self.min_floors, "max": self.max_floors},
            "spots": [{"kind": s.kind, "x": s.x, "y": s.y, "z": s.z, "facing": s.facing, **({"note": s.note} if s.note else {})} for s in self.spots],
            "notes": self.notes,
        }
