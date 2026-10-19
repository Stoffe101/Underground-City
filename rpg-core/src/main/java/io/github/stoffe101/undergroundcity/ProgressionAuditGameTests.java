package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * Progression without monsters (D-095): the simulation world has no zombies, spiders, endermen or blazes, so every item that the game's own recipes make from their drops needs
 * another way in. These tests read the recipes that are actually loaded in the pack that is running (so a mod update that adds a new dependency fails here, not in a player's
 * hands) and the regional-exchange lines that are actually on sale, and prove that
 * <ul>
 * <li>every route the catalogue promises exists: the exchange line sells that item, in a city, from a finite shelf; the recipe it names is loaded;</li>
 * <li>nothing in the approved mods needs a hostile drop without a route (or is excluded with a reason);</li>
 * <li>the backpack items need exactly the drops the backpack policy says.</li>
 * </ul>
 * What they cannot see: Immersive Vehicles keeps its materials in pack files of its own, not in recipes (docs/technical/monster-resources.md lists what a static scan of the
 * pinned pack files found), and whether a human finds the shops in time is for the playtest.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProgressionAuditGameTests {
    private ProgressionAuditGameTests() {}

    @GameTest(template = "empty")
    public static void everyRouteOfTheCatalogueIsReal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        JsonObject catalogue = MonsterDependencies.catalogue();
        Set<String> routed = MonsterDependencies.routed(catalogue);
        for (Map.Entry<String, JsonElement> entry : catalogue.getAsJsonObject("roots").entrySet()) {
            String root = entry.getKey();
            check(ForgeRegistries.ITEMS.containsKey(ResourceLocation.parse(root)), root + " is not a registered item");
            JsonObject definition = entry.getValue().getAsJsonObject();
            if (!definition.has("route") || definition.get("route").isJsonNull()) {
                check(definition.has("needed_by_approved_gameplay") && !definition.get("needed_by_approved_gameplay").getAsBoolean(), root + " has no route, so it must say it is needed by nothing");
                continue;
            }
            JsonObject route = definition.getAsJsonObject("route");
            String kind = route.get("kind").getAsString();
            switch (kind) {
                case "exchange" -> {
                    for (JsonElement line : route.getAsJsonArray("lines")) {
                        RegionalStock stock = RegionalCatalog.INSTANCE.find(line.getAsString());
                        check(stock != null, root + ": the exchange line " + line.getAsString() + " is not on sale");
                        check(stock.item().toString().equals(root), line.getAsString() + " sells " + stock.item() + ", not " + root);
                        check(stock.soldAt("city") && stock.capacity() >= 1 && stock.unitsPerInterval() >= 1 && (long) stock.capacity() * stock.unitPrice() <= 1200, line.getAsString() + " is not a small, finite shelf in a city");
                    }
                }
                case "recipe", "byproduct" -> {
                    String mod = route.has("mod") ? route.get("mod").getAsString() : "minecraft";
                    if (ModList.get().isLoaded(mod)) check(level.getRecipeManager().byKey(ResourceLocation.parse(route.get("recipe").getAsString())).isPresent(), root + ": the recipe " + route.get("recipe").getAsString() + " of " + mod + " is not loaded");
                }
                case "derived" -> {
                    check(routed.contains(route.get("from").getAsString()), root + " is derived from " + route.get("from").getAsString() + ", which has no route");
                    check(level.getRecipeManager().byKey(ResourceLocation.parse(route.get("recipe").getAsString())).isPresent(), root + ": the recipe " + route.get("recipe").getAsString() + " is not loaded");
                }
                default -> check(false, root + ": unknown route kind " + kind);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void nothingTheApprovedModsOfferNeedsAHostileDropWithoutARoute(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        JsonObject catalogue = MonsterDependencies.catalogue();
        Map<String, Set<String>> needs = MonsterDependencies.rootsNeeded(level, MonsterDependencies.allRoots(catalogue));
        Map<String, Set<String>> unrouted = MonsterDependencies.unrouted(needs, catalogue);
        // the record for docs/technical/monster-resources.md: everything in the approved mods that does need a root, and which
        Map<String, Set<String>> report = new TreeMap<>();
        Set<String> namespaces = new TreeSet<>();
        for (JsonElement e : catalogue.getAsJsonObject("approved_scopes").getAsJsonArray("namespaces")) namespaces.add(e.getAsString());
        needs.forEach((item, roots) -> {
            if (!roots.isEmpty() && namespaces.contains(ResourceLocation.parse(item).getNamespace())) report.put(item, roots);
        });
        Map<String, List<String>> byRoot = new TreeMap<>();
        report.forEach((item, roots) -> roots.forEach(root -> byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(item)));
        byRoot.forEach((root, items) -> System.out.println("[UC-PROGRESSION-AUDIT] " + root + " is needed by " + items.size() + " item(s) of the approved mods: " + (items.size() > 30 ? items.subList(0, 30) + " ..." : items)));
        System.out.println("[UC-PROGRESSION-AUDIT] items without a route: " + unrouted);
        check(unrouted.isEmpty(), "approved items need hostile drops that have no route: " + unrouted);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void theBackpackItemsNeedExactlyTheDropsThePolicyDeclares(GameTestHelper helper) {
        if (!SurvivalSet.backpacksLoaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        JsonObject catalogue = MonsterDependencies.catalogue();
        Map<String, Set<String>> needs = MonsterDependencies.rootsNeeded(level, MonsterDependencies.allRoots(catalogue));
        JsonObject declared = BackpackPolicy.load().getAsJsonObject("monster_inputs");
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
            Set<String> expected = new TreeSet<>();
            for (JsonElement e : (JsonArray) entry.getValue()) expected.add(e.getAsString());
            Set<String> actual = needs.getOrDefault(entry.getKey(), Set.of());
            if (!actual.equals(expected)) wrong.add(entry.getKey() + " needs " + actual + " by the loaded recipes, the policy says " + expected);
        }
        if (!wrong.isEmpty()) {
            for (String item : List.of("sophisticatedbackpacks:pickup_upgrade", "sophisticatedbackpacks:deposit_upgrade", "minecraft:piston", "minecraft:sticky_piston", "sophisticatedbackpacks:feeding_upgrade", "sophisticatedbackpacks:netherite_backpack"))
                System.out.println("[UC-PROGRESSION-AUDIT] " + MonsterDependencies.explain(level, needs, item));
        }
        check(wrong.isEmpty(), "the backpack policy's monster_inputs are out of date: " + wrong);
        helper.succeed();
    }
}
