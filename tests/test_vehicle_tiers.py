"""Vehicle and boat tiers (D-101): one priced table, one implemented acquisition path, no claim that is not built.

The tiers are a planning table; what is implemented is a dealer that sells the two Modern Life vehicles. These tests keep the table, the generated dealer lines, the model and the
words honest: prices rise with the tier, a craftable item never costs less than 1.8 times its materials, the dealer lines are generated from the table, and nothing in the table claims
fuel, repair or insurance that the project does not have.
"""
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import economy_model as em  # noqa: E402

SOURCE = ROOT / "content/gameplay/vehicle-tiers.json"
STOCK = ROOT / "rpg-core/src/main/resources/data/underground_city/regional_stock/vehicles"


def tiers():
    return json.loads(SOURCE.read_text(encoding="utf-8"))


class VehicleTierTests(unittest.TestCase):
    def test_the_generated_dealer_lines_are_current_and_valid(self):
        done = subprocess.run([sys.executable, str(ROOT / "scripts/generate_vehicle_supply.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

    def test_prices_rise_with_the_tier_and_a_craftable_item_never_costs_less_than_its_materials(self):
        data = tiers()
        floor = data["pricing_rule"]["materials_multiple_floor"]
        prices = [t["price"] for t in data["tiers"]]
        self.assertEqual(prices, sorted(set(prices)))
        for t in data["tiers"]:
            if t["materials"]:
                m = t["materials"]
                stated = m["iron_ingot_equivalent"] * data["pricing_rule"]["iron_value_credits"] + m["allowance_credits"]
                self.assertAlmostEqual(stated, m["value_credits"], delta=1.0, msg=f"{t['id']}: the stated materials value is not iron x price + allowance")
                self.assertGreaterEqual(t["price"], floor * m["value_credits"], t["id"])

    def test_only_what_is_built_is_sold_and_everything_else_says_it_is_a_proposal(self):
        data = tiers()
        sold = {line["tier"] for line in data["dealer"]}
        for t in data["tiers"]:
            if t["id"] in sold:
                self.assertTrue(t["implemented"] and t["acquisition"] == "dealer", t["id"])
            else:
                self.assertFalse(t["implemented"], t["id"])
                self.assertIn("PROPOSED", t["note"], f"{t['id']} is not sold and must say its price is a proposal")

    def test_dealer_prices_come_from_the_table_in_every_city(self):
        data = tiers()
        by_id = {t["id"]: t for t in data["tiers"]}
        files = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in STOCK.glob("*.json")}
        self.assertEqual(set(files), {line["id"] for line in data["dealer"]})
        for line in data["dealer"]:
            body = files[line["id"]]
            self.assertEqual(body["unit_price"], by_id[line["tier"]]["price"], line["id"])
            self.assertEqual(body["requires_mod"], "modernlife", "a pack without the mod must still load")
            self.assertLessEqual(body["capacity"], 4, "a vehicle shelf is small and shared")

    def test_nothing_claims_fuel_repair_or_insurance(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertIn("FUEL, REPAIR AND INSURANCE ARE NOT IMPLEMENTED", text)
        for forbidden in ("fuel is supplied", "fuel supply", "insured", "gas station"):
            self.assertNotIn(forbidden, text.lower())
        self.assertNotIn("create", {t["mod"] for t in tiers()["tiers"]})

    def test_aircraft_tanks_and_weapons_are_listed_as_not_offered(self):
        kinds = {e["kind"] for e in tiers()["excluded"]}
        self.assertEqual(kinds, {"aircraft", "tank", "weapons"})

    def test_the_audit_totals_match_the_family_table(self):
        audit = tiers()["audit"]
        families = sum(sum(pack.values()) for pack in audit["vehicle_families"].values())
        self.assertEqual(families, audit["immersive_vehicles_items"]["ItemVehicle"])


class TheModelCatchesBadTiers(unittest.TestCase):
    def test_a_tier_that_is_cheaper_than_its_materials_fails(self):
        model = em.Model()
        original = em.VEHICLES.read_text(encoding="utf-8")
        data = json.loads(original)
        data["tiers"][1]["price"] = 300     # a motorboat under 1.8 times the 240 of iron in it (and cheaper than the tier before it must not be)
        em.VEHICLES = ROOT / "build/test-vehicle-tiers.json"
        em.VEHICLES.parent.mkdir(exist_ok=True)
        em.VEHICLES.write_text(json.dumps(data), encoding="utf-8")
        try:
            self.assertTrue(any("buying would beat making" in p for p in em.invariants(model)))
        finally:
            em.VEHICLES = SOURCE

    def test_a_tier_that_takes_the_wrong_number_of_hours_fails(self):
        model = em.Model()
        data = tiers()
        data["tiers"][3]["price"] = 3400 * 3   # a small car at three times the planned wage-hours
        em.VEHICLES = ROOT / "build/test-vehicle-tiers.json"
        em.VEHICLES.parent.mkdir(exist_ok=True)
        em.VEHICLES.write_text(json.dumps(data), encoding="utf-8")
        try:
            self.assertTrue(any("small_car" in p and "active hours" in p for p in em.invariants(model)))
        finally:
            em.VEHICLES = SOURCE


if __name__ == "__main__":
    unittest.main()
