"""The archipelago layout: three separate islands joined by three bridges (scripts/plan_archipelago.py).

These tests check the *plan* (the numbers every other part is generated from). They say nothing about how the generated terrain looks;
that is photographed from the game and measured by scripts/run_terrain_map.py (docs/technical/archipelago-world.md).
"""
import json
import math
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import plan_archipelago as plan  # noqa: E402

WALK = plan.WALK_SPEED


def land_distance(point, shapes):
    """Blocks inside the land (positive) or outside it (negative) at a point, before the shore noise."""
    land, bay = -1e9, -1e9
    for shape in shapes:
        if shape["type"] == "disc":
            inside = shape["radius"] - math.dist(point, shape["center"])
        else:
            ax, az = shape["from"]; bx, bz = shape["to"]
            dx, dz = bx - ax, bz - az
            t = max(0.0, min(1.0, ((point[0] - ax) * dx + (point[1] - az) * dz) / (dx * dx + dz * dz)))
            inside = shape["radius"] - math.dist(point, (ax + dx * t, az + dz * t))
        if shape["op"] == "carve":
            bay = max(bay, inside)
        else:
            land = max(land, inside)
    return min(land, -bay)


class ArchipelagoPlanTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cities, cls.corridors, cls.islands, _ = plan.build()
        cls.by_id = {c["id"]: c for c in cls.cities["cities"]}
        cls.centre = {c: tuple(v["center"]) for c, v in cls.islands["cities"].items()}
        cls.shapes = cls.islands["plan"]["shapes"]

    def test_committed_blueprints_match_the_plan(self):
        for name, text in plan.render().items():
            self.assertEqual((plan.OUT / name).read_text(encoding="utf-8"), text, f"{name} is stale; run scripts/plan_archipelago.py --write")
        for path, text in plan.render_core().items():
            self.assertEqual(path.read_text(encoding="utf-8"), text, f"{path.name} is stale; run scripts/plan_archipelago.py --write")

    def test_three_cities_and_three_routes(self):
        self.assertEqual(set(self.by_id), {"ironhaven", "valewood", "blackwater"})
        self.assertEqual(len(self.corridors["routes"]), 3)
        self.assertEqual({(r["from"], r["to"]) for r in self.corridors["routes"]}, set(plan.PAIRS))

    def test_walking_between_city_outskirts_is_three_to_five_minutes(self):
        for route in self.corridors["routes"]:
            a, b = self.by_id[route["from"]], self.by_id[route["to"]]
            edge = math.dist(self.centre[a["id"]], self.centre[b["id"]]) - a["radius_blocks"] - b["radius_blocks"]
            minutes = edge / WALK / 60
            self.assertTrue(3.0 <= minutes <= 5.0, f"{route['id']}: {minutes:.1f} min between city edges")

    def test_centre_to_centre_stays_inside_the_ten_minute_hard_limit(self):
        for a, b in plan.PAIRS:
            minutes = math.dist(self.centre[a], self.centre[b]) / WALK / 60
            self.assertLessEqual(minutes, 8.0, f"{a}-{b}: {minutes:.1f} min centre to centre")

    def test_the_cities_are_far_smaller_than_the_legacy_circles(self):
        legacy = {"ironhaven": 600, "valewood": 410, "blackwater": 570}
        for city, radius in legacy.items():
            self.assertLess(self.by_id[city]["radius_blocks"], radius * 0.65)

    def test_every_city_circle_is_dry_land_with_a_wide_margin(self):
        for city, spec in self.islands["cities"].items():
            cx, cz = spec["center"]
            for degrees in range(0, 360, 15):
                point = (cx + math.cos(math.radians(degrees)) * spec["radius"], cz + math.sin(math.radians(degrees)) * spec["radius"])
                self.assertGreater(land_distance(point, self.shapes), 120 - plan.SHORE_NOISE["amplitude"] * 2,
                                   f"{city} at {degrees} degrees is too close to the water")

    def test_the_straits_are_open_water_between_the_cape_tips(self):
        # the midpoint of each road's open stretch is sea, not land: the bridge is what crosses it
        for route in self.corridors["routes"]:
            a, b = self.centre[route["from"]], self.centre[route["to"]]
            middle = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            self.assertLess(land_distance(middle, self.shapes), -40, f"{route['id']} has no strait to bridge")

    def test_each_road_starts_and_ends_on_land(self):
        for route in self.corridors["routes"]:
            for point in (route["waypoints"][0], route["waypoints"][-1]):
                self.assertGreater(land_distance(point, self.shapes), 100, route["id"])

    def test_the_water_a_bridge_crosses_is_short_enough_to_build(self):
        for route in self.corridors["routes"]:
            water = 0
            points = route["waypoints"]
            for (x0, z0), (x1, z1) in zip(points, points[1:]):
                steps = int(math.dist((x0, z0), (x1, z1)) // 4)
                for i in range(steps):
                    t = i / steps
                    if land_distance((x0 + (x1 - x0) * t, z0 + (z1 - z0) * t), self.shapes) < plan.SHORE_NOISE["amplitude"] * -1:
                        water += 4
            self.assertTrue(120 <= water <= 380, f"{route['id']}: {water} blocks of water before the shore noise")

    def test_islands_do_not_touch(self):
        for a, b in plan.PAIRS:
            # sample the straight line between the centres: there must be a clear gap of sea
            gap = 0
            ca, cb = self.centre[a], self.centre[b]
            for i in range(0, 401):
                t = i / 400
                point = (ca[0] + (cb[0] - ca[0]) * t, ca[1] + (cb[1] - ca[1]) * t)
                if land_distance(point, self.shapes) < 0:
                    gap += math.dist(ca, cb) / 400
            self.assertGreater(gap, 150, f"{a} and {b} are almost joined")

    def test_the_arrival_site_is_on_flat_ground_on_land_and_outside_every_city(self):
        arrival = self.islands["arrival"]
        point = tuple(arrival["anchor"])
        self.assertGreater(land_distance(point, self.shapes), 120)
        for city, spec in self.islands["cities"].items():
            self.assertGreater(math.dist(point, spec["center"]), spec["radius"] + 60, city)
        self.assertTrue(any(p["id"] == "arrival_landing" and p["center"] == arrival["anchor"] for p in self.islands["plan"]["plateaus"]))

    def test_every_island_has_its_own_climate_and_none_is_frozen_or_hot(self):
        temperatures = {c["island"]: c["temperature"] for c in self.islands["plan"]["climate"]}
        humidities = {c["island"]: c["humidity"] for c in self.islands["plan"]["climate"]}
        self.assertEqual(len(set(temperatures.values())), 3)
        for island, value in temperatures.items():
            # Minecraft's temperate band is -0.15 to 0.2 (cold below it is taiga and snow, warm above it is savanna and jungle): keep every island, with the noise added, inside it
            self.assertGreater(value - 0.10, -0.15, island)
            self.assertLess(value + 0.10, 0.20, island)
        self.assertGreater(humidities["valewood"], humidities["ironhaven"])
        self.assertGreater(humidities["blackwater"], humidities["valewood"] + 0.25)  # Blackwater is the dark, wet one

    def test_plan_is_deterministic(self):
        again = plan.render()
        self.assertEqual(again, plan.render())

    def test_corridors_keep_the_checkpoint_contract(self):
        for route in self.corridors["routes"]:
            self.assertEqual(len(route["checkpoints"]), 2)
            self.assertEqual(route["crossings"][0]["kind"], "proposed_bridge")
            self.assertIn("boat", route["travel_modes"])


if __name__ == "__main__":
    unittest.main()
