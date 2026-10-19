#!/usr/bin/env python3
"""Boot the production-style Underground City server twice and prove that it authenticates players only when it is told to (D-090).

1. ``online-mode=true`` (what ``make_server_pack.py`` writes): a login under a made-up name must be answered with an Encryption Request, i.e. the
   server will not go on until the client proves, through Mojang's session server, that it owns the account.
2. ``online-mode=false`` (the developer launch): the same login is accepted unproven. This second run exists so the first result means something: it
   shows the probe can tell the two apart, and it states plainly what the developer launch is.

This is a protocol-level check with no Minecraft client and no Microsoft account. It proves the server refuses unauthenticated identities; it does not
prove a real launcher account gets in (the manual Windows playtest does that).

    gradle -p rpg-core build
    python scripts/run_auth_check.py --work build/auth-check

Nothing is committed or uploaded. ``eula=true`` is written for this throwaway test server only, as the other cloud checks do; a real server's operator
reads the EULA and decides (``make_server_pack.py`` leaves ``eula=false`` unless asked).
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_server_pack as pack  # noqa: E402
import probe_login as probe  # noqa: E402
import run_prod_boot_check as boot  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PORT = 25590


def start(server: Path) -> subprocess.Popen:
    args = server / "libraries" / "net" / "minecraftforge" / "forge" / boot.FORGE / "unix_args.txt"
    log = (server / "auth-check.log").open("w", encoding="utf-8")
    return subprocess.Popen(["java", f"@{server / 'user_jvm_args.txt'}", f"@{args}", "nogui"], cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)


def wait_done(server: Path, process: subprocess.Popen, seconds: int) -> bool:
    deadline = time.time() + seconds
    log = server / "auth-check.log"
    while time.time() < deadline:
        if process.poll() is not None:
            return False
        if log.exists() and re.search(r"Done \([0-9.]+s\)!", log.read_text(encoding="utf-8", errors="replace")):
            return True
        time.sleep(2)
    return False


def stop(process: subprocess.Popen) -> None:
    try:
        process.stdin.write("stop\n")
        process.stdin.flush()
        process.wait(timeout=90)
    except Exception:  # noqa: BLE001 - the throwaway server is killed if it will not stop
        process.kill()


def run_once(server: Path, authenticated: bool, expect: str, timeout: int) -> dict:
    (server / "server.properties").write_text(pack.properties(PORT, "auth check", authenticated).replace("server-port=" + str(PORT), "server-ip=127.0.0.1\nserver-port=" + str(PORT)), encoding="utf-8")
    process = start(server)
    try:
        if not wait_done(server, process, timeout):
            return {"authenticated_setting": authenticated, "verdict": "server did not start", "ok": False}
        verdict, detail = probe.probe("127.0.0.1", PORT, "UcProbe", 20.0)
        # The server should also say, in its own log, what it will do about identities.
        log = (server / "auth-check.log").read_text(encoding="utf-8", errors="replace")
        said = re.findall(r"\[UC\] identity: .*", log)
        return {"authenticated_setting": authenticated, "verdict": verdict, "detail": detail, "ok": verdict == expect, "server_said": said[:2]}
    finally:
        stop(process)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work", type=Path, default=ROOT / "build/auth-check")
    parser.add_argument("--core-jar", type=Path, default=None)
    parser.add_argument("--timeout", type=int, default=420)
    args = parser.parse_args()
    core = args.core_jar or next(iter(sorted((ROOT / "rpg-core/build/libs").glob("underground_city-*.jar"))), None)
    if core is None or not core.exists():
        raise SystemExit("Build the Core jar first: gradle -p rpg-core build")
    args.work.mkdir(parents=True, exist_ok=True)
    server = boot.install_forge(args.work.resolve())
    staged = boot.stage_mods(server, [], core)          # Core and Q Shop only: authentication does not depend on the other mods
    pack.write_scripts(server)
    (server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    results = [run_once(server, True, "authenticated", args.timeout), run_once(server, False, "unauthenticated", args.timeout)]
    report = {"forge": boot.FORGE, "staged": staged, "runs": results}
    (args.work / "auth-check-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    for run in results:
        print(f"online-mode={str(run['authenticated_setting']).lower()}: {run['verdict']} {run.get('detail', '')}")
        for line in run.get("server_said", []):
            print("   server log:", line)
    ok = all(run["ok"] for run in results)
    print("RESULT:", "pass" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
