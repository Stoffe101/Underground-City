#!/usr/bin/env python3
"""Do the generated buildings stand as their data says? Missing blocks and foreign blocks, building by building (owner request, ninth pass).

``CityProbe`` (``run_city_probe.py --dump <folder> --dump-prefix ""``) writes every generated building chunk as text, layer by layer. This tool
renders the same building from its Lost Cities data (the buildings, parts and palettes of the packs given) for the floors Lost Cities chose,
and compares the two cell by cell over the chunk, from the street up:

* **missing**: the data puts a block there and the world has air (a hole: a block the game dropped, or a port that mapped a block to air);
* **foreign**: the data leaves the cell empty and the world has a block (something else built into the building: another structure, a run-time
  builder, the street of a neighbouring chunk);
* **other**: both have a block but not the same one (a random palette choice, a style's block, or a block replaced by something else);
* **above**: a block over the building's top, where its data has nothing at all (another structure, a tree or a run-time builder over the roof).

A palette character the packs do not define (a city style's own characters) matches anything, and a random palette entry matches any of its
choices. The report is counts and block ids per building type, never a building's content, so it can be shared for a private pack too.

    building_audit.py --dumps <folder> --pack <pack folder> [--pack ...] [--json out.json]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

FLOOR = 6
HEADER = re.compile(r"^# (?P<type>\S+) chunk (?P<cx>-?\d+),(?P<cz>-?\d+) floors (?P<floors>\d+) cellars (?P<cellars>\d+) street y (?P<street>-?\d+)")
LAYER = re.compile(r"^--- y (?P<y>-?\d+) \(street (?P<rel>[+-]?\d+)\)")
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air", ""}
WILD = None   # a cell the data does not decide


def block_id(spec: str) -> str:
    spec = (spec or "").split("[", 1)[0]
    return spec if ":" in spec else "minecraft:" + spec


class Packs:
    def __init__(self, roots: list[Path]):
        self.roots = roots
        self.cache: dict[tuple[str, str], dict | None] = {}

    def doc(self, kind: str, rid: str) -> dict | None:
        key = (kind, rid)
        if key not in self.cache:
            ns, path = rid.split(":", 1) if ":" in rid else ("minecraft", rid)
            found = None
            for root in self.roots:
                f = root / "data" / ns / "lostcities" / kind / f"{path}.json"
                if f.is_file():
                    found = json.loads(f.read_text(encoding="utf-8"))
            self.cache[key] = found
        return self.cache[key]

    def palette(self, rid: str | None) -> dict[str, frozenset | None]:
        out: dict[str, frozenset | None] = {}
        doc = self.doc("palettes", rid) if rid else None
        for entry in (doc or {}).get("palette", []):
            out[str(entry.get("char"))] = options(entry)
        return out


def options(entry: dict) -> frozenset | None:
    """The block ids a palette entry may become, or None when it cannot be told (a variant, a style's block)."""
    if "block" in entry:
        return frozenset({block_id(entry["block"])})
    if "blocks" in entry:
        return frozenset(block_id(b.get("block", "")) for b in entry["blocks"])
    return WILD


def part_matches(p: dict, f: int, floors: int) -> bool:
    if p.get("cellar", False) != (f < 0):
        return False
    if "ground" in p and p["ground"] != (f == 0):
        return False
    if "top" in p and p["top"] != (f == floors):
        return False
    if "floor" in p and int(p["floor"]) != f:
        return False
    if "range" in p:
        lo, _, hi = str(p["range"]).partition("-")
        if not (int(lo) <= f <= int(hi or lo)):
            return False
    return True


def render(packs: Packs, building_id: str, floors: int) -> dict[tuple[int, int, int], frozenset | None] | None:
    """The expected cells of a building with ``floors`` floors: (x, y above street, z) -> the block ids allowed there (an empty set is air)."""
    building = packs.doc("buildings", building_id)
    if building is None:
        return None
    base = packs.palette(building.get("refpalette"))
    base.update({str(e.get("char")): options(e) for e in building.get("palette", []) if isinstance(e, dict)})
    cells: dict[tuple[int, int, int], frozenset | None] = {}
    for f in range(0, floors + 1):
        part_ref = next((p for p in building.get("parts", []) if isinstance(p, dict) and part_matches(p, f, floors)), None)
        if part_ref is None:
            continue
        part = packs.doc("parts", part_ref["part"])
        if part is None:
            continue
        palette = dict(base)
        palette.update(packs.palette(part.get("refpalette")))
        palette.update({str(e.get("char")): options(e) for e in part.get("palette", []) if isinstance(e, dict)})
        for dy, layer in enumerate(part.get("slices", [])):
            for z, row in enumerate(layer):
                for x, ch in enumerate(row):
                    y = f * FLOOR + dy
                    if ch == " ":
                        cells[(x, y, z)] = frozenset()
                    else:
                        cells[(x, y, z)] = palette.get(ch, WILD)
    return cells


def parse_dump(path: Path) -> dict | None:
    lines = path.read_text(encoding="utf-8").split("\n")
    head = HEADER.match(lines[0]) if lines else None
    if not head:
        return None
    legend: dict[str, str] = {}
    for line in lines[:4]:
        if line.startswith("# legend:"):
            for item in line[len("# legend:"):].split():
                glyph, _, name = item.partition("=")
                legend[glyph] = block_id(name)
    world: dict[tuple[int, int, int], str] = {}
    rel = None
    z = 0
    for line in lines[1:]:
        m = LAYER.match(line)
        if m:
            rel, z = int(m.group("rel")), 0
            continue
        if rel is None or line.startswith("#") or len(line) != 16:
            continue
        for x, ch in enumerate(line):
            world[(x, rel, z)] = "minecraft:air" if ch == "." else legend.get(ch, "?")
        z += 1
    return {"type": head.group("type"), "chunk": (int(head.group("cx")), int(head.group("cz"))), "floors": int(head.group("floors")), "world": world, "ambiguous": "~" in legend}


def audit(dumps: Path, packs: Packs) -> dict:
    per_type: dict[str, dict] = defaultdict(lambda: {"chunks": 0, "missing": 0, "foreign": 0, "other": 0, "above": 0, "cells": 0, "missing_blocks": Counter(), "foreign_blocks": Counter(),
                                                     "above_blocks": Counter(), "worst": []})
    unknown = Counter()
    for path in sorted(dumps.glob("*.txt")):
        d = parse_dump(path)
        if d is None:
            continue
        cells = render(packs, d["type"], d["floors"])
        if cells is None:
            unknown[d["type"]] += 1
            continue
        t = per_type[d["type"]]
        t["chunks"] += 1
        miss = foreign = other = 0
        for (x, y, z), allowed in cells.items():
            if y < 0 or allowed is WILD:
                continue
            got = d["world"].get((x, y, z))
            if got is None or got == "?":
                continue
            t["cells"] += 1
            if not allowed:
                if got not in AIR:
                    foreign += 1
                    t["foreign_blocks"][got] += 1
            elif got in AIR:
                if not (allowed & AIR):
                    miss += 1
                    t["missing_blocks"][sorted(allowed)[0]] += 1
            elif got not in allowed:
                other += 1
        # over the top of the building: nothing of its data stands there, so any block is something else's
        roof = max((y for (_, y, _) in cells), default=-1)
        above = 0
        for (x, y, z), got in d["world"].items():
            if y > roof and got not in AIR and got != "?":
                above += 1
                t["above_blocks"][got] += 1
        t["missing"] += miss
        t["foreign"] += foreign
        t["other"] += other
        t["above"] += above
        t["worst"].append((miss + foreign + above, f"{d['chunk'][0]},{d['chunk'][1]}"))
    report = {"types": {}, "no_data_for": dict(unknown)}
    for name, t in sorted(per_type.items()):
        t["worst"].sort(reverse=True)
        report["types"][name] = {"chunks": t["chunks"], "cells_compared": t["cells"], "missing": t["missing"], "foreign": t["foreign"], "other": t["other"], "above": t["above"],
                                 "missing_blocks": dict(t["missing_blocks"].most_common(8)), "foreign_blocks": dict(t["foreign_blocks"].most_common(8)),
                                 "above_blocks": dict(t["above_blocks"].most_common(8)),
                                 "worst_chunks": [c for _, c in t["worst"][:3]]}
    return report


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dumps", type=Path, required=True)
    parser.add_argument("--pack", type=Path, action="append", default=[], help="a pack folder holding data/<ns>/lostcities (repeatable; later packs win, as in the game)")
    parser.add_argument("--json", type=Path)
    args = parser.parse_args(argv)
    report = audit(args.dumps, Packs(args.pack))
    if args.json:
        args.json.write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8")
    for name, t in report["types"].items():
        share = lambda n: f"{100.0 * n / max(1, t['cells_compared']):.1f}%"
        print(f"{name}: {t['chunks']} chunks, missing {t['missing']} ({share(t['missing'])}), foreign {t['foreign']} ({share(t['foreign'])}), other {t['other']}, above {t['above']}"
              + (f"; missing {t['missing_blocks']}" if t["missing"] else "") + (f"; foreign {t['foreign_blocks']}" if t["foreign"] else "")
              + (f"; above {t['above_blocks']}" if t["above"] else ""))
    if report["no_data_for"]:
        print("no data for: " + ", ".join(f"{k} ({v})" for k, v in report["no_data_for"].items()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
