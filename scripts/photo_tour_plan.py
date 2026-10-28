#!/usr/bin/env python3
"""A photo tour of a private pack's interiors (eleventh pass), written from its plans: for each use the selection lets (houses, flats, shops, workshops,
warehouses, offices, clubs), the first buildings of that use, photographed at their street door from the street and inside at their first bed, stove,
bathroom, seat and counter, each with the tour's safe camera (PhotoTour: a clear view of the spot, or the frame from in front of it, said in the log).

    photo_tour_plan.py <staged pack> <selection.json> <tour.json> [--per-use 1]

The tour, and every frame taken from it, is private: it is written outside every checkout (refused inside one) and its shot names are the use and a
number, never a building's name. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import private_pack_tool as packs  # noqa: E402

INSIDE = ["bed", "stove", "bath", "seat", "counter"]


def plan_of(pack: Path, building: str) -> dict | None:
    ns, path = building.split(":", 1)
    file = pack / "data" / ns / "city_buildings" / f"{path}.json"
    return json.loads(file.read_text(encoding="utf-8")) if file.is_file() else None


def tour(pack: Path, selection: dict, per_use: int) -> dict:
    by_use: dict[str, list[str]] = {}
    # the most likely first: a building of low weight may not stand within reach of its city's centre (private tour 14 skipped a house never generated there)
    for b in sorted(selection.get("buildings", []), key=lambda b: -float(b.get("weight", 1.0))):
        family = b["id"].rsplit("_", 1)[0] if b["id"].rsplit("_", 1)[-1] in ("90", "180", "270") else b["id"]
        ids = by_use.setdefault(b["use"], [])
        if b["id"] not in ids and not any(i.startswith(family) for i in ids):
            ids.append(b["id"])
    for m in selection.get("multi_buildings", []):
        if m["id"].endswith(":none") or "use" not in m:
            continue
        # a multi-chunk building's parts are buildings of their own to Lost Cities: its first part with a street door stands for it
        ns = m["id"].split(":", 1)[0]
        for part in sorted((pack / "data" / ns / "city_buildings").glob(m["id"].split(":", 1)[1] + "_*.json")):
            doc = json.loads(part.read_text(encoding="utf-8"))
            if doc.get("spots") and doc["spots"][0]["kind"] == "door":
                by_use.setdefault(m["use"], []).append(f"{ns}:{part.stem}")
                break
    shots = []
    for use, ids in sorted(by_use.items()):
        for n, building in enumerate(ids[:per_use], start=1):
            doc = plan_of(pack, building)
            if not doc:
                continue
            kinds = [s["kind"] for s in doc.get("spots", [])]
            if kinds and kinds[0] == "door":
                shots.append({"name": f"{use}{n}-street", "building": building, "nth": 0, "spot": "door", "back": 9, "eye": 5, "pitch": 12, "hour": 6000, "settle": 200})
            for kind in INSIDE:
                if kind in kinds:
                    shots.append({"name": f"{use}{n}-{kind}", "building": building, "nth": 0, "spot": kind, "back": 4, "eye": 1.62, "hour": 6000, "settle": 160})
    return {"shots": shots, "note": "PRIVATE: a tour of a private pack's interiors; keep it and its frames outside every repository."}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pack", type=Path)
    parser.add_argument("selection", type=Path)
    parser.add_argument("out", type=Path)
    parser.add_argument("--per-use", type=int, default=1)
    args = parser.parse_args(argv)
    packs.refuse_in_repo(args.out)
    result = tour(args.pack, json.loads(args.selection.read_text(encoding="utf-8")), args.per_use)
    args.out.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    print(f"{len(result['shots'])} shot(s) written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
