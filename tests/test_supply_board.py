import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from render_supply_board import build_board
from simulate_regional_supply import read_json, SCHEMA_FILE, CITIES_FILE


class SupplyBoardTests(unittest.TestCase):
    def test_board_lists_all_careers_and_origins_without_claiming_live_crafting(self):
        supply = read_json(SCHEMA_FILE)
        city = read_json(CITIES_FILE)
        board = build_board(supply, city)
        for name in ("Ironhaven", "Valewood", "Blackwater",
                     "Farmer", "Smith", "Gunsmith", "Technician"):
            self.assertIn(name, board)
        for row in supply["recurring_recipes"]:
            self.assertIn(row["output"]["id"].replace("_", " "), board)
        self.assertEqual(board.count("\n| Farmer |"), 3)
        self.assertEqual(board.count("\n| Smith |"), 3)
        self.assertEqual(board.count("\n| Gunsmith |"), 3)
        self.assertEqual(board.count("\n| Technician |"), 3)
        self.assertIn("No Minecraft merchant inventory", board)
        self.assertIn("travel enforcement", board)
        self.assertIn("Tier 1 work remain local", board)


if __name__ == "__main__":
    unittest.main()
