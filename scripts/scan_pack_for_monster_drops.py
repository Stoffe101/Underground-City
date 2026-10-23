#!/usr/bin/env python3
"""Static scan of pinned mod files for hostile-creature drops (D-095).

ProgressionAuditGameTests reads the recipes that the game loads. Immersive Vehicles does not keep its materials in recipes: its packs list them inside part and item
definitions (JSON files in the pack jar), so the game test cannot see them. This script reads every JSON file of the jars it is given, finds references to the roots of
data/underground_city/progression/monster_resources.json (by item name or by the Forge tag that stands for the item) and prints which mod needs which root. ``--check`` fails
when a referenced root has no route and the (mod, root) pair is not in ``ALLOWED`` with a reason.

    python scripts/scan_pack_for_monster_drops.py --dir <folder with the pinned jars>
    python scripts/scan_pack_for_monster_drops.py --gradle-cache --check

The jars are the author-hosted, hash-pinned files that scripts/fetch_dev_pack.py or Gradle download; nothing is copied into the repository.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOGUE = ROOT / "rpg-core/src/main/resources/data/underground_city/progression/monster_resources.json"
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1/maven.modrinth"
# Forge tags that stand for a root item
TAGS = {"forge:gunpowder": "minecraft:gunpowder", "forge:string": "minecraft:string", "forge:rods/blaze": "minecraft:blaze_rod", "forge:bones": "minecraft:bone", "forge:slimeballs": "minecraft:slime_ball",
        "forge:ender_pearls": "minecraft:ender_pearl", "forge:nether_stars": "minecraft:nether_star", "forge:eggs": None}
# (jar name fragment, root): references that are known and not progression, each with the reason. Everything else without a route fails the check.
ALLOWED = [
    ("immersive-vehicles-official-content-pack", "minecraft:nether_star", "a cosmetic hood ornament (mirror ornament) of the official content pack"),
    ("modern-life", "minecraft:prismarine_shard", "prismarine furniture variants (decoration; the same furniture exists in other materials)"),
    ("farmers-delight", "minecraft:rotten_flesh", "dog food and one compost recipe; nothing the game offers as progression needs it"),
    ("lets-do-farm-charm", "minecraft:rotten_flesh", "fertilizer, which has another recipe"),
    ("lost-cities-modern-tweaks", "minecraft:rotten_flesh", "one entry of the basic food-shop chest loot table (spoiled food as set dressing); no recipe or order needs it"),
    ("farmers-delight", "minecraft:shulker_shell", "a loot-modifier file that lets shulkers drop shells; shulkers do not exist in the simulation world and nothing needs the shell"),
    ("farmers-delight", "minecraft:trident", "a cutting-board tool entry (a trident lying flat), not an ingredient"),
    ("security-craft", "minecraft:nether_star", "the codebreaker and the level-3 reinforcer; the experimental trial set removes every SecurityCraft recipe while it is loaded (D-140)"),
]
POLICY = ROOT / "rpg-core/src/main/resources/data/underground_city/backpack_policy/policy.json"
RETIRED = ("travelersbackpack",)   # the legacy_travelers set (D-092): never loaded with the survival set


def load_catalogue() -> dict:
    return json.loads(CATALOGUE.read_text(encoding="utf-8"))


def patterns(roots: dict) -> list[tuple[re.Pattern, str]]:
    found = [(re.compile(r'"' + re.escape(root) + r'(?::\d+(?::\d+)?)?"'), root) for root in roots]
    found += [(re.compile(r'"' + re.escape(tag) + r'"'), root) for tag, root in TAGS.items() if root]
    # Immersive Vehicles writes a material as "minecraft:blaze_powder:0:5" inside strings, anywhere in a list
    found += [(re.compile(re.escape(root) + r":\d+:\d+"), root) for root in roots]
    return found


def scan(jar: Path, roots: dict) -> dict[str, set[str]]:
    hits: dict[str, set[str]] = defaultdict(set)
    compiled = patterns(roots)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith(".json") or name.startswith("assets/minecraft/") or "/lang/" in name or "/blockstates/" in name or "/models/" in name or "/advancements/" in name:
                continue
            text = archive.read(name).decode("utf-8", "ignore")
            for pattern, root in compiled:
                if pattern.search(text):
                    hits[root].add(name.rsplit("/", 1)[-1][:-5])
    return hits


def find_jars(args) -> list[Path]:
    if args.dir:
        return sorted(Path(args.dir).glob("*.jar"))
    if args.gradle_cache:
        return sorted(CACHE.glob("*/*/*/*.jar"))
    raise SystemExit("give --dir <folder> or --gradle-cache")


def report(jars: list[Path], catalogue: dict, only: str | None = None) -> tuple[dict, list[str]]:
    roots = catalogue["roots"]
    matrix: dict[str, dict[str, set[str]]] = defaultdict(dict)
    for jar in jars:
        mod = jar.parent.parent.parent.name if jar.parent.parent.parent.parent == CACHE else jar.stem
        if only and only not in mod:
            continue
        for root, files in scan(jar, roots).items():
            matrix[mod][root] = files
    problems = []
    disabled = {item.split(":")[1] for item in json.loads(POLICY.read_text(encoding="utf-8"))["disabled"]} if POLICY.is_file() else set()
    for mod, per_root in matrix.items():
        if mod in RETIRED:
            continue
        for root, files in per_root.items():
            if roots[root].get("route"):
                continue
            if mod.startswith("sophisticated"):
                files = {name for name in files if name not in disabled}   # the policy switches those items and their recipes off
                if not files:
                    continue
            if any(fragment in mod and root == allowed for fragment, allowed, _why in ALLOWED):
                continue
            problems.append(f"{mod} references {root}, which has no route: {sorted(files)[:5]}")
    return matrix, problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir")
    parser.add_argument("--gradle-cache", action="store_true")
    parser.add_argument("--only", help="a fragment of the mod name, e.g. immersive")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    matrix, problems = report(find_jars(args), load_catalogue(), args.only)
    for mod in sorted(matrix):
        print(mod)
        for root in sorted(matrix[mod]):
            files = sorted(matrix[mod][root])
            print(f"   {root}: {len(files)} file(s) {files[:6]}{' ...' if len(files) > 6 else ''}")
    for line in problems:
        print("PROBLEM:", line)
    return 1 if args.check and problems else 0


if __name__ == "__main__":
    sys.exit(main())
