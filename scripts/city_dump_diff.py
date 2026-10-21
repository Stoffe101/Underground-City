#!/usr/bin/env python3
"""Compare a generated building (a chunk dump of the city probe) with what its parts say, cell by cell: the fidelity check of a furnished building.

    python scripts/city_dump_diff.py <dump.txt> <building id, e.g. vw_house_a> [--floors N]

The expected blocks come from the same Python source that emitted the building (citykit); the real ones from the dump. Cells whose palette entry is random (lawn flowers) are only checked for
being one of the options; a space is air. It prints the differences grouped by kind, so that what Lost Cities adds, removes or moves (stairs, doors, rotation, ceilings) is visible.
"""
from __future__ import annotations

import argparse
import collections
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import city_content  # noqa: E402
from city_dump_view import layers  # noqa: E402


def strip(name: str) -> str:
    return re.sub(r"\[.*\]$", "", name).replace("minecraft:", "")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path)
    parser.add_argument("building")
    parser.add_argument("--floors", type=int, default=0, help="how many floors the dump holds (default: the building's minimum)")
    args = parser.parse_args()
    building = next((b for b in city_content.all_buildings() if b.id == args.building), None)
    if building is None:
        print("unknown building " + args.building)
        return 2
    text = args.dump.read_text(encoding="utf-8")
    legend_line = next(line for line in text.splitlines() if line.startswith("# legend:"))
    legend = {}
    for token in legend_line[len("# legend:"):].split():
        glyph, _, name = token.partition("=")
        legend[glyph] = name.replace("minecraft:", "")
    _, got = layers(text)
    floors = args.floors or building.min_floors
    # the parts in the order Lost Cities stacks them: ground floor, upper floors, top
    ground = next(p for p, f in building.parts if f.get("ground"))
    upper = next((p for p, f in building.parts if "floor" in f), None)
    top = next(p for p, f in building.parts if f.get("top"))
    stack = [ground] + [upper] * (floors - 1) + [top]
    palette = building.palette
    chars = palette.entries
    diffs = collections.defaultdict(list)
    checked = 0
    for index, part in enumerate(stack):
        for y in range(6):
            street = index * 6 + y
            rows = got.get(street)
            if rows is None:
                continue
            for z in range(16):
                for x in range(16):
                    c = part.layers[y][z][x]
                    spec = " " if c == " " else chars[c]
                    options = {"air"} if c == " " else {strip(spec)} if isinstance(spec, str) else {strip(b) for _, b in spec}
                    options = {("air" if o == "minecraft:air" else o) for o in options}
                    glyph = rows[z][x]
                    real = "air" if glyph == "." else legend.get(glyph, "?" + glyph)
                    real = strip(real)
                    checked += 1
                    if real not in options:
                        diffs[(sorted(options)[0], real)].append((street, x, z))
    print(f"{checked} cells compared, {sum(len(v) for v in diffs.values())} differ")
    for (want, real), cells in sorted(diffs.items(), key=lambda kv: -len(kv[1]))[:40]:
        sample = ", ".join(f"({x},{s},{z})" for s, x, z in cells[:4])
        print(f"  expected {want:34s} found {real:34s} x{len(cells):4d}  e.g. {sample}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
