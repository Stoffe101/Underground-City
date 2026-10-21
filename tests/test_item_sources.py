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




class MonsterDrops(unittest.TestCase):
    """D-095: the simulation world has no hostile creatures, so what only they drop needs a route, and the audit notices when one is missing."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        shutil.copytree(audit.DATA, self.tmp / "data")
        self.real = audit.DATA
        audit.DATA = self.tmp / "data"
        self.addCleanup(lambda: (setattr(audit, "DATA", self.real), shutil.rmtree(self.tmp)))

    def catalogue_path(self):
        return audit.DATA / "progression/monster_resources.json"

    def edit_catalogue(self, change):
        data = json.loads(self.catalogue_path().read_text())
        change(data)
        self.catalogue_path().write_text(json.dumps(data))

    def monster_kinds(self):
        return {(f["kind"], f["item"]) for f in audit.audit()["findings"]}

    def test_the_real_catalogue_has_a_route_for_every_root_anything_needs(self):
        data = audit.monster_catalogue()
        needed = {"minecraft:gunpowder", "minecraft:string", "minecraft:slime_ball", "minecraft:ender_pearl", "minecraft:blaze_rod", "minecraft:blaze_powder", "minecraft:spider_eye"}
        for item in needed:
            self.assertTrue(data["roots"][item]["route"], item)
        self.assertEqual([], [f for f in audit.audit()["findings"] if f["kind"] in ("MONSTER ONLY", "BROKEN ROUTE")])

    def test_every_listed_root_without_a_route_says_nothing_needs_it(self):
        for item, root in audit.monster_catalogue()["roots"].items():
            if not root.get("route"):
                self.assertIs(root.get("needed_by_approved_gameplay"), False, item)

    def test_a_route_to_an_exchange_line_that_does_not_exist_is_caught(self):
        (audit.DATA / "regional_stock/survival/string_valewood.json").unlink()
        (audit.DATA / "regional_stock/survival/string_ironhaven.json").unlink()
        self.assertIn(("BROKEN ROUTE", "minecraft:string"), self.monster_kinds())

    def test_a_line_that_sells_something_else_or_a_stockpile_is_caught(self):
        path = audit.DATA / "regional_stock/survival/slime_ball_ironhaven.json"
        line = json.loads(path.read_text())
        line["capacity"] = 256
        path.write_text(json.dumps(line))
        self.assertIn(("BROKEN ROUTE", "minecraft:slime_ball"), self.monster_kinds())
        line.update(capacity=4, item="minecraft:gold_ingot")
        path.write_text(json.dumps(line))
        self.assertIn(("BROKEN ROUTE", "minecraft:slime_ball"), self.monster_kinds())

    def test_a_derived_root_whose_source_has_no_route_is_caught(self):
        self.edit_catalogue(lambda d: d["roots"]["minecraft:blaze_rod"].update(route=None, needed_by_approved_gameplay=False))
        self.assertIn(("BROKEN ROUTE", "minecraft:blaze_powder"), self.monster_kinds())

    def test_a_work_order_that_needs_an_unrouted_drop_is_caught(self):
        self.edit_catalogue(lambda d: d["roots"]["minecraft:gunpowder"].update(route=None, needed_by_approved_gameplay=False))
        (audit.DATA / "work_orders/t_powder.json").write_text(json.dumps({"schema_version": 3, "title": "t", "career": "smith", "level": 2, "foreign_visits": 0, "credits": 10, "xp": 10,
                                                                          "capacity": 1, "refill_ticks": 100, "requirements": [{"item": "minecraft:gunpowder", "count": 4}]}))
        self.assertIn(("MONSTER ONLY", "minecraft:gunpowder"), self.monster_kinds())

    def test_a_backpack_item_that_needs_an_unrouted_drop_is_caught(self):
        self.edit_catalogue(lambda d: d["roots"]["minecraft:ender_pearl"].update(route=None, needed_by_approved_gameplay=False))
        self.assertIn(("MONSTER ONLY", "minecraft:ender_pearl"), self.monster_kinds())

    def test_an_unrouted_root_must_say_that_nothing_needs_it(self):
        self.edit_catalogue(lambda d: d["roots"]["minecraft:rotten_flesh"].pop("needed_by_approved_gameplay"))
        self.assertIn(("MONSTER ONLY", "minecraft:rotten_flesh"), self.monster_kinds())

    def test_the_supply_lines_of_the_routes_are_small_slow_and_not_for_hoarding(self):
        for root, definition in audit.monster_catalogue()["roots"].items():
            route = definition.get("route") or {}
            if route.get("kind") != "exchange":
                continue
            for line_id in route["lines"]:
                line = audit.stock_lines()[line_id]
                hourly = line["units_per_interval"] * 72000 / line["interval_ticks"]
                self.assertLessEqual(line["max_order"], 16, line_id)
                self.assertLessEqual(hourly, 20, f"{line_id} refills {hourly} an hour")
                self.assertLessEqual(line["capacity"] * line["unit_price"], 1200, line_id)
        # the rare imports are rare: an ender pearl or a blaze rod is at most about one every hour and a half
        for line_id in ("underground_city:survival/ender_pearl_blackwater", "underground_city:survival/blaze_rod_blackwater"):
            line = audit.stock_lines()[line_id]
            self.assertEqual(1, line["max_order"])
            self.assertLessEqual(line["units_per_interval"] * 72000 / line["interval_ticks"], 0.7, line_id)
            self.assertGreaterEqual(line["unit_price"], 100, line_id)



class OreRoutes(unittest.TestCase):
    """D-120: a world made with the scarcity package has no natural ore, so what is only mined needs a route, and the audit notices when one is missing."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        shutil.copytree(audit.DATA, self.tmp / "data")
        self.real = audit.DATA
        audit.DATA = self.tmp / "data"
        self.addCleanup(lambda: (setattr(audit, "DATA", self.real), shutil.rmtree(self.tmp)))

    def kinds(self):
        return {(f["kind"], f["item"]) for f in audit.audit()["findings"]}

    def edit(self, change):
        path = audit.DATA / "progression/ore_resources.json"
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))

    def test_every_ore_material_the_economy_uses_has_a_route(self):
        data = audit.ore_catalogue()
        for item in ("minecraft:iron_ingot", "minecraft:coal", "minecraft:copper_ingot", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:gold_ingot", "minecraft:diamond", "minecraft:iron_nugget"):
            self.assertTrue(data["roots"][item]["route"], item)
        self.assertEqual([], [f for f in audit.audit()["findings"] if f["kind"] in ("ORE ONLY", "BROKEN ROUTE")])

    def test_every_root_without_a_route_says_nothing_needs_it(self):
        for item, root in audit.ore_catalogue()["roots"].items():
            if not root.get("route"):
                self.assertIs(root.get("needed_by_approved_gameplay"), False, item)

    def test_a_site_route_needs_a_real_site_with_a_vein_of_that_ore(self):
        # the quarry is a second source of iron, coal and copper (D-123): the audit checks the site is really there and really grows the ore
        data = audit.ore_catalogue()
        for item, ore in (("minecraft:iron_ingot", "minecraft:iron_ore"), ("minecraft:coal", "minecraft:coal_ore"), ("minecraft:copper_ingot", "minecraft:copper_ore")):
            self.assertEqual(data["roots"][item]["route"]["also_from_site"], {"id": "ironhaven_quarry", "ore": ore})
        site = audit.DATA / "resource_sites/ironhaven_quarry.json"
        saved = site.read_text()
        site.unlink()
        self.assertIn(("BROKEN ROUTE", "minecraft:coal"), self.kinds())
        content = json.loads(saved)
        content["veins"] = [v for v in content["veins"] if v["block"] != "minecraft:copper_ore"]
        site.write_text(json.dumps(content))
        kinds = self.kinds()
        self.assertIn(("BROKEN ROUTE", "minecraft:copper_ingot"), kinds)
        self.assertNotIn(("BROKEN ROUTE", "minecraft:iron_ingot"), kinds)

    def test_a_route_to_a_missing_line_is_caught(self):
        (audit.DATA / "regional_stock/materials/diamond_blackwater.json").unlink()
        self.assertIn(("BROKEN ROUTE", "minecraft:diamond"), self.kinds())

    def test_a_stockpile_or_another_item_is_caught(self):
        path = audit.DATA / "regional_stock/materials/gold_ingot_blackwater.json"
        line = json.loads(path.read_text())
        line["capacity"] = 200
        path.write_text(json.dumps(line))
        self.assertIn(("BROKEN ROUTE", "minecraft:gold_ingot"), self.kinds())
        line.update(capacity=4, item="minecraft:iron_ingot")
        path.write_text(json.dumps(line))
        self.assertIn(("BROKEN ROUTE", "minecraft:gold_ingot"), self.kinds())

    def test_a_derived_root_whose_source_lost_its_route_is_caught(self):
        self.edit(lambda d: d["roots"]["minecraft:iron_ingot"].update(route=None, needed_by_approved_gameplay=False))
        self.assertIn(("BROKEN ROUTE", "minecraft:iron_nugget"), self.kinds())

    def test_a_work_order_that_needs_an_unrouted_ore_is_caught(self):
        self.edit(lambda d: d["roots"]["minecraft:redstone"].update(route=None, needed_by_approved_gameplay=False))
        (audit.DATA / "work_orders/t_dust.json").write_text(json.dumps({"schema_version": 3, "title": "t", "career": "smith", "level": 2, "foreign_visits": 0, "credits": 10, "xp": 10,
                                                                       "capacity": 1, "refill_ticks": 100, "requirements": [{"item": "minecraft:redstone", "count": 4}]}))
        self.assertIn(("ORE ONLY", "minecraft:redstone"), self.kinds())

    def test_the_material_shelves_are_small_slow_and_the_precious_ones_slower(self):
        lines = {line_id: line for line_id, line in audit.stock_lines().items() if line_id.startswith("underground_city:materials/")}
        self.assertGreaterEqual(len(lines), 10)
        hourly = lambda l: l["units_per_interval"] * 72000 / l["interval_ticks"]
        for line_id, line in lines.items():
            self.assertLessEqual(line["capacity"] * line["unit_price"], 1200, line_id)
            self.assertLessEqual(line["max_order"], 24, line_id)
        diamond = lines["underground_city:materials/diamond_blackwater"]
        self.assertLessEqual(hourly(diamond), 1.0)
        self.assertGreaterEqual(diamond["unit_price"], 100)


if __name__ == "__main__":
    unittest.main()