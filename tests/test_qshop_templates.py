import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from install_qshop_templates import TEMPLATE, install, verify_templates


class QShopTemplateTests(unittest.TestCase):
    def test_vanilla_only_starter_shop_and_no_arbitrage(self):
        self.assertEqual(verify_templates(), [])
        configs = json.loads((TEMPLATE/"shops/uc_starter_supply.json").read_text())
        self.assertEqual(configs["id"], "uc_starter_supply")
        self.assertEqual(configs["currency"], "credits")
        self.assertTrue(any(e["type"] == "BUY" for e in configs["entries"]))
        self.assertTrue(any(e["type"] == "SELL" for e in configs["entries"]))

    def test_install_new_server_is_idempotent_and_does_not_copy_third_party_jar(self):
        with tempfile.TemporaryDirectory() as d:
            server = Path(d)
            one = install(server)
            self.assertEqual([x["operation"] for x in one["operations"]],
                             ["created", "created"])
            path = server/"config/qshop/shops/uc_starter_supply.json"
            before = path.read_bytes()
            two = install(server)
            self.assertEqual([x["operation"] for x in two["operations"]],
                             ["preserved", "preserved"])
            self.assertEqual(path.read_bytes(), before)
            self.assertEqual(list(server.rglob("*.jar")), [])

    def test_reject_existing_live_world_without_mutation(self):
        with tempfile.TemporaryDirectory() as d:
            server = Path(d)
            live = server/"world/serverconfig/qshop/shops"
            live.mkdir(parents=True)
            (live/"player_owned_shop.json").write_text("CUSTOM", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Existing world"):
                install(server)
            self.assertFalse((server/"config/qshop").exists())
            self.assertEqual((live/"player_owned_shop.json").read_text(), "CUSTOM")

    def test_unsupported_existing_currency_template_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            server = Path(d)
            existing = server/"config/qshop/currencies.json"
            existing.parent.mkdir(parents=True)
            existing.write_text('{"currencies":[{"id":"other"}]}', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "lacks 'credits'"):
                install(server)
            self.assertFalse((server/"config/qshop/shops/uc_starter_supply.json").exists())
            self.assertIn('"other"', existing.read_text())

    def test_invalid_prices_and_high_tier_loot_cannot_pass_template_check(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root/"shops").mkdir()
            (root/"currencies.json").write_bytes((TEMPLATE/"currencies.json").read_bytes())
            shop = json.loads((TEMPLATE/"shops/uc_starter_supply.json").read_text())
            shop["entries"].append({
                "type":"BUY", "item":{"item":"minecraft:diamond","count":1},
                "currency":"credits","price":1
            })
            (root/"shops/uc_starter_supply.json").write_text(json.dumps(shop))
            self.assertTrue(any("Advanced gear" in x for x in verify_templates(root)))


if __name__ == "__main__":
    unittest.main()
