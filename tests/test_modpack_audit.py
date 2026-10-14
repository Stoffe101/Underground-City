"""No Minecraft, external packages or network required."""
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from audit_modpack import audit_mod_directory, read_pack


class ModpackAuditTests(unittest.TestCase):
    def test_curseforge_export(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "pack.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("manifest.json", json.dumps({
                    "name": "Example", "minecraft": {
                        "version": "1.20.1", "modLoaders": [{"id": "forge-47.4.10"}]
                    }, "files": [{"projectID": 123, "fileID": 456}]
                }))
            summary = read_pack(path)
            self.assertEqual(summary["type"], "curseforge")
            self.assertEqual(summary["entries"], 1)
            self.assertEqual(summary["minecraft"], "1.20.1")

    def test_modrinth_export(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "pack.mrpack"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("modrinth.index.json", json.dumps({
                    "name": "Example",
                    "dependencies": {"minecraft": "1.20.1", "forge": "47.4.10"},
                    "files": []
                }))
            self.assertEqual(read_pack(path)["loader"]["forge"], "47.4.10")

    def test_duplicate_and_mandatory_dependency(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder)
            toml = '''modLoader="javafml"
[[mods]]
modId="example"
[[dependencies.example]]
modId="absentlibrary"
mandatory=true
'''
            for name in ("one.jar", "two.jar"):
                with zipfile.ZipFile(p / name, "w") as archive:
                    archive.writestr("META-INF/mods.toml", toml)
            report = audit_mod_directory(p)
            self.assertEqual(len(report["duplicate_mod_ids"]["example"]), 2)
            self.assertEqual(len(report["possibly_missing_dependencies"]), 2)

    def test_no_metadata_warning(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder)
            with zipfile.ZipFile(p / "library.jar", "w") as archive:
                archive.writestr("someclass.class", b"not a real Java class")
            report = audit_mod_directory(p)
            self.assertIn("No recognized", report["jars"][0]["issues"][0])


if __name__ == "__main__":
    unittest.main()
