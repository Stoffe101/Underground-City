"""The item-source audit (scripts/audit_item_sources.py): the real data has no dead ends, and the audit itself catches the three ways to have one."""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import audit_item_sources as audit  # noqa: E402


class RealData(unittest.TestCase):
    def test_every_work_order_input_has_a_deterministic_source_in_time(self):
        result = audit.audit()
        self.assertEqual([], result["findings"], "an order asks for something a player cannot get: " + json.dumps(result["findings"], indent=1))

    def test_the_restricted_rifle_blueprint_is_the_plain_ak47_and_comes_from_a_component_run_not_from_luck(self):
        orders = audit.load_dir("work_orders")
        pays = [name for name, o in orders.items() if any(r["item"] == "taczblueprints:blueprint_rifle" for r in o.get("rewards", []))]
        self.assertEqual(["dray_rifle_run"], pays)
        reward = orders["dray_rifle_run"]["rewards"][0]
        self.assertIn("tacz:ak47", reward["nbt"], "the M4A1 comes off the Gun Smith Table with a stock fitted and contracts take only plain guns")
        run, brokerage = orders["dray_rifle_run"], orders["arms_brokerage"]
        self.assertLess(run["level"], brokerage["level"], "the blueprint must be earned before the rifle contract opens")
        needed = {r["item"] for r in run["requirements"]}
        self.assertEqual({"underground_city:machined_parts", "underground_city:optical_components"}, needed, "the run is a components run (bought at the exchange, which needs travel)")

    def test_code_sources_are_still_in_the_code(self):
        for item, (file, snippet, _why) in audit.CODE_SOURCES.items():
            source = (ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity" / file).read_text()
            self.assertIn(snippet, source, f"{item} is listed as made by {file}, which no longer contains {snippet!r}")


class DetectsDeadEnds(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        shutil.copytree(audit.DATA, self.tmp / "data")
        self.real = audit.DATA
        audit.DATA = self.tmp / "data"
        self.addCleanup(lambda: (setattr(audit, "DATA", self.real), shutil.rmtree(self.tmp)))

    def order(self, name, **fields):
        base = {"schema_version": 3, "title": name, "career": "smith", "level": 2, "foreign_visits": 0, "credits": 10, "xp": 10, "capacity": 1, "refill_ticks": 100}
        base.update(fields)
        (audit.DATA / "work_orders" / f"{name}.json").write_text(json.dumps(base))

    def kinds(self):
        return {(f["order"], f["kind"]) for f in audit.audit()["findings"]}

    def test_an_item_nothing_makes_is_a_dead_end(self):
        self.order("t_dead", requirements=[{"item": "underground_city:nothing_makes_this", "count": 1}])
        self.assertIn(("t_dead", "DEAD END"), self.kinds())

    def test_an_item_only_a_later_order_pays_is_too_late(self):
        self.order("t_late_reward", level=9, requirements=[{"item": "minecraft:stone", "count": 1}], rewards=[{"item": "underground_city:only_late", "count": 1}])
        self.order("t_early", level=2, requirements=[{"item": "underground_city:only_late", "count": 1}])
        self.assertIn(("t_early", "TOO LATE"), self.kinds())

    def test_an_item_only_a_rolled_table_gives_is_luck_only(self):
        path = audit.DATA / "exceptional_loot" / "industrial.json"
        loot = json.loads(path.read_text())
        loot["entries"].append({"item": "underground_city:lucky_only", "weight": 1})
        path.write_text(json.dumps(loot))
        self.order("t_luck", requirements=[{"item": "underground_city:lucky_only", "count": 1}])
        self.assertIn(("t_luck", "LUCK ONLY"), self.kinds())

    def test_a_gun_nobody_can_get_a_blueprint_for_is_reported_and_one_who_can_is_not(self):
        self.order("t_gun", level=2, requirements=[{"item": "tacz:modern_kinetic_gun", "count": 1, "gun_ids": ["tacz:ak47"]}])
        self.order("t_gun_ok", level=2, requirements=[{"item": "tacz:modern_kinetic_gun", "count": 1, "gun_ids": ["tacz:glock_17"]}])
        kinds = self.kinds()
        self.assertIn(("t_gun", "UNKNOWN GUN"), kinds)
        self.assertNotIn(("t_gun_ok", "UNKNOWN GUN"), kinds)

    def test_an_item_made_from_obtainable_parts_by_a_recipe_is_fine_and_one_made_from_a_dead_end_is_not(self):
        recipe = {"type": "minecraft:crafting_shapeless", "ingredients": [{"item": "underground_city:machined_parts"}, {"item": "minecraft:iron_ingot"}], "result": {"item": "underground_city:t_made"}}
        (audit.DATA / "recipes" / "t_made.json").write_text(json.dumps(recipe))
        recipe["ingredients"].append({"item": "underground_city:nothing_makes_this"})
        recipe["result"] = {"item": "underground_city:t_unmade"}
        (audit.DATA / "recipes" / "t_unmade.json").write_text(json.dumps(recipe))
        self.order("t_made_ok", requirements=[{"item": "underground_city:t_made", "count": 1}])
        self.order("t_unmade_bad", requirements=[{"item": "underground_city:t_unmade", "count": 1}])
        kinds = self.kinds()
        self.assertNotIn(("t_made_ok", "DEAD END"), kinds)
        self.assertIn(("t_unmade_bad", "DEAD END"), kinds)

    def test_another_mods_item_is_external_only_when_the_order_names_that_mod(self):
        self.order("t_ext_ok", requirements=[{"item": "vinery:red_wine", "count": 1}], requires_mod="vinery")
        self.order("t_ext_bad", requirements=[{"item": "vinery:red_wine", "count": 1}])
        kinds = self.kinds()
        self.assertNotIn(("t_ext_ok", "DEAD END"), kinds)
        self.assertIn(("t_ext_bad", "DEAD END"), kinds)


if __name__ == "__main__":
    unittest.main()
