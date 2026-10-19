"""The production-style boot check's verdicts (scripts/run_prod_boot_check.evaluate) on synthetic server logs: every fact the October 19 pass made mandatory must fail the check when it is missing or wrong."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import run_prod_boot_check as boot  # noqa: E402

BASE = [
    "[UC-BOOT] mod underground_city=true", "[UC-BOOT] mod qshop=true",
    "[UC-BOOT] mod sophisticatedbackpacks=true", "[UC-BOOT] mod sophisticatedcore=true", "[UC-BOOT] mod thirstwastaken2=true", "[UC-BOOT] mod farmersdelight=true", "[UC-BOOT] mod comforts=true",
    "[UC-BOOT] simulation=SIMULATION", "[UC-BOOT] backpack_policy=ok", "[UC-BOOT] monster_routes=ok", "[UC-BOOT] nav_unloaded_chunk=ok",
    "[11:00:00] [Server thread/INFO] [UndergroundCity/Simulation/]: [UC-SIMULATION] hostile creatures: SIMULATION (x)",
    "[UC-BOOT] done=true",
]


def check(lines, sets=("survival",)):
    return boot.evaluate(list(lines), list(sets))[1]


class BootVerdicts(unittest.TestCase):
    def test_a_complete_survival_boot_passes(self):
        self.assertEqual(check(BASE), [])

    def test_the_navigation_fact_is_required(self):
        problems = check([l for l in BASE if "nav_unloaded_chunk" not in l])
        self.assertTrue(any("path request" in p for p in problems), problems)
        problems = check([l.replace("nav_unloaded_chunk=ok", "nav_unloaded_chunk=FAILED path=null chunkStillUnloaded=false ms=900") for l in BASE])
        self.assertTrue(any("path request" in p for p in problems), problems)

    def test_the_backpack_policy_must_match(self):
        problems = check([l.replace("backpack_policy=ok", "backpack_policy=drift") for l in BASE])
        self.assertTrue(any("backpack configuration" in p for p in problems), problems)

    def test_every_monster_drop_needs_a_route(self):
        problems = check([l.replace("monster_routes=ok", "monster_routes=FAILED minecraft:gunpowder") for l in BASE])
        self.assertTrue(any("hostile drop" in p for p in problems), problems)

    def test_the_world_must_be_a_simulation(self):
        problems = check([l.replace("simulation=SIMULATION", "simulation=CLASSIC") for l in BASE])
        self.assertTrue(any("SIMULATION" in p for p in problems), problems)

    def test_the_retired_backpack_mod_must_not_load_with_the_new_one(self):
        problems = check(BASE + ["[UC-BOOT] mod travelersbackpack=true"])
        self.assertTrue(any("Traveler" in p for p in problems), problems)


if __name__ == "__main__":
    unittest.main()
