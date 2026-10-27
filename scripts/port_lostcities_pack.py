#!/usr/bin/env python3
"""Port a PRIVATE Lost Cities content pack made for Minecraft 1.18.2 to this game (1.20.1, no Create, no hostile spawning, no foreign loot).

The private content the owner may use (docs/legal/private-content.md, D-116) is a 1.18.2 server pack's Lost Cities data. This tool reads a private
pack folder (outside every git checkout), writes a converted pack to another folder outside every checkout, and writes a report of what it did. It
never reads or writes the repository's tracked files, never downloads anything, and refuses a source or destination inside a git work tree. It has
been run on an invented fixture (``fixture``) in CI; private content is ported by it only outside any checkout, and its output never enters the repository.

    port_lostcities_pack.py fixture <dir>                         an invented 1.18.2-style pack (nobody's content) for tests and CI
    port_lostcities_pack.py port <source-pack> <dest-pack> [--registry REG.json] [--mapping MAP.json] [--keep-mod NS ...] [--force]

What a port does to every palette entry (``data/<ns>/lostcities/palettes/*.json`` and the inline palettes of parts and buildings):

* 1.18.2 block ids and properties that changed by 1.20.1 are renamed (``RENAMES``; a ``--mapping`` file adds or overrides rules).
* Every ``create:`` block is replaced (the project does not use Create, D-080): by the mapping, else by ``CREATE_FALLBACKS`` (structural blocks
  become their nearest vanilla look, machinery becomes air), and counted.
* A block of a mod that is not kept (``--keep-mod``; vanilla and the furniture mod this pack already installs, ``modernlife``, are kept by default) is
  replaced by the mapping, else by its nearest look (``block_lookalikes.py``: the public rules of
  ``content/integrations/private-import/block-lookalikes.json``, then its shape in its material when a registry is given), else by ``minecraft:air``,
  and listed, so a building never names a block the game does not have (which empties its chunk).
* With a registry, a block of a kept mod that 1.20.1 no longer has becomes its sibling of the same mod (another wood, colour or number) or its
  nearest look, and a property 1.20.1 does not accept is dropped from the spec; both are listed.
* Spawners and mob markers are removed (a spawner becomes air, a palette entry's ``mob`` is dropped): this game has no ordinary hostile creatures.
* A palette entry's ``loot`` table (``lostcities:chests/...`` and the like) is dropped: loot is this game's own (docs/technical/item-source-audit.md);
  the chest stays, empty. ``--loot-map`` is deliberately not offered: a port must not invent rewards.
* With ``--registry`` (a block registry a real 1.20.1 game dumped, ``CityProbe --registry``) every resulting block is checked like
  ``validate_city_blocks.py`` checks the furnished cities, and problems are listed (a port with problems is still written, and the report says
  it must not be loaded).

Parts' slices, multibuildings and citystyles are copied unchanged. A building's floors are made explicit for Lost Cities 7 (``overrideFloors``) and
raised when its middle parts (a roof, an upper storey) would otherwise never be built. A character a building's parts take from the pack's own
city-style palettes (``styles/*.json``), which no palette of the building or its parts defines, is given to the building's palette: this game's city
styles choose the look, and a character none of them defines makes Lost Cities throw and leave the lot empty. World styles are left out unless ``--keep-worldstyles``
(then copied and flagged for a person to review): Lost Cities 7 loads them as a registry, and one that names a biome of a mod that is not loaded
stops the world from loading; the game's own world style chooses the cities. The report (``uc-port-report.json`` in the destination) holds counts and block ids, never
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
import block_lookalikes  # noqa: E402
import private_pack_tool as packs  # noqa: E402
import validate_city_blocks as blocks  # noqa: E402
from citykit import CHAR_POOL, LOST_CITIES_RESERVED, LEGACY_RENAMED, as_written  # noqa: E402

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


def inline_entries(doc) -> list | None:
    """The entry list of a part's or building's inline palette, in either form Lost Cities reads (``"palette": [...]`` or ``"palette": {"palette": [...]}``)."""
    if not isinstance(doc, dict):
        return None
    inline = doc.get("palette")
    if isinstance(inline, list):
        return inline
    if isinstance(inline, dict) and isinstance(inline.get("palette"), list):
        return inline["palette"]
    return None


class Porter:
    def __init__(self, mapping: dict[str, str] | None = None, keep: set[str] | None = None, registry: dict | None = None, keep_worldstyles: bool = False):
        self.keep_worldstyles = keep_worldstyles
        self.mapping = dict(mapping or {})
        self.keep = set(DEFAULT_KEEP) | set(keep or set())
        self.registry = registry
        self.look = block_lookalikes.Lookalikes(registry=registry)
        self.counts = {"palettes": 0, "entries": 0, "renamed": 0, "create_replaced": 0, "foreign_replaced": 0, "spawners_removed": 0,
                       "mob_markers_removed": 0, "loot_dropped": 0, "files_copied": 0, "worldstyles_to_review": 0, "worldstyles_left_out": 0,
                       "kept_but_gone": 0, "properties_dropped": 0, "by_rule": 0, "by_sibling": 0, "by_shape": 0, "to_air": 0,
                       "floors_made_explicit": 0, "floors_raised": 0, "style_characters_renamed": 0, "parts_with_style_characters_renamed": 0,
                       "multi_parts_given_building_characters": 0, "multi_part_characters_given": 0,
                       "buildings_given_style_characters": 0, "style_characters_given": 0, "legacy_names_kept": 0}
        self.foreign: dict[str, str] = {}      # replaced block id -> what it became
        self.gone: dict[str, str] = {}         # a kept mod's block that 1.20.1 no longer has -> what it became
        self.dropped: dict[str, list[str]] = {}   # block id -> properties of the 1.18.2 spec that the 1.20.1 block does not accept
        self.problems: list[str] = []
        self.style_chars: dict[str, list[str]] = {}   # palette -> Lost Cities style characters it gives a block of its own (a warning: see LOST_CITIES_RESERVED)
        self.char_map: dict[str, str] = {}             # a style character a pack's palette took -> the free character it became (rename_style_characters)
        self.undefined: dict[str, list[str]] = {}       # building -> characters its parts use that no palette of the pack defines (still undefined after the port)

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
            # the nearest look of a block the game has (content/integrations/private-import/block-lookalikes.json), else air, said in the report
            self.counts["foreign_replaced"] += 1
            result, how = self.look.resolve(name, props)
            self.counts["to_air" if how == "air" or result == "minecraft:air" else "by_" + how] += 1
            self.foreign[name] = result.split("[", 1)[0]
            return result
        if self.registry is not None and name not in self.registry and any(k.startswith(ns + ":") for k in self.registry):
            # a kept mod's block that 1.20.1 no longer has: its sibling of the same mod, else the nearest look
            self.counts["kept_but_gone"] += 1
            result, how = self.look.resolve(name, props, kept_namespace=True)
            self.counts["to_air" if how == "air" or result == "minecraft:air" else "by_" + how] += 1
            self.gone[name] = result.split("[", 1)[0]
            return result
        if self.registry is not None and name in self.registry and props:
            props = self.look.renamed(ns, props)   # a property the mod renamed by 1.20.1 (block-lookalikes.json, property_renames)
            have = self.registry[name]
            bad = sorted(k for k, v in props.items() if k not in have or v not in have[k])
            if bad:
                # a property 1.20.1 renamed or dropped: the block keeps the rest, and the report lists what went
                self.counts["properties_dropped"] += len(bad)
                self.dropped.setdefault(name, sorted(set(self.dropped.get(name, [])) | set(bad)))
                props = {k: v for k, v in props.items() if k not in bad}
        return name + ("[" + ",".join(f"{k}={v}" for k, v in props.items()) + "]" if props else "")

    def check(self, spec: str, where: str) -> None:
        if self.registry is None or spec == "minecraft:air":
            return
        ns = namespace(blocks.parse_spec(spec)[0]) if blocks.SPEC.match(spec) else ""
        if ns and ns != "minecraft" and not any(k.startswith(ns + ":") for k in self.registry):
            self.problems.append(f"{where}: the registry has no block of the kept mod {ns} at all: dump it with that namespace (CityProbe --registry --namespaces)")
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
            out["block"] = self.written(self.block(out["block"]))
            self.check(out["block"], where)
        if "blocks" in out:
            converted = []
            for option in out["blocks"]:
                option = dict(option)
                option["block"] = self.written(self.block(option["block"]))
                self.check(option["block"], where)
                converted.append(option)
            out["blocks"] = converted
        return out

    def written(self, spec: str) -> str:
        """The spec as Lost Cities must read it to place that very block (citykit.LEGACY_RENAMED): a short grass, an empty flower pot or a pumpkin, the pack's own or a
        look-alike, is otherwise placed as a grass block, a potted cactus or a carved pumpkin."""
        if spec in LEGACY_RENAMED:
            self.counts["legacy_names_kept"] += 1
        return as_written(spec)

    def palette(self, entries: list, where: str) -> list:
        self.counts["palettes"] += 1
        # a building's palette is merged over the city style's in its chunk, so these characters redraw the retaining walls and streets around it (intended only in a style's own palette)
        taken = sorted({str(e.get("char", "")) for e in entries} & set(LOST_CITIES_RESERVED))
        if taken and not where.startswith(("styles/", "citystyles/")):
            self.style_chars[where] = taken
        return [self.entry(e, f"{where} '{e.get('char', '?')}'") for e in entries]

    def floors(self, building: dict) -> None:
        """A building's floors for Lost Cities 7. A 1.18.2 building leaves its floor count to the city style unless it says otherwise, and Lost Cities 7 builds
        storeys 0 to ``floors`` with the top part last: a building whose middle parts (its roof, its upper storeys) need more floors than it asks for was
        generated as a bare ground storey (measured in a probe: no block above the street storey). Its own floors are made explicit, and raised until every
        middle part is used."""
        middle = [p for p in building["parts"] if isinstance(p, dict) and not p.get("cellar") and not p.get("ground") and not p.get("top")]
        if "overrideFloors" not in building:
            building["overrideFloors"] = True
            self.counts["floors_made_explicit"] += 1
        if middle:
            needed = max(int(p.get("floor", 1)) for p in middle) + 1
            if int(building.get("maxfloors", building.get("minfloors", 1))) < needed:
                building["minfloors"] = max(int(building.get("minfloors", 1)), needed)
                building["maxfloors"] = max(int(building.get("maxfloors", 1)), needed)
                self.counts["floors_raised"] += 1

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
                if kind == "worldstyles" and not self.keep_worldstyles:
                    # a world style decides a whole world's layout, and Lost Cities 7 loads it as a registry: one that names a biome of a mod that is not
                    # loaded stops the world from loading at all (measured: a 1.18.2 world style naming another mod's biomes). The game's own world style
                    # chooses the cities; a pack's buildings reach them through city_character (private_wiring.py)
                    target.unlink(missing_ok=True)
                    self.counts["worldstyles_left_out"] += 1
                    continue
                inline = inline_entries(doc)
                if inline is not None:
                    inline[:] = self.palette(inline, where)
                if kind == "buildings" and isinstance(doc, dict) and isinstance(doc.get("parts"), list):
                    self.floors(doc)
                if kind == "worldstyles":
                    self.counts["worldstyles_to_review"] += 1
                target.write_text(json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                continue
            shutil.copyfile(path, target)
            self.counts["files_copied"] += 1
        self.rename_style_characters(dest)
        self.give_style_characters(dest)
        self.give_multi_part_characters(dest)
        return self.report()

    def rename_style_characters(self, dest: Path) -> None:
        """Give every Lost Cities style character a pack's palette takes (``LOST_CITIES_RESERVED``) a free character instead, in that palette and in the
        slices of every part that reads it. Lost Cities merges a building's palettes over the city style's in the building's chunk and draws its own
        retaining walls, streets, park ground, corridors and bridge supports there with those characters: a ported palette that took one redrew them with
        its furniture (measured on our own buildings, October 23; the owner's pack took them in 1,000 palettes). A part that uses the character without
        any palette of the pack defining it means the style's block, and keeps it."""
        docs: dict[Path, dict] = {}
        for path in sorted(dest.rglob("*.json")):
            parts = path.relative_to(dest).parts
            if "lostcities" in parts and parts.index("lostcities") + 1 < len(parts):
                kind = parts[parts.index("lostcities") + 1]
                if kind in ("palettes", "parts", "buildings"):
                    docs[path] = json.loads(path.read_text(encoding="utf-8"))

        def rid(path: Path) -> str:
            parts = path.relative_to(dest).parts
            i = parts.index("lostcities")
            return parts[1] + ":" + "/".join(parts[i + 2:])[:-len(".json")]

        def kind_of(path: Path) -> str:
            parts = path.relative_to(dest).parts
            return parts[parts.index("lostcities") + 1]

        def chars(entries) -> set[str]:
            return {str(e.get("char", "")) for e in entries or [] if isinstance(e, dict)}

        palettes = {rid(p): chars(d.get("palette")) for p, d in docs.items() if kind_of(p) == "palettes" and isinstance(d, dict)}
        defined_by_part: dict[str, set[str]] = {}
        for p, d in docs.items():
            if kind_of(p) == "parts" and isinstance(d, dict):
                defined_by_part[rid(p)] = chars(inline_entries(d)) | palettes.get(d.get("refpalette") or "", set())
        for p, d in docs.items():
            if kind_of(p) == "buildings" and isinstance(d, dict):
                own = chars(inline_entries(d)) | palettes.get(d.get("refpalette") or "", set())
                for ref in d.get("parts", []):
                    if isinstance(ref, dict) and ref.get("part") in defined_by_part:
                        defined_by_part[ref["part"]] |= own
        taken = set().union(*palettes.values(), *defined_by_part.values()) if palettes or defined_by_part else set()
        for d in docs.values():
            for layer in (d.get("slices") or []) if isinstance(d, dict) else []:
                for row in layer:
                    taken |= set(row)
        wanted = sorted(set(LOST_CITIES_RESERVED) & (set().union(*palettes.values(), *defined_by_part.values()) if palettes or defined_by_part else set()))
        free = [c for c in CHAR_POOL if c not in taken]
        if len(free) < len(wanted):
            self.problems.append(f"no free character left for the style characters {''.join(wanted)}: they are left as they are")
            return
        self.char_map = dict(zip(wanted, free))
        if not self.char_map:
            return
        for p, d in docs.items():
            changed = False
            if inline_entries(d) is not None:
                for e in inline_entries(d):
                    if isinstance(e, dict) and str(e.get("char", "")) in self.char_map:
                        e["char"] = self.char_map[str(e["char"])]
                        changed = True
                if changed:
                    self.counts["style_characters_renamed"] += 1
            if kind_of(p) == "parts" and isinstance(d.get("slices"), list):
                mine = {c: n for c, n in self.char_map.items() if c in defined_by_part.get(rid(p), set())}
                if mine:
                    table = str.maketrans(mine)
                    slices = [[row.translate(table) for row in layer] for layer in d["slices"]]
                    if slices != d["slices"]:
                        d["slices"] = slices
                        self.counts["parts_with_style_characters_renamed"] += 1
                        changed = True
            if changed:
                p.write_text(json.dumps(d, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        # what is left to warn about: none, unless a style's own palette (kept as it is) takes them
        self.style_chars = {k: v for k, v in self.style_chars.items() if not set(v) <= set(self.char_map)}

    def give_style_characters(self, dest: Path) -> None:
        """Give a building the characters its parts take from the pack's own city-style palettes when no palette of the building or of those parts
        defines them. A 1.18.2 pack's styles merge a shared palette under every building (one pack's styles all use one "common" palette), and its
        parts lean on it for blocks of their own. This game's city styles replace the pack's, so such a character is defined nowhere: Lost Cities throws
        ("Could not find entry ... in the palette for part ...") and the lot stays empty (measured: a factory of the owner's pack, 68 lots in three cities,
        October 27). The style characters Lost Cities draws its streets with (``LOST_CITIES_RESERVED``) stay the city style's. The building's palette
        sits between the style's and the part's, so a part's own definition still wins."""
        docs: dict[Path, dict] = {}
        for path in sorted(dest.rglob("*.json")):
            parts = path.relative_to(dest).parts
            if "lostcities" in parts and parts.index("lostcities") + 1 < len(parts) and parts[parts.index("lostcities") + 1] in ("palettes", "parts", "buildings", "styles"):
                docs[path] = json.loads(path.read_text(encoding="utf-8"))

        def rid(path: Path) -> str:
            parts = path.relative_to(dest).parts
            return parts[1] + ":" + "/".join(parts[parts.index("lostcities") + 2:])[:-len(".json")]

        def kind_of(path: Path) -> str:
            parts = path.relative_to(dest).parts
            return parts[parts.index("lostcities") + 1]

        def entries_by_char(entries) -> dict[str, dict]:
            return {str(e.get("char", "")): e for e in entries or [] if isinstance(e, dict)}

        palettes = {rid(p): entries_by_char(d.get("palette")) for p, d in docs.items() if kind_of(p) == "palettes" and isinstance(d, dict)}
        # the styles' palettes, merged in the order a style lists them (the first choice of each group: they are alternatives of one look)
        style: dict[str, dict] = {}
        for p, d in docs.items():
            if kind_of(p) != "styles" or not isinstance(d, dict):
                continue
            for group in d.get("randompalettes") or []:
                choices = group if isinstance(group, list) else [group]
                first = next((c.get("palette") for c in choices if isinstance(c, dict) and c.get("palette")), None)
                for char, entry in palettes.get(first or "", {}).items():
                    style.setdefault(char, entry)
        parts_by_id = {rid(p): d for p, d in docs.items() if kind_of(p) == "parts" and isinstance(d, dict)}
        for p, d in docs.items():
            if kind_of(p) != "buildings" or not isinstance(d, dict):
                continue
            own = set(entries_by_char(inline_entries(d))) | set(palettes.get(d.get("refpalette") or "", {}))
            missing: set[str] = set()
            for ref in list(d.get("parts") or []) + list(d.get("parts2") or []):
                part = parts_by_id.get(ref.get("part")) if isinstance(ref, dict) else None
                if part is None:
                    continue
                defined = own | set(entries_by_char(inline_entries(part))) | set(palettes.get(part.get("refpalette") or "", {}))
                used = {c for layer in part.get("slices") or [] for row in layer for c in row}
                missing |= used - defined - set(LOST_CITIES_RESERVED) - {" "}
            given = [dict(style[c]) for c in sorted(missing) if c in style]
            unknown = sorted(c for c in missing if c not in style)
            if unknown:
                self.undefined[rid(p)] = unknown
            if not given:
                continue
            if inline_entries(d) is not None:
                inline_entries(d).extend(given)
            elif d.get("refpalette"):
                # Lost Cities reads a building's inline palette instead of its referenced one, so the referenced palette is copied with the additions
                name = rid(p).split(":", 1)[1].replace("/", "_") + "_with_style"
                target = p.parents[1] / "palettes" / f"{name}.json"
                target.parent.mkdir(parents=True, exist_ok=True)
                merged = list(palettes.get(d["refpalette"], {}).values()) + given
                target.write_text(json.dumps({"palette": merged}, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                d["refpalette"] = rid(p).split(":", 1)[0] + ":" + name
            else:
                d["palette"] = {"palette": given}
            p.write_text(json.dumps(d, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            self.counts["buildings_given_style_characters"] += 1
            self.counts["style_characters_given"] += len(given)

    def give_multi_part_characters(self, dest: Path) -> None:
        """Give each part of a multi-chunk building's buildings the characters it takes from its building's own palette. Lost Cities 7.5.5 does
        not find them for a part of a multi-chunk building whose building alone defines a character ("Could not find entry 'H' in the palette for
        part ...", measured on the owner's pack, October 27: three lots of two multi-chunk buildings left as raw ground); a part's own palette is
        layered over everything else, so the same entry copied there gives the same block wherever Lost Cities looks. Only characters the part uses,
        does not define itself, and its building does define are copied."""
        def load(kind: str, rid: str):
            ns, path = rid.split(":", 1)
            file = dest / "data" / ns / "lostcities" / kind / f"{path}.json"
            return (file, json.loads(file.read_text(encoding="utf-8"))) if file.is_file() else (file, None)

        def by_char(entries) -> dict[str, dict]:
            return {str(e.get("char", "")): e for e in entries or [] if isinstance(e, dict)}

        for multi_file in sorted(dest.rglob("multibuildings/*.json")):
            ns = multi_file.relative_to(dest).parts[1]
            multi = json.loads(multi_file.read_text(encoding="utf-8"))
            for column in multi.get("buildings") or []:
                for name in column:
                    _, building = load("buildings", name if ":" in name else f"{ns}:{name}")
                    if not isinstance(building, dict):
                        continue
                    _, ref = load("palettes", building["refpalette"]) if building.get("refpalette") else (None, None)
                    own = {**by_char((ref or {}).get("palette")), **by_char(inline_entries(building))}
                    for partref in list(building.get("parts") or []) + list(building.get("parts2") or []):
                        if not isinstance(partref, dict) or not partref.get("part"):
                            continue
                        part_file, part = load("parts", partref["part"])
                        if not isinstance(part, dict):
                            continue
                        # what Lost Cities reads as the part's own palette: its inline one when it has one, and only then; else the palette it names
                        _, part_ref = load("palettes", part["refpalette"]) if part.get("refpalette") else (None, None)
                        mine = inline_entries(part)
                        if mine is None:
                            mine = [dict(e) for e in (part_ref or {}).get("palette") or [] if isinstance(e, dict)]
                        used = {c for layer in part.get("slices") or [] for row in layer for c in row}
                        given = [dict(own[c]) for c in sorted(used - set(by_char(mine)) - {" "}) if c in own]
                        if not given:
                            continue
                        # written in the object form ({"palette": [...]}), the only one Lost Cities 1.20.1 decodes for a part: it drops a bare list without a word
                        # (multi run 2, October 27: the list form left the same two buildings unbuilt), and reads the inline palette in place of the named one
                        part["palette"] = {"palette": mine + given}
                        part.pop("refpalette", None)
                        part_file.write_text(json.dumps(part, indent=2, sort_keys=True) + "\n", encoding="utf-8")
                        self.counts["multi_parts_given_building_characters"] += 1
                        self.counts["multi_part_characters_given"] += len(given)

    def report(self) -> dict:
        return {"schema_version": 1, "counts": self.counts, "replaced_blocks": dict(sorted(self.foreign.items())), "kept_mod_blocks_gone": dict(sorted(self.gone.items())),
                "properties_dropped": dict(sorted(self.dropped.items())), "registry_checked": self.registry is not None,
                "problems": self.problems, "loadable": not self.problems,
                "style_characters_redefined": dict(sorted(self.style_chars.items())),
                "style_characters_renamed_to": dict(sorted(self.char_map.items())),
                # a building listed here uses a character no palette of the pack defines: Lost Cities leaves its lot empty (leave it out of a selection)
                "buildings_with_undefined_characters": {b: "".join(c) for b, c in sorted(self.undefined.items())},
                "note": "Counts and block ids only; no content. A port with problems must not be loaded: fix the mapping and port again."}


def port(source: Path, dest: Path, mapping: dict | None = None, keep: set[str] | None = None, registry: dict | None = None, force: bool = False, keep_worldstyles: bool = False) -> dict:
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
    porter = Porter(mapping, keep, registry, keep_worldstyles)
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
        "slices": [["##", "##"], ["Mc", "Cf"], ["rs", "tp"], ["xy", "  "], ["  ", "  "], ["##", "##"]],
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
    p.add_argument("--keep-worldstyles", action="store_true", help="copy the pack's world styles too (left out by default: see Porter.port)")
    args = parser.parse_args(argv)
    if args.command == "fixture":
        print(f"invented fixture at {fixture(args.dir)}")
        return 0
    mapping = json.loads(args.mapping.read_text(encoding="utf-8")) if args.mapping else None
    registry = json.loads(args.registry.read_text(encoding="utf-8")) if args.registry else None
    result = port(args.source, args.dest, mapping, set(args.keep_mod), registry, args.force, args.keep_worldstyles)
    print(json.dumps(result["counts"], indent=2))
    if result["problems"]:
        print(f"{len(result['problems'])} problems: this port must not be loaded (see {args.dest / REPORT})")
        return 1
    print(f"ported to {args.dest}" + ("" if registry else " (no registry given: blocks were not checked against a real game)"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
