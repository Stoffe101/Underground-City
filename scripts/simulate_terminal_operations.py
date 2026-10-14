#!/usr/bin/env python3
"""Server-authoritative design *simulation* for Underground City's computer terminal.

Not a Forge GUI, live shipment, spawned chest, populated raid, wallet API or
registered weapon item. All world/city permission flags in this model are
assertions whose production implementation MUST verify them on the server.
"""
from __future__ import annotations

import hashlib
import json
import random
from dataclasses import dataclass
from pathlib import Path

from simulate_regional_supply import (
    SCHEMA_FILE as SUPPLY_PATH, Supplier, SupplySimulation, read_json,
)

ROOT = Path(__file__).resolve().parents[1]
CATALOG_FILE = ROOT / "content/gameplay/terminal-operations.json"
PROPERTY_FILE = ROOT / "content/property/restock-policies.json"


def validate(data: dict, supply: dict, property_data: dict) -> list[str]:
    issues = []
    if data.get("schema_version") != 1 or data.get("prototype_only") is not True:
        issues.append("Terminal operations must be marked experimental")
    if data.get("clock") != "server_active_minutes":
        issues.append("Incidents must use active server time, never wall-clock decay")
    required_tabs = {"contracts", "imports", "raids", "black_market", "deliveries"}
    if set(data.get("terminal", {}).get("screens", [])) != required_tabs:
        issues.append("One terminal must expose all five gameplay activities")
    props = {p["id"] for p in property_data["policies"]}
    profiles = data.get("loot_profiles", [])
    if {p["id"] for p in profiles} != props:
        issues.append("Each shared property category must have a loot profile")
    for p in profiles:
        if p["slots"] <= 0 or p["replenishment_policy"] != p["id"]:
            issues.append(f"{p['id']}: not connected to existing property category")
        weights = p.get("profession_weights", {})
        if set(weights) != {"farmer", "smith", "gunsmith", "technician", "general"}:
            issues.append(f"{p['id']}: incomplete career loot distribution")
        if sum(weights.values()) != 100 or min(weights.values()) < 0:
            issues.append(f"{p['id']}: career probabilities must total 100")
        if not 0 <= p.get("rare_item_probability_percent", -1) <= 40:
            issues.append(f"{p['id']}: uncontrolled rare gear abundance")
        if not 0 <= p.get("locked_safe_chance_percent", -1) <= 100:
            issues.append(f"{p['id']}: invalid safe chance")
    ordinary = next((p for p in profiles if p["id"] == "apartment_common"), {})
    if any("weapon" in x or "gun" in x for x in ordinary.get("samples", [])):
        issues.append("Ordinary common apartments must not become weapon farms")
    loots = data["special_loot"]
    for rule in ("household_inventory_shared", "high_tier_parts_in_locked_safes_or_high_risk_structures",
                 "refill_respects_household_budget", "never_unlimited_rare_blueprint_farming"):
        if loots.get(rule) is not True:
            issues.append(f"Important loot safeguard missing: {rule}")
    timing = data["shipments"]
    for k in ("active_minutes_to_dispatch", "travel_active_minutes", "salvage_deadline_active_minutes"):
        if type(timing.get(k)) is not int or timing[k] <= 0:
            issues.append(f"Shipment timing {k} must be positive")
    if not 0 <= timing.get("incident_chance_percent", -1) <= 35:
        issues.append("Shipment incidents should be occasional, not constant")
    if timing["time_outcome"].get("legal") != "claim_eligible" or timing["time_outcome"].get("illegal") != "confiscated":
        issues.append("Legal and illegal cargo must not have identical arbitrary confiscation")
    if timing["delivery_security"].get("physical_world_encounter_requires_grounded_location") is not True:
        issues.append("No virtual pickup of world shipment events")
    price_tags = set()
    for band in data["black_market"]["price_bands"]:
        price_tags.add(band["item_tag"])
        if not 0 < band["min_price"] <= band["market_price"] <= band["max_price"]:
            issues.append(f"Bad market price limits: {band['item_tag']}")
    if price_tags != {"gun_equipment", "weapon_component", "technical_equipment"}:
        issues.append("Black market must cover equipment and gun components")
    market = data["black_market"]
    for guard in ("npc_buyers_have_finite_budget", "listings_do_not_instantly_sell",
                  "escrow_before_dropbox_deposit", "single_use_dropbox_per_transaction",
                  "meeting_points_generated_from_approved_markers_not_random_air",
                  "item_nbt_identity_must_be_preserved"):
        if market.get(guard) is not True:
            issues.append(f"Missing escrow/dropbox safety rule: {guard}")
    if len(data.get("raid_templates", [])) < 3:
        issues.append("Early gang raids and late-game police raids require separate templates")
    kinds = {r["id"] for r in data["raid_templates"]}
    if not {"gang_safehouse", "warehouse_heist", "police_station"} <= kinds:
        issues.append("Missing gang, warehouse or police target")
    for r in data["raid_templates"]:
        if r["min_tier"] < 2 or r["cooldown_active_minutes"] < 120 or r["needs_physical_arrival"] is not True:
            issues.append(f"{r['id']}: raid must be late-game, gated and physically entered")
    if data["raids"].get("no_spawn_from_terminal_only") is not True:
        issues.append("Selecting raid on PC must not instantly spawn a finished battle")
    supply_ids = {s["id"] for s in supply["supplier_stock"]}
    for profile in profiles:
        for item in profile["samples"]:
            if item not in supply_ids and item not in {
                "licensed_weapon_component", "weapon_repair_kit",
                "small_electronic_parts", "paper_records",
            }:
                issues.append(f"{profile['id']}: unknown conceptual item {item}")
    return issues


def _roll(seed: str) -> int:
    return int.from_bytes(hashlib.sha256(seed.encode("utf-8")).digest()[:8], "big")


def household_loot(data: dict, category: str, *, safe_opened: bool, seed: str) -> dict:
    """Theoretical reward potential. Real container assignment is NOT implemented."""
    profile = next(p for p in data["loot_profiles"] if p["id"] == category)
    rng = random.Random(_roll(f"{seed}:{category}:{safe_opened}"))
    if safe_opened and rng.randrange(100) >= profile["locked_safe_chance_percent"]:
        # A household may not even have a locked safe. Caller also must
        # verify legitimate access, lockpicking and shared restock state.
        return {"safe_present": False, "items": []}
    if rng.randrange(100) >= profile["rare_item_probability_percent"]:
        return {"safe_present": bool(safe_opened), "items": []}
    candidates = list(profile["samples"])
    if not safe_opened and category == "apartment_affluent":
        candidates = [x for x in candidates if x != "licensed_weapon_component"]
    if not safe_opened and category in ("industrial", "illegal_cache"):
        candidates = [x for x in candidates if x != "licensed_weapon_component"]
    return {"safe_present": bool(safe_opened), "items": [rng.choice(candidates)]}


@dataclass(frozen=True)
class TerminalReceipt:
    status: str
    transaction: str


class TerminalSimulation:
    """Single-threaded test of intended effects; native server must serialize actions."""

    def __init__(self, data: dict, supply_data: dict, *, world_seed: str = "prototype"):
        self.rules = data
        self.supply = SupplySimulation(supply_data)
        self.seed = world_seed
        self.orders: dict[str, dict] = {}
        self.listings: dict[str, dict] = {}
        self.dropboxes: dict[str, dict] = {}
        self.raids: dict[str, dict] = {}
        self.buyer_budget = 6000
        self.items: dict[str, dict[str, dict]] = {}
        self.receipts: set[str] = set()
        self.raid_cooldown: dict[str, int] = {}

    @property
    def now(self) -> int:
        return self.supply.active_minute

    def register(self, player: str, home: str, career: str, *, credits: int = 3000, tier: int = 1) -> None:
        if tier not in (1, 2, 3):
            raise ValueError("Invalid initial tier")
        self.supply.register(player, home, career, credits)
        # Tiers granted here ONLY as test fixture. Production uses quest verification.
        self.supply.players[player]["tier"] = tier
        self.items[player] = {}

    def advance(self, minutes: int, *, players_online: bool = True) -> None:
        self.supply.advance_active_time(minutes, players_online=players_online)
        for oid in list(self.orders):
            self._update_order(oid)

    def order(self, player: str, commodity: str, count: int, order_id: str,
              *, legal: bool, permit_verified: bool) -> dict:
        if order_id in self.orders or not order_id:
            raise ValueError("Order identifier must be unique")
        actor = self.supply.players[player]
        spec = self.supply.spec[commodity]
        remote = next(x for x in self.supply.data["travel_channels"]
                      if x["id"] == "licensed_convoy_import")
        if spec["city"] == actor["city"]:
            raise ValueError("Use a local direct supplier for goods from current city")
        if spec["city"] not in actor["visited"]:
            raise PermissionError("Order imports only after a qualified visit to supplier city")
        if legal and not permit_verified:
            raise PermissionError("Import permit required")
        if type(count) is not int or not 1 <= count <= remote["max_quantity_per_order"]:
            raise ValueError("Remote shipments are limited to small batches")
        available = self.supply.available(commodity)
        if available.quantity < count:
            raise ValueError("Supplier has no stock to reserve")
        price = count * spec["unit_price"] * remote["price_multiplier_percent"] // 100
        if actor["credits"] < price:
            raise ValueError("Insufficient funds for premium import")
        # Consume reserved foreign supplier stock exactly once.
        self.supply.suppliers[commodity] = Supplier(
            available.quantity - count, available.last_refill_minute)
        actor["credits"] -= price
        chance = self.rules["shipments"]["incident_chance_percent"]
        incident = _roll(f"{self.seed}|shipment|{order_id}") % 100 < chance
        kinds = (["road_crash", "gang_ambush"] if legal
                 else self.rules["shipments"]["incident_types"])
        incident_type = kinds[_roll(f"{self.seed}|type|{order_id}") % len(kinds)] if incident else None
        event_minute = (self.now + self.rules["shipments"]["active_minutes_to_dispatch"]
                        + self.rules["shipments"]["travel_active_minutes"])
        self.orders[order_id] = {
            "owner": player, "commodity": commodity, "count": count,
            "price": price, "legal": legal, "state": "in_transit",
            "incident_type": incident_type, "arrival_minute": event_minute,
            "deadline": event_minute + self.rules["shipments"]["salvage_deadline_active_minutes"],
            "from_city": spec["city"], "to_city": actor["city"],
        }
        return dict(self.orders[order_id])

    def _grant_order_cargo(self, order: dict) -> None:
        inv = self.supply.players[order["owner"]]["inventory"]
        key = order["commodity"]
        inv[key] = inv.get(key, 0) + order["count"]

    def _update_order(self, order_id: str) -> None:
        order = self.orders[order_id]
        if order["state"] not in ("in_transit", "recovery_open"):
            return
        if self.now < order["arrival_minute"]:
            return
        if order["incident_type"] is None:
            self._grant_order_cargo(order)
            order["state"] = "delivered"
        elif self.now >= order["deadline"]:
            if order["legal"]:
                self.supply.players[order["owner"]]["credits"] += order["price"]
                order["state"] = "insured"
            else:
                order["state"] = "confiscated"
        else:
            order["state"] = "recovery_open"

    def salvage(self, player: str, order_id: str, *, at_verified_crash_site: bool) -> dict:
        self._update_order(order_id)
        order = self.orders[order_id]
        if order["owner"] != player or order["state"] != "recovery_open":
            raise PermissionError("This cargo cannot currently be claimed")
        if at_verified_crash_site is not True:
            raise PermissionError("Must physically arrive at guarded crash scene")
        self._grant_order_cargo(order)
        order["state"] = "recovered"
        return dict(order)

    def give_item(self, player: str, instance_id: str, tag: str, *,
                  quality: int = 100) -> None:
        if not instance_id or instance_id in self.items[player]:
            raise ValueError("Each item requires a distinct durable instance ID")
        if tag not in {x["item_tag"] for x in self.rules["black_market"]["price_bands"]}:
            raise ValueError("Item not accepted on market")
        if not 1 <= quality <= 100:
            raise ValueError("Item quality out of range")
        self.items[player][instance_id] = {"tag": tag, "quality": quality}

    def list_item(self, player: str, item_id: str, price: int, listing_id: str) -> dict:
        if listing_id in self.listings or not listing_id:
            raise ValueError("Listing ID already used")
        if item_id not in self.items[player]:
            raise ValueError("Seller must physically own item")
        if any(x["owner"] == player and x["item_id"] == item_id and
               x["state"] in ("open", "accepted", "deposited") for x in self.listings.values()):
            raise ValueError("Item already reserved by active listing")
        item = self.items[player][item_id]
        band = next(x for x in self.rules["black_market"]["price_bands"]
                    if x["item_tag"] == item["tag"])
        if type(price) is not int or not band["min_price"] <= price <= band["max_price"]:
            raise ValueError("Price must be within bounded market guide")
        self.listings[listing_id] = {
            "owner": player, "item_id": item_id, "item_tag": item["tag"],
            "guide_price": band["market_price"], "price": price,
            "state": "open", "created": self.now, "dropbox_id": None,
        }
        return dict(self.listings[listing_id])

    def accept_buyer(self, listing_id: str, *, marker_id: str, marker_approved: bool) -> dict:
        listing = self.listings[listing_id]
        if listing["state"] != "open":
            raise ValueError("Listing is not open for bids")
        if not marker_id or marker_approved is not True:
            raise PermissionError("Only surveyed city dropbox anchors are allowed")
        if marker_id in self.dropboxes:
            raise ValueError("Dropbox marker already used or requires cleanup")
        if self.buyer_budget < listing["price"]:
            raise ValueError("No buyer with sufficient escrow budget")
        # Reserve NPC buyer's actual finite currency before advertising payout.
        self.buyer_budget -= listing["price"]
        self.dropboxes[marker_id] = {
            "listing_id": listing_id, "held_payment": listing["price"],
            "held_item": None, "state": "awaiting_seller",
        }
        listing["state"] = "accepted"
        listing["dropbox_id"] = marker_id
        listing["pickup_deadline"] = self.now + self.rules["black_market"]["dropbox_pickup_window_minutes"]
        return dict(listing)

    def deposit(self, player: str, listing_id: str, *, at_approved_box: bool) -> dict:
        listing = self.listings[listing_id]
        if listing["owner"] != player or listing["state"] != "accepted" or not at_approved_box:
            raise PermissionError("Seller must be at their assigned approved city box")
        box = self.dropboxes[listing["dropbox_id"]]
        if self.now >= listing["pickup_deadline"]:
            raise ValueError("Dropbox deadline expired")
        if listing["item_id"] not in self.items[player]:
            raise ValueError("Original item no longer in seller inventory")
        # Exact item instance survives transfer; no fake copy is introduced.
        box["held_item"] = self.items[player].pop(listing["item_id"])
        box["state"] = "awaiting_money_collection"
        listing["state"] = "deposited"
        return dict(box)

    def collect_money(self, player: str, listing_id: str) -> dict:
        listing = self.listings[listing_id]
        if listing["owner"] != player or listing["state"] != "deposited":
            raise PermissionError("No escrowed seller payment waiting")
        box = self.dropboxes[listing["dropbox_id"]]
        funds = box["held_payment"]
        if funds <= 0:
            raise ValueError("Money already paid")
        box["held_payment"] = 0
        box["state"] = "settled"
        self.supply.players[player]["credits"] += funds
        self.supply.players[player]["heat"] = self.supply.players[player].get("heat", 0) + 1
        listing["state"] = "settled"
        return {"payout": funds, "item_id": listing["item_id"], "status": "settled"}

    def expire_listing(self, listing_id: str) -> None:
        listing = self.listings[listing_id]
        if listing["state"] == "deposited" or listing["state"] == "settled":
            # Once an item is physically deposited, payout remains claimable
            # and cannot be silently deleted by expiry.
            return
        if listing["state"] not in ("open", "accepted"):
            return
        deadline = (listing.get("pickup_deadline") if listing["state"] == "accepted"
                    else listing["created"] + self.rules["black_market"]["listing_expiry_active_minutes"])
        if self.now < deadline:
            return
        if listing["state"] == "accepted":
            box = self.dropboxes[listing["dropbox_id"]]
            self.buyer_budget += box["held_payment"]
            box["held_payment"] = 0
            box["state"] = "expired"
        listing["state"] = "expired"

    def start_raid(self, player: str, raid_id: str, transaction_id: str, *,
                   gear_score: int, arrived_at_real_target: bool) -> dict:
        if transaction_id in self.raids:
            raise ValueError("Raid instance IDs must be unique")
        rule = next(x for x in self.rules["raid_templates"] if x["id"] == raid_id)
        actor = self.supply.players[player]
        if actor["tier"] < rule["min_tier"] or gear_score < rule["gear_score"]:
            raise PermissionError("Late-game tier or equipment requirement missing")
        if arrived_at_real_target is not True:
            raise PermissionError("Selecting raid on PC cannot teleport/spawn rewards")
        if self.now < self.raid_cooldown.get(f"{player}:{raid_id}", 0):
            raise ValueError("Raid is cooling down")
        if any(r["owner"] == player and r["state"] == "active" for r in self.raids.values()):
            raise ValueError("One active raid per player")
        self.raids[transaction_id] = {
            "owner": player, "template": raid_id, "start": self.now,
            "state": "active", "reward_claimed": False,
        }
        self.raid_cooldown[f"{player}:{raid_id}"] = self.now + rule["cooldown_active_minutes"]
        return dict(self.raids[transaction_id])

    def complete_raid(self, player: str, transaction_id: str,
                      *, objective_verified: bool) -> dict:
        raid = self.raids[transaction_id]
        if raid["owner"] != player or raid["state"] != "active" or objective_verified is not True:
            raise PermissionError("Reward requires live verified raid objectives")
        rule = next(r for r in self.rules["raid_templates"]
                    if r["id"] == raid["template"])
        if self.now > raid["start"] + rule["active_minutes"]:
            raid["state"] = "failed"
            raise ValueError("Raid expired")
        raid["state"] = "completed"
        raid["reward_claimed"] = True
        actor = self.supply.players[player]
        actor["heat"] = actor.get("heat", 0) + (10 if raid["template"] == "police_station" else 3)
        return {"status": "completed", "reward_source": rule["reward_source"]}

    def snapshot(self) -> dict:
        return json.loads(json.dumps({
            "version": 1, "supply": self.supply.snapshot(), "orders": self.orders,
            "listings": self.listings, "dropboxes": self.dropboxes,
            "raids": self.raids, "buyer_budget": self.buyer_budget,
            "items": self.items, "raid_cooldown": self.raid_cooldown,
        }))

    @classmethod
    def restore(cls, data: dict, supply_data: dict, save: dict, *,
                world_seed: str = "prototype") -> "TerminalSimulation":
        if save.get("version") != 1:
            raise ValueError("Unknown terminal save schema")
        result = cls(data, supply_data, world_seed=world_seed)
        result.supply = SupplySimulation.restore(supply_data, save["supply"])
        result.orders = save["orders"]
        result.listings = save["listings"]
        result.dropboxes = save["dropboxes"]
        result.raids = save["raids"]
        result.buyer_budget = save["buyer_budget"]
        result.items = save["items"]
        result.raid_cooldown = save["raid_cooldown"]
        if type(result.buyer_budget) is not int or result.buyer_budget < 0:
            raise ValueError("Corrupt NPC buyer balance")
        return result


def main() -> int:
    data, supply = read_json(CATALOG_FILE), read_json(SUPPLY_PATH)
    issues = validate(data, supply, read_json(PROPERTY_FILE))
    if issues:
        raise SystemExit("Terminal design invalid:\n" + "\n".join(issues))
    print(json.dumps({
        "validated": True, "terminal_tabs": len(data["terminal"]["screens"]),
        "property_loot_profiles": len(data["loot_profiles"]),
        "black_market_price_categories": len(data["black_market"]["price_bands"]),
        "raid_templates": len(data["raid_templates"]),
        "live_in_game_features_implemented": False,
    }, indent=2))


if __name__ == "__main__":
    main()
