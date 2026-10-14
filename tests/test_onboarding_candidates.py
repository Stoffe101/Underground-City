"""Integration-like tests of starter manifest generation, no external mod binaries."""
import copy
import hashlib
import json
import sys
import tempfile
import tomllib
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from build_onboarding_candidates import (
    build, class_enhancement_fragment, generate_manifest, validate_profiles
)

PROFILES = json.loads(
    (ROOT / "content/modpacks/onboarding-candidates.json").read_text(encoding="utf-8")
)
CATALOG = json.loads(
    (ROOT / "content/onboarding/catalog.json").read_text(encoding="utf-8")
)


class CandidatePackTests(unittest.TestCase):
    def test_all_profiles_have_one_pinned_release(self):
        validate_profiles(PROFILES)
        self.assertEqual(len(PROFILES["profiles"]), 3)
        for p in PROFILES["profiles"]:
            manifest = generate_manifest(PROFILES, p)
            self.assertEqual(len(manifest["files"]), 1)
            self.assertEqual(manifest["files"][0]["projectID"], p["project_id"])
            self.assertEqual(manifest["files"][0]["fileID"], p["file_id"])
            self.assertEqual(manifest["minecraft"]["modLoaders"][0]["id"], "forge-47.4.10")

    def test_archives_are_binary_free_and_reproducible(self):
        with tempfile.TemporaryDirectory() as root:
            a = build(PROFILES, CATALOG, Path(root) / "a")
            b = build(PROFILES, CATALOG, Path(root) / "b")
            self.assertEqual([x.name for x in a], [x.name for x in b])
            for first, second in zip(a, b):
                self.assertEqual(
                    hashlib.sha256(first.read_bytes()).digest(),
                    hashlib.sha256(second.read_bytes()).digest(),
                )
                with zipfile.ZipFile(first) as z:
                    names = set(z.namelist())
                    self.assertIn("manifest.json", names)
                    self.assertIn("overrides/UNDERGROUND_CITY_FIRST_LOGIN_TEST.txt", names)
                    self.assertFalse(any(n.endswith((".jar", ".mca", ".nbt", ".schem")) for n in names))
                    self.assertTrue(all(n.startswith("overrides/") or n == "manifest.json" for n in names))
                    manifest = json.loads(z.read("manifest.json"))
                    self.assertEqual(len(manifest["files"]), 1)

    def test_generated_class_fragment_exactly_matches_careers(self):
        fragment = class_enhancement_fragment(CATALOG)
        parsed = tomllib.loads(fragment)
        self.assertEqual(len(parsed["custom_classes"]), len(CATALOG["professions"]))
        for i, career in enumerate(CATALOG["professions"]):
            section = parsed["custom_classes"][f"custom_{i}"]
            self.assertEqual(section["id"], career["id"])
            self.assertEqual(section["commands"], [])
            self.assertEqual(section["items"], [])
            self.assertEqual(section["attributes"], [])

    def test_pins_and_sources_are_required(self):
        broken = copy.deepcopy(PROFILES)
        broken["profiles"][0]["file_id"] = 0
        with self.assertRaisesRegex(ValueError, "positive integer"):
            validate_profiles(broken)
        broken = copy.deepcopy(PROFILES)
        broken["profiles"][0]["source"] = "https://example.org/not-the-author"
        with self.assertRaisesRegex(ValueError, "source URL"):
            validate_profiles(broken)
        broken = copy.deepcopy(PROFILES)
        broken["profiles"][1]["id"] = broken["profiles"][0]["id"]
        with self.assertRaisesRegex(ValueError, "Duplicate profile"):
            validate_profiles(broken)


if __name__ == "__main__":
    unittest.main()
