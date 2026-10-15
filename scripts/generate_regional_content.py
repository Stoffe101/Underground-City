#!/usr/bin/env python3
"""Generate Core regional goods, recipes, supplier stock and recurring work orders.

Source of truth: content/gameplay/regional-content.json. The script is deterministic
and writes only original data/art (programmatically drawn 16x16 icons). Use --check
in CI/tests to prove committed resources match the source definition.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "content/gameplay/regional-content.json"
CONTRACTS = ROOT / "content/gameplay/contracts.json"
CORRIDORS = ROOT / "content/region-blueprints/corridors.json"
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
CHECKPOINT_RADIUS = 48
RES = ROOT / "rpg-core/src/main/resources"
NAMESPACE = "underground_city"
CITIES = ("ironhaven", "valewood", "blackwater")

# (light, mid, dark, accent, outline)
PALETTES = {
    "ironhaven": ((190, 201, 212), (126, 139, 153), (78, 89, 103), (232, 143, 62), (28, 33, 40)),
    "valewood": ((166, 220, 118), (92, 164, 76), (52, 104, 48), (238, 200, 80), (22, 40, 22)),
    "blackwater": ((150, 140, 232), (94, 84, 176), (52, 46, 116), (62, 222, 214), (18, 16, 44)),
    "neutral": ((214, 218, 222), (150, 156, 164), (96, 100, 108), (225, 175, 108), (30, 32, 36)),
}


def load_source() -> dict:
    data = json.loads(SOURCE.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit("Unsupported regional content schema")
    return data


def validate(data: dict) -> None:
    commodity_ids = {c["id"] for c in data["commodities"]}
    product_ids = {p["id"] for p in data["products"]}
    tool_ids = {t["id"] for t in data["tools"]}
    special_ids = {x["id"] for x in data.get("specials", [])}
    all_ids = commodity_ids | product_ids | tool_ids | special_ids
    if len(all_ids) != len(commodity_ids) + len(product_ids) + len(tool_ids) + len(data.get("specials", [])):
        raise SystemExit("Duplicate item IDs in regional content")
    for x in data.get("specials", []):
        if x["kind"] not in ("seed", "product", "tool", "rare") or x["city"] not in CITIES:
            raise SystemExit(f"Invalid special item {x['id']}")
        for key, count in x.get("inputs", {}).items():
            if ":" not in key and key not in commodity_ids:
                raise SystemExit(f"{x['id']} consumes unknown commodity {key}")
            if not 1 <= count <= 9:
                raise SystemExit(f"{x['id']} has an invalid input count")
        if x.get("inputs") and not 1 <= x.get("count", 1) <= 8:
            raise SystemExit(f"{x['id']} has an invalid output count")
        if "shortcut" in x:
            sc = x["shortcut"]
            if sc["result"] not in commodity_ids or not 1 <= sc["count"] <= 8:
                raise SystemExit(f"{x['id']} shortcut must unpack into a regional commodity")
    prices = {c["id"]: c["stock"]["unit_price"] for c in data["commodities"]}
    for c in data["commodities"]:
        s = c["stock"]
        if c["city"] not in CITIES or not (1 <= s["initial"] <= s["capacity"] <= 64 * 4):
            raise SystemExit(f"Invalid commodity {c['id']}")
        if s["units_per_interval"] < 1 or s["interval_minutes"] < 1 or s["unit_price"] < 1 or not 1 <= s["max_order"] <= 64:
            raise SystemExit(f"Invalid stock numbers for {c['id']}")
    cost_per_product = {}
    for p in data["products"]:
        cost = 0
        for key, count in p["inputs"].items():
            if ":" in key:
                continue  # ordinary vanilla goods are never gated or priced here
            if key not in commodity_ids:
                raise SystemExit(f"{p['id']} consumes unknown commodity {key}")
            cost += prices[key] * count
        cost_per_product[p["id"]] = cost / p["count"]
    lo, hi = data["economy_rules"]["minimum_margin"], data["economy_rules"]["maximum_margin"]
    seen = set()
    for o in data["orders"]:
        if o["id"] in seen or o["career"] not in ("farmer", "smith", "gunsmith", "technician"):
            raise SystemExit(f"Invalid order {o['id']}")
        seen.add(o["id"])
        if not 0 <= o["foreign_visits"] <= 2:
            raise SystemExit(f"Invalid visit gate {o['id']}")
        cost = 0.0
        for item, count in o["requirements"].items():
            if item not in product_ids:
                raise SystemExit(f"{o['id']} requires unknown product {item}")
            cost += cost_per_product[item] * count
        margin = o["credits"] / cost
        if not lo <= margin <= hi:
            raise SystemExit(f"{o['id']} margin {margin:.2f} outside {lo}-{hi}")


SECURITY_LOCKPICKS = {"low": 1, "medium": 2, "high": 3, "faction": 4}


def load_property() -> tuple[dict, dict]:
    policies = json.loads((ROOT / "content/property/restock-policies.json").read_text(encoding="utf-8"))
    loot = json.loads((ROOT / "content/property/loot-tables.json").read_text(encoding="utf-8"))
    ids = {p["id"] for p in policies["policies"]}
    if set(loot["tables"]) != ids:
        raise SystemExit("Every property policy needs exactly one loot table")
    for policy in policies["policies"]:
        if not (1 <= policy["capacity"] <= 9 and 0 <= policy["initial_stock"] <= policy["capacity"]
                and policy["security"] in SECURITY_LOCKPICKS and policy["items_per_interval"] >= 1):
            raise SystemExit(f"Invalid property policy {policy['id']}")
    return policies, loot


def lootable_item_ids(data: dict) -> set[str]:
    return {f"{NAMESPACE}:{e['id']}" for e in data["commodities"] + data["products"] + data["tools"] + data.get("specials", [])}


def load_contracts() -> dict:
    data = json.loads(CONTRACTS.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit("Unsupported contracts schema")
    return data


def _json_dir(name: str) -> dict[str, dict]:
    return {path.stem: json.loads(path.read_text(encoding="utf-8")) for path in sorted((DATA / name).glob("*.json"))}


def unit_costs(data: dict, contracts: dict) -> dict[str, float]:
    """Credit cost of one unit of every priced item, for margin checks. Vanilla goods inside a recipe are free
    by convention (ordinary goods are never gated); standalone vanilla and gun requirements use reference values."""
    refs = contracts["reference_values"]
    prices = {f"{NAMESPACE}:{c['id']}": c["stock"]["unit_price"] for c in data["commodities"]}
    costs = dict(prices)
    for p in data["products"] + data.get("specials", []):
        if p.get("inputs"):
            total = sum(prices[f"{NAMESPACE}:{k}"] * n for k, n in p["inputs"].items() if ":" not in k)
            costs[f"{NAMESPACE}:{p['id']}"] = total / p["count"]
    costs.update(refs["harvest_units"])
    costs.update(refs["vanilla"])
    return costs


def contract_cost(contract: dict, costs: dict[str, float], guns: dict[str, float]) -> float:
    total = 0.0
    for req in contract["requirements"]:
        if req.get("gun_ids"):
            total += req["count"] * min(guns[g] for g in req["gun_ids"])
        elif req["item"] in costs:
            total += req["count"] * costs[req["item"]]
        else:
            raise SystemExit(f"{contract['id']}: no reference cost for {req['item']}")
    return total


def validate_contracts(data: dict, contracts: dict) -> None:
    skills = {}
    for career, tree in _json_dir("skill_trees").items():
        for node in tree["nodes"]:
            skills[node["id"]] = (career, node["branch"])
    buyers = _json_dir("buyers")
    contraband = _json_dir("contraband")
    licensed_guns = {g for cat in contraband.values() if cat["category"] == "firearms_licensed"
                     for e in cat["entries"] for g in e.get("values", [])}
    restricted_cultivar = {e["item"] for e in contraband["restricted_cultivar"]["entries"]}
    legal_lo, legal_hi = contracts["economy_rules"]["legal_margin"]
    ug_lo, ug_hi = contracts["economy_rules"]["underground_nominal_margin"]
    costs = unit_costs(data, contracts)
    guns = contracts["reference_values"]["guns"]
    reward_values = contracts["reference_values"]["rewards"]
    existing = {p.stem for p in (DATA / "work_orders").glob("*.json")} - {c["id"] for c in contracts["contracts"]}
    seen = set()
    for c in contracts["contracts"]:
        cid = c["id"]
        if cid in seen or cid in existing:
            raise SystemExit(f"Duplicate contract id {cid}")
        seen.add(cid)
        if c["career"] not in ("farmer", "smith", "gunsmith", "technician") or not 1 <= c["level"] <= 30 or c["legality"] not in ("legal", "underground"):
            raise SystemExit(f"Invalid contract {cid}")
        underground = c["legality"] == "underground"
        skill = skills.get(c["requires_skill"])
        if skill is None or skill[0] != c["career"]:
            raise SystemExit(f"{cid}: requires_skill {c['requires_skill']} is not a skill of {c['career']}")
        if underground and skill[1] != "underground":
            raise SystemExit(f"{cid}: underground contracts need an underground-branch skill")
        if not underground and skill[1] == "underground":
            raise SystemExit(f"{cid}: legal contracts must not need an underground skill")
        buyer = c.get("buyer")
        if underground and not buyer:
            raise SystemExit(f"{cid}: underground contracts need a buyer in a fixed city")
        if buyer:
            if buyer not in buyers or (buyers[buyer]["legality"] == "underground") != underground:
                raise SystemExit(f"{cid}: buyer {buyer} missing or of the wrong legality")
        if (c.get("min_trust", 0) or c.get("trust_gain", 0)) and not underground:
            raise SystemExit(f"{cid}: trust belongs to underground buyers only")
        if not 1 <= len(c["requirements"]) <= 4 or len(c.get("rewards", [])) > 3:
            raise SystemExit(f"{cid}: 1-4 requirements and at most 3 rewards")
        for req in c["requirements"]:
            if req.get("gun_ids"):
                bad = [g for g in req["gun_ids"] if g not in guns]
                if bad:
                    raise SystemExit(f"{cid}: no reference cost for guns {bad}")
                is_licensed = all(g in licensed_guns for g in req["gun_ids"])
                is_restricted = all(g not in licensed_guns for g in req["gun_ids"])
                if (underground and not is_restricted) or (not underground and not is_licensed):
                    raise SystemExit(f"{cid}: legal gun contracts need licensed guns, underground ones restricted guns")
        cost = contract_cost(c, costs, guns)
        margin = c["credits"] / cost
        lo, hi = (ug_lo, ug_hi) if underground and not c.get("errand") else (legal_lo, legal_hi)
        if c.get("errand") and not underground:
            raise SystemExit(f"{cid}: only underground contracts can be errands")
        if underground and not c.get("errand") and c.get("origin") not in CITIES:
            raise SystemExit(f"{cid}: underground contracts name the origin city whose gates they cross")
        if not lo <= margin <= hi:
            raise SystemExit(f"{cid}: margin {margin:.2f} is outside {lo}-{hi} for a {c['legality']} contract")
        value = sum(reward_values.get(r["item"], 0) * r["count"] for r in c.get("rewards", []))
        if value > 0.9 * c["credits"]:
            raise SystemExit(f"{cid}: rewards worth {value} exceed 90% of the {c['credits']} credits")
        for r in c.get("rewards", []):
            if r["item"] not in reward_values:
                raise SystemExit(f"{cid}: reward {r['item']} has no reference value")
        if not underground and any(req["item"] in restricted_cultivar and not buyer for req in c["requirements"]):
            raise SystemExit(f"{cid}: legal contracts for controlled goods need a licensed buyer")
        if not 1 <= c["credits"] <= 1000 or not 1 <= c["xp"] <= 1000:
            raise SystemExit(f"{cid}: credits and xp are limited to 1000")


# ---------------------------------------------------------------- icon drawing
def _fill(px, boxes, color):
    for x0, y0, x1, y1 in boxes:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if 0 <= x < 16 and 0 <= y < 16:
                    px[x, y] = color + (255,)


def _poly(draw, pts, color):
    draw.polygon(pts, fill=color + (255,))


def icon(kind: str, pal) -> "Image.Image":
    from PIL import Image, ImageDraw

    light, mid, dark, accent, outline = pal[:5]
    sparkle = pal[5] if len(pal) > 5 else None
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    px = img.load()
    if kind == "ingot":
        _poly(d, [(2, 10), (5, 5), (14, 5), (11, 10)], light)
        _fill(px, [(2, 10, 11, 12)], mid)
        _poly(d, [(11, 10), (14, 5), (14, 8), (11, 12)], dark)
        _fill(px, [(4, 7, 11, 7)], (255, 255, 255))
    elif kind == "gear":
        _fill(px, [(7, 1, 8, 14), (1, 7, 14, 8), (3, 3, 12, 12)], mid)
        _fill(px, [(2, 2, 3, 3), (12, 2, 13, 3), (2, 12, 3, 13), (12, 12, 13, 13)], mid)
        _fill(px, [(4, 4, 11, 11)], light)
        _fill(px, [(5, 5, 10, 10)], mid)
        _fill(px, [(7, 7, 8, 8)], dark)
        _fill(px, [(4, 4, 6, 4), (4, 4, 4, 6)], (255, 255, 255))
    elif kind == "fabric":
        _fill(px, [(2, 3, 13, 12)], mid)
        for i in range(0, 12, 3):
            _fill(px, [(2 + i, 3, 3 + i, 12)], light)
        _fill(px, [(2, 7, 13, 8)], accent)
        _fill(px, [(2, 12, 13, 12)], dark)
    elif kind == "seeds":
        for cx, cy, col in [(4, 4, accent), (9, 3, mid), (12, 7, accent), (6, 9, light), (10, 12, mid), (3, 12, accent)]:
            _fill(px, [(cx, cy, cx + 1, cy + 2)], col)
            _fill(px, [(cx, cy, cx, cy)], (255, 255, 255))
    elif kind == "vial":
        _fill(px, [(6, 1, 9, 2), (7, 3, 8, 5)], light)
        _poly(d, [(4, 12), (6, 6), (9, 6), (11, 12)], mid)
        _fill(px, [(4, 9, 11, 13)], mid)
        _fill(px, [(5, 10, 10, 12)], accent)
        _fill(px, [(6, 6, 6, 8)], light)
    elif kind == "crystal":
        _poly(d, [(8, 1), (13, 6), (11, 14), (5, 14), (3, 6)], mid)
        _poly(d, [(8, 1), (3, 6), (8, 8)], light)
        _poly(d, [(8, 8), (13, 6), (11, 14), (8, 14)], dark)
        _fill(px, [(7, 4, 7, 5)], (255, 255, 255))
        _fill(px, [(8, 9, 9, 12)], accent)
    elif kind == "chip":
        _fill(px, [(4, 4, 11, 11)], dark)
        _fill(px, [(5, 5, 10, 10)], mid)
        _fill(px, [(7, 7, 8, 8)], accent)
        for t in (5, 7, 9):
            _fill(px, [(2, t, 3, t), (12, t, 13, t), (t, 2, t, 3), (t, 12, t, 13)], light)
        _fill(px, [(5, 5, 6, 5)], (255, 255, 255))
    elif kind == "disc":
        d.ellipse((1, 1, 14, 14), fill=mid + (255,))
        d.ellipse((2, 2, 9, 9), fill=light + (255,))
        d.ellipse((6, 6, 9, 9), fill=(0, 0, 0, 0))
        _fill(px, [(10, 10, 12, 12)], accent)
        _fill(px, [(4, 3, 5, 3)], (255, 255, 255))
    elif kind == "lens":
        d.ellipse((2, 2, 13, 13), fill=dark + (255,))
        d.ellipse((3, 3, 12, 12), fill=mid + (255,))
        d.ellipse((4, 4, 10, 10), fill=accent + (255,))
        _fill(px, [(5, 5, 6, 6)], (255, 255, 255))
        _fill(px, [(9, 9, 10, 9)], light)
    elif kind == "kit":
        _fill(px, [(2, 5, 13, 13)], mid)
        _fill(px, [(2, 5, 13, 6)], light)
        _fill(px, [(6, 3, 9, 4)], dark)
        _fill(px, [(7, 7, 8, 12), (5, 9, 10, 10)], accent)
        _fill(px, [(2, 13, 13, 13)], dark)
    elif kind == "module":
        _fill(px, [(3, 2, 12, 13)], mid)
        for y in (4, 7, 10):
            _fill(px, [(4, y, 11, y)], dark)
            _fill(px, [(5, y + 1, 5, y + 1), (9, y + 1, 9, y + 1)], accent)
        _fill(px, [(3, 2, 12, 2)], light)
    elif kind == "cube":
        _poly(d, [(8, 1), (14, 4), (8, 7), (2, 4)], light)
        _poly(d, [(2, 4), (8, 7), (8, 14), (2, 11)], mid)
        _poly(d, [(8, 7), (14, 4), (14, 11), (8, 14)], dark)
        _fill(px, [(10, 8, 12, 9)], accent)
    elif kind == "wrench":
        _poly(d, [(3, 12), (4, 13), (12, 5), (11, 4)], mid)
        d.ellipse((9, 1, 14, 6), fill=light + (255,))
        d.ellipse((11, 2, 12, 3), fill=(0, 0, 0, 0))
        _fill(px, [(2, 12, 4, 14)], accent)
    elif kind == "shell":
        d.pieslice((1, 3, 14, 18), 180, 360, fill=mid + (255,))
        _fill(px, [(2, 10, 13, 12)], dark)
        _fill(px, [(4, 6, 6, 7)], light)
        _fill(px, [(7, 11, 8, 11)], accent)
    elif kind == "bracket":
        _fill(px, [(3, 2, 6, 13), (3, 10, 13, 13)], mid)
        _fill(px, [(3, 2, 3, 13)], light)
        _fill(px, [(10, 7, 12, 9)], accent)
        _fill(px, [(4, 11, 4, 11), (8, 11, 8, 11)], dark)
    elif kind == "clipboard":
        _fill(px, [(3, 2, 12, 14)], mid)
        _fill(px, [(4, 4, 11, 13)], light)
        _fill(px, [(6, 1, 9, 3)], dark)
        for y in (6, 8, 10):
            _fill(px, [(5, y, 10, y)], dark)
        _fill(px, [(5, 12, 7, 12)], accent)
    elif kind == "plate":
        _fill(px, [(1, 5, 14, 11)], mid)
        _fill(px, [(1, 5, 14, 6)], light)
        _fill(px, [(1, 11, 14, 11)], dark)
        for x in (3, 7, 11):
            _fill(px, [(x, 8, x, 8)], accent)
    elif kind == "box":
        _fill(px, [(2, 3, 13, 13)], mid)
        _fill(px, [(2, 3, 13, 4)], light)
        _fill(px, [(2, 13, 13, 13)], dark)
        _fill(px, [(4, 7, 11, 9)], dark)
        _fill(px, [(10, 6, 11, 6)], accent)
        _fill(px, [(5, 7, 6, 7)], accent)
    elif kind == "dish":
        _poly(d, [(1, 3), (14, 3), (11, 9), (4, 9)], mid)
        _fill(px, [(1, 3, 14, 4)], light)
        _fill(px, [(7, 9, 8, 12)], dark)
        _fill(px, [(4, 13, 11, 14)], dark)
        _fill(px, [(7, 1, 8, 2)], accent)
        _fill(px, [(5, 6, 6, 6)], dark)
    elif kind == "bearing":
        d.ellipse((1, 1, 14, 14), fill=mid + (255,))
        d.ellipse((4, 4, 11, 11), fill=dark + (255,))
        d.ellipse((6, 6, 9, 9), fill=light + (255,))
        for bx, by in ((7, 2), (7, 12), (2, 7), (12, 7)):
            _fill(px, [(bx, by, bx + 1, by + 1)], light)
        _fill(px, [(3, 3, 4, 3)], (255, 255, 255))
        _fill(px, [(11, 11, 12, 12)], accent)
    elif kind == "panel":
        _fill(px, [(1, 1, 14, 14)], dark)
        for x in (2, 8):
            for y in (2, 8):
                _fill(px, [(x, y, x + 4, y + 4)], mid)
                _fill(px, [(x, y, x + 4, y)], light)
        _fill(px, [(5, 5, 5, 5)], accent)
    elif kind == "flask":
        _fill(px, [(6, 1, 9, 2), (7, 3, 8, 6)], light)
        d.ellipse((2, 6, 13, 14), fill=mid + (255,))
        d.ellipse((3, 9, 12, 14), fill=accent + (255,))
        _fill(px, [(4, 8, 5, 9)], (255, 255, 255))
        _fill(px, [(6, 11, 9, 11)], light)
    elif kind == "ledger":
        _fill(px, [(3, 2, 12, 14)], mid)
        _fill(px, [(3, 2, 4, 14)], dark)
        _fill(px, [(5, 3, 11, 13)], light)
        for y in (5, 7, 9):
            _fill(px, [(6, y, 10, y)], dark)
        _fill(px, [(9, 11, 11, 12)], accent)
        _fill(px, [(12, 6, 13, 8)], accent)
    elif kind == "sprout":
        _fill(px, [(7, 8, 8, 14)], mid)
        _poly(d, [(7, 9), (2, 5), (3, 3), (7, 6)], light)
        _poly(d, [(8, 8), (13, 3), (14, 5), (9, 10)], mid)
        _fill(px, [(7, 3, 8, 6)], light)
        _fill(px, [(6, 14, 9, 15)], dark)
        _fill(px, [(3, 4, 3, 4), (7, 3, 7, 3)], (255, 255, 255))
        _fill(px, [(8, 5, 8, 5)], accent)
    elif kind == "bale":
        _fill(px, [(2, 4, 13, 12)], mid)
        for x in (3, 6, 9, 12):
            _fill(px, [(x, 4, x, 12)], light)
        _fill(px, [(2, 4, 13, 5)], light)
        _fill(px, [(2, 12, 13, 12)], dark)
        _fill(px, [(2, 7, 13, 8)], accent)
        _fill(px, [(7, 7, 8, 8)], dark)
    elif kind == "lockpick":
        _poly(d, [(2, 13), (3, 14), (12, 4), (11, 3)], light)
        _fill(px, [(10, 2, 13, 2), (13, 2, 13, 5)], mid)
        _fill(px, [(1, 12, 3, 14)], accent)
    else:
        raise SystemExit(f"Unknown icon kind {kind}")
    if sparkle is not None:
        # Rare finds carry a small gold sparkle in the upper right so they never read as the commodity they unpack into.
        for x, y in ((13, 1), (12, 2), (13, 2), (14, 2), (13, 3)):
            px[x, y] = sparkle + (255,)
    # 1px outline around opaque pixels.
    alpha = img.getchannel("A")
    out = img.copy()
    opx, apx = out.load(), alpha.load()
    for y in range(16):
        for x in range(16):
            if apx[x, y] == 0:
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if 0 <= nx < 16 and 0 <= ny < 16 and apx[nx, ny] > 0:
                        opx[x, y] = outline + (255,)
                        break
    return out


def safe_texture() -> "Image.Image":
    """Original 16x16 steel safe face: panel, dial and handle (no third-party art)."""
    from PIL import Image, ImageDraw

    light, mid, dark, accent, outline = PALETTES["neutral"]
    img = Image.new("RGBA", (16, 16), dark + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=outline + (255,))
    d.rectangle((1, 1, 14, 14), outline=light + (255,))
    d.rectangle((2, 2, 13, 13), fill=mid + (255,))
    d.rectangle((2, 2, 13, 3), fill=light + (255,))
    d.rectangle((2, 13, 13, 13), fill=dark + (255,))
    d.ellipse((5, 4, 11, 10), fill=dark + (255,))
    d.ellipse((6, 5, 10, 9), fill=light + (255,))
    d.ellipse((7, 6, 9, 8), fill=accent + (255,))
    d.rectangle((6, 11, 10, 12), fill=dark + (255,))
    for corner in ((3, 3), (12, 3), (3, 12), (12, 12)):
        d.point(corner, fill=outline + (255,))
    return img


def secure_terminal_texture() -> "Image.Image":
    """Original 16x16 face of the secured data terminal: dark chassis, signal screen with four pad lights, amber LED."""
    from PIL import Image, ImageDraw

    shell, rim, screen, glow, amber = (30, 40, 52), (70, 92, 112), (14, 26, 38), (85, 211, 188), (225, 175, 108)
    pads = ((85, 211, 188), (225, 175, 108), (108, 180, 225), (211, 108, 208))
    img = Image.new("RGBA", (16, 16), shell + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle((0, 0, 15, 15), outline=rim + (255,))
    d.rectangle((2, 2, 13, 9), fill=screen + (255,))
    d.rectangle((2, 2, 13, 9), outline=(48, 70, 90, 255))
    d.line([(3, 4), (6, 4)], fill=glow + (255,))
    d.line([(3, 6), (9, 6)], fill=glow + (255,))
    d.point((3, 8), fill=glow + (255,))
    for index, colour in enumerate(pads):
        x = 3 + index * 3
        d.rectangle((x, 11, x + 1, 12), fill=colour + (255,))
    d.rectangle((12, 11, 13, 12), fill=amber + (255,))
    d.rectangle((2, 14, 13, 14), fill=(20, 28, 38, 255))
    return img


def crop_texture(stage: int) -> "Image.Image":
    """Original 16x16 growth stage of the fictional Sable Leaf plant (0 = sprout .. 3 = mature)."""
    from PIL import Image, ImageDraw

    stem, leaf, leaf_light, leaf_dark, bud = (74, 120, 62), (60, 150, 112), (110, 196, 150), (34, 88, 70), (225, 175, 108)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    heights = (4, 8, 12, 15)
    top = 15 - heights[stage]
    d.rectangle((7, top + 1, 8, 15), fill=stem + (255,))
    pairs = (1, 2, 3, 4)[stage]
    for i in range(pairs):
        y = 14 - i * 3 - (0 if stage else 1)
        reach = 2 + min(i + stage, 4)
        d.polygon([(7, y), (7 - reach, y - 2), (7 - reach + 1, y - 3), (7, y - 1)], fill=leaf + (255,))
        d.polygon([(8, y), (8 + reach, y - 2), (8 + reach - 1, y - 3), (8, y - 1)], fill=leaf + (255,))
        d.line([(7, y - 1), (7 - reach + 2, y - 2)], fill=leaf_light + (255,))
        d.line([(8, y - 1), (8 + reach - 2, y - 2)], fill=leaf_dark + (255,))
    d.rectangle((6, top, 9, top + 1), fill=leaf_light + (255,))
    if stage == 3:
        for x, y in ((6, 3), (9, 2), (7, 5), (8, 6)):
            d.point((x, y), fill=bud + (255,))
    return img


def palette_for(entry: dict):
    base = PALETTES[entry.get("city", "neutral")]
    if entry.get("kind") == "rare":
        light, mid, dark, _accent, outline = base
        return (light, mid, dark, (255, 205, 80), outline, (255, 236, 150))
    return base


# --------------------------------------------------------------- file emission
def jdump(obj) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def build(data: dict, contracts: dict) -> dict[Path, bytes]:
    """Return every generated file as relative-to-ROOT path -> bytes."""
    from io import BytesIO

    files: dict[Path, bytes] = {}
    items = []
    lang = {
        "block.underground_city.operations_terminal": "Operations Terminal",
        "item.underground_city.operations_terminal": "Operations Terminal",
        "block.underground_city.property_cache": "Household Safe",
        "item.underground_city.property_cache": "Household Safe",
        "container.underground_city.property_cache": "Household Safe",
        "block.underground_city.secure_data_terminal": "Secured Data Terminal",
        "item.underground_city.secure_data_terminal": "Secured Data Terminal",
    }

    def emit_item(entry, kind, stack):
        iid = entry["id"]
        items.append({"id": iid, "kind": kind, "stack": stack})
        lang[f"item.{NAMESPACE}.{iid}"] = entry["name"]
        files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/{iid}.json")] = jdump(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NAMESPACE}:item/{iid}"}}).encode()
        buf = BytesIO()
        icon(entry["icon"], palette_for(entry)).save(buf, "PNG", optimize=True)
        files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/textures/item/{iid}.png")] = buf.getvalue()

    for c in data["commodities"]:
        emit_item(c, "commodity", 64)
        s = c["stock"]
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/regional_stock/{c['id']}.json")] = jdump({
            "schema_version": 1, "city": c["city"], "item": f"{NAMESPACE}:{c['id']}",
            "unit_price": s["unit_price"], "initial": s["initial"], "capacity": s["capacity"],
            "units_per_interval": s["units_per_interval"], "interval_ticks": s["interval_minutes"] * 1200,
            "max_order": s["max_order"]}).encode()
    for p in data["products"]:
        emit_item(p, "product", 16)
        ingredients = []
        for key, count in p["inputs"].items():
            item_id = key if ":" in key else f"{NAMESPACE}:{key}"
            ingredients.extend({"item": item_id} for _ in range(count))
        if len(ingredients) > 9:
            raise SystemExit(f"{p['id']} recipe exceeds the 3x3 crafting grid")
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/recipes/{p['id']}.json")] = jdump({
            "type": "minecraft:crafting_shapeless", "category": "misc", "group": f"{NAMESPACE}_regional",
            "ingredients": ingredients, "result": {"item": f"{NAMESPACE}:{p['id']}", "count": p["count"]}}).encode()
    for t in data["tools"]:
        emit_item(t, "tool", 16)
        ingredients = []
        for key, count in t["recipe"].items():
            ingredients.extend({"item": key} for _ in range(count))
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/recipes/{t['id']}.json")] = jdump({
            "type": "minecraft:crafting_shapeless", "category": "equipment", "group": f"{NAMESPACE}_tools",
            "ingredients": ingredients, "result": {"item": f"{NAMESPACE}:{t['id']}", "count": t["count"]}}).encode()
    for x in data.get("specials", []):
        emit_item(x, x["kind"], 16 if x["kind"] in ("rare", "tool") else 64)
        if x.get("inputs"):
            ingredients = []
            for key, count in x["inputs"].items():
                item_id = key if ":" in key else f"{NAMESPACE}:{key}"
                ingredients.extend({"item": item_id} for _ in range(count))
            if len(ingredients) > 9:
                raise SystemExit(f"{x['id']} recipe exceeds the 3x3 crafting grid")
            files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/recipes/{x['id']}.json")] = jdump({
                "type": "minecraft:crafting_shapeless", "category": "misc", "group": f"{NAMESPACE}_restricted",
                "ingredients": ingredients, "result": {"item": f"{NAMESPACE}:{x['id']}", "count": x["count"]}}).encode()
        if "shortcut" in x:
            files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/recipes/{x['id']}_unpack.json")] = jdump({
                "type": "minecraft:crafting_shapeless", "category": "misc", "group": f"{NAMESPACE}_rare_finds",
                "ingredients": [{"item": f"{NAMESPACE}:{x['id']}"}],
                "result": {"item": f"{NAMESPACE}:{x['shortcut']['result']}", "count": x["shortcut"]["count"]}}).encode()
    cdefaults = contracts["defaults"]
    for c in contracts["contracts"]:
        document = {"schema_version": 3, "title": c["title"], "career": c["career"], "level": c["level"], "foreign_visits": 0,
                    "requirements": [], "credits": c["credits"], "xp": c["xp"],
                    "capacity": c.get("capacity", cdefaults["capacity"]), "refill_ticks": c.get("refill_ticks", cdefaults["refill_ticks"]),
                    "legality": c["legality"], "requires_skill": c["requires_skill"]}
        for req in c["requirements"]:
            entry = {"item": req["item"], "count": req["count"]}
            if req.get("gun_ids"):
                entry["gun_ids"] = req["gun_ids"]
            if req.get("label"):
                entry["label"] = req["label"]
            document["requirements"].append(entry)
        for key in ("buyer", "min_trust", "trust_gain", "requires_mod"):
            if c.get(key):
                document[key] = c[key]
        if c.get("rewards"):
            document["rewards"] = [{k: v for k, v in r.items()} for r in c["rewards"]]
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/work_orders/{c['id']}.json")] = jdump(document).encode()
    crop = f"{NAMESPACE}:sable_leaf_crop"
    variants = {}
    for age in range(4):
        buf = BytesIO()
        crop_texture(age).save(buf, "PNG", optimize=True)
        files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/textures/block/sable_leaf_crop_stage{age}.png")] = buf.getvalue()
        files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/block/sable_leaf_crop_stage{age}.json")] = jdump(
            {"parent": "minecraft:block/crop", "textures": {"crop": f"{NAMESPACE}:block/sable_leaf_crop_stage{age}"}}).encode()
        for nourished in ("false", "true"):
            variants[f"age={age},nourished={nourished}"] = {"model": f"{NAMESPACE}:block/sable_leaf_crop_stage{age}"}
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/blockstates/sable_leaf_crop.json")] = jdump({"variants": variants}).encode()
    mature = {"condition": "minecraft:block_state_property", "block": crop, "properties": {"age": "3"}}
    files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/loot_tables/blocks/sable_leaf_crop.json")] = jdump({
        "type": "minecraft:block", "random_sequence": f"{NAMESPACE}:blocks/sable_leaf_crop",
        "pools": [
            {"rolls": 1, "entries": [{"type": "minecraft:alternatives", "children": [
                {"type": "minecraft:item", "name": f"{NAMESPACE}:sable_leaf_bale", "conditions": [mature],
                 "functions": [{"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 2, "max": 3}}]},
                {"type": "minecraft:item", "name": f"{NAMESPACE}:sable_cutting"}]}]},
            {"rolls": 1, "conditions": [mature, {"condition": "minecraft:random_chance", "chance": 0.4}],
             "entries": [{"type": "minecraft:item", "name": f"{NAMESPACE}:sable_cutting"}]}]}).encode()
    lang["block.underground_city.sable_leaf_crop"] = "Sable Leaf Plant"
    corridors = json.loads(CORRIDORS.read_text(encoding="utf-8"))
    for route in corridors["routes"]:
        for gate in route["checkpoints"]:
            other = route["to"] if gate["faction"] == route["from"] else route["from"]
            files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/checkpoints/{gate['id']}.json")] = jdump({
                "schema_version": 1, "title": f"{gate['faction'].capitalize()} gate on the {other.capitalize()} road",
                "jurisdiction": gate["faction"], "dimension": "minecraft:overworld", "x": gate["position"][0], "z": gate["position"][1],
                "radius": CHECKPOINT_RADIUS, "risk": gate["risk"], "route": route["id"]}).encode()
    defaults = data["order_defaults"]
    for o in data["orders"]:
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/work_orders/{o['id']}.json")] = jdump({
            "schema_version": 2, "title": o["title"], "career": o["career"], "level": o["level"],
            "foreign_visits": o["foreign_visits"],
            "requirements": [{"item": f"{NAMESPACE}:{k}", "count": v} for k, v in o["requirements"].items()],
            "credits": o["credits"], "xp": o["xp"], "capacity": defaults["capacity"],
            "refill_ticks": defaults["refill_ticks"]}).encode()
    policies, loot = load_property()
    allowed_items = lootable_item_ids(data)
    for policy in policies["policies"]:
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/property_policies/{policy['id']}.json")] = jdump({
            "schema_version": 1, "capacity": policy["capacity"], "initial_stock": policy["initial_stock"],
            "interval_ticks": policy["interval_minutes"] * 1200, "items_per_interval": policy["items_per_interval"],
            "lockpicks": SECURITY_LOCKPICKS[policy["security"]],
            "loot_table": f"{NAMESPACE}:property/{policy['id']}"}).encode()
    for name, entries in loot["tables"].items():
        pool = []
        for entry in entries:
            namespace, _, _ = entry["item"].partition(":")
            if namespace == NAMESPACE and entry["item"] not in allowed_items:
                raise SystemExit(f"Loot table {name} references unknown Core item {entry['item']}")
            pool.append({"type": "minecraft:item", "name": entry["item"], "weight": entry["weight"], "functions": [
                {"function": "minecraft:set_count", "count": {"min": entry["min"], "max": entry["max"]}}]})
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/loot_tables/property/{name}.json")] = jdump(
            {"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": pool}]}).encode()
    safe_png = BytesIO()
    safe_texture().save(safe_png, "PNG", optimize=True)
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/textures/block/property_cache.png")] = safe_png.getvalue()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/block/property_cache.json")] = jdump(
        {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NAMESPACE}:block/property_cache"}}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/property_cache.json")] = jdump(
        {"parent": f"{NAMESPACE}:block/property_cache"}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/blockstates/property_cache.json")] = jdump(
        {"variants": {"": {"model": f"{NAMESPACE}:block/property_cache"}}}).encode()
    terminal_png = BytesIO()
    secure_terminal_texture().save(terminal_png, "PNG", optimize=True)
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/textures/block/secure_data_terminal.png")] = terminal_png.getvalue()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/block/secure_data_terminal.json")] = jdump(
        {"parent": "minecraft:block/cube_all", "textures": {"all": f"{NAMESPACE}:block/secure_data_terminal"}}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/secure_data_terminal.json")] = jdump(
        {"parent": f"{NAMESPACE}:block/secure_data_terminal"}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/blockstates/secure_data_terminal.json")] = jdump(
        {"variants": {"": {"model": f"{NAMESPACE}:block/secure_data_terminal"}}}).encode()
    blueprint = json.loads((ROOT / "content/region-blueprints/cities.json").read_text(encoding="utf-8"))
    for city in blueprint["cities"]:
        files[Path(f"rpg-core/src/main/resources/data/{NAMESPACE}/regions/{city['id']}.json")] = jdump({
            "schema_version": 1, "id": city["id"], "dimension": blueprint["dimension"],
            "center_chunk": city["center_chunk"], "radius_blocks": city["radius_blocks"]}).encode()
    files[Path(f"rpg-core/src/main/resources/{NAMESPACE}_items.json")] = jdump(
        {"schema_version": 1, "items": items}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/lang/en_us.json")] = jdump(dict(sorted(lang.items()))).encode()
    return files


def same_content(target: Path, expected: bytes) -> bool:
    """Byte equality, except PNGs compare decoded pixels (encoder output varies by Pillow/zlib)."""
    actual = target.read_bytes()
    if target.suffix != ".png":
        return actual == expected
    from io import BytesIO
    from PIL import Image
    a, b = Image.open(BytesIO(actual)).convert("RGBA"), Image.open(BytesIO(expected)).convert("RGBA")
    return a.size == b.size and a.tobytes() == b.tobytes()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if committed files differ")
    parser.add_argument("--sheet", help="write an 8x contact sheet PNG for visual review")
    args = parser.parse_args()
    data = load_source()
    validate(data)
    contracts = load_contracts()
    validate_contracts(data, contracts)
    files = build(data, contracts)
    if args.sheet:
        from PIL import Image
        names = [e for e in data["commodities"] + data["products"] + data["tools"] + data.get("specials", [])]
        sheet = Image.new("RGBA", (8 * 16 * 8, ((len(names) + 7) // 8) * 16 * 8), (60, 64, 72, 255))
        for i, e in enumerate(names):
            tile = icon(e["icon"], palette_for(e)).resize((128, 128), Image.NEAREST)
            sheet.alpha_composite(tile, ((i % 8) * 128, (i // 8) * 128))
        sheet.save(args.sheet)
    drift = []
    for rel, content in files.items():
        target = ROOT / rel
        if args.check:
            if not target.is_file() or not same_content(target, content):
                drift.append(str(rel))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
    if drift:
        print("Generated regional content is stale; run scripts/generate_regional_content.py:")
        for item in drift:
            print(" -", item)
        return 1
    print(("Checked " if args.check else "Wrote ") + f"{len(files)} generated files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
