"""Data checks for the legal/underground career systems (D-049..D-053).

These read the committed datapack JSON, so they fail when skill trees, laws, contraband, buyers, contracts, the
dev-pack manifest or the GUI coverage manifest drift apart. They are arithmetic and schema checks, not gameplay tests;
the Forge GameTests cover behaviour against the real loaded datapack.
"""
import hashlib
import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import generate_regional_content as content  # noqa: E402
import validate_underground_balance as balance  # noqa: E402

DATA = ROOT / "rpg-core/src/main/resources/data/underground_city"
CAREERS = ("farmer", "vintner", "smith", "gunsmith", "technician")
CITIES = ("ironhaven", "valewood", "blackwater")
BRANCHES = {"core", "legal", "underground", "utility", "crossover"}


def load(name):
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in sorted((DATA / name).glob("*.json"))}


class SkillTreeTests(unittest.TestCase):
    def setUp(self):
        self.trees = load("skill_trees")

    def test_every_career_has_a_tree_with_both_branches(self):
        self.assertEqual(set(self.trees), set(CAREERS))
        for career, tree in self.trees.items():
            branches = {n["branch"] for n in tree["nodes"]}
            self.assertTrue({"legal", "underground", "core"} <= branches, career)
            self.assertLessEqual(branches, BRANCHES)

    def test_whole_tree_is_affordable_at_max_level_so_nothing_is_a_permanent_choice(self):
        for career, tree in self.trees.items():
            self.assertLessEqual(sum(n["cost"] for n in tree["nodes"]), 29, career)

    def test_prerequisites_exist_are_acyclic_and_levels_never_decrease(self):
        for career, tree in self.trees.items():
            nodes = {n["id"]: n for n in tree["nodes"]}
            self.assertEqual(len(nodes), len(tree["nodes"]), "duplicate ids")
            for node in nodes.values():
                self.assertTrue(node["id"].startswith(career + "."))
                self.assertLessEqual(len(node["description"]), 170)
                self.assertLessEqual(len(node["title"]), 40)
                for required in node["requires"]:
                    self.assertIn(required, nodes, node["id"])
                    self.assertLessEqual(nodes[required]["min_level"], node["min_level"], node["id"])

            def reaches(start, target, seen):
                for required in nodes[start]["requires"]:
                    if required == target or (required not in seen and not seen.add(required) and reaches(required, target, seen)):
                        return True
                return False

            for node_id in nodes:
                self.assertFalse(reaches(node_id, node_id, set()), f"cycle through {node_id}")

    def test_the_underground_branch_builds_on_the_legal_one_it_does_not_replace_it(self):
        # "Farmer -> Advanced Farming -> Restricted Cultivation": the first underground skill needs a legal or core skill.
        for career, tree in self.trees.items():
            nodes = {n["id"]: n for n in tree["nodes"]}
            first_underground = [n for n in tree["nodes"] if n["branch"] == "underground" and
                                 all(nodes[r]["branch"] != "underground" for r in n["requires"])]
            self.assertTrue(first_underground, career)
            for node in first_underground:
                self.assertTrue(node["requires"], f"{node['id']} must build on a legal or foundation skill")

    def test_the_legal_line_never_needs_a_crime_and_a_crossover_joins_both(self):
        # D-098: a character who only follows the law reaches every legal, foundation and utility skill; a crossover needs a legal and an underground skill.
        for career, tree in self.trees.items():
            nodes = {n["id"]: n for n in tree["nodes"]}

            def ancestors(node_id, seen=None):
                seen = set() if seen is None else seen
                for required in nodes[node_id]["requires"]:
                    if required not in seen:
                        seen.add(required)
                        ancestors(required, seen)
                return seen

            for node in nodes.values():
                lineage = {nodes[a]["branch"] for a in ancestors(node["id"])}
                if node["branch"] in ("legal", "core"):
                    self.assertNotIn("underground", lineage, f"{node['id']} is a {node['branch']} skill that needs a crime")
                if node["branch"] == "crossover":
                    parents = {nodes[r]["branch"] for r in node["requires"]}
                    self.assertGreaterEqual(len(node["requires"]), 2, node["id"])
                    self.assertIn("legal", parents, f"{node['id']} needs a legal skill")
                    self.assertTrue("underground" in parents or "underground" in lineage, f"{node['id']} needs an underground skill")
            self.assertTrue(any(n["branch"] == "crossover" for n in nodes.values()), f"{career} has no crossover skill")
            self.assertGreaterEqual(sum(1 for n in nodes.values() if n["requires"] and len(n["requires"]) >= 2), 3, f"{career}: the tree should have several multi-prerequisite skills")

    def test_percentage_effects_have_one_consumer_each_and_stay_inside_their_ceilings(self):
        # the whole-tree ceilings are SkillNode.MAX_*; the per-node ceilings are SkillRules.validateTree
        ceilings = {"legal_payout_percent": 12, "underground_payout_percent": 12, "yield_percent": 40, "upgrade_discount_percent": 20}
        per_node = {"legal_payout_percent": 6, "underground_payout_percent": 6, "yield_percent": 30, "upgrade_discount_percent": 15}
        for career, tree in self.trees.items():
            for key, ceiling in ceilings.items():
                self.assertLessEqual(sum(n["effects"].get(key, 0) for n in tree["nodes"]), ceiling, f"{career} {key}")
                for n in tree["nodes"]:
                    self.assertLessEqual(n["effects"].get(key, 0), per_node[key], f"{n['id']} {key}")
            unknown = {k for n in tree["nodes"] for k in n["effects"]} - {"licences", "inspection_modifier", "exchange_discount", *ceilings}
            self.assertFalse(unknown, f"{career}: effects nothing consumes: {unknown}")
        # the yield effect has exactly one consumer, the Sable Leaf plant, so only the Farmer's tree may carry it
        for career, tree in self.trees.items():
            if career != "farmer":
                self.assertFalse(any(n["effects"].get("yield_percent") for n in tree["nodes"]), career)

    def test_a_skill_that_nothing_needs_does_something(self):
        # D-098: no dead leaves. A skill is allowed to be only a stepping stone (the foundation of a tree, the entry of an underground line) when
        # another skill builds on it; a skill nothing builds on must carry an effect, open a work order, or be one of the two skills Core code checks directly.
        orders = load("work_orders")
        gated = {o.get("requires_skill") for o in orders.values() if o.get("requires_skill")}
        checked_in_code = {"farmer.restricted_cultivation", "technician.bypass_tooling"}   # RestrictedCuttingItem, SecureTerminalService
        percent = ("legal_payout_percent", "underground_payout_percent", "yield_percent", "upgrade_discount_percent", "inspection_modifier", "exchange_discount", "licences")
        for career, tree in self.trees.items():
            needed = {r for n in tree["nodes"] for r in n["requires"]}
            for node in tree["nodes"]:
                if node["id"] in needed:
                    continue
                effective = any(node["effects"].get(k) for k in percent) or node["id"] in gated or node["id"] in checked_in_code
                self.assertTrue(effective, f"{node['id']} is a dead leaf: nothing needs it and it does nothing")
        # and the stepping stones are exactly the foundations plus the Vintner's still
        stones = sorted(n["id"] for tree in self.trees.values() for n in tree["nodes"]
                        if not any(n["effects"].get(k) for k in percent) and n["id"] not in gated and n["id"] not in checked_in_code)
        self.assertEqual(stones, sorted([f"{c}.fundamentals" for c in CAREERS] + ["vintner.still_craft"]))

    def test_the_utility_branch_is_more_than_a_discount(self):
        for career, tree in self.trees.items():
            utility = [n for n in tree["nodes"] if n["branch"] == "utility"]
            self.assertGreaterEqual(len(utility), 1, career)
        for career in ("vintner", "smith", "gunsmith", "technician"):
            self.assertTrue(any(n["branch"] == "utility" and n["effects"].get("upgrade_discount_percent") for n in self.trees[career]["nodes"]), f"{career}: no home-trade utility skill")

    def test_effects_stay_inside_the_design_caps(self):
        for career, tree in self.trees.items():
            concealment = sum(n["effects"].get("inspection_modifier", 0) for n in tree["nodes"])
            discount = sum(n["effects"].get("exchange_discount", 0) for n in tree["nodes"])
            self.assertGreaterEqual(concealment, -0.35, career)
            self.assertLessEqual(discount, 0.10 + 1e-9, career)
            for node in tree["nodes"]:
                self.assertGreaterEqual(node["effects"].get("inspection_modifier", 0), -0.25)


class LawTests(unittest.TestCase):
    def test_every_city_has_a_status_for_every_contraband_category(self):
        laws, contraband = load("city_laws"), load("contraband")
        self.assertEqual(set(laws), set(CITIES))
        for city, law in laws.items():
            self.assertEqual(set(law["categories"]), set(contraband), city)
            self.assertTrue(set(law["categories"].values()) <= {"legal", "permit", "prohibited"})
            ins = law["inspection"]
            self.assertTrue(0 <= ins["low"] <= ins["inspection"] <= ins["high"] <= ins["max"] <= 0.95, city)
            self.assertLessEqual(law["fines"]["permit"], law["fines"]["prohibited"])

    def test_cities_differ_in_the_way_the_design_says(self):
        laws = load("city_laws")
        self.assertGreater(laws["ironhaven"]["inspection"]["inspection"], laws["blackwater"]["inspection"]["inspection"])
        self.assertEqual(laws["blackwater"]["categories"]["restricted_cultivar"], "permit")
        self.assertEqual(laws["valewood"]["categories"]["restricted_cultivar"], "prohibited")
        self.assertLess(laws["blackwater"]["witness_chance"], laws["ironhaven"]["witness_chance"])

    def test_contraband_core_items_exist(self):
        items = {i["id"] for i in json.loads((ROOT / "rpg-core/src/main/resources/underground_city_items.json").read_text())["items"]}
        for name, category in load("contraband").items():
            self.assertEqual(category["category"], name)
            for entry in category["entries"]:
                if "tag" in entry:          # a tag entry names a datapack tag: its own file must exist
                    namespace, _, tag = entry["tag"].partition(":")
                    self.assertTrue((DATA / "tags/items" / f"{tag}.json").is_file(), f"{name}: tag {entry['tag']}")
                    continue
                namespace, _, path = entry["item"].partition(":")
                if namespace == "underground_city":
                    self.assertIn(path, items, f"{name}: {entry['item']}")

    def test_every_gun_id_used_by_a_contract_is_classified_consistently(self):
        contraband = load("contraband")
        licensed = {g for c in contraband.values() if c["category"] == "firearms_licensed" for e in c["entries"] for g in e.get("values", [])}
        for contract in content.load_contracts()["contracts"]:
            for req in contract["requirements"]:
                for gun in req.get("gun_ids", []):
                    if contract["legality"] == "legal":
                        self.assertIn(gun, licensed, contract["id"])
                    else:
                        self.assertNotIn(gun, licensed, contract["id"])


class ContractTests(unittest.TestCase):
    def test_contract_generation_validates_and_is_committed(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate_regional_content.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_every_career_has_legal_and_underground_contracts(self):
        contracts = content.load_contracts()["contracts"]
        for career in CAREERS:
            mine = [c for c in contracts if c["career"] == career]
            self.assertTrue(any(c["legality"] == "legal" for c in mine), career)
            self.assertTrue(any(c["legality"] == "underground" for c in mine), career)

    def test_underground_work_always_has_a_buyer_a_skill_and_a_trust_ladder(self):
        contracts = content.load_contracts()["contracts"]
        buyers = load("buyers")
        for c in contracts:
            if c["legality"] != "underground":
                self.assertNotIn("min_trust", c)
                self.assertNotIn("trust_gain", c)
                continue
            self.assertIn(c["buyer"], buyers)
            self.assertEqual(buyers[c["buyer"]]["legality"], "underground")
        # Every underground buyer is reachable: its first contract needs no trust.
        for buyer in {c["buyer"] for c in contracts if c["legality"] == "underground"}:
            theirs = [c for c in contracts if c.get("buyer") == buyer]
            self.assertTrue(any(c.get("min_trust", 0) == 0 for c in theirs), buyer)
            if any(c.get("min_trust", 0) >= 2 for c in theirs):
                self.assertGreaterEqual(sum(1 for c in theirs if c.get("trust_gain", 0) >= 1 and c.get("min_trust", 0) < 2), 1, buyer)

    def test_rewards_are_valued_and_bounded(self):
        contracts = content.load_contracts()
        for c in contracts["contracts"]:
            value = sum(contracts["reference_values"]["rewards"][r["item"]] * r["count"] for r in c.get("rewards", []))
            self.assertLessEqual(value, 0.9 * c["credits"], c["id"])

    def test_buyers_have_fixed_cities(self):
        for name, buyer in load("buyers").items():
            self.assertIn(buyer["city"], CITIES, name)
            self.assertIn(buyer["legality"], ("legal", "underground"))


class BalanceTests(unittest.TestCase):
    def test_underground_work_is_risk_adjusted_against_legal_work(self):
        rows = balance.evaluate()
        self.assertGreaterEqual(len(rows), 8)
        self.assertEqual(balance.violations(rows), [])

    def test_unprepared_smuggling_loses_to_legal_work_and_preparation_helps(self):
        for row in balance.evaluate():
            self.assertLess(row["careless"], row["legal_baseline"], row["id"])
            self.assertLess(row["hot"], row["careless"], row["id"])
            self.assertGreater(row["trained"], row["careless"], row["id"])

    def test_inspection_arithmetic_matches_the_core_implementation(self):
        law = load("city_laws")["valewood"]
        self.assertAlmostEqual(balance.inspection_chance(law, "inspection", 0, 0), 0.22)
        self.assertAlmostEqual(balance.inspection_chance(law, "inspection", 3, 0), 0.37)
        self.assertAlmostEqual(balance.inspection_chance(law, "inspection", 0, -0.12), 0.10)
        self.assertEqual(balance.inspection_chance(law, "low", 0, -0.35), 0.02)
        self.assertEqual(balance.inspection_chance(law, "high", 30, 0), law["inspection"]["max"])


class DevPackTests(unittest.TestCase):
    def setUp(self):
        self.pack = json.loads((ROOT / "content/modpacks/dev-pack.json").read_text(encoding="utf-8"))

    def test_every_pin_is_exact_and_verifiable(self):
        self.assertEqual(self.pack["minecraft"], "1.20.1")
        ids = [m["id"] for m in self.pack["mods"]]
        self.assertEqual(len(ids), len(set(ids)))
        for mod in self.pack["mods"]:
            self.assertRegex(mod["sha512"], r"^[0-9a-f]{128}$")
            self.assertRegex(mod["sha1"], r"^[0-9a-f]{40}$")
            self.assertGreater(mod["size"], 1000)
            self.assertTrue(mod["version_id"] and mod["project_id"] and mod["filename"].endswith(".jar"))
            for dependency in mod["requires"]:
                self.assertIn(dependency, ids)

    def test_no_third_party_binary_is_committed(self):
        tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True).stdout.split("\n")
        binaries = [p for p in tracked if p.endswith((".jar", ".zip", ".mrpack"))]
        # The only committed binary is the official Gradle wrapper jar, accepted by the validator only at its pinned hash.
        wrapper = "gradle/wrapper/gradle-wrapper.jar"
        if wrapper in binaries:
            digest = hashlib.sha256((ROOT / wrapper).read_bytes()).hexdigest()
            self.assertEqual(digest, "7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172")
            binaries.remove(wrapper)
        self.assertEqual(binaries, [])

    def test_the_policy_says_metadata_only(self):
        self.assertIn("never committed", self.pack["policy"])

    def test_q_shop_remains_the_only_wallet(self):
        wallets = [m for m in self.pack["external"] if "wallet" in m["role"]]
        self.assertEqual([m["id"] for m in wallets], ["qshop"])


class GuiCoverageTests(unittest.TestCase):
    def test_every_capture_case_is_registered_and_every_registration_is_captured(self):
        source = (ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/VisualCaptureHarness.java").read_text(encoding="utf-8")
        names = set(re.findall(r'"(uc-[a-z0-9_-]+)"', source))
        screen = (ROOT / "rpg-core/src/main/java/io/github/stoffe101/undergroundcity/TerminalScreen.java").read_text(encoding="utf-8")
        enum = screen[screen.index("public enum Page"):]
        pages = re.findall(r'\b([A-Z_]+)\("', enum[:enum.index(";")])
        names |= {"uc-terminal-" + p.lower() for p in pages}
        names.discard("uc-terminal-")
        # the Crew screen's poses are generated by a loop over a list of fixture names; read the list, as the terminal's pages are read above
        loop = re.search(r'for\(String pose:new String\[\]\{([^}]*)\}\)\s*\{\s*cases\.add\(Case\.crew\(', source)
        self.assertIsNotNone(loop, "the Crew poses loop was not found in the capture harness")
        names |= {"uc-crew-" + pose for pose in re.findall(r'"([a-z-]+)"', loop.group(1))}
        names.discard("uc-crew-")
        manifest = json.loads((ROOT / "content/qa/gui-coverage.json").read_text(encoding="utf-8"))
        expected = {s["expected_file"][:-4] for s in manifest["integrated_surfaces"]}
        self.assertEqual(names, expected)


class LayoutTests(unittest.TestCase):
    def test_blueprints_match_the_triangle_plan_and_every_pair_has_a_walkable_road(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/plan_triangle_layout.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout)
        corridors = json.loads((ROOT / "content/region-blueprints/corridors.json").read_text())
        built = set(corridors["road_generation"]["build_route_kinds"])
        self.assertEqual(len([r for r in corridors["routes"] if r["kind"] in built]), 3)
        for route in corridors["routes"]:
            length = sum(((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2) ** 0.5 for a, b in zip(route["waypoints"], route["waypoints"][1:]))
            minutes = length / corridors["transport"]["routine_travel_budget"]["estimated_walking_blocks_per_second"] / 60
            self.assertLessEqual(minutes, 5.0, f"{route['id']} is {minutes:.1f} minutes between city edges")
            self.assertGreaterEqual(minutes, 3.0, f"{route['id']} is {minutes:.1f} minutes between city edges")
            self.assertEqual(len(route["checkpoints"]), 2)


class DistrictTests(unittest.TestCase):
    def test_committed_district_matches_its_generator(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate_district.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_district_is_bounded_clear_of_hardcoded_coordinates_and_names_real_things(self):
        for path in (DATA / "districts").glob("*.json"):
            district = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(district["id"], path.stem)
            self.assertLessEqual(len(district["ops"]), 1000)
            xs, ys, zs = [], [], []
            safes = terminals = clerks = secure = 0
            citizens = []
            for op in district["ops"]:
                points = [op[k] for k in ("from", "to", "at", "terminal") if k in op]
                for x, y, z in points:
                    xs.append(x); ys.append(y); zs.append(z)
                if op["op"] == "safe":
                    safes += 1
                    self.assertTrue((DATA / "property_policies" / f"{op['policy']}.json").exists(), op["policy"])
                if op["op"] in ("fill", "block", "sign"):
                    # Vanilla blocks only, except an operation that names its mod: it applies only where that mod is installed.
                    namespace = op.get("requires_mod", "minecraft")
                    self.assertRegex(op["block"], rf"^{namespace}:[a-z_]+(\[[a-z_=0-9,]+\])?$")
                terminals += op["op"] == "terminal"; clerks += op["op"] == "clerk"; secure += op["op"] == "secure_terminal"
                if op["op"] == "npc":
                    citizens.append(op)
            volume = (max(xs) - min(xs) + 1) * (max(ys) - min(ys) + 1) * (max(zs) - min(zs) + 1)
            self.assertLessEqual(volume, 64000)
            # Offsets are relative to the operator's origin: the lot starts at the origin, never at a world coordinate.
            self.assertEqual((min(xs), min(zs)), (0, 0))
            self.assertLess(max(xs), 64)
            self.assertLessEqual(clerks, 1)
            if district["id"] == "founders_row":
                self.assertGreaterEqual(terminals, 1)
                self.assertGreaterEqual(safes, 1)
                self.assertGreaterEqual(secure, 1)
            if district["id"] == "checkpoint_post":
                self.assertTrue(district.get("repeatable"), "a checkpoint post is built once per zone")
                self.assertEqual([npc["role"] for npc in citizens], ["guard"])
            if district["id"] in ("wayside_shelter", "wayside_well"):
                self.assertTrue(district.get("repeatable"), "a shelter is built once per stretch of road")
                self.assertEqual([npc["role"] for npc in citizens], ["warden"])
                self.assertLessEqual(max(xs), 10)
                self.assertLessEqual(max(zs), 8)
                # D-091 (owner direction in D-079): a shelter has exactly one provisions desk, an Operations Terminal that sells a few basics through the
                # exchange ledger; it still has no safe, no secured terminal and no clerk, and its orders, contacts and law are the cities' (the desk hides them)
                self.assertEqual(terminals, 1, "a shelter has one provisions desk")
                self.assertEqual(safes + secure + clerks, 0, "a shelter holds no safe, secured terminal or clerk: it is a place to rest, a person to ask and a small desk")
            # Citizens: bounded, named, with a role Core knows, short lines, and each employer bound to a real terminal of the lot.
            self.assertLessEqual(len(citizens), 20)
            terminal_spots = [op["at"] for op in district["ops"] if op["op"] == "terminal"]
            self.assertEqual({npc["role"] for npc in citizens} - {"registrar", "employer", "resident", "guard", "vendor", "instructor", "warden"}, set())
            self.assertEqual(len({npc["name"] for npc in citizens}), len(citizens), "citizen names must be unique")
            for npc in citizens:
                self.assertTrue(0 < len(npc["name"]) <= 40, npc["name"])
                self.assertLessEqual(len(npc.get("lines", [])), 8, npc["name"])
                for line in npc.get("lines", []):
                    self.assertTrue(0 < len(line) <= 180, f"{npc['name']}: {len(line)} characters")
                if npc["role"] == "employer":
                    self.assertIn(npc["career"], ("farmer", "vintner", "smith", "gunsmith", "technician"))
                    self.assertIn(npc["terminal"], terminal_spots, npc["name"] + " needs a real terminal in the lot")
            careers = {npc["career"] for npc in citizens if npc["role"] == "employer"}
            if district["id"] == "founders_row":
                self.assertEqual(careers, {"farmer", "vintner", "smith", "gunsmith", "technician"}, "every career has an employer in Founders Row")


if __name__ == "__main__":
    unittest.main()
