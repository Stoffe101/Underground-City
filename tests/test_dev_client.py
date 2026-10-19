"""The one-command development client (gradlew runClient) depends on committed, generated inputs and on the pack manifest."""
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from emit_dev_world_pack import OUT, expected  # noqa: E402

MANIFEST = json.loads((ROOT / "content/modpacks/dev-pack.json").read_text(encoding="utf-8"))
CITIES = json.loads((ROOT / "content/region-blueprints/cities.json").read_text(encoding="utf-8"))


class DevClientInputs(unittest.TestCase):
    def test_generated_world_package_is_current(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/emit_dev_world_pack.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_generated_world_package_selects_the_three_city_profile(self):
        files = expected(CITIES)
        self.assertIn("pack/META-INF/mods.toml", files)
        self.assertIn("pack/data/underground_city/lostcities/worldstyles/three_city.json", files)
        profile = json.loads(files["game-config/config/lostcities/profiles/uc_three_city.json"])
        self.assertEqual(profile["lostcity"]["worldStyle"], "underground_city:three_city")
        self.assertIn(b"uc_three_city", files["game-config/defaultconfigs/lostcities-server.toml"])
        for city in CITIES["cities"]:
            self.assertIn(f"pack/data/underground_city/lostcities/predefinedcities/{city['id']}.json", files)

    def test_every_pack_entry_declares_where_it_is_loaded_from(self):
        for mod in MANIFEST["mods"]:
            self.assertIn(mod["placement"], ("classpath", "mods_folder"), mod["id"])
        folder = {m["id"] for m in MANIFEST["mods"] if m["placement"] == "mods_folder"}
        # Immersive Vehicles content packs are found by scanning the mods folder, not the classpath.
        self.assertEqual(folder, {"immersive_vehicles_ocp", "immersive_vehicles_oamp"})

    def test_the_full_development_pack_has_every_required_mod(self):
        ids = {m["mod_id"] for m in MANIFEST["mods"]}
        self.assertTrue({"lostcities", "tacz", "taczblueprints", "modernlife", "mts", "mtsofficialpack", "oamp"} <= ids)
        sets = {s for m in MANIFEST["mods"] for s in m["sets"]}
        # "vintner", "farming", "qol" and "survival" are opt-in candidate sets (-PucPack=...,vintner,farming,qol,survival); the default pack stays the four above.
        # "legacy_travelers" holds only the retired Traveler's Backpack (D-092), for opening an old world once; it is never combined with "survival".
        self.assertEqual(sets, {"world", "firearms", "vehicles", "boats", "vintner", "farming", "qol", "survival", "legacy_travelers"})
        legacy = {m["id"] for m in MANIFEST["mods"] if "legacy_travelers" in m["sets"]}
        # exactly the October 18 survival set: the same three mods and the retired backpack, and never a Sophisticated mod beside it
        self.assertEqual(legacy, {"travelers_backpack", "thirst_was_taken_2", "farmers_delight", "comforts"})
        survival = {m["id"] for m in MANIFEST["mods"] if "survival" in m["sets"]}
        self.assertEqual(survival, {"sophisticated_core", "sophisticated_backpacks", "thirst_was_taken_2", "farmers_delight", "comforts"})
        self.assertEqual(survival & legacy, {"thirst_was_taken_2", "farmers_delight", "comforts"})

    def test_wrapper_and_run_configurations_exist(self):
        for name in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.properties", "settings.gradle"):
            self.assertTrue((ROOT / name).is_file(), name)
        properties = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("distributionSha256Sum=", properties)
        configs = list((ROOT / ".run").glob("*.run.xml"))
        self.assertTrue(any("runClient_full_dev_pack" in c.name for c in configs))


if __name__ == "__main__":
    unittest.main()
