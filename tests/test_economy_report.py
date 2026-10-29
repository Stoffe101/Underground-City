"""The economy telemetry report (twelfth pass): sessions, income by source, order timings against the model, travel and police, read from Core's JSON lines."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import economy_report as er  # noqa: E402

M = er.TICKS_PER_MINUTE


def line(t, kind, p="a1", **fields):
    return {"t": t, "wall": 0, "p": p, "career": "smith", "level": fields.pop("level", 3), "wallet": fields.pop("wallet", 100), "kind": kind, **fields}


class Report(unittest.TestCase):
    def write(self, events, rolled=None):
        folder = Path(tempfile.mkdtemp())
        (folder / er.FILE).write_text("\n".join(json.dumps(e) for e in events) + "\n{broken", encoding="utf-8")
        if rolled:
            (folder / (er.FILE + ".1")).write_text("\n".join(json.dumps(e) for e in rolled) + "\n", encoding="utf-8")
        return folder

    def test_a_session_with_orders_purchases_a_fine_and_a_trip(self):
        events = [
            line(0, "login"),
            line(0, "city", city="ironhaven", **{"from": "?"}),
            line(2 * M, "purchase", credits=176, bank=0, good="refined_alloy", units=4),
            line(3 * M, "accepted", order="foundry_parts"),
            line(13 * M, "order", order="foundry_parts", legality="legal", credits=290, minutes=10.0, level_up=True),
            line(15 * M, "city", city="", **{"from": "ironhaven"}),
            line(20 * M, "city", city="valewood", **{"from": ""}),
            line(22 * M, "arrest", city="valewood", fine=40, asked=40, stacks=1),
            line(25 * M, "fence", credits=60),
            line(30 * M, "logout"),
        ]
        rolled = [line(-10 * M, "service", credits=80, what="smith_repair", p="b2")]
        s = er.summarise(er.read([self.write(events, rolled)]))
        a = s["players"]["a1"]
        self.assertAlmostEqual(a["minutes"], 30.0)
        self.assertEqual(a["income"]["legal orders"], 290)
        self.assertEqual(a["income"]["fences"], 60)
        self.assertEqual(a["spent"]["goods at the exchanges"], 176)
        self.assertEqual(a["spent"]["fines"], 40)
        self.assertEqual(a["net"], 290 + 60 - 176 - 40)
        self.assertAlmostEqual(a["per_hour"], 2 * (290 + 60 - 176 - 40))
        self.assertEqual(a["level_ups"], 1)
        self.assertEqual(s["orders"], {"foundry_parts": [10.0]})
        self.assertEqual(s["travel"], [("ironhaven", "valewood", 5.0)])
        self.assertEqual(s["police"]["arrests"], 1)
        self.assertEqual(s["police"]["stacks"], 1)
        self.assertIn("b2", s["players"], "the rolled file is read too")
        text = er.report(s, {"foundry_parts": 6.9})
        self.assertIn("| foundry_parts | 1 | 10.0 |", text)
        self.assertIn("0.69", text, "the model's minutes against the observed ones")
        self.assertNotIn("Policecases", text)

    def test_two_logins_count_both_sessions_and_not_the_gap(self):
        events = [line(0, "login"), line(10 * M, "logout"), line(100 * M, "login"), line(105 * M, "order", order="x", legality="underground", credits=500, minutes=-1)]
        s = er.summarise(er.read([self.write(events)]))
        self.assertAlmostEqual(s["players"]["a1"]["minutes"], 15.0)
        self.assertEqual(s["players"]["a1"]["income"]["underground orders"], 500)
        self.assertEqual(s["orders"], {}, "a delivery whose acceptance was not seen has no timing")

    def test_nothing_to_read_is_an_error(self):
        self.assertEqual(er.read([Path(tempfile.mkdtemp())]), [])


if __name__ == "__main__":
    unittest.main()
