#!/usr/bin/env python3
"""Reads a generated world back and finds, in every furnished building it holds, the blocks of the building's plan that the world does not have.

Lost Cities writes a part's blocks lower x first, then lower z, then from the top down, and as each block goes in it has the blocks beside it check their shape against the world, where
what it has not written yet is missing. A block that needs a support written after it (a carpet or a garden plant on its own floor, a shelf, a screen or a camera on a wall to its east
or south) turns to air, and every check that reads the plan alone sees it as there. This compares the plan with what a world really holds: the October 24 read-back of a new world found the
club's and the warehouse's cameras, the club's shelves, the store's screen, the flats' carpets and the cottages' berry bushes and ferns missing (city_content.fit_for_lost_cities).

    python scripts/world_plan_audit.py <world folder>                         a summary: each missing plan block, how often, and one example
    python scripts/world_plan_audit.py <world folder> --json out.json --fail  the full report; exit code 1 when anything is missing

A chunk is matched to a building by its ground floor's plain blocks (more than 95 percent alike at some height); its other floors are then matched six blocks apart above it. Blocks
Lost Cities changes on purpose are not reported (a flower pot gets a random plant; grass under a block turns to dirt). The world is only read (a disposable test world). Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from world_furniture_audit import chunks_of_region, section_blocks  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
LOSTCITIES = ROOT / "content/generated/world-furnished/pack/data/underground_city/lostcities"
FLOOR_H = 6
GROUND_BASES = range(64, 80)
# what the game itself replaces, so that a difference is not a loss: grass under a block becomes dirt. (A flower pot and a pumpkin were here too, thought to be Lost Cities' own
# choices; they were its reading of 1.12 names, and are now written so that it places them as drawn: citykit.LEGACY_RENAMED.)
ON_PURPOSE = {"minecraft:grass_block": "minecraft:dirt"}


def load_parts(lostcities: Path = LOSTCITIES) -> dict[str, tuple[list, dict[str, str]]]:
    """Every part of the furnished pack: its layers and its palette (character -> block)."""
    parts = {}
    for path in sorted((lostcities / "parts").glob("*.json")):
        part = json.loads(path.read_text(encoding="utf-8"))
        palette_path = lostcities / "palettes" / (part["refpalette"].split(":", 1)[1] + ".json")
        palette = {e["char"]: e.get("block", "") for e in json.loads(palette_path.read_text(encoding="utf-8"))["palette"]}
        parts[path.stem] = (part["slices"], palette)
    return parts


def likeness(grid: dict, slices: list, palette: dict, base: int, layers: int = 3) -> float:
    """The share of the part's plain blocks (vanilla, no states) in its first layers that the world holds at this height; 0 when it has none."""
    alike = total = 0
    for y, layer in enumerate(slices[:layers]):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                block = palette.get(ch, "")
                if ch == " " or not block.startswith("minecraft:") or "[" in block:
                    continue
                total += 1
                alike += grid.get((x, base + y, z)) == block
    return alike / total if total else 0.0


def missing(grid: dict, slices: list, palette: dict, base: int) -> list[tuple[tuple[int, int, int], str, str]]:
    """The plan's blocks the world does not hold at this height: (position, planned block, what is there)."""
    out = []
    for y, layer in enumerate(slices):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                block = palette.get(ch, "")
                if ch == " " or not block:
                    continue
                name = block.split("[", 1)[0]
                have = grid.get((x, base + y, z), "minecraft:air")
                if have == name or (name in ON_PURPOSE and ON_PURPOSE[name] in have):
                    continue
                out.append(((x, base + y, z), block, have))
    return out


def match(grid: dict, parts: dict) -> list[tuple[str, int]]:
    """The parts a chunk holds: its ground floor at the height it fits best, then each further floor of the same building above it (best fit, six blocks a floor)."""
    best = None
    for name, (slices, palette) in parts.items():
        if not name.endswith("_ground"):
            continue
        for base in GROUND_BASES:
            fit = likeness(grid, slices, palette, base)
            if fit > 0.95 and (best is None or fit > best[0]):
                best = (fit, name, base)
    if best is None:
        return []
    _, ground, base = best
    building = ground[: -len("_ground")]
    found = [(ground, base)]
    others = [n for n in parts if n.startswith(building + "_") and n != ground]
    for k in range(1, 8):
        level = base + k * FLOOR_H
        fits = [(likeness(grid, parts[n][0], parts[n][1], level, FLOOR_H), n) for n in others]
        fits = [f for f in fits if f[0] > 0.9]
        if not fits:
            break
        found.append((max(fits)[1], level))
    return found


def audit(world: Path, parts: dict | None = None) -> dict:
    parts = parts if parts is not None else load_parts()
    lost: Counter = Counter()
    examples: dict = defaultdict(list)
    matched: Counter = Counter()
    for region in sorted((world / "region").glob("r.*.mca")):
        for cx, cz, root in chunks_of_region(region):
            grid = {}
            for section in root.get("sections", []):
                y0 = section.get("Y", 0) * 16
                if y0 < GROUND_BASES.start - 16 or y0 > GROUND_BASES.stop + 8 * FLOOR_H:
                    continue
                for index, name in section_blocks(section, lambda n: n != "minecraft:air").items():
                    grid[(index % 16, y0 + index // 256, (index // 16) % 16)] = name
            for name, base in match(grid, parts):
                matched[name] += 1
                for pos, block, have in missing(grid, *parts[name], base):
                    lost[(name, block)] += 1
                    if len(examples[(name, block)]) < 3:
                        examples[(name, block)].append({"chunk": [cx, cz], "at": list(pos), "found": have})
    return {"parts_matched": dict(sorted(matched.items())),
            "missing": [{"part": part, "block": block, "count": n, "examples": examples[(part, block)]} for (part, block), n in lost.most_common()]}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("world", type=Path)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--fail", action="store_true", help="exit code 1 when any plan block is missing")
    args = parser.parse_args(argv)
    report = audit(args.world)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("parts matched:", report["parts_matched"])
    for entry in report["missing"]:
        print(f"{entry['count']:5d}  {entry['part']:28s} {entry['block']:60s} e.g. {entry['examples'][0]}")
    if not report["missing"]:
        print("every planned block is in the world")
    return 1 if args.fail and report["missing"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
