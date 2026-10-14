#!/usr/bin/env python3
"""Render original *schematic* city corridors to SVG (not a Minecraft world map).

Derived from blueprint block X/Z coordinates. No third-party images/assets.
The symbol positions do not claim actual water, terrain Y, roads or bridges.
"""
import argparse
import html
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COLORS = {
    "ironhaven": "#73a5bc",
    "valewood": "#73bf99",
    "blackwater": "#b08bd0",
}
SECURITY = {
    "guarded_highway": "#69bea8",
    "militarized_highway": "#ed9b69",
    "long_perimeter_route": "#b48bc9",
}


def build_svg(world: dict, corridors: dict) -> str:
    width, height = 1024, 760
    all_points = [[v * 16 for v in city["center_chunk"]]
                  for city in world["cities"]]
    all_points += [pt for path in corridors["routes"] for pt in path["waypoints"]]
    min_x, max_x = min(v[0] for v in all_points), max(v[0] for v in all_points)
    min_z, max_z = min(v[1] for v in all_points), max(v[1] for v in all_points)
    min_x -= 780
    max_x += 780
    min_z -= 600
    max_z += 580
    scale = min((width-70)/(max_x-min_x), (height-100)/(max_z-min_z))

    def xy(coord):
        return ((coord[0]-min_x)*scale+30, (coord[1]-min_z)*scale+70)

    nodes = [
        '<svg xmlns="http://www.w3.org/2000/svg" role="img" viewBox="0 0 1024 760">',
        '<title>Underground City: provisional road network, not in-game terrain</title>',
        '<rect width="1024" height="760" fill="#101b28"/>',
        '<g font-family="sans-serif" fill="#e6eef7">',
        '<text x="36" y="30" font-size="22" font-weight="bold">Underground City · corridor concept</text>',
        '<text x="36" y="50" font-size="12" fill="#acb8c7">World X/Z coordinates only. Bridge spans are PROPOSED and need terrain confirmation.</text>',
    ]
    # Draw roads first, so city radius rings/labels are readable.
    for route in corridors["routes"]:
        color = SECURITY.get(route["kind"], "#dddddd")
        points = " ".join(f"{xy(pt)[0]:.1f},{xy(pt)[1]:.1f}" for pt in route["waypoints"])
        nodes.append(f'<polyline points="{points}" stroke="#070e18" stroke-width="10" fill="none" stroke-linejoin="round" stroke-linecap="round"/>')
        nodes.append(f'<polyline points="{points}" stroke="{color}" stroke-width="5" fill="none" stroke-dasharray="8 4" stroke-linejoin="round" stroke-linecap="round"/>')
        for crossing in route["crossings"]:
            a, b = crossing["between_waypoints"]
            pt_a, pt_b = xy(route["waypoints"][a]), xy(route["waypoints"][b])
            mid_x = (pt_a[0]+pt_b[0])/2
            mid_y = (pt_a[1]+pt_b[1])/2
            nodes.append(f'<rect x="{mid_x-6:.1f}" y="{mid_y-6:.1f}" width="12" height="12" fill="#ffd28a" stroke="#192537" transform="rotate(45 {mid_x:.1f} {mid_y:.1f})"/>')
        for checkpoint in route["checkpoints"]:
            x, y = xy(checkpoint["position"])
            nodes.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="4" fill="#e9b76c" stroke="#171d2a" stroke-width="1"/>')
    for city in world["cities"]:
        x, y = xy([v * 16 for v in city["center_chunk"]])
        radius = city["radius_blocks"]*scale
        color = COLORS.get(city["id"], "#cccccc")
        nodes.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{radius:.1f}" fill="{color}" fill-opacity=".15" stroke="{color}" stroke-opacity=".8" stroke-width="2"/>')
        nodes.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="6" fill="{color}" stroke="#eef2f7" stroke-width="2"/>')
        label = html.escape(city["id"].title())
        nodes.append(f'<text x="{x+11:.1f}" y="{y+5:.1f}" font-size="16" font-weight="bold">{label}</text>')
    nodes.append('<text x="36" y="708" font-size="13">Dashed lines = planned corridors · Orange dots = checkpoints · Gold diamonds = candidate bridge spans</text>')
    nodes.append('<text x="36" y="733" font-size="12" fill="#aeb7c3">Generated structures: not verified. Roads built: 0. No water/terrain heights surveyed.</text>')
    nodes += ['</g>', '</svg>']
    return "\n".join(nodes) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    world = json.loads((ROOT/"content/region-blueprints/cities.json").read_text(encoding="utf-8"))
    corridors = json.loads((ROOT/"content/region-blueprints/corridors.json").read_text(encoding="utf-8"))
    from validate_intercity import load, validate
    issues = validate(load())
    if issues:
        parser.exit(2, "Invalid corridor plan: " + "; ".join(issues))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(build_svg(world, corridors), encoding="utf-8")
    print("Wrote conceptual route diagram:", args.output)


if __name__ == "__main__":
    main()
