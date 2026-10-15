#!/usr/bin/env python3
"""Generate the Founders Row district definition (D-055).

The district is data: a list of block operations relative to an origin chosen by an operator at build time (the
footprint's north-west corner, extending east and south), so no world coordinate is hard-coded. ``y = 0`` is the
walkable level; the paved platform is ``y = -1``. Core reads ``data/underground_city/districts/*.json``.

Founders Row is one small lot that makes the first playable loop usable end to end: an Operations Terminal with the
supply clerk, three registered Household Safes, a Technician's Secured Data Terminal and a glass greenhouse for the
Farmer's controlled crop. It is an operator tool for a fresh world, not generated city content.

``python scripts/generate_district.py`` writes the file; ``--check`` fails when the committed file differs.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "rpg-core/src/main/resources/data/underground_city/districts/founders_row.json"
WIDTH, DEPTH, HEIGHT = 25, 17, 8


def fill(a, b, block):
    return {"op": "fill", "from": list(a), "to": list(b), "block": block}


def put(at, block):
    return {"op": "block", "at": list(at), "block": block}


def stall(x0: int, x1: int, floor: str) -> list[dict]:
    """A roofed open-front stall against the north wall: wood floor, back wall, fence posts, slab roof, hanging lantern."""
    mid = (x0 + x1) // 2
    return [
        fill((x0, -1, 1), (x1, -1, 5), floor),
        fill((x0, 0, 1), (x1, 2, 1), "minecraft:spruce_planks"),
        fill((x0, 0, 5), (x0, 2, 5), "minecraft:spruce_fence"),
        fill((x1, 0, 5), (x1, 2, 5), "minecraft:spruce_fence"),
        fill((x0, 3, 1), (x1, 3, 5), "minecraft:spruce_slab[type=bottom]"),
        put((mid, 2, 3), "minecraft:lantern[hanging=true]"),
    ]


def build() -> dict:
    ops = [
        fill((0, -4, 0), (WIDTH - 1, -2, DEPTH - 1), "minecraft:stone"),
        fill((0, -1, 0), (WIDTH - 1, -1, DEPTH - 1), "minecraft:stone_bricks"),
        fill((0, 0, 0), (WIDTH - 1, HEIGHT - 1, DEPTH - 1), "minecraft:air"),
        fill((10, -1, 6), (14, -1, DEPTH - 1), "minecraft:polished_andesite"),
        fill((0, 0, 0), (WIDTH - 1, 0, 0), "minecraft:stone_brick_wall"),
    ]
    for x in (0, 6, 12, 18, 24):
        ops += [fill((x, 0, 0), (x, 2, 0), "minecraft:stone_brick_wall"), put((x, 3, 0), "minecraft:lantern")]
    for x, z in ((0, DEPTH - 1), (WIDTH - 1, DEPTH - 1), (12, DEPTH - 1)):
        ops += [fill((x, 0, z), (x, 2, z), "minecraft:stone_brick_wall"), put((x, 3, z), "minecraft:lantern")]
    # A: Operations Terminal and the supply clerk.
    ops += stall(1, 5, "minecraft:spruce_planks")
    ops += [{"op": "terminal", "at": [3, 0, 2], "facing": "south"}, {"op": "clerk", "at": [4, 0, 2], "terminal": [3, 0, 2]}]
    # B: records office with three registered Household Safes of different security.
    ops += stall(7, 11, "minecraft:smooth_stone")
    for x, policy in ((8, "apartment_common"), (9, "office"), (10, "industrial")):
        ops.append({"op": "safe", "at": [x, 0, 2], "policy": policy})
    # C: data office, walled on three sides with a window of bars, holding the Secured Data Terminal.
    ops += [
        fill((13, -1, 1), (17, -1, 5), "minecraft:polished_deepslate"),
        fill((13, 0, 1), (17, 3, 1), "minecraft:deepslate_bricks"),
        fill((13, 0, 2), (13, 3, 5), "minecraft:deepslate_bricks"),
        fill((17, 0, 2), (17, 3, 5), "minecraft:deepslate_bricks"),
        fill((13, 4, 1), (17, 4, 5), "minecraft:deepslate_brick_slab[type=bottom]"),
        fill((14, 3, 5), (16, 3, 5), "minecraft:deepslate_bricks"),
        fill((13, 1, 3), (13, 2, 3), "minecraft:iron_bars"),
        fill((17, 1, 3), (17, 2, 3), "minecraft:iron_bars"),
        put((15, 3, 3), "minecraft:lantern[hanging=true]"),
        {"op": "secure_terminal", "at": [15, 0, 2]},
    ]
    # D: glass greenhouse with irrigated soil for the controlled crop.
    ops += [
        fill((19, -1, 1), (23, -1, 6), "minecraft:stone_bricks"),
        fill((20, -1, 2), (22, -1, 5), "minecraft:farmland[moisture=7]"),
        put((21, -1, 3), "minecraft:water"),
        put((21, -1, 4), "minecraft:water"),
        fill((19, 0, 1), (23, 3, 1), "minecraft:glass"),
        fill((19, 0, 1), (19, 3, 6), "minecraft:glass"),
        fill((23, 0, 1), (23, 3, 6), "minecraft:glass"),
        fill((19, 0, 6), (23, 3, 6), "minecraft:glass"),
        fill((19, 4, 1), (23, 4, 6), "minecraft:glass"),
        fill((21, 0, 6), (21, 1, 6), "minecraft:air"),
        put((20, 3, 2), "minecraft:lantern[hanging=true]"),
        put((22, 3, 5), "minecraft:lantern[hanging=true]"),
    ]
    return {
        "schema_version": 1, "id": "founders_row", "title": "Founders Row",
        "note": "Operator-built lot for a fresh world; origin is the north-west corner, extending east and south.",
        "size": [WIDTH, HEIGHT + 4, DEPTH], "ops": ops,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    text = json.dumps(build(), indent=1, ensure_ascii=False) + "\n"
    if args.check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print("Committed district differs; run scripts/generate_district.py")
            return 1
        print("District matches the generator")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"Wrote {OUT.relative_to(ROOT)} with {len(build()['ops'])} operations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
