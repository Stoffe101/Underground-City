package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Roadside places (D-138): the shipped catalog, where along a road a place may stand, which way it faces, and one real place built beside a synthetic road with its driveway and people. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RoadsidePlaceGameTests {
    private RoadsidePlaceGameTests() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new net.minecraft.gametest.framework.GameTestAssertException(message);
    }

    /** Ground described by one height everywhere and nothing reserved or wet. */
    private record Flat(int y) implements DistrictSites.Ground {
        @Override public int height(int x, int z) { return y; }
        @Override public boolean wet(int x, int z) { return false; }
        @Override public boolean reserved(int x, int z) { return false; }
    }

    /** An east-running road 1400 blocks long between two city circles of radius 100, on flat land at 64 except a 100-block stretch of sea from x = 600 to 700. */
    private static CorridorPlan roadWithASea() {
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{0, 1400}, new double[]{0, 0}, 7,
            List.of(new CorridorPlan.City(0, 0, 100), new CorridorPlan.City(1400, 0, 100)));
        plan.build(new CorridorPlan.Terrain() {
            @Override public int surface(int x, int z) { return x >= 600 && x <= 700 ? 63 : 64; }
            @Override public int floor(int x, int z) { return x >= 600 && x <= 700 ? 50 : 64; }
        });
        return plan;
    }

    private static RoadsidePlaces.Place place(String id, double at, int width, int depth) {
        return new RoadsidePlaces.Place(id, id, "ironhaven_valewood", at, "service", width, depth, Map.of("north", "n", "east", "e", "south", "s", "west", "w"));
    }

    @GameTest(template = "empty")
    public static void everyShippedRoadsidePlaceHasFourRotationsWithItsPeopleAndCounters(GameTestHelper helper) {
        List<RoadsidePlaces.Place> places = RoadsidePlaces.CATALOG.all();
        check(places.size() == 6, "six roadside places ship: " + places.stream().map(RoadsidePlaces.Place::id).toList());
        Set<String> roads = places.stream().map(RoadsidePlaces.Place::road).collect(Collectors.toSet());
        check(roads.equals(Set.of("ironhaven_valewood", "ironhaven_blackwater", "valewood_blackwater")), "every road has its places: " + roads);
        for (String road : roads) check(RoadsidePlaces.CATALOG.onRoad(road).size() == 2, road + " has two places");
        for (RoadsidePlaces.Place place : places) {
            Integer people = null;
            for (var variant : place.variants().entrySet()) {
                DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(variant.getValue());
                check(district != null, place.id() + ": no district " + variant.getValue());
                int[] size = place.size(variant.getKey());
                int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
                check(width == size[0] && depth == size[1], place.id() + " facing " + variant.getKey() + ": the district is " + width + " by " + depth + " but the lot " + size[0] + " by " + size[1]);
                check(!district.repeatable() && district.homePrefix().equals("rs_" + place.id()), place.id() + ": a one-off whose homes keep one name in every rotation: " + district.homePrefix());
                int here = district.npcOps().size();
                check(people == null || people == here, place.id() + ": every rotation has the same people");
                people = here;
                for (DistrictCatalog.Op op : district.npcOps()) {
                    if (!op.npc().role().equals("shopkeeper")) continue;
                    check(ShopCounters.INSTANCE.forBuilding(op.npc().shop()).isPresent(), place.id() + ": " + op.npc().name() + " keeps a counter that does not exist: " + op.npc().shop());
                }
            }
            check(people != null && people >= 1, place.id() + " has somebody in it");
        }
        // the homes beside the road are on the property ladder
        for (String home : List.of("rs_saltmarsh_motel:room_1", "rs_saltmarsh_motel:room_4", "rs_harrow_farm:farmhouse")) check(PropertyCatalog.INSTANCE.find(home).isPresent(), "the ladder lists " + home);
        check(PropertyCatalog.INSTANCE.find("rs_harrow_farm:farmhouse").get().canBuy() && !PropertyCatalog.INSTANCE.find("rs_saltmarsh_motel:room_1").get().canBuy(), "the farmhouse can be bought, a motel room only rented");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlaceStandsOnLandClearOfTheCitiesTheSheltersAndTheOtherPlaces(GameTestHelper helper) {
        CorridorPlan plan = roadWithASea();
        RoadsidePlaces.Place diner = place("diner", 0.5, 30, 26), motel = place("motel", 0.5, 34, 24);
        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan), target = first + 0.5 * (last - first);
        check(Math.abs(target - 700) < 2, "the place's share of the road is at the sea: " + target);
        List<Double> shelters = WaysideShelters.positions(plan, WaysideShelters.MAX_GAP, WaysideShelters.EDGE);
        var plan2 = RoadsidePlaces.plan(plan, List.of(diner, motel), (x, z, margin) -> false);
        // a city's region near the road keeps every spot within reach of it out
        var nearCity = RoadsidePlaces.plan(plan, List.of(diner), (x, z, margin) -> x > 300);
        check(nearCity.get("diner").stream().allMatch(c -> plan.pointAt(c)[0] <= 300), "no spot within a city's region: " + nearCity);
        List<Double> a = plan2.get("diner"), b = plan2.get("motel");
        check(!a.isEmpty() && !b.isEmpty(), "both places have somewhere to stand: " + plan2);
        double need = Math.hypot(30, 26) / 2.0 + 12.0;
        for (double s : a) {
            check(RoadsidePlaces.land(plan, s - need, s + need), "a candidate spot is on land: " + s);
            check(s >= first + RoadsidePlaces.EDGE && s <= last - RoadsidePlaces.EDGE, "a candidate spot is clear of the city circles: " + s);
            for (double shelter : shelters) check(Math.abs(s - shelter) >= RoadsidePlaces.KEEP + need, "a candidate spot keeps clear of the shelter at " + shelter + ": " + s);
        }
        for (int i = 1; i < a.size(); i++) check(Math.abs(a.get(i) - target) >= Math.abs(a.get(i - 1) - target) - 8.0, "the candidates are nearest first: " + a);
        check(Math.abs(a.get(0) - 700) < 140 || Math.abs(a.get(0) - 600) < 140, "the first choice is the nearest land to the sea's edge: " + a.get(0));
        check(Math.abs(b.get(0) - a.get(0)) >= RoadsidePlaces.KEEP, "the second place on the road keeps clear of the first: " + a.get(0) + " and " + b.get(0));
        CorridorPlan shortRoad = new CorridorPlan("a_b", new double[]{0, 400}, new double[]{0, 0}, 7, List.of(new CorridorPlan.City(0, 0, 100), new CorridorPlan.City(400, 0, 100)));
        shortRoad.build(new CorridorPlan.Terrain() {
            @Override public int surface(int x, int z) { return 64; }
            @Override public int floor(int x, int z) { return 64; }
        });
        check(RoadsidePlaces.candidates(shortRoad, diner, List.of()).isEmpty(), "a road too short for a place gets none");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlaceFacesTheRoadAndSitsNearTheDecksHeight(GameTestHelper helper) {
        check(RoadsidePlaces.facing(0, 1).equals("south") && RoadsidePlaces.facing(0, -1).equals("north") && RoadsidePlaces.facing(1, 0.2).equals("east") && RoadsidePlaces.facing(-1, -0.3).equals("west"), "the nearest compass direction");
        RoadsidePlaces.Place diner = place("diner", 0.5, 30, 26);
        check(diner.size("north")[0] == 30 && diner.size("east")[0] == 26 && diner.size("west")[1] == 30, "a lot turned east or west swaps its sides");
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{0, 400}, new double[]{0, 0}, 7, List.of());
        List<RoadsidePlaces.Site> sites = RoadsidePlaces.sites(new Flat(65), plan, diner, 200, s -> 64);
        check(!sites.isEmpty(), "level ground at the deck's height has lots on both sides");
        for (RoadsidePlaces.Site site : sites) {
            int[] size = diner.size(site.facing());
            boolean north = site.origin().getZ() + size[1] <= 0, south = site.origin().getZ() >= 0;
            check(north || south, "a lot is wholly on one side of the road: " + site);
            check(north ? site.facing().equals("south") : site.facing().equals("north"), "a lot's front looks at the road: " + site);
            check(Math.min(Math.abs(site.origin().getZ()), Math.abs(site.origin().getZ() + size[1])) > plan.reach(), "a lot never touches the road: " + site);
        }
        check(RoadsidePlaces.sites(new Flat(65 + RoadsidePlaces.MAX_STEP_TO_DECK + 1), plan, diner, 200, s -> 64).isEmpty(), "a lot too far above the deck is refused: its driveway would be a climb");
        check(!RoadsidePlaces.sites(new Flat(65 + RoadsidePlaces.MAX_STEP_TO_DECK), plan, diner, 200, s -> 64).isEmpty(), "a lot a few blocks above the deck is fine");
        helper.succeed();
    }

    /** The diner built beside a straight road on a stone platform in the sky, as a traveller's arrival would: once, facing the road, with a concrete driveway and its two shopkeepers. */
    @GameTest(template = "empty", batch = "uc_roadside_build", timeoutTicks = 1200)
    public static void theDinerIsBuiltOnceBesideTheRoadFacingItWithADrivewayAndItsShopkeepers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RoadsidePlaces.Place diner = RoadsidePlaces.CATALOG.find("crossroads_diner");
        check(diner != null, "the diner ships");
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        int y = level.getMaxBuildHeight() - 100, ox = arena.getX(), oz = arena.getZ() + 900;
        BlockPos lo = new BlockPos(ox - 110, y - 12, oz - 60), hi = new BlockPos(ox + 110, y + 14, oz + 60);
        for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, true);
        WaysideShelterGameTests.emptyTheWorldData();
        for (String id : diner.variants().values()) DistrictBuilder.forgetForTest(level, id);
        RoadsidePlaces.resetForTest();
        CorridorPlan plan = new CorridorPlan("ironhaven_valewood", new double[]{ox - 100, ox + 100}, new double[]{oz, oz}, 7, List.of());
        List<CorridorPlan> plans = List.of(plan);
        Runnable cleanup = () -> {
            for (String id : diner.variants().values()) DistrictBuilder.forgetForTest(level, id);
            for (BlockPos at : BlockPos.betweenClosed(lo, hi)) level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            for (int cx = lo.getX() >> 4; cx <= hi.getX() >> 4; cx++) for (int cz = lo.getZ() >> 4; cz <= hi.getZ() >> 4; cz++) level.setChunkForced(cx, cz, false);
            RoadsidePlaces.resetForTest();
            WaysideShelterGameTests.restoreTheWorldData();
        };
        List<BlockPos> probes = List.of(lo, hi, new BlockPos(ox, y, oz - 30), new BlockPos(ox, y, oz + 30), new BlockPos(ox - 40, y, oz - 30), new BlockPos(ox + 40, y, oz + 30));
        UndergroundGameTests.whenEntitiesTick(helper, level, probes, 40, () -> {
            BlockPos origin;
            DistrictCatalog.District built;
            try {
                for (BlockPos at : BlockPos.betweenClosed(new BlockPos(ox - 104, y - 2, oz - 56), new BlockPos(ox + 104, y, oz + 56))) level.setBlock(at, Blocks.STONE.defaultBlockState(), 2);
                // a tall spike of rock in the lot the north side will choose, taller than the diner's own clearing reaches, and a pit just beyond its far edge
                // (on both sides, so that neither lot is the level one and the north one, searched first, is chosen)
                int[] size0 = diner.size("south");
                BlockPos corner = CheckpointPosts.lotBeside(plan, 100.0, size0[0], size0[1], -1).orElseThrow().origin();
                BlockPos spike = new BlockPos(corner.getX() + size0[0] / 2, y + 1, corner.getZ() + size0[1] / 2);
                BlockPos pit = new BlockPos(corner.getX() + size0[0] / 2, y, corner.getZ() - 1);
                BlockPos southCorner = CheckpointPosts.lotBeside(plan, 100.0, size0[0], size0[1], 1).orElseThrow().origin();
                for (BlockPos at : List.of(spike, new BlockPos(southCorner.getX() + size0[0] / 2, y + 1, southCorner.getZ() + size0[1] / 2)))
                    for (int up = 0; up < 12; up++) level.setBlock(at.above(up), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(pit, Blocks.AIR.defaultBlockState(), 2); level.setBlock(pit.below(), Blocks.AIR.defaultBlockState(), 2);
                PlacedSites state = new PlacedSites();
                java.util.function.DoubleToIntFunction deck = s -> y;
                check(RoadsidePlaces.serve(level, plans, diner, List.of(100.0), ox + 600.0, oz + 600.0, state, 24, deck) == null, "nothing is decided far from a traveller");
                // the way a server does it (D-148): the place goes up a slice at a time; two milliseconds a slice here, so that it takes several
                RoadsidePlaces.Outcome outcome = RoadsidePlaces.serve(level, plans, diner, List.of(100.0), ox + 0.5, oz + 0.5, state, 24, deck, true);
                check(outcome != null && outcome.status().equals("building"), "a traveller close by gets the diner started: " + outcome);
                check(!state.done.contains("crossroads_diner") && state.done.stream().anyMatch(d -> d.startsWith("crossroads_diner" + RoadsidePlaces.BUILDING_MARK)), "the world marks the diner as under way: " + state.done);
                RoadsidePlaces.Construction going = RoadsidePlaces.building("crossroads_diner");
                int guard = 0;
                while (!going.step(level, state, 2_000_000L)) check(++guard < 20_000, "the diner never finished going up");
                check(going.slices() > 1, "the diner went up in one slice: the work was not spread over ticks");
                outcome = going.outcome();
                check(outcome.status().equals("built"), "a traveller close by gets the diner built: " + outcome);
                check(state.done.stream().noneMatch(d -> d.contains(RoadsidePlaces.BUILDING_MARK)), "the marker of a finished place stays behind: " + state.done);
                origin = outcome.origin();
                check(state.done.contains("crossroads_diner") && state.where.get("crossroads_diner") == origin.asLong(), "the world remembers where it stands");
                check(origin.getY() == y + 1, "the lot stands on the platform: " + origin);
                built = DistrictCatalog.INSTANCE.find(diner.variants().get(outcome.facing()));
                check(DistrictBuilder.count(level, built.id()) == 1, "the rotation that faces the road is the one built: " + outcome.facing());
                int[] size = diner.size(outcome.facing());
                boolean north = origin.getZ() + size[1] <= oz;
                check(outcome.facing().equals(north ? "south" : "north"), "its front looks at the road: " + outcome);
                check(outcome.driveway() > 0 && outcome.drivewayStep() == 0, "a driveway was laid, level on level ground: " + outcome);
                // the driveway's middle, between the lot's front and the road
                int front = north ? origin.getZ() + size[1] : origin.getZ() - 1, toward = north ? 1 : -1;
                int mx = origin.getX() + size[0] / 2;
                BlockPos onDriveway = new BlockPos(mx, y, front + toward * 2);
                check(level.getBlockState(onDriveway).is(Blocks.GRAY_CONCRETE), "the driveway is surfaced: " + level.getBlockState(onDriveway) + " at " + onDriveway);
                check(RoadsidePlaces.serve(level, plans, diner, List.of(100.0), ox + 0.5, oz + 0.5, state, 24, deck) == null && DistrictBuilder.count(level, built.id()) == 1, "it is built once");
                check(north, "with both lots equally rough, the north one (searched first) is chosen: " + outcome);
                if (north) {
                    check(level.getBlockState(spike.above(11)).isAir() && level.getBlockState(spike.above(10)).isAir(), "the rock spike in the lot was cut away to the sky, not left over the roof");
                    check(!level.getBlockState(pit).isAir(), "the pit beyond the lot's edge was graded in: " + level.getBlockState(pit));
                }
                var reloaded = PlacedSites.load(state.save(new net.minecraft.nbt.CompoundTag()));
                check(reloaded.done.equals(state.done) && reloaded.where.equals(state.where), "the placement survives a save and load");
            } catch (RuntimeException error) {
                cleanup.run();
                throw error;
            }
            DistrictCatalog.District district = built;
            int people = district.npcOps().size();
            UndergroundGameTests.waitUntil(helper, () -> DistrictBuilder.citizensForTest(level, district.id()).size() == people
                && DistrictBuilder.citizensForTest(level, district.id()).stream().allMatch(id -> level.getEntity(id) != null), 0, () -> {
                try {
                    int keepers = 0;
                    for (var id : DistrictBuilder.citizensForTest(level, district.id())) {
                        check(level.getEntity(id) instanceof Villager person && person.isInvulnerable() && person.hasCustomName(), "a named, protected person: " + level.getEntity(id));
                        Villager person = (Villager) level.getEntity(id);
                        var npc = CityNpcs.definition(person).orElseThrow();
                        if (!npc.role().equals("shopkeeper")) continue;
                        keepers++;
                        check(ShopCounters.INSTANCE.forBuilding(npc.shop()).isPresent(), npc.name() + " keeps a real counter: " + npc.shop());
                        check(person.hasRestriction() && person.getRestrictRadius() == CityNpcs.SHOPKEEPER_TETHER, npc.name() + " stays at the counter");
                        check(person.blockPosition().getY() >= origin.getY() - 1 && person.blockPosition().getY() <= origin.getY() + 3, npc.name() + " stands in the diner: " + person.position());
                    }
                    check(keepers == 2, "the cook and the clerk: " + keepers);
                    DistrictBuilder.undoAt(level, district, origin);
                    check(DistrictBuilder.count(level, district.id()) == 0, "removing it takes it and its people away");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
            });
        });
    }

    /** A bed that Lost Cities wrote without its block entity (it is drawn by that alone) gets one when its chunk is first generated (D-141). */
    @GameTest(template = "empty")
    public static void aBedWrittenWithoutItsBlockEntityGetsOne(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = helper.absolutePos(new BlockPos(1, 2, 1));
        var chunk = level.getChunkAt(foot);
        var section = chunk.getSection(chunk.getSectionIndex(foot.getY()));
        var bed = Blocks.RED_BED.defaultBlockState();
        section.setBlockState(foot.getX() & 15, foot.getY() & 15, foot.getZ() & 15, bed);   // as a generator writes it: straight into the section, no block entity
        check(chunk.getBlockEntity(foot, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK) == null, "the bed starts without its block entity");
        check(MissingBlockEntities.repair(chunk) >= 1, "the repair makes it");
        check(chunk.getBlockEntity(foot, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK) instanceof net.minecraft.world.level.block.entity.BedBlockEntity, "the bed has its block entity");
        check(MissingBlockEntities.repair(chunk) == 0, "a second pass makes nothing");
        level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2);
        helper.succeed();
    }

    /** The loot op fills a container from a loot table once, the first time it is opened, as vanilla's chests do; the table is seeded by the spot and the world. */
    @GameTest(template = "empty")
    public static void aLootOpGivesItsContainerATableAndASeed(GameTestHelper helper) {
        DistrictCatalog.District barn = DistrictCatalog.INSTANCE.find("rs_harrow_farm_n");
        check(barn != null, "the farm ships");
        List<DistrictCatalog.Op> loot = barn.ops().stream().filter(op -> op.kind().equals("loot")).toList();
        check(loot.size() >= 2 && loot.stream().allMatch(op -> op.loot().equals("underground_city:roadside/farm_store")), "the barn's barrels draw from the farm store: " + loot);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(at, Blocks.BARREL.defaultBlockState(), 3);
        check(helper.getLevel().getBlockEntity(at) instanceof RandomizableContainerBlockEntity, "a barrel takes a loot table");
        check(helper.getLevel().getServer().getLootData().getLootTable(new net.minecraft.resources.ResourceLocation("underground_city", "roadside/farm_store")) != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "the farm store table is loaded");
        helper.succeed();
    }
}
