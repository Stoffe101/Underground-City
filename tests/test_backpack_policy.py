"""The Sophisticated Backpacks policy (D-092/D-093): one file says which tiers and upgrades exist and how big they are, the configuration is generated from it, and
nothing the shops sell or the tests rely on contradicts it."""
import copy
import json
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import generate_backpack_config as gen  # noqa: E402

POLICY = gen.load()


class BackpackPolicy(unittest.TestCase):
    def test_policy_is_valid(self):
        self.assertEqual(gen.problems(POLICY), [])

    def test_generated_files_are_current(self):
        result = subprocess.run([sys.executable, str(ROOT / "scripts/generate_backpack_config.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_every_rule_it_enforces_fires_on_a_broken_policy(self):
        def broken(change):
            copy_ = copy.deepcopy(POLICY)
            change(copy_)
            return gen.problems(copy_)
        self.assertTrue(broken(lambda p: p["tiers"]["netheriteBackpack"].update(inventorySlotCount=200)))                       # bigger than the mod's own default
        self.assertTrue(broken(lambda p: p["server"]["entityBackpackAdditions"].update(chance=0.1)))                          # mobs wearing backpacks
        self.assertTrue(broken(lambda p: p["server"].update(allowOpeningOtherPlayerBackpacks=True)))
        self.assertTrue(broken(lambda p: p["server"].update(itemFluidHandlerEnabled=True)))
        self.assertTrue(broken(lambda p: p["server"].update(tickDedupeLogicDisabled=True)))
        self.assertTrue(broken(lambda p: p["common"].update(chestLootEnabled=True)))
        self.assertTrue(broken(lambda p: p["items"]["enabled"].append({"item": "create:backpack_like", "why": "x"})))              # Create is prohibited
        self.assertTrue(broken(lambda p: p["items"]["disabled"].append(dict(p["items"]["enabled"][0]))))                        # enabled and disabled at once
        self.assertTrue(broken(lambda p: p["items"]["enabled"][1].pop("why")))
        self.assertTrue(broken(lambda p: p["tiers"]["copperBackpack"].update(inventorySlotCount=27)))                         # a tier that does not grow

    def test_tiers_grow_and_stay_modest(self):
        tiers = [POLICY["tiers"][name] for name in gen.MOD_DEFAULTS]
        self.assertEqual(sorted(t["inventorySlotCount"] for t in tiers), [t["inventorySlotCount"] for t in tiers])
        self.assertEqual(tiers[0]["inventorySlotCount"], 27)    # a leather pack is not bigger than a vanilla chest
        self.assertLessEqual(tiers[-1]["inventorySlotCount"], 90)
        self.assertLessEqual(tiers[-1]["upgradeSlotCount"], 4)

    def test_strong_upgrades_are_off_and_basic_ones_on(self):
        enabled = {e["item"] for e in POLICY["items"]["enabled"]}
        disabled = {e["item"] for e in POLICY["items"]["disabled"]}
        for upgrade in ("pickup_upgrade", "filter_upgrade", "magnet_upgrade", "feeding_upgrade", "restock_upgrade", "crafting_upgrade", "deposit_upgrade"):
            self.assertIn(f"sophisticatedbackpacks:{upgrade}", enabled)
        # nothing that makes goods out of nothing or deletes the scarcity this project is built on
        for upgrade in ("infinity_upgrade", "everlasting_upgrade", "inception_upgrade", "void_upgrade", "advanced_void_upgrade", "xp_pump_upgrade", "pump_upgrade",
                        "auto_smelting_upgrade", "auto_blasting_upgrade", "auto_smoking_upgrade", "advanced_magnet_upgrade", "stack_upgrade_omega_tier"):
            self.assertIn(f"sophisticatedbackpacks:{upgrade}", disabled, upgrade)

    def test_every_disabled_item_says_why_and_no_item_is_in_both_lists(self):
        for status in ("enabled", "disabled"):
            for entry in POLICY["items"][status]:
                self.assertGreater(len(entry["why"]), 15, entry["item"])
        self.assertFalse({e["item"] for e in POLICY["items"]["enabled"]} & {e["item"] for e in POLICY["items"]["disabled"]})

    def test_what_the_shops_sell_is_enabled_by_the_policy(self):
        enabled = {e["item"] for e in POLICY["items"]["enabled"]}
        supply = json.loads((ROOT / "content/gameplay/survival-supply.json").read_text(encoding="utf-8"))
        for line in supply["lines"]:
            if line["item"].startswith(POLICY["provider"]["backpacks"] + ":"):
                self.assertIn(line["item"], enabled, line["id"])

    def test_the_server_defaults_name_every_tier(self):
        text = gen.server_toml(POLICY)
        for name in gen.MOD_DEFAULTS:
            self.assertIn(f"[server.{name}]", text)
        self.assertIn("allowOpeningOtherPlayerBackpacks = false", text)
        self.assertIn("chance = 0.0", text)

    def test_no_trace_of_the_retired_provider_in_the_selected_survival_set(self):
        manifest = json.loads((ROOT / "content/modpacks/dev-pack.json").read_text(encoding="utf-8"))
        by_id = {m["id"]: m for m in manifest["mods"]}
        self.assertIn("survival", by_id["sophisticated_backpacks"]["sets"])
        self.assertIn("survival", by_id["sophisticated_core"]["sets"])
        self.assertEqual(["legacy_travelers"], by_id["travelers_backpack"]["sets"])
        self.assertNotIn("create", "".join(by_id).lower())


if __name__ == "__main__":
    unittest.main()
