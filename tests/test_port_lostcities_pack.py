"""The private-pack porter (scripts/port_lostcities_pack.py, D-116): proven on an INVENTED 1.18.2-style fixture only; no private content is used."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import block_lookalikes  # noqa: E402
import port_lostcities_pack as porter  # noqa: E402

SCRIPT = ROOT / "scripts" / "port_lostcities_pack.py"


def every_json(folder: Path) -> str:
    return "\n".join(p.read_text(encoding="utf-8") for p in folder.rglob("*.json") if p.name != porter.REPORT)


class PortTheFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.source = porter.fixture(self.root)
        self.dest = self.root / "ported"

    def tearDown(self):
        self.tmp.cleanup()

    def test_create_spawners_mobs_loot_and_unknown_mods_are_gone_and_counted(self):
        report = porter.port(self.source, self.dest)
        text = every_json(self.dest / "data")
        for gone in ("create:", "minecraft:spawner", '"mob"', '"loot"', "otherfurniture:", "grass_path"):
            self.assertNotIn(gone, text, f"{gone} survived the port")
        c = report["counts"]
        self.assertEqual((c["create_replaced"], c["spawners_removed"], c["mob_markers_removed"], c["loot_dropped"], c["foreign_replaced"], c["renamed"]), (4, 1, 1, 1, 1, 1))
        self.assertEqual((c["worldstyles_left_out"], c["worldstyles_to_review"]), (1, 0), "a world style is left out unless asked for")
        self.assertIn("modernlife:oak_chair[facing=south]", text, "a block of a kept mod must be kept, properties and all")
        self.assertIn("minecraft:dirt_path", text)

    def test_the_slices_buildings_and_manifest_survive_and_the_pack_is_a_1_20_1_pack(self):
        porter.port(self.source, self.dest)
        part = json.loads((self.dest / "data/fixturecity/lostcities/parts/fixture_house_ground.json").read_text(encoding="utf-8"))
        original = json.loads((self.source / "data/fixturecity/lostcities/parts/fixture_house_ground.json").read_text(encoding="utf-8"))
        renamed = str.maketrans(json.loads((self.dest / porter.REPORT).read_text(encoding="utf-8"))["style_characters_renamed_to"])
        self.assertEqual(part["slices"], [[row.translate(renamed) for row in layer] for layer in original["slices"]])
        self.assertEqual(part["palette"][0]["block"], "minecraft:air", "an inline palette of a part is ported too")
        manifest = json.loads((self.dest / "uc-private-pack.json").read_text(encoding="utf-8"))
        self.assertTrue(manifest["private_only"])
        self.assertEqual(manifest["id"], "uc_port_fixture-port")
        self.assertEqual(json.loads((self.dest / "pack.mcmeta").read_text(encoding="utf-8"))["pack"]["pack_format"], 15)

    def test_the_report_holds_counts_and_block_ids_never_the_content(self):
        porter.port(self.source, self.dest)
        report = (self.dest / porter.REPORT).read_text(encoding="utf-8")
        self.assertNotIn("slices", report)
        self.assertNotIn("##", report)

    def test_a_style_character_a_palette_takes_is_renamed_and_one_left_to_the_style_is_kept(self):
        # the fixture's ground part gives "x" (a park's raised ground and a corridor roof in Lost Cities' style) a block of its own and uses it, and uses "y"
        # (a retaining wall) without defining it: Lost Cities would draw its walls round the building with the part's "x" block, and the part's "y" is the style's
        result = porter.port(self.source, self.dest)
        new = result["style_characters_renamed_to"]["x"]
        self.assertNotIn(new, "ywSbBx+Z9v ")
        self.assertEqual(set(result["style_characters_renamed_to"]), {"x"}, "only the style characters a palette takes are renamed")
        part = json.loads((self.dest / "data/fixturecity/lostcities/parts/fixture_house_ground.json").read_text(encoding="utf-8"))
        self.assertEqual(part["slices"][3], [new + "y", "  "])
        self.assertEqual([e["char"] for e in part["palette"]], [new])
        self.assertEqual(result["style_characters_redefined"], {}, "nothing is left to warn about")
        self.assertEqual(result["counts"]["parts_with_style_characters_renamed"], 1)
        self.assertTrue(result["loadable"])

    def add_style_pack(self):
        """An invented style whose shared palette defines "H" and "y", and two buildings whose parts lean on it: one with no palette of its own, one with a
        referenced palette; a third character ("Q") nothing defines."""
        lc = self.source / "data" / "fixturecity" / "lostcities"
        (lc / "styles").mkdir(parents=True, exist_ok=True)
        (lc / "palettes" / "fixture_common.json").write_text(json.dumps({"palette": [
            {"char": "H", "block": "minecraft:iron_bars"}, {"char": "y", "block": "minecraft:stone"}, {"char": "#", "block": "minecraft:cobblestone"}]}), encoding="utf-8")
        (lc / "styles" / "fixture_style.json").write_text(json.dumps({"randompalettes": [[{"factor": 1.0, "palette": "fixturecity:fixture_common"}]]}), encoding="utf-8")
        (lc / "palettes" / "fixture_shed.json").write_text(json.dumps({"palette": [{"char": "#", "block": "minecraft:bricks"}]}), encoding="utf-8")
        (lc / "parts" / "fixture_shed_ground.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "refpalette": "fixturecity:fixture_shed",
            "slices": [["##", "##"], ["Hy", "  "], ["  ", "  "], ["  ", "  "], ["  ", "  "], ["##", "##"]]}), encoding="utf-8")
        (lc / "buildings" / "fixture_shed.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1,
            "parts": [{"ground": True, "part": "fixturecity:fixture_shed_ground"}]}), encoding="utf-8")
        (lc / "buildings" / "fixture_shed_ref.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "refpalette": "fixturecity:fixture_shed",
            "parts": [{"ground": True, "part": "fixturecity:fixture_shed_ground"}]}), encoding="utf-8")
        (lc / "parts" / "fixture_odd_ground.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "refpalette": "fixturecity:fixture_shed",
            "slices": [["#Q", "##"], ["  ", "  "], ["  ", "  "], ["  ", "  "], ["  ", "  "], ["##", "##"]]}), encoding="utf-8")
        (lc / "buildings" / "fixture_odd.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "parts": [{"ground": True, "part": "fixturecity:fixture_odd_ground"}]}),
                                                         encoding="utf-8")
        return lc

    def test_a_character_the_parts_take_from_the_packs_style_is_given_to_the_building(self):
        # the pack's style palette defines "H"; this game's city styles replace the pack's, so without the building's own definition Lost Cities throws and the lot stays empty
        self.add_style_pack()
        result = porter.port(self.source, self.dest)
        lc = self.dest / "data" / "fixturecity" / "lostcities"
        shed = json.loads((lc / "buildings" / "fixture_shed.json").read_text(encoding="utf-8"))
        self.assertEqual(shed["palette"], {"palette": [{"char": "H", "block": "minecraft:iron_bars"}]},
                         "the building with no palette gets an inline palette (Lost Cities' object form) with the style's H, and not the style character y")
        ref = json.loads((lc / "buildings" / "fixture_shed_ref.json").read_text(encoding="utf-8"))
        self.assertNotIn("palette", ref, "an inline palette would replace the referenced one in Lost Cities")
        self.assertEqual(ref["refpalette"], "fixturecity:fixture_shed_ref_with_style")
        merged = json.loads((lc / "palettes" / "fixture_shed_ref_with_style.json").read_text(encoding="utf-8"))["palette"]
        self.assertEqual({e["char"]: e["block"] for e in merged}, {"#": "minecraft:bricks", "H": "minecraft:iron_bars"}, "the referenced palette is kept and H added")
        self.assertEqual(result["counts"]["buildings_given_style_characters"], 2)
        self.assertEqual(result["counts"]["style_characters_given"], 2)
        self.assertEqual(result["buildings_with_undefined_characters"], {"fixturecity:fixture_odd": "Q"})
        self.assertTrue(result["loadable"], "an undefined character empties a lot, it does not stop a world from loading")

    def test_an_inline_palette_in_lost_cities_object_form_is_ported_too(self):
        lc = self.source / "data" / "fixturecity" / "lostcities"
        (lc / "parts" / "fixture_obj_ground.json").write_text(json.dumps({"xsize": 1, "zsize": 1, "palette": {"palette": [{"char": "k", "block": "create:andesite_casing"}]},
            "slices": [["k"], [" "], [" "], [" "], [" "], [" "]]}), encoding="utf-8")
        porter.port(self.source, self.dest)
        part = json.loads((self.dest / "data" / "fixturecity" / "lostcities" / "parts" / "fixture_obj_ground.json").read_text(encoding="utf-8"))
        self.assertNotIn("create:", json.dumps(part), "a Create block in an object-form inline palette is replaced like any other")

    def test_a_mapping_decides_before_the_fallbacks(self):
        report = porter.port(self.source, self.dest, mapping={"create:shaft": "minecraft:chain[axis=y]"})
        self.assertIn("minecraft:chain[axis=y]", every_json(self.dest / "data"))
        self.assertNotIn("create:shaft", report["replaced_blocks"])

    def test_a_registry_check_finds_a_block_the_game_does_not_have_and_the_port_is_not_loadable(self):
        registry = {"minecraft:stone_bricks": {}, "minecraft:dirt_path": {}, "minecraft:polished_andesite": {}, "minecraft:cut_copper": {}, "minecraft:oak_planks": {},
                    "minecraft:chest": {"facing": ["north", "south", "east", "west"], "type": ["single"], "waterlogged": ["true", "false"]}}
        report = porter.port(self.source, self.dest, registry=registry)
        self.assertFalse(report["loadable"])
        self.assertTrue(any("no block of the kept mod modernlife" in p for p in report["problems"]), report["problems"])
        registry["modernlife:oak_chair"] = {"facing": ["north", "south", "east", "west"]}
        self.assertTrue(porter.port(self.source, self.root / "again", registry=registry)["loadable"])

    def test_it_refuses_a_folder_inside_a_checkout_a_pack_without_permission_and_a_full_destination(self):
        with self.assertRaises(SystemExit):
            porter.port(self.source, ROOT / "build" / "never-here")
        unmarked = self.root / "unmarked"
        unmarked.mkdir()
        with self.assertRaises(SystemExit):
            porter.port(unmarked, self.root / "x")
        porter.port(self.source, self.dest)
        with self.assertRaises(SystemExit):
            porter.port(self.source, self.dest)
        porter.port(self.source, self.dest, force=True)

    def test_the_command_line_fails_on_problems(self):
        registry = self.root / "registry.json"
        registry.write_text(json.dumps({"minecraft:stone_bricks": {}}), encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "port", str(self.source), str(self.dest), "--registry", str(registry)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("must not be loaded", result.stdout)

    def test_a_foreign_block_becomes_its_nearest_look_and_a_gone_block_of_a_kept_mod_its_sibling(self):
        registry = {"minecraft:stone_bricks": {}, "minecraft:dirt_path": {}, "minecraft:polished_andesite": {}, "minecraft:cut_copper": {}, "minecraft:oak_planks": {},
                    "minecraft:chest": {"facing": ["north", "south", "east", "west"], "type": ["single"], "waterlogged": ["true", "false"]},
                    "minecraft:spruce_stairs": {"facing": ["north", "south", "east", "west"], "half": ["top", "bottom"], "shape": ["straight"], "waterlogged": ["true", "false"]},
                    "minecraft:glass_pane": {}, "minecraft:barrel": {"facing": ["north", "up"], "open": ["true", "false"]},
                    "modernlife:oak_chair": {"facing": ["north", "south", "east", "west"]}, "modernlife:birch_sofa": {"facing": ["north", "south", "east", "west"]}}
        look = block_lookalikes.Lookalikes(registry=registry)
        # a shape in its material: a fir stair of another mod is a spruce stair, facing and half kept, an unknown property dropped
        self.assertEqual(look.resolve("othermod:fir_stairs", {"facing": "east", "half": "top", "colour": "red"}), ("minecraft:spruce_stairs[facing=east,half=top]", "shape"))
        # a rule of the public table: a storage mod's drawer is a barrel, a loot mod's chest a chest
        self.assertEqual(look.resolve("storagedrawers:oak_full_drawers_1", {"facing": "north"})[0], "minecraft:barrel[facing=north]")
        self.assertEqual(look.resolve("lootr:lootr_chest", {"facing": "south"})[0], "minecraft:chest[facing=south]")
        # a gone block of a kept mod: the same thing in another material
        self.assertEqual(look.resolve("modernlife:granite_sofa", {"facing": "west"}, kept_namespace=True), ("modernlife:birch_sofa[facing=west]", "sibling"))
        # nothing near: air, said
        self.assertEqual(look.resolve("othermod:flux_capacitor"), ("minecraft:air", "air"))
        # without a registry nothing is guessed beyond the rules
        self.assertEqual(block_lookalikes.Lookalikes().resolve("othermod:fir_stairs"), ("minecraft:air", "air"))

    def test_a_port_with_a_registry_reports_what_became_what(self):
        registry = {"minecraft:stone_bricks": {}, "minecraft:dirt_path": {}, "minecraft:polished_andesite": {}, "minecraft:cut_copper": {}, "minecraft:oak_planks": {},
                    "minecraft:chest": {"facing": ["north", "south", "east", "west"], "type": ["single"], "waterlogged": ["true", "false"]},
                    "modernlife:oak_chair": {"facing": ["north", "south", "east", "west"]}}
        report = porter.port(self.source, self.dest, registry=registry)
        self.assertTrue(report["loadable"], report["problems"])
        self.assertEqual(report["replaced_blocks"]["otherfurniture:fancy_chair"], "minecraft:air")
        self.assertEqual(report["counts"]["to_air"], 1)


if __name__ == "__main__":
    unittest.main()
