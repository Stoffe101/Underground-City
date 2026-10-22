#!/usr/bin/env python3
"""Port a PRIVATE Lost Cities content pack made for Minecraft 1.18.2 to this game (1.20.1, no Create, no hostile spawning, no foreign loot).

The private content the owner may use (docs/legal/private-content.md, D-116) is a 1.18.2 server pack's Lost Cities data. This tool reads a private
pack folder (outside every git checkout), writes a converted pack to another folder outside every checkout, and writes a report of what it did. It
never reads or writes the repository's tracked files, never downloads anything, and refuses a source or destination inside a git work tree. It has
only been run on an INVENTED fixture (``fixture``); no private content has been ported by it.

    port_lostcities_pack.py fixture <dir>                         an invented 1.18.2-style pack (nobody's content) for tests and CI
    port_lostcities_pack.py port <source-pack> <dest-pack> [--registry REG.json] [--mapping MAP.json] [--keep-mod NS ...] [--force]

What a port does to every palette entry (``data/<ns>/lostcities/palettes/*.json`` and the inline palettes of parts and buildings):

* 1.18.2 block ids and properties that changed by 1.20.1 are renamed (``RENAMES``; a ``--mapping`` file adds or overrides rules).
* Every ``create:`` block is replaced (the project does not use Create, D-080): by the mapping, else by ``CREATE_FALLBACKS`` (structural blocks
  become their nearest vanilla look, machinery becomes air), and counted.
* A block of a mod that is not kept (``--keep-mod``; vanilla and the furniture mod this pack already installs, ``modernlife``, are kept by default) is
  replaced by the mapping or by ``minecraft:air`` and listed, so a building never names a block the game does not have (which empties its chunk).
* Spawners and mob markers are removed (a spawner becomes air, a palette entry's ``mob`` is dropped): this game has no ordinary hostile creatures.
* A palette entry's ``loot`` table (``lostcities:chests/...`` and the like) is dropped: loot is this game's own (docs/technical/item-source-audit.md);
  the chest stays, empty. ``--loot-map`` is deliberately not offered: a port must not invent rewards.
* With ``--registry`` (a block registry a real 1.20.1 game dumped, ``CityProbe --registry``) every resulting block is checked like
  ``validate_city_blocks.py`` checks the furnished cities, and problems are listed (a port with problems is still written, and the report says
  it must not be loaded).

Everything else (parts' slices, buildings, multibuildings, citystyles) is copied unchanged; worldstyles are copied and flagged for a person to
review (their format changed between Lost Cities 5 and 7). The report (``uc-port-report.json`` in the destination) holds counts and block ids, never
the content itself.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import private_pack_tool as packs  # noqa: E402
import validate_city_blocks as blocks  # noqa: E402

REPORT = "uc-port-report.json"
PACK_FORMAT_1201 = 15

# 1.18.2 -> 1.20.1 changes that matter for building palettes (vanilla). A rule maps an old id to a new one, optionally with property renames.
RENAMES: dict[str, str] = {
    "minecraft:grass_path": "minecraft:dirt_path",
}
# The project does not use Create: what each kind of Create block becomes (first matching pattern wins).
CREATE_FALLBACKS: list[tuple[str, str]] = [
    (r"create:.*_casing$", "minecraft:polished_andesite"),
    (r"create:.*andesite.*", "minecraft:polished_andesite"),
    (r"create:.*(brass|copper).*(block|tiles|shingles)", "minecraft:cut_copper"),
    (r"create:.*(scorchia|limestone|veridium|ochrum|crimsite|asurine).*", "minecraft:stone_bricks"),
    (r"create:.*(window|glass).*", "minecraft:glass_pane"),
    (r"create:.*(ladder).*", "minecraft:ladder"),
    (r"create:.*(door).*", "minecraft:iron_door"),
    (r"create:.*", "minecraft:air"),
]
DEFAULT_KEEP = {"minecraft", "modernlife"}
SPAWNERS = {"minecraft:spawner"}


def namespace(spec: str) -> str:
    return spec.split(":", 1)[0] if ":" in spec else "minecraft"


class Porter:
    def __init__(self, mapping: dict[str, str] | None = None, keep: set[str] | None = None, registry: dict | None = None):
        self.mapping = dict(mapping or {})
        self.keep = set(DEFAULT_KEEP) | set(keep or set())
        self.registry = registry
        self.counts = {"palettes": 0, "entries": 0, "renamed": 0, "create_replaced": 0, "foreign_replaced": 0, "spawners_removed": 0,
                       "mob_markers_removed": 0, "loot_dropped": 0, "files_copied": 0, "worldstyles_to_review": 0}
        self.foreign: dict[str, str] = {}      # replaced block id -> what it became
        self.problems: list[str] = []

    # ------------------------------------------------------------------------------------------------ one block spec

    def block(self, spec: str) -> str:
        name, props = blocks.parse_spec(spec) if blocks.SPEC.match(spec) else (spec, {})
        if spec in self.mapping or name in self.mapping:
            return self.mapping.get(spec, self.mapping.get(name))
        if name in SPAWNERS:
            self.counts["spawners_removed"] += 1
            return "minecraft:air"
        if name in RENAMES:
            self.counts["renamed"] += 1
            name = RENAMES[name]
        ns = namespace(name)
        if ns == "create":
            self.counts["create_replaced"] += 1
            for pattern, fallback in CREATE_FALLBACKS:
                if re.fullmatch(pattern, name):
                    self.foreign[name] = fallback
                    return fallback
        if ns not in self.keep:
            self.counts["foreign_replaced"] += 1
            self.foreign[name] = "minecraft:air"
            return "minecraft:air"
        return name + ("[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]" if props else "")

    def check(self, spec: str, where: str) -> None:
        if self.registry is None or spec == "minecraft:air":
            return
        for problem in blocks.problems_of(spec, self.registry):
            self.problems.append(f"{where}: {problem}")

    # ------------------------------------------------------------------------------------------------ one palette entry

    def entry(self, e: dict, where: str) -> dict:
        out = dict(e)
        self.counts["entries"] += 1
        if "mob" in out:
            out.pop("mob")
            self.counts["mob_markers_removed"] += 1
        if "loot" in out:
            out.pop("loot")
            self.counts["loot_dropped"] += 1
        if "block" in out:
            out["block"] = self.block(out["block"])
            self.check(out["block"], where)
        if "blocks" in out:
            converted = []
            for option in out["blocks"]:
                option = dict(option)
                option["block"] = self.block(option["block"])
                self.check(option["block"], where)
                converted.append(option)
            out["blocks"] = converted
        return out

    def palette(self, entries: list, where: str) -> list:
        self.counts["palettes"] += 1
        return [self.entry(e, f"{where} '{e.get('char', '?')}'") for e in entries]

    # ------------------------------------------------------------------------------------------------ a whole pack

    def port(self, source: Path, dest: Path) -> dict:
        for path in sorted(p for p in source.rglob("*") if p.is_file()):
            rel = path.relative_to(source)
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            if rel.name in (packs.MANIFEST, packs.INDEX, REPORT, "pack.mcmeta"):
                continue
            parts = rel.parts
            if path.suffix == ".json" and "lostcities" in parts:
                doc = json.loads(path.read_text(encoding="utf-8"))
                kind = parts[parts.index("lostcities") + 1] if parts.index("lostcities") + 1 < len(parts) else ""
                where = "/".join(parts[parts.index("lostcities") + 1:])
                if isinstance(doc, dict) and isinstance(doc.get("palette"), list):
                    doc["palette"] = self.palette(doc["palette"], where)
                if kind == "worldstyles":
                    self.counts["worldstyles_to_review"] += 1
                target.write_text(json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                continue
            shutil.copyfile(path, target)
            self.counts["files_copied"] += 1
        return self.report()

    def report(self) -> dict:
        return {"schema_version": 1, "counts": self.counts, "replaced_blocks": dict(sorted(self.foreign.items())), "registry_checked": self.registry is not None,
                "problems": self.problems, "loadable": not self.problems,
                "note": "Counts and block ids only; no content. A port with problems must not be loaded: fix the mapping and port again."}


def port(source: Path, dest: Path, mapping: dict | None = None, keep: set[str] | None = None, registry: dict | None = None, force: bool = False) -> dict:
    for path in (source, dest):
        packs.refuse_in_repo(path)
    manifest_path = source / packs.MANIFEST
    if not manifest_path.is_file():
        raise SystemExit(f"{source} has no {packs.MANIFEST}: only a private pack with a manifest can be ported")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not manifest.get("private_only"):
        raise SystemExit(f"{source}: the manifest does not say private_only: true")
    if dest.exists() and any(dest.iterdir()):
        if not force:
            raise SystemExit(f"{dest} is not empty (use --force to replace it)")
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)
    porter = Porter(mapping, keep, registry)
    result = porter.port(source, dest)
    ported = dict(manifest, id=(manifest["id"] + "-port")[:64], title=manifest.get("title", manifest["id"]) + " (ported to 1.20.1)",
                  ported_from={"id": manifest["id"], "minecraft": manifest.get("minecraft", "1.18.2")})
    (dest / packs.MANIFEST).write_text(json.dumps(ported, indent=2) + "\n", encoding="utf-8")
    (dest / "pack.mcmeta").write_text(json.dumps({"pack": {"pack_format": PACK_FORMAT_1201, "description": ported["title"]}}, indent=2) + "\n", encoding="utf-8")
    (dest / REPORT).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def fixture(root: Path) -> Path:
    """An invented 1.18.2-style Lost Cities pack with every kind of thing a port must handle. Nothing in it is anybody's content."""
    pack = root / "uc_port_fixture"
    packs.refuse_in_repo(pack)
    packs.init(pack, "uc_port_fixture", "Synthetic 1.18.2 fixture", "this repository (synthetic, not anybody's content)", "an invented pack that exists only to test the porter", True, False)
    lc = pack / "data" / "fixturecity" / "lostcities"
    for sub in ("palettes", "parts", "buildings", "worldstyles"):
        (lc / sub).mkdir(parents=True, exist_ok=True)
    (lc / "palettes" / "fixture_house.json").write_text(json.dumps({"palette": [
        {"char": "#", "block": "minecraft:stone_bricks"},
        {"char": "p", "block": "minecraft:grass_path"},
        {"char": "c", "block": "create:andesite_casing"},
        {"char": "s", "block": "create:shaft[axis=y]"},
        {"char": "M", "block": "minecraft:spawner", "mob": "minecraft:zombie"},
        {"char": "C", "block": "minecraft:chest[facing=north]", "loot": "lostcities:chests/lostcitychest"},
        {"char": "f", "block": "otherfurniture:fancy_chair[facing=east]"},
        {"char": "r", "blocks": [{"random": 300, "block": "create:brass_block"}, {"random": 1000, "block": "minecraft:oak_planks"}]},
        {"char": "t", "block": "modernlife:oak_chair[facing=south]"},
    ]}, indent=2), encoding="utf-8")
    (lc / "parts" / "fixture_house_ground.json").write_text(json.dumps({"xsize": 2, "zsize": 2, "refpalette": "fixturecity:fixture_house",
        "slices": [["##", "##"], ["Mc", "Cf"], ["rs", "tp"], ["  ", "  "], ["  ", "  "], ["##", "##"]],
        "palette": [{"char": "x", "block": "create:cogwheel"}]}, indent=2), encoding="utf-8")
    (lc / "buildings" / "fixture_house.json").write_text(json.dumps({"minfloors": 1, "maxfloors": 1, "refpalette": "fixturecity:fixture_house",
        "parts": [{"top": False, "ground": True, "part": "fixturecity:fixture_house_ground"}]}, indent=2), encoding="utf-8")
    (lc / "worldstyles" / "fixture.json").write_text(json.dumps({"citystyles": [{"factor": 1.0, "citystyle": "fixturecity:fixture"}]}, indent=2), encoding="utf-8")
    return pack


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    f = sub.add_parser("fixture")
    f.add_argument("dir", type=Path)
    p = sub.add_parser("port")
    p.add_argument("source", type=Path)
    p.add_argument("dest", type=Path)
    p.add_argument("--registry", type=Path)
    p.add_argument("--mapping", type=Path, help="a JSON object of old block id (or full spec) -> new block spec")
    p.add_argument("--keep-mod", action="append", default=[], help="a namespace whose blocks the game has (repeatable)")
    p.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)
    if args.command == "fixture":
        print(f"invented fixture at {fixture(args.dir)}")
        return 0
    mapping = json.loads(args.mapping.read_text(encoding="utf-8")) if args.mapping else None
    registry = json.loads(args.registry.read_text(encoding="utf-8")) if args.registry else None
    result = port(args.source, args.dest, mapping, set(args.keep_mod), registry, args.force)
    print(json.dumps(result["counts"], indent=2))
    if result["problems"]:
        print(f"{len(result['problems'])} problems: this port must not be loaded (see {args.dest / REPORT})")
        return 1
    print(f"ported to {args.dest}" + ("" if registry else " (no registry given: blocks were not checked against a real game)"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
