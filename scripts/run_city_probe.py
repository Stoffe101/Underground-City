#!/usr/bin/env python3
"""Generate real Lost Cities chunks of a layout on a disposable dedicated server and write what is in them (``CityProbe``).

Used for the October 20 findings: which vegetation really stands inside or on top of city buildings and roads, and what the volume of a building really is (floors, cellars, roof, doors, glass,
containers). Everything it reports was read from generated blocks and Lost Cities' own chunk plan, not assumed.

    python scripts/run_city_probe.py --name arch1 --layout archipelago --output build/probe/arch1.json --radius 6

The world is ``rpg-core/run-cityprobe-<name>`` (fresh; an existing directory is refused). ``max-tick-time=-1`` and ``online-mode=false`` are for this disposable measurement server only (nothing can
join it: it stops by itself), as in ``run_terrain_map.py``.
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
    parser.add_argument("--radius", default="6")
    parser.add_argument("--cities", default="ironhaven,valewood,blackwater")
    parser.add_argument("--extra", default="", help="more chunks as x:z,x:z")
    parser.add_argument("--sets", default="world", help="dev-pack sets to load, comma separated (world is the Lost Cities profile the probe needs); e.g. world,citytweaks")
    parser.add_argument("--timeout", type=int, default=3000)
    args = parser.parse_args()
    run_name = f"run-cityprobe-{args.name}"
    run_dir = ROOT / "rpg-core" / run_name
    if run_dir.exists():
        print(f"{run_dir} already exists; a generation test needs a fresh disposable world", file=sys.stderr)
        return 2
    run_dir.mkdir(parents=True)
    preset = "underground_city:archipelago" if args.layout == "archipelago" else "minecraft:normal"
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text("\n".join([
        f"level-type={preset}", f"level-seed={args.seed}", "online-mode=false", "spawn-protection=0", "view-distance=4", "simulation-distance=4", "max-tick-time=-1",
        "level-name=world", "white-list=false"]) + "\n", encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(ROOT / "gradlew"), "--no-daemon", ":rpg-core:runServer", f"-PucRunDir={run_name}", f"-PucPack={args.sets}", "-PucDevWorld=true", "-PucAutoWorld=false",
               f"-PucWorldLayout={args.layout}", f"-PucCityProbe={args.output.resolve()}", f"-PucCpRadius={args.radius}", f"-PucCpCities={args.cities}"]
    if args.extra:
        command.append(f"-PucCpExtra={args.extra}")
    print(" ".join(command))
    result = subprocess.run(command, cwd=ROOT, timeout=args.timeout)
    if not args.output.is_file():
        print("the probe wrote no file; see " + str(run_dir / "logs/latest.log"), file=sys.stderr)
        return result.returncode or 1
    print(f"city probe: {args.output} ({args.output.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
