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
        for (var who : a) check(who.role().equals("resident") && (who.skin() % PopulationPlan.OUTFITS == 7 || who.skin() % PopulationPlan.OUTFITS == 8), "a resident is not dressed as one: skin " + who.skin());
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

    @GameTest(template = "empty")
    public static void residentsAppearIndoorsAtTheirBedsWhenAPlayerIsNearAndLeaveWhenNobodyIs(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(TYPE, house()));
        try (Room room = new Room(helper, "uc_hh_a", TYPE, null)) {
            HouseholdService.dayTimeOverride = NIGHT;
            for (int pass = 0; pass < 6; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            var bodies = room.bodies();
            var expected = HouseholdPlan.residents("valewood", room.level.getSeed(), room.building.key(), house(), names()).stream()
                .filter(who -> HouseholdPlan.where(who, house(), room.level.getSeed(), 23, 0) != HouseholdPlan.AWAY).count();
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
            HouseholdService.dayTimeOverride = NIGHT;
            for (int pass = 0; pass < 6; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            int before = room.bodies().size();
            check(before >= 1, "setup: nobody was at home at night");
            HouseholdService.dayTimeOverride = NOON;
            // the visitor is in the room, so nobody leaves in front of them whatever their day holds
            HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().size() == before, "a resident left in front of a player's eyes");
            // 30 blocks away (near enough to see the building, too far to see a face) the working day takes them out
            room.visitor.setPos(room.chunk.getMinBlockX() + 7.5, room.streetY + 1, room.chunk.getMinBlockZ() + 7.5 + 30);
            HouseholdService.pass(room.level, List.of(room.visitor));
            var left = before - room.bodies().size();
            var due = HouseholdPlan.residents("valewood", room.level.getSeed(), room.building.key(), house(), names()).stream()
                .filter(who -> HouseholdPlan.where(who, house(), room.level.getSeed(), 12, 0) == HouseholdPlan.AWAY).count();
            check(left >= 1 && HouseholdService.removed >= left, "nobody went out for the working day: " + before + " before, " + room.bodies().size() + " after (" + due + " away at noon)");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theShopkeeperStandsAtTheCounterByDayAndTheShopIsEmptyAtNight(GameTestHelper helper) {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(SHOP, shop()));
        try (Room room = new Room(helper, "uc_hh_c", SHOP, null)) {
            HouseholdService.dayTimeOverride = NIGHT;
            for (int pass = 0; pass < 4; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            check(room.bodies().isEmpty(), "a shop was staffed at night");
            HouseholdService.dayTimeOverride = (10 - 6) * 1000L;
            for (int pass = 0; pass < 4; pass++) HouseholdService.pass(room.level, List.of(room.visitor));
            var keeper = HouseholdPlan.residents("valewood", room.level.getSeed(), room.building.key(), shop(), names()).get(0);
            boolean due = HouseholdPlan.where(keeper, shop(), room.level.getSeed(), 10, 0) != HouseholdPlan.AWAY;
            check(room.bodies().size() == (due ? 1 : 0), "the shopkeeper's presence at ten does not match the plan (due " + due + ", present " + room.bodies().size() + ")");
            for (TownspersonEntity body : room.bodies()) {
                check(body.role().equals("worker") && room.spot(shop().spots().get(1)).distSqr(body.blockPosition()) <= 9, "the shopkeeper is not at the counter: " + body.blockPosition());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aResidentAtHomeIsAWitnessThatBringsThePoliceAndTheForcedDoorIsRemembered(GameTestHelper helper) throws Exception {
        BuildingSpots.INSTANCE.replaceForTest(Map.of(TYPE, house()));
        var noAlarm = new CityBuildings.SecurityClass("test_house", "deadbolt", true, "", 0, "test house", new CityBuildings.Response(0.0, 60, 1, 60));
        try (Room room = new Room(helper, "uc_hh_d", TYPE, noAlarm)) {
            HouseholdService.dayTimeOverride = NIGHT;
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
}
