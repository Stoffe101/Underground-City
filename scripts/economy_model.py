#!/usr/bin/env python3
"""A reproducible economy model of Underground City (D-098).

It answers, from the REAL game data and a short file of stated assumptions, the questions the owner asked about progression:
what does a delivery earn after its materials, how much is that per hour of play, how long until a first apartment, does rent
fit the income it is paid from, can one sale skip a milestone, can anyone buy low and sell high.

Inputs (nothing is copied; change the data and the answers change):

* the work orders Core loads (``rpg-core/src/main/resources/data/underground_city/work_orders``),
* the regional exchange prices, products and recipes (``content/gameplay/regional-content.json``),
* reference values for goods the exchange does not price (``content/gameplay/contracts.json``),
* the skill trees (which orders a skill unlocks, how many points a level gives),
* the property ladder (``data/underground_city/properties/ladder.json``: rent, deposit, purchase price),
* the vehicle and boat tiers (``content/gameplay/vehicle-tiers.json``: price, materials, the hours of wage a tier is meant to take),
* the Q Shop starter shop (``content/integrations/qshop``),
* ``content/gameplay/economy-model.json``: the ASSUMPTIONS, i.e. how many minutes a Minecraft task takes. Nobody has timed a person, so
  these are provisional and every output is labelled that way.

Money is Q Shop credits (the one currency). Time is *active* time: minutes with a human online, the same clock as work-order demand,
exchange restocking and rent. Net profit here is ``credits - cash spent at an exchange or shop``; goods a player gathers themselves
cost TIME, not credits, and the time is what turns net profit into credits per hour.

Run ``python scripts/economy_model.py`` for the report, ``--markdown`` for the document body, ``--check`` to exit 1 on a broken invariant.
This is arithmetic over data. It is not a playtest, and the report says so.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_regional_content as content  # noqa: E402
import validate_underground_balance as underground  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
ASSUMPTIONS = ROOT / "content/gameplay/economy-model.json"
LADDER = DATA / "properties/ladder.json"
VEHICLES = ROOT / "content/gameplay/vehicle-tiers.json"
TICKS_PER_MINUTE = 1200
CAREERS = ("farmer", "vintner", "smith", "gunsmith", "technician")
MAX_LEVEL = 30


def xp_at_level(level: int) -> int:
    n = max(1, level) - 1
    return 125 * n * n


def level_for_xp(xp: float) -> int:
    return min(MAX_LEVEL, 1 + int(math.floor(math.sqrt(xp / 125.0))))


# ------------------------------------------------------------------------------------------------------------------ data loading

def load_dir(name: str) -> dict[str, dict]:
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / name).glob("*.json"))}


@dataclass
class Order:
    id: str
    career: str
    level: int
    title: str
    legality: str
    credits: int
    xp: int
    capacity: int
    refill_minutes: float
    foreign_visits: int
    skill: str
    mod: str
    buyer: str
    requirements: list[dict]
    rewards: list[dict]
    max_level: int = 99
    # filled in by economics()
    goods_units: dict = field(default_factory=dict)
    cash: float = 0.0
    hands: float = 0.0
    wait: float = 0.0
    exchange_goods: set = field(default_factory=set)
    reward_value: float = 0.0
    notes: list = field(default_factory=list)

    @property
    def net(self) -> float:
        return self.credits - self.cash

    @property
    def per_hour(self) -> float:
        return 60.0 * self.net / self.hands if self.hands > 0 else 0.0

    @property
    def margin(self) -> float:
        return self.credits / self.cash if self.cash > 0 else float("inf")


class Model:
    def __init__(self, scale: float = 1.0):
        self.a = json.loads(ASSUMPTIONS.read_text(encoding="utf-8"))
        self.scale = scale  # sensitivity: every hands-on minute is multiplied by this
        self.regional = content.load_source()
        self.contracts = content.load_contracts()
        self.refs = self.contracts["reference_values"]
        self.prices = {f"underground_city:{c['id']}": c["stock"]["unit_price"] for c in self.regional["commodities"]}
        self.stock = {f"underground_city:{c['id']}": c["stock"] for c in self.regional["commodities"]}
        self.commodity_city = {f"underground_city:{c['id']}": c["city"] for c in self.regional["commodities"]}
        self.products = {f"underground_city:{p['id']}": p for p in self.regional["products"]}
        self.specials = {f"underground_city:{p['id']}": p for p in self.regional.get("specials", [])}
        self.trees = load_dir("skill_trees")
        self.ladder = json.loads(LADDER.read_text(encoding="utf-8"))
        self.rules = self.ladder["rules"]
        self.orders = self._load_orders()
        for order in self.orders:
            self._economics(order)

    # -- orders -----------------------------------------------------------------------------------------------------------

    def _load_orders(self) -> list[Order]:
        orders = []
        for path in sorted((DATA / "work_orders").glob("*.json")):
            d = json.loads(path.read_text(encoding="utf-8"))
            reqs = d.get("requirements") or [{"item": d["item"], "count": d["count"]}]
            terms = d
            orders.append(Order(
                id=path.stem, career=d["career"], level=d["level"], title=d["title"], legality=d.get("legality", "legal"),
                credits=d["credits"], xp=d["xp"], capacity=d["capacity"], refill_minutes=d["refill_ticks"] / TICKS_PER_MINUTE,
                foreign_visits=d.get("foreign_visits", 0), skill=d.get("requires_skill", ""), mod=d.get("requires_mod", ""),
                buyer=d.get("buyer", ""), requirements=reqs, rewards=terms.get("rewards", []), max_level=d.get("max_level", 99)))
        return orders

    def minutes(self, item: str) -> float:
        table = self.a["hands_on_minutes_each"]
        if item not in table:
            raise KeyError(f"economy-model.json has no hands-on minutes for {item}")
        return table[item] * self.scale

    def _economics(self, o: Order) -> None:
        overhead = self.a["overhead"]
        run = overhead["exchange_run_round_trip_minutes"] / overhead["exchange_run_orders_per_trip"]
        cash = hands = wait = 0.0
        for req in o.requirements:
            item, count = req["item"], req["count"]
            if req.get("gun_ids"):
                gm = self.a["gun_minutes"]
                guns = self.refs["guns"]
                cheapest = min(req["gun_ids"], key=lambda g: guns[g])
                iron = guns[cheapest] / gm["iron_value_credits"]
                hands += count * (iron * gm["minutes_per_iron"] * self.scale + gm["bench_minutes"])
                continue
            if item in self.products or item in self.specials:
                p = self.products.get(item) or self.specials[item]
                inputs = p.get("inputs")
                if inputs:
                    crafts = math.ceil(count / p["count"])
                    for key, n in inputs.items():
                        if ":" in key and not key.startswith("underground_city:"):
                            hands += crafts * n * self.minutes(key)
                        else:
                            good = key if ":" in key else f"underground_city:{key}"
                            cash += crafts * n * self.prices[good]
                            o.exchange_goods.add(good)
                            o.goods_units[good] = o.goods_units.get(good, 0) + crafts * n
                    hands += crafts * self.a["overhead"]["craft_operation_minutes"]
                else:  # a harvest-only Core good, or a workshop good (D-163): its operating cost per unit is in the reference values
                    cash += count * (self.refs["harvest_units"].get(item) or self.refs.get("workshop_units", {}).get(item, 0))
                    hands += count * self.minutes(item)
                    wait = max(wait, self.a["wait_minutes"].get(item, 0))
                continue
            if item in self.prices:  # a raw regional commodity asked for directly
                cash += count * self.prices[item]
                o.exchange_goods.add(item)
                o.goods_units[item] = o.goods_units.get(item, 0) + count
                continue
            hands += count * self.minutes(item)
            wait = max(wait, self.a["wait_minutes"].get(item, 0))
        if o.exchange_goods:
            hands += run
        if o.buyer:  # the goods must be carried to the buyer's city; one trip delivers every open slot
            hands += overhead["buyer_trip_round_trip_minutes"] / max(1, o.capacity)
        hands += overhead["terminal_minutes_per_order"]
        for reward in o.rewards:
            o.reward_value += reward.get("count", 1) * self.refs["rewards"].get(reward["item"], 0)
        o.cash, o.hands, o.wait = cash, hands, wait

    # -- skills -----------------------------------------------------------------------------------------------------------

    def unlocked_skills(self, career: str, level: int, underground_too: bool = False) -> set[str]:
        """A sensible spending order: cheapest legal/core/utility node first, prerequisites respected, one point per level above 1."""
        nodes = self.trees[career]["nodes"]
        points = level - 1
        have: set[str] = set()
        progress = True
        while progress:
            progress = False
            for node in sorted(nodes, key=lambda n: (n["min_level"], n["cost"], n["id"])):
                if node["id"] in have or node["min_level"] > level or node["cost"] > points:
                    continue
                if node["branch"] == "underground" and not underground_too:
                    continue
                if any(r not in have for r in node["requires"]):
                    continue
                have.add(node["id"])
                points -= node["cost"]
                progress = True
        return have

    def payout_percent(self, career: str, legality: str, skills: set[str]) -> int:
        """The contract-rate skills the character holds for orders of this legality (SkillRules.payout): summed over the tree, capped at 12."""
        key = "underground_payout_percent" if legality == "underground" else "legal_payout_percent"
        total = sum(n["effects"].get(key, 0) for n in self.trees[career]["nodes"] if n["id"] in skills)
        return min(12, total)

    def best_percent(self, career: str, legality: str, up_to_level: int = 99) -> int:
        """The most a character of this career can earn on top of an order of this legality, with every payout skill they can hold at that level."""
        return self.payout_percent(career, legality, {n["id"] for n in self.trees[career]["nodes"] if n["min_level"] <= up_to_level})

    def paid(self, o: Order, skills: set[str]) -> int:
        """What delivering the order pays a character with these skills: whole credits, the bonus rounded down, as in the game."""
        return o.credits + o.credits * self.payout_percent(o.career, o.legality, skills) // 100

    def best_credits(self, o: Order) -> int:
        """The highest payment the order can ever make (every payout skill held): what the exploit checks must be safe against."""
        return o.credits + o.credits * self.best_percent(o.career, o.legality, o.max_level) // 100

    def available(self, career: str, level: int, mods: set[str], legal_only: bool = True) -> list[Order]:
        skills = self.unlocked_skills(career, level, underground_too=not legal_only)
        out = []
        for o in self.orders:
            if o.career != career or o.level > level or level > o.max_level:
                continue
            if legal_only and o.legality != "legal":
                continue
            if o.mod and o.mod not in mods:
                continue
            if o.skill and o.skill not in skills:
                continue
            out.append(o)
        return out

    # -- income -----------------------------------------------------------------------------------------------------------

    def supply_pool(self) -> dict[str, float]:
        """Units per hour each regional commodity can sustain: the shelf refills ``units_per_interval`` every ``interval_minutes`` and the opening
        stock is spread over the horizon. The shelf is SHARED by every player in the world (D-023), so a household draws on one pool."""
        horizon = self.a["supply_horizon_hours"]
        return {good: s["units_per_interval"] * 60.0 / s["interval_minutes"] + s["initial"] / horizon for good, s in self.stock.items()}

    def household_income(self, members: list[tuple[str, int]], mods: set[str]) -> dict:
        """The best hour a household can have. Each member has ``efficiency`` x 60 hands-on minutes. Orders are taken in order of net credits per
        hands-on minute across everyone; an order's employer demand (a burst of ``capacity`` spread over the horizon plus one slot per refill) and
        every regional commodity's shelf are shared by the whole household. Returns net credits, XP per member, and the picks."""
        horizon = self.a["supply_horizon_hours"]
        eff = self.a["efficiency"]["hands_on_share_of_active_time"]
        minutes = [60.0 * eff for _ in members]
        demand = {o.id: o.capacity / horizon + 60.0 / o.refill_minutes for o in self.orders}
        supply = self.supply_pool()
        candidates = []
        bonus: dict[tuple[int, str], int] = {}
        for idx, (career, level) in enumerate(members):
            skills = self.unlocked_skills(career, level, underground_too=False)
            for o in self.available(career, level, mods):
                extra = self.paid(o, skills) - o.credits
                bonus[(idx, o.id)] = extra
                if o.net + extra > 0 and o.hands > 0:
                    candidates.append(((o.net + extra) / o.hands, idx, o))
        candidates.sort(key=lambda c: -c[0])
        net = 0.0
        xp = [0.0] * len(members)
        gross = 0.0
        picks: list[tuple] = []
        for _, idx, o in candidates:
            if minutes[idx] <= 1e-9 or demand[o.id] <= 1e-9:
                continue
            n = min(demand[o.id], minutes[idx] / o.hands)
            for good, units in o.goods_units.items():
                n = min(n, supply[good] / units)
            if n <= 1e-9:
                continue
            minutes[idx] -= n * o.hands
            demand[o.id] -= n
            for good, units in o.goods_units.items():
                supply[good] -= n * units
            extra = bonus[(idx, o.id)]
            net += n * (o.net + extra)
            gross += n * (o.credits + extra)
            xp[idx] += n * o.xp
            picks.append((members[idx][0], o.id, round(n, 2)))
        return {"net": net, "gross": gross, "xp": xp, "picks": picks, "idle_minutes": sum(minutes)}

    def income(self, career: str, level: int, mods: set[str], share: float = 1.0) -> dict:
        """One player alone. ``share`` < 1 models a second player of the same career sharing the employers' demand."""
        result = self.household_income([(career, level)], mods)
        if share < 1.0:
            result = {**result, "net": result["net"] * share, "xp": [x * share for x in result["xp"]]}
        result["xp"] = result["xp"][0]
        return result

    def first_payout(self, career: str, mods: set[str]) -> dict:
        """Minutes of play until a brand-new citizen holds their first credits, and the order that does it."""
        best = None
        for o in self.available(career, 1, mods):
            if o.net <= 0 and any(x.net > 0 for x in self.available(career, 1, mods)):
                continue                                                   # nobody's first credits come from an order that costs more than it pays
            minutes = o.hands + o.wait
            if best is None or minutes < best[0]:
                best = (minutes, o)
        return {"minutes": best[0], "order": best[1].id, "credits": best[1].credits, "net": best[1].net} if best else {"minutes": None}

    # -- property --------------------------------------------------------------------------------------------------------

    def listings(self):
        """The district listings and the furnished cities' templates (D-127); a template stands for every unit of its building and is checked like a listing."""
        city = [dict(t, id="city:" + t["building"].split(":", 1)[1], title=t["area"] + " " + t["category"]) for t in self.ladder.get("city_listings", [])]
        return self.ladder["listings"] + city

    def categories(self):
        return self.ladder["categories"]


# ------------------------------------------------------------------------------------------------------------------ a scarcity world

MATERIALS = ROOT / "content/gameplay/materials-supply.json"
SHOP_SUPPLY = ROOT / "content/gameplay/shop-supply.json"
SURVIVAL_SUPPLY = ROOT / "content/gameplay/survival-supply.json"
SITES = DATA / "resource_sites"
FENCES = DATA / "fences"
ORE_BLOCKS = {"minecraft:iron_ore": "minecraft:iron_ingot", "minecraft:copper_ore": "minecraft:copper_ingot", "minecraft:coal_ore": "minecraft:coal"}


class ScarcityModel(Model):
    """A new world made with the scarcity package (D-120): nothing to mine. Every ore-derived unit an order needs comes off a city's finite shelf
    (``source="shelf"``: bought at the cheapest shelf price, plus an exchange run, from a pool the shelves refill) or, for the base metals the quarry
    grows back, out of the quarry (``source="quarry"``: quarry minutes each, from a pool of veins x 60 / interval an hour, shared by the world; the
    other ores still come off the shelves). The default model's mining minutes for those units are taken out."""

    def __init__(self, source: str, scale: float = 1.0):
        self.source = source
        self.sc = json.loads(ASSUMPTIONS.read_text(encoding="utf-8"))["scarcity"]
        self.shelves: dict[str, list[dict]] = {}
        for line in json.loads(MATERIALS.read_text(encoding="utf-8"))["lines"]:
            self.shelves.setdefault(line["item"], []).append(line)
        self.quarry: dict[str, float] = {}
        for path in sorted(SITES.glob("*.json")):
            site = json.loads(path.read_text(encoding="utf-8"))
            for vein in site["veins"]:
                root = ORE_BLOCKS.get(vein["block"])
                if root:
                    self.quarry[root] = self.quarry.get(root, 0.0) + 60.0 / site["interval_minutes"]
        super().__init__(scale)

    def cheapest(self, root: str) -> float:
        lines = self.shelves.get(root)
        if not lines:
            raise KeyError(f"no shelf sells {root}: a scarcity world has no route to it")
        return min(l["price"] for l in lines)

    def ore_units(self, o: Order) -> dict[str, float]:
        """The ore-derived units an order consumes, from its requirements and the vanilla inputs of the products it asks for."""
        content_of = self.sc["ore_content"]
        units: dict[str, float] = {}

        def add(item: str, n: float) -> None:
            for root, k in content_of.get(item, {}).items():
                units[root] = units.get(root, 0.0) + n * k

        for req in o.requirements:
            item, count = req["item"], req["count"]
            if req.get("gun_ids"):
                guns = self.refs["guns"]
                iron = guns[min(req["gun_ids"], key=lambda g: guns[g])] / self.a["gun_minutes"]["iron_value_credits"]
                units["minecraft:iron_ingot"] = units.get("minecraft:iron_ingot", 0.0) + count * iron
                continue
            if item in self.products or item in self.specials:
                p = self.products.get(item) or self.specials[item]
                crafts = math.ceil(count / p["count"]) if p.get("inputs") else 0
                for key, n in (p.get("inputs") or {}).items():
                    if ":" in key and not key.startswith("underground_city:"):
                        add(key, crafts * n)
                continue
            if item not in self.prices:
                add(item, count)
        return units

    def _economics(self, o: Order) -> None:
        super()._economics(o)
        units = self.ore_units(o)
        if not units:
            return
        had_run = bool(o.exchange_goods)
        for root, n in units.items():
            mined = self.a["gun_minutes"]["minutes_per_iron"] * self.scale if root == "minecraft:iron_ingot" else self.minutes(root)
            o.hands -= n * mined
            if self.source == "quarry" and root in self.quarry:
                o.hands += n * self.sc["quarry_minutes_each"][root] * self.scale
                good = "quarry:" + root
            else:
                o.cash += n * self.cheapest(root)
                good = "shelf:" + root
                o.exchange_goods.add(good)
            o.goods_units[good] = o.goods_units.get(good, 0.0) + n
        if o.exchange_goods and not had_run:
            overhead = self.a["overhead"]
            o.hands += overhead["exchange_run_round_trip_minutes"] / overhead["exchange_run_orders_per_trip"]
        o.hands = max(o.hands, self.a["overhead"]["terminal_minutes_per_order"])

    def supply_pool(self) -> dict[str, float]:
        pool = super().supply_pool()
        horizon = self.a["supply_horizon_hours"]
        for root, lines in self.shelves.items():
            pool["shelf:" + root] = sum(l["units_per_interval"] * 60.0 / l["interval_minutes"] + l["initial"] / horizon for l in lines)
        for root, rate in self.quarry.items():
            pool["quarry:" + root] = rate
        return pool


def scarcity_rows(model: Model) -> list[dict]:
    """Each career's legal net per active hour in the ore world, with the shelves only, and with the quarry for the base metals, by level."""
    shelf, quarry = ScarcityModel("shelf", model.scale), ScarcityModel("quarry", model.scale)
    rows = []
    for level in (1, 3, 5, 8):
        for career in CAREERS:
            rows.append({"career": career, "level": level, "ore": model.income(career, level, ALL_MODS)["net"],
                         "shelf": shelf.income(career, level, ALL_MODS)["net"], "quarry": quarry.income(career, level, ALL_MODS)["net"]})
    return rows


def quarry_and_shelves(model: Model) -> list[list]:
    q = ScarcityModel("quarry", model.scale)
    pool = q.supply_pool()
    rows = []
    for root in sorted(set(q.shelves) | set(q.quarry)):
        rows.append([root.split(":")[1], f"{q.cheapest(root)}" if root in q.shelves else "-", f"{pool.get('shelf:' + root, 0):.1f}", f"{q.quarry.get(root, 0):.0f}"])
    return rows


# ------------------------------------------------------------------------------------------------------------------ salvage yards (D-138)

SALVAGE_BLOCKS = {"underground_city:scrap_heap": DATA / "loot_tables/blocks/scrap_heap.json"}


def expected_drops(table: dict) -> dict[str, float]:
    """The mean count of each item one roll of a simple block loot table gives (pools of integer rolls, weighted entries, uniform set_count)."""
    out: dict[str, float] = {}
    for pool in table["pools"]:
        entries = pool["entries"]
        total = sum(e.get("weight", 1) for e in entries)
        for e in entries:
            if e["type"] != "minecraft:item":
                continue
            count = 1.0
            for f in e.get("functions", []):
                if f["function"] == "minecraft:set_count":
                    c = f["count"]
                    count = (c["min"] + c["max"]) / 2.0 if isinstance(c, dict) else float(c)
            out[e["name"]] = out.get(e["name"], 0.0) + pool["rolls"] * count * e.get("weight", 1) / total
    return out


def salvage_rows(model: "Model") -> list[dict]:
    """Each salvage yard: one row per place (its four rotations are one yard; a world builds one), heaps, interval, and the mean items an active hour if every heap is stripped."""
    yards: dict[str, dict] = {}
    for path in sorted(SITES.glob("*.json")):
        site = json.loads(path.read_text(encoding="utf-8"))
        heaps = [v for v in site["veins"] if v["block"] in SALVAGE_BLOCKS]
        if not heaps:
            continue
        family = site["district"].rsplit("_", 1)[0] if site["district"].rsplit("_", 1)[-1] in ("n", "e", "s", "w") else site["district"]
        per_hour = len(heaps) * 60.0 / site["interval_minutes"]
        drops: dict[str, float] = {}
        for block, table_path in SALVAGE_BLOCKS.items():
            n = sum(1 for v in heaps if v["block"] == block) * 60.0 / site["interval_minutes"]
            for item, mean in expected_drops(json.loads(table_path.read_text(encoding="utf-8"))).items():
                drops[item] = drops.get(item, 0.0) + mean * n
        yards[family] = {"yard": family, "heaps": len(heaps), "interval": site["interval_minutes"], "heaps_per_hour": per_hour, "drops": drops}
    return list(yards.values())


# ------------------------------------------------------------------------------------------------------------------ fences (D-125)

def honest_prices(model: Model) -> dict[str, float]:
    """The cheapest price anybody honest sells each item at: the cities' shelves and shops, the survival lines, the exchange, the starter shop."""
    prices: dict[str, float] = {}

    def offer(item: str, price: float) -> None:
        prices[item] = min(price, prices.get(item, float("inf")))

    for path in (MATERIALS, SHOP_SUPPLY, SURVIVAL_SUPPLY):
        for line in json.loads(path.read_text(encoding="utf-8"))["lines"]:
            offer(line["item"], line["price"])
    for item, price in model.prices.items():
        offer(item, price)
    shop = json.loads((ROOT / "content/integrations/qshop/config/qshop/shops/uc_starter_supply.json").read_text(encoding="utf-8"))
    for e in shop["entries"]:
        if e["type"] == "BUY":
            offer(e["item"]["item"], e["price"] / e["item"]["count"])
    return prices


def fences(model: Model) -> tuple[list[dict], dict[str, int]]:
    if hasattr(model, "fence_override"):          # a test's own fences and values
        return model.fence_override
    values = json.loads((FENCES / "values.json").read_text(encoding="utf-8"))["values"]
    out = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(FENCES.glob("*.json")) if p.name != "values.json"]
    return out, values


def fence_rows(model: Model) -> list[dict]:
    """Each fence's best rate (with every payout skill), the most it pays an hour once its opening budget is spent, against the best legal hour at level 5."""
    found, _ = fences(model)
    best_legal = max(model.income(c, 5, ALL_MODS)["net"] for c in CAREERS)
    rows = []
    for f in found:
        rate = min(60, max(f["rate_percent"], f["same_city_rate_percent"]) + 10)
        hourly = f["refill_credits"] * 60.0 / f["refill_minutes"]
        rows.append({"city": f["city"], "title": f["title"], "rate": f["rate_percent"], "same_city": f["same_city_rate_percent"], "best_rate": rate,
                     "budget": f["budget"], "hourly": hourly, "best_legal": best_legal, "share": hourly / best_legal if best_legal else float("inf")})
    return rows


def fence_problems(model: Model) -> list[str]:
    problems = []
    found, values = fences(model)
    prices = honest_prices(model)
    best_rate = max(min(60, max(f["rate_percent"], f["same_city_rate_percent"]) + 10) for f in found) if found else 0
    for item, worth in values.items():
        if item in prices and worth > prices[item]:
            problems.append(f"fence: {item} is worth {worth} to a fence but sells honestly at {prices[item]:g}: buying it and fencing it would launder credits")
        if item in prices and worth * best_rate // 100 >= prices[item]:
            problems.append(f"fence: {item} fences for {worth * best_rate // 100} at the best rate, not below its honest price {prices[item]:g}")
    limit = model.a["targets"]["fence_hourly_ceiling_vs_best_legal_max"]
    for row in fence_rows(model):
        if row["share"] > limit:
            problems.append(f"fence: {row['title']} pays up to {row['hourly']:.0f} an hour after its budget, {row['share']:.0%} of the best legal hour at level 5 (limit {limit:.0%})")
    return problems


# ------------------------------------------------------------------------------------------------------------------ timelines

def simulate(model: Model, careers: list[str], mods: set[str], hours: float = 400.0, dt: float = 0.25) -> dict:
    """One household of 1-3 players working their careers while sharing one home. Rent is paid per household, once per cycle, from the
    household's credits, and the clock advances once however many members are online. Returns the active hour at which each milestone
    is first reached.

    Spending policy (deliberately plain, and stated so it can be argued with): the household takes the cheapest motel at once (the welcome
    hours are free); it rents the next apartment when it can pay the deposit and six cycles of rent and the rent is at most 20 percent of
    its net income; it buys that apartment when it holds the purchase price plus six cycles of rent; it rents the house when the rent is at
    most 25 percent of net income and it can pay the deposit and six cycles; it buys the house when it holds the price plus six cycles of the
    upkeep it already pays. Owned homes cost their upkeep, rented homes their rent; a renter who buys gets the deposit back."""
    rules = model.rules
    cycle_hours = rules["cycle_minutes"] / 60.0
    by_category = {}
    for listing in model.listings():
        by_category.setdefault(listing["category"], listing)
    flat, house, motel = by_category["small_apartment"], by_category["workshop_house"], by_category["motel_room"]
    living = model.a["living_costs"]["credits_per_player_hour"] * len(careers)
    xp = [0.0] * len(careers)
    credits = 0.0
    state = {"motel": "rent", "flat": None, "house": None}   # None, "rent" or "own"
    welcome = rules["welcome_motel_cycles"]
    clock, t = 0.0, 0.0
    milestones: dict[str, float] = {"motel_rented": 0.0}
    net = 0.0
    income_net = 0.0
    while t < hours:
        levels = [level_for_xp(xp[i]) for i in range(len(careers))]
        inc = model.household_income(list(zip(careers, levels)), mods)
        income_net = inc["net"]
        for i in range(len(careers)):
            xp[i] += inc["xp"][i] * dt
        credits += (income_net - living) * dt
        for i, career in enumerate(careers):
            if levels[i] in (3, 5, 8, 10, 15, 20):
                milestones.setdefault(f"{career}_level_{levels[i]}", t)
        if careers:                      # the rent clock is an ACTIVE clock: it runs only while a member is online, once, whatever their number
            clock += dt
        while clock >= cycle_hours:
            clock -= cycle_hours
            for name, listing in (("motel", motel), ("flat", flat), ("house", house)):
                kind = state[name]
                if kind == "rent":
                    if name == "motel" and welcome > 0:
                        welcome -= 1
                    else:
                        credits -= listing["rent"]
                elif kind == "own":
                    credits -= listing["upkeep"]
        # decisions (one purchase per step)
        if state["flat"] is None and flat["rent"] <= 0.20 * income_net and credits >= flat["deposit"] + 6 * flat["rent"]:
            credits -= flat["deposit"]
            state["flat"], state["motel"] = "rent", None
            milestones["rented_flat"] = t
        elif state["flat"] == "rent" and credits >= flat["buy_price"] + 6 * flat["rent"]:
            credits += flat["deposit"] - flat["buy_price"]
            state["flat"] = "own"
            milestones["bought_flat"] = t
        elif state["house"] is None and state["flat"] and house["rent"] <= 0.25 * income_net and credits >= house["deposit"] + 6 * house["rent"]:
            credits -= house["deposit"]
            state["house"] = "rent"
            milestones["rented_house"] = t
        elif state["house"] is None and state["flat"] == "own" and credits >= house["buy_price"] + 6 * house["upkeep"]:
            credits -= house["buy_price"]
            state["house"] = "own"
            milestones["bought_house"] = t
        elif state["house"] == "rent" and credits >= house["buy_price"] + 6 * house["upkeep"]:
            credits += house["deposit"] - house["buy_price"]
            state["house"] = "own"
            milestones["bought_house"] = t
        t += dt
    return {"milestones": milestones, "final_credits": credits, "final_levels": [level_for_xp(xp[i]) for i in range(len(careers))],
            "net_per_hour_end": income_net}


# ------------------------------------------------------------------------------------------------------------------ checks

def exploit_checks(model: Model) -> list[str]:
    """Loops that turn credits into more credits without work. Each returns a finding string."""
    findings: list[str] = []
    shop = json.loads((ROOT / "content/integrations/qshop/config/qshop/shops/uc_starter_supply.json").read_text(encoding="utf-8"))
    buy_per_unit, sell_per_unit = {}, {}
    for e in shop["entries"]:
        unit = e["price"] / e["item"]["count"]
        (buy_per_unit if e["type"] == "BUY" else sell_per_unit)[e["item"]["item"]] = unit
    # 1. the starter shop: buy an item and sell it back for more
    for item, sell in sell_per_unit.items():
        if item in buy_per_unit and sell >= buy_per_unit[item]:
            findings.append(f"shop loop: {item} is bought at {buy_per_unit[item]:.2f} and sold at {sell:.2f} per unit")
    # 2. an order that pays more than the SHOP charges for the goods it asks for (buy at the shop, deliver unchanged)
    for o in model.orders:
        shop_cost = 0.0
        ok = True
        for r in o.requirements:
            if r["item"] in buy_per_unit:
                shop_cost += buy_per_unit[r["item"]] * r["count"]
            else:
                ok = False
        if ok and shop_cost and model.best_credits(o) >= shop_cost:
            findings.append(f"buy-and-deliver loop: order {o.id} pays up to {model.best_credits(o)} (with every payout skill) for goods the shop sells for {shop_cost:.1f}")
    # 3. regional commodity bought at the exchange and delivered raw for more than its price: allowed only for a declared courier errand whose
    #    margin is modest and whose throughput the shelf itself caps
    courier = model.a["courier_orders"]
    supply = model.supply_pool()
    for o in model.orders:
        if all(r["item"] in model.prices for r in o.requirements):
            cost = sum(model.prices[r["item"]] * r["count"] for r in o.requirements)
            best = model.best_credits(o)
            if best <= cost:
                continue
            if o.id not in courier["ids"]:
                findings.append(f"exchange pass-through: order {o.id} pays up to {best} for goods the exchange sells for {cost}")
                continue
            if best / cost > courier["max_margin"]:
                findings.append(f"courier errand {o.id} pays {best / cost:.2f}x the exchange price with every payout skill (limit {courier['max_margin']})")
            per_hour = min([supply[g] / u for g, u in o.goods_units.items()] + [o.capacity / model.a["supply_horizon_hours"] + 60.0 / o.refill_minutes])
            if per_hour * (best - cost) > courier["max_net_per_hour"]:
                findings.append(f"courier errand {o.id}: {per_hour:.2f} deliveries an hour x {best - cost:.0f} net = {per_hour * (best - cost):.0f}/h (limit {courier['max_net_per_hour']})")
    # 4. an order whose delivery pays more than its shop-priced goods plus exchange goods when everything can be bought
    return findings


def shop_sell_vs_orders(model: Model) -> list[str]:
    """Surplus sold to the shop must stay below what the same goods earn in an order, or nobody would deliver."""
    shop = json.loads((ROOT / "content/integrations/qshop/config/qshop/shops/uc_starter_supply.json").read_text(encoding="utf-8"))
    out = []
    for e in shop["entries"]:
        if e["type"] != "SELL":
            continue
        unit = e["price"] / e["item"]["count"]
        for o in model.orders:
            for r in o.requirements:
                if r["item"] == e["item"]["item"] and len(o.requirements) == 1:
                    per_unit = o.credits / r["count"]
                    if per_unit < unit:
                        out.append(f"{o.id} pays {per_unit:.2f} per {r['item']}, the shop pays {unit:.2f}")
    return out


# ------------------------------------------------------------------------------------------------ a player's counter and shortage contracts (D-207, D-208)

BUSINESS_TAX = 0.12                # PlayerBusiness.TAX
BUSINESS_MAX_PRICE = 60            # PlayerBusiness.CUSTOMER_MAX_PRICE
BUSINESS_SALES_PER_HOUR = 8        # PlayerBusiness.SALES_PER_HOUR
BUSINESS_MIN_TAX = 2               # PlayerBusiness.MIN_TAX
CONTRACT_PAY = 1.5                 # ShortageContracts.PAY
MAX_TOTAL_DISCOUNT = 0.10          # SkillNode.MAX_TOTAL_DISCOUNT
REGIONAL_STOCK = ROOT / "rpg-core/src/main/resources/data/underground_city/regional_stock"


def java_round(x: float) -> int:
    return int(math.floor(x + 0.5))


def business_tax(price: int) -> int:
    """PlayerBusiness.tax: rounded up, at least MIN_TAX."""
    return max(BUSINESS_MIN_TAX, math.ceil(price * BUSINESS_TAX - 1e-9))


def regional_lines() -> list[dict]:
    return [json.loads(f.read_text(encoding="utf-8")) for f in sorted(REGIONAL_STOCK.rglob("*.json"))]


def business_problems(model: Model) -> list[str]:
    """A counter must never turn shop goods into credits, a shortage contract must pay more than the shop and less than it charges, and a counter's best hour must stay under the best
    legal hour at the level premises are taken (it sells only what was made or bought, to people who walk in)."""
    problems = []
    lines = regional_lines()
    lowest: dict[str, int] = {}
    for line in lines:
        if "item" in line and line.get("unit_price", 0) > 0:
            lowest[line["item"]] = min(lowest.get(line["item"], line["unit_price"]), line["unit_price"])
    for item, price in lowest.items():
        if not BUSINESS_MIN_TAX < price <= BUSINESS_MAX_PRICE:
            continue
        net = price - business_tax(price)
        cheapest_buy = max(1, java_round(price * (1 - MAX_TOTAL_DISCOUNT)))   # RegionalMarket rounds a discounted price
        if net >= cheapest_buy:
            problems.append(f"business: {item} bought at {cheapest_buy} with every discount sells for {net} at a counter after tax: a loop")
    for line in lines:
        supply = line.get("supply")
        if not supply:
            continue
        unit = max(1, min(line["unit_price"] - 1, java_round(supply["pays"] * CONTRACT_PAY)))
        if unit <= supply["pays"]:
            continue   # too cheap for a contract to pay more than the shop: none is posted (ShortageContracts.order)
        if not unit < line["unit_price"]:
            problems.append(f"shortage contract: {line['item']} pays {unit} a unit, not between the shop's {supply['pays']} and its price {line['unit_price']}")
    sellable = [p for p in lowest.values() if BUSINESS_MIN_TAX < p <= BUSINESS_MAX_PRICE]
    hourly = BUSINESS_SALES_PER_HOUR * max(p - business_tax(p) for p in sellable) if sellable else 0
    best_legal = max(model.income(c, 12, ALL_MODS)["net"] for c in CAREERS)
    limit = model.a["targets"]["business_hourly_ceiling_vs_best_legal_max"]
    if hourly > limit * best_legal:
        problems.append(f"business: a counter's best hour takes {hourly} after tax, over {limit:.0%} of the best legal hour at level 12 ({best_legal:.0f})")
    return problems


PREMISES = {"commercial_workshop", "warehouse"}


# homes the model takes to be a two-income household's (D-176): the villa's three bedrooms are for a couple or a Crew, as the business premises are a Crew's (an assumption, not a measurement)
SHARED_HOMES = {"premium_residence"}


def rent_shares(model: Model, mods: set[str]) -> list[dict]:
    """Rent as a share of the net income a household typically has at the level where it rents that category."""
    rows = []
    # the level at which a household typically takes each kind of home; business premises (D-127) are taken late, and usually by a Crew with two incomes
    typical = {"motel_room": 1, "small_apartment": 4, "comfortable_apartment": 5, "large_apartment": 6, "small_house": 7, "workshop_house": 8, "commercial_workshop": 12, "warehouse": 15,
               "premium_residence": 15}
    for listing in model.listings():
        level = typical.get(listing["category"], 6)
        incomes = {c: model.income(c, level, mods)["net"] for c in CAREERS}
        mean = sum(incomes.values()) / len(incomes)
        worst = min(incomes, key=incomes.get)
        # business premises are a Crew's: the household pays them from two incomes (an assumption of the model, not a measurement; docs/technical/economy-model.md)
        if listing["category"] in PREMISES or listing["category"] in SHARED_HOMES:
            mean *= 2
        rows.append({"listing": listing["id"], "category": listing["category"], "rent": listing["rent"], "level": level,
                     "mean_income": mean, "mean_share": listing["rent"] / mean if mean else None,
                     "worst_career": worst, "worst_income": incomes[worst], "worst_share": listing["rent"] / incomes[worst] if incomes[worst] else None})
    return rows


def entry_vs_skilled(model: Model, mods: set[str]) -> list[dict]:
    """Per career: the mean net credits per hands-on hour of the entry (apprentice) orders against the career's other legal orders. Vanilla item delivery
    must not beat real production (the owner's rule), so the entry mean has to be the smaller."""
    rows = []
    for career in CAREERS:
        usable = [o for o in model.orders if o.career == career and o.legality == "legal" and (not o.mod or o.mod in mods) and o.hands > 0]
        entry = [o.per_hour for o in usable if o.max_level < 99]
        skilled = [o.per_hour for o in usable if o.max_level == 99]
        if entry and skilled:
            rows.append({"career": career, "entry": sum(entry) / len(entry), "skilled": sum(skilled) / len(skilled), "entry_n": len(entry), "skilled_n": len(skilled)})
    return rows


def vehicle_rows(model: Model, mods: set[str]) -> list[dict]:
    """Each vehicle tier: its price, the mean net credits per active hour of the five careers at the level it is meant for, and the active hours that is."""
    tiers = json.loads(VEHICLES.read_text(encoding="utf-8"))
    rows = []
    for t in tiers["tiers"]:
        mean = sum(model.income(c, t["target_level"], mods)["net"] for c in CAREERS) / len(CAREERS)
        mat = (t.get("materials") or {}).get("value_credits")
        rows.append({"tier": t["tier"], "id": t["id"], "price": t["price"], "level": t["target_level"], "target_hours": t["target_active_hours"], "mean_income": mean,
                     "hours": t["price"] / mean, "materials": mat, "floor": tiers["pricing_rule"]["materials_multiple_floor"], "implemented": t["implemented"], "acquisition": t["acquisition"]})
    return rows


def career_spread(model: Model, mods: set[str], level: int) -> dict[str, float]:
    return {c: model.income(c, level, mods)["net"] for c in CAREERS}


def single_sale_max(model: Model, mods: set[str]) -> tuple[Order, float]:
    best = max((o for o in model.orders if o.legality == "legal" and (not o.mod or o.mod in mods)), key=lambda o: o.credits)
    return best, best.credits


def underground_returns() -> list[dict]:
    rows = underground.evaluate()
    return rows


ALL_MODS = {"tacz", "taczblueprints", "vinery", "kaleidoscope_tavern", "farm_and_charm"}


def invariants(model: Model) -> list[str]:
    """Hard rules. A failure means the data would break the economy the owner asked for."""
    problems: list[str] = []
    t = model.a["targets"]
    rules = model.rules
    listings = model.listings()
    by_cat = {l["category"]: l for l in listings}
    # ladder is monotonic and priced coherently
    cats = sorted(model.categories(), key=lambda c: c["tier"])
    for a, b in zip(cats, cats[1:]):
        if b["rent_hint"] <= a["rent_hint"]:
            problems.append(f"ladder: {b['id']} rent {b['rent_hint']} must exceed {a['id']} {a['rent_hint']}")
        if b["buy_hint"] and a["buy_hint"] and b["buy_hint"] <= a["buy_hint"]:
            problems.append(f"ladder: {b['id']} purchase price must exceed {a['id']}")
    for l in listings:
        if "buy" in l["tenures"] and l["buy_price"] <= 0:
            problems.append(f"{l['id']} can be bought but has no price")
        if "rent" in l["tenures"] and l["rent"] <= 0:
            problems.append(f"{l['id']} can be rented but has no rent")
        if "buy" in l["tenures"] and l["buy_price"] < l["rent"] * 20:
            problems.append(f"{l['id']}: buying ({l['buy_price']}) must cost at least 20 cycles of rent ({l['rent']}) or renting is pointless")
        if "buy" in l["tenures"] and l["buy_price"] > l["rent"] * 100:
            problems.append(f"{l['id']}: buying ({l['buy_price']}) is over 100 cycles of rent ({l['rent']}); nobody would")
        if l["deposit"] and l["deposit"] != l["rent"] * rules["deposit_cycles"]:
            problems.append(f"{l['id']}: deposit must be {rules['deposit_cycles']} cycles of rent")
        # category data agrees with the listing it is the example of
        cat = next(c for c in model.categories() if c["id"] == l["category"])
        if l["rent"] != cat["rent_hint"] or (l["buy_price"] and l["buy_price"] != cat["buy_hint"]):
            problems.append(f"{l['id']}: terms differ from its category's hint ({cat['rent_hint']}/{cat['buy_hint']})")
    # sell-back must lose money: nobody profits by buying and selling
    if rules["sell_back_percent"] >= 100:
        problems.append("sell-back must return less than was paid")
    # rent share of income
    for row in rent_shares(model, ALL_MODS):
        lo, hi = t["rent_share_of_net_income"]
        floor = 0.03 if row["category"] == "motel_room" else lo * 0.8
        if row["mean_share"] is not None and not (floor <= row["mean_share"] <= hi * 1.5):
            problems.append(f"rent share: {row['listing']} costs {row['mean_share']:.0%} of mean net income at level {row['level']} (target {lo:.0%}-{hi:.0%})")
    # a single sale cannot buy the next tier or the top tier
    best, credits = single_sale_max(model, ALL_MODS)
    top = cats[-1]["buy_hint"] or cats[-1]["rent_hint"] * 50
    if credits > t["single_sale_vs_top_tier_price_max"] * top:
        problems.append(f"single sale: {best.id} pays {credits}, over {t['single_sale_vs_top_tier_price_max']:.0%} of the top tier ({top})")
    for listing in listings:
        if listing["buy_price"] and credits > 0.9 * listing["buy_price"]:
            problems.append(f"single sale: {best.id} ({credits}) nearly buys {listing['id']} ({listing['buy_price']}) outright")
    # reaching a flat takes several profitable deliveries, not one and not hundreds; the house is a long-term goal
    flat = by_cat.get("small_apartment")
    if flat:
        nets = [o.net for o in model.available("smith", 4, ALL_MODS) + model.available("farmer", 4, ALL_MODS) if o.net > 0]
        mean_net = sum(nets) / len(nets)
        deliveries = flat["buy_price"] / mean_net
        lo, hi = t["upgrade_cycles_between"]
        if not (2 * lo <= deliveries <= 2 * hi + 20):
            problems.append(f"upgrade pace: the flat costs {deliveries:.0f} average level-4 deliveries (expected {2 * lo}-{2 * hi + 20})")
    house = by_cat.get("workshop_house")
    if house and flat and house["buy_price"] < 2.5 * flat["buy_price"]:
        problems.append("the house must be a clearly longer goal than the flat (at least 2.5 times the price)")
    # delivering what the world gives you (the entry orders) pays less per hands-on hour than making what a trade makes
    for row in entry_vs_skilled(model, ALL_MODS):
        if row["entry"] > row["skilled"]:
            problems.append(f"{row['career']}: its {row['entry_n']} entry orders pay {row['entry']:.0f} per hands-on hour, more than its other legal orders ({row['skilled']:.0f}): item delivery would beat production")
    # vehicles and boats: dear enough that making one is never worse than buying, and a tier takes about the hours of wage it was meant to
    prev = 0
    for row in vehicle_rows(model, ALL_MODS):
        if row["price"] <= prev:
            problems.append(f"vehicles: tier {row['id']} ({row['price']}) must cost more than the tier before ({prev})")
        prev = row["price"]
        if row["materials"] and row["price"] < row["floor"] * row["materials"]:
            problems.append(f"vehicles: {row['id']} costs {row['price']}, under {row['floor']} times its materials ({row['materials']}): buying would beat making")
        if not 0.7 * row["target_hours"] <= row["hours"] <= 1.4 * row["target_hours"]:
            problems.append(f"vehicles: {row['id']} takes {row['hours']:.1f} active hours of a level-{row['level']} wage, not the {row['target_hours']} it is meant to")
    # a scarcity world (no ore to mine): every career still has its first payout, and none loses most of its level-5 income to the shelves
    shelf_world = ScarcityModel("shelf", model.scale)
    for career in CAREERS:
        fp = shelf_world.first_payout(career, set())
        if fp["minutes"] is None or fp["minutes"] > t["first_payout_minutes_max"] or fp["net"] <= 0:
            problems.append(f"scarcity: {career} has no quick, profitable first payout without ore ({fp})")
    floor = model.a["scarcity"]["ore_world_share_min_at_level_5"]
    for row in scarcity_rows(model):
        if row["level"] == 5 and row["ore"] > 0 and max(row["shelf"], row["quarry"]) < floor * row["ore"]:
            problems.append(f"scarcity: a level-5 {row['career']} earns {max(row['shelf'], row['quarry']):.0f}/h without ore, under {floor:.0%} of the {row['ore']:.0f}/h of the ore world")
    problems += fence_problems(model)
    # a salvage yard is a side source: its iron (nuggets, nine to an ingot) never out-supplies the regrowing sites' iron, and it is not a machined-parts mine
    quarry_iron = ScarcityModel("quarry", model.scale).quarry.get("minecraft:iron_ingot", 0.0)
    for y in salvage_rows(model):
        iron = y["drops"].get("minecraft:iron_nugget", 0.0) / 9.0 + y["drops"].get("minecraft:iron_ingot", 0.0)
        if iron > quarry_iron:
            problems.append(f"salvage: {y['yard']} gives {iron:.1f} iron an active hour, more than the regrowing sites' {quarry_iron:.1f}")
        if y["drops"].get("underground_city:machined_parts", 0.0) > 2.0:
            problems.append(f"salvage: {y['yard']} gives {y['drops']['underground_city:machined_parts']:.1f} machined parts an active hour; a yard is not a parts mine")
    # no loops
    problems += exploit_checks(model)
    problems += [f"shop undercuts an order: {x}" for x in shop_sell_vs_orders(model)]
    problems += business_problems(model)
    # a brand-new citizen of every career can earn something at level 1 in the default pack (Core only), and quickly
    for career in CAREERS:
        fp = model.first_payout(career, set())
        if fp["minutes"] is None:
            problems.append(f"{career}: no order is open at level 1 in the default pack, so a new citizen can never earn XP")
        elif fp["minutes"] > t["first_payout_minutes_max"]:
            problems.append(f"{career}: first payout takes {fp['minutes']:.0f} minutes (target at most {t['first_payout_minutes_max']})")
        elif fp["net"] <= 0:
            problems.append(f"{career}: the first order a new citizen can do ({fp['order']}) loses money")
    # no career is far behind (level 5, legal)
    for level in (3, 5, 8):
        spread = career_spread(model, ALL_MODS, level)
        top_career = max(spread.values())
        for career, value in spread.items():
            if value < 0.45 * top_career:
                problems.append(f"career spread at level {level}: {career} earns {value:.0f}/h, under 45% of the best ({top_career:.0f})")
    return problems


# ------------------------------------------------------------------------------------------------------------------ report

def table(rows: list[list], header: list[str]) -> str:
    widths = [max(len(str(r[i])) for r in [header] + rows) for i in range(len(header))]
    line = lambda r: "| " + " | ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)) + " |"
    return "\n".join([line(header), "|" + "|".join("-" * (w + 2) for w in widths) + "|"] + [line(r) for r in rows])


def report(model: Model, markdown: bool = False) -> str:
    out: list[str] = []
    h = (lambda s: f"\n### {s}\n") if markdown else (lambda s: f"\n== {s} ==")
    # orders
    rows = []
    for o in sorted(model.orders, key=lambda o: (o.career, o.level, o.id)):
        rows.append([o.career, o.level, o.id, o.legality[:2], o.credits, f"{o.cash:.0f}", f"{o.net:.0f}", f"{o.hands:.1f}", f"{o.per_hour:.0f}",
                     o.xp, f"{60 / o.refill_minutes:.0f}/h" if o.refill_minutes else "", o.skill.split('.')[-1] if o.skill else "", o.mod])
    out.append(h("Every work order: credits, cash spent at an exchange, net, hands-on minutes, net per hands-on hour"))
    out.append(table(rows, ["career", "L", "order", "path", "credits", "cash", "net", "min", "net/h", "xp", "demand", "skill", "mod"]))
    # first payout
    rows = []
    for career in CAREERS:
        core = model.first_payout(career, set())
        full = model.first_payout(career, ALL_MODS)
        rows.append([career, "none" if core["minutes"] is None else f"{core['minutes']:.0f} ({core['order']}, {core['credits']} cr)",
                     "none" if full["minutes"] is None else f"{full['minutes']:.0f} ({full['order']}, {full['credits']} cr)"])
    out.append(h("Minutes to a new citizen's first credits (hands-on + waiting)"))
    out.append(table(rows, ["career", "Core only", "every mod"]))
    # income by level
    rows = []
    for level in (1, 2, 3, 4, 5, 6, 8, 10, 12):
        row = [level]
        for career in CAREERS:
            inc = model.income(career, level, ALL_MODS)
            row.append(f"{inc['net']:.0f} / {inc['xp']:.0f}")
        rows.append(row)
    out.append(h("Legal net credits per active hour / XP per active hour, by career level (every mod installed)"))
    out.append(table(rows, ["level"] + list(CAREERS)))
    rows = []
    for level in (1, 2, 3, 4, 5, 6, 8, 10, 12):
        row = [level]
        for career in CAREERS:
            inc = model.income(career, level, set())
            row.append(f"{inc['net']:.0f} / {inc['xp']:.0f}")
        rows.append(row)
    out.append(h("The same with Core only (no TaCZ, Vinery, Kaleidoscope Tavern)"))
    out.append(table(rows, ["level"] + list(CAREERS)))
    rows = [[r["career"], r["entry_n"], f"{r['entry']:.0f}", r["skilled_n"], f"{r['skilled']:.0f}", f"{r['entry'] / r['skilled']:.0%}"] for r in entry_vs_skilled(model, ALL_MODS)]
    out.append(h("Entry (apprentice) orders against the rest: mean net credits per hands-on hour"))
    out.append(table(rows, ["career", "entry orders", "entry net/h", "other legal orders", "other net/h", "entry as a share of other"]))
    # rent
    pct = lambda v: "n/a" if v is None else f"{v:.0%}"
    rows = [[r["listing"], r["rent"], r["level"], f"{r['mean_income']:.0f}", pct(r["mean_share"]), f"{r['worst_career']} {r['worst_income']:.0f}", pct(r["worst_share"])] for r in rent_shares(model, ALL_MODS)]
    out.append(h("Rent against income (a household of one)"))
    out.append(table(rows, ["home", "rent/h", "at level", "mean net/h", "share", "weakest career", "share"]))
    # timelines
    scen = model.a["scenarios"]
    rows = []
    groups = [[c] for c in scen["solo"]] + scen["crew_two"] + scen["crew_three"]
    for group in groups:
        sim = simulate(model, group, ALL_MODS)
        ms = sim["milestones"]
        pick = lambda k: f"{ms[k]:.0f}" if k in ms else "-"
        rows.append([" + ".join(group), pick("rented_flat"), pick("bought_flat"), pick("rented_house"), pick("bought_house"),
                     "/".join(str(x) for x in sim["final_levels"]), f"{sim['net_per_hour_end']:.0f}"])
    out.append(h("Active hours until each housing milestone (household policy in `simulate`)"))
    out.append(table(rows, ["household", "rent flat", "buy flat", "rent house", "buy house", "levels at 400 h", "net/h at 400 h"]))
    rows = [[r["tier"], r["id"], "dealer" if r["acquisition"] == "dealer" and r["implemented"] else "bench (proposed)", r["price"], f"{r['materials']}" if r["materials"] else "not priced",
             r["level"], f"{r['mean_income']:.0f}", f"{r['hours']:.1f}", r["target_hours"]] for r in vehicle_rows(model, ALL_MODS)]
    out.append(h("Vehicle and boat tiers: active hours of the five careers' mean wage to afford one (prices are the planning table of D-101)"))
    out.append(table(rows, ["tier", "vehicle", "acquired by", "price", "materials value", "at level", "mean net/h", "hours", "meant to be"]))
    # a scarcity world
    rows = [[r["career"], r["level"], f"{r['ore']:.0f}", f"{r['shelf']:.0f}", f"{r['quarry']:.0f}", f"{max(r['shelf'], r['quarry']) / r['ore']:.0%}" if r["ore"] > 0 else "-"] for r in scarcity_rows(model)]
    out.append(h("A scarcity world (no ore to mine, D-120): legal net credits per active hour with metals from the shelves only, or with the regrowing sites' base metals (every mod)"))
    out.append(table(rows, ["career", "level", "ore world", "shelves", "sites + shelves", "best as a share of the ore world"]))
    losers = [[o.career, o.level, o.id, o.credits, f"{o.cash:.0f}"] for o in ScarcityModel("shelf", model.scale).orders if o.net <= 0 and o.cash > 0]
    out.append(h("Orders that cost more than they pay when every ore unit is bought from a shelf (a scarcity world; the quarry's base metals do not help these)"))
    out.append(table(losers, ["career", "L", "order", "credits", "shelf cost"]) if losers else "none")
    out.append(h("Where a scarcity world's ore comes from: cheapest shelf price, units an hour the shelves sustain (all cities), units an hour the regrowing sites give back (the old quarry and the Valewood pit, shared by everybody)"))
    out.append(table(quarry_and_shelves(model), ["ore", "cheapest shelf", "shelves/h", "sites/h"]))
    out.append(h("Salvage yards (D-138): heaps that refill in active time, and the mean items an active hour if every heap is stripped (one rotation of each yard stands in a world)"))
    out.append(table([[y["yard"], y["heaps"], y["interval"], f"{y['heaps_per_hour']:.0f}", ", ".join(f"{k.split(':')[1]} {v:.1f}" for k, v in sorted(y["drops"].items()))] for y in salvage_rows(model)],
                     ["yard", "heaps", "minutes", "heaps/h", "items/h"]))
    rows = [[r["city"], r["title"], f"{r['rate']}% ({r['same_city']}% at home)", f"{r['best_rate']}%", r["budget"], f"{r['hourly']:.0f}", f"{r['best_legal']:.0f}", f"{r['share']:.0%}"] for r in fence_rows(model)]
    out.append(h("Fences (D-125): rate, best rate with every payout skill, opening budget, the most they pay an hour after it, against the best legal hour at level 5"))
    out.append(table(rows, ["city", "fence", "rate", "best rate", "budget", "per hour", "best legal/h (L5)", "share"]))
    # exploit findings
    out.append(h("Loops the data allows"))
    findings = exploit_checks(model) + fence_problems(model)
    out.append("\n".join(f"- {f}" for f in findings) if findings else "none found")
    return "\n".join(out)


def underground_report() -> str:
    rows = underground_returns()
    t = [[r["id"], r["origin"] + ">" + r["buyer_city"], f"{r['nominal']:.2f}", f"{r['careless']:.2f}", f"{r['trained']:.2f}", f"{r['best']:.2f}", f"{100 * r['careless_seizure']:.0f}%"] for r in rows]
    return table(t, ["contract", "route", "nominal return", "careless", "trained", "licensed", "seized (careless)"])


def sensitivity(model_class=Model) -> str:
    rows = []
    for scale in Model().a["targets"]["sensitivity"]:
        m = model_class(scale)
        row = [f"x{scale}"]
        for career in CAREERS:
            row.append(f"{m.income(career, 5, ALL_MODS)['net']:.0f}")
        sim = simulate(m, ["smith"], ALL_MODS)
        ms = sim["milestones"]
        row += [f"{ms.get('rented_flat', float('nan')):.0f}", f"{ms.get('bought_flat', float('nan')):.0f}"]
        rows.append(row)
    return table(rows, ["hands-on minutes"] + [f"{c} net/h at L5" for c in CAREERS] + ["smith rents flat (h)", "smith buys flat (h)"])


DOC_BEGIN, DOC_END = "<!-- economy-model:begin -->", "<!-- economy-model:end -->"


def doc_block(model: Model) -> str:
    """The generated body of docs/technical/economy-model.md, so the document can never drift from the data."""
    return "\n".join([DOC_BEGIN, "", report(model, markdown=True).strip("\n"), "", "### Sensitivity: every hands-on minute scaled", "", sensitivity().strip("\n"), "", DOC_END])


def splice(text: str, block: str) -> str:
    start, end = text.index(DOC_BEGIN), text.index(DOC_END) + len(DOC_END)
    return text[:start] + block + text[end:]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-doc", metavar="PATH", help="rewrite the generated block of a markdown document (between the economy-model markers)")
    parser.add_argument("--check-doc", metavar="PATH", help="exit 1 if the generated block of the document is stale")
    parser.add_argument("--markdown", action="store_true", help="print the report as a markdown body")
    parser.add_argument("--check", action="store_true", help="exit 1 when an invariant fails")
    parser.add_argument("--underground", action="store_true", help="include the risk-adjusted underground table")
    args = parser.parse_args()
    model = Model()
    if args.update_doc or args.check_doc:
        path = Path(args.update_doc or args.check_doc)
        text = path.read_text(encoding="utf-8")
        fresh = splice(text, doc_block(model))
        if args.update_doc:
            path.write_text(fresh, encoding="utf-8")
            print(f"updated {path}")
            return 0
        if fresh != text:
            print(f"{path} is stale: run python scripts/economy_model.py --update-doc {path}")
            return 1
        print(f"{path} is current")
        return 0
    print(report(model, args.markdown))
    if args.underground:
        print("\n== Underground contracts: expected return per credit of cost ==\n" + underground_report())
    print("\n== Sensitivity: every hands-on minute scaled ==\n" + sensitivity())
    problems = invariants(model)
    print("\n== Invariants ==")
    if problems:
        for p in problems:
            print("FAIL:", p)
    else:
        print("all hold")
    return 1 if (args.check and problems) else 0


if __name__ == "__main__":
    sys.exit(main())
