#!/usr/bin/env python3
"""Make a human-reviewable regional shopping board from original design assets."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from simulate_regional_supply import (
    read_json, validate, SCHEMA_FILE, CITIES_FILE, CAREERS_FILE,
    SPECIALTIES_FILE,
)

ROOT = Path(__file__).resolve().parents[1]


def build_board(data: dict, cities: dict) -> str:
    titles = {c["id"]: c["id"].title() for c in cities["cities"]}
    suppliers = {s["id"]: s for s in data["supplier_stock"]}
    rows = [
        "# Underground City: regional procurement preview",
        "",
        "**Conceptual design only.** No Minecraft merchant inventory, item registry,"
        " recipe, convoy, or actual travel enforcement has been created.",
        "",
        "For repeatable advanced equipment and profession work, buy batches of"
        " specialized goods in other cities, return home to craft, then replenish"
        " your supply later. Normal iron/wood/stone remain broadly available.",
        "",
        "## Regional markets",
        "",
        "| City | Specialty material | Opening stock / cap | Replenishment (active play) | Unit credits |",
        "| --- | --- | ---: | --- | ---: |",
    ]
    for city in cities["cities"]:
        for s in data["supplier_stock"]:
            if s["city"] == city["id"]:
                rows.append(
                    f"| {titles[s['city']]} | {s['id'].replace('_', ' ').title()} "
                    f"| {s['initial_stock']} / {s['capacity']} "
                    f"| +{s['units_per_interval']} every {s['interval_minutes']} min "
                    f"| {s['unit_price']} |"
                )
    rows += [
        "",
        "## Repeatable profession orders",
        "",
        "All the required goods are consumed **every time** the recipe or contract"
        " is performed. Prices below are the sum of the listed input quantities"
        " at current prototype supplier base prices, excluding permits/travel.",
        "",
        "| Career | Tier | Job / repeatable output | Materials (origin) | Unit input cost |",
        "| --- | ---: | --- | --- | ---: |",
    ]
    for recipe in data["recurring_recipes"]:
        cost = 0
        needs = []
        for ing in recipe["inputs"]:
            spec = suppliers[ing["commodity"]]
            needs.append(
                f"{ing['count']}× {ing['commodity'].replace('_', ' ')}"
                f" ({titles[spec['city']]})"
            )
            cost += ing["count"]*spec["unit_price"]
        rows.append(
            f"| {recipe['career'].title()} | {recipe['tier']} "
            f"| {recipe['output']['count']}× {recipe['output']['id'].replace('_', ' ')} "
            f"| {', '.join(needs)} | {cost} |"
        )
    rows += [
        "",
        "## Logistics rules",
        "",
        "- Basic survival and Tier 1 work remain local and accessible.",
        "- Tier 2 needs an actual trip to a foreign city; Tier 3 requires"
        " first visiting all three cities before advancing.",
        "- After that initial discovery, every new batch still consumes specialized"
        " materials from its source city. You can stockpile several batches per"
        " expedition rather than taking a trip for every single craft.",
        "- Shared supplier inventories are capped and refill gradually over active"
        " server time, not idle offline time. Two players do not duplicate stock.",
        "- Risky break-ins and smuggling are planned alternatives; legal permits,"
        " couriers, co-op trading and escorts must remain possible.",
        "- Licensed remote import is a future *limited and expensive* safety valve,"
        " not a free replacement for world exploration.",
        "- No physical city roads/shops, living NPC traders or craft recipes"
        " have been integrated yet.",
        "",
    ]
    return "\n".join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    data = read_json(SCHEMA_FILE)
    cities = read_json(CITIES_FILE)
    issues = validate(data, cities, read_json(CAREERS_FILE),
                      read_json(SPECIALTIES_FILE))
    if issues:
        parser.exit(2, "Invalid economy: " + "; ".join(issues))
    report = build_board(data, cities)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print("Created simulated regional supply board:", args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
