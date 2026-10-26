package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;

/**
 * The islands generated ahead of play (D-194): what {@link ChunkPregen} plans to generate and in which order, and what it refuses. The generation itself is measured through a real client
 * (pregen runs); nothing here generates a chunk, as a test that starts a plan stops it in the same tick, before a ticket is taken.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PregenGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    @GameTest(template = "empty", batch = "uc_pregen")
    public static void aCityIsPlannedFromItsCentreOutwardAndOnlyItsCircle(GameTestHelper helper) {
        CityRegions.Region vale = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals("valewood")).findFirst().orElseThrow(() -> new GameTestAssertException("no Valewood region"));
        List<ChunkPos> plan = ChunkPregen.chunks(helper.getLevel(), "valewood", 0);
        check(!plan.isEmpty(), "Valewood has no chunks to generate");
        check(plan.get(0).x == vale.centerChunkX() && plan.get(0).z == vale.centerChunkZ(), "the first chunk is not Valewood's centre but " + plan.get(0));
        int reach = (Math.max(vale.radius(), vale.cityRadius()) + 15) / 16;
        long last = -1;
        for (ChunkPos c : plan) {
            long d = (long) (c.x - vale.centerChunkX()) * (c.x - vale.centerChunkX()) + (long) (c.z - vale.centerChunkZ()) * (c.z - vale.centerChunkZ());
            check(d <= (long) reach * reach, c + " lies outside Valewood's circle");
            check(d >= last, "the plan goes back towards the centre at " + c);
            last = d;
        }
        check(new HashSet<>(plan).size() == plan.size(), "a chunk is planned twice");
        check(ChunkPregen.chunks(helper.getLevel(), "valewood", 64).size() > plan.size(), "a margin adds no chunks");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_pregen")
    public static void everyCityIsPlannedOnceAndAWorldWithoutIslandsGetsItsCities(GameTestHelper helper) {
        List<ChunkPos> all = ChunkPregen.chunks(helper.getLevel(), "all", 32);
        int each = 0;
        for (CityRegions.Region r : CityRegions.INSTANCE.all()) each += ChunkPregen.chunks(helper.getLevel(), r.id(), 32).size();
        check(new HashSet<>(all).size() == all.size(), "a chunk of two overlapping cities is planned twice");
        check(all.size() <= each && !all.isEmpty(), "every city together is " + all.size() + " chunks, the cities one by one " + each);
        // the test world has no island plan (it is not the archipelago): the islands are then the cities
        check(IslandField.of(helper.getLevel()).isEmpty(), "the GameTest world unexpectedly has an island plan");
        check(ChunkPregen.chunks(helper.getLevel(), "islands", 32).equals(all), "without an island plan the islands are not the cities");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_pregen")
    public static void anUnknownScopeIsRefusedAndAStopLeavesNothingRunning(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ChunkPregen.Store store = ChunkPregen.store(server);
        boolean wasRunning = store.running;
        String said = ChunkPregen.start(server, "atlantis", 32);
        check(said.startsWith("Unknown scope"), "an unknown scope was not refused: " + said);
        check(store.running == wasRunning, "a refused start changed the saved state");
        // a start and a stop in the same tick: the plan is saved and stopped before any ticket is taken
        check(ChunkPregen.start(server, "valewood", 0).startsWith("Generating"), "a city's start was refused");
        check(store.running && store.total > 0 && store.next == 0, "the started plan was not saved");
        check(ChunkPregen.stop(server).startsWith("Stopped"), "the stop was refused");
        check(!store.running, "the generation still runs after a stop");
        check(ChunkPregen.status(server).contains("stopped"), "the status does not say it stopped: " + ChunkPregen.status(server));
        helper.succeed();
    }
}
