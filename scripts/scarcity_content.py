#!/usr/bin/env python3
"""The scarcity package: a NEW world has no natural ore (D-120), packaged as the low-code mod ``underground_city_scarcity``.

The hybrid economy of the owner's brief: raw materials come from buyers' and suppliers' stock (Q Shop), from wrecks and salvage in the cities, and from ONE regenerating resource site, not from
digging the ground for diamonds. The mod holds one Forge biome modifier that removes the vanilla ore placed features from every overworld biome. It changes only what the next generated chunk is, so an
existing world must never take part (``GenerationPackGuard`` refuses it); it never touches a block that already exists, so no existing ore is ever removed or replaced.

The feature list is vanilla Minecraft 1.20.1's own ids; the ones that exist in the game are checked by ``OreAudit`` on a generated world (``scripts/run_ore_audit.py``): that is the evidence, not this list.

    python scripts/emit_dev_world_pack.py --layout scarcity        write content/generated/world-scarcity
    python scripts/emit_dev_world_pack.py --layout scarcity --check    fail when it is out of date
"""
from __future__ import annotations

import json

MOD_ID = "underground_city_scarcity"
NAMESPACE = "underground_city"

# Every vanilla overworld ore placed feature that yields a material the economy sells (coal, iron, copper, gold, redstone, lapis, diamond, emerald) and the infested stone that spawns silverfish
# (a hostile creature, D-094). Stone variants (granite, diorite, andesite, tuff), dirt, gravel, clay and sand are building materials and stay.
REMOVED_PLACED_FEATURES = [
    "ore_coal_upper", "ore_coal_lower",
    "ore_iron_upper", "ore_iron_middle", "ore_iron_small",
    "ore_copper", "ore_copper_large",
    "ore_gold", "ore_gold_lower", "ore_gold_extra",
    "ore_redstone", "ore_redstone_lower",
    "ore_lapis", "ore_lapis_buried",
    "ore_diamond", "ore_diamond_large", "ore_diamond_buried",
    "ore_emerald",
    "ore_infested",
]


def json_bytes(document) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8")


def package() -> dict[str, bytes]:
    files: dict[str, bytes] = {}
    files["META-INF/mods.toml"] = (
        'modLoader="lowcodefml"\nloaderVersion="[47,)"\nlicense="All Rights Reserved"\n\n[[mods]]\n'
        f'modId="{MOD_ID}"\nversion="0.1.0"\n'
        'displayName="Underground City: Scarce Natural Ore"\n'
        'description="A new world has no natural ore: materials come from suppliers, salvage and one regenerating resource site."\n'
        f'\n[[dependencies.{MOD_ID}]]\nmodId="{NAMESPACE}"\nmandatory=true\nversionRange="[0.1.0,)"\nordering="NONE"\nside="BOTH"\n'
    ).encode()
    files["pack.mcmeta"] = json_bytes({"pack": {"pack_format": 15, "description": "Underground City scarce natural ore"}})
    files[f"data/{NAMESPACE}/forge/biome_modifier/no_natural_ore.json"] = json_bytes({
        "type": "forge:remove_features",
        "biomes": "#minecraft:is_overworld",
        "features": [f"minecraft:{name}" for name in REMOVED_PLACED_FEATURES],
        "steps": ["underground_ores"],
    })
    return files


if __name__ == "__main__":
    for name in sorted(package()):
        print(name)
