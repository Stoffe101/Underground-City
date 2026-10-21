#!/usr/bin/env python3
"""The old quarry (D-123): one built district whose mined veins grow back in active play time.

Writes two files from one description, so the pit and the list of its veins cannot disagree:

* ``rpg-core/src/main/resources/data/underground_city/districts/old_quarry.json``: the district (a stepped pit, six one-block terraces deep, with ore in the treads), built by the same
  reversible DistrictBuilder as Founders Row;
* ``rpg-core/src/main/resources/data/underground_city/resource_sites/ironhaven_quarry.json``: which cells are veins and what grows there, and how long a mined one takes to grow back.

    python scripts/generate_resource_site.py           # write both
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
MAX_ORES_PER_HOUR = 60.0   # the ceiling the data check enforces: nothing here may out-supply the cities' shelves several times over

# (terrace k, side, z or x along the tread, ore): a tread block of terrace k is its outermost floor block, one below its standing level; its top and the side facing the next terrace are open air
VEINS = [
    (1, "west", 6, "minecraft:iron_ore"), (2, "east", 10, "minecraft:iron_ore"), (3, "west", 12, "minecraft:iron_ore"),
    (4, "east", 5, "minecraft:iron_ore"), (2, "west", 4, "minecraft:iron_ore"), (3, "east", 8, "minecraft:iron_ore"),
    (0, "west", 9, "minecraft:coal_ore"), (0, "east", 9, "minecraft:coal_ore"), (1, "east", 12, "minecraft:coal_ore"),
    (4, "west", 9, "minecraft:coal_ore"), (2, "west", 14, "minecraft:coal_ore"),
    (3, "west", 6, "minecraft:copper_ore"), (4, "east", 11, "minecraft:copper_ore"), (1, "west", 14, "minecraft:copper_ore"),
]


def tread(k: int, side: str, along: int) -> tuple[int, int, int]:
    x = 1 + k if side == "west" else WIDTH - 2 - k
    return (x, -2 - k, along)


def district() -> dict:
    last = WIDTH - 1
    ops: list[dict] = [
        {"op": "fill", "from": [0, -8, 0], "to": [last, -2, last], "block": "minecraft:stone"},
        {"op": "fill", "from": [0, -1, 0], "to": [last, -1, last], "block": "minecraft:coarse_dirt"},
        {"op": "fill", "from": [0, 0, 0], "to": [last, HEIGHT - 1, last], "block": "minecraft:air"},
    ]
    for k in range(DEPTH):
        ops.append({"op": "fill", "from": [1 + k, -1 - k, 1 + k], "to": [last - 1 - k, -1, last - 1 - k], "block": "minecraft:air"})
    for k, side, along, ore in VEINS:
        ops.append({"op": "block", "at": list(tread(k, side, along)), "block": ore})
    ops.append({"op": "block", "at": [9, 0, 0], "block": "minecraft:spruce_sign[rotation=0]"})
    return {
        "schema_version": 1, "id": "old_quarry", "title": "Old quarry",
        "note": "A stepped open-cast pit with seams of iron, coal and copper in its treads; mined seams grow back in active play time (resource_sites/ironhaven_quarry.json). Origin is the north-west corner, extending east and south; the rim is level with the ground.",
        "size": [WIDTH, HEIGHT, WIDTH], "ops": ops,
    }


def site() -> dict:
    return {
        "schema_version": 1, "id": "ironhaven_quarry", "title": "Ironhaven old quarry", "district": "old_quarry", "interval_minutes": INTERVAL_MINUTES,
        "note": "Mined seams grow back after interval_minutes of ACTIVE play time (never while nobody is online), unless somebody stands in the cell; a cell somebody built in is never overwritten. Gold, diamond, redstone and lapis are deliberately not here.",
        "veins": [{"at": list(tread(k, side, along)), "block": ore} for k, side, along, ore in VEINS],
    }


def validate() -> list[str]:
    """The pit simulated block by block: every vein is a stone cell in a tread with open air beside it, inside the lot, and the yield ceiling holds."""
    problems: list[str] = []
    cells: dict[tuple[int, int, int], str] = {}
    for op in district()["ops"]:
        if op["op"] == "fill":
            (x0, y0, z0), (x1, y1, z1) = op["from"], op["to"]
            for x in range(x0, x1 + 1):
                for y in range(y0, y1 + 1):
                    for z in range(z0, z1 + 1):
                        cells[(x, y, z)] = op["block"]
        elif op["op"] == "block" and "ore" not in op["block"]:
            cells[tuple(op["at"])] = op["block"]
    seen: set[tuple[int, int, int]] = set()
    for k, side, along, ore in VEINS:
        at = tread(k, side, along)
        if at in seen:
            problems.append(f"two veins at {at}")
        seen.add(at)
        if cells.get(at) != "minecraft:stone":
            problems.append(f"vein {at} is not set in stone ({cells.get(at)})")
        if not all(0 <= c < WIDTH for c in (at[0], at[2])) or not -8 <= at[1] < 0:
            problems.append(f"vein {at} is outside the lot")
        neighbours = [(at[0] + dx, at[1] + dy, at[2] + dz) for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, 0, 1), (0, 0, -1))]
        if not any(cells.get(n) == "minecraft:air" for n in neighbours):
            problems.append(f"vein {at} has no open face: nobody could see or mine it")
        if ore not in ("minecraft:iron_ore", "minecraft:coal_ore", "minecraft:copper_ore"):
            problems.append(f"{ore} is not a base metal: gold, diamond, redstone and lapis stay on the shelves")
    ceiling = len(VEINS) * 60.0 / INTERVAL_MINUTES
    if ceiling > MAX_ORES_PER_HOUR:
        problems.append(f"the site could give {ceiling:.0f} ores an active hour, above the ceiling of {MAX_ORES_PER_HOUR:.0f}")
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
    wanted = {DISTRICT_FILE: render(district()), SITE_FILE: render(site())}
    if args.check:
        stale = [str(path.relative_to(ROOT)) for path, text in wanted.items() if not path.exists() or path.read_text(encoding="utf-8") != text]
        if stale:
            print("out of date, run python scripts/generate_resource_site.py: " + ", ".join(stale), file=sys.stderr)
            return 1
        print("old quarry files are current")
        return 0
    for path, text in wanted.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        print("wrote", path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
