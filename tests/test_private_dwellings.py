"""Where each home of a building ends (scripts/private_dwellings.py, D-206), on INVENTED buildings drawn here; no private content is used."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import private_dwellings as dw  # noqa: E402

PALETTE = {"#": "minecraft:stone_bricks", "=": "minecraft:oak_planks", "B": "minecraft:red_bed[facing=north,part=head]", "b": "minecraft:red_bed[facing=north,part=foot]",
           "U": "minecraft:oak_stairs[facing=north]", "w": "cfm:oak_wardrobe[facing=south]", "c": "minecraft:chest[facing=south]",
           "N": "minecraft:oak_door[facing=north,half=lower]", "n": "minecraft:oak_door[facing=north,half=upper]",
           "S": "minecraft:oak_door[facing=south,half=lower]", "s": "minecraft:oak_door[facing=south,half=upper]",
           "E": "minecraft:oak_door[facing=east,half=lower]", "e": "minecraft:oak_door[facing=east,half=upper]",
           "W": "minecraft:oak_door[facing=west,half=lower]", "v": "minecraft:oak_door[facing=west,half=upper]"}
UPPER = {"N": "n", "S": "s", "E": "e", "W": "v"}


def storey(plan: list[str]) -> list[list[str]]:
    """Six layers (floor slab, four inside, ceiling) from a 16 x 16 plan read at head height:
    '#' wall, '.' floor, 'o' yard (no roof), 'N'/'S'/'E'/'W' a door facing that way, 'B' a bed, 'U' a stair under an opening, 'u' a stair under the ceiling (a seat),
    'w' a wardrobe of a furniture mod two blocks high, 'c' a chest."""
    assert len(plan) == 16 and all(len(r) == 16 for r in plan), plan
    layers = [[[" "] * 16 for _ in range(16)] for _ in range(6)]
    for z, row in enumerate(plan):
        for x, ch in enumerate(row):
            if ch != "o":
                layers[0][z][x] = "="
                layers[5][z][x] = "="
            if ch == "#":
                for y in range(1, 5):
                    layers[y][z][x] = "#"
            elif ch in UPPER:
                layers[1][z][x], layers[2][z][x] = ch, UPPER[ch]
                for y in (3, 4):
                    layers[y][z][x] = "#"
            elif ch == "B":
                layers[1][z][x] = "B"
            elif ch in "Uu":
                layers[1][z][x] = "U"
                if ch == "U":
                    layers[5][z][x] = " "
            elif ch == "w":
                layers[1][z][x] = layers[2][z][x] = "w"
            elif ch == "c":
                layers[1][z][x] = "c"
    return [["".join(r) for r in layer] for layer in layers]


def building(parts: dict[str, list[list[str]]]):
    """layers_of for invented parts: each part a list of storey plans, bottom first."""
    def layers_of(part: str):
        out = []
        for n, plan in enumerate(parts[part]):
            slices = storey(plan)
            if n + 1 < len(parts[part]):
                # a stair's opening goes through the next storey's floor slab too
                above = storey(parts[part][n + 1])
                for z, row in enumerate(plan):
                    for x, ch in enumerate(row):
                        if ch == "U":
                            r = list(above[0][z]); r[x] = " "; above[0][z] = "".join(r)
                parts[part][n + 1] = parts[part][n + 1]
            out.append((n * dw.FLOOR, slices, PALETTE))
        # the opening in each upper storey's floor over a stair below
        for n in range(1, len(out)):
            y0, slices, palette = out[n]
            below = parts[part][n - 1]
            for z, row in enumerate(below):
                for x, ch in enumerate(row):
                    if ch == "U":
                        r = list(slices[0][z]); r[x] = " "; slices[0][z] = "".join(r)
        return out, {}
    return layers_of


def rows(*lines: str) -> list[str]:
    assert len(lines) == 16
    return list(lines)


YARD = "o" * 16
# a house shell over a part: walls round x 0..15 z 1..14, with the side towards the other part open ("left" open on the west, "right" on the east), a door on the south wall
def house_part(open_west: bool, open_east: bool, door_x: int | None, bed: bool, wall_west=True, wall_east=True) -> list[str]:
    out = [YARD]
    for z in range(1, 15):
        r = ["."] * 16
        if z in (1, 14):
            r = ["#"] * 16
        if not open_west and wall_west:
            r[0] = "#"
        if not open_east and wall_east:
            r[15] = "#"
        out.append("".join(r))
    if door_x is not None:
        r = list(out[14]); r[door_x] = "S"; out[14] = "".join(r)
    if bed:
        r = list(out[5]); r[4] = "B"; out[5] = "".join(r)
    out.append(YARD)
    return out


class Rooms(unittest.TestCase):
    def test_a_detached_house_over_two_chunks_is_one_dwelling_with_rooms_in_both(self):
        layers_of = building({"x:west": [house_part(False, True, 7, True)], "x:east": [house_part(True, False, None, False)]})
        found, report = dw.analyse([("x:west", 0, 0), ("x:east", 1, 0)], layers_of, "house")
        self.assertEqual(report["uncertain"], [])
        self.assertEqual(len(found), 1, report)
        self.assertEqual({tuple(r["at"]) for r in found[0]["rooms"]}, {(0, 0), (1, 0)}, "the house's rooms are in both chunks")
        self.assertEqual(found[0]["id"], "house")

    def test_two_terraced_houses_with_a_wall_between_are_two_dwellings_that_never_touch(self):
        layers_of = building({"x:west": [house_part(False, False, 7, True)], "x:east": [house_part(False, False, 7, True)]})
        found, report = dw.analyse([("x:west", 0, 0), ("x:east", 1, 0)], layers_of, "house")
        self.assertEqual(len(found), 2, report)
        self.assertEqual([[tuple(r["at"]) for r in d["rooms"]] for d in found], [[(0, 0)], [(1, 0)]])
        self.assertEqual(dw.overlaps(found), "")

    def test_a_house_part_with_no_street_door_of_its_own_and_no_way_in_is_nobodys(self):
        # the east part is sealed from the west by a wall and has no door: its rooms are nobody's, the west house is still one
        layers_of = building({"x:west": [house_part(False, False, 7, True)], "x:east": [house_part(False, False, None, True)]})
        found, report = dw.analyse([("x:west", 0, 0), ("x:east", 1, 0)], layers_of, "house")
        self.assertEqual(len(found), 1)
        self.assertEqual(report["nobodys_rooms"], 1)

    def test_premises_with_two_street_doors_are_one_lease(self):
        # the shop and its store room over two chunks, a wall between them and a street door each: one lease (the owner's rule for premises)
        layers_of = building({"x:west": [house_part(False, False, 7, False)], "x:east": [house_part(False, False, 7, False)]})
        found, report = dw.analyse([("x:west", 0, 0), ("x:east", 1, 0)], layers_of, "shop")
        self.assertEqual(len(found), 1, report)
        self.assertEqual({tuple(r["at"]) for r in found[0]["rooms"]}, {(0, 0), (1, 0)})

    def test_a_garage_behind_its_own_door_with_no_bed_is_no_home_and_the_house_beside_it_is_still_one(self):
        layers_of = building({"x:west": [house_part(False, False, 7, True)], "x:east": [house_part(False, False, 7, False)]})
        found, report = dw.analyse([("x:west", 0, 0), ("x:east", 1, 0)], layers_of, "house")
        self.assertEqual(len(found), 1, report)
        self.assertEqual({tuple(r["at"]) for r in found[0]["rooms"]}, {(0, 0)})
        self.assertEqual(report["uncertain"], [])
        self.assertTrue(report.get("notes"), "the garage is not reported")

    def test_furniture_against_a_wall_and_a_chest_are_inside_the_room(self):
        plan = house_part(False, False, 7, True)
        r = list(plan[2]); r[1] = "w"; r[2] = "c"; plan[2] = "".join(r)
        found, _ = dw.analyse([("x:one", 0, 0)], building({"x:one": [plan]}), "house")
        cells = {(x, z) for room in found[0]["rooms"] for x in range(room["box"][0], room["box"][3] + 1) for z in range(room["box"][2], room["box"][5] + 1)}
        self.assertIn((1, 2), cells, "a wardrobe against the wall is outside the home: its things would be in the shell")
        self.assertIn((2, 2), cells)


def flats_ground(stair: str = "U", bed_in_hall: bool = False) -> tuple[list[str], list[str]]:
    """The ground floor of a block over two parts: a yard (z 0), the front wall with the street door (z 1, west part), a hall (z 2 and 3) with the stair, the flats' wall with
    a door each (z 4), a flat each side (z 5 to 13), the back wall (z 14) and a yard. The parts meet at the border with a wall between the flats but none in the hall."""
    def part(west: bool) -> list[str]:
        out = [YARD]
        front = list("#" * 16)
        if west:
            front[7] = "N"
        out.append("".join(front))
        for z in (2, 3):
            r = ["."] * 16
            if west:
                r[0] = "#"
            else:
                r[15] = "#"
            out.append("".join(r))
        wall = list("#" * 16); wall[7] = "N"
        out.append("".join(wall))
        for z in range(5, 14):
            r = ["."] * 16
            r[0] = "#" if west else r[0]
            r[15] = "#"
            if not west:
                r[0] = "#"
            out.append("".join(r))
        out.append("#" * 16)
        out.append(YARD)
        r = list(out[8]); r[5] = "B"; out[8] = "".join(r)
        if west:
            r = list(out[2]); r[12] = stair; out[2] = "".join(r)
            if bed_in_hall:
                r = list(out[3]); r[3] = "B"; out[3] = "".join(r)
        return out
    return part(True), part(False)


def flats_upper() -> tuple[list[str], list[str]]:
    """The first floor: the hall over the one below (where the stair arrives), and one flat over both parts with a door from the hall (west part), no wall at the border."""
    def part(west: bool) -> list[str]:
        out = [YARD, "#" * 16]
        for z in (2, 3):
            r = ["."] * 16
            r[0 if west else 15] = "#"
            out.append("".join(r))
        wall = list("#" * 16)
        if west:
            wall[7] = "N"
        out.append("".join(wall))
        for z in range(5, 14):
            r = ["."] * 16
            r[0 if west else 15] = "#"
            out.append("".join(r))
        out.append("#" * 16)
        out.append(YARD)
        if not west:
            r = list(out[9]); r[9] = "B"; out[9] = "".join(r)
        return out
    return part(True), part(False)


class Flats(unittest.TestCase):
    def grid(self, ground, upper=None):
        west, east = ground
        parts = {"x:west": [west], "x:east": [east]}
        if upper:
            parts["x:west"].append(upper[0])
            parts["x:east"].append(upper[1])
        return [("x:west", 0, 0), ("x:east", 1, 0)], building(parts)

    def test_flats_over_two_chunks_share_the_hall_and_stairs_and_each_flat_is_its_own(self):
        grid, layers_of = self.grid(flats_ground(), flats_upper())
        found, report = dw.analyse(grid, layers_of, "flats")
        self.assertEqual(report["uncertain"], [])
        self.assertEqual(len(found), 3, report)
        ats = [sorted({tuple(r["at"]) for r in d["rooms"]}) for d in found]
        self.assertEqual(ats, [[(0, 0)], [(1, 0)], [(0, 0), (1, 0)]], "two flats downstairs, one over both parts upstairs")
        hall = {(x, z) for x in range(1, 16) for z in (2, 3)}
        for d in found:
            for room in d["rooms"]:
                if room["at"] == [0, 0] and room["box"][1] < dw.FLOOR:
                    cells = {(x, z) for x in range(room["box"][0], room["box"][3] + 1) for z in range(room["box"][2], room["box"][5] + 1)}
                    self.assertFalse(cells & hall, "a flat took part of the shared hall")
        self.assertEqual(dw.overlaps(found), "")

    def test_a_hall_with_a_street_door_a_bed_and_a_stair_is_reported_and_nothing_is_guessed(self):
        grid, layers_of = self.grid(flats_ground(bed_in_hall=True), flats_upper())
        found, report = dw.analyse(grid, layers_of, "flats")
        self.assertEqual(found, [])
        self.assertTrue(any("a flat or the hall" in u for u in report["uncertain"]), report)

    def test_a_stair_under_a_whole_ceiling_is_a_seat_not_a_staircase(self):
        grid, layers_of = self.grid(flats_ground(stair="u"), flats_upper())
        found, report = dw.analyse(grid, layers_of, "flats")
        # without the staircase the upper hall is joined to nothing common: the flat behind it has no way in, and is nobody's
        self.assertEqual(len(found), 2, report)
        self.assertGreaterEqual(report["nobodys_rooms"], 1)


class Boxes(unittest.TestCase):
    def test_rectangles_cover_a_shape_exactly_once(self):
        cells = {(x, z) for x in range(1, 9) for z in range(1, 6)} | {(x, z) for x in range(1, 4) for z in range(6, 12)}
        rects = dw.rectangles(cells)
        covered = [(x, z) for x0, z0, x1, z1 in rects for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)]
        self.assertEqual(sorted(covered), sorted(cells), "a cell covered twice or missed")
        self.assertLessEqual(len(rects), 2)

    def test_a_house_over_two_storeys_takes_in_its_own_floor_and_keeps_its_outer_walls_and_door_as_shell(self):
        plan = house_part(False, False, 7, True)
        upper = house_part(False, False, None, False)
        r = list(plan[13]); r[14] = "U"; plan[13] = "".join(r)
        found, report = dw.analyse([("x:one", 0, 0)], building({"x:one": [plan, upper]}), "house")
        self.assertEqual(len(found), 1, report)
        tall = [room["box"] for room in found[0]["rooms"] if room["box"][1] == 1 and room["box"][4] >= 4]
        self.assertTrue(tall, "the ground floor's layers were not stacked into boxes of the room's height")
        upstairs = [room["box"] for room in found[0]["rooms"] if room["box"][4] >= dw.FLOOR + 1]
        self.assertTrue(upstairs, "the stairs did not join the upper storey to the house")
        # the floor between the storeys is the house's own (a tenant's house, as a single lot's one box from the ground floor to under the roof): a box runs through it
        self.assertTrue(any(b[1] <= dw.FLOOR <= b[4] for b in (room["box"] for room in found[0]["rooms"])), "the house's own floor between its storeys is not inside it")
        # its outer walls and its front door stay the city's shell
        door = (7, 1, 14)
        self.assertFalse(any(b[0] <= door[0] <= b[3] and b[1] <= door[1] <= b[4] and b[2] <= door[2] <= b[5] for b in (room["box"] for room in found[0]["rooms"])),
                         "the front door is inside the house's boxes")

    def test_two_dwellings_with_no_wall_between_are_named(self):
        a = {"id": "flat_1", "rooms": [{"at": [0, 0], "box": [1, 1, 1, 15, 4, 5]}]}
        b = {"id": "flat_2", "rooms": [{"at": [1, 0], "box": [0, 1, 1, 14, 4, 5]}]}
        self.assertIn("no wall", dw.overlaps([a, b]))


if __name__ == "__main__":
    unittest.main()
