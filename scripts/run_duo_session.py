#!/usr/bin/env python3
"""Two real clients on a dedicated server for the ninth pass (DuoSessionServer, DuoSessionClient) on a NEW furnished archipelago world.

Phase 1: both clients join and answer their own first-login screen (one Valewood, one Blackwater); the server checks where each was really put, runs the operator commands from its console
(``/uc admin``: credits through Q Shop, levels, points, a general skill, the audit, and the mistakes), checks that the player who is not an operator cannot reach them, then the supply chain (DuoSupplyChain), then police case 8 (DuoPolice: the Farmer strikes a passer-by beside
the Vintner; only the Farmer is arrested and fined, and the Vintner's marked goods, wallet and record are untouched), then a two-player burglary
(only the permit holder may break things; nothing drops), then a save and a normal stop. Phase 2 starts the same world again: both rejoin as the same citizens, nothing moves them again, the
commands' changes are still there, and the house is put back once its wait (shortened to one minute with ``-Duc.burglary.restoreMinutes=1``) is over. The report is build/duo-session/report.json;
build/duo-session/logs has every process's log.

    xvfb-run -a -s '-screen 0 1920x1080x24' python scripts/run_duo_session.py

Never touches existing saves: the server and client folders are made fresh and an existing one is refused. The server listens on the loopback address only with online-mode=false, because the
clients are development clients without accounts; a server anybody else can reach keeps online-mode=true and an allowlist (docs/production/playtest-guide-windows11.md).
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
OUTPUT = ROOT / 'build' / 'duo-session'
SERVER = MODULE / 'run-duo-server'
SETS = 'world,furnished,scarcity'
CLIENT_SETS = SETS
WORLD_FLAGS = ['-PucDevWorld=true', '-PucAutoWorld=false', '-PucWorldLayout=archipelago', '-PucJourneyScenario=duo']
NAMES = {'valewood': 'UC_Valewood', 'blackwater': 'UC_Blackwater'}


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


def start(module: Path, task: str, flags: list[str], log: Path, tmpdir: Path | None = None, extra_java: str = ''):
    log.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, LIBGL_ALWAYS_SOFTWARE='1', GALLIUM_DRIVER='llvmpipe', LP_NUM_THREADS='1', ALSOFT_DRIVERS='null', SDL_AUDIODRIVER='dummy')
    if tmpdir is not None:
        tmpdir.mkdir(parents=True, exist_ok=True)
        env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') + f' -Djava.io.tmpdir={tmpdir}').strip()
    if extra_java:
        env['JAVA_TOOL_OPTIONS'] = (env.get('JAVA_TOOL_OPTIONS', '') + ' ' + extra_java).strip()
    stream = log.open('w')
    process = subprocess.Popen(['gradle', '-p', str(module), task, '-PucEconomyTest=true', *flags, '-Dorg.gradle.jvmargs=-Xmx1G', '--no-daemon'],
                               env=env, stdout=stream, stderr=subprocess.STDOUT)
    return process, stream


def phase(number: int, modules: list[Path], minutes: float) -> dict:
    processes = []
    marker = SERVER / f'duo-session-phase{number}.json'
    budget = 900 + 900
    try:
        server_log = OUTPUT / 'logs' / f'server-phase{number}.log'
        processes.append(start(MODULE, 'runServer', ['-PucRunDir=run-duo-server', f'-PucJourneyPhase={number}', '-PucServerHeap=3G', f'-PucPack={SETS}', *WORLD_FLAGS], server_log,
                               extra_java='-Duc.burglary.restoreMinutes=1'))
        deadline = time.monotonic() + 900
        while 'Done (' not in server_log.read_text(errors='replace'):
            if processes[0][0].poll() is not None or time.monotonic() > deadline: raise RuntimeError(f'the server did not start; see {server_log}')
            time.sleep(2)
        for module, role in zip(modules, NAMES):
            processes.append(start(module, 'runClient', [f'-PucRunDir=run-journey-client-{role}', f'-PucJourneyClient={NAMES[role]}', f'-PucJourneyPhase={number}', '-PucClientHeap=2G', f'-PucPack={CLIENT_SETS}', *WORLD_FLAGS],
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
            path = module / f'run-journey-client-{role}' / f'duo-client-phase{number}.json'
            clients[role] = json.loads(path.read_text()) if path.exists() else {}
        report['clients'] = clients
        return report
    finally:
        for process, stream in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=30)
                except subprocess.TimeoutExpired: process.kill()
            stream.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.parse_args()
    modules = prepare()
    report = {'server_sets': SETS, 'client_sets': CLIENT_SETS, 'phases': []}
    for number in (1, 2):
        report['phases'].append(phase(number, modules, 0))
        print(f'duo session phase {number} done', flush=True)
    (OUTPUT / 'report.json').write_text(json.dumps(report, indent=1) + '\n')
    for p in report['phases']:
        for c in p.get('checks', []):
            print(('PASS ' if c['passed'] else 'FAIL ') + c['check'] + ': ' + c['detail'])
        for role, client in p.get('clients', {}).items():
            if client: print(f'{role} heard: ' + ' | '.join(client.get('heard_after_command', [])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
