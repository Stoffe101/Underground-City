#!/usr/bin/env python3
"""Reads a generated world's region files and finds furniture where no furniture belongs: the same piece stacked three or more high in one column.

No room stacks three sofas, beds, toilets, tables or doors on top of each other, but a palette character that Lost Cities draws the city's retaining walls with did exactly that
under every raised furnished building (furnished-cities.md, "How it is checked"): the registry check and the plan comparison could not see it, because every block was real and
every floor matched its plan. This reads the blocks a world really holds, so it sees what a player would.

    python scripts/world_furniture_audit.py <world folder>                      a summary by block
    python scripts/world_furniture_audit.py <world folder> --json out.json --fail   the full report; exit code 1 when anything is found

The world is only read (a disposable test world: never point it at a world somebody plays while the game has it open). Standard library only.
"""
from __future__ import annotations

import argparse
import io
import json
import math
import re
import struct
import sys
import zlib
from collections import Counter, defaultdict
from pathlib import Path

# a block whose name holds one of these is a piece of furniture (a run of windows, walls, hedges or shelves of books is not)
FURNITURE = re.compile(r"sofa|_bed$|toilet|chair|stool|table|cabinet|drawer|refrigerator|fridge|stove|kitchen_sink|flatscreen_tv|monitor|photocopier|bathtub|shower|floor_mat"
                       r"|_door$|campfire|air_conditioner|microwave|dishwasher|washing_machine|modern_oak_bookshelf|coffee_table")
DOOR = re.compile(r"_door$")


def is_furniture(name: str) -> bool:
    return bool(FURNITURE.search(name))


# ------------------------------------------------------------------------------------------------ NBT and region files

def read_nbt(data: bytes):
    """The root compound of an uncompressed NBT document."""
    buf = io.BytesIO(data)

    def read(tag):
        if tag == 1: return struct.unpack(">b", buf.read(1))[0]
        if tag == 2: return struct.unpack(">h", buf.read(2))[0]
        if tag == 3: return struct.unpack(">i", buf.read(4))[0]
        if tag == 4: return struct.unpack(">q", buf.read(8))[0]
        if tag == 5: return struct.unpack(">f", buf.read(4))[0]
        if tag == 6: return struct.unpack(">d", buf.read(8))[0]
        if tag == 7: return buf.read(read(3))
        if tag == 8: return buf.read(struct.unpack(">H", buf.read(2))[0]).decode("utf-8", "replace")
        if tag == 9:
            inner = buf.read(1)[0]
            return [read(inner) for _ in range(read(3))]
        if tag == 10:
            out = {}
            while True:
                inner = buf.read(1)[0]
                if inner == 0:
                    return out
                key = read(8)
                out[key] = read(inner)
        if tag == 11:
            n = read(3)
            return list(struct.unpack(f">{n}i", buf.read(4 * n)))
        if tag == 12:
            n = read(3)
            return list(struct.unpack(f">{n}q", buf.read(8 * n)))
        raise ValueError(f"unknown NBT tag {tag}")

    root = buf.read(1)[0]
    read(8)
    return read(root)


def chunks_of_region(path: Path):
    """(chunk x, chunk z, root compound) for every chunk stored in one region file."""
    data = path.read_bytes()
    if len(data) < 8192:
        return
    rx, rz = (int(v) for v in path.name.split(".")[1:3])
    for i in range(1024):
        offset = int.from_bytes(data[4 * i:4 * i + 3], "big")
        if offset == 0:
            continue
        start = offset * 4096
        length = struct.unpack(">i", data[start:start + 4])[0]
        kind = data[start + 4]
        raw = data[start + 5:start + 4 + length]
        if kind == 2:
            raw = zlib.decompress(raw)
        elif kind == 1:
            import gzip
            raw = gzip.decompress(raw)
        elif kind != 3:
            continue
        yield rx * 32 + (i % 32), rz * 32 + (i // 32), read_nbt(raw)


def section_blocks(section: dict, wanted) -> dict[int, str]:
    """Index (y*256 + z*16 + x) -> block name, for the blocks of one section whose name ``wanted`` accepts (sections without any are skipped cheaply)."""
    states = section.get("block_states") or {}
    palette = states.get("palette") or []
    names = [entry.get("Name", "minecraft:air") for entry in palette]
    keep = {i for i, name in enumerate(names) if wanted(name)}
    if not keep:
        return {}
    if len(palette) == 1 or not states.get("data"):
        return {index: names[0] for index in range(4096)} if 0 in keep else {}
    bits = max(4, math.ceil(math.log2(len(palette))))
    per = 64 // bits
    mask = (1 << bits) - 1
    out = {}
    for w, word in enumerate(states["data"]):
        word &= 0xFFFFFFFFFFFFFFFF
        base = w * per
        for k in range(per):
            index = base + k
            if index >= 4096:
                break
            entry = (word >> (k * bits)) & mask
            if entry in keep:
                out[index] = names[entry]
    return out


# ------------------------------------------------------------------------------------------------ the audit

def stacks(column: dict[int, str], min_run: int = 3) -> list[tuple[int, int, str]]:
    """Runs of at least ``min_run`` of the same piece one above the other in one column: (lowest y, length, block). Doors count in pieces (a door is two)."""
    out = []
    ys = sorted(column)
    i = 0
    while i < len(ys):
        j = i
        while j + 1 < len(ys) and ys[j + 1] == ys[j] + 1 and column[ys[j + 1]] == column[ys[i]]:
            j += 1
        length = j - i + 1
        needed = min_run + 1 if DOOR.search(column[ys[i]]) else min_run   # a door is two blocks; three door blocks is half a door on a door
        if length >= needed:
            out.append((ys[i], length, column[ys[i]]))
        i = j + 1
    return out


def audit(world: Path, min_run: int = 3, samples: int = 8) -> dict:
    region_dir = world / "region"
    if not region_dir.is_dir():
        raise SystemExit(f"{world} has no region folder")
    chunks = 0
    found = []
    pieces = Counter()
    for path in sorted(region_dir.glob("r.*.*.mca")):
        for cx, cz, root in chunks_of_region(path):
            if root.get("Status") not in (None, "minecraft:full", "full"):
                continue
            chunks += 1
            columns: dict[tuple[int, int], dict[int, str]] = defaultdict(dict)
            for section in root.get("sections") or []:
                sy = section.get("Y", 0)
                for index, name in section_blocks(section, is_furniture).items():
                    pieces[name] += 1
                    x, z, y = index & 15, (index >> 4) & 15, sy * 16 + (index >> 8)
                    columns[(x, z)][y] = name
            for (x, z), column in columns.items():
                for y, length, name in stacks(column, min_run):
                    found.append({"x": cx * 16 + x, "y": y, "z": cz * 16 + z, "height": length, "block": name})
    by_block = Counter(f["block"] for f in found)
    by_chunk = len({(f["x"] >> 4, f["z"] >> 4) for f in found})
    return {"schema_version": 1, "world": world.name, "chunks_read": chunks, "furniture_blocks": sum(pieces.values()), "min_run": min_run,
            "stacks": len(found), "chunks_with_stacks": by_chunk, "by_block": dict(by_block.most_common()),
            "samples": found[:samples], "all": found}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("world", type=Path)
    parser.add_argument("--min-run", type=int, default=3)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--fail", action="store_true", help="exit code 1 when any stack is found")
    args = parser.parse_args(argv)
    report = audit(args.world, args.min_run)
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{report['chunks_read']} chunks, {report['furniture_blocks']} furniture blocks; {report['stacks']} stacks of {args.min_run} or more in {report['chunks_with_stacks']} chunks")
    for block, n in report["by_block"].items():
        print(f"  {n:5d}  {block}")
    for s in report["samples"]:
        print(f"  e.g. {s['block']} x{s['height']} at {s['x']} {s['y']} {s['z']}")
    return 1 if args.fail and report["stacks"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
