#!/usr/bin/env python3
"""Two actual Forge clients, real terminal/shop mouse actions, then dedicated-server restart.

Run inside Xvfb on Codex Cloud or a GitHub-hosted runner. Never touches existing saves.
"""
from __future__ import annotations
import json
import os
import shutil
import subprocess
import time
from pathlib import Path
from install_qshop_templates import install

ROOT=Path(__file__).resolve().parents[1]
MODULE=ROOT/'rpg-core'
OUTPUT=ROOT/'build/cloud-journey'
SERVER=MODULE/'run-journey-server'


def prepare() -> list[tuple[Path,str]]:
    if SERVER.exists() or OUTPUT.exists():
        raise ValueError('Disposable journey directory already exists; preserve it and use a fresh checkout.')
    SERVER.mkdir()
    (SERVER/'eula.txt').write_text('eula=true\n')
    flat=json.dumps({'biome':'minecraft:plains','layers':[{'block':'minecraft:bedrock','height':1},
        {'block':'minecraft:dirt','height':2},{'block':'minecraft:grass_block','height':1}],
        'lakes':False,'features':False,'structure_overrides':[]},separators=(',',':'))
    (SERVER/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25565\nonline-mode=false\n'
        'enforce-secure-profile=false\nlevel-name=world\nlevel-type=minecraft:flat\ngenerator-settings='+flat+
        '\nview-distance=3\nsimulation-distance=3\ndifficulty=peaceful\npvp=false\nmax-players=2\nspawn-protection=0\n')
    install(SERVER)
    clients=[]
    for role in ('farmer','smith'):
        module=OUTPUT/'clients'/role/'rpg-core'
        shutil.copytree(MODULE,module,ignore=shutil.ignore_patterns('run','run-*','build','.gradle'))
        clients.append((module,role))
    return clients


def start(module: Path, task: str, flags: list[str], logfile: Path, tmpdir: Path | None = None):
    logfile.parent.mkdir(parents=True,exist_ok=True)
    env=dict(os.environ,LIBGL_ALWAYS_SOFTWARE='1',GALLIUM_DRIVER='llvmpipe',LP_NUM_THREADS='1',
        ALSOFT_DRIVERS='null',SDL_AUDIODRIVER='dummy')
    if tmpdir is not None:
        # Both clients start at the same moment and LWJGL unpacks its native libraries into one shared directory under java.io.tmpdir; two JVMs doing that at once
        # produced "Failed to locate library: liblwjgl_stb.so" and a SIGABRT (exit 134) in hosted runs. A private temporary directory per client removes the race.
        tmpdir.mkdir(parents=True,exist_ok=True)
        env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+f' -Djava.io.tmpdir={tmpdir}').strip()
    stream=logfile.open('w')
    process=subprocess.Popen(['gradle','-p',str(module),task,'-PucEconomyTest=true',*flags,
        '-Dorg.gradle.jvmargs=-Xmx1G','--no-daemon'],env=env,stdout=stream,stderr=subprocess.STDOUT)
    return process,stream


def phase(number: int, clients: list[tuple[Path,str]]) -> dict:
    processes=[]
    try:
        server_log=OUTPUT/'logs'/f'server-phase{number}.log'
        processes.append(start(MODULE,'runServer',['-PucRunDir=run-journey-server',f'-PucJourneyPhase={number}'],server_log))
        deadline=time.monotonic()+900
        while time.monotonic()<deadline:
            if 'Done (' in server_log.read_text(errors='replace'): break
            if processes[0][0].poll() is not None: raise RuntimeError('Dedicated server exited before startup; inspect '+str(server_log))
            time.sleep(2)
        else: raise TimeoutError('Dedicated server startup timed out')
        for module,role in clients:
            name='UC_Farmer' if role=='farmer' else 'UC_Smith'
            processes.append(start(module,'runClient',[f'-PucRunDir=run-journey-client-{role}',f'-PucJourneyClient={name}'],
                OUTPUT/'logs'/f'{role}-phase{number}.log',OUTPUT/'tmp'/role))
        marker=SERVER/f'journey-phase{number}.json'
        while time.monotonic()<deadline:
            if marker.exists(): break
            if any(process.poll() is not None for process,_ in processes):
                raise RuntimeError('A journey process exited before successful assertions; inspect logs')
            time.sleep(2)
        else: raise TimeoutError('Two-client gameplay timed out; inspect logs')
        for process,_ in processes:
            if process.wait(timeout=120)!=0: raise RuntimeError('Journey process failed after assertions')
        result=json.loads(marker.read_text())
        if result['status']!='passed' or result['connected_clients']!=2: raise RuntimeError('Incomplete journey report')
        print('Real connected-client phase',number,'passed',flush=True)
        return result
    finally:
        for process,stream in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill()
            stream.close()


def main() -> None:
    clients=prepare()
    results=[phase(1,clients),phase(2,clients)]
    (OUTPUT/'report.json').write_text(json.dumps({'minecraft':'1.20.1','forge':'47.4.10',
        'qshop':'1.8.4','actors':'two real loopback-only offline test clients','phases':results},indent=2)+'\n')


if __name__=='__main__': main()
