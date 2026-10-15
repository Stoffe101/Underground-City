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
from build_world_prototype import LOOT, package, render, road_feature, validate
WORLD = json.loads((ROOT / "content/region-blueprints/cities.json").read_text(encoding="utf-8"))
CORRIDORS = json.loads((ROOT / "content/region-blueprints/corridors.json").read_text(encoding="utf-8"))
ROAD = "data/underground_city/worldgen/configured_feature/corridor_roads.json"


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

    def test_fixed_city_style_does_not_suppress_ocean_anchors(self):
        style=json.loads(render(WORLD)["data/underground_city/lostcities/worldstyles/three_city.json"])
        self.assertEqual(style["citybiomemultipliers"], [])
        self.assertEqual(style["outsidestyle"], "lostcities:outside")

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

    def test_road_feature_is_generated_from_blueprints_only(self):
        files = render(WORLD)
        configured = json.loads(files[ROAD])
        self.assertEqual(configured["type"], "underground_city:corridor_roads")
        config = configured["config"]
        self.assertEqual(config["width"], CORRIDORS["construction_policy"]["road_width_blocks"])
        built_kinds = set(CORRIDORS["road_generation"]["build_route_kinds"])
        expected = [r for r in CORRIDORS["routes"] if r["kind"] in built_kinds]
        self.assertEqual([r["id"] for r in config["routes"]], [r["id"] for r in expected])
        # Every pair of cities has a built road: three bridges for three corridors.
        self.assertEqual(len(expected), 3)
        self.assertEqual({frozenset((r["from"], r["to"])) for r in config["routes"] and expected},
                         {frozenset(("ironhaven", "valewood")), frozenset(("ironhaven", "blackwater")),
                          frozenset(("valewood", "blackwater"))})
        for route, source in zip(config["routes"], expected):
            self.assertEqual(route["waypoints"], source["waypoints"])
        # City circles are exactly the Lost Cities predefined cities (centre of the centre chunk).
        by_id = {c["id"]: c for c in config["cities"]}
        for city in WORLD["cities"]:
            circle = by_id[city["id"]]
            self.assertEqual(circle["x"], city["center_chunk"][0] * 16 + 8)
            self.assertEqual(circle["z"], city["center_chunk"][1] * 16 + 8)
            self.assertEqual(circle["radius"], city["radius_blocks"])
        placed = json.loads(files["data/underground_city/worldgen/placed_feature/corridor_roads.json"])
        self.assertEqual(placed, {"feature": "underground_city:corridor_roads", "placement": []})
        modifier = json.loads(files["data/underground_city/forge/biome_modifier/corridor_roads.json"])
        self.assertEqual(modifier["type"], "forge:add_features")
        self.assertEqual(modifier["step"], "top_layer_modification")
        self.assertEqual(modifier["biomes"], "#minecraft:is_overworld")

    def test_world_package_requires_the_core_mod_that_registers_the_road_feature(self):
        metadata = tomllib.loads(render(WORLD)["META-INF/mods.toml"].decode())
        dependencies = {d["modId"]: d for d in metadata["dependencies"]["underground_city_world"]}
        self.assertTrue(dependencies["underground_city"]["mandatory"])
        self.assertTrue(dependencies["lostcities"]["mandatory"])

    def test_road_feature_rejects_unsafe_policies(self):
        bad = copy.deepcopy(CORRIDORS)
        bad["construction_policy"]["road_width_blocks"] = 40
        with self.assertRaisesRegex(ValueError, "road_width_blocks"):
            road_feature(WORLD, bad)
        bad = copy.deepcopy(CORRIDORS)
        bad["road_generation"]["new_chunks_only"] = False
        with self.assertRaisesRegex(ValueError, "newly generated"):
            road_feature(WORLD, bad)
        bad = copy.deepcopy(CORRIDORS)
        bad["road_generation"]["build_route_kinds"] = ["nothing"]
        with self.assertRaisesRegex(ValueError, "No buildable"):
            road_feature(WORLD, bad)
        bad = copy.deepcopy(CORRIDORS)
        bad["routes"][0]["waypoints"][0] = [1.5, 2]
        with self.assertRaisesRegex(ValueError, "integer"):
            road_feature(WORLD, bad)


if __name__ == "__main__":
    unittest.main()
