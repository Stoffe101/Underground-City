package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Staff-only areas of a building open to the public (D-159), in place of the rule that nothing in an open building may be searched at all (D-147).
 *
 * <p>While a shop is open in its hours, or a fence's building at the fence's, its <b>public floor</b> is what a customer reaches from the street doors, on the floor they open onto, without
 * passing another door. Somebody is watching it: nothing on it is searched and no safe on it is cracked. Everything else is <b>staff only</b>: a back room behind a door, the floors above and
 * below.
 *
 * <ul>
 * <li>A door between the public floor and a back room is a <b>staff door</b>. It is shut to the public side and locked like the building's street door, so a lockpick opens it; it opens freely
 * from behind.</li>
 * <li>Furniture, containers and safes in a staff-only area may be searched and cracked while the place is open, under the usual witnesses: anybody of the household who sees it calls the police.</li>
 * <li>A person in a staff-only area whom somebody of the household can see is first told to leave ({@link #GRACE_TICKS}), then reported if they stay in sight.</li>
 * </ul>
 *
 * <p>Nothing is drawn or placed: the zones are read from the building's own walls and doors, a bounded flood from the street doors over one floor of one chunk, cached per building for a minute of
 * server time and worked out again only when somebody needs them.
 */
public final class StaffZones {
    StaffZones() {}

    /** The flood's bound (a chunk's floor is 256 cells; a few floors' worth of stray cells at most). */
    static final int MAX_CELLS = 1024;
    /** How long a building's zones are kept before they are read again (server ticks), and how many buildings are kept. */
    static final long TTL_TICKS = 1200;
    static final int CACHED = 64;
    /** How long a person seen in a staff-only area has to leave before they are reported, and how far a person of the household sees them. */
    static final long GRACE_TICKS = 100;
    static final double SEE_RANGE = 12;

    /** What a person is told who tries to search or crack something on the public floor of an open building. */
    static final String ON_THE_FLOOR = "Not out on the floor while it is open: somebody is watching. The back rooms are another matter.";

    /**
     * A building's public floor (cells a customer's feet can stand in), the ground outside its street doors on that floor (the pavement, under an awning or the eaves), and its staff doors
     * (lower halves), as read at {@code at}.
     */
    record Zones(long at, Set<Long> publicCells, Set<Long> outsideCells, Set<Long> staffDoors) {
        boolean isPublic(BlockPos cell) { return publicCells.contains(cell.asLong()); }
        boolean isOutside(BlockPos cell) { return outsideCells.contains(cell.asLong()); }
        boolean isStaffDoor(BlockPos lower) { return staffDoors.contains(lower.asLong()); }
    }

    private static final Map<Long, Zones> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Zones> eldest) { return size() > CACHED; }
    };
    static long computed, warned, reported;

    static Zones of(ServerLevel level, CityBuildings.Building b) {
        long now = level.getServer().getTickCount();
        synchronized (CACHE) {
            Zones z = CACHE.get(b.key());
            if (z != null && now - z.at() < TTL_TICKS) return z;
        }
        Zones z = compute(level, b, now);
        synchronized (CACHE) { CACHE.put(b.key(), z); }
        return z;
    }

    /** A cell a person can stand in: two cells free to walk through above something that holds them. */
    static boolean walkable(BlockGetter level, BlockPos cell) {
        return clear(level, cell) && clear(level, cell.above()) && level.getBlockState(cell.below()).blocksMotion();
    }

    /** The highest collision a person walks over without noticing: a mat, a rug, a carpet. */
    static final double LOW_STEP = 3 / 16.0;

    /**
     * True when nothing a person would bump into stands in this cell: no collision, or one no higher than {@link #LOW_STEP}. {@code blocksMotion()} alone is not enough: Modern Life's floor
     * mat copies bamboo's block properties, which force it "solid" although it is a sixteenth of a block high, so the mats inside the corner store's door read as a wall, its public floor came out
     * empty and a customer at its terminal was "in the back" (unmarked run 2, eighth pass).
     */
    static boolean clear(BlockGetter level, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (!state.blocksMotion()) return true;
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, cell);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= LOW_STEP;
    }

    static Zones compute(ServerLevel level, CityBuildings.Building b, long now) {
        computed++;
        Set<Long> open = new HashSet<>(), outside = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(), out = new ArrayDeque<>();
        List<BlockPos> doorsMet = new ArrayList<>();
        int x0 = b.chunkX() * 16, z0 = b.chunkZ() * 16;
        // the seeds: the cell just inside every street door on the ground floors
        for (int y = b.streetY(); y <= b.streetY() + 3; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos pos = new BlockPos(x0 + x, y, z0 + z);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER || !BuildingGuard.exterior(level, pos, state)) continue;
            Direction facing = state.getValue(DoorBlock.FACING);
            for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
                BlockPos cell = pos.relative(side);
                if (!b.contains(cell) || !walkable(level, cell)) continue;
                if (BuildingGuard.leadsOut(level, pos, side)) { if (outside.add(cell.asLong())) out.add(cell); }
                else if (open.add(cell.asLong())) queue.add(cell);
            }
        }
        // the flood: over the floor each seed stands on, through open cells, never through a door
        while (!queue.isEmpty() && open.size() < MAX_CELLS) {
            BlockPos cell = queue.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(d);
                if (!b.contains(next) || open.contains(next.asLong())) continue;
                BlockState state = level.getBlockState(next);
                if (state.getBlock() instanceof DoorBlock) { doorsMet.add(BuildingGuard.lowerOf(next, state)); continue; }
                if (!walkable(level, next)) continue;
                open.add(next.asLong());
                queue.add(next);
            }
        }
        // the ground outside the street doors, so that a person on the pavement under an awning is never "in the back"
        while (!out.isEmpty() && outside.size() < MAX_CELLS) {
            BlockPos cell = out.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(d);
                if (!b.contains(next) || outside.contains(next.asLong()) || open.contains(next.asLong()) || level.getBlockState(next).getBlock() instanceof DoorBlock || !walkable(level, next)) continue;
                outside.add(next.asLong());
                out.add(next);
            }
        }
        // a staff door: an inside door with the public floor on one side only
        Set<Long> staff = new HashSet<>();
        for (BlockPos door : doorsMet) {
            BlockState state = level.getBlockState(door);
            if (!(state.getBlock() instanceof DoorBlock) || BuildingGuard.exterior(level, door, state)) continue;
            Direction facing = state.getValue(DoorBlock.FACING);
            boolean front = open.contains(door.relative(facing).asLong()), back = open.contains(door.relative(facing.getOpposite()).asLong());
            if (front != back) staff.add(door.asLong());
        }
        return new Zones(now, Set.copyOf(open), Set.copyOf(outside), Set.copyOf(staff));
    }

    // ------------------------------------------------------------------------------------------------ what other systems ask

    /** True when furniture, a container or a safe at {@code pos} is out on the public floor: it stands beside a cell of it, at its height or one above (a shelf on the wall). */
    static boolean onPublicFloor(ServerLevel level, CityBuildings.Building b, BlockPos pos) {
        Zones z = of(level, b);
        if (z.isPublic(pos) || z.isPublic(pos.below())) return true;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos next = pos.relative(d);
            if (z.isPublic(next) || z.isPublic(next.below())) return true;
        }
        return false;
    }

    /** True when the door whose lower half is {@code lower} is a staff door of {@code b} and {@code b} is open to the public now. */
    static boolean staffDoorNow(ServerLevel level, CityBuildings.Building b, BlockPos lower) {
        return BuildingGuard.openToPublic(level, b, null) && of(level, b).isStaffDoor(lower);
    }

    /** True when {@code at} is on the public side of the staff door {@code lower}. */
    static boolean onPublicSide(ServerLevel level, CityBuildings.Building b, BlockPos lower, Vec3 at) {
        BlockState state = level.getBlockState(lower);
        if (!(state.getBlock() instanceof DoorBlock)) return false;
        Direction facing = state.getValue(DoorBlock.FACING);
        Zones z = of(level, b);
        Direction publicSide = z.isPublic(lower.relative(facing)) ? facing : facing.getOpposite();
        Vec3 centre = Vec3.atCenterOf(lower);
        return (at.x - centre.x) * publicSide.getStepX() + (at.z - centre.z) * publicSide.getStepZ() > 0;
    }

    /** True when a person standing at {@code feet} inside {@code b} is in a staff-only area: under a roof, not on the public floor, not outside the street doors and not in a doorway. */
    static boolean inStaffArea(ServerLevel level, CityBuildings.Building b, BlockPos feet) {
        Zones z = of(level, b);
        return !z.isPublic(feet) && !z.isOutside(feet) && !(level.getBlockState(feet).getBlock() instanceof DoorBlock) && roofed(level, feet);
    }

    /**
     * True when a person standing at {@code feet} is inside {@code b}'s walls: under its roof, and not on the ground outside a street door (the pavement under the eaves or an awning, within
     * three blocks of the door). The outside flood alone would also count a room reached through a doorless opening as outside, where a camera must still see a burglar at night.
     */
    static boolean indoors(ServerLevel level, CityBuildings.Building b, BlockPos feet) {
        if (!roofed(level, feet)) return false;
        if (!of(level, b).isOutside(feet)) return true;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 1; dy++) {
            BlockPos at = feet.offset(dx, dy, dz);
            BlockState state = level.getBlockState(at);
            if (state.getBlock() instanceof DoorBlock && BuildingGuard.exterior(level, at, state)) return false;
        }
        return true;
    }

    /** True when something solid stands above {@code pos} (read from the block heightmap: the sky light lags behind a change and passes through glass). */
    static boolean roofed(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY() + 1;
    }

    // ------------------------------------------------------------------------------------------------ the household's eyes

    private record Sighting(long key, long since) {}
    private static final Map<UUID, Sighting> SEEN = new HashMap<>();
    /** Who was reported, for which building and when: one call per stay in the back, not one every few seconds while they are still seen there. */
    private static final Map<UUID, Sighting> REPORTED = new HashMap<>();
    static final long REPORT_AGAIN_TICKS = 1200;

    /** Staff doors a redstone signal held open and that were shut again (a pressure plate or a button beside a staff door must not be a way into the back). */
    static long poweredShut;

    /**
     * A staff door is opened by its key, a pick or the household, never by redstone: a pressure plate or button the generator or Lost Cities left beside it would otherwise let anybody who
     * steps on it into the back. Five times a second, the staff doors of the buildings around each player that stand open while powered are shut again; the power stays, so the door stays
     * shut until the signal goes off and on again, and is then shut again within a fifth of a second. Nothing is removed from the world.
     */
    static void shutPowered(ServerLevel level, ServerPlayer player) {
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null || !CityBuildings.INSTANCE.askable(level, cx + dx, cz + dz)) continue;   // never waits on Lost Cities (D-167)
            Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz);
            if (b.isPresent()) shutPowered(level, b.get());
        }
    }

    /** Shuts the staff doors of one building that a redstone signal holds open; returns how many. Visible for the tests. */
    static int shutPowered(ServerLevel level, CityBuildings.Building b) {
        int shut = 0;
        for (long key : of(level, b).staffDoors()) {
            BlockPos lower = BlockPos.of(key);
            BlockState state = level.getBlockState(lower);
            if (!(state.getBlock() instanceof DoorBlock door) || !state.getValue(DoorBlock.OPEN) || !state.getValue(DoorBlock.POWERED)) continue;
            door.setOpen(null, level, state, lower, false);
            poweredShut++;
            shut++;
        }
        return shut;
    }

    /** Once a second: a person in a staff-only area of an open building whom the household can see is told to leave, then reported. */
    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int t = event.getServer().getTickCount();
        if (t % 4 == 1) {
            ServerLevel doors = event.getServer().overworld();
            if (doors != null) for (ServerPlayer player : doors.players()) {
                if (player instanceof FakePlayer) continue;
                try { shutPowered(doors, player); }
                catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(StaffZones.class).warn("A staff-door check failed: {}", error.toString()); }
            }
        }
        if (t % 20 != 11) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        for (ServerPlayer player : level.players()) {
            if (player instanceof FakePlayer) continue;
            try { watch(level, player); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(StaffZones.class).warn("A staff-area check failed: {}", error.toString()); }
        }
    }

    static void watch(ServerLevel level, ServerPlayer player) {
        // a timed check: while Lost Cities generates and this chunk's plan is not known, look again next second rather than wait on its lock (eleventh pass: a 0.45 s tick)
        if (!CityBuildings.INSTANCE.askable(level, player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4)) return;
        Optional<CityBuildings.Building> found = BurglarySearch.foreignBuilding(player, level, player.blockPosition());
        if (found.isEmpty() || !BuildingGuard.openToPublic(level, found.get(), null) || !inStaffArea(level, found.get(), player.blockPosition())) { SEEN.remove(player.getUUID()); return; }
        CityBuildings.Building b = found.get();
        if (!seenByHousehold(level, b, player)) return;
        sighted(level, player, b, null);
    }

    /**
     * A person in the back of an open shop was seen, by the household or by one of its cameras ({@code camera} not null): told once to go back out front, and reported only when still in the
     * back after the grace. A customer who strays a step off the public floor by day is a customer, whoever or whatever saw them (smith run 3: a camera called the police at once, D-178).
     */
    static void sighted(ServerLevel level, ServerPlayer player, CityBuildings.Building b, BlockPos camera) {
        long now = LockService.clock(level.getServer());
        Sighting last = REPORTED.get(player.getUUID());
        if (last != null && last.key() == b.key() && now - last.since() < REPORT_AGAIN_TICKS) return;   // reported a moment ago: the police are on their way, no second call (D-178 review)
        Sighting s = SEEN.get(player.getUUID());
        if (s == null || s.key() != b.key()) {
            SEEN.put(player.getUUID(), new Sighting(b.key(), now));
            warned++;
            player.displayClientMessage(Component.literal(camera == null ? "\"Staff only. Back out front, please.\"" : "A camera turns to you: staff only. Back out front, please.").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        if (now - s.since() < GRACE_TICKS) return;
        SEEN.remove(player.getUUID());
        REPORTED.put(player.getUUID(), new Sighting(b.key(), now));
        reported++;
        player.sendSystemMessage(Component.literal(camera == null ? "You stayed in the back after you were told to leave. They are calling the police."
            : "A camera kept you in the back after you were told to leave. The building's alarm is calling the police.").withStyle(ChatFormatting.RED));
        PoliceResponse.raise(player, level, b, camera == null ? player.blockPosition() : camera, camera != null, false, true);
    }

    static boolean seenByHousehold(ServerLevel level, CityBuildings.Building b, ServerPlayer player) {
        return !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(player.blockPosition()).inflate(SEE_RANGE), person -> person.isAlive() && !person.police()
            && person.homeKey() == b.key() && person.hasLineOfSight(player)).isEmpty();
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { SEEN.remove(event.getEntity().getUUID()); REPORTED.remove(event.getEntity().getUUID()); }

    /** Drops one chunk's cached zones (a GameTest's stand-in building shares its chunk with other tests' structures). */
    static void forgetForTest(CityBuildings.Building b) { synchronized (CACHE) { CACHE.remove(b.key()); } }

    static void resetForTest() { synchronized (CACHE) { CACHE.clear(); } SEEN.clear(); REPORTED.clear(); computed = warned = reported = poweredShut = 0; }

    static String status() { return "zones read " + computed + ", warned " + warned + ", reported " + reported + ", powered staff doors shut " + poweredShut; }
}
