"""The private wiring tool (scripts/private_wiring.py, D-192, D-193) and the building audit (scripts/building_audit.py): proven on INVENTED
fixtures only; no private content is used."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import building_audit as audit  # noqa: E402
import private_wiring as wiring  # noqa: E402

NS = "fixtureworks"
PALETTE = {"#": "minecraft:stone_bricks", "=": "minecraft:oak_planks", "D": "minecraft:oak_door[facing=south,half=lower]",
           "d": "minecraft:oak_door[facing=south,half=upper]", "B": "minecraft:red_bed[facing=north,part=head]", "b": "minecraft:red_bed[facing=north,part=foot]",
           "c": "minecraft:light_gray_carpet"}


def slices(cells: dict) -> list:
    """Six layers of 16 rows of 16 characters from {(x, y, z): char}; everything else is air."""
    return [["".join(cells.get((x, y, z), " ") for x in range(16)) for z in range(16)] for y in range(6)]


def storey(x0=3, x1=12, z0=3, z1=12, door=(7, 12), canopy=0, bed=False) -> dict:
    """A box of walls with a ceiling, a door in its south wall, and a ceiling ``canopy`` blocks deep in front of the door."""
    cells = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            cells[(x, 0, z)] = "="
            cells[(x, 5, z)] = "="
            if x in (x0, x1) or z in (z0, z1):
                for y in range(1, 5):
                    cells[(x, y, z)] = "#"
    if door:
        cells[(door[0], 1, door[1])] = "D"
        cells[(door[0], 2, door[1])] = "d"
    for k in range(1, canopy + 1):
        for x in range(door[0] - 1, door[0] + 2):
            cells[(x, 5, door[1] + k)] = "="
    if bed:
        cells[(5, 1, 5)] = "B"
        cells[(5, 1, 6)] = "b"
    return cells


class Fixture:
    def __init__(self, root: Path):
        self.root = root
        self.pack = root / "pack"
        self.public = root / "public"
        (self.pack / "data").mkdir(parents=True)
        (self.pack / "uc-private-pack.json").write_text(json.dumps({"id": "fixture_private", "title": "Fixture"}), encoding="utf-8")
        for city in ("valewood", "ironhaven"):
            out = self.public / "data/underground_city/city_character" / f"{city}.json"
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(json.dumps({"schema_version": 1, "city": city, "style": f"underground_city:{city}", "zones": [
                {"within": 0.5, "buildings": [{"id": f"underground_city:{city}_shop", "weight": 3}, {"id": f"underground_city:{city}_house", "weight": 2}]},
                {"within": 1.0, "buildings": [{"id": f"underground_city:{city}_house", "weight": 4}]}]}), encoding="utf-8")

    def write(self, kind: str, name: str, doc: dict) -> None:
        out = self.pack / "data" / NS / "lostcities" / kind / f"{name}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(doc), encoding="utf-8")

    def building(self, name: str, ground: dict, upper: list | None = None, top: dict | None = None) -> str:
        """A building of a ground part, ``upper`` storeys and a top part, all with the fixture palette."""
        palette = [{"char": c, "block": b} for c, b in PALETTE.items()]
        self.write("parts", f"{name}_ground", {"xsize": 16, "zsize": 16, "slices": slices(ground), "palette": palette})
        parts = [{"part": f"{NS}:{name}_ground", "ground": True, "top": False}]
        for i, cells in enumerate(upper or []):
            self.write("parts", f"{name}_floor{i}", {"xsize": 16, "zsize": 16, "slices": slices(cells), "palette": palette})
            parts.append({"part": f"{NS}:{name}_floor{i}", "ground": False, "top": False})
        self.write("parts", f"{name}_top", {"xsize": 16, "zsize": 16, "slices": slices(top or {}), "palette": palette})
        parts.append({"part": f"{NS}:{name}_top", "top": True})
        self.write("buildings", name, {"minfloors": 1 + len(upper or []), "maxfloors": 1 + len(upper or []), "parts": parts})
        return f"{NS}:{name}"


class WireTheFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.f = Fixture(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def plan(self, bid: str, use: str = "house") -> tuple[dict, list]:
        return wiring.plan(wiring.Pack(self.f.pack), bid, "valewood", use)

    def test_a_door_with_the_sky_beside_it_is_the_street_door_and_its_facing_is_the_way_out(self):
        doc, missing = self.plan(self.f.building("house", storey(bed=True)))
        self.assertEqual(doc["spots"][0]["kind"], "door")
        self.assertEqual((doc["spots"][0]["x"], doc["spots"][0]["z"], doc["spots"][0]["facing"]), (7, 12, "south"))
        self.assertEqual(missing, [])
        self.assertEqual(doc["units"][0]["box"], [4, 1, 4, 11, 4, 11], "a house's unit is its roofed footprint inside its walls")

    def test_a_part_whose_palette_is_in_lost_cities_object_form_is_read(self):
        # the porter writes a multi-chunk part's palette as {"palette": [...]} (the only form Lost Cities decodes); read as a list, the house had no walls and an empty unit (multi run 3)
        bid = self.f.building("objhouse", storey(bed=True))
        part = self.f.pack / "data" / NS / "lostcities" / "parts" / "objhouse_ground.json"
        doc = json.loads(part.read_text(encoding="utf-8"))
        doc["palette"] = {"palette": doc["palette"]}
        part.write_text(json.dumps(doc), encoding="utf-8")
        plan, missing = self.plan(bid)
        self.assertEqual(plan["units"][0]["box"], [4, 1, 4, 11, 4, 11])
        self.assertEqual(missing, [])

    def test_a_house_with_no_roof_gets_no_unit_and_says_so(self):
        doc, missing = self.plan(self.f.building("yard", {(x, 0, z): "=" for x in range(16) for z in range(16)}))
        self.assertNotIn("units", doc, "an empty box stops Core from loading the pack")
        self.assertTrue(any("no unit" in m for m in missing), missing)

    def test_a_door_under_a_canopy_within_the_guards_covered_step_is_the_street_door(self):
        doc, missing = self.plan(self.f.building("porch", storey(z1=11, door=(7, 11), canopy=2, bed=True)))
        self.assertEqual((doc["spots"][0]["kind"], doc["spots"][0]["z"], doc["spots"][0]["facing"]), ("door", 11, "south"))
        self.assertTrue(any("covered step" in m for m in missing), missing)

    def test_a_door_deeper_than_the_covered_step_is_not_guessed(self):
        doc, missing = self.plan(self.f.building("arcade", storey(z1=9, door=(7, 9), canopy=5), ), use="shop")
        self.assertFalse([s for s in doc["spots"] if s["kind"] == "door"], "no door may be named when none leads out within the covered step")
        self.assertTrue(any("no street door" in m for m in missing), missing)

    def test_flats_have_a_unit_on_each_storey_with_a_bed(self):
        doc, missing = self.plan(self.f.building("block", storey(), upper=[storey(door=None, bed=True)]), use="flats")
        self.assertEqual([(u["id"], u["box"][1], u["box"][4]) for u in doc["units"]], [("flat_1", 7, 10)])
        self.assertEqual(missing, [])

    def test_public_weights_leave_buildings_out_and_a_building_in_two_rings_is_listed_once(self):
        bid = self.f.building("house", storey(bed=True))
        listing = {"share_percent": 30, "category": "small_house", "rent": 85, "deposit": 170, "buy_price": 5400, "upkeep": 17}
        selection = {"schema_version": 1, "buildings": [
            {"id": bid, "city": "valewood", "zone": 0, "weight": 4, "use": "house", "class": "house", "listing": listing},
            {"id": bid, "city": "valewood", "zone": 1, "weight": 6, "use": "house", "class": "house", "listing": listing}],
            "public_weights": {"underground_city:valewood_house": 0}}
        report = wiring.wire(self.f.pack, selection, self.f.public)
        character = json.loads((self.f.pack / "data/underground_city/city_character/valewood.json").read_text(encoding="utf-8"))
        self.assertEqual([[(b["id"], b["weight"]) for b in z["buildings"]] for z in character["zones"]],
                         [[("underground_city:valewood_shop", 3), (bid, 4)], [(bid, 6)]])
        listings = json.loads((self.f.pack / "data/underground_city/properties/fixture_private.json").read_text(encoding="utf-8"))["city_listings"]
        self.assertEqual(len(listings), 1, "the game refuses a building templated twice")
        self.assertEqual(report["cities"]["valewood"]["rings"][1]["private_share"], 1.0)

    def test_a_building_chosen_for_two_cities_is_refused(self):
        bid = self.f.building("house", storey(bed=True))
        selection = {"schema_version": 1, "buildings": [{"id": bid, "city": "valewood", "zone": 0, "use": "house"}, {"id": bid, "city": "ironhaven", "zone": 0, "use": "house"}]}
        with self.assertRaises(SystemExit):
            wiring.wire(self.f.pack, selection, self.f.public)

    def two_chunk_house(self) -> str:
        """A house over two chunks (D-198): the south part has the street door and the beds, the north part its kitchen under a roof that runs on across the border, with a
        doorway in the border wall."""
        south = storey(z0=0, z1=12, door=(7, 12), bed=True)
        south[(7, 1, 0)] = "D"
        south[(7, 2, 0)] = "d"
        north = storey(z0=3, z1=15, door=None)
        self.f.building("double_0", north)
        self.f.building("double_1", south)
        self.f.write("multibuildings", "double", {"dimx": 1, "dimz": 2, "buildings": [["double_0", "double_1"]]})
        return f"{NS}:double"

    def test_a_door_into_the_next_part_of_a_multi_chunk_building_is_not_a_street_door(self):
        mid = self.two_chunk_house()
        pack = wiring.Pack(self.f.pack)
        self.assertEqual(wiring.multi_parts(pack, mid), [(f"{NS}:double_0", 0, 0), (f"{NS}:double_1", 0, 1)], "a part named without a namespace is the multi's")
        alone, _ = wiring.plan(pack, f"{NS}:double_1", "valewood", "house")
        north = wiring.roofed_cells(pack, f"{NS}:double_0")
        beside = {(x, z - 16) for x, z in north}
        within, missing = wiring.plan(pack, f"{NS}:double_1", "valewood", "house", beside=beside)
        self.assertEqual((within["spots"][0]["x"], within["spots"][0]["z"]), (7, 12), "the street door is the one to the open street")
        self.assertEqual(within["spots"][0]["facing"], "south")
        self.assertEqual(missing, [])
        self.assertEqual(len([s for s in alone["spots"] if s["kind"] == "door"]), 2)

    def test_a_multi_chunk_building_is_wired_part_by_part_and_kept_by_its_city(self):
        mid = self.two_chunk_house()
        listing = {"share_percent": 30, "category": "small_house", "rent": 85, "deposit": 170, "buy_price": 5400, "upkeep": 17}
        selection = {"schema_version": 1, "buildings": [], "cities": ["valewood"], "multi_factor": 2.0, "multi_buildings": [
            {"id": mid, "city": "valewood", "use": "house", "class": "house", "weight": 3, "listing": listing},
            {"id": f"{NS}:none", "city": "valewood", "weight": 2}]}
        report = wiring.wire(self.f.pack, selection, self.f.public)
        for part in ("double_0", "double_1"):
            self.assertTrue((self.f.pack / "data" / NS / "city_buildings" / f"{part}.json").is_file(), part)
        character = json.loads((self.f.pack / "data/underground_city/city_character/valewood.json").read_text(encoding="utf-8"))
        self.assertEqual(character["multi_buildings"], [{"id": mid, "weight": 3}, {"id": f"{NS}:none", "weight": 2}])
        style = json.loads((self.f.pack / "data/lostcities/lostcities/citystyles/citystyle_standard.json").read_text(encoding="utf-8"))
        self.assertEqual(style["inherit"], "citystyle_common")
        self.assertEqual(style["selectors"]["multibuildings"], [{"factor": 6.0, "value": mid}], "the flatten share is not a building of the style")
        rules = json.loads((self.f.pack / "data/underground_city/building_security/fixture_private.json").read_text(encoding="utf-8"))["rules"]
        self.assertEqual(rules, [{"type_prefix": mid + "_", "class": "house", "priority": 20}])
        listings = json.loads((self.f.pack / "data/underground_city/properties/fixture_private.json").read_text(encoding="utf-8"))["city_listings"]
        self.assertEqual([entry["building"] for entry in listings], [f"{NS}:double_1"], "only the part with the street door and the bed is let")
        self.assertEqual(report["buildings"][mid]["listed_parts"], 1)

    def test_a_ring_left_without_any_building_is_refused(self):
        bid = self.f.building("house", storey(bed=True))
        selection = {"schema_version": 1, "buildings": [{"id": bid, "city": "valewood", "zone": 0, "use": "house"}],
                     "public_weights": {"underground_city:valewood_house": 0}}
        with self.assertRaises(SystemExit):
            wiring.wire(self.f.pack, selection, self.f.public)


class AuditTheFixture(unittest.TestCase):
    """building_audit.py renders a building from its data and compares it with a CityProbe dump of the same chunk."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.f = Fixture(Path(self.tmp.name))
        self.dumps = Path(self.tmp.name) / "dumps"
        self.dumps.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def dump(self, bid: str, floors: int, world: dict) -> None:
        legend, glyphs = {}, "0123456789abcdefghij"
        body = []
        for y in range(-3, floors * 6 + 10):
            body.append(f"--- y {70 + y} (street {'+' if y >= 0 else ''}{y})")
            for z in range(16):
                row = ""
                for x in range(16):
                    block = world.get((x, y, z))
                    if not block:
                        row += "."
                        continue
                    legend.setdefault(block, glyphs[len(legend)])
                    row += legend[block]
                body.append(row)
        head = [f"# {bid} chunk 4,-2 floors {floors} cellars 0 street y 70", "# x grows east (columns), z grows south (rows)",
                "# legend:" + "".join(f" {g}={b.replace('minecraft:', '')}" for b, g in legend.items())]
        (self.dumps / f"{bid.replace(':', '_')}_4_-2.txt").write_text("\n".join(head + body) + "\n", encoding="utf-8")

    def test_a_dropped_block_a_foreign_block_and_a_block_over_the_roof_are_each_counted(self):
        ground = storey(bed=True)
        ground[(8, 1, 8)] = "c"
        bid = self.f.building("house", ground)
        cells = audit.render(audit.Packs([self.f.pack]), bid, 1)
        world = {k: sorted(v)[0] for k, v in cells.items() if v}
        world.pop((8, 1, 8))                              # the carpet is gone
        world[(9, 2, 9)] = "minecraft:cobblestone"        # something inside the house that its data leaves empty
        world[(7, 13, 7)] = "minecraft:oak_leaves"        # a tree over the roof (the top part reaches y 11)
        self.dump(bid, 1, world)
        report = audit.audit(self.dumps, audit.Packs([self.f.pack]))["types"][bid]
        self.assertEqual((report["missing"], report["foreign"], report["above"]), (1, 1, 1))
        self.assertEqual(report["missing_blocks"], {"minecraft:light_gray_carpet": 1})
        self.assertEqual(report["above_blocks"], {"minecraft:oak_leaves": 1})

    def test_a_building_as_its_data_says_has_nothing_to_report(self):
        bid = self.f.building("house", storey(bed=True))
        cells = audit.render(audit.Packs([self.f.pack]), bid, 1)
        self.dump(bid, 1, {k: sorted(v)[0] for k, v in cells.items() if v})
        report = audit.audit(self.dumps, audit.Packs([self.f.pack]))["types"][bid]
        self.assertEqual((report["missing"], report["foreign"], report["other"], report["above"]), (0, 0, 0, 0))

    def test_a_storey_lost_cities_chose_among_several_is_not_counted_as_another_storeys_missing_blocks(self):
        ground = storey(bed=True)
        first = {(4, 1, 4): "c"}                          # an upper storey with a carpet in one corner
        second = {(11, 1, 11): "c"}                       # the other storey Lost Cities may pick instead: the carpet in the far corner
        bid = self.f.building("hotel", ground, upper=[first], top={})
        self.f.write("parts", "hotel_floor_b", {"xsize": 16, "zsize": 16, "slices": slices(second), "palette": [{"char": c, "block": b} for c, b in PALETTE.items()]})
        path = self.f.pack / "data" / NS / "lostcities" / "buildings" / "hotel.json"
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["parts"].insert(2, {"part": f"{NS}:hotel_floor_b", "ground": False, "top": False})
        path.write_text(json.dumps(doc), encoding="utf-8")
        # the world holds the second storey
        world = {k: sorted(v)[0] for k, v in audit.render(audit.Packs([self.f.pack]), bid, 2).items() if v}
        world.pop((4, 7, 4), None)
        world[(11, 7, 11)] = "minecraft:light_gray_carpet"
        self.dump(bid, 2, world)
        report = audit.audit(self.dumps, audit.Packs([self.f.pack]))["types"][bid]
        self.assertEqual((report["missing"], report["foreign"]), (0, 0), "the storey the game chose is the one compared")
        world.pop((11, 7, 11))                            # and a carpet that really is gone is still found
        self.dump(bid, 2, world)
        self.assertEqual(audit.audit(self.dumps, audit.Packs([self.f.pack]))["types"][bid]["missing"], 1)

    def test_an_inline_palette_in_lost_cities_object_form_is_read(self):
        # Lost Cities writes a building's or part's own palette as {"palette": [...]}; read as a list it was skipped and its characters matched anything
        ground = storey(bed=True)
        bid = self.f.building("house", ground)
        path = self.f.pack / "data" / NS / "lostcities" / "parts" / "house_ground.json"
        doc = json.loads(path.read_text(encoding="utf-8"))
        doc["palette"] = {"palette": doc["palette"]}
        path.write_text(json.dumps(doc), encoding="utf-8")
        cells = audit.render(audit.Packs([self.f.pack]), bid, 1)
        wall = next(k for k, c in ground.items() if c == "#")
        self.assertEqual(cells[wall], frozenset({"minecraft:stone_bricks"}), "the ground part's # is read from its object-form palette, not matched as anything")

    def test_a_dump_that_gave_a_block_kind_the_dot_of_air_does_not_count_it_as_missing(self):
        # Before the ninth pass the probe's alphabet held the dot, so the 71st block kind of a chunk was written as "." like air: one kind of block per
        # chunk read as missing (a roof's slabs, a vent, two iron bars). A dot such a dump lists in its legend is that block or air, and counts as neither.
        bid = self.f.building("house", storey(bed=True))
        world = {k: sorted(v)[0] for k, v in audit.render(audit.Packs([self.f.pack]), bid, 1).items() if v}
        self.dump(bid, 1, world)
        path = next(self.dumps.glob("*.txt"))
        lines = path.read_text(encoding="utf-8").split("\n")
        glyph = next(item[0] for item in lines[2][len("# legend:"):].split() if item.endswith("=red_bed"))
        lines[2] = lines[2].replace(f" {glyph}=", " .=")
        lines[3:] = [line if line.startswith("---") else line.replace(glyph, ".") for line in lines[3:]]
        path.write_text("\n".join(lines), encoding="utf-8")
        report = audit.audit(self.dumps, audit.Packs([self.f.pack]))["types"][bid]
        self.assertEqual((report["missing"], report["foreign"]), (0, 0))

    def test_the_probes_alphabet_has_no_dot_equals_sign_or_question_mark_and_no_glyph_twice(self):
        source = (ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/CityProbe.java").read_text(encoding="utf-8")
        glyphs = source.split('GLYPHS = "', 1)[1].split('"', 1)[0]
        self.assertFalse(set(glyphs) & set(".=? #"), "the dot is air, = joins the legend and ? is a reader's unknown glyph")
        self.assertEqual(len(glyphs), len(set(glyphs)))
        self.assertGreaterEqual(len(glyphs), 80)


if __name__ == "__main__":
    unittest.main()
