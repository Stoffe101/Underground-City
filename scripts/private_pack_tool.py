#!/usr/bin/env python3
"""Make, seal, verify, back up and restore a PRIVATE content pack (docs/legal/private-content.md, docs/technical/private-content-workflow.md).

A private pack is a folder outside this repository that holds a Minecraft data pack and/or resource pack (`data/`, `assets/`, `pack.mcmeta`)
plus a manifest, `uc-private-pack.json`, that says whose permission covers it. The game loads every pack under the private root
(`-PucPrivateContent=<folder>` or the UC_PRIVATE_CONTENT_DIR environment variable) as an always-on pack; nothing is copied into the repository,
the Core jar or a world.

    private_pack_tool.py init <pack-dir> --id ID --title TITLE --holder NAME --summary TEXT [--data] [--assets]
    private_pack_tool.py seal <pack-dir>       write uc-private-index.json: every file's size and SHA-256 (names and hashes, never content)
    private_pack_tool.py verify <pack-dir>     compare the folder with its index
    private_pack_tool.py backup <pack-dir> <zip>   a zip OUTSIDE any git work tree, with its SHA-256
    private_pack_tool.py restore <zip> <pack-dir>  unpack a backup (refuses to overwrite)
    private_pack_tool.py fixture <dir>         a SYNTHETIC pack made here (nothing of anybody's content) for tests and public CI

This tool never downloads anything and never reads the repository's tracked files; it works on the folder it is pointed at. It refuses a
destination inside a git work tree. Standard library only.
"""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

MANIFEST = "uc-private-pack.json"
INDEX = "uc-private-index.json"
SCHEMA = 1


def inside_work_tree(path: Path) -> bool:
    """True when the path is, or is below, a folder that holds a .git entry (a checkout)."""
    current = path.resolve()
    for folder in [current, *current.parents]:
        if (folder / ".git").exists():
            return True
    return False


def refuse_in_repo(path: Path) -> None:
    if inside_work_tree(path):
        raise SystemExit(f"refused: {path} is inside a git work tree; private content lives outside every checkout")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def files_of(pack: Path) -> list[Path]:
    return sorted(p for p in pack.rglob("*") if p.is_file() and p.name not in (INDEX,))


def init(pack: Path, pack_id: str, title: str, holder: str, summary: str, data: bool, assets: bool) -> None:
    refuse_in_repo(pack)
    if not pack_id or not all(c.islower() or c.isdigit() or c in "_-" for c in pack_id):
        raise SystemExit("the id must be lowercase letters, digits, - and _")
    if not holder.strip() or not summary.strip():
        raise SystemExit("a private pack names who gave the permission and what it covers")
    pack.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema_version": SCHEMA,
        "id": pack_id,
        "title": title,
        "provides": {"data": data or not assets, "assets": assets},
        "private_only": True,
        "permission": {"holder": holder, "summary": summary, "evidence_kept_by_owner": True},
    }
    (pack / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    meta = pack / "pack.mcmeta"
    if not meta.exists():
        meta.write_text(json.dumps({"pack": {"pack_format": 15, "description": title + " (private)"}}, indent=2) + "\n", encoding="utf-8")
    print(f"created {pack / MANIFEST}")


def seal(pack: Path) -> None:
    index = {"schema_version": SCHEMA, "files": {str(p.relative_to(pack)).replace("\\", "/"): {"size": p.stat().st_size, "sha256": sha256(p)} for p in files_of(pack)}}
    (pack / INDEX).write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"sealed {len(index['files'])} files (names and hashes only) into {pack / INDEX}")


def verify(pack: Path) -> int:
    index_path = pack / INDEX
    if not index_path.is_file():
        print("no index: run seal first")
        return 2
    index = json.loads(index_path.read_text(encoding="utf-8"))["files"]
    now = {str(p.relative_to(pack)).replace("\\", "/"): p for p in files_of(pack)}
    problems = [f"missing: {n}" for n in index if n not in now] + [f"new: {n}" for n in now if n not in index]
    problems += [f"changed: {n}" for n, p in now.items() if n in index and (p.stat().st_size != index[n]["size"] or sha256(p) != index[n]["sha256"])]
    for problem in problems:
        print(problem)
    print("verified: the folder is what was sealed" if not problems else f"{len(problems)} difference(s)")
    return 1 if problems else 0


def backup(pack: Path, target: Path) -> None:
    refuse_in_repo(target.parent)
    if target.exists():
        raise SystemExit(f"refused: {target} exists")
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for p in files_of(pack):
            archive.write(p, str(p.relative_to(pack)).replace("\\", "/"))
        if (pack / INDEX).is_file():
            archive.write(pack / INDEX, INDEX)
    print(f"backed up to {target}; sha256 {sha256(target)}")


def restore(source: Path, pack: Path) -> None:
    refuse_in_repo(pack)
    if pack.exists() and any(pack.iterdir()):
        raise SystemExit(f"refused: {pack} is not empty")
    pack.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as archive:
        for name in archive.namelist():
            target = (pack / name).resolve()
            if not target.is_relative_to(pack.resolve()):
                raise SystemExit(f"refused: {name} would leave the pack folder")
        archive.extractall(pack)
    print(f"restored into {pack}")


def fixture(root: Path) -> None:
    """A synthetic pack: invented content in a namespace of its own, enough to prove the loader and to give public CI something to load."""
    refuse_in_repo(root)
    pack = root / "uc_private_fixture"
    init(pack, "uc_private_fixture", "Synthetic fixture", "this repository (synthetic, not anybody's content)", "an invented pack that exists only to test the loader", True, False)
    data = pack / "data" / "underground_city" / "building_security"
    data.mkdir(parents=True, exist_ok=True)
    (data / "zz_private_fixture.json").write_text(json.dumps({
        "schema_version": 1,
        "classes": {"private_fixture_class": {"lock_profile": "deadbolt", "locked_exterior": True, "stash_policy": "", "stash_chance": 0.0, "label": "fixture"}},
        "rules": []
    }, indent=2) + "\n", encoding="utf-8")
    seal(pack)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    command, rest = argv[0], argv[1:]
    if command == "init":
        import argparse
        parser = argparse.ArgumentParser(prog="init")
        parser.add_argument("pack"); parser.add_argument("--id", required=True); parser.add_argument("--title", required=True)
        parser.add_argument("--holder", required=True); parser.add_argument("--summary", required=True)
        parser.add_argument("--data", action="store_true"); parser.add_argument("--assets", action="store_true")
        a = parser.parse_args(rest)
        init(Path(a.pack), a.id, a.title, a.holder, a.summary, a.data, a.assets)
    elif command == "seal":
        seal(Path(rest[0]))
    elif command == "verify":
        return verify(Path(rest[0]))
    elif command == "backup":
        backup(Path(rest[0]), Path(rest[1]))
    elif command == "restore":
        restore(Path(rest[0]), Path(rest[1]))
    elif command == "fixture":
        fixture(Path(rest[0]))
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
