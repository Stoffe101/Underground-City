import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import validate_city_blocks as v  # noqa: E402
import citykit  # noqa: E402

REGISTRY = {
    "minecraft:oak_planks": {},
    "minecraft:oak_door": {"facing": ["north", "south", "east", "west"], "half": ["upper", "lower"], "hinge": ["left", "right"], "open": ["true", "false"]},
    "modernlife:oak_chair": {"facing": ["north", "south", "east", "west"]},
}


class CityBlockTests(unittest.TestCase):
    def test_a_known_block_with_valid_properties_has_no_problems(self):
        self.assertEqual(v.problems_of("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false]", REGISTRY), [])
        self.assertEqual(v.problems_of("minecraft:oak_planks", REGISTRY), [])

    def test_an_unknown_block_is_named(self):
        self.assertEqual(v.problems_of("modernlife:night_stand", REGISTRY), ["unknown block modernlife:night_stand"])

    def test_an_unknown_property_or_value_is_named(self):
        self.assertIn("no property colour", v.problems_of("modernlife:oak_chair[colour=red]", REGISTRY)[0])
        self.assertIn("facing=up is not one of", v.problems_of("modernlife:oak_chair[facing=up]", REGISTRY)[0])

    def test_a_palette_with_weighted_entries_is_flattened(self):
        palette = citykit.Palette("t")
        palette.char("minecraft:oak_planks")
        palette.char([(70, "minecraft:oak_planks"), (0, "modernlife:night_stand")])
        self.assertEqual(sorted(v.specs_of(palette.entries)), ["minecraft:oak_planks", "minecraft:oak_planks", "modernlife:night_stand"])

    def test_garbage_is_reported_not_raised(self):
        self.assertEqual(len(v.problems_of("Not A Block", REGISTRY)), 1)

    def test_a_palette_never_takes_a_character_lost_cities_draws_the_city_with(self):
        # a Blackwater office once gave "y" (the raised block's retaining wall) to a sofa, and the wall under it came out as sofas
        palette = citykit.Palette("t")
        chars = {palette.char(f"minecraft:block_{i}") for i in range(120)}
        self.assertFalse(chars & set(citykit.LOST_CITIES_RESERVED))
        for path in sorted((Path(__file__).resolve().parents[1] / "content/generated/world-furnished/pack/data/underground_city/lostcities/palettes").glob("*.json")):
            used = {entry["char"] for entry in __import__("json").loads(path.read_text(encoding="utf-8"))["palette"]}
            self.assertFalse(used & set(citykit.LOST_CITIES_RESERVED), f"{path.name} redraws the city with {sorted(used & set(citykit.LOST_CITIES_RESERVED))}")



class StreetDoorTests(unittest.TestCase):
    """Every street door of a furnished building can be walked through: nothing that blocks a person stands in the cell before it or behind it, at the feet or the head. A ring of hedge
    planters stood before all four doors of the Blackwater lofts, and a sofa behind the Valewood cottage's front door (homes run 1, seventh pass)."""

    PASSABLE = ("carpet", "pressure_plate", "rail", "button", "torch", "sign", "light_bulb", "banner", "floor_mat", "poppy", "dandelion", "daisy", "minecraft:grass", "fern", "air")

    def passable(self, spec) -> bool:
        options = [s for _, s in spec] if isinstance(spec, list) else [spec]
        return all(any(p in str(o) for p in self.PASSABLE) for o in options)

    def test_every_street_door_can_be_walked_through(self):
        import city_content
        doors = 0
        for b in city_content.all_buildings():
            ground = next(part for part, flags in b.parts if flags.get("ground"))
            for spot in (s for s in b.spots if s.kind == "door"):
                doors += 1
                dx, dz = citykit.DIRS[spot.facing]
                for side in (1, -1):
                    x, z = spot.x + side * dx, spot.z + side * dz
                    if not (0 <= x < ground.xsize and 0 <= z < ground.zsize):
                        continue
                    for y in (spot.y, spot.y + 1):
                        ch = ground.get(x, y, z)
                        if ch != " ":
                            self.assertTrue(self.passable(ground.palette.entries[ch]), f"{b.id} {spot.note} at {spot.x},{spot.z}: {ground.palette.entries[ch]} stands at {x},{y},{z}")
        self.assertGreater(doors, 20)


class CameraTests(unittest.TestCase):
    """Lost Cities writes a part lower x and lower z first and, as each block goes in, has its neighbours check their shape; a wall-hung camera whose wall is written after it finds
    no wall and turns to air. The first cameras by tier (D-149) hung over south doors looking north and never existed in a generated world (read back from the advanced burglary run's world)."""

    LOSTCITIES = Path(__file__).resolve().parents[1] / "content/generated/world-furnished/pack/data/underground_city/lostcities"

    def cameras(self):
        import json
        for palette_path in sorted((self.LOSTCITIES / "palettes").glob("*.json")):
            entries = {e["char"]: e.get("block", "") for e in json.loads(palette_path.read_text(encoding="utf-8"))["palette"]}
            for part_path in sorted((self.LOSTCITIES / "parts").glob(palette_path.stem + "_*.json")):
                part = json.loads(part_path.read_text(encoding="utf-8"))
                for y, layer in enumerate(part["slices"]):
                    for z, row in enumerate(layer):
                        for x, ch in enumerate(row):
                            if "cctv_camera" in entries.get(ch, ""):
                                facing = entries[ch].split("facing=")[1].split(",")[0].rstrip("]")
                                behind = citykit.DIRS[facing]
                                bx, bz = x - behind[0], z - behind[1]
                                back = layer[bz][bx] if 0 <= bz < len(layer) and 0 <= bx < len(layer[bz]) else " "
                                yield part_path.stem, (x, y, z), facing, back

    def test_every_camera_outlives_lost_cities_writing_its_part(self):
        import city_content
        found = list(self.cameras())
        self.assertTrue(found)
        for part, at, facing, back in found:
            self.assertTrue(back == " " or facing in city_content.CAMERA_WALL_FIRST, f"{part} {at}: a camera looking {facing} with a wall behind it that Lost Cities writes after it")

    def test_a_camera_on_a_wall_written_after_it_is_refused(self):
        import city_content
        palette = citykit.Palette("t")
        part = citykit.Part("t", palette)
        part.put(7, 3, 14, "minecraft:stone")
        self.assertFalse(city_content.camera_survives(part, 7, 3, 13, "north"))   # the club's first camera: its wall to the south, written after it
        part.put(2, 3, 8, "minecraft:stone")
        self.assertTrue(city_content.camera_survives(part, 3, 3, 8, "east"))      # its wall to the west, written first
        self.assertTrue(city_content.camera_survives(part, 7, 3, 10, "west"))     # nothing behind it

    def test_the_well_off_buildings_watch_a_street_door(self):
        parts = {part for part, _, _, _ in self.cameras()}
        for building in ("bw_club", "bw_apartments", "bw_office", "ih_warehouse"):
            self.assertIn(building + "_ground", parts, f"{building} (tier 4 or more) has no camera on its ground floor")


if __name__ == "__main__":
    unittest.main()
