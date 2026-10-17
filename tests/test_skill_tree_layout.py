"""Every skill tree must fit the terminal's skill page without scrolling.

The page lays a tree out in three columns (legal, foundation, underground). A node sits one row below its lowest parent and never on the row of another
node of its own column (TerminalScreen.layoutTree); five rows fit the node at full height. This mirrors that rule so a tree that is too deep fails here,
in a unit test, rather than as a node hidden behind the detail panel in the real client.
"""
import json
import unittest
from pathlib import Path

TREES = Path(__file__).resolve().parents[1] / "rpg-core/src/main/resources/data/underground_city/skill_trees"
MAX_ROWS = 5


def rows(nodes):
    index = {node["id"]: node for node in nodes}
    depth = {}

    def tier(node_id, guard=0):
        if node_id in depth:
            return depth[node_id]
        parents = [p for p in index[node_id].get("requires", []) if p in index]
        depth[node_id] = 0 if guard > 8 or not parents else 1 + max(tier(p, guard + 1) for p in parents)
        return depth[node_id]

    order = sorted(index, key=lambda i: (tier(i), list(index).index(i)))
    column = {"legal": 0, "underground": 2}
    last = {0: -1, 1: -1, 2: -1}
    placed = {}
    for node_id in order:
        col = column.get(index[node_id].get("branch"), 1)
        row = 0
        for parent in index[node_id].get("requires", []):
            if parent in placed:
                row = max(row, placed[parent] + 1)
        row = max(row, last[col] + 1)
        placed[node_id] = row
        last[col] = row
    return placed


class SkillTreeLayoutTest(unittest.TestCase):
    def test_every_tree_fits_five_rows(self):
        for path in sorted(TREES.glob("*.json")):
            tree = json.loads(path.read_text(encoding="utf-8"))
            placed = rows(tree["nodes"])
            self.assertLessEqual(max(placed.values()) + 1, MAX_ROWS, f"{path.name} needs {max(placed.values()) + 1} rows: {placed}")

    def test_no_two_nodes_share_a_cell(self):
        for path in sorted(TREES.glob("*.json")):
            tree = json.loads(path.read_text(encoding="utf-8"))
            placed = rows(tree["nodes"])
            column = {"legal": 0, "underground": 2}
            cells = [(column.get(n.get("branch"), 1), placed[n["id"]]) for n in tree["nodes"]]
            self.assertEqual(len(cells), len(set(cells)), f"{path.name} draws two skills on one cell")


if __name__ == "__main__":
    unittest.main()
