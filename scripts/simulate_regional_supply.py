#!/usr/bin/env python3
"""Validate and simulate recurring inter-city procurement without Minecraft.

Everything here is a pure, deterministic design-model proof. This script does
not register Forge recipes/items, place merchants, inspect player coordinates,
or connect to any live economy, faction, locking or NPC APIs.
"""
from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_FILE = ROOT / "content/progression/recurring-supply.json"
CITIES_FILE = ROOT / "content/region-blueprints/cities.json"
CAREERS_FILE = ROOT / "content/onboarding/catalog.json"
SPECIALTIES_FILE = ROOT / "content/progression/intercity-supply.json"


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def validate(
    data: dict, city_data: dict, career_data: dict, specialties_data: dict
) -> list[str]:
    errors: list[str] = []
    if data.get("schema_version") != 1 or data.get("prototype_only") is not True:
        errors.append("Recurring supply must be explicitly prototype-only schema 1")
    if data.get("clock") != "server_active_minutes":
        errors.append("Supply economy must use active server minutes, not offline time")
    cities = {c["id"] for c in city_data["cities"]}
    professions = {c["id"] for c in career_data["professions"]}
    exports = {c["city"]: set(c["exports"]) for c in specialties_data["city_specialties"]}
    if set(exports) != cities:
        errors.append("Supply origins differ from original three-city specialty list")
    policy = data.get("supply_policy", {})
    for field in (
        "local_basic_minecraft_materials_ungated",
        "specialty_output_city_exclusive",
        "supplier_stock_shared_by_players",
        "no_idle_offline_penalty",
        "restock_only_in_response_to_bounded_active_time",
        "no_infinite_remote_delivery",
        "first_tier_two_unlock_requires_foreign_visit",
        "tier_three_requires_visit_all_three",
        "remote_import_available_only_after_qualifying_foreign_visit",
        "remote_import_more_expensive_and_limited",
        "never_one_trip_per_single_craft",
        "allow_batched_materials_and_multiple_crafts_per_expedition",
        "peaceful_foreign_market_access_possible_with_permit_or_escort",
    ):
        if policy.get(field) is not True:
            errors.append(f"Required accessibility/economy policy missing: {field}")

    suppliers = data.get("supplier_stock", [])
    by_id: dict[str, dict] = {}
    for supplier in suppliers:
        ident = supplier["id"]
        if ident in by_id:
            errors.append(f"Duplicate supplier for {ident}")
        by_id[ident] = supplier
        if ident not in exports.get(supplier["city"], set()):
            errors.append(f"{ident} is not an export of {supplier['city']}")
        for key in ("initial_stock", "capacity", "units_per_interval", "interval_minutes",
                    "unit_price", "lot_size_hint"):
            n = supplier.get(key)
            if type(n) is not int or n <= 0:
                errors.append(f"{ident}: expected positive integer {key}")
        if supplier.get("capacity", 0) < supplier.get("initial_stock", 0):
            errors.append(f"{ident}: initial stock exceeds maximum")
        if supplier.get("lot_size_hint", 0) > supplier.get("initial_stock", 0):
            errors.append(f"{ident}: recommended batch exceeds available initial stock")
        if supplier.get("interval_minutes", 0) < 20:
            errors.append(f"{ident}: supplier refills too rapidly")
    expected = {item for city in exports.values() for item in city}
    if set(by_id) != expected:
        errors.append("Supplier list does not cover exactly the nine exclusive city exports")
    recipes = data.get("recurring_recipes", [])
    if len(recipes) < 2 * len(professions):
        errors.append("Every career requires several repeatable procurement sinks")
    recipe_ids = set()
    output_ids = set()
    by_career = {p: set() for p in professions}
    for recipe in recipes:
        name = recipe["id"]
        if name in recipe_ids:
            errors.append(f"Duplicate recurring recipe id: {name}")
        recipe_ids.add(name)
        owner = recipe["career"]
        if owner not in professions:
            errors.append(f"Recipe {name} has unknown career {owner}")
            continue
        tier = recipe["tier"]
        if type(tier) is not int or tier not in (2, 3):
            errors.append(f"{name}: repeatable advanced recipe must be tier 2 or 3")
        by_career[owner].add(tier)
        if recipe.get("repeatable") is not True:
            errors.append(f"{name}: recipe must remain repeatable")
        if recipe.get("available_after_tier_unlock") is not True:
            errors.append(f"{name}: no known ability unlock")
        out = recipe["output"]
        if out["id"] in output_ids:
            errors.append(f"{name}: duplicate logical output ID")
        output_ids.add(out["id"])
        if type(out.get("count")) is not int or not 1 <= out["count"] <= 16:
            errors.append(f"{name}: invalid output count")
        inputs = recipe.get("inputs", [])
        cities_used = set()
        commodities = set()
        for row in inputs:
            commodity = row["commodity"]
            source = by_id.get(commodity)
            if source is None:
                errors.append(f"{name}: unknown input {commodity}")
                continue
            cities_used.add(source["city"])
            if commodity in commodities:
                errors.append(f"{name}: duplicate input {commodity}")
            commodities.add(commodity)
            if type(row.get("count")) is not int or not 1 <= row["count"] <= 16:
                errors.append(f"{name}: invalid input count")
            if row["count"] > source["lot_size_hint"]:
                errors.append(f"{name}: one batch exceeds plausible source lot for {commodity}")
        if len(cities_used) < (3 if tier == 3 else 2):
            errors.append(f"{name}: must consume supplies from multiple distinct cities")
    for career, tiers in by_career.items():
        if tiers != {2, 3}:
            errors.append(f"{career}: requires both tier 2 and tier 3 repeatable sinks")
    channels = {c["id"]: c for c in data.get("travel_channels", [])}
    direct = channels.get("direct_foreign_supplier", {})
    remote = channels.get("licensed_convoy_import", {})
    if direct.get("requires_player_at_source_city") is not True:
        errors.append("Direct purchase must be tied to physical city arrival")
    if type(direct.get("max_quantity_per_order")) is not int or not 2 <= direct["max_quantity_per_order"] <= 16:
        errors.append("Direct purchase lot size must permit practical batch crafting")
    if (remote.get("requires_prior_city_visit") is not True or
            remote.get("price_multiplier_percent", 0) <= direct.get("price_multiplier_percent", 100) or
            remote.get("max_quantity_per_order", 100) >= direct.get("max_quantity_per_order", 0) or
            remote.get("implementation_status") != "future"):
        errors.append("Remote supplier bypass must remain expensive, throttled and future-only")
    if any(c.get("implementation_status") not in (None, "future")
           for c in channels.values() if c["id"] != "direct_foreign_supplier"):
        errors.append("Unsupported remote or illegal mechanics must not claim implemented status")
    return errors


@dataclass(frozen=True)
class Supplier:
    quantity: int
    last_refill_minute: int


class SupplySimulation:
    """Serializable server-authoritative *model* with atomic purchase/craft semantics.

    The real Minecraft adapter must verify player coordinates, permission, stock
    ownership, transaction IDs, and use a serialized server thread/lock.
    """

    def __init__(self, data: dict):
        self.data = data
        self.spec = {x["id"]: x for x in data["supplier_stock"]}
        self.recipes = {x["id"]: x for x in data["recurring_recipes"]}
        self.active_minute = 0
        self.suppliers = {
            ident: Supplier(spec["initial_stock"], 0)
            for ident, spec in self.spec.items()
        }
        self.players: dict[str, dict] = {}
        self.transactions: dict[str, dict] = {}
        self.supplier_revenue: dict[str, int] = {
            ident: 0 for ident in self.spec
        }

    def register(self, player: str, city: str, career: str, credits: int = 1200) -> None:
        if not player or player in self.players:
            raise ValueError("A player can register only once")
        if city not in {s["city"] for s in self.spec.values()}:
            raise ValueError("Unknown home city")
        if career not in {r["career"] for r in self.recipes.values()}:
            raise ValueError("Unknown profession")
        if type(credits) is not int or credits < 0:
            raise ValueError("Invalid starting credit amount")
        self.players[player] = {
            "home": city, "city": city, "career": career,
            "visited": [city], "tier": 1, "credits": credits,
            "inventory": {},
        }

    def travel(self, player: str, city: str, *, crossing_approved: bool) -> None:
        """Called only after a future server adapter proves the player arrived."""
        actor = self.players[player]
        if city not in {s["city"] for s in self.spec.values()}:
            raise ValueError("Unknown destination")
        if city != actor["home"] and crossing_approved is not True:
            raise PermissionError("Foreign city gate not approved")
        actor["city"] = city
        if city not in actor["visited"]:
            actor["visited"].append(city)

    def unlock_tier(self, player: str, tier: int, *, external_quest_verified: bool) -> None:
        """Do not grant tiers here unless authoritative future quest code verified it."""
        actor = self.players[player]
        if external_quest_verified is not True:
            raise PermissionError("Must validate actual in-game career progression")
        if tier != actor["tier"] + 1 or tier not in (2, 3):
            raise ValueError("Only consecutive career tiers may unlock")
        visited = set(actor["visited"])
        if tier == 2 and visited == {actor["home"]}:
            raise PermissionError("Tier 2 requires personal travel outside home city")
        if tier == 3 and len(visited) < 3:
            raise PermissionError("Tier 3 requires visiting all three cities")
        actor["tier"] = tier

    def advance_active_time(self, minutes: int, *, players_online: bool) -> None:
        if type(minutes) is not int or minutes < 0:
            raise ValueError("Active time must be nonnegative integer minutes")
        if players_online is True:
            self.active_minute += minutes

    def available(self, commodity: str) -> Supplier:
        spec = self.spec[commodity]
        stock = self.suppliers[commodity]
        if stock.quantity == spec["capacity"]:
            new_stock = Supplier(stock.quantity, self.active_minute)
        else:
            elapsed = self.active_minute - stock.last_refill_minute
            intervals = elapsed // spec["interval_minutes"]
            if intervals <= 0:
                return stock
            # Cap catch-up so loading an abandoned chunk is not an infinite mine.
            cycles = min(intervals, 8)
            qty = min(spec["capacity"], stock.quantity + cycles * spec["units_per_interval"])
            new_stock = Supplier(qty, self.active_minute - elapsed % spec["interval_minutes"])
        self.suppliers[commodity] = new_stock
        return new_stock

    def _idempotent(self, transaction: str, signature: list) -> dict | None:
        if not isinstance(transaction, str) or not transaction.strip():
            raise ValueError("Missing durable transaction ID")
        previous = self.transactions.get(transaction)
        if previous:
            if previous["signature"] != signature:
                raise ValueError("Transaction ID reused for a different purchase/craft")
            return {"status": "duplicate", **previous["outcome"]}
        return None

    def _commit(self, transaction: str, signature: list, result: dict) -> dict:
        self.transactions[transaction] = {
            "signature": signature, "outcome": dict(result)
        }
        return {"status": "applied", **result}

    def purchase(self, player: str, commodity: str, count: int, transaction: str,
                 *, permit_verified: bool) -> dict:
        signature = ["purchase", player, commodity, count, permit_verified]
        prior = self._idempotent(transaction, signature)
        if prior is not None:
            return prior
        actor = self.players[player]
        spec = self.spec[commodity]
        max_lot = self.data["travel_channels"][0]["max_quantity_per_order"]
        if type(count) is not int or not 1 <= count <= max_lot:
            raise ValueError("Procurement lot is invalid")
        if actor["city"] != spec["city"] or permit_verified is not True:
            raise PermissionError("Must physically reach licensed source with access")
        value = count * spec["unit_price"]
        if actor["credits"] < value:
            raise ValueError("Insufficient cash")
        current = self.available(commodity)
        if current.quantity < count:
            raise ValueError("Supplier stock is temporarily depleted")
        self.suppliers[commodity] = Supplier(current.quantity - count, current.last_refill_minute)
        actor["credits"] -= value
        actor["inventory"][commodity] = actor["inventory"].get(commodity, 0) + count
        self.supplier_revenue[commodity] += value
        return self._commit(transaction, signature, {
            "commodity": commodity, "count": count,
            "paid": value, "origin": spec["city"]
        })

    def shopping_list(self, player: str, recipe_id: str, batches: int = 1) -> dict:
        if type(batches) is not int or not 1 <= batches <= 16:
            raise ValueError("Invalid batch quantity")
        recipe = self.recipes[recipe_id]
        actor = self.players[player]
        shortages = {}
        for item in recipe["inputs"]:
            item_id = item["commodity"]
            missing = max(0, item["count"] * batches - actor["inventory"].get(item_id, 0))
            if missing:
                city = self.spec[item_id]["city"]
                shortages.setdefault(city, {})[item_id] = missing
        return shortages

    def craft(self, player: str, recipe_id: str, batches: int, transaction: str) -> dict:
        signature = ["craft", player, recipe_id, batches]
        prior = self._idempotent(transaction, signature)
        if prior is not None:
            return prior
        actor = self.players[player]
        recipe = self.recipes[recipe_id]
        if recipe["career"] != actor["career"]:
            raise PermissionError("Wrong career")
        if recipe["tier"] > actor["tier"]:
            raise PermissionError("Profession tier is not unlocked")
        if type(batches) is not int or not 1 <= batches <= 8:
            raise ValueError("Invalid crafting batch count")
        lacking = self.shopping_list(player, recipe_id, batches)
        if lacking:
            raise ValueError("Missing city-specific components: " + json.dumps(lacking, sort_keys=True))
        # All checks are complete before mutation: no partial crafting.
        for item in recipe["inputs"]:
            ident = item["commodity"]
            actor["inventory"][ident] -= item["count"] * batches
            if actor["inventory"][ident] == 0:
                del actor["inventory"][ident]
        out = recipe["output"]
        actor["inventory"][out["id"]] = actor["inventory"].get(out["id"], 0) + out["count"] * batches
        return self._commit(transaction, signature, {"output": out["id"], "count": out["count"] * batches})

    def snapshot(self) -> dict:
        return {
            "schema_version": 1,
            "active_minute": self.active_minute,
            "suppliers": {k: {"quantity": v.quantity, "last_refill_minute": v.last_refill_minute}
                          for k, v in self.suppliers.items()},
            "players": json.loads(json.dumps(self.players)),
            "transactions": json.loads(json.dumps(self.transactions)),
            "supplier_revenue": dict(self.supplier_revenue),
        }

    @classmethod
    def restore(cls, data: dict, snapshot: dict) -> "SupplySimulation":
        if snapshot.get("schema_version") != 1:
            raise ValueError("Unsupported economy save schema")
        model = cls(data)
        minute = snapshot.get("active_minute")
        if type(minute) is not int or minute < 0:
            raise ValueError("Invalid active clock")
        model.active_minute = minute
        if set(snapshot["suppliers"]) != set(model.spec):
            raise ValueError("Supplier registry mismatch: migration required")
        for ident, row in snapshot["suppliers"].items():
            spec = model.spec[ident]
            qty, stamp = row["quantity"], row["last_refill_minute"]
            if (type(qty) is not int or type(stamp) is not int or
                    not 0 <= qty <= spec["capacity"] or not 0 <= stamp <= minute):
                raise ValueError("Corrupted persisted supplier")
            model.suppliers[ident] = Supplier(qty, stamp)
        model.players = json.loads(json.dumps(snapshot["players"]))
        model.transactions = json.loads(json.dumps(snapshot["transactions"]))
        model.supplier_revenue = dict(snapshot["supplier_revenue"])
        return model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, default=SCHEMA_FILE)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    data = read_json(args.file)
    errors = validate(data, read_json(CITIES_FILE), read_json(CAREERS_FILE),
                      read_json(SPECIALTIES_FILE))
    if errors:
        for error in errors:
            print("FAIL:", error)
        raise SystemExit(1)
    report = {
        "schema": "repeatable_regional_supply_v1",
        "status": "source_and_pure_simulation_only",
        "city_supplier_count": len(data["supplier_stock"]),
        "repeating_career_recipes": len(data["recurring_recipes"]),
        "remote_delivery_implemented": False,
        "actual_minecraft_recipes_registered": False,
        "npc_seller_budgets_live": False,
        "active_clock_and_transaction_idempotency_simulated": True,
    }
    output = json.dumps(report, indent=2, sort_keys=True)+"\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output, encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
