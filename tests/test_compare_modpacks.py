"""Focused tests for safe pack metadata comparison."""
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from compare_modpacks import compare, load, main


def curseforge(projects, mc="1.20.1", loader="forge-47.4.10"):
    return {
        "type": "curseforge",
        "name": "test-pack",
        "minecraft": mc,
        "loader": [loader],
        "referenced_files": [
            {"projectID": item, "fileID": 1000 + item} for item in projects
        ],
    }


class ModpackComparisonTests(unittest.TestCase):
    def test_same_format_reports_shared_projects_and_changes(self):
        left = curseforge([12, 13, 14])
        right = curseforge([12, 14, 15])
        right["referenced_files"][0]["fileID"] = 999
        report = compare(left, right)
        self.assertEqual(report["comparison"]["shared_count"], 2)
        self.assertEqual(report["comparison"]["left_only"], ["13"])
        self.assertEqual(report["comparison"]["right_only"], ["15"])
        self.assertEqual(report["comparison"]["jaccard_similarity"], .5)
        self.assertEqual(report["comparison"]["shared_projects_with_different_file_ids"][0]["project_id"], "12")

    def test_cross_format_does_not_invent_shared_mods(self):
        left = curseforge([123])
        right = {
            "type": "modrinth",
            "minecraft": "1.20.1",
            "loader": {"forge": "47.4.10"},
            "referenced_files": [{"path": "mods/example.jar"}],
        }
        report = compare(left, right)
        self.assertIsNone(report["comparison"])
        self.assertFalse(report["same_identifier_system"])
        self.assertTrue(any("Cross-format" in x for x in report["cautions"]))

    def test_minecraft_mismatch(self):
        result = compare(curseforge([1], "1.20.1"), curseforge([1], "1.21.1"))
        self.assertTrue(result["minecraft_version_mismatch"])

    def test_cli_compares_local_export_zips(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for title, projects in (("first.zip", [10, 11]), ("second.zip", [11, 12])):
                with zipfile.ZipFile(root / title, "w") as archive:
                    archive.writestr("manifest.json", json.dumps({
                        "name": title,
                        "minecraft": {"version": "1.20.1",
                                      "modLoaders": [{"id": "forge-47.4.10"}]},
                        "files": [{"projectID": p, "fileID": p + 100} for p in projects]
                    }))
            output = root / "comparison.json"
            self.assertEqual(main([str(root / "first.zip"), str(root / "second.zip"),
                                   "--output", str(output)]), 0)
            content = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(content["comparison"]["shared_ids"], ["11"])


if __name__ == "__main__":
    unittest.main()
