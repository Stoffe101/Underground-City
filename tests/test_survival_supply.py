"""The survival goods of the exchange and the wayside provisions desks (D-091): generated data is current and obeys the rules that keep the stock small,
the prices sensible and the optional mods optional."""
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import generate_survival_supply as gen  # noqa: E402

SOURCE = gen.load()
LINES = SOURCE["lines"]


class SurvivalSupply(unittest.TestCase):
    def test_source_is_valid(self):
        self.assertEqual(gen.problems(SOURCE), [])

    def test_generated_files_are_current(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate_survival_supply.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_every_third_party_line_is_optional_and_no_vanilla_line_is(self):
        for line in LINES:
            namespace = line["item"].split(":")[0]
            self.assertEqual(line.get("requires_mod", "minecraft"), namespace if namespace != "minecraft" else "minecraft", line["id"])

    def test_create_is_nowhere(self):
        text = SOURCE_TEXT = (ROOT / "content/gameplay/survival-supply.json").read_text(encoding="utf-8")
        self.assertNotIn("create:", text)

    def test_every_city_sells_the_first_flask_and_has_a_wayside_list(self):
        for city in gen.CITIES:
            self.assertTrue([l for l in LINES if l["city"] == city and l["item"] == "thirstwastaken2:waterskin" and "city" in l["outlets"]], city)
            self.assertTrue([l for l in LINES if l["city"] == city and "wayside" in l["outlets"]], city)

    def test_stock_is_finite_and_refills_slowly(self):
        for line in LINES:
            self.assertLessEqual(line["capacity"] * line["price"], 1200, line["id"])
            hourly = line["units_per_interval"] * 60 / line["interval_minutes"]
            self.assertLessEqual(hourly, 24, f"{line['id']} refills {hourly} units an hour")
        # the big purchases refill at most once an hour
        for item in ("travelersbackpack:standard", "thirstwastaken2:iron_flask"):
            for line in LINES:
                if line["item"] == item:
                    self.assertGreaterEqual(line["interval_minutes"], 60, line["id"])

    def test_the_wayside_costs_more_than_the_city(self):
        by_item = {}
        for line in LINES:
            for outlet in line["outlets"]:
                by_item.setdefault(line["item"], {}).setdefault(outlet, []).append(line["price"])
        for item, outlets in by_item.items():
            if "city" in outlets and "wayside" in outlets:
                self.assertGreater(min(outlets["wayside"]), max(outlets["city"]), item)
        self.assertGreater(min(by_item["minecraft:bread"]["wayside"]), gen.STARTER_SHOP_UNIT_PRICES["minecraft:bread"])

    def test_a_backpack_is_the_dearest_everyday_purchase_and_a_sleeping_bag_is_not_free_housing(self):
        price = {l["item"]: l["price"] for l in LINES if "city" in l["outlets"]}
        self.assertGreaterEqual(price["travelersbackpack:standard"], 100)
        self.assertGreaterEqual(price["comforts:sleeping_bag_red"], 40)

    def test_no_medical_item_is_pretended(self):
        text = (ROOT / "content/gameplay/survival-supply.json").read_text(encoding="utf-8")
        self.assertIn("no medical item", text)
        self.assertFalse([l for l in LINES if "bandage" in l["item"] or "medkit" in l["item"]])


if __name__ == "__main__":
    unittest.main()
