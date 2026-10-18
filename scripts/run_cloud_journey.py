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
SCENARIO=os.environ.get('UC_JOURNEY_SCENARIO','').strip()  # '' = the economy journey; 'crew' = the Crew journey (CrewJourneyServer / CrewJourneyActions)


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
        if os.environ.get('UC_JOURNEY_PACK','').strip():
            # the build reads the pinned dev-pack manifest from ../content/modpacks relative to the module
            shutil.copytree(ROOT/'content/modpacks',module.parent/'content/modpacks')
        clients.append((module,role))
    return clients


def pack_flags() -> list[str]:
    """Third-party dev-pack sets to load on the server and on both clients (UC_JOURNEY_PACK, e.g. firearms,vehicles,vintner,farming), with a bigger client heap."""
    sets=os.environ.get('UC_JOURNEY_PACK','').strip()
    return [f'-PucPack={sets}','-PucClientHeap=2G'] if sets else []


def scenario_flags(number: int) -> list[str]:
    return [f'-PucJourneyScenario={SCENARIO}',f'-PucJourneyPhase={number}'] if SCENARIO else []


def marker_name(number: int) -> str:
    return f'journey-{SCENARIO}-phase{number}.json' if SCENARIO else f'journey-phase{number}.json'


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
        processes.append(start(MODULE,'runServer',['-PucRunDir=run-journey-server',f'-PucJourneyPhase={number}',*scenario_flags(number)[:1],*pack_flags()],server_log))
        deadline=time.monotonic()+900
        while time.monotonic()<deadline:
            if 'Done (' in server_log.read_text(errors='replace'): break
            if processes[0][0].poll() is not None: raise RuntimeError('Dedicated server exited before startup; inspect '+str(server_log))
            time.sleep(2)
        else: raise TimeoutError('Dedicated server startup timed out')
        for module,role in clients:
            name='UC_Farmer' if role=='farmer' else 'UC_Smith'
            processes.append(start(module,'runClient',[f'-PucRunDir=run-journey-client-{role}',f'-PucJourneyClient={name}',*scenario_flags(number),*pack_flags()],
                OUTPUT/'logs'/f'{role}-phase{number}.log',OUTPUT/'tmp'/role))
        marker=SERVER/marker_name(number)
        while time.monotonic()<deadline:
            if marker.exists(): break
            if any(process.poll() is not None for process,_ in processes):
                raise RuntimeError('A journey process exited before successful assertions; inspect logs')
            time.sleep(2)
        else: raise TimeoutError('Two-client gameplay timed out; inspect logs')
        abrupt=SCENARIO=='crew' and number==1  # the Crew journey ends its first phase by halting the server JVM with no stop sequence
        for index,(process,_) in enumerate(processes):
            code=process.wait(timeout=120)
            if index==0 and abrupt:
                log=server_log.read_text(errors='replace')
                if code==0 or 'Stopping server' in log or 'Saving worlds' in log:
                    raise RuntimeError('The Crew journey must end phase 1 without a stop sequence (exit code %s)'%code)
                result_extra={'server_exit_code':code,'server_logged_a_stop_sequence':False}
            elif code!=0: raise RuntimeError('Journey process failed after assertions')
        result=json.loads(marker.read_text())
        if result['status']!='passed' or result['connected_clients']!=2: raise RuntimeError('Incomplete journey report')
        if abrupt: result.update(result_extra)
        if SCENARIO=='crew': result['clients']=check_crew_clients(number,clients)
        result['logins']=login_report(number)
        print('Real connected-client phase',number,'passed',flush=True)
        return result
    finally:
        for process,stream in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill()
            stream.close()


def login_report(number: int) -> dict:
    """How many connection attempts each client needed. A retried login is allowed (one seen on a hosted runner, cause unproven) but is never silent:
    it is printed as a warning annotation and counted in the report, so a run that needed a retry is visibly different from one that did not."""
    report={}
    for role in ('farmer','smith'):
        path=OUTPUT/'clients'/role/'rpg-core'/f'run-journey-client-{role}'/'journey-client-connection.json'
        report[role]=json.loads(path.read_text()) if path.exists() else {'attempts':None,'joined':None,'dropped_logins':[]}
        if report[role]['attempts'] not in (1,):
            print(f"::warning::Journey phase {number}: the {role} client needed {report[role]['attempts']} login attempt(s); dropped: {report[role]['dropped_logins']}",flush=True)
    return report


def read_client(role: str, number: int) -> dict:
    path=OUTPUT/'clients'/role/'rpg-core'/f'run-journey-client-{role}'/f'journey-crew-client-phase{number}.json'
    if not path.exists(): raise RuntimeError(f'The {role} client wrote no observations: {path}')
    return json.loads(path.read_text())


def need(condition: bool, what: str) -> None:
    if not condition: raise RuntimeError('Crew journey, client side: '+what)


def check_crew_clients(number: int, clients: list[tuple[Path,str]]) -> dict:
    """What each real client's own screen showed, compared with what the server was told to expect (the server asserts the ledger itself)."""
    founder,member=read_client('farmer',number),read_client('smith',number)
    if number==1:
        need(founder['after_step_1']['in_crew'] and founder['after_step_1']['role']=='Founder' and founder['after_step_1']['crew']=='Row Harvest','the founder did not see their new Crew')
        need(founder['after_step_5']['bank']==180 and founder['after_step_4']['wallet']==80,'the founder did not see 180 in the bank after both deposits: '+json.dumps(founder['after_step_5']))
        need('Row House:Crew:private=0' in founder['after_step_6']['homes'] and founder['after_step_6']['bank']==40,'the founder did not see the Row House as the Crew home with 40 left in the bank')
        need(founder['after_step_7']['member_may_withdraw'],'the founder did not see the withdraw permission switched on')
        need(member['after_step_1']['in_crew'] and member['after_step_1']['role']=='Member' and len(member['after_step_1']['members'])==2,'the member did not see the Crew after accepting')
        need(member['after_step_4']['wallet']==150,'the member did not receive the 10 withdrawn credits')
        need('Row House:Crew:private=1' in member['after_step_5']['homes'],'the member did not see their private container: '+json.dumps(member['after_step_5']['homes']))
    else:
        for who,obs,wallet,role in (('founder',founder,65,'Founder'),('member',member,150,'Member')):
            seen=obs['after_restart']
            need(seen['in_crew'] and seen['crew']=='Row Harvest' and seen['role']==role,f'the {who} lost the Crew across the restart: '+json.dumps(seen))
            need(seen['bank']==45 and seen['wallet']==wallet,f'the {who} saw bank {seen["bank"]} / wallet {seen["wallet"]} after the restart, expected 45 / {wallet}')
            need(len(seen['members'])==2 and any(h.startswith('Row House:Crew') for h in seen['homes']),f'the {who} lost members or the home across the restart')
            need(any(line.startswith('DEPOSIT 15') for line in seen['history_newest_first']),f'the {who} does not see the recovered deposit in the history')
        need(member['after_restart']['homes'] and 'private=1' in ''.join(member['after_restart']['homes']),'the private container was not shown after the restart')
        need(founder['after_step_2']['wallet']==60 and member['after_step_2']['wallet']==170,'the transfers made after the restart did not reach the screens')
    return {'founder':founder,'member':member}


def main() -> None:
    clients=prepare()
    results=[phase(1,clients),phase(2,clients)]
    (OUTPUT/'report.json').write_text(json.dumps({'minecraft':'1.20.1','forge':'47.4.10',
        'qshop':'1.8.4','scenario':SCENARIO or 'economy','actors':'two real loopback-only offline test clients','pack':os.environ.get('UC_JOURNEY_PACK','') or 'core only','phases':results},indent=2)+'\n')


if __name__=='__main__': main()
