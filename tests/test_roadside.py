"""The roadside places (D-138) and the road encounters (D-139): generated data is current, every place is a sound set of four rotations, and every encounter
is optional, bounded and names only things that exist (or a mod it says it needs)."""
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
sys.path.insert(0, str(ROOT / "scripts"))
import roadside_places as places  # noqa: E402

ROADS = {"ironhaven_valewood", "ironhaven_blackwater", "valewood_blackwater"}


class RoadsidePlaces(unittest.TestCase):
    def test_generated_files_are_current(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/roadside_places.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_two_places_on_each_road_with_four_rotations_that_fit_the_lot(self):
        found = [json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / "roadside_places").glob("*.json"))]
        self.assertEqual(len(found), 6)
        for road in ROADS:
            self.assertEqual(len([p for p in found if p["road"] == road]), 2, road)
        for place in found:
            self.assertTrue(0.0 <= place["at"] <= 1.0, place["id"])
            width, depth = place["size"]
            for facing, district_id in place["variants"].items():
                district = json.loads((DATA / "districts" / f"{district_id}.json").read_text(encoding="utf-8"))
                xs = [op[k][0] for op in district["ops"] for k in ("from", "to", "at") if k in op]
                zs = [op[k][2] for op in district["ops"] for k in ("from", "to", "at") if k in op]
                w, d = max(xs) - min(xs) + 1, max(zs) - min(zs) + 1
                expected = (width, depth) if facing in ("north", "south") else (depth, width)
                self.assertEqual((w, d), expected, f"{district_id}: the lot is {w} x {d}, the place says {expected}")
                self.assertEqual(district.get("home_prefix"), f"rs_{place['id']}", district_id)
                self.assertFalse(district.get("repeatable", False), district_id)

    def test_the_places_are_new_world_only_and_sell_their_own_citys_lines(self):
        # the shops' lines carry an outlet each and are never cheaper than the city's own exchange (the GameTests check the latter against the live catalog)
        for path in (DATA / "regional_stock" / "roadside").glob("*.json"):
            line = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(len(line["outlets"]), 1, path.name)
            self.assertTrue(line["outlets"][0].startswith("shop:"), path.name)
            namespace = line["item"].split(":")[0]
            if namespace not in ("minecraft", "underground_city"):
                self.assertEqual(line.get("requires_mod"), namespace, f"{path.name}: a third-party item must name its mod")

    def test_create_is_nowhere(self):
        text = "".join(p.read_text(encoding="utf-8") for p in list((DATA / "districts").glob("rs_*.json")) + list((DATA / "road_encounters").glob("*.json")))
        self.assertNotIn("create:", text)


class RoadEncounters(unittest.TestCase):
    def load(self):
        return [json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / "road_encounters").glob("*.json"))]

    def test_every_kind_is_there_and_every_encounter_is_bounded(self):
        encounters = self.load()
        self.assertEqual({e["kind"] for e in encounters}, {"legit", "underground", "environmental"})
        for e in encounters:
            self.assertRegex(e["id"], r"^[a-z0-9_]{3,40}$")
            for road in e.get("roads", []):
                self.assertIn(road, ROADS, e["id"])
            self.assertTrue(e.get("people") or e.get("props"), f"{e['id']}: nobody and nothing")
            for prop in e.get("props", []):
                along, out, up = prop["at"]
                self.assertTrue(abs(along) <= 4 and 0 <= out <= 4 and 0 <= up <= 2, f"{e['id']}: a prop outside its plot")
                self.assertTrue(prop["block"].startswith("minecraft:"), f"{e['id']}: props are vanilla blocks")
            for choice in e.get("choices", []):
                self.assertNotIn(choice["id"], ("bye", "end"))
                self.assertLessEqual(choice.get("pay", 0), 60, f"{e['id']}: an encounter pays no more than a small order")
                self.assertLessEqual(choice.get("cost", 0), 60)
                self.assertTrue(0 <= choice.get("heat_chance", 0) <= 1)

    def test_underground_offers_stay_off_the_guarded_highway(self):
        for e in self.load():
            if e["kind"] == "underground":
                self.assertTrue(e.get("roads"), f"{e['id']}: an underground offer names its roads")
                self.assertNotIn("ironhaven_valewood", e["roads"], f"{e['id']}: the Ironhaven-Valewood highway is the guarded one")

    def test_loot_and_tags_named_by_encounters_exist(self):
        for e in self.load():
            tables = [c["gives"]["loot"] for c in e.get("choices", []) if "loot" in c.get("gives", {})] + [p["loot"] for p in e.get("props", []) if "loot" in p]
            for table in tables:
                namespace, path = table.split(":", 1)
                self.assertTrue((ROOT / f"rpg-core/src/main/resources/data/{namespace}/loot_tables/{path}.json").exists(), table)
            for c in e.get("choices", []):
                tag = c.get("needs", {}).get("tag")
                if tag:
                    namespace, path = tag.split(":", 1)
                    self.assertTrue((ROOT / f"rpg-core/src/main/resources/data/{namespace}/tags/items/{path}.json").exists(), tag)


if __name__ == "__main__":
    unittest.main()
