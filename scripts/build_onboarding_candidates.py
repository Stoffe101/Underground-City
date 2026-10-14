#!/usr/bin/env python3
"""Generate *references-only* CurseForge-compatible isolated onboarding packs.

Each ZIP contains one official CurseForge file reference, original test notes,
and (for Class Enhancement) an original authored TOML example. It contains NO
downloaded third-party mod, no Minecraft binary and no RPG Core JAR.

This does not assert that launcher import or multiplayer behavior has passed.
"""
from __future__ import annotations

import argparse
import json
import re
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROFILES = ROOT / "content/modpacks/onboarding-candidates.json"
DEFAULT_CATALOG = ROOT / "content/onboarding/catalog.json"
STAMP = (2020, 1, 1, 0, 0, 0)
SAFE_ID = re.compile(r"^[a-z][a-z0-9-]{1,48}$")


def validate_profiles(definition: dict) -> None:
    if definition.get("schema_version") != 1:
        raise ValueError("Expected candidate manifest schema_version=1")
    if definition.get("minecraft") != "1.20.1" or definition.get("forge") != "47.4.10":
        raise ValueError("Candidate profiles must match the pinned Forge 1.20.1 baseline")
    profiles = definition.get("profiles")
    if not isinstance(profiles, list) or len(profiles) < 1:
        raise ValueError("Profiles list must not be empty")
    seen = set()
    for profile in profiles:
        if not isinstance(profile, dict):
            raise ValueError("Each profile must be an object")
        ident = profile.get("id")
        if not isinstance(ident, str) or not SAFE_ID.fullmatch(ident):
            raise ValueError(f"Unsafe profile id: {ident!r}")
        if ident in seen:
            raise ValueError(f"Duplicate profile: {ident}")
        seen.add(ident)
        for field in ("display_name", "version", "license", "setup", "mode"):
            if not isinstance(profile.get(field), str) or not profile[field].strip():
                raise ValueError(f"{ident}: missing {field}")
        for field in ("project_id", "file_id"):
            if type(profile.get(field)) is not int or profile[field] <= 0:
                raise ValueError(f"{ident}: must pin positive integer {field}")
        target = profile.get("source", "")
        if (not isinstance(target, str)
                or not target.startswith("https://www.curseforge.com/minecraft/mc-mods/")
                or not target.endswith(f"/files/{profile['file_id']}")):
            raise ValueError(f"{ident}: source URL must identify exact pinned release")


def generate_manifest(definition: dict, profile: dict) -> dict:
    return {
        "minecraft": {
            "version": definition["minecraft"],
            "modLoaders": [{"id": "forge-" + definition["forge"], "primary": True}],
        },
        "manifestType": "minecraftModpack",
        "manifestVersion": 1,
        "name": "Underground City - Onboarding Test: " + profile["display_name"],
        "version": "screening-1",
        "author": "Stoffe101",
        "files": [
            {
                "projectID": profile["project_id"],
                "fileID": profile["file_id"],
                "required": True,
            }
        ],
        "overrides": "overrides",
    }


def toml_string(value: str) -> str:
    # TOML basic strings use JSON string escaping for these allowed inputs.
    return json.dumps(value, ensure_ascii=False)


def class_enhancement_fragment(catalog: dict) -> str:
    """Emit documented and Forge-observed class fields for version 2.6.

    Not a full class_enhancement.toml. This is an original reference fragment,
    excluded from automatically loaded Minecraft config paths.
    """
    careers = catalog["professions"]
    profession_icons = {
        "farmer": "minecraft:wheat",
        "smith": "minecraft:iron_ingot",
        "gunsmith": "minecraft:crossbow",
        "technician": "minecraft:redstone",
    }
    lines = [
        "# Original Underground City class reference for Class Enhancement V2.6.",
        "# This is NOT a complete mod config or automatically applied.",
        "# Copy the sections into the mod-generated config/class_enhancement.toml",
        "# and adjust the mod's custom-class count setting using the generated comments.",
        "# City/faction selection remains a separate unsolved integration.",
        "",
    ]
    for idx, career in enumerate(careers):
        text = career["description"].replace("\n", " ").strip()
        if career["id"] not in profession_icons:
            raise ValueError(f"No UI icon for profession {career['id']}")
        lines += [
            f"[custom_classes.custom_{idx}]",
            "id = " + toml_string(career["id"]),
            "name = " + toml_string(career["name"]),
            "description = " + toml_string(text),
            "icon = " + toml_string(profession_icons[career["id"]]),
            "stats = []",
            "attributes = []",
            'main_hand = ""',
            'off_hand = ""',
            "armor = []",
            "items = []",
            "commands = []",
            "",
        ]
    fragment = "\n".join(lines)
    tomllib.loads(fragment)  # fail immediately on a broken literal/duplicate TOML key
    return fragment



def class_enhancement_config(catalog: dict) -> str:
    """Original minimal *actual* Forge config using fields verified in boot logs."""
    fragment = class_enhancement_fragment(catalog)
    content = (
        "# Underground City authored config for Class Enhancement 2.6.\n"
        "# Forge generates remaining default settings at startup.\n"
        "[general]\n"
        "disableDefaultClasses = true\n"
        f"customClassSlots = {len(catalog['professions'])}\n"
        "allowClassReselection = false\n\n"
        + fragment[fragment.index("[custom_classes.custom_0]"):]
    )
    tomllib.loads(content)
    return content


def class_selection_config(catalog: dict) -> str:
    """Customize the faction/side menu with three original cities.

    Config keys and sideDefinitions format verified from mod 1.6.0 Forge boot.
    The class list itself is maintained through the mod's admin editor.
    """
    textures = {
        "valewood": "minecraft:textures/item/wheat.png",
        "ironhaven": "minecraft:textures/item/iron_ingot.png",
        "blackwater": "minecraft:textures/item/redstone.png",
    }
    cities = catalog["cities"]
    sides = []
    for city in cities:
        if city["id"] not in textures:
            raise ValueError(f"City {city['id']} needs an original/vanilla menu icon")
        fields = (city["id"], city["name"], city["subtitle"], textures[city["id"]])
        if any("|" in field or "\n" in field for field in fields):
            raise ValueError("Faction fields must not contain config delimiters")
        sides.append("|".join(fields))
    lines = [
        "# Underground City original side/citizenship settings for Class Selection Mod 1.6.0.",
        "# The mod still owns its class list; edit classes via its admin UI.",
        "[general]",
        "enableRaceSelection = false",
        "requireRaceBeforeClassSelection = false",
        "requireRaceBeforeSideSelection = false",
        "enableSideSelection = true",
        "requireSideBeforeClassSelection = true",
        "showClassGuiOnFirstJoin = true",
        "forceClassSelection = true",
        "allowClassChangeOnlyOnFirstJoin = true",
        "giveStarterKitOnlyFirstTime = true",
        "enableAuthentication = false",
        "enableMessenger = false",
        "sideOptions = " + json.dumps([city["id"] for city in cities]),
        "sideDefinitions = " + json.dumps(sides, ensure_ascii=False),
        "",
    ]
    content = "\n".join(lines)
    tomllib.loads(content)
    return content


def candidate_notes(definition: dict, profile: dict) -> str:
    return (
        "UNDERGROUND CITY: ISOLATED ONBOARDING EVALUATION\n"
        "===============================================\n\n"
        f"Candidate: {profile['display_name']} v{profile['version']}\n"
        f"Minecraft: {definition['minecraft']}  Forge: {definition['forge']}\n"
        f"Official file: {profile['source']}\n"
        f"License: {profile['license']}\n\n"
        "This package contains a REFERENCE to exactly one third-party mod,\n"
        "not its binary. The launcher must fetch the file from the original host.\n"
        "Do not combine these three candidate instances with one another.\n"
        "Do not add RPG Core until first-login provider ownership is resolved.\n\n"
        "SETUP\n"
        f"{profile['setup']}\n\n"
        "EVALUATION\n"
        "1. Use a completely new world and disposable test profile.\n"
        "2. Confirm the menu can select our intended identities, when configured.\n"
        "3. Quit, reconnect, and ensure selection never grants duplicate items.\n"
        "4. Repeat with a second account and a dedicated server.\n"
        "5. Check mod logs and class/faction data access, then screenshots.\n\n"
        "This is NOT a playable Underground City modpack and no compatibility\n"
        "test has been run merely because the launcher imported this manifest.\n"
    )


def zip_with_fixed_metadata(path: Path, entries: dict[str, bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for filename, data in sorted(entries.items()):
            info = zipfile.ZipInfo(filename, STAMP)
            info.external_attr = 0o644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)


def build(definition: dict, catalog: dict, output: Path) -> list[Path]:
    validate_profiles(definition)
    names = []
    for profile in definition["profiles"]:
        entries = {
            "manifest.json": (
                json.dumps(generate_manifest(definition, profile),
                           sort_keys=True, indent=2) + "\n"
            ).encode("utf-8"),
            "overrides/UNDERGROUND_CITY_FIRST_LOGIN_TEST.txt":
                candidate_notes(definition, profile).encode("utf-8"),
        }
        if profile["id"] == "class-enhancement":
            entries["overrides/config/class_enhancement.toml"] = (
                class_enhancement_config(catalog).encode("utf-8")
            )
            entries["overrides/underground-city/reference/class_enhancement_classes.toml"] = (
                class_enhancement_fragment(catalog).encode("utf-8")
            )
        elif profile["id"] == "class-selection":
            entries["overrides/config/classmod-common.toml"] = (
                class_selection_config(catalog).encode("utf-8")
            )
        path = output / f"underground-city-onboarding-{profile['id']}.zip"
        zip_with_fixed_metadata(path, entries)
        names.append(path)
    return names


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profiles", type=Path, default=DEFAULT_PROFILES)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        definition = json.loads(args.profiles.read_text(encoding="utf-8"))
        catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
        result = build(definition, catalog, args.output)
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as err:
        parser.exit(2, f"Cannot construct onboarding profiles: {err}\n")
    for file in result:
        print(file)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
