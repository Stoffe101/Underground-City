"""The stall probe's configuration override (scripts/run_stall_probe.py): the world set's Lost Cities defaults name only the profile, so an override of another key must be added to the [profiles] table, and an existing key must be rewritten."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import run_stall_probe as probe  # noqa: E402

DEFAULTS = '[profiles]\nselectedProfile = "uc_three_city"\nselectedCustomJson = ""\n'


class Overrides(unittest.TestCase):
    def folder(self, text=DEFAULTS):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        run = Path(tmp.name)
        (run / "defaultconfigs").mkdir()
        (run / "defaultconfigs" / "lostcities-server.toml").write_text(text, encoding="utf-8")
        (run / "config").mkdir()
        return run

    def test_a_missing_key_is_added_to_the_profiles_table(self):
        run = self.folder()
        probe.apply_overrides(run, ["forceSaplingGrowth=false"])
        text = (run / "defaultconfigs" / "lostcities-server.toml").read_text(encoding="utf-8")
        self.assertIn("[profiles]\nforceSaplingGrowth = false\n", text)
        self.assertIn('selectedProfile = "uc_three_city"', text)

    def test_an_existing_key_is_rewritten(self):
        run = self.folder(DEFAULTS + "forceSaplingGrowth = true\n")
        probe.apply_overrides(run, ["forceSaplingGrowth=false"])
        text = (run / "defaultconfigs" / "lostcities-server.toml").read_text(encoding="utf-8")
        self.assertIn("forceSaplingGrowth = false", text)
        self.assertNotIn("true", text)


class GeneratedDefault(unittest.TestCase):
    def test_the_world_set_ships_the_tree_growth_default_for_new_worlds(self):
        """D-096: the generated defaults carry forceSaplingGrowth = false; the generator and the committed file agree (emit_dev_world_pack --check also runs in CI)."""
        import json
        import install_world_prototype as iwp
        from build_world_prototype import DEFAULT
        files = iwp.config_files(json.loads(DEFAULT.read_text(encoding="utf-8")))
        self.assertIn("forceSaplingGrowth = false", files["defaultconfigs/lostcities-server.toml"])
        committed = (Path(__file__).resolve().parents[1] / "content/generated/world-prototype/game-config/defaultconfigs/lostcities-server.toml").read_text(encoding="utf-8")
        self.assertEqual(committed, files["defaultconfigs/lostcities-server.toml"])


if __name__ == "__main__":
    unittest.main()
