import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import world_plan_audit as audit  # noqa: E402


def plan(rows_by_layer: list[list[str]]) -> list:
    """A part's layers, each a list of 16 rows of 16 characters (shorter rows are padded with air)."""
    return [[row.ljust(16) for row in layer] + [" " * 16] * (16 - len(layer)) for layer in rows_by_layer]


PALETTE = {"#": "minecraft:stone_bricks", "c": "modernlife:cctv_camera[facing=north]", "w": "minecraft:white_carpet", "p": "minecraft:flower_pot"}
GROUND = plan([["################"] * 16, ["#" + " " * 14 + "#"] * 15 + ["#######c########"], ["#  w"] + ["#"] * 15])


def world(part: list, palette: dict, base: int, drop: set = frozenset(), change: dict | None = None) -> dict:
    """The blocks a world would hold for a part placed at this height, less the cells in ``drop`` and with ``change`` applied."""
    grid = {}
    for y, layer in enumerate(part):
        for z, row in enumerate(layer):
            for x, ch in enumerate(row):
                if ch != " " and (x, base + y, z) not in drop:
                    grid[(x, base + y, z)] = palette[ch].split("[", 1)[0]
    grid.update(change or {})
    return grid


class PlanAuditTests(unittest.TestCase):
    def test_a_ground_floor_is_matched_at_its_height(self):
        parts = {"t_ground": (GROUND, PALETTE)}
        self.assertEqual(audit.match(world(GROUND, PALETTE, 71), parts), [("t_ground", 71)])
        self.assertEqual(audit.match({}, parts), [])

    def test_a_block_the_world_lacks_is_reported_with_what_is_there(self):
        grid = world(GROUND, PALETTE, 71, drop={(7, 72, 15)})
        lost = audit.missing(grid, GROUND, PALETTE, 71)
        self.assertEqual(lost, [((7, 72, 15), "modernlife:cctv_camera[facing=north]", "minecraft:air")])

    def test_what_lost_cities_changes_on_purpose_is_not_a_loss(self):
        part = plan([["################"] * 16, ["p"]])
        grid = world(part, {"#": "minecraft:stone_bricks", "p": "minecraft:flower_pot"}, 71, change={(0, 72, 0): "minecraft:potted_cactus"})
        self.assertEqual(audit.missing(grid, part, {"#": "minecraft:stone_bricks", "p": "minecraft:flower_pot"}, 71), [])

    def test_the_floors_above_are_matched_six_blocks_apart(self):
        upper = plan([["################"] * 16, ["#" + " " * 14 + "#"] * 16])
        parts = {"t_ground": (GROUND, PALETTE), "t_floor": (upper, PALETTE)}
        grid = world(GROUND, PALETTE, 71)
        grid.update(world(upper, PALETTE, 77))
        self.assertEqual(audit.match(grid, parts), [("t_ground", 71), ("t_floor", 77)])

    def test_the_shipped_pack_has_parts_to_compare(self):
        parts = audit.load_parts()
        self.assertTrue(any(name.endswith("_ground") for name in parts))
        for name, (slices, palette) in parts.items():
            self.assertTrue(slices and palette, name)


if __name__ == "__main__":
    unittest.main()
