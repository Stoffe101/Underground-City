#!/usr/bin/env python3
"""Generate the vehicle dealer lines of the regional exchange (D-101) from content/gameplay/vehicle-tiers.json.

Only a tier that says ``"acquisition": "dealer"`` and ``"implemented": true`` is sold. A dealer line takes its price from its tier, so the price lives in one place. Each line
becomes data/underground_city/regional_stock/vehicles/<id>.json, read by the same loader as every other finite supplier line, and carries ``requires_mod`` so that a pack without
Modern Life still loads. Fuel, repair and insurance do not exist; nothing here sells or implies them.

    python scripts/generate_vehicle_supply.py          write
    python scripts/generate_vehicle_supply.py --check  fail if the committed files differ
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "content/gameplay/vehicle-tiers.json"
OUT = ROOT / "rpg-core/src/main/resources/data/underground_city/regional_stock/vehicles"
CITIES = {"valewood", "ironhaven", "blackwater"}
MAX_LINES_IN_GAME = 64          # RegionalCatalog.apply refuses more
OTHER_LINES = 9 + 29            # the generated commodities and the survival goods


def load() -> dict:
    return json.loads(SOURCE.read_text(encoding="utf-8"))


def problems(data: dict) -> list[str]:
    found: list[str] = []
    tiers = {t["id"]: t for t in data["tiers"]}
    if [t["tier"] for t in data["tiers"]] != list(range(1, len(data["tiers"]) + 1)):
        found.append("tiers must be numbered 1, 2, 3 ... in order")
    prices = [t["price"] for t in data["tiers"]]
    if prices != sorted(prices) or len(set(prices)) != len(prices):
        found.append(f"prices must strictly rise with the tier: {prices}")
    ids = [line["id"] for line in data["dealer"]]
    if len(ids) != len(set(ids)):
        found.append("duplicate dealer line ids")
    if len(ids) + OTHER_LINES > MAX_LINES_IN_GAME:
        found.append(f"{len(ids)} dealer lines plus {OTHER_LINES} other lines exceed the {MAX_LINES_IN_GAME} the game loads")
    price_of_item: dict[str, int] = {}
    for line in data["dealer"]:
        name = line["id"]
        tier = tiers.get(line["tier"])
        if not re.fullmatch(r"[a-z0-9_]{3,48}", name):
            found.append(f"{name}: bad id")
        if tier is None:
            found.append(f"{name}: unknown tier {line['tier']}")
            continue
        if tier["acquisition"] != "dealer" or not tier["implemented"]:
            found.append(f"{name}: tier {tier['id']} is not an implemented dealer tier")
        if line["city"] not in CITIES:
            found.append(f"{name}: unknown city {line['city']}")
        if line["item"] not in tier["examples"]:
            found.append(f"{name}: {line['item']} is not an example of tier {tier['id']}")
        namespace = line["item"].split(":")[0]
        if line.get("requires_mod") != namespace:
            found.append(f"{name}: an item of {namespace} must say requires_mod {namespace}, so the pack without it still loads")
        if "create" in namespace:
            found.append(f"{name}: the Create mod is not used anywhere in this project")
        for key, low, high in (("initial", 0, 16), ("capacity", 1, 16), ("units_per_interval", 1, 8), ("interval_minutes", 1, 600), ("max_order", 1, 2)):
            if not isinstance(line[key], int) or not low <= line[key] <= high:
                found.append(f"{name}: {key} must be an integer from {low} to {high}")
        if line["initial"] > line["capacity"] or line["max_order"] > line["capacity"]:
            found.append(f"{name}: initial stock or an order larger than the shelf")
        # a vehicle is scarce: the shelf may never hold more than two tiers' worth of one day's pay of the whole world
        if line["capacity"] * tier["price"] > 1200:
            found.append(f"{name}: a full shelf would be worth {line['capacity'] * tier['price']} credits; keep the stock small")
        # the same item costs the same in every city
        if price_of_item.setdefault(line["item"], tier["price"]) != tier["price"]:
            found.append(f"{name}: the price of {line['item']} differs between cities")
    return found


def build(data: dict) -> dict[Path, str]:
    tiers = {t["id"]: t for t in data["tiers"]}
    files: dict[Path, str] = {}
    for line in data["dealer"]:
        body = {
            "schema_version": 1,
            "city": line["city"],
            "item": line["item"],
            "unit_price": tiers[line["tier"]]["price"],
            "initial": line["initial"],
            "capacity": line["capacity"],
            "units_per_interval": line["units_per_interval"],
            "interval_ticks": line["interval_minutes"] * 1200,
            "max_order": line["max_order"],
            "outlets": ["city"],
            "requires_mod": line["requires_mod"],
        }
        files[OUT / f"{line['id']}.json"] = json.dumps(body, indent=2) + "\n"
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = load()
    found = problems(data)
    if found:
        print("Vehicle tiers are invalid:")
        for item in found:
            print(" -", item)
        return 1
    files = build(data)
    if args.check:
        stale = [str(path.relative_to(ROOT)) for path, text in files.items() if not path.is_file() or path.read_text(encoding="utf-8") != text]
        extra = sorted(p.name for p in OUT.glob("*.json") if p not in files) if OUT.is_dir() else []
        if stale or extra:
            print("Vehicle dealer files differ from the source; run scripts/generate_vehicle_supply.py:")
            for item in stale + [f"(orphan) {name}" for name in extra]:
                print(" -", item)
            return 1
        print(f"Checked {len(files)} vehicle dealer files")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for path in OUT.glob("*.json"):
        if path not in files:
            path.unlink()
    for path, text in files.items():
        path.write_text(text, encoding="utf-8")
    print(f"Wrote {len(files)} vehicle dealer files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
