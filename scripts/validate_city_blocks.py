#!/usr/bin/env python3
"""Check every block a furnished-city palette names against the game's own block registry.

A Lost Cities palette that names a block the game does not register makes the whole chunk fail to generate (the house of the first Valewood probe simply was not there: ``Unknown block type
'modernlife:night_stand'``). The registry is dumped by a real server, never guessed from a jar's file names:

    python scripts/run_city_probe.py --name reg --sets world,boats,furnished --cities valewood --radius 0 --output build/probe/reg.json --registry build/probe/block-registry.json
    python scripts/validate_city_blocks.py --registry build/probe/block-registry.json

Without the registry file (public CI has no Modern Life, it is a runtime-only download) the check only prints that it was skipped, unless ``--require`` is given.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

SPEC = re.compile(r"^(?P<name>[a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(?P<props>[^\]]*)\])?$")


def parse_spec(spec: str) -> tuple[str, dict[str, str]]:
    match = SPEC.match(spec)
    if not match:
        raise ValueError(f"not a block spec: {spec!r}")
    props = {}
    if match.group("props"):
        for pair in match.group("props").split(","):
            key, _, value = pair.partition("=")
            props[key.strip()] = value.strip()
    return match.group("name"), props


def problems_of(spec: str, registry: dict[str, dict[str, list[str]]]) -> list[str]:
    """What is wrong with one block spec: an unknown block, an unknown property, or a value the property cannot have."""
    try:
        name, props = parse_spec(spec)
    except ValueError as error:
        return [str(error)]
    if name not in registry:
        return [f"unknown block {name}"]
    known = registry[name]
    out = []
    for key, value in props.items():
        if key not in known:
            out.append(f"{name} has no property {key} (it has: {', '.join(sorted(known)) or 'none'})")
        elif value not in known[key]:
            out.append(f"{name}: {key}={value} is not one of {', '.join(known[key])}")
    return out


def specs_of(palette_entries: dict) -> list[str]:
    """Every block spec of a palette's entries (a plain string, or a list of (chance, block) pairs)."""
    out = []
    for spec in palette_entries.values():
        if isinstance(spec, str):
            out.append(spec)
        else:
            out.extend(block for _, block in spec)
    return out


def validate(registry: dict, buildings) -> list[str]:
    errors = []
    for building in buildings:
        for spec in sorted(set(specs_of(building.palette.entries))):
            for problem in problems_of(spec, registry):
                errors.append(f"{building.id}: {problem}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--registry", type=Path, default=Path("build/probe/block-registry.json"))
    parser.add_argument("--require", action="store_true", help="fail instead of skipping when the registry file does not exist")
    args = parser.parse_args()
    if not args.registry.is_file():
        print(f"block registry {args.registry} not found: skipped (it is a dump of a real server with Modern Life; see the module docstring)")
        return 1 if args.require else 0
    registry = json.loads(args.registry.read_text(encoding="utf-8"))
    import city_content
    errors = validate(registry, city_content.all_buildings())
    for error in errors:
        print(error)
    print(f"{len(errors)} problem(s) in {len(city_content.all_buildings())} building palette(s) against {len(registry)} registered blocks")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
