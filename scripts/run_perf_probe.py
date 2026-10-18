#!/usr/bin/env python3
"""Measure the compute cost of a server tick with and without Founders Row (PerfProbe.java) on a disposable flat-world dedicated server.

Usage: python scripts/run_perf_probe.py [--pack firearms,vehicles,...]
Prints the [UC-PERF] lines. The numbers describe the machine they ran on only (in the cloud container the server shares four cores with whatever else runs).
"""
from __future__ import annotations
import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path
from install_qshop_templates import install

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'rpg-core'
RUN = MODULE / 'run-perf'


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--pack', default='', help='dev-pack sets to load as well, e.g. firearms,vehicles')
    parser.add_argument('--timeout', type=int, default=1500)
    args = parser.parse_args()
    if RUN.exists():
        shutil.rmtree(RUN)
    RUN.mkdir()
    (RUN / 'eula.txt').write_text('eula=true\n')
    flat = json.dumps({'biome': 'minecraft:plains', 'layers': [{'block': 'minecraft:bedrock', 'height': 1}, {'block': 'minecraft:dirt', 'height': 40},
                       {'block': 'minecraft:grass_block', 'height': 1}], 'lakes': False, 'features': False, 'structure_overrides': []}, separators=(',', ':'))
    (RUN / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25566\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft:flat\n'
        'generator-settings=' + flat + '\nview-distance=8\nsimulation-distance=8\ndifficulty=normal\nmax-players=2\nspawn-protection=0\n')
    install(RUN)
    command = ['gradle', '-p', str(MODULE), 'runServer', '-PucPerfProbe=true', '-PucEconomyTest=true', '-PucRunDir=run-perf', '--no-daemon']
    if args.pack:
        command.append('-PucPack=' + args.pack)
    result = subprocess.run(command, capture_output=True, text=True, timeout=args.timeout)
    log = ROOT / 'build' / 'perf-probe.log'
    log.parent.mkdir(exist_ok=True)
    log.write_text(result.stdout + result.stderr, encoding='utf-8')
    lines = [line for line in (result.stdout + result.stderr).splitlines() if '[UC-PERF]' in line]
    print('\n'.join(lines) if lines else 'No [UC-PERF] lines; exit code ' + str(result.returncode))
    shutil.rmtree(RUN, ignore_errors=True)
    return 0 if lines else 1


if __name__ == '__main__':
    sys.exit(main())
