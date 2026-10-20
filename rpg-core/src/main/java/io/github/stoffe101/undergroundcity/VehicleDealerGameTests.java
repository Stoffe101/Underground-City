package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The one implemented way to acquire a vehicle (D-101): the exchange's dealer lines for the Modern Life bicycle and Motorboat. The test buys the real items with real credits
 * from the real shared stock, and reads the Motorboat's crafting recipe from the running game to show that the iron in the planning table is the iron in the recipe.
 * Passes trivially when Modern Life is not installed (the lines are then left out at load time, which is also checked). Nothing about fuel, driving or the boat's hold is asserted here.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VehicleDealerGameTests {
    private VehicleDealerGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final String BICYCLE = "underground_city:vehicles/bicycle_valewood", MOTORBOAT = "underground_city:vehicles/motorboat_valewood";

    @GameTest(template = "empty")
    public static void theDealerSellsRealVehiclesOnlyWhereTheirModIsInstalled(GameTestHelper helper) {
        boolean installed = ModList.get().isLoaded("modernlife");
        for (String city : List.of("valewood", "ironhaven", "blackwater")) {
            long lines = RegionalCatalog.INSTANCE.forCity(city).stream().filter(stock -> stock.id().startsWith(UndergroundCity.MOD_ID + ":vehicles/")).count();
            check(lines == (installed ? (city.equals("valewood") ? 2 : 1) : 0), city + " sells " + lines + " vehicle lines with Modern Life " + (installed ? "installed" : "absent"));
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_vehicle_dealer")
    public static void aBicycleAndAMotorboatAreBoughtFromSharedStockForTheirTierPrice(GameTestHelper helper) throws Exception {
        if (!ModList.get().isLoaded("modernlife") || !QShopEconomy.available()) { helper.succeed(); return; }
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/dealer-" + UUID.randomUUID() + ".dat");
        List<CityRegions.Region> original = CityRegions.INSTANCE.all();
        try {
            WorkOrderLedger ledger = new WorkOrderLedger(path);
            BlockPos terminal = helper.absolutePos(new BlockPos(0, 2, 0));
            CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("valewood", helper.getLevel().dimension().location().toString(), terminal.getX() >> 4, terminal.getZ() >> 4, 64)));
            FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_dealer"));
            player.getInventory().clearContent();
            CitizenState.get(player.server).register(player.getUUID(), "valewood", "smith");
            CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
            QShopEconomy.applyBalance(player, 1000);
            RegionalStock bicycle = RegionalCatalog.INSTANCE.find(BICYCLE), boat = RegionalCatalog.INSTANCE.find(MOTORBOAT);
            check(bicycle != null && boat != null && bicycle.unitPrice() == 60 && boat.unitPrice() == 600, "the dealer lines carry the tier prices");
            Item bike = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("modernlife:bicycle_item")), boatItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("modernlife:motorboat_item"));
            int bikesBefore = ledger.regionalUnits(bicycle), boatsBefore = ledger.regionalUnits(boat);
            RegionalMarket.purchase(player, terminal, ledger, citizen, BICYCLE, 1, UUID.randomUUID());
            RegionalMarket.purchase(player, terminal, ledger, citizen, MOTORBOAT, 1, UUID.randomUUID());
            check(player.getInventory().countItem(bike) == 1 && player.getInventory().countItem(boatItem) == 1, "the real items arrived");
            check(QShopEconomy.balance(player) == 1000 - 60 - 600, "the wallet paid exactly the tier prices: " + QShopEconomy.balance(player));
            check(ledger.regionalUnits(bicycle) == bikesBefore - 1 && ledger.regionalUnits(boat) == boatsBefore - 1, "the shared shelves went down by one each");
            // a boat is scarce: the shelf holds one, the next order is refused until it refills
            QShopEconomy.applyBalance(player, 1000);
            try { RegionalMarket.purchase(player, terminal, ledger, citizen, MOTORBOAT, 1, UUID.randomUUID()); throw new AssertionError("a second boat was sold from a shelf that held one"); }
            catch (IllegalStateException expected) { /* sold out */ }
            check(QShopEconomy.balance(player) == 1000, "a refused sale cost nothing");
            helper.succeed();
        } finally {
            CityRegions.INSTANCE.replaceForTest(original);
            Files.deleteIfExists(path);
        }
    }

    @GameTest(template = "empty")
    public static void theMotorboatsIronInThePlanningTableIsTheIronInItsRecipe(GameTestHelper helper) {
        if (!ModList.get().isLoaded("modernlife")) { helper.succeed(); return; }
        Item boat = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("modernlife:motorboat_item"));
        double ironEquivalent = -1;
        for (Recipe<?> recipe : helper.getLevel().getRecipeManager().getRecipes()) {
            if (!recipe.getResultItem(helper.getLevel().registryAccess()).is(boat)) continue;
            ironEquivalent = 0;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                boolean block = false, ingot = false;
                for (var stack : ingredient.getItems()) { block |= stack.is(Items.IRON_BLOCK); ingot |= stack.is(Items.IRON_INGOT); }
                ironEquivalent += block ? 9 : ingot ? 1 : 0;
            }
        }
        check(ironEquivalent == 28, "the Motorboat recipe holds 28 iron ingots' worth of iron (three blocks and an ingot), as content/gameplay/vehicle-tiers.json says: " + ironEquivalent);
        helper.succeed();
    }
}
