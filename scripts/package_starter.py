#!/usr/bin/env python3
"""Package our own Forge Core into reproducible starter imports.

No Minecraft binaries, downloaded third-party mods, CurseForge pack data, or
world saves are included. This utility only packages the explicitly supplied
original underground_city jar and standard pack metadata.
"""
from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

ZIP_TIMESTAMP = (2020, 1, 1, 0, 0, 0)
MC_VERSION = "1.20.1"
FORGE_VERSION = "47.4.10"
PROJECT_VERSION = "0.1.0"


def write_archive(path: Path, entries: dict[str, bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, payload in sorted(entries.items()):
            info = zipfile.ZipInfo(name, ZIP_TIMESTAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, payload)


def metadata(value: dict) -> bytes:
    return (json.dumps(value, sort_keys=True, indent=2) + "\n").encode("utf-8")


def validate_original_jar(jar: Path) -> bytes:
    if not jar.is_file() or not jar.name.startswith("underground_city-") or jar.suffix != ".jar":
        raise ValueError("Expected an existing underground_city-*.jar original build")
    payload = jar.read_bytes()
    with zipfile.ZipFile(jar) as archive:
        if "META-INF/mods.toml" not in archive.namelist():
            raise ValueError("JAR missing Forge META-INF/mods.toml")
        mods_toml = archive.read("META-INF/mods.toml").decode("utf-8")
        if 'modId="underground_city"' not in mods_toml:
            raise ValueError("JAR does not appear to be the Underground City mod")
    return payload


def package(jar: Path, output: Path) -> tuple[Path, Path]:
    original = validate_original_jar(jar)
    name = "Underground City Core - Development Preview"
    summary = "Minimal private-play Forge 1.20.1 starter with our original Core only."
    modrinth = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": PROJECT_VERSION,
        "name": name,
        "summary": summary,
        "files": [],
        "dependencies": {
            "minecraft": MC_VERSION,
            "forge": FORGE_VERSION,
        },
    }
    curseforge = {
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": name,
        "version": PROJECT_VERSION,
        "author": "Stoffe101",
        "minecraft": {
            "version": MC_VERSION,
            "modLoaders": [{"id": "forge-" + FORGE_VERSION, "primary": True}],
        },
        "files": [],
        "overrides": "overrides",
    }
    overrides_path = "overrides/mods/" + jar.name
    readme = (
        "Underground City Core Development Preview\n"
        "====================================\n"
        "This pack ONLY includes the original experimental RPG Core. It does NOT yet\n"
        "contain cities, NPCs, weapons, economies, other mods, or a finished game.\n"
        "A first-time join uses /uc register <city> <career>, then /uc status.\n"
        "Do not import into an existing world without a backup.\n"
        "Requires Minecraft 1.20.1 and Forge " + FORGE_VERSION + ".\n"
        "Original mod © 2026 Stoffe101. All rights reserved.\n"
    ).encode("utf-8")

    mrpack = output / "underground-city-core-preview.mrpack"
    cfpack = output / "underground-city-core-preview-curseforge.zip"
    write_archive(mrpack, {
        "modrinth.index.json": metadata(modrinth),
        overrides_path: original,
        "overrides/UNDERGROUND_CITY_README.txt": readme,
    })
    write_archive(cfpack, {
        "manifest.json": metadata(curseforge),
        overrides_path: original,
        "overrides/UNDERGROUND_CITY_README.txt": readme,
    })
    return mrpack, cfpack


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    for archive in package(args.jar, args.output):
        print("Created:", archive)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
