"""scripts/ci_run_log.py: a CI check sees the whole run's log when Forge rolled latest.log over at midnight (October 27: two jobs failed their check although
every GameTest had passed)."""
import gzip
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import ci_run_log  # noqa: E402


class CiRunLogTest(unittest.TestCase):
    def logs(self, files: dict) -> Path:
        folder = Path(tempfile.mkdtemp()) / "logs"
        folder.mkdir()
        for name, text in files.items():
            data = text.encode()
            (folder / name).write_bytes(gzip.compress(data) if name.endswith(".gz") else data)
        return folder

    def test_a_run_over_midnight_is_read_whole_and_in_order(self):
        logs = self.logs({"2026-10-26-1.log.gz": "[23:59:57] All 444 required tests passed :)\n", "latest.log": "[00:00:02] Saving chunks\n",
                          "2026-10-26-2.log.gz": "[23:59:59] Saving worlds\n", "debug-1.log.gz": "a size-rolled debug log\n"})
        text = ci_run_log.combine(logs).read_text()
        self.assertEqual(text, "[23:59:57] All 444 required tests passed :)\n[23:59:59] Saving worlds\n[00:00:02] Saving chunks\n")

    def test_a_run_within_one_day_is_its_latest_log(self):
        logs = self.logs({"latest.log": "All 3 required tests passed :)\n"})
        self.assertEqual(ci_run_log.combine(logs).read_text(), "All 3 required tests passed :)\n")

    def test_the_archives_follow_forges_counter_not_the_alphabet(self):
        logs = self.logs({"2026-10-26-10.log.gz": "ten\n", "2026-10-26-9.log.gz": "nine\n", "latest.log": "now\n"})
        self.assertEqual(ci_run_log.combine(logs).read_text(), "nine\nten\nnow\n")

    def test_no_latest_log_is_a_failure(self):
        logs = self.logs({"2026-10-26-1.log.gz": "old\n"})
        self.assertEqual(ci_run_log.main([str(logs)]), 1)


if __name__ == "__main__":
    unittest.main()
