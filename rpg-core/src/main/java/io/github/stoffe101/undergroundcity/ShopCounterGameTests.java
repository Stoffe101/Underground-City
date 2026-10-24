package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Shopkeepers who sell (D-126): the shipped shops and their lines, a counter that is an outlet of its city's finite exchange stock, a purchase there through the same journalled purchase, and a
 * counter that exists only while its shopkeeper stands at it (no keeper, no shop; orders are never taken at a counter).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ShopCounterGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @GameTest(template = "empty")
    public static void everyShopSellsItsOwnCitysLinesAndNeverUndercutsTheExchange(GameTestHelper helper) {
        check(ShopCounters.INSTANCE.all().size() >= 4, "the four shops are not loaded: " + ShopCounters.INSTANCE.all().keySet());
        for (ShopCounters.Shop shop : ShopCounters.INSTANCE.all().values()) {
            List<RegionalStock> lines = RegionalCatalog.INSTANCE.forOutlet(shop.city(), shop.outlet());
            check(!lines.isEmpty(), shop.outlet() + " sells nothing");
            check(lines.size() <= TerminalData.MAX_OFFERS, shop.outlet() + " has more lines than a counter shows");
            for (RegionalStock line : lines) {
                check(line.city().equals(shop.city()), line.id() + " is another city's line");
                int cheapestCity = RegionalCatalog.INSTANCE.all().stream().filter(s -> s.item().equals(line.item()) && s.soldAt(RegionalStock.CITY)).mapToInt(RegionalStock::unitPrice).min().orElse(Integer.MAX_VALUE);
                check(line.soldAt(RegionalStock.CITY) || cheapestCity == Integer.MAX_VALUE || line.unitPrice() >= cheapestCity, line.id() + " undercuts the exchange's " + cheapestCity);
                check(line.capacity() * line.unitPrice() <= 1200, line.id() + " is a stockpile");
            }
        }
        // the hardware counter and the exchange share Ironhaven's iron shelf: one shelf, two counters
        RegionalStock iron = RegionalCatalog.INSTANCE.find("underground_city:materials/iron_ingot_ironhaven");
        check(iron != null && iron.soldAt(RegionalStock.CITY) && iron.soldAt("shop:hardware"), "Ironhaven's iron is not sold at both the exchange and the hardware counter");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCounterSellsOnlyWhileItsShopkeeperIsThere(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
        WorkOrderLedger ledger = new WorkOrderLedger(path);
        BlockPos counter = helper.absolutePos(new BlockPos(1, 2, 1));
        TownspersonEntity keeper = UndergroundCity.TOWNSPERSON.get().create(helper.getLevel());
        try {
            ShopCounters.Shop general = ShopCounters.INSTANCE.all().values().stream().filter(s -> s.outlet().equals("shop:general")).findFirst().orElseThrow();
            keeper.moveTo(counter.getX() + 0.5, counter.getY(), counter.getZ() + 0.5);
            helper.getLevel().addFreshEntity(keeper);
            ShopCounters.openForTest(counter, general, keeper.getUUID(), helper.getLevel().getServer().getTickCount() + ShopCounters.OPEN_TICKS);
            var exchange = Exchanges.at(helper.getLevel(), counter).orElseThrow();
            check(exchange.counter() && exchange.city().equals("valewood") && exchange.outlet().equals("shop:general") && exchange.title().equals(general.title()), "the counter is not the general store's outlet: " + exchange);
            check(!exchange.roadside() && exchange.seller().equals(general.title() + " in Valewood"), "a purchase at a city's shop names " + exchange.seller());
            check(new Exchanges.Exchange("valewood", RegionalStock.CITY).seller().equals("the Valewood exchange") && new Exchanges.Exchange("valewood", RegionalStock.WAYSIDE).seller().equals("the Valewood wayside desk"),
                "an exchange and a desk are named by their city, capitalised");
            check(exchange.shopOnly(), "a counter must be only a shop");
            FakePlayer buyer = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_shop_buyer"));
            buyer.getInventory().clearContent();
            buyer.moveTo(counter.getX() + 2.5, counter.getY(), counter.getZ() + 0.5);
            CitizenState.get(buyer.server).register(buyer.getUUID(), "ironhaven", "smith");
            QShopEconomy.applyBalance(buyer, 50);
            check(ShopCounters.authorized(buyer, counter), "a registered buyer beside an open counter is not served");
            RegionalStock planks = RegionalCatalog.INSTANCE.find("underground_city:shops/store_planks_valewood");
            check(planks != null && planks.soldAt("shop:general"), "the planks line is missing");
            int shelf = ledger.regionalUnits(planks);
            RegionalMarket.purchase(buyer, counter, ledger, CitizenState.get(buyer.server).find(buyer.getUUID()).orElseThrow(), planks.id(), 8, UUID.randomUUID());
            check(buyer.getInventory().countItem(Items.OAK_PLANKS) == 8 && QShopEconomy.balance(buyer) == 50 - 8 * planks.unitPrice(), "the counter purchase did not deliver and charge exactly once");
            check(ledger.regionalUnits(planks) == shelf - 8, "the counter did not draw on the shared shelf");
            // the exchange's own lines are not sold at the general store
            RegionalStock charcoal = RegionalCatalog.INSTANCE.find("underground_city:materials/charcoal_valewood");
            boolean refused = false;
            try { RegionalMarket.purchase(buyer, counter, ledger, CitizenState.get(buyer.server).find(buyer.getUUID()).orElseThrow(), charcoal.id(), 1, UUID.randomUUID()); }
            catch (IllegalStateException expected) { refused = true; }
            check(refused, "a counter sold a line of another outlet");
            // the shopkeeper leaves: the counter is gone, and so is the shop
            keeper.discard();
            check(Exchanges.at(helper.getLevel(), counter).map(e -> !e.counter()).orElse(true), "the counter outlived its shopkeeper");
            check(!ShopCounters.authorized(buyer, counter), "a counter with nobody behind it still sells");
        } finally {
            ShopCounters.clearForTest();
            if (keeper.isAlive()) keeper.discard();
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }
}
