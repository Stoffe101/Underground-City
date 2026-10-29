"""The Windows 11 playtest scripts (scripts/windows): what they write and pass to gradle matches build.gradle, and, where PowerShell is installed, they build the right gradle command and
refuse the unsafe or destructive cases. They run on GitHub's Ubuntu runners under PowerShell 7 and on its Windows runners under PowerShell 7 and Windows PowerShell 5.1
(.github/workflows/windows-scripts.yml, UC_PWSH=powershell); on Windows the stand-ins for gradle, Java and Python are batch files and the backup and rollback run real robocopy."""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = ROOT / "scripts" / "windows"
GRADLE = (ROOT / "rpg-core" / "build.gradle").read_text(encoding="utf-8")
PWSH = os.environ.get("UC_PWSH") or shutil.which("pwsh")
WINDOWS_HOST = os.name == "nt"


def stub(folder: Path, name: str, sh: str, cmd: str) -> Path:
    """A stand-in program: a shell script on Linux, a batch file (name.cmd) on Windows, where PowerShell finds it through PATHEXT."""
    if WINDOWS_HOST:
        path = folder / (name if name.endswith(".bat") else name + ".cmd")
        path.write_text("@echo off\r\n" + cmd.replace("\n", "\r\n"), encoding="utf-8")
    else:
        path = folder / name
        path.write_text("#!/bin/sh\n" + sh, encoding="utf-8")
        path.chmod(0o755)
    return path
sys.path.insert(0, str(ROOT / "tests"))


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

    def test_the_scripts_accept_exactly_the_sets_gradle_knows(self):
        # build.gradle derives its sets from the manifest (every set but the deferred ones, plus Core's scarcity package); the scripts list them so that a typing
        # mistake is caught before gradle starts. The furniture set was once missing here (eleventh pass): equality, not a subset, catches that.
        self.assertIn("def knownSets = (devPackManifest.mods.collectMany { it.sets }.findAll { !it.endsWith('_deferred') } + ['scarcity']).unique()", GRADLE)
        manifest = json.loads((ROOT / "content/modpacks/dev-pack.json").read_text(encoding="utf-8"))
        known = {s for m in manifest["mods"] for s in m["sets"] if not s.endswith("_deferred")} | {"scarcity"}
        common = (WINDOWS / "uc-common.ps1").read_text(encoding="utf-8")
        accepted = re.findall(r"'([a-z_]+)'", re.search(r"UcKnownSets = @\((.*?)\)", common).group(1))
        self.assertEqual(set(accepted), known - {"legacy_travelers"}, "legacy_travelers (the retired backpack, for the save-guard test) is the one set the scripts leave out")
        self.assertNotIn("create", accepted)
        c70 = re.findall(r"'([a-z_]+)'", re.search(r"UcC70Sets = @\((.*?)\)", common).group(1))
        self.assertEqual(c70, ["furnished", "scarcity", "furniture"])

    def test_the_private_folder_gradle_is_given_is_the_property_the_game_reads(self):
        self.assertIn("providers.gradleProperty('ucPrivateContent')", GRADLE)
        for name in ("uc-server.ps1", "uc-client.ps1"):
            self.assertIn('"-PucPrivateContent=$privateDir"', (WINDOWS / name).read_text(encoding="utf-8"), name)

    def test_the_playtest_branch_is_this_passes_branch(self):
        self.assertIn("$script:UcBranch = 'feature/twelfth-pass-playable-alpha'", (WINDOWS / "uc-common.ps1").read_text(encoding="utf-8"))

    def test_offline_mode_is_never_the_default(self):
        script = (WINDOWS / "uc-server.ps1").read_text(encoding="utf-8")
        self.assertIn("[switch] $LanOfflineMode", script)
        self.assertEqual(1, script.count("'online-mode' 'false'"), "online-mode=false is written in one place, behind the switch")


class _Scripts:
    """A copy of the scripts in a scratch repository, with stand-ins for gradle and Java on the PATH and a scratch home folder."""

    def setUp(self):
        self.dir = Path(os.path.realpath(tempfile.mkdtemp()))   # long names on Windows: the scripts resolve RUNNER~1 to runneradmin
        self.repo = self.dir / "repo"
        shutil.copytree(WINDOWS, self.repo / "scripts" / "windows")
        for tool in ("private_c70.py", "private_pack_tool.py"):
            shutil.copy(ROOT / "scripts" / tool, self.repo / "scripts" / tool)
        stub(self.repo, "gradlew.bat", 'echo "$@" > "$(dirname "$0")/gradle-args.txt"\n', 'echo %*> "%~dp0gradle-args.txt"\n')
        bin_dir = self.dir / "bin"
        bin_dir.mkdir()
        stub(bin_dir, "java", 'echo \'openjdk version "17.0.12" 2024-07-16\' 1>&2\n', 'echo openjdk version "17.0.12" 2024-07-16 1>&2\n')
        self.home = self.dir / "home"
        self.home.mkdir()
        self.env = dict(os.environ, PATH=f"{bin_dir}{os.pathsep}{os.environ['PATH']}", HOME=str(self.home), USERPROFILE=str(self.home))
        self.env.pop("UC_PRIVATE_CONTENT_DIR", None)
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def run_script(self, name, *args):
        result = subprocess.run([PWSH, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(self.repo / "scripts" / "windows" / name), *args], cwd=self.repo, env=self.env,
                                capture_output=True, text=True, timeout=180)
        gradle = self.repo / "gradlew.bat"
        called = (self.repo / "gradle-args.txt")
        args_seen = called.read_text(encoding="utf-8").split() if called.exists() else None
        if called.exists():
            called.unlink()
        self.assertTrue(gradle.exists())
        return result, args_seen



@unittest.skipUnless(PWSH, "PowerShell (pwsh, or powershell on Windows) is not installed")
class Behaviour(_Scripts, unittest.TestCase):
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

    def test_pregen_asks_gradle_for_the_islands_and_a_joining_client_cannot(self):
        result, args = self.run_script("uc-server.ps1", "-Sets", "furnished", "-World", "UC-P1", "-LanOfflineMode", "-Pregen")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runServer", "-PucExtraSets=furnished", "-PucPregen=islands"], args)
        result, args = self.run_script("uc-new-world.ps1", "-Name", "Alice", "-World", "UC-P2", "-Pregen")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runClient", "-PucUsername=Alice", "-PucExtraSets=furnished,scarcity", "-PucDevWorldName=UC-P2", "-PucPregen=islands"], args)
        result, args = self.run_script("uc-client.ps1", "-Name", "Alice", "-Join", "-Pregen")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(args)
        self.assertIn("ucPregen", GRADLE, "build.gradle must know the flag the scripts pass")

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

    def staged(self, where: Path) -> Path:
        from test_private_c70 import staged
        return staged(where)

    def test_a_c70_server_loads_the_three_sets_and_the_checked_private_folder(self):
        runtime = self.staged(self.dir / "p")
        result, args = self.run_script("uc-server.ps1", "-C70", "-PrivateContent", str(runtime), "-World", "UC-C1", "-LanOfflineMode")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runServer", "-PucExtraSets=furnished,scarcity,furniture", f"-PucPrivateContent={runtime}"], args)
        self.assertIn("private content: ready", result.stdout)

    def test_c70_without_a_staged_folder_or_with_a_wrong_one_starts_nothing(self):
        result, args = self.run_script("uc-client.ps1", "-Name", "Alice", "-C70")
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(args)
        self.assertIn("uc-private-stage.ps1", result.stdout + result.stderr)
        runtime = self.staged(self.dir / "p")
        for bad in (["-C70", "-PrivateContent", str(runtime / "c70-port")], ["-Sets", "furnished", "-PrivateContent", str(runtime)]):
            result, args = self.run_script("uc-client.ps1", "-Name", "Alice", *bad)
            self.assertNotEqual(0, result.returncode, bad)
            self.assertIsNone(args, bad)
        self.assertIn("furniture", result.stdout, "the set the pack needs is named")

    def test_c70_finds_the_staged_default_and_a_joining_client_needs_no_folder(self):
        default = self.staged(self.home / "UndergroundCityPrivate")
        result, args = self.run_script("uc-new-world.ps1", "-Name", "Alice", "-World", "UC-N1", "-C70")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runClient", "-PucUsername=Alice", "-PucExtraSets=furnished,scarcity,furniture", "-PucDevWorldName=UC-N1", f"-PucPrivateContent={default}"], args)
        shutil.rmtree(default)
        result, args = self.run_script("uc-client.ps1", "-Name", "Bob", "-C70", "-Join")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(["runClient", "-PucUsername=Bob", "-PucExtraSets=furnished,scarcity,furniture", "-PucAutoWorld=false"], args)

    def test_a_private_session_report_leaves_screenshots_out_and_hides_private_ids(self):
        runtime = self.staged(self.dir / "p")
        run = self.repo / "rpg-core" / "run-dev"
        (run / "logs").mkdir(parents=True)
        (run / "logs" / "latest.log").write_text("[UC-PRIVATE] private content folder configured: 1 pack(s) will load\nCould not find entry 'H' for part fixtureworks:house_ground\n", encoding="utf-8")
        (run / "screenshots").mkdir()
        (run / "screenshots" / "inside.png").write_bytes(b"png")
        result, _ = self.run_script("uc-logs.ps1", "-PrivateContent", str(runtime))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        import zipfile
        archive = zipfile.ZipFile(next((self.repo / "reports").glob("uc-report-*.zip")))
        self.assertFalse(any("screenshots" in n for n in archive.namelist()), archive.namelist())
        log = archive.read("logs_latest.log").decode("utf-8")
        self.assertNotIn("fixtureworks:", log)
        self.assertIn("<private>", log)

    def test_the_stage_script_hands_the_owners_paths_to_the_tool(self):
        fake = self.dir / "fakepy"
        fake.mkdir()
        record = self.dir / "py-args.txt"
        stub(fake, "py", 'if [ "$2" = "-c" ]; then echo True; exit 0; fi\necho "$@" > "' + str(record) + '"\n',
             'if "%2"=="-c" (echo True& exit /b 0)\necho %*> "' + str(record) + '"\n')
        self.env["PATH"] = f"{fake}{os.pathsep}{self.env['PATH']}"
        source, registry = self.dir / "c70.zip", self.dir / "registry.json"
        result, args = self.run_script("uc-private-stage.ps1", "-Source", str(source), "-Registry", str(registry), "-Holder", "Owner", "-Summary", "private")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIsNone(args, "staging never starts the game")
        seen = record.read_text(encoding="utf-8").split()
        tool = str(self.repo / "scripts" / "private_c70.py")
        self.assertEqual(["-3", tool, "stage", "--source", str(source), "--out", str(self.home / "UndergroundCityPrivate" / "runtime"), "--registry", str(registry),
                          "--holder", "Owner", "--summary", "private"], seen)

    def test_the_doctor_reads_java_and_the_server_and_changes_nothing(self):
        run = self.repo / "rpg-core" / "run-dev"
        run.mkdir(parents=True)
        (run / "server.properties").write_text("online-mode=false\n", encoding="utf-8")
        result, args = self.run_script("uc-doctor.ps1", "-NoBranchCheck")
        out = result.stdout + result.stderr
        self.assertIsNone(args, "the doctor never starts gradle")
        self.assertIn("OK    Java 17", out)
        self.assertIn("OFFLINE mode", out)
        self.assertIn("no backup of run-dev yet", out)
        self.assertEqual((run / "server.properties").read_text(encoding="utf-8"), "online-mode=false\n")
        self.assertEqual(0, result.returncode, out)

    def test_the_doctor_fails_on_the_wrong_java(self):
        bin_dir = self.dir / "bin"
        for old in bin_dir.iterdir():
            old.unlink()
        stub(bin_dir, "java", 'echo \'openjdk version "21.0.4" 2024-07-16\' 1>&2\n', 'echo openjdk version "21.0.4" 2024-07-16 1>&2\n')
        result, _ = self.run_script("uc-doctor.ps1", "-NoBranchCheck")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("FIX   java -version says", result.stdout)

    def test_restore_lists_and_refuses_an_unknown_backup(self):
        (self.repo / "backups" / "run-dev-20261029-120000" / "UC-S1").mkdir(parents=True)
        (self.repo / "backups" / "run-dev-20261029-120000" / "UC-S1" / "level.dat").write_text("x", encoding="utf-8")
        result, _ = self.run_script("uc-restore.ps1", "-List")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("run-dev-20261029-120000   server worlds: UC-S1", result.stdout)
        for bad in (["-From", "run-dev-19990101-000000"], ["-From", "..\\..\\x"]):
            result, _ = self.run_script("uc-restore.ps1", *bad, "-Force")
            self.assertNotEqual(0, result.returncode, bad)


@unittest.skipUnless(PWSH and WINDOWS_HOST and shutil.which("robocopy"), "robocopy exists only on Windows")
class RealWindows(_Scripts, unittest.TestCase):
    """On a Windows runner: a backup and a rollback with the real robocopy, the world put back as it was and the state before the rollback kept."""

    def test_a_backup_and_a_rollback_round_trip(self):
        run = self.repo / "rpg-core" / "run-dev"
        world = run / "UC-S1"
        world.mkdir(parents=True)
        (world / "level.dat").write_text("before", encoding="utf-8")
        (run / "mods").mkdir()
        (run / "mods" / "a.jar").write_text("jar", encoding="utf-8")
        result, _ = self.run_script("uc-backup.ps1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        backups = sorted(p.name for p in (self.repo / "backups").iterdir())
        self.assertEqual(1, len(backups), backups)
        self.assertFalse((self.repo / "backups" / backups[0] / "mods").exists(), "the mods are not backed up")
        (world / "level.dat").write_text("after", encoding="utf-8")
        (world / "region.mca").write_text("new", encoding="utf-8")
        result, _ = self.run_script("uc-restore.ps1", "-From", backups[0], "-Force")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("before", (world / "level.dat").read_text(encoding="utf-8"))
        self.assertFalse((world / "region.mca").exists(), "a file the backup did not have is gone after the rollback")
        self.assertTrue((run / "mods" / "a.jar").exists(), "the mods folder is left alone")
        after = sorted(p.name for p in (self.repo / "backups").iterdir())
        self.assertEqual(2, len(after), "the state before the rollback is kept as a backup")
        self.assertEqual("after", (self.repo / "backups" / after[1] / "UC-S1" / "level.dat").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
