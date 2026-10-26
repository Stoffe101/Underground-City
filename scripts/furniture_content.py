#!/usr/bin/env python3
"""The furniture set's quiet package (D-192): a low-code mod that keeps the decorative furniture mods decorative.

EmbellishCraft, one of the furniture set's mods (``content/modpacks/dev-pack.json``, set ``furniture``), adds six decorative stone blobs to every
overworld biome through its own biome modifiers and has no configuration switch for them. A new world of this game has its materials from
suppliers, salvage and resource sites (D-120), so this package replaces those six files with ``forge:none``. It orders itself after
EmbellishCraft, so its data wins; without EmbellishCraft its files name nothing and do nothing. Gradle stages it whenever the furniture set
is chosen. Written by ``scripts/emit_dev_world_pack.py --layout furniture``.
"""
from __future__ import annotations

import json

MOD_ID = "underground_city_furniture"
NAMESPACE = "underground_city"
# EmbellishCraft 1.20.1-7.0.0.0's own biome modifiers (read from the pinned jar): data/embellishcraft/forge/biome_modifier/<name>.json
QUIETED = {"embellishcraft": ["add_basalt_ore", "add_gneiss_ore", "add_jade_ore", "add_larvikite_ore", "add_marble_ore", "add_slate_ore"]}


def json_bytes(document) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8")


def package() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    toml = (
        'modLoader="lowcodefml"\nloaderVersion="[47,)"\nlicense="All Rights Reserved"\n\n[[mods]]\n'
        f'modId="{MOD_ID}"\nversion="0.1.0"\n'
        'displayName="Underground City: Quiet Furniture"\n'
        'description="Keeps the furniture mods decorative: their natural stone generation is switched off."\n'
        f'\n[[dependencies.{MOD_ID}]]\nmodId="{NAMESPACE}"\nmandatory=true\nversionRange="[0.1.0,)"\nordering="NONE"\nside="BOTH"\n'
    )
    for mod in sorted(QUIETED):
        toml += f'\n[[dependencies.{MOD_ID}]]\nmodId="{mod}"\nmandatory=false\nversionRange="*"\nordering="AFTER"\nside="BOTH"\n'
    files["META-INF/mods.toml"] = toml.encode()
    files["pack.mcmeta"] = json_bytes({"pack": {"pack_format": 15, "description": "Underground City quiet furniture"}})
    for mod, names in sorted(QUIETED.items()):
        for name in names:
            files[f"data/{mod}/forge/biome_modifier/{name}.json"] = json_bytes({"type": "forge:none"})
    return files


if __name__ == "__main__":
    for name in sorted(package()):
        print(name)
