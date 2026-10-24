#!/usr/bin/env python3
"""Read a chunk dump written by the city probe (``run_city_probe.py --dump``): print chosen layers of one building as text, so a generated building is read without a client.

    python scripts/city_dump_view.py <dump.txt> [--from 0] [--to 12] [--step 1]    layers by height above the street (street -1 is the foundation)

The first lines of the dump hold the legend (one glyph per block kind, '.' is air).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


def layers(text: str) -> tuple[list[str], dict[int, list[str]]]:
    lines = text.splitlines()
    header = [line for line in lines if line.startswith("#")]
    out: dict[int, list[str]] = {}
    current = None
    for line in lines:
        match = re.match(r"--- y (-?\d+) \(street ([+-]?\d+)\)", line)
        if match:
            current = int(match.group(2))
            out[current] = []
        elif current is not None and line and not line.startswith("#"):
            out[current].append(line)
    return header, out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path)
    parser.add_argument("--from", dest="lo", type=int, default=-1)
    parser.add_argument("--to", dest="hi", type=int, default=12)
    parser.add_argument("--step", type=int, default=1)
    parser.add_argument("--side-by-side", type=int, default=4, help="how many layers per row of the printout")
    args = parser.parse_args()
    header, out = layers(args.dump.read_text(encoding="utf-8"))
    print("\n".join(header))
    heights = [h for h in sorted(out) if args.lo <= h <= args.hi and (h - args.lo) % args.step == 0]
    per_row = max(1, args.side_by_side)
    for start in range(0, len(heights), per_row):
        group = heights[start:start + per_row]
        print("  ".join(f"street {h:+d}".ljust(16) for h in group))
        for z in range(16):
            print("  ".join((out[h][z] if z < len(out[h]) else "?" * 16) for h in group))
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
