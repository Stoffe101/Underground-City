#!/usr/bin/env python3
"""The regrowing resource sites (D-123, D-134): built districts whose mined veins grow back in active play time.

Writes two files per site from one description, so a pit and the list of its veins cannot disagree:

* ``rpg-core/src/main/resources/data/underground_city/districts/<district>.json``: the district (a stepped pit, one-block terraces, with ore in the treads), built by the same
  reversible DistrictBuilder as Founders Row;
* ``rpg-core/src/main/resources/data/underground_city/resource_sites/<site>.json``: which cells are veins and what grows there, and how long a mined one takes to grow back.

Two sites: the old quarry beside the arrival landing near Ironhaven (D-123), and the smaller Valewood pit beside the road where it leaves Valewood (D-134), so the base metals also renew
on the island with the fewest metal shelves.

    python scripts/generate_resource_site.py           # write them all
    python scripts/generate_resource_site.py --check   # exit 1 when a file differs from the description

Only the base metals are here (iron, coal, copper). Gold, diamond, redstone and lapis stay on the cities' shelves: a deliberate economic choice, not an omission (docs/technical/hybrid-economy.md).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
DISTRICT_FILE = DATA / "districts/old_quarry.json"
SITE_FILE = DATA / "resource_sites/ironhaven_quarry.json"

WIDTH = 19            # x and z, in blocks
DEPTH = 6             # terraces
HEIGHT = 8            # air above the rim
INTERVAL_MINUTES = 20
MAX_ORES_PER_HOUR = 60.0   # the ceiling the data check enforces, per site: nothing here may out-supply the cities' shelves several times over

# (terrace k, side, z or x along the tread, ore): a tread block of terrace k is its outermost floor block, one below its standing level; its top and the side facing the next terrace are open air
VEINS = [
    (1, "west", 6, "minecraft:iron_ore"), (2, "east", 10, "minecraft:iron_ore"), (3, "west", 12, "minecraft:iron_ore"),
    (4, "east", 5, "minecraft:iron_ore"), (2, "west", 4, "minecraft:iron_ore"), (3, "east", 8, "minecraft:iron_ore"),
    (0, "west", 9, "minecraft:coal_ore"), (0, "east", 9, "minecraft:coal_ore"), (1, "east", 12, "minecraft:coal_ore"),
    (4, "west", 9, "minecraft:coal_ore"), (2, "west", 14, "minecraft:coal_ore"),
    (3, "west", 6, "minecraft:copper_ore"), (4, "east", 11, "minecraft:copper_ore"), (1, "west", 14, "minecraft:copper_ore"),
]


# The Valewood pit (D-134): smaller and slower than the old quarry, with more copper; on Valewood's island, beside the road to Ironhaven where it leaves the city
PIT_WIDTH = 13
PIT_DEPTH = 4
PIT_INTERVAL_MINUTES = 25
PIT_VEINS = [
    (0, "west", 4, "minecraft:iron_ore"), (1, "east", 6, "minecraft:iron_ore"), (2, "west", 6, "minecraft:iron_ore"), (3, "east", 5, "minecraft:iron_ore"),
    (0, "east", 8, "minecraft:copper_ore"), (1, "west", 8, "minecraft:copper_ore"), (2, "east", 4, "minecraft:copper_ore"),
    (0, "west", 9, "minecraft:coal_ore"), (1, "east", 3, "minecraft:coal_ore"), (3, "west", 7, "minecraft:coal_ore"),
]
PIT_DISTRICT_FILE = DATA / "districts/valewood_pit.json"
PIT_SITE_FILE = DATA / "resource_sites/valewood_pit.json"


class Pit:
    """One stepped pit and its veins."""

    def __init__(self, *, district: str, site: str, title: str, site_title: str, width: int, depth: int, interval: int, veins: list, note: str,
                 district_file: Path, site_file: Path):
        self.district_id, self.site_id, self.title, self.site_title = district, site, title, site_title
        self.width, self.depth, self.interval, self.veins, self.note = width, depth, interval, veins, note
        self.district_file, self.site_file = district_file, site_file

    def tread(self, k: int, side: str, along: int) -> tuple[int, int, int]:
        x = 1 + k if side == "west" else self.width - 2 - k
        return (x, -2 - k, along)

    def district(self) -> dict:
        last = self.width - 1
        ops: list[dict] = [
            {"op": "fill", "from": [0, -8, 0], "to": [last, -2, last], "block": "minecraft:stone"},
            {"op": "fill", "from": [0, -1, 0], "to": [last, -1, last], "block": "minecraft:coarse_dirt"},
            {"op": "fill", "from": [0, 0, 0], "to": [last, HEIGHT - 1, last], "block": "minecraft:air"},
        ]
        for k in range(self.depth):
            ops.append({"op": "fill", "from": [1 + k, -1 - k, 1 + k], "to": [last - 1 - k, -1, last - 1 - k], "block": "minecraft:air"})
        for k, side, along, ore in self.veins:
            ops.append({"op": "block", "at": list(self.tread(k, side, along)), "block": ore})
        ops.append({"op": "block", "at": [self.width // 2, 0, 0], "block": "minecraft:spruce_sign[rotation=0]"})
        return {"schema_version": 1, "id": self.district_id, "title": self.title, "note": self.note, "size": [self.width, HEIGHT, self.width], "ops": ops}

    def site(self) -> dict:
        return {
            "schema_version": 1, "id": self.site_id, "title": self.site_title, "district": self.district_id, "interval_minutes": self.interval,
            "note": "Mined seams grow back after interval_minutes of ACTIVE play time (never while nobody is online), unless somebody stands in the cell; a cell somebody built in is never overwritten. Gold, diamond, redstone and lapis are deliberately not here.",
            "veins": [{"at": list(self.tread(k, side, along)), "block": ore} for k, side, along, ore in self.veins],
        }

    def validate(self) -> list[str]:
        """The pit simulated block by block: every vein is a stone cell in a tread with open air beside it, inside the lot, and the yield ceiling holds."""
        problems: list[str] = []
        cells: dict[tuple[int, int, int], str] = {}
        for op in self.district()["ops"]:
            if op["op"] == "fill":
                (x0, y0, z0), (x1, y1, z1) = op["from"], op["to"]
                for x in range(x0, x1 + 1):
                    for y in range(y0, y1 + 1):
                        for z in range(z0, z1 + 1):
                            cells[(x, y, z)] = op["block"]
            elif op["op"] == "block" and "ore" not in op["block"]:
                cells[tuple(op["at"])] = op["block"]
        seen: set[tuple[int, int, int]] = set()
        for k, side, along, ore in self.veins:
            at = self.tread(k, side, along)
            if at in seen:
                problems.append(f"{self.site_id}: two veins at {at}")
            seen.add(at)
            if cells.get(at) != "minecraft:stone":
                problems.append(f"{self.site_id}: vein {at} is not set in stone ({cells.get(at)})")
            if not all(0 <= c < self.width for c in (at[0], at[2])) or not -8 <= at[1] < 0:
                problems.append(f"{self.site_id}: vein {at} is outside the lot")
            neighbours = [(at[0] + dx, at[1] + dy, at[2] + dz) for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, 0, 1), (0, 0, -1))]
            if not any(cells.get(n) == "minecraft:air" for n in neighbours):
                problems.append(f"{self.site_id}: vein {at} has no open face: nobody could see or mine it")
            if ore not in ("minecraft:iron_ore", "minecraft:coal_ore", "minecraft:copper_ore"):
                problems.append(f"{self.site_id}: {ore} is not a base metal: gold, diamond, redstone and lapis stay on the shelves")
        ceiling = len(self.veins) * 60.0 / self.interval
        if ceiling > MAX_ORES_PER_HOUR:
            problems.append(f"{self.site_id}: the site could give {ceiling:.0f} ores an active hour, above the ceiling of {MAX_ORES_PER_HOUR:.0f}")
        if self.interval < 5:
            problems.append(f"{self.site_id}: an interval under five minutes is refused by the loader")
        return problems


QUARRY = Pit(district="old_quarry", site="ironhaven_quarry", title="Old quarry", site_title="Ironhaven old quarry", width=WIDTH, depth=DEPTH, interval=INTERVAL_MINUTES,
             veins=VEINS, district_file=DISTRICT_FILE, site_file=SITE_FILE,
             note="A stepped open-cast pit with seams of iron, coal and copper in its treads; mined seams grow back in active play time (resource_sites/ironhaven_quarry.json). Origin is the north-west corner, extending east and south; the rim is level with the ground.")
PIT = Pit(district="valewood_pit", site="valewood_pit", title="Valewood pit", site_title="Valewood hillside pit", width=PIT_WIDTH, depth=PIT_DEPTH, interval=PIT_INTERVAL_MINUTES,
          veins=PIT_VEINS, district_file=PIT_DISTRICT_FILE, site_file=PIT_SITE_FILE,
          note="A small stepped pit on Valewood's island, beside the road to Ironhaven where it leaves the city, with seams of iron, copper and coal; mined seams grow back in active play time (resource_sites/valewood_pit.json). Origin is the north-west corner, extending east and south; the rim is level with the ground.")
PITS = [QUARRY, PIT]


def tread(k: int, side: str, along: int) -> tuple[int, int, int]:
    return QUARRY.tread(k, side, along)


def district() -> dict:
    return QUARRY.district()


def site() -> dict:
    return QUARRY.site()


def validate() -> list[str]:
    """Every pit simulated block by block (see Pit.validate), and no two sites with one id."""
    problems: list[str] = []
    for pit in PITS:
        problems += pit.validate()
    if len({p.site_id for p in PITS}) != len(PITS) or len({p.district_id for p in PITS}) != len(PITS):
        problems.append("two pits share an id")
    return problems


def render(data: dict) -> str:
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    problems = validate()
    if problems:
        print("\n".join(problems), file=sys.stderr)
        return 1
    wanted = {}
    for pit in PITS:
        wanted[pit.district_file] = render(pit.district())
        wanted[pit.site_file] = render(pit.site())
    if args.check:
        stale = [str(path.relative_to(ROOT)) for path, text in wanted.items() if not path.exists() or path.read_text(encoding="utf-8") != text]
        if stale:
            print("out of date, run python scripts/generate_resource_site.py: " + ", ".join(stale), file=sys.stderr)
            return 1
        print("resource site files are current")
        return 0
    for path, text in wanted.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print("wrote", path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
