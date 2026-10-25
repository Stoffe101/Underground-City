"""The Windows 11 playtest scripts (scripts/windows): what they write and pass to gradle matches build.gradle, and, where PowerShell 7 is installed (GitHub's Ubuntu runners have it), they build the
right gradle command and refuse the unsafe or destructive cases. Robocopy (the backup and the second copy) exists only on Windows and is not exercised here."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = ROOT / "scripts" / "windows"
GRADLE = (ROOT / "rpg-core" / "build.gradle").read_text(encoding="utf-8")
PWSH = os.environ.get("UC_PWSH") or shutil.which("pwsh")


def gradle_server_properties() -> list[str]:
    """The lines prepareDevRun writes for a new archipelago dev server."""
    block = re.search(r"def properties = \[(.*?)\]", GRADLE, re.S).group(1)
    lines = re.findall(r"'([^']+)'", block)
    archipelago = re.search(r"properties\.add\(0, '([^']+)'\)", GRADLE).group(1)
    return [archipelago] + lines


class Static(unittest.TestCase):
    def test_the_server_script_writes_the_same_new_server_properties_as_gradle(self):
        script = (WINDOWS / "uc-server.ps1").read_text(encoding="utf-8")
        written = re.search(r"Set-Content -Path \$props -Encoding ASCII -Value @\((.*?)\)\n", script, re.S).group(1)
        self.assertEqual(gradle_server_properties(), re.findall(r"'([^']+)'", written))

    def test_every_set_the_scripts_accept_is_one_gradle_knows(self):
        known = re.search(r"known sets: ([a-z_, ]+)\)", GRADLE).group(1).split(", ")
        accepted = re.findall(r"'([a-z_]+)'", re.search(r"UcKnownSets = @\((.*?)\)", (WINDOWS / "uc-common.ps1").read_text(encoding="utf-8")).group(1))
        self.assertTrue(set(accepted) <= set(known), sorted(set(accepted) - set(known)))
        self.assertNotIn("create", accepted)

    def test_offline_mode_is_never_the_default(self):
        script = (WINDOWS / "uc-server.ps1").read_text(encoding="utf-8")
        self.assertIn("[switch] $LanOfflineMode", script)
        self.assertEqual(1, script.count("'online-mode' 'false'"), "online-mode=false is written in one place, behind the switch")


@unittest.skipUnless(PWSH, "PowerShell 7 (pwsh) is not installed")
class Behaviour(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.repo = self.dir / "repo"
        shutil.copytree(WINDOWS, self.repo / "scripts" / "windows")
        stub = self.repo / "gradlew.bat"
        stub.write_text('#!/bin/sh\necho "$@" > "$(dirname "$0")/gradle-args.txt"\n', encoding="utf-8")
        stub.chmod(0o755)
        bin_dir = self.dir / "bin"
        bin_dir.mkdir()
        java = bin_dir / "java"
        java.write_text('#!/bin/sh\necho \'openjdk version "17.0.12" 2024-07-16\' 1>&2\n', encoding="utf-8")
        java.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}")
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_script(self, name, *args):
        result = subprocess.run([PWSH, "-NoProfile", "-File", str(self.repo / "scripts" / "windows" / name), *args], cwd=self.repo, env=self.env, capture_output=True, text=True, timeout=120)
        gradle = self.repo / "gradlew.bat"
        called = (self.repo / "gradle-args.txt")
        args_seen = called.read_text(encoding="utf-8").split() if called.exists() else None
        if called.exists():
            called.unlink()
        self.assertTrue(gradle.exists())
        return result, args_seen

    def test_a_new_lan_server_world_and_the_refusal_to_stay_offline_silently(self):
        result, args = self.run_script("uc-server.ps1", "-Sets", "furnished,scarcity,navigation", "-World", "UC-T1", "-LanOfflineMode", "-AcceptEula")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runServer", "-PucExtraSets=furnished,scarcity", "-PucAcceptEula=true"], args, "Xaero is left out of the server")
        props = (self.repo / "rpg-core" / "run-dev" / "server.properties").read_text(encoding="utf-8").splitlines()
        self.assertEqual(gradle_server_properties() + ["level-name=UC-T1", "online-mode=false"], props)
        result, args = self.run_script("uc-server.ps1")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(args, "a server left in offline mode does not start without the switch")

    def test_a_client_joins_with_xaero_and_bad_input_is_refused(self):
        result, args = self.run_script("uc-client.ps1", "-Name", "Alice", "-Sets", "furnished,scarcity", "-Join", "-Xaero")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runClient", "-PucUsername=Alice", "-PucExtraSets=furnished,scarcity,navigation", "-PucAutoWorld=false"], args)
        for bad in (["-Name", "A"], ["-Name", "Alice", "-Sets", "create"], ["-Name", "Alice", "-Join", "-World", "UC-X"]):
            result, args = self.run_script("uc-client.ps1", *bad)
            self.assertNotEqual(0, result.returncode, bad)
            self.assertIsNone(args, bad)

    def test_a_new_world_never_replaces_one(self):
        (self.repo / "rpg-core" / "run-dev" / "saves" / "UC-S1").mkdir(parents=True)
        result, args = self.run_script("uc-new-world.ps1", "-Name", "Alice", "-World", "UC-S1")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(args)
        result, args = self.run_script("uc-new-world.ps1", "-Name", "Alice", "-World", "UC-S2")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runClient", "-PucUsername=Alice", "-PucExtraSets=furnished,scarcity", "-PucDevWorldName=UC-S2"], args)

    def test_the_report_holds_logs_and_no_world(self):
        run = self.repo / "rpg-core" / "run-dev"
        (run / "logs").mkdir(parents=True)
        (run / "logs" / "latest.log").write_text("log\n", encoding="utf-8")
        (run / "saves" / "UC-S1").mkdir(parents=True)
        (run / "saves" / "UC-S1" / "level.dat").write_text("x", encoding="utf-8")
        result, _ = self.run_script("uc-logs.ps1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        zips = list((self.repo / "reports").glob("uc-report-*.zip"))
        self.assertEqual(1, len(zips))
        import zipfile
        names = zipfile.ZipFile(zips[0]).namelist()
        self.assertIn("logs_latest.log", names)
        self.assertFalse(any("level.dat" in n or "saves" in n for n in names), names)


if __name__ == "__main__":
    unittest.main()
