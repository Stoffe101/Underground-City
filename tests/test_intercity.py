import copy
import json
import sys
import tempfile
import unittest
from dataclasses import asdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from validate_intercity import Stock, load, reconcile, validate, withdraw


class InterCityDesignTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = load(ROOT)

    def test_city_graph_border_rules_and_progression_requirements(self):
        self.assertEqual(validate(self.base), [])
        self.assertEqual(len(self.base["routes"]["routes"]), 3)
        self.assertEqual(len(self.base["supply"]["career_milestones"]), 8)

    def test_geometry_rejects_unconnected_and_crossing_third_city(self):
        x = copy.deepcopy(self.base)
        x["routes"]["routes"].pop()
        self.assertTrue(any("triangular network" in err or "Missing cross-city" in err
                            for err in validate(x)))
        x = copy.deepcopy(self.base)
        # Put V→B route through Ironhaven's center.
        x["routes"]["routes"][2]["waypoints"][2] = [0, 0]
        self.assertTrue(any("unrelated city" in err for err in validate(x)))

    def test_short_mandatory_travel_budget_and_nonmandatory_frontier(self):
        travel = self.base["routes"]["transport"]["routine_travel_budget"]
        self.assertLessEqual(travel["maximum_routine_door_to_door_minutes"], 10)
        self.assertTrue(travel["long_frontier_route_optional_until_viable_ferry_or_convoy"])
        self.assertEqual(self.base["routes"]["road_generation"]["reference_seed"], 579)
        oversized = copy.deepcopy(self.base)
        # Deliberately detour an existing mandatory paved road far into the wilderness.
        oversized["routes"]["routes"][0]["waypoints"].insert(2, [500, 4500])
        self.assertTrue(any("estimated mandatory walking journey" in err
                            for err in validate(oversized)))
        unbounded = copy.deepcopy(self.base)
        unbounded["routes"]["transport"]["routine_travel_budget"]["maximum_routine_door_to_door_minutes"] = 15
        self.assertTrue(any("exceeds 10-minute maximum" in err
                            for err in validate(unbounded)))

    def test_bridge_requires_terrain_survey_before_placement(self):
        x = copy.deepcopy(self.base)
        x["routes"]["routes"][0]["crossings"][0]["requires_water_or_ravine_survey"] = False
        self.assertTrue(any("terrain verification" in err for err in validate(x)))

    def test_all_career_tiers_need_multiple_city_inputs(self):
        x = copy.deepcopy(self.base)
        x["supply"]["career_milestones"][0]["requires"] = [
            {"commodity": "rare_seeds", "origin": "valewood"}]
        self.assertTrue(any("cross-city" in err for err in validate(x)))
        x = copy.deepcopy(self.base)
        x["supply"]["career_milestones"][1]["legitimate_paths"] = []
        self.assertTrue(any("peaceful" in err for err in validate(x)))

    def test_remote_purchase_cannot_replace_actual_foreign_city_visit(self):
        x = copy.deepcopy(self.base)
        x["supply"]["career_milestones"][0]["visit_requirement"] = "none"
        self.assertTrue(any("first-hand inter-city travel" in err for err in validate(x)))
        x = copy.deepcopy(self.base)
        x["supply"]["policy"]["require_all_three_city_visits_for_tier_3"] = False
        self.assertTrue(any("Progression safety policy" in err for err in validate(x)))
        x = copy.deepcopy(self.base)
        x["supply"]["location_reward_templates"][0]["commodity_pool"] = []
        self.assertTrue(any("every specialty" in err for err in validate(x)))

    def test_conflict_relations_and_safe_options_are_mandatory(self):
        x = copy.deepcopy(self.base)
        x["conflict"]["initial_relations"] = []
        self.assertTrue(any("War relations" in err for err in validate(x)))
        x = copy.deepcopy(self.base)
        x["conflict"]["visitor_rules"]["legitimate_supply_obtainable_without_pvp"] = False
        self.assertTrue(any("safety requirement" in err for err in validate(x)))

    def test_restock_bounds_and_no_per_player_duplicate(self):
        x = copy.deepcopy(self.base)
        x["restock"]["policies"][0]["interval_minutes"] = 1
        self.assertTrue(any("too fast" in err for err in validate(x)))
        x = copy.deepcopy(self.base)
        x["restock"]["one_authoritative_inventory_per_property"] = False
        self.assertTrue(any("shared" in err for err in validate(x)))


class HouseholdReplenishmentTests(unittest.TestCase):
    def test_recent_robbery_has_no_instant_refill_then_partial(self):
        start = Stock(5, 0)
        stolen = withdraw(start, 5)
        self.assertEqual(stolen.count, 0)
        self.assertEqual(reconcile(stolen, capacity=6,
                                   interval_minutes=40, items_per_interval=1,
                                   now=15).count, 0)
        after_one = reconcile(stolen, capacity=6, interval_minutes=40,
                              items_per_interval=1, now=40)
        self.assertEqual(after_one.count, 1)
        after_two_more = reconcile(after_one, capacity=6, interval_minutes=40,
                                   items_per_interval=1, now=125)
        self.assertEqual(after_two_more.count, 3)

    def test_progressive_return_to_max_and_no_hidden_bank(self):
        base = Stock(0, 0)
        full = reconcile(base, capacity=6, interval_minutes=40,
                         items_per_interval=1, now=600)
        self.assertEqual(full.count, 6)
        full = reconcile(full, capacity=6, interval_minutes=40,
                         items_per_interval=1, now=1000)
        theft = withdraw(full, 6)
        self.assertEqual(reconcile(theft, capacity=6, interval_minutes=40,
                                   items_per_interval=1, now=1001).count, 0)

    def test_dormant_property_max_backlog_and_chunk_reconciliation(self):
        # This runs only when the game would load/reconcile a household.
        stock = Stock(0, 0)
        after_year = reconcile(stock, capacity=20, interval_minutes=60,
                               items_per_interval=1, max_backlog_intervals=8,
                               now=525600)
        self.assertEqual(after_year.count, 8)
        self.assertEqual(after_year.last_restock_minute, 525600)
        self.assertEqual(reconcile(after_year, capacity=20, interval_minutes=60,
                                   items_per_interval=1, now=525601).count, 8)

    def test_clock_stops_offline_and_persists_without_rollback(self):
        first = Stock(count=2, last_restock_minute=180)
        # World inactive => active minute remains at 180 even after real-world week.
        self.assertEqual(reconcile(first, capacity=6, interval_minutes=40,
                                   items_per_interval=1, now=180).count, 2)
        serialized = json.dumps(asdict(first))
        restored = Stock(**json.loads(serialized))
        self.assertEqual(restored, first)
        self.assertEqual(reconcile(restored, capacity=6, interval_minutes=40,
                                   items_per_interval=1, now=220).count, 3)

    def test_guards_against_negative_stock_time_and_double_claim(self):
        state = Stock(2, 180)
        for params in [
            {"capacity": 1, "interval_minutes": 40, "items_per_interval": 1, "now": 200},
            {"capacity": 5, "interval_minutes": 40, "items_per_interval": 1, "now": 160},
        ]:
            with self.assertRaises(ValueError):
                reconcile(state, **params)
        with self.assertRaises(ValueError):
            withdraw(state, 3)
        self.assertEqual(withdraw(withdraw(state, 1), 1).count, 0)
        # The runtime still needs a server-side lock and durable event ID.


if __name__ == "__main__":
    unittest.main()
