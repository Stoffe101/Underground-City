#!/usr/bin/env python3
"""Stage original Underground City civilian Q Shop templates without overwriting saves.

Q Shop itself MUST be obtained from its original author. This only copies
our authored JSON to <server_root>/config/qshop for first world initialization.
For existing worlds QShop reads world/serverconfig/qshop: manual migration
and explicit backups are required instead of silently stomping live shops.
"""
from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "content/integrations/qshop/config/qshop"
RELATIVE_FILES = ("currencies.json", "shops/uc_starter_supply.json")


def verify_templates(base: Path = TEMPLATE) -> list[str]:
    errors: list[str] = []
    currency_file = base / RELATIVE_FILES[0]
    shop_file = base / RELATIVE_FILES[1]
    currency = json.loads(currency_file.read_text(encoding="utf-8"))
    shop = json.loads(shop_file.read_text(encoding="utf-8"))
    currencies = currency.get("currencies", [])
    if not any(x.get("id") == "credits" for x in currencies):
        errors.append("Underground City credits currency missing")
    if shop.get("id") != "uc_starter_supply" or shop.get("currency") != "credits":
        errors.append("Starter shop must use the fixed, server-whitelisted ID and credits")
    entries = shop.get("entries", [])
    if not entries or not any(x.get("type") == "BUY" for x in entries) or not any(
        x.get("type") == "SELL" for x in entries
    ):
        errors.append("Starter shop needs ordinary buy and sell entries")
    all_items: dict[str, dict[str, int]] = {}
    for row in entries:
        ident = row.get("item", {}).get("item")
        qty = row.get("item", {}).get("count")
        price = row.get("price")
        operation = row.get("type")
        if (operation not in ("BUY", "SELL") or
            not isinstance(ident, str) or not ident.startswith("minecraft:") or
            type(qty) is not int or qty <= 0 or
            type(price) is not int or price <= 0 or
            row.get("currency") != "credits"):
            errors.append(f"Invalid civilian entry: {row}")
            continue
        if any(term in ident for term in ("diamond", "netherite", "gun", "weapon")):
            errors.append(f"Advanced gear must not enter the starter shop: {ident}")
        if (type(row.get("globalLimit")) is not int or row["globalLimit"] <= 0 or
            type(row.get("playerLimit")) is not int or row["playerLimit"] <= 0 or
            row.get("limitReset") != "DAILY"):
            errors.append(f"Starter demand must be bounded with DAILY world-time limits: {ident}")
        all_items.setdefault(ident, {})[operation] = price / qty
    for item, values in all_items.items():
        if "BUY" in values and "SELL" in values and values["BUY"] <= values["SELL"]:
            errors.append(f"Arbitrage exploit: {item} sell price >= buy price")
    return errors


def install(server_root: Path, source: Path = TEMPLATE) -> dict:
    issues = verify_templates(source)
    if issues:
        raise ValueError("Invalid original QShop templates: " + "; ".join(issues))
    dest = server_root / "config/qshop"
    # Preserve renamed dedicated worlds and client saves as well as world/.
    existing_live = list(server_root.glob("**/serverconfig/qshop"))
    if existing_live:
        raise ValueError(
            "Existing world QShop configuration found. Back up and review "
            "serverconfig/qshop first; starter templates cannot overwrite live markets."
        )
    operations = []
    for rel in RELATIVE_FILES:
        src = source / rel
        dst = dest / rel
        if dst.exists():
            if rel == "currencies.json":
                cur = json.loads(dst.read_text(encoding="utf-8"))
                if not any(c.get("id") == "credits" for c in cur.get("currencies", [])):
                    raise ValueError(
                        "Existing QShop currency template lacks 'credits'. "
                        "Back up and merge manually; nothing overwritten."
                    )
            operations.append({"file": rel, "operation": "preserved"})
            continue
        operations.append({"file": rel, "operation": "created"})
    # Validate all before mutating anything. Never overwrite user custom content.
    for row in operations:
        if row["operation"] == "created":
            src = source / row["file"]
            dst = dest / row["file"]
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
    return {"provider": "qshop", "status": "original templates only", "operations": operations}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-root", type=Path, required=True)
    parser.add_argument("--source", type=Path, default=TEMPLATE)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    if args.check_only:
        issues = verify_templates(args.source)
        if issues:
            raise SystemExit("\n".join(issues))
        print("Original QShop starter templates validated (no files installed)")
        return 0
    print(json.dumps(install(args.server_root, args.source), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
