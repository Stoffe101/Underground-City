#!/usr/bin/env python3
"""How the three cities' economies differ, from the game's own data, and the rules that keep them different without a trick in between.

The owner asked (twelfth pass) for Ironhaven to be the industrial city (manufacturing, machinery, security, logistics), Valewood the farming and wine town (food, small
community business) and Blackwater the city of commerce, electronics, offices, nightlife and high-value trade, and for trade between them to be worth a trip for a real reason
without a trivial arbitrage or a necessity sold in only one city. This script reads what the game loads (regional exchange lines, shop supply lines, buyers and their orders,
city laws, the property ladder's city price factors and the street population) and checks:

* **specialists:** each city has a legal buyer for every career the owner gave it, and a shop there that buys one of those trades' goods (a reason to travel with them);
* **no loop:** nothing a shop buys from players can be bought in any city's exchange, with every discount a buyer can hold, for less than or the same as the shop pays;
* **necessities everywhere:** bread, the First Aid Kit and torches are sold in every city;
* **laws differ:** the three cities' witness chances and fines are not the same, Blackwater most lenient and Ironhaven strictest, as their law summaries say;
* **every buyer has work:** a buyer with no order is a contact that leads nowhere.

``--check`` exits 1 on a problem; ``--update-doc``/``--check-doc`` keep the table in docs/technical/city-economies.md current.
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
CITIES = ("ironhaven", "valewood", "blackwater")
#: The careers the owner's brief gives each city (October 29); a city must have a legal buyer for each and a shop that buys from at least one of them.
SPECIALISTS = {"ironhaven": ("smith", "gunsmith"), "valewood": ("farmer", "vintner"), "blackwater": ("technician", "vintner")}
#: Goods a citizen needs wherever they live (the survival pillar's bought basics); sold in every city.
NECESSITIES = ("minecraft:bread", "underground_city:first_aid_kit", "minecraft:torch")
#: The most a buyer can take off a shop's price (SkillNode.MAX_TOTAL_DISCOUNT); a discounted price is rounded as RegionalMarket does.
MAX_DISCOUNT = 0.10
#: The lenient-to-strict order the law summaries describe.
LENIENT_TO_STRICT = ("blackwater", "valewood", "ironhaven")

BEGIN, END = "<!-- city-economies:begin -->", "<!-- city-economies:end -->"


def load_dir(name: str) -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / name).glob("*.json"))}


def exchange_lines() -> list[dict]:
    return [json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / "regional_stock").rglob("*.json"))]


def java_round(x: float) -> int:
    return int(x + 0.5) if x >= 0 else -int(-x + 0.5)


class Cities:
    def __init__(self) -> None:
        self.lines = exchange_lines()
        self.buyers = load_dir("buyers")
        self.orders = load_dir("work_orders")
        self.laws = load_dir("city_laws")
        self.population = {c: json.loads((DATA / "population" / f"{c}.json").read_text(encoding="utf-8")) for c in CITIES}
        self.ladder = json.loads((DATA / "properties/ladder.json").read_text(encoding="utf-8"))

    def sold(self, city: str) -> list[dict]:
        return [line for line in self.lines if line["city"] == city and line.get("unit_price", 0) > 0]

    def supply(self, city: str) -> list[dict]:
        return [line for line in self.sold(city) if line.get("supply")]

    def buyer_orders(self, city: str, legality: str) -> dict[str, list[dict]]:
        out: dict[str, list[dict]] = collections.defaultdict(list)
        for oid, order in self.orders.items():
            buyer = self.buyers.get(order.get("buyer", ""))
            if buyer and buyer["city"] == city and order.get("legality", "legal") == legality:
                out[order["career"]].append(dict(order, id=oid))
        return out

    def problems(self) -> list[str]:
        problems = []
        for city, careers in SPECIALISTS.items():
            legal = self.buyer_orders(city, "legal")
            for career in careers:
                if career not in legal:
                    problems.append(f"specialists: {city} has no legal buyer for the {career}")
            if not any(line["supply"].get("career") in careers for line in self.supply(city)):
                problems.append(f"specialists: no shop in {city} buys a {' or '.join(careers)}'s goods")
        cheapest: dict[str, int] = {}
        for line in self.lines:
            if line.get("unit_price", 0) > 0:
                cheapest[line["item"]] = min(cheapest.get(line["item"], line["unit_price"]), max(1, java_round(line["unit_price"] * (1 - MAX_DISCOUNT))))
        for line in self.lines:
            supply = line.get("supply")
            if supply and supply["pays"] >= cheapest[line["item"]]:
                problems.append(f"loop: {line['city']}'s shop pays {supply['pays']} for {line['item']}, which an exchange sells for {cheapest[line['item']]} with every discount")
        for item in NECESSITIES:
            missing = [c for c in CITIES if not any(line["item"] == item and not line.get("requires_mod") for line in self.sold(c))]
            if missing:
                problems.append(f"necessities: {item} is not sold in {', '.join(missing)}")
        witness = [self.laws[c]["witness_chance"] for c in LENIENT_TO_STRICT]
        if not witness[0] < witness[1] < witness[2]:
            problems.append(f"laws: witness chances {dict(zip(LENIENT_TO_STRICT, witness))} are not lenient to strict")
        if len({json.dumps(self.laws[c]["fines"], sort_keys=True) for c in CITIES}) < len(CITIES):
            problems.append("laws: two cities fine alike")
        for bid in self.buyers:
            if not any(o.get("buyer") == bid for o in self.orders.values()):
                problems.append(f"buyers: {bid} has no order")
        return problems

    def table(self) -> str:
        factor = self.ladder["rules"]["city_price_factor"]
        rows = ["| | " + " | ".join(c.capitalize() for c in CITIES) + " |", "| --- | " + " | ".join("---" for _ in CITIES) + " |"]

        def row(label: str, fn) -> None:
            rows.append(f"| {label} | " + " | ".join(fn(c) for c in CITIES) + " |")

        def own_goods(c: str) -> str:
            mine = sorted({line["item"].split(":")[1] for line in self.sold(c) if line["item"].startswith("underground_city:")
                           and not any(o["city"] != c and o["item"] == line["item"] for o in self.lines)})
            return ", ".join(mine[:6]) + (f" and {len(mine) - 6} more" if len(mine) > 6 else "") if mine else "none"

        def careers(c: str, legality: str) -> str:
            found = self.buyer_orders(c, legality)
            return ", ".join(f"{k} ({len(v)}, {min(o['credits'] for o in v)}–{max(o['credits'] for o in v)})" for k, v in sorted(found.items())) or "none"

        def buyers(c: str) -> str:
            return ", ".join(sorted(b["title"] for b in self.buyers.values() if b["city"] == c))

        row("Exchange lines (default pack)", lambda c: str(sum(1 for line in self.sold(c) if not line.get("requires_mod"))))
        row("Goods only this city's exchange sells", own_goods)
        row("Shops buy from players", lambda c: ", ".join(sorted({line["supply"]["career"] for line in self.supply(c)})) or "none")
        row("Buyers", buyers)
        row("Legal buyers' orders: career (orders, credits)", lambda c: careers(c, "legal"))
        row("Underground buyers' orders", lambda c: careers(c, "underground"))
        row("Witness chance", lambda c: f"{self.laws[c]['witness_chance']:.0%}")
        row("Homes cost (city price factor)", lambda c: f"{factor[c]:.2f}×")
        row("Street people (count; workers, visitors %)", lambda c: f"{self.population[c]['count']}; {self.population[c]['mix']['worker']}%, {self.population[c]['mix']['visitor']}%")
        return "\n".join(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--update-doc")
    parser.add_argument("--check-doc")
    args = parser.parse_args()
    cities = Cities()
    text = cities.table()
    if args.update_doc or args.check_doc:
        path = Path(args.update_doc or args.check_doc)
        doc = path.read_text(encoding="utf-8")
        head, rest = doc.split(BEGIN, 1)
        _, tail = rest.split(END, 1)
        new = head + BEGIN + "\n\n" + text + "\n\n" + END + tail
        if args.check_doc:
            if new != doc:
                print(f"{path} is stale: run --update-doc")
                return 1
            return 0
        path.write_text(new, encoding="utf-8")
        print(f"updated {path}")
        return 0
    print(text)
    problems = cities.problems()
    print("\n== Rules ==")
    print("\n".join(problems) if problems else "all hold")
    return 1 if args.check and problems else 0


if __name__ == "__main__":
    sys.exit(main())
