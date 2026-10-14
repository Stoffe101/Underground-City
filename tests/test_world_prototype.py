import copy
import hashlib
import json
import sys
import tempfile
import unittest
import zipfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build_world_prototype import LOOT, package, render, validate
WORLD = json.loads((ROOT / "content/region-blueprints/cities.json").read_text(encoding="utf-8"))


class WorldPrototypeTests(unittest.TestCase):
    def test_blueprint_and_city_spacing(self):
        validate(WORLD)
        self.assertEqual(
            {x["id"] for x in WORLD["cities"]},
            {"ironhaven", "valewood", "blackwater"},
        )

    def test_three_predefined_cities_have_unique_styles(self):
        files = render(WORLD)
        base = "data/underground_city/lostcities/"
        city_files = [k for k in files if k.startswith(base+"predefinedcities/")]
        style_files = [k for k in files if k.startswith(base+"citystyles/")]
        self.assertEqual(len(city_files), 3)
        self.assertEqual(len(style_files), 3)
        for city in WORLD["cities"]:
            definition = json.loads(files[base+f"predefinedcities/{city['id']}.json"])
            style = json.loads(files[base+f"citystyles/{city['id']}.json"])
            self.assertEqual(definition["chunkx"], city["center_chunk"][0])
            self.assertEqual(definition["chunkz"], city["center_chunk"][1])
            self.assertEqual(definition["radius"], city["radius_blocks"])
            self.assertEqual(definition["citystyle"], city["city_style"])
            self.assertEqual(style["inherit"], "lostcities:citystyle_standard")
            self.assertEqual(style["buildingsettings"]["maxfloors"], city["max_floors"])

    def test_world_package_accepts_actual_pinned_forge_mod_version(self):
        metadata = tomllib.loads(render(WORLD)["META-INF/mods.toml"].decode())
        dependency = metadata["dependencies"]["underground_city_world"][0]
        self.assertEqual(dependency["modId"], "lostcities")
        # The published Forge manifest includes its game-prefix, not plain 7.x.
        self.assertEqual(dependency["versionRange"], "[1.20-7.5.5]")

    def test_no_unknown_modded_ids_in_prototype_loot(self):
        files = render(WORLD)
        loot_files = [k for k in files if "/loot_tables/chests/" in k]
        self.assertEqual(len(loot_files), len(LOOT))
        for name in loot_files:
            doc = json.loads(files[name])
            self.assertEqual(doc["type"], "minecraft:chest")
            for pool in doc["pools"]:
                for entry in pool["entries"]:
                    self.assertTrue(entry["name"].startswith("minecraft:"))
                    self.assertGreater(entry["weight"], 0)

    def test_reproducible_original_only_zip(self):
        with tempfile.TemporaryDirectory() as folder:
            a = package(WORLD, Path(folder)/"one.jar")
            b = package(WORLD, Path(folder)/"two.jar")
            self.assertEqual(hashlib.sha256(a.read_bytes()).digest(),
                             hashlib.sha256(b.read_bytes()).digest())
            with zipfile.ZipFile(a) as archive:
                names = archive.namelist()
                self.assertIn("META-INF/mods.toml", names)
                self.assertIn("pack.mcmeta", names)
                self.assertFalse(any(n.endswith((".class", ".mca", ".nbt", ".schem"))
                                     for n in names))
                self.assertEqual(len(names), len(render(WORLD)))

    def test_rejects_overlapping_cities_and_unbounded_random_generation(self):
        bad = copy.deepcopy(WORLD)
        bad["cities"][1]["center_chunk"] = [0, 0]
        with self.assertRaisesRegex(ValueError, "overlap"):
            validate(bad)
        bad = copy.deepcopy(WORLD)
        bad["profile_requirements"]["random_city_chance"] = 0.01
        with self.assertRaisesRegex(ValueError, "random cities"):
            validate(bad)


if __name__ == "__main__":
    unittest.main()
