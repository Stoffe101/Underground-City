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
# The combined journey (D-091): UC_JOURNEY_WORLD=generated runs on a Lost Cities world generated from seed 5908 instead of the flat fixture, and UC_JOURNEY_SURVIVAL=true adds the
# survival and exchange half (SurvivalJourney.java) to the Crew journey. Both need UC_JOURNEY_SCENARIO=crew; the pack sets are chosen with UC_JOURNEY_PACK as before.
GENERATED=os.environ.get('UC_JOURNEY_WORLD','').strip()=='generated'
SURVIVAL=os.environ.get('UC_JOURNEY_SURVIVAL','').strip().lower()=='true'
BANK_SPENT=14 if SURVIVAL else 0  # the interrupted wayside purchase (2 loaves at 7 credits), paid entirely by the Crew bank, which recovery committed at login


def prepare() -> list[tuple[Path,str]]:
    if SERVER.exists() or OUTPUT.exists():
        raise ValueError('Disposable journey directory already exists; preserve it and use a fresh checkout.')
    SERVER.mkdir()
    (SERVER/'eula.txt').write_text('eula=true\n')
    if GENERATED:
        if SCENARIO!='crew': raise ValueError('UC_JOURNEY_WORLD=generated needs UC_JOURNEY_SCENARIO=crew')
        # a real generated world: the normal overworld generator with the Lost Cities profile the dev pack stages (the world set), the reference seed of the world plan
        (SERVER/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25565\nonline-mode=false\n'
            'enforce-secure-profile=false\nlevel-name=world\nlevel-seed=5908\nview-distance=4\nsimulation-distance=4\ndifficulty=normal\n'
            'pvp=false\nmax-players=2\nspawn-protection=0\nmax-tick-time=-1\n')
    else:
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
        if GENERATED:
            # the world set's own jar and the Lost Cities profile are staged by prepareDevRun from here
            shutil.copytree(ROOT/'content/generated/world-prototype',module.parent/'content/generated/world-prototype')
        if 'survival' in os.environ.get('UC_JOURNEY_PACK','').split(','):
            # the generated Sophisticated Backpacks policy (server settings and item switches, D-092) is staged by prepareDevRun from here
            shutil.copytree(ROOT/'content/generated/survival-config',module.parent/'content/generated/survival-config')
        # Forge's early loading window gives up ("Timed out trying to setup the Game Window") when it cannot get a GL context in time, and the JVM then stays up behind an error dialog. It happened
        # to the second client of the every-set job on a loaded hosted runner and cost the 15-minute phase limit. The window is only the loading splash, so the journey clients start without it
        # (the same setting the world tour uses); Minecraft opens its own window as usual and the real screens are unaffected.
        config=module/f'run-journey-client-{role}'/'config'
        config.mkdir(parents=True,exist_ok=True)
        (config/'fml.toml').write_text('earlyWindowControl = false\n')
        clients.append((module,role))
    return clients


def pack_flags(server: bool = False) -> list[str]:
    """Third-party dev-pack sets to load on the server and on both clients (UC_JOURNEY_PACK, e.g. firearms,vehicles,vintner,farming), with a bigger client heap."""
    sets=os.environ.get('UC_JOURNEY_PACK','').strip()
    flags=[f'-PucPack={sets}','-PucClientHeap=2G'] if sets else []
    if GENERATED: flags+=['-PucDevWorld=true','-PucAutoWorld=false']
    if SURVIVAL: flags+=['-PucJourneySurvival=true']   # the server's staging (SurvivalJourney) and the Smith's client script (SurvivalJourneyActions) both need to know
    if server and GENERATED: flags+=['-PucJourneyWorld=generated','-PucServerHeap=3G']
    return flags


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


# First-start crash signatures of a pack mod that are allowed ONE retry (never silent: a warning annotation, the first log is kept and the retry is counted in the report). Traveler's Backpack 9.1.57
# had one (TravelersBackpackConfig$Server.loadItemsFromConfig, an ArrayIndexOutOfBoundsException while Forge reloaded a config the mod was still filling; D-092 retired that mod). Nothing in the
# Sophisticated Backpacks pair has crashed a start so far (docs/technical/first-start-stability.md), so the list is empty and a crash during a first start fails the journey; add a signature only
# for a crash that has been reproduced and understood.
FIRST_START_RACES: tuple[str, ...] = ()
# Forge's own message when a mod's config file could not be read (a torn write from a concurrent save) and was backed up and rewritten. The server survives it; the report counts it.
CONFIG_CORRECTION = 'is not correct. Correcting'


def start_server(number: int, server_log: Path, processes: list) -> int:
    """Starts the dedicated server and waits for it to be up. Returns how many first-start retries were needed (0 or 1)."""
    retries=0
    while True:
        processes.insert(0,start(MODULE,'runServer',['-PucRunDir=run-journey-server',f'-PucJourneyPhase={number}',*scenario_flags(number)[:1],*pack_flags(server=True)],server_log))
        deadline=time.monotonic()+900
        while time.monotonic()<deadline:
            text=server_log.read_text(errors='replace')
            if 'Done (' in text: return retries
            if processes[0][0].poll() is not None: break
            time.sleep(2)
        else: raise TimeoutError('Dedicated server startup timed out')
        text=server_log.read_text(errors='replace')
        race=next((signature for signature in FIRST_START_RACES if signature in text),None)
        if retries==0 and race:
            retries=1
            print(f'::warning::Journey phase {number}: the server died during its first start in a known race ({race}); starting it once more',flush=True)
            process,stream=processes.pop(0)
            stream.close()
            shutil.copy(server_log,server_log.with_name(f'server-phase{number}-first-start.log'))
            continue
        raise RuntimeError('Dedicated server exited before startup; inspect '+str(server_log))


def phase(number: int, clients: list[tuple[Path,str]]) -> dict:
    processes=[]
    try:
        server_log=OUTPUT/'logs'/f'server-phase{number}.log'
        server_retries=start_server(number,server_log,processes)
        deadline=time.monotonic()+900
        if GENERATED and number==1:
            # the generated world's fixture sites (the Row Flats beside the spawn, a wayside shelter in the open country) are requested from the chunk system as soon as the server is up; the clients
            # start only when they are in memory, so that the server has every core to itself while it generates and no client can be starved or timed out by it
            while time.monotonic()<deadline:
                if 'fixture sites generated' in server_log.read_text(errors='replace'): break
                if processes[0][0].poll() is not None: raise RuntimeError('Dedicated server exited while warming up the fixture sites; inspect '+str(server_log))
                time.sleep(2)
            else: raise TimeoutError('Fixture sites were not generated in time')
        for module,role in clients:
            name='UC_Farmer' if role=='farmer' else 'UC_Smith'
            processes.append(start(module,'runClient',[f'-PucRunDir=run-journey-client-{role}',f'-PucJourneyClient={name}',*scenario_flags(number),*pack_flags()],
                OUTPUT/'logs'/f'{role}-phase{number}.log',OUTPUT/'tmp'/role))
        marker=SERVER/marker_name(number)
        while time.monotonic()<deadline:
            if marker.exists(): break
            if any(process.poll() is not None for process,_ in processes):
                raise RuntimeError('A journey process exited before successful assertions; inspect logs')
            dead=client_that_died_starting(number)
            if dead: raise RuntimeError(f'The {dead} client could not open its window and is waiting behind an error dialog; inspect build/cloud-journey/logs/{dead}-phase{number}.log')
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
        if SCENARIO=='crew' and SURVIVAL: result['smith_backpack_screens']=check_survival_clients(number)
        result['logins']=login_report(number)
        result['server_first_start_retries']=server_retries
        result['server_config_corrections']=server_log.read_text(errors='replace').count(CONFIG_CORRECTION)
        print('Real connected-client phase',number,'passed',flush=True)
        return result
    finally:
        for process,stream in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=20)
                except subprocess.TimeoutExpired: process.kill()
            stream.close()


# Forge's early display prints "We died but didn't somehow?" with a stack trace each time one OpenGL version fails and then tries the next, so that line alone is not an error. It gives up with
# "Timed out trying to setup the Game Window" and an error dialog, and the JVM then stays up, so the runner would wait out its whole limit for a client that can never join.
WINDOW_FAILURE = 'Timed out trying to setup the Game Window'


def client_that_died_starting(number: int) -> str | None:
    for role in ('farmer','smith'):
        log=OUTPUT/'logs'/f'{role}-phase{number}.log'
        if log.exists() and WINDOW_FAILURE in log.read_text(errors='replace'): return role
    return None


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


def read_survival_client(number: int) -> dict:
    path=OUTPUT/'clients'/'smith'/'rpg-core'/'run-journey-client-smith'/f'journey-survival-client-phase{number}.json'
    if not path.exists(): raise RuntimeError(f'The Smith client wrote no backpack observations: {path}')
    return json.loads(path.read_text())


def collect_screenshots(number: int) -> list[str]:
    """Copies the screenshots the Smith's client took of its own screens (exchange, inventory, backpack at 720p, 1080p and 1440p) next to the report. Returns their names."""
    target=OUTPUT/'screenshots'
    target.mkdir(parents=True,exist_ok=True)
    names=[]
    shots=OUTPUT/'clients'/'smith'/'rpg-core'/'run-journey-client-smith'/'screenshots'
    for path in sorted(shots.glob(f'journey-{number}-*.png')) if shots.is_dir() else []:
        shutil.copy2(path,target/path.name)
        names.append(path.name)
    return names


def check_survival_clients(number: int) -> dict:
    """The backpack part of the combined journey, as the Smith's own screens showed it (the server asserts the contents itself, from its side)."""
    seen=read_survival_client(number)
    names=collect_screenshots(number)
    need(len(names)>=(3*4 if number==1 else 3),f'the Smith client took {len(names)} screenshots, fewer than expected: {names}')
    # three sizes per series; the client reports what the framebuffer really was, so a window the display could not give is not passed off as 1440p
    for shot in seen['screenshots']:
        need(shot['actual_framebuffer']==shot['requested'],f"a screenshot was requested at {shot['requested']} but the framebuffer was {shot['actual_framebuffer']}: the display is too small ({shot['file']})")
    if number==1:
        need(seen.get('backpack_price_seen')==70 and seen.get('backpack_stock_seen',0)>=1,'the exchange screen did not show the 70-credit backpack with stock: '+json.dumps({k:v for k,v in seen.items() if k.startswith('backpack')}))
        need(seen.get('worn_after_click') is True and seen.get('opened_with_key') is True,'the backpack was not worn by a click or opened with the key')
        need(seen.get('screen_shows_diamonds')==7 and seen.get('screen_shows_bread')==12 and seen.get('pickup_upgrade_left_the_inventory') is True,'the pack screen did not show what was moved in: '+json.dumps(seen))   # the upgrade slot itself is checked by the server
    else:
        need(seen.get('worn_after_restart') is True,'the backpack was not on the back after the restart')
        need(seen.get('screen_after_restart_shows_diamonds')==7 and seen.get('screen_after_restart_shows_bread')==12,'the pack screen after the restart did not show the contents: '+json.dumps(seen))   # the upgrade slot is checked by the server
    seen['screenshot_files']=names
    return seen


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
            need(seen['bank']==45-BANK_SPENT and seen['wallet']==wallet,f'the {who} saw bank {seen["bank"]} / wallet {seen["wallet"]} after the restart, expected {45-BANK_SPENT} / {wallet}')
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
