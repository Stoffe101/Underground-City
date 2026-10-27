#!/usr/bin/env python3
"""Sample a world layout's real terrain on a disposable dedicated server and write a CSV of heights and biomes.

Underground City's first human playtest found three cities that read as one built-up area under snow, on a seed chosen from a
climate-sampler scan. This runs the *game's own* chunk generator and biome source (``TerrainMapHarness``) on a brand-new world of the
chosen preset and seed, so a layout is judged on generated terrain and biome distribution rather than on a geometry sketch.
``scripts/render_terrain_map.py`` turns the CSV into a picture and a table.

    python scripts/run_terrain_map.py --name arch-a --preset underground_city:archipelago --seed 5908 --output build/terrain/arch-a.csv
    python scripts/run_terrain_map.py --name legacy-5908 --preset minecraft:normal --seed 5908 --output build/terrain/legacy-5908.csv

The world lives in ``rpg-core/run-terrain-<name>`` (created fresh; an existing directory is refused, so a saved world can never be reused or
overwritten). Developer-only settings, stated plainly: ``online-mode=false`` (nobody can join this server: it stops itself when the survey
is written) and ``max-tick-time=-1`` (the survey is one long synchronous job on the server thread; the watchdog is never disabled on a server
that carries a player, D-096).
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True, help="run directory suffix (rpg-core/run-terrain-<name>)")
    parser.add_argument("--preset", default="underground_city:archipelago", help="level-type (a world preset id)")
    parser.add_argument("--seed", default="5908")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--pack", default="none", help="dev-pack sets to load (none = Core alone)")
    parser.add_argument("--x0", default="-900"); parser.add_argument("--x1", default="2500")
    parser.add_argument("--z0", default="-900"); parser.add_argument("--z1", default="2300")
    parser.add_argument("--step", default="16")
    parser.add_argument("--city-continents", help="calibration: the continentalness given to the flat ground under a city")
    parser.add_argument("--flat-erosion", help="calibration: the erosion given to the flat ground under a city")
    parser.add_argument("--plateau-ridges", help="calibration: the peaks-and-valleys value of the flat ground under a city (decides its height)")
    parser.add_argument("--humidity-shrink", help="calibration: how many blocks less than the plateau the plains-humidity under a city reaches")
    parser.add_argument("--humidity-ease", help="calibration: the share of the plateau's easing the plains-humidity fades over")
    args = parser.parse_args()

    run_name = f"run-terrain-{args.name}"
    run_dir = ROOT / "rpg-core" / run_name
    if run_dir.exists():
        print(f"{run_dir} already exists; a generation test needs a fresh disposable world (pick another --name or delete it yourself)", file=sys.stderr)
        return 2
    run_dir.mkdir(parents=True)
    (run_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (run_dir / "server.properties").write_text("\n".join([
        f"level-type={args.preset}", f"level-seed={args.seed}", "online-mode=false", "spawn-protection=0", "view-distance=4", "simulation-distance=4",
        "max-tick-time=-1", "motd=Underground City terrain survey (disposable)", "level-name=world", "enable-command-block=false", "white-list=false"]) + "\n", encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(ROOT / "gradlew"), "--no-daemon", ":rpg-core:runServer", f"-PucRunDir={run_name}", f"-PucPack={args.pack}",
               f"-PucTerrainMap={args.output.resolve()}", f"-PucTmX0={args.x0}", f"-PucTmX1={args.x1}", f"-PucTmZ0={args.z0}", f"-PucTmZ1={args.z1}", f"-PucTmStep={args.step}"]
    if args.city_continents:
        command.append(f"-PucIslandCityContinents={args.city_continents}")
    if args.humidity_shrink:
        command.append(f"-PucIslandHumidityShrink={args.humidity_shrink}")
    if args.humidity_ease:
        command.append(f"-PucIslandHumidityEase={args.humidity_ease}")
    if args.flat_erosion:
        command.append(f"-PucIslandFlatErosion={args.flat_erosion}")
    if args.plateau_ridges:
        command.append(f"-PucIslandPlateauRidges={args.plateau_ridges}")
    print(" ".join(command))
    result = subprocess.run(command, cwd=ROOT)
    if not args.output.is_file():
        print("the survey wrote no file; see the server log in " + str(run_dir / "logs/latest.log"), file=sys.stderr)
        return result.returncode or 1
    print(f"terrain survey: {args.output} ({args.output.stat().st_size // 1024} KiB)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
