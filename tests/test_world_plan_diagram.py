import json
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from render_world_plan import build_svg


class RoutePreviewTests(unittest.TestCase):
    def test_schematic_has_all_cities_and_only_expected_provisional_bridges(self):
        world = json.loads((ROOT / "content/region-blueprints/cities.json").read_text())
        corridors = json.loads((ROOT / "content/region-blueprints/corridors.json").read_text())
        preview = build_svg(world, corridors)
        image = ET.fromstring(preview)
        ns = "{http://www.w3.org/2000/svg}"
        self.assertEqual(image.tag, ns+"svg")
        self.assertEqual(len(image.findall(".//"+ns+"polyline")), 6)
        self.assertEqual(len(image.findall(".//"+ns+"rect")), 3)  # background + 2 bridge diamonds
        for city in ("Ironhaven", "Valewood", "Blackwater"):
            self.assertIn(city, preview)
        self.assertIn("Roads built: 0", preview)
        self.assertIn("NOT", preview)


if __name__ == "__main__":
    unittest.main()
