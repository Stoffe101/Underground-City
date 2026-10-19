#!/usr/bin/env python3
"""Generate the Sophisticated Backpacks configuration and Core's copy of the backpack policy (D-092) from content/gameplay/backpack-policy.json.

The policy is the one place that says which backpack tiers and upgrades exist in Underground City and how big they are. This script writes

* ``content/generated/survival-config/defaultconfigs/sophisticatedbackpacks-server.toml``: the server configuration every NEW world starts from (capacity, nerfs, upgrade limits,
  no mobs with backpacks, nobody opens another player's worn pack, no item fluid handler);
* ``content/generated/survival-config/config/sophisticatedcore-common.toml``: Sophisticated Core's ``enabledItems`` list, which switches an item and its recipe on or off;
* ``content/generated/survival-config/config/sophisticatedbackpacks-common.toml``: no backpack loot in vanilla chests;
* ``rpg-core/src/main/resources/data/underground_city/backpack_policy/policy.json``: what Core's BackpackPolicyEnforcer and the GameTests read.

The staged files are copied into a run directory or a server pack **only when absent** (the development launch, ``make_server_pack.py``); a world that already exists keeps
the configuration it was created with (see docs/technical/backpack-economy.md).

    python scripts/generate_backpack_config.py          write
    python scripts/generate_backpack_config.py --check  fail if the committed files differ
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "content/gameplay/backpack-policy.json"
OUT_DIR = ROOT / "content/generated/survival-config"
CORE_COPY = ROOT / "rpg-core/src/main/resources/data/underground_city/backpack_policy/policy.json"
# the mod's own defaults (read from a real server's generated configuration, Sophisticated Backpacks 3.26.12): a policy may shrink them, never exceed them
MOD_DEFAULTS = {
    "leatherBackpack": (27, 1), "copperBackpack": (45, 1), "ironBackpack": (54, 2),
    "goldBackpack": (81, 3), "diamondBackpack": (108, 5), "netheriteBackpack": (120, 7),
}
ITEM = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")


def load() -> dict:
    return json.loads(SOURCE.read_text(encoding="utf-8"))


def problems(policy: dict) -> list[str]:
    found: list[str] = []
    provider = policy["provider"]["backpacks"]
    tiers = policy["tiers"]
    if set(tiers) != set(MOD_DEFAULTS):
        found.append(f"tiers must be exactly {sorted(MOD_DEFAULTS)}")
    last_slots, last_up = 0, 0
    for name in MOD_DEFAULTS:
        tier = tiers.get(name)
        if not tier:
            continue
        slots, ups = tier["inventorySlotCount"], tier["upgradeSlotCount"]
        if slots > MOD_DEFAULTS[name][0] or ups > MOD_DEFAULTS[name][1]:
            found.append(f"{name}: {slots} slots / {ups} upgrade slots exceed the mod's default {MOD_DEFAULTS[name]}; a policy may only shrink them")
        if slots <= last_slots or ups < last_up:
            found.append(f"{name}: each tier must hold more than the one before it (and never fewer upgrade slots)")
        last_slots, last_up = slots, ups
        if not tier.get("why"):
            found.append(f"{name}: say why")
        if not ITEM.fullmatch(tier["item"]):
            found.append(f"{name}: bad item id")
    seen: dict[str, str] = {}
    for status in ("enabled", "disabled"):
        for entry in policy["items"][status]:
            item = entry["item"]
            if not ITEM.fullmatch(item) or not item.startswith(provider + ":"):
                found.append(f"{item}: must be an item of {provider}")
            if item in seen:
                found.append(f"{item}: listed as both {seen[item]} and {status}" if seen[item] != status else f"{item}: listed twice")
            seen[item] = status
            if not entry.get("why"):
                found.append(f"{item}: say why")
            if "create" in item.split(":")[0]:
                found.append(f"{item}: the Create mod is not used anywhere in this project")
    for name, tier in tiers.items():
        if seen.get(tier["item"]) != "enabled":
            found.append(f"{name}: the tier's item {tier['item']} must be enabled")
    server = policy["server"]
    if server["entityBackpackAdditions"]["chance"] != 0.0:
        found.append("mobs must never spawn with backpacks (chance 0.0): a backpack is made or bought, not farmed (D-093)")
    if server["allowOpeningOtherPlayerBackpacks"] is not False:
        found.append("allowOpeningOtherPlayerBackpacks must be false")
    if server["itemFluidHandlerEnabled"] is not False:
        found.append("itemFluidHandlerEnabled must be false (the mod warns of duplication)")
    if server["tickDedupeLogicDisabled"] is not False:
        found.append("tickDedupeLogicDisabled must be false (the duplicate-contents check must stay on)")
    if policy["common"]["chestLootEnabled"] is not False:
        found.append("chestLootEnabled must be false")
    for entry in server["maxUpgradesPerStorage"]:
        if not re.fullmatch(r"[a-z0-9_/.-]+\|\d+", entry):
            found.append(f"maxUpgradesPerStorage entry {entry!r} is not 'name|number'")
    return found


def toml_value(value) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        return "[" + ", ".join(toml_value(v) for v in value) + "]"
    return json.dumps(value)


def server_toml(policy: dict) -> str:
    s = policy["server"]
    lines = ["# Generated by scripts/generate_backpack_config.py from content/gameplay/backpack-policy.json (D-092). Do not edit by hand.",
             "# This is the DEFAULT for a new world; an existing world keeps the file in its serverconfig folder.",
             "[server]"]
    for key in ("allowOpeningOtherPlayerBackpacks", "itemFluidHandlerEnabled", "tickDedupeLogicDisabled", "canBePlacedInContainerItems", "containerItemsDisallowed", "maxUpgradesPerStorage"):
        lines.append(f"\t{key} = {toml_value(s[key])}")
    for name in MOD_DEFAULTS:
        tier = policy["tiers"][name]
        lines += [f"\t[server.{name}]", f"\t\tinventorySlotCount = {tier['inventorySlotCount']}", f"\t\tupgradeSlotCount = {tier['upgradeSlotCount']}"]
    lines.append("\t[server.nerfs]")
    for key, value in s["nerfs"].items():
        lines.append(f"\t\t{key} = {toml_value(value)}")
    lines.append("\t[server.entityBackpackAdditions]")
    for key, value in s["entityBackpackAdditions"].items():
        lines.append(f"\t\t{key} = {toml_value(value)}")
    lines.append("\t[server.magnetUpgrade]")
    lines.append(f"\t\tmagnetRange = {toml_value(s['magnetUpgrade']['magnetRange'])}")
    return "\n".join(lines) + "\n"


def core_common_toml(policy: dict) -> str:
    entries = [f"{e['item']}|true" for e in policy["items"]["enabled"]] + [f"{e['item']}|false" for e in policy["items"]["disabled"]]
    return ("# Generated by scripts/generate_backpack_config.py from content/gameplay/backpack-policy.json (D-092). Do not edit by hand.\n"
            "#Common Settings\n[common]\n\t#Disable / enable any items here (disables their recipes)\n\tenabledItems = " + toml_value(entries) + "\n")


def backpacks_common_toml(policy: dict) -> str:
    return ("# Generated by scripts/generate_backpack_config.py from content/gameplay/backpack-policy.json (D-092). Do not edit by hand.\n"
            "#Common Settings\n[common]\n\t#Turns on/off loot added to various vanilla chest loot tables\n\tchestLootEnabled = " + toml_value(policy["common"]["chestLootEnabled"]) + "\n")


def core_copy(policy: dict) -> str:
    server = policy["server"]
    doc = {
        "schema_version": 1,
        "provider": policy["provider"],
        "tiers": {name: {"item": t["item"], "inventorySlotCount": t["inventorySlotCount"], "upgradeSlotCount": t["upgradeSlotCount"]} for name, t in policy["tiers"].items()},
        "enabled": [e["item"] for e in policy["items"]["enabled"]],
        "disabled": [e["item"] for e in policy["items"]["disabled"]],
        "server": {key: server[key] for key in ("allowOpeningOtherPlayerBackpacks", "itemFluidHandlerEnabled", "tickDedupeLogicDisabled", "canBePlacedInContainerItems", "containerItemsDisallowed", "maxUpgradesPerStorage")},
        "nerfs": server["nerfs"],
        "mob_backpack_chance": server["entityBackpackAdditions"]["chance"],
        "magnet_range": server["magnetUpgrade"]["magnetRange"],
        "chest_loot": policy["common"]["chestLootEnabled"],
    }
    return json.dumps(doc, indent=2) + "\n"


def outputs(policy: dict) -> dict[Path, str]:
    return {
        OUT_DIR / "defaultconfigs/sophisticatedbackpacks-server.toml": server_toml(policy),
        OUT_DIR / "config/sophisticatedcore-common.toml": core_common_toml(policy),
        OUT_DIR / "config/sophisticatedbackpacks-common.toml": backpacks_common_toml(policy),
        CORE_COPY: core_copy(policy),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    policy = load()
    found = problems(policy)
    if found:
        print("Backpack policy problems:\n - " + "\n - ".join(found), file=sys.stderr)
        return 1
    files = outputs(policy)
    if args.check:
        stale = [str(path.relative_to(ROOT)) for path, text in files.items() if not path.exists() or path.read_text(encoding="utf-8") != text]
        if stale:
            print("Stale generated backpack files (run scripts/generate_backpack_config.py): " + ", ".join(stale), file=sys.stderr)
            return 1
        print(f"Checked {len(files)} backpack configuration files")
        return 0
    for path, text in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    print(f"Wrote {len(files)} backpack configuration files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
