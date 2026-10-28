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


class CreateStones(unittest.TestCase):
    """Create's decorative stones are walls, floors and steps: ported to air they left holes in the owner's houses (eleventh pass)."""

    def test_a_create_stone_keeps_its_shape_and_its_properties_and_is_never_air(self):
        self.assertEqual(porter.create_shaped("create:cut_calcite", {}), "minecraft:calcite")
        self.assertEqual(porter.create_shaped("create:polished_cut_calcite_stairs", {"facing": "east", "half": "top", "shape": "straight", "waterlogged": "false"}),
                         "minecraft:smooth_quartz_stairs[facing=east,half=top,shape=straight,waterlogged=false]")
        self.assertEqual(porter.create_shaped("create:polished_cut_scoria_slab", {"type": "bottom"}), "minecraft:nether_brick_slab[type=bottom]")
        self.assertEqual(porter.create_shaped("create:cut_tuff_brick_wall", {"up": "true", "north": "low", "axis": "y"}), "minecraft:andesite_wall[north=low,up=true]")
        self.assertEqual(porter.create_shaped("create:calcite_pillar", {"axis": "y"}), "minecraft:calcite", "a full block takes no shape properties")
        for stone in porter.CREATE_STONES:
            for prefix in ("cut_", "polished_cut_", "layered_", "small_"):
                self.assertNotEqual(porter.create_shaped(f"create:{prefix}{stone}", {}), "minecraft:air")

    def test_glass_stays_whole_a_seat_is_a_carpet_and_machinery_is_still_air(self):
        self.assertEqual(porter.create_shaped("create:framed_glass", {}), "minecraft:glass")
        self.assertEqual(porter.create_shaped("create:dark_oak_window_pane", {"north": "true"}), "minecraft:glass_pane[north=true]")
        self.assertEqual(porter.create_shaped("create:red_seat", {}), "minecraft:red_carpet")
        self.assertIsNone(porter.create_shaped("create:shaft", {"axis": "y"}))
        self.assertIsNone(porter.create_shaped("create:cogwheel", {}))


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

    def test_a_building_of_one_part_without_conditions_is_built_once(self):
        # Lost Cities 7 builds storeys 0 to floors and puts a part with no conditions up on every one: a cabin on its own mound, raised to two floors and given two
        # cellars, came out as five copies whose mounds buried the bedrooms below (eleventh pass, a private generated world)
        lc = self.source / "data" / "fixturecity" / "lostcities"
        (lc / "parts" / "fixture_cabin.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "slices": [["##", "##"]] * 9}), encoding="utf-8")
        (lc / "buildings" / "fixture_cabin.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "parts": [{"part": "fixturecity:fixture_cabin"}]}), encoding="utf-8")
        (lc / "buildings" / "fixture_cellared.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "maxcellars": 1, "parts": [{"part": "fixturecity:fixture_cabin"}]}),
                                                              encoding="utf-8")
        (lc / "parts" / "fixture_tall_ground.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "slices": [["##", "##"]] * 6}), encoding="utf-8")
        (lc / "parts" / "fixture_tall_floor.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "slices": [["##", "##"]] * 6}), encoding="utf-8")
        (lc / "parts" / "fixture_tall_top.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "slices": [["##", "##"]] * 6}), encoding="utf-8")
        (lc / "buildings" / "fixture_tall.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "parts": [
            {"part": "fixturecity:fixture_tall_ground", "ground": True, "top": False}, {"part": "fixturecity:fixture_tall_floor", "ground": False, "top": False},
            {"part": "fixturecity:fixture_tall_top", "top": True, "ground": False}]}), encoding="utf-8")
        result = porter.port(self.source, self.dest)
        out = self.dest / "data" / "fixturecity" / "lostcities" / "buildings"
        cabin = json.loads((out / "fixture_cabin.json").read_text(encoding="utf-8"))
        self.assertEqual((cabin["minfloors"], cabin["maxfloors"], cabin["mincellars"], cabin["maxcellars"]), (0, 0, 0, 0), "storey 0 alone, and no cellar")
        self.assertTrue(cabin["overrideFloors"], "Lost Cities 7 reads the building's own numbers only when it overrides")
        cellared = json.loads((out / "fixture_cellared.json").read_text(encoding="utf-8"))
        self.assertEqual((cellared["minfloors"], cellared["maxfloors"], cellared["maxcellars"]), (0, 0, 1), "cellars the building names are kept")
        self.assertNotIn("mincellars", cellared)
        tall = json.loads((out / "fixture_tall.json").read_text(encoding="utf-8"))
        self.assertEqual((tall["minfloors"], tall["maxfloors"]), (2, 2), "a ground, a middle and a top part still need storeys 0 to 2")
        self.assertNotIn("maxcellars", tall)
        self.assertEqual(result["counts"]["single_structures"], 2)

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

    def test_a_part_of_a_multi_chunk_building_is_given_the_characters_its_building_defines(self):
        # Lost Cities 7.5.5 does not find a character only the building of a multi-chunk building's part defines (D-198): the part is given it
        lc = self.source / "data" / "fixturecity" / "lostcities"
        (lc / "parts").mkdir(parents=True, exist_ok=True)
        (lc / "parts" / "duo_1_ground.json").write_text(json.dumps({"xsize": 1, "zsize": 1, "palette": [{"char": "#", "block": "minecraft:bricks"}],
            "slices": [["#H"], ["  "], ["  "], ["  "], ["  "], ["  "]]}), encoding="utf-8")
        (lc / "parts" / "duo_0_ground.json").write_text(json.dumps({"xsize": 1, "zsize": 1, "slices": [["#"], [" "], [" "], [" "], [" "], [" "]]}), encoding="utf-8")
        (lc / "buildings").mkdir(parents=True, exist_ok=True)
        (lc / "buildings" / "duo_1.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "palette": {"palette": [{"char": "H", "block": "minecraft:black_concrete"},
            {"char": "#", "block": "minecraft:stone"}]}, "parts": [{"part": "fixturecity:duo_1_ground", "ground": True}]}), encoding="utf-8")
        (lc / "buildings" / "duo_0.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "parts": [{"part": "fixturecity:duo_0_ground", "ground": True}]}), encoding="utf-8")
        (lc / "multibuildings").mkdir(parents=True, exist_ok=True)
        (lc / "multibuildings" / "duo.json").write_text(json.dumps({"dimx": 1, "dimz": 2, "buildings": [["duo_0", "fixturecity:duo_1"]]}), encoding="utf-8")
        result = porter.port(self.source, self.dest)
        out = self.dest / "data" / "fixturecity" / "lostcities" / "parts"
        part = json.loads((out / "duo_1_ground.json").read_text(encoding="utf-8"))
        self.assertEqual({e["char"]: e["block"] for e in part["palette"]["palette"]}, {"#": "minecraft:bricks", "H": "minecraft:black_concrete"},
                         "the part gets the building's H, and keeps its own # over the building's, in the object form Lost Cities decodes")
        self.assertNotIn("palette", json.loads((out / "duo_0_ground.json").read_text(encoding="utf-8")), "a part whose building defines nothing it uses is left alone")
        self.assertEqual((result["counts"]["multi_parts_given_building_characters"], result["counts"]["multi_part_characters_given"]), (1, 1))

    def test_a_multi_chunk_part_that_names_its_palette_keeps_that_palettes_entries_in_its_own(self):
        # Lost Cities reads a part's inline palette in place of the one it names: the named palette's entries go into the inline one with the building's character (multi run 2)
        lc = self.source / "data" / "fixturecity" / "lostcities"
        for folder in ("parts", "buildings", "multibuildings", "palettes"):
            (lc / folder).mkdir(parents=True, exist_ok=True)
        (lc / "palettes" / "trio_1_ground_palette.json").write_text(json.dumps({"palette": [{"char": "G", "block": "minecraft:grass_block"}]}), encoding="utf-8")
        (lc / "parts" / "trio_1_ground.json").write_text(json.dumps({"xsize": 1, "zsize": 1, "refpalette": "fixturecity:trio_1_ground_palette",
            "slices": [["GH"], ["  "], ["  "], ["  "], ["  "], ["  "]]}), encoding="utf-8")
        (lc / "buildings" / "trio_1.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "palette": {"palette": [{"char": "H", "block": "minecraft:black_concrete"}]},
            "parts": [{"part": "fixturecity:trio_1_ground", "ground": True}]}), encoding="utf-8")
        (lc / "multibuildings" / "trio.json").write_text(json.dumps({"dimx": 1, "dimz": 1, "buildings": [["fixturecity:trio_1"]]}), encoding="utf-8")
        porter.port(self.source, self.dest)
        part = json.loads((self.dest / "data" / "fixturecity" / "lostcities" / "parts" / "trio_1_ground.json").read_text(encoding="utf-8"))
        self.assertEqual({e["char"]: e["block"] for e in part["palette"]["palette"]}, {"G": "minecraft:grass_block", "H": "minecraft:black_concrete"})
        self.assertNotIn("refpalette", part, "one palette is read: the inline one")

    def test_an_inline_palette_in_lost_cities_object_form_is_ported_too(self):
        lc = self.source / "data" / "fixturecity" / "lostcities"
        (lc / "parts" / "fixture_obj_ground.json").write_text(json.dumps({"xsize": 1, "zsize": 1, "palette": {"palette": [{"char": "k", "block": "create:andesite_casing"}]},
            "slices": [["k"], [" "], [" "], [" "], [" "], [" "]]}), encoding="utf-8")
        porter.port(self.source, self.dest)
        part = json.loads((self.dest / "data" / "fixturecity" / "lostcities" / "parts" / "fixture_obj_ground.json").read_text(encoding="utf-8"))
        self.assertNotIn("create:", json.dumps(part), "a Create block in an object-form inline palette is replaced like any other")

    def test_a_road_surface_fuel_and_bricks_of_a_machinery_mod_are_not_its_catch_all_iron(self):
        # the catch-all for Immersive Engineering and Immersive Petroleum is an iron block: asphalt drew shiny iron courtyards in the owner's factory (October 27)
        looks = block_lookalikes.Lookalikes()
        self.assertEqual(looks.resolve("immersivepetroleum:asphalt")[0], "minecraft:black_concrete")
        self.assertEqual(looks.resolve("immersivepetroleum:asphalt_slab", {"type": "top"})[0].split("[")[0], "minecraft:polished_blackstone_slab")
        self.assertEqual(looks.resolve("immersivepetroleum:crudeoil")[0], "minecraft:air", "a fuel is a fluid, not a block of iron")
        self.assertEqual(looks.resolve("immersiveengineering:cokebrick")[0], "minecraft:bricks")
        self.assertEqual(looks.resolve("immersiveengineering:biodiesel_fluid_block")[0], "minecraft:air")
        self.assertEqual(looks.resolve("immersiveengineering:radiator")[0], "minecraft:iron_block", "a machine is still the catch-all's iron")

    def test_a_short_grass_a_flower_pot_and_a_pumpkin_are_written_so_that_lost_cities_places_them(self):
        # without properties Lost Cities reads these by their 1.12 names: a look-alike short grass (another mod's dead grass) became a grass block round a factory
        lc = self.source / "data" / "fixturecity" / "lostcities"
        (lc / "palettes" / "fixture_garden.json").write_text(json.dumps({"palette": [{"char": "1", "block": "biomesoplenty:dead_grass"}, {"char": "2", "block": "minecraft:flower_pot"},
            {"char": "3", "blocks": [{"random": 500, "block": "minecraft:pumpkin"}, {"random": 1000, "block": "minecraft:melon"}]}]}), encoding="utf-8")
        report = porter.port(self.source, self.dest)
        out = json.loads((self.dest / "data" / "fixturecity" / "lostcities" / "palettes" / "fixture_garden.json").read_text(encoding="utf-8"))["palette"]
        self.assertEqual(out[0]["block"], "minecraft:grass[]")
        self.assertEqual(out[1]["block"], "minecraft:flower_pot[]")
        self.assertEqual([o["block"] for o in out[2]["blocks"]], ["minecraft:pumpkin[]", "minecraft:melon"])
        self.assertEqual(report["counts"]["legacy_names_kept"], 3)

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

    def test_a_woven_mat_is_a_floor_and_never_the_bamboo_plant(self):
        # private tour 15: a restaurant's floor of Quark bamboo mats came out as the bamboo plant, from the shapeless fallback's bare material
        registry = {"minecraft:bamboo": {"age": ["0", "1"]}, "minecraft:bamboo_mosaic": {}, "minecraft:bamboo_planks": {}, "minecraft:yellow_carpet": {}, "minecraft:granite": {}}
        look = block_lookalikes.Lookalikes(registry=registry)
        self.assertEqual(look.resolve("quark:bamboo_mat", {"facing": "north"}), ("minecraft:bamboo_mosaic", "rule"))
        self.assertEqual(look.resolve("quark:bamboo_mat_carpet"), ("minecraft:yellow_carpet", "rule"))
        self.assertEqual(look.resolve("othermod:bamboo_thing"), ("minecraft:bamboo_planks", "shape"))
        self.assertEqual(look.resolve("othermod:granite"), ("minecraft:granite", "shape"))

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
