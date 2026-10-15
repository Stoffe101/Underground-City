#!/usr/bin/env python3
"""Resolve the exact third-party releases of the development pack from Modrinth.

Maintainer tool. It reads ``content/modpacks/dev-pack.pins.json`` (which version of
which project we want) and writes ``content/modpacks/dev-pack.json`` with the file
name, size, SHA-1/SHA-512, licence and declared dependencies reported by the
Modrinth API. Only METADATA is written: no third-party binary is downloaded or
committed. ``scripts/fetch_dev_pack.py`` later downloads and verifies the files
into an untracked directory for private play.

Usage:
    python scripts/resolve_dev_pack.py            # rewrite dev-pack.json
    python scripts/resolve_dev_pack.py --check    # fail if the file is stale
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PINS = ROOT / "content/modpacks/dev-pack.pins.json"
OUTPUT = ROOT / "content/modpacks/dev-pack.json"
API = "https://api.modrinth.com/v2"
USER_AGENT = "underground-city-dev/0.1 (private development pack resolver)"


def get(path: str) -> object:
    request = urllib.request.Request(API + path, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def resolve_entry(pin: dict) -> dict:
    version = get(f"/version/{pin['version_id']}")
    project = get(f"/project/{version['project_id']}")
    if project["slug"] != pin["slug"]:
        raise SystemExit(f"{pin['slug']}: version {pin['version_id']} belongs to {project['slug']}")
    if "1.20.1" not in version["game_versions"] or "forge" not in version["loaders"]:
        raise SystemExit(f"{pin['slug']}: version {version['version_number']} is not Forge 1.20.1")
    primary = [f for f in version["files"] if f.get("primary")] or version["files"][:1]
    if not primary:
        raise SystemExit(f"{pin['slug']}: no downloadable file")
    file = primary[0]
    return {
        "id": pin["id"],
        "name": project["title"],
        "slug": project["slug"],
        "project_id": project["id"],
        "version_id": version["id"],
        "version_number": version["version_number"],
        "published": version["date_published"][:10],
        "filename": file["filename"],
        "size": file["size"],
        "sha1": file["hashes"]["sha1"],
        "sha512": file["hashes"]["sha512"],
        "license": project["license"]["id"],
        "mod_id": pin["mod_id"],
        "role": pin["role"],
        "sets": pin["sets"],
        "requires": pin.get("requires", []),
        "side": {"client": project["client_side"], "server": project["server_side"]},
        "source_url": f"https://modrinth.com/mod/{project['slug']}/version/{version['id']}",
        "distribution": pin.get("distribution", "download from the author's page; never commit the binary"),
        "notes": pin.get("notes", ""),
    }


def build() -> dict:
    pins = json.loads(PINS.read_text(encoding="utf-8"))
    entries = [resolve_entry(pin) for pin in pins["mods"]]
    ids = [entry["id"] for entry in entries]
    if len(set(ids)) != len(ids):
        raise SystemExit("Duplicate dev-pack IDs")
    for entry in entries:
        for dependency in entry["requires"]:
            if dependency not in ids:
                raise SystemExit(f"{entry['id']} requires unknown dev-pack entry {dependency}")
    return {
        "schema_version": 1,
        "minecraft": pins["minecraft"],
        "forge": pins["forge"],
        "policy": pins["policy"],
        "mods": entries,
        "external": pins.get("external", []),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if dev-pack.json is not current")
    args = parser.parse_args()
    try:
        document = build()
    except (urllib.error.URLError, OSError) as error:
        print(f"Cannot reach Modrinth: {error}", file=sys.stderr)
        return 2
    text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(encoding="utf-8") != text:
            print("content/modpacks/dev-pack.json is stale; run scripts/resolve_dev_pack.py", file=sys.stderr)
            return 1
        print("dev-pack.json matches Modrinth metadata")
        return 0
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"Wrote {OUTPUT.relative_to(ROOT)} with {len(document['mods'])} pinned releases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
