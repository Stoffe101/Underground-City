import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from validate_onboarding_catalog import check_catalog, check_core_parity

CATALOG = json.loads((ROOT / "content/onboarding/catalog.json").read_text(encoding="utf-8"))
JAVA_SOURCE = (ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/ProgressionRules.java").read_text(encoding="utf-8")


class OnboardingDesignTests(unittest.TestCase):
    def test_valid_catalog_and_fallback_parity(self):
        self.assertEqual(check_catalog(CATALOG), [])
        self.assertEqual(check_core_parity(CATALOG, JAVA_SOURCE), [])

    def test_rejects_reversed_selection_order(self):
        broken = copy.deepcopy(CATALOG)
        broken["selection_order"].reverse()
        self.assertTrue(any("city before profession" in e for e in check_catalog(broken)))

    def test_rejects_duplicate_professions(self):
        broken = copy.deepcopy(CATALOG)
        broken["professions"].append(copy.deepcopy(broken["professions"][0]))
        self.assertTrue(any("duplicate id" in e for e in check_catalog(broken)))

    def test_rejects_invalid_branch_and_missing_objectives(self):
        broken = copy.deepcopy(CATALOG)
        broken["professions"][0]["branches"] = ["cultivation", "cultivation"]
        broken["professions"][0]["starter_objectives"] = []
        errors = check_catalog(broken)
        self.assertTrue(any("distinct" in e for e in errors))
        self.assertTrue(any("starter objectives" in e for e in errors))

    def test_detects_fallback_java_drift(self):
        broken = copy.deepcopy(CATALOG)
        broken["cities"][0]["id"] = "replacement_city"
        self.assertTrue(any("does not match" in e for e in check_core_parity(broken, JAVA_SOURCE)))


if __name__ == "__main__":
    unittest.main()
