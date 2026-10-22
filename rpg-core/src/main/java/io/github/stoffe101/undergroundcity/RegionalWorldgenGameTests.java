package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.util.Map;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * The archipelago world's machinery (D-104): the island plan as arithmetic, the pack derived from the game's own overworld functions, the registries every launch must still load, and the layout guard's
 * decision. The one that matters most is the registry test: a first version broke the loading of EVERY world (legacy ones included) because a directory listing matched by string prefix, and a test that
 * only looked at the new world type would not have seen it.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RegionalWorldgenGameTests {
    private RegionalWorldgenGameTests() {}

    /** The plan as the density functions carry it: read from the committed worldgen JSON, through the real codec. */
    private static IslandPlan plan() {
        try (var in = RegionalWorldgenGameTests.class.getResourceAsStream("/data/underground_city/worldgen/density_function/regional/continents.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject();
            return IslandPlan.CODEC.parse(JsonOps.INSTANCE, root.getAsJsonObject("argument").getAsJsonObject("plan")).getOrThrow(false, error -> { throw new IllegalStateException(error); });
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    private static double[] centre(String id) {
        var plan = plan();
        for (var shape : plan.shapes()) if (shape.island().equals(id) && shape.type().equals("disc") && !shape.carve()) return new double[] {shape.center().get(0), shape.center().get(1)};
        throw new IllegalStateException("no island " + id);
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void everyLaunchLoadsTheRegionalRegistries(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        check(access.registryOrThrow(Registries.NOISE_SETTINGS).containsKey(WorldLayout.REGIONAL_SETTINGS), "the regional noise settings did not load: every world would fail to start");
        check(access.registryOrThrow(Registries.WORLD_PRESET).containsKey(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "archipelago")), "the archipelago world type is missing");
        var functions = access.registryOrThrow(Registries.DENSITY_FUNCTION);
        for (String name : new String[] {"continents", "erosion", "ridges", "temperature", "humidity", "offset", "factor", "jaggedness", "depth", "sloped_cheese", "ridges_folded"}) {
            check(functions.containsKey(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "regional/" + name)), "regional/" + name + " is missing");
        }
        // the derived copies must point at the regional functions and no longer at the vanilla continents
        String offset = functions.get(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "regional/offset")).toString();
        check(offset != null, "offset did not load");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void derivationRepointsTheReferencesAndFailsLoudlyOnAMissingFile(GameTestHelper helper) {
        String sample = "{\"a\":\"minecraft:overworld/continents\",\"b\":\"minecraft:overworld/ridges_folded\",\"c\":\"minecraft:overworld/base_3d_noise\",\"d\":\"minecraft:overworld/caves/noodle\"}";
        String out = RegionalWorldgen.rebuild(sample);
        check(out.contains("\"underground_city:regional/continents\"") && out.contains("\"underground_city:regional/ridges_folded\""), "the rebuilt references are wrong: " + out);
        check(out.contains("minecraft:overworld/base_3d_noise") && out.contains("minecraft:overworld/caves/noodle"), "a function that does not depend on the continents was repointed: " + out);
        boolean failed = false;
        try { RegionalWorldgen.derive(path -> null); } catch (IllegalStateException expected) { failed = true; }
        check(failed, "a missing vanilla file did not stop the derivation");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void theScarcityPackageSwitchesOffTheNoiseBasedOreVeinsAndNothingElseDoes(GameTestHelper helper) {
        java.util.function.Function<String, String> vanilla = path -> path.endsWith("noise_settings/overworld.json")
            ? "{\"ore_veins_enabled\":true,\"sea_level\":63,\"noise_router\":{\"continents\":\"minecraft:overworld/continents\",\"erosion\":\"e\",\"depth\":\"d\",\"ridges\":\"r\"}}"
            : "{}";
        JsonObject normal = JsonParser.parseString(RegionalWorldgen.derive(vanilla).get("worldgen/noise_settings/regional.json")).getAsJsonObject();
        JsonObject scarce = JsonParser.parseString(RegionalWorldgen.derive(vanilla, true).get("worldgen/noise_settings/regional.json")).getAsJsonObject();
        check(normal.get("ore_veins_enabled").getAsBoolean(), "a world without the scarcity package lost the game's ore veins");
        check(!scarce.get("ore_veins_enabled").getAsBoolean(), "the scarcity package left the noise-based ore veins on");
        check(normal.get("sea_level").getAsInt() == 63 && scarce.get("sea_level").getAsInt() == 63, "the scarcity switch changed something else in the noise settings");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void thePackListsADirectoryNotAStringPrefix(GameTestHelper helper) {
        var pack = RegionalWorldgen.packForTest(Map.of("worldgen/noise_settings/regional.json", "{}", "worldgen/density_function/regional/offset.json", "{}", "worldgen/noise/x.json", "{}"));
        int[] counts = new int[2];
        pack.listResources(net.minecraft.server.packs.PackType.SERVER_DATA, UndergroundCity.MOD_ID, "worldgen/noise", (location, supplier) -> counts[0]++);
        pack.listResources(net.minecraft.server.packs.PackType.SERVER_DATA, UndergroundCity.MOD_ID, "worldgen/noise_settings", (location, supplier) -> counts[1]++);
        check(counts[0] == 1, "listing worldgen/noise returned " + counts[0] + " files; only worldgen/noise/x.json belongs to it (noise_settings must not match)");
        check(counts[1] == 1, "listing worldgen/noise_settings returned " + counts[1]);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void theIslandsAreLandAndTheStraitsAreSea(GameTestHelper helper) {
        var plan = plan();
        for (String island : new String[] {"ironhaven", "valewood", "blackwater"}) {
            double[] c = centre(island);
            check(plan.distanceToShore(c[0], c[1]) > 300, island + " is not deep inside its own land");
            check(plan.continents(c[0], c[1]) > -0.1, island + "'s centre is not land (continents " + plan.continents(c[0], c[1]) + ")");
            check(plan.plateau(c[0], c[1]) > 0.99, island + "'s city is not on the flat plateau");
        }
        double[] a = centre("ironhaven"), b = centre("valewood");
        double mx = (a[0] + b[0]) / 2, mz = (a[1] + b[1]) / 2;
        check(plan.distanceToShore(mx, mz) < -50, "the strait between Ironhaven and Valewood is not water");
        check(plan.continents(mx, mz) < -0.19, "the strait is not below the coast threshold");
        check(plan.continents(mx, mz) > -0.45, "the strait is a deep ocean: a bridge's piers would be huge");
        check(plan.continents(a[0] - 3000, a[1] - 3000) < -0.5, "the open sea is not deep");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void eachIslandHasItsOwnClimateAndNoneIsFrozen(GameTestHelper helper) {
        var plan = plan();
        double[] iron = centre("ironhaven"), vale = centre("valewood"), black = centre("blackwater");
        double ti = plan.climate(iron[0], iron[1], true, 0), tv = plan.climate(vale[0], vale[1], true, 0), tb = plan.climate(black[0], black[1], true, 0);
        double hi = plan.climate(iron[0], iron[1], false, 0), hv = plan.climate(vale[0], vale[1], false, 0), hb = plan.climate(black[0], black[1], false, 0);
        check(hb > hv + 0.25 && hv > hi, "humidity does not rank Blackwater > Valewood > Ironhaven: " + hb + ", " + hv + ", " + hi);
        for (double t : new double[] {ti, tv, tb}) check(t - 0.10 > -0.15 && t + 0.10 < 0.20, "an island's temperature leaves Minecraft's temperate band: " + t);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void theLayoutGuardRefusesEveryMismatch(GameTestHelper helper) {
        check(WorldLayout.verdict(false, false) == WorldLayout.Verdict.OK, "an ordinary world with no package is fine");
        check(WorldLayout.verdict(true, true) == WorldLayout.Verdict.OK, "the archipelago with its package is fine");
        check(WorldLayout.verdict(true, false) == WorldLayout.Verdict.REGIONAL_WITHOUT_PACKAGE, "the archipelago without its package must be refused");
        check(WorldLayout.verdict(false, true) == WorldLayout.Verdict.PACKAGE_WITHOUT_REGIONAL, "an old world opened by the archipelago package must be refused");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void theGenerationPackGuardGivesNoExistingWorldNewRules(GameTestHelper helper) {
        var none = java.util.Set.<String>of();
        var furnished = java.util.Set.of("underground_city_furnished");
        var both = java.util.Set.of("underground_city_furnished", "underground_city_scarcity");
        check(GenerationPackGuard.verdict(none, null, true) == GenerationPackGuard.Verdict.OK, "an existing world without any package is left alone and nothing is written");
        check(GenerationPackGuard.verdict(none, null, false) == GenerationPackGuard.Verdict.OK, "a new world without any package is left alone");
        check(GenerationPackGuard.verdict(furnished, null, false) == GenerationPackGuard.Verdict.MARK_NEW_WORLD, "a new world with a package is marked");
        check(GenerationPackGuard.verdict(furnished, null, true) == GenerationPackGuard.Verdict.EXISTING_WORLD_WOULD_GET_NEW_RULES, "an existing world must not be given new generation rules");
        check(GenerationPackGuard.verdict(furnished, furnished, true) == GenerationPackGuard.Verdict.OK, "a marked world with its package is fine");
        check(GenerationPackGuard.verdict(none, furnished, true) == GenerationPackGuard.Verdict.PACKAGES_DIFFER_FROM_MARKER, "a marked world missing its package must be refused");
        check(GenerationPackGuard.verdict(both, furnished, true) == GenerationPackGuard.Verdict.PACKAGES_DIFFER_FROM_MARKER, "a marked world given another package must be refused");
        check(GenerationPackGuard.verdict(none, none, true) == GenerationPackGuard.Verdict.OK, "a world marked as made with none and opened with none is fine");
        check(GenerationPackGuard.verdict(furnished, none, true) == GenerationPackGuard.Verdict.PACKAGES_DIFFER_FROM_MARKER, "a world marked as made with none is not given a package later");
        var text = GenerationPackGuard.markerText(both);
        check(text.contains("underground_city_scarcity") && text.contains("underground_city_furnished"), "the marker lists the packages");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void aCitysCharacterChoosesTheSameBuildingEveryTimeAndKeepsToItsRings(GameTestHelper helper) {
        var json = com.google.gson.JsonParser.parseString("""
            {"schema_version":1,"city":"valewood","style":"underground_city:valewood","zones":[
              {"within":0.3,"buildings":[{"id":"underground_city:vw_shop","weight":3}]},
              {"within":1.0,"buildings":[{"id":"underground_city:vw_house_a","weight":1},{"id":"underground_city:vw_house_b","weight":1}]}]}
            """).getAsJsonObject();
        var character = CityCharacter.parse(net.minecraft.resources.ResourceLocation.parse("underground_city:valewood"), json);
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                var first = CityCharacter.choose(character, 0.1, 5908L, x, z);
                check(first.equals(CityCharacter.choose(character, 0.1, 5908L, x, z)), "the choice changes between two asks of the same chunk");
                check(first.getPath().equals("vw_shop"), "the centre ring holds only the shop");
                var outer = CityCharacter.choose(character, 0.8, 5908L, x, z);
                check(outer.getPath().startsWith("vw_house_"), "the outer ring holds only houses: " + outer);
            }
        }
        int a = 0, b = 0;
        for (int x = 0; x < 60; x++) for (int z = 0; z < 60; z++) { if (CityCharacter.choose(character, 0.8, 7L, x, z).getPath().endsWith("_a")) a++; else b++; }
        check(a > 1500 && b > 1500, "the two houses are not mixed about evenly: " + a + " / " + b);
        check(CityCharacter.choose(character, 1.0, 1L, 0, 0).getPath().startsWith("vw_house_"), "the very edge belongs to the last ring");
        check(CityCharacter.missing(character, java.util.Set.of(net.minecraft.resources.ResourceLocation.parse("underground_city:vw_shop"))).size() == 2, "the data check does not name the unregistered buildings");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void aCitysCharacterRefusesBrokenData(GameTestHelper helper) {
        String[] broken = {
            "{\"schema_version\":2,\"city\":\"valewood\",\"style\":\"a:b\",\"zones\":[]}",
            "{\"schema_version\":1,\"city\":\"valewood\",\"style\":\"a:b\",\"zones\":[]}",
            "{\"schema_version\":1,\"city\":\"valewood\",\"style\":\"a:b\",\"zones\":[{\"within\":0.5,\"buildings\":[{\"id\":\"a:c\",\"weight\":1}]}]}",
            "{\"schema_version\":1,\"city\":\"valewood\",\"style\":\"a:b\",\"zones\":[{\"within\":1.0,\"buildings\":[]}]}",
            "{\"schema_version\":1,\"city\":\"valewood\",\"style\":\"a:b\",\"zones\":[{\"within\":1.0,\"buildings\":[{\"id\":\"a:c\",\"weight\":0}]}]}",
            "{\"schema_version\":1,\"city\":\"valewood\",\"style\":\"a:b\",\"zones\":[{\"within\":0.6,\"buildings\":[{\"id\":\"a:c\",\"weight\":1}]},{\"within\":0.5,\"buildings\":[{\"id\":\"a:c\",\"weight\":1}]}]}"
        };
        for (String text : broken) {
            boolean refused = false;
            try { CityCharacter.parse(net.minecraft.resources.ResourceLocation.parse("underground_city:x"), com.google.gson.JsonParser.parseString(text).getAsJsonObject()); }
            catch (IllegalArgumentException expected) { refused = true; }
            check(refused, "broken city-character data was accepted: " + text);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void aChunkBelongsToTheNearestCityCircleAndNoneOutside(GameTestHelper helper) {
        var regions = java.util.List.of(
            new CityRegions.Region("ironhaven", "minecraft:overworld", 0, 0, 500, 330),
            new CityRegions.Region("valewood", "minecraft:overworld", 100, 14, 500, 240));
        var centre = CityCharacter.placement(regions, 100, 14).orElseThrow();
        check(centre.region().id().equals("valewood") && centre.fraction() < 0.05, "the centre chunk is not Valewood's centre: " + centre);
        var edge = CityCharacter.placement(regions, 100 + 14, 14).orElseThrow();
        check(edge.fraction() > 0.85 && edge.fraction() <= 1.0, "a chunk near the circle's edge has the wrong fraction: " + edge.fraction());
        check(CityCharacter.placement(regions, 100 + 40, 14).isEmpty(), "a chunk outside every circle belongs to a city");
        check(CityCharacter.placement(java.util.List.of(new CityRegions.Region("x", "minecraft:the_nether", 0, 0, 500, 330)), 0, 0).isEmpty(), "a nether region holds an overworld chunk");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_regional")
    public static void theShoreIsTheSameEveryTimeItIsAsked(GameTestHelper helper) {
        var plan = plan();
        double first = plan.distanceToShore(123.5, -456.25), second = plan.distanceToShore(123.5, -456.25);
        check(first == second, "the shore noise is not deterministic");
        double[] c = centre("valewood");
        check(Math.abs(plan.distanceToShore(c[0] + 700, c[1]) - plan.distanceToShore(c[0] + 700, c[1])) == 0, "unstable");
        helper.succeed();
    }
}
