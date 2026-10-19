#!/usr/bin/env python3
"""What really happens to Traveler's Backpack items in a world that is opened without the mod (D-092, docs/technical/backpack-migration.md).

The owner's rule: Traveler's Backpack items in an existing world are never deleted silently, and automatic migration is not claimed unless it is implemented and tested. This script
does not convert anything. It **measures** what Forge 47.4.10 does on a production-style dedicated server, so that the migration document states what was observed:

  1. Start the server with the retired provider (the ``legacy_travelers`` set), put a Traveler's Backpack block, a chest that holds one beside a diamond, and a dropped backpack item
     into the world with console commands, and stop it normally.
  2. Start the same world with the current provider (the ``survival`` set: Sophisticated Backpacks) and no Traveler's Backpack. Record what the log says, whether the server asks a
     question or refuses, what the three places hold, then stop it normally (which saves the world).
  3. Start the same world once more with the retired provider. Did the items come back?
  4. As a control, restore the world copy taken after step 1 and open it with the retired provider only (the documented way to get the contents out).

Nothing is committed. The jars are downloaded from the authors' pages and hash-verified as for every other pinned mod. Run it in Claude's cloud environment or a hosted runner.

    gradle -p rpg-core build
    python scripts/run_backpack_migration_check.py --work build/migration
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_prod_boot_check as boot  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
TRAVELER_BLOCK = "travelersbackpack:standard"
MARKER = "uc_migration_marker"


class Console:
    """A dedicated server process whose console we can type into and whose output we can wait on."""

    def __init__(self, server: Path, label: str, log_dir: Path):
        self.log_path = log_dir / f"{label}.log"
        self.sink = self.log_path.open("w", encoding="utf-8")
        args = server / "libraries" / "net" / "minecraftforge" / "forge" / boot.FORGE / "unix_args.txt"
        jvm = server / "user_jvm_args.txt"
        jvm.write_text("-Xms512M\n-Xmx3G\n", encoding="utf-8")
        self.process = subprocess.Popen(["java", f"@{jvm}", f"@{args}", "nogui"], cwd=server, stdin=subprocess.PIPE, stdout=self.sink, stderr=subprocess.STDOUT, text=True)

    def text(self) -> str:
        if not self.sink.closed:
            self.sink.flush()
        return self.log_path.read_text(encoding="utf-8", errors="replace")

    def wait_for(self, pattern: str, timeout: int, since: int = 0) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if re.search(pattern, self.text()[since:]):
                return True
            if self.process.poll() is not None:
                return bool(re.search(pattern, self.text()[since:]))
            time.sleep(1)
        return False

    def send(self, command: str, expect: str | None = None, timeout: int = 30) -> str:
        """Types a console command and returns the log text it produced (waits for ``expect`` when given, else for a short pause)."""
        before = len(self.text())
        assert self.process.stdin is not None
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        if expect:
            self.wait_for(expect, timeout, before)
        else:
            time.sleep(2)
        return self.text()[before:]

    def stop(self, timeout: int = 180) -> int | None:
        if self.process.poll() is None:
            try:
                assert self.process.stdin is not None
                self.process.stdin.write("stop\n")
                self.process.stdin.flush()
            except (BrokenPipeError, ValueError):
                pass
            try:
                self.process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                self.process.kill()
        self.sink.close()
        return self.process.returncode


def prepare_server(base: Path, target: Path, sets: list[str], core: Path) -> Path:
    """A server folder with the mods of ``sets``; ``libraries`` is shared by link, ``mods`` is staged fresh."""
    target.mkdir(parents=True, exist_ok=True)
    if not (target / "libraries").exists():
        os.symlink((base / "libraries").resolve(), target / "libraries")
    boot.stage_mods(target, sets, core)
    (target / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (target / "server.properties").write_text("server-ip=127.0.0.1\nonline-mode=false\nview-distance=3\nsimulation-distance=3\nlevel-seed=166\nspawn-protection=0\n", encoding="utf-8")
    return target


def swap_mods(server: Path, sets: list[str], core: Path) -> None:
    boot.stage_mods(server, sets, core)


def fml_registry_mentions(world: Path, needle: str) -> dict:
    """Does level.dat still name the mod? (Forge stores the registry names it saw under the 'fml' tag.)"""
    path = world / "level.dat"
    if not path.exists():
        return {"level_dat": False}
    import gzip
    raw = gzip.decompress(path.read_bytes())
    return {"level_dat": True, "mentions_" + needle: needle.encode() in raw, "bytes": len(raw)}


def region_mentions_dir(folder: Path, needle: str) -> int:
    """How many chunks of the region files in ``folder`` still contain ``needle`` (a chunk is zlib-compressed on its own inside the region file)."""
    import struct
    import zlib
    hits = 0
    for path in sorted(folder.glob("*.mca")) if folder.is_dir() else []:
        data = path.read_bytes()
        for i in range(1024):
            offset = struct.unpack(">I", data[4 * i:4 * i + 4])[0]
            sector, count = offset >> 8, offset & 0xFF
            if not sector or not count:
                continue
            start = sector * 4096
            length = struct.unpack(">I", data[start:start + 4])[0]
            try:
                raw = zlib.decompress(data[start + 5:start + 4 + length]) if data[start + 4] == 2 else data[start + 5:start + 4 + length]
            except zlib.error:
                continue
            if needle.encode() in raw:
                hits += 1
    return hits


def snapshot(server: Path) -> dict:
    world = server / "world"
    return {
        "level_dat": fml_registry_mentions(world, "travelersbackpack"),
        "chunks_naming_the_mod": region_mentions_dir(world / "region", "travelersbackpack"),
        "entity_chunks_naming_the_mod": region_mentions_dir(world / "entities", "travelersbackpack"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work", type=Path, default=ROOT / "build/migration")
    parser.add_argument("--core-jar", type=Path, default=None)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    args.work = args.work.resolve()
    core = args.core_jar or next(iter(sorted((ROOT / "rpg-core/build/libs").glob("underground_city-*.jar"))), None)
    if core is None or not core.exists():
        raise SystemExit("Build the Core jar first: gradle -p rpg-core build")
    args.work.mkdir(parents=True, exist_ok=True)
    base = boot.install_forge(args.work.resolve())
    logs = args.work / "logs"
    logs.mkdir(exist_ok=True)
    server = prepare_server(base, args.work / "server", ["legacy_travelers"], core)
    if (server / "world").exists():
        shutil.rmtree(server / "world")
    report: dict = {"forge": boot.FORGE, "steps": {}}

    # 1. the old world, made with the retired provider ---------------------------------------------------------------------------------------------------
    c = Console(server, "1-populate-with-travelers", logs)
    if not c.wait_for(r"Done \(", args.timeout):
        c.stop()
        raise SystemExit("step 1: the server did not start; see " + str(c.log_path))
    c.send("forceload add 0 0")
    placed = c.send(f"setblock 0 100 0 {TRAVELER_BLOCK}", r"Changed the block|Could not set", 20)
    chest = c.send(f'setblock 2 100 0 minecraft:chest{{Items:[{{Slot:0b,id:"{TRAVELER_BLOCK}",Count:1b,tag:{{{MARKER}:1b}}}},{{Slot:1b,id:"minecraft:diamond",Count:5b}}]}}', r"Changed the block|Could not set", 20)
    dropped = c.send(f'summon minecraft:item 4 100 0 {{Item:{{id:"{TRAVELER_BLOCK}",Count:1b,tag:{{{MARKER}:1b}}}},PersistenceRequired:1b}}', r"Summoned|Unable", 20)
    c.send("data get block 2 100 0 Items", r"has the following block data|Found no", 20)
    c.send("save-all flush", r"Saved the game|Saved", 60)
    code1 = c.stop()
    report["steps"]["1_populate"] = {"exit_code": code1, "core_notice_logged": "THIS WORLD WAS SAVED WITH TRAVELER'S BACKPACK" in c.text(), "placed_block": placed.strip()[-120:], "chest": chest.strip()[-160:], "dropped_item": dropped.strip()[-120:], "world": snapshot(server)}
    print(json.dumps(report["steps"]["1_populate"], indent=2), flush=True)
    backup = args.work / "world-after-step-1"
    if backup.exists():
        shutil.rmtree(backup)
    shutil.copytree(server / "world", backup)

    # 2. the same world, current provider, no Traveler's Backpack -----------------------------------------------------------------------------------------
    swap_mods(server, ["survival"], core)
    boot.stage_survival_config(server)
    c = Console(server, "2-open-with-sophisticated", logs)
    started = c.wait_for(r"Done \(", 240)
    log2 = c.text()
    step2: dict = {"reached_done": started, "core_notice_logged_before_anything_was_saved": "THIS WORLD WAS SAVED WITH TRAVELER'S BACKPACK, WHICH IS NOT INSTALLED" in log2}
    for pattern in ("Missing registry|missing registry|Unknown registry|Registry Mismatch|registry mismatch|mismatch", "travelersbackpack", "confirm|Confirm", "Failed to start|Exception caught"):
        step2["log_lines_matching_" + pattern.split("|")[0].replace(" ", "_")] = [line.strip()[:220] for line in log2.splitlines() if re.search(pattern, line)][:8]
    if started:
        step2["chest_after"] = c.send("data get block 2 100 0 Items", r"has the following block data|Found no|The target block", 20).strip()[-200:]
        step2["block_after"] = c.send("execute if block 0 100 0 minecraft:air run say AIR_AT_0", r"AIR_AT_0|Test failed", 20).strip()[-120:]
        step2["items_in_world"] = c.send("execute as @e[type=minecraft:item] run data get entity @s Item", r"has the following entity data|No entity was found", 20).strip()[-300:]
        c.send("save-all flush", r"Saved", 60)
    code2 = c.stop()
    step2["exit_code"] = code2
    step2["world"] = snapshot(server)
    report["steps"]["2_open_without_the_mod"] = step2
    print(json.dumps(step2, indent=2), flush=True)

    # 3. back to the retired provider on the world that step 2 saved ------------------------------------------------------------------------------------------
    swap_mods(server, ["legacy_travelers"], core)
    c = Console(server, "3-reopen-with-travelers", logs)
    started3 = c.wait_for(r"Done \(", 240)
    step3: dict = {"reached_done": started3}
    if started3:
        step3["chest_after"] = c.send("data get block 2 100 0 Items", r"has the following block data|Found no|The target block", 20).strip()[-200:]
        step3["block_after"] = c.send(f"execute if block 0 100 0 {TRAVELER_BLOCK} run say TRAVELER_AT_0", r"TRAVELER_AT_0|Test failed", 20).strip()[-120:]
    step3["exit_code"] = c.stop()
    report["steps"]["3_reopen_with_the_mod_after_step_2"] = step3
    print(json.dumps(step3, indent=2), flush=True)

    # 4. control: the copy from after step 1, retired provider only ------------------------------------------------------------------------------------------
    shutil.rmtree(server / "world")
    shutil.copytree(backup, server / "world")
    c = Console(server, "4-control-copy-from-step-1", logs)
    started4 = c.wait_for(r"Done \(", 240)
    step4: dict = {"reached_done": started4}
    if started4:
        step4["chest_after"] = c.send("data get block 2 100 0 Items", r"has the following block data|Found no|The target block", 20).strip()[-200:]
        step4["block_after"] = c.send(f"execute if block 0 100 0 {TRAVELER_BLOCK} run say TRAVELER_AT_0", r"TRAVELER_AT_0|Test failed", 20).strip()[-120:]
    step4["exit_code"] = c.stop()
    report["steps"]["4_control_original_copy"] = step4
    print(json.dumps(step4, indent=2), flush=True)

    (args.work / "migration-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("report:", args.work / "migration-report.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
