#!/usr/bin/env python3
"""Item-source audit: can a player always get what a work order asks for, without luck and without a contract they cannot yet take?

For every work order the audit takes each required item and asks where it can come from, using only Core's own data (work orders, recipes,
starter kits, regional stock, loot tables). A source is *deterministic* (a starter kit, the regional exchange, a recipe made of obtainable things,
a work order's reward, or an ordinary vanilla item) or *luck* (a rolled loot table). It reports:

* DEAD END    - the item has no source at all in Core's data and is not a vanilla item.
* LUCK ONLY   - the only source is a rolled loot table (D-053: no progression may depend on luck).
* TOO LATE    - the only deterministic source is a reward or recipe that needs a higher career level than the order that asks for it
                (the chicken and the egg).
* UNKNOWN GUN - a gun requirement none of whose guns has an obtainable blueprint.
* EXTERNAL    - (informational, not a failure) the item belongs to another mod that the order itself requires (``requires_mod``), so that mod's own
                production chain is its source; the audit cannot see inside it. These need a real-pack production test, not a data audit.

Run ``python scripts/audit_item_sources.py`` for the report, ``--check`` to fail on any finding (CI), ``--json`` for machine output.
Vanilla items (``minecraft:``) count as obtainable at level 1: the world supplies them. Mods other than Core are taken at their word only where a
requirement names a gun (TaCZ crafts it from vanilla materials at the Gun Smith Table behind a blueprint, D-073).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'rpg-core/src/main/resources/data/underground_city'
INF = 10 ** 6

# Deterministic sources that are code or a block's drops rather than data the audit can read. Each names where to find it; tests/test_item_sources.py checks
# that the named code still exists, so this list cannot rot silently.
CODE_SOURCES = {
    'underground_city:data_ledger': ('SecureTerminalService.java', 'UndergroundCity.item("data_ledger")', 'a successful Technician trace pays one (D-054)'),
}


def load_dir(name: str) -> dict[str, dict]:
    return {path.stem: json.loads(path.read_text()) for path in sorted((DATA / name).glob('*.json'))}


def ingredients(recipe: dict) -> list[list[str]]:
    """Each ingredient as the list of acceptable items (a tag is represented by its name, which is treated as vanilla when it is a minecraft/forge tag)."""
    found: list[list[str]] = []

    def one(entry):
        if isinstance(entry, list):
            options = []
            for sub in entry:
                options += one(sub)
            return options
        if isinstance(entry, dict):
            if 'item' in entry:
                return [entry['item']]
            if 'tag' in entry:
                return ['#' + entry['tag']]
        return []

    if 'ingredients' in recipe:
        for entry in recipe['ingredients']:
            found.append(one(entry))
    if 'key' in recipe:
        used = set(''.join(recipe.get('pattern', [])))
        for symbol, entry in recipe['key'].items():
            if symbol in used:
                found.append(one(entry))
    return [options for options in found if options]


def result_of(recipe: dict) -> str | None:
    result = recipe.get('result')
    if isinstance(result, dict):
        return result.get('item')
    return result if isinstance(result, str) else None


def iter_entries(entries):
    for entry in entries:
        yield entry
        yield from iter_entries(entry.get('children', []))


def requirements(order: dict) -> list[dict]:
    if 'requirements' in order:
        return order['requirements']
    return [{'item': order['item'], 'count': order.get('count', 1)}] if 'item' in order else []


def earliest_levels(orders: dict[str, dict], recipes: dict[str, dict], kits: dict, stock: dict, loot_items: set[str]) -> tuple[dict[str, int], set[str]]:
    """Lowest career level at which each item can be had deterministically, by fixed-point iteration over the recipes."""
    level: dict[str, int] = {}

    def offer(item: str, at: int) -> bool:
        if at < level.get(item, INF):
            level[item] = at
            return True
        return False

    for kit in kits.values():
        for entry in kit['items']:
            offer(entry['item'], 1)
    for entry in stock.values():
        offer(entry['item'], 1)
    for order in orders.values():
        for reward in order.get('rewards', []):
            offer(reward['item'], order['level'])
    for item in CODE_SOURCES:
        offer(item, 1)
    for path in (DATA / 'loot_tables/blocks').glob('*.json'):  # what a crop or a block drops when harvested is a source anyone can reach (planting needs a recipe-made cutting)
        for name in set(entry['name'] for pool in json.loads(path.read_text()).get('pools', []) for entry in iter_entries(pool.get('entries', [])) if 'name' in entry):
            if name.startswith('underground_city:'):
                offer(name, 1)
    changed = True
    while changed:
        changed = False
        for recipe in recipes.values():
            out = result_of(recipe)
            if out is None:
                continue
            need = 1
            for options in ingredients(recipe):
                best = min((1 if option.startswith('minecraft:') or option.startswith('#minecraft:') or option.startswith('#forge:') else level.get(option, INF) for option in options), default=INF)
                need = max(need, best)
            if need < INF and offer(out, need):
                changed = True
    return level, loot_items


def audit() -> dict:
    orders, recipes = load_dir('work_orders'), load_dir('recipes')
    kits, stock = load_dir('starter_kits'), load_dir('regional_stock')
    loot_items: set[str] = set()
    for path in (DATA / 'exceptional_loot').glob('*.json'):
        loot_items |= {entry['item'] for entry in json.loads(path.read_text())['entries']}
    for path in (DATA / 'loot_tables/property').glob('*.json'):
        for pool in json.loads(path.read_text()).get('pools', []):
            loot_items |= {entry['name'] for entry in pool.get('entries', []) if 'name' in entry}
    level, loot_items = earliest_levels(orders, recipes, kits, stock, loot_items)

    # blueprints by gun: a blueprint item carries GunId in its nbt string
    blueprint_level: dict[str, int] = {}

    def note_blueprint(entry: dict, at: int):
        if entry['item'].startswith('taczblueprints:') and 'nbt' in entry:
            gun = entry['nbt'].split('"')[1] if '"' in entry['nbt'] else ''
            blueprint_level[gun] = min(blueprint_level.get(gun, INF), at)

    for kit in kits.values():
        for entry in kit['items']:
            note_blueprint(entry, 1)
    for order in orders.values():
        for reward in order.get('rewards', []):
            note_blueprint(reward, order['level'])

    findings: list[dict] = []
    external: list[dict] = []
    for name, order in orders.items():
        for need in requirements(order):
            item = need['item']
            if item == 'tacz:modern_kinetic_gun' and need.get('gun_ids'):
                reachable = [gun for gun in need['gun_ids'] if blueprint_level.get(gun, INF) <= order['level']]
                if not reachable:
                    findings.append({'order': name, 'career': order['career'], 'level': order['level'], 'kind': 'UNKNOWN GUN', 'item': ','.join(need['gun_ids']),
                                     'detail': 'no listed gun has a blueprint from a starter kit or a reward at or below this level: ' +
                                               str({gun: blueprint_level.get(gun) for gun in need['gun_ids']})})
                continue
            if item.startswith('minecraft:'):
                continue
            have = level.get(item, INF)
            if have == INF and item.split(':')[0] in str(order.get('requires_mod', '')).split(','):
                external.append({'order': name, 'item': item, 'mod': item.split(':')[0]})
                continue
            if have == INF:
                kind = 'LUCK ONLY' if item in loot_items else 'DEAD END'
                findings.append({'order': name, 'career': order['career'], 'level': order['level'], 'kind': kind, 'item': item,
                                 'detail': 'found only in rolled loot' if kind == 'LUCK ONLY' else 'no kit, exchange, recipe or reward produces it'})
            elif have > order['level']:
                findings.append({'order': name, 'career': order['career'], 'level': order['level'], 'kind': 'TOO LATE', 'item': item,
                                 'detail': f'first obtainable at career level {have}, the order needs level {order["level"]}'})
    return {'orders': len(orders), 'recipes': len(recipes), 'findings': findings, 'external': external,
            'items_with_a_deterministic_source': len(level), 'loot_only_items': sorted(i for i in loot_items if i not in level and i.startswith('underground_city:'))}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--check', action='store_true', help='exit 1 on any finding')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args()
    result = audit()
    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"{result['orders']} work orders, {result['recipes']} recipes, {result['items_with_a_deterministic_source']} items with a deterministic source")
        for f in result['findings']:
            print(f"  {f['kind']:<11} {f['career']:<10} L{f['level']:<3} {f['order']:<26} {f['item']}: {f['detail']}")
        if result['loot_only_items']:
            print('Core items found only in rolled loot (fine unless an order needs them):', ', '.join(result['loot_only_items']))
        if result['external']:
            print('Produced by the mod the order itself requires (not auditable here):', ', '.join(sorted({f"{e['item']} ({e['order']})" for e in result['external']})))
        print('findings:', len(result['findings']))
    return 1 if args.check and result['findings'] else 0


if __name__ == '__main__':
    sys.exit(main())
