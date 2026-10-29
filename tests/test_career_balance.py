"""Legal, underground and hybrid careers compared (twelfth pass, D-210): the shipped data keeps every rule, and each rule can fail.

The rules answer the owner's question of October 29: does honest work stay worth doing once a career's underground path opens? The
second half breaks the data in memory (underground pay doubled so a hybrid abandons its trade, the advanced legal orders made worthless,
an install that pays less than the array it uses) and checks the script notices. The service-call fees the model uses are read from the
Java so the model cannot drift from the game.
"""
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import career_balance as cb  # noqa: E402
import economy_model as em  # noqa: E402

JAVA = ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity"


class ShippedData(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.b = cb.Balance()
        cls.report = cb.report(cls.b)

    def test_every_rule_holds(self):
        self.assertIn("### Rules\n\nall hold", self.report)

    def test_the_document_is_current(self):
        path = ROOT / "docs/technical/career-balance.md"
        text = path.read_text(encoding="utf-8")
        head, rest = text.split(cb.BEGIN, 1)
        _, tail = rest.split(cb.END, 1)
        self.assertEqual(head + cb.BEGIN + "\n\n" + self.report + "\n\n" + cb.END + tail, text, "run: python scripts/career_balance.py --update-doc docs/technical/career-balance.md")

    def test_a_hybrid_never_earns_less_than_its_legal_trade(self):
        for career in em.CAREERS:
            for level in (10, 15, 20):
                with self.subTest(career=career, level=level):
                    legal = self.b.hour([(career, level, "legal")], em.ALL_MODS)["risk"]
                    hybrid = self.b.hour([(career, level, "hybrid")], em.ALL_MODS)["risk"]
                    self.assertGreaterEqual(hybrid + 1e-6, legal)

    def test_a_shared_run_lowers_an_underground_orders_minutes_only_when_it_carries_more_than_one(self):
        h = self.b.hour([("technician", 16, "underground")], em.ALL_MODS)
        self.assertTrue(any(oid.startswith("ledger_") for _, oid, _ in h["picks"]), h["picks"])
        single = self.b.hour([("technician", 7, "underground")], em.ALL_MODS)
        self.assertTrue(any(oid == "ledger_recovery" for _, oid, _ in single["picks"]), single["picks"])

    def test_station_tiers_follow_the_assumption(self):
        self.assertEqual([self.b.station_tier(level) for level in (1, 7, 8, 13, 14, 30)], [1, 1, 2, 2, 3, 3])

    def test_a_technician_without_its_repair_skills_repairs_only_low_tiers(self):
        repair = self.b.by_id["tech_repair"]
        novice = self.b.reached(repair, set())
        trained = self.b.reached(repair, {"technician.fundamentals", "technician.municipal_repairs"})
        self.assertEqual(novice.credits, 30 + 12 * 2)
        self.assertEqual(novice.cash, 0.0, "a tier II repair uses no circuit board")
        self.assertGreater(trained.credits, novice.credits)
        self.assertGreater(trained.cash, 0.0, "a tier III or higher repair uses a circuit board")

    def test_every_install_pays_for_the_array_it_uses(self):
        install = self.b.by_id["tech_install"]
        self.assertGreater(install.credits, install.cash, "an install loses money against its array")
        job = next(j for j in self.b.a["service_work"]["jobs"] if j["id"] == "tech_install")
        self.assertGreater(job["fee_base"] + job["fee_per_tier"] * 2, install.cash, "a tier II install loses money")


class ServiceFeesMatchTheJava(unittest.TestCase):
    """The fees in economy-model.json's service_work are the game's: read from TechService and MachineService."""

    def fee(self, source: str, name: str) -> tuple[int, int]:
        text = (JAVA / source).read_text(encoding="utf-8")
        m = re.search(r"static int " + name + r"\(int tier\) \{ return (\d+) \+ (\d+) \* Math\.max\(1, tier\); \}", text)
        self.assertIsNotNone(m, f"{source} has no {name}(tier) of the form a + b * tier")
        return int(m.group(1)), int(m.group(2))

    def test_fees(self):
        jobs = {j["id"]: j for j in cb.Balance().a["service_work"]["jobs"]}
        for job, source, fee, xp in (("tech_repair", "TechService.java", "repairFee", "repairXp"), ("tech_install", "TechService.java", "installFee", "installXp"),
                                     ("tech_upgrade", "TechService.java", "upgradeFee", "upgradeXp"), ("smith_repair", "MachineService.java", "fee", "xp")):
            with self.subTest(job=job):
                self.assertEqual(self.fee(source, fee), (jobs[job]["fee_base"], jobs[job]["fee_per_tier"]))
                self.assertEqual(self.fee(source, xp), (jobs[job]["xp_base"], jobs[job]["xp_per_tier"]))

    def test_fault_gaps(self):
        text = (JAVA / "TechService.java").read_text(encoding="utf-8")
        lo = int(re.search(r"FAULT_GAP_MIN = (\d+) \* 1200L", text).group(1))
        hi = int(re.search(r"FAULT_GAP_MAX = (\d+) \* 1200L", text).group(1))
        job = next(j for j in cb.Balance().a["service_work"]["jobs"] if j["id"] == "tech_repair")
        self.assertAlmostEqual(job["per_hour"], 60 / ((lo + hi) / 2), delta=0.1)
        text = (JAVA / "MachineService.java").read_text(encoding="utf-8")
        lo = int(re.search(r"GAP_MIN = (\d+) \* 1200L", text).group(1))
        hi = int(re.search(r"GAP_MAX = (\d+) \* 1200L", text).group(1))
        job = next(j for j in cb.Balance().a["service_work"]["jobs"] if j["id"] == "smith_repair")
        self.assertAlmostEqual(job["per_hour"], 60 / ((lo + hi) / 2), delta=0.1)


class RulesCanFail(unittest.TestCase):
    def test_doubling_underground_pay_makes_a_hybrid_abandon_its_trade(self):
        """The owner's fear: once crime pays a little more per minute, the best hour has no honest work in it at all."""
        b = cb.Balance()
        for o in b.m.orders:
            if o.legality == "underground" and not o.id.startswith("raid_"):
                o.credits *= 2
        h = b.hour([("technician", 15, "hybrid")], em.ALL_MODS)
        self.assertLess(h["legal_share"], cb.RULES["hybrid_legal_share_min"])
        u = b.hour([("gunsmith", 15, "underground")], em.ALL_MODS)["risk"]
        legal = b.hour([("gunsmith", 15, "legal")], em.ALL_MODS)["risk"]
        self.assertLess(legal, cb.RULES["legal_after_unlock_min"] * u, "legal work stays ahead even with crime paying double")

    def test_worthless_advanced_orders_and_no_station_upgrades_stop_legal_growth(self):
        """A smith's growth comes from two places: better orders, and the grades of upgraded stations (foundry parts turned at tier III are Reserve)."""
        b = cb.Balance()
        b.a["station_tiers"] = {"by_level": {"1": 1}}
        for o in b.m.orders + b.services:
            if o.legality == "legal" and o.level >= 7:
                o.credits = 1
        l6 = b.hour([("smith", 6, "legal")], em.ALL_MODS)["risk"]
        l20 = b.hour([("smith", 20, "legal")], em.ALL_MODS)["risk"]
        self.assertLess(l20, cb.RULES["legal_growth_min"] * l6)

    def test_one_huge_order_trips_the_single_activity_rule(self):
        b = cb.Balance()
        o = next(x for x in b.m.orders if x.id == "foundry_parts")
        o.credits *= 4
        h = b.hour([("smith", 20, "legal")], em.ALL_MODS)
        self.assertGreater(max(b.shares("smith", 20, h).values()), cb.RULES["single_activity_max"])

    def test_the_old_install_fee_lost_money(self):
        b = cb.Balance()
        install = b.by_id["tech_install"]
        self.assertLess(45 + 18 * 3, install.cash, "the pre-D-210 tier III install fee covered the array")


if __name__ == "__main__":
    unittest.main()
