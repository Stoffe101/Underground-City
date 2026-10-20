#!/usr/bin/env python3
"""Prove the building guard, the lock and the stash on one REAL generated apartment block, across a server restart (``BurglaryHarness``).

Phase a starts a brand-new disposable archipelago world (Lost Cities generates Ironhaven), finds an apartment-class building with a ground-floor exterior door and, as a survival player, tries its
wall, its door by hand and with picks, picks it with the honest solver, finds the stash it was furnished with, picks that and takes one item, then saves and stops. Phase b starts the SAME world again
and checks what phase a left (door window, furnished building, safe stock, break-ins) and that the door locks again.

    python scripts/run_burglary_check.py --name bg1 --output build/burglary/bg1

The world is ``rpg-core/run-burglary-<name>`` (fresh; an existing directory is refused, so a saved world can never be reused or overwritten). ``online-mode=false`` and ``max-tick-time=-1`` are for this
disposable measurement server only (nobody can join it: it stops by itself), as in ``run_city_probe.py``.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(name: str, phase: str, report: Path, state: Path, layout: str, timeout: int) -> int:
    command = [str(ROOT / "gradlew"), "--no-daemon", ":rpg-core:runServer", f"-PucRunDir=run-burglary-{name}", "-PucPack=world", "-PucDevWorld=true", "-PucAutoWorld=false",
               f"-PucWorldLayout={layout}", f"-PucBurglary={report.resolve()}", f"-PucBgPhase={phase}", f"-PucBgState={state.resolve()}"]
    print(" ".join(command))
    return subprocess.run(command, cwd=ROOT, timeout=timeout).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True)
    parser.add_argument("--layout", choices=["legacy", "archipelago"], default="archipelago")
    parser.add_argument("--seed", default="5908")
    parser.add_argument("--output", type=Path, required=True, help="a folder for phase-a.json, phase-b.json and state.json")
    parser.add_argument("--timeout", type=int, default=3000)
    args = parser.parse_args()
    run_dir = ROOT / "rpg-core" / f"run-burglary-{args.name}"
    if run_dir.exists():
        print(f"{run_dir} already exists; a generation test needs a fresh disposable world", file=sys.stderr)
        return 2
    run_dir.mkdir(parents=True)
    preset = "underground_city:archipelago" if args.layout == "archipelago" else "minecraft:normal"
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text("\n".join([
        f"level-type={preset}", f"level-seed={args.seed}", "online-mode=false", "spawn-protection=0", "view-distance=4", "simulation-distance=4", "max-tick-time=-1",
        "level-name=world", "white-list=false"]) + "\n", encoding="utf-8")
    args.output.mkdir(parents=True, exist_ok=True)
    state = args.output / "state.json"
    failures = 0
    for phase in ("a", "b"):
        report = args.output / f"phase-{phase}.json"
        run(args.name, phase, report, state, args.layout, args.timeout)
        if not report.is_file():
            print(f"phase {phase} wrote no report; see {run_dir / 'logs/latest.log'}", file=sys.stderr)
            return 1
        data = json.loads(report.read_text(encoding="utf-8"))
        failures += data["failures"]
        for fact in data["facts"]:
            print(f"[{phase}] {'PASS' if fact['ok'] else 'FAIL'}  {fact['fact']}: {fact['detail']}")
    print("burglary check:", "all facts hold" if failures == 0 else f"{failures} failed facts")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
