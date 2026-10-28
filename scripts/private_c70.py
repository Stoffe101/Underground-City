#!/usr/bin/env python3
"""Stage the owner's private Lost Cities buildings (the C70 pack) into a private runtime folder in one command, and check a private folder before a launch.

Private content never enters this repository (docs/legal/private-content.md, D-116). This tool reads an original pack the owner keeps outside every checkout and
writes outside every checkout too. It runs the porter, the wiring and the pack tool in the tested order of docs/technical/private-content-workflow.md, so that
getting the owner's buildings into a game is one command and starting a game with them is another:

    private_c70.py stage --source <original folder or .zip> --out <runtime folder> [--registry REG.json] [--selection SEL.json] [--holder NAME --summary TEXT]
    private_c70.py select <ported pack> <selection.json>     the automatic selection, written for the owner to read, change and pass back with --selection
    private_c70.py check <runtime folder> [--sets furnished,scarcity,furniture] [--json]

stage:

1. finds the Lost Cities data in the source (a folder, or a zip whose member paths are checked before anything is unpacked) and copies it, unchanged, to
   ``<runtime>-work/original`` with a manifest: the original's own, or one made from ``--holder`` and ``--summary`` (who gave the permission is the owner's
   statement and is never assumed);
2. uses ``--registry``, or the registry it dumped last time for the same pinned mods, or dumps one from a real game (a disposable dedicated server through
   ``run_city_probe.py --registry``, a few minutes; nothing private is loaded in it);
3. ports it (``port_lostcities_pack.port``), keeping the mods of the ``furniture`` set; a port that is not loadable stops here and nothing is installed;
4. chooses the buildings: ``--selection``, or the automatic selection (``auto_select``), which is written to ``<runtime>-work/selection-auto.json``;
5. wires them (``private_wiring.wire``), seals the result and writes ``uc-private-stage.json``: the porter's and the wiring's versions and the sets it needs;
6. installs it as ``<runtime>/c70-port``, moving the pack it replaces to ``<runtime>-work/previous`` (the last three are kept, the rest removed).

check is what the Windows launchers run before a game starts with private content. The folder holds packs (it is not a pack itself); every pack's manifest
is valid the way the game reads it (``PrivateContent``); no pack is an unported 1.18.2 original; a staged pack was made by a porter and a wiring this checkout
still accepts; and the sets the launch loads include the ones the pack needs. It prints counts and reasons, never content.

Standard library only. The porter and the wiring are imported only by stage and select, so check runs without them.
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
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import private_pack_tool as packs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DEV_PACK = ROOT / "content/modpacks/dev-pack.json"
LADDER = ROOT / "rpg-core/src/main/resources/data/underground_city/properties/ladder.json"
STAGE = "uc-private-stage.json"
PACK_NAME = "c70-port"
STAGE_SCHEMA = 1
# a pack staged by an older porter or wiring than these is refused at launch: stage it again (the numbers rise when a port or a plan changes in a way an old one lacks)
MIN_PORTER = 12
MIN_WIRING = 12
# what a pack of Lost Cities buildings needs loaded besides itself: the furnished cities (its buildings join theirs) and the furniture its palettes name
NEEDS_SETS = ["furnished", "furniture"]
RECOMMENDED_SETS = ["furnished", "scarcity", "furniture"]
PACK_FORMAT_1201 = 15
PACK_ID = re.compile(r"[a-z0-9_-]{1,48}")
MAX_MEMBERS, MAX_BYTES = 200_000, 2 << 30


def fail(message: str) -> None:
    raise SystemExit(message)


def remove_tree(path: Path) -> None:
    """Remove a folder this tool made, also when its files are read-only (a copy of a read-only original, and Windows)."""
    def writable(function, name, _):
        os.chmod(name, 0o700)
        function(name)
    if path.exists():
        if sys.version_info >= (3, 12):
            shutil.rmtree(path, onexc=writable)
        else:
            shutil.rmtree(path, onerror=writable)


def copy_tree(source: Path, target: Path) -> None:
    """Copy a folder's files without their permissions, so that the copy can be replaced later even when the original is read-only."""
    for folder, _, names in os.walk(source):
        here = target / Path(folder).relative_to(source)
        here.mkdir(parents=True, exist_ok=True)
        for name in names:
            shutil.copyfile(Path(folder) / name, here / name)


# ---------------------------------------------------------------------------------------------------------------------------------- check


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def checkout_problem(folder: Path) -> str | None:
    """As PrivateContent.checkoutProblem: a folder inside a git work tree is refused unless it is that tree's ignored private-content folder."""
    folder = folder.resolve()
    for up in [folder, *folder.parents]:
        if (up / ".git").exists():
            relative = folder.relative_to(up)
            return None if relative.parts and relative.parts[0] == "private-content" else "it is inside a git work tree; private content lives outside every checkout"
    return None


def manifest_problem(pack: Path) -> str | None:
    """Why the game would refuse this pack folder (PrivateContent.read), or None."""
    manifest = read_json(pack / packs.MANIFEST)
    if not isinstance(manifest, dict):
        return "the manifest cannot be read as JSON"
    if manifest.get("schema_version") != 1:
        return "unsupported manifest schema"
    if not PACK_ID.fullmatch(str(manifest.get("id", ""))):
        return "the id must be lowercase letters, digits, - and _"
    if manifest.get("private_only") is not True:
        return "the manifest does not say private_only: true"
    permission = manifest.get("permission") if isinstance(manifest.get("permission"), dict) else {}
    if not str(permission.get("holder", "")).strip() or not str(permission.get("summary", "")).strip():
        return "the manifest does not name who gave the permission and what it covers"
    if not (pack / "pack.mcmeta").is_file():
        return "there is no pack.mcmeta"
    return None


def check(root: Path, sets: list[str]) -> dict:
    """What a launch with this private folder would get: {"ok", "errors", "warnings", "packs"}. Reasons and counts only."""
    errors: list[str] = []
    warnings: list[str] = []
    found: list[dict] = []
    result = {"ok": False, "root": str(root), "errors": errors, "warnings": warnings, "packs": found}
    if not root.is_dir():
        errors.append(f"the private content folder {root} does not exist (stage it first: uc-private-stage.ps1 or private_c70.py stage)")
        return result
    problem = checkout_problem(root)
    if problem:
        errors.append(f"{root}: {problem}")
        return result
    if (root / packs.MANIFEST).is_file():
        errors.append(f"{root} is a pack, not the folder that holds packs: point at {root.parent}")
        return result
    for pack in sorted(p for p in root.iterdir() if p.is_dir() and (p / packs.MANIFEST).is_file()):
        problem = manifest_problem(pack)
        if problem:
            errors.append(f"{pack.name}: {problem}")
            continue
        info = {"folder": pack.name, "id": read_json(pack / packs.MANIFEST)["id"], "staged": False}
        meta = read_json(pack / "pack.mcmeta") or {}
        pack_format = (meta.get("pack") or {}).get("pack_format") if isinstance(meta, dict) else None
        if isinstance(pack_format, int) and pack_format < PACK_FORMAT_1201:
            errors.append(f"{pack.name}: pack format {pack_format} is an unported original (Minecraft 1.18.2); stage it with uc-private-stage.ps1, which ports it")
            continue
        stage = read_json(pack / STAGE)
        lost_cities = any((pack / "data").glob("*/lostcities/buildings"))
        ported = read_json(pack / "uc-port-report.json")
        if lost_cities and not isinstance(ported, dict):
            # the porter writes its report into every port; Lost Cities buildings without one are an original that never went through it
            errors.append(f"{pack.name}: holds Lost Cities buildings that were never ported (no uc-port-report.json); stage the original with uc-private-stage.ps1 instead")
            continue
        if isinstance(ported, dict) and ported.get("loadable") is not True:
            errors.append(f"{pack.name}: its port report says it is not loadable; stage it again")
            continue
        if isinstance(stage, dict):
            info["staged"] = True
            info["porter_version"], info["wiring_version"] = stage.get("porter_version", 0), stage.get("wiring_version", 0)
            if info["porter_version"] < MIN_PORTER or info["wiring_version"] < MIN_WIRING:
                errors.append(f"{pack.name}: staged by porter {info['porter_version']} and wiring {info['wiring_version']}; this checkout needs at least {MIN_PORTER} and {MIN_WIRING}. Stage it again")
            needs = [s for s in stage.get("needs_sets", NEEDS_SETS)]
        else:
            needs = NEEDS_SETS if lost_cities else []
            if lost_cities:
                warnings.append(f"{pack.name}: has no {STAGE} (made by hand, not by stage): its porter and wiring versions cannot be checked")
        missing = [s for s in needs if s not in sets]
        if missing:
            errors.append(f"{pack.name} needs the set(s) {', '.join(missing)} loaded too (pass -C70, or add them to -Sets)")
        index = read_json(pack / packs.INDEX)
        if isinstance(index, dict) and isinstance(index.get("files"), dict):
            now = {str(p.relative_to(pack)).replace("\\", "/"): p.stat().st_size for p in packs.files_of(pack)}
            sealed = {name: entry.get("size") for name, entry in index["files"].items()}
            changed = sum(1 for name in set(now) | set(sealed) if now.get(name) != sealed.get(name))
            if changed:
                warnings.append(f"{pack.name}: {changed} file(s) differ from its seal (private_pack_tool.py verify names them)")
        info["needs_sets"] = needs
        info["files"] = len(packs.files_of(pack))
        found.append(info)
    if not found and not errors:
        errors.append(f"{root} holds no private pack (a folder with {packs.MANIFEST})")
    result["ok"] = not errors
    return result


# ---------------------------------------------------------------------------------------------------------------------------------- selection

# What a building is for, from the words of its id (first match wins): use, security class, title (None: the matched word), counter outlet, listing category.
KINDS: list[tuple[str, str, str, str | None, str | None, str | None]] = [
    (r"hospital|clinic|community|school|library|church|police|fire_?station|town_?hall|museum", "office", "public", None, None, None),
    (r"gas_?station|petrol|fuel", "shop", "storefront", "Petrol station", "shop:general", None),
    (r"car_?service|garage|mechanic", "workshop", "workshop", "Garage", None, "commercial_workshop"),
    (r"warehouse|depot", "warehouse", "warehouse", "Warehouse", None, "warehouse"),
    (r"factory|industr|workshop", "workshop", "workshop", "Workshop", None, "commercial_workshop"),
    (r"bakery", "shop", "storefront", "Bakery", "shop:bakery", None),
    (r"restaurant|cafe|diner|bistro|tavern|pub(?!lic)", "shop", "storefront", None, "shop:pub", None),
    (r"store|shop|market|grocer", "shop", "storefront", "Shop", "shop:store", None),
    (r"gallery", "shop", "storefront", "Gallery", None, None),
    (r"club", "club", "club", "Club", None, None),
    (r"bank", "office", "office", "Bank", None, None),
    (r"office", "office", "office", "Office", None, None),
    (r"tower|condo", "flats", "flat_tower", "Apartments", None, "comfortable_apartment"),
    (r"hotel|apartment|flat|tenement", "flats", "flat_block", "Apartments", None, "small_apartment"),
    (r"farmhouse|residential|house|home|cottage|villa|cabin|bungalow", "house", "house", "House", None, "small_house"),
]
# where each use goes (D-193: Valewood is residential, Ironhaven industrial with flats, Blackwater offices and towers; shops and public buildings in every city)
CITIES = {"house": ["valewood"], "flats": ["ironhaven", "blackwater"], "workshop": ["ironhaven"], "warehouse": ["ironhaven"], "office": ["blackwater"],
          "public": ["ironhaven", "blackwater", "valewood"], "shop": ["valewood", "blackwater", "ironhaven"], "club": ["ironhaven", "blackwater"]}
# how often a building of a use is picked in each ring of its city (centre, middle, edge), split over the rotations of one family; these are the weights the
# owner's first C70-first selection used (D-193), and the owner changes them in the written selection
RINGS = {"house": [1, 2.25, 2], "flats": [4, 2, 2], "workshop": [2, 3, 3], "warehouse": [0, 2, 3], "office": [1, 0.5, 0.25], "public": [0.5, 0, 0],
         "shop": [2, 1, 0], "club": [0, 1, 0]}
MULTI_WEIGHT = {"house": 3, "flats": 3, "workshop": 2, "warehouse": 2}
# how often a city plans no multi-chunk building where one could stand (the cloud's selection, D-198)
MULTI_NONE = {"valewood": 4, "ironhaven": 3, "blackwater": 2}
# the public buildings a C70-first selection leaves out once private buildings of the same use stand in that city, and the public functional buildings it keeps at a lower weight
PUBLIC_GENERIC = {"underground_city:vw_house_a": ("valewood", "house"), "underground_city:vw_house_b": ("valewood", "house"),
                  "underground_city:vw_cottage": ("valewood", "house"), "underground_city:bw_office": ("blackwater", "office")}
PUBLIC_KEPT = ["underground_city:ih_flats", "underground_city:ih_workshop", "underground_city:ih_warehouse", "underground_city:ih_pub",
               "underground_city:bw_apartments", "underground_city:bw_lofts", "underground_city:bw_club"]
ROTATION = re.compile(r"_(90|180|270)$")


def kind_of(rid: str):
    name = rid.split(":", 1)[1].lower()
    for pattern, use, cls, title, counter, listing in KINDS:
        m = re.search(pattern, name)
        if m:
            return {"use": "public" if cls == "public" else use, "wired_use": use, "class": cls, "title": title or m.group(0).replace("_", " ").title(),
                    "counter": counter, "listing": listing}
    return None


def listing_terms() -> dict[str, dict]:
    """A category's terms from the public ladder (its first city listing), so a private building is let on the same terms as a public one of its category."""
    ladder = json.loads(LADDER.read_text(encoding="utf-8"))
    out: dict[str, dict] = {}
    for entry in ladder.get("city_listings", []):
        out.setdefault(entry["category"], {k: v for k, v in entry.items() if k not in ("building", "area")})
    return out


def auto_select(port: Path) -> tuple[dict, dict]:
    """A C70-first selection for a ported pack, from what its buildings are called and what their plans hold, and a report of what was left out and why.
    Nothing uncertain is guessed: a building whose id names no known use, a house without a bed or a street door, flats without a unit, a building whose parts use a
    character no palette defines, are all left out and listed."""
    import private_wiring as wiring  # noqa: E402
    pack = wiring.Pack(port)
    report_file = read_json(port / "uc-port-report.json") or {}
    broken = set((report_file.get("buildings_with_undefined_characters") or {}).keys())
    terms = listing_terms()
    left_out: dict[str, str] = {}
    namespaces = sorted(p.parts[-3] for p in port.glob("data/*/lostcities/buildings") if p.parts[-3] not in ("lostcities", "underground_city"))
    if not namespaces:
        fail(f"{port} holds no Lost Cities buildings")
    multis = []
    parts_of_multis: set[str] = set()
    for ns in namespaces:
        for file in sorted((port / "data" / ns / "lostcities" / "multibuildings").glob("*.json")):
            mid = f"{ns}:{file.stem}"
            grid = wiring.multi_parts(pack, mid)
            parts_of_multis.update(part for part, _, _ in grid)
            multis.append((mid, grid))
    singles: dict[str, dict[str, list[str]]] = {}
    for ns in namespaces:
        for file in sorted((port / "data" / ns / "lostcities" / "buildings").glob("*.json")):
            bid = f"{ns}:{file.stem}"
            if bid in parts_of_multis:
                continue
            kind = kind_of(bid)
            if kind is None:
                left_out[bid] = "its id names no use this selection knows"
                continue
            if bid in broken:
                left_out[bid] = "the port found a character no palette defines (Lost Cities would leave its lot empty)"
                continue
            family = ROTATION.sub("", bid)
            singles.setdefault(kind["use"], {}).setdefault(family, []).append(bid)
    buildings = []
    for use, families in sorted(singles.items()):
        cities = CITIES[use]
        names = sorted(families)
        for i, family in enumerate(names):
            ids = sorted(families[family])
            # enough families: one city each, in turn; fewer families than cities: the family's rotations are shared out so every city gets one
            by_city: dict[str, list[str]] = {}
            if len(names) >= len(cities):
                by_city[cities[i % len(cities)]] = ids
            else:
                for r, bid in enumerate(ids):
                    by_city.setdefault(cities[r % len(cities)], []).append(bid)
            for city, chosen in by_city.items():
                kept = []
                for bid in chosen:
                    kind = kind_of(bid)
                    doc, missing = wiring.plan(pack, bid, city, kind["wired_use"], kind["title"])
                    spots = [s["kind"] for s in doc["spots"]]
                    street_door = bool(spots) and spots[0] == "door"
                    if kind["wired_use"] == "house" and ("bed" not in spots or not street_door or not doc.get("units")):
                        left_out[bid] = "a house needs a bed, a street door and a roof over its rooms; its plan has " + ", ".join(missing or ["not all of them"])
                        continue
                    if kind["wired_use"] == "flats" and not doc.get("units"):
                        left_out[bid] = "flats need a storey with a bed under a roof; its plan has none"
                        continue
                    kept.append((bid, kind, "counter" in spots))
                for zone, weight in enumerate(RINGS[use]):
                    if weight <= 0 or not kept:
                        continue
                    for bid, kind, has_counter in kept:
                        entry = {"id": bid, "city": city, "zone": zone, "weight": round(weight / len(kept), 3), "use": kind["wired_use"], "class": kind["class"], "title": kind["title"]}
                        if kind["listing"] and kind["listing"] in terms:
                            entry["listing"] = terms[kind["listing"]]
                        if kind["counter"] and has_counter:
                            entry["counter"] = {"outlet": kind["counter"], "title": "The " + kind["title"]}
                        buildings.append(entry)
    multi_entries = []
    turn: dict[str, int] = {}
    for mid, grid in multis:
        kind = kind_of(mid)
        if kind is None:
            left_out[mid] = "its id names no use this selection knows"
            continue
        bad = [part for part, _, _ in grid if part in broken or pack.doc("buildings", part) is None]
        if bad:
            left_out[mid] = f"{len(bad)} of its parts are missing or use a character no palette defines"
            continue
        cities = CITIES[kind["use"]]
        city = cities[turn.get(kind["use"], 0) % len(cities)]
        turn[kind["use"]] = turn.get(kind["use"], 0) + 1
        entry = {"id": mid, "city": city, "use": kind["wired_use"], "weight": MULTI_WEIGHT.get(kind["use"], 1), "class": kind["class"], "title": kind["title"]}
        if kind["listing"] and kind["listing"] in terms:
            entry["listing"] = terms[kind["listing"]]
        multi_entries.append(entry)
    if multi_entries:
        for city in sorted({m["city"] for m in multi_entries}):
            multi_entries.append({"id": f"{namespaces[0]}:none", "city": city, "weight": MULTI_NONE.get(city, 2)})
    chosen_uses = {(b["city"], b["use"]) for b in buildings}
    public_weights = {pid: 0 for pid, (city, use) in PUBLIC_GENERIC.items() if (city, use) in chosen_uses and all(
        any(b["city"] == city and b["use"] == use and b["zone"] == z for b in buildings) for z in range(3))}
    public_weights.update({pid: 1 for pid in PUBLIC_KEPT})
    selection = {"schema_version": 1, "note": "PRIVATE: keep this file beside the private pack, never in a repository. Written by private_c70.py select (C70 first, D-193); change it and stage with --selection.",
                 "buildings": buildings, "multi_buildings": multi_entries, "public_weights": public_weights, "multi_factor": 1.0}
    summary = {"buildings": len({b["id"] for b in buildings}), "multi_buildings": sum(1 for m in multi_entries if not m["id"].endswith(":none")), "left_out": len(left_out),
               "by_city": {}}
    for b in buildings:
        summary["by_city"].setdefault(b["city"], {}).setdefault(b["use"], set()).add(b["id"])
    summary["by_city"] = {c: {u: len(ids) for u, ids in uses.items()} for c, uses in sorted(summary["by_city"].items())}
    return selection, {"summary": summary, "left_out": dict(sorted(left_out.items()))}


# ---------------------------------------------------------------------------------------------------------------------------------- stage


def safe_extract(archive: Path, target: Path) -> None:
    """Unpack a zip after checking every member: no absolute path, no drive, no '..', no link, a bounded count and size."""
    with zipfile.ZipFile(archive) as z:
        infos = z.infolist()
        if len(infos) > MAX_MEMBERS:
            fail(f"{archive}: {len(infos)} members is more than a pack of buildings has; refused")
        if sum(i.file_size for i in infos) > MAX_BYTES:
            fail(f"{archive}: more than 2 GiB unpacked; refused")
        for info in infos:
            name = info.filename.replace("\\", "/")
            if name.startswith("/") or re.match(r"^[A-Za-z]:", name) or ".." in name.split("/"):
                fail(f"{archive}: the member {name!r} would leave the folder; refused")
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                fail(f"{archive}: the member {name!r} is a link; refused")
        target.mkdir(parents=True)
        z.extractall(target)


def find_pack_root(folder: Path) -> Path:
    """The folder that holds data/<ns>/lostcities/buildings, at most four levels down (a server pack's zip puts it under a world or a kubejs folder)."""
    found = sorted({p.parents[3] for p in folder.glob("**/data/*/lostcities/buildings") if len(p.relative_to(folder).parts) <= 8})
    found = [p for p in found if not any(q != p and q in p.parents for q in found)]
    if not found:
        fail(f"{folder}: no Lost Cities buildings (data/<namespace>/lostcities/buildings) were found in it")
    if len(found) > 1:
        fail(f"{folder}: Lost Cities buildings in {len(found)} places ({', '.join(str(p.relative_to(folder)) or '.' for p in found)}); pass the one to use as --source")
    return found[0]


def prepare_original(source: Path, work: Path, holder: str, summary: str) -> Path:
    """The original, unchanged, as a private pack the porter accepts, in <work>/original. The source itself is only read."""
    original = work / "original"
    # the manifest of the last staging, for a source that has none of its own (a raw download): the owner's statement is asked for once, not at every staging
    previous_manifest = read_json(original / packs.MANIFEST)
    remove_tree(original)
    unpacked = None
    if source.is_file():
        if not zipfile.is_zipfile(source):
            fail(f"{source} is neither a folder nor a zip")
        unpacked = work / "unpacked"
        remove_tree(unpacked)
        safe_extract(source, unpacked)
        folder = unpacked
    elif source.is_dir():
        folder = source
    else:
        fail(f"{source} does not exist")
    root = find_pack_root(folder)
    original.mkdir(parents=True)
    # only the Lost Cities data: a server pack's other data (loot tables, recipes, spawn rules) is not this game's and is never copied
    for lost_cities in sorted((root / "data").glob("*/lostcities")):
        copy_tree(lost_cities, original / "data" / lost_cities.parent.name / "lostcities")
    if (root / "pack.mcmeta").is_file():
        shutil.copyfile(root / "pack.mcmeta", original / "pack.mcmeta")
    else:
        (original / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": 9, "description": "original (private)"}}) + "\n", encoding="utf-8")
    manifest = read_json(root / packs.MANIFEST)
    if isinstance(manifest, dict) and manifest.get("private_only") is True and manifest_problem(root) is None:
        shutil.copyfile(root / packs.MANIFEST, original / packs.MANIFEST)
    elif holder.strip() and summary.strip():
        packs.init(original, "c70-original", "C70 original buildings", holder, summary, True, False)
    elif isinstance(previous_manifest, dict) and previous_manifest.get("private_only") is True:
        (original / packs.MANIFEST).write_text(json.dumps(previous_manifest, indent=2) + "\n", encoding="utf-8")
    else:
        fail(f"{source} has no private pack manifest. Say who gave the permission and what it covers, once: --holder \"<name>\" --summary \"<what it allows>\"")
    if unpacked is not None:
        remove_tree(unpacked)
    return original


def furniture_namespaces(manifest: dict) -> list[str]:
    return sorted({m["mod_id"] for m in manifest["mods"] if "furniture" in m["sets"]})


def registry_key(manifest: dict) -> str:
    """What a registry dump depends on: the game, the loader and every pinned mod of the sets it is dumped with."""
    lines = [f"minecraft {manifest['minecraft']}", f"forge {manifest['forge']}"]
    lines += sorted(f"{m['mod_id']} {m['version_number']} {m['sha512'][:16]}" for m in manifest["mods"] if set(m["sets"]) & {"world", "furnished", "furniture"})
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()[:16]


def dump_registry(work: Path, namespaces: list[str], key: str) -> Path:
    """Boot a disposable dedicated server with the furniture set (no private content) and write its block registry; reused while the key is the same."""
    registry, key_file = work / "registry.json", work / "registry.key"
    if registry.is_file() and key_file.is_file() and key_file.read_text(encoding="utf-8").strip() == key:
        print(f"registry: reusing {registry} (same pinned mods)")
        return registry
    name = "stage" + time.strftime("%Y%m%d%H%M%S")
    command = [sys.executable, str(ROOT / "scripts/run_city_probe.py"), "--name", name, "--sets", "world,furnished,furniture", "--registry", str(registry),
               "--namespaces", ",".join(["minecraft", "modernlife", "underground_city", *namespaces]), "--output", str(work / "registry-probe.json"), "--radius", "1", "--cities", "valewood"]
    print("registry: dumping it from a disposable dedicated server (a few minutes; nothing private is loaded):\n  " + " ".join(command))
    result = subprocess.run(command, cwd=ROOT)
    shutil.rmtree(ROOT / "rpg-core" / f"run-cityprobe-{name}", ignore_errors=True)
    if result.returncode != 0 or not registry.is_file():
        fail("the registry dump failed; see the gradle output above (Java 17 and a network for the first download are needed)")
    key_file.write_text(key + "\n", encoding="utf-8")
    return registry


def stage(source: Path, out: Path, work: Path | None, registry_file: Path | None, selection_file: Path | None, holder: str, summary: str, dwellings_file: Path | None = None) -> dict:
    import port_lostcities_pack as porter  # noqa: E402
    import private_wiring as wiring  # noqa: E402
    source, out = source.resolve(), out.resolve()
    work = (work or out.parent / (out.name + "-work")).resolve()
    for path in (source, out, work):
        packs.refuse_in_repo(path)
    if (out / packs.MANIFEST).is_file():
        fail(f"{out} is a pack; --out is the folder that holds packs (the game is pointed at it)")
    work.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(DEV_PACK.read_text(encoding="utf-8"))
    namespaces = furniture_namespaces(manifest)
    original = prepare_original(source, work, holder, summary)
    registry_path = registry_file.resolve() if registry_file else dump_registry(work, namespaces, registry_key(manifest))
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    fresh = work / "port-new"
    report = porter.port(original, fresh, keep=set(namespaces), registry=registry, force=True)
    if not report.get("loadable"):
        fail(f"the port is not loadable ({len(report.get('problems', []))} problem(s), listed in {fresh / porter.REPORT}); nothing was installed")
    counts = report.get("counts", {})
    print(f"port: {counts.get('entries', 0)} palette entries; {counts.get('foreign_replaced', 0)} blocks of mods that are not loaded replaced, {counts.get('kept_but_gone', 0)} gone by 1.20.1, "
          f"{counts.get('to_air', 0)} of them to air (the full list: {fresh / porter.REPORT})")
    if selection_file:
        selection = json.loads(selection_file.read_text(encoding="utf-8"))
        if selection.get("schema_version") != 1:
            fail(f"{selection_file}: unsupported selection schema")
        chosen_by = "owner"
    else:
        selection, picked = auto_select(fresh)
        (work / "selection-auto.json").write_text(json.dumps(selection, indent=1) + "\n", encoding="utf-8")
        (work / "selection-auto-report.json").write_text(json.dumps(picked, indent=1) + "\n", encoding="utf-8")
        if not picked["summary"]["buildings"] and not picked["summary"]["multi_buildings"]:
            fail(f"no building could be chosen ({picked['summary']['left_out']} left out, why: in {work / 'selection-auto-report.json'}); nothing was installed")
        print(f"selection: automatic, {picked['summary']['buildings']} building(s) and {picked['summary']['multi_buildings']} multi-chunk building(s); "
              f"{picked['summary']['left_out']} left out (why: {work / 'selection-auto-report.json'}); the selection is {work / 'selection-auto.json'}")
        chosen_by = "auto"
    if dwellings_file:
        # the owner's own dwellings for the buildings whose rooms do not say for certain where a home ends (D-206): {"<building id>": [dwellings] or "none"}
        overrides = json.loads(dwellings_file.read_text(encoding="utf-8"))
        if not isinstance(overrides, dict):
            fail(f"{dwellings_file}: dwellings overrides are an object of building id to dwellings")
        selection = dict(selection, dwellings={**selection.get("dwellings", {}), **overrides})
    wired = wiring.wire(fresh, selection, wiring.PUBLIC)
    homes = {"multi_buildings_with_dwellings": sum(1 for b in wired["buildings"].values() if b.get("multi") and b.get("dwellings")),
             "multi_buildings_uncertain": sum(1 for b in wired["buildings"].values() if b.get("multi") and b.get("uncertain")),
             "dwellings": sum(b.get("dwellings", 0) for b in wired["buildings"].values() if b.get("multi"))}
    (work / "wiring-report.json").write_text(json.dumps(wired, indent=1) + "\n", encoding="utf-8")
    print(f"dwellings: {homes['dwellings']} in {homes['multi_buildings_with_dwellings']} multi-chunk building(s); {homes['multi_buildings_uncertain']} uncertain and not let "
          f"(why: {work / 'wiring-report.json'}; name their dwellings yourself with --dwellings)")
    stage_doc = {"schema_version": STAGE_SCHEMA, "porter_version": porter.PORTER_VERSION, "wiring_version": wiring.WIRING_VERSION, "needs_sets": NEEDS_SETS,
                 "recommended_sets": RECOMMENDED_SETS, "selection": chosen_by, "registry_key": registry_key(manifest), "core_commit": core_commit(),
                 "staged_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
                 "counts": {"buildings": len(wired["buildings"]), "listings": wired["listings"], "cities": sorted(wired["cities"]), **homes},
                 "note": "Counts and versions only; written by private_c70.py stage."}
    (fresh / STAGE).write_text(json.dumps(stage_doc, indent=2) + "\n", encoding="utf-8")
    packs.seal(fresh)
    out.mkdir(parents=True, exist_ok=True)
    installed = out / PACK_NAME
    if installed.exists():
        previous = work / "previous"
        previous.mkdir(exist_ok=True)
        shutil.move(str(installed), str(previous / f"{PACK_NAME}-{time.strftime('%Y%m%d-%H%M%S')}"))
        for old in sorted(previous.iterdir())[:-3]:
            remove_tree(old)
    shutil.move(str(fresh), str(installed))
    print(f"staged: {installed} ({stage_doc['counts']['buildings']} building(s) wired, {stage_doc['counts']['listings']} listing(s)); launch with -C70 -PrivateContent \"{out}\"")
    return stage_doc


def core_commit() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short=12", "HEAD"], cwd=ROOT, capture_output=True, text=True, timeout=20).stdout.strip() or "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("stage")
    s.add_argument("--source", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--work", type=Path)
    s.add_argument("--registry", type=Path)
    s.add_argument("--selection", type=Path)
    s.add_argument("--holder", default="")
    s.add_argument("--summary", default="")
    s.add_argument("--dwellings", type=Path, help="the owner's dwellings for buildings whose rooms are uncertain: {\"<building id>\": [dwellings] or \"none\"}")
    p = sub.add_parser("select")
    p.add_argument("port", type=Path)
    p.add_argument("selection", type=Path)
    c = sub.add_parser("check")
    c.add_argument("root", type=Path)
    c.add_argument("--sets", default=",".join(RECOMMENDED_SETS))
    c.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    if args.command == "stage":
        stage(args.source, args.out, args.work, args.registry, args.selection, args.holder, args.summary, args.dwellings)
        return 0
    if args.command == "select":
        packs.refuse_in_repo(args.port)
        packs.refuse_in_repo(args.selection)
        selection, picked = auto_select(args.port)
        args.selection.write_text(json.dumps(selection, indent=1) + "\n", encoding="utf-8")
        print(json.dumps(picked["summary"], indent=1))
        return 0
    result = check(args.root.expanduser().absolute(), [x.strip().lower() for x in args.sets.split(",") if x.strip()])
    if args.json:
        print(json.dumps(result, indent=1))
    else:
        for pack in result["packs"]:
            print(f"private pack {pack['folder']}: {pack['files']} files, staged {'yes' if pack['staged'] else 'no'}, needs {', '.join(pack['needs_sets']) or 'nothing more'}")
        for line in result["warnings"]:
            print("warning: " + line)
        for line in result["errors"]:
            print("error: " + line)
        print("private content: ready" if result["ok"] else "private content: NOT ready")
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
