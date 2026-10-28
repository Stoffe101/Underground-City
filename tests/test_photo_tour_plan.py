"""The private photo tour's plan (scripts/photo_tour_plan.py) on an invented staged pack: shots at the street door and the spots inside, the most likely
building of each use first, and never written inside a checkout."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import photo_tour_plan as plan  # noqa: E402


class PhotoTourPlanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.pack = Path(self.tmp.name) / "pack"
        plans = self.pack / "data" / "fixturecity" / "city_buildings"
        plans.mkdir(parents=True)
        for name, kinds in (("cottage", ["door", "bed", "stove", "bath"]), ("hut", ["door", "bed"]), ("store", ["door", "counter", "seat"])):
            (plans / f"{name}.json").write_text(json.dumps({"spots": [{"kind": k, "x": 1, "y": 1, "z": 1} for k in kinds]}), encoding="utf-8")
        self.selection = {"buildings": [
            {"id": "fixturecity:hut", "use": "house", "weight": 0.5},
            {"id": "fixturecity:cottage", "use": "house", "weight": 3},
            {"id": "fixturecity:store", "use": "shop", "weight": 1}]}

    def tearDown(self):
        self.tmp.cleanup()

    def test_the_street_door_and_each_spot_inside_with_the_likeliest_building_first(self):
        shots = plan.tour(self.pack, self.selection, 1)["shots"]
        self.assertEqual([s["name"] for s in shots], ["house1-street", "house1-bed", "house1-stove", "house1-bath", "shop1-street", "shop1-seat", "shop1-counter"])
        self.assertTrue(all(s["building"] == "fixturecity:cottage" for s in shots[:4]), "the house of the larger weight is photographed")
        street = shots[0]
        self.assertEqual((street["spot"], street["back"], street["eye"]), ("door", 9, 5), "the street door from across the street")
        self.assertTrue(all(s["back"] == 4 for s in shots if s["spot"] != "door"), "inside, from a room's width away")
        self.assertFalse(any("cottage" in s["name"] or "store" in s["name"] for s in shots), "a shot's name never names a building")

    def test_the_tour_is_never_written_inside_a_checkout(self):
        out = ROOT / "build" / "tour-test.json"
        with self.assertRaises(SystemExit):
            plan.main([str(self.pack), str(Path(self.tmp.name) / "selection.json"), str(out)])
        self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
