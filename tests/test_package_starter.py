"""Verify starter exports contain only approved original content."""
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from package_starter import package, validate_original_jar


class PackCreationTests(unittest.TestCase):
    def make_jar(self, location: Path) -> Path:
        jar = location / "underground_city-0.1.0.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/mods.toml", 'modLoader="javafml"\n[[mods]]\nmodId="underground_city"')
        return jar

    def test_archives_only_contain_original_mod(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = self.make_jar(root)
            modrinth, curseforge = package(jar, root / "output")
            for archive_path, manifest in (
                (modrinth, "modrinth.index.json"),
                (curseforge, "manifest.json"),
            ):
                with zipfile.ZipFile(archive_path) as archive:
                    names = archive.namelist()
                    self.assertEqual(len(names), 3)
                    self.assertIn(manifest, names)
                    self.assertIn("overrides/mods/" + jar.name, names)
                    self.assertEqual(archive.read("overrides/mods/" + jar.name), jar.read_bytes())
                    self.assertNotIn("modrinth.index.json" if manifest == "manifest.json" else "manifest.json", names)
                    metadata = json.loads(archive.read(manifest))
                    self.assertEqual(metadata["minecraft"]["version"]
                                     if manifest == "manifest.json"
                                     else metadata["dependencies"]["minecraft"], "1.20.1")

    def test_rejects_invalid_jar(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "underground_city-0.1.0.jar"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("META-INF/mods.toml", 'modId="not_ours"')
            with self.assertRaises(ValueError):
                validate_original_jar(path)


if __name__ == "__main__":
    unittest.main()
