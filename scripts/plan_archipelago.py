#!/usr/bin/env python3
"""Lay out Ironhaven, Valewood and Blackwater as three separate islands joined by three bridges.

Why (the first human playtest, October 20, 2026): on seed 5908 the cities read as one continuous built-up area because
Lost Cities buildings filled 600, 410 and 570 block circles and the land between them was ordinary vanilla terrain, mostly
snow-dusted taiga. The owner's hand-drawn reference is three distinct landmasses with one road-and-bridge connection
between each pair, each with its own character. This planner derives the *geometry* of that world from a few stated
numbers (city radii, the countryside around each city, the water between the islands) and writes:

* ``content/region-blueprints/archipelago/cities.json``     city circles in the same schema as the legacy layout
* ``content/region-blueprints/archipelago/corridors.json``  three routes, each ending at a cape on its island and
                                                            crossing the strait by a bridge
* ``content/region-blueprints/archipelago/islands.json``    the shapes the terrain generator turns into land, the flat
                                                            plateaus under the cities, the climate of each island and the
                                                            arrival site

Nothing here is a coordinate in game logic: Java reads the generated data only. ``--check`` fails when the committed files
differ, so the layout cannot drift from its numbers. The legacy seed-5908 layout is untouched (``plan_triangle_layout.py``).

Run ``python scripts/plan_archipelago.py --write`` to rewrite the blueprints, ``--check`` in CI, ``--preview out.png`` for a
silhouette picture (a picture of the *plan*; the real terrain is photographed from the game, see docs/technical/archipelago-world.md).
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "content/region-blueprints/archipelago"
WALK_SPEED = 4.317  # blocks per second, the figure every other validator uses

LAYOUT_ID = "archipelago"
GROUND_Y = 71           # Lost Cities' default ground level; the streets of every city sit here
SEA_LEVEL = 63

# --- the numbers that define the world --------------------------------------------------------------------------
# radius: the Lost Cities circle (buildings and streets); countryside: flat-ish land kept between that circle and the shore
CITIES = {
    "ironhaven":  dict(radius=330, countryside=230, style="underground_city:ironhaven", building_chance=0.78, max_floors=9, park_chance=0.05,
                       economy=["manufacturing", "security", "metallurgy"],
                       visual_goal="Industrial island: a dense grey skyline of factories, warehouses and working-class blocks behind a long quay, with plain temperate countryside and the arrival landing on its western shore.",
                       temperature=0.02, humidity=-0.10),
    "valewood":   dict(radius=240, countryside=330, style="underground_city:valewood", building_chance=0.44, max_floors=3, park_chance=0.26,
                       economy=["agriculture", "wine", "trade"],
                       visual_goal="Green island: a low-rise market town in rolling farmland, vineyards, meadows and woods, the warmest and wettest of the three.",
                       temperature=0.07, humidity=0.05),
    "blackwater": dict(radius=300, countryside=200, style="underground_city:blackwater", building_chance=0.84, max_floors=11, park_chance=0.03,
                       economy=["commerce", "technology", "underground_trade"],
                       visual_goal="Port island: a tall glassy commercial core around a harbour bay, marinas and a dark swampy shore; technology and the underground trade.",
                       temperature=0.05, humidity=0.46),
}
# water between the two cape tips of each pair, in blocks (a bridge's longest open span is a little longer than this)
STRAITS = {("ironhaven", "valewood"): 300, ("ironhaven", "blackwater"): 340, ("valewood", "blackwater"): 310}
CAPE_REACH = 95          # how far a cape runs beyond the island's own shore, towards its neighbour
CAPE_WIDTH = 64          # radius of a cape (the road, its verges and a little land either side)
CAPE_TIP_RADIUS = 70
TILT_DEGREES = 8
GATE_OFFSET = 70         # a checkpoint gate stands this far beyond the city circle along its road
INTERIOR_WAYPOINTS = 3
PLATEAU_PAD = 40         # the flat ground reaches this far beyond the circle, then eases back to the vanilla shape
PLATEAU_EASE = 140
SHORE_NOISE = {"amplitude": 38.0, "scale": 210.0, "seed": 5908}  # a fixed seed: every new world gets the same three islands
OPEN_SEA = -0.9          # continentalness far from every island (deep ocean)
STRAIT_SEA = -0.38       # continentalness in a strait (a shallow ocean, so piers stay short)
ARRIVAL = {"island": "ironhaven", "bearing_degrees": 212, "beyond_city_blocks": 105, "plateau_radius": 95}
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
CROSSING_NAMES = {"ironhaven_valewood": "east_causeway", "ironhaven_blackwater": "south_viaduct", "valewood_blackwater": "harbour_span"}
LORE = {
    "ironhaven_valewood": "Ironhaven manufactured goods reach Valewood's crop-and-vineyard exporters across the East Causeway.",
    "ironhaven_blackwater": "The South Viaduct is Ironhaven's lifeline to the port; everyone on it is watched.",
    "valewood_blackwater": "The Harbour Span carries Valewood's wine to Blackwater's markets, and sometimes other things.",
}
PAIRS = [("ironhaven", "valewood"), ("ironhaven", "blackwater"), ("valewood", "blackwater")]


def shore_radius(city: str) -> float:
    return CITIES[city]["radius"] + CITIES[city]["countryside"]


def pair_distance(a: str, b: str) -> float:
    """Centre to centre: both shores, both capes and the water between the cape tips."""
    return shore_radius(a) + CAPE_REACH + STRAITS[(a, b)] + CAPE_REACH + shore_radius(b)


def plan_centres() -> dict[str, tuple[float, float]]:
    """Ironhaven at the origin chunk, Valewood to the east, Blackwater to the south-east (the triangle is solved from its three sides)."""
    d_iv, d_ib, d_vb = (pair_distance(*pair) for pair in PAIRS)
    cos_gamma = (d_iv ** 2 + d_ib ** 2 - d_vb ** 2) / (2 * d_iv * d_ib)
    gamma = math.acos(cos_gamma)
    tilt = math.radians(TILT_DEGREES)
    iron = (8.0, 8.0)
    valewood = (iron[0] + d_iv * math.cos(tilt), iron[1] + d_iv * math.sin(tilt))
    blackwater = (iron[0] + d_ib * math.cos(tilt + gamma), iron[1] + d_ib * math.sin(tilt + gamma))
    snap = lambda p: (round((p[0] - 8) / 16) * 16 + 8.0, round((p[1] - 8) / 16) * 16 + 8.0)
    return {"ironhaven": snap(iron), "valewood": snap(valewood), "blackwater": snap(blackwater)}


def unit(a, b):
    dx, dz = b[0] - a[0], b[1] - a[1]
    distance = math.hypot(dx, dz)
    return dx / distance, dz / distance, distance


def chunk_of(point) -> list[int]:
    return [round((point[0] - 8) / 16), round((point[1] - 8) / 16)]


def island_shapes(centres) -> list[dict]:
    """The land: one disc per island, one capsule per cape, and a bay carved out of Blackwater (its harbour)."""
    shapes = []
    for city, centre in centres.items():
        shapes.append({"island": city, "op": "add", "type": "disc", "center": [round(centre[0]), round(centre[1])], "radius": round(shore_radius(city))})
    for a, b in PAIRS:
        ux, uz, _ = unit(centres[a], centres[b])
        for here, there, sign in ((a, b, 1), (b, a, -1)):
            dx, dz = ux * sign, uz * sign
            start = shore_radius(here) - 90
            end = shore_radius(here) + CAPE_REACH - CAPE_TIP_RADIUS * 0.55
            cx, cz = centres[here]
            shapes.append({"island": here, "op": "add", "type": "capsule", "toward": there,
                           "from": [round(cx + dx * start), round(cz + dz * start)],
                           "to": [round(cx + dx * end), round(cz + dz * end)], "radius": CAPE_TIP_RADIUS if end > shore_radius(here) else CAPE_WIDTH})
    # Blackwater's harbour: a round bay notched into the shore on the side that faces away from both neighbours; its far edge stops well short of the city circle.
    bx, bz = centres["blackwater"]
    away = [centres["blackwater"][i] - (centres["ironhaven"][i] + centres["valewood"][i]) / 2 for i in range(2)]
    norm = math.hypot(*away)
    away = (away[0] / norm, away[1] / norm)
    bay_centre = (bx + away[0] * shore_radius("blackwater"), bz + away[1] * shore_radius("blackwater"))
    shapes.append({"island": "blackwater", "op": "carve", "type": "disc", "center": [round(bay_centre[0]), round(bay_centre[1])], "radius": 130})
    return shapes


def plateaus(centres) -> list[dict]:
    """Flat ground at Lost Cities' ground level under every city, and under the arrival site."""
    out = []
    for city, centre in centres.items():
        out.append({"id": f"{city}_city", "center": [round(centre[0]), round(centre[1])], "radius": CITIES[city]["radius"] + PLATEAU_PAD,
                    "ease": PLATEAU_EASE, "ground_y": GROUND_Y})
    arrival = arrival_site(centres)
    out.append({"id": "arrival_landing", "center": arrival["anchor"], "radius": ARRIVAL["plateau_radius"], "ease": 90, "ground_y": GROUND_Y})
    return out


def arrival_site(centres) -> dict:
    cx, cz = centres[ARRIVAL["island"]]
    angle = math.radians(ARRIVAL["bearing_degrees"])
    distance = CITIES[ARRIVAL["island"]]["radius"] + ARRIVAL["beyond_city_blocks"]
    return {"island": ARRIVAL["island"], "anchor": [round(cx + math.cos(angle) * distance), round(cz + math.sin(angle) * distance)],
            "bearing_degrees": ARRIVAL["bearing_degrees"], "plateau_radius": ARRIVAL["plateau_radius"], "ground_y": GROUND_Y}


def climate(centres) -> list[dict]:
    return [{"island": city, "center": [round(centre[0]), round(centre[1])], "radius": round(shore_radius(city) + CAPE_REACH),
             "temperature": CITIES[city]["temperature"], "humidity": CITIES[city]["humidity"]} for city, centre in centres.items()]


def corridor(a_id: str, b_id: str, centres) -> dict:
    ca, cb = centres[a_id], centres[b_id]
    ux, uz, distance = unit(ca, cb)
    ra, rb = CITIES[a_id]["radius"], CITIES[b_id]["radius"]
    start = (ca[0] + ux * (ra - 12), ca[1] + uz * (ra - 12))
    end = (cb[0] - ux * (rb - 12), cb[1] - uz * (rb - 12))
    points = [start] + [(start[0] + (end[0] - start[0]) * i / (INTERIOR_WAYPOINTS + 1), start[1] + (end[1] - start[1]) * i / (INTERIOR_WAYPOINTS + 1))
                        for i in range(1, INTERIOR_WAYPOINTS + 1)] + [end]
    gate_a = (ca[0] + ux * (ra + GATE_OFFSET), ca[1] + uz * (ra + GATE_OFFSET))
    gate_b = (cb[0] - ux * (rb + GATE_OFFSET), cb[1] - uz * (rb + GATE_OFFSET))
    return {"waypoints": [[round(x), round(z)] for x, z in points], "gates": [[round(gate_a[0]), round(gate_a[1])], [round(gate_b[0]), round(gate_b[1])]],
            "edge_gap": round(distance - ra - rb)}


def build() -> tuple[dict, dict, dict, list[str]]:
    centres = plan_centres()
    cities = {"schema_version": 1, "prototype_only": True, "layout": LAYOUT_ID, "dimension": "minecraft:overworld", "minimum_city_edge_gap_blocks": 800,
              "cities": [], "profile_requirements": {"random_city_chance": 0.0, "city_max_radius_at_least": 330, "disable_ruins": True,
                                                    "disable_spawners": True, "generate_loot": True}}
    for city_id, spec in CITIES.items():
        cities["cities"].append({"id": city_id, "center_chunk": chunk_of(centres[city_id]), "radius_blocks": spec["radius"], "city_style": spec["style"],
                                 "building_chance": spec["building_chance"], "max_floors": spec["max_floors"], "park_chance": spec["park_chance"],
                                 "economy": spec["economy"], "visual_goal": spec["visual_goal"]})
    routes, lines = [], []
    for (first, second), (route_id, kind, security) in ROUTE_KINDS.items():
        built = corridor(first, second, centres)
        risk_a, risk_b = GATE_RISK[(first, second)]
        routes.append({
            "id": route_id, "from": first, "to": second, "kind": kind, "waypoints": built["waypoints"],
            "crossings": [{"id": CROSSING_NAMES[route_id], "kind": "proposed_bridge", "between_waypoints": [1, 2], "requires_water_or_ravine_survey": True}],
            "checkpoints": [{"id": f"{first}_{second}_gate_{first}", "position": built["gates"][0], "faction": first, "risk": risk_a},
                            {"id": f"{first}_{second}_gate_{second}", "position": built["gates"][1], "faction": second, "risk": risk_b}],
            "travel_modes": ["walking", "vehicle", "boat", "licensed_convoy"], "security": security, "lore": LORE[route_id]})
        length = sum(math.dist(a, b) for a, b in zip(built["waypoints"], built["waypoints"][1:]))
        lines.append(f"{route_id:22} {kind:20} edge gap {built['edge_gap']:5d} blocks, road {length:6.0f} blocks = {length / WALK_SPEED / 60:4.1f} min on foot, "
                     f"centre to centre {pair_distance(first, second) / WALK_SPEED / 60:4.1f} min")
    corridors = json.loads((ROOT / "content/region-blueprints/corridors.json").read_text(encoding="utf-8"))
    corridors["routes"] = routes
    corridors["terrain_verified"] = False
    corridors["layout"] = LAYOUT_ID
    generation = corridors["road_generation"]
    generation["reference_seed"] = SHORE_NOISE["seed"]
    generation["water_policy"] = ("the islands are separated by straits; every road ends at a cape on each island and crosses its strait by a bridge whose deck stays four free "
                                  "blocks above the water so boats can pass beneath; a boat is the alternative to a bridge")
    shapes, plateau_list, climate_list = island_shapes(centres), plateaus(centres), climate(centres)
    islands = {
        "schema_version": 1, "layout": LAYOUT_ID, "sea_level": SEA_LEVEL, "ground_y": GROUND_Y,
        # exactly what the density functions of the terrain carry (IslandPlan.CODEC): the generated worldgen JSON embeds this object
        "plan": {"shore": SHORE_NOISE, "open_sea": OPEN_SEA, "strait_sea": STRAIT_SEA, "shapes": shapes, "plateaus": plateau_list, "climate": climate_list},
        "arrival": arrival_site(centres),
        "cities": {c: {"center": [round(centres[c][0]), round(centres[c][1])], "radius": CITIES[c]["radius"], "shore_radius": round(shore_radius(c))} for c in CITIES},
    }
    return cities, corridors, islands, lines


CORE_WORLDGEN = ROOT / "rpg-core/src/main/resources/data/underground_city/worldgen/density_function/regional"
def _shifted(noise: str) -> dict:
    return {"type": "minecraft:shifted_noise", "noise": noise, "shift_x": "minecraft:shift_x", "shift_y": 0.0, "shift_z": "minecraft:shift_z", "xz_scale": 0.25, "y_scale": 0.0}


# channel -> the vanilla function it modifies, referenced by name (the game's own data, never copied into this repository)
CHANNEL_INPUT = {"continents": "minecraft:overworld/continents", "erosion": "minecraft:overworld/erosion", "ridges": "minecraft:overworld/ridges",
                 "temperature": _shifted("minecraft:temperature"), "humidity": _shifted("minecraft:vegetation")}


def density_functions(islands: dict) -> dict[str, dict]:
    """The four original terrain channels of the archipelago, each embedding the island plan (generated; edit the planner, not these)."""
    out = {}
    for channel, source in CHANNEL_INPUT.items():
        node = {"type": "underground_city:island", "channel": channel, "plan": islands["plan"], "input": source}
        out[f"{channel}.json"] = {"type": "minecraft:flat_cache", "argument": node}
    return out


def render() -> dict[str, str]:
    cities, corridors, islands, _ = build()
    dump = lambda obj: json.dumps(obj, indent=2, ensure_ascii=False) + "\n"
    return {"cities.json": dump(cities), "corridors.json": dump(corridors), "islands.json": dump(islands)}


def render_core() -> dict[Path, str]:
    """The worldgen files the Core jar carries for every launch (inert unless a world uses the archipelago preset)."""
    _, _, islands, _ = build()
    compact = lambda obj: json.dumps(obj, indent=1, ensure_ascii=False) + "\n"
    return {CORE_WORLDGEN / name: compact(document) for name, document in density_functions(islands).items()}


def preview(path: Path) -> None:
    """A silhouette of the plan: land, plateaus, city circles, roads. Needs Pillow (the art pipeline already does)."""
    from PIL import Image, ImageDraw
    cities, corridors, islands, _ = build()
    scale = 0.5
    shapes = islands["plan"]["shapes"]
    xs = [s["center"][0] if "center" in s else s["from"][0] for s in shapes]
    zs = [s["center"][1] if "center" in s else s["from"][1] for s in shapes]
    x0, x1, z0, z1 = min(xs) - 900, max(xs) + 900, min(zs) - 900, max(zs) + 900
    width, height = int((x1 - x0) * scale), int((z1 - z0) * scale)
    image = Image.new("RGB", (width, height), (24, 58, 92))
    draw = ImageDraw.Draw(image)
    px = lambda p: ((p[0] - x0) * scale, (p[1] - z0) * scale)
    for op, colour in (("add", (96, 140, 72)), ("carve", (24, 58, 92))):
        for s in shapes:
            if s["op"] != op:
                continue
            if s["type"] == "disc":
                cx, cz = px(s["center"]); r = s["radius"] * scale
                draw.ellipse((cx - r, cz - r, cx + r, cz + r), fill=colour)
            else:
                steps = 12
                for i in range(steps + 1):
                    t = i / steps
                    cx, cz = px((s["from"][0] + (s["to"][0] - s["from"][0]) * t, s["from"][1] + (s["to"][1] - s["from"][1]) * t)); r = s["radius"] * scale
                    draw.ellipse((cx - r, cz - r, cx + r, cz + r), fill=colour)
    for city in cities["cities"]:
        c = islands["cities"][city["id"]]["center"]; cx, cz = px(c); r = city["radius_blocks"] * scale
        draw.ellipse((cx - r, cz - r, cx + r, cz + r), outline=(230, 230, 230), width=2)
    for route in corridors["routes"]:
        draw.line([px(p) for p in route["waypoints"]], fill=(240, 190, 60), width=3)
    ax, az = px(islands["arrival"]["anchor"]); draw.ellipse((ax - 5, az - 5, ax + 5, az + 5), fill=(255, 80, 80))
    image.save(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=Path)
    args = parser.parse_args()
    cities, corridors, islands, lines = build()
    centres = {c["id"]: islands["cities"][c["id"]]["center"] for c in cities["cities"]}
    for a, b in PAIRS:
        print(f"{a}-{b}: centre distance {math.dist(centres[a], centres[b]):.0f}")
    print("\n".join(lines))
    for city in cities["cities"]:
        print(f"{city['id']:10} chunk {city['center_chunk']} centre {centres[city['id']]} city radius {city['radius_blocks']} shore radius {islands['cities'][city['id']]['shore_radius']}")
    print(f"arrival site {islands['arrival']['anchor']} on {islands['arrival']['island']}")
    files = render()
    if args.preview:
        preview(args.preview)
        print(f"preview written to {args.preview}")
    if args.write:
        OUT.mkdir(parents=True, exist_ok=True)
        for name, text in files.items():
            (OUT / name).write_text(text, encoding="utf-8")
        print("Archipelago blueprints rewritten")
    core = render_core()
    if args.write:
        for path, text in core.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
    elif args.check:
        stale = [name for name, text in files.items() if not (OUT / name).is_file() or (OUT / name).read_text(encoding="utf-8") != text]
        stale += [str(path.relative_to(ROOT)) for path, text in core.items() if not path.is_file() or path.read_text(encoding="utf-8") != text]
        if stale:
            print("Archipelago blueprints differ from the plan; run with --write: " + ", ".join(stale))
            return 1
        print("Archipelago blueprints match the plan")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
