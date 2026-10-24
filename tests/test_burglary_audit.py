"""The burglary audit's rules hold for the data as committed: a richer building is worth at least as much to a fence, and no single search is a jackpot."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import burglary_audit as audit  # noqa: E402


class BurglaryAuditTest(unittest.TestCase):
    def test_the_rules_hold(self):
        kinds, values = audit.load()
        self.assertEqual(audit.problems(kinds, values), [])

    def test_the_audit_follows_the_game_roll(self):
        # one draw below tier 4 and two from it, as BurglarySearch.roll draws
        kinds, values = audit.load()
        bedside = kinds["bedside"]
        self.assertAlmostEqual(audit.expected(bedside, 4, "house", values), 2 * audit.expected(bedside, 4, "house", values, draws=1))
        self.assertAlmostEqual(audit.expected(bedside, 3, "house", values), audit.expected(bedside, 3, "house", values, draws=1))

    def test_back_room_stock_is_only_a_businesses(self):
        kinds, values = audit.load()
        self.assertEqual(audit.expected(kinds["stock"], 3, "house", values, draws=3), 0.0)
        self.assertGreater(audit.expected(kinds["stock"], 3, "pub", values, draws=3), 0.0)


if __name__ == "__main__":
    unittest.main()
