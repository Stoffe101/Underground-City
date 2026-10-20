#!/usr/bin/env python3
"""Draw a terrain survey CSV (``scripts/run_terrain_map.py``) as a biome map with hillshading, and print what it measured.

The table answers the owner's three complaints directly: how much of each island is snow or ice, how the land is divided into
biomes, and how far apart the cities really are across land and water. The picture overlays the city circles and the planned roads of
the chosen layout. Needs Pillow only.

    python scripts/render_terrain_map.py build/terrain/arch-a.csv --layout archipelago --png docs/images/world/arch-a.png
"""
from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

PALETTE = {
    "ocean": (28, 74, 140), "deep_ocean": (14, 40, 96), "cold_ocean": (40, 90, 150), "deep_cold_ocean": (20, 52, 110), "lukewarm_ocean": (40, 120, 170),
    "deep_lukewarm_ocean": (24, 80, 140), "warm_ocean": (50, 150, 190), "frozen_ocean": (150, 190, 230), "deep_frozen_ocean": (120, 160, 210),
    "river": (60, 120, 200), "frozen_river": (170, 210, 240), "beach": (226, 214, 150), "snowy_beach": (235, 240, 245), "stony_shore": (130, 130, 130),
    "plains": (136, 190, 80), "sunflower_plains": (170, 210, 70), "snowy_plains": (240, 246, 250), "ice_spikes": (190, 230, 255), "meadow": (120, 200, 110),
    "forest": (52, 120, 52), "flower_forest": (190, 120, 190), "birch_forest": (110, 160, 80), "old_growth_birch_forest": (90, 150, 80), "dark_forest": (30, 70, 36),
    "taiga": (50, 110, 100), "snowy_taiga": (200, 225, 225), "old_growth_pine_taiga": (60, 100, 70), "old_growth_spruce_taiga": (60, 100, 70),
    "swamp": (100, 120, 60), "mangrove_swamp": (70, 110, 60), "savanna": (190, 180, 90), "savanna_plateau": (180, 170, 80), "windswept_savanna": (170, 160, 90),
    "desert": (230, 205, 120), "badlands": (200, 110, 60), "jungle": (30, 140, 40), "sparse_jungle": (60, 150, 60), "bamboo_jungle": (50, 160, 80),
    "windswept_hills": (120, 130, 120), "windswept_gravelly_hills": (140, 140, 140), "windswept_forest": (80, 120, 90),
    "grove": (200, 225, 215), "snowy_slopes": (225, 235, 245), "frozen_peaks": (250, 252, 255), "jagged_peaks": (235, 240, 245), "stony_peaks": (150, 150, 150),
    "cherry_grove": (240, 170, 200), "lush_caves": (60, 200, 90), "dripstone_caves": (140, 110, 80),
}


SNOW_BIOMES = {"snowy_plains", "ice_spikes", "snowy_taiga", "snowy_beach", "snowy_slopes", "grove", "frozen_peaks", "jagged_peaks", "frozen_river", "frozen_ocean", "deep_frozen_ocean"}


def is_snow(biome: str) -> bool:
    return biome.split(":")[-1] in SNOW_BIOMES


def is_ocean(biome: str) -> bool:
    return "ocean" in biome


def load(path: Path):
    meta, rows = "", []
    with path.open(encoding="utf-8") as handle:
        first = handle.readline()
        if first.startswith("#"):
            meta = first.strip()
        else:
            handle.seek(0)
        for row in csv.DictReader(handle):
            rows.append((int(row["x"]), int(row["z"]), int(row["floor"]), int(row["surface"]), row["biome"]))
    return meta, rows


def layout_data(layout: str):
    folder = ROOT / "content/region-blueprints" / ("archipelago" if layout == "archipelago" else "")
    cities = json.loads((folder / "cities.json").read_text(encoding="utf-8"))
    corridors = json.loads((folder / "corridors.json").read_text(encoding="utf-8"))
    circles = {c["id"]: (c["center_chunk"][0] * 16 + 8, c["center_chunk"][1] * 16 + 8, c["radius_blocks"]) for c in cities["cities"]}
    return circles, corridors["routes"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path)
    parser.add_argument("--layout", choices=["legacy", "archipelago"], default="archipelago")
    parser.add_argument("--png", type=Path)
    parser.add_argument("--scale", type=int, default=2, help="pixels per sample")
    args = parser.parse_args()
    meta, rows = load(args.csv)
    circles, routes = layout_data(args.layout)
    xs = sorted({r[0] for r in rows}); zs = sorted({r[1] for r in rows})
    step = xs[1] - xs[0]
    grid = {(r[0], r[1]): r for r in rows}

    print(meta or args.csv.name)
    total = len(rows)
    sea_level = 63
    water = sum(1 for r in rows if r[3] > r[2] or is_ocean(r[4]))
    print(f"{total} columns, {100 * water / total:.0f}% water")
    # per-region report: the circle of each city plus its countryside ring (the island), by nearest city
    regions: dict[str, list] = defaultdict(list)
    for x, z, floor, surface, biome in rows:
        best, best_d = None, 1e9
        for city, (cx, cz, radius) in circles.items():
            d = math.hypot(x - cx, z - cz) - radius
            if d < best_d:
                best, best_d = city, d
        if best_d < 260 and not is_ocean(biome):
            regions[best].append((x, z, floor, surface, biome, best_d))
    for city, cells in regions.items():
        count = len(cells)
        snow = sum(1 for c in cells if is_snow(c[4]))
        hist = Counter(c[4].split(":")[-1] for c in cells)
        inside = [c for c in cells if c[5] <= 0]
        heights = sorted(c[3] for c in inside)
        median = heights[len(heights) // 2] if heights else None
        spread = f"{heights[len(heights) // 20]}..{heights[-len(heights) // 20 - 1]}" if len(heights) > 20 else "-"
        print(f"\n{city}: {count} land columns within 260 blocks of its circle, {100 * snow / max(count, 1):.1f}% snowy or frozen biomes")
        print(f"   surface height inside the circle: median {median}, 5th-95th percentile {spread}  (Lost Cities' ground level is 71)")
        print("   biomes: " + ", ".join(f"{name} {100 * n / count:.0f}%" for name, n in hist.most_common(7)))
    overall = Counter(r[4].split(":")[-1] for r in rows)
    land = [r for r in rows if not is_ocean(r[4])]
    print(f"\nland biomes overall: " + ", ".join(f"{name} {100 * n / max(len(land), 1):.0f}%" for name, n in Counter(r[4].split(':')[-1] for r in land).most_common(9)))
    print(f"snowy/frozen share of all land: {100 * sum(1 for r in land if is_snow(r[4])) / max(len(land), 1):.1f}%")

    # along every planned road: land, water and the longest run of water (what a bridge has to span)
    print()
    for route in routes:
        pts = route["waypoints"]
        run, longest, water_cols, total_cols, deepest = 0, 0, 0, 0, 0
        for (x0, z0), (x1, z1) in zip(pts, pts[1:]):
            length = math.hypot(x1 - x0, z1 - z0)
            samples = int(length // step)
            for i in range(samples + 1):
                t = i / max(samples, 1)
                gx = xs[0] + round((x0 + (x1 - x0) * t - xs[0]) / step) * step; gz = zs[0] + round((z0 + (z1 - z0) * t - zs[0]) / step) * step
                cell = grid.get((gx, gz))
                if cell is None:
                    continue
                total_cols += 1
                wet = cell[3] > cell[2] or cell[2] < sea_level
                if wet:
                    water_cols += 1; run += step; longest = max(longest, run); deepest = max(deepest, sea_level - cell[2])
                else:
                    run = 0
        print(f"road {route['id']:22} {total_cols * step:5d} blocks sampled, {100 * water_cols / max(total_cols, 1):3.0f}% over water, longest water run {longest:4d} blocks, deepest floor {deepest} below sea level")

    if args.png:
        from PIL import Image, ImageDraw
        scale = args.scale
        width, height = len(xs) * scale, len(zs) * scale
        image = Image.new("RGB", (width, height))
        pixels = image.load()
        for iz, z in enumerate(zs):
            for ix, x in enumerate(xs):
                _, _, floor, surface, biome = grid[(x, z)]
                name = biome.split(":")[-1]
                colour = PALETTE.get(name, (255, 0, 255))
                if is_ocean(name) or surface > floor:
                    depth = max(0, sea_level - floor)
                    k = max(0.45, 1.0 - depth / 90.0)
                    colour = tuple(int(c * k) for c in colour)
                else:
                    west = grid.get((x - step, z)); north = grid.get((x, z - step))
                    shade = 0.0
                    if west and north:
                        shade = (surface - west[3]) * 0.07 + (surface - north[3]) * 0.07
                    k = max(0.55, min(1.35, 1.0 + shade))
                    colour = tuple(min(255, int(c * k)) for c in colour)
                for dx in range(scale):
                    for dz in range(scale):
                        pixels[ix * scale + dx, iz * scale + dz] = colour
        draw = ImageDraw.Draw(image)
        px = lambda x, z: ((x - xs[0]) / step * scale, (z - zs[0]) / step * scale)
        for city, (cx, cz, radius) in circles.items():
            x, z = px(cx, cz); r = radius / step * scale
            draw.ellipse((x - r, z - r, x + r, z + r), outline=(255, 255, 255), width=2)
            draw.text((x - 20, z - 6), city, fill=(255, 255, 255))
        for route in routes:
            draw.line([px(*p) for p in route["waypoints"]], fill=(255, 200, 40), width=2)
        args.png.parent.mkdir(parents=True, exist_ok=True)
        image.save(args.png)
        print(f"\npicture: {args.png}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
