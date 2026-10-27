#!/usr/bin/env python3
"""The whole log of one game run, for CI checks that grep it.

Forge 47.4.10 rolls ``logs/latest.log`` over at midnight (log4j's time-based policy, into ``logs/<yyyy-MM-dd>-<n>.log.gz``). A job that runs past 00:00 UTC
then has the first part of its run in the archive and only the rest in ``latest.log``. A check that greps ``latest.log`` alone failed although every GameTest
had passed (October 27: two jobs whose servers reported "All 444 required tests passed" at 23:59:57 and 00:00:00).

This writes the dated archives, oldest first, followed by ``latest.log``, to ``<logs folder>/run-complete.log`` and prints that path:

    log=$(python3 scripts/ci_run_log.py rpg-core/run/logs)
    grep -E "All [0-9]+ required tests passed" "$log"

A CI job uses each run folder once, so every dated archive in it is from the same run. ``debug-<n>.log.gz`` (rolled by size) is left out. Standard library only.
"""
from __future__ import annotations

import gzip
import re
import sys
from pathlib import Path

DATED = re.compile(r"^(\d{4}-\d{2}-\d{2})-(\d+)\.log\.gz$")


def archives(logs: Path) -> list[Path]:
    """The dated archives of a logs folder, oldest first (by date, then by Forge's counter)."""
    found = []
    for path in logs.glob("*.log.gz"):
        match = DATED.match(path.name)
        if match:
            found.append((match.group(1), int(match.group(2)), path))
    return [path for _, _, path in sorted(found)]


def combine(logs: Path) -> Path:
    latest = logs / "latest.log"
    if not latest.is_file():
        raise FileNotFoundError(f"{latest} does not exist")
    out = logs / "run-complete.log"
    with out.open("wb") as sink:
        for archive in archives(logs):
            sink.write(gzip.decompress(archive.read_bytes()))
        sink.write(latest.read_bytes())
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("usage: ci_run_log.py <logs folder>", file=sys.stderr)
        return 2
    try:
        print(combine(Path(argv[0])))
    except FileNotFoundError as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
