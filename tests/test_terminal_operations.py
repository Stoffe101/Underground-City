"""Transactional prototypes for Underground City's single in-game terminal."""
import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from simulate_regional_supply import read_json, SCHEMA_FILE
from simulate_terminal_operations import (
    CATALOG_FILE, PROPERTY_FILE, TerminalSimulation, household_loot, validate
)

DATA = read_json(CATALOG_FILE)
SUPPLY = read_json(SCHEMA_FILE)
PROPERTY = read_json(PROPERTY_FILE)


class TerminalCatalogTests(unittest.TestCase):
    def test_all_core_screens_loot_profiles_and_late_raids(self):
        self.assertEqual(validate(DATA, SUPPLY, PROPERTY), [])
        self.assertEqual(len(DATA["terminal"]["screens"]), 5)
        self.assertEqual(len(DATA["loot_profiles"]), 5)

    def test_unsecure_house_loot_never_awards_rare_weapons(self):
        for index in range(200):
            result = household_loot(DATA, "apartment_common",
                                    safe_opened=False, seed=str(index))
            self.assertFalse(any("weapon" in x or "gun" in x
                                 for x in result["items"]))
            result = household_loot(DATA, "apartment_affluent",
                                    safe_opened=False, seed=str(index))
            self.assertNotIn("licensed_weapon_component", result["items"])

    def test_valuable_safe_loot_possible_but_not_guaranteed(self):
        found = set()
        for index in range(1500):
            reward = household_loot(DATA, "illegal_cache",
                                    safe_opened=True, seed=str(index))
            found.update(reward["items"])
        self.assertIn("licensed_weapon_component", found)
        self.assertIn("optical_components", found)

    def test_catalog_rejects_infinite_loot_and_bad_escrow(self):
        bad = copy.deepcopy(DATA)
        bad["loot_profiles"][0]["rare_item_probability_percent"] = 90
        self.assertTrue(any("uncontrolled rare gear" in e for e in validate(bad, SUPPLY, PROPERTY)))
        bad = copy.deepcopy(DATA)
        bad["black_market"]["escrow_before_dropbox_deposit"] = False
        self.assertTrue(any("escrow" in e for e in validate(bad, SUPPLY, PROPERTY)))


class DeliveriesTests(unittest.TestCase):
    def setUp(self):
        self.rules = copy.deepcopy(DATA)
        self.market = TerminalSimulation(self.rules, SUPPLY, world_seed="sample-world")
        self.market.register("a", "ironhaven", "technician", credits=4000, tier=2)
        self.market.supply.travel("a", "blackwater", crossing_approved=True)
        self.market.supply.travel("a", "ironhaven", crossing_approved=True)

    def test_normal_remote_order_is_costlier_and_escrowed_once(self):
        self.rules["shipments"]["incident_chance_percent"] = 0
        m = self.market
        initial = m.supply.players["a"]["credits"]
        stock = m.supply.available("control_circuits").quantity
        order = m.order("a", "control_circuits", 2, "delivery-1",
                        legal=True, permit_verified=True)
        self.assertEqual(order["state"], "in_transit")
        self.assertLess(m.supply.players["a"]["credits"], initial - 2*m.supply.spec["control_circuits"]["unit_price"])
        self.assertEqual(m.supply.available("control_circuits").quantity, stock-2)
        with self.assertRaises(ValueError):
            m.order("a", "control_circuits", 2, "delivery-1",
                    legal=True, permit_verified=True)
        m.advance(49)
        self.assertEqual(m.orders["delivery-1"]["state"], "in_transit")
        m.advance(1)
        self.assertEqual(m.orders["delivery-1"]["state"], "delivered")
        self.assertEqual(m.supply.players["a"]["inventory"]["control_circuits"], 2)
        m.advance(100)
        self.assertEqual(m.supply.players["a"]["inventory"]["control_circuits"], 2)

    def test_legal_crash_can_be_salvaged_or_insured(self):
        self.rules["shipments"]["incident_chance_percent"] = 100
        m = self.market
        first = m.order("a", "control_circuits", 1, "crash-1",
                        legal=True, permit_verified=True)
        self.assertIn(first["incident_type"], ("road_crash", "gang_ambush"))
        m.advance(50)
        self.assertEqual(m.orders["crash-1"]["state"], "recovery_open")
        with self.assertRaises(PermissionError):
            m.salvage("a", "crash-1", at_verified_crash_site=False)
        m.salvage("a", "crash-1", at_verified_crash_site=True)
        with self.assertRaises(PermissionError):
            m.salvage("a", "crash-1", at_verified_crash_site=True)
        self.assertEqual(m.supply.players["a"]["inventory"]["control_circuits"], 1)
        m.order("a", "optical_components", 1, "crash-2",
                legal=True, permit_verified=True)
        funds_after_payment = m.supply.players["a"]["credits"]
        m.advance(75)
        self.assertEqual(m.orders["crash-2"]["state"], "insured")
        self.assertEqual(m.supply.players["a"]["credits"],
                         funds_after_payment+m.orders["crash-2"]["price"])
        m.advance(100)
        self.assertEqual(m.supply.players["a"]["credits"],
                         funds_after_payment+m.orders["crash-2"]["price"])

    def test_illegal_lost_cargo_expires_and_is_confiscated(self):
        self.rules["shipments"]["incident_chance_percent"] = 100
        m = self.market
        m.order("a", "control_circuits", 1, "illegal-1",
                legal=False, permit_verified=False)
        paid = m.supply.players["a"]["credits"]
        m.advance(75)
        self.assertEqual(m.orders["illegal-1"]["state"], "confiscated")
        self.assertEqual(m.supply.players["a"]["credits"], paid)
        with self.assertRaises(PermissionError):
            m.salvage("a", "illegal-1", at_verified_crash_site=True)

    def test_no_permit_or_unknown_city_contact(self):
        m = TerminalSimulation(DATA, SUPPLY)
        m.register("v", "valewood", "farmer")
        with self.assertRaises(PermissionError):
            m.order("v", "control_circuits", 1, "pre-visit",
                    legal=True, permit_verified=True)
        m.supply.travel("v", "blackwater", crossing_approved=True)
        m.supply.travel("v", "valewood", crossing_approved=True)
        with self.assertRaises(PermissionError):
            m.order("v", "control_circuits", 1, "no-permit",
                    legal=True, permit_verified=False)
        with self.assertRaises(ValueError):
            m.order("v", "control_circuits", 8, "huge-order",
                    legal=True, permit_verified=True)

    def test_delivery_timer_pauses_while_only_partner_plays(self):
        m = self.market
        self.rules["shipments"]["incident_chance_percent"] = 100
        m.register("partner", "valewood", "farmer", credits=1000)
        m.order("a", "control_circuits", 1, "partner-clock",
                legal=True, permit_verified=True)
        m.advance(200, online_players={"partner"})
        self.assertEqual(m.orders["partner-clock"]["state"], "in_transit")
        self.assertEqual(m.owner_online_minutes["a"], 0)
        m.advance(50, online_players={"a", "partner"})
        self.assertEqual(m.orders["partner-clock"]["state"], "recovery_open")
        m.advance(25, online_players={"partner"})
        self.assertEqual(m.orders["partner-clock"]["state"], "recovery_open")
        m.salvage("a", "partner-clock", at_verified_crash_site=True)
        self.assertEqual(m.orders["partner-clock"]["state"], "recovered")

    def test_save_restore_in_transit_and_no_double_delivery(self):
        m = self.market
        self.rules["shipments"]["incident_chance_percent"] = 0
        m.order("a", "control_circuits", 1, "persistent-order",
                legal=True, permit_verified=True)
        rebuilt = TerminalSimulation.restore(self.rules, SUPPLY, json.loads(json.dumps(m.snapshot())),
                                            world_seed="sample-world")
        rebuilt.advance(50)
        self.assertEqual(rebuilt.orders["persistent-order"]["state"], "delivered")
        rebuilt.advance(100)
        self.assertEqual(rebuilt.supply.players["a"]["inventory"]["control_circuits"], 1)


class DropboxAndRaidsTests(unittest.TestCase):
    def setUp(self):
        self.model = TerminalSimulation(DATA, SUPPLY)
        self.model.register("seller", "blackwater", "gunsmith",
                            credits=300, tier=3)
        self.model.give_item("seller", "weapon-nbt-uuid-9", "gun_equipment", quality=79)

    def test_market_sale_escrow_and_single_seller_payout(self):
        m = self.model
        price = 650
        listing = m.list_item("seller", "weapon-nbt-uuid-9", price, "listing-1")
        self.assertEqual(listing["guide_price"], 500)
        with self.assertRaises(ValueError):
            m.list_item("seller", "weapon-nbt-uuid-9", 700, "listing-2")
        with self.assertRaises(PermissionError):
            m.accept_buyer("listing-1", marker_id="box-1", marker_approved=False)
        buyer_before = m.buyer_budget
        m.accept_buyer("listing-1", marker_id="box-1", marker_approved=True)
        self.assertEqual(m.buyer_budget, buyer_before-price)
        with self.assertRaises(PermissionError):
            m.deposit("seller", "listing-1", at_approved_box=False)
        original = dict(m.items["seller"]["weapon-nbt-uuid-9"])
        m.deposit("seller", "listing-1", at_approved_box=True)
        self.assertNotIn("weapon-nbt-uuid-9", m.items["seller"])
        self.assertEqual(m.dropboxes["box-1"]["held_item"], original)
        credits = m.supply.players["seller"]["credits"]
        m.collect_money("seller", "listing-1")
        self.assertEqual(m.supply.players["seller"]["credits"], credits+price)
        with self.assertRaises(PermissionError):
            m.collect_money("seller", "listing-1")
        self.assertEqual(m.supply.players["seller"]["heat"], 1)

    def test_expired_box_refunds_npc_and_keeps_seller_item(self):
        m = self.model
        m.list_item("seller", "weapon-nbt-uuid-9", 500, "listing-expire")
        budget = m.buyer_budget
        m.accept_buyer("listing-expire", marker_id="approved-box-2",
                       marker_approved=True)
        m.advance(30)
        m.expire_listing("listing-expire")
        self.assertEqual(m.listings["listing-expire"]["state"], "expired")
        self.assertEqual(m.buyer_budget, budget)
        self.assertIn("weapon-nbt-uuid-9", m.items["seller"])
        with self.assertRaises(PermissionError):
            m.deposit("seller", "listing-expire", at_approved_box=True)

    def test_deposited_item_cannot_be_lost_on_expiry(self):
        m = self.model
        m.list_item("seller", "weapon-nbt-uuid-9", 500, "listing-final")
        m.accept_buyer("listing-final", marker_id="approved-box-3",
                       marker_approved=True)
        m.deposit("seller", "listing-final", at_approved_box=True)
        m.advance(300)
        m.expire_listing("listing-final")
        self.assertEqual(m.listings["listing-final"]["state"], "deposited")
        m.collect_money("seller", "listing-final")
        self.assertEqual(m.dropboxes["approved-box-3"]["held_payment"], 0)

    def test_raid_enforces_gear_tier_target_completion_and_cooldown(self):
        m = self.model
        with self.assertRaises(PermissionError):
            m.start_raid("seller", "police_station", "raid-pre", gear_score=30,
                         arrived_at_real_target=True)
        with self.assertRaises(PermissionError):
            m.start_raid("seller", "police_station", "raid-pre", gear_score=99,
                         arrived_at_real_target=False)
        m.start_raid("seller", "police_station", "raid-1", gear_score=100,
                     arrived_at_real_target=True)
        with self.assertRaises(PermissionError):
            m.complete_raid("seller", "raid-1", objective_verified=False)
        m.complete_raid("seller", "raid-1", objective_verified=True)
        with self.assertRaises(PermissionError):
            m.complete_raid("seller", "raid-1", objective_verified=True)
        self.assertGreaterEqual(m.supply.players["seller"]["heat"], 10)
        with self.assertRaises(ValueError):
            m.start_raid("seller", "police_station", "raid-2", gear_score=100,
                         arrived_at_real_target=True)
        m.advance(720)
        m.start_raid("seller", "police_station", "raid-2", gear_score=100,
                     arrived_at_real_target=True)

    def test_save_restore_escrow_item_and_money_while_pending(self):
        m = self.model
        m.list_item("seller", "weapon-nbt-uuid-9", 600, "saved-listing")
        m.accept_buyer("saved-listing", marker_id="saved-safe-box",
                       marker_approved=True)
        m.deposit("seller", "saved-listing", at_approved_box=True)
        reborn = TerminalSimulation.restore(DATA, SUPPLY, json.loads(json.dumps(m.snapshot())))
        reborn.collect_money("seller", "saved-listing")
        with self.assertRaises(PermissionError):
            reborn.collect_money("seller", "saved-listing")
        self.assertEqual(reborn.dropboxes["saved-safe-box"]["held_payment"], 0)


if __name__ == "__main__":
    unittest.main()
