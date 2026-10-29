#!/usr/bin/env python3
"""Legal, underground and hybrid careers compared, level by level (twelfth pass).

The owner's question of October 29: does a legal career stay worth playing once its underground path opens, or does
the best strategy become "unlock the illegal path and never do honest work again"? This script answers it from the
game's own data (the work orders, the skill trees, the city laws, the corridor gates, the fences, the property
ladder) through the economy model (``scripts/economy_model.py``), adding what that model leaves out: the
underground orders themselves, the cost of being caught, and the spread of an underground hour.

For every career at levels 1, 3, 6, 10, 15, 20 and 30 it computes the best active hour of six builds:

    A legal        legal orders only, skill points spent on the legal and core branches
    B underground  underground orders wherever the character can take them, legal work only to fill the hour,
                   underground skills first, plus burglary (the Thief track) through the fences
    C hybrid       both, every order taken on its risk-adjusted value, points spent cheapest first
    D new          a legal beginner with no capital (no order that needs cash at an exchange until the first earnings)
    E experienced  legal, with trade premises rented (the orders that ask a trade facility); every build's stations are fitted to the tier
                   its level affords (``station_tiers``, an assumption) and its goods graded as Workshops.gradeFor grades them
    F crew         two careers in one household (the economy model's shared demand and shelves), legal and hybrid

An underground order is risk-adjusted along its real corridor (the same arithmetic as ``LawMath.inspectionChance``
and ``validate_underground_balance.py``): ``value = P(not searched) x paid - cost - E[fine]``. A burglary or a raid is
risk-adjusted with the police: ``P(noticed)`` from the city law and the security classes, ``P(arrest | noticed)`` from
``police_risk`` in ``content/gameplay/economy-model.json`` (an ASSUMPTION until the scripted police runs give a rate),
the city's fine escalated by priors as ``LawMath.escalatedFine`` does, and the haul confiscated. Every probability is
from code or data, or named as an assumption in that file. The spread of an underground hour is a seeded Monte Carlo
of the same events (1000 hours): the mean, the tenth and ninetieth percentiles and the share of hours that lose money.

This is arithmetic over data plus stated assumptions about time, not a measurement of play.

    python scripts/career_balance.py              the report
    python scripts/career_balance.py --check      exit 1 when a balance rule below breaks
    python scripts/career_balance.py --update-doc docs/technical/career-balance.md

Rules (``RULES`` below; each is also shown to fail on data made to break it, ``tests/test_career_balance.py``):

    legal_after_unlock    at every level from 10, a career's best legal hour is at least 75% of its best
                          risk-adjusted underground hour: honest work stays a real choice after the path opens
    underground_premium   at the levels where an underground path is open, its NOMINAL hour beats the legal hour
                          (crime pays more on paper) but its risk-adjusted hour stays under 1.6 times it
    legal_growth          a legal career's best hour at level 20 is at least 1.25 times its best hour at level 6:
                          legal progression does not stop at the middle levels
    hybrid_uses_legal     a hybrid character still spends at least 25% of its hour on legal work at level 15
    no_single_activity    no single order makes more than 60% of a household's legal hour at level 20
    affordability         a legal-only character of every career can rent a house within 15 active hours and
                          buy one within 60, and a legal Crew of two can buy the villa within 120
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import economy_model as em  # noqa: E402
import validate_underground_balance as ub  # noqa: E402

ROOT = em.ROOT
DATA = em.DATA
LEVELS = (1, 3, 6, 10, 15, 20, 30)
#: The city a career's goods usually leave from, for an order that names a buyer but no contract route.
HOME_CITY = {"farmer": "valewood", "vintner": "valewood", "smith": "ironhaven", "gunsmith": "ironhaven", "technician": "blackwater"}
RULES = {
    "legal_after_unlock_min": 0.75,
    "underground_risk_max": 1.6,
    "legal_growth_min": 1.25,
    "hybrid_legal_share_min": 0.25,
    "single_activity_max": 0.80,
    "career_spread_max": 1.5,
    "rent_house_hours_max": 15,
    "buy_house_hours_max": 60,
    "crew_villa_hours_max": 120,
}
SIMULATED_HOURS = 1000
#: The most orders for one buyer a run is taken to carry (a run with more goods is a bigger loss when it is searched; three is an assumption).
TRIP_SHARE_MAX = 3
#: What a buyer pays for each grade against Standard (``Workshops.GRADE_PRICE``), and the most a delivery can be weighted (``WorkOrderService.gradedPayout``).
GRADE_PRICE = (1.0, 1.25, 1.6)


class Balance:
    def __init__(self, model: em.Model | None = None):
        self.m = model or em.Model()
        self.a = self.m.a
        self.police = self.a.get("police_risk", {})
        self.contracts = {c["id"]: c for c in self.m.contracts["contracts"]}
        self.laws = ub.load_dir("city_laws")
        self.contraband = ub.load_dir("contraband")
        self.buyers = ub.load_dir("buyers")
        self.corridors = json.loads((ROOT / "content/region-blueprints/corridors.json").read_text(encoding="utf-8"))
        self.security = json.loads((DATA / "building_security/classes.json").read_text(encoding="utf-8"))
        self.eff = self.a["efficiency"]["hands_on_share_of_active_time"]
        self.gradings: dict[str, list[dict]] = {}
        self.hand_turned: set[str] = set()
        for path in sorted((DATA / "workshops").glob("*.json")):
            for r in json.loads(path.read_text(encoding="utf-8")).get("recipes", []):
                if r.get("grading"):
                    self.gradings.setdefault(r["output"], []).append(r["grading"])
                if r.get("hands_on"):
                    self.hand_turned.add(r["output"])
        self.services = self._services()
        self.by_id = {o.id: o for o in self.m.orders + self.services}
        self.service_ids = {o.id for o in self.services}
        self._cache: dict = {}

    # -- service calls (the Technician's panels, the Smith's drives) ----------------------------------------------------------------

    def _services(self) -> list[em.Order]:
        """Each service job of ``service_work`` as an order: the fee at the mean building tier (or the most the career may take on without the skill that lets
        it reach higher), the parts it uses as its requirements (their cost and making time from the economy model), and its minutes on top."""
        sw = self.a.get("service_work", {})
        out = []
        for job in sw.get("jobs", []):
            tier = sw["building_tier"]
            fee = job["fee_base"] + job["fee_per_tier"] * tier
            o = em.Order(id=job["id"], career=job["career"], level=job["level"], title=job["id"], legality="legal", credits=int(round(fee)),
                         xp=int(round(job["xp_base"] + job["xp_per_tier"] * tier)), capacity=0, refill_minutes=60.0 / job["per_hour"], foreign_visits=0,
                         skill=job.get("skill", ""), mod="", buyer="", requirements=[{"item": k, "count": n} for k, n in job["uses"].items()], rewards=[])
            self.m._economics(o)
            o.hands += job["minutes"] - self.a["overhead"]["terminal_minutes_per_order"]   # no terminal: a call is taken and paid at the panel or the drive
            out.append(o)
        return out

    def reached(self, o: em.Order, skills: set[str]) -> em.Order:
        """A service job as this character can take it: a Technician without Municipal Repairs repairs only buildings up to the tier its skills reach
        (TechWork.repairReach), so the fee is that tier's, and a repair below tier III uses no circuit board."""
        job = next((j for j in self.a.get("service_work", {}).get("jobs", []) if j["id"] == o.id), None)
        if not job or "reach" not in job:
            return o
        reach = max(t for k, t in job["reach"].items() if k == "" or k in skills)
        tier = min(self.a["service_work"]["building_tier"], reach)
        if tier == self.a["service_work"]["building_tier"]:
            return o
        r = dataclasses.replace(o, credits=int(round(job["fee_base"] + job["fee_per_tier"] * tier)), goods_units={}, exchange_goods=set())
        if tier < job.get("uses_from_tier", 0):
            r.requirements, r.cash = [], 0.0
            r.hands = job["minutes"]
        return r

    # -- grades (D-163): what an experienced maker's upgraded stations and skills make, and what a delivery of it pays -------------------

    def grade(self, item: str, skills: set[str], tier: int, depth: int = 0) -> int:
        """The grade (0 Standard, 1 Fine, 2 Reserve) of ``item`` made at a station of ``tier`` by somebody holding ``skills``, as ``Workshops.gradeFor``. A part turned
        by hand to its drawing (``hands_on``) is taken as Reserve for an experienced maker at tier 3 and Fine below (the engineer scenario turned a Reserve shaft)."""
        if item in self.hand_turned:
            return 2 if tier >= 3 else 1 if tier >= 2 else 0
        best = 0
        for g in self.gradings.get(item, []):
            v = g.get("base", 0) + (g.get("tier2", 0) if tier >= 2 else 0) + (g.get("tier3", 0) if tier >= 3 else 0)
            v += sum(n for k, n in g.get("skills", {}).items() if k in skills)
            if g.get("from_input") and g.get("from_item") and depth < 3:
                v += self.grade(g["from_item"], skills, tier, depth + 1)
            best = max(best, min(g.get("max", 2), v))
        return best

    def grade_weight(self, order: em.Order, skills: set[str], tier: int) -> float:
        """The payout multiplier of a delivery of graded goods (``WorkOrderService.gradedPayout``): the mean grade price over every unit, at most Reserve's."""
        units = weight = 0.0
        for req in order.requirements:
            n = req["count"]
            units += n
            weight += n * GRADE_PRICE[self.grade(req["item"], skills, tier)]
        return min(GRADE_PRICE[2], weight / units) if units else 1.0

    # -- skills ---------------------------------------------------------------------------------------------------------

    def skills(self, career: str, level: int, order: str) -> set[str]:
        """Points spent in an order of branches: ``legal`` (legal, core, utility, crossover, never underground), ``legal_first`` (those, then underground with
        what is left), ``underground`` (underground first, then the rest) or ``cheap`` (cheapest first, both)."""
        nodes = self.m.trees[career]["nodes"]
        points = level - 1
        have: set[str] = set()

        def rank(n):
            ug = n["branch"] == "underground"
            if order in ("legal", "legal_first"):
                return (1 if ug else 0, n["min_level"], n["cost"], n["id"])
            if order == "underground":
                return (0 if ug else 1, n["min_level"], n["cost"], n["id"])
            return (n["min_level"], n["cost"], n["id"])

        progress = True
        while progress:
            progress = False
            for n in sorted(nodes, key=rank):
                if n["id"] in have or n["min_level"] > level or n["cost"] > points:
                    continue
                if order == "legal" and n["branch"] == "underground":
                    continue
                if any(r not in have for r in n["requires"]):
                    continue
                have.add(n["id"])
                points -= n["cost"]
                progress = True
                break
        return have

    # -- risk of an underground order -----------------------------------------------------------------------------------

    def route(self, order: em.Order):
        """(origin, destination, contraband category) of an order's goods, or None when nothing carried is illegal or the order has no buyer."""
        c = self.contracts.get(order.id)
        buyer = (c or {}).get("buyer") or order.buyer
        if not buyer or buyer not in self.buyers:
            return None
        origin = (c or {}).get("origin") or HOME_CITY[order.career]
        dest = self.buyers[buyer]["city"]
        category = ub.category_of(c or {"requirements": order.requirements}, self.contraband)
        return origin, dest, category

    def seizure(self, order: em.Order, skills: set[str], heat: int) -> tuple[float, float]:
        """(probability the goods are seized on the way, expected fine) along the corridor gates, as ``LawMath.inspectionChance`` and ``expected_return``."""
        r = self.route(order)
        if r is None or r[2] is None or r[0] == r[1]:
            return 0.0, 0.0
        origin, dest, category = r
        tree = self.m.trees[order.career]["nodes"] if order.career in self.m.trees else []
        modifier = max(-0.25, sum(n["effects"].get("inspection_modifier", 0.0) for n in tree if n["id"] in skills))
        licensed = any(category in n["effects"].get("licences", []) for n in tree if n["id"] in skills)
        survive, fine = 1.0, 0.0
        for gate in ub.gates_between(self.corridors, origin, dest):
            law = self.laws[gate["faction"]]
            status = law["categories"].get(category, "legal")
            if status == "legal" or (status == "permit" and licensed):
                continue
            p = ub.inspection_chance(law, gate["risk"], heat, modifier)
            fine += survive * p * law["fines"][status]
            survive *= 1 - p
        return 1 - survive, fine

    def police_event(self, kind: str) -> dict:
        """The police side of a raid or a burglary: P(noticed), P(arrest | noticed), fine, from ``police_risk`` (assumptions, each with its source)."""
        p = self.police[kind]
        return {"p_notice": p["p_noticed"], "p_arrest": self.police["p_arrest_given_noticed"], "fine": p["fine"], "source": p["source"]}

    def order_value(self, order: em.Order, skills: set[str], heat: int, tier: int = 1) -> dict:
        """Nominal and risk-adjusted net credits of one delivery, and the bad outcome's loss (for the spread). ``tier`` is the tier of the maker's stations (graded goods pay more)."""
        paid = order.credits if order.career == "any" else self.m.paid(order, skills)
        if order.id not in self.service_ids:
            paid = int(round(paid * self.grade_weight(order, skills, tier)))
        nominal = paid - order.cash
        if order.legality != "underground":
            return {"nominal": nominal, "risk": nominal, "p_bad": 0.0, "bad": nominal, "good": nominal}
        if order.id.startswith("raid_"):
            ev = self.police_event("raid")
            p = ev["p_notice"] * ev["p_arrest"]
            bad = -order.cash - ev["fine"]
            return {"nominal": nominal, "risk": (1 - p) * nominal + p * bad, "p_bad": p, "bad": bad, "good": nominal}
        p, fine = self.seizure(order, skills, heat)
        # a seizure takes the goods (their cost is already spent) and fines the carrier: E[fine] is the fine weighted by when the search happens
        bad = -order.cash - (fine / p if p > 0 else 0.0)
        return {"nominal": nominal, "risk": (1 - p) * paid - order.cash - fine, "p_bad": p, "bad": bad, "good": nominal}

    # -- burglary (the Thief track) -------------------------------------------------------------------------------------

    def burglary(self, tier: int) -> dict:
        """One burglary of a typical house of a tier, from the burglary audit's expected haul and ``police_risk``'s minutes and odds."""
        import burglary_audit as ba
        kinds, values = ba.load()
        haul = sum(n * ba.expected(kinds[k], tier, "house", values) for k, n in ba.TYPICAL["house"].items()) * ba.RATE
        b = self.police["burglary"]
        minutes = b["minutes_per_visit"] + self.a["overhead"]["buyer_trip_round_trip_minutes"] / b["visits_per_fence_trip"]
        p_notice = b["p_noticed_by_tier"][str(tier)]
        p = p_notice * self.police["p_arrest_given_noticed"]
        tools = b["lockpicks_per_visit"] * b["lockpick_price"]
        good = haul - tools
        bad = -tools - b["fine"]
        return {"tier": tier, "haul": haul, "minutes": minutes, "p_bad": p, "good": good, "bad": bad, "nominal": good,
                "risk": (1 - p) * good + p * bad, "cap_per_hour": b["fence_credits_per_hour"]}

    def thief_tier(self, level: int) -> int:
        """The house tier an underground specialist of this career level can work (an assumption: the Thief track keeps pace with the career)."""
        bands = self.police["burglary"]["tier_by_level"]
        tier = 1
        for lvl, t in sorted(((int(k), v) for k, v in bands.items())):
            if level >= lvl:
                tier = t
        return tier

    # -- the best hour of a build ---------------------------------------------------------------------------------------

    def station_tier(self, level: int) -> int:
        """The tier a character of this level has fitted to its stations (``station_tiers`` in economy-model.json, an assumption)."""
        tier = 1
        for lvl, t in sorted((int(k), v) for k, v in self.a.get("station_tiers", {}).get("by_level", {"1": 1}).items()):
            if level >= lvl:
                tier = t
        return tier

    def hour(self, members: list[tuple[str, int, str]], mods: set[str], facility: bool = False, no_cash: bool = False, heat: int | None = None, tier: int | None = None) -> dict:
        """The best hour over the ways a hybrid can spend its points (cheapest first, its legal trade first, its underground first): a hybrid character is
        one that chooses well, not one that spends points at random."""
        hybrids = [i for i, m in enumerate(members) if m[2] == "hybrid"]
        if not hybrids:
            return self._hour(members, mods, facility, no_cash, heat, tier, {})
        best = None
        for order in ("cheap", "legal_first", "underground"):
            h = self._hour(members, mods, facility, no_cash, heat, tier, {i: order for i in hybrids})
            if best is None or h["risk"] > best["risk"] + 1e-9:
                best = h
        return best

    def _hour(self, members: list[tuple[str, int, str]], mods: set[str], facility: bool, no_cash: bool, heat: int | None, tier: int | None, orders: dict[int, str]) -> dict:
        """The best active hour of a household. Each member is (career, level, mode) with mode legal, underground or hybrid. Orders are taken in order of
        risk-adjusted net per hands-on minute (an underground specialist takes underground orders first), sharing employer demand and exchange shelves as
        ``Model.household_income`` does. Returns the nominal and risk-adjusted net, the share of minutes on legal work, the picks and a seeded spread."""
        heat = self.police.get("typical_heat", 1) if heat is None else heat
        horizon = self.a["supply_horizon_hours"]
        minutes = [60.0 * self.eff for _ in members]
        demand = {o.id: o.capacity / horizon + 60.0 / o.refill_minutes for o in self.m.orders + self.services}
        supply = self.m.supply_pool()
        candidates = []
        trip = self.a["overhead"]["buyer_trip_round_trip_minutes"]
        for idx, (career, level, mode) in enumerate(members):
            order = orders.get(idx) or {"legal": "legal", "underground": "underground", "hybrid": "cheap"}[mode]
            skills = self.skills(career, level, order)
            open_orders = []
            for o in self.m.orders + self.services:
                if not o.career in (career, "any") or o.level > level or level > o.max_level:
                    continue
                if o.mod and o.mod not in mods:
                    continue
                if o.skill and o.skill not in skills and not (o.career == "any" and o.skill in ("pro_tools",)):
                    continue
                if o.career == "any" and o.skill == "pro_tools" and mode == "legal":
                    continue
                if mode == "legal" and o.legality != "legal":
                    continue
                if no_cash and o.cash > 0:
                    continue
                if o.id in self.facility_orders and not facility:
                    continue
                open_orders.append(o)
            # one run to a buyer carries every open order for that buyer (each is delivered to the same counter): the economy model charges each order of
            # capacity one a whole round trip, as if it were the only one carried; a smuggler or a licensed carrier with three orders for one buyer makes one run
            per_buyer: dict[str, int] = {}
            for o in open_orders:
                if o.buyer and o.career != "any":
                    per_buyer[o.buyer] = per_buyer.get(o.buyer, 0) + 1
            station = tier if tier is not None else self.station_tier(level)
            open_orders = [self.reached(o, skills) for o in open_orders]
            for o in open_orders:
                v = self.order_value(o, skills, heat, station)
                hands = o.hands
                if o.buyer and per_buyer.get(o.buyer, 0) > 1:
                    hands -= trip / max(1, o.capacity) - trip / max(o.capacity, min(TRIP_SHARE_MAX, per_buyer[o.buyer]))
                if v["risk"] <= 0 or hands <= 0:
                    continue
                priority = 1 if (mode == "underground" and o.legality == "underground") else 0
                candidates.append((priority, v["risk"] / hands, idx, o, {**v, "hands": hands}))
            if mode in ("underground",) and level >= self.police["burglary"]["from_level"]:
                b = self.burglary(self.thief_tier(level))
                if b["risk"] > 0:
                    candidates.append((1, b["risk"] / b["minutes"], idx, "burglary", b))
            elif mode == "hybrid" and level >= self.police["burglary"]["from_level"]:
                b = self.burglary(self.thief_tier(level))
                if b["risk"] > 0:
                    candidates.append((0, b["risk"] / b["minutes"], idx, "burglary", b))
        candidates.sort(key=lambda c: (-c[0], -c[1]))
        nominal = risk = legal_minutes = 0.0
        events = []          # (count, p_bad, good, bad) for the spread
        picks = []
        fence_left = [self.police["burglary"]["fence_credits_per_hour"]] * len(members)
        used = [0.0] * len(members)
        for _, _, idx, o, v in candidates:
            if minutes[idx] <= 1e-9:
                continue
            if o == "burglary":
                n = minutes[idx] / v["minutes"]
                n = min(n, fence_left[idx] / max(1.0, v["haul"]))
                if n <= 1e-9:
                    continue
                minutes[idx] -= n * v["minutes"]
                fence_left[idx] -= n * v["haul"]
                nominal += n * v["nominal"]; risk += n * v["risk"]
                events.append((n, v["p_bad"], v["good"], v["bad"]))
                picks.append((members[idx][0], "burglary_t" + str(v["tier"]), round(n, 2)))
                used[idx] += n * v["minutes"]
                continue
            if demand[o.id] <= 1e-9:
                continue
            n = min(demand[o.id], minutes[idx] / v["hands"])
            for good, units in o.goods_units.items():
                n = min(n, supply[good] / units)
            if n <= 1e-9:
                continue
            minutes[idx] -= n * v["hands"]
            demand[o.id] -= n
            for good, units in o.goods_units.items():
                supply[good] -= n * units
            nominal += n * v["nominal"]; risk += n * v["risk"]
            if o.legality == "legal":
                legal_minutes += n * v["hands"]
            events.append((n, v["p_bad"], v["good"], v["bad"]))
            picks.append((members[idx][0], o.id, round(n, 2)))
            used[idx] += n * v["hands"]
        busy = sum(used)
        spread = self.spread(events)
        return {"nominal": nominal, "risk": risk, "legal_share": legal_minutes / busy if busy else 0.0, "picks": picks, **spread}

    def shares(self, career: str, level: int, h: dict) -> dict[str, float]:
        """Each pick's share of an hour's risk-adjusted credits."""
        skills = self.skills(career, level, "legal")
        tier = self.station_tier(level)
        out: dict[str, float] = {}
        for _, oid, n in h["picks"]:
            o = self.by_id.get(oid)
            if o is not None:
                out[oid] = out.get(oid, 0.0) + n * self.order_value(self.reached(o, skills), skills, 1, tier)["risk"]
        total = sum(out.values())
        return {k: v / total for k, v in out.items()} if total > 0 else {}

    @property
    def facility_orders(self) -> set[str]:
        out = set()
        for path in sorted((DATA / "work_orders").glob("*.json")):
            d = json.loads(path.read_text(encoding="utf-8"))
            if d.get("requires_facility"):
                out.add(path.stem)
        return out

    @staticmethod
    def spread(events: list[tuple[float, float, float, float]]) -> dict:
        """A seeded Monte Carlo of one hour's deliveries: each of the n (rounded with its fraction as a chance) succeeds with 1 - p_bad."""
        rng = random.Random(1029)
        hours = []
        for _ in range(SIMULATED_HOURS):
            total = 0.0
            for n, p, good, bad in events:
                k = int(n) + (1 if rng.random() < n - int(n) else 0)
                for _ in range(k):
                    total += bad if rng.random() < p else good
            hours.append(total)
        hours.sort()
        return {"p10": hours[SIMULATED_HOURS // 10], "p90": hours[SIMULATED_HOURS * 9 // 10], "losing": sum(1 for h in hours if h < 0) / SIMULATED_HOURS}

    # -- the tables -----------------------------------------------------------------------------------------------------

    def career_rows(self, mods: set[str] = em.ALL_MODS) -> list[dict]:
        key = ("careers", frozenset(mods))
        if key not in self._cache:
            self._cache[key] = self._career_rows(mods)
        return self._cache[key]

    def _career_rows(self, mods: set[str]) -> list[dict]:
        rows = []
        for career in em.CAREERS:
            for level in LEVELS:
                legal = self.hour([(career, level, "legal")], mods)
                ug = self.hour([(career, level, "underground")], mods)
                hybrid = self.hour([(career, level, "hybrid")], mods)
                experienced = self.hour([(career, level, "legal")], mods, facility=True)
                rows.append({"career": career, "level": level, "legal": legal, "underground": ug, "hybrid": hybrid, "experienced": experienced,
                             "ug_open": any(o.legality == "underground" and o.career == career and o.level <= level for o in self.m.orders)})
        return rows

    def crew_rows(self, mods: set[str] = em.ALL_MODS) -> list[dict]:
        rows = []
        for pair in (("farmer", "vintner"), ("smith", "gunsmith"), ("technician", "smith"), ("farmer", "gunsmith")):
            for level in (6, 15, 30):
                legal = self.hour([(pair[0], level, "legal"), (pair[1], level, "legal")], mods)
                hybrid = self.hour([(pair[0], level, "hybrid"), (pair[1], level, "hybrid")], mods)
                rows.append({"pair": " + ".join(pair), "level": level, "legal": legal, "hybrid": hybrid})
        return rows

    def timeline(self, members: list[tuple[str, str]], mods: set[str] = em.ALL_MODS, hours: float = 200.0, dt: float = 0.25) -> dict:
        """Active hours until each milestone for a household that plays one build: income at its level each step (risk-adjusted), XP levels it up, savings
        buy each milestone in turn (the cheapest listing of its category; rent means the deposit plus six prepaid hours, a purchase its price; a vehicle or
        a counter its price). Living costs (``living_costs_per_active_hour``) and rent of the home held are paid out of income."""
        targets = self.milestones()
        xp = [0.0] * len(members)
        saved = 0.0
        t = 0.0
        reached: dict[str, float] = {}
        rent = 0.0
        living = self.a["living_costs"]["credits_per_player_hour"] * len(members)
        cache: dict = {}
        while t < hours and len(reached) < len(targets):
            levels = tuple(em.level_for_xp(x) for x in xp)
            key = levels
            if key not in cache:
                cache[key] = self.hour([(c, lvl, self.timeline_mode(c, mode, lvl)) for (c, mode), lvl in zip(members, levels)], mods,
                                       facility="workshop_rent" in reached, no_cash=(saved < 50 and t < 1))
                cache[key]["xp"] = self._xp(members, levels, mods)
            h = cache[key]
            saved += (h["risk"] - living - rent) * dt
            for i, x in enumerate(h["xp"]):
                xp[i] += x * dt
            t += dt
            for name, (cost, hourly) in targets.items():
                if name not in reached and saved >= cost:
                    reached[name] = round(t, 1)
                    saved -= cost if not name.endswith("_rent") else cost - hourly * 6
                    if name.endswith("_rent") and name.startswith(("apartment", "house")):
                        rent = max(rent, hourly)
        return {name: reached.get(name) for name in targets}

    def timeline_mode(self, career: str, mode: str, level: int) -> str:
        """An underground specialist works legally until its career's first underground order opens: burglary pays Thief XP, not career XP, so a character
        who only burgled would never reach the level its underground trade opens at."""
        if mode != "underground":
            return mode
        first = min((o.level for o in self.m.orders if o.career == career and o.legality == "underground"), default=99)
        return "legal" if level < first else mode

    def _xp(self, members, levels, mods) -> list[float]:
        out = []
        for (career, mode), level in zip(members, levels):
            h = self.hour([(career, level, self.timeline_mode(career, mode, level))], mods)
            xp = 0.0
            for _, oid, n in h["picks"]:
                o = self.by_id.get(oid)
                xp += n * (o.xp if o else self.police["burglary"]["career_xp_per_visit"])
            out.append(xp)
        return out

    def milestones(self) -> dict[str, tuple[float, float]]:
        cats = {c["id"]: c for c in self.m.categories()}
        rules = self.m.rules
        out = {}
        def listing(cat):
            ls = [l for l in self.m.listings() if l["category"] == cat]
            return min(ls, key=lambda l: l["rent"]) if ls else None
        for name, cat in (("starter_rent", "motel_room"), ("apartment_rent", "small_apartment"), ("house_rent", "small_house"),
                          ("workshop_rent", "commercial_workshop"), ("apartment_buy", "small_apartment"), ("house_buy", "small_house"), ("villa_buy", "premium_residence")):
            l = listing(cat)
            if l is None:
                continue
            if name.endswith("_rent"):
                out[name] = (l.get("deposit", 0) + 6 * l["rent"], l["rent"])
            else:
                out[name] = (cats[cat]["buy_hint"], 0)
        vehicles = json.loads(em.VEHICLES.read_text(encoding="utf-8"))
        for v in vehicles.get("tiers", vehicles.get("vehicles", [])):
            if v["id"] in ("bicycle", "small_car"):
                out["vehicle_" + v["id"]] = (v["price"], 0)
        out["business_counter"] = (self.a.get("business_counter_price", 0) + out.get("workshop_rent", (0, 0))[0], 0)
        return dict(sorted(out.items(), key=lambda kv: kv[1][0]))


# ---------------------------------------------------------------------------------------------------------------- checks

def problems(b: Balance) -> list[str]:
    out = []
    rows = b.career_rows()
    by = {(r["career"], r["level"]): r for r in rows}
    for r in rows:
        legal, ug = r["legal"]["risk"], r["underground"]["risk"]
        if r["level"] >= 10 and r["ug_open"] and ug > 0 and legal < RULES["legal_after_unlock_min"] * ug:
            out.append(f"{r['career']} L{r['level']}: the best legal hour ({legal:.0f}) is under {RULES['legal_after_unlock_min']:.0%} of the risk-adjusted underground hour ({ug:.0f})")
        if r["ug_open"] and r["level"] >= 10 and ug > RULES["underground_risk_max"] * legal:
            out.append(f"{r['career']} L{r['level']}: the risk-adjusted underground hour ({ug:.0f}) is over {RULES['underground_risk_max']} times the legal hour ({legal:.0f})")
        if r["level"] == 15 and r["ug_open"] and r["hybrid"]["legal_share"] < RULES["hybrid_legal_share_min"]:
            out.append(f"{r['career']} L15: a hybrid character spends only {r['hybrid']['legal_share']:.0%} of its hour on legal work")
    for career in em.CAREERS:
        l6, l20 = by[(career, 6)]["legal"]["risk"], by[(career, 20)]["legal"]["risk"]
        if l20 < RULES["legal_growth_min"] * l6:
            out.append(f"{career}: the legal hour at level 20 ({l20:.0f}) is under {RULES['legal_growth_min']} times level 6 ({l6:.0f}): legal progression stops at the middle levels")
        shares = b.shares(career, 20, by[(career, 20)]["legal"])
        if shares and max(shares.values()) > RULES["single_activity_max"]:
            top = max(shares, key=shares.get)
            out.append(f"{career} L20: one order ({top}) makes {shares[top]:.0%} of the legal hour")
    for r in rows:
        if r["hybrid"]["risk"] + 1e-6 < r["legal"]["risk"]:
            out.append(f"{r['career']} L{r['level']}: the hybrid hour ({r['hybrid']['risk']:.0f}) is under the legal hour ({r['legal']['risk']:.0f})")
    l20 = [by[(c, 20)]["legal"]["risk"] for c in em.CAREERS]
    if min(l20) > 0 and max(l20) > RULES["career_spread_max"] * min(l20):
        out.append(f"at level 20 the best career's legal hour ({max(l20):.0f}) is over {RULES['career_spread_max']} times the worst's ({min(l20):.0f})")
    for career in em.CAREERS:
        t = b.timeline([(career, "legal")])
        if t.get("house_rent") is None or t["house_rent"] > RULES["rent_house_hours_max"]:
            out.append(f"{career}: a legal character rents a house after {t.get('house_rent')} active hours (at most {RULES['rent_house_hours_max']})")
        if t.get("house_buy") is None or t["house_buy"] > RULES["buy_house_hours_max"]:
            out.append(f"{career}: a legal character buys a house after {t.get('house_buy')} active hours (at most {RULES['buy_house_hours_max']})")
    t = b.timeline([("farmer", "legal"), ("smith", "legal")])
    if t.get("villa_buy") is None or t["villa_buy"] > RULES["crew_villa_hours_max"]:
        out.append(f"a legal Crew of two buys the villa after {t.get('villa_buy')} active hours (at most {RULES['crew_villa_hours_max']})")
    return out


# ---------------------------------------------------------------------------------------------------------------- report

def report(b: Balance) -> str:
    lines = []
    rows = b.career_rows()
    lines.append("### Net credits per active hour by career and level: legal, underground and hybrid (risk-adjusted; nominal in brackets)\n")
    t = [["career", "L", "legal", "underground", "hybrid", "hybrid on legal", "with trade premises", "underground p10..p90", "losing hours"]]
    for r in rows:
        u, h = r["underground"], r["hybrid"]
        t.append([r["career"], r["level"], f"{r['legal']['risk']:.0f}", f"{u['risk']:.0f} ({u['nominal']:.0f})" if r["ug_open"] or u["nominal"] != r["legal"]["nominal"] else "-",
                  f"{h['risk']:.0f} ({h['nominal']:.0f})", f"{h['legal_share']:.0%}", f"{r['experienced']['risk']:.0f}",
                  f"{u['p10']:.0f}..{u['p90']:.0f}", f"{u['losing']:.0%}"])
    lines.append(em.table(t[1:], t[0]))
    lines.append("\n### What each build takes at level 15 (orders and burglaries an hour)\n")
    t = [["career", "build", "picks"]]
    for r in rows:
        if r["level"] != 15:
            continue
        for build in ("legal", "underground", "hybrid"):
            t.append([r["career"], build, ", ".join(f"{oid} {n}" for _, oid, n in r[build]["picks"][:6])])
    lines.append(em.table(t[1:], t[0]))
    lines.append("\n### Two-player Crews: net credits per active hour of the household (risk-adjusted)\n")
    t = [["crew", "L", "legal", "hybrid", "hybrid on legal"]]
    for r in b.crew_rows():
        t.append([r["pair"], r["level"], f"{r['legal']['risk']:.0f}", f"{r['hybrid']['risk']:.0f}", f"{r['hybrid']['legal_share']:.0%}"])
    lines.append(em.table(t[1:], t[0]))
    lines.append("\n### Affordability: active hours until each milestone (risk-adjusted income, living costs and rent paid; '-' not within 200 hours)\n")
    names = list(b.milestones())
    t = [["household"] + names]
    for members in ([(c, "legal")] for c in em.CAREERS):
        tl = b.timeline(members)
        t.append([members[0][0] + " legal"] + [str(tl[n]) if tl[n] is not None else "-" for n in names])
    for c in ("gunsmith", "technician"):
        tl = b.timeline([(c, "hybrid")])
        t.append([c + " hybrid"] + [str(tl[n]) if tl[n] is not None else "-" for n in names])
        tl = b.timeline([(c, "underground")])
        t.append([c + " underground"] + [str(tl[n]) if tl[n] is not None else "-" for n in names])
    tl = b.timeline([("farmer", "legal"), ("smith", "legal")])
    t.append(["farmer + smith legal"] + [str(tl[n]) if tl[n] is not None else "-" for n in names])
    lines.append(em.table(t[1:], [h.replace("_", " ") for h in t[0]]))
    costs = b.milestones()
    lines.append("\nMilestone costs: " + ", ".join(f"{n.replace('_', ' ')} {c:.0f}" for n, (c, _) in costs.items()) + ".")
    lines.append("\n### Burglary by tier (one visit; the fences' hourly budget caps it)\n")
    t = [["tier", "haul to a fence", "minutes", "P(arrested)", "nominal", "risk-adjusted", "per active hour (risk)"]]
    for tier in range(1, 7):
        v = b.burglary(tier)
        t.append([tier, f"{v['haul']:.0f}", f"{v['minutes']:.1f}", f"{v['p_bad']:.0%}", f"{v['nominal']:.0f}", f"{v['risk']:.0f}",
                  f"{min(v['cap_per_hour'], 60 * b.eff * v['risk'] / v['minutes']):.0f}"])
    lines.append(em.table(t[1:], t[0]))
    found = problems(b)
    lines.append("\n### Rules\n")
    lines.append("all hold" if not found else "\n".join("* " + p for p in found))
    return "\n".join(lines)


BEGIN, END = "<!-- career-balance:begin -->", "<!-- career-balance:end -->"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--update-doc")
    parser.add_argument("--check-doc")
    args = parser.parse_args()
    b = Balance()
    if args.check:
        found = problems(b)
        for p in found:
            print("FAIL:", p)
        return 1 if found else 0
    text = report(b)
    if args.update_doc or args.check_doc:
        path = Path(args.update_doc or args.check_doc)
        doc = path.read_text(encoding="utf-8")
        head, rest = doc.split(BEGIN, 1)
        _, tail = rest.split(END, 1)
        new = head + BEGIN + "\n\n" + text + "\n\n" + END + tail
        if args.check_doc:
            if new != doc:
                print(f"{path} is stale: run --update-doc")
                return 1
            return 0
        path.write_text(new, encoding="utf-8")
        return 0
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
