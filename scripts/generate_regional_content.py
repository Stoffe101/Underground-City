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
    all_ids = commodity_ids | product_ids | tool_ids
    if len(all_ids) != len(commodity_ids) + len(product_ids) + len(tool_ids):
        raise SystemExit("Duplicate item IDs in regional content")
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
    return {f"{NAMESPACE}:{e['id']}" for e in data["commodities"] + data["products"] + data["tools"]}


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

    light, mid, dark, accent, outline = pal
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
    elif kind == "lockpick":
        _poly(d, [(2, 13), (3, 14), (12, 4), (11, 3)], light)
        _fill(px, [(10, 2, 13, 2), (13, 2, 13, 5)], mid)
        _fill(px, [(1, 12, 3, 14)], accent)
    else:
        raise SystemExit(f"Unknown icon kind {kind}")
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


def palette_for(entry: dict):
    return PALETTES[entry.get("city", "neutral")]


# --------------------------------------------------------------- file emission
def jdump(obj) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def build(data: dict) -> dict[Path, bytes]:
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
    files = build(data)
    if args.sheet:
        from PIL import Image
        names = [e for e in data["commodities"] + data["products"] + data["tools"]]
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
