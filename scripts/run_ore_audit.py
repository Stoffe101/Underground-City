#!/usr/bin/env python3
"""Generate chunks of a layout on a disposable dedicated server and count every ore block by kind (``OreAudit``).

    python scripts/run_ore_audit.py --name base --sets world            the baseline: how much natural ore a new archipelago world has
    python scripts/run_ore_audit.py --name scarce --sets world,scarcity   the same world with the scarcity package (D-120)

The world is ``rpg-core/run-oreaudit-<name>`` (fresh; an existing directory is refused). ``online-mode=false`` and ``max-tick-time=-1`` are for this disposable measurement server only (nothing can join it:
it stops by itself), as in ``run_city_probe.py``. Nothing here touches an existing world.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True)
    parser.add_argument("--seed", default="5908")
    parser.add_argument("--sets", default="world,scarcity")
    parser.add_argument("--radius", default="4")
    parser.add_argument("--spread", default="260")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--timeout", type=int, default=3000)
    args = parser.parse_args()
    output = args.output or ROOT / "build" / "probe" / f"ore-{args.name}.json"
    run_name = f"run-oreaudit-{args.name}"
    run_dir = ROOT / "rpg-core" / run_name
    if run_dir.exists():
        print(f"{run_dir} already exists; a generation test needs a fresh disposable world", file=sys.stderr)
        return 2
    run_dir.mkdir(parents=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text("\n".join([
        "level-type=underground_city:archipelago", f"level-seed={args.seed}", "online-mode=false", "spawn-protection=0", "view-distance=4", "simulation-distance=4", "max-tick-time=-1",
        "level-name=world", "white-list=false"]) + "\n", encoding="utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(ROOT / "gradlew"), "--no-daemon", ":rpg-core:runServer", f"-PucRunDir={run_name}", f"-PucPack={args.sets}", "-PucDevWorld=true", "-PucAutoWorld=false",
               "-PucWorldLayout=archipelago", f"-PucOreAudit={output.resolve()}", f"-PucOaRadius={args.radius}", f"-PucOaSpread={args.spread}"]
    print(" ".join(command))
    subprocess.run(command, cwd=ROOT, timeout=args.timeout)
    if not output.is_file():
        print("the audit wrote no file; see " + str(run_dir / "logs/latest.log"), file=sys.stderr)
        return 1
    report = json.loads(output.read_text(encoding="utf-8"))
    print(f"ore audit: {output}: {report['chunks_scanned']} chunks, {report['ore_blocks_total']} ore blocks")
    for kind, row in report["by_kind"].items():
        print(f"  {kind}: {row['blocks']} (y {row['lowest_y']}..{row['highest_y']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
