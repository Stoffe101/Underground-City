import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import check_city_probe as c  # noqa: E402

CHARACTER = {"valewood": {"underground_city:vw_house_a"}}


def chunk(style="underground_city:valewood", building="underground_city:vw_house_a", cover=80, city=True):
    return {"chunk_x": 1, "chunk_z": 2, "city": city, "style": style, "building": building, "covered_columns_at_street_plus_4": cover}


class CheckCityProbeTests(unittest.TestCase):
    def test_a_good_chunk_and_a_street_pass(self):
        self.assertEqual([], c.judge({"chunks": [chunk(), chunk(building="")]}, CHARACTER))

    def test_the_standard_style_is_caught(self):
        self.assertEqual(1, len(c.judge({"chunks": [chunk(style="citystyle_standard")]}, CHARACTER)))

    def test_a_stock_building_is_caught(self):
        self.assertEqual(1, len(c.judge({"chunks": [chunk(building="lostcities:building5")]}, CHARACTER)))

    def test_an_empty_building_is_caught(self):
        self.assertIn("did not generate", c.judge({"chunks": [chunk(cover=0)]}, CHARACTER)[0])

    def test_a_chunk_outside_every_city_is_ignored(self):
        self.assertEqual([], c.judge({"chunks": [chunk(city=False, style="", building="")]}, CHARACTER))


if __name__ == "__main__":
    unittest.main()
