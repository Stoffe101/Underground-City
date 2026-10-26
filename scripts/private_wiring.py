#!/usr/bin/env python3
"""Wire a ported private pack's buildings into the three cities (D-192), inside the private pack itself.

A ported pack (``port_lostcities_pack.py``) holds Lost Cities buildings that the game could build but never chooses: a city's buildings are
chosen by ``data/underground_city/city_character/<city>.json`` (D-117), and a building takes part in the game's systems (a household with beds,
a street door that is locked, a listing to let, a security class) only through its plan (``data/<ns>/city_buildings/<id>.json``), a security
rule and a city listing. This tool writes those, generically, from a selection file the owner keeps beside the private pack:

    {"schema_version": 1, "buildings": [
       {"id": "<ns>:<building>", "city": "valewood", "zone": 1, "weight": 2, "use": "house", "class": "house", "title": "House",
        "listing": {"share_percent": 30, "category": "small_house", "rent": 60, "deposit": 120, "buy_price": 3200, "upkeep": 10}}]}

    private_wiring.py plan <ported pack> <ns>:<building>             what the plan of one building would be (counts only)
    private_wiring.py wire <ported pack> <selection.json> [--public <generated furnished pack>]

What ``wire`` writes into the ported pack, never into the repository:

* ``data/<ns>/city_buildings/<id>.json`` for every chosen building: its use and city, its floors, and the spots read from its own parts (doors,
  beds, stoves, seats, counters) with one unit (the inside of the building) for a house. The parts are rendered as Lost Cities would for the
  smallest number of floors; a spot's block is found through the part's own palette, its reference palette and the building's.
* ``data/underground_city/city_character/<city>.json``: the public furnished city's file (``content/generated/world-furnished``) with the chosen
  buildings added to the chosen ring. A private pack sits above the furnished package, so its file replaces the public one for that city.
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
import private_pack_tool as packs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = ROOT / "content/generated/world-furnished/pack"
FLOOR = 6
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


def layers(pack: Pack, building_id: str) -> tuple[list[tuple[int, list[list[str]], dict[str, str]]], dict]:
    """The building as Lost Cities would put it up for its smallest number of floors: (y of the part's floor slab, slices, palette) per part."""
    building = pack.doc("buildings", building_id)
    if building is None:
        raise SystemExit(f"no building {building_id} in {pack.root}")
    floors = max(1, int(building.get("minfloors", 1)))
    base = pack.palette(building.get("refpalette"))
    base.update({str(e.get("char")): e.get("block", "") for e in building.get("palette", []) if isinstance(e, dict)})
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
        palette.update({str(e.get("char")): e.get("block", "") for e in part.get("palette", []) if isinstance(e, dict)})
        out.append((floor * FLOOR, part.get("slices", []), palette))
    return out, building


def plan(pack: Pack, building_id: str, city: str, use: str, title: str = "") -> tuple[dict, list[str]]:
    """The plan of one building, read from its parts, and what is missing from it."""
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
    # the street door: a ground-floor door with open sky on one side of it
    step = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}
    doors = [s for s in spots if s["kind"] == "door"]
    street = [s for s in doors if s["y"] < FLOOR and any((s["x"] + k * step[s["facing"]][0], s["z"] + k * step[s["facing"]][1]) not in roofed for k in (1, -1))]
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
                if not (0 <= x <= 15 and 0 <= z <= 15) or (x, z) not in roofed:
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
    if use == "house":
        doc["units"] = [{"id": "house", "title": title or "House", "box": box}]
    return doc, missing


def wire(pack_root: Path, selection: dict, public: Path) -> dict:
    packs.refuse_in_repo(pack_root)
    manifest = json.loads((pack_root / packs.MANIFEST).read_text(encoding="utf-8"))
    pack_id = manifest["id"]
    pack = Pack(pack_root)
    report = {"buildings": {}, "cities": {}, "listings": 0}
    rules, listings, by_city = [], [], {}
    for chosen in selection["buildings"]:
        bid, city, use = chosen["id"], chosen["city"], chosen["use"]
        if use not in USES:
            raise SystemExit(f"{bid}: unknown use {use}")
        doc, missing = plan(pack, bid, city, use, chosen.get("title", ""))
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
        if chosen.get("listing"):
            listings.append({"building": bid, **chosen["listing"], "area": city.capitalize(), "tenures": chosen["listing"].get("tenures", ["rent", "buy"]),
                             "max_containers": chosen["listing"].get("max_containers", 6), "private_claims": chosen["listing"].get("private_claims", 2),
                             "facilities": chosen["listing"].get("facilities", ["bed", "storage", "plumbing"]),
                             "summary": chosen["listing"].get("summary", "A furnished home.")})
        by_city.setdefault(city, []).append(chosen)
    for city, chosen in by_city.items():
        source = public / "data/underground_city/city_character" / f"{city}.json"
        character = json.loads(source.read_text(encoding="utf-8"))
        for c in chosen:
            zone = character["zones"][min(int(c.get("zone", 0)), len(character["zones"]) - 1)]
            zone["buildings"].append({"id": c["id"], "weight": c.get("weight", 1)})
        out = pack_root / "data/underground_city/city_character" / f"{city}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(character, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        report["cities"][city] = len(chosen)
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
