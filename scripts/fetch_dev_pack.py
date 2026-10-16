#!/usr/bin/env python3
"""Download the pinned development-pack jars for PRIVATE play into an untracked folder.

Reads content/modpacks/dev-pack.json (written by resolve_dev_pack.py), downloads each
selected release from Modrinth's CDN and refuses any file whose size or SHA-512 differs
from the manifest. Nothing is committed or uploaded by this script. The owner remains
responsible for the licence terms of each author (most are All Rights Reserved: private
use only, no redistribution).

Usage:
    python scripts/fetch_dev_pack.py --sets world firearms vehicles --output build/dev-pack/mods
    python scripts/fetch_dev_pack.py --list
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "content/modpacks/dev-pack.json"
USER_AGENT = "underground-city-dev/0.1 (private development pack fetch)"


def load() -> dict:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit("Unsupported dev-pack manifest schema")
    return data


def selected(data: dict, sets: list[str]) -> list[dict]:
    by_id = {mod["id"]: mod for mod in data["mods"]}
    chosen: dict[str, dict] = {}

    def add(mod_id: str) -> None:
        mod = by_id[mod_id]
        for dependency in mod["requires"]:
            add(dependency)
        chosen[mod_id] = mod

    for mod in data["mods"]:
        if set(mod["sets"]) & set(sets):
            add(mod["id"])
    return list(chosen.values())


def download(mod: dict, destination: Path) -> Path:
    target = destination / mod["filename"].replace(" ", "_")
    if target.exists() and hashlib.sha512(target.read_bytes()).hexdigest() == mod["sha512"]:
        return target
    url = f"https://cdn.modrinth.com/data/{mod['project_id']}/versions/{mod['version_id']}/{mod['filename']}"
    request = urllib.request.Request(urllib.request.quote(url, safe=":/"), headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response:
        payload = response.read()
    if len(payload) != mod["size"]:
        raise SystemExit(f"{mod['id']}: size {len(payload)} differs from pinned {mod['size']}")
    if hashlib.sha512(payload).hexdigest() != mod["sha512"]:
        raise SystemExit(f"{mod['id']}: SHA-512 mismatch; refusing to keep the file")
    target.write_bytes(payload)
    return target


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sets", nargs="*", default=[], help="world firearms vehicles")
    parser.add_argument("--output", type=Path, default=ROOT / "build/dev-pack/mods")
    parser.add_argument("--list", action="store_true", help="print the pins and exit")
    args = parser.parse_args()
    data = load()
    if args.list or not args.sets:
        for mod in data["mods"]:
            print(f"{mod['id']:26} {mod['version_number']:18} sets={','.join(mod['sets']):10} {mod['license']}")
        for mod in data["external"]:
            print(f"{mod['id']:26} {mod['version_number']:18} sets={','.join(mod['sets']):10} {mod['license']} (obtain from {mod['source_url']})")
        return 0
    chosen = selected(data, args.sets)
    args.output.mkdir(parents=True, exist_ok=True)
    for mod in chosen:
        path = download(mod, args.output)
        print(f"verified {path.name} ({mod['license']})")
    print("External jars (Q Shop) must be added from the author's release; see docs/technical/economy-integration.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
