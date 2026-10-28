"""The one-command private staging and the launch check (scripts/private_c70.py): proven on INVENTED fixtures only; no private content is used."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "tests"))
import private_c70 as c70  # noqa: E402
import private_pack_tool as packs  # noqa: E402
from test_private_wiring import NS, Fixture, storey  # noqa: E402

SCRIPT = ROOT / "scripts" / "private_c70.py"
FACING = ["north", "south", "east", "west"]
REGISTRY = {"minecraft:stone_bricks": {}, "minecraft:oak_planks": {}, "minecraft:light_gray_carpet": {}, "minecraft:air": {},
            "minecraft:oak_door": {"facing": FACING, "half": ["lower", "upper"]}, "minecraft:red_bed": {"facing": FACING, "part": ["head", "foot"]}}


def original(root: Path, manifest: bool = True) -> Path:
    """An invented 1.18.2-style pack of four buildings: a house with a bed (two rotations), a house without one, flats and a building whose id names no use."""
    f = Fixture(root)
    f.building("house", storey(bed=True))
    f.building("house_90", storey(bed=True))
    f.building("bare_house", storey())
    f.building("flat_block", storey(), upper=[storey(door=None, bed=True)])
    f.building("silo", storey())
    (f.pack / packs.MANIFEST).unlink()
    if manifest:
        packs.init(f.pack, "fixture-original", "Invented original", "this repository (synthetic)", "invented content for tests", True, False)
    (f.pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 9, "description": "invented"}}), encoding="utf-8")
    return f.pack


def staged(root: Path, **stage_doc) -> Path:
    """A runtime folder holding one pack that looks staged."""
    pack = root / "runtime" / "c70-port"
    (pack / "data" / NS / "lostcities" / "buildings").mkdir(parents=True)
    # written here rather than by private_pack_tool.init, which (rightly) refuses a folder inside a checkout: one test needs one there
    (pack / packs.MANIFEST).write_text(json.dumps({"schema_version": 1, "id": "fixture-port", "title": "Invented port", "provides": {"data": True, "assets": False}, "private_only": True,
                                                   "permission": {"holder": "this repository (synthetic)", "summary": "invented content for tests"}}), encoding="utf-8")
    (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 15, "description": "invented"}}), encoding="utf-8")
    (pack / "uc-port-report.json").write_text(json.dumps({"loadable": True}), encoding="utf-8")
    doc = {"schema_version": 1, "porter_version": c70.MIN_PORTER, "wiring_version": c70.MIN_WIRING, "needs_sets": c70.NEEDS_SETS, **stage_doc}
    (pack / c70.STAGE).write_text(json.dumps(doc), encoding="utf-8")
    return root / "runtime"


class Check(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_staged_pack_with_the_sets_it_needs_is_ready(self):
        result = c70.check(staged(self.root), ["furnished", "scarcity", "furniture"])
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["packs"][0]["needs_sets"], ["furnished", "furniture"])

    def test_a_launch_without_the_furniture_set_is_refused_with_the_set_named(self):
        result = c70.check(staged(self.root), ["furnished", "scarcity"])
        self.assertFalse(result["ok"])
        self.assertTrue(any("furniture" in e for e in result["errors"]), result["errors"])

    def test_pointing_at_the_pack_instead_of_the_folder_that_holds_it_is_refused(self):
        runtime = staged(self.root)
        result = c70.check(runtime / "c70-port", c70.RECOMMENDED_SETS)
        self.assertFalse(result["ok"])
        self.assertIn(str(runtime), result["errors"][0])

    def test_an_unported_original_and_a_stale_or_unloadable_port_are_refused(self):
        runtime = self.root / "originals"
        runtime.mkdir()
        original(runtime / "one")
        (runtime / "one" / "pack").rename(runtime / "c70-original")
        result = c70.check(runtime, c70.RECOMMENDED_SETS)
        self.assertFalse(result["ok"])
        self.assertIn("unported original", " ".join(result["errors"]), "a 1.18.2 pack format")
        (runtime / "c70-original" / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 15}}), encoding="utf-8")
        self.assertIn("never ported", " ".join(c70.check(runtime, c70.RECOMMENDED_SETS)["errors"]), "an original given a 1.20.1 pack format, as private_pack_tool init writes")
        stale = staged(self.root / "stale", porter_version=c70.MIN_PORTER - 1)
        self.assertIn("Stage it again", " ".join(c70.check(stale, c70.RECOMMENDED_SETS)["errors"]))
        broken = staged(self.root / "broken")
        (broken / "c70-port" / "uc-port-report.json").write_text(json.dumps({"loadable": False}), encoding="utf-8")
        self.assertIn("not loadable", " ".join(c70.check(broken, c70.RECOMMENDED_SETS)["errors"]))

    def test_a_manifest_the_game_would_refuse_and_an_empty_or_missing_folder_are_refused(self):
        runtime = staged(self.root)
        manifest = json.loads((runtime / "c70-port" / packs.MANIFEST).read_text(encoding="utf-8"))
        manifest["permission"]["holder"] = ""
        (runtime / "c70-port" / packs.MANIFEST).write_text(json.dumps(manifest), encoding="utf-8")
        self.assertIn("permission", " ".join(c70.check(runtime, c70.RECOMMENDED_SETS)["errors"]))
        empty = self.root / "empty"
        empty.mkdir()
        self.assertIn("holds no private pack", c70.check(empty, c70.RECOMMENDED_SETS)["errors"][0])
        self.assertIn("does not exist", c70.check(self.root / "nowhere", c70.RECOMMENDED_SETS)["errors"][0])

    def test_a_folder_inside_a_checkout_is_refused_unless_it_is_its_ignored_private_content_folder(self):
        tree = self.root / "checkout"
        tree.mkdir()
        (tree / ".git").mkdir()
        inside = staged(tree / "somewhere")
        self.assertIn("git work tree", c70.check(inside, c70.RECOMMENDED_SETS)["errors"][0])
        ignored = staged(tree / "private-content")
        self.assertTrue(c70.check(ignored, c70.RECOMMENDED_SETS)["ok"], "the game itself accepts a checkout's ignored private-content folder (PrivateContent.checkoutProblem)")

    def test_a_file_changed_since_the_seal_is_a_warning_and_the_command_line_says_not_ready_with_exit_1(self):
        runtime = staged(self.root)
        packs.seal(runtime / "c70-port")
        (runtime / "c70-port" / "data" / "extra.json").write_text("{}", encoding="utf-8")
        result = c70.check(runtime, c70.RECOMMENDED_SETS)
        self.assertTrue(result["ok"])
        self.assertTrue(any("differ from its seal" in w for w in result["warnings"]), result["warnings"])
        out = subprocess.run([sys.executable, str(SCRIPT), "check", str(runtime), "--sets", "furnished"], capture_output=True, text=True)
        self.assertEqual(out.returncode, 1)
        self.assertIn("NOT ready", out.stdout)


class Stage(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.registry = self.root / "registry.json"
        self.registry.write_text(json.dumps(REGISTRY), encoding="utf-8")

    def tearDown(self):
        self.tmp.cleanup()

    def stage(self, source: Path, holder: str = "", summary: str = "", selection: Path | None = None) -> dict:
        return c70.stage(source, self.root / "runtime", None, self.registry, selection, holder, summary)

    def test_an_original_is_ported_chosen_wired_sealed_and_installed_and_the_launch_check_accepts_it(self):
        doc = self.stage(original(self.root / "src"))
        runtime = self.root / "runtime"
        pack = runtime / c70.PACK_NAME
        self.assertTrue((pack / "uc-private-index.json").is_file(), "sealed")
        self.assertEqual(doc["selection"], "auto")
        self.assertEqual((doc["porter_version"], doc["wiring_version"]), (c70.MIN_PORTER, c70.MIN_WIRING))
        self.assertTrue(c70.check(runtime, c70.RECOMMENDED_SETS)["ok"])
        self.assertFalse((runtime / "original").exists(), "the unported original never sits where the game loads packs")
        report = json.loads((self.root / "runtime-work" / "selection-auto-report.json").read_text(encoding="utf-8"))
        self.assertIn(f"{NS}:bare_house", report["left_out"], "a house without a bed is not guessed into a home")
        self.assertIn(f"{NS}:silo", report["left_out"], "an id that names no use is left out")
        selection = json.loads((self.root / "runtime-work" / "selection-auto.json").read_text(encoding="utf-8"))
        houses = {b["id"]: b["city"] for b in selection["buildings"] if b["use"] == "house"}
        self.assertEqual(houses, {f"{NS}:house": "valewood", f"{NS}:house_90": "valewood"})
        flats = {b["city"] for b in selection["buildings"] if b["use"] == "flats"}
        self.assertEqual(flats, {"ironhaven"}, "one family of flats and one rotation: the first city of its use")
        self.assertTrue(all(b.get("listing", {}).get("category") for b in selection["buildings"] if b["use"] in ("house", "flats")))
        self.assertEqual(selection["public_weights"].get("underground_city:vw_house_a"), 0, "C70 first: the public houses give way where private houses stand in every ring")
        self.assertTrue((pack / "data" / NS / "city_buildings" / "house.json").is_file())
        self.assertTrue((pack / "data/underground_city/city_character/valewood.json").is_file())

    def test_a_download_without_a_manifest_needs_the_owners_statement_once_and_a_restage_keeps_the_last_pack(self):
        source = original(self.root / "src", manifest=False)
        with self.assertRaises(SystemExit) as refused:
            self.stage(source)
        self.assertIn("--holder", str(refused.exception))
        self.stage(source, holder="the owner (synthetic)", summary="invented")
        self.stage(source)
        previous = list((self.root / "runtime-work" / "previous").iterdir())
        self.assertEqual(len(previous), 1, "the replaced pack is kept")
        self.assertTrue(c70.check(self.root / "runtime", c70.RECOMMENDED_SETS)["ok"])

    def test_a_zip_is_found_inside_a_server_pack_layout_and_a_member_that_leaves_the_folder_is_refused(self):
        source = original(self.root / "src")
        archive = self.root / "serverpack.zip"
        with zipfile.ZipFile(archive, "w") as z:
            for path in source.rglob("*"):
                if path.is_file():
                    z.write(path, "world/datapacks/c/" + str(path.relative_to(source)).replace(os.sep, "/"))
        self.stage(archive)
        self.assertTrue((self.root / "runtime" / c70.PACK_NAME / c70.STAGE).is_file())
        evil = self.root / "evil.zip"
        with zipfile.ZipFile(evil, "w") as z:
            z.writestr("../outside.json", "{}")
        with self.assertRaises(SystemExit) as refused:
            c70.stage(evil, self.root / "runtime2", None, self.registry, None, "", "")
        self.assertIn("would leave", str(refused.exception))
        self.assertFalse((self.root / "outside.json").exists())

    def test_a_registry_without_a_kept_furniture_mod_makes_the_port_unloadable_and_installs_nothing(self):
        source = original(self.root / "src")
        part = source / "data" / NS / "lostcities" / "parts" / "house_ground.json"
        doc = json.loads(part.read_text(encoding="utf-8"))
        doc["palette"].append({"char": "T", "block": "cfm:oak_table"})
        part.write_text(json.dumps(doc), encoding="utf-8")
        with self.assertRaises(SystemExit) as refused:
            self.stage(source)
        self.assertIn("not loadable", str(refused.exception), "a registry dumped without the furniture set")
        self.assertFalse((self.root / "runtime" / c70.PACK_NAME).exists())

    def test_a_registry_that_lacks_the_doors_and_beds_leaves_nothing_to_stage(self):
        self.registry.write_text(json.dumps({"minecraft:stone_bricks": {}, "minecraft:oak_planks": {}}), encoding="utf-8")
        with self.assertRaises(SystemExit) as refused:
            self.stage(original(self.root / "src"))
        self.assertIn("no building", str(refused.exception))
        self.assertFalse((self.root / "runtime" / c70.PACK_NAME).exists())

    def test_the_owners_own_selection_is_used_as_written(self):
        selection = self.root / "mine.json"
        selection.write_text(json.dumps({"schema_version": 1, "buildings": [
            {"id": f"{NS}:house", "city": "valewood", "zone": 0, "weight": 1, "use": "house", "class": "house"}]}), encoding="utf-8")
        doc = self.stage(original(self.root / "src"), selection=selection)
        self.assertEqual((doc["selection"], doc["counts"]["buildings"]), ("owner", 1))

    def test_the_output_may_not_be_inside_a_checkout(self):
        tree = self.root / "checkout"
        (tree / ".git").mkdir(parents=True)
        with self.assertRaises(SystemExit):
            c70.stage(original(self.root / "src"), tree / "runtime", None, self.registry, None, "", "")


class Kinds(unittest.TestCase):
    def test_words_of_an_id_choose_a_use_and_a_word_inside_another_does_not(self):
        self.assertEqual(c70.kind_of("x:building_residential1")["use"], "house")
        self.assertEqual(c70.kind_of("x:multi_megatower1")["class"], "flat_tower")
        self.assertEqual(c70.kind_of("x:public_library")["use"], "public", "public is not a pub")
        self.assertEqual(c70.kind_of("x:corner_pub")["counter"], "shop:pub")
        self.assertIsNone(c70.kind_of("x:building_bunker1"))

    def test_every_use_and_listing_category_exists_where_the_wiring_and_the_ladder_look(self):
        import private_wiring as wiring
        terms = c70.listing_terms()
        for _, use, _, _, _, listing in c70.KINDS:
            self.assertIn(use, wiring.USES)
            self.assertTrue(listing is None or listing in terms, listing)
        self.assertEqual(set(c70.CITIES), set(c70.RINGS))
        self.assertEqual(c70.MIN_PORTER, __import__("port_lostcities_pack").PORTER_VERSION, "a fresh port is never refused by its own checkout")
        self.assertEqual(c70.MIN_WIRING, wiring.WIRING_VERSION)


if __name__ == "__main__":
    unittest.main()
