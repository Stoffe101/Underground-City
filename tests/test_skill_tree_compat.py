"""Saves keep every skill they own (D-098).

A save stores the ids of the skills a character has learned, and the points spent are worked out from the catalog on every load. So a redesign of a tree
is save-compatible only if no skill id disappears, a skill never moves to another career, and no skill costs more than it did (a character who bought it
would otherwise be over-spent). The table below is the shipped catalog as it stood before the October 20 redesign: the id, the career, and the cost.
"""
import json
import unittest
from pathlib import Path

TREES = Path(__file__).resolve().parents[1] / "rpg-core/src/main/resources/data/underground_city/skill_trees"

# id -> the cost it had in the October 19 catalog
LEGACY = {
    "farmer.fundamentals": 1, "farmer.greenhouse_management": 2, "farmer.cultivar_broker": 2, "farmer.estate_wholesale": 3, "farmer.controlled_crop_licence": 2,
    "farmer.restricted_cultivation": 3, "farmer.concealed_stores": 2, "farmer.underground_trade": 3, "farmer.grey_market_contacts": 3, "farmer.field_logistics": 1,
    "vintner.fundamentals": 1, "vintner.cellar_craft": 2, "vintner.estate_vintages": 3, "vintner.cellar_logistics": 1, "vintner.still_craft": 3,
    "vintner.distillers_licence": 2, "vintner.concealed_stores": 2, "vintner.bootleg_trade": 3, "vintner.grey_market_contacts": 3,
    "smith.fundamentals": 1, "smith.structural_works": 2, "smith.armoury_contracts": 2, "smith.industrial_licence": 2, "smith.precision_machining": 2,
    "smith.unlisted_fabrication": 3, "smith.unstamped_production": 2, "smith.contraband_armoury": 3,
    "gunsmith.fundamentals": 1, "gunsmith.licensed_repairs": 2, "gunsmith.dealer_licence": 3, "gunsmith.security_contracts": 2, "gunsmith.ballistics_lab": 2,
    "gunsmith.unregistered_builds": 3, "gunsmith.filed_serials": 2, "gunsmith.arms_brokerage": 3,
    "technician.fundamentals": 1, "technician.municipal_repairs": 2, "technician.automation_install": 2, "technician.security_systems": 3, "technician.signal_analysis": 2,
    "technician.bypass_tooling": 3, "technician.signal_scrubbing": 2, "technician.data_brokerage": 3,
}


class SkillTreeCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.nodes = {}
        for path in sorted(TREES.glob("*.json")):
            tree = json.loads(path.read_text(encoding="utf-8"))
            for node in tree["nodes"]:
                self.nodes[node["id"]] = (tree["career"], node)

    def test_no_skill_id_a_save_may_hold_has_disappeared(self):
        self.assertEqual(sorted(set(LEGACY) - set(self.nodes)), [])

    def test_no_skill_moved_to_another_career_or_costs_more(self):
        for skill, old_cost in LEGACY.items():
            career, node = self.nodes[skill]
            self.assertEqual(skill.split(".")[0], career, skill)
            self.assertLessEqual(node["cost"], old_cost, f"{skill} costs {node['cost']}, it cost {old_cost}: a character who owns it could be over-spent")

    def test_an_owned_skill_stays_owned_when_its_prerequisites_grew(self):
        # nothing re-checks prerequisites of an owned skill (SkillRules.blocker answers "Already unlocked" first), which is what makes tightening them safe
        java = (Path(__file__).resolve().parents[1] / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/SkillRules.java").read_text(encoding="utf-8")
        self.assertLess(java.index('"Already unlocked"'), java.index("node.requires()"), "SkillRules.blocker must test ownership before prerequisites")


if __name__ == "__main__":
    unittest.main()
