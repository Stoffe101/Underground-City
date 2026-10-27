#!/usr/bin/env python3
"""Judge a city probe report (``run_city_probe.py``) of a furnished world: every city chunk has its city's style, every building chunk is one of that city's furnished buildings, and none of
them is empty (a palette error makes Lost Cities skip a chunk and leaves bare ground, which only the blocks show), and every shop counter can be reached by a customer (D-178).

    python scripts/check_city_probe.py build/probe/furn10.json [--log run-cityprobe-furn10/logs/latest.log]

Exit 1 when anything is wrong. It reads the report and optionally the server log (any ``Error generating chunk`` line fails); it never starts a server.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def judge(report: dict, character: dict[str, set[str]], min_cover: int = 30) -> list[str]:
    """``character`` maps a city id to the set of building ids its CityCharacter may choose."""
    problems = []
    for chunk in report["chunks"]:
        if not chunk.get("city"):
            continue
        style = chunk.get("style", "")
        city = style.split(":")[-1] if style.startswith("underground_city:") else ""
        where = f"chunk {chunk['chunk_x']},{chunk['chunk_z']}"
        if city not in character:
            problems.append(f"{where}: style {style!r} is not a furnished city's")
            continue
        building = chunk.get("building", "")
        if not building:
            continue                                                  # a street, a park or an open lot
        if building not in character[city]:
            problems.append(f"{where}: building {building!r} is not one of {city}'s furnished buildings")
        elif chunk.get("covered_columns_at_street_plus_4", 0) < min_cover:
            problems.append(f"{where}: {building} stands on only {chunk.get('covered_columns_at_street_plus_4')} columns: it did not generate")
    return problems + counter_problems(report)


def counter_problems(report: dict) -> list[str]:
    """Every shop counter the probe found (D-178): its keeper has somewhere to stand, inside the walls, and a customer on the public floor within three blocks can see them."""
    problems = []
    for chunk in report["chunks"]:
        for counter in chunk.get("counters", []):
            where = f"chunk {chunk['chunk_x']},{chunk['chunk_z']} {counter.get('building')} spot {counter.get('spot')}"
            if "stand" not in counter:
                problems.append(f"{where}: the keeper has nowhere to stand")
            elif counter.get("stand_outside"):
                problems.append(f"{where}: the keeper stands outside the walls at {counter['stand']}")
            elif not counter.get("reachable"):
                problems.append(f"{where}: no public cell within three blocks sees the keeper at {counter['stand']} ({counter.get('public_within_reach')} within reach)")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("report", type=Path)
    parser.add_argument("--log", type=Path)
    args = parser.parse_args()
    import city_content
    character: dict[str, set[str]] = {}
    for module_name in ("city_valewood", "city_ironhaven", "city_blackwater"):
        module = __import__(module_name)
        ids = {f"underground_city:{building_id}" for _, picks in module.zones() for building_id, _ in picks}
        character[module.CITY] = ids
    problems = judge(json.loads(args.report.read_text(encoding="utf-8")), character)
    if args.log and args.log.is_file():
        problems += [line.strip()[:200] for line in args.log.read_text(encoding="utf-8", errors="ignore").splitlines() if "Error generating chunk" in line][:20]
    for problem in problems[:60]:
        print(problem)
    chunks = json.loads(args.report.read_text(encoding="utf-8"))["chunks"]
    built = sum(1 for c in chunks if c.get("building"))
    print(f"{len(problems)} problem(s) in {len(chunks)} chunks ({built} buildings)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
