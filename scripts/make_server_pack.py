#!/usr/bin/env python3
"""Build a production-style dedicated server folder for Underground City that authenticates players (D-090).

Why: the developer launch (``gradlew runServer``) is meant for one developer or a trusted LAN; its clients use *offline* profiles. A server that real
launcher accounts join must run with ``online-mode=true``, so that Minecraft's own authentication proves which Mojang account owns each connection.
Underground City keys everything that matters (Crew membership, roles, the Crew bank, homes and private containers, wallets, career progress) by that
account's UUID, never by the name, so authentication is what stops one player from acting as another.

This script installs the pinned Forge server in an untracked folder, stages the reobfuscated Core jar, Q Shop 1.8.4 and the chosen pinned mods (every
jar is downloaded from its author's page and hash-verified, see ``content/modpacks/dev-pack.json``), and writes a ``server.properties`` with
``online-mode=true``, ``enforce-secure-profile=true`` and a white-list, plus start scripts for Windows and Linux. Nothing is committed or uploaded.

    gradle -p rpg-core build
    python scripts/make_server_pack.py --sets world firearms vehicles boats --output build/server-pack --whitelist Alice Bob
    # read the Minecraft EULA yourself, then either edit eula.txt or add --accept-eula
    build/server-pack/server/start-server.sh        (Windows: build\\server-pack\\server\\start-server.bat)

The white-list takes player NAMES here only because Minecraft's own command and file need them; the server resolves each to the Mojang account UUID
the first time the file is read, and from then on only that UUID matters.

Writes ``client-mods.json``: the mods a joining player must have, with their pinned versions and SHA-512 hashes. Players fetch the same pinned jars themselves with
``scripts/fetch_dev_pack.py`` (do not redistribute third-party jars) and receive the Core jar from you privately.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_prod_boot_check as boot  # noqa: E402
import fetch_dev_pack as pack  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
WINDOWS_ARGS = "libraries/net/minecraftforge/forge/{forge}/win_args.txt"
UNIX_ARGS = "libraries/net/minecraftforge/forge/{forge}/unix_args.txt"


def properties(port: int, motd: str, authenticated: bool) -> str:
    """The server.properties of the pack. ``authenticated=False`` exists only for the cloud test that proves the difference."""
    rows = {
        "server-port": str(port),
        "online-mode": "true" if authenticated else "false",
        "enforce-secure-profile": "true" if authenticated else "false",
        "white-list": "true" if authenticated else "false",
        "enforce-whitelist": "true" if authenticated else "false",
        "level-seed": "5908",            # the proven development seed (do not change a proven seed for convenience)
        "motd": motd,
        "view-distance": "8",
        "simulation-distance": "6",
        "difficulty": "normal",
        "gamemode": "survival",
        "max-players": "8",
        "spawn-protection": "0",
        "pvp": "false",                  # core co-op: no forced PvP (CLAUDE.md)
    }
    return "".join(f"{key}={value}\n" for key, value in rows.items())


def whitelist_file(names: list[str]) -> str:
    """Minecraft reads names from a command, not from this file (it wants UUIDs); the operator runs ``whitelist add`` on first start. This lists the names to add."""
    return "".join(f"whitelist add {name}\n" for name in names)


def write_scripts(server: Path) -> None:
    (server / "user_jvm_args.txt").write_text("-Xms2G\n-Xmx4G\n", encoding="utf-8")
    forge = boot.FORGE
    (server / "start-server.bat").write_text(
        "@echo off\r\ncd /d %~dp0\r\n"
        f"java @user_jvm_args.txt @{WINDOWS_ARGS.format(forge=forge)} nogui %*\r\npause\r\n", encoding="utf-8")
    shell = server / "start-server.sh"
    shell.write_text(
        "#!/bin/sh\ncd \"$(dirname \"$0\")\"\n"
        f"exec java @user_jvm_args.txt @{UNIX_ARGS.format(forge=forge)} nogui \"$@\"\n", encoding="utf-8")
    shell.chmod(0o755)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sets", nargs="+", default=["world", "firearms", "vehicles", "boats"])
    parser.add_argument("--output", type=Path, default=ROOT / "build/server-pack")
    parser.add_argument("--core-jar", type=Path, default=None, help="reobfuscated Core jar (default: rpg-core/build/libs/underground_city-*.jar)")
    parser.add_argument("--whitelist", nargs="*", default=[], help="player names to white-list after the first start")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--motd", default="Underground City (private)")
    parser.add_argument("--accept-eula", action="store_true", help="write eula=true. That is YOUR decision to accept the Minecraft EULA (https://aka.ms/MinecraftEULA)")
    parser.add_argument("--unauthenticated", action="store_true", help="online-mode=false. ONLY for a throwaway test on a closed network; never for a server others can reach")
    args = parser.parse_args()
    core = args.core_jar or next(iter(sorted((ROOT / "rpg-core/build/libs").glob("underground_city-*.jar"))), None)
    if core is None or not core.exists():
        raise SystemExit("Build the Core jar first: gradle -p rpg-core build")
    args.output.mkdir(parents=True, exist_ok=True)
    server = boot.install_forge(args.output.resolve())
    staged = boot.stage_mods(server, args.sets, core)
    (server / "server.properties").write_text(properties(args.port, args.motd, not args.unauthenticated), encoding="utf-8")
    (server / "eula.txt").write_text(("eula=true\n" if args.accept_eula else "eula=false\n# Read https://aka.ms/MinecraftEULA and set this to true yourself.\n"), encoding="utf-8")
    write_scripts(server)
    if args.whitelist:
        (server / "whitelist-commands.txt").write_text(whitelist_file(args.whitelist), encoding="utf-8")
    data = pack.load()
    manifest = {
        "forge": boot.FORGE,
        "sets": args.sets,
        "authenticated": not args.unauthenticated,
        "mods": [{"file": name} for name in staged],
        "fetch_command": "python scripts/fetch_dev_pack.py --sets " + " ".join(args.sets) + " --output <your .minecraft/mods>",
        "also_needed": ["qshop-forge-1.20.1-1.8.4.jar (hash-pinned, see content/modpacks/dev-pack.json)", core.name + " (from the host)"],
        "pinned": {m["id"]: {"file": m["filename"], "version": m["version_number"], "sha512": m["sha512"], "license": m["license"]} for m in pack.selected(data, args.sets)},
    }
    (args.output / "client-mods.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("server folder:", server)
    print("online-mode:", "false (UNAUTHENTICATED, test only)" if args.unauthenticated else "true (accounts are verified by Mojang)")
    print("staged:", ", ".join(staged))
    print("next: read the EULA, set eula=true (or re-run with --accept-eula), start start-server.bat / start-server.sh, then run the commands in whitelist-commands.txt in the server console")
    return 0


if __name__ == "__main__":
    sys.exit(main())
