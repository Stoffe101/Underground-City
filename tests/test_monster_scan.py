"""The static scan of pinned jars for hostile-creature drops (scripts/scan_pack_for_monster_drops.py), proved on small jars built here."""
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import scan_pack_for_monster_drops as scan  # noqa: E402


def jar(folder: Path, name: str, files: dict) -> Path:
    path = folder / name
    with zipfile.ZipFile(path, "w") as archive:
        for entry, body in files.items():
            archive.writestr(entry, json.dumps(body))
    return path


class Scan(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.folder = Path(self.tmp.name)
        self.catalogue = scan.load_catalogue()

    def test_a_vehicle_material_string_and_a_forge_tag_are_both_found(self):
        path = jar(self.folder, "pack.jar", {"assets/pack/jsondefs/parts/engine.json": {"materials": ["minecraft:blaze_powder:0:5", "minecraft:iron_ingot:0:2"]},
                                              "data/pack/recipes/shell.json": {"ingredients": [{"tag": "forge:gunpowder"}]}})
        hits = scan.scan(path, self.catalogue["roots"])
        self.assertEqual({"engine"}, hits["minecraft:blaze_powder"])
        self.assertEqual({"shell"}, hits["minecraft:gunpowder"])
        self.assertNotIn("minecraft:iron_ingot", hits)

    def test_a_root_with_a_route_is_not_a_problem_and_one_without_is(self):
        path = jar(self.folder, "pack.jar", {"data/pack/recipes/a.json": {"item": "minecraft:string"}, "data/pack/recipes/b.json": {"item": "minecraft:nether_star"}})
        _matrix, problems = scan.report([path], self.catalogue)
        self.assertEqual(1, len(problems))
        self.assertIn("minecraft:nether_star", problems[0])

    def test_a_known_cosmetic_use_is_allowed_only_for_its_mod_and_root(self):
        allowed = jar(self.folder, "immersive-vehicles-official-content-pack.jar", {"assets/x/jsondefs/mirrorornament.json": {"materials": ["minecraft:nether_star:0:1"]}})
        other = jar(self.folder, "someother.jar", {"assets/x/jsondefs/mirrorornament.json": {"materials": ["minecraft:nether_star:0:1"]}})
        self.assertEqual([], scan.report([allowed], self.catalogue)[1])
        self.assertTrue(scan.report([other], self.catalogue)[1])

    def test_the_pinned_packs_have_no_unexplained_dependency_when_they_are_available(self):
        jars = sorted(scan.CACHE.glob("*/*/*/*.jar")) if scan.CACHE.is_dir() else []
        if not jars:
            self.skipTest("the pinned jars are not in the Gradle cache of this machine")
        _matrix, problems = scan.report(jars, self.catalogue)
        self.assertEqual([], problems)


if __name__ == "__main__":
    unittest.main()
