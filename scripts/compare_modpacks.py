#!/usr/bin/env python3
"""Compare two existing CurseForge/Modrinth pack exports or installed mod folders.

This tool reads local metadata only. It does not download copyrighted pack data
or infer Forge compatibility from Minecraft version alone.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from audit_modpack import read_pack, audit_mod_directory


def load(path: Path) -> dict:
    if path.is_dir():
        return audit_mod_directory(path)
    return read_pack(path)


def identifier_set(info: dict) -> set[str]:
    if info["type"] == "curseforge":
        return {str(v["projectID"]) for v in info["referenced_files"]
                if v.get("projectID") is not None}
    if info["type"] == "modrinth":
        return {str(v["path"]) for v in info["referenced_files"]
                if isinstance(v.get("path"), str)}
    if info["type"] == "installed_jars":
        return {mod for record in info["jars"] for mod in record["mods"]}
    raise ValueError("Unsupported pack type")


def versions(info: dict) -> tuple[str | None, str]:
    if info["type"] == "installed_jars":
        return None, "undetermined"
    loader = info.get("loader")
    return info.get("minecraft"), (
        ",".join(str(x) for x in loader) if isinstance(loader, list)
        else ",".join(f"{key}={value}" for key, value in loader.items())
        if isinstance(loader, dict) else str(loader)
    )


def compare(a: dict, b: dict) -> dict:
    compatible_ids = a["type"] == b["type"]
    va, la = versions(a)
    vb, lb = versions(b)
    minecraft_mismatch = bool(va and vb and va != vb)
    result = {
        "left": {"type": a["type"], "name": a.get("name"), "minecraft": va, "loader": la},
        "right": {"type": b["type"], "name": b.get("name"), "minecraft": vb, "loader": lb},
        "same_identifier_system": compatible_ids,
        "minecraft_version_mismatch": minecraft_mismatch,
        "loader_versions": {"left": la, "right": lb},
        "cautions": [
            "Static manifests cannot prove successful boot, compatibility, or redistribution rights."
        ],
    }
    if not compatible_ids:
        result["cautions"].append(
            "Cross-format IDs are not equivalent: cannot compute meaningful overlap without"
            " resolving project identities via a trusted catalog."
        )
        result["comparison"] = None
        return result

    ids_a = identifier_set(a)
    ids_b = identifier_set(b)
    both = sorted(ids_a & ids_b)
    left_only = sorted(ids_a - ids_b)
    right_only = sorted(ids_b - ids_a)
    union = ids_a | ids_b
    result["comparison"] = {
        "left_items": len(ids_a),
        "right_items": len(ids_b),
        "shared_count": len(both),
        "shared_ids": both,
        "left_only": left_only,
        "right_only": right_only,
        "jaccard_similarity": round(len(both) / len(union), 4) if union else 1.0,
    }
    if a["type"] == "curseforge":
        a_versions = {str(x["projectID"]): x.get("fileID") for x in a["referenced_files"]
                      if x.get("projectID") is not None}
        b_versions = {str(x["projectID"]): x.get("fileID") for x in b["referenced_files"]
                      if x.get("projectID") is not None}
        result["comparison"]["shared_projects_with_different_file_ids"] = [
            {"project_id": project, "left_file": a_versions[project], "right_file": b_versions[project]}
            for project in both if a_versions[project] != b_versions[project]
        ]
    if minecraft_mismatch:
        result["cautions"].append(
            "Minecraft versions differ; do not mix the packs without component-by-component verification."
        )
    if la != lb:
        result["cautions"].append(
            "Loader labels or versions differ; check exact Forge/NeoForge/Fabric compatibility."
        )
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("left", type=Path, help="Pack .zip/.mrpack or installed mods directory")
    parser.add_argument("right", type=Path, help="Pack .zip/.mrpack or installed mods directory")
    parser.add_argument("--output", type=Path, help="Optional machine-readable JSON output")
    args = parser.parse_args(argv)
    try:
        result = compare(load(args.left), load(args.right))
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print("Could not compare pack manifests:", error, file=sys.stderr)
        return 2
    output = json.dumps(result, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(output, encoding="utf-8")
    print(output)
    return 1 if result["minecraft_version_mismatch"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
