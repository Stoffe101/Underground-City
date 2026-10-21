"""The private-content guard: it must catch what it exists to catch, on real git repositories made for the test, and the real repository must pass it."""
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import check_private_content as guard  # noqa: E402


def git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)


class GuardTest(unittest.TestCase):
    def make_repo(self) -> Path:
        repo = Path(tempfile.mkdtemp(prefix="uc-guard-"))
        self.addCleanup(shutil.rmtree, repo, ignore_errors=True)
        git(repo, "init", "-q")
        git(repo, "config", "user.email", "test@example.invalid")
        git(repo, "config", "user.name", "Test")
        (repo / "content").mkdir()
        shutil.copy(ROOT / guard.POLICY_PATH, repo / guard.POLICY_PATH)
        policy = json.loads((repo / guard.POLICY_PATH).read_text())
        (repo / ".gitignore").write_text("\n".join(policy["required_gitignore_patterns"]) + "\n")
        (repo / "README.md").write_text("a public project\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-qm", "start")
        return repo

    def add(self, repo: Path, name: str, text: str = "{}") -> None:
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        git(repo, "add", "-f", name)

    def test_a_clean_repository_passes(self):
        repo = self.make_repo()
        self.assertEqual(guard.run(repo), [])

    def test_the_real_repository_passes(self):
        self.assertEqual(guard.run(ROOT, history=False), [])

    def test_a_tracked_private_folder_is_caught_even_when_forced_past_gitignore(self):
        repo = self.make_repo()
        self.add(repo, "private-content/anything.json")
        self.add(repo, "kubejs/data/c70cities/lostcities/buildings/x.json")
        issues = guard.run(repo, history=False)
        self.assertTrue(any("private-content/anything.json" in i for i in issues), issues)
        self.assertTrue(any("c70cities/lostcities/buildings/x.json" in i for i in issues), issues)

    def test_a_namespace_inside_tracked_data_is_caught_but_not_inside_the_allowlist(self):
        repo = self.make_repo()
        self.add(repo, "data/mine/worldstyle.json", '{"style": "c70cities:modern"}')
        self.add(repo, "scripts/check_private_content.py", "c70cities")   # allowlisted by the policy
        issues = guard.run(repo, history=False)
        self.assertTrue(any("data/mine/worldstyle.json" in i for i in issues), issues)
        self.assertFalse(any("check_private_content.py" in i for i in issues), issues)

    def test_a_script_that_names_the_private_source_is_caught(self):
        repo = self.make_repo()
        self.add(repo, "scripts/fetch.py", "url = 'https://github.com/TqLxQuanZ/DeceasedCraft'\n")
        self.assertTrue(any("scripts/fetch.py" in i for i in guard.run(repo, history=False)))

    def test_a_missing_gitignore_pattern_is_caught(self):
        repo = self.make_repo()
        (repo / ".gitignore").write_text("*.log\n")
        issues = guard.run(repo, history=False)
        self.assertTrue(any("/private-content/" in i for i in issues), issues)

    def test_a_workflow_may_not_upload_a_private_folder_or_name_the_source(self):
        repo = self.make_repo()
        self.add(repo, ".github/workflows/x.yml", "jobs:\n  a:\n    steps:\n      - name: save\n        uses: actions/upload-artifact@v4\n        with:\n          path: private-content/\n")
        self.add(repo, ".github/workflows/y.yml", "jobs:\n  a:\n    steps:\n      - run: git clone https://github.com/tqlxquanz/deceasedcraft\n")
        issues = guard.run(repo, history=False)
        self.assertTrue(any("x.yml" in i and "artifact" in i for i in issues), issues)
        self.assertTrue(any("y.yml" in i for i in issues), issues)

    def test_history_remembers_a_file_that_was_committed_and_then_removed(self):
        repo = self.make_repo()
        self.add(repo, "private-content/leak.json")
        git(repo, "commit", "-qm", "oops")
        git(repo, "rm", "-q", "-f", "private-content/leak.json")
        git(repo, "commit", "-qm", "remove")
        self.assertEqual(guard.run(repo, history=False), [], "the working tree is clean again")
        self.assertTrue(any("history" in i and "leak.json" in i for i in guard.run(repo)), "the history still holds it")

    def test_staged_mode_sees_only_what_is_about_to_be_committed(self):
        repo = self.make_repo()
        self.add(repo, "private-content/new.json")
        self.assertTrue(any("new.json" in i for i in guard.run(repo, staged=True)))
        git(repo, "reset", "-q")
        self.assertEqual(guard.run(repo, staged=True), [])

    def test_a_branch_is_checked_against_its_base(self):
        repo = self.make_repo()
        base = subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        self.add(repo, "assets/c70cities/part.json")
        git(repo, "commit", "-qm", "add")
        issues = guard.run(repo, base=base, history=False)
        self.assertTrue(any("added since" in i for i in issues), issues)

    def test_the_report_never_contains_file_content(self):
        repo = self.make_repo()
        self.add(repo, "data/x.json", '{"secret_marker_text": "c70cities:building_apartment1_ground"}')
        text = "\n".join(guard.run(repo, history=False))
        self.assertNotIn("secret_marker_text", text)
        self.assertNotIn("building_apartment1_ground", text)


if __name__ == "__main__":
    unittest.main()
