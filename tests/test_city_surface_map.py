"""The city surface map's road audit (D-196), on invented probe reports: what counts as the paved street, what on it is reported, and what is the city's own design."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import city_surface_map as csm  # noqa: E402

LEGEND = ["smooth_stone_slab", "grass_block", "stone", "brown_mushroom", "stone_bricks", "grass", "cyan_terracotta"]
SLAB, GRASS_BLOCK, STONE, MUSHROOM, BRICKS, TUFT, TERRACOTTA = range(len(LEGEND))


def street_chunk(x: int, z: int, layer: list[int], over: list[int], over_rel: str, building: str = "") -> dict:
    return {"chunk_x": x, "chunk_z": z, "city": True, "type": building, "street_y": 71, "city_level": 0, "street_layer": layer, "over_street": over, "over_street_rel": over_rel,
            "surface": layer, "surface_rel": "3" * 256}


def plain_street() -> tuple[list[int], list[int], list[str]]:
    """A street eight cells wide down the middle (rows 4 to 11), grass verges either side."""
    layer = [SLAB if 4 <= i // 16 <= 11 else GRASS_BLOCK for i in range(256)]
    return layer, [-1] * 256, ["."] * 256


class RoadAuditTest(unittest.TestCase):
    def audit(self, chunks: list[dict]) -> dict:
        report = {"surface_legend": LEGEND, "chunks": chunks}
        return csm.summarise(report)["ironhaven"]

    def test_the_street_block_is_the_most_common_built_block_at_the_street_height(self):
        layer, over, rel = plain_street()
        layer[0] = BRICKS   # a park bed's corner: built, but not the street
        row = self.audit([street_chunk(0, 0, layer, over, "".join(rel))])
        self.assertEqual(row["street_block"], "smooth_stone_slab")
        self.assertEqual(row["paved_cells"], 128)

    def test_a_grass_block_or_a_mushroom_on_the_paving_is_reported_and_the_citys_furniture_is_built(self):
        layer, over, rel = plain_street()
        for i, block in ((5 * 16 + 3, GRASS_BLOCK), (6 * 16 + 3, MUSHROOM), (7 * 16 + 3, TERRACOTTA), (8 * 16 + 3, TUFT)):
            over[i] = block
            rel[i] = "1"
        row = self.audit([street_chunk(0, 0, layer, over, "".join(rel))])
        self.assertEqual(row["on_paving"]["terrain:grass_block"], 1)
        self.assertEqual(row["on_paving"]["vegetation:brown_mushroom"], 1)
        self.assertEqual(row["on_paving"]["vegetation:grass"], 1)
        self.assertEqual(row["on_paving"]["built:cyan_terracotta"], 1)
        self.assertEqual(len(row["on_paving_examples"]), 3, "built furniture is not an example of a problem")

    def test_what_stands_on_a_park_bed_is_not_on_the_paving(self):
        layer, over, rel = plain_street()
        for i in range(16):   # the first row is a raised bed: stone bricks with its lawn one block up
            layer[i] = BRICKS
            over[i] = GRASS_BLOCK
            rel[i] = "1"
        row = self.audit([street_chunk(0, 0, layer, over, "".join(rel))])
        self.assertNotIn("terrain:grass_block", row["on_paving"])
        self.assertEqual(row["on_other_built_ground"]["grass_block"], 16)

    def test_a_terrain_cell_inside_the_street_is_a_hole_and_bare_rock_on_a_verge_is_counted(self):
        layer, over, rel = plain_street()
        layer[6 * 16 + 6] = STONE    # paving on all four sides
        layer[1 * 16 + 1] = STONE    # in the verge, open to the sky
        row = self.audit([street_chunk(0, 0, layer, over, "".join(rel))])
        self.assertEqual(row["holes_in_paving"]["stone"], 1)
        self.assertEqual(row["bare_verge"]["stone"], 1)

    def test_a_building_chunk_is_not_a_street(self):
        layer, over, rel = plain_street()
        over[5 * 16] = GRASS_BLOCK
        row = self.audit([street_chunk(0, 0, layer, over, "".join(rel), building="underground_city:ih_flats")])
        self.assertEqual(row["paved_cells"], 0)
        self.assertEqual(row["building_chunks"], 1)

    def test_named_paving_wins_over_the_most_common_block(self):
        layer, over, rel = plain_street()
        report = {"surface_legend": LEGEND, "chunks": [street_chunk(0, 0, layer, over, "".join(rel))]}
        row = csm.summarise(report, {"stone_bricks"})["ironhaven"]
        self.assertEqual(row["street_block"], "stone_bricks")
        self.assertEqual(row["paved_cells"], 0)


if __name__ == "__main__":
    unittest.main()
