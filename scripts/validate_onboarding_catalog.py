#!/usr/bin/env python3
"""Validate portable character-onboarding design data and fallback Core IDs.

This is design/schema validation only, NOT a test of any downloaded class mod.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "content/onboarding/catalog.json"
CORE_RULES = ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/ProgressionRules.java"
ID_RE = re.compile(r"^[a-z][a-z0-9_]*$")


def check_catalog(catalog: dict) -> list[str]:
    errors = []
    if not isinstance(catalog, dict):
        return ["Catalog must be an object"]
    if catalog.get("schema_version") != 1:
        errors.append("Unsupported/missing schema_version")
    if catalog.get("selection_order") != ["city", "profession"]:
        errors.append("Onboarding must present city before profession")
    for collection in ("cities", "professions", "general_skills"):
        rows = catalog.get(collection)
        if not isinstance(rows, list) or not rows:
            errors.append(f"{collection}: expected a non-empty list")
            continue
        used = set()
        for position, entry in enumerate(rows):
            context = f"{collection}[{position}]"
            if not isinstance(entry, dict):
                errors.append(f"{context}: entry must be an object")
                continue
            entry_id = entry.get("id")
            if not isinstance(entry_id, str) or not ID_RE.fullmatch(entry_id):
                errors.append(f"{context}: invalid id {entry_id!r}")
            elif entry_id in used:
                errors.append(f"{context}: duplicate id {entry_id!r}")
            used.add(entry_id)
            if not isinstance(entry.get("name"), str) or not entry["name"].strip():
                errors.append(f"{context}: missing display name")
            if collection != "general_skills":
                if not isinstance(entry.get("description"), str) or len(entry["description"].strip()) < 40:
                    errors.append(f"{context}: description must explain real gameplay")
            if collection == "cities":
                for key in ("economy", "advantages", "challenges"):
                    value = entry.get(key)
                    if not isinstance(value, list) or not value or not all(isinstance(x, str) and x.strip() for x in value):
                        errors.append(f"{context}: {key} must be non-empty strings")
            if collection == "professions":
                branches = entry.get("branches")
                if not isinstance(branches, list) or len(branches) < 2 or len(set(branches)) != len(branches):
                    errors.append(f"{context}: branches must be distinct and numerous")
                if not isinstance(entry.get("starter_objectives"), list) or not entry["starter_objectives"]:
                    errors.append(f"{context}: requires starter objectives")
                for key in ("legal_path", "criminal_path"):
                    if not isinstance(entry.get(key), bool):
                        errors.append(f"{context}: {key} must be boolean")
    return errors


def java_constants(source: str, label: str) -> set[str]:
    pattern = rf"\b{re.escape(label)}\s*=\s*Set\.of\((.*?)\);"
    match = re.search(pattern, source, flags=re.DOTALL)
    if not match:
        raise ValueError(f"Cannot locate fallback Java {label} set")
    return set(re.findall(r'"([^"]+)"', match.group(1)))


def check_core_parity(catalog: dict, java_source: str) -> list[str]:
    errors = []
    for catalog_section, java_section in (
        ("cities", "CITIES"),
        ("professions", "CAREERS"),
        ("general_skills", "GENERAL_SKILLS"),
    ):
        entries = catalog.get(catalog_section)
        if not isinstance(entries, list):
            continue
        try:
            expected = java_constants(java_source, java_section)
        except ValueError as ex:
            errors.append(str(ex))
            continue
        actual = {entry.get("id") for entry in entries if isinstance(entry, dict)}
        if expected != actual:
            errors.append(
                f"{catalog_section}: does not match fallback Core {java_section} "
                f"(catalog only={sorted(actual - expected)}, Java only={sorted(expected - actual)})"
            )
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--core", type=Path, default=CORE_RULES)
    args = parser.parse_args(argv)
    try:
        catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
        java_source = args.core.read_text(encoding="utf-8")
    except (OSError, ValueError) as ex:
        print("Cannot read character catalog:", ex, file=sys.stderr)
        return 2
    errors = check_catalog(catalog) + check_core_parity(catalog, java_source)
    if errors:
        for error in errors:
            print("ERROR:", error, file=sys.stderr)
        return 1
    print(f"Onboarding design validated: {len(catalog['cities'])} cities, "
          f"{len(catalog['professions'])} professions, "
          f"{len(catalog['general_skills'])} general skills; "
          "fallback Java IDs agree.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
