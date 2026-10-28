#!/usr/bin/env python3
"""Do the generated buildings stand as their data says? Missing blocks and foreign blocks, building by building (owner request, ninth pass).

``CityProbe`` (``run_city_probe.py --dump <folder> --dump-prefix ""``) writes every generated building chunk as text, layer by layer. This tool
renders the same building from its Lost Cities data (the buildings, parts and palettes of the packs given) for the floors Lost Cities chose,
and compares the two cell by cell over the chunk, from the street up:

* **missing**: the data puts a block there and the world has air (a hole: a block the game dropped, or a port that mapped a block to air);
* **foreign**: the data leaves the cell empty and the world has a block (something else built into the building: another structure, a run-time
  builder, the street of a neighbouring chunk);
* **other**: both have a block but not the same one (a random palette choice, a style's block, or a block replaced by something else);
* **above**: a block over the building's top, where its data has nothing at all (another structure, a tree or a run-time builder over the roof);
* **buried**: a cell one storey draws as air that a storey above draws as a block (a part taller than a storey, put up again six blocks higher).

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


def inline(doc: dict) -> list:
    """A part's or building's inline palette entries, in either form Lost Cities reads (a list, or ``{"palette": [...]}``)."""
    entries = doc.get("palette")
    if isinstance(entries, dict):
        entries = entries.get("palette")
    return entries if isinstance(entries, list) else []


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


def part_cells(packs: Packs, part: dict, base: dict, f: int) -> dict[tuple[int, int, int], frozenset | None]:
    """The cells one part draws as floor ``f`` of a building whose own palette is ``base``."""
    palette = dict(base)
    palette.update(packs.palette(part.get("refpalette")))
    palette.update({str(e.get("char")): options(e) for e in inline(part) if isinstance(e, dict)})
    cells: dict[tuple[int, int, int], frozenset | None] = {}
    for dy, layer in enumerate(part.get("slices", [])):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                cells[(x, f * FLOOR + dy, z)] = frozenset() if ch == " " else palette.get(ch, WILD)
    return cells


def mismatches(cells: dict, world: dict) -> int:
    """How many cells of ``cells`` the world does not hold as drawn (a missing, foreign or other block)."""
    n = 0
    for key, allowed in cells.items():
        got = world.get(key)
        if allowed is WILD or got is None or got == "?":
            continue
        if not allowed:
            n += got not in AIR
        elif got in AIR:
            n += not (allowed & AIR)
        else:
            n += got not in allowed
    return n


def buried(packs: Packs, building_id: str, floors: int) -> int:
    """Cells one storey draws as air that a storey above it draws as a block: a part taller than a storey put up again six blocks higher fills the rooms below
    (a cabin on its own mound, eleventh pass). The world then matches the data, so ``render`` alone cannot tell; this counts it from the data."""
    building = packs.doc("buildings", building_id)
    if building is None:
        return 0
    drawn: dict[tuple[int, int, int], bool] = {}   # cell -> drawn as air by an earlier storey
    n = 0
    for f in range(0, floors + 1):
        candidates = [p for p in building.get("parts", []) if isinstance(p, dict) and part_matches(p, f, floors)]
        part = packs.doc("parts", candidates[0]["part"]) if candidates else None
        if part is None:
            continue
        for dy, layer in enumerate(part.get("slices", [])):
            for z, row in enumerate(layer):
                for x, ch in enumerate(row):
                    key = (x, f * FLOOR + dy, z)
                    if ch != " " and drawn.get(key) is True:
                        n += 1
                    drawn[key] = ch == " "
    return n


def render(packs: Packs, building_id: str, floors: int, world: dict | None = None) -> dict[tuple[int, int, int], frozenset | None] | None:
    """The expected cells of a building with ``floors`` floors: (x, y above street, z) -> the block ids allowed there (an empty set is air).

    Lost Cities picks one at random of the parts that fit a floor (a hotel's three storeys, say). Without ``world`` the first is drawn; with the generated
    chunk's blocks, the one that fits them best, so a storey the game chose is not counted as the wrong storey's missing blocks. A real defect still shows:
    no candidate matches it."""
    building = packs.doc("buildings", building_id)
    if building is None:
        return None
    base = packs.palette(building.get("refpalette"))
    base.update({str(e.get("char")): options(e) for e in inline(building) if isinstance(e, dict)})
    cells: dict[tuple[int, int, int], frozenset | None] = {}
    for f in range(0, floors + 1):
        candidates = [p for p in building.get("parts", []) if isinstance(p, dict) and part_matches(p, f, floors)]
        best = None
        for ref in candidates if world is not None else candidates[:1]:
            part = packs.doc("parts", ref["part"])
            if part is None:
                continue
            drawn = part_cells(packs, part, base, f)
            score = mismatches(drawn, world) if world is not None else 0
            if best is None or score < best[0]:
                best = (score, drawn)
        if best is not None:
            cells.update(best[1])
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
                if len(item) > 2 and item[1] == "=":      # the glyph is one character, and may itself be "=" in a dump written before the ninth pass
                    legend[item[0]] = block_id(item[2:])
    # A dump written before the ninth pass gave its 71st block kind the dot of air: there a dot is that block or air, and is not counted either way
    dot = "?" if "." in legend else "minecraft:air"
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
            world[(x, rel, z)] = dot if ch == "." else legend.get(ch, "?")
        z += 1
    return {"type": head.group("type"), "chunk": (int(head.group("cx")), int(head.group("cz"))), "floors": int(head.group("floors")), "world": world, "ambiguous": "~" in legend}


def audit(dumps: Path, packs: Packs) -> dict:
    per_type: dict[str, dict] = defaultdict(lambda: {"chunks": 0, "missing": 0, "foreign": 0, "other": 0, "above": 0, "buried": 0, "cells": 0, "missing_blocks": Counter(), "foreign_blocks": Counter(),
                                                     "above_blocks": Counter(), "worst": []})
    unknown = Counter()
    for path in sorted(dumps.glob("*.txt")):
        d = parse_dump(path)
        if d is None:
            continue
        cells = render(packs, d["type"], d["floors"], d["world"])
        if cells is None:
            unknown[d["type"]] += 1
            continue
        t = per_type[d["type"]]
        t["chunks"] += 1
        t["buried"] += buried(packs, d["type"], d["floors"])
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
        report["types"][name] = {"chunks": t["chunks"], "cells_compared": t["cells"], "missing": t["missing"], "foreign": t["foreign"], "other": t["other"], "above": t["above"], "buried": t["buried"],
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
        print(f"{name}: {t['chunks']} chunks, missing {t['missing']} ({share(t['missing'])}), foreign {t['foreign']} ({share(t['foreign'])}), other {t['other']}, above {t['above']}" + (f", buried {t['buried']}" if t["buried"] else "")
              + (f"; missing {t['missing_blocks']}" if t["missing"] else "") + (f"; foreign {t['foreign_blocks']}" if t["foreign"] else "")
              + (f"; above {t['above_blocks']}" if t["above"] else ""))
    if report["no_data_for"]:
        print("no data for: " + ", ".join(f"{k} ({v})" for k, v in report["no_data_for"].items()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
