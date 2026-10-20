"""Every skill tree must be reachable on the terminal's skill page.

The page lays a tree out in three columns (legal, the foundation/utility/crossover middle, underground). A node sits one row below its lowest parent and
never on the row of another node of its own column (TerminalScreen.layoutTree). Five rows fit at full node height; a deeper tree scrolls (mouse wheel or
drag, TerminalScreen.skillScroll), up to MAX_ROWS rows. This mirrors the layout rule so a tree that is too deep, or two skills drawn on one cell, fails here
in a unit test rather than as a node hidden behind the detail panel in the real client.
"""
import json
import unittest
from pathlib import Path

TREES = Path(__file__).resolve().parents[1] / "rpg-core/src/main/resources/data/underground_city/skill_trees"
FIT_ROWS = 5      # rows that fit at full node height without scrolling
MAX_ROWS = 8      # the deepest tree the scrolling page is built and photographed for


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


DESCRIPTION_CHARS = 76   # two lines of the detail panel at its narrowest (640 pixel wide GUI), measured on the 720p capture of the deepest tree


class SkillTreeTextTest(unittest.TestCase):
    """The detail panel shows the description on two lines and the effect line (generated from the numbers) under it."""

    def test_descriptions_fit_two_lines_and_never_restate_the_numbers(self):
        import re
        for path in sorted(TREES.glob("*.json")):
            for node in json.loads(path.read_text(encoding="utf-8"))["nodes"]:
                text = node["description"]
                self.assertLessEqual(len(text), DESCRIPTION_CHARS, f"{node['id']} is cut off on the detail panel: {text!r}")
                # a number in the prose would go stale when the node's effect is retuned; the effect line is generated from the data
                self.assertIsNone(re.search(r"\d+\s*(%|points)", text), f"{node['id']} restates an effect in its description: {text!r}")


class SkillTreeLayoutTest(unittest.TestCase):
    def test_every_tree_fits_the_scrolling_page(self):
        for path in sorted(TREES.glob("*.json")):
            tree = json.loads(path.read_text(encoding="utf-8"))
            placed = rows(tree["nodes"])
            self.assertLessEqual(max(placed.values()) + 1, MAX_ROWS, f"{path.name} needs {max(placed.values()) + 1} rows: {placed}")

    def test_at_least_one_tree_is_deeper_than_the_page_fits_so_the_scroll_is_exercised(self):
        deepest = max(max(rows(json.loads(p.read_text(encoding="utf-8"))["nodes"]).values()) + 1 for p in TREES.glob("*.json"))
        self.assertGreater(deepest, FIT_ROWS)

    def test_no_two_nodes_share_a_cell(self):
        for path in sorted(TREES.glob("*.json")):
            tree = json.loads(path.read_text(encoding="utf-8"))
            placed = rows(tree["nodes"])
            column = {"legal": 0, "underground": 2}
            cells = [(column.get(n.get("branch"), 1), placed[n["id"]]) for n in tree["nodes"]]
            self.assertEqual(len(cells), len(set(cells)), f"{path.name} draws two skills on one cell")


if __name__ == "__main__":
    unittest.main()
