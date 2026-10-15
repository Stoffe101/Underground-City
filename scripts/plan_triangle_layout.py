#!/usr/bin/env python3
"""Lay out Ironhaven, Valewood and Blackwater as a close triangle with three direct corridors.

Why: the first prototype put the cities on a line (Blackwater - Ironhaven - Valewood), so
Valewood to Blackwater was about 3,000 blocks (12 minutes on foot) and had no road. D-048 and
the October 16 brief want three practical bridges, 3-5 minutes between city outskirts and
6-8 minutes terminal to terminal. This tool derives city centres and the three corridors from
the radii and the *edge gaps* (the length of the road between two city circles), so the layout
is data, not hand-placed coordinates.

Run ``python scripts/plan_triangle_layout.py --write`` to rewrite the blueprints, or without
``--write`` to print the plan. ``--check`` fails when the committed blueprints differ.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CITIES = ROOT / "content/region-blueprints/cities.json"
CORRIDORS = ROOT / "content/region-blueprints/corridors.json"
WALK_SPEED = 4.317  # blocks per second, the same figure the validator uses

# Edge gap = road length between the two city circles. 3-5 minutes on foot is 780-1,300 blocks.
EDGE_GAPS = {("ironhaven", "valewood"): 1000, ("ironhaven", "blackwater"): 1100, ("valewood", "blackwater"): 1000}
# Gate house position, measured in blocks inward from each city circle along the corridor.
GATE_OFFSET = 70
INTERIOR_WAYPOINTS = 3
ROUTE_KINDS = {
    ("ironhaven", "valewood"): ("ironhaven_valewood", "guarded_highway", "patrolled"),
    ("ironhaven", "blackwater"): ("ironhaven_blackwater", "militarized_highway", "contested"),
    ("valewood", "blackwater"): ("valewood_blackwater", "frontier_highway", "unpredictable"),
}
GATE_RISK = {
    ("ironhaven", "valewood"): ("inspection", "inspection"),
    ("ironhaven", "blackwater"): ("high", "high"),
    ("valewood", "blackwater"): ("inspection", "low"),
}
CROSSING_NAMES = {
    "ironhaven_valewood": "east_causeway",
    "ironhaven_blackwater": "south_viaduct",
    "valewood_blackwater": "harbour_span",
}


def centre_of(city: dict) -> tuple[float, float]:
    return city["center_chunk"][0] * 16 + 8, city["center_chunk"][1] * 16 + 8


def plan_centres(radii: dict[str, int]) -> dict[str, tuple[int, int]]:
    """Ironhaven at the origin chunk, Valewood to the east, Blackwater to the south-east."""
    d_iv = EDGE_GAPS[("ironhaven", "valewood")] + radii["ironhaven"] + radii["valewood"]
    d_ib = EDGE_GAPS[("ironhaven", "blackwater")] + radii["ironhaven"] + radii["blackwater"]
    d_vb = EDGE_GAPS[("valewood", "blackwater")] + radii["valewood"] + radii["blackwater"]
    cos_gamma = (d_iv ** 2 + d_ib ** 2 - d_vb ** 2) / (2 * d_iv * d_ib)
    gamma = math.acos(cos_gamma)
    iron = (8.0, 8.0)
    # Rotate the triangle a little so no corridor is exactly axis-aligned (nicer coastlines, fewer ties).
    tilt = math.radians(8)
    valewood = (iron[0] + d_iv * math.cos(tilt), iron[1] + d_iv * math.sin(tilt))
    blackwater = (iron[0] + d_ib * math.cos(tilt + gamma), iron[1] + d_ib * math.sin(tilt + gamma))
    snap = lambda p: (round((p[0] - 8) / 16), round((p[1] - 8) / 16))
    return {"ironhaven": (0, 0), "valewood": snap(valewood), "blackwater": snap(blackwater)}


def unit(a: tuple[float, float], b: tuple[float, float]) -> tuple[float, float, float]:
    dx, dz = b[0] - a[0], b[1] - a[1]
    distance = math.hypot(dx, dz)
    return dx / distance, dz / distance, distance


def corridor(a: dict, b: dict) -> dict:
    ca, cb = centre_of(a), centre_of(b)
    ux, uz, distance = unit(ca, cb)
    # The first and last waypoints sit just inside the circles (the validator requires that);
    # the road builder itself stops at each circle where Lost Cities owns the streets.
    start_r, end_r = a["radius_blocks"] - 12, b["radius_blocks"] - 12
    start = (ca[0] + ux * start_r, ca[1] + uz * start_r)
    end = (cb[0] - ux * end_r, cb[1] - uz * end_r)
    points = [start]
    for index in range(1, INTERIOR_WAYPOINTS + 1):
        t = index / (INTERIOR_WAYPOINTS + 1)
        points.append((start[0] + (end[0] - start[0]) * t, start[1] + (end[1] - start[1]) * t))
    points.append(end)
    rounded = [[round(x), round(z)] for x, z in points]
    gate_a = (ca[0] + ux * (a["radius_blocks"] + GATE_OFFSET), ca[1] + uz * (a["radius_blocks"] + GATE_OFFSET))
    gate_b = (cb[0] - ux * (b["radius_blocks"] + GATE_OFFSET), cb[1] - uz * (b["radius_blocks"] + GATE_OFFSET))
    return {"waypoints": rounded, "gates": [[round(gate_a[0]), round(gate_a[1])], [round(gate_b[0]), round(gate_b[1])]],
            "edge_gap": round(distance - a["radius_blocks"] - b["radius_blocks"])}


def build() -> tuple[dict, dict, list[str]]:
    cities = json.loads(CITIES.read_text(encoding="utf-8"))
    corridors = json.loads(CORRIDORS.read_text(encoding="utf-8"))
    by_id = {c["id"]: c for c in cities["cities"]}
    centres = plan_centres({k: v["radius_blocks"] for k, v in by_id.items()})
    for key, chunk in centres.items():
        by_id[key]["center_chunk"] = list(chunk)
    cities["minimum_city_edge_gap_blocks"] = 800
    old_routes = {r["id"]: r for r in corridors["routes"]}
    routes, lines = [], []
    for (first, second), (route_id, kind, security) in ROUTE_KINDS.items():
        built = corridor(by_id[first], by_id[second])
        previous = old_routes.get(route_id, {})
        risk_a, risk_b = GATE_RISK[(first, second)]
        route = {
            "id": route_id, "from": first, "to": second, "kind": kind, "waypoints": built["waypoints"],
            "crossings": [{"id": CROSSING_NAMES[route_id], "kind": "proposed_bridge",
                           "between_waypoints": [1, 2], "requires_water_or_ravine_survey": True}],
            "checkpoints": [
                {"id": f"{first}_{second}_gate_{first}", "position": built["gates"][0], "faction": first, "risk": risk_a},
                {"id": f"{first}_{second}_gate_{second}", "position": built["gates"][1], "faction": second, "risk": risk_b},
            ],
            "travel_modes": ["walking", "vehicle", "boat", "licensed_convoy"],
            "security": security,
            "lore": previous.get("lore", ""),
        }
        routes.append(route)
        length = sum(math.dist(a, b) for a, b in zip(built["waypoints"], built["waypoints"][1:]))
        minutes = length / WALK_SPEED / 60
        lines.append(f"{route_id:22} {kind:20} edge gap {built['edge_gap']:5d} blocks, road {length:6.0f} blocks = {minutes:4.1f} min on foot")
    corridors["routes"] = routes
    generation = corridors["road_generation"]
    generation["build_route_kinds"] = ["guarded_highway", "militarized_highway", "frontier_highway"]
    generation["unpaved_route_kinds"] = []
    budget = corridors["transport"]["routine_travel_budget"]
    budget.pop("long_frontier_route_optional_until_viable_ferry_or_convoy", None)
    budget["all_three_routes_walkable_within_budget"] = True
    return cities, corridors, lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="rewrite cities.json and corridors.json")
    mode.add_argument("--check", action="store_true", help="fail if the committed blueprints are not this plan")
    args = parser.parse_args()
    cities, corridors, lines = build()
    centres = {c["id"]: centre_of(c) for c in cities["cities"]}
    for a, b in (("ironhaven", "valewood"), ("ironhaven", "blackwater"), ("valewood", "blackwater")):
        print(f"{a}-{b}: centre distance {math.dist(centres[a], centres[b]):.0f}")
    print("\n".join(lines))
    for city in cities["cities"]:
        x, z = centre_of(city)
        print(f"{city['id']:10} chunk {city['center_chunk']} centre ({x:.0f}, {z:.0f}) radius {city['radius_blocks']}")
    text_cities = json.dumps(cities, indent=2, ensure_ascii=False) + "\n"
    text_corridors = json.dumps(corridors, indent=2, ensure_ascii=False) + "\n"
    if args.write:
        CITIES.write_text(text_cities, encoding="utf-8")
        CORRIDORS.write_text(text_corridors, encoding="utf-8")
        print("Blueprints rewritten")
    elif args.check:
        if CITIES.read_text(encoding="utf-8") != text_cities or CORRIDORS.read_text(encoding="utf-8") != text_corridors:
            print("Blueprints differ from the planned layout; run with --write")
            return 1
        print("Blueprints match the planned layout")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
