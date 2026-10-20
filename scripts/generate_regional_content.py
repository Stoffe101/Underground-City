#!/usr/bin/env python3
"""Generate Core regional goods, recipes, supplier stock and recurring work orders.

Source of truth: content/gameplay/regional-content.json. The script is deterministic and writes data
(recipes, stock, orders, policies, loot tables, item models, names). The pixel art is NOT drawn here: every
texture, block model and blockstate comes from tools/art (see tools/art/build_all.py, which has its own
--check). Use --check in CI/tests to prove committed resources match the source definition.
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
        if o["id"] in seen or o["career"] not in ("farmer", "vintner", "smith", "gunsmith", "technician"):
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
# the lockpicking game a safe of each security level plays (data/underground_city/lock_profiles): more pins, a tighter sweet spot, a harsher snap
SECURITY_LOCK_PROFILE = {"low": "deadbolt", "medium": "safe", "high": "commercial", "faction": "high_security"}


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
    costs.update(refs.get("mods", {}))   # third-party goods a contract names (Vinery wine, Tadacko's spirits)
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
        if c["career"] not in ("farmer", "vintner", "smith", "gunsmith", "technician") or not 1 <= c["level"] <= 30 or c["legality"] not in ("legal", "underground"):
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
        "block.underground_city.public_ledger": "Public Ledger",
        "item.underground_city.public_ledger": "Public Ledger",
    }

    def emit_item(entry, kind, stack):
        iid = entry["id"]
        items.append({"id": iid, "kind": kind, "stack": stack})
        lang[f"item.{NAMESPACE}.{iid}"] = entry["name"]
        files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/{iid}.json")] = jdump(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NAMESPACE}:item/{iid}"}}).encode()

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
    # the plant's textures, models and blockstate come from tools/art (gen_crop.py)
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
    lang["key.underground_city.tracker"] = "Show or hide the active order"
    lang["generator.underground_city.archipelago"] = "Underground City: Archipelago"   # the world type list at world creation (D-104)
    lang["key.underground_city.crew"] = "Open the Crew screen"
    lang["key.categories.underground_city"] = "Underground City"
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
            "lock_profile": SECURITY_LOCK_PROFILE[policy["security"]],
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
    # block textures, models and blockstates come from tools/art (gen_blocks.py); the block items point at the block models
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/property_cache.json")] = jdump(
        {"parent": f"{NAMESPACE}:block/property_cache"}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/secure_data_terminal.json")] = jdump(
        {"parent": f"{NAMESPACE}:block/secure_data_terminal"}).encode()
    files[Path(f"rpg-core/src/main/resources/assets/{NAMESPACE}/models/item/public_ledger.json")] = jdump(
        {"parent": f"{NAMESPACE}:block/public_ledger"}).encode()
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
    args = parser.parse_args()
    data = load_source()
    validate(data)
    contracts = load_contracts()
    validate_contracts(data, contracts)
    files = build(data, contracts)
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
