#!/usr/bin/env python3
"""Draw a city's ground from a city probe report, and count what lies on its streets (D-196).

    python scripts/city_surface_map.py build/probe/t10.json --out build/probe/t10-maps

For every probed chunk the probe writes, per column, the top block from three under the street height to four above it (``surface``, ``surface_rel``) and, when it ran with
``-PucCpTrace=true``, which columns changed after Lost Cities built the chunk (``after_lost_cities.changed_columns``). One picture per city is drawn, a pixel per column:
paving grey, buildings dark, grass green, earth brown, gravel and natural stone blue-grey, ore magenta, plants light green, mushrooms red, water blue. A change made after Lost
Cities is marked on top: red in the street's own layer, orange above it, violet only underneath. The text summary counts, for the chunks without a building (streets, squares,
parks), the columns whose surface is natural terrain, and the changes by cause and kind.

The pictures are drawn from a probe of a disposable world; a probe that loaded private packs writes its report and its pictures outside the checkout (private-content-workflow.md).
"""
from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CITIES = ROOT / "content" / "region-blueprints" / "archipelago" / "cities.json"

TERRAIN = {
    "grass_block": "grass", "dirt": "earth", "coarse_dirt": "earth", "podzol": "earth", "rooted_dirt": "earth", "mud": "earth", "mycelium": "earth", "dirt_path": "path",
    "moss_block": "earth", "gravel": "rock", "sand": "sand", "red_sand": "sand", "clay": "rock", "stone": "rock", "granite": "rock", "diorite": "rock", "andesite": "rock",
    "tuff": "rock", "deepslate": "rock", "calcite": "rock", "water": "water", "lava": "lava",
}
COLOURS = {
    "paving": (150, 150, 150), "building": (70, 70, 78), "grass": (86, 140, 60), "earth": (125, 88, 52), "path": (150, 120, 70), "rock": (105, 115, 135), "sand": (215, 200, 140),
    "ore": (200, 40, 200), "plant": (150, 205, 110), "mushroom": (230, 30, 30), "tree": (35, 85, 35), "water": (50, 90, 200), "lava": (255, 120, 0), "none": (0, 0, 0),
}
MARKS = {"s": (255, 0, 0), "h": (255, 165, 0), "f": (160, 80, 220)}


def kind(name: str) -> str:
    """The class of a surface block, from its registry name (minecraft: prefix dropped by the probe)."""
    if name in TERRAIN:
        return TERRAIN[name]
    if name.endswith("_ore"):
        return "ore"
    if "mushroom" in name and "block" not in name and "stem" not in name:
        return "mushroom"
    if name.endswith("_leaves") or name.endswith("_log") or name.endswith("_wood"):
        return "tree"
    if name in ("grass", "short_grass", "tall_grass", "fern", "large_fern") or name.endswith("_tulip") or name in (
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower", "lily_of_the_valley", "sunflower", "lilac", "rose_bush", "peony", "dead_bush"):
        return "plant"
    return "paving"


def city_of(chunk_x: int, chunk_z: int, cities: list[dict]) -> str:
    best, best_d = "", math.inf
    for c in cities:
        cx, cz = c["center_chunk"]
        d = math.hypot(chunk_x - cx, chunk_z - cz)
        if d < best_d:
            best, best_d = c["id"], d
    return best


def street_blocks(report: dict, cities: list[dict]) -> dict[str, str]:
    """Each city's street block: the built block most often at the street height in its chunks without a building (Lost Cities lays its street there; park beds, borders and
    station platforms are other built ground)."""
    legend = report.get("surface_legend", [])
    counts: dict[str, Counter] = defaultdict(Counter)
    for chunk in report["chunks"]:
        if not chunk.get("city") or chunk.get("type") or not chunk.get("street_layer"):
            continue
        name = city_of(chunk["chunk_x"], chunk["chunk_z"], cities)
        for index in chunk["street_layer"]:
            block = legend[index] if 0 <= index < len(legend) else "air"
            if kind(block) == "paving" and block != "air":
                counts[name][block] += 1
    return {name: c.most_common(1)[0][0] for name, c in counts.items() if c}


def summarise(report: dict, paving: set[str] | None = None) -> dict:
    """Counts per city: street-chunk columns by surface class, terrain at the street layer, what stands on the paving, and the changes after Lost Cities by zone, cause and kind."""
    cities = json.loads(CITIES.read_text(encoding="utf-8"))["cities"]
    legend = report.get("surface_legend", [])
    streets = street_blocks(report, cities)
    out: dict = defaultdict(lambda: {"street_chunks": 0, "building_chunks": 0, "street_surface": Counter(), "terrain_on_street": Counter(),
                                     "changes_by_zone": Counter(), "changes_by_cause": Counter(), "changes_by_kind": Counter(), "terrain_examples": [],
                                     "paved_cells": 0, "on_paving": Counter(), "on_paving_examples": [], "holes_in_paving": Counter(), "bare_verge": Counter(),
                                     "street_block": "", "on_other_built_ground": Counter()})
    for chunk in report["chunks"]:
        if not chunk.get("city"):
            continue
        name = city_of(chunk["chunk_x"], chunk["chunk_z"], cities)
        row = out[name]
        street = not chunk.get("type")
        row["street_chunks" if street else "building_chunks"] += 1
        surface, rel = chunk.get("surface", []), chunk.get("surface_rel", "")
        if street and surface:
            for i, index in enumerate(surface):
                block = legend[index] if 0 <= index < len(legend) else "none"
                k = kind(block)
                row["street_surface"][k] += 1
                # natural terrain at the street's own height (where Lost Cities lays its street block) or above it, in a chunk the city laid out as street, square or park
                if k in ("earth", "rock", "ore", "sand", "mushroom") and rel and rel[i] != "." and int(rel[i]) - 3 >= 0:
                    row["terrain_on_street"][block] += 1
                    if len(row["terrain_examples"]) < 12:
                        x = chunk["chunk_x"] * 16 + i % 16
                        z = chunk["chunk_z"] * 16 + i // 16
                        row["terrain_examples"].append(f"{x},{chunk['street_y'] + int(rel[i]) - 3},{z} {block}")
        if street and chunk.get("street_layer"):
            row["street_block"] = ",".join(sorted(paving)) if paving else streets.get(name, "")
            paved(chunk, legend, row, paving or {streets.get(name, "")})
        after = chunk.get("after_lost_cities")
        if after:
            row["changes_by_zone"].update(after.get("by_zone", {}))
            row["changes_by_cause"].update(after.get("by_cause", {}))
            row["changes_by_kind"].update(after.get("by_kind", {}))
    return out


def paved(chunk: dict, legend: list[str], row: dict, paving: set[str]) -> None:
    """The paved road of a street chunk: its cells are those whose block at the street height is built (not terrain, plant or water). Counts what stands on them (the first
    block in the four over the paving, by class: a plant, earth or stone on the road is the owner's complaint; a lamp or a bench is the city's), the holes in the paving (a
    terrain cell at the street height with paving on three or four sides), and the bare verges (stone, gravel, earth or ore at the street height, open to the sky)."""
    layer, over, over_rel = chunk["street_layer"], chunk.get("over_street", []), chunk.get("over_street_rel", "")

    def name(index: int) -> str:
        return legend[index] if 0 <= index < len(legend) else "air"

    kinds = [("street" if name(i) in paving else kind(name(i))) if i >= 0 else "none" for i in layer]
    for i, k in enumerate(kinds):
        x, z = i % 16, i // 16
        wx, wz = chunk["chunk_x"] * 16 + x, chunk["chunk_z"] * 16 + z
        if k == "paving":
            # built ground that is not the street: a park's raised bed, a border, a station's platform; what stands on it is the city's design or its plants
            if i < len(over) and over[i] >= 0:
                row["on_other_built_ground"][name(over[i])] += 1
            continue
        if k == "street":
            row["paved_cells"] += 1
            if i < len(over) and over[i] >= 0:
                block = name(over[i])
                cls = kind(block)
                label = ("vegetation" if cls in ("plant", "mushroom", "tree") else "terrain" if cls in ("grass", "earth", "rock", "sand", "ore", "path") else "built") + ":" + block
                row["on_paving"][label] += 1
                if not label.startswith("built") and len(row["on_paving_examples"]) < 15:
                    row["on_paving_examples"].append(f"{wx},{chunk['street_y'] + int(over_rel[i])},{wz} {block}")
            continue
        if k in ("earth", "rock", "ore", "sand", "grass", "path"):
            around = [kinds[j] for j in (i - 16 if z > 0 else -1, i + 16 if z < 15 else -1, i - 1 if x > 0 else -1, i + 1 if x < 15 else -1) if j >= 0]
            if sum(1 for n in around if n == "street") >= 3:
                row["holes_in_paving"][name(layer[i])] += 1
            elif k != "grass" and (i >= len(over) or over[i] < 0):
                row["bare_verge"][name(layer[i])] += 1


def draw(report: dict, out_dir: Path, scale: int = 3) -> list[Path]:
    from PIL import Image  # only the pictures need Pillow

    cities = json.loads(CITIES.read_text(encoding="utf-8"))["cities"]
    legend = report.get("surface_legend", [])
    by_city: dict[str, list[dict]] = defaultdict(list)
    for chunk in report["chunks"]:
        by_city[city_of(chunk["chunk_x"], chunk["chunk_z"], cities)].append(chunk)
    written = []
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, chunks in sorted(by_city.items()):
        xs = [c["chunk_x"] for c in chunks]
        zs = [c["chunk_z"] for c in chunks]
        x0, z0 = min(xs), min(zs)
        width, height = (max(xs) - x0 + 1) * 16, (max(zs) - z0 + 1) * 16
        image = Image.new("RGB", (width, height), COLOURS["none"])
        pixels = image.load()
        for c in chunks:
            surface = c.get("surface", [])
            marks = (c.get("after_lost_cities") or {}).get("changed_columns", "")
            building = bool(c.get("type"))
            for i in range(256):
                px = (c["chunk_x"] - x0) * 16 + i % 16
                pz = (c["chunk_z"] - z0) * 16 + i // 16
                block = legend[surface[i]] if i < len(surface) and 0 <= surface[i] < len(legend) else "none"
                k = kind(block) if block != "none" else "none"
                colour = COLOURS["building"] if building and k == "paving" else COLOURS[k]
                if not c.get("city"):
                    colour = tuple(v // 2 for v in colour)
                if marks and marks[i] in MARKS:
                    colour = MARKS[marks[i]]
                pixels[px, pz] = colour
        image = image.resize((width * scale, height * scale), Image.NEAREST)
        path = out_dir / f"{name}-surface.png"
        image.save(path)
        written.append(path)
    return written


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("report", type=Path)
    parser.add_argument("--out", type=Path, default=None, help="a folder for one picture per city (needs Pillow); the summary is printed either way")
    parser.add_argument("--json", type=Path, default=None, help="write the summary as json")
    parser.add_argument("--paving", default="", help="the street blocks, comma separated (default: each city's most common built block at the street height in its street chunks)")
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    summary = summarise(report, {b.strip() for b in args.paving.split(",") if b.strip()} or None)
    print(report.get("city_ground", ""))
    for name, row in sorted(summary.items()):
        print(f"\n== {name}: {row['street_chunks']} street, square or park chunks, {row['building_chunks']} building chunks")
        print("  street-chunk surface:", dict(row["street_surface"].most_common()))
        print("  natural terrain at or above the street layer:", dict(row["terrain_on_street"].most_common()) or "none")
        for example in row["terrain_examples"]:
            print("    ", example)
        print(f"  paved road ({row['street_block']}): {row['paved_cells']} cells; on the paving:", dict(row["on_paving"].most_common(20)) or "nothing")
        for example in row["on_paving_examples"]:
            print("    ", example)
        print("  holes in the paving:", dict(row["holes_in_paving"].most_common()) or "none", "; bare verges:", dict(row["bare_verge"].most_common()) or "none")
        print("  on other built ground at the street height (park beds, borders, platforms):", dict(row["on_other_built_ground"].most_common(8)) or "nothing")
        if row["changes_by_zone"]:
            print("  changed after Lost Cities, by zone:", dict(row["changes_by_zone"].most_common()))
            print("  by cause:", dict(row["changes_by_cause"].most_common(12)))
            print("  by kind:", dict(row["changes_by_kind"].most_common(15)))
    if args.json:
        args.json.write_text(json.dumps({k: {kk: (dict(vv) if isinstance(vv, Counter) else vv) for kk, vv in v.items()} for k, v in summary.items()}, indent=1) + "\n", encoding="utf-8")
    if args.out:
        for path in draw(report, args.out):
            print("wrote", path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
