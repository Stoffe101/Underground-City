package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.IntBinaryOperator;

/** Wayside shelters (D-079): spacing, the warden's answers and the site search as pure functions, and one real shelter built beside a synthetic road. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WaysideShelterGameTests {
    private WaysideShelterGameTests() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    /** A road between two cities {@code open} + 200 blocks apart (circles of radius 100), so that {@code open} blocks of it are open. */
    private static CorridorPlan road(int open) {
        return new CorridorPlan("ironhaven_valewood", new double[]{0, open + 200}, new double[]{0, 0}, 7,
            List.of(new CorridorPlan.City(0, 0, 100), new CorridorPlan.City(open + 200, 0, 100)));
    }

    /** Ground described by two functions. */
    private record Land(IntBinaryOperator height, BiPredicate<Integer, Integer> water) implements DistrictSites.Ground {
        @Override public int height(int x, int z) { return height.applyAsInt(x, z); }
        @Override public boolean wet(int x, int z) { return water.test(x, z); }
        @Override public boolean reserved(int x, int z) { return false; }
    }

    @GameTest(template = "empty")
    public static void sheltersAreSpreadEvenlyOnOpenRoadAndNeverNearACity(GameTestHelper helper) {
        for (int open = 500; open <= 1800; open += 50) {
            CorridorPlan plan = road(open);
            List<Double> at = WaysideShelters.positions(plan, 450, 150);
            check(!at.isEmpty(), "A road with " + open + " blocks of open road gets a shelter");
            double previous = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan);
            for (double s : at) {
                check(s - previous >= 240 && s - previous <= 460, "Open road " + open + ": " + (s - previous) + " blocks between shelters, which must stay within the 450 the design allows: " + at);
                check(s - RoadMarkers.firstOpen(plan) >= 150 && last - s >= 150, "A shelter stands clear of the city circles: " + s);
                previous = s;
            }
            check(last - previous >= 240 && last - previous <= 460, "Open road " + open + ": " + (last - previous) + " blocks from the last shelter to the city");
        }
        check(WaysideShelters.positions(road(250), 450, 150).isEmpty(), "A short road gets no shelter");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theWardenAnswersFromTheRealRoadAndTheRealLaw(GameTestHelper helper) {
        List<CorridorPlan> plans = List.of(road(800));
        String where = WaysideShelters.where(plans, 500, 20);
        check(where.equals("This is the road between Ironhaven and Valewood. Ironhaven lies west, about 400 blocks (1.5 min on foot); Valewood lies east, about 400 blocks (1.5 min on foot)."), "Where: " + where);
        check(WaysideShelters.where(plans, 500, 5000).equals("I cannot place this spot on any road I know."), "Far from every road");
        check(WaysideShelters.where(plans, 140, 20).contains("about 40 blocks (under a minute on foot)") && WaysideShelters.where(List.of(road(2000)), 1000, 20).contains("(3.5 min on foot)"), "Half minutes and short walks: " + WaysideShelters.where(plans, 140, 20));
        String law = WaysideShelters.roadLaw(plans, 500, 20);
        String ironhaven = CityLaws.INSTANCE.find("ironhaven").summary(), valewood = CityLaws.INSTANCE.find("valewood").summary();
        check(law.equals("Ironhaven: " + ironhaven + " Valewood: " + valewood), "Law: " + law);
        check(law.length() <= 330, "Both summaries fit the dialogue panel's seven lines: " + law.length());
        var choices = Dialogue.choices(new DistrictCatalog.Npc("warden", "Road Warden", "cartographer", "", List.of("x")), null);
        check(choices.stream().map(Dialogue.Choice::id).toList().equals(List.of("where", "posts", "roadlaw", "rumour", "bye")), "The warden's questions: " + choices);
        check(Dialogue.subtitle(new DistrictCatalog.Npc("warden", "Road Warden", "cartographer", "", List.of())).equals("Road warden"), "Subtitle");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theSiteSearchPrefersLevelDryGroundBesideTheRoadAndGivesUpWithoutIt(GameTestHelper helper) {
        for (CorridorPlan plan : List.of(new CorridorPlan("a_b", new double[]{0, 1000}, new double[]{0, 0}, 7, List.of()),
                                         new CorridorPlan("c_d", new double[]{0, 700}, new double[]{0, 700}, 7, List.of()))) {
            double ideal = 500;
            double[] at = plan.pointAt(ideal);
            // flat and dry: the best lot is on the left of the direction of travel at the ideal spot, and every one of them is clear of the road
            List<WaysideShelters.Site> flat = WaysideShelters.sites(new Land((x, z) -> 64, (x, z) -> false), plan, ideal, 11, 9);
            check(!flat.isEmpty() && flat.get(0).shift() == 0 && flat.get(0).side() == -1 && flat.get(0).relief() == 0, plan.id() + ": best site on flat ground " + (flat.isEmpty() ? "none" : flat.get(0)));
            for (WaysideShelters.Site site : flat) {
                for (int dx : new int[]{0, 10}) for (int dz : new int[]{0, 8}) {
                    double distance = plan.project(site.origin().getX() + dx, site.origin().getZ() + dz).distance();
                    check(distance > plan.reach() + 1, plan.id() + ": a corner of a lot (" + site + ") is " + distance + " from the road, which reaches " + plan.reach());
                }
                check(site.origin().getY() == 64, "The lot stands at the ground's level");
            }
            // water on the left bank, round the best lot: the right-hand side is taken at the same spot rather than a slide along the road
            WaysideShelters.Site leftBest = flat.get(0);
            List<WaysideShelters.Site> lake = WaysideShelters.sites(new Land((x, z) -> 64,
                (x, z) -> Math.hypot(x - (leftBest.origin().getX() + 5.5), z - (leftBest.origin().getZ() + 4.5)) < 16), plan, ideal, 11, 9);
            check(!lake.isEmpty() && lake.get(0).side() == 1 && lake.get(0).shift() == 0, plan.id() + ": with a lake on the left the first choice is " + (lake.isEmpty() ? "none" : lake.get(0)));
            // a step in the ground of six blocks right under the ideal spot: the lot slides along the road to level ground
            double stepAt = plan.id().equals("a_b") ? at[0] : at[0] + at[1];
            List<WaysideShelters.Site> step = WaysideShelters.sites(new Land((x, z) -> (plan.id().equals("a_b") ? x : x + z) < stepAt ? 60 : 66, (x, z) -> false), plan, ideal, 11, 9);
            check(!step.isEmpty() && step.get(0).relief() == 0 && step.get(0).shift() > 0, plan.id() + ": a lot over a step slides to level ground " + (step.isEmpty() ? "none" : step.get(0)));
            // water everywhere, and ground too steep everywhere: nothing
            check(WaysideShelters.sites(new Land((x, z) -> 64, (x, z) -> true), plan, ideal, 11, 9).isEmpty(), plan.id() + ": no shelter in a lake");
            check(WaysideShelters.sites(new Land((x, z) -> (x / 3 + z / 3) % 2 == 0 ? 60 : 70, (x, z) -> false), plan, ideal, 11, 9).isEmpty(), plan.id() + ": no shelter on broken ground");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_shelter", timeoutTicks = 800)
    public static void aPavilionIsBuiltBesideTheRoadOnceWithItsWardenAndTheWorldRemembers(GameTestHelper helper) {
        buildOneKind(helper, 0);
    }

    @GameTest(template = "empty", batch = "uc_shelter", timeoutTicks = 800)
    public static void aWellIsBuiltBesideTheRoadOnceWithItsWardenAndTheWorldRemembers(GameTestHelper helper) {
        buildOneKind(helper, 1);
    }

    @GameTest(template = "empty")
    public static void everyKindOfStopIsChosenByItsKeyAndBothKindsAppearOnOneRoad(GameTestHelper helper) {
        check(WaysideShelters.KINDS.size() >= 2 && WaysideShelters.KINDS.stream().allMatch(id -> DistrictCatalog.INSTANCE.find(id) != null), "The kinds exist: " + WaysideShelters.KINDS);
        check(WaysideShelters.kindOf("ironhaven_valewood", 0).equals(WaysideShelters.kindOf("ironhaven_valewood", 0)), "The same spot is always the same kind");
        for (String road : List.of("ironhaven_valewood", "ironhaven_blackwater", "valewood_blackwater")) {
            java.util.Set<String> kinds = new java.util.HashSet<>();
            for (int i = 0; i < 2; i++) kinds.add(WaysideShelters.kindOf(road, i));
            check(kinds.size() == 2, road + ": two neighbouring stops on a road are different kinds, so a road has variety: " + kinds);
        }
        // the lots have one size, so the site search does not care which kind it is placing
        var first = DistrictCatalog.INSTANCE.find(WaysideShelters.KINDS.get(0));
        for (String id : WaysideShelters.KINDS) {
            var kind = DistrictCatalog.INSTANCE.find(id);
            check(kind.min().equals(first.min()) && kind.max().equals(first.max()) && kind.repeatable() && kind.npcOps().size() == 1 && kind.npcOps().get(0).npc().role().equals("warden"),
                id + " is a repeatable lot of the same size with one warden");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theOperatorCommandListsTheStopsAndRefusesAnOrdinaryPlayer(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        // a console-level source runs it (it answers with the number of lines: none where the test world has no generated roads); a source without permission is refused
        dispatcher.execute("uc_shelters list", server.createCommandSourceStack());
        boolean refused = false;
        try { dispatcher.execute("uc_shelters list", server.createCommandSourceStack().withPermission(0)); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { refused = true; }
        check(refused, "A source without operator permission cannot list the stops");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_shelter", timeoutTicks = 600)
    public static void aFrozenSeaIsNotGroundForAShelter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        // a sheet of ice over three blocks of water over stone, and beside it a plain stone floor, in the sky: the first real shelter went up on a frozen sea in a real world,
        // because the ground scan steps over water but stops on ice
        int y = level.getMaxBuildHeight() - 100, ox = arena.getX(), oz = arena.getZ() + 400;
        BlockPos lo = new BlockPos(ox - 30, y - 8, oz - 20), hi = new BlockPos(ox + 30, y + 10, oz + 20);
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        Runnable cleanup = () -> {
            for (BlockPos at : BlockPos.betweenClosed(lo, hi)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
        };
        UndergroundGameTests.whenEntitiesTick(helper, level, List.of(lo, hi, new BlockPos(ox, y, oz)), 40, () -> {
            try {
                for (int dx = -25; dx <= 25; dx++) for (int dz = -15; dz <= 15; dz++) {
                    BlockPos column = new BlockPos(ox + dx, y, oz + dz);
                    if (dx < 0) {
                        level.setBlock(column.below(3), Blocks.STONE.defaultBlockState(), 2);
                        for (int d = 1; d <= 2; d++) level.setBlock(column.below(3 - d), Blocks.WATER.defaultBlockState(), 2);
                        level.setBlock(column, Blocks.ICE.defaultBlockState(), 2);
                    } else {
                        level.setBlock(column, Blocks.STONE.defaultBlockState(), 2);
                    }
                }
                var ground = WaysideShelters.ground(level, List.of());
                check(ground.wet(ox - 10, oz) && ground.wet(ox - 24, oz + 10), "A frozen sea is wet, though the ice is solid");
                // the waymarkers have the same rule: a road along the frozen sea gets no post on its verge, one along the stone floor does
                CorridorPlan onIce = new CorridorPlan("a_b", new double[]{ox - 25, ox - 3}, new double[]{oz - 12, oz - 12}, 7, List.of());
                check(RoadMarkers.plan(level, onIce, 0, 11).isEmpty(), "No waymarker on ice");
                CorridorPlan onStone = new CorridorPlan("a_b", new double[]{ox + 3, ox + 25}, new double[]{oz - 12, oz - 12}, 7, List.of());
                check(RoadMarkers.plan(level, onStone, 0, 11).isPresent(), "A waymarker on a stone floor");
                check(!ground.wet(ox + 10, oz) && !ground.wet(ox + 24, oz + 10), "A stone floor is not wet");
                // open water too: the surface scan steps over it onto the bed
                for (int dx = 1; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) level.setBlock(new BlockPos(ox + 20 + dx, y + 1, oz + dz), Blocks.WATER.defaultBlockState(), 2);
                check(ground.wet(ox + 21, oz), "Open water over a bed is wet");
            } finally {
                cleanup.run();
            }
            helper.succeed();
        });
    }

    /** The test world's cells vary, so the road ids are chosen here to land on the kind under test. */
    private static String roadOfKind(int kind) {
        for (int n = 0; ; n++) {
            String road = "test" + n + "_road";
            if (WaysideShelters.kindOf(road, 0).equals(WaysideShelters.KINDS.get(kind))) return road;
        }
    }

    // The two build tests run at the same time (one batch), and both need the city circles and checkpoint zones out of the way. The first to start keeps the real lists, the last to finish puts them back;
    // a test that took its own copy would copy the other's empty list and leave the empty one behind for every later test.
    private static int emptied;
    private static List<CityRegions.Region> realCities = List.of();
    private static List<CheckpointCatalog.Checkpoint> realZones = List.of();

    private static synchronized void emptyTheWorldData() {
        if (emptied++ == 0) {
            realCities = CityRegions.INSTANCE.all();
            realZones = CheckpointCatalog.INSTANCE.all();
        }
        CityRegions.INSTANCE.replaceForTest(List.of());
        CheckpointCatalog.INSTANCE.replaceForTest(List.of());
    }

    private static synchronized void restoreTheWorldData() {
        if (--emptied == 0) {
            CityRegions.INSTANCE.replaceForTest(realCities);
            CheckpointCatalog.INSTANCE.replaceForTest(realZones);
        }
    }

    private static void buildOneKind(GameTestHelper helper, int kind) {
        ServerLevel level = helper.getLevel();
        String road = roadOfKind(kind);
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(WaysideShelters.KINDS.get(kind));
        check(district != null && district.repeatable() && district.npcOps().size() == 1 && district.npcOps().get(0).npc().role().equals("warden"), "The stop is a repeatable district with one warden");
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        // In the sky, as the other district tests build: a stone platform with a straight road along its middle, away from every other test.
        int y = level.getMaxBuildHeight() - 100, ox = arena.getX(), oz = arena.getZ() + 160 + 120 * kind; // each kind has its own stretch of sky: the tests of one batch run at the same time
        BlockPos lo = new BlockPos(ox - 70, y - 8, oz - 40), hi = new BlockPos(ox + 70, y + 12, oz + 40);
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        emptyTheWorldData();
        DistrictBuilder.forgetForTest(level, district.id());
        CorridorPlan plan = new CorridorPlan(road, new double[]{ox - 60, ox + 60}, new double[]{oz, oz}, 7, List.of());
        List<CorridorPlan> plans = List.of(plan);
        Runnable cleanup = () -> {
            DistrictBuilder.forgetForTest(level, district.id());
            for (BlockPos at : BlockPos.betweenClosed(lo, hi)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
            restoreTheWorldData();
        };
        List<BlockPos> probes = List.of(lo, hi, new BlockPos(ox, y, oz - 18), new BlockPos(ox, y, oz));
        UndergroundGameTests.whenEntitiesTick(helper, level, probes, 40, () -> {
            BlockPos origin;
            try {
                for (BlockPos at : BlockPos.betweenClosed(new BlockPos(ox - 64, y, oz - 36), new BlockPos(ox + 64, y, oz + 36))) level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                // the site search sees real blocks: a city over the lot, then a house in it, each refuse it; clear ground accepts
                var ground = WaysideShelters.ground(level, plans);
                int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
                CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region("valewood", level.dimension().location().toString(), ox >> 4, (oz - 18) >> 4, 64)));
                check(WaysideShelters.sites(ground, plan, 60, width, depth).isEmpty(), "No shelter inside a city circle");
                CityRegions.INSTANCE.replaceForTest(List.of());
                var sites = WaysideShelters.sites(ground, plan, 60, width, depth);
                check(!sites.isEmpty() && sites.get(0).side() == -1 && sites.get(0).shift() == 0 && sites.get(0).origin().getY() == y + 1, "Level ground beside the road is found: " + sites);
                origin = sites.get(0).origin();
                check(origin.getZ() < oz, "The best lot is on the left of a road running east (north of it): " + origin);
                check(!WaysideShelters.builtOver(level, origin, district), "Bare ground has nothing built on it");
                level.setBlock(origin.offset(3, 1, 3), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                check(WaysideShelters.builtOver(level, origin, district), "Planks in the lot mean somebody built here");
                level.setBlock(origin.offset(3, 1, 3), Blocks.AIR.defaultBlockState(), 2);
                PlacedSites state = new PlacedSites();
                check(WaysideShelters.serve(level, plans, plan, List.of(60.0), ox + 500.0, oz + 500.0, state, 24) == 0, "Nothing is built far from the traveller");
                check(WaysideShelters.serve(level, plans, plan, List.of(60.0), ox + 0.5, oz + 0.5, state, 24) == 1, "A traveller close by gets the shelter built");
                check(state.done.contains(road + "#0") && state.where.get(road + "#0") == origin.asLong(), "The world remembers where it stands: " + state.where);
                check(WaysideShelters.serve(level, plans, plan, List.of(60.0), ox + 0.5, oz + 0.5, state, 24) == 0 && DistrictBuilder.count(level, district.id()) == 1, "A shelter is built once");
                var reloaded = PlacedSites.load(state.save(new net.minecraft.nbt.CompoundTag()));
                check(reloaded.done.equals(state.done) && reloaded.where.equals(state.where), "Placed shelters survive a save and load");
                if (kind == 0) {
                    check(level.getBlockState(origin.offset(5, 0, 4)).is(Blocks.CAMPFIRE) && level.getBlockState(origin.offset(2, 0, 1)).is(Blocks.STRIPPED_SPRUCE_LOG)
                        && level.getBlockState(origin.offset(5, 4, 4)).is(Blocks.SPRUCE_PLANKS) && level.getBlockState(origin.offset(5, -1, 4)).is(Blocks.STONE_BRICKS), "The pavilion: campfire, posts, roof and floor");
                    check(level.getBlockEntity(origin.offset(2, 2, 2)) instanceof SignBlockEntity sign && sign.getFrontText().getMessage(0, false).getString().equals("WAYSIDE") && sign.isWaxed(), "The sign is written and waxed");
                } else {
                    check(level.getBlockState(origin.offset(5, -1, 4)).is(Blocks.WATER) && level.getBlockState(origin.offset(4, 0, 3)).is(Blocks.STONE_BRICK_WALL)
                        && level.getBlockState(origin.offset(4, 2, 3)).is(Blocks.STRIPPED_OAK_LOG) && level.getBlockState(origin.offset(5, 4, 4)).is(Blocks.OAK_PLANKS), "The well: water in a ring of wall, posts and a roof");
                    check(level.getBlockEntity(origin.offset(3, 2, 3)) instanceof SignBlockEntity sign && sign.getFrontText().getMessage(0, false).getString().equals("THE WELL") && sign.isWaxed(), "The sign is written and waxed");
                }
            } catch (RuntimeException error) {
                cleanup.run();
                throw error;
            }
            // a villager added to a chunk whose entity section has not been promoted yet is invisible to lookups for a few ticks
            UndergroundGameTests.waitUntil(helper, () -> DistrictBuilder.citizensForTest(level, district.id()).size() == 1
                && DistrictBuilder.citizensForTest(level, district.id()).stream().allMatch(id -> level.getEntity(id) != null), 0, () -> {
                try {
                    var id = DistrictBuilder.citizensForTest(level, district.id()).get(0);
                    check(level.getEntity(id) instanceof Villager warden && warden.hasCustomName() && warden.isInvulnerable()
                        && CityNpcs.definition(warden).map(npc -> npc.role().equals("warden")).orElse(false), "The warden is a named, protected citizen with the warden role");
                    check(Math.abs(level.getEntity(id).getY() - (y + 1)) < 2 && Math.abs(level.getEntity(id).getZ() - (oz - 16)) < 12, "The warden stands in the lot: " + level.getEntity(id).position());
                    // the warden is on a leash around where they were put, and gets it back when they load again (a mob does not save its own)
                    Villager keeper = (Villager) level.getEntity(id);
                    BlockPos post = keeper.getRestrictCenter();
                    check(keeper.hasRestriction() && keeper.getRestrictRadius() == CityNpcs.TETHER_RADIUS && post.distSqr(keeper.blockPosition()) < 100, "The warden is tethered to the shelter: " + post);
                    keeper.restrictTo(BlockPos.ZERO, 1);
                    new CityNpcs().joined(new net.minecraftforge.event.entity.EntityJoinLevelEvent(keeper, level));
                    check(keeper.getRestrictCenter().equals(post) && keeper.getRestrictRadius() == CityNpcs.TETHER_RADIUS, "The leash is restored when the warden loads: " + keeper.getRestrictCenter());
                    DistrictBuilder.undoNear(level, district, new BlockPos(ox, y + 1, oz - 16));
                    check(DistrictBuilder.count(level, district.id()) == 0 && !level.getBlockState(new BlockPos(ox - 1, y + 1, oz - 16)).is(Blocks.CAMPFIRE) && !level.getBlockState(new BlockPos(ox - 1, y, oz - 16)).is(Blocks.WATER), "Removing it restores the ground and takes the warden away");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
            });
        });
    }
}
