package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The people who live and work in the furnished buildings (D-124). The arithmetic cases need only the code; the world cases build a room in the test arena, give it a plan of spots through the test seams
 * (the real spots come with the furnished package, which this job does not load), set the clock by hand and drive the service with a fake player. They show: a household is the same every time and bounded; a night
 * finds the residents at their beds and a working day finds the shopkeeper at the counter; bodies appear indoors at the spots when a player is near, are never saved and never open doors, and go when nobody is
 * near or when their person has gone out; a resident at home is a witness that brings the police and a forced door is remembered, in a record that survives a save. It does not show a person walking into a
 * furnished house, the cost under a real city, or how any of it feels.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HouseholdGameTests {
    private static final String TYPE = "underground_city:test_house", SHOP = "underground_city:test_shop";

    private static BuildingSpots.Plan house() {
        return new BuildingSpots.Plan(TYPE, "house", "valewood", List.of(
            new BuildingSpots.Spot("door", 7, 1, 2, "north", ""), new BuildingSpots.Spot("bed", 4, 1, 4, "east", "bedroom"), new BuildingSpots.Spot("bed", 9, 1, 4, "west", "second bedroom"),
            new BuildingSpots.Spot("stove", 4, 1, 10, "south", ""), new BuildingSpots.Spot("seat", 10, 1, 10, "west", "sofa"), new BuildingSpots.Spot("seat", 8, 1, 10, "west", "table")));
    }

    private static BuildingSpots.Plan shop() {
        return new BuildingSpots.Plan(SHOP, "shop", "valewood", List.of(
            new BuildingSpots.Spot("door", 7, 1, 2, "north", ""), new BuildingSpots.Spot("counter", 6, 1, 6, "south", "till"), new BuildingSpots.Spot("shelf", 10, 1, 10, "west", "")));
    }

    private static PopulationPlan.Names names() { return PopulationCatalog.INSTANCE.city("valewood").names(); }

    // ------------------------------------------------------------------------------------------------ arithmetic

    @GameTest(template = "empty")
    public static void aHouseholdIsTheSameEveryTimeBoundedAndDressedForItsRole(GameTestHelper helper) {
        var many = new BuildingSpots.Plan("underground_city:many", "flats", "ironhaven", java.util.stream.IntStream.range(0, 8).mapToObj(i -> new BuildingSpots.Spot("bed", i, 1, 0, "east", "")).toList());
        var big = new BuildingSpots.Plan("underground_city:big", "office", "blackwater", List.of(new BuildingSpots.Spot("counter", 1, 1, 1, "", ""), new BuildingSpots.Spot("desk", 2, 1, 1, "", ""),
            new BuildingSpots.Spot("desk", 3, 1, 1, "", ""), new BuildingSpots.Spot("desk", 4, 1, 1, "", "")));
        var a = HouseholdPlan.residents("ironhaven", 5908L, 42L, many, names());
        check(a.equals(HouseholdPlan.residents("ironhaven", 5908L, 42L, many, names())), "a household changed between two calls");
        check(a.size() == HouseholdPlan.MAX_RESIDENTS, "eight flats gave " + a.size() + " residents, not the cap of " + HouseholdPlan.MAX_RESIDENTS);
        check(a.stream().map(HouseholdPlan.Resident::id).distinct().count() == a.size(), "two residents share an identity");
        check(!a.equals(HouseholdPlan.residents("ironhaven", 5908L, 43L, many, names())) && !a.equals(HouseholdPlan.residents("ironhaven", 5909L, 42L, many, names())), "another building or another world gave the same people");
        for (var who : a) check(who.role().equals("resident") && java.util.Arrays.stream(PopulationPlan.outfitsOf("resident", "ironhaven")).anyMatch(o -> o == who.skin() % PopulationPlan.OUTFITS), "a resident is not dressed as one: skin " + who.skin());
        var staff = HouseholdPlan.residents("blackwater", 5908L, 7L, big, names());
        check(staff.size() == HouseholdPlan.MAX_WORKERS && staff.stream().allMatch(w -> w.role().equals("worker")), "an office gave " + staff.size() + " workers");
        for (var who : staff) check(java.util.Arrays.stream(PopulationPlan.outfitsOf("worker", "blackwater")).anyMatch(o -> o == who.skin() % PopulationPlan.OUTFITS), "a worker is not dressed as one of the city's trades");
        check(HouseholdPlan.residents("valewood", 1L, 1L, null, names()).isEmpty() && HouseholdPlan.residents("valewood", 1L, 1L, house(), null).isEmpty(), "a building with no plan or no names has people");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aNightFindsTheResidentsAtTheirBedsAndADayFindsTheShopkeeperAtTheCounter(GameTestHelper helper) {
        BuildingSpots.Plan home = house(), shop = shop();
        var people = HouseholdPlan.residents("valewood", 5908L, 99L, home, names());
        var keeper = HouseholdPlan.residents("valewood", 5908L, 98L, shop, names());
        check(people.size() == 2 && keeper.size() == 1 && keeper.get(0).role().equals("worker"), "setup: " + people.size() + " residents, " + keeper.size() + " workers");
        check(HouseholdPlan.averageIn(people, home, 5908L, 23) > 0.8 && HouseholdPlan.averageIn(people, home, 5908L, 3) > 0.8, "the residents are not at home at night");
        check(HouseholdPlan.averageIn(people, home, 5908L, 12) < 0.5, "the residents are all at home in the middle of the working day");
        check(HouseholdPlan.averageIn(people, home, 5908L, 19) > 0.7, "the residents are not at home in the evening");
        check(HouseholdPlan.averageIn(keeper, shop, 5908L, 10) > 0.85 && HouseholdPlan.averageIn(keeper, shop, 5908L, 14) > 0.85, "the shopkeeper is not at the counter in business hours");
        check(HouseholdPlan.averageIn(keeper, shop, 5908L, 23) == 0 && HouseholdPlan.averageIn(keeper, shop, 5908L, 4) == 0 && HouseholdPlan.averageIn(keeper, shop, 5908L, 19) == 0, "a shop is staffed out of hours");
        for (long day = 0; day < 30; day++) {
            for (var who : people) {
                int night = HouseholdPlan.where(who, home, 5908L, 1, day), morning = HouseholdPlan.where(who, home, 5908L, 8, day), evening = HouseholdPlan.where(who, home, 5908L, 20, day);
                check(night == HouseholdPlan.AWAY || home.spots().get(night).kind().equals("bed"), "a resident sleeps somewhere that is not a bed");
                check(morning == HouseholdPlan.AWAY || home.spots().get(morning).kind().equals("stove"), "a resident is not at the stove for breakfast");
                check(evening == HouseholdPlan.AWAY || home.spots().get(evening).kind().equals("seat"), "a resident is not sitting in the evening");
            }
            int at = HouseholdPlan.where(keeper.get(0), shop, 5908L, 11, day);
            check(at == HouseholdPlan.AWAY || shop.spots().get(at).kind().equals("counter"), "the shopkeeper is not at the counter");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ in the world

    private static final class Room implements AutoCloseable {
        final ServerLevel level;
        final ChunkPos chunk;
        final int streetY;
        final CityBuildings.Building building;
        final ServerPlayer visitor;
        Room(GameTestHelper helper, String name, String type, CityBuildings.SecurityClass security) {
            level = helper.getLevel();
            BlockPos placed = helper.absolutePos(new BlockPos(0, 2, 0));
            chunk = new ChunkPos(placed);
            // the room goes high in the sky above the test's chunk: it is built over the whole chunk, and nothing else of the test world may be touched
            streetY = level.getMaxBuildHeight() - 80;
            int x0 = chunk.getMinBlockX(), z0 = chunk.getMinBlockZ();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = -1; y <= 6; y++) level.setBlock(new BlockPos(x0 + x, streetY + y, z0 + z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            for (int x = 2; x <= 12; x++) for (int z = 2; z <= 12; z++) {
                boolean wall = x == 2 || x == 12 || z == 2 || z == 12;
                level.setBlock(new BlockPos(x0 + x, streetY, z0 + z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                level.setBlock(new BlockPos(x0 + x, streetY + 4, z0 + z), Blocks.STONE.defaultBlockState(), 2);
                if (wall) for (int y = 1; y <= 3; y++) level.setBlock(new BlockPos(x0 + x, streetY + y, z0 + z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
            building = new CityBuildings.Building("valewood", chunk.x, chunk.z, type, type, 1, 0, streetY, security);
            CityBuildings.INSTANCE.overrideForTest((cx, cz) -> cx == chunk.x && cz == chunk.z ? Optional.of(building) : Optional.empty());
            visitor = player(helper, name, "farmer");
            visitor.setPos(x0 + 7.5, streetY + 1, z0 + 7.5);
            HouseholdService.resetForTest();
            PoliceResponse.resetForTest();
        }
        BlockPos spot(BuildingSpots.Spot s) { return new BlockPos(chunk.getMinBlockX() + s.x(), streetY + s.y(), chunk.getMinBlockZ() + s.z()); }
        List<TownspersonEntity> bodies() { return level.getEntitiesOfClass(TownspersonEntity.class, new AABB(spot(new BuildingSpots.Spot("x", 8, 1, 8, "", ""))).inflate(24), t -> t.homeKey() == building.key()); }
        @Override public void close() {
            for (TownspersonEntity body : bodies()) body.discard();
            HouseholdService.resetForTest();
            PoliceResponse.resetForTest();
            BuildingSpots.INSTANCE.replaceForTest(Map.of());
            CityBuildings.INSTANCE.overrideForTest(null);
        }
    }

    private static final long NIGHT = (23 - 6) * 1000L, NOON = (12 - 6) * 1000L;

    /** The world clock for {@code hour} on {@code day}. */
    private static long clock(long day, int hour) { return day * 24000L + (hour - 6) * 1000L; }

    /** A day on which the arithmetic gives the case a test needs, found by looking, so that no test depends on what this world's seed happens to give. */
    private static long dayWhere(java.util.function.LongPredicate suits) {
        for (long day = 0; day < 400; day++) if (suits.test(day)) return day;
        throw new AssertionError("no day in four hundred suits the case: the schedule never gives it");
    }

    private static long presentAt(Room room, BuildingSpots.Plan plan, int hour, long day) {
        return HouseholdPlan.residents("valewood", room.level.getSeed(), room.building.key(), plan, names()).stream().filter(who -> HouseholdPlan.where(who, plan, room.level.getSeed(), hour, day) != HouseholdPlan.AWAY).count();
    }

    @GameTest(template = "empty")
    public static void residentsAppearIndoorsAtTheirBedsWhenAPlayerIsNearAndLeaveWhenNobodyIs(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(TYPE, house()));
        try (Room room = new Room(helper, "uc_hh_a", TYPE, null)) {
            long day = dayWhere(d -> presentAt(room, house(), 23, d) >= 1);
            HouseholdService.dayTimeOverride = clock(day, 23);
            for (int pass = 0; pass < 6; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            var bodies = room.bodies();
            var expected = presentAt(room, house(), 23, day);
            check(bodies.size() == expected && expected >= 1, "at night " + bodies.size() + " residents stood where " + expected + " were due");
            Set<String> names = new HashSet<>();
            for (TownspersonEntity body : bodies) {
                check(names.add(body.getName().getString()) && body.role().equals("resident") && body.city().equals("valewood"), "a resident has the wrong identity: " + body.getName().getString());
                check(!body.shouldBeSaved() && !body.getNavigation().getNodeEvaluator().canOpenDoors(), "a resident would be saved or may open doors");
                check(body.blockPosition().getX() >> 4 == room.chunk.x && body.blockPosition().getZ() >> 4 == room.chunk.z && body.blockPosition().getY() == room.streetY + 1, "a resident is not indoors on the floor: " + body.blockPosition());
                boolean atABed = house().indexesOf("bed").stream().anyMatch(i -> room.spot(house().spots().get(i)).distSqr(body.blockPosition()) <= 9);
                check(atABed, "a resident at night is not by a bed: " + body.blockPosition());
            }
            check(HouseholdService.activeCount() == bodies.size(), "the service counts " + HouseholdService.activeCount() + " bodies, the world has " + bodies.size());
            // nobody near: all of them go
            room.visitor.setPos(room.chunk.getMinBlockX() + 7.5 + 300, room.streetY + 1, room.chunk.getMinBlockZ() + 7.5);
            HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().isEmpty() && HouseholdService.activeCount() == 0, "residents stayed with nobody near");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void thoseWhoHaveGoneOutLeaveOnceNobodyIsLookingCloselyAndNeverBeforeAPlayersEyes(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(TYPE, house()));
        try (Room room = new Room(helper, "uc_hh_b", TYPE, null)) {
            // a day on which somebody who is home at night is out at noon
            long day = dayWhere(d -> HouseholdPlan.residents("valewood", room.level.getSeed(), room.building.key(), house(), names()).stream()
                .anyMatch(who -> HouseholdPlan.where(who, house(), room.level.getSeed(), 23, d) != HouseholdPlan.AWAY && HouseholdPlan.where(who, house(), room.level.getSeed(), 12, d) == HouseholdPlan.AWAY));
            HouseholdService.dayTimeOverride = clock(day, 23);
            for (int pass = 0; pass < 6; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            int before = room.bodies().size();
            check(before >= 1, "setup: nobody was at home at night");
            HouseholdService.dayTimeOverride = clock(day, 12);
            // the visitor is in the room, so nobody leaves in front of them whatever their day holds
            HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().size() == before, "a resident left in front of a player's eyes");
            // 30 blocks away (near enough to see the building, too far to see a face) the working day takes them out
            room.visitor.setPos(room.chunk.getMinBlockX() + 7.5, room.streetY + 1, room.chunk.getMinBlockZ() + 7.5 + 30);
            HouseholdService.pass(room.level, List.of(room.visitor));
            var left = before - room.bodies().size();
            check(left >= 1 && HouseholdService.removed >= left, "nobody went out for the working day: " + before + " before, " + room.bodies().size() + " after");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theShopkeeperStandsAtTheCounterByDayAndTheShopIsEmptyAtNight(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(SHOP, shop()));
        try (Room room = new Room(helper, "uc_hh_c", SHOP, null)) {
            long day = dayWhere(d -> presentAt(room, shop(), 10, d) == 1);
            HouseholdService.dayTimeOverride = clock(day, 23);
            for (int pass = 0; pass < 4; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().isEmpty(), "a shop was staffed at night");
            HouseholdService.dayTimeOverride = clock(day, 10);
            for (int pass = 0; pass < 4; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().size() == 1, "the shopkeeper was not at the counter at ten on a day the plan has them in: " + room.bodies().size());
            for (TownspersonEntity body : room.bodies()) {
                check(body.role().equals("worker") && room.spot(shop().spots().get(1)).distSqr(body.blockPosition()) <= 9, "the shopkeeper is not at the counter: " + body.blockPosition());
            }
        }
        helper.succeed();
    }

    /** A shop whose street door is in the south wall of the test room, so the street in front of it lies inside the room's own chunk. */
    private static BuildingSpots.Plan southShop() {
        return new BuildingSpots.Plan(SHOP, "shop", "valewood", List.of(
            new BuildingSpots.Spot("door", 7, 1, 12, "south", "shop door"), new BuildingSpots.Spot("counter", 6, 1, 6, "south", "till"), new BuildingSpots.Spot("shelf", 10, 1, 10, "west", "")));
    }

    /** Runs the household over the minutes of one hour of {@code day} until {@code done} holds; false when the hour ended first. */
    private static boolean runHour(Room room, long day, int hour, java.util.function.BooleanSupplier done) {
        for (int minute = 0; minute < 60; minute++) {
            HouseholdService.dayTimeOverride = clock(day, hour) + minute * 1000L / 60;
            HouseholdService.pass(room.level, List.of(room.visitor));
            if (done.getAsBoolean()) return true;
        }
        return false;
    }

    @GameTest(template = "empty")
    public static void theShopkeeperWalksInThroughTheStreetDoorAtEightAndOutAgainAtSix(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(SHOP, southShop()));
        try (Room room = new Room(helper, "uc_hh_go", SHOP, null)) {
            BlockPos door = room.spot(southShop().spots().get(0));
            var oak = Blocks.OAK_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.FACING, net.minecraft.core.Direction.SOUTH);
            room.level.setBlock(door, oak.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER), 2);
            room.level.setBlock(door.above(), oak.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 2);
            long day = dayWhere(d -> presentAt(room, southShop(), 10, d) == 1);
            // eight in the morning: the shopkeeper comes up the street to the door, not out of thin air at the counter
            check(runHour(room, day, 8, () -> HouseholdService.commuterCount() > 0), "nobody came to work at eight");
            TownspersonEntity coming = room.bodies().stream().filter(b -> HouseholdService.commuteOf(b) != null).findFirst().orElseThrow();
            var commute = HouseholdService.commuteOf(coming);
            check(commute.entering() && coming.role().equals("worker") && commute.door().equals(door), "the commute is not the shopkeeper coming in by the door: " + commute + " " + coming.role());
            check(coming.blockPosition().distSqr(door) >= 4 && coming.errandPlace() != null && coming.errandPlace().distSqr(door.relative(net.minecraft.core.Direction.SOUTH).below()) <= 1,
                "the shopkeeper did not start in the street and walk to the door: at " + coming.blockPosition() + ", going to " + coming.errandPlace());
            check(ShopCounters.shopOf(coming).isEmpty(), "a shopkeeper on the way to work opened the shop in the street");
            coming.moveTo(door.getX() + 0.5, door.getY() - 1, door.getZ() + 1.5, 0f, 0f);
            HouseholdService.pass(room.level, List.of(room.visitor));
            check(!coming.isAlive() && HouseholdService.commuterCount() == 0, "the shopkeeper did not go in at the door");
            check(room.level.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "the street door did not open for the shopkeeper");
            // the same hour: nobody comes twice, and the shopkeeper is now behind the counter
            runHour(room, day, 9, () -> false);
            check(HouseholdService.commuterCount() == 0 && room.bodies().size() == 1 && room.bodies().get(0).role().equals("worker"), "after going in the shopkeeper is not simply at work: " + room.bodies().size());
            // six in the evening, with the visitor out of the shop: the shopkeeper comes out of the door and goes down the street
            room.visitor.setPos(room.chunk.getMinBlockX() + 14.5, room.streetY, room.chunk.getMinBlockZ() + 15.5);
            room.level.setBlock(door, room.level.getBlockState(door).setValue(net.minecraft.world.level.block.DoorBlock.OPEN, false), 2);
            check(runHour(room, day, 18, () -> HouseholdService.commuterCount() > 0), "nobody went home at six");
            TownspersonEntity going = room.bodies().stream().filter(b -> HouseholdService.commuteOf(b) != null).findFirst().orElseThrow();
            check(!HouseholdService.commuteOf(going).entering() && going.blockPosition().distSqr(door) <= 4, "the shopkeeper did not leave from just inside the door: " + going.blockPosition());
            check(room.level.getBlockState(door).getValue(net.minecraft.world.level.block.DoorBlock.OPEN), "the street door did not open to let the shopkeeper out");
            check(room.bodies().stream().noneMatch(b -> HouseholdService.commuteOf(b) == null), "the shopkeeper was both at the counter and on the way home");
            // nobody to see them: they are simply gone
            room.visitor.setPos(room.visitor.getX() + 200, room.visitor.getY(), room.visitor.getZ());
            HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().isEmpty() && HouseholdService.commuterCount() == 0, "a commuter stayed with nobody near");
            room.level.setBlock(door.above(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            room.level.setBlock(door, Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aResidentAtHomeIsAWitnessThatBringsThePoliceAndTheForcedDoorIsRemembered(GameTestHelper helper) throws Exception {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(TYPE, house()));
        var noAlarm = new CityBuildings.SecurityClass("test_house", "deadbolt", true, "", 0, "test house", new CityBuildings.Response(0.0, 60, 1, 60));
        try (Room room = new Room(helper, "uc_hh_d", TYPE, noAlarm)) {
            long day = dayWhere(d -> presentAt(room, house(), 23, d) >= 1);
            HouseholdService.dayTimeOverride = clock(day, 23);
            for (int pass = 0; pass < 6; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            check(!room.bodies().isEmpty(), "setup: nobody was home");
            BlockPos door = new BlockPos(room.chunk.getMinBlockX() + 7, room.streetY + 1, room.chunk.getMinBlockZ() + 2);
            room.visitor.setPos(door.getX() + 0.5, door.getY(), door.getZ() + 1.5);
            long now = LockService.clock(room.level.getServer());
            // the control: a break-in of the same building when nobody is in is seen by nobody (the street here is empty)
            List<TownspersonEntity> inside = room.bodies();
            for (TownspersonEntity body : inside) body.setPos(body.getX(), body.getY() + 40, body.getZ());
            for (int t = 0; t < 200; t++) PoliceResponse.noticed(room.visitor, new BuildingBreachEvent(room.visitor.getUUID(), door, "valewood", "test_house", BuildingBreachEvent.Outcome.OPENED, now + t, false, room.visitor));
            check(PoliceResponse.dispatches() == 0, "the police came to an empty house nobody saw");
            for (TownspersonEntity body : inside) body.setPos(body.getX(), body.getY() - 40, body.getZ());
            int tries = 0;
            while (PoliceResponse.dispatches() == 0 && tries < 400) {
                PoliceResponse.noticed(room.visitor, new BuildingBreachEvent(room.visitor.getUUID(), door, "valewood", "test_house", BuildingBreachEvent.Outcome.OPENED, now + 1000 + tries++, false, room.visitor));
            }
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).why.equals("witness"), "a resident at home never called the police in " + tries + " tries");
            // the household remembers the forced door, and says so to the thief and to a stranger differently
            new HouseholdService().breach(new BuildingBreachEvent(room.visitor.getUUID(), door, "valewood", "test_house", BuildingBreachEvent.Outcome.OPENED, now, false, room.visitor));
            var mem = HouseholdService.memory(room.level).byBuilding.get(room.building.key());
            check(mem != null && mem.intruder().equals(room.visitor.getUUID()) && mem.seen(), "the household did not remember who forced its door: " + mem);
            TownspersonEntity resident = room.bodies().get(0);
            ServerPlayer stranger = player(helper, "uc_hh_e", "smith");
            String toThief = HouseholdService.say(room.visitor, resident, false), toStranger = HouseholdService.say(stranger, resident, false);
            check(toThief.matches(".*(know your face|saw you|saw what you did).*") && toStranger.matches(".*(forced our lock|break-in|picked the lock).*") && !toThief.equals(toStranger), "the resident did not speak differently to the thief: '" + toThief + "' / '" + toStranger + "'");
            // the record survives being saved, and is bounded
            CompoundTag saved = HouseholdService.memory(room.level).save(new CompoundTag());
            var restored = HouseholdService.Memory.load(saved);
            check(restored.byBuilding.get(room.building.key()).equals(mem), "the memory did not survive a save");
            var big = new HouseholdService.Memory();
            for (int i = 0; i < HouseholdService.MEMORY_LIMIT + 20; i++) big.remember(i, new HouseholdService.Mem(UUID.randomUUID(), i, false));
            check(big.byBuilding.size() == HouseholdService.MEMORY_LIMIT && !big.byBuilding.containsKey(0L) && big.byBuilding.containsKey((long) HouseholdService.MEMORY_LIMIT + 19), "the memory is not bounded to its newest entries");
            HouseholdService.memory(room.level).byBuilding.remove(room.building.key());
            for (TownspersonEntity body : room.bodies()) body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBreachPostedOnTheRealBusReachesBothThePoliceAndTheHousehold(GameTestHelper helper) {
        // the other cases call the handlers directly; this one posts the event the lock posts, on the Forge bus the game registered its listeners on
        BuildingSpots.INSTANCE.replaceForTest(Map.of(TYPE, house()));
        var alarmed = new CityBuildings.SecurityClass("test_house", "deadbolt", true, "", 0, "test house", new CityBuildings.Response(1.0, 60, 1, 60));
        try (Room room = new Room(helper, "uc_hh_g", TYPE, alarmed)) {
            BlockPos door = new BlockPos(room.chunk.getMinBlockX() + 7, room.streetY + 1, room.chunk.getMinBlockZ() + 2);
            room.visitor.setPos(door.getX() + 0.5, door.getY(), door.getZ() + 1.5);
            long now = LockService.clock(room.level.getServer());
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new BuildingBreachEvent(room.visitor.getUUID(), door, "valewood", "test_house", BuildingBreachEvent.Outcome.OPENED, now, false, room.visitor));
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).why.equals("alarm"), "the police did not hear a breach posted on the bus: " + PoliceResponse.status());
            var mem = HouseholdService.memory(room.level).byBuilding.get(room.building.key());
            check(mem != null && mem.intruder().equals(room.visitor.getUUID()), "the household did not hear a breach posted on the bus");
            HouseholdService.memory(room.level).byBuilding.remove(room.building.key());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBuildingWithNoSpotsHasNobodyInIt(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of());
        try (Room room = new Room(helper, "uc_hh_f", TYPE, null)) {
            HouseholdService.dayTimeOverride = NIGHT;
            for (int pass = 0; pass < 6; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().isEmpty() && HouseholdService.activeCount() == 0, "people appeared in a building nobody furnished");
            check(room.level.getEntitiesOfClass(TownspersonEntity.class, new AABB(room.visitor.blockPosition()).inflate(32)).stream().allMatch(t -> t.homeKey() == 0), "a street person claims a house");
        }
        helper.succeed();
    }

    /**
     * A person serving at a spot stands beside it on its side of any wall (D-178): smith run 3 found the warehouse's keeper standing in the yard outside its east wall, two cells from the desk in the
     * corner, where nobody inside could reach them. A counter between leaves head height open and still counts; a wall does not, straight or round a corner.
     */
    @GameTest(template = "empty")
    public static void aServingSpotIsNeverStoodAtFromBeyondAWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spot = helper.absolutePos(new BlockPos(2, 2, 2));
        check(HouseholdService.besideSpot(level, spot, new int[] {1, 0}), "the cell next to a spot is not beside it");
        level.setBlockAndUpdate(spot.east(), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(spot.east().above(), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        check(!HouseholdService.besideSpot(level, spot, new int[] {2, 0}), "a cell beyond a wall counts as beside the spot");
        level.setBlockAndUpdate(spot.west(), net.minecraft.world.level.block.Blocks.OAK_SLAB.defaultBlockState());
        check(HouseholdService.besideSpot(level, spot, new int[] {-2, 0}), "a counter between the spot and the cell shuts it off");
        level.setBlockAndUpdate(spot.south(), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        level.setBlockAndUpdate(spot.south().above(), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState());
        check(!HouseholdService.besideSpot(level, spot, new int[] {1, 1}), "a corner walled on both sides counts as beside the spot");
        check(HouseholdService.besideSpot(level, spot, new int[] {-1, -1}), "an open corner is not beside the spot");
        helper.succeed();
    }
}
