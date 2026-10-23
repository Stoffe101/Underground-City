package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** Waymarkers (D-078): the planning rules as pure functions, and one real post built beside a synthetic road in a test world. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoadMarkerGameTests {
    private RoadMarkerGameTests() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @GameTest(template = "empty")
    public static void markersAreSpacedOnOpenRoadAndNeverNearACity(GameTestHelper helper) {
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{0, 1000}, new double[]{0, 0}, 7,
            List.of(new CorridorPlan.City(0, 0, 100), new CorridorPlan.City(1000, 0, 100)));
        List<Double> at = RoadMarkers.positions(plan, 250, 90);
        check(at.size() == 2 && Math.abs(at.get(1) - at.get(0) - 250) < 1e-9, "Two posts 250 blocks apart on a 1000-block road between two cities: " + at);
        for (double s : at) check(plan.openAt(s) && s - RoadMarkers.firstOpen(plan) >= 90 && RoadMarkers.lastOpen(plan) - s >= 90, "A post must stand clear of the city circles: " + s);
        check(RoadMarkers.positions(new CorridorPlan("a_b", new double[]{0, 150}, new double[]{0, 0}, 7, List.of()), 250, 90).isEmpty(), "A short road gets no post");
        helper.succeed();
    }

    /** Where a road meets its cities (D-137): one end at each city circle, pointing on into the city along the road's line; a road that starts in open country has no end there. */
    @GameTest(template = "empty")
    public static void aRoadKnowsWhereItMeetsEachCityAndWhichWayIsIn(GameTestHelper helper) {
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{0, 1000}, new double[]{0, 0}, 7,
            List.of(new CorridorPlan.City(0, 0, 100), new CorridorPlan.City(1000, 0, 100)));
        CorridorPlan.End[] ends = plan.ends();
        check(ends.length == 2, "Two ends on a road between two cities: " + ends.length);
        check(Math.abs(ends[0].x() - 101) <= 1.5 && ends[0].dirX() < -0.99, "The first end is at Ironhaven's circle and points west into it: " + ends[0]);
        check(Math.abs(ends[1].x() - 899) <= 1.5 && ends[1].dirX() > 0.99, "The second end is at Valewood's circle and points east into it: " + ends[1]);
        CorridorPlan oneCity = new CorridorPlan("a_b", new double[]{0, 1000}, new double[]{0, 0}, 7, List.of(new CorridorPlan.City(1000, 0, 100)));
        check(oneCity.ends().length == 1 && oneCity.ends()[0].dirX() > 0.99, "A road that starts in open country meets one city: " + oneCity.ends().length);
        helper.succeed();
    }

    /**
     * A ramp never makes the server generate a chunk (journey runs stalled 4 to 43 s as each was built): it covers every chunk the build touches, and an end whose chunks are not
     * loaded is not ready; once they are, it is.
     */
    @GameTest(template = "empty")
    public static void aRampWaitsForItsChunksRatherThanGeneratingThem(GameTestHelper helper) {
        var level = helper.getLevel();
        CorridorPlan.End far = new CorridorPlan.End(1_000_003.5, 2_000_001.5, 0, 0.6, 0.8);
        var chunks = RoadApproaches.chunks(far);
        for (int k = 0; k <= RoadApproaches.FAR; k++) for (int u = -RoadApproaches.HALF; u <= RoadApproaches.HALF; u++) {
            var column = new net.minecraft.world.level.ChunkPos((int) Math.floor(far.x() + far.dirX() * k - far.dirZ() * u) >> 4, (int) Math.floor(far.z() + far.dirZ() * k + far.dirX() * u) >> 4);
            check(chunks.contains(column), "The ramp's chunks include the one under column " + k + ", " + u + ": " + column);
        }
        // a ramp reads up to FAR (112) blocks in for the city's ground: a diagonal one's band crosses some sixteen chunks
        check(chunks.size() <= 20, "A diagonal ramp's reach touches a bounded number of chunks: " + chunks.size());
        check(chunks.containsAll(RoadApproaches.rampChunks(far)) && RoadApproaches.rampChunks(far).size() < chunks.size(), "The ramp's own chunks are a part of its reach's");
        check(!RoadApproaches.ready(level, far), "An end a million blocks out is not loaded and not ready");
        check(chunks.stream().allMatch(c -> level.getChunkSource().getChunkNow(c.x, c.z) == null), "Asking whether it is ready loaded nothing");
        BlockPos here = helper.absolutePos(BlockPos.ZERO);
        CorridorPlan.End near = new CorridorPlan.End(here.getX() + 0.5, here.getZ() + 0.5, 0, 1, 0);
        for (var c : RoadApproaches.chunks(near)) level.getChunk(c.x, c.z);
        check(RoadApproaches.ready(level, near), "Once its chunks are loaded, an end is ready");
        helper.succeed();
    }

    /**
     * A ramp covers every block column of its band, with no holes: stepping a slanting line by whole blocks skipped columns, and journey run 55's walker fell into the five-block shaft one
     * left on the centre line of the Ironhaven end (whose line runs about six degrees off the x axis, as here).
     */
    @GameTest(template = "empty")
    public static void aRampHasNoHolesWhereTheRoadMeetsTheCityAtASlant(GameTestHelper helper) {
        double len = Math.hypot(0.995, 0.0998);
        for (CorridorPlan.End end : new CorridorPlan.End[]{new CorridorPlan.End(334.2, 53.7, 0, -0.995 / len, -0.0998 / len), new CorridorPlan.End(10.3, -7.9, 0, 0.6, 0.8),
                new CorridorPlan.End(0.5, 0.5, 0, 1, 0)}) {
            var cells = RoadApproaches.band(end, 0, 64, 3.5);
            java.util.Set<Long> at = new java.util.HashSet<>();
            for (var cell : cells) check(at.add(BlockPos.asLong(cell.x(), 0, cell.z())), "A column is laid once: " + cell);
            check(Math.abs(cells.size() - 64 * 7) <= 24, "About the band's area (64 by 7), not fewer: " + cells.size() + " for " + end);
            for (var cell : cells) {
                if (cell.k() < 1 || cell.k() >= 63 || Math.abs(cell.u()) > 2.5) continue;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    check(at.contains(BlockPos.asLong(cell.x() + d[0], 0, cell.z() + d[1])), "No hole beside " + cell.x() + ", " + cell.z() + " (" + (cell.x() + d[0]) + ", " + (cell.z() + d[1]) + ") for " + end);
                }
            }
        }
        helper.succeed();
    }

    /** The travel step points at the road out (a journey walked 720 blocks from the Row to a road's end with nothing saying where it was). */
    @GameTest(template = "empty")
    public static void theTravelStepPointsAtTheRoadOutOfTheCity(GameTestHelper helper) {
        List<CorridorPlan.City> cities = List.of(new CorridorPlan.City(0, 0, 100), new CorridorPlan.City(1000, 0, 100), new CorridorPlan.City(0, 1000, 100));
        List<CorridorPlan> roads = List.of(new CorridorPlan("ironhaven_valewood", new double[]{0, 1000}, new double[]{0, 0}, 7, cities.subList(0, 2)),
            new CorridorPlan("ironhaven_blackwater", new double[]{0, 0}, new double[]{0, 1000}, 7, List.of(cities.get(0), cities.get(2))));
        Journey.RoadOut home = Journey.roadOut(roads, "ironhaven", "ironhaven", -50, 20);
        check(home != null && home.to().equals("blackwater") && Math.abs(home.z() - 101) <= 1.5, "In Ironhaven, the nearer road out (to Blackwater, its end at z 101): " + home);
        Journey.RoadOut east = Journey.roadOut(roads, "ironhaven", "ironhaven", 60, -10);
        check(east != null && east.to().equals("valewood") && Math.abs(east.x() - 101) <= 1.5, "Nearer the east edge, the road to Valewood: " + east);
        check(Journey.roadOut(roads, "valewood", "ironhaven", 900, 0) == null, "In a rival city already, the step is a terminal there, not a road");
        Journey.RoadOut onTheRoad = Journey.roadOut(roads, "", "ironhaven", 500, 10);
        check(onTheRoad != null && onTheRoad.to().equals("valewood") && Math.abs(onTheRoad.x() - 899) <= 1.5, "On the road, the end ahead where it enters Valewood: " + onTheRoad);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void signsSayWhereTheRoadLeadsAndHowLong(GameTestHelper helper) {
        check(RoadMarkers.minutes(1100) == 4 && RoadMarkers.minutes(40) == 1, "Minutes on foot at 4.3 blocks a second: " + RoadMarkers.minutes(1100));
        check(RoadMarkers.face("BLACKWATER", 1104).equals(List.of("TO BLACKWATER", "1,100 m", "4 min on foot", "")), "A face reads: " + RoadMarkers.face("BLACKWATER", 1104));
        check(java.util.Arrays.equals(RoadMarkers.ends("ironhaven_valewood"), new String[]{"IRONHAVEN", "VALEWOOD"}), "A road id names its two cities");
        check(RoadMarkers.ends("a_b_c") == null && RoadMarkers.ends("alone") == null, "A road id that is not two cities gets no signs");
        // the nearest compass direction of travel (x is east, z is south)
        check(RoadMarkers.direction(1, 0.2) == Direction.EAST && RoadMarkers.direction(-1, 0.2) == Direction.WEST
            && RoadMarkers.direction(0.1, -1) == Direction.NORTH && RoadMarkers.direction(0.1, 1) == Direction.SOUTH, "Direction of travel");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_markers", timeoutTicks = 600)
    public static void aPostIsBuiltBesideTheRoadOnceAndItsSignIsWritten(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        // In the sky on a platform of its own: the natural terrain at a test cell depends on the mods loaded and on how many tests came before, and a post cannot stand
        // in a lake or on a tree. (The first version of this test stood on the ground and failed on hosted runners whose cells fell on water.)
        int y = level.getMaxBuildHeight() - 120, ox = arena.getX(), oz = arena.getZ() + 260;
        BlockPos lo = new BlockPos(ox - 80, y - 2, oz - 60), hi = new BlockPos(ox + 80, y + 6, oz + 80);
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        Runnable cleanup = () -> {
            for (BlockPos at : BlockPos.betweenClosed(lo, hi)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
        };
        UndergroundGameTests.whenEntitiesTick(helper, level, List.of(lo, hi, new BlockPos(lo.getX(), y, hi.getZ()), new BlockPos(hi.getX(), y, lo.getZ())), 40, () -> {
            try {
                for (BlockPos at : BlockPos.betweenClosed(new BlockPos(ox - 70, y, oz - 5), new BlockPos(ox + 70, y, oz + 40))) level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                postIsBuiltOnce(level, ox, oz);
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }

    private static void postIsBuiltOnce(net.minecraft.server.level.ServerLevel level, int ox, int oz) {
        // a road running east through the platform, 12 blocks south of its origin
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{ox - 50, ox + 50}, new double[]{oz + 12, oz + 12}, 7, List.of());
        var spot = RoadMarkers.plan(level, plan, 0, 50).orElseThrow(() -> new IllegalStateException("No spot found beside the road"));
        check(spot.base().getX() == ox && spot.base().getZ() == oz + 12 + 6, "The post stands on the right-hand verge, clear of the road: " + spot.base());
        check(spot.forward() == Direction.EAST, "The road runs east: " + spot.forward());
        check(spot.front().get(0).equals("TO VALEWOOD") && spot.back().get(0).equals("TO IRONHAVEN"), "Front and back name the two ends: " + spot.front() + spot.back());
        check(spot.front().get(1).equals("50 m") && spot.back().get(1).equals("50 m"), "Distances to each end: " + spot.front() + spot.back());
        PlacedSites state = new PlacedSites();
        check(RoadMarkers.serve(level, plan, List.of(50.0), ox + 0.5, oz + 12.5, state) == 1, "A traveller close by gets the post built");
        BlockPos base = spot.base();
        check(level.getBlockState(base).is(Blocks.STONE_BRICKS) && level.getBlockState(base.above()).is(Blocks.STONE_BRICKS) && level.getBlockState(base.above(2)).is(Blocks.LANTERN), "A stone pillar with a lantern on top");
        // heading east, the sign for the far city hangs on the pillar's west face looking west, the other on its east face
        var front = level.getBlockState(spot.frontSign()); var back = level.getBlockState(spot.backSign());
        check(front.is(Blocks.OAK_WALL_SIGN) && front.getValue(WallSignBlock.FACING) == Direction.WEST && spot.frontSign().equals(base.above().west()), "The front sign hangs on the west face: " + front);
        check(back.is(Blocks.OAK_WALL_SIGN) && back.getValue(WallSignBlock.FACING) == Direction.EAST && spot.backSign().equals(base.above().east()), "The back sign hangs on the east face: " + back);
        check(level.getBlockEntity(spot.frontSign()) instanceof SignBlockEntity f && f.getFrontText().getMessage(0, false).getString().equals("TO VALEWOOD")
            && f.getFrontText().getMessage(1, false).getString().equals("50 m") && f.getFrontText().getMessage(2, false).getString().equals("1 min on foot") && f.isWaxed(), "The front sign is written and waxed");
        check(level.getBlockEntity(spot.backSign()) instanceof SignBlockEntity b2 && b2.getFrontText().getMessage(0, false).getString().equals("TO IRONHAVEN") && b2.isWaxed(), "The back sign is written and waxed");
        check(RoadMarkers.serve(level, plan, List.of(50.0), ox + 0.5, oz + 12.5, state) == 0 && state.done.contains("ironhaven_valewood#0"), "A post is built once");
        check(state.where.get("ironhaven_valewood#0") == base.asLong(), "The world remembers where the post stands");
        var reloaded = PlacedSites.load(state.save(new net.minecraft.nbt.CompoundTag()));
        check(reloaded.done.equals(state.done) && reloaded.where.equals(state.where), "Placed posts survive a save and load");
        // a traveller far away builds nothing
        CorridorPlan far = new CorridorPlan("ironhaven_valewood", new double[]{ox - 50, ox + 50}, new double[]{oz + 12, oz + 12}, 7, List.of());
        check(RoadMarkers.serve(level, far, List.of(50.0), ox + 500.0, oz + 500.0, new PlacedSites()) == 0, "Nothing is built far from the traveller");
    }
}
