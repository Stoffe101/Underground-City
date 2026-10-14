"""Pure supply-chain tests: actual mod inventory/trading integration is still pending."""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from simulate_regional_supply import (
    SupplySimulation, read_json, validate, SCHEMA_FILE, CITIES_FILE,
    CAREERS_FILE, SPECIALTIES_FILE,
)

DATA = read_json(SCHEMA_FILE)
CITIES = read_json(CITIES_FILE)
CAREERS = read_json(CAREERS_FILE)
SPECIALTIES = read_json(SPECIALTIES_FILE)


class SupplyDesignTests(unittest.TestCase):
    def test_all_origins_and_professions_have_recurring_regional_sinks(self):
        self.assertEqual(validate(DATA, CITIES, CAREERS, SPECIALTIES), [])
        self.assertEqual(len(DATA["supplier_stock"]), 9)
        self.assertEqual(len(DATA["recurring_recipes"]), 12)
        for career in ("farmer", "smith", "gunsmith", "technician"):
            recipes = [r for r in DATA["recurring_recipes"] if r["career"] == career]
            self.assertEqual(len(recipes), 3)
            self.assertEqual({x["tier"] for x in recipes}, {2, 3})

    def test_rejects_local_only_recipes_and_supply_duplicates(self):
        wrong = copy.deepcopy(DATA)
        wrong["recurring_recipes"][0]["inputs"] = [
            {"commodity": "machined_parts", "count": 1},
            {"commodity": "refined_alloy", "count": 1},
        ]
        errors = validate(wrong, CITIES, CAREERS, SPECIALTIES)
        self.assertTrue(any("multiple distinct cities" in e for e in errors))
        wrong = copy.deepcopy(DATA)
        wrong["supplier_stock"][0]["city"] = "blackwater"
        errors = validate(wrong, CITIES, CAREERS, SPECIALTIES)
        self.assertTrue(any("not an export" in e for e in errors))

    def test_rejects_infinite_remote_or_nonrepeatable_crafting(self):
        wrong = copy.deepcopy(DATA)
        wrong["travel_channels"][1]["price_multiplier_percent"] = 50
        self.assertTrue(any("Remote supplier bypass" in e for e in
                            validate(wrong, CITIES, CAREERS, SPECIALTIES)))
        wrong = copy.deepcopy(DATA)
        wrong["recurring_recipes"][0]["repeatable"] = False
        self.assertTrue(any("must remain repeatable" in e for e in
                            validate(wrong, CITIES, CAREERS, SPECIALTIES)))


class RecurringSupplySimulationTests(unittest.TestCase):
    def setUp(self):
        self.model = SupplySimulation(DATA)

    def test_real_intercity_trip_and_repeated_batches_deplete_player_supply(self):
        self.model.register("farmer_player", "valewood", "farmer", credits=1500)
        m = self.model
        with self.assertRaises(PermissionError):
            m.travel("farmer_player", "ironhaven", crossing_approved=False)
        with self.assertRaises(PermissionError):
            m.unlock_tier("farmer_player", 2, external_quest_verified=True)
        m.travel("farmer_player", "ironhaven", crossing_approved=True)
        m.unlock_tier("farmer_player", 2, external_quest_verified=True)
        m.purchase("farmer_player", "machined_parts", 2, "iron-order-1", permit_verified=True)
        m.travel("farmer_player", "valewood", crossing_approved=True)
        m.purchase("farmer_player", "rare_seeds", 4, "farm-order-1", permit_verified=True)
        self.assertEqual(m.shopping_list("farmer_player", "greenhouse_service_batch", 2), {})
        first = m.craft("farmer_player", "greenhouse_service_batch", 2, "craft-job-1")
        self.assertEqual(first["count"], 4)
        self.assertEqual(m.players["farmer_player"]["inventory"]["irrigation_service_kit"], 4)
        needed = m.shopping_list("farmer_player", "greenhouse_service_batch", 2)
        self.assertEqual(needed["ironhaven"]["machined_parts"], 2)
        self.assertEqual(needed["valewood"]["rare_seeds"], 4)
        with self.assertRaisesRegex(ValueError, "Missing city-specific"):
            m.craft("farmer_player", "greenhouse_service_batch", 2, "craft-job-2")
        # The next batch requires real inputs again: no one-time unlock bypass.

    def test_tier_three_needs_all_cities_even_if_materials_bought(self):
        m = self.model
        m.register("gunsmith_player", "blackwater", "gunsmith", credits=1600)
        m.travel("gunsmith_player", "ironhaven", crossing_approved=True)
        m.unlock_tier("gunsmith_player", 2, external_quest_verified=True)
        with self.assertRaises(PermissionError):
            m.unlock_tier("gunsmith_player", 3, external_quest_verified=True)
        with self.assertRaises(PermissionError):
            m.unlock_tier("gunsmith_player", 3, external_quest_verified=False)
        m.travel("gunsmith_player", "valewood", crossing_approved=True)
        m.unlock_tier("gunsmith_player", 3, external_quest_verified=True)
        self.assertEqual(m.players["gunsmith_player"]["tier"], 3)

    def test_shared_stock_between_players_and_bounded_refill(self):
        m = self.model
        m.register("a", "ironhaven", "smith", credits=10000)
        m.register("b", "valewood", "farmer", credits=10000)
        m.travel("b", "ironhaven", crossing_approved=True)
        m.purchase("a", "machined_parts", 8, "a-order", permit_verified=True)
        m.purchase("b", "machined_parts", 8, "b-order", permit_verified=True)
        self.assertEqual(m.available("machined_parts").quantity, 2)
        with self.assertRaisesRegex(ValueError, "temporarily depleted"):
            m.purchase("a", "machined_parts", 3, "a-too-soon", permit_verified=True)
        m.advance_active_time(45, players_online=False)
        self.assertEqual(m.available("machined_parts").quantity, 2)
        m.advance_active_time(45, players_online=True)
        self.assertEqual(m.available("machined_parts").quantity, 6)
        m.purchase("a", "machined_parts", 3, "a-later", permit_verified=True)
        self.assertEqual(m.available("machined_parts").quantity, 3)
        self.assertEqual(m.supplier_revenue["machined_parts"], 19*18)

    def test_transactions_are_atomic_idempotent_and_persist_after_restart(self):
        m = self.model
        m.register("p", "ironhaven", "smith", credits=3000)
        order = m.purchase("p", "refined_alloy", 3, "payment-uuid-1", permit_verified=True)
        funds = m.players["p"]["credits"]
        stock = m.suppliers["refined_alloy"].quantity
        again = m.purchase("p", "refined_alloy", 3, "payment-uuid-1", permit_verified=True)
        self.assertEqual(again["status"], "duplicate")
        self.assertEqual(order["paid"], again["paid"])
        self.assertEqual(m.players["p"]["credits"], funds)
        self.assertEqual(m.suppliers["refined_alloy"].quantity, stock)
        with self.assertRaisesRegex(ValueError, "reused"):
            m.purchase("p", "refined_alloy", 4, "payment-uuid-1", permit_verified=True)
        restored = SupplySimulation.restore(DATA, json.loads(json.dumps(m.snapshot())))
        repeated = restored.purchase("p", "refined_alloy", 3, "payment-uuid-1", permit_verified=True)
        self.assertEqual(repeated["status"], "duplicate")
        self.assertEqual(restored.players["p"]["credits"], funds)
        self.assertEqual(restored.suppliers["refined_alloy"].quantity, stock)

    def test_craft_does_not_partially_consume_and_does_not_duplicate(self):
        m = self.model
        m.register("p", "ironhaven", "smith", credits=9000)
        m.travel("p", "blackwater", crossing_approved=True)
        m.unlock_tier("p", 2, external_quest_verified=True)
        m.purchase("p", "control_circuits", 2, "circuits", permit_verified=True)
        m.travel("p", "ironhaven", crossing_approved=True)
        before = dict(m.players["p"]["inventory"])
        with self.assertRaisesRegex(ValueError, "Missing city-specific"):
            m.craft("p", "calibrated_machine_parts", 2, "premature")
        self.assertEqual(m.players["p"]["inventory"], before)
        m.purchase("p", "refined_alloy", 4, "alloys", permit_verified=True)
        first = m.craft("p", "calibrated_machine_parts", 2, "craft-success")
        after = dict(m.players["p"]["inventory"])
        self.assertEqual(first["count"], 4)
        again = m.craft("p", "calibrated_machine_parts", 2, "craft-success")
        self.assertEqual(again["status"], "duplicate")
        self.assertEqual(m.players["p"]["inventory"], after)
        with self.assertRaisesRegex(ValueError, "reused"):
            m.craft("p", "calibrated_machine_parts", 1, "craft-success")

    def test_no_remote_teleport_buying_and_permit_is_checked(self):
        m = self.model
        m.register("p", "ironhaven", "smith", credits=9000)
        with self.assertRaises(PermissionError):
            m.purchase("p", "control_circuits", 2, "remote-buy", permit_verified=True)
        m.travel("p", "blackwater", crossing_approved=True)
        with self.assertRaises(PermissionError):
            m.purchase("p", "control_circuits", 2, "no-permit", permit_verified=False)
        self.assertEqual(m.players["p"]["inventory"], {})

    def test_clock_capacity_and_corrupted_save_are_bounded(self):
        m = self.model
        m.register("p", "ironhaven", "smith")
        with self.assertRaises(ValueError):
            m.advance_active_time(-1, players_online=True)
        m.advance_active_time(6000, players_online=True)
        refilled = m.available("encrypted_design")
        spec = m.spec["encrypted_design"]
        self.assertLessEqual(refilled.quantity, spec["capacity"])
        copy_state = m.snapshot()
        copy_state["suppliers"]["machined_parts"]["quantity"] = 100000
        with self.assertRaisesRegex(ValueError, "Corrupted"):
            SupplySimulation.restore(DATA, copy_state)


if __name__ == "__main__":
    unittest.main()
