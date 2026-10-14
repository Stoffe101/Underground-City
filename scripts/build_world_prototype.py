#!/usr/bin/env python3
"""Create original prototype Lost Cities low-code mod with EXACTLY three city anchors.

This is a reversible experimental datapack/Forge low-code mod. It defines
named city shapes and optional vanilla loot tables but cannot automatically
connect cities by roads, add populations, lock doors, or select the world
generation profile. Those require further integration and tested assets.
"""
from __future__ import annotations
import argparse
import json
import math
import re
import zipfile
from pathlib import Path

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


def render(data: dict) -> dict[str, bytes]:
    validate(data)
    files = {}
    files["META-INF/mods.toml"] = (
        'modLoader="lowcodefml"\nloaderVersion="[47,)"\n'
        'license="All Rights Reserved"\n\n[[mods]]\n'
        'modId="underground_city_world"\nversion="0.1.0"\n'
        'displayName="Underground City: World Prototype"\n'
        'description="Original three-city world placement and prototype loot."\n'
        '\n[[dependencies.underground_city_world]]\n'
        'modId="lostcities"\nmandatory=true\n'
        'versionRange="[7,)"\nordering="AFTER"\nside="BOTH"\n'
    ).encode()
    files["pack.mcmeta"] = json_bytes({
        "pack": {"pack_format": 15,
                 "description": "Underground City original Lost Cities world prototype"}
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
    return files


def package(data: dict, dest: Path) -> Path:
    files = render(data)
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
