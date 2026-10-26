#!/usr/bin/env python3
"""The nearest look of a block the game has, for a block it does not have (D-192).

A private 1.18.2 Lost Cities pack names blocks of mods this game does not load (Create and its add-ons, which the project never uses; zombie and
combat mods; storage, power and vehicle mods), and a few blocks that its kept mods dropped by 1.20.1. A building with an unknown block loses its
whole chunk, and one with air in its place has holes in its walls, so ``port_lostcities_pack.py`` asks this module for a look-alike:

1. the rules of ``content/integrations/private-import/block-lookalikes.json`` (public mods' naming only), in order;
2. for a block of a kept mod that is gone, a sibling of the same mod (the same thing in another wood, colour or number);
3. the block's shape (stairs, slab, wall, fence, door, pane, ...) in its material (oak, spruce, stone, quartz, a colour, ...);
4. air, said in the report.

Every result is checked against a block registry dumped by the real game (``CityProbe --registry``), and the properties a result keeps are
the ones that block has with a value it accepts (a facing, a half, a slab type...). Without a registry nothing can be checked, so nothing is
guessed: only rules whose results are plain vanilla ids are used, and the report says the port was not checked.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "content/integrations/private-import/block-lookalikes.json"

WOODS = ["dark_oak", "oak", "spruce", "birch", "jungle", "acacia", "mangrove", "cherry", "crimson", "warped", "bamboo"]
COLOURS = ["light_blue", "light_gray", "white", "orange", "magenta", "yellow", "lime", "pink", "gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
STONES = ["cobbled_deepslate", "polished_deepslate", "deepslate", "polished_blackstone", "blackstone", "polished_andesite", "andesite", "polished_diorite", "diorite",
          "polished_granite", "granite", "smooth_sandstone", "red_sandstone", "sandstone", "nether_brick", "end_stone", "mossy_cobblestone", "cobblestone", "smooth_stone",
          "stone", "quartz", "purpur", "prismarine", "mud", "tuff", "calcite", "terracotta", "brick", "iron", "gold", "copper", "cut_copper"]
WOODEN_SHAPES = {"fence", "fence_gate", "door", "trapdoor", "planks", "log", "wood", "sign", "vertical_slab"}


def load(path: Path = DATA) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise SystemExit(f"{path}: unsupported schema")
    return data


class Lookalikes:
    def __init__(self, data: dict | None = None, registry: dict | None = None):
        self.data = data or load()
        self.registry = registry
        self.rules = [(re.compile(r["match"]), r["to"]) for r in self.data["rules"]]
        self.keep = set(self.data["keep_properties"])
        self.wood_syn = self.data["wood_synonyms"]
        self.stone_syn = self.data["stone_synonyms"]

    # ------------------------------------------------------------------------------------------------ reading a block id

    def material(self, path: str) -> str | None:
        """The block's wood, colour, stone or metal, read from its id (``fir_planks`` is spruce, ``marble_tiles`` quartz, ``red_sofa`` red)."""
        tokens = path.split("_")
        joined = "_" + path + "_"
        for word, vanilla in sorted({**self.wood_syn, **self.stone_syn}.items(), key=lambda kv: -len(kv[0])):
            if f"_{word}_" in joined:
                return vanilla
        for word in WOODS + COLOURS + STONES:
            if f"_{word}_" in joined:
                return word
        return tokens[0] if tokens and tokens[0] in WOODS else None

    def shape(self, path: str) -> str:
        for shape in self.data["shapes"]:
            if path.endswith("_" + shape) or path == shape or path.startswith(shape + "_") or f"_{shape}_" in f"_{path}_":
                return shape
        return ""

    # ------------------------------------------------------------------------------------------------ checking a candidate

    def known(self, name: str) -> bool:
        return self.registry is None and name.startswith("minecraft:") or self.registry is not None and name in self.registry

    def with_props(self, name: str, props: dict[str, str]) -> str:
        """``name`` with the source's properties it has and accepts (a facing, a half, a slab type...)."""
        if self.registry is None or name not in self.registry:
            return name
        have = self.registry[name]
        kept = {k: v for k, v in props.items() if k in self.keep and k in have and v in have[k]}
        if "persistent" in have and "persistent" not in kept:
            kept["persistent"] = "true"   # leaves in a building never decay
        return name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(kept.items())) + "]" if kept else "")

    # ------------------------------------------------------------------------------------------------ the answer

    def resolve(self, name: str, props: dict[str, str] | None = None, kept_namespace: bool = False) -> tuple[str, str]:
        """(block spec, how) for a block the game does not have: how is 'rule', 'sibling', 'shape' or 'air'."""
        props = props or {}
        ns, path = name.split(":", 1) if ":" in name else ("minecraft", name)
        material = self.material(path)
        shape = self.shape(path)
        for pattern, target in self.rules:
            match = pattern.fullmatch(name)
            if not match:
                continue
            if target == "air":
                return "minecraft:air", "rule"
            candidate = match.expand(target).replace("{shape}", shape).replace("{material}", material or ("oak" if shape in WOODEN_SHAPES else "stone"))
            if self.known(candidate):
                return self.with_props(candidate, props), "rule"
        if kept_namespace and self.registry is not None:
            sibling = self.sibling(ns, path)
            if sibling:
                return self.with_props(sibling, props), "sibling"
        if self.registry is not None:
            fallbacks = self.data["shape_fallbacks"].get(shape, self.data["shape_fallbacks"][""])
            mat = material or ("oak" if shape in WOODEN_SHAPES else "stone")
            for template in fallbacks:
                candidate = "minecraft:" + template.format(material=mat)
                if candidate in self.registry:
                    return self.with_props(candidate, props), "shape"
        return "minecraft:air", "air"

    def sibling(self, ns: str, path: str) -> str | None:
        """The same thing of the same mod in another material or number: ``cfm:granite_chair`` is ``cfm:oak_chair``, ``x:calcite_8`` is ``x:calcite_7``."""
        numbered = re.fullmatch(r"(.*_)(\d+)", path)
        if numbered:
            for n in range(int(numbered.group(2)) - 1, 0, -1):
                if f"{ns}:{numbered.group(1)}{n}" in self.registry:
                    return f"{ns}:{numbered.group(1)}{n}"
        tokens = path.split("_")
        for cut in range(1, len(tokens)):
            rest = "_".join(tokens[cut:])
            for word in ["oak", "spruce", "stripped_oak", "light_gray", "white", "stone", "smooth_stone"] + WOODS + COLOURS:
                if f"{ns}:{word}_{rest}" in self.registry:
                    return f"{ns}:{word}_{rest}"
        return None
