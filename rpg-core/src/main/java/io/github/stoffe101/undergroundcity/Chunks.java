package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Whether a block can be read now. {@code Level.isLoaded} is not that on a server: it is true as soon as a chunk is scheduled to be full (its ticket level), while the chunk may still be
 * generating, and a {@code getBlockState} there makes the server thread wait for the generation workers. Measured in the tenth pass's exploring session: the door watch and the
 * exploration crates waited 6.6 s that way in ticks of up to 16 s (docs/technical/lost-cities-stalls.md). Everything that looks round a player on a timer asks this instead and tries
 * again on its next pass.
 */
final class Chunks {
    private Chunks() {}

    /** True when the chunk holding {@code pos} is generated and loaded, so reading it cannot wait. Always false off the server thread (the chunk cache answers only there). */
    static boolean ready(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }
}
