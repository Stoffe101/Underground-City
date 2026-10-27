"""The private-pack tool: manifests that name their permission, seals that notice changes, backups that stay outside a checkout, and a synthetic fixture."""
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import private_pack_tool as tool  # noqa: E402


class PackToolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="uc-pack-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def test_init_writes_a_manifest_that_names_the_permission_and_is_private_only(self):
        pack = self.tmp / "p"
        tool.init(pack, "demo", "Demo", "the holder", "what it covers", True, False)
        manifest = json.loads((pack / tool.MANIFEST).read_text())
        self.assertTrue(manifest["private_only"])
        self.assertEqual(manifest["permission"]["holder"], "the holder")
        self.assertTrue((pack / "pack.mcmeta").is_file())

    def test_init_refuses_a_missing_holder_and_a_bad_id(self):
        with self.assertRaises(SystemExit):
            tool.init(self.tmp / "a", "demo", "Demo", " ", "x", True, False)
        with self.assertRaises(SystemExit):
            tool.init(self.tmp / "b", "Bad Id", "Demo", "h", "x", True, False)

    def test_a_pack_inside_a_git_work_tree_is_refused(self):
        with self.assertRaises(SystemExit):
            tool.init(ROOT / "private-content" / "x", "demo", "Demo", "h", "s", True, False)

    def test_seal_then_verify_notices_a_changed_a_new_and_a_missing_file(self):
        pack = self.tmp / "p"
        tool.init(pack, "demo", "Demo", "h", "s", True, False)
        (pack / "data").mkdir()
        (pack / "data" / "a.json").write_text("{}")
        tool.seal(pack)
        self.assertEqual(tool.verify(pack), 0)
        (pack / "data" / "a.json").write_text('{"changed": true}')
        (pack / "data" / "b.json").write_text("{}")
        self.assertEqual(tool.verify(pack), 1)
        (pack / "data" / "a.json").unlink()
        self.assertEqual(tool.verify(pack), 1)

    def test_the_index_holds_names_and_hashes_and_never_content(self):
        pack = self.tmp / "p"
        tool.init(pack, "demo", "Demo", "h", "s", True, False)
        (pack / "data").mkdir()
        (pack / "data" / "a.json").write_text('{"a_distinctive_marker": 1}')
        tool.seal(pack)
        self.assertNotIn("a_distinctive_marker", (pack / tool.INDEX).read_text())

    def test_backup_and_restore_round_trip_and_never_overwrite(self):
        pack = self.tmp / "p"
        tool.init(pack, "demo", "Demo", "h", "s", True, False)
        (pack / "data").mkdir()
        (pack / "data" / "a.json").write_text("{}")
        tool.seal(pack)
        archive = self.tmp / "backup.zip"
        tool.backup(pack, archive)
        with self.assertRaises(SystemExit):
            tool.backup(pack, archive)
        again = self.tmp / "restored"
        tool.restore(archive, again)
        self.assertEqual(tool.verify(again), 0)
        with self.assertRaises(SystemExit):
            tool.restore(archive, again)

    def test_the_fixture_is_synthetic_and_loadable_data(self):
        tool.fixture(self.tmp)
        pack = self.tmp / "uc_private_fixture"
        manifest = json.loads((pack / tool.MANIFEST).read_text())
        self.assertIn("synthetic", manifest["permission"]["holder"])
        data = json.loads((pack / "data/underground_city/building_security/zz_private_fixture.json").read_text())
        self.assertEqual(data["schema_version"], 1)
        self.assertIn("private_fixture_class", data["classes"])
        self.assertEqual(tool.verify(pack), 0)


if __name__ == "__main__":
    unittest.main()
