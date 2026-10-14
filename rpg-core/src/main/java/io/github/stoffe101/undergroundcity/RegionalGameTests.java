package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Dedicated-server tests of the regional supply loop against the real loaded datapack. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RegionalGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe action accepted");
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, path); }
    private static Item item(String path) { return ForgeRegistries.ITEMS.getValue(id(path)); }

    private static FakePlayer player(GameTestHelper helper, String city, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_r_" + career));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), city, career);
        return player;
    }

    private record Fixture(WorkOrderLedger ledger, Path path, List<CityRegions.Region> original, BlockPos pos) {
        void close() throws java.io.IOException {
            CityRegions.INSTANCE.replaceForTest(original);
            Files.deleteIfExists(path);
        }
    }

    /** Isolated journal plus a city circle of {@code city} around the test terminal. */
    private static Fixture fixture(GameTestHelper helper, String city) {
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("uc-test-journals/" + UUID.randomUUID() + ".dat");
        BlockPos pos = helper.absolutePos(new BlockPos(0, 2, 0));
        List<CityRegions.Region> original = CityRegions.INSTANCE.all();
        CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(city,
            helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
        return new Fixture(new WorkOrderLedger(path), path, original, pos);
    }

    private static void purchase(FakePlayer player, Fixture fixture, String commodity, int quantity, UUID nonce) {
        RegionalMarket.purchase(player, fixture.pos, fixture.ledger, CitizenState.get(player.server).find(player.getUUID()).orElseThrow(),
            UndergroundCity.MOD_ID + ":" + commodity, quantity, nonce);
    }

    @GameTest(template = "empty")
    public static void regionalDataLoadsAndEconomyCannotPrintMoney(GameTestHelper helper) {
        check(RegionalCatalog.INSTANCE.forCity("ironhaven").size() == 3 && RegionalCatalog.INSTANCE.forCity("valewood").size() == 3
            && RegionalCatalog.INSTANCE.forCity("blackwater").size() == 3, "Each city must sell three specialist lines");
        check(CityRegions.INSTANCE.all().size() == 3, "Three data-defined city regions expected");
        int regional = 0;
        for (String career : List.of("farmer", "smith", "gunsmith", "technician")) {
            for (WorkOrder order : WorkOrderCatalog.INSTANCE.forCareer(career)) {
                if (order.foreignVisits() == 0) continue;
                regional++;
                double cost = 0;
                for (WorkOrder.Requirement requirement : order.requirements()) {
                    var recipes = helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                        .filter(recipe -> recipe.getResultItem(helper.getLevel().registryAccess()).is(requirement.registryItem())).toList();
                    check(recipes.size() == 1, "Every regional product needs exactly one crafting recipe: " + requirement.item());
                    var recipe = recipes.get(0);
                    check(recipe.getIngredients().size() <= 9, "Recipe does not fit the crafting grid");
                    double batch = 0;
                    for (Ingredient ingredient : recipe.getIngredients()) {
                        for (ItemStack option : ingredient.getItems()) {
                            RegionalStock line = RegionalCatalog.INSTANCE.forCity("ironhaven").stream()
                                .filter(stock -> stock.registryItem() == option.getItem()).findFirst()
                                .or(() -> RegionalCatalog.INSTANCE.forCity("valewood").stream().filter(stock -> stock.registryItem() == option.getItem()).findFirst())
                                .or(() -> RegionalCatalog.INSTANCE.forCity("blackwater").stream().filter(stock -> stock.registryItem() == option.getItem()).findFirst())
                                .orElse(null);
                            if (line != null) batch += line.unitPrice();
                        }
                    }
                    cost += batch / recipe.getResultItem(helper.getLevel().registryAccess()).getCount() * requirement.count();
                }
                double margin = order.credits() / cost;
                check(cost > 0 && margin >= 1.3 && margin <= 2.1, order.id() + " margin " + margin + " is outside the balanced band");
            }
        }
        check(regional == 12, "Twelve recurring regional orders expected, found " + regional);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cityRegionLookupIsGeographic(GameTestHelper helper) {
        var level = helper.getLevel();
        List<CityRegions.Region> original = CityRegions.INSTANCE.all();
        try {
            check(CityRegions.INSTANCE.cityAt(level, new BlockPos(0, 64, 0)).orElse("").equals("ironhaven"), "Ironhaven centre not recognised");
            check(CityRegions.INSTANCE.cityAt(level, new BlockPos(2016, 64, 704)).orElse("").equals("valewood"), "Valewood centre not recognised");
            check(CityRegions.INSTANCE.cityAt(level, new BlockPos(-2016, 64, 1072)).orElse("").equals("blackwater"), "Blackwater centre not recognised");
            check(CityRegions.INSTANCE.cityAt(level, new BlockPos(1000, 64, 1000)).isEmpty(), "Wilderness between cities matched a city");
            check(CityRegions.INSTANCE.cityAt(level, new BlockPos(8 + 601, 64, 8)).isEmpty(), "Position outside the radius matched");
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("ironhaven", "minecraft:the_nether", 0, 0, 600)));
            check(CityRegions.INSTANCE.cityAt(level, new BlockPos(0, 64, 0)).isEmpty(), "Wrong dimension matched an overworld city");
        } finally { CityRegions.INSTANCE.replaceForTest(original); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void purchaseUsesSharedStockSingleWalletAndRejectsAbuse(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "ironhaven");
        try {
            FakePlayer first = player(helper, "valewood", "farmer"), second = player(helper, "ironhaven", "smith");
            QShopEconomy.applyBalance(first, 100); QShopEconomy.applyBalance(second, 100);
            RegionalStock parts = RegionalCatalog.INSTANCE.find("underground_city:machined_parts");
            int start = fixture.ledger.regionalUnits(parts);
            UUID nonce = UUID.randomUUID();
            purchase(first, fixture, "machined_parts", 2, nonce);
            check(first.getInventory().countItem(item("machined_parts")) == 2, "Purchased parts not delivered");
            check(QShopEconomy.balance(first) == 100 - 2 * parts.unitPrice(), "Single Q Shop wallet was not debited exactly");
            check(fixture.ledger.regionalUnits(parts) == start - 2, "Shared stock did not decrease");
            check(fixture.ledger.foreignVisits(first.getUUID(), "valewood") == 1, "Visit to Ironhaven exchange not recorded");
            check(fixture.ledger.foreignVisits(second.getUUID(), "ironhaven") == 0, "Home-city purchase counted as a foreign visit");
            rejected(() -> purchase(first, fixture, "machined_parts", 2, nonce)); // replayed click/packet
            check(first.getInventory().countItem(item("machined_parts")) == 2, "Replayed purchase delivered items twice");
            check(QShopEconomy.balance(first) == 100 - 2 * parts.unitPrice(), "Replayed purchase charged twice");
            purchase(second, fixture, "machined_parts", 3, UUID.randomUUID());
            check(fixture.ledger.regionalUnits(parts) == start - 5, "Two players did not draw from one shared shelf");
            // Abuse: out-of-range quantities, foreign-city line, unknown line, insufficient credits, outside any city.
            rejected(() -> purchase(first, fixture, "machined_parts", 0, UUID.randomUUID()));
            rejected(() -> purchase(first, fixture, "machined_parts", parts.maxOrder() + 1, UUID.randomUUID()));
            rejected(() -> purchase(first, fixture, "rare_seeds", 1, UUID.randomUUID()));
            rejected(() -> purchase(first, fixture, "not_a_line", 1, UUID.randomUUID()));
            QShopEconomy.applyBalance(first, 5);
            rejected(() -> purchase(first, fixture, "machined_parts", 1, UUID.randomUUID()));
            check(QShopEconomy.balance(first) == 5 && fixture.ledger.regionalUnits(parts) == start - 5, "Failed purchase changed wallet or stock");
            CityRegions.INSTANCE.replaceForTest(List.of());
            QShopEconomy.applyBalance(first, 100);
            rejected(() -> purchase(first, fixture, "machined_parts", 1, UUID.randomUUID()));
            check(fixture.ledger.regionalUnits(parts) == start - 5, "Purchase outside every city changed stock");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryAndSoldOutLeaveEverythingUntouched(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "blackwater");
        try {
            FakePlayer player = player(helper, "blackwater", "gunsmith");
            QShopEconomy.applyBalance(player, 1000);
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) player.getInventory().items.set(slot, new ItemStack(Items.DIRT, 64));
            RegionalStock circuits = RegionalCatalog.INSTANCE.find("underground_city:control_circuits");
            int start = fixture.ledger.regionalUnits(circuits);
            rejected(() -> purchase(player, fixture, "control_circuits", 1, UUID.randomUUID()));
            check(QShopEconomy.balance(player) == 1000 && fixture.ledger.regionalUnits(circuits) == start, "Full inventory still charged or consumed stock");
            player.getInventory().clearContent();
            // Drain the shared shelf exactly, then confirm it is sold out rather than negative.
            int remaining = start;
            while (remaining > 0) {
                int take = Math.min(circuits.maxOrder(), remaining);
                QShopEconomy.applyBalance(player, 10_000);
                player.getInventory().clearContent();
                purchase(player, fixture, "control_circuits", take, UUID.randomUUID());
                remaining -= take;
            }
            check(fixture.ledger.regionalUnits(circuits) == 0, "Shelf should be sold out");
            rejected(() -> purchase(player, fixture, "control_circuits", 1, UUID.randomUUID()));
            check(fixture.ledger.regionalUnits(circuits) == 0, "Sold-out purchase drove stock negative");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stockReplenishesOnlyThroughActiveTicksAndNeverBanksBacklog(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper, "valewood");
        try {
            RegionalStock seeds = RegionalCatalog.INSTANCE.find("underground_city:rare_seeds");
            WorkOrderLedger ledger = fixture.ledger;
            check(ledger.regionalUnits(seeds) == seeds.initial(), "First access should start at the initial stock");
            check(ledger.regionalUnits(seeds) == seeds.initial(), "Reading stock must not change it");
            ledger.advance(seeds.intervalTicks() - 1);
            check(ledger.regionalUnits(seeds) == seeds.initial(), "Stock grew before a whole interval elapsed");
            ledger.advance(1);
            check(ledger.regionalUnits(seeds) == seeds.initial() + seeds.unitsPerInterval(), "One interval should add exactly one batch");
            ledger.advance(seeds.intervalTicks() * 1000L);
            check(ledger.regionalUnits(seeds) == seeds.capacity(), "Stock exceeded capacity");
            // Full shelves bank no backlog: after one purchase only one interval's worth returns.
            FakePlayer farmer = player(helper, "valewood", "farmer");
            if (QShopEconomy.available()) {
                QShopEconomy.applyBalance(farmer, 1000);
                purchase(farmer, fixture, "rare_seeds", 8, UUID.randomUUID());
                check(ledger.regionalUnits(seeds) == seeds.capacity() - 8, "Purchase should draw the full shelf down");
                ledger.advance(seeds.intervalTicks());
                check(ledger.regionalUnits(seeds) == seeds.capacity() - 8 + seeds.unitsPerInterval(), "A banked backlog refilled the shelf at once");
            }
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void preparedPurchaseIsReplayedExactlyOnceAfterRestart(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "ironhaven");
        try {
            FakePlayer player = player(helper, "ironhaven", "smith");
            QShopEconomy.applyBalance(player, 200);
            RegionalStock alloy = RegionalCatalog.INSTANCE.find("underground_city:refined_alloy");
            int start = fixture.ledger.regionalUnits(alloy);
            // Crash window: journal is durable but the live wallet/inventory were never changed.
            var inventory = new net.minecraft.world.entity.player.Inventory(player);
            inventory.add(new ItemStack(item("refined_alloy"), 3));
            UUID nonce = UUID.randomUUID();
            fixture.ledger.preparePurchase(player.getUUID(), nonce, alloy, 3, inventory.save(new net.minecraft.nbt.ListTag()),
                200 - 3 * alloy.unitPrice(), CitizenState.get(player.server).find(player.getUUID()).orElseThrow().careerXp());
            WorkOrderLedger restarted = new WorkOrderLedger(fixture.path);
            check(restarted.player(player.getUUID()).contains("pending"), "Prepared purchase lost on reload");
            check(restarted.regionalUnits(alloy) == start - 3, "Stock decrement was not durable with the prepared record");
            rejected(() -> restarted.preparePurchase(player.getUUID(), UUID.randomUUID(), alloy, 1, new net.minecraft.nbt.ListTag(), 1, 0));
            WorkOrderService.recover(player, restarted);
            WorkOrderService.recover(player, restarted);
            check(player.getInventory().countItem(item("refined_alloy")) == 3, "Recovery delivered the wrong number of items");
            check(QShopEconomy.balance(player) == 200 - 3 * alloy.unitPrice(), "Recovery charged the wrong amount");
            check(restarted.player(player.getUUID()).getInt("purchases") == 1 && !restarted.player(player.getUUID()).contains("pending"), "Recovery did not settle exactly once");
            check(restarted.hasPurchase(player.getUUID(), nonce), "Replay nonce lost");
            check(restarted.regionalUnits(alloy) == start - 3, "Recovery changed shared stock again");
            check(new WorkOrderLedger(fixture.path).regionalUnits(alloy) == start - 3, "Settled stock not durable");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullRegionalFarmerLoopBuyCraftDeliver(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "ironhaven");
        try {
            FakePlayer farmer = player(helper, "valewood", "farmer");
            QShopEconomy.applyBalance(farmer, 400);
            CitizenState.get(farmer.server).addCareerXp(farmer.getUUID(), 500); // level 3 via the real XP curve
            check(CitizenState.get(farmer.server).find(farmer.getUUID()).orElseThrow().careerLevel() == 3, "Fixture level wrong");
            var citizen = CitizenState.get(farmer.server).find(farmer.getUUID()).orElseThrow();
            // Gate 1: the regional order needs a rival-city exchange visit first.
            rejected(() -> WorkOrderService.accept(farmer, fixture.ledger, citizen, "underground_city:greenhouse_service"));
            // Machined parts exist only at the Ironhaven exchange (visit recorded by the purchase itself).
            purchase(farmer, fixture, "machined_parts", 2, UUID.randomUUID());
            // Rare seeds are a Valewood (home-city) line.
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("valewood",
                helper.getLevel().dimension().location().toString(), fixture.pos.getX() >> 4, fixture.pos.getZ() >> 4, 64)));
            purchase(farmer, fixture, "rare_seeds", 4, UUID.randomUUID());
            check(QShopEconomy.balance(farmer) == 400 - 2 * 18 - 4 * 14, "Wallet after purchases wrong");
            // Real vanilla crafting: shapeless recipe from the loaded datapack, two batches.
            int kits = 0;
            for (int batch = 0; batch < 2; batch++) {
                var crafting = new TransientCraftingContainer(farmer.inventoryMenu, 3, 3);
                var recipe = helper.getLevel().getRecipeManager().byKey(id("irrigation_service_kit")).orElseThrow();
                int slot = 0;
                for (Ingredient ingredient : recipe.getIngredients()) crafting.setItem(slot++, ingredient.getItems()[0].copy());
                var match = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, crafting, helper.getLevel()).orElseThrow();
                ItemStack result = match.assemble(crafting, helper.getLevel().registryAccess());
                check(result.is(item("irrigation_service_kit")), "Crafting produced the wrong item");
                kits += result.getCount();
                // Consume exactly the recipe inputs from the real inventory.
                for (Ingredient ingredient : recipe.getIngredients()) {
                    Item needed = ingredient.getItems()[0].getItem();
                    boolean removed = false;
                    for (ItemStack stack : farmer.getInventory().items) {
                        if (stack.is(needed)) { stack.shrink(1); removed = true; break; }
                    }
                    if (!removed) farmer.getInventory().add(new ItemStack(needed)); // vanilla bucket etc. are ungated and supplied here
                }
            }
            check(kits == 4, "Two batches must produce four kits");
            farmer.getInventory().add(new ItemStack(item("irrigation_service_kit"), 4));
            UUID order = fixture.ledger.accept(farmer.getUUID(), WorkOrderCatalog.INSTANCE.find("underground_city:greenhouse_service"));
            double before = QShopEconomy.balance(farmer);
            WorkOrderService.complete(farmer, fixture.ledger, order);
            check(farmer.getInventory().countItem(item("irrigation_service_kit")) == 0, "Delivery did not consume the products");
            check(QShopEconomy.balance(farmer) == before + 150, "Order did not pay 150 credits");
            check(CitizenState.get(farmer.server).find(farmer.getUUID()).orElseThrow().careerXp() == 800, "Order XP wrong");
            rejected(() -> WorkOrderService.complete(farmer, fixture.ledger, order));
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void visitGateRequiresRivalCityPresence(GameTestHelper helper) throws Exception {
        Fixture fixture = fixture(helper, "ironhaven");
        try {
            FakePlayer smith = player(helper, "ironhaven", "smith");
            CitizenState.get(smith.server).addCareerXp(smith.getUUID(), 1200);
            var citizen = CitizenState.get(smith.server).find(smith.getUUID()).orElseThrow();
            check(citizen.careerLevel() >= 4, "Fixture smith level wrong");
            rejected(() -> WorkOrderService.accept(smith, fixture.ledger, citizen, "underground_city:foundry_parts"));
            check(!fixture.ledger.noteVisit(smith.getUUID(), "not_a_city"), "Invalid city recorded as a visit");
            check(!fixture.ledger.noteVisit(smith.getUUID(), "ironhaven") || fixture.ledger.foreignVisits(smith.getUUID(), "ironhaven") == 0, "Home visit counted as foreign");
            rejected(() -> WorkOrderService.accept(smith, fixture.ledger, citizen, "underground_city:foundry_parts"));
            fixture.ledger.noteVisit(smith.getUUID(), "blackwater");
            fixture.ledger.noteVisit(smith.getUUID(), "blackwater");
            check(fixture.ledger.foreignVisits(smith.getUUID(), "ironhaven") == 1, "Repeated visits to one city counted twice");
            WorkOrderService.accept(smith, fixture.ledger, citizen, "underground_city:foundry_parts");
            rejected(() -> WorkOrderService.accept(smith, fixture.ledger, citizen, "underground_city:composite_housings"));
            rejected(() -> WorkOrderService.accept(smith, fixture.ledger, citizen, "underground_city:harvest")); // wrong career
            check(new WorkOrderLedger(fixture.path).foreignVisits(smith.getUUID(), "ironhaven") == 1, "Visits not durable");
        } finally { fixture.close(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedAndLegacyDefinitionsAreHandledSafely(GameTestHelper helper) {
        ResourceLocation test = id("bad");
        String base = "{\"schema_version\":2,\"title\":\"T\",\"career\":\"farmer\",\"level\":1,\"requirements\":[{\"item\":\"minecraft:wheat\",\"count\":1}],\"credits\":5,\"xp\":5,\"capacity\":1,\"refill_ticks\":1200}";
        check(WorkOrder.fromJson(test, JsonParser.parseString(base).getAsJsonObject()).requirements().size() == 1, "Valid schema-2 order rejected");
        String[] bad = {
            base.replace("\"schema_version\":2", "\"schema_version\":9"),
            base.replace("\"career\":\"farmer\"", "\"career\":\"wizard\""),
            base.replace("minecraft:wheat", "minecraft:no_such_item"),
            base.replace("minecraft:wheat", "minecraft:air"),
            base.replace("\"count\":1}", "\"count\":65}"),
            base.replace("\"credits\":5", "\"credits\":100000"),
            base.replace("[{\"item\":\"minecraft:wheat\",\"count\":1}]", "[]"),
            base.replace("[{\"item\":\"minecraft:wheat\",\"count\":1}]", "[{\"item\":\"minecraft:wheat\",\"count\":1},{\"item\":\"minecraft:wheat\",\"count\":2}]"),
            base.replace("\"refill_ticks\":1200", "\"refill_ticks\":1"),
            base.replace("\"capacity\":1", "\"capacity\":0"),
            base.replace("\"level\":1", "\"level\":99"),
            base.replace("\"requirements\"", "\"foreign_visits\":7,\"requirements\""),
        };
        for (String json : bad) {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            rejected(() -> WorkOrder.fromJson(test, object));
        }
        // Schema 1 definitions and old single-item journal snapshots keep loading.
        JsonObject legacy = JsonParser.parseString("{\"schema_version\":1,\"title\":\"T\",\"career\":\"farmer\",\"level\":1,\"item\":\"minecraft:wheat\",\"count\":16,\"credits\":24,\"xp\":125,\"capacity\":4,\"refill_ticks\":6000}").getAsJsonObject();
        WorkOrder old = WorkOrder.fromJson(test, legacy);
        check(old.requirements().size() == 1 && old.foreignVisits() == 0, "Legacy order mis-parsed");
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("id", "underground_city:harvest"); snapshot.putString("title", "T"); snapshot.putString("career", "farmer");
        snapshot.putString("item", "minecraft:wheat"); snapshot.putInt("level", 1); snapshot.putInt("count", 16);
        snapshot.putInt("credits", 24); snapshot.putInt("xp", 125); snapshot.putInt("capacity", 4); snapshot.putInt("refill_ticks", 6000);
        check(WorkOrder.load(snapshot).equals(old.withId("underground_city:harvest")), "Pre-schema-2 accepted-order snapshot no longer loads");
        WorkOrder multi = WorkOrderCatalog.INSTANCE.find("underground_city:greenhouse_service");
        check(WorkOrder.load(multi.save()).equals(multi), "Schema-2 snapshot round trip changed the order");
        // Regional stock and city circles validate too.
        rejected(() -> RegionalStock.fromJson(test, JsonParser.parseString("{\"schema_version\":1,\"city\":\"nowhere\",\"item\":\"minecraft:stick\",\"unit_price\":1,\"initial\":1,\"capacity\":2,\"units_per_interval\":1,\"interval_ticks\":1200,\"max_order\":1}").getAsJsonObject()));
        rejected(() -> RegionalStock.fromJson(test, JsonParser.parseString("{\"schema_version\":1,\"city\":\"ironhaven\",\"item\":\"minecraft:stick\",\"unit_price\":0,\"initial\":1,\"capacity\":2,\"units_per_interval\":1,\"interval_ticks\":1200,\"max_order\":1}").getAsJsonObject()));
        rejected(() -> RegionalStock.fromJson(test, JsonParser.parseString("{\"schema_version\":1,\"city\":\"ironhaven\",\"item\":\"minecraft:stick\",\"unit_price\":1,\"initial\":9,\"capacity\":2,\"units_per_interval\":1,\"interval_ticks\":1200,\"max_order\":1}").getAsJsonObject()));
        rejected(() -> CityRegions.Region.fromJson(test, JsonParser.parseString("{\"schema_version\":1,\"id\":\"ironhaven\",\"dimension\":\"minecraft:overworld\",\"center_chunk\":[0,0],\"radius_blocks\":1}").getAsJsonObject()));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void staleReplayAndInterleavedSettlementsStayIsolated(GameTestHelper helper) throws Exception {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Fixture fixture = fixture(helper, "valewood");
        try {
            FakePlayer a = player(helper, "valewood", "farmer"), b = player(helper, "ironhaven", "farmer");
            QShopEconomy.applyBalance(a, 0); QShopEconomy.applyBalance(b, 0);
            WorkOrder harvest = WorkOrderCatalog.INSTANCE.find("underground_city:harvest");
            UUID nonceA = fixture.ledger.accept(a.getUUID(), harvest), nonceB = fixture.ledger.accept(b.getUUID(), harvest);
            check(fixture.ledger.available(harvest) == harvest.capacity() - 2, "Both players must draw one shared demand pool");
            a.getInventory().setItem(0, new ItemStack(Items.WHEAT, 16)); b.getInventory().setItem(0, new ItemStack(Items.WHEAT, 16));
            // A is mid-settlement (prepared, not applied) when B completes in full: no cross-contamination.
            var after = new net.minecraft.world.entity.player.Inventory(a);
            fixture.ledger.prepare(a.getUUID(), nonceA, after.save(new net.minecraft.nbt.ListTag()), 24, 125);
            WorkOrderService.complete(b, fixture.ledger, nonceB);
            check(QShopEconomy.balance(b) == 24 && b.getInventory().countItem(Items.WHEAT) == 0, "Second player settlement wrong");
            check(QShopEconomy.balance(a) == 0 && a.getInventory().countItem(Items.WHEAT) == 16, "A prepared settlement leaked before recovery");
            rejected(() -> WorkOrderService.complete(a, fixture.ledger, nonceA)); // pending blocks a second concurrent delivery
            WorkOrderService.recover(a, fixture.ledger);
            check(QShopEconomy.balance(a) == 24 && a.getInventory().countItem(Items.WHEAT) == 0, "Recovery of the first settlement wrong");
            // Old receipts cannot pay again, even after a new order is accepted.
            UUID next = fixture.ledger.accept(a.getUUID(), harvest);
            check(!next.equals(nonceA), "Order IDs must be server-generated and fresh");
            a.getInventory().setItem(0, new ItemStack(Items.WHEAT, 16));
            rejected(() -> WorkOrderService.complete(a, fixture.ledger, nonceA));
            rejected(() -> fixture.ledger.cancel(a.getUUID(), nonceA));
            check(QShopEconomy.balance(a) == 24 && a.getInventory().countItem(Items.WHEAT) == 16, "Stale receipt paid or consumed goods");
        } finally { fixture.close(); }
        helper.succeed();
    }
}
