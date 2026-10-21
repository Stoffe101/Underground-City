package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * City-building protection and burglary (D-105), proved on one representative apartment block before it is scaled: a small house is built in the test world and Lost Cities' plan is answered
 * for it through a test seam, so what is tested is Core's guard, door lock, picking, stash and persistence, and not whether a generated city happens to have a house at some coordinate.
 * The real generated city is checked by {@code BurglaryHarness}-style runs, not here. Every test pairs a refusal with the control that shows the action works for someone entitled (or
 * outside the volume), so nothing passes merely because nothing happens.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildingSecurityGameTests {
    private BuildingSecurityGameTests() {}

    /** A two-floor "apartment block" of the flat_block class (a deadbolt door, a stash chance of 70 percent), 7 by 7 with its door in the north wall. */
    static final class House implements AutoCloseable {
        final ServerLevel level;
        final BlockPos base, doorLower;
        final CityBuildings.Building building;

        House(GameTestHelper helper) { this(helper, Direction.NORTH); }

        /** {@code doorFacing} NORTH: the door's open side faces the street, so its leaf stands on the room side of its block; SOUTH: the other way round (both occur in generated buildings). */
        House(GameTestHelper helper, Direction doorFacing) {
            level = helper.getLevel();
            // Lost Cities' plan, and so a building's volume, is per chunk: the building takes its chunk, its north wall on the chunk's edge, so that the street in front of the door is the
            // neighbouring chunk (a street chunk is not a building). Wherever the test runner put this test (its place moves with the number of tests) the fixture is put there, which is what
            // the tests assumed and what they only got by luck of the layout. Each fixture has a batch to itself, so nothing else builds here at the same time.
            BlockPos placed = helper.absolutePos(new BlockPos(0, 2, 0));
            base = new BlockPos(Math.floorDiv(placed.getX(), 16) * 16 + 4, placed.getY(), Math.floorDiv(placed.getZ(), 16) * 16);
            // the whole chunk is cleared, not only the house: the stash search looks at every column of the chunk, and what earlier batches left standing in it (the survival set's tests build here too) is a place for a stash
            for (int x = -4; x <= 11; x++) for (int z = -3; z <= 15; z++) for (int y = -1; y <= 14; y++) level.setBlock(base.offset(x, y, z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) for (int y = 0; y <= 3; y++) {
                boolean wall = x == 0 || x == 6 || z == 0 || z == 6;
                if (wall) level.setBlock(base.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
            for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) level.setBlock(base.offset(x, 4, z), Blocks.STONE.defaultBlockState(), 2);   // the roof
            for (int x = 1; x <= 5; x++) for (int z = 1; z <= 5; z++) level.setBlock(base.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);   // the floor of the room
            doorLower = base.offset(3, 0, 0);
            level.setBlock(doorLower, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
            level.setBlock(doorLower.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, doorFacing).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
            CityBuildings.SecurityClass flats = CityBuildings.INSTANCE.securityClass("flat_block");
            check(flats != null && flats.lockedExterior(), "the flat_block class did not load from data");
            ChunkPos chunk = new ChunkPos(base);
            building = new CityBuildings.Building("ironhaven", chunk.x, chunk.z, "building1", "lostcities:building1", 2, 0, base.getY() - 1, flats);
            CityBuildings.INSTANCE.overrideForTest((cx, cz) -> cx == chunk.x && cz == chunk.z ? Optional.of(building) : Optional.empty());
            LockService.resetForTest();
            BuildingGuard.roofReach = 10;   // the house is five high; other tests build above it in this crowded world
        }

        BlockPos in(int x, int y, int z) { return base.offset(x, y, z); }
        BlockPos outside(int x, int y, int z) { return base.offset(x, y, -z); }

        @Override public void close() {
            CityBuildings.INSTANCE.overrideForTest(null);
            LockService.resetForTest();
            BuildingGuard.roofReach = BuildingGuard.ROOF_REACH;
        }
    }

    private static ServerPlayer visitor(GameTestHelper helper, House h, String name) {
        ServerPlayer p = player(helper, name, "farmer");
        p.setGameMode(GameType.SURVIVAL);
        p.moveTo(h.doorLower.getX() + 0.5, h.doorLower.getY(), h.doorLower.getZ() - 1.5);   // on the street side of the north wall
        return p;
    }

    private static BlockHitResult click(BlockPos against, Direction face) { return new BlockHitResult(Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, against, false); }

    private static InteractionResult use(ServerPlayer player, ItemStack stack, BlockPos against, Direction face) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItemOn(player, player.serverLevel(), stack, InteractionHand.MAIN_HAND, click(against, face));
    }

    /** Knocks on a door with whatever the player already holds (the lockpicks stay in the inventory wherever they are). */
    private static InteractionResult knock(ServerPlayer player, BlockPos door, Direction face) {
        return player.gameMode.useItemOn(player, player.serverLevel(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, click(door, face));
    }

    private static ItemStack picks(int n) { return new ItemStack(UndergroundCity.item("lockpick"), n); }

    // ------------------------------------------------------------------------------------------------ data

    @GameTest(template = "empty", batch = "uc_building")
    public static void aRuleWithAHigherPriorityOutranksTheGenericFallback(GameTestHelper helper) {
        var unordered = List.of(
            new CityBuildings.Rule("*", "", 0, 99, "cottage"),
            new CityBuildings.Rule("*", "underground_city:vw_shop", 0, 99, "storefront", 10),
            new CityBuildings.Rule("valewood", "", 0, 99, "workshop"));
        var ordered = CityBuildings.ordered(unordered);
        check(ordered.get(0).securityClass().equals("storefront"), "the priority rule is not first: " + ordered);
        check(ordered.get(1).securityClass().equals("cottage") && ordered.get(2).securityClass().equals("workshop"), "equal priorities lost their file order: " + ordered);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_building")
    public static void theClassesAndRulesLoadAndAssignEveryKindOfBuilding(GameTestHelper helper) {
        CityBuildings b = CityBuildings.INSTANCE;
        check(!b.classes().isEmpty(), "no building security classes loaded");
        check(b.classify("ironhaven", "building2", 2).id().equals("workshop"), "a low Ironhaven building is a workshop");
        check(b.classify("ironhaven", "building2", 5).id().equals("flat_block"), "a tall Ironhaven building is an apartment block");
        check(b.classify("valewood", "building1", 1).id().equals("cottage"), "a one-storey Valewood building is a cottage");
        check(b.classify("blackwater", "building3", 4).id().equals("office"), "a mid-height Blackwater building is an office");
        check(b.classify("blackwater", "building3", 6).id().equals("flat_tower"), "a tall Blackwater building is an apartment tower");
        check(b.classify("ironhaven", "center10", 3).id().equals("vault"), "the downtown centrepiece is the high-value premises");
        check(!b.classify("valewood", "shopping_open01", 4).lockedExterior(), "a shopping building is public");
        // the tiers climb: a cottage is easier to pick than an apartment tower, which is easier than the high-value premises
        var ladder = List.of("cottage", "flat_block", "flat_tower", "vault");
        for (int i = 1; i < ladder.size(); i++) {
            var easier = LockProfiles.INSTANCE.find(b.securityClass(ladder.get(i - 1)).lockProfile());
            var harder = LockProfiles.INSTANCE.find(b.securityClass(ladder.get(i)).lockProfile());
            check(harder.pins() >= easier.pins() && harder.tolerance() <= easier.tolerance(), ladder.get(i) + " is not at least as hard as " + ladder.get(i - 1));
        }
        for (var c : b.classes().values()) {
            if (c.stashPolicy().isEmpty()) continue;
            check(PropertyPolicies.INSTANCE.find(c.stashPolicy()) != null, c.id() + " names the missing stash policy " + c.stashPolicy());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_building")
    public static void aBuildingVolumeIsTheChunkFromBelowItsCellarsToAboveItsRoof(GameTestHelper helper) {
        var security = CityBuildings.INSTANCE.securityClass("flat_block");
        var building = new CityBuildings.Building("ironhaven", 10, 20, "building1", "lostcities:building1", 3, 2, 71, security);
        check(building.minY() == 71 - 6 * 3 - 2 && building.maxY() == 71 + 18 + 8, "volume " + building.minY() + ".." + building.maxY());
        check(building.contains(new BlockPos(10 * 16 + 5, 71, 20 * 16 + 5)) && building.contains(new BlockPos(10 * 16, building.minY(), 20 * 16 + 15)), "the building does not contain its own blocks");
        check(!building.contains(new BlockPos(9 * 16 + 15, 71, 20 * 16)) && !building.contains(new BlockPos(10 * 16, building.minY() - 1, 20 * 16)) && !building.contains(new BlockPos(10 * 16, building.maxY(), 20 * 16)),
            "the volume reaches beyond the building");
        check(building.key() != new CityBuildings.Building("ironhaven", 10, 21, "x", "x", 1, 0, 71, security).key(), "two buildings share a key");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ protection

    @GameTest(template = "empty", batch = "uc_building_guard")
    public static void aVisitorCannotBreakPlacePourOrBlastInsideABuildingButMayAnywhereElse(GameTestHelper helper) {
        try (House h = new House(helper)) {
            ServerPlayer visitor = visitor(helper, h, "uc_bgv");
            BlockPos wall = h.in(0, 2, 3), window = h.in(6, 2, 3), furniture = h.in(2, 0, 4), free = h.in(3, 0, -3);
            level(h).setBlock(window, Blocks.GLASS.defaultBlockState(), 2);
            level(h).setBlock(furniture, Blocks.CHEST.defaultBlockState(), 2);
            level(h).setBlock(free, Blocks.DIRT.defaultBlockState(), 2);
            // breaking, by the game's own path (which fires Forge's break event)
            for (BlockPos pos : List.of(wall, window, furniture, h.in(3, 4, 3))) {
                visitor.gameMode.destroyBlock(pos);
                check(!level(h).getBlockState(pos).isAir(), "a visitor broke " + pos + " inside a building");
            }
            // the control: outside the volume the same player breaks the same kind of block
            visitor.gameMode.destroyBlock(free);
            check(level(h).getBlockState(free).isAir(), "a visitor could not break dirt outside a building: the guard covers the whole street");
            // placing: the block does not land
            use(visitor, new ItemStack(Items.DIRT), h.in(3, -1, 3), Direction.UP);
            check(level(h).getBlockState(h.in(3, 0, 3)).isAir(), "a visitor placed a block inside the building");
            // pouring and scooping go through the bucket event
            FillBucketEvent pour = new FillBucketEvent(visitor, new ItemStack(Items.WATER_BUCKET), level(h), click(h.in(3, -1, 3), Direction.UP));
            MinecraftForge.EVENT_BUS.post(pour);
            check(pour.isCanceled(), "a visitor was let pour water inside the building");
            FillBucketEvent pourOutside = new FillBucketEvent(visitor, new ItemStack(Items.WATER_BUCKET), level(h), click(h.in(3, -1, -4), Direction.UP));
            MinecraftForge.EVENT_BUS.post(pourOutside);
            check(!pourOutside.isCanceled(), "control: a bucket was stopped outside the building");
            // a blast leaves the building standing and removes the outside block
            Explosion explosion = new Explosion(level(h), null, h.in(3, 1, 3).getX(), h.in(3, 1, 3).getY(), h.in(3, 1, 3).getZ(), 4f, false, Explosion.BlockInteraction.DESTROY);
            level(h).setBlock(free, Blocks.DIRT.defaultBlockState(), 2);
            explosion.getToBlow().addAll(List.of(wall, window, furniture, free));
            MinecraftForge.EVENT_BUS.post(new ExplosionEvent.Detonate(level(h), explosion, new ArrayList<>()));
            check(explosion.getToBlow().equals(List.of(free)), "a blast was allowed to take " + explosion.getToBlow() + " (only the block outside may go)");
            // an operator is never stopped
            ServerPlayer operator = visitor(helper, h, "uc_bgo");
            operator.setGameMode(GameType.CREATIVE);
            BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level(h), wall, level(h).getBlockState(wall), operator);
            check(!MinecraftForge.EVENT_BUS.post(event), "creative mode was stopped by the building guard");
            helper.succeed();
        }
    }

    private static ServerLevel level(House h) { return h.level; }

    /** For a failure message: the first block in the column above the position that stops motion within the roof reach, and where. */
    private static String firstSolidAbove(ServerLevel level, BlockPos pos) {
        for (int y = pos.getY(); y <= pos.getY() + BuildingGuard.roofReach; y++) {
            BlockState state = level.getBlockState(new BlockPos(pos.getX(), y, pos.getZ()));
            if (state.blocksMotion()) return state.getBlock() + " at y " + y;
        }
        return "none";
    }

    @GameTest(template = "empty", batch = "uc_building_volume")
    public static void theGuardCoversTheWallsAndNotTheStreetOrTheSky(GameTestHelper helper) {
        // positions covered by a registered residence are answered by ResidenceGuard, never by this guard (two guards must not fight over the same block)
        try (House h = new House(helper)) {
            check(BuildingGuard.covers(h.level, h.in(0, 1, 3)), "the wall is not covered");
            check(!BuildingGuard.covers(h.level, h.in(0, 1, -2)), "the street is covered (first solid above it: " + firstSolidAbove(h.level, h.in(0, 1, -2)) + "; base " + h.base + ")");
            check(!BuildingGuard.covers(h.level, h.in(0, 40, 3)), "the sky over the roof is covered");
            helper.succeed();
        }
    }

    // ------------------------------------------------------------------------------------------------ the door

    @GameTest(template = "empty", batch = "uc_building_door", timeoutTicks = 200)
    public static void aLockedExteriorDoorOpensOnlyByPickingItAndShutsAgain(GameTestHelper helper) {
        try (House h = new House(helper)) { doorBody(helper, h); }
    }

    private static void doorBody(GameTestHelper helper, House h) {
        ServerPlayer thief = visitor(helper, h, "uc_bgt");
        ServerLevel level = h.level;
        long start = WorkOrderLedger.get(level.getServer()).clock();
        BlockState closed = level.getBlockState(h.doorLower);
        check(closed.getBlock() instanceof DoorBlock && !closed.getValue(DoorBlock.OPEN), "the door did not stand closed");
        Direction facingOfDoor = closed.getValue(DoorBlock.FACING);
        check(BuildingGuard.exterior(level, h.doorLower, closed), "the door of the house is not recognised as an exterior door (the street side sees the sky, the room does not): door " + h.doorLower.toShortString() + " facing " + facingOfDoor
            + ", height at the front " + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, h.doorLower.relative(facingOfDoor).getX(), h.doorLower.relative(facingOfDoor).getZ())
            + ", at the back " + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, h.doorLower.relative(facingOfDoor.getOpposite()).getX(), h.doorLower.relative(facingOfDoor.getOpposite()).getZ())
            + ", min/max build " + level.getMinBuildHeight() + "/" + level.getMaxBuildHeight() + ", first solid above the front: " + firstSolidAbove(level, h.doorLower.relative(facingOfDoor).above())
            + ", above the back: " + firstSolidAbove(level, h.doorLower.relative(facingOfDoor.getOpposite()).above()));
        check(BuildingGuard.outsideSide(level, h.doorLower, closed, thief.position()), "the street side is not recognised as the visitor's side");
        check(!BuildingGuard.outsideSide(level, h.doorLower, closed, Vec3.atCenterOf(h.in(3, 0, 2))), "the room side is recognised as a visitor's side");

        // 1. without a lockpick the click opens nothing
        thief.getInventory().clearContent();
        knock(thief, h.doorLower, Direction.NORTH);
        check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "a visitor opened a locked door with an empty hand");
        check(!LockService.busy(thief.getUUID()), "a lock session started without a lockpick");

        // 2. with a pick the click starts the lock (and still does not open the door)
        thief.getInventory().add(picks(5));
        knock(thief, h.doorLower, Direction.NORTH);
        check(LockService.busy(thief.getUUID()) && !level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the click did not start the lock");
        LockService.Active active = LockService.activeFor(thief.getUUID());
        check(active.target.profile().equals("deadbolt") && active.target.city().equals("ironhaven"), "the lock is not the class's lock: " + active.target);

        // 3. an honest solver opens it through the server's own tick handling, which applies the ending
        LockpickSession.State end = LockpickSessionGameTests.solve(active.session, false, (position, pressing) -> {
            active.position = position; active.pressing = pressing;
            LockService.tickOne(level.getServer(), thief, active);
        });
        check(end == LockpickSession.State.OPENED, "an honest player did not open the deadbolt: " + end);
        check(level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the picked door did not open");
        PropertyLocks locks = PropertyLocks.get(level.getServer());
        long now = WorkOrderLedger.get(level.getServer()).clock();
        check(locks.breached(h.doorLower, now) && !locks.breached(h.doorLower, now + LockService.BREACH_TICKS + 1), "the door is not unlocked for exactly its window");
        check(LockService.picks(thief) >= 4, "more than one lockpick wore out in one lock: " + LockService.picks(thief));
        check(WorkOrderLedger.get(level.getServer()).heat(thief.getUUID(), "ironhaven") >= LockService.HEAT_OPENED, "no heat was recorded for the break-in");
        check(locks.furnished(h.building.key()), "the building was not considered for a stash");

        // 4. somebody else may walk in while it is open (a friend following the picker), and from the inside a door always opens
        ServerPlayer friend = visitor(helper, h, "uc_bgf");
        knock(friend, h.doorLower, Direction.NORTH);   // the door is already open: a click closes it, and a closed unlocked door opens again
        check(!LockService.busy(friend.getUUID()), "a click on a door inside its window started a lock");

        // 5. the window lapses (the saved window is moved into the past: the shared clock of this test world is left alone): the sweep shuts the door and it is locked again
        locks.breach(h.doorLower, WorkOrderLedger.get(level.getServer()).clock() - 1);
        level.setBlock(h.doorLower, level.getBlockState(h.doorLower).setValue(DoorBlock.OPEN, true), 2);   // somebody left it open
        LockService.sweep(level.getServer());
        check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the sweep did not shut a door whose window had lapsed");
        knock(friend, h.doorLower, Direction.NORTH);
        check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "a door opened by hand after its window lapsed");
        check(start <= WorkOrderLedger.get(level.getServer()).clock(), "the clock ran backwards");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the doorway (the survival bypass found in the first human playtest)

    /**
     * Where a person's feet stand when they walk into a closed door and stop against its leaf (holding W): the leaf is 3/16 thick and fills the edge of its block opposite the door's facing, a player is
     * 0.6 wide, so the centre stops 0.3 from the leaf's face. {@code streetSide} picks the sky side of the north wall (the street); the door's front is the street when it faces north.
     */
    static Vec3 flush(BlockPos doorLower, Direction doorFacing, boolean streetSide) {
        boolean streetIsFront = doorFacing == Direction.NORTH;
        double along = streetSide == streetIsFront ? -0.5 + 3.0 / 16.0 + 0.3 : -0.5 - 0.3;   // measured from the block's centre toward the front
        Vec3 centre = Vec3.atBottomCenterOf(doorLower);
        return centre.add(doorFacing.getStepX() * along, 0, doorFacing.getStepZ() * along);
    }

    @GameTest(template = "empty", batch = "uc_building_flush_n", timeoutTicks = 200)
    public static void aVisitorWalkingIntoALockedDoorThatFacesTheStreetCannotOpenIt(GameTestHelper helper) {
        try (House h = new House(helper, Direction.NORTH)) { flushBody(helper, h, Direction.NORTH); }
    }

    @GameTest(template = "empty", batch = "uc_building_flush_s", timeoutTicks = 200)
    public static void aVisitorWalkingIntoALockedDoorThatFacesTheRoomCannotOpenIt(GameTestHelper helper) {
        try (House h = new House(helper, Direction.SOUTH)) { flushBody(helper, h, Direction.SOUTH); }
    }

    private static void flushBody(GameTestHelper helper, House h, Direction facing) {
        ServerLevel level = h.level;
        BlockState closed = level.getBlockState(h.doorLower);
        Vec3 street = flush(h.doorLower, facing, true), room = flush(h.doorLower, facing, false);
        check(BuildingGuard.outsideSide(level, h.doorLower, closed, street), "a visitor standing against the leaf on the street side is taken for somebody inside (door facing " + facing + ", feet at " + street + ")");
        check(!BuildingGuard.outsideSide(level, h.doorLower, closed, room), "somebody standing against the leaf on the room side is taken for a visitor (door facing " + facing + ", feet at " + room + ")");

        for (boolean armed : new boolean[] {false, true}) {   // with and without a lockpick: the playtest saw both walk in
            ServerPlayer thief = visitor(helper, h, armed ? "uc_flp" : "uc_fln");
            thief.getInventory().clearContent();
            if (armed) thief.getInventory().add(picks(3));
            thief.moveTo(street.x, street.y, street.z);
            knock(thief, h.doorLower, Direction.NORTH);
            check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "a visitor walking into the door " + (armed ? "with" : "without") + " a lockpick opened it with a click (door facing " + facing + ")");
            check(LockService.busy(thief.getUUID()) == armed, "a lock session " + (armed ? "did not start" : "started") + " for a visitor " + (armed ? "with" : "without") + " a lockpick");
            LockService.abort(thief);
            LockService.resetForTest();
        }

        // the person inside, standing against the same leaf from the other side, still leaves by it
        ServerPlayer resident = visitor(helper, h, "uc_flr");
        resident.getInventory().clearContent();
        resident.moveTo(room.x, room.y, room.z);
        knock(resident, h.doorLower, Direction.SOUTH);
        check(level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "somebody inside, standing against the door, could not open it to leave (door facing " + facing + ")");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_building_redstone", timeoutTicks = 100)
    public static void aLockedDoorThatRedstoneOpensIsShutAgainWithinATick(GameTestHelper helper) {
        House h = new House(helper, Direction.NORTH);
        h.level.setBlock(h.doorLower.north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);   // the street cell in front of the door
        boolean opened = h.level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN);
        if (!opened) { h.close(); throw new AssertionError("the fixture did not open the door by redstone, so the test would prove nothing"); }
        int before = BuildingGuard.shutByWatch;
        helper.runAfterDelay(4, () -> {
            try {
                check(!h.level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "a locked door stood open after redstone powered it");
                check(BuildingGuard.shutByWatch > before, "the door closed but not by the watch");
                helper.succeed();
            } finally { h.close(); }
        });
    }

    @GameTest(template = "empty", batch = "uc_building_signal", timeoutTicks = 200)
    public static void aVisitorCannotRigAPlateOrABlockOfRedstoneAgainstALockedDoor(GameTestHelper helper) {
        try (House h = new House(helper, Direction.NORTH)) {
            ServerPlayer thief = visitor(helper, h, "uc_bgs");
            thief.getInventory().clearContent();
            BlockPos ground = h.doorLower.north().below();
            h.level.setBlock(ground, Blocks.STONE.defaultBlockState(), 2);
            for (var item : List.of(Items.REDSTONE_BLOCK, Items.STONE_PRESSURE_PLATE, Items.OAK_PRESSURE_PLATE, Items.LEVER, Items.REDSTONE_TORCH)) {
                use(thief, new ItemStack(item), ground, Direction.UP);
                check(h.level.getBlockState(ground.above()).isAir(), "a visitor put " + item + " against a locked door");
            }
            // control: farther than the reach, the same plate is allowed (the street is public)
            BlockPos far = h.doorLower.north(4).below();
            h.level.setBlock(far, Blocks.STONE.defaultBlockState(), 2);
            h.level.setBlock(far.above(), Blocks.AIR.defaultBlockState(), 2);
            use(thief, new ItemStack(Items.STONE_PRESSURE_PLATE), far, Direction.UP);
            check(h.level.getBlockState(far.above()).is(Blocks.STONE_PRESSURE_PLATE), "control: a plate four blocks from the door was refused (the cell holds " + h.level.getBlockState(far.above()).getBlock() + ", the ground " + h.level.getBlockState(far).getBlock() + ")");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", batch = "uc_building_exit", timeoutTicks = 200)
    public static void aDoorOpenedFromInsideToLeaveShutsItselfAndStaysLockedToTheStreet(GameTestHelper helper) {
        try (House h = new House(helper, Direction.NORTH)) {
            ServerLevel level = h.level;
            PropertyLocks locks = PropertyLocks.get(level.getServer());
            ServerPlayer resident = visitor(helper, h, "uc_bge");
            resident.getInventory().clearContent();
            Vec3 room = flush(h.doorLower, Direction.NORTH, false);
            resident.moveTo(room.x, room.y, room.z);
            knock(resident, h.doorLower, Direction.SOUTH);
            check(level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the person inside could not open the door");
            check(locks.openOnPurpose(h.doorLower), "the exit was not remembered");
            check(!locks.breached(h.doorLower, WorkOrderLedger.get(level.getServer()).clock()), "leaving unlocked the door for the street");
            check(!BuildingGuard.enforce(level, h.doorLower), "the watch shut a door somebody had just opened to leave");
            // a visitor outside, on the street, clicks it while it stands open: it shuts, and he cannot open it again
            ServerPlayer thief = visitor(helper, h, "uc_bgt2");
            Vec3 street = flush(h.doorLower, Direction.NORTH, true);
            thief.moveTo(street.x, street.y, street.z);
            thief.getInventory().clearContent();
            knock(thief, h.doorLower, Direction.NORTH);
            check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "a click on the open door did not shut it");
            knock(thief, h.doorLower, Direction.NORTH);
            check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the visitor opened the door after the resident left");
            // the resident leaves it open again and walks off: the sweep shuts it when the few seconds are over
            level.setBlock(h.doorLower, level.getBlockState(h.doorLower).setValue(DoorBlock.OPEN, true), 2);
            locks.closed(h.doorLower);   // the earlier exit is over; this one has run its few seconds (the shared clock of this test world cannot be advanced)
            locks.exitOpen(h.doorLower, WorkOrderLedger.get(level.getServer()).clock() - 1);
            LockService.sweep(level.getServer());
            check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the door that somebody left open was not shut by the sweep");
            check(!locks.openOnPurpose(h.doorLower), "the shut door is still on the sweep's list");
            helper.succeed();
        }
    }

    @GameTest(template = "empty", batch = "uc_building_watch", timeoutTicks = 200)
    public static void aDoorFoundOpenWithoutAReasonIsShutExceptWhereABuilderWorks(GameTestHelper helper) {
        try (House h = new House(helper, Direction.NORTH)) {
            ServerLevel level = h.level;
            level.setBlock(h.doorLower, level.getBlockState(h.doorLower).setValue(DoorBlock.OPEN, true), 2);
            check(BuildingGuard.watchAround(level, h.doorLower.north(2)) == 1, "a door that stood open for no reason was not found within six blocks");
            check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the door was found but not shut");
            // a picked door is somebody's business until its window ends
            PropertyLocks.get(level.getServer()).breach(h.doorLower, WorkOrderLedger.get(level.getServer()).clock() + 200);
            level.setBlock(h.doorLower, level.getBlockState(h.doorLower).setValue(DoorBlock.OPEN, true), 2);
            check(BuildingGuard.watchAround(level, h.doorLower.north(2)) == 0 && level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "the watch shut a door inside its picked window");
            PropertyLocks.get(level.getServer()).breach(h.doorLower, WorkOrderLedger.get(level.getServer()).clock() - 1);
            PropertyLocks.get(level.getServer()).closed(h.doorLower);
            // a builder in creative mode is not fought
            ServerPlayer builder = visitor(helper, h, "uc_bgb");
            builder.setGameMode(GameType.CREATIVE);
            level.addFreshEntity(builder);
            builder.moveTo(h.doorLower.getX() + 0.5, h.doorLower.getY(), h.doorLower.getZ() - 1.5);
            check(BuildingGuard.enforce(level, h.doorLower) == false, "a creative builder's open door was shut");
            builder.discard();
            helper.succeed();
        }
    }

    @GameTest(template = "empty", batch = "uc_building")
    public static void theDoorTableOfAnOlderSaveGetsItsCloseTimeFromItsWindow(GameTestHelper helper) {
        CompoundTag old = new CompoundTag();
        old.putInt("schema_version", 1);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        CompoundTag door = new CompoundTag();
        door.putLong("pos", new BlockPos(3, 70, 4).asLong());
        door.putLong("breached_until", 4000);
        door.putLong("jammed_until", 0);
        door.putBoolean("closed", false);
        door.putInt("breaches", 1);
        list.add(door);
        old.put("doors", list);
        PropertyLocks loaded = PropertyLocks.load(old);
        PropertyLocks.Door d = loaded.door(new BlockPos(3, 70, 4));
        check(d != null && d.closeAt() == 4000 && !d.closed(), "a schema 1 door did not keep its window as its close time: " + d);
        check(loaded.dueToClose(3999).isEmpty() && loaded.dueToClose(4000).size() == 1, "the old window no longer decides when the door is shut");
        CompoundTag saved = loaded.save(new CompoundTag());
        check(saved.getInt("schema_version") == 2 && PropertyLocks.load(saved).door(new BlockPos(3, 70, 4)).closeAt() == 4000, "the upgraded table did not round-trip");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_building_careless", timeoutTicks = 200)
    public static void aCarelessPickSnapsJamsTheLockAndCostsAPick(GameTestHelper helper) {
        try (House h = new House(helper)) { careless(helper, h); }
    }

    private static void careless(GameTestHelper helper, House h) {
        ServerPlayer thief = visitor(helper, h, "uc_bgc");
        ServerLevel level = h.level;
        thief.getInventory().clearContent();
        thief.getInventory().add(picks(3));
        knock(thief, h.doorLower, Direction.NORTH);
        LockService.Active active = LockService.activeFor(thief.getUUID());
        check(active != null, "the lock did not start");
        // a hurried player presses at the edge of the close band
        LockpickSession.State end = LockpickSessionGameTests.solve(active.session, true, (position, pressing) -> {
            active.position = position; active.pressing = pressing;
            LockService.tickOne(level.getServer(), thief, active);
        });
        if (end == LockpickSession.State.OPENED) { helper.succeed(); return; }   // a careless hand can get lucky; the arithmetic tests cover how rarely
        check(end == LockpickSession.State.BROKEN || end == LockpickSession.State.TIMED_OUT, "unexpected ending " + end);
        check(!level.getBlockState(h.doorLower).getValue(DoorBlock.OPEN), "a failed pick opened the door");
        if (end == LockpickSession.State.BROKEN) {
            check(LockService.picks(thief) == 2, "a snapped pick did not cost exactly one lockpick: " + LockService.picks(thief));
            PropertyLocks locks = PropertyLocks.get(level.getServer());
            long now = WorkOrderLedger.get(level.getServer()).clock();
            check(locks.jamRemaining(h.doorLower, now) > 0, "the lock was not jammed");
            knock(thief, h.doorLower, Direction.NORTH);
            check(!LockService.busy(thief.getUUID()), "a jammed lock could be tried again at once");
            locks.jam(h.doorLower, now - 1);   // the lockout has run out
            knock(thief, h.doorLower, Direction.NORTH);
            check(LockService.busy(thief.getUUID()), "the lock stayed jammed after its lockout");
        } else {
            check(LockService.picks(thief) == 3, "running out of time cost a lockpick");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_building_inside", timeoutTicks = 200)
    public static void aPersonInsideAlwaysGetsOutAndAnInteriorDoorIsNotLocked(GameTestHelper helper) {
        try (House house = new House(helper)) {
            {
                ServerPlayer resident = player(helper, "uc_bgr", "farmer");
                resident.setGameMode(GameType.SURVIVAL);
                resident.moveTo(house.doorLower.getX() + 0.5, house.doorLower.getY(), house.doorLower.getZ() + 1.5);   // in the room
                knock(resident, house.doorLower, Direction.SOUTH);
                check(house.level.getBlockState(house.doorLower).getValue(DoorBlock.OPEN), "somebody inside could not open the door to leave");
                check(!LockService.busy(resident.getUUID()), "leaving started a lock");
                // a door with a roof on both sides is an inside door, never locked
                BlockPos inner = house.in(3, 0, 3);
                house.level.setBlock(inner, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
                house.level.setBlock(inner.above(), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
                check(!BuildingGuard.exterior(house.level, inner, house.level.getBlockState(inner)), "a door inside the house is taken for an exterior door");
                helper.succeed();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the stash

    @GameTest(template = "empty", batch = "uc_building_stash", timeoutTicks = 200)
    public static void aBuildingIsFurnishedOnceWithAStashOfItsClassAndThePickedSafeStaysOpenForItsWindow(GameTestHelper helper) {
        try (House h = new House(helper)) { stash(helper, h); }
    }

    private static void stash(GameTestHelper helper, House h) {
        ServerLevel level = h.level;
        // make the stash a certainty for this test: the class is the real one, its chance is raised
        var real = h.building.security();
        var sure = new CityBuildings.SecurityClass(real.id(), real.lockProfile(), real.lockedExterior(), real.stashPolicy(), 1.0, real.label());
        var building = new CityBuildings.Building(h.building.city(), h.building.chunkX(), h.building.chunkZ(), h.building.type(), h.building.buildingId(), h.building.floors(), h.building.cellars(), h.building.streetY(), sure);
        long now = WorkOrderLedger.get(level.getServer()).clock();
        Optional<BlockPos> first = BuildingStash.furnish(level, building, h.doorLower, now);
        check(first.isPresent(), "a building with a certain stash got none (no sound spot in a 5 by 5 room?)");
        BlockPos at = first.get();
        check(building.contains(at) && level.getBlockEntity(at) instanceof PropertyCacheBlockEntity cache && cache.registered() && cache.policyId().equals(real.stashPolicy()), "the stash is not a registered safe of the class's policy at " + at);
        check(at.getX() > h.base.getX() && at.getX() < h.base.getX() + 6 && at.getZ() > h.base.getZ() && at.getZ() < h.base.getZ() + 6, "the stash is not inside the room: " + at + " (base " + h.base + ", height there " + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()) + ")");
        check(BuildingStash.furnish(level, building, h.doorLower, now).isEmpty(), "a building was furnished twice");
        check(PropertyLocks.get(level.getServer()).furnished(building.key()), "the furnishing was not remembered");

        // the safe is picked on the server, stays open for its window and keeps one shared inventory
        ServerPlayer thief = player(helper, "uc_bgs", "farmer");
        thief.setGameMode(GameType.SURVIVAL);
        thief.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5);
        thief.getInventory().add(picks(6));
        PropertyCacheBlockEntity safe = (PropertyCacheBlockEntity) level.getBlockEntity(at);
        var entry = safe.entry(level, thief, now);
        check(entry.kind() == PropertyCacheBlockEntity.Entry.Kind.PICK && entry.profile().equals("deadbolt"), "a locked safe did not ask for a pick: " + entry);
        String refusal = LockService.begin(thief, level, new LockService.Target(LockService.Kind.SAFE, at, entry.profile(), "ironhaven", "safe", "Household safe", entry.picksNeeded()));
        check(refusal == null && LockService.busy(thief.getUUID()), "the safe's lock did not start: " + refusal);
        LockService.Active active = LockService.activeFor(thief.getUUID());
        LockpickSession.State end = LockpickSessionGameTests.solve(active.session, false, (position, pressing) -> {
            active.position = position; active.pressing = pressing;
            LockService.tickOne(level.getServer(), thief, active);
        });
        check(end == LockpickSession.State.OPENED, "an honest player did not open the safe: " + end);
        check(safe.forcedAt(WorkOrderLedger.get(level.getServer()).clock()) && safe.breakins() == 1 && safe.vigilance() == 1, "the safe did not record the break-in");
        check(safe.entry(level, thief, WorkOrderLedger.get(level.getServer()).clock()).kind() == PropertyCacheBlockEntity.Entry.Kind.OPEN, "a safe inside its window asked for another pick");
        // the contents are one shared finite inventory: what one person takes is gone for the next
        int before = safe.stock();
        for (int slot = 0; slot < safe.getContainerSize(); slot++) if (!safe.getItem(slot).isEmpty()) { safe.removeItem(slot, 64); break; }
        check(safe.stock() == before - 1, "taking an item did not remove it from the shared inventory");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ persistence

    @GameTest(template = "empty", batch = "uc_building")
    public static void doorsAndFurnishedBuildingsSurviveASaveAndAReloadAndAnOpenDoorIsRemembered(GameTestHelper helper) {
        PropertyLocks locks = new PropertyLocks();
        BlockPos a = new BlockPos(100, 72, -40), b = new BlockPos(-5, 80, 9);
        locks.breach(a, 5000);
        locks.jam(b, 9000);
        locks.markFurnished(1234567L);
        locks.markFurnished(-77L);
        CompoundTag saved = locks.save(new CompoundTag());
        PropertyLocks reloaded = PropertyLocks.load(saved);
        check(reloaded.breached(a, 4999) && !reloaded.breached(a, 5000), "an open door lost its window through a save");
        check(reloaded.jamRemaining(b, 8000) == 1000, "a jam lost its time through a save");
        check(reloaded.furnished(1234567L) && reloaded.furnished(-77L) && !reloaded.furnished(5L), "furnished buildings were not kept");
        check(reloaded.dueToClose(5000).equals(List.of(a)), "the open door is not waiting to be shut: " + reloaded.dueToClose(5000));
        reloaded.closed(a);
        check(reloaded.dueToClose(5000).isEmpty(), "a shut door is still waiting to be shut");
        reloaded.prune(10_000);
        check(reloaded.remembered() == 0, "finished doors were kept for ever: " + reloaded.remembered());
        // a save written by a newer version is refused, never half read
        CompoundTag future = locks.save(new CompoundTag());
        future.putInt("schema_version", PropertyLocks.SCHEMA + 1);
        boolean refused = false;
        try { PropertyLocks.load(future); } catch (IllegalStateException expected) { refused = true; }
        check(refused, "a newer property-lock save was read as if it were current");
        // the cap: the oldest finished doors go first
        PropertyLocks many = new PropertyLocks();
        for (int i = 0; i < PropertyLocks.MAX_DOORS + 50; i++) { BlockPos p = new BlockPos(i, 70, 0); many.breach(p, 1); many.closed(p); }
        check(many.remembered() <= PropertyLocks.MAX_DOORS + 1, "the list of doors grew without bound: " + many.remembered());
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_building")
    public static void aLockIsTheSameEveryTimeItIsPickedAndDifferentFromItsNeighbour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos a = helper.absolutePos(new BlockPos(1, 2, 1));
        check(LockService.seedFor(level, a) == LockService.seedFor(level, a), "a lock's seed changed between asks");
        check(LockService.seedFor(level, a) != LockService.seedFor(level, a.east()), "two doors share a lock");
        var profile = LockProfiles.INSTANCE.find("deadbolt");
        check(java.util.Arrays.equals(new LockpickSession(profile, LockService.seedFor(level, a)).sweetSpots(), new LockpickSession(profile, LockService.seedFor(level, a)).sweetSpots()), "the same door gave two different locks");
        helper.succeed();
    }
}
