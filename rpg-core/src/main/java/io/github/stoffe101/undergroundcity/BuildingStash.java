package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Furnishes a city building with its stash the first time anybody gets inside (D-105). A stash is a Household Safe: one shared, finite, physical inventory that restocks slowly in active
 * time, grows more watchful after a break-in and is never refilled for free (the rules of {@link PropertyCacheBlockEntity}, unchanged). Doing it when the door is first picked, rather than
 * when the chunk is generated, means no chunk-generation hook and no thousand-safe city: only buildings somebody really broke into hold one, each is decided exactly once (it is recorded in
 * {@link PropertyLocks}, whether a safe was placed or not), and the place chosen is a pure function of the building and the world seed.
 *
 * <p>Whether a building has a stash at all is the security class's chance; where it stands is the {@code k}-th of the sound candidate spots on the first floors, a spot being an empty floor block
 * against a wall, out of the sky's view, not in anybody's way at a door.
 */
public final class BuildingStash {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Floors above the door's floor that may hold the stash. */
    static final int MAX_FLOORS = 3;

    private BuildingStash() {}

    /** 0 to 1, a pure function of the building and the world seed. */
    static double roll(ServerLevel level, CityBuildings.Building building, int salt) {
        long z = building.key() * 0x9E3779B97F4A7C15L + level.getSeed() * 0xBF58476D1CE4E5B9L + salt * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) / (double) (1L << 53);
    }

    /** Decides, once, whether this building has a stash and where; places and registers it. Returns the position when one was placed. */
    public static Optional<BlockPos> furnish(ServerLevel level, CityBuildings.Building building, BlockPos entrance, long now) {
        PropertyLocks locks = PropertyLocks.get(level.getServer());
        if (locks.furnished(building.key())) return Optional.empty();
        locks.markFurnished(building.key());
        CityBuildings.SecurityClass security = building.security();
        if (security == null || security.stashPolicy().isEmpty() || roll(level, building, 1) >= security.stashChance()) return Optional.empty();
        PropertyPolicy policy = PropertyPolicies.INSTANCE.find(security.stashPolicy());
        if (policy == null) { LOGGER.warn("Building class {} names the unknown stash policy {}", security.id(), security.stashPolicy()); return Optional.empty(); }
        List<BlockPos> spots = candidates(level, building, entrance);
        if (spots.isEmpty()) { LOGGER.info("No place for a stash in {} at chunk {},{}", building.type(), building.chunkX(), building.chunkZ()); return Optional.empty(); }
        BlockPos at = spots.get((int) (roll(level, building, 2) * spots.size()));
        place(level, at, policy, now);
        return Optional.of(at);
    }

    static void place(ServerLevel level, BlockPos at, PropertyPolicy policy, long now) {
        Direction facing = Direction.NORTH;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(at.relative(direction)).isAir()) { facing = direction; break; }
        }
        level.setBlock(at, UndergroundCity.PROPERTY_CACHE.get().defaultBlockState().setValue(PropertyCacheBlock.FACING, facing), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof PropertyCacheBlockEntity cache) cache.register(level, policy, now);
    }

    /** Sound spots on the first floors, in a fixed order (so the {@code k}-th is the same on every machine): empty, floored, roofed, against a wall, away from doors. */
    static List<BlockPos> candidates(ServerLevel level, CityBuildings.Building building, BlockPos entrance) {
        List<BlockPos> out = new ArrayList<>();
        int floors = Math.max(1, Math.min(building.floors(), MAX_FLOORS));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int floor = 0; floor < floors; floor++) {
            int y = entrance.getY() + floor * CityBuildings.FLOOR;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                pos.set(building.chunkX() * 16 + x, y, building.chunkZ() * 16 + z);
                // never in a flat, house or premises that is to let or let (D-127): a stash is the household's, and that unit has no household
                if (sound(level, pos) && !nearDoor(level, pos) && !CityHomes.letAt(level, building, pos)) out.add(pos.immutable());
            }
        }
        return out;
    }

    private static boolean sound(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) return false;
        BlockState below = level.getBlockState(pos.below());
        if (!below.isFaceSturdy(level, pos.below(), Direction.UP) || below.getBlock() instanceof DoorBlock) return false;
        if (BuildingGuard.exposed(level, pos)) return false;
        boolean againstWall = false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState side = level.getBlockState(pos.relative(direction));
            if (side.isFaceSturdy(level, pos.relative(direction), direction.getOpposite())) againstWall = true;
        }
        return againstWall;
    }

    private static boolean nearDoor(ServerLevel level, BlockPos pos) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 1; dy++) {
            if (level.getBlockState(pos.offset(dx, dy, dz)).getBlock() instanceof DoorBlock) return true;
        }
        return false;
    }
}
