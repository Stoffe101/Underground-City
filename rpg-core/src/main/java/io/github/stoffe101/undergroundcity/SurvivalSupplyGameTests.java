package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;
import static io.github.stoffe101.undergroundcity.CrewGameTests.rejected;

/**
 * The survival goods on sale (D-091): finite, shared, priced in Q Shop credits, sold at a city's exchange and at the provisions desks of the wayside shelters
 * that belong to it, left out of the game while their mod is not installed. These tests run in every pack; the lines that belong to the survival set are checked
 * only when that set is loaded.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurvivalSupplyGameTests {
    private SurvivalSupplyGameTests() {}

    private static final String SURVIVAL = "underground_city:survival/";

    private static boolean survivalLoaded() {
        return ModList.get().isLoaded("travelersbackpack") && ModList.get().isLoaded("thirstwastaken2") && ModList.get().isLoaded("farmersdelight") && ModList.get().isLoaded("comforts");
    }

    @GameTest(template = "empty")
    public static void survivalGoodsAreOnSaleOnlyWhileTheirModIsInstalled(GameTestHelper helper) {
        // vanilla goods of the wayside desks are always there
        for (String city : List.of("valewood", "ironhaven", "blackwater")) {
            RegionalStock bread = RegionalCatalog.INSTANCE.find(SURVIVAL + "wayside_bread_" + city);
            RegionalStock torch = RegionalCatalog.INSTANCE.find(SURVIVAL + "wayside_torch_" + city);
            check(bread != null && torch != null, city + "'s wayside desk should always sell bread and torches");
            check(bread.outlets().equals(Set.of("wayside")) && !bread.soldAt("city") && bread.unitPrice() == 7 && bread.requiresMod().isEmpty(), city + ": the wayside bread is sold at the desk only, at 7 credits");
        }
        // the third-party goods exist exactly when their mod does
        Map<String, String> needs = Map.of("waterskin_valewood", "thirstwastaken2", "waterskin_ironhaven", "thirstwastaken2", "waterskin_blackwater", "thirstwastaken2",
            "cooking_pot_valewood", "farmersdelight", "copper_canteen_ironhaven", "thirstwastaken2", "backpack_ironhaven", "travelersbackpack", "sleeping_bag_blackwater", "comforts",
            "wayside_water_valewood", "thirstwastaken2");
        needs.forEach((line, mod) -> {
            boolean on = RegionalCatalog.INSTANCE.find(SURVIVAL + line) != null;
            check(on == ModList.get().isLoaded(mod), line + " is " + (on ? "on sale" : "left out") + " but " + mod + " is " + (ModList.get().isLoaded(mod) ? "installed" : "not installed"));
        });
        // and every line that is on sale really is a registered item with a sensible price and a small shelf
        for (RegionalStock stock : RegionalCatalog.INSTANCE.allForTest()) {
            if (!stock.id().startsWith(SURVIVAL)) continue;
            check(ForgeRegistries.ITEMS.containsKey(stock.item()) && stock.unitPrice() >= 1 && (long) stock.capacity() * stock.unitPrice() <= 1200, stock.id() + " is not a small shelf of a real item");
        }
        helper.succeed();
    }

    /** A provisions desk is a terminal inside the lot of a built wayside shelter: it belongs to the nearest city, sells the wayside lines and is only a shop. */
    @GameTest(template = "empty")
    public static void aWaysideDeskSellsTheNearestCitysBasicsFromAFiniteSharedStockAndIsOnlyAShop(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        BlockPos arena = helper.absolutePos(new BlockPos(0, 2, 0));
        String dimension = level.dimension().location().toString();
        var original = List.copyOf(CityRegions.INSTANCE.all());
        Path journal = level.getServer().getWorldPath(LevelResource.ROOT).resolve("uc-test-journals/wayside-" + UUID.randomUUID() + ".dat");
        PlacedSites sites = PlacedSites.get(level, PlacedSites.SHELTERS);
        String key = "uc_test_road#0";
        try {
            // two cities well away from the arena, ironhaven nearer: the shelter belongs to ironhaven; the arena itself is inside neither
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("ironhaven", dimension, (arena.getX() >> 4) + 40, arena.getZ() >> 4, 64),
                new CityRegions.Region("valewood", dimension, (arena.getX() >> 4) - 90, arena.getZ() >> 4, 64)));
            check(CityRegions.INSTANCE.cityAt(level, arena).isEmpty() && CityRegions.INSTANCE.nearest(level, arena).orElse("").equals("ironhaven"), "setup: the arena is open country near ironhaven");
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(WaysideShelters.kindOf("uc_test_road", 0));
            check(district != null, "the shelter district for the test road exists");
            BlockPos origin = arena;
            BlockPos desk = origin.offset(7, 0, 2), outside = origin.offset(30, 0, 2);
            level.setBlockAndUpdate(desk, UndergroundCity.TERMINAL.get().defaultBlockState());
            level.setBlockAndUpdate(outside, UndergroundCity.TERMINAL.get().defaultBlockState());
            check(Exchanges.at(level, desk).isEmpty(), "before the shelter is recorded as built, a terminal there is not a desk");
            sites.where.put(key, origin.asLong());
            var exchange = Exchanges.at(level, desk).orElseThrow(() -> new AssertionError("a terminal inside a built shelter's lot is not recognised as its desk"));
            check(exchange.city().equals("ironhaven") && exchange.wayside(), "the desk belongs to the nearest city: " + exchange);
            check(Exchanges.at(level, outside).isEmpty(), "a terminal beside the lot is not a desk");

            // what the desk offers: the wayside lines of the city, none of the city's own durable goods
            ServerPlayer buyer = player(helper, "uc_wayfarer", "farmer");
            WorkOrderLedger orders = new WorkOrderLedger(journal);
            CitizenState.Citizen citizen = CitizenState.get(level.getServer()).find(buyer.getUUID()).orElseThrow();
            var offers = RegionalMarket.offers(orders, "ironhaven", RegionalStock.WAYSIDE, citizen);
            var cityOffers = RegionalMarket.offers(orders, "ironhaven", RegionalStock.CITY, citizen);
            check(offers.stream().anyMatch(o -> o.id().equals(SURVIVAL + "wayside_bread_ironhaven")) && offers.stream().noneMatch(o -> o.id().contains("backpack") || o.id().contains("iron_flask")),
                "the desk's list is the wayside lines only: " + offers.stream().map(TerminalData.Offer::id).toList());
            check(cityOffers.stream().noneMatch(o -> o.id().contains("wayside")), "the city exchange does not list the desk's lines");
            // the terminal screen shows a shop and nothing else
            TerminalData shown = TerminalData.snapshot(buyer, desk);
            check(shown.jobs().isEmpty() && shown.region().isEmpty() && !shown.offers().isEmpty() && shown.contacts().isEmpty(), "a wayside desk showed orders, a region or contacts: " + shown.jobs().size() + "/" + shown.region());

            // buying bread: priced, paid from the Q Shop wallet, taken from the shelf
            QShopEconomy.applyBalance(buyer, 100);
            RegionalStock bread = RegionalCatalog.INSTANCE.find(SURVIVAL + "wayside_bread_ironhaven");
            int shelf = orders.regionalUnits(bread);
            int visits = orders.foreignVisits(buyer.getUUID(), "valewood");
            RegionalMarket.purchase(buyer, desk, orders, citizen, bread.id(), 3, id());
            check(buyer.getInventory().countItem(Items.BREAD) == 3 && QShopEconomy.balance(buyer) == 100 - 3 * RegionalMarket.unitPrice(bread, citizen) && orders.regionalUnits(bread) == shelf - 3,
                "bread bought at the desk: goods " + buyer.getInventory().countItem(Items.BREAD) + ", wallet " + QShopEconomy.balance(buyer) + ", shelf " + orders.regionalUnits(bread) + " (was " + shelf + ")");
            check(orders.foreignVisits(buyer.getUUID(), "valewood") == visits && !orders.player(buyer.getUUID()).getList("visited", 8).toString().contains("ironhaven"), "a purchase at a roadside desk counted as a visit to the city");
            // the shelf is finite
            rejected(() -> RegionalMarket.purchase(buyer, desk, orders, citizen, bread.id(), bread.maxOrder() + 1, id()), "an order larger than one purchase allows");
            int left = orders.regionalUnits(bread);
            while (left > 0) { int n = Math.min(bread.maxOrder(), left); RegionalMarket.purchase(buyer, desk, orders, citizen, bread.id(), n, id()); left -= n; QShopEconomy.applyBalance(buyer, 100); buyer.getInventory().clearContent(); }
            rejected(() -> RegionalMarket.purchase(buyer, desk, orders, citizen, bread.id(), 1, id()), "buying from an empty shelf");
            // a city-only line is not for sale at the desk, and the desk's line not in the city
            rejected(() -> RegionalMarket.purchase(buyer, desk, orders, citizen, "underground_city:machined_parts", 1, id()), "a city line at a wayside desk");
            // orders are the cities' business: the packet path refuses an order at the desk, and takes it at a terminal inside a city (the control)
            WorkOrderLedger live = WorkOrderLedger.get(level.getServer());
            buyer.moveTo(desk.getX() + 0.5, desk.getY(), desk.getZ() + 1.5);
            WorkOrderService.action(buyer, desk, 0, "underground_city:harvest", null);
            check(!live.player(buyer.getUUID()).hasUUID("order"), "an order was accepted at a wayside desk");
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("valewood", dimension, outside.getX() >> 4, outside.getZ() >> 4, 64)));
            ServerPlayer other = player(helper, "uc_wayfarer2", "farmer");   // the terminal answers a player at most once every five ticks, so the control is another player
            other.moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 1.5);
            WorkOrderService.action(other, outside, 0, "underground_city:harvest", null);
            check(live.player(other.getUUID()).hasUUID("order"), "control: the same request at a terminal inside a city was not accepted, so the refusal above proves nothing");
            live.cancel(other.getUUID(), live.player(other.getUUID()).getUUID("order"));
        } finally {
            sites.where.remove(key);
            CityRegions.INSTANCE.replaceForTest(original);
            Files.deleteIfExists(journal);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theFirstSurvivalKitCanBeBoughtAndUsedWithTheCreditsOfOneCareer(GameTestHelper helper) throws Exception {
        if (!survivalLoaded() || !QShopEconomy.available()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        BlockPos terminal = helper.absolutePos(new BlockPos(0, 2, 0));
        var original = List.copyOf(CityRegions.INSTANCE.all());
        Path journal = level.getServer().getWorldPath(LevelResource.ROOT).resolve("uc-test-journals/kit-" + UUID.randomUUID() + ".dat");
        try {
            for (String city : List.of("valewood", "ironhaven", "blackwater")) {
                CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(city, level.dimension().location().toString(), terminal.getX() >> 4, terminal.getZ() >> 4, 64)));
                ServerPlayer buyer = player(helper, "uc_kit_" + city.substring(0, 4), "farmer");
                WorkOrderLedger orders = new WorkOrderLedger(journal);
                CitizenState.Citizen citizen = CitizenState.get(level.getServer()).find(buyer.getUUID()).orElseThrow();
                QShopEconomy.applyBalance(buyer, 5000);
                // the offers a player sees in this city are all real items they can afford one of
                var offers = RegionalMarket.offers(orders, city, citizen);
                check(offers.stream().anyMatch(o -> o.id().contains("waterskin")), city + " sells the first flask");
                for (var offer : offers) {
                    check(offer.stock() > 0 && offer.price() > 0 && offer.price() <= 200 && ForgeRegistries.ITEMS.containsKey(ResourceLocation.parse(offer.item())), city + ": " + offer.id() + " is a real, affordable, stocked line");
                }
                for (var offer : offers) {
                    if (!offer.id().startsWith(SURVIVAL)) continue;
                    double before = QShopEconomy.balance(buyer);
                    RegionalMarket.purchase(buyer, terminal, orders, citizen, offer.id(), 1, id());
                    check(before - QShopEconomy.balance(buyer) == offer.price(), city + ": " + offer.id() + " cost " + (before - QShopEconomy.balance(buyer)) + " instead of " + offer.price());
                    check(buyer.getInventory().countItem(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(offer.item()))) >= 1, city + ": " + offer.id() + " was not delivered");
                }
            }
        } finally {
            CityRegions.INSTANCE.replaceForTest(original);
            Files.deleteIfExists(journal);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theProvisionsDeskIsPartOfBothShelterDesigns(GameTestHelper helper) {
        for (String id : List.of("wayside_shelter", "wayside_well")) {
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(id);
            check(district != null, id + " exists");
            long terminals = district.ops().stream().filter(op -> "terminal".equals(op.kind())).count();
            check(terminals == 1, id + " has " + terminals + " provisions desks, expected exactly one");
        }
        helper.succeed();
    }
}
