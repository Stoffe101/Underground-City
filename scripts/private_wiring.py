#!/usr/bin/env python3
"""Wire a ported private pack's buildings into the three cities (D-192), inside the private pack itself.

A ported pack (``port_lostcities_pack.py``) holds Lost Cities buildings that the game could build but never chooses: a city's buildings are
chosen by ``data/underground_city/city_character/<city>.json`` (D-117), and a building takes part in the game's systems (a household with beds,
a street door that is locked, a listing to let, a security class) only through its plan (``data/<ns>/city_buildings/<id>.json``), a security
rule and a city listing. This tool writes those, generically, from a selection file the owner keeps beside the private pack:

    {"schema_version": 1, "buildings": [
       {"id": "<ns>:<building>", "city": "valewood", "zone": 1, "weight": 2, "use": "house", "class": "house", "title": "House",
        "listing": {"share_percent": 30, "category": "small_house", "rent": 60, "deposit": 120, "buy_price": 3200, "upkeep": 10}}],
     "public_weights": {"underground_city:vw_house_a": 0}}

    private_wiring.py plan <ported pack> <ns>:<building>             what the plan of one building would be (counts only)
    private_wiring.py wire <ported pack> <selection.json> [--public <generated furnished pack>]

What ``wire`` writes into the ported pack, never into the repository:

* ``data/<ns>/city_buildings/<id>.json`` for every chosen building: its use and city, its floors, and the spots read from its own parts (doors,
  beds, stoves, seats, counters) with one unit (the inside of the building) for a house. The parts are rendered as Lost Cities would for the
  smallest number of floors; a spot's block is found through the part's own palette, its reference palette and the building's.
* ``data/underground_city/city_character/<city>.json``: the public furnished city's file (``content/generated/world-furnished``) with the chosen
  buildings added to the chosen ring, and the public buildings at the weights ``public_weights`` gives (0 leaves one out). A private pack sits
  above the furnished package, so its file replaces the public one for that city.
* ``data/underground_city/building_security/<pack id>.json``: a rule per chosen building that names its security class (classes merge).
* ``data/underground_city/properties/<pack id>.json``: city listings for buildings the selection lets.
* ``data/underground_city/shop_counters/<pack id>-<building>.json``: for a shop with a ``counter`` (an outlet of the game's and a title), its keeper
  sells that outlet's goods over the building's first counter.

The report printed holds counts and ids only. Nothing is guessed silently: a building without a street door or a bed is listed.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import private_dwellings as dwellings_of  # noqa: E402
import private_pack_tool as packs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "content/generated/world-furnished/pack"
FLOOR = 6
COVERED_STEP = 3   # BuildingGuard.COVERED_STEP
# raised when a plan or a city file changes in a way an older wiring lacks; private_c70.py check refuses a staged pack wired by an older one
WIRING_VERSION = 12
SPEC = re.compile(r"^(?P<name>[a-z0-9_.-]+:[a-z0-9_./-]+)(?:\[(?P<props>[^\]]*)\])?$")
# what a block is for a household, by words in its id (furniture mods name their blocks plainly)
KINDS = [("bed", re.compile(r"_bed$|:bed$|fancy_bed")), ("stove", re.compile(r"furnace|smoker|stove|oven|cooking_pot|range_hood|microwave")),
         ("seat", re.compile(r"chair|sofa|stool|couch|bench|armchair")), ("counter", re.compile(r"counter|register|till"))]
USES = {"house", "flats", "shop", "workshop", "warehouse", "office", "club", "terrace"}


def parse(spec: str) -> tuple[str, dict[str, str]]:
    match = SPEC.match(spec or "")
    if not match:
        return spec or "", {}
    props = dict(p.split("=", 1) for p in (match.group("props") or "").split(",") if "=" in p)
    return match.group("name"), props


class Pack:
    def __init__(self, root: Path):
        self.root = root
        self.cache: dict[tuple[str, str], dict] = {}

    def doc(self, kind: str, rid: str) -> dict | None:
        ns, path = rid.split(":", 1)
        key = (kind, rid)
        if key not in self.cache:
            file = self.root / "data" / ns / "lostcities" / kind / f"{path}.json"
            self.cache[key] = json.loads(file.read_text(encoding="utf-8")) if file.is_file() else None
        return self.cache[key]

    def palette(self, rid: str | None) -> dict[str, str]:
        out: dict[str, str] = {}
        doc = self.doc("palettes", rid) if rid else None
        for entry in (doc or {}).get("palette", []):
            out[str(entry.get("char"))] = entry.get("block") or next((b.get("block") for b in entry.get("blocks", [])), "") or ""
        return out


def inline(doc: dict) -> list:
    """A part's or building's own palette entries. Lost Cities 7.5.5 decodes a part's only in the object form (``{"palette": [...]}``) and drops a bare list without a word
    (multi run 2, October 27); the list form is read here too, as fixtures and older ports wrote it."""
    entries = doc.get("palette")
    if isinstance(entries, dict):
        entries = entries.get("palette")
    return entries if isinstance(entries, list) else []


def layers(pack: Pack, building_id: str) -> tuple[list[tuple[int, list[list[str]], dict[str, str]]], dict]:
    """The building as Lost Cities would put it up for its smallest number of floors: (y of the part's floor slab, slices, palette) per part."""
    building = pack.doc("buildings", building_id)
    if building is None:
        raise SystemExit(f"no building {building_id} in {pack.root}")
    floors = max(1, int(building.get("minfloors", 1)))
    base = pack.palette(building.get("refpalette"))
    base.update({str(e.get("char")): e.get("block", "") for e in inline(building) if isinstance(e, dict)})
    out = []
    for floor in range(floors + 1):
        top = floor == floors
        # a part's flags are conditions; a flag it does not set matches every floor (Lost Cities' own rule)
        want = [p for p in building.get("parts", []) if not p.get("cellar") and p.get("top", top) == top and (p.get("ground", floor == 0) == (floor == 0) or top and "ground" not in p)]
        if not want and top:
            continue
        if not want:
            want = [p for p in building.get("parts", []) if not p.get("cellar") and "top" not in p]
        if not want:
            continue
        part = pack.doc("parts", want[0]["part"])
        if part is None:
            continue
        palette = dict(base)
        palette.update(pack.palette(part.get("refpalette")))
        palette.update({str(e.get("char")): e.get("block", "") for e in inline(part) if isinstance(e, dict)})
        out.append((floor * FLOOR, part.get("slices", []), palette))
    return out, building


def roofed_cells(pack: Pack, building_id: str) -> set[tuple[int, int]]:
    """The columns of a building's plot under the ground storey's ceiling (as ``plan`` reads them), in the building's own coordinates."""
    parts, _ = layers(pack, building_id)
    out = set()
    for y0, slices, palette in parts:
        for dy, layer in enumerate(slices):
            if not (FLOOR - 2 <= y0 + dy < FLOOR):
                continue
            for z, row in enumerate(layer):
                for x, ch in enumerate(row):
                    block = palette.get(ch, "")
                    if block and not block.endswith(":air") and ch != " ":
                        out.add((x, z))
    return out


def multi_parts(pack: Pack, multi_id: str) -> list[tuple[str, int, int]]:
    """The parts of a multi-chunk building with their place in it: (building id, x, z); Lost Cities' grid is ``buildings[x][z]`` and a part named without a namespace is the multi's."""
    doc = pack.doc("multibuildings", multi_id)
    if doc is None:
        raise SystemExit(f"no multi-chunk building {multi_id} in {pack.root}")
    ns = multi_id.split(":", 1)[0]
    out = []
    for x, column in enumerate(doc.get("buildings", [])):
        for z, part in enumerate(column):
            out.append((part if ":" in part else f"{ns}:{part}", x, z))
    return out


def plan(pack: Pack, building_id: str, city: str, use: str, title: str = "", beside: set[tuple[int, int]] | None = None) -> tuple[dict, list[str]]:
    """The plan of one building, read from its parts, and what is missing from it. ``beside``: the roofed columns of the other parts of a multi-chunk building, in this part's
    coordinates (outside 0 to 15): a door into a neighbouring part is not a street door."""
    beside = beside or set()
    parts, building = layers(pack, building_id)
    spots, cells = [], []
    for y0, slices, palette in parts:
        for dy, layer in enumerate(slices):
            for z, row in enumerate(layer):
                for x, ch in enumerate(row):
                    block = palette.get(ch, "")
                    if not block or block.endswith(":air") or ch == " ":
                        continue
                    name, props = parse(block)
                    cells.append((x, y0 + dy, z))
                    if name.endswith("_door") and props.get("half", "lower") == "lower":
                        spots.append({"kind": "door", "x": x, "y": y0 + dy, "z": z, "facing": props.get("facing", "north")})
                        continue
                    for kind, pattern in KINDS:
                        # a home's kitchen counter is no shop counter: a counter spot is where a worker serves (HouseholdPlan), so a home has none
                        if kind == "counter" and use in ("house", "flats"):
                            continue
                        if pattern.search(name) and (kind != "bed" or props.get("part", "head") == "head"):
                            spots.append({"kind": kind, "x": x, "y": y0 + dy, "z": z, "facing": props.get("facing", "north")})
                            break
    missing = []
    if not cells:
        missing.append("no blocks")
    # under a roof: a column with the ground storey's ceiling over it (its top two layers; a garden, a yard, a fence and the eaves of a roof above are not);
    # the house is the roofed part of the plot, as high as the highest block over it
    roofed = {(x, z) for x, y, z in cells if FLOOR - 2 <= y < FLOOR}
    xs, zs = [c[0] for c in roofed] or [0], [c[1] for c in roofed] or [0]
    top_y = max([y for x, y, z in cells if (x, z) in roofed] or [1])
    # the inside of the building: the roofed footprint one block in from its outer walls, from the first floor up to under the roof
    box = [min(xs) + 1, 1, min(zs) + 1, max(xs) - 1, max(1, top_y - 1), max(zs) - 1]
    # the street door: a ground-floor door with open sky on one side of it, else one whose open floor reaches the sky within the guard's covered step
    # (BuildingGuard.COVERED_STEP: the eaves, a porch or a canopy over an entrance), nearest the sky first
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    doors = [s for s in spots if s["kind"] == "door"]
    solid = set(cells)

    def to_sky(s: dict) -> int:
        best = COVERED_STEP + 1
        for sign in (1, -1):
            dx, dz = step[s["facing"]][0] * sign, step[s["facing"]][1] * sign
            for k in range(1, COVERED_STEP + 1):
                x, z = s["x"] + k * dx, s["z"] + k * dz
                if (x, z) not in beside and ((x, z) not in roofed or not (0 <= x <= 15 and 0 <= z <= 15)):
                    best = min(best, k)
                    break
                if k > 1 and any((x - dx, s["y"] + dy, z - dz) in solid for dy in (0, 1)):
                    break   # a wall or furniture in the way: the guard's search ends there too
        return best

    ground = [s for s in doors if s["y"] < FLOOR]
    street = sorted([s for s in ground if to_sky(s) <= COVERED_STEP], key=to_sky)
    if street and to_sky(street[0]) > 1:
        missing.append(f"street door under a covered step ({to_sky(street[0])} blocks to the sky)")
    if not street:
        # under an awning or a porch both sides of a door are roofed: the ground-floor door nearest the edge of the plot is then taken, and the report says so
        # no guess: the guard locks a plan's first door as the street door (BuildingGuard.planStreetDoor), and a guessed one was a kitchen door in an open-fronted
        # restaurant (measured). The plan then names no door at all and the guard keeps to its own sky rule for this building's doors
        spots[:] = [sp for sp in spots if sp["kind"] != "door"]
        missing.append("no street door: no ground-floor door has open sky on one side, so the plan names none")
    beds = [s for s in spots if s["kind"] == "bed"]
    if use in ("house", "flats") and not beds:
        missing.append("no bed")
    if street:
        # the street door's facing is the way out (BuildingGuard.planStreetDoor reads it): the side that reaches unroofed ground soonest, else the nearer edge of the plot
        d = street[0]
        opposite = {"north": "south", "south": "north", "east": "west", "west": "east"}
        def reach(side: str) -> tuple[int, int]:
            dx, dz = step[side]
            for k in range(1, 16):
                x, z = d["x"] + k * dx, d["z"] + k * dz
                if (x, z) not in beside and (not (0 <= x <= 15 and 0 <= z <= 15) or (x, z) not in roofed):
                    return k, 0
            return 16, 0
        sides = [d["facing"], opposite[d["facing"]]]
        edge = {"north": d["z"], "south": 15 - d["z"], "west": d["x"], "east": 15 - d["x"]}
        d["facing"] = min(sides, key=lambda side: (reach(side)[0], edge[side]))
        d["note"] = "street door (the way out is its facing)"
    ordered = street[:1] + [s for s in spots if s is not (street[0] if street else None)]
    doc = {"schema_version": 1, "id": building_id.split(":", 1)[1], "city": city, "use": use,
           "floors": {"min": int(building.get("minfloors", 1)), "max": int(building.get("maxfloors", building.get("minfloors", 1)))},
           "spots": ordered[:48], "notes": "Derived by scripts/private_wiring.py from the building's own parts (private pack)."}
    if use in ("house", "workshop", "warehouse", "flats") and (box[0] > box[3] or box[2] > box[5]):
        # no roofed footprint inside walls: no unit at all (Core refuses a datapack with an empty unit box, and the server does not start; multi run 3)
        missing.append("no unit: no roofed footprint inside walls")
    elif use == "house":
        doc["units"] = [{"id": "house", "title": title or "House", "box": box}]
    elif use in ("workshop", "warehouse"):
        doc["units"] = [{"id": "premises", "title": title or use.capitalize(), "box": box}]
    elif use == "flats":
        # a flat per storey that has a bed, the storey's roofed box from its floor to under its ceiling (HouseholdPlan houses a household per unit)
        storeys = sorted({b["y"] // FLOOR for b in spots if b["kind"] == "bed"})
        doc["units"] = [{"id": f"flat_{n}", "title": f"Flat {n}", "box": [box[0], f * FLOOR + 1, box[2], box[3], f * FLOOR + FLOOR - 2, box[5]]}
                        for n, f in enumerate(storeys, start=1)]
        if not storeys:
            missing.append("no flat: no storey has a bed")
    return doc, missing


def wire(pack_root: Path, selection: dict, public: Path) -> dict:
    packs.refuse_in_repo(pack_root)
    manifest = json.loads((pack_root / packs.MANIFEST).read_text(encoding="utf-8"))
    pack_id = manifest["id"]
    pack = Pack(pack_root)
    report = {"buildings": {}, "cities": {}, "listings": 0}
    rules, listings, by_city = [], [], {}
    first: dict[str, dict] = {}
    multis: dict[str, list[dict]] = {}
    overrides = selection.get("dwellings", {})
    for chosen in selection.get("multi_buildings", []):
        # a multi-chunk building (D-198): the city keeps it where Lost Cities plans it, or puts it in place of one with its footprint; each part has a plan of its own (its
        # household's spots, read with the other parts' roofs as inside), and its homes are the building's dwellings (D-206), never a part's
        mid, city = chosen["id"], chosen["city"]
        multis.setdefault(city, []).append({"id": mid, "weight": chosen.get("weight", 1)})
        if mid.split(":", 1)[1] == "none":
            continue
        use = chosen["use"]
        if use not in USES:
            raise SystemExit(f"{mid}: unknown use {use}")
        grid = multi_parts(pack, mid)
        roofs = {(x, z): roofed_cells(pack, part) for part, x, z in grid}
        parts_report = {}
        for part, x, z in grid:
            if part in first:
                continue
            beside = {(cx + (ox - x) * 16, cz + (oz - z) * 16) for (ox, oz), cells in roofs.items() if (ox, oz) != (x, z) for cx, cz in cells}
            doc, missing = plan(pack, part, city, use, chosen.get("title", ""), beside)
            pns, ppath = part.split(":", 1)
            target = pack_root / "data" / pns / "city_buildings" / f"{ppath}.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            first[part] = {"city": city}
            kinds: dict[str, int] = {}
            for sp in doc["spots"]:
                kinds[sp["kind"]] = kinds.get(sp["kind"], 0) + 1
            street_door = bool(doc["spots"]) and doc["spots"][0]["kind"] == "door"
            parts_report[part] = {"at": [x, z], "spots": kinds, "units": len(doc.get("units", [])), "street_door": street_door, "missing": missing}
        found, why = multi_dwellings(pack, mid, grid, use, overrides.get(mid))
        if found:
            write_dwellings(pack_root, mid, grid, found)
        if chosen.get("listing") and found:
            listings.append(listing(mid, chosen["listing"], city))
        if chosen.get("class"):
            rules.append({"type_prefix": mid + "_", "class": chosen["class"], "priority": 20})
        report["buildings"][mid] = {"multi": True, "parts": parts_report, "dwellings": len(found), "dwellings_from": why["from"], "uncertain": why.get("uncertain", []),
                                    "nobodys_rooms": why.get("nobodys_rooms", 0), "listed": bool(chosen.get("listing") and found)}
    for chosen in selection["buildings"]:
        bid, city, use = chosen["id"], chosen["city"], chosen["use"]
        if use not in USES:
            raise SystemExit(f"{bid}: unknown use {use}")
        if bid in first:
            # the same building in another ring of its city: one more pick there, and the plan, rule, counter and listing of its first entry
            if first[bid]["city"] != city:
                raise SystemExit(f"{bid} is chosen for {first[bid]['city']} and {city}: a building belongs to one city (its counter serves one city, it has one listing)")
            by_city.setdefault(city, []).append(chosen)
            continue
        first[bid] = chosen
        doc, missing = plan(pack, bid, city, use, chosen.get("title", ""))
        if use == "flats":
            # a flat is the rooms behind its own door, not a storey (D-206): the hall and the stairs are nobody's; where that is not certain, nothing is let
            found, why = dwellings_of.analyse([(bid, 0, 0)], lambda part: layers(pack, part), "flats") if bid not in overrides else (override_dwellings(overrides[bid]), {"uncertain": []})
            units = [{"id": d["id"], "title": d["title"], "boxes": [r["box"] for r in d["rooms"]]} for d in found if len(d["rooms"]) <= 64]
            if units:
                doc["units"] = units
            else:
                doc.pop("units", None)
                missing.append("no flat let: " + ("; ".join(why.get("uncertain", [])) or "no rooms behind a door off the hall with a bed"))
        ns, path = bid.split(":", 1)
        target = pack_root / "data" / ns / "city_buildings" / f"{path}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(doc, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        kinds: dict[str, int] = {}
        for s in doc["spots"]:
            kinds[s["kind"]] = kinds.get(s["kind"], 0) + 1
        report["buildings"][bid] = {"spots": kinds, "units": len(doc.get("units", [])), "missing": missing}
        if chosen.get("class"):
            rules.append({"type_prefix": bid, "class": chosen["class"], "priority": 20})
        if chosen.get("counter"):
            # a shop's keeper sells the goods of one of the game's outlets over the building's first counter (data/underground_city/shop_counters)
            c = chosen["counter"]
            out = pack_root / "data/underground_city/shop_counters" / f"{pack_id}-{path}.json"
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps({"schema_version": 1, "outlet": c["outlet"], "building": bid, "spot": c.get("spot", "counter"), "city": city, "title": c["title"]}, indent=2) + "\n", encoding="utf-8")
        if chosen.get("listing") and doc.get("units"):
            listings.append(listing(bid, chosen["listing"], city))
        by_city.setdefault(city, []).append(chosen)
    # the public buildings a selection keeps, and how often: {"<ns>:<building>": weight}, 0 to leave one out of every ring of its city (the owner prefers
    # the private buildings, D-193); the public buildings it does not name keep their weights
    public_weights = {k: float(v) for k, v in selection.get("public_weights", {}).items()}
    for city in sorted(set(by_city) | set(selection.get("cities", [])) | set(multis)):
        chosen = by_city.get(city, [])
        source = public / "data/underground_city/city_character" / f"{city}.json"
        character = json.loads(source.read_text(encoding="utf-8"))
        for zone in character["zones"]:
            kept = []
            for pick in zone["buildings"]:
                weight = public_weights.get(pick["id"], pick["weight"])
                if weight > 0:
                    kept.append({**pick, "weight": weight})
            zone["buildings"] = kept
        for c in chosen:
            zone = character["zones"][min(int(c.get("zone", 0)), len(character["zones"]) - 1)]
            zone["buildings"].append({"id": c["id"], "weight": c.get("weight", 1)})
        if multis.get(city):
            character["multi_buildings"] = multis[city]
        out = pack_root / "data/underground_city/city_character" / f"{city}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(character, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        if any(not zone["buildings"] for zone in character["zones"]):
            raise SystemExit(f"{city}: a ring has no building left (public_weights left out every public building and the selection adds none there)")
        report["cities"][city] = {"chosen": len(chosen), "rings": [{"picks": len(z["buildings"]), "private_share": round(sum(b["weight"] for b in z["buildings"] if not b["id"].startswith("underground_city:")) / sum(b["weight"] for b in z["buildings"]), 2)} for z in character["zones"]]}
    if multis:
        # Lost Cities plans multi-chunk buildings from the city style of the area, which the archipelago's world style makes its standard one: the chosen ones join that
        # style's list (Lost Cities adds a style's lists to the ones it inherits) at the selection's factors, and each city keeps or swaps in its own (CityCharacter)
        factors: dict[str, float] = {}
        for city_list in multis.values():
            for pick in city_list:
                if pick["id"].split(":", 1)[1] != "none":
                    factors[pick["id"]] = factors.get(pick["id"], 0) + float(pick["weight"])
        style = {"style": "standard", "inherit": "citystyle_common",
                 "selectors": {"multibuildings": [{"factor": round(f * float(selection.get("multi_factor", 1.0)), 3), "value": mid} for mid, f in sorted(factors.items())]}}
        out = pack_root / "data/lostcities/lostcities/citystyles/citystyle_standard.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(style, indent=2) + "\n", encoding="utf-8")
        report["multi_buildings_in_the_standard_style"] = len(factors)
    if rules:
        out = pack_root / "data/underground_city/building_security" / f"{pack_id}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"schema_version": 1, "classes": {}, "rules": rules}, indent=2) + "\n", encoding="utf-8")
    if listings:
        out = pack_root / "data/underground_city/properties" / f"{pack_id}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({"schema_version": 1, "city_listings": listings}, indent=2) + "\n", encoding="utf-8")
        report["listings"] = len(listings)
    return report


def listing(building: str, terms: dict, city: str) -> dict:
    """A city listing (data/underground_city/properties) for a building, or a multi-chunk building whose dwellings are let on its terms."""
    return {"building": building, **terms, "area": city.capitalize(), "tenures": terms.get("tenures", ["rent", "buy"]), "max_containers": terms.get("max_containers", 6),
            "private_claims": terms.get("private_claims", 2), "facilities": terms.get("facilities", ["bed", "storage", "plumbing"]), "summary": terms.get("summary", "A furnished home.")}


def override_dwellings(given) -> list[dict]:
    """The owner's own dwellings for a building (the selection's ``dwellings``): a list as CityDwellings reads it, or "none" to let nothing. Checked as Core will check it."""
    if given == "none":
        return []
    if not isinstance(given, list):
        raise SystemExit("a dwellings override is a list of dwellings or \"none\"")
    for d in given:
        if not re.fullmatch(r"[a-z0-9_]{2,24}", str(d.get("id", ""))) or not d.get("rooms"):
            raise SystemExit(f"a dwellings override has a dwelling without an id or rooms: {d.get('id')}")
    clash = dwellings_of.overlaps(given)
    if clash:
        raise SystemExit(f"a dwellings override is refused: {clash}")
    return given


def multi_dwellings(pack: "Pack", mid: str, grid: list[tuple[str, int, int]], use: str, given) -> tuple[list[dict], dict]:
    """The dwellings of a multi-chunk building: the owner's override, or what its rooms say (private_dwellings), or none when that is uncertain."""
    if given is not None:
        return override_dwellings(given), {"from": "override"}
    found, report = dwellings_of.analyse(grid, lambda part: layers(pack, part), use)
    report["from"] = "rooms"
    return found, report


def write_dwellings(pack_root: Path, mid: str, grid: list[tuple[str, int, int]], found: list[dict]) -> None:
    """data/<ns>/city_dwellings/<multi>.json, as CityDwellings reads it: the size, the part at each place (Lost Cities' grid) and the dwellings."""
    w, h = max(x for _, x, _ in grid) + 1, max(z for _, _, z in grid) + 1
    parts = [[next(p for p, x, z in grid if x == gx and z == gz) for gz in range(h)] for gx in range(w)]
    ns, path = mid.split(":", 1)
    out = pack_root / "data" / ns / "city_dwellings" / f"{path}.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"schema_version": 1, "size": [w, h], "parts": parts, "dwellings": found}, indent=1) + "\n", encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan")
    p.add_argument("pack", type=Path)
    p.add_argument("building")
    p.add_argument("--city", default="valewood")
    p.add_argument("--use", default="house")
    w = sub.add_parser("wire")
    w.add_argument("pack", type=Path)
    w.add_argument("selection", type=Path)
    w.add_argument("--public", type=Path, default=PUBLIC)
    args = parser.parse_args(argv)
    if args.command == "plan":
        packs.refuse_in_repo(args.pack)
        doc, missing = plan(Pack(args.pack), args.building, args.city, args.use)
        kinds: dict[str, int] = {}
        for s in doc["spots"]:
            kinds[s["kind"]] = kinds.get(s["kind"], 0) + 1
        print(json.dumps({"spots": kinds, "units": doc.get("units", []), "floors": doc["floors"], "missing": missing}, indent=2))
        return 0
    selection = json.loads(args.selection.read_text(encoding="utf-8"))
    if selection.get("schema_version") != 1:
        raise SystemExit("unsupported selection schema")
    print(json.dumps(wire(args.pack, selection, args.public), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
