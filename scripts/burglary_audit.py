#!/usr/bin/env python3
"""What a burglary is worth by building tier: the expected fence value of one search of each kind of furniture (D-144) and of a business's back-room stock (D-164).

For every searchable kind and tier 1 to 6 this follows ``BurglarySearch.roll`` exactly: one draw (two from tier 4), each draw picks an entry the tier and the building's use allow, weighted, or
nothing with the falling empty weight ``max(4, empty_weight * (8 - tier) / 7)``. A find is valued at what a fence thinks it is worth (``fences/values.json``) times a fence's usual rate
(45 to 50 percent for goods taken in another city; 30 percent in the same one). Items a fence does not buy are worth nothing here. The expected haul of a typical building puts the searches
a thief can make in one visit together (``TYPICAL``: an estimate of what the furnished plans hold, not a count from a generated world).

    python scripts/burglary_audit.py            the tables
    python scripts/burglary_audit.py --check    exit 1 when a rule below breaks

Rules: a richer tier is worth at least as much as a poorer one for every typical building, and no single search at any tier is worth more than ``CEILING`` credits to a fence on average.
This is arithmetic over data, not a playtest.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
RATE = 0.475          # a fence's usual share of the worth for goods from another city (The Yard 45%, The Back Room 50%)
CEILING = 120         # the most one search may be worth to a fence on average, at any tier
#: What a thief searches in one visit, by building use (an estimate of the furnished plans' furniture; a business's back room adds its stock containers).
TYPICAL = {
    "house":     {"bedside": 2, "kitchen": 2, "books": 1, "shelf": 1, "trash": 1},
    "flats":     {"bedside": 1, "kitchen": 1, "shelf": 1, "trash": 1},
    "shop":      {"office": 1, "shelf": 1, "trash": 1, "stock": 2},
    "pub":       {"office": 1, "kitchen": 1, "trash": 1, "stock": 2},
    "club":      {"office": 1, "music": 1, "shelf": 1, "stock": 2},
    "office":    {"office": 3, "books": 1, "trash": 1},
    "workshop":  {"workshop": 2, "shelf": 1, "stock": 1},
    "warehouse": {"workshop": 1, "stock": 2},
}


def load():
    furniture = json.loads((DATA / "search_spots/furniture.json").read_text(encoding="utf-8"))
    values = json.loads((DATA / "fences/values.json").read_text(encoding="utf-8"))["values"]
    return furniture["kinds"], values


def expected(kind: dict, tier: int, use: str, values: dict, draws: int | None = None) -> float:
    """Expected fence worth (before the fence's rate) of one search of ``kind`` at ``tier`` in a building of ``use``."""
    fits = [e for e in kind["loot"] if e.get("min_tier", 1) <= tier <= e.get("max_tier", 6) and (not e.get("uses") or use in e["uses"]) and not e.get("requires_mod")]
    if not fits:
        return 0.0
    total = sum(e["weight"] for e in fits)
    empty = max(4, kind["empty_weight"] * (8 - tier) // 7)
    per_draw = 0.0
    for e in fits:
        lo, hi = (e["count"] if "count" in e else [1, 1])
        per_draw += e["weight"] / (total + empty) * (lo + hi) / 2 * values.get(e["item"], 0)
    n = draws if draws is not None else (2 if tier >= 4 else 1)
    return per_draw * n


def table(kinds: dict, values: dict) -> tuple[list[str], dict[str, list[float]]]:
    lines = ["| use | " + " | ".join(f"tier {t}" for t in range(1, 7)) + " |", "|---|" + "---|" * 6]
    haul: dict[str, list[float]] = {}
    for use, spots in TYPICAL.items():
        row = []
        for tier in range(1, 7):
            worth = 0.0
            for name, n in spots.items():
                kind = kinds.get(name)
                if kind is None:
                    continue
                # a back-room container is filled with three draws (BurglarySearch.stock); furniture as roll() says
                worth += n * expected(kind, tier, use, values, draws=3 if name == "stock" else None)
            row.append(worth * RATE)
        haul[use] = row
        lines.append(f"| {use} | " + " | ".join(f"{v:.0f}" for v in row) + " |")
    return lines, haul


def per_kind(kinds: dict, values: dict) -> list[str]:
    lines = ["| kind (use) | " + " | ".join(f"tier {t}" for t in range(1, 7)) + " |", "|---|" + "---|" * 6]
    for name, kind in kinds.items():
        uses = sorted({u for e in kind["loot"] for u in e.get("uses", [])}) or ["any"]
        use = "house" if "house" in uses or uses == ["any"] else uses[0]
        draws = 3 if name == "stock" else None
        lines.append(f"| {name} ({use}) | " + " | ".join(f"{expected(kind, t, use, values, draws) * RATE:.1f}" for t in range(1, 7)) + " |")
    return lines


def problems(kinds: dict, values: dict) -> list[str]:
    found = []
    _, haul = table(kinds, values)
    for use, row in haul.items():
        for t in range(1, 6):
            if row[t] + 1e-9 < row[t - 1]:
                found.append(f"a tier {t + 1} {use} is worth less to a fence than a tier {t} one ({row[t]:.0f} < {row[t - 1]:.0f})")
    for name, kind in kinds.items():
        for use in {u for e in kind["loot"] for u in e.get("uses", [])} | {"house"}:
            for t in range(1, 7):
                v = expected(kind, t, use, values, 3 if name == "stock" else None) * RATE
                if v > CEILING:
                    found.append(f"one search of {name} in a tier {t} {use} is worth {v:.0f} to a fence, above {CEILING}")
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    kinds, values = load()
    bad = problems(kinds, values)
    if args.check:
        for b in bad:
            print("FAIL:", b)
        print("burglary audit: " + ("all rules hold" if not bad else f"{len(bad)} problem(s)"))
        return 1 if bad else 0
    print(f"== Expected fence credits for one search, by kind and tier (a fence's usual rate, {RATE:.0%}) ==")
    print("\n".join(per_kind(kinds, values)))
    print()
    print("== Expected fence credits for one visit to a typical building, by use and tier ==")
    print("\n".join(table(kinds, values)[0]))
    print()
    print("== Rules ==")
    print("\n".join(bad) if bad else "all hold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
