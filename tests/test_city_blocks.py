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


if __name__ == "__main__":
    unittest.main()
