#!/usr/bin/env python3
"""Generate the survival-goods stock lines of the regional exchange and the wayside provisions desks (D-091) from content/gameplay/survival-supply.json.

Each line becomes data/underground_city/regional_stock/survival/<id>.json, read by the same loader as every other finite supplier line. A line carries
``requires_mod`` when its item belongs to an optional mod: it is left out at load time while that mod is not installed. ``outlets`` says where the line is
sold: ``city`` (an Operations Terminal inside the city), ``wayside`` (the provisions desk of a wayside shelter that belongs to the city) and/or
``shop:<kind>`` (the counter of a shopkeeper in a furnished building of that kind, D-126; the shops themselves are declared in content/gameplay/shop-supply.json
and written to data/underground_city/shop_counters).

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
# the raw materials of the hybrid economy (D-120): the same kind of finite, shared, slowly refilling line, sold in the cities only
MATERIALS_SOURCE = ROOT / "content/gameplay/materials-supply.json"
MATERIALS_OUT = ROOT / "rpg-core/src/main/resources/data/underground_city/regional_stock/materials"
# what the shopkeepers of the furnished cities sell over their counters (D-126)
SHOP_SOURCE = ROOT / "content/gameplay/shop-supply.json"
SHOP_OUT = ROOT / "rpg-core/src/main/resources/data/underground_city/regional_stock/shops"
COUNTER_OUT = ROOT / "rpg-core/src/main/resources/data/underground_city/shop_counters"
CITIES = {"valewood", "ironhaven", "blackwater"}
OUTLETS = {"city", "wayside"}
SHOP_OUTLET = re.compile(r"shop:[a-z_]{2,24}")
MAX_LINES_IN_GAME = 128         # RegionalCatalog.apply refuses more
EXISTING_REGIONAL_LINES = 13    # the generated commodities (9) and vehicle lines (4)
STARTER_SHOP_UNIT_PRICES = {"minecraft:bread": 5.0}   # Q Shop starter shop: 4 for 20


def load() -> dict:
    return json.loads(SOURCE.read_text(encoding="utf-8"))


def problems(data: dict, wayside: bool = True, other_lines: int = 0) -> list[str]:
    found: list[str] = []
    lines = data["lines"]
    ids = [line["id"] for line in lines]
    if len(ids) != len(set(ids)):
        found.append("duplicate line ids")
    if len(lines) + other_lines + EXISTING_REGIONAL_LINES > MAX_LINES_IN_GAME:
        found.append(f"{len(lines)} lines plus {other_lines} others and {EXISTING_REGIONAL_LINES} commodities exceed the {MAX_LINES_IN_GAME} the game loads")
    city_price: dict[str, int] = {}
    for line in lines:
        name = line["id"]
        if not re.fullmatch(r"[a-z0-9_]{3,48}", name):
            found.append(f"{name}: bad id")
        if line["city"] not in CITIES:
            found.append(f"{name}: unknown city {line['city']}")
        if not line["outlets"] or any(o not in OUTLETS and not SHOP_OUTLET.fullmatch(o) for o in line["outlets"]):
            found.append(f"{name}: outlets must be a non-empty list of {sorted(OUTLETS)} or shop:<kind>")
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
    for city in CITIES if wayside else ():
        if not [line for line in lines if line["city"] == city and "wayside" in line["outlets"]]:
            found.append(f"{city} has no wayside lines")
    return found


def shop_problems(shops: dict, all_lines: list[dict]) -> list[str]:
    """The shop counters: each names a furnished building and a spot, sells at least one line of its own city, and is never cheaper than the cheapest other honest price."""
    found: list[str] = []
    outlets = [shop["outlet"] for shop in shops["shops"]]
    if len(outlets) != len(set(outlets)):
        found.append("two shops share an outlet")
    for shop in shops["shops"]:
        if not SHOP_OUTLET.fullmatch(shop["outlet"]) or shop["city"] not in CITIES or shop["spot"] not in ("counter", "desk"):
            found.append(f"{shop['outlet']}: needs a shop:<kind> outlet, a city and a counter or desk spot")
        if not re.fullmatch(r"underground_city:[a-z0-9_]+", shop["building"]):
            found.append(f"{shop['outlet']}: building must be an underground_city building id")
        sold = [line for line in all_lines if shop["outlet"] in line["outlets"]]
        if not sold:
            found.append(f"{shop['outlet']}: sells nothing")
        if any(line["city"] != shop["city"] for line in sold):
            found.append(f"{shop['outlet']}: sells another city's line")
        if len(sold) > 16:
            found.append(f"{shop['outlet']}: more than the 16 lines a counter shows")
    known = set(outlets)
    for line in all_lines:
        for outlet in line["outlets"]:
            if outlet.startswith("shop:") and outlet not in known:
                found.append(f"{line['id']}: sold at {outlet}, which no shop declares")
    # a counter is a convenience, never an arbitrage: no counter undercuts the cheapest city or starter-shop price for the same item
    cheapest: dict[str, float] = dict(STARTER_SHOP_UNIT_PRICES)
    cheapest["minecraft:cobblestone"] = 1.5   # starter supply: 16 for 24
    cheapest["minecraft:crafting_table"] = 18.0
    for line in all_lines:
        if "city" in line["outlets"]:
            cheapest[line["item"]] = min(cheapest.get(line["item"], 10**9), line["price"])
    for line in all_lines:
        if any(o.startswith("shop:") for o in line["outlets"]) and "city" not in line["outlets"] and line["price"] < cheapest.get(line["item"], 0):
            found.append(f"{line['id']}: a counter price of {line['price']} undercuts the usual {cheapest[line['item']]}")
    return found


def build_counters(shops: dict) -> dict[Path, str]:
    files: dict[Path, str] = {}
    for shop in shops["shops"]:
        kind = shop["outlet"].split(":", 1)[1]
        files[COUNTER_OUT / f"{kind}.json"] = json.dumps({"schema_version": 1, "outlet": shop["outlet"], "building": shop["building"], "spot": shop["spot"],
                                                         "city": shop["city"], "title": shop["title"]}, indent=2) + "\n"
    return files


def build(data: dict, out: Path = OUT) -> dict[Path, str]:
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
        files[out / f"{line['id']}.json"] = json.dumps(body, indent=2) + "\n"
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    survival = load()
    materials = json.loads(MATERIALS_SOURCE.read_text(encoding="utf-8"))
    shops = json.loads(SHOP_SOURCE.read_text(encoding="utf-8"))
    found = (problems(survival, True, len(materials["lines"]) + len(shops["lines"])) + problems(materials, False, len(survival["lines"]) + len(shops["lines"]))
             + problems(shops, False, len(survival["lines"]) + len(materials["lines"])))
    found += shop_problems(shops, survival["lines"] + materials["lines"] + shops["lines"])
    # an item sold by two sources would give one shelf two owners
    seen: dict[str, str] = {}
    for label, source in (("survival", survival), ("materials", materials), ("shops", shops)):
        for line in source["lines"]:
            if line["id"] in seen:
                found.append(f"line id {line['id']} is in both the {seen[line['id']]} and the {label} supply files")
            seen[line["id"]] = label
    if found:
        print("Supply data is invalid:")
        for item in found:
            print(" -", item)
        return 1
    groups = [(build(survival, OUT), OUT, "survival"), (build(materials, MATERIALS_OUT), MATERIALS_OUT, "materials"), (build(shops, SHOP_OUT), SHOP_OUT, "shop"),
              (build_counters(shops), COUNTER_OUT, "shop counter")]
    if args.check:
        bad = []
        for files, out, label in groups:
            for path, text in files.items():
                if not path.is_file() or path.read_text(encoding="utf-8") != text:
                    bad.append(str(path.relative_to(ROOT)))
            bad += [f"(orphan) {label}/{p.name}" for p in (sorted(out.glob("*.json")) if out.is_dir() else []) if p not in files]
        if bad:
            print("Supply files differ from the source; run scripts/generate_survival_supply.py:")
            for item in bad:
                print(" -", item)
            return 1
        print(f"Checked {sum(len(files) for files, _, _ in groups)} supply files")
        return 0
    for files, out, label in groups:
        out.mkdir(parents=True, exist_ok=True)
        for path in out.glob("*.json"):
            if path not in files:
                path.unlink()
        for path, text in files.items():
            path.write_text(text, encoding="utf-8")
        print(f"Wrote {len(files)} {label} supply files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
