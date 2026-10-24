package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    static boolean walkable(ServerLevel level, BlockPos cell) {
        return !level.getBlockState(cell).blocksMotion() && !level.getBlockState(cell.above()).blocksMotion() && level.getBlockState(cell.below()).blocksMotion();
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

    /** True when something solid stands above {@code pos} (read from the block heightmap: the sky light lags behind a change and passes through glass). */
    static boolean roofed(net.minecraft.world.level.Level level, BlockPos pos) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY() + 1;
    }

    // ------------------------------------------------------------------------------------------------ the household's eyes

    private record Sighting(long key, long since) {}
    private static final Map<UUID, Sighting> SEEN = new HashMap<>();

    /** Once a second: a person in a staff-only area of an open building whom the household can see is told to leave, then reported. */
    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 11) return;
        ServerLevel level = event.getServer().overworld();
        if (level == null) return;
        for (ServerPlayer player : level.players()) {
            if (player instanceof FakePlayer) continue;
            try { watch(level, player); }
            catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(StaffZones.class).warn("A staff-area check failed: {}", error.toString()); }
        }
    }

    static void watch(ServerLevel level, ServerPlayer player) {
        Optional<CityBuildings.Building> found = BurglarySearch.foreignBuilding(player, level, player.blockPosition());
        if (found.isEmpty() || !BuildingGuard.openToPublic(level, found.get(), null) || !inStaffArea(level, found.get(), player.blockPosition())) { SEEN.remove(player.getUUID()); return; }
        CityBuildings.Building b = found.get();
        if (!seenByHousehold(level, b, player)) return;
        long now = LockService.clock(level.getServer());
        Sighting s = SEEN.get(player.getUUID());
        if (s == null || s.key() != b.key()) {
            SEEN.put(player.getUUID(), new Sighting(b.key(), now));
            warned++;
            player.displayClientMessage(Component.literal("\"Staff only. Back out front, please.\"").withStyle(ChatFormatting.GOLD), true);
            return;
        }
        if (now - s.since() < GRACE_TICKS) return;
        SEEN.remove(player.getUUID());
        reported++;
        player.sendSystemMessage(Component.literal("You stayed in the back after you were told to leave. They are calling the police.").withStyle(ChatFormatting.RED));
        PoliceResponse.raise(player, level, b, player.blockPosition(), false, false, true);
    }

    static boolean seenByHousehold(ServerLevel level, CityBuildings.Building b, ServerPlayer player) {
        return !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(player.blockPosition()).inflate(SEE_RANGE), person -> person.isAlive() && !person.police()
            && person.homeKey() == b.key() && person.hasLineOfSight(player)).isEmpty();
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { SEEN.remove(event.getEntity().getUUID()); }

    static void resetForTest() { synchronized (CACHE) { CACHE.clear(); } SEEN.clear(); computed = warned = reported = 0; }

    static String status() { return "zones read " + computed + ", warned " + warned + ", reported " + reported; }
}
