#!/usr/bin/env python3
"""Repeatable comparison of the long server ticks that Lost Cities causes after bulk chunk generation (docs/technical/lost-cities-stalls.md).

For each requested Lost Cities release this builds a disposable copy of the module whose development-pack manifest names that release (metadata from the Modrinth API; the jar
is then fetched and hash-verified by Gradle as for any pinned mod, never committed), starts a dedicated server on a generated Lost Cities world (seed 5908, the world set's profile,
no client) with ``max-tick-time=-1`` ONLY so that a stall can be measured instead of ending the run, makes the server request a square of chunks the way a pregenerator does
(``StallProbe``), and collects the probe's ``RESULT`` line and the stacks it sampled while a tick ran long. It never changes the watchdog of any real server.

    python scripts/run_stall_probe.py --versions 7.5.5 7.5.6 --radius 8 --runs 2 --mode bulk
    python scripts/run_stall_probe.py --versions 7.5.5 --radius 8 --lost-cities-config name=value   (a Lost Cities common-config override, repeatable)

Results go to ``build/stall-probe/<label>/run<N>/`` (log and result JSON) and a summary table is printed. Run it in Claude's cloud environment or a hosted runner, not on a
player's computer.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "rpg-core"
OUTPUT = ROOT / "build/stall-probe"
VERSION_IDS = {"7.5.5": "Ec9sXB06", "7.5.6": "YOr2hSvX", "7.5.7": "zDRDuewL"}
API = "https://api.modrinth.com/v2"


def api(path: str):
    request = urllib.request.Request(API + path, headers={"User-Agent": "underground-city-dev/0.1 (stall probe)"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def manifest_for(version: str) -> dict:
    """The development-pack manifest with the Lost Cities entry replaced by the requested release (metadata only)."""
    manifest = json.loads((ROOT / "content/modpacks/dev-pack.json").read_text(encoding="utf-8"))
    if version not in VERSION_IDS:
        raise SystemExit(f"unknown Lost Cities release {version}; known: {sorted(VERSION_IDS)}")
    release = api(f"/version/{VERSION_IDS[version]}")
    file = next(f for f in release["files"] if f.get("primary", True))
    for mod in manifest["mods"]:
        if mod["id"] != "lostcities":
            continue
        mod.update(version_id=release["id"], version_number=release["version_number"], filename=file["filename"], sha512=file["hashes"]["sha512"],
                   sha1=file["hashes"].get("sha1", mod.get("sha1")), size=file["size"], published=release["date_published"][:10], source_url=f"https://modrinth.com/mod/the-lost-cities/version/{release['id']}")
    return manifest


def prepare(version: str, label: str) -> Path:
    base = OUTPUT / "projects" / label
    if base.exists():
        return base / "rpg-core"
    shutil.copytree(MODULE, base / "rpg-core", ignore=shutil.ignore_patterns("run", "run-*", "build", ".gradle"))
    (base / "content").mkdir(parents=True)
    shutil.copytree(ROOT / "content/modpacks", base / "content/modpacks")
    shutil.copytree(ROOT / "content/generated/world-prototype", base / "content/generated/world-prototype")
    (base / "content/modpacks/dev-pack.json").write_text(json.dumps(manifest_for(version), indent=2), encoding="utf-8")
    # the world set's own mods.toml demands exactly Lost Cities 7.5.5 (the release its surveys were measured on, a deliberate gate); the probe copy, and only the copy, accepts newer ones
    toml = base / "content/generated/world-prototype/pack/META-INF/mods.toml"
    toml.write_text(toml.read_text(encoding="utf-8").replace('versionRange="[1.20-7.5.5]"', 'versionRange="[1.20-7.5.5,)"'), encoding="utf-8")
    return base / "rpg-core"


def run_once(module: Path, label: str, number: int, args, overrides: list[str]) -> dict:
    run_dir = module / "run-stall"
    if run_dir.exists():
        shutil.rmtree(run_dir)
    run_dir.mkdir()
    (run_dir / "eula.txt").write_text("eula=true\n")
    (run_dir / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=25570\nonline-mode=false\nenforce-secure-profile=false\nlevel-name=world\nlevel-seed=5908\n"
                                               "view-distance=4\nsimulation-distance=4\ndifficulty=normal\npvp=false\nmax-players=2\nspawn-protection=0\nmax-tick-time=-1\n")
    log = OUTPUT / label / f"run{number}"
    log.mkdir(parents=True, exist_ok=True)
    command = ["gradle", "-p", str(module), "runServer", "-PucPack=world", "-PucDevWorld=true", "-PucAutoWorld=false", "-PucStallProbe=true", "-PucRunDir=run-stall",
               f"-PucStallRadius={args.radius}", f"-PucStallMode={args.mode}", f"-PucStallCenter={args.center}", f"-PucStallSettle={args.settle}", f"-PucStallPertick={args.per_tick}", f"-PucStallEvery={args.every}", "--no-daemon"]
    started = time.monotonic()
    with (log / "server.log").open("w") as sink:
        # the module is a copy: a Lost Cities configuration override is applied to its staged config before the server reads it (done below, after prepareDevRun, by running it first)
        if overrides:
            subprocess.run(["gradle", "-p", str(module), "prepareDevRun", "-PucPack=world", "-PucDevWorld=true", "-PucAutoWorld=false", "-PucRunDir=run-stall", "--no-daemon"], stdout=sink, stderr=subprocess.STDOUT, check=True)
            apply_overrides(run_dir, overrides)
        result = subprocess.run(command, stdout=sink, stderr=subprocess.STDOUT, timeout=args.timeout)
    text = (log / "server.log").read_text(errors="replace")
    match = re.search(r"\[UC-STALL-PROBE\] RESULT (\{.*\})", text)
    summary = json.loads(match.group(1)) if match else {"error": f"no result (exit {result.returncode})"}
    summary["label"], summary["run"], summary["gradle_seconds"] = label, number, int(time.monotonic() - started)
    summary["server_can_not_keep_up_lines"] = len(re.findall(r"Can't keep up", text))
    summary["worst_behind_ms"] = max([int(x) for x in re.findall(r"Running (\d+)ms or", text)] or [0])
    where = [line for line in text.splitlines() if "where long ticks spent their time" in line]
    (log / "result.json").write_text(json.dumps({"summary": summary, "where": where}, indent=2))
    return summary


def apply_overrides(run_dir: Path, overrides: list[str]) -> None:
    """Rewrites ``key = value`` lines in the Lost Cities configuration files the world set staged into the run directory (the common config and the defaults a NEW world starts from)."""
    for key_value in overrides:
        key, value = key_value.split("=", 1)
        changed = False
        for folder in ("config", "defaultconfigs"):
            for path in (run_dir / folder).rglob("*"):
                if path.is_file() and path.suffix in (".toml", ".cfg") and "lostcit" in path.name.lower():
                    text = path.read_text(encoding="utf-8")
                    new = re.sub(rf"(\b{re.escape(key)}\b\s*[=:]\s*)[^\n,]+", rf"\g<1>{value}", text)
                    if new != text:
                        path.write_text(new, encoding="utf-8")
                        changed = True
        if not changed:
            # the world set's defaults name only the profile; every other key of the [profiles] table of lostcities-server.toml takes the mod's own default until a line sets it, so the line is added
            defaults = run_dir / "defaultconfigs" / "lostcities-server.toml"
            text = defaults.read_text(encoding="utf-8") if defaults.exists() else "[profiles]\n"
            if "[profiles]" not in text:
                raise SystemExit("the staged lostcities-server.toml has no [profiles] table to hold the override")
            defaults.parent.mkdir(parents=True, exist_ok=True)
            defaults.write_text(text.replace("[profiles]", f"[profiles]\n{key} = {value}", 1), encoding="utf-8")
            changed = True
            print(f"override {key} = {value} added to the [profiles] table of the staged defaultconfigs/lostcities-server.toml", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--versions", nargs="+", default=["7.5.5"])
    parser.add_argument("--radius", type=int, default=8)
    parser.add_argument("--center", default="0,0")
    parser.add_argument("--mode", choices=["bulk", "paced"], default="bulk")
    parser.add_argument("--settle", type=int, default=1200)
    parser.add_argument("--per-tick", type=int, default=4, help="chunks requested per tick in paced mode (a walking player needs about 3 per second, 1 per 7 ticks; 1 per tick is a fast pregenerator)")
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=3000)
    parser.add_argument("--label", default="")
    parser.add_argument("--every", type=int, default=1, help="paced mode: ask for --per-tick chunks every this many ticks (--per-tick 1 --every 7 is about a walking player)")
    parser.add_argument("--lost-cities-config", action="append", default=[], help="key=value override in the staged Lost Cities configuration (repeatable)")
    args = parser.parse_args()
    rows = []
    for version in args.versions:
        label = (args.label or version) + ("+" + "+".join(args.lost_cities_config) if args.lost_cities_config and not args.label else "")
        module = prepare(version, re.sub(r"[^A-Za-z0-9_.+=-]", "_", label))
        for number in range(1, args.runs + 1):
            rows.append(run_once(module, re.sub(r"[^A-Za-z0-9_.+=-]", "_", label), number, args, args.lost_cities_config))
            print(json.dumps(rows[-1]), flush=True)
    print("\nlabel | run | chunks full | s to chunks | max tick ms | >1s | >5s | >30s | >60s | over-budget total s | behind ms | leaves / logs / saplings in the area")
    for r in rows:
        if "error" in r:
            print(f"{r['label']} | {r['run']} | {r['error']}")
            continue
        print(f"{r['label']} | {r['run']} | {r['chunks_full']}/{r['chunks_requested']} | {r['seconds_to_all_chunks']} | {r['max_tick_ms']} | {r['ticks_over_1s']} | {r['ticks_over_5s']} | "
              f"{r['ticks_over_30s']} | {r['ticks_over_60s_watchdog_would_kill']} | {r['over_budget_total_ms'] // 1000} | {r['worst_behind_ms']}"
              + (f" | {r['leaf_blocks']} / {r['log_blocks']} / {r['sapling_blocks']}" if "leaf_blocks" in r else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
