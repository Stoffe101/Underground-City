package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Map;

/**
 * The raw materials of the hybrid economy (D-120): a new world has no natural ore, so iron, coal, copper, redstone, lapis, gold and diamonds are sold by finite, shared, slowly refilling shelves
 * of the regional exchange ({@code content/gameplay/materials-supply.json}). These tests prove the lines load and have the shape the economy depends on; the refill clock is the exchange's own and
 * is proved by its own tests.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MaterialsSupplyGameTests {
    private MaterialsSupplyGameTests() {}

    private static final String MATERIALS = "underground_city:materials/";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @GameTest(template = "empty")
    public static void everyRawMaterialIsSoldByAFiniteShelfInItsCityAndNothingIsASecondCurrency(GameTestHelper helper) {
        Map<String, String> expected = Map.ofEntries(
            Map.entry("iron_ingot_ironhaven", "ironhaven"), Map.entry("coal_ironhaven", "ironhaven"), Map.entry("copper_ingot_ironhaven", "ironhaven"),
            Map.entry("charcoal_valewood", "valewood"), Map.entry("iron_ingot_valewood", "valewood"),
            Map.entry("redstone_blackwater", "blackwater"), Map.entry("iron_ingot_blackwater", "blackwater"), Map.entry("lapis_blackwater", "blackwater"),
            Map.entry("gold_ingot_blackwater", "blackwater"), Map.entry("diamond_blackwater", "blackwater"));
        for (var entry : expected.entrySet()) {
            RegionalStock line = RegionalCatalog.INSTANCE.find(MATERIALS + entry.getKey());
            check(line != null, entry.getKey() + " is not loaded");
            check(line.city().equals(entry.getValue()), entry.getKey() + " is sold in " + line.city());
            check(ForgeRegistries.ITEMS.containsKey(line.item()), entry.getKey() + " sells the unregistered item " + line.item());
            check(line.item().getNamespace().equals("minecraft"), entry.getKey() + " must be a vanilla item so that no mod is needed for the basics");
            check(line.soldAt("city") && !line.soldAt("wayside"), entry.getKey() + " is a city shelf, not a wayside one");
            check(line.capacity() >= 1 && line.initial() <= line.capacity() && line.maxOrder() <= line.capacity(), entry.getKey() + " has an impossible shelf");
            check((long) line.capacity() * line.unitPrice() <= 1200, entry.getKey() + ": a full shelf is a stockpile of " + (long) line.capacity() * line.unitPrice() + " credits");
            check(line.intervalTicks() >= 20 * 60 * 5, entry.getKey() + " refills faster than every five minutes");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void gemsAndGoldAreDearerAndSlowerThanIronSoTheUpperBackpackTiersStayALongTermPurchase(GameTestHelper helper) {
        RegionalStock iron = RegionalCatalog.INSTANCE.find(MATERIALS + "iron_ingot_ironhaven");
        RegionalStock gold = RegionalCatalog.INSTANCE.find(MATERIALS + "gold_ingot_blackwater");
        RegionalStock diamond = RegionalCatalog.INSTANCE.find(MATERIALS + "diamond_blackwater");
        check(iron != null && gold != null && diamond != null, "the lines are missing");
        // credits of shelf refilled per active hour: the more precious the material, the slower the market can ever deliver it
        double ironPerHour = (double) iron.unitsPerInterval() * 72000 / iron.intervalTicks();
        double diamondPerHour = (double) diamond.unitsPerInterval() * 72000 / diamond.intervalTicks();
        check(diamond.unitPrice() >= gold.unitPrice() * 3 && gold.unitPrice() >= iron.unitPrice() * 3, "the price ladder iron < gold < diamond is too flat");
        check(diamondPerHour <= 1.0, "diamonds refill at " + diamondPerHour + " per active hour; a stack of the fifth tier (eight) must take many hours");
        check(ironPerHour >= 6 * diamondPerHour, "iron is not meaningfully more plentiful than diamonds");
        // the eight diamonds of the fifth backpack tier cost more than a day of one career's net income would be reasonable to spend at once (a design bound, see docs/technical/hybrid-economy.md)
        check(8 * diamond.unitPrice() >= 1000, "eight diamonds are too cheap");
        helper.succeed();
    }
}
