package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Levels the ground of the arrival landing once, when a brand-new archipelago world is made (D-107).
 *
 * <p>The first survey of the landing on the game's own generator found rolling plains with ten to fifteen blocks of relief across the lot: a "plateau" in the island plan only
 * holds the terrain functions still, and Lost Cities' own levelling stops at a city's edge, so Founders Row (33 by 49 blocks, with the range, the flats and the motel beside it)
 * did not fit anywhere within 160 blocks and was silently not built. The grader makes the lot level and eases the ground back to the natural height over a ramp that can be walked,
 * and clears trees and plants from the lot. It touches only what it is asked to and only in a world whose game time is still zero; it never runs on an existing save.
 */
final class LandingGrader {
    private LandingGrader() {}

    /** Blocks over which the graded ground eases back to the natural ground: wide enough that a 15-block step is a slope that can be walked. */
    static final int RAMP = 28;

    /** The level lot, inclusive corners. */
    record Rect(int x0, int z0, int x1, int z1) {
        double distance(int x, int z) {
            int dx = Math.max(Math.max(x0 - x, x - x1), 0), dz = Math.max(Math.max(z0 - z, z - z1), 0);
            return Math.hypot(dx, dz);
        }
        Rect grow(int by) { return new Rect(x0 - by, z0 - by, x1 + by, z1 + by); }
    }

    /** What was done, for the log and the tests. */
    record Result(int columns, int skippedWet, int changed, int maxCut, int maxFill, long millis) {}

    /** The height a column is given: {@code level} inside the lot, easing (smoothstep) back to its {@code natural} height over {@link #RAMP}, its own beyond. */
    static int target(Rect rect, int level, int natural, int x, int z) {
        double d = rect.distance(x, z);
        if (d <= 0) return level;
        if (d >= RAMP) return natural;
        double t = d / RAMP;
        double s = t * t * (3 - 2 * t);
        return (int) Math.round(level + (natural - level) * s);
    }

    /** True for what is not ground: air, anything a block can replace, trees and plants. */
    static boolean cover(BlockState state) {
        return state.isAir() || state.canBeReplaced() || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.getBlock() instanceof BushBlock
            || state.is(BlockTags.FLOWERS) || state.is(Blocks.VINE) || state.is(Blocks.MOSS_CARPET) || state.is(Blocks.SNOW);
    }

    /**
     * Grades the lot and its ramp. The chunks must be loadable (they are generated if they are not). A column with water in it is left as the generator made it.
     */
    static Result grade(ServerLevel level, Rect rect, int groundLevel) {
        long started = System.nanoTime();
        Rect area = rect.grow(RAMP);
        for (int cx = area.x0 >> 4; cx <= area.x1 >> 4; cx++) for (int cz = area.z0 >> 4; cz <= area.z1 >> 4; cz++) level.getChunk(cx, cz);
        int columns = 0, wet = 0, changed = 0, maxCut = 0, maxFill = 0;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = area.x0; x <= area.x1; x++) {
            for (int z = area.z0; z <= area.z1; z++) {
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z); // the first free block above everything, trees and water included
                int ground = top - 1;
                boolean water = false;
                while (ground > level.getMinBuildHeight()) {
                    BlockState state = level.getBlockState(pos.set(x, ground, z));
                    if (!state.getFluidState().isEmpty()) { water = true; break; }
                    if (!cover(state)) break;
                    ground--;
                }
                if (water) { wet++; continue; }
                columns++;
                int wanted = target(rect, groundLevel, ground, x, z);
                if (wanted == ground && top - 1 == ground) continue;
                if (wanted > ground) {
                    maxFill = Math.max(maxFill, wanted - ground);
                    for (int y = ground + 1; y < wanted; y++) { level.setBlock(pos.set(x, y, z), dirt, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); changed++; }
                } else if (wanted < ground) {
                    maxCut = Math.max(maxCut, ground - wanted);
                }
                // everything that stood above the new surface goes: the cut ground, trees, plants
                for (int y = wanted + 1; y < top; y++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) { level.setBlock(pos, air, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); changed++; }
                }
                level.setBlock(pos.set(x, wanted, z), grass, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                changed++;
                for (int y = wanted - 1; y >= wanted - 3; y--) {
                    BlockState below = level.getBlockState(pos.set(x, y, z));
                    if (!below.is(Blocks.DIRT) && !below.is(Blocks.GRASS_BLOCK) && !below.is(Blocks.WATER)) { level.setBlock(pos, dirt, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); changed++; }
                }
            }
        }
        return new Result(columns, wet, changed, maxCut, maxFill, (System.nanoTime() - started) / 1_000_000L);
    }
}
