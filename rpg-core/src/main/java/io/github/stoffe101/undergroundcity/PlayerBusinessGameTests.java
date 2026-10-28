package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Player business v1 (D-208) on the dedicated test server with a scratch work-order journal: a counter opens a business only in trade premises its holder works in; it takes plain goods a
 * city's shop sells, at the city's lowest price; customers buy at most twelve an hour of play, each sale paying the city its tax before the till; the till is paid into Q Shop's wallet once,
 * survives a restart, and is never anybody's who may not run the business; and the city's people come only to an open, stocked counter in good standing and in a shop's hours.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PlayerBusinessGameTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    private static ItemStack wine(int n) { return new ItemStack(UndergroundCity.item("berry_wine"), n); }

    private static int wineIn(ServerPlayer p) { return p.getInventory().countItem(UndergroundCity.item("berry_wine")); }

    private static int stockOf(WorkOrderLedger orders, String id) {
        var stock = orders.business(id).getCompound("stock");
        return stock.getAllKeys().stream().mapToInt(stock::getInt).sum();
    }

    /** A holder's trade premises (slot 0) and flat (slot 1), with a counter set down in each. */
    private static Residence[] premises(PropertyGameTests.Fix f, ServerPlayer owner) {
        Residence shop = f.home("t:biz", 0), flat = f.home("t:bizflat", 1);
        for (Residence r : List.of(shop, flat)) { r.ownerType = OwnerType.PLAYER; r.owner = owner.getUUID(); r.tenure = Tenure.OWN; }
        return new Residence[] {shop, flat};
    }

    private static BlockPos counter(PropertyGameTests.Fix f, ServerLevel level, int slot, int x, int z) {
        BlockPos at = f.in(slot, x, 1, z);
        level.setBlock(at, UndergroundCity.BUSINESS_COUNTER.get().defaultBlockState(), 3);
        return at;
    }

    @GameTest(template = "empty", timeoutTicks = 300, batch = "uc_player_business")
    public static void aCounterSellsWhatItsHolderStockedAtTheCitysPriceAndPaysTheTillOnceAfterTax(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        try (PropertyGameTests.Fix f = new PropertyGameTests.Fix(helper, Map.of("t:biz", BusinessGameTests.tradeTerms("t:biz"), "t:bizflat", PropertyGameTests.flatTerms("t:bizflat")));
             BusinessGameTests.Orders o = new BusinessGameTests.Orders(helper, "ironhaven")) {
            ServerLevel level = helper.getLevel();
            ServerPlayer owner = f.person(helper, "uc_pb_owner", "vintner", 0), stranger = f.person(helper, "uc_pb_other", "vintner", 0);
            Residence shop = premises(f, owner)[0];
            BlockPos inShop = counter(f, level, 0, 2, 2), inFlat = counter(f, level, 1, 2, 2);
            // only in trade premises, and only for somebody who may work there
            check(PlayerBusiness.placed(owner, o.ledger, inFlat) == null, "a counter in a flat opened a business");
            check(PlayerBusiness.placed(stranger, o.ledger, inShop) == null, "somebody who may not work in the premises opened a business");
            String id = PlayerBusiness.placed(owner, o.ledger, inShop);
            check(shop.id.equals(id) && id.equals(PlayerBusiness.businessAt(o.ledger, inShop)), "the holder's counter opened the premises' business: " + id);
            check(PlayerBusiness.placed(owner, o.ledger, counter(f, level, 0, 3, 3)) == null, "a second counter in the same premises opened another business");
            // what it takes: plain goods a city's shop sells
            check(!PlayerBusiness.sellable(new ItemStack(Items.DIRT, 4)), "the counter takes goods no shop sells");
            check(!PlayerBusiness.sellable(new ItemStack(UndergroundCity.item("pump_assembly"), 1)), "the counter takes a works' pump assembly, dearer than a street person buys");
            check(PlayerBusiness.TAX > SkillNode.MAX_TOTAL_DISCOUNT, "the sales tax does not exceed every discount a buyer can hold");
            ItemStack marked = wine(1);
            marked.getOrCreateTag().putString("uc_stolen_from", "somewhere");
            check(!PlayerBusiness.sellable(marked), "the counter takes marked goods");
            String key = PlayerBusiness.key(wine(1));
            long cheapest = RegionalCatalog.INSTANCE.all().stream().filter(l -> l.item().equals(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(UndergroundCity.item("berry_wine"))))
                .mapToLong(RegionalStock::unitPrice).min().orElse(-1);
            check(PlayerBusiness.price(key) == cheapest && cheapest > 0, "a bottle sells at the city's lowest price: " + PlayerBusiness.price(key) + " against " + cheapest);
            // stocked from the inventory, once (a prepared settlement)
            owner.getInventory().add(wine(20));
            check(PlayerBusiness.stock(owner, o.ledger, id, key, 20) == 20 && wineIn(owner) == 0 && stockOf(o.ledger, id) == 20, "twenty bottles moved onto the counter");
            check(!o.ledger.player(owner.getUUID()).contains("pending"), "the stock settlement was not retired");
            // the city's people buy at most twelve an hour of play, each paying its tax to the city before the till
            long gross = PlayerBusiness.price(key), tax = PlayerBusiness.tax(gross);
            check(tax > 0 && tax < gross, "the sales tax of a bottle is " + tax);
            for (int i = 0; i < PlayerBusiness.SALES_PER_HOUR; i++) check(PlayerBusiness.sell(o.ledger, id, 0), "sale " + (i + 1) + " was refused");
            check(!PlayerBusiness.sell(o.ledger, id, 0), "a thirteenth sale in the same hour of play");
            check(o.ledger.business(id).getLong("takings") == PlayerBusiness.SALES_PER_HOUR * (gross - tax) && o.ledger.business(id).getLong("tax") == PlayerBusiness.SALES_PER_HOUR * tax,
                "the till holds the sales less their tax: " + o.ledger.business(id));
            check(stockOf(o.ledger, id) == 20 - PlayerBusiness.SALES_PER_HOUR, "each sale took one bottle");
            o.ledger.advance(72_000);
            check(PlayerBusiness.sell(o.ledger, id, 0), "the next hour of play sells again");
            long till = o.ledger.business(id).getLong("takings");
            // a restart keeps the stock and the till
            o.restart();
            check(o.ledger.business(id).getLong("takings") == till && stockOf(o.ledger, id) == 20 - PlayerBusiness.SALES_PER_HOUR - 1, "a restart lost the stock or the till");
            // the till is the business's: never the stranger's, and the holder's once
            check(!PlayerBusiness.mayRun(level, o.ledger.business(id), stranger.getUUID()), "a stranger may run the business");
            QShopEconomy.applyBalance(owner, 0);
            check(PlayerBusiness.collect(owner, o.ledger, id) == till && QShopEconomy.balance(owner) == till, "the takings did not reach the wallet: " + QShopEconomy.balance(owner));
            check(PlayerBusiness.collect(owner, o.ledger, id) == 0 && QShopEconomy.balance(owner) == till, "the takings were paid twice");
            check(o.ledger.business(id).getInt("sold") == 0 && o.ledger.business(id).getLong("paid_total") == till, "the statement did not start again after collecting");
            // the rest of the stock comes back, and a counter taken away closes the business but keeps its records
            int left = stockOf(o.ledger, id);
            check(PlayerBusiness.withdraw(owner, o.ledger, id) == left && wineIn(owner) == left && stockOf(o.ledger, id) == 0, "the stock did not come back");
            PlayerBusiness.removed(o.ledger, inShop);
            check(o.ledger.hasBusiness(id) && !o.ledger.business(id).getBoolean("placed") && PlayerBusiness.businessAt(o.ledger, inShop) == null, "a removed counter left the business open or lost it");
            check(shop.id.equals(PlayerBusiness.placed(owner, o.ledger, inShop)) && o.ledger.business(id).getLong("paid_total") == till, "a counter set down again found the same business");
            for (BlockPos p : List.of(inShop, inFlat, f.in(0, 3, 1, 3))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 300, batch = "uc_player_business")
    public static void theCitysPeopleComeOnlyToAnOpenStockedCounterInGoodStandingAndInAShopsHours(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        long time = level.getDayTime();
        try (PropertyGameTests.Fix f = new PropertyGameTests.Fix(helper, Map.of("t:biz", BusinessGameTests.tradeTerms("t:biz"), "t:bizflat", PropertyGameTests.flatTerms("t:bizflat")));
             BusinessGameTests.Orders o = new BusinessGameTests.Orders(helper, "ironhaven")) {
            ServerPlayer owner = f.person(helper, "uc_pb_open", "vintner", 0);
            Residence shop = premises(f, owner)[0];
            BlockPos at = counter(f, level, 0, 2, 2);
            String id = PlayerBusiness.placed(owner, o.ledger, at);
            check(id != null, "the counter did not open a business");
            level.setDayTime(6000);   // noon
            check(!PlayerBusiness.open(level, o.ledger.business(id)), "a counter nobody opened is open");
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            PlayerBusiness.use(owner, o.ledger, at, true);   // sneak and use: open
            check(o.ledger.business(id).getBoolean("open") && !PlayerBusiness.open(level, o.ledger.business(id)), "an empty counter is open to customers");
            owner.setItemInHand(InteractionHand.MAIN_HAND, wine(6));
            PlayerBusiness.use(owner, o.ledger, at, false);   // use with goods: stock
            check(stockOf(o.ledger, id) == 6 && wineIn(owner) == 0, "using the counter with goods did not stock it");
            check(PlayerBusiness.open(level, o.ledger.business(id)), "an opened, stocked counter in good standing at noon is shut");
            PlayerBusiness.Target t = PlayerBusiness.nearestOpen(level, o.ledger, at.offset(12, 0, 0));
            check(t != null && t.id().equals(id) && t.front().distManhattan(at) == 1, "a street person nearby does not find the counter");
            check(PlayerBusiness.nearestOpen(level, o.ledger, at.offset(PlayerBusiness.REACH + 8, 0, 0)) == null, "a counter out of reach drew a customer");
            // a customer buys only at the counter
            check(!PlayerBusiness.customerBuys(level, o.ledger, id, at.offset(10, 0, 0)), "a customer bought from across the street");
            check(PlayerBusiness.customerBuys(level, o.ledger, id, t.front()) && stockOf(o.ledger, id) == 5 && o.ledger.business(id).getLong("takings") > 0, "a customer at the counter did not buy");
            // a customer asks first for what the city's own shops have run short of (D-201): the pub out of cider, the counter's cider goes before its wine
            owner.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UndergroundCity.item("apple_cider"), 5));
            PlayerBusiness.use(owner, o.ledger, at, false);
            check(stockOf(o.ledger, id) == 10, "the counter did not take the cider");
            RegionalStock pubWine = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":shops/pub_wine_ironhaven"), pubCider = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":shops/pub_cider_ironhaven");
            check(pubWine != null && pubCider != null, "the pub's wine and cider lines did not load");
            o.ledger.setRegionalUnitsForTest(pubWine, pubWine.capacity());
            o.ledger.setRegionalUnitsForTest(pubCider, 0);
            List<String> wanted = PlayerBusiness.wanted(o.ledger, "ironhaven", o.ledger.business(id).getCompound("stock"));
            check(wanted.size() == 1 && wanted.get(0).startsWith(UndergroundCity.MOD_ID + ":apple_cider|"), "the short cider is not what Ironhaven's customers ask for: " + wanted);
            check(PlayerBusiness.wanted(o.ledger, "valewood", o.ledger.business(id).getCompound("stock")).isEmpty(), "Ironhaven's shortage drew Valewood's customers");
            o.ledger.business(id).putString("city", "ironhaven");   // the test's structure stands in no city
            for (int i = 0; i < 2; i++) check(PlayerBusiness.customerBuys(level, o.ledger, id, t.front()), "a customer at the counter did not buy");
            int wineLeft = o.ledger.business(id).getCompound("stock").getAllKeys().stream().filter(k -> k.startsWith(UndergroundCity.MOD_ID + ":berry_wine|")).mapToInt(k -> o.ledger.business(id).getCompound("stock").getInt(k)).sum();
            check(stockOf(o.ledger, id) == 8 && wineLeft == 5, "customers did not buy the short cider first (wine left " + wineLeft + ", stock " + stockOf(o.ledger, id) + ")");
            o.ledger.setRegionalUnitsForTest(pubCider, pubCider.capacity());
            check(PlayerBusiness.wanted(o.ledger, "ironhaven", o.ledger.business(id).getCompound("stock")).isEmpty(), "a refilled shelf still sends its customers to the counter first");
            // shut: at night, while the rent is owed, and when the founder no longer works there
            level.setDayTime(18000);   // midnight
            check(!PlayerBusiness.open(level, o.ledger.business(id)) && PlayerBusiness.nearestOpen(level, o.ledger, at) == null, "a counter is open at midnight");
            level.setDayTime(6000);
            shop.tenure = Tenure.RENT; shop.charge = 135; shop.arrears = 99;
            check(!PlayerBusiness.open(level, o.ledger.business(id)) && PlayerBusiness.whyShut(level, o.ledger.business(id)).contains("good standing"), "premises in arrears still sell");
            check(!PlayerBusiness.customerBuys(level, o.ledger, id, t.front()), "a customer bought from premises in arrears");
            shop.tenure = Tenure.OWN; shop.arrears = 0;
            check(PlayerBusiness.open(level, o.ledger.business(id)), "the counter did not open again when the premises were paid");
            // closing it (sneak and use) stops the customers
            owner.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            PlayerBusiness.use(owner, o.ledger, at, true);
            check(!PlayerBusiness.open(level, o.ledger.business(id)) && PlayerBusiness.nearestOpen(level, o.ledger, at) == null, "a closed counter drew a customer");
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        } finally {
            level.setDayTime(time);
        }
        helper.succeed();
    }
}
