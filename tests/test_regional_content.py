import json
import sys
import unittest
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import generate_regional_content as gen  # noqa: E402

RES = ROOT / "rpg-core/src/main/resources"
SOURCE = json.loads((ROOT / "content/gameplay/regional-content.json").read_text(encoding="utf-8"))
SUPPLY = json.loads((ROOT / "content/progression/recurring-supply.json").read_text(encoding="utf-8"))
CITIES = json.loads((ROOT / "content/region-blueprints/cities.json").read_text(encoding="utf-8"))


class RegionalContentTests(unittest.TestCase):
    def test_source_validates_and_generated_resources_are_current(self):
        gen.validate(SOURCE)
        stale = [str(rel) for rel, content in gen.build(SOURCE).items()
                 if not (ROOT / rel).is_file() or not gen.same_content(ROOT / rel, content)]
        self.assertEqual(stale, [], "run scripts/generate_regional_content.py")

    def test_no_orphaned_generated_files(self):
        expected = {ROOT / rel for rel in gen.build(SOURCE)}
        for folder, suffixes in (("data/underground_city/regional_stock", {".json"}),
                                 ("data/underground_city/regions", {".json"}),
                                 ("assets/underground_city/textures/item", {".png"}),
                                 ("assets/underground_city/models/item", {".json"})):
            for path in (RES / folder).glob("*"):
                if path.suffix in suffixes and path.name != "operations_terminal.json":
                    self.assertIn(path, expected, f"orphaned generated file {path}")

    def test_city_regions_are_derived_from_the_world_blueprint(self):
        for city in CITIES["cities"]:
            region = json.loads((RES / f"data/underground_city/regions/{city['id']}.json").read_text())
            self.assertEqual(region["center_chunk"], city["center_chunk"])
            self.assertEqual(region["radius_blocks"], city["radius_blocks"])
            self.assertEqual(region["dimension"], CITIES["dimension"])

    def test_stock_matches_the_planning_model_numbers(self):
        planned = {s["id"]: s for s in SUPPLY["supplier_stock"]}
        self.assertEqual(set(planned), {c["id"] for c in SOURCE["commodities"]})
        for c in SOURCE["commodities"]:
            p, s = planned[c["id"]], c["stock"]
            self.assertEqual((c["city"], s["initial"], s["capacity"], s["units_per_interval"], s["interval_minutes"], s["unit_price"]),
                             (p["city"], p["initial_stock"], p["capacity"], p["units_per_interval"], p["interval_minutes"], p["unit_price"]), c["id"])

    def test_products_match_the_twelve_planned_recurring_recipes(self):
        planned = {r["output"]["id"]: r for r in SUPPLY["recurring_recipes"]}
        self.assertEqual(set(planned), {p["id"] for p in SOURCE["products"]})
        for product in SOURCE["products"]:
            recipe = planned[product["id"]]
            commodities = {k: v for k, v in product["inputs"].items() if ":" not in k}
            self.assertEqual(commodities, {i["commodity"]: i["count"] for i in recipe["inputs"]}, product["id"])
            self.assertEqual(product["count"], recipe["output"]["count"], product["id"])

    def test_three_orders_per_career_with_tier_gates(self):
        by_career = {}
        for order in SOURCE["orders"]:
            by_career.setdefault(order["career"], []).append(order)
        self.assertEqual({k: len(v) for k, v in by_career.items()}, {"farmer": 3, "smith": 3, "gunsmith": 3, "technician": 3})
        for orders in by_career.values():
            tiers = sorted(o["foreign_visits"] for o in orders)
            self.assertEqual(tiers, [1, 1, 2], "two tier-2 orders and one tier-3 order per career")

    def test_every_item_has_texture_model_and_name(self):
        lang = json.loads((RES / "assets/underground_city/lang/en_us.json").read_text())
        listing = json.loads((RES / "underground_city_items.json").read_text())
        ids = [e["id"] for e in listing["items"]]
        self.assertEqual(len(ids), 22)
        from PIL import Image
        for item_id in ids:
            self.assertIn(f"item.underground_city.{item_id}", lang)
            model = json.loads((RES / f"assets/underground_city/models/item/{item_id}.json").read_text())
            self.assertEqual(model["textures"]["layer0"], f"underground_city:item/{item_id}")
            image = Image.open(RES / f"assets/underground_city/textures/item/{item_id}.png")
            self.assertEqual(image.size, (16, 16))
            self.assertGreater(image.convert("RGBA").getchannel("A").getextrema()[1], 0, f"blank texture {item_id}")

    def test_recipes_fit_the_crafting_grid_and_are_ungated_for_vanilla_inputs(self):
        for product in SOURCE["products"]:
            recipe = json.loads((RES / f"data/underground_city/recipes/{product['id']}.json").read_text())
            self.assertEqual(recipe["type"], "minecraft:crafting_shapeless")
            self.assertLessEqual(len(recipe["ingredients"]), 9)
            self.assertTrue(any(i["item"].startswith("minecraft:") for i in recipe["ingredients"]),
                            "recipes keep at least one ordinary vanilla ingredient ungated")

    def test_generated_item_icons_are_distinct(self):
        from PIL import Image
        seen = {}
        for entry in SOURCE["commodities"] + SOURCE["products"] + SOURCE["tools"]:
            buf = BytesIO()
            gen.icon(entry["icon"], gen.palette_for(entry)).save(buf, "PNG")
            pixels = Image.open(BytesIO(buf.getvalue())).convert("RGBA").tobytes()
            self.assertNotIn(pixels, seen, f"{entry['id']} duplicates the icon of {seen.get(pixels)}")
            seen[pixels] = entry["id"]


if __name__ == "__main__":
    unittest.main()
