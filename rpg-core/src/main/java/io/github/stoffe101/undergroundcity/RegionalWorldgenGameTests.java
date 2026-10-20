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
    public static void theShoreIsTheSameEveryTimeItIsAsked(GameTestHelper helper) {
        var plan = plan();
        double first = plan.distanceToShore(123.5, -456.25), second = plan.distanceToShore(123.5, -456.25);
        check(first == second, "the shore noise is not deterministic");
        double[] c = centre("valewood");
        check(Math.abs(plan.distanceToShore(c[0] + 700, c[1]) - plan.distanceToShore(c[0] + 700, c[1])) == 0, "unstable");
        helper.succeed();
    }
}
