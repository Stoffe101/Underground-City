#!/usr/bin/env python3
"""First-start and restart stability of a production-style Underground City server (D-097).

A server whose FIRST start fails is the worst failure a player can meet, and some mods write their configuration while Forge reads it (Traveler's Backpack crashed one first start in
eight; Sophisticated Core was seen to corrupt and then correct its own file). This starts the same pack many times from clean, disposable server folders and then again from the
populated folder, and reports what happened each time: did the server reach "Done", did a crash report appear, did any mod's configuration have to be corrected, how long did
it take, and what did the boot smoke harness (``BootSmokeHarness``) report. Nothing is committed, retried silently or hidden: a failed start is a failed row.

    gradle -p rpg-core build
    python scripts/run_first_start_check.py --sets survival --clean 8 --restarts 2 --work build/first-start
    python scripts/run_first_start_check.py --sets world firearms vehicles boats vintner farming survival --clean 3 --restarts 1

Each clean start gets a new world folder, new ``config`` and (for the survival set) the generated ``defaultconfigs``; ``libraries`` and ``mods`` are shared read-only by link.
The jars are downloaded from the authors' pages and hash-verified by ``run_prod_boot_check`` / ``fetch_dev_pack``; nothing is committed.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_prod_boot_check as boot  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

def make_instance(base: Path, target: Path, sets: list[str], seed_config: bool = True) -> Path:
    """A clean server folder that shares ``libraries`` and ``mods`` with the base by symbolic link."""
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    for name in ("libraries", "mods"):
        os.symlink((base / name).resolve(), target / name)
    for name in ("user_jvm_args.txt",):
        if (base / name).exists():
            shutil.copy2(base / name, target / name)
    if "world" in sets:
        boot.stage_world_config(target)
    if "survival" in sets and seed_config:
        boot.stage_survival_config(target)
    return target


def start(server: Path, seconds: int, label: str, log_dir: Path) -> dict:
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text("server-ip=127.0.0.1\nonline-mode=false\nview-distance=3\nsimulation-distance=3\nlevel-seed=166\n", encoding="utf-8")
    jvm = server / "user_jvm_args.txt"
    jvm.write_text("-Xms512M\n-Xmx3G\n-Duc.bootSmoke=true\n", encoding="utf-8")
    args = server / "libraries" / "net" / "minecraftforge" / "forge" / boot.FORGE / "unix_args.txt"
    log = log_dir / f"{label}.log"
    began = time.monotonic()
    timed_out = False
    with log.open("w", encoding="utf-8") as sink:
        try:
            subprocess.run(["java", f"@{jvm}", f"@{args}", "nogui"], cwd=server, stdout=sink, stderr=subprocess.STDOUT, timeout=seconds, check=False)
        except subprocess.TimeoutExpired:
            timed_out = True
            sink.write("\n[UC-FIRST-START] timed out\n")
    text = log.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    crash_reports = sorted(p.name for p in (server / "crash-reports").glob("*.txt")) if (server / "crash-reports").is_dir() else []
    result = {
        "label": label,
        "seconds": int(time.monotonic() - began),
        "reached_done": "[UC-BOOT] done" in text,
        "timed_out": timed_out,
        "crash_reports": crash_reports,
        "config_corrected": sum(1 for line in lines if "Correcting" in line and "Configuration" in line),
        "config_files_corrected": sorted({m.group(1) for line in lines for m in [re.search(r"Configuration file (\S+) is not correct", line)] if m}),
        "error_lines": sum(1 for line in lines if re.search(r"/ERROR\]", line)),
        "first_errors": [line.strip()[:200] for line in lines if re.search(r"/ERROR\]", line)][:3],
        "watchdog_hits": sum(1 for line in lines if "single server tick took" in line),
        "simulation_line": any("[UC-SIMULATION] hostile creatures: SIMULATION" in line for line in lines),
        "backpack_policy_ok": any("matches the Underground City policy" in line for line in lines) if "sophisticatedbackpacks" in text else None,
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sets", nargs="+", default=["survival"])
    parser.add_argument("--clean", type=int, default=3, help="number of clean first starts")
    parser.add_argument("--restarts", type=int, default=1, help="populated restarts after each clean start")
    parser.add_argument("--work", type=Path, default=ROOT / "build/first-start")
    parser.add_argument("--core-jar", type=Path, default=None)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--tag", default="run", help="name of this series: its logs go to <work>/logs/<tag>/ and its report to <work>/first-start-report-<tag>.json")
    parser.add_argument("--without-survival-config", action="store_true", help="control: do NOT pre-seed the generated backpack configuration, so that the mods write their own files on the first start")
    args = parser.parse_args()
    args.work = args.work.resolve()   # the server processes run in other directories and are given paths
    core = args.core_jar or next(iter(sorted((ROOT / "rpg-core/build/libs").glob("underground_city-*.jar"))), None)
    if core is None or not core.exists():
        raise SystemExit("Build the Core jar first: gradle -p rpg-core build")
    args.work.mkdir(parents=True, exist_ok=True)
    base = boot.install_forge(args.work.resolve())
    staged = boot.stage_mods(base, args.sets, core)
    print("staged:", ", ".join(staged), flush=True)
    log_dir = args.work / "logs" / args.tag
    log_dir.mkdir(parents=True, exist_ok=True)
    rows = []
    for i in range(1, args.clean + 1):
        instance = make_instance(base, args.work / f"instance-{args.tag}-{i}", args.sets, not args.without_survival_config)
        rows.append(start(instance, args.timeout, f"clean-{i}-first", log_dir))
        print(json.dumps(rows[-1]), flush=True)
        for r in range(1, args.restarts + 1):
            rows.append(start(instance, args.timeout, f"clean-{i}-restart-{r}", log_dir))
            print(json.dumps(rows[-1]), flush=True)
        shutil.rmtree(instance / "world", ignore_errors=True)
    failed = [r for r in rows if not r["reached_done"] or r["crash_reports"] or r["watchdog_hits"]]
    corrected = [r for r in rows if r["config_corrected"]]
    summary = {"tag": args.tag, "sets": args.sets, "backpack_config_pre_seeded": not args.without_survival_config, "starts": len(rows), "clean_starts": args.clean, "failed_starts": len(failed), "starts_with_a_config_correction": len(corrected),
               "median_seconds": sorted(r["seconds"] for r in rows)[len(rows) // 2], "max_seconds": max(r["seconds"] for r in rows)}
    (args.work / f"first-start-report-{args.tag}.json").write_text(json.dumps({"summary": summary, "rows": rows}, indent=2), encoding="utf-8")
    print("\n" + json.dumps(summary, indent=2))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
