"""The economy model (D-098): the shipped data must satisfy every invariant, and each invariant must be able to fail.

The second half deliberately breaks the data in memory (a bread order that pays more than the shop charges, rent that eats the whole income,
a single sale that buys a house, a career with no starting work) and checks that the model notices. A guard that cannot fail is not a guard.
"""
import copy
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import economy_model as em  # noqa: E402


class ShippedData(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model = em.Model()

    def test_every_invariant_holds(self):
        self.assertEqual(em.invariants(self.model), [])

    def test_every_career_has_work_at_level_one_in_the_default_pack(self):
        for career in em.CAREERS:
            with self.subTest(career=career):
                self.assertIsNotNone(self.model.first_payout(career, set())["minutes"], f"{career} has nothing to do at level 1")

    def test_first_payout_is_within_the_target(self):
        limit = self.model.a["targets"]["first_payout_minutes_max"]
        for career in em.CAREERS:
            self.assertLessEqual(self.model.first_payout(career, set())["minutes"], limit, career)

    def test_no_career_runs_out_of_legal_work(self):
        """Apprentice orders fade out (max_level); at no level from 1 to 30 may a career be left with fewer than three legal orders."""
        for career in em.CAREERS:
            for level in range(1, 31):
                orders = self.model.available(career, level, set())
                if level >= 5:
                    self.assertGreaterEqual(len(orders), 3, f"{career} level {level} has {len(orders)} legal orders in Core only")
                else:
                    self.assertGreaterEqual(len(orders), 1, f"{career} level {level} has no order")

    def test_income_never_falls_as_a_career_levels_after_level_four(self):
        for career in em.CAREERS:
            previous = 0.0
            for level in range(4, 31):
                now = self.model.income(career, level, em.ALL_MODS)["net"]
                self.assertGreaterEqual(now + 1e-6, previous * 0.9, f"{career}: income dropped from {previous:.0f} to {now:.0f} at level {level}")
                previous = max(previous, now)

    def test_rent_does_not_accumulate_with_nobody_online(self):
        """The model has no clock without a player: a household with no members earns nothing and pays nothing."""
        sim = em.simulate(self.model, [], set(), hours=10)
        self.assertEqual(sim["final_credits"], 0.0)

    def test_two_members_pay_one_rent(self):
        """A pair earns about twice what one earns but its housing costs are one household's: the flat is reached sooner, not at twice the price."""
        solo = em.simulate(self.model, ["smith"], em.ALL_MODS)["milestones"]["bought_flat"]
        pair = em.simulate(self.model, ["smith", "technician"], em.ALL_MODS)["milestones"]["bought_flat"]
        self.assertLess(pair, solo)

    def test_solo_players_reach_the_house_without_a_crew(self):
        for career in em.CAREERS:
            ms = em.simulate(self.model, [career], em.ALL_MODS, hours=200)["milestones"]
            self.assertIn("bought_flat", ms, career)
            self.assertIn("bought_house", ms, career)
            self.assertGreater(ms["bought_house"], 2 * ms["bought_flat"], f"{career}: the house must be a clearly longer goal than the flat")

    def test_ladder_is_data_driven_and_monotonic(self):
        tiers = sorted(self.model.categories(), key=lambda c: c["tier"])
        self.assertEqual(len(tiers), 10)
        self.assertEqual([c["tier"] for c in tiers], list(range(1, 11)))
        rents = [c["rent_hint"] for c in tiers]
        self.assertEqual(rents, sorted(rents))

    def test_sensitivity_keeps_the_milestones_in_order(self):
        for scale in self.model.a["targets"]["sensitivity"]:
            m = em.Model(scale)
            ms = em.simulate(m, ["smith"], em.ALL_MODS)["milestones"]
            self.assertLess(ms["rented_flat"], ms["bought_flat"])
            self.assertLess(ms["bought_flat"], ms["bought_house"])


class TheGuardsCanFail(unittest.TestCase):
    def fresh(self):
        return em.Model()

    def test_bread_that_beats_the_shop_is_a_loop(self):
        model = self.fresh()
        bread = next(o for o in model.orders if o.id == "bread")
        bread.credits = 46   # the shop sells 8 bread for 40
        self.assertTrue(any("buy-and-deliver loop" in p for p in em.invariants(model)))

    def test_a_shop_that_buys_back_dearer_is_a_loop(self):
        model = self.fresh()
        # an order paying less per unit than the shop pays for the same goods makes nobody deliver
        order = next(o for o in model.orders if o.id == "harvest")
        order.credits = 1
        self.assertTrue(any("shop undercuts an order" in p for p in em.invariants(model)))

    def test_rent_that_eats_the_income_fails(self):
        model = self.fresh()
        for listing in model.ladder["listings"]:
            if listing["id"].endswith(":flat_1"):
                listing["rent"] = 400
                listing["deposit"] = 800
        problems = em.invariants(model)
        self.assertTrue(any("rent share" in p for p in problems), problems)

    def test_a_single_sale_that_buys_a_house_fails(self):
        model = self.fresh()
        model.orders[0].credits = 7000
        model.orders[0].legality = "legal"
        model.orders[0].mod = ""
        problems = em.invariants(model)
        self.assertTrue(any("single sale" in p for p in problems), problems)

    def test_a_career_with_no_starting_work_fails(self):
        model = self.fresh()
        for o in model.orders:
            if o.career == "vintner" and o.level == 1:
                o.level = 3
        self.assertTrue(any("vintner" in p and "level 1" in p for p in em.invariants(model)))

    def test_a_non_monotonic_ladder_fails(self):
        model = self.fresh()
        for c in model.ladder["categories"]:
            if c["id"] == "small_apartment":
                c["rent_hint"] = 5
        self.assertTrue(any("ladder" in p for p in em.invariants(model)))

    def test_a_purchase_cheaper_than_twenty_rents_fails(self):
        model = self.fresh()
        for l in model.ladder["listings"]:
            if l["id"].endswith(":flat_1"):
                l["buy_price"] = 100
        self.assertTrue(any("must cost at least 20 cycles" in p for p in em.invariants(model)))

    def test_a_payout_skill_that_lets_an_apprentice_order_beat_the_shop_fails(self):
        model = self.fresh()
        # bread (38 for goods the shop sells at 40) is offered up to level 6; a 12% skill available at level 2 would push it over the shop price
        for node in model.trees["farmer"]["nodes"]:
            if node["id"] == "farmer.fundamentals":
                node["effects"]["legal_payout_percent"] = 12
        self.assertTrue(any("buy-and-deliver loop" in p and "bread" in p for p in em.invariants(model)))

    def test_a_payout_skill_that_arrives_after_the_apprentice_orders_end_is_harmless(self):
        model = self.fresh()
        self.assertEqual(model.best_percent("farmer", "legal", 6), 0)
        self.assertGreater(model.best_percent("farmer", "legal"), 0)

    def test_item_delivery_that_beats_production_fails(self):
        model = self.fresh()
        for o in model.orders:
            if o.career == "smith" and o.max_level < 99:
                o.credits = 900   # the entry orders now pay far more per hands-on hour than anything the smith makes
        self.assertTrue(any("item delivery would beat production" in p for p in em.invariants(model)))

    def test_an_unlisted_courier_margin_fails(self):
        model = self.fresh()
        model.a["courier_orders"]["ids"].remove("heirloom_seeds")
        self.assertTrue(any("exchange pass-through" in p and "heirloom_seeds" in p for p in em.invariants(model)))

    def test_a_career_far_behind_fails(self):
        model = self.fresh()
        for o in model.orders:
            if o.career == "farmer":
                o.credits = max(1, o.credits // 6)
        self.assertTrue(any("career spread" in p for p in em.invariants(model)))


class AScarcityWorldAndTheFences(unittest.TestCase):
    """The scarcity scenario (D-120) and the fences (D-125): the shipped data holds, and each guard can fail."""

    def test_the_shelf_world_takes_the_mining_out_and_charges_the_shelf(self):
        ore, shelf = em.Model(), em.ScarcityModel("shelf")
        hopper = next(o for o in ore.orders if any(r["item"] == "minecraft:hopper" for r in o.requirements))
        twin = next(o for o in shelf.orders if o.id == hopper.id)
        self.assertGreater(twin.cash, hopper.cash, "hoppers need iron, which a scarcity world sells")
        self.assertLess(twin.hands, hopper.hands, "bought iron needs no mining")
        self.assertIn("shelf:minecraft:iron_ingot", twin.goods_units)

    def test_the_quarry_world_draws_iron_from_the_quarry_and_redstone_from_the_shelf(self):
        quarry = em.ScarcityModel("quarry")
        self.assertGreater(quarry.supply_pool()["quarry:minecraft:iron_ingot"], 0)
        piston = next((o for o in quarry.orders if any(r["item"] == "minecraft:piston" for r in o.requirements)), None)
        if piston:
            self.assertIn("quarry:minecraft:iron_ingot", piston.goods_units)
            self.assertIn("shelf:minecraft:redstone", piston.goods_units)

    def test_a_scarcity_world_without_profitable_first_work_fails(self):
        original = em.ScarcityModel

        class NoSwitches(original):
            # without its two level-1 orders (the switches and the signal lights; D-175 made the lights pay for their shelf redstone) a Technician has no first payout
            def _load_orders(self):
                return [o for o in super()._load_orders() if o.id not in ("switches", "torches")]

        em.ScarcityModel = NoSwitches
        try:
            problems = em.invariants(em.Model())
        finally:
            em.ScarcityModel = original
        self.assertTrue(any("scarcity: technician" in p for p in problems), problems)

    def test_a_fence_that_pays_more_than_an_honest_price_fails(self):
        model = em.Model()
        found, values = em.fences(model)
        model.fence_override = (found, dict(values, **{"minecraft:iron_ingot": 30}))
        self.assertTrue(any("launder" in p and "iron_ingot" in p for p in em.invariants(model)))

    def test_a_fence_that_out_earns_legal_work_fails(self):
        model = em.Model()
        found, values = em.fences(model)
        rich = [dict(f, refill_credits=f["refill_credits"] * 40) for f in found]
        model.fence_override = (rich, values)
        self.assertTrue(any("of the best legal hour" in p for p in em.invariants(model)))


class APlayersCounterAndShortageContracts(unittest.TestCase):
    """The counter's tax, its price ceiling and its sales an hour, and a shortage contract's pay (D-207, D-208): each guard fails on numbers made to break it."""

    def setUp(self):
        self.saved = (em.BUSINESS_TAX, em.BUSINESS_MIN_TAX, em.BUSINESS_SALES_PER_HOUR, em.CONTRACT_PAY)

    def tearDown(self):
        em.BUSINESS_TAX, em.BUSINESS_MIN_TAX, em.BUSINESS_SALES_PER_HOUR, em.CONTRACT_PAY = self.saved

    def test_the_shipped_numbers_hold(self):
        self.assertEqual(em.business_problems(em.Model()), [])

    def test_a_tax_no_larger_than_the_discounts_is_a_loop(self):
        em.BUSINESS_TAX, em.BUSINESS_MIN_TAX = 0.10, 1
        self.assertTrue(any("a loop" in p for p in em.business_problems(em.Model())))

    def test_a_counter_that_out_sells_legal_work_fails(self):
        em.BUSINESS_SALES_PER_HOUR = 12
        self.assertTrue(any("best legal hour" in p for p in em.business_problems(em.Model())))

    def test_a_contract_never_reaches_the_shops_price_however_generous_its_rate(self):
        em.CONTRACT_PAY = 3.0   # the game caps a contract's unit at the shop's price less one (ShortageContracts.unitPay); the guard reads the capped pay
        self.assertEqual([p for p in em.business_problems(em.Model()) if p.startswith("shortage")], [])


class TheDocumentIsCurrent(unittest.TestCase):
    def test_the_generated_block_of_the_economy_model_document_matches_the_data(self):
        path = ROOT / "docs/technical/economy-model.md"
        text = path.read_text(encoding="utf-8")
        self.assertEqual(em.splice(text, em.doc_block(em.Model())), text, "run: python scripts/economy_model.py --update-doc docs/technical/economy-model.md")


if __name__ == "__main__":
    unittest.main()
