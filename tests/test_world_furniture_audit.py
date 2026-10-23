import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import world_furniture_audit as audit  # noqa: E402


def nbt_string(s: str) -> bytes:
    raw = s.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def section(palette: list[str], indices: list[int]) -> dict:
    """A section as the reader returns it, packed the way 1.20.1 packs it (entries never span longs)."""
    bits = max(4, (len(palette) - 1).bit_length())
    per = 64 // bits
    words = []
    for w in range(0, 4096, per):
        word = 0
        for k, index in enumerate(indices[w:w + per]):
            word |= index << (k * bits)
        words.append(word - (1 << 64) if word >= 1 << 63 else word)
    return {"Y": 4, "block_states": {"palette": [{"Name": n} for n in palette], "data": words}}


class FurnitureAuditTests(unittest.TestCase):
    def test_furniture_is_furniture_and_windows_walls_and_hedges_are_not(self):
        for name in ("modernlife:light_gray_sofa", "minecraft:brown_bed", "modernlife:toilet", "modernlife:dual_monitor", "minecraft:spruce_door", "modernlife:oak_small_table"):
            self.assertTrue(audit.is_furniture(name), name)
        for name in ("minecraft:glass_pane", "modernlife:hedge", "minecraft:stone_bricks", "minecraft:bookshelf", "modernlife:iron_glass", "minecraft:bedrock"):
            self.assertFalse(audit.is_furniture(name), name)

    def test_three_of_a_piece_stacked_is_found_and_two_or_a_mixed_column_is_not(self):
        self.assertEqual(audit.stacks({70: "modernlife:light_gray_sofa", 71: "modernlife:light_gray_sofa", 72: "modernlife:light_gray_sofa"}), [(70, 3, "modernlife:light_gray_sofa")])
        self.assertEqual(audit.stacks({70: "modernlife:light_gray_sofa", 71: "modernlife:light_gray_sofa"}), [])
        self.assertEqual(audit.stacks({70: "modernlife:oak_chair", 71: "modernlife:oak_small_table", 72: "modernlife:oak_chair"}), [])
        self.assertEqual(audit.stacks({70: "minecraft:brown_bed", 71: "minecraft:brown_bed", 73: "minecraft:brown_bed"}), [])

    def test_a_door_is_two_blocks_so_three_is_not_yet_a_stack_and_four_is(self):
        self.assertEqual(audit.stacks({70: "minecraft:oak_door", 71: "minecraft:oak_door", 72: "minecraft:oak_door"}), [])
        self.assertEqual(len(audit.stacks({y: "minecraft:oak_door" for y in range(70, 74)})), 1)

    def test_a_packed_section_is_read_back_at_the_right_positions(self):
        indices = [0] * 4096
        for y in range(3):
            indices[y * 256 + 5 * 16 + 2] = 1      # x 2, z 5, y 0..2 of the section
        found = audit.section_blocks(section(["minecraft:air", "modernlife:light_gray_sofa", "minecraft:stone"], indices), audit.is_furniture)
        self.assertEqual(sorted(found), [0 * 256 + 82, 1 * 256 + 82, 2 * 256 + 82])
        self.assertEqual(audit.section_blocks(section(["minecraft:air", "minecraft:stone"], [1] * 4096), audit.is_furniture), {})

    def test_an_nbt_document_is_read(self):
        doc = (b"\x0a" + nbt_string("") + b"\x08" + nbt_string("Status") + nbt_string("minecraft:full")
               + b"\x03" + nbt_string("DataVersion") + struct.pack(">i", 3465) + b"\x00")
        self.assertEqual(audit.read_nbt(doc), {"Status": "minecraft:full", "DataVersion": 3465})


if __name__ == "__main__":
    unittest.main()
