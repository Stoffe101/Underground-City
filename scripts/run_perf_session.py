#!/usr/bin/env python3
"""Two real clients on a dedicated server for half an hour on a NEW furnished archipelago world, measured (alpha criterion 9; PerfSessionServer, PerfSessionClient).

Phase 1: both clients join; the server registers them, then runs three segments (both in Ironhaven by day, Ironhaven and Valewood by day, both in Blackwater at night), each opened with a
logged teleport to a street near the city's centre, while the clients walk on their own; then a timed full save and a normal stop. Phase 2 starts the same world again, both clients rejoin,
two more minutes are measured. The report (build/perf-session/report.json) holds the server's tick percentiles per segment, the entity, chunk and heap samples, and each client's frames
per minute; build/perf-session/logs has every process's log.

    xvfb-run -a -s '-screen 0 1920x1080x24' python scripts/run_perf_session.py [--minutes 30]

The server and both clients share one machine: the clients render in software, so the server's numbers are an upper bound for a server with a machine to itself, and the clients' frame
times say whether the game hitches, not how fast a graphics card draws it. Never touches existing saves: the server and client folders are made fresh and an existing one is refused.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from install_qshop_templates import install

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'rpg-core'
OUTPUT = ROOT / 'build' / 'perf-session'
SERVER = MODULE / 'run-perf-server'
SETS = 'world,furnished,scarcity'
WORLD_FLAGS = ['-PucDevWorld=true', '-PucAutoWorld=false', '-PucWorldLayout=archipelago', f'-PucPack={SETS}', '-PucJourneyScenario=perf']
NAMES = {'farmer': 'UC_Farmer', 'smith': 'UC_Smith'}


def prepare() -> list[Path]:
    if SERVER.exists() or OUTPUT.exists():
        raise SystemExit(f'{SERVER} or {OUTPUT} already exists; the session needs fresh disposable folders (move them away first).')
    SERVER.mkdir(parents=True)
    (SERVER / 'eula.txt').write_text('eula=true\n')
    # disposable measurement server on the loopback address only; online-mode=false because the clients are development clients without accounts
    (SERVER / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25565\nonline-mode=false\nenforce-secure-profile=false\nlevel-name=world\n'
                                              'level-type=underground_city:archipelago\nlevel-seed=5908\nview-distance=8\nsimulation-distance=6\ndifficulty=normal\n'
                                              'pvp=false\nmax-players=2\nspawn-protection=0\n')
    install(SERVER)
    modules = []
    for role in NAMES:
        copy = OUTPUT / 'clients' / role
        shutil.copytree(MODULE, copy / 'rpg-core', ignore=shutil.ignore_patterns('run', 'run-*', 'build', '.gradle'))
        shutil.copytree(ROOT / 'content', copy / 'content')
        config = copy / 'rpg-core' / f'run-journey-client-{role}' / 'config'
        config.mkdir(parents=True)
        (config / 'fml.toml').write_text('earlyWindowControl = false\n')   # the loading splash gives up under software rendering (run_cloud_journey.py)
        modules.append(copy / 'rpg-core')
    return modules


def start(module: Path, task: str, flags: list[str], log: Path, tmpdir: Path | None = None):
    log.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe', LP_NUM_THREADS='1', ALSOFT_DRIVERS='null', SDL_AUDIODRIVER='dummy')
    if tmpdir is not None:
        tmpdir.mkdir(parents=True, exist_ok=True)
        env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') + f' -Djava.io.tmpdir={tmpdir}').strip()
    stream = log.open('w')
    process = subprocess.Popen(['gradle', '-p', str(module), task, '-PucEconomyTest=true', *flags, '-Dorg.gradle.jvmargs=-Xmx1G', '--no-daemon'],
                               env=env, stdout=stream, stderr=subprocess.STDOUT)
    return process, stream


def phase(number: int, modules: list[Path], minutes: float) -> dict:
    processes = []
    marker = SERVER / f'perf-session-phase{number}.json'
    budget = 900 + (minutes * 60 if number == 1 else 300) + 600
    try:
        server_log = OUTPUT / 'logs' / f'server-phase{number}.log'
        processes.append(start(MODULE, 'runServer', ['-PucRunDir=run-perf-server', f'-PucJourneyPhase={number}', f'-PucPerfMinutes={minutes}', '-PucServerHeap=3G', *WORLD_FLAGS], server_log))
        deadline = time.monotonic() + 900
        while 'Done (' not in server_log.read_text(errors='replace'):
            if processes[0][0].poll() is not None or time.monotonic() > deadline: raise RuntimeError(f'the server did not start; see {server_log}')
            time.sleep(2)
        for module, role in zip(modules, NAMES):
            processes.append(start(module, 'runClient', [f'-PucRunDir=run-journey-client-{role}', f'-PucJourneyClient={NAMES[role]}', f'-PucJourneyPhase={number}', '-PucClientHeap=2G', *WORLD_FLAGS],
                                   OUTPUT / 'logs' / f'{role}-phase{number}.log', OUTPUT / 'tmp' / role))
        deadline = time.monotonic() + budget
        while not marker.exists():
            if any(p.poll() is not None for p, _ in processes): raise RuntimeError(f'a process stopped before phase {number} was done; see {OUTPUT / "logs"}')
            if time.monotonic() > deadline: raise TimeoutError(f'phase {number} ran out of time')
            time.sleep(5)
        processes[0][0].wait(timeout=300)
        report = json.loads(marker.read_text())
        if report.get('status') != 'passed': raise RuntimeError(f'phase {number} reported {report.get("status")}: {report.get("error")}')
        clients = {}
        for module, role in zip(modules, NAMES):
            path = module / f'run-journey-client-{role}' / f'perf-client-phase{number}.json'
            for _ in range(60):
                if path.exists(): break
                time.sleep(1)
            clients[role] = json.loads(path.read_text()) if path.exists() else {'minutes': []}
        report['clients'] = clients
        return report
    finally:
        for process, stream in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired: process.kill()
            stream.close()


def table(report: dict) -> str:
    lines = ['| segment | ticks | mean ms | p95 ms | p99 ms | worst ms | over 100 ms | over 250 ms |', '| --- | --- | --- | --- | --- | --- | --- | --- |']
    for phase_report in report['phases']:
        for name, s in phase_report['segments'].items():
            if s.get('ticks'):
                lines.append(f"| {name} | {s['ticks']} | {s['mean_ms']} | {s['p95_ms']} | {s['p99_ms']} | {s['worst_ms']} | {s['over_100_ms']} | {s['over_250_ms']} |")
    return '\n'.join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--minutes', type=float, default=30.0, help='length of phase 1 (three equal segments)')
    args = parser.parse_args()
    modules = prepare()
    report = {'sets': SETS, 'minutes': args.minutes, 'phases': []}
    for number in (1, 2):
        report['phases'].append(phase(number, modules, args.minutes))
        print(f'perf session phase {number} done', flush=True)
    (OUTPUT / 'report.json').write_text(json.dumps(report, indent=1) + '\n')
    print(table(report))
    return 0


if __name__ == '__main__':
    sys.exit(main())
