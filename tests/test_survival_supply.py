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
            if namespace in ("minecraft", "underground_city"):
                self.assertNotIn("requires_mod", line, line["id"] + ": a vanilla or Core item is always there")
            else:
                self.assertEqual(line.get("requires_mod"), namespace, line["id"])

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
        # the big purchases refill at most once an hour; the plain backpack, which every newcomer needs (D-092), at most about once in three quarters of an hour per city
        for line in LINES:
            if line["item"] == "thirstwastaken2:iron_flask":
                self.assertGreaterEqual(line["interval_minutes"], 60, line["id"])
            if line["item"] == "sophisticatedbackpacks:backpack":
                self.assertGreaterEqual(line["interval_minutes"], 30, line["id"])

    def test_the_wayside_costs_more_than_the_city(self):
        by_item = {}
        for line in LINES:
            for outlet in line["outlets"]:
                by_item.setdefault(line["item"], {}).setdefault(outlet, []).append(line["price"])
        for item, outlets in by_item.items():
            if "city" in outlets and "wayside" in outlets:
                self.assertGreater(min(outlets["wayside"]), max(outlets["city"]), item)
        self.assertGreater(min(by_item["minecraft:bread"]["wayside"]), gen.STARTER_SHOP_UNIT_PRICES["minecraft:bread"])

    def test_the_first_backpack_is_within_a_starter_budget_and_a_sleeping_bag_is_not_free_housing(self):
        price = {l["item"]: l["price"] for l in LINES if "city" in l["outlets"]}
        # D-092: an ordinary citizen has a basic (leather) backpack early, so it is dearer than a loaf but well inside a first day's wages; only the plain pack is sold, the bigger tiers are made
        self.assertTrue(40 <= price["sophisticatedbackpacks:backpack"] <= 100, price["sophisticatedbackpacks:backpack"])
        self.assertGreater(price["sophisticatedbackpacks:backpack"], gen.STARTER_SHOP_UNIT_PRICES["minecraft:bread"] * 5)
        self.assertGreaterEqual(price["comforts:sleeping_bag_red"], 40)
        self.assertFalse([l for l in LINES if l["item"].startswith("sophisticatedbackpacks:") and l["item"] != "sophisticatedbackpacks:backpack" and "city" in l["outlets"] and not l["item"].endswith("_upgrade") and l["item"] != "sophisticatedbackpacks:upgrade_base"],
                         "only the plain pack and upgrade parts are sold; the bigger packs are crafted from materials")

    def test_the_backpack_provider_is_one_mod(self):
        providers = {l["item"].split(":")[0] for l in LINES if "backpack" in l["item"]}
        self.assertEqual({"sophisticatedbackpacks"}, providers)
        self.assertFalse([l for l in LINES if "travelersbackpack" in l["item"]])
        for line in LINES:
            if line["item"].startswith("sophisticatedbackpacks:"):
                self.assertEqual("sophisticatedbackpacks", line.get("requires_mod"), line["id"])

    def test_the_one_medical_item_is_cores_first_aid_kit_bought_in_every_city_and_at_every_wayside_desk(self):
        # D-203: no medical mod is selected; the medical item is Core's own kit, sold, never free, and dearer on the road
        medical = [l for l in LINES if any(word in l["item"] for word in ("first_aid", "bandage", "medkit", "medical"))]
        self.assertTrue(medical and all(l["item"] == "underground_city:first_aid_kit" for l in medical), [l["item"] for l in medical])
        for city in ("valewood", "ironhaven", "blackwater"):
            for outlet in ("city", "wayside"):
                self.assertTrue([l for l in medical if l["city"] == city and outlet in l["outlets"]], f"{city} sells no kit at its {outlet} outlet")
        city_price = min(l["price"] for l in medical if "city" in l["outlets"])
        self.assertTrue(all(l["price"] >= city_price * 1.2 for l in medical if "wayside" in l["outlets"]), "a wayside kit is not dearer than the city's")
        texture = ROOT / "rpg-core/src/main/resources/assets/underground_city/textures/item/first_aid_kit.png"
        self.assertTrue(texture.is_file(), "the kit has no texture")


if __name__ == "__main__":
    unittest.main()
