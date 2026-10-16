#!/usr/bin/env python3
"""Data-layer checks for inter-city route topology, cross-city progression and loot policies.

No game integration, actual roads or loot refills are implied by a green check.
Run without Minecraft, dependencies, mod JARs or network access.
"""
from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "cities": "content/region-blueprints/cities.json",
    "routes": "content/region-blueprints/corridors.json",
    "supply": "content/progression/intercity-supply.json",
    "restock": "content/property/restock-policies.json",
    "conflict": "content/factions/border-conflict.json",
    "catalog": "content/onboarding/catalog.json",
}


def load(root: Path = ROOT) -> dict[str, dict]:
    return {key: json.loads((root / path).read_text(encoding="utf-8"))
            for key, path in FILES.items()}


def _distance(point, anchor) -> float:
    return math.dist(point, anchor)


def _center(city: dict) -> list[int]:
    return [v * 16 for v in city["center_chunk"]]


def _segment_distance(point, a, b) -> float:
    dx, dz = b[0] - a[0], b[1] - a[1]
    if dx == dz == 0:
        return _distance(point, a)
    t = max(0.0, min(1.0,
        ((point[0]-a[0])*dx + (point[1]-a[1])*dz) / (dx*dx + dz*dz)))
    return _distance(point, [a[0]+t*dx, a[1]+t*dz])


def validate(data: dict[str, dict]) -> list[str]:
    errors: list[str] = []
    cities = {c["id"]: c for c in data["cities"]["cities"]}
    route_info = data["routes"]
    if route_info.get("prototype_only") is not True or route_info.get("coordinate_units") != "overworld_blocks":
        errors.append("Corridor definitions must remain explicitly provisional and use block coordinates")
    if route_info.get("terrain_verified") is not False:
        errors.append("Do not mark unsurveyed bridge paths as terrain-verified")
    routes = route_info.get("routes", [])
    if len(routes) != 3:
        errors.append("Exactly three paired city corridors expected for a connected triangular network")
    # Quantitative travel budget: detect accidental continent-scale mandatory highways.
    # This is a planning-time straight-line polyline estimate, NOT an in-game
    # travel-time benchmark with doors, mobs, loading, elevation or checkpoints.
    budget = route_info.get("transport", {}).get("routine_travel_budget", {})
    speed = budget.get("estimated_walking_blocks_per_second")
    allowance = budget.get("reserved_city_access_minutes")
    hard_limit = budget.get("maximum_routine_door_to_door_minutes")
    preferred = budget.get("preferred_between_city_edges_minutes")
    if not (type(speed) in (float, int) and 3.5 <= speed <= 6
            and type(allowance) in (float, int) and 0 <= allowance <= 4
            and type(hard_limit) in (float, int) and 0 < hard_limit <= 10
            and type(preferred) is list and len(preferred) == 2
            and 2 <= preferred[0] < preferred[1] <= 6):
        errors.append("Routine road travel budget missing or exceeds 10-minute maximum")
    if budget.get("mandatory_route_must_not_require_vehicle") is not True:
        errors.append("Mandatory journeys must be possible without owning a car")
    if budget.get("all_three_routes_walkable_within_budget") is not True:
        errors.append("All three inter-city routes must be built and walkable within the travel budget")
    unpaved = set(route_info.get("road_generation", {}).get("unpaved_route_kinds", []))
    if unpaved:
        errors.append("Every corridor must be a built road; unpaved routes would make a mandatory trip too long")
    build_kinds = set(route_info.get("road_generation", {}).get("build_route_kinds", []))
    routes_by_pair = {}
    crossings = 0
    route_ids = set()
    for route in routes:
        ident = route["id"]
        if ident in route_ids:
            errors.append(f"Repeated corridor id: {ident}")
        route_ids.add(ident)
        frm, to = route["from"], route["to"]
        pair = frozenset((frm, to))
        if frm == to or frm not in cities or to not in cities:
            errors.append(f"Unknown/self corridor endpoints: {ident}")
            continue
        if pair in routes_by_pair:
            errors.append(f"Duplicate edge for {frm} and {to}")
        routes_by_pair[pair] = route
        points = route["waypoints"]
        if route.get("kind") in build_kinds and type(speed) in (float, int) and speed > 0 \
                and type(allowance) in (float, int) and type(hard_limit) in (float, int):
            distance_blocks = sum(_distance(a, b) for a, b in zip(points, points[1:]))
            projected = distance_blocks / speed / 60 + allowance
            if projected > hard_limit:
                errors.append(f"{ident}: estimated mandatory walking journey {projected:.1f} min exceeds {hard_limit}-minute maximum")
        if len(points) < 2 or any(len(p) != 2 or any(type(x) is not int for x in p) for p in points):
            errors.append(f"{ident}: waypoints must have integer X,Z coordinates")
            continue
        for side, city, point in (("start", cities[frm], points[0]),
                                  ("end", cities[to], points[-1])):
            dist = _distance(point, _center(city))
            if dist > city["radius_blocks"]:
                errors.append(f"{ident}: {side} does not reach {city['id']} district edge")
        for third_id, third in cities.items():
            if third_id in pair:
                continue
            for a, b in zip(points, points[1:]):
                if _segment_distance(_center(third), a, b) < third["radius_blocks"] + 50:
                    errors.append(f"{ident}: passes dangerously close to unrelated city {third_id}")
        for crossing in route.get("crossings", []):
            if crossing.get("kind") != "proposed_bridge" or crossing.get("requires_water_or_ravine_survey") is not True:
                errors.append(f"{ident}: bridges must require in-engine terrain verification")
            pair_index = crossing["between_waypoints"]
            if (len(pair_index) != 2 or pair_index[1] != pair_index[0] + 1 or
                    not (0 <= pair_index[0] < len(points)-1)):
                errors.append(f"{ident}: bridge span points invalid")
            crossings += 1
        if not route.get("travel_modes") or "walking" not in route["travel_modes"]:
            errors.append(f"{ident}: early players must be able to cross on foot")
        if not route.get("checkpoints"):
            errors.append(f"{ident}: no playable checkpoints")
        for gate in route.get("checkpoints", []):
            if gate.get("faction") not in pair:
                errors.append(f"{ident}: checkpoint belongs to unrelated city")
            if min(_segment_distance(gate["position"], a, b)
                   for a, b in zip(points, points[1:])) > 128:
                errors.append(f"{ident}: checkpoint off the planned corridor")
    if crossings < 2:
        errors.append("Prototype must plan at least two bridge/viaduct crossing opportunities")
    expected_pairs = {frozenset(pair) for pair in
                      (("ironhaven", "valewood"),
                       ("ironhaven", "blackwater"),
                       ("valewood", "blackwater"))}
    if set(routes_by_pair) != expected_pairs:
        errors.append("Missing cross-city connection or too few redundant routes")

    supply = data["supply"]
    if supply.get("items_are_logical_not_minecraft_registries") is not True:
        errors.append("Do not claim conceptual supply IDs are Minecraft registry items")
    specialties = {entry["city"]: set(entry["exports"])
                   for entry in supply["city_specialties"]}
    if set(specialties) != set(cities):
        errors.append("Every city needs distinct export specialties")
    all_export_ids = [x for items in specialties.values() for x in items]
    if len(all_export_ids) != len(set(all_export_ids)):
        errors.append("Each scarce commodity must have a unique origin")
    professions = {x["id"] for x in data["catalog"]["professions"]}
    unlocked_by_profession = {x: set() for x in professions}
    for milestone in supply["career_milestones"]:
        career = milestone["career"]
        if career not in professions:
            errors.append(f"Milestone {milestone['id']} has unknown career")
            continue
        if milestone["tier"] not in (2, 3):
            errors.append(f"{milestone['id']}: early/local tier 1 should remain ungated")
        sources = set()
        for need in milestone["requires"]:
            city_id = need["origin"]
            sources.add(city_id)
            if need["commodity"] not in specialties.get(city_id, set()):
                errors.append(f"{milestone['id']}: invalid supply from {city_id}")
        if len(sources) < (3 if milestone["tier"] == 3 else 2):
            errors.append(f"{milestone['id']}: missing cross-city resource dependency")
        if not milestone.get("legitimate_paths"):
            errors.append(f"{milestone['id']}: no peaceful progress route")
        expected_visit = ("personally_visit_all_three_cities" if milestone["tier"] == 3
                          else "personally_visit_at_least_one_required_foreign_city")
        if milestone.get("visit_requirement") != expected_visit:
            errors.append(f"{milestone['id']}: first-hand inter-city travel must be necessary")
        unlocked_by_profession[career].add(milestone["tier"])
    for career, tiers in unlocked_by_profession.items():
        if tiers != {2, 3}:
            errors.append(f"{career}: tier 2 and tier 3 must both have cross-city objectives")

    policy = supply["policy"]
    for key in ("tier_1_resources_available_in_home_city", "advanced_tiers_require_multiple_cities",
                "all_essential_inputs_have_nonviolent_routes", "no_city_borders_are_absolute_player_softlocks",
                "no_requirement_for_absent_third_player",
                "require_physical_non_home_city_visit_for_tier_2",
                "require_all_three_city_visits_for_tier_3",
                "remote_resale_does_not_satisfy_first_discovery",
                "legal_market_access_exists_during_war_but_requires_permit_or_escort"):
        if policy.get(key) is not True:
            errors.append(f"Progression safety policy {key} must be true")

    seen_source_sites = set()
    for site in supply.get("location_reward_templates", []):
        city = site.get("city")
        if city not in specialties or city in seen_source_sites:
            errors.append(f"Invalid or duplicate resource site for city {city}")
            continue
        seen_source_sites.add(city)
        if set(site.get("commodity_pool", [])) != specialties[city]:
            errors.append(f"{city}: loot/source plan does not include every specialty")
        if not site.get("legal_method") or not site.get("risk_method"):
            errors.append(f"{city}: need both safe work and dangerous acquisition options")
    if seen_source_sites != set(cities):
        errors.append("Every city must have an explorable specialty location")

    relations = data["conflict"]["initial_relations"]
    if {frozenset(x["cities"]) for x in relations} != expected_pairs:
        errors.append("War relations must cover every distinct city pair")
    if set(x["corridor"] for x in data["conflict"]["crossing_contracts"]) != route_ids:
        errors.append("Each corridor must have at least one real travel contract")
    for key in ("legitimate_supply_obtainable_without_pvp", "no_passive_offline_raids",
                "safe_starters_and_registration_offices"):
        if data["conflict"]["visitor_rules"].get(key) is not True:
            errors.append(f"World hostility safety requirement missing: {key}")

    restock = data["restock"]
    if restock.get("unit") != "server_active_minutes" or restock.get("clock_rule") != "increment_only_while_one_or_more_players_active":
        errors.append("Property refill must use a server-authoritative active-play clock")
    if restock.get("restock_only_when_chunk_active") is not True:
        errors.append("Restock should be reconciled lazily when a property is loaded")
    if restock.get("one_authoritative_inventory_per_property") is not True:
        errors.append("Property stock must be shared, not copied for each burglar")
    seen = set()
    for p in restock["policies"]:
        if p["id"] in seen:
            errors.append(f"Duplicate property category: {p['id']}")
        seen.add(p["id"])
        if not (1 <= p["initial_stock"] <= p["capacity"] <= 128):
            errors.append(f"{p['id']}: invalid stock bounds")
        if not (15 <= p["interval_minutes"] <= 1440 and 1 <= p["items_per_interval"] <= 4):
            errors.append(f"{p['id']}: refill too fast or too large")
        if p["max_value_per_180_minutes"] <= 0:
            errors.append(f"{p['id']}: economy value budget missing")
        if p["loot_table"] != f"underground_city:chests/{p['id']}":
            errors.append(f"{p['id']}: loot table not mapped to correct property class")
    if seen != {"apartment_common","apartment_affluent","office","industrial","illegal_cache"}:
        errors.append("Property loot categories must match the five initial world loot tables")
    for key in ("cooldown_applies_per_property_not_per_player", "persist_last_restock_clock_and_stock",
                "do_not_refill_occupied_slots", "repeat_burglary_escalates_witness_attention"):
        if restock["rules"].get(key) is not True:
            errors.append(f"Missing property safety rule: {key}")
    return errors


@dataclass(frozen=True)
class Stock:
    """Pure model of one shared property. Runtime world/container wiring not implemented."""
    count: int
    last_restock_minute: int


def reconcile(stock: Stock, *, capacity: int, interval_minutes: int,
              items_per_interval: int, now: int, max_backlog_intervals: int = 8) -> Stock:
    if not (0 <= stock.count <= capacity) or stock.last_restock_minute < 0:
        raise ValueError("Invalid persisted property state")
    if not (isinstance(now, int) and now >= stock.last_restock_minute):
        raise ValueError("Time cannot move backwards or use wall clock floats")
    if interval_minutes <= 0 or items_per_interval <= 0 or max_backlog_intervals <= 0:
        raise ValueError("Invalid replenishment rate")
    if stock.count == capacity:
        # Full homes cannot accumulate invisible extra refills to spend after robbery.
        return Stock(stock.count, now)
    elapsed = now - stock.last_restock_minute
    earned_intervals = elapsed // interval_minutes
    if earned_intervals == 0:
        return stock
    cycles = min(earned_intervals, max_backlog_intervals)
    increment = min(capacity - stock.count, cycles * items_per_interval)
    # Consume all elapsed intervals even if capped to prevent catch-up abuse.
    watermark = now - elapsed % interval_minutes
    return Stock(stock.count + increment, watermark)


def withdraw(stock: Stock, count: int) -> Stock:
    """Example atomic stock removal; the Minecraft runtime must enforce locking."""
    if type(count) is not int or not (0 <= count <= stock.count):
        raise ValueError("Cannot steal more than is available")
    return Stock(stock.count - count, stock.last_restock_minute)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        data = load(args.root)
        issues = validate(data)
    except (ValueError, OSError, KeyError, TypeError, json.JSONDecodeError) as ex:
        parser.exit(2, f"Failed to load intercity design: {ex}\n")
    if issues:
        for error in issues:
            print("FAIL:", error)
        return 1
    report = {
        "schema": "underground_city_world_plan_v1",
        "status": "static_geometry_and_design_validation_only",
        "routes": len(data["routes"]["routes"]),
        "planned_bridge_spans": sum(len(x.get("crossings", [])) for x in data["routes"]["routes"]),
        "career_unlock_milestones": len(data["supply"]["career_milestones"]),
        "property_restock_categories": len(data["restock"]["policies"]),
        "city_war_relation_pairs": len(data["conflict"]["initial_relations"]),
        "in_game_generated_roads": False,
        "runtime_stock_refill_implemented": False,
    }
    encoded = json.dumps(report, indent=2, sort_keys=True)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
