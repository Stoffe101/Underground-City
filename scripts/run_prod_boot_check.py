#!/usr/bin/env python3
"""Boot Underground City on a production-style Forge dedicated server with the pinned dev-pack mods.

Why: the ForgeGradle development environment is stricter than a real server (for example Forge throws, only in
development, when a mod reads its config in its constructor, which Modern Life does) and loads content-pack mods
differently. This check installs a real Forge 1.20.1-47.4.10 server in an untracked folder, puts the reobfuscated
Core jar, Q Shop 1.8.4 and the selected pinned mods in an ordinary ``mods`` folder, starts it with
``-Duc.bootSmoke=true`` and reads the ``[UC-BOOT]`` lines that ``BootSmokeHarness`` prints (mods loaded, registry
counts, Core datapack catalogs, the motorboat's cargo round trip) before the server stops itself.

Nothing is committed or uploaded: every jar is downloaded from the author's page, size and hash verified, into the
work directory. Usage:

    gradle -p rpg-core build
    python scripts/run_prod_boot_check.py --sets firearms vehicles boats --work build/prod-boot

Exit code 0 only when the server reached "done", every mod of the selected sets is loaded and no check failed.
This proves the mods boot together and register their content; it does not prove gameplay, driving or client use.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_dev_pack as pack  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
FORGE = "1.20.1-47.4.10"
INSTALLER_URL = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{FORGE}/forge-{FORGE}-installer.jar"
QSHOP_URL = "https://github.com/QLNPLUS/Q-shop/releases/download/v1.8.4-forge-1.20.1/qshop-forge-1.20.1-1.8.4.jar"
USER_AGENT = "underground-city-dev/0.1 (production boot check)"


def fetch(url: str, target: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=600) as response:
        target.write_bytes(response.read())


def install_forge(work: Path) -> Path:
    server = work / "server"
    marker = server / "libraries" / "net" / "minecraftforge" / "forge" / FORGE / "unix_args.txt"
    if marker.exists():
        return server
    server.mkdir(parents=True, exist_ok=True)
    installer = work / f"forge-{FORGE}-installer.jar"
    fetch(INSTALLER_URL, installer)
    published = urllib.request.urlopen(urllib.request.Request(INSTALLER_URL + ".sha1", headers={"User-Agent": USER_AGENT}), timeout=60).read().decode().strip()
    if hashlib.sha1(installer.read_bytes()).hexdigest() != published:
        raise SystemExit("Forge installer SHA-1 differs from the one Forge publishes; refusing to run it")
    subprocess.run(["java", "-jar", str(installer), "--installServer", str(server)], check=True, cwd=work)
    if not marker.exists():
        raise SystemExit("Forge server install did not produce unix_args.txt")
    return server


def stage_mods(server: Path, sets: list[str], core_jar: Path) -> list[str]:
    mods = server / "mods"
    if mods.exists():
        shutil.rmtree(mods)
    mods.mkdir()
    staged = []
    data = pack.load()
    for mod in pack.selected(data, sets):
        path = pack.download(mod, mods)
        staged.append(path.name)
    qshop = mods / "qshop-forge-1.20.1-1.8.4.jar"
    fetch(QSHOP_URL, qshop)
    expected = next(m["sha256"] for m in data["external"] if m["id"] == "qshop")
    if hashlib.sha256(qshop.read_bytes()).hexdigest() != expected:
        qshop.unlink()
        raise SystemExit("Q Shop SHA-256 differs from the pin")
    shutil.copy2(core_jar, mods / core_jar.name)
    staged += [qshop.name, core_jar.name]
    if "world" in sets:
        subprocess.run([sys.executable, str(ROOT / "scripts/build_world_prototype.py"), "--output", str(mods / "underground-city-world-prototype.jar")], check=True)
        staged.append("underground-city-world-prototype.jar")
    return staged


def run_server(server: Path, seconds: int) -> list[str]:
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server / "server.properties").write_text("server-ip=127.0.0.1\nonline-mode=false\nview-distance=3\nsimulation-distance=3\nlevel-seed=166\n", encoding="utf-8")
    jvm = server / "user_jvm_args.txt"
    jvm.write_text("-Xms512M\n-Xmx3G\n-Duc.bootSmoke=true\n", encoding="utf-8")
    args = server / "libraries" / "net" / "minecraftforge" / "forge" / FORGE / "unix_args.txt"
    command = ["java", f"@{jvm}", f"@{args}", "nogui"]
    log = server / "boot-stdout.log"
    with log.open("w", encoding="utf-8") as sink:
        try:
            subprocess.run(command, cwd=server, stdout=sink, stderr=subprocess.STDOUT, timeout=seconds, check=False)
        except subprocess.TimeoutExpired:
            sink.write("\n[UC-BOOT-CHECK] timed out\n")
    return log.read_text(encoding="utf-8", errors="replace").splitlines()


def evaluate(lines: list[str], sets: list[str]) -> tuple[dict, list[str]]:
    facts: dict[str, str] = {}
    for line in lines:
        match = re.search(r"\[UC-BOOT\] (.*)", line)
        if match:
            text = match.group(1)
            key, _, value = text.partition("=")
            facts[key.strip()] = value.strip() if value else "true"
    problems = []
    if "done" not in facts:
        problems.append("the server never reached the boot-smoke harness (see boot-stdout.log)")
    for line in lines:
        if "Failed to start the minecraft server" in line or "Exception caught during firing event" in line:
            problems.append("mod loading error: " + line.strip()[:160])
            break
    need = {"firearms": ["tacz", "taczblueprints"], "vehicles": ["mts", "mtsofficialpack", "oamp"], "boats": ["modernlife"], "world": ["lostcities"]}
    for name in sets:
        for mod_id in need.get(name, []):
            if facts.get(f"mod {mod_id}") != "True" and facts.get(f"mod {mod_id}") != "true":
                problems.append(f"mod {mod_id} did not load")
    for mod_id in ("underground_city", "qshop"):
        if facts.get(f"mod {mod_id}", "").lower() != "true":
            problems.append(f"mod {mod_id} did not load")
    if "vehicles" in sets:
        # Immersive Vehicles registers each content-pack vehicle and part as an mts:<pack>.<name> item.
        for pack_name in ("mtsofficialpack", "oamp"):
            count = facts.get(f"mts pack {pack_name} items", "0")
            if not count.isdigit() or int(count) < 1:
                problems.append(f"content pack {pack_name} registered no vehicle items")
    if "boats" in sets and not facts.get("boat_cargo", "").startswith("ok"):
        problems.append("boat cargo round trip: " + facts.get("boat_cargo", "not run"))
    return facts, problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sets", nargs="+", default=["firearms", "vehicles", "boats"])
    parser.add_argument("--work", type=Path, default=ROOT / "build/prod-boot")
    parser.add_argument("--core-jar", type=Path, default=None, help="reobfuscated Core jar (default: rpg-core/build/libs/underground_city-*.jar)")
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    core = args.core_jar or next(iter(sorted((ROOT / "rpg-core/build/libs").glob("underground_city-*.jar"))), None)
    if core is None or not core.exists():
        raise SystemExit("Build the Core jar first: gradle -p rpg-core build")
    args.work.mkdir(parents=True, exist_ok=True)
    server = install_forge(args.work.resolve())
    staged = stage_mods(server, args.sets, core)
    print("staged:", ", ".join(staged))
    lines = run_server(server, args.timeout)
    facts, problems = evaluate(lines, args.sets)
    report = {"forge": FORGE, "sets": args.sets, "staged": staged, "facts": facts, "problems": problems}
    (args.work / "prod-boot-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    for key, value in facts.items():
        print(f"  {key} = {value}")
    for problem in problems:
        print("PROBLEM:", problem)
    print("RESULT:", "pass" if not problems else "FAIL")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
