#!/usr/bin/env python3
"""Generate the survival-goods stock lines of the regional exchange and the wayside provisions desks (D-091) from content/gameplay/survival-supply.json.

Each line becomes data/underground_city/regional_stock/survival/<id>.json, read by the same loader as every other finite supplier line. A line carries
``requires_mod`` when its item belongs to an optional mod: it is left out at load time while that mod is not installed. ``outlets`` says where the line is
sold: ``city`` (an Operations Terminal inside the city) and/or ``wayside`` (the provisions desk of a wayside shelter that belongs to the city).

    python scripts/generate_survival_supply.py          write
    python scripts/generate_survival_supply.py --check  fail if the committed files differ
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "content/gameplay/survival-supply.json"
OUT = ROOT / "rpg-core/src/main/resources/data/underground_city/regional_stock/survival"
CITIES = {"valewood", "ironhaven", "blackwater"}
OUTLETS = {"city", "wayside"}
MAX_LINES_IN_GAME = 64          # RegionalCatalog.apply refuses more
EXISTING_REGIONAL_LINES = 9     # the generated commodities
STARTER_SHOP_UNIT_PRICES = {"minecraft:bread": 5.0}   # Q Shop starter shop: 4 for 20


def load() -> dict:
    return json.loads(SOURCE.read_text(encoding="utf-8"))


def problems(data: dict) -> list[str]:
    found: list[str] = []
    lines = data["lines"]
    ids = [line["id"] for line in lines]
    if len(ids) != len(set(ids)):
        found.append("duplicate line ids")
    if len(lines) + EXISTING_REGIONAL_LINES > MAX_LINES_IN_GAME:
        found.append(f"{len(lines)} survival lines plus {EXISTING_REGIONAL_LINES} commodities exceed the {MAX_LINES_IN_GAME} the game loads")
    city_price: dict[str, int] = {}
    for line in lines:
        name = line["id"]
        if not re.fullmatch(r"[a-z0-9_]{3,48}", name):
            found.append(f"{name}: bad id")
        if line["city"] not in CITIES:
            found.append(f"{name}: unknown city {line['city']}")
        if not set(line["outlets"]) or not set(line["outlets"]) <= OUTLETS:
            found.append(f"{name}: outlets must be a non-empty subset of {sorted(OUTLETS)}")
        namespace = line["item"].split(":")[0]
        if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", line["item"]):
            found.append(f"{name}: bad item id {line['item']}")
        if namespace != "minecraft" and line.get("requires_mod") != namespace:
            found.append(f"{name}: an item of {namespace} must say requires_mod {namespace}, so the pack without it still loads")
        if namespace == "minecraft" and line.get("requires_mod"):
            found.append(f"{name}: a vanilla item needs no requires_mod")
        if "create" in line["item"].split(":")[0]:
            found.append(f"{name}: the Create mod is not used anywhere in this project")
        for key, low, high in (("price", 1, 1000), ("initial", 0, 256), ("capacity", 1, 256), ("units_per_interval", 1, 64), ("interval_minutes", 1, 600), ("max_order", 1, 64)):
            if not isinstance(line[key], int) or not low <= line[key] <= high:
                found.append(f"{name}: {key} must be an integer from {low} to {high}")
        if isinstance(line.get("initial"), int) and isinstance(line.get("capacity"), int) and line["initial"] > line["capacity"]:
            found.append(f"{name}: initial stock above capacity")
        if line["max_order"] > line["capacity"]:
            found.append(f"{name}: an order larger than the whole shelf")
        # stock is finite and small: no line may offer a stockpile
        if line["capacity"] * line["price"] > 1200:
            found.append(f"{name}: a full shelf would be worth {line['capacity'] * line['price']} credits; keep the stock small")
        if "city" in line["outlets"]:
            city_price[line["item"]] = min(city_price.get(line["item"], 10**9), line["price"])
    # a wayside price carries a convenience premium over the cheapest city or starter-shop price for the same thing
    for line in lines:
        if "wayside" not in line["outlets"]:
            continue
        cheapest = min(city_price.get(line["item"], 10**9), STARTER_SHOP_UNIT_PRICES.get(line["item"], 10**9))
        if cheapest < 10**9 and line["price"] < cheapest * 1.2:
            found.append(f"{line['id']}: the wayside price {line['price']} must be at least 20% above the usual {cheapest}")
    # every wayside outlet of one city offers the same short list, and every city has one
    for city in CITIES:
        if not [line for line in lines if line["city"] == city and "wayside" in line["outlets"]]:
            found.append(f"{city} has no wayside lines")
    return found


def build(data: dict) -> dict[Path, str]:
    files: dict[Path, str] = {}
    for line in data["lines"]:
        body = {
            "schema_version": 1,
            "city": line["city"],
            "item": line["item"],
            "unit_price": line["price"],
            "initial": line["initial"],
            "capacity": line["capacity"],
            "units_per_interval": line["units_per_interval"],
            "interval_ticks": line["interval_minutes"] * 1200,
            "max_order": line["max_order"],
            "outlets": line["outlets"],
        }
        if line.get("requires_mod"):
            body["requires_mod"] = line["requires_mod"]
        files[OUT / f"{line['id']}.json"] = json.dumps(body, indent=2) + "\n"
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = load()
    found = problems(data)
    if found:
        print("Survival supply is invalid:")
        for item in found:
            print(" -", item)
        return 1
    files = build(data)
    stale = []
    if args.check:
        for path, text in files.items():
            if not path.is_file() or path.read_text(encoding="utf-8") != text:
                stale.append(str(path.relative_to(ROOT)))
        extra = sorted(p.name for p in OUT.glob("*.json") if p not in files) if OUT.is_dir() else []
        if stale or extra:
            print("Survival supply files differ from the source; run scripts/generate_survival_supply.py:")
            for item in stale + [f"(orphan) {name}" for name in extra]:
                print(" -", item)
            return 1
        print(f"Checked {len(files)} survival supply files")
        return 0
    OUT.mkdir(parents=True, exist_ok=True)
    for path in OUT.glob("*.json"):
        if path not in files:
            path.unlink()
    for path, text in files.items():
        path.write_text(text, encoding="utf-8")
    print(f"Wrote {len(files)} survival supply files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
