#!/usr/bin/env python3
"""Create original prototype Lost Cities low-code mod with EXACTLY three city anchors.

This is a reversible experimental datapack/Forge low-code mod. It defines
named city shapes, route plans, the datapack configuration of Underground City
Core's road/bridge worldgen feature, and loot tables. It cannot add populations,
lock doors, or select the world generation profile. Roads are only built into
NEWLY generated chunks of a fresh world; nothing rewrites an existing save.
"""
from __future__ import annotations
import argparse
import json
import math
import re
import zipfile
from pathlib import Path

from validate_intercity import load as load_intercity, validate as validate_intercity
from simulate_regional_supply import read_json as read_supply_json, validate as validate_regional_supply
from simulate_terminal_operations import validate as validate_terminal_operations

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / "content/region-blueprints/cities.json"
STAMP = (2020, 1, 1, 0, 0, 0)
ID = re.compile(r"^[a-z][a-z0-9_]{1,40}$")
MOD = "underground_city_world"


def validate(data: dict) -> None:
    if data.get("schema_version") != 1 or data.get("prototype_only") is not True:
        raise ValueError("Only the explicitly experimental schema 1 prototype is supported")
    if data.get("dimension") != "minecraft:overworld":
        raise ValueError("This experiment targets the Overworld")
    cities = data.get("cities")
    if not isinstance(cities, list) or len(cities) != 3:
        raise ValueError("Expected exactly three city definitions")
    min_gap = data.get("minimum_city_edge_gap_blocks")
    if type(min_gap) is not int or min_gap < 100:
        raise ValueError("Minimum edge separation must be >=100 blocks")
    seen = set()
    for city in cities:
        city_id = city.get("id")
        if not isinstance(city_id, str) or not ID.fullmatch(city_id) or city_id in seen:
            raise ValueError(f"Invalid or repeated city ID: {city_id!r}")
        seen.add(city_id)
        if city.get("city_style") != f"underground_city:{city_id}":
            raise ValueError(f"Non-namespaced city style for {city_id}")
        loc = city.get("center_chunk")
        if (not isinstance(loc, list) or len(loc) != 2
                or any(type(x) is not int or abs(x) > 100000 for x in loc)):
            raise ValueError(f"City {city_id}: invalid chunk coordinates")
        radius = city.get("radius_blocks")
        if type(radius) is not int or not (100 <= radius <= 1000):
            raise ValueError(f"City {city_id}: invalid radius")
        if (type(city.get("max_floors")) is not int
                or not (1 <= city["max_floors"] <= 20)):
            raise ValueError(f"City {city_id}: invalid floor bound")
        for key in ("building_chance", "park_chance"):
            x = city.get(key)
            if type(x) not in (int, float) or not (0.0 <= x <= 1.0):
                raise ValueError(f"City {city_id}: {key} must be 0..1")
        if not isinstance(city.get("visual_goal"), str) or not city["visual_goal"]:
            raise ValueError("Missing city design goal")
    for i, a in enumerate(cities):
        for b in cities[i+1:]:
            distance = math.dist(
                [16*x for x in a["center_chunk"]],
                [16*x for x in b["center_chunk"]],
            )
            if distance - a["radius_blocks"] - b["radius_blocks"] < min_gap:
                raise ValueError(f"{a['id']} and {b['id']} overlap or lack travel countryside")
    if data.get("profile_requirements", {}).get("random_city_chance") != 0.0:
        raise ValueError("Unbounded random cities would undermine three-city design")
    if data["profile_requirements"].get("city_max_radius_at_least", 0) < max(
            c["radius_blocks"] for c in cities):
        raise ValueError("City radius profile scan must include every predefined city")


ROAD_FEATURE = "underground_city:corridor_roads"


def road_feature(cities: dict, corridors: dict) -> dict[str, dict]:
    """Datapack files that place Core's road feature in every Overworld biome.

    Coordinates come only from the blueprints: the route waypoints and the circles
    Lost Cities owns. Only the routes whose kind is listed as buildable receive a
    deck; the long frontier route stays an unpaved path.
    """
    policy = corridors.get("construction_policy", {})
    generation = corridors.get("road_generation", {})
    width = policy.get("road_width_blocks")
    if type(width) is not int or not 3 <= width <= 15:
        raise ValueError("road_width_blocks must be an integer between 3 and 15")
    if generation.get("mode") != "worldgen_feature" or generation.get("feature") != ROAD_FEATURE:
        raise ValueError("road_generation must select the Core corridor road feature")
    if generation.get("new_chunks_only") is not True:
        raise ValueError("Roads must only be built into newly generated chunks")
    kinds = set(generation.get("build_route_kinds", []))
    routes = []
    for route in corridors["routes"]:
        if route["kind"] not in kinds:
            continue
        points = route["waypoints"]
        if len(points) < 2 or any(len(p) != 2 or any(type(v) is not int for v in p) for p in points):
            raise ValueError(f"{route['id']}: road waypoints must be integer [x, z] pairs")
        routes.append({"id": route["id"], "waypoints": points})
    if not routes:
        raise ValueError("No buildable road routes in the blueprint")
    circles = [{"id": c["id"], "x": float(c["center_chunk"][0] * 16 + 8),
                "z": float(c["center_chunk"][1] * 16 + 8), "radius": float(c["radius_blocks"])}
               for c in cities["cities"]]
    namespace = "data/underground_city/"
    return {
        namespace + "worldgen/configured_feature/corridor_roads.json": {
            "type": ROAD_FEATURE,
            "config": {"width": width, "routes": routes, "cities": circles},
        },
        namespace + "worldgen/placed_feature/corridor_roads.json": {
            "feature": ROAD_FEATURE, "placement": [],
        },
        namespace + "forge/biome_modifier/corridor_roads.json": {
            "type": "forge:add_features",
            "biomes": "#minecraft:is_overworld",
            "features": ROAD_FEATURE,
            "step": "top_layer_modification",
        },
    }


def json_bytes(obj: dict) -> bytes:
    return (json.dumps(obj, indent=2, ensure_ascii=False, sort_keys=True) + "\n").encode()


def loot_table(loot: list[tuple[str, int, int, int]]) -> dict:
    """Vanilla-only conservative placeholder loot; no chosen currency yet."""
    entries = []
    for item, weight, low, high in loot:
        entry = {
            "type": "minecraft:item",
            "name": item,
            "weight": weight,
            "functions": [{
                "function": "minecraft:set_count",
                "count": {"type": "minecraft:uniform", "min": low, "max": high},
            }],
        }
        entries.append(entry)
    return {
        "type": "minecraft:chest",
        "pools": [{
            "rolls": {"type": "minecraft:uniform", "min": 1, "max": 3},
            "entries": entries,
        }],
    }


LOOT = {
    "apartment_common": [
        ("minecraft:bread", 12, 1, 3),
        ("minecraft:paper", 9, 1, 5),
        ("minecraft:iron_nugget", 5, 1, 4),
        ("minecraft:leather", 4, 1, 2),
    ],
    "apartment_affluent": [
        ("minecraft:gold_nugget", 7, 1, 4),
        ("minecraft:emerald", 2, 1, 2),
        ("minecraft:clock", 3, 1, 1),
        ("minecraft:book", 8, 1, 2),
    ],
    "office": [
        ("minecraft:paper", 10, 1, 6),
        ("minecraft:redstone", 8, 1, 3),
        ("minecraft:compass", 2, 1, 1),
        ("minecraft:iron_nugget", 5, 1, 3),
    ],
    "industrial": [
        ("minecraft:iron_ingot", 6, 1, 2),
        ("minecraft:copper_ingot", 9, 1, 4),
        ("minecraft:redstone", 6, 1, 3),
        ("minecraft:coal", 10, 1, 5),
    ],
    "illegal_cache": [
        ("minecraft:gold_ingot", 2, 1, 2),
        ("minecraft:crossbow", 1, 1, 1),
        ("minecraft:iron_ingot", 7, 1, 3),
        ("minecraft:emerald", 2, 1, 2),
    ],
}


LAYOUTS = {
    "legacy": {"mod": "underground_city_world", "name": "Underground City: World Prototype",
               "description": "Original three-city world placement and prototype loot."},
    "archipelago": {"mod": "underground_city_archipelago", "name": "Underground City: Archipelago World",
                    "description": "The three islands of the archipelago layout: city placement, regions, checkpoints, roads and loot."},
}


def render(data: dict, layout: str = "legacy") -> dict[str, bytes]:
    validate(data)
    mod = LAYOUTS[layout]["mod"]
    files = {}
    files["META-INF/mods.toml"] = (
        'modLoader="lowcodefml"\nloaderVersion="[47,)"\n'
        'license="All Rights Reserved"\n\n[[mods]]\n'
        f'modId="{mod}"\nversion="0.1.0"\n'
        f'displayName="{LAYOUTS[layout]["name"]}"\n'
        f'description="{LAYOUTS[layout]["description"]}"\n'
        f'\n[[dependencies.{mod}]]\n'
        'modId="lostcities"\nmandatory=true\n'
        'versionRange="[1.20-7.5.5]"\nordering="AFTER"\nside="BOTH"\n'
        f'\n[[dependencies.{mod}]]\n'
        'modId="underground_city"\nmandatory=true\n'
        'versionRange="[0.1.0,)"\nordering="AFTER"\nside="BOTH"\n'
    ).encode()
    files["pack.mcmeta"] = json_bytes({
        "pack": {"pack_format": 15,
                 "description": "Underground City original Lost Cities world prototype"}
    })
    # Fixed city anchors must survive vanilla ocean/river biomes. The standard
    # style multiplies their city factor below the building threshold there.
    files["data/underground_city/lostcities/worldstyles/three_city.json"] = json_bytes({
        "outsidestyle": "lostcities:outside",
        "bridgesupport": "v",
        "highwaysupport": "v",
        "citystyles": [{"factor": 1.0, "citystyle": "lostcities:citystyle_standard"}],
        "citybiomemultipliers": [],
    })
    for city in data["cities"]:
        name = city["id"]
        base = "data/underground_city/lostcities/"
        files[f"{base}predefinedcities/{name}.json"] = json_bytes({
            "dimension": data["dimension"],
            "chunkx": city["center_chunk"][0],
            "chunkz": city["center_chunk"][1],
            "radius": city["radius_blocks"],
            "citystyle": city["city_style"],
        })
        files[f"{base}citystyles/{name}.json"] = json_bytes({
            "inherit": "lostcities:citystyle_standard",
            "buildingsettings": {
                "maxfloors": city["max_floors"],
                "buildingchance": city["building_chance"],
            },
            "parkblocks": {"parkchance": city["park_chance"]},
        })
    for name, entries in LOOT.items():
        files[f"data/underground_city/loot_tables/chests/{name}.json"] = json_bytes(
            loot_table(entries))
    # Ship design metadata so future import/bridge/loot tools can consume it,
    # but do NOT imply Lost Cities automatically places roads or restocks chests.
    planning = load_intercity(ROOT, layout)
    planning["cities"] = data
    issues = validate_intercity(planning)
    if issues:
        raise ValueError("Intercity world plan invalid: " + "; ".join(issues))
    for name, document in road_feature(data, planning["routes"]).items():
        files[name] = json_bytes(document)
    if layout == "archipelago":
        files.update(archipelago_files(data, planning["routes"]))
    for tag, file_id in (
        ("routes", "corridors"),
        ("supply", "intercity-supply"),
        ("restock", "restock-policies"),
        ("conflict", "border-conflict"),
    ):
        files[f"assets/underground_city/prototype_plans/{file_id}.json"] = json_bytes(planning[tag])
    recurring = read_supply_json(ROOT / "content/progression/recurring-supply.json")
    economy_issues = validate_regional_supply(
        recurring, data, planning["catalog"], planning["supply"]
    )
    if economy_issues:
        raise ValueError("Recurring regional economy invalid: " + "; ".join(economy_issues))
    # Packaged as clearly inert design data. Minecraft doesn't load these
    # prototype_plan files as recipes, items, NPC shop inventories or contracts.
    files["assets/underground_city/prototype_plans/recurring-supply.json"] = json_bytes(recurring)
    terminal = read_supply_json(ROOT / "content/gameplay/terminal-operations.json")
    terminal_issues = validate_terminal_operations(terminal, recurring, planning["restock"])
    if terminal_issues:
        raise ValueError("Terminal planning model invalid: " + "; ".join(terminal_issues))
    # Inert original design source: not a live computer screen, chest loot table
    # injection, world shipment encounter, physical black-market box or raid.
    files["assets/underground_city/prototype_plans/terminal-operations.json"] = json_bytes(terminal)
    return files


CHECKPOINT_RADIUS = 48
CIVIC_MARGIN = 60  # a city's civic region (law, exchange, "In Ironhaven") reaches to within this many blocks of its island's shore


def archipelago_files(data: dict, corridors: dict) -> dict[str, bytes]:
    """What only the archipelago layout carries: where each civic region and checkpoint is (overriding Core's legacy-layout coordinates by id, so a
    legacy launch is untouched) and the marker that says which layout this world package is. Coordinates come from the blueprints, never from Java."""
    namespace = "data/underground_city/"
    islands = json.loads((ROOT / "content/region-blueprints/archipelago/islands.json").read_text(encoding="utf-8"))
    files: dict[str, bytes] = {}
    for city in data["cities"]:
        shore = islands["cities"][city["id"]]["shore_radius"]
        files[f"{namespace}regions/{city['id']}.json"] = json_bytes({
            "schema_version": 1, "id": city["id"], "dimension": data["dimension"], "center_chunk": city["center_chunk"],
            "radius_blocks": shore - CIVIC_MARGIN, "city_radius_blocks": city["radius_blocks"]})
    for route in corridors["routes"]:
        for gate in route["checkpoints"]:
            other = route["to"] if gate["faction"] == route["from"] else route["from"]
            files[f"{namespace}checkpoints/{gate['id']}.json"] = json_bytes({
                "schema_version": 1, "title": f"{gate['faction'].capitalize()} gate on the {other.capitalize()} road",
                "jurisdiction": gate["faction"], "dimension": data["dimension"], "x": gate["position"][0], "z": gate["position"][1],
                "radius": CHECKPOINT_RADIUS, "risk": gate["risk"], "route": route["id"]})
    files[f"{namespace}layouts/archipelago.json"] = json_bytes({
        "schema_version": 1, "layout": "archipelago", "arrival": islands["arrival"],
        "cities": {c["id"]: {"center_chunk": c["center_chunk"], "radius_blocks": c["radius_blocks"],
                             "shore_radius": islands["cities"][c["id"]]["shore_radius"]} for c in data["cities"]}})
    return files


def package(data: dict, dest: Path, layout: str = "legacy") -> Path:
    files = render(data, layout)
    dest.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, content in sorted(files.items()):
            info = zipfile.ZipInfo(name, STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, content)
    return dest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--blueprint", type=Path, default=DEFAULT)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        data = json.loads(args.blueprint.read_text(encoding="utf-8"))
        output = package(data, args.output)
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as ex:
        parser.exit(2, f"Cannot generate prototype: {ex}\n")
    print(f"Built original world prototype: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
