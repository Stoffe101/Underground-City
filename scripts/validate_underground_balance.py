#!/usr/bin/env python3
"""Risk-adjusted balance of underground contracts against legal work (D-052).

The pillar: illegal work pays more on paper but is never the free option. For every non-errand underground
contract this tool walks the real corridor between the contract's origin city and its buyer's city, takes the
inspection chance at each gate from the city law and the gate's risk level (the same arithmetic as
``LawMath.inspectionChance`` in Core), applies the traveller's concealment skills or licence, and computes
the expected return per credit of input cost:

    EV = P(not searched) * credits - cost - E[fine]          return = EV / cost

and compares it with the career's legal baseline L (mean of credits/cost - 1 over its legal, priced orders).
Rules (checked for every contract):

    careless  (no skills, no heat)          return <= 0.95 L     smuggling without preparation loses to legal work
    hot       (careless, heat 4)            return <= 0.60 L     a bad record makes it clearly worse
    trained   (all concealment skills)      0.85 L <= return <= 1.80 L   a prepared smuggler earns modestly more
    best      (concealment + licence)       return <= 2.00 L     nothing, not even a licensed carrier, doubles legal work

Run ``python scripts/validate_underground_balance.py`` (exit 1 on a violation) or ``--table`` for the numbers.
This is arithmetic over data, not a playtest; real balance needs 30-60 minute co-op sessions.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_regional_content as content  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
HEAT_CAP_PROFILE = 4


def load_dir(name: str) -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / name).glob("*.json"))}


def inspection_chance(law: dict, risk: str, heat: int, skill_modifier: float) -> float:
    base = law["inspection"][risk]
    chance = base + law["inspection"]["heat_step"] * max(0, heat) + min(0.0, skill_modifier)
    return max(0.02, min(law["inspection"]["max"], chance))


def tag_items(tag: str) -> set[str]:
    """The item ids an item tag of the Core's own datapack lists (third-party ids included, installed or not)."""
    namespace, _, name = tag.partition(":")
    if namespace != "underground_city":
        raise SystemExit(f"contraband tag {tag}: only Core tags can be checked offline")
    data = json.loads((DATA / "tags" / "items" / f"{name}.json").read_text(encoding="utf-8"))
    return {v["id"] if isinstance(v, dict) else v for v in data["values"]}


def category_of(contract: dict, contraband: dict[str, dict]) -> str | None:
    """The contraband category of the contract's goods, or None when nothing carried is illegal."""
    found = None
    for req in contract["requirements"]:
        item = req["item"]
        for name, category in contraband.items():
            for entry in category["entries"]:
                if "tag" in entry:
                    if item in tag_items(entry["tag"]):
                        found = name
                    continue
                if entry["item"] != item:
                    continue
                if not req.get("gun_ids") and "nbt_key" not in entry:
                    found = name
                elif req.get("gun_ids"):
                    # Guns: restricted unless every accepted gun is on the licensed list.
                    licensed = {g for c in contraband.values() if c["category"] == "firearms_licensed"
                                for e in c["entries"] for g in e.get("values", [])}
                    found = "firearms_licensed" if all(g in licensed for g in req["gun_ids"]) else "firearms_restricted"
    return found


def gates_between(corridors: dict, origin: str, destination: str) -> list[dict]:
    for route in corridors["routes"]:
        if {route["from"], route["to"]} == {origin, destination}:
            ordered = sorted(route["checkpoints"], key=lambda g: 0 if g["faction"] == origin else 1)
            return ordered
    raise SystemExit(f"No corridor between {origin} and {destination}")


def expected_return(contract: dict, cost: float, category: str | None, gates: list[dict], laws: dict[str, dict],
                    heat: int, skill_modifier: float, licensed: bool) -> tuple[float, float]:
    """Returns (return per credit of cost, probability of seizure)."""
    survive, expected_fine = 1.0, 0.0
    if category is not None:
        for gate in gates:
            law = laws[gate["faction"]]
            status = law["categories"].get(category, "legal")
            if status == "legal" or (status == "permit" and licensed):
                continue
            p = inspection_chance(law, gate["risk"], heat, skill_modifier)
            expected_fine += survive * p * law["fines"][status]
            survive *= 1 - p
    ev = survive * contract["credits"] - cost - expected_fine
    return ev / cost, 1 - survive


def legal_baseline(data: dict, contracts: dict, costs: dict, guns: dict) -> dict[str, float]:
    """Mean credits/cost - 1 over each career's legal, priced orders and contracts."""
    product_costs = {}
    prices = {c["id"]: c["stock"]["unit_price"] for c in data["commodities"]}
    for p in data["products"]:
        product_costs[p["id"]] = sum(prices[k] * n for k, n in p["inputs"].items() if ":" not in k) / p["count"]
    ratios: dict[str, list[float]] = {}
    for order in data["orders"]:
        cost = sum(product_costs[k] * n for k, n in order["requirements"].items())
        ratios.setdefault(order["career"], []).append(order["credits"] / cost - 1)
    for c in contracts["contracts"]:
        if c["legality"] == "legal":
            ratios.setdefault(c["career"], []).append(c["credits"] / content.contract_cost(c, costs, guns) - 1)
    return {career: sum(values) / len(values) for career, values in ratios.items()}


def evaluate() -> list[dict]:
    data = content.load_source()
    contracts = content.load_contracts()
    costs = content.unit_costs(data, contracts)
    guns = contracts["reference_values"]["guns"]
    laws, contraband, buyers, trees = load_dir("city_laws"), load_dir("contraband"), load_dir("buyers"), load_dir("skill_trees")
    corridors = json.loads((ROOT / "content/region-blueprints/corridors.json").read_text(encoding="utf-8"))
    baseline = legal_baseline(data, contracts, costs, guns)
    rows = []
    for c in contracts["contracts"]:
        if c["legality"] != "underground" or c.get("errand"):
            continue
        cost = content.contract_cost(c, costs, guns)
        category = category_of(c, contraband)
        tree = trees[c["career"]]["nodes"]
        concealment = sum(n["effects"].get("inspection_modifier", 0.0) for n in tree)
        has_licence = category is not None and any(category in n["effects"].get("licences", []) for n in tree)
        gates = gates_between(corridors, c["origin"], buyers[c["buyer"]]["city"])
        profiles = {
            "careless": (0, 0.0, False),
            "hot": (HEAT_CAP_PROFILE, 0.0, False),
            "trained": (0, concealment, False),
            "best": (0, concealment, has_licence),
        }
        row = {"id": c["id"], "career": c["career"], "origin": c["origin"], "buyer_city": buyers[c["buyer"]]["city"],
               "category": category, "nominal": c["credits"] / cost, "legal_baseline": baseline[c["career"]]}
        for name, (heat, mod, licensed) in profiles.items():
            ret, seized = expected_return(c, cost, category, gates, laws, heat, mod, licensed)
            row[name] = ret
            row[name + "_seizure"] = seized
        rows.append(row)
    return rows


def violations(rows: list[dict]) -> list[str]:
    problems = []
    for row in rows:
        L = row["legal_baseline"]
        checks = [
            (row["careless"] <= 0.95 * L, f"careless return {row['careless']:.2f} must not beat 0.95 x legal ({0.95 * L:.2f})"),
            (row["hot"] <= 0.60 * L, f"hot return {row['hot']:.2f} must be under 0.60 x legal ({0.60 * L:.2f})"),
            (0.85 * L <= row["trained"] <= 1.80 * L, f"trained return {row['trained']:.2f} must sit in 0.85-1.80 x legal ({0.85 * L:.2f}-{1.8 * L:.2f})"),
            (row["best"] <= 2.00 * L, f"best-case return {row['best']:.2f} must not exceed 2 x legal ({2 * L:.2f})"),
            (row["careless"] <= row["trained"] <= row["best"] + 1e-9, "returns must rise with preparation"),
        ]
        problems += [f"{row['id']}: {message}" for ok, message in checks if not ok]
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--table", action="store_true", help="print the numbers")
    args = parser.parse_args()
    rows = evaluate()
    if args.table or True:
        print(f"{'contract':24}{'route':24}{'category':22}{'nominal':>8}{'legal':>7}{'careless':>9}{'hot':>7}{'trained':>8}{'best':>7}{'seized%':>8}")
        for r in rows:
            print(f"{r['id']:24}{r['origin'] + '>' + r['buyer_city']:24}{str(r['category']):22}{r['nominal']:8.2f}{r['legal_baseline']:7.2f}"
                  f"{r['careless']:9.2f}{r['hot']:7.2f}{r['trained']:8.2f}{r['best']:7.2f}{100 * r['careless_seizure']:7.0f}%")
    problems = violations(rows)
    for problem in problems:
        print("FAIL:", problem)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
