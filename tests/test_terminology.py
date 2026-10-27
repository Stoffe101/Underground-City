"""The controlled crop is called Cannabis (owner direction, D-142). The fictional name it had before must not come back into anything a player reads or into a current
document: item and block names, lang files, data (titles, descriptions, blurbs, notes), code that builds text for a player, generators and current documentation.

The old ids live on in exactly one place, ``RenamedIds`` (aliases so that older saves load), and historical reports keep their wording with a note that it was superseded.
"""
from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OLD = re.compile(r"\bsable(?:[ _-]?leaf)?\b|sable_(?:cutting|leaf|cola|seedling|stem|stake)|SableLeaf|sableLeaf", re.IGNORECASE)
#: the alias table that keeps older saves loading, and this test
ALLOWED_FILES = {"rpg-core/src/main/java/io/github/stoffe101/undergroundcity/RenamedIds.java", "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/SkillEffectGameTests.java",
                 "tests/test_terminology.py"}
#: dated reports and the decision log are history: they keep what was true then, and each says the name was superseded
HISTORICAL = re.compile(r"^docs/production/(sprint-\d{4}-\d{2}-\d{2}|oct19-test-readiness|pr23-playtest-readiness|world-playability-overhaul|living-cities-overhaul|gameplay-completion-pass|living-world-roadside|decisions)\.md$")
NOTE = "D-142"


def tracked() -> list[str]:
    out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    return [line for line in out.splitlines() if line]


class TerminologyTest(unittest.TestCase):
    def test_the_old_crop_name_is_gone_from_everything_current(self):
        problems = []
        for name in tracked():
            path = ROOT / name
            if name in ALLOWED_FILES or HISTORICAL.match(name) or path.suffix not in {".java", ".json", ".py", ".md", ".csv", ".toml", ".txt", ".yml"}:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (UnicodeDecodeError, FileNotFoundError):
                continue
            for number, line in enumerate(text.splitlines(), 1):
                if OLD.search(line):
                    problems.append(f"{name}:{number}: {line.strip()[:120]}")
        self.assertEqual(problems, [], "the crop is called Cannabis (D-142):\n" + "\n".join(problems))

    def test_historical_reports_that_use_the_old_name_say_it_was_superseded(self):
        for name in tracked():
            if not HISTORICAL.match(name):
                continue
            text = (ROOT / name).read_text(encoding="utf-8")
            if OLD.search(text):
                self.assertIn(NOTE, text, f"{name} uses the crop's old name without a note pointing at {NOTE}")

    def test_the_names_a_player_reads_are_cannabis(self):
        import json
        lang = json.loads((ROOT / "rpg-core/src/main/resources/assets/underground_city/lang/en_us.json").read_text(encoding="utf-8"))
        self.assertEqual(lang["item.underground_city.cannabis_seeds"], "Cannabis Seeds")
        self.assertEqual(lang["item.underground_city.cannabis_flower"], "Cannabis Flower")
        self.assertEqual(lang["block.underground_city.cannabis_plant"], "Cannabis Plant")

    def test_every_old_id_is_aliased_to_a_registered_new_one(self):
        java = (ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/RenamedIds.java").read_text(encoding="utf-8")
        items = [i["id"] for i in __import__("json").loads((ROOT / "rpg-core/src/main/resources/underground_city_items.json").read_text())["items"]]
        for old, new in (("sable_cutting", "cannabis_seeds"), ("sable_leaf_bale", "cannabis_flower")):
            self.assertIn(f'"{old}", "{new}"', java)
            self.assertIn(new, items)
        self.assertIn('"sable_leaf_crop", "cannabis_plant"', java)


if __name__ == "__main__":
    unittest.main()
