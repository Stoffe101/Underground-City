"""The old quarry (D-123): the pit and its list of veins are generated from one description and checked block by block."""
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import generate_resource_site as quarry  # noqa: E402


class OldQuarry(unittest.TestCase):
    def test_files_are_current(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate_resource_site.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_the_pit_is_sound(self):
        self.assertEqual(quarry.validate(), [])

    def test_only_base_metals_grow_back_and_the_ceiling_holds(self):
        ores = {vein[3] for vein in quarry.VEINS}
        self.assertEqual(ores, {"minecraft:iron_ore", "minecraft:coal_ore", "minecraft:copper_ore"})
        self.assertLessEqual(len(quarry.VEINS) * 60.0 / quarry.INTERVAL_MINUTES, quarry.MAX_ORES_PER_HOUR)
        self.assertGreaterEqual(quarry.INTERVAL_MINUTES, 5)

    def test_every_vein_of_the_site_is_placed_by_the_district(self):
        district = json.loads(quarry.DISTRICT_FILE.read_text(encoding="utf-8"))
        site = json.loads(quarry.SITE_FILE.read_text(encoding="utf-8"))
        placed = {tuple(op["at"]): op["block"] for op in district["ops"] if op["op"] == "block"}
        for vein in site["veins"]:
            self.assertEqual(placed.get(tuple(vein["at"])), vein["block"], vein)
        self.assertEqual(site["district"], district["id"])

    def test_the_district_is_not_repeatable(self):
        # one quarry per world: the resource site finds it by the district's id
        district = json.loads(quarry.DISTRICT_FILE.read_text(encoding="utf-8"))
        self.assertFalse(district.get("repeatable", False))


if __name__ == "__main__":
    unittest.main()


class ValewoodPit(unittest.TestCase):
    """The second site (D-134): sound, bounded, base metals only, placed by its own district, and slower than the old quarry."""

    def test_every_pit_is_sound_and_ids_are_unique(self):
        for pit in quarry.PITS:
            self.assertEqual(pit.validate(), [], pit.site_id)
        self.assertEqual(len({p.site_id for p in quarry.PITS}), len(quarry.PITS))

    def test_the_pit_is_smaller_and_slower_than_the_quarry(self):
        pit, old = quarry.PIT, quarry.QUARRY
        self.assertLess(len(pit.veins) * 60.0 / pit.interval, len(old.veins) * 60.0 / old.interval)
        self.assertEqual({v[3] for v in pit.veins}, {"minecraft:iron_ore", "minecraft:coal_ore", "minecraft:copper_ore"})

    def test_every_vein_of_the_pit_is_placed_by_its_district(self):
        district = json.loads(quarry.PIT_DISTRICT_FILE.read_text(encoding="utf-8"))
        site = json.loads(quarry.PIT_SITE_FILE.read_text(encoding="utf-8"))
        placed = {tuple(op["at"]): op["block"] for op in district["ops"] if op["op"] == "block"}
        for vein in site["veins"]:
            self.assertEqual(placed.get(tuple(vein["at"])), vein["block"], vein)
        self.assertEqual(site["district"], district["id"])
        self.assertFalse(district.get("repeatable", False))
