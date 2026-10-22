package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The server's half of the scripted "walk into a locked door" playtest (the survival bypass found in the first human playtest): where a real generated building has a ground-floor exterior door of
 * each orientation, where a player stands to approach it, and what the server says afterwards. It is reachable only from {@link Scenarios}; nothing here runs in normal play.
 */
final class DoorWalk {
    private DoorWalk() {}

    /** A real door of a generated building and the street in front of it. {@code frontIsStreet}: the door faces the street (its leaf stands on the room side of its block). */
    record Spot(BlockPos door, Direction facing, boolean frontIsStreet, Direction out, Vec3 stand, float yaw, String type, String securityClass) {
        /** How far, in blocks, a position is past the door toward the building's inside, measured from the door block's centre. */
        double past(Vec3 at) {
            Vec3 c = Vec3.atCenterOf(door);
            return -((at.x - c.x) * out.getStepX() + (at.z - c.z) * out.getStepZ());
        }
    }

    /** The first ground-floor exterior door, nearest Ironhaven's centre, with exactly one sky side whose street cells are clear for four blocks. */
    static Optional<Spot> find(ServerLevel level, boolean frontIsStreet, String preferredClass) { return find(level, frontIsStreet, preferredClass, b -> true); }

    /** The same, among the buildings {@code accept} approves (the burglary scenario wants one whose alarm is armed and whose stash exists). */
    static Optional<Spot> find(ServerLevel level, boolean frontIsStreet, String preferredClass, java.util.function.Predicate<CityBuildings.Building> accept) {
        return find(level, "ironhaven", frontIsStreet, preferredClass, accept);
    }

    /** The same, nearest the centre of the named city. */
    static Optional<Spot> find(ServerLevel level, String city, boolean frontIsStreet, String preferredClass, java.util.function.Predicate<CityBuildings.Building> accept) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst().orElseThrow();
        Spot fallback = null;
        for (int radius = 1; radius <= 16; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int cx = region.centerChunkX() + dx, cz = region.centerChunkZ() + dz;
                Optional<CityBuildings.Building> building = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
                if (building.isEmpty() || !building.get().locked() || !accept.test(building.get())) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockPos pos = new BlockPos(cx * 16 + x, building.get().streetY() + 1, cz * 16 + z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER || state.getValue(DoorBlock.OPEN)) continue;
                    if (!BuildingGuard.exterior(level, pos, state)) continue;
                    Direction facing = state.getValue(DoorBlock.FACING);
                    boolean frontSky = BuildingGuard.exposed(level, pos.relative(facing).above()), backSky = BuildingGuard.exposed(level, pos.relative(facing.getOpposite()).above());
                    if (frontSky == backSky || frontSky != frontIsStreet) continue;
                    Direction out = frontSky ? facing : facing.getOpposite();
                    boolean clear = true;
                    for (int k = 1; k <= 4 && clear; k++) {
                        BlockPos cell = pos.relative(out, k);
                        level.getChunk(cell.getX() >> 4, cell.getZ() >> 4);
                        clear = !level.getBlockState(cell).blocksMotion() && !level.getBlockState(cell.above()).blocksMotion() && level.getBlockState(cell.below()).blocksMotion();
                    }
                    if (!clear) continue;
                    Vec3 stand = Vec3.atBottomCenterOf(pos).add(out.getStepX() * 3.5, 0, out.getStepZ() * 3.5);
                    float yaw = (float) Math.toDegrees(Math.atan2(out.getStepX(), -out.getStepZ()));
                    Spot spot = new Spot(pos, facing, frontIsStreet, out, stand, yaw, building.get().type(), building.get().security().id());
                    if (preferredClass.isEmpty() || building.get().security().id().equals(preferredClass)) return Optional.of(spot);
                    if (fallback == null) fallback = spot;
                }
            }
            if (fallback != null && radius >= 6) break;   // an apartment is preferred, but do not search the whole city for one
        }
        return Optional.ofNullable(fallback);
    }

    /** Puts the real player on the spot, door shut and locked as it was generated, with or without lockpicks, facing the door. */
    static void prepare(ServerPlayer player, Spot spot, boolean armed) {
        ServerLevel level = player.serverLevel();
        PropertyLocks locks = PropertyLocks.get(level.getServer());
        BlockState state = level.getBlockState(spot.door());
        if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN)) door.setOpen(null, level, state, spot.door(), false);
        locks.breach(spot.door(), 0);
        locks.closed(spot.door());
        LockService.abort(player);
        player.getInventory().clearContent();
        if (armed) player.getInventory().add(new net.minecraft.world.item.ItemStack(UndergroundCity.item("lockpick"), 5));
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.teleportTo(level, spot.stand().x, spot.stand().y, spot.stand().z, spot.yaw(), 0f);
        player.setDeltaMovement(Vec3.ZERO);
    }

    static boolean doorOpen(ServerLevel level, Spot spot) { return level.getBlockState(spot.door()).getValue(DoorBlock.OPEN); }
}
