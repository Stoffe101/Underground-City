#!/usr/bin/env python3
"""Offline modpack analyzer: no keys, downloads, or unpacked third-party assets.

Supports a CurseForge or Modrinth export ZIP and a local directory of actual
installed mod JARs. Outputs machine-readable JSON and a friendly summary.
"""
from __future__ import annotations

import argparse
import json
import sys
import tomllib
import zipfile
from pathlib import Path

INTEGRATED = {"minecraft", "forge", "java", "fml", "neoforge"}


def read_pack(path: Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        filenames = set(archive.namelist())
        if "manifest.json" in filenames:
            doc = json.loads(archive.read("manifest.json"))
            if not isinstance(doc, dict) or "minecraft" not in doc:
                raise ValueError("Malformed CurseForge manifest")
            mc = doc["minecraft"]
            return {
                "type": "curseforge",
                "name": doc.get("name", path.stem),
                "minecraft": mc.get("version"),
                "loader": [x.get("id") for x in mc.get("modLoaders", [])],
                "entries": len(doc.get("files", [])),
                "referenced_files": [
                    {"projectID": x.get("projectID"), "fileID": x.get("fileID")}
                    for x in doc.get("files", [])
                ],
                "overrides": doc.get("overrides", "overrides"),
            }
        if "modrinth.index.json" in filenames:
            doc = json.loads(archive.read("modrinth.index.json"))
            if not isinstance(doc, dict) or "dependencies" not in doc:
                raise ValueError("Malformed Modrinth manifest")
            deps = doc["dependencies"]
            return {
                "type": "modrinth",
                "name": doc.get("name", path.stem),
                "minecraft": deps.get("minecraft"),
                "loader": {k: v for k, v in deps.items() if k != "minecraft"},
                "entries": len(doc.get("files", [])),
                "referenced_files": [{"path": x.get("path"), "hashes": x.get("hashes", {})}
                                     for x in doc.get("files", [])],
            }
    raise ValueError("No CurseForge manifest.json or Modrinth modrinth.index.json in ZIP")


def read_jar(jar: Path) -> dict:
    record: dict = {"filename": jar.name, "mods": [], "depends": [], "issues": []}
    try:
        with zipfile.ZipFile(jar) as archive:
            filenames = set(archive.namelist())
            if "META-INF/mods.toml" in filenames:
                try:
                    doc = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8"))
                    record["mods"] = [str(x.get("modId")) for x in doc.get("mods", [])
                                      if x.get("modId")]
                    for mod_id, requirements in doc.get("dependencies", {}).items():
                        for requirement in requirements:
                            if requirement.get("mandatory") is True:
                                dep = requirement.get("modId")
                                if dep and dep not in INTEGRATED:
                                    record["depends"].append({"owner": mod_id, "id": str(dep)})
                except (ValueError, UnicodeDecodeError, TypeError) as ex:
                    record["issues"].append("Could not parse Forge metadata: " + str(ex))
            elif "fabric.mod.json" in filenames:
                try:
                    doc = json.loads(archive.read("fabric.mod.json"))
                    record["mods"] = [doc["id"]] if isinstance(doc.get("id"), str) else []
                    record["loader"] = "fabric"
                except (ValueError, KeyError, UnicodeDecodeError) as ex:
                    record["issues"].append("Could not parse Fabric metadata: " + str(ex))
            else:
                record["issues"].append("No recognized Forge/Fabric mod metadata (possible library or non-mod JAR)")
    except (OSError, zipfile.BadZipFile) as ex:
        record["issues"].append("Unreadable JAR: " + str(ex))
    return record


def audit_mod_directory(directory: Path) -> dict:
    if not directory.is_dir():
        raise ValueError("Mod path must be an existing directory")
    records = [read_jar(path) for path in sorted(directory.glob("*.jar"))]
    present: dict[str, list[str]] = {}
    for jar in records:
        for mod_id in jar["mods"]:
            present.setdefault(mod_id, []).append(jar["filename"])
    duplicates = {key: value for key, value in present.items() if len(value) > 1}
    # Some mods ship libraries inside other JARs (Jar-in-Jar); flag missing
    # prerequisites as suspected, not conclusive without a runtime check.
    missing = []
    for jar in records:
        for dep in jar["depends"]:
            if dep["id"] not in present:
                missing.append({"jar": jar["filename"], **dep})
    fabric = [r["filename"] for r in records if r.get("loader") == "fabric"]
    return {
        "type": "installed_jars",
        "files": len(records),
        "unique_mod_ids": len(present),
        "duplicate_mod_ids": duplicates,
        "possibly_missing_dependencies": missing,
        "fabric_mods": fabric,
        "jars": records,
        "notes": [
            "This is static metadata analysis, not confirmation of loader compatibility.",
            "Jar-in-Jar dependencies and mod IDs synthesized at runtime can cause false positives.",
            "Forge metadata version ranges are not resolved in this first version.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--pack", type=Path, help="Existing CurseForge/Modrinth pack export zip")
    group.add_argument("--mods", type=Path, help="Installed Minecraft mods directory")
    parser.add_argument("--output", type=Path, help="Optional JSON report destination")
    args = parser.parse_args(argv)
    try:
        report = read_pack(args.pack) if args.pack else audit_mod_directory(args.mods)
    except (ValueError, OSError, zipfile.BadZipFile, json.JSONDecodeError) as ex:
        print("Audit could not complete:", ex, file=sys.stderr)
        return 2
    encoded = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    if report["type"] == "installed_jars" and (
        report["duplicate_mod_ids"] or report["fabric_mods"]):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
