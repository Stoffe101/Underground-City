"""The three cities' economies stay different and nothing in between is a trick (twelfth pass): scripts/city_economies.py's rules hold on the committed data, each rule
fails when its data is broken, and the table in docs/technical/city-economies.md is current."""
import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import city_economies as ce  # noqa: E402


class CityEconomies(unittest.TestCase):
    def setUp(self):
        self.cities = ce.Cities()

    def test_every_rule_holds_on_the_committed_data(self):
        self.assertEqual(self.cities.problems(), [])

    def test_a_city_without_its_specialists_buyer_fails(self):
        for oid, order in self.cities.orders.items():
            if order.get("buyer") == "blackwater_towers":
                order["buyer"] = "valewood_council"
        self.assertTrue(any("blackwater has no legal buyer for the technician" in p for p in self.cities.problems()))

    def test_a_shop_that_pays_more_than_an_exchange_charges_is_a_loop(self):
        line = next(line for line in self.cities.lines if line.get("supply"))
        line["supply"] = dict(line["supply"], pays=line["unit_price"])
        self.assertTrue(any(p.startswith("loop:") for p in self.cities.problems()))

    def test_a_necessity_missing_from_one_city_fails(self):
        self.cities.lines = [line for line in self.cities.lines if not (line["item"] == "minecraft:bread" and line["city"] == "valewood")]
        self.assertTrue(any("bread is not sold in valewood" in p for p in self.cities.problems()))

    def test_laws_out_of_order_fail(self):
        self.cities.laws = copy.deepcopy(self.cities.laws)
        self.cities.laws["blackwater"]["witness_chance"] = 0.5
        self.assertTrue(any(p.startswith("laws:") for p in self.cities.problems()))

    def test_a_buyer_with_no_order_fails(self):
        self.cities.buyers["nobody_here"] = {"title": "Nobody", "city": "valewood", "legality": "legal"}
        self.assertIn("buyers: nobody_here has no order", self.cities.problems())

    def test_the_doc_table_is_current(self):
        doc = (ROOT / "docs/technical/city-economies.md").read_text(encoding="utf-8")
        self.assertIn(self.cities.table(), doc, "run python scripts/city_economies.py --update-doc docs/technical/city-economies.md")


if __name__ == "__main__":
    unittest.main()
