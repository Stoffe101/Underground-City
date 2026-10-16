#!/usr/bin/env python3
"""Stage original city data and a source-checked Lost Cities 7.5.5 profile in a NEW instance.

Does not install Lost Cities, generate terrain, survey roads, or overwrite saves. The staged package also
carries the configuration of the road/bridge worldgen feature, which only affects chunks generated after the
world is created (use seed 579 to reproduce the documented measurements).
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from build_world_prototype import DEFAULT, package, validate


def profile(data: dict) -> dict:
    validate(data)
    return {
        "public": True,
        "lostcity": {
            "description": "Underground City disposable three-city survey",
            "worldStyle": "underground_city:three_city",
            "generateSpawners": False,
            "generateLoot": True,
            "ruinChance": 0.0,
            "rubbleLayer": False,
            "highwayGenerationMode": "INTERCITY_NETWORK_V1",
        },
        "cities": {
            "cityChance": 0.0,
            "cityMinRadius": 100,
            "cityMaxRadius": max(city["radius_blocks"] for city in data["cities"]),
            "cityMinHeight": -64,
            "cityMaxHeight": 320,
        },
        "explosions": {"explosionChance": 0.0, "miniExplosionChance": 0.0},
    }


def config_files(data: dict) -> dict[str, str]:
    """The Lost Cities configuration that selects the three-city profile (relative to a game directory)."""
    return {
        "config/lostcities/profiles/uc_three_city.json": json.dumps(profile(data), indent=2)+"\n",
        "config/lostcities/common.toml": '[profiles]\ndimensionsWithProfiles = ["minecraft:overworld=uc_three_city", "lostcities:lostcity=biosphere", "lostworlds:abyss=biosphere_caves"]\n',
        "defaultconfigs/lostcities-server.toml": '[profiles]\nselectedProfile = "uc_three_city"\nselectedCustomJson = ""\n',
    }


def install(server_root: Path, data: dict) -> list[str]:
    if list(server_root.glob("**/level.dat")) or list(server_root.glob("**/level.dat_old")):
        raise ValueError("Existing save found. City profiles only belong in a new disposable instance.")
    files = config_files(data)
    jar = server_root/"mods/underground-city-world-prototype.jar"
    targets = [server_root/name for name in files] + [jar]
    if any(path.exists() for path in targets):
        raise ValueError("Existing profile, server default or world package found; nothing overwritten.")
    for name, contents in files.items():
        path = server_root/name
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("x", encoding="utf-8") as stream:
            stream.write(contents)
    package(data, jar)
    return [str(path) for path in targets]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-root", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(install(args.server_root, json.loads(DEFAULT.read_text())), indent=2))


if __name__ == "__main__":
    main()
