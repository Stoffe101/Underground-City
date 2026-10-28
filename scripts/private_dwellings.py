#!/usr/bin/env python3
"""Where each home of a building ends (D-206: a property follows the dwelling, not the chunk), read from the building's own parts.

Used by ``private_wiring.py`` for the owner's private buildings; it reads a ported pack and writes nothing itself. A building (a single lot, or a
multi-chunk building's grid of parts) is put together as Lost Cities would put it up for each part's smallest number of floors, and read storey by
storey at head height (two blocks above the storey's floor):

* a **room** is a stretch of open floor under a roof: air and furniture are open, walls and windows are not, a door is a link to the room on its other side;
  a stretch that reaches the edge of the plot or open sky without a door (a porch, an arcade, the yard under the eaves) is **outside**, and nobody's;
* an **entrance** is a ground-floor door between a room and the outside;
* **stairs** and ladders link a room to the room above them.

Then, by the building's use:

* a house, a shop, a workshop, a warehouse, an office or a club is let whole: each group of rooms joined by doors and stairs that has an entrance is one
  dwelling, so a detached house over two chunks is one and two terraced houses with a wall between them are two;
* flats share their entrances and stairs: the rooms with an entrance or a stair are the **common** rooms, nobody's; every group of other rooms joined by
  doors that opens on a common room and has a bed is one flat.

Every dwelling becomes exact boxes (rectangles of its rooms, one storey and one chunk at a time, stacked where a storey repeats the one below), so no home
ever reaches into its neighbour's shell. Nothing is guessed: a room with an entrance, a bed and a stair in flats (a flat or the hall?), a group of rooms with no
entrance, a home that would need more boxes than Core takes, are each reported, and a building with any uncertainty gets no dwellings unless the owner's
selection names them (``dwellings`` overrides). The report holds counts and reasons only.
"""
from __future__ import annotations

import re
from collections import deque

FLOOR = 6
HEAD = 2          # the layer of a storey read for its rooms: two above its floor slab
INSIDE = (1, 4)   # a storey's interior, above its floor slab and under its ceiling layer (as private_wiring's flats)
MAX_BOXES = 160   # CrewLedger.MAX_BOXES
MAX_DWELLINGS = 24  # CityDwellings.MAX_DWELLINGS
LET_WHOLE = {"house", "shop", "workshop", "warehouse", "office", "club", "terrace"}
MARGIN = 2        # how far roofed space next to open air is the margin (eaves, a porch, an arcade), not a room

STEP = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
SPEC = re.compile(r"^(?P<name>[a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(?P<props>[^\]]*)\])?$")
# what a block at head height is: a door, a wall (anything that stops a person and is part of the building), or open (air and the furniture a room holds)
FURNITURE = re.compile(r"chest|barrel|shulker|furnace|smoker|crafting|table|chair|sofa|couch|_bed$|lamp|lantern|light|torch|candle|_pot$|plant|flower|cauldron|anvil|lectern|loom|"
                       r"jukebox|bell|banner|sign|carpet|rug|curtain|blind|shelf|cabinet|cupboard|wardrobe|drawer|desk|counter|fridge|freezer|oven|stove|sink|toilet|bath|shower|"
                       r"tv$|television|computer|clock|painting|picture|vase|statue|bench|stool|stand|rack|crate|bin$|basket|kitchen|cooking|armchair|bookcase|dresser|mirror|"
                       r"nightstand|locker|safe$|register|till|microwave|dishwasher|washing|dryer|radiator|fan$|plushie|cushion|pillow|coat|hanger|easel|piano|keg|cask")
FURNITURE_MODS = {"cfm", "pfm", "another_furniture", "embellishcraft", "mapperbase", "simplylight", "buildersdelight", "farmersdelight", "modernlife"}
STRUCTURAL = re.compile(r"brick|plank|stone|cobble|concrete|terracotta|_wool$|quartz|glass|_pane$|_log$|_wood$|tile|marble|plaster|wallpaper|siding|shingle|slab$|"
                        r"_wall$|iron_bars|deepslate|sandstone|prismarine|basalt|granite|diorite|andesite|beam|pillar|column|cladding|_block$")
STAIRS = re.compile(r"_stairs$|:ladder$|scaffolding")
STYLE = "lostcities:style_character"   # a character only a city style defines


def parse(spec: str) -> tuple[str, dict[str, str]]:
    m = SPEC.match(spec.strip()) if spec else None
    if not m:
        return spec or "", {}
    props = dict(kv.split("=", 1) for kv in (m.group("props") or "").split(",") if "=" in kv)
    return m.group("name"), props


def kind(block: str) -> str:
    """'air', 'door', 'stairs', 'wall' or 'open' (furniture) for a block spec at head height."""
    if not block or block.endswith(":air") or block.endswith(":cave_air") or block.endswith(":void_air"):
        return "air"
    name, _ = parse(block)
    path = name.split(":", 1)[-1]
    if path.endswith("_door") and "trapdoor" not in path:
        return "door"
    if STAIRS.search(name):
        return "stairs"
    if "glass" in path or path.endswith("_pane") or "iron_bars" in path or name == STYLE:
        return "wall"
    if name.split(":", 1)[0] in FURNITURE_MODS and not STRUCTURAL.search(path):
        return "open"
    if FURNITURE.search(path):
        return "open"
    return "wall"


class Stitched:
    """A building's blocks in its own coordinates: x and z across the whole grid of parts (column * 16 + x), y above the street."""

    def __init__(self, grid: list[tuple[str, int, int]], layers_of) -> None:
        self.cells: dict[tuple[int, int, int], str] = {}
        self.w = max(x for _, x, _ in grid) + 1
        self.h = max(z for _, _, z in grid) + 1
        for part, gx, gz in grid:
            parts, _ = layers_of(part)
            for y0, slices, palette in parts:
                for dy, layer in enumerate(slices):
                    for z, row in enumerate(layer):
                        for x, ch in enumerate(row):
                            if ch == " ":
                                continue
                            # a character the building's palettes leave to the city style (its walls, its glass) is building material wherever the building stands
                            block = palette.get(ch, STYLE)
                            if block.endswith(":air"):
                                continue
                            self.cells[(gx * 16 + x, y0 + dy, gz * 16 + z)] = block
        self.top = max((y for _, y, _ in self.cells), default=0)
        self.column_top: dict[tuple[int, int], int] = {}
        for x, y, z in self.cells:
            if y > self.column_top.get((x, z), -1):
                self.column_top[(x, z)] = y

    def inside_grid(self, x: int, z: int) -> bool:
        return 0 <= x < self.w * 16 and 0 <= z < self.h * 16

    def kind_at(self, x: int, y: int, z: int) -> str:
        return kind(self.cells.get((x, y, z), ""))

    def roofed(self, x: int, z: int, storey: int) -> bool:
        """Something over this column from the storey's top layers up, anywhere in the building: under a roof, not a yard. A stairwell's opening in the floor above is
        still under the building's roof (judged within the storey alone, every room beside a staircase read as open sky)."""
        return self.column_top.get((x, z), -1) >= storey * FLOOR + INSIDE[1]


def analyse(grid: list[tuple[str, int, int]], layers_of, use: str) -> tuple[list[dict], dict]:
    """The dwellings of a building and a report. ``grid``: (part id, column, row) per chunk; ``layers_of``: part id -> private_wiring.layers(...).

    The building is read in three dimensions, as a person moves through it: **space** is every cell of air, furniture or stairs with something over it in the
    building; a **room** is a connected stretch of space, whose only links to other rooms are doors (walls, floors and ceilings part it; a stairwell's opening
    joins the storeys it serves). A room that reaches the edge of the plot or open sky without passing a door is outside. The storeys of the owner's parts do
    not keep to six-block steps (beds were found two and four blocks above a floor's base), so nothing here assumes where a storey starts."""
    s = Stitched(grid, layers_of)
    xs, zs = s.w * 16, s.h * 16
    report = {"use": use, "storeys": s.top // FLOOR + 1, "rooms": 0, "entrances": 0, "uncertain": [], "nobodys_rooms": 0, "dwellings": 0}

    def space(x: int, y: int, z: int) -> bool:
        return s.kind_at(x, y, z) in ("air", "open", "stairs") and s.column_top.get((x, z), -1) > y

    def walkway(x: int, y: int, z: int) -> bool:
        """Somewhere a person walks: a floor under it and headroom over it. Only from such a cell does a room open on the street or the sky; the air under a roof
        that reaches the eaves (the roof void over the walls) opens on nothing a person could walk out to."""
        return y >= 1 and s.kind_at(x, y - 1, z) not in ("air", "open") and s.kind_at(x, y + 1, z) in ("air", "open", "stairs")

    # the margin: roofed space within MARGIN blocks of open air or the edge of the plot on its own level (the strip under the eaves, a porch, an arcade, the roof void
    # over the walls). It is nobody's, and a room is entered from it. Read as a whole, one doorless opening onto a terrace made every room it led to "outside"
    margin: set[tuple[int, int, int]] = set()
    for y in range(1, s.top + 1):
        frontier = []
        for x in range(xs):
            for z in range(zs):
                if not space(x, y, z):
                    continue
                for dx, dz in STEP.values():
                    nx, nz = x + dx, z + dz
                    if not (0 <= nx < xs and 0 <= nz < zs) or s.kind_at(nx, y, nz) in ("air", "open", "stairs") and s.column_top.get((nx, nz), -1) <= y:
                        frontier.append((x, z))
                        break
        for depth in range(MARGIN):
            nxt = []
            for x, z in frontier:
                if (x, y, z) in margin:
                    continue
                margin.add((x, y, z))
                for dx, dz in STEP.values():
                    nx, nz = x + dx, z + dz
                    if 0 <= nx < xs and 0 <= nz < zs and space(nx, y, nz) and (nx, y, nz) not in margin:
                        nxt.append((nx, nz))
            frontier = nxt

    room_of: dict[tuple[int, int, int], int] = {}
    rooms: list[dict] = []
    for y0 in range(1, s.top + 1):
        for x0 in range(xs):
            for z0 in range(zs):
                if (x0, y0, z0) in room_of or (x0, y0, z0) in margin or not space(x0, y0, z0):
                    continue
                rid = len(rooms)
                cells, opening = [], False
                queue = deque([(x0, y0, z0)])
                room_of[(x0, y0, z0)] = rid
                while queue:
                    x, y, z = queue.popleft()
                    cells.append((x, y, z))
                    for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 1, 0), (0, -1, 0)):
                        nx, ny, nz = x + dx, y + dy, z + dz
                        if not (0 <= nx < xs and 0 <= nz < zs) or ny < 1 or ny > s.top or (nx, ny, nz) in room_of:
                            continue
                        if (nx, ny, nz) in margin:
                            # a doorless way out onto the margin, where a person walks
                            opening = opening or dy == 0 and walkway(x, y, z)
                            continue
                        if space(nx, ny, nz):
                            room_of[(nx, ny, nz)] = rid
                            queue.append((nx, ny, nz))
                rooms.append({"cells": cells, "outside": False, "entrance": opening, "beds": 0, "links": set()})
    report["rooms"] = len(rooms)
    report["entrances"] = sum(1 for r in rooms if r["entrance"])

    def room(x: int, y: int, z: int) -> int | None:
        return room_of.get((x, y, z))

    def beyond(x: int, y: int, z: int) -> bool:
        """The other side of a door is outside: off the plot, open sky, or the margin."""
        if not (0 <= x < xs and 0 <= z < zs) or (x, y, z) in margin:
            return True
        return s.kind_at(x, y, z) in ("air", "open", "stairs") and s.column_top.get((x, z), -1) <= y

    for (x, y, z), block in s.cells.items():
        name, props = parse(block)
        path = name.split(":", 1)[-1]
        if path.endswith("_door") and "trapdoor" not in path and props.get("half", "lower") == "lower":
            dx, dz = STEP.get(props.get("facing", "north"), (0, -1))
            a, b = (x + dx, y, z + dz), (x - dx, y, z - dz)
            ra, rb = room(*a), room(*b)
            if ra is not None and rb is not None and ra != rb:
                rooms[ra]["links"].add(rb)
                rooms[rb]["links"].add(ra)
            elif (ra is not None) != (rb is not None) and (beyond(*a) if ra is None else beyond(*b)):
                if not rooms[ra if ra is not None else rb]["entrance"]:
                    report["entrances"] += 1
                rooms[ra if ra is not None else rb]["entrance"] = True
        elif (path.endswith("_bed") or path == "bed") and props.get("part", "head") == "head":
            r = room(x, y, z)
            if r is None and (x, y, z) in margin:
                # a bed by a doorless opening lies in the margin: it is the room's beside it
                r = next((room(x + dx, y, z + dz) for dx, dz in STEP.values() if room(x + dx, y, z + dz) is not None), None)
            if r is not None:
                rooms[r]["beds"] += 1

    def groups(members: set[int]) -> list[set[int]]:
        seen, out = set(), []
        for start in sorted(members):
            if start in seen:
                continue
            group, queue = set(), deque([start])
            seen.add(start)
            while queue:
                r = queue.popleft()
                group.add(r)
                for other in rooms[r]["links"]:
                    if other in members and other not in seen:
                        seen.add(other)
                        queue.append(other)
            out.append(group)
        return out

    every = set(range(len(rooms)))
    chosen: list[tuple[str, set[int]]] = []
    if use in LET_WHOLE:
        entered = []
        for group in groups(every):
            if not any(rooms[r]["entrance"] for r in group):
                report["nobodys_rooms"] += len(group)
                continue
            if use == "house" and not any(rooms[r]["beds"] for r in group):
                # behind a street door of its own with no bed (a garage, a shed, a storey entered from outside): no home, nobody's; the house beside it is still one
                report["nobodys_rooms"] += len(group)
                report.setdefault("notes", []).append(f"{len(group)} room(s) behind a street door with no bed are no home")
                continue
            entered.append(group)
        if use == "house":
            # each house its own: two terraced houses, each behind its own street door, are two homes
            chosen.extend(("house", group) for group in entered)
        elif entered:
            # premises are one lease whatever doors they have (the owner's rule: a shop, a workshop, a warehouse over several chunks is let as one)
            chosen.append(("premises", set().union(*entered)))
    elif use == "flats":
        # the common rooms: those with a street door and no bed (the hall, the stairs and the landings they join, which are one stretch of space)
        common = {r for r in every if rooms[r]["entrance"] and not rooms[r]["beds"]}
        for r in every - common:
            if rooms[r]["entrance"] and rooms[r]["beds"] and any(o not in common and rooms[o]["beds"] for o in rooms[r]["links"]):
                report["uncertain"].append("a room with a street door and a bed that other flats open on: a flat or the hall")
        for group in groups(every - common):
            opens = any(other in common for r in group for other in rooms[r]["links"])
            own_entrance = any(rooms[r]["entrance"] for r in group)
            if not (opens or own_entrance) or not any(rooms[r]["beds"] for r in group):
                report["nobodys_rooms"] += len(group)
                continue
            chosen.append(("flat", group))
    else:
        report["uncertain"].append(f"no rule for the use {use}")
    dwellings = []
    order = sorted(chosen, key=lambda c: min(min((y, x, z) for x, y, z in rooms[r]["cells"]) for r in c[1]))
    def foreign(x: int, y: int, z: int, group: set[int]) -> bool:
        """A cell that is somebody else's or nobody's: another room, the margin, open air, beyond the plot."""
        if not (0 <= x < xs and 0 <= z < zs):
            return True
        if (x, y, z) in margin:
            return True
        r = room_of.get((x, y, z))
        if r is not None:
            return r not in group
        return s.kind_at(x, y, z) in ("air", "open", "stairs")   # space that is no room: open air

    def own_fill(group: set[int], reach: int = 1) -> set[tuple[int, int, int]]:
        """The walls, furniture and inner doors a dwelling's boxes may take in: cells inside its rooms' reach with nothing of anybody else within a block, so a
        neighbour's room, the margin and the street stay at least one block away and the outer walls and the front doors stay the city's shell."""
        cells = [c for r in group for c in rooms[r]["cells"]]
        lo = [min(c[i] for c in cells) - 1 for i in range(3)]
        hi = [max(c[i] for c in cells) + 1 for i in range(3)]
        out = set()
        for x in range(max(lo[0], 0), min(hi[0], xs - 1) + 1):
            for y in range(max(lo[1], 1), hi[1] + 1):
                for z in range(max(lo[2], 0), min(hi[2], zs - 1) + 1):
                    if room_of.get((x, y, z)) is not None or (x, y, z) in margin or s.kind_at(x, y, z) in ("air", "open", "stairs"):
                        continue
                    near = range(-reach, reach + 1)
                    if not any(foreign(x + dx, y + dy, z + dz, group) for dx in near for dy in near for dz in near if dx or dy or dz):
                        out.add((x, y, z))
        return out

    named = []
    for n, (what, group) in enumerate(order, start=1):
        did, title = (f"flat_{n}", f"Flat {n}") if what == "flat" else ((f"house_{n}", f"No. {n}") if what == "house" and len(chosen) > 1 else (f"{what}_{n}" if len(chosen) > 1 else what, what.capitalize()))
        named.append((did, title, what, group))
    # each dwelling takes in its own walls and furniture; two that would meet inside a thick wall between them take in less (nothing of the other within two
    # blocks), and then none at all, until no two touch
    reach = {did: 1 for did, _, _, _ in named}
    for _ in range(3):
        dwellings = []
        for did, title, what, group in named:
            fill = own_fill(group, reach[did]) if reach[did] else set()
            dwellings.append({"id": did, "title": title, "rooms": boxes(rooms, group, fill)})
        clashes = clashing(dwellings)
        if not clashes:
            break
        for did in clashes:
            reach[did] = 2 if reach[did] == 1 else 0
    for d, (_, _, what, _) in zip(list(dwellings), named):
        if len(d["rooms"]) > MAX_BOXES:
            report["uncertain"].append(f"a {what} would need {len(d['rooms'])} boxes (Core takes {MAX_BOXES})")
    if len(dwellings) > MAX_DWELLINGS:
        report["uncertain"].append(f"{len(dwellings)} dwellings (Core takes {MAX_DWELLINGS})")
    report["dwellings"] = len(dwellings)
    # each room in counts only: its size, height, beds, whether it has a street door or is outside, how many doors join it to others
    report["room_summary"] = [{"cells": len(r["cells"]), "y": [min(c[1] for c in r["cells"]), max(c[1] for c in r["cells"])], "beds": r["beds"],
                               "entrance": r["entrance"], "outside": r["outside"], "doors": len(r["links"])} for r in rooms]
    if report["uncertain"]:
        return [], report
    clash = overlaps(dwellings)
    if clash:
        report["uncertain"].append(clash)
        return [], report
    return dwellings, report


def boxes(rooms: list[dict], group: set[int], fill: set[tuple[int, int, int]] | None = None) -> list[dict]:
    """A group of rooms as boxes, per chunk: greedy in three dimensions, from the lowest cell not yet covered, as wide, as deep and as high as the room and its
    own ``fill`` (walls, furniture and inner doors nobody else is near) allow. Every cell of the rooms is covered and nothing outside rooms and fill is."""
    fill = fill or set()
    out: list[dict] = []
    by_part: dict[tuple[int, int], set[tuple[int, int, int]]] = {}
    for r in group:
        for x, y, z in rooms[r]["cells"]:
            by_part.setdefault((x // 16, z // 16), set()).add((x % 16, y, z % 16))
    fill_part: dict[tuple[int, int], set[tuple[int, int, int]]] = {}
    for x, y, z in fill:
        fill_part.setdefault((x // 16, z // 16), set()).add((x % 16, y, z % 16))
    for (gx, gz), must in sorted(by_part.items()):
        ok = must | fill_part.get((gx, gz), set())
        left = set(must)
        while left:
            x0, y0, z0 = min(left, key=lambda c: (c[1], c[2], c[0]))
            x1 = x0
            while x1 < 15 and (x1 + 1, y0, z0) in ok:
                x1 += 1
            z1 = z0
            while z1 < 15 and all((x, y0, z1 + 1) in ok for x in range(x0, x1 + 1)):
                z1 += 1
            y1 = y0
            while all((x, y1 + 1, z) in ok for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)):
                y1 += 1
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    for z in range(z0, z1 + 1):
                        left.discard((x, y, z))
            out.append({"at": [gx, gz], "box": [x0, y0, z0, x1, y1, z1]})
    return out


def rectangles(cells: set[tuple[int, int]]) -> list[tuple[int, int, int, int]]:
    """Exact cover of a set of cells by rectangles, greedily: the first free cell in z then x, widened along x, then lengthened along z while every row is whole."""
    left = set(cells)
    out = []
    for z in range(16):
        for x in range(16):
            if (x, z) not in left:
                continue
            x1 = x
            while (x1 + 1, z) in left:
                x1 += 1
            z1 = z
            while all((xx, z1 + 1) in left for xx in range(x, x1 + 1)):
                z1 += 1
            for zz in range(z, z1 + 1):
                for xx in range(x, x1 + 1):
                    left.discard((xx, zz))
            out.append((x, z, x1, z1))
    return out


def clashing(dwellings: list[dict]) -> set[str]:
    """The ids of dwellings whose boxes reach into another's shell."""
    out = set()
    for i in range(len(dwellings)):
        for j in range(i + 1, len(dwellings)):
            if overlaps([dwellings[i], dwellings[j]]):
                out.update((dwellings[i]["id"], dwellings[j]["id"]))
    return out


def overlaps(dwellings: list[dict]) -> str:
    """As CityDwellings.parse: why two dwellings would reach into each other's shell, or ''."""
    def span(room):
        (gx, gz), b = room["at"], room["box"]
        return gx * 16 + b[0], b[1], gz * 16 + b[2], gx * 16 + b[3], b[4], gz * 16 + b[5]
    for i in range(len(dwellings)):
        for j in range(i + 1, len(dwellings)):
            for a in map(span, dwellings[i]["rooms"]):
                for b in map(span, dwellings[j]["rooms"]):
                    if a[0] <= b[3] + 1 and a[3] >= b[0] - 1 and a[1] <= b[4] + 1 and a[4] >= b[1] - 1 and a[2] <= b[5] + 1 and a[5] >= b[2] - 1:
                        return f"{dwellings[i]['id']} and {dwellings[j]['id']} have no wall between them"
    return ""
