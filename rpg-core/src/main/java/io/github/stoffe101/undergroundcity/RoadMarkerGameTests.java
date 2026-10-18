package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
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

    @GameTest(template = "empty")
    public static void signsSayWhereTheRoadLeadsAndHowLong(GameTestHelper helper) {
        check(RoadMarkers.minutes(1100) == 4 && RoadMarkers.minutes(40) == 1, "Minutes on foot at 4.3 blocks a second: " + RoadMarkers.minutes(1100));
        check(RoadMarkers.face("BLACKWATER", 1104).equals(List.of("TO BLACKWATER", "1,100 m", "4 min on foot", "")), "A face reads: " + RoadMarkers.face("BLACKWATER", 1104));
        check(java.util.Arrays.equals(RoadMarkers.ends("ironhaven_valewood"), new String[]{"IRONHAVEN", "VALEWOOD"}), "A road id names its two cities");
        check(RoadMarkers.ends("a_b_c") == null && RoadMarkers.ends("alone") == null, "A road id that is not two cities gets no signs");
        // the front of a post faces back at the traveller: walking east (+x) a face looks west, which is rotation 4 (east 12)
        check(RoadMarkers.rotation(1, 0) == 4 && RoadMarkers.rotation(-1, 0) == 12 && RoadMarkers.rotation(0, -1) == 0 && RoadMarkers.rotation(0, 1) == 8, "Rotation of a face for each direction of travel");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPostIsBuiltBesideTheRoadOnceAndItsSignIsWritten(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        int ox = origin.getX(), oz = origin.getZ();
        // a road running east through the test area, 12 blocks south of its origin
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{ox - 50, ox + 50}, new double[]{oz + 12, oz + 12}, 7, List.of());
        var spot = RoadMarkers.plan(level, plan, 0, 50).orElseThrow(() -> new IllegalStateException("No spot found beside the road"));
        check(spot.base().getX() == ox && spot.base().getZ() == oz + 12 + 6, "The post stands on the right-hand verge, clear of the road: " + spot.base());
        check(spot.rotation() == 4, "The front faces back at an eastbound traveller: " + spot.rotation());
        check(spot.front().get(0).equals("TO VALEWOOD") && spot.back().get(0).equals("TO IRONHAVEN"), "Front and back name the two ends: " + spot.front() + spot.back());
        check(spot.front().get(1).equals("50 m") && spot.back().get(1).equals("50 m"), "Distances to each end: " + spot.front() + spot.back());
        RoadMarkers.State state = new RoadMarkers.State();
        check(RoadMarkers.serve(level, plan, List.of(50.0), ox + 0.5, oz + 12.5, state) == 1, "A traveller close by gets the post built");
        BlockPos base = spot.base();
        check(level.getBlockState(base).is(Blocks.STONE_BRICKS) && level.getBlockState(base.above()).is(Blocks.STONE_BRICK_WALL), "Stone post");
        var sign = level.getBlockState(base.above(2));
        check(sign.is(Blocks.OAK_SIGN) && sign.getValue(StandingSignBlock.ROTATION) == 4, "A standing sign on top, rotated: " + sign);
        check(level.getBlockEntity(base.above(2)) instanceof SignBlockEntity entity
            && entity.getFrontText().getMessage(0, false).getString().equals("TO VALEWOOD")
            && entity.getBackText().getMessage(0, false).getString().equals("TO IRONHAVEN")
            && entity.getFrontText().getMessage(2, false).getString().equals("1 min on foot") && entity.isWaxed(), "Both faces are written and waxed");
        check(RoadMarkers.serve(level, plan, List.of(50.0), ox + 0.5, oz + 12.5, state) == 0 && state.done.contains("ironhaven_valewood#0"), "A post is built once");
        check(state.where.get("ironhaven_valewood#0") == base.asLong(), "The world remembers where the post stands");
        var reloaded = RoadMarkers.State.load(state.save(new net.minecraft.nbt.CompoundTag()));
        check(reloaded.done.equals(state.done) && reloaded.where.equals(state.where), "Placed posts survive a save and load");
        // a traveller far away builds nothing
        CorridorPlan far = new CorridorPlan("ironhaven_valewood", new double[]{ox - 50, ox + 50}, new double[]{oz + 12, oz + 12}, 7, List.of());
        check(RoadMarkers.serve(level, far, List.of(50.0), ox + 500.0, oz + 500.0, new RoadMarkers.State()) == 0, "Nothing is built far from the traveller");
        level.removeBlock(base.above(2), false); level.removeBlock(base.above(), false); level.removeBlock(base, false);
        helper.succeed();
    }
}
