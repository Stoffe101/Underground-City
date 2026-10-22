"""The private-pack porter (scripts/port_lostcities_pack.py, D-116): proven on an INVENTED 1.18.2-style fixture only; no private content is used."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
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
        self.assertEqual(c["worldstyles_to_review"], 1)
        self.assertIn("modernlife:oak_chair[facing=south]", text, "a block of a kept mod must be kept, properties and all")
        self.assertIn("minecraft:dirt_path", text)

    def test_the_slices_buildings_and_manifest_survive_and_the_pack_is_a_1_20_1_pack(self):
        porter.port(self.source, self.dest)
        part = json.loads((self.dest / "data/fixturecity/lostcities/parts/fixture_house_ground.json").read_text(encoding="utf-8"))
        original = json.loads((self.source / "data/fixturecity/lostcities/parts/fixture_house_ground.json").read_text(encoding="utf-8"))
        self.assertEqual(part["slices"], original["slices"])
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

    def test_a_mapping_decides_before_the_fallbacks(self):
        report = porter.port(self.source, self.dest, mapping={"create:shaft": "minecraft:chain[axis=y]"})
        self.assertIn("minecraft:chain[axis=y]", every_json(self.dest / "data"))
        self.assertNotIn("create:shaft", report["replaced_blocks"])

    def test_a_registry_check_finds_a_block_the_game_does_not_have_and_the_port_is_not_loadable(self):
        registry = {"minecraft:stone_bricks": {}, "minecraft:dirt_path": {}, "minecraft:polished_andesite": {}, "minecraft:cut_copper": {}, "minecraft:oak_planks": {},
                    "minecraft:chest": {"facing": ["north", "south", "east", "west"], "type": ["single"], "waterlogged": ["true", "false"]}}
        report = porter.port(self.source, self.dest, registry=registry)
        self.assertFalse(report["loadable"])
        self.assertTrue(any("unknown block modernlife:oak_chair" in p for p in report["problems"]), report["problems"])
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


if __name__ == "__main__":
    unittest.main()
