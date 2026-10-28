package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.id;
import static io.github.stoffe101.undergroundcity.CrewGameTests.rejected;

/**
 * A property follows the dwelling, not the chunk (D-206), on invented two-chunk buildings: a detached house over two chunks is one home with a box in each; two terraced houses side by side
 * are two homes, and one tenant's key never opens the other's door; a block of flats over two chunks lets each flat on its own, its shared street door opens for every tenant and a flat's own
 * door for that flat's only; a burglar's permit covers the whole building and still never reaches a home; a home of several boxes survives a restart, and an older save of one box still loads.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DwellingGameTests {
    private DwellingGameTests() {}

    private static final String DETACHED = "underground_city:test_detached", TERRACE = "underground_city:test_terrace", BLOCK = "underground_city:test_flatblock";
    private static final String PART_W = "underground_city:test_part_w", PART_E = "underground_city:test_part_e";

    /** Two parts side by side, west (column 0) and east (column 1), each a single-unit plan of its own: what a part's own plan says is never let one chunk at a time. */
    private static Map<String, BuildingSpots.Plan> partPlans() {
        return Map.of(PART_W, new BuildingSpots.Plan(PART_W, "house", "valewood", List.of(new BuildingSpots.Spot("door", 7, 1, 15, "south", "")), List.of(new BuildingSpots.Unit("house", "House", 1, 1, 1, 14, 4, 14))),
            PART_E, new BuildingSpots.Plan(PART_E, "house", "valewood", List.of(new BuildingSpots.Spot("door", 7, 1, 15, "south", "")), List.of(new BuildingSpots.Unit("house", "House", 1, 1, 1, 14, 4, 14))));
    }

    private static CityDwellings.Building dwellings(String multi, String json) {
        return CityDwellings.parse(multi, JsonParser.parseString(json).getAsJsonObject());
    }

    private static final String PARTS = "\"size\":[2,1],\"parts\":[[\"" + PART_W + "\"],[\"" + PART_E + "\"]]";

    private static Map<String, CityDwellings.Building> files() {
        return Map.of(
            // one house whose rooms run from x 1 in the west part to x 14 in the east part: the border between the chunks is inside it
            DETACHED, dwellings(DETACHED, "{\"schema_version\":1," + PARTS + ",\"dwellings\":[{\"id\":\"house\",\"title\":\"House\",\"rooms\":["
                + "{\"at\":[0,0],\"box\":[1,1,1,15,4,14]},{\"at\":[1,0],\"box\":[0,1,1,14,4,14]}]}]}"),
            // two houses that share the wall at the border (x 15 of the west part and x 0 of the east part are walls)
            TERRACE, dwellings(TERRACE, "{\"schema_version\":1," + PARTS + ",\"dwellings\":["
                + "{\"id\":\"house_a\",\"title\":\"No. 1\",\"rooms\":[{\"at\":[0,0],\"box\":[1,1,1,14,4,14]}]},"
                + "{\"id\":\"house_b\",\"title\":\"No. 2\",\"rooms\":[{\"at\":[1,0],\"box\":[1,1,1,14,4,14]}]}]}"),
            // flats: a corridor along z 1 to 3 in both parts belongs to nobody; flat 1 and flat 2 on the ground floor, one each side; flat 3 upstairs over both parts, in two boxes
            BLOCK, dwellings(BLOCK, "{\"schema_version\":1," + PARTS + ",\"dwellings\":["
                + "{\"id\":\"flat_1\",\"title\":\"Flat 1\",\"rooms\":[{\"at\":[0,0],\"box\":[1,1,5,14,3,14]}]},"
                + "{\"id\":\"flat_2\",\"title\":\"Flat 2\",\"rooms\":[{\"at\":[1,0],\"box\":[1,1,5,14,3,14]}]},"
                + "{\"id\":\"flat_3\",\"title\":\"Flat 3\",\"rooms\":[{\"at\":[0,0],\"box\":[1,5,1,15,7,14]},{\"at\":[1,0],\"box\":[0,5,1,14,7,14]}]}]}"));
    }

    /** The two chunks of a multi-chunk building at the test's origin, Lost Cities' answer for each. */
    private record Pair(CityBuildings.Building west, CityBuildings.Building east) {}

    private static Pair pair(GameTestHelper helper, String multi) {
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4;
        var security = CityBuildings.INSTANCE.securityClass("house");
        return new Pair(new CityBuildings.Building("valewood", cx, cz, PART_W, "", 2, 0, origin.getY(), security, new CityBuildings.Multi(multi, 0, 0, 2, 1)),
            new CityBuildings.Building("valewood", cx + 1, cz, PART_E, "", 2, 0, origin.getY(), security, new CityBuildings.Multi(multi, 1, 0, 2, 1)));
    }

    /** Runs {@code body} with the two chunks of {@code multi} as the world's buildings, every invented file loaded and the multi-chunk buildings fully let. */
    private static void with(GameTestHelper helper, String multi, Body body) throws Exception {
        Pair p = pair(helper, multi);
        var previousTemplates = PropertyCatalog.INSTANCE.installCityTemplatesForTest(Map.of(DETACHED, new PropertyCatalog.CityTemplate(DETACHED, 100, PropertyGameTests.houseTerms("template")),
            TERRACE, new PropertyCatalog.CityTemplate(TERRACE, 100, PropertyGameTests.houseTerms("template")), BLOCK, new PropertyCatalog.CityTemplate(BLOCK, 100, PropertyGameTests.flatTerms("template")),
            PART_W, new PropertyCatalog.CityTemplate(PART_W, 100, PropertyGameTests.houseTerms("template")), PART_E, new PropertyCatalog.CityTemplate(PART_E, 100, PropertyGameTests.houseTerms("template"))));
        var previousPlans = BuildingSpots.INSTANCE.plansForTest();
        BuildingSpots.INSTANCE.replaceForTest(partPlans());
        var previousDwellings = CityDwellings.INSTANCE.replaceForTest(files());
        CityBuildings.INSTANCE.overrideForTest((x, z) -> x == p.west().chunkX() && z == p.west().chunkZ() ? Optional.of(p.west()) : x == p.east().chunkX() && z == p.east().chunkZ() ? Optional.of(p.east()) : Optional.empty());
        try (PropertyGameTests.Fix f = new PropertyGameTests.Fix(helper, Map.of())) {
            body.run(f, p);
        } finally {
            CityBuildings.INSTANCE.overrideForTest(null);
            CityDwellings.INSTANCE.replaceForTest(previousDwellings);
            BuildingSpots.INSTANCE.replaceForTest(previousPlans);
            PropertyCatalog.INSTANCE.installCityTemplatesForTest(previousTemplates);
            BurglaryDamage.resetForTest();
        }
    }

    private interface Body { void run(PropertyGameTests.Fix f, Pair p) throws Exception; }

    /** A dwellings file the loader must refuse, for the reason given (its message names it). */
    private static void refused(Runnable parse, String reason, String why) {
        try { parse.run(); } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(reason), why + " was refused for another reason: " + expected.getMessage());
            return;
        }
        throw new AssertionError("A dwellings file was accepted: " + why);
    }

    private static BlockPos at(CityBuildings.Building b, int x, int y, int z) { return new BlockPos(b.chunkX() * 16 + x, b.streetY() + y, b.chunkZ() * 16 + z); }
    private static BlockState door(Direction facing) { return Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing); }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "uc_dwellings")
    public static void aDetachedHouseOverTwoChunksIsOneHome(GameTestHelper helper) throws Exception {
        with(helper, DETACHED, (f, p) -> {
            check(CityHomes.ensureBuilding(helper.getLevel(), p.west()) == 1, "the house over two chunks was not registered as one home");
            check(CityHomes.ensureBuilding(helper.getLevel(), p.east()) == 0, "the east chunk registered a second home for the same house");
            List<CityHomes.Place> fromWest = CityHomes.places(helper.getLevel().getSeed(), p.west()), fromEast = CityHomes.places(helper.getLevel().getSeed(), p.east());
            check(fromWest.size() == 1 && fromWest.get(0).id().equals(fromEast.get(0).id()), "the two chunks name the house differently: " + fromWest + " / " + fromEast);
            CrewLedger.Residence house = f.l.residence(fromWest.get(0).id());
            check(house != null && house.boxes.size() == 2 && house.chunks().size() == 2, "the house is not one home of two boxes in two chunks: " + (house == null ? null : house.boxes));
            String dim = house.dimension;
            BlockPos westRoom = at(p.west(), 8, 2, 8), border = at(p.west(), 15, 2, 8), eastRoom = at(p.east(), 8, 2, 8);
            check(house.insideInterior(dim, westRoom) && house.insideInterior(dim, border) && house.insideInterior(dim, eastRoom), "a room of the house is outside it");
            check(!house.insideInterior(dim, at(p.east(), 15, 2, 8)) && house.insideShell(dim, at(p.east(), 15, 2, 8)), "the east wall is not the house's shell");
            check(CityHomes.placeOfDoor(helper.getLevel().getSeed(), p.east(), at(p.east(), 7, 1, 15), door(Direction.SOUTH)) != null, "the street door in the east chunk is not the house's own");
            // one tenant, rights in both chunks; a stranger none
            ServerPlayer tenant = f.person(helper, "uc_dw_tenant", "farmer", 5_000), stranger = f.person(helper, "uc_dw_stranger", "smith", 5_000);
            tenant.moveTo(westRoom.getX() + 0.5, westRoom.getY(), westRoom.getZ() + 0.5);
            PropertyService.rent(tenant, id(), house.id, false, false);
            check(f.l.residence(house.id).owned(), "the house was not rented");
            for (BlockPos pos : List.of(westRoom, border, eastRoom)) {
                check(ResidenceGuard.allowed(f.l, house, tenant.getUUID(), dim, pos, ResidenceGuard.Act.PLACE), "the tenant cannot build at " + pos.toShortString());
                check(!ResidenceGuard.allowed(f.l, house, stranger.getUUID(), dim, pos, ResidenceGuard.Act.DOOR), "a stranger may enter at " + pos.toShortString());
            }
            check(CityHomes.holdsKey(tenant, p.east(), at(p.east(), 7, 1, 15), door(Direction.SOUTH)) && !CityHomes.holdsKey(stranger, p.east(), at(p.east(), 7, 1, 15), door(Direction.SOUTH)),
                "the house's door does not open for its tenant alone");
            // a restart keeps both boxes and the tenancy
            f.rig.restart();
            f.l = f.rig.ledger;
            CrewLedger.Residence again = f.l.residence(house.id);
            check(again != null && again.boxes.equals(house.boxes) && again.owned() && again.owner.equals(tenant.getUUID()), "the house came back from a restart as " + (again == null ? null : again.boxes));
            check(f.l.residenceAt(dim, eastRoom) == again, "after the restart the east room is not the house's");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "uc_dwellings")
    public static void twoTerracedHousesAreTwoHomesAndOneKeyNeverOpensTheOther(GameTestHelper helper) throws Exception {
        with(helper, TERRACE, (f, p) -> {
            check(CityHomes.ensureBuilding(helper.getLevel(), p.west()) == 2, "the terrace is not two homes");
            List<CityHomes.Place> places = CityHomes.places(helper.getLevel().getSeed(), p.west());
            CrewLedger.Residence a = f.l.residence(places.get(0).id()), b = f.l.residence(places.get(1).id());
            ServerPlayer alice = f.person(helper, "uc_dw_alice", "farmer", 5_000), bob = f.person(helper, "uc_dw_bob", "vintner", 5_000);
            alice.moveTo(at(p.west(), 8, 2, 8).getX(), at(p.west(), 8, 2, 8).getY(), at(p.west(), 8, 2, 8).getZ());
            bob.moveTo(at(p.east(), 8, 2, 8).getX(), at(p.east(), 8, 2, 8).getY(), at(p.east(), 8, 2, 8).getZ());
            // Alice's house is her Crew's: Carol, in the Crew, has its key too; neither has Bob's
            ServerPlayer carol = f.person(helper, "uc_dw_carol", "smith", 5_000);
            f.l.create(alice.getUUID(), "uc_dw_alice", "Row Crew");
            f.l.accept(f.l.invite(alice.getUUID(), carol.getUUID(), "uc_dw_carol", "uc_dw_alice").id(), carol.getUUID(), "uc_dw_carol");
            PropertyService.rent(alice, id(), a.id, true, false);
            PropertyService.rent(bob, id(), b.id, false, false);
            check(f.l.residence(a.id).ownerType == CrewLedger.OwnerType.CREW, "Alice's house is not her Crew's");
            BlockPos aliceDoor = at(p.west(), 7, 1, 15), bobDoor = at(p.east(), 7, 1, 15);
            check(CityHomes.holdsKey(alice, p.west(), aliceDoor, door(Direction.SOUTH)) && !CityHomes.holdsKey(alice, p.east(), bobDoor, door(Direction.SOUTH)), "Alice's key opens Bob's door, or not her own");
            check(CityHomes.holdsKey(bob, p.east(), bobDoor, door(Direction.SOUTH)) && !CityHomes.holdsKey(bob, p.west(), aliceDoor, door(Direction.SOUTH)), "Bob's key opens Alice's door, or not his own");
            check(CityHomes.holdsKey(carol, p.west(), aliceDoor, door(Direction.SOUTH)) && !CityHomes.holdsKey(carol, p.east(), bobDoor, door(Direction.SOUTH)), "a Crew member's key is not the Crew house's alone");
            check(ResidenceGuard.allowed(f.l, f.l.residence(a.id), carol.getUUID(), a.dimension, at(p.west(), 8, 2, 8), ResidenceGuard.Act.DOOR), "the Crew member cannot enter the Crew's house");
            String dim = a.dimension;
            check(!ResidenceGuard.allowed(f.l, b, alice.getUUID(), dim, at(p.east(), 8, 2, 8), ResidenceGuard.Act.CONTAINER), "Alice can open Bob's chests");
            check(f.l.residenceAt(dim, at(p.west(), 15, 2, 8)) == a && f.l.residenceAt(dim, at(p.east(), 0, 2, 8)) == b, "the shared wall is not each house's shell on its own side");
            // the shells may touch, but a home that reaches into a neighbour's is refused
            rejected(() -> f.l.registerResidence("test:intruder", "Intruder", "small_house", dim, "city",
                List.of(new CrewLedger.Box(at(p.west(), 10, 1, 10), at(p.west(), 15, 3, 12))), 100, 0, 100), "a home reaching into the neighbour's shell");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "uc_dwellings")
    public static void flatsOverTwoChunksShareTheirEntranceAndNeverEachOther(GameTestHelper helper) throws Exception {
        with(helper, BLOCK, (f, p) -> {
            check(CityHomes.ensureBuilding(helper.getLevel(), p.east()) == 3, "the block's three flats were not registered");
            List<CityHomes.Place> places = CityHomes.places(helper.getLevel().getSeed(), p.west());
            CrewLedger.Residence flat1 = f.l.residence(places.get(0).id()), flat2 = f.l.residence(places.get(1).id()), flat3 = f.l.residence(places.get(2).id());
            check(flat3.boxes.size() == 2 && flat3.chunks().size() == 2, "flat 3 is not one flat over both chunks");
            ServerPlayer one = f.person(helper, "uc_dw_one", "farmer", 5_000), three = f.person(helper, "uc_dw_three", "smith", 5_000), stranger = f.person(helper, "uc_dw_nobody", "technician", 5_000);
            one.moveTo(at(p.west(), 8, 2, 8).getX(), at(p.west(), 8, 2, 8).getY(), at(p.west(), 8, 2, 8).getZ());
            three.moveTo(at(p.east(), 8, 6, 8).getX(), at(p.east(), 8, 6, 8).getY(), at(p.east(), 8, 6, 8).getZ());
            PropertyService.rent(one, id(), flat1.id, false, false);
            PropertyService.rent(three, id(), flat3.id, false, false);
            // the street door opens on the corridor, which is nobody's: every tenant's key opens it, a stranger's does not
            BlockPos street = at(p.east(), 7, 1, 0);
            check(CityHomes.placeOfDoor(helper.getLevel().getSeed(), p.east(), street, door(Direction.NORTH)) == null, "the street door into the corridor belongs to a flat");
            check(CityHomes.holdsKey(one, p.east(), street, door(Direction.NORTH)) && CityHomes.holdsKey(three, p.east(), street, door(Direction.NORTH))
                && !CityHomes.holdsKey(stranger, p.east(), street, door(Direction.NORTH)), "the shared street door does not open for every tenant and nobody else");
            // flat 2's own door (from the corridor at z 4 into the flat) is flat 2's: neither the tenant of flat 1 nor of flat 3 has its key
            BlockPos flat2Door = at(p.east(), 7, 1, 4);
            check(CityHomes.placeOfDoor(helper.getLevel().getSeed(), p.east(), flat2Door, door(Direction.NORTH)) != null, "flat 2's door is not its own");
            check(!CityHomes.holdsKey(one, p.east(), flat2Door, door(Direction.NORTH)) && !CityHomes.holdsKey(three, p.east(), flat2Door, door(Direction.NORTH)), "a neighbour's key opens flat 2");
            String dim = flat1.dimension;
            check(!ResidenceGuard.allowed(f.l, flat2, one.getUUID(), dim, at(p.east(), 8, 2, 8), ResidenceGuard.Act.PLACE), "flat 1's tenant can build in flat 2");
            check(ResidenceGuard.allowed(f.l, flat3, three.getUUID(), dim, at(p.west(), 8, 6, 8), ResidenceGuard.Act.PLACE), "flat 3's tenant cannot build in its west half");
            // a burglar who picked the building's lock may break into the corridor's things in both chunks, and into no flat, let or held
            long now = LockService.clock(helper.getLevel().getServer());
            BurglaryDamage.grantForTest(stranger, p.west(), now);
            check(BurglaryDamage.permitted(stranger, p.east(), now), "the permit does not cover the whole building");
            for (BlockPos pos : List.of(at(p.west(), 8, 2, 8), at(p.east(), 8, 2, 8), at(p.east(), 8, 6, 8), at(p.west(), 15, 2, 8), at(p.east(), 8, 4, 8))) {
                check(BurglaryDamage.judge(stranger, helper.getLevel(), pos.getX() >> 4 == p.west().chunkX() ? p.west() : p.east(), pos, now) == BurglaryDamage.Verdict.HOME,
                    "a burglar may break " + pos.toShortString() + ", in or round a flat");
            }
            // a single lot's permit is its own, as before
            var lotA = new CityBuildings.Building("valewood", p.west().chunkX(), p.west().chunkZ() + 3, PART_W, "", 2, 0, p.west().streetY(), p.west().security());
            var lotB = new CityBuildings.Building("valewood", p.west().chunkX() + 1, p.west().chunkZ() + 3, PART_E, "", 2, 0, p.west().streetY(), p.west().security());
            BurglaryDamage.grantForTest(stranger, lotA, now);
            check(BurglaryDamage.permitted(stranger, lotA, now) && !BurglaryDamage.permitted(stranger, lotB, now), "a lot's permit reached the next lot");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200, batch = "uc_dwellings")
    public static void nothingIsGuessedWhereADwellingEnds(GameTestHelper helper) throws Exception {
        with(helper, "underground_city:test_no_file", (f, p) -> {
            check(CityHomes.places(helper.getLevel().getSeed(), p.west()).isEmpty() && CityHomes.ensureBuilding(helper.getLevel(), p.west()) == 0,
                "a multi-chunk building with no dwellings let its parts one chunk at a time");
        });
        // a part that is not the one the file names at its place lets nothing
        with(helper, DETACHED, (f, p) -> {
            var swapped = new CityBuildings.Building("valewood", p.west().chunkX(), p.west().chunkZ(), PART_E, "", 2, 0, p.west().streetY(), p.west().security(), p.west().multi());
            check(CityHomes.places(helper.getLevel().getSeed(), swapped).isEmpty(), "a chunk whose part is not the file's still lets");
        });
        // a file whose dwellings reach into each other's shell, or whose room lies outside the building, is refused whole
        refused(() -> dwellings(TERRACE, "{\"schema_version\":1," + PARTS + ",\"dwellings\":[{\"id\":\"aa\",\"rooms\":[{\"at\":[0,0],\"box\":[1,1,1,15,4,14]}]},"
            + "{\"id\":\"bb\",\"rooms\":[{\"at\":[1,0],\"box\":[0,1,1,14,4,14]}]}]}"), "no wall between", "two houses with no wall between them");
        refused(() -> dwellings(TERRACE, "{\"schema_version\":1," + PARTS + ",\"dwellings\":[{\"id\":\"aa\",\"rooms\":[{\"at\":[2,0],\"box\":[1,1,1,14,4,14]}]}]}"), "bad room", "a room outside the building");
        refused(() -> dwellings(TERRACE, "{\"schema_version\":1,\"size\":[2,1],\"parts\":[[\"" + PART_W + "\"]],\"dwellings\":[{\"id\":\"aa\",\"rooms\":[{\"at\":[0,0],\"box\":[1,1,1,14,4,14]}]}]}"),
            "columns", "a parts grid that is not the building's size");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_dwellings")
    public static void aSaveFromBeforeDwellingsLoadsAsOneBox(GameTestHelper helper) throws Exception {
        try (CrewGameTests.Rig rig = new CrewGameTests.Rig(helper)) {
            BlockPos base = helper.absolutePos(new BlockPos(0, 2, 0));
            rig.ledger.registerResidence("test:old_shape", "Old", "small_house", helper.getLevel().dimension().location().toString(), "city", base, base.offset(4, 2, 4), 100, 0);
            rig.restart();
            CrewLedger.Residence r = rig.ledger.residence("test:old_shape");
            check(r != null && r.boxes.size() == 1 && r.min.equals(base) && r.max.equals(base.offset(4, 2, 4)), "a one-box home did not come back as one box: " + (r == null ? null : r.boxes));
        }
        helper.succeed();
    }
}
