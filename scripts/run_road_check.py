#!/usr/bin/env python3
"""Read the real blocks along every generated corridor road and bridge of a layout on a disposable dedicated server (``RoadCheck``).

The world-integration job only ever surveyed the legacy prototype's roads. This runs the same verifier (deck present, three free blocks of headroom, no step above half a block, parapets,
water under the bridges, vegetation over the carriageway) against the archipelago's three bridges.

    python scripts/run_road_check.py --name arch1 --layout archipelago --output build/roads/arch1.json

The world is ``rpg-core/run-roadcheck-<name>`` (fresh; an existing directory is refused). ``max-tick-time=-1`` and ``online-mode=false`` are for this disposable measurement server only
(nothing can join it: it stops by itself), as in ``run_city_probe.py``.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True)
    parser.add_argument("--layout", choices=["legacy", "archipelago"], default="archipelago")
    parser.add_argument("--seed", default="5908")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=25565, help="the disposable server's port (another measurement server may be running beside it)")
    parser.add_argument("--timeout", type=int, default=3600)
    parser.add_argument("--pack", default="world", help="world package sets, as -PucPack (for example world,furnished,scarcity: the furnished cities change what stands at a road's end)")
    args = parser.parse_args()
    run_name = f"run-roadcheck-{args.name}"
    run_dir = ROOT / "rpg-core" / run_name
    if run_dir.exists():
        print(f"{run_dir} already exists; a generation test needs a fresh disposable world", file=sys.stderr)
        return 2
    run_dir.mkdir(parents=True)
    preset = "underground_city:archipelago" if args.layout == "archipelago" else "minecraft:normal"
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text("\n".join([
        f"level-type={preset}", f"level-seed={args.seed}", "online-mode=false", "spawn-protection=0", "view-distance=4", "simulation-distance=4", "max-tick-time=-1",
        "level-name=world", "white-list=false", f"server-port={args.port}"]) + "\n", encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(ROOT / "gradlew"), "--no-daemon", ":rpg-core:runServer", f"-PucRunDir={run_name}", f"-PucPack={args.pack}", "-PucDevWorld=true", "-PucAutoWorld=false",
               f"-PucWorldLayout={args.layout}", f"-PucRoadCheck={args.output.resolve()}"]
    print(" ".join(command))
    result = subprocess.run(command, cwd=ROOT, timeout=args.timeout)
    if not args.output.is_file():
        print("the road check wrote no file; see " + str(run_dir / "logs/latest.log"), file=sys.stderr)
        return result.returncode or 1
    print(f"road check: {args.output} ({args.output.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
