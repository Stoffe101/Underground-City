package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Builds the inter-city roads and bridges while NEW chunks are generated, so it can never rewrite an existing save.
 * The geometry (centreline, deck height, bridge flag) comes from {@link CorridorPlan}, which depends only on the world
 * seed and the datapack route data; every chunk therefore agrees with its neighbours without storing anything.
 * Each call writes inside the region Minecraft lets a feature touch (its own chunk and the eight neighbours). Original vanilla blocks only.
 */
public final class CorridorRoadFeature extends Feature<CorridorRoadConfig> {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Map<ServerLevel, List<CorridorPlan>> PLANS = new WeakHashMap<>();
    private static final BlockState DECK = Blocks.POLISHED_ANDESITE.defaultBlockState();
    private static final BlockState DECK_SLAB = Blocks.POLISHED_ANDESITE_SLAB.defaultBlockState();
    private static final BlockState MASONRY = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState RAIL = Blocks.STONE_BRICK_WALL.defaultBlockState();
    private static final BlockState LAMP = Blocks.LANTERN.defaultBlockState();

    public CorridorRoadFeature() { super(CorridorRoadConfig.CODEC); }

    /** Plans already built for a level (empty before the first road chunk was generated). */
    public static synchronized List<CorridorPlan> plans(ServerLevel level) {
        List<CorridorPlan> plans = PLANS.get(level);
        return plans == null ? List.of() : plans;
    }

    private static synchronized List<CorridorPlan> plansFor(FeaturePlaceContext<CorridorRoadConfig> context) {
        ServerLevel server = context.level().getLevel();
        List<CorridorPlan> plans = PLANS.get(server);
        if (plans != null) return plans;
        long started = System.nanoTime();
        var generator = context.chunkGenerator();
        var random = server.getChunkSource().randomState();
        var region = context.level();
        CorridorPlan.Terrain terrain = new CorridorPlan.Terrain() {
            @Override public int surface(int x, int z) { return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, region, random); }
            @Override public int floor(int x, int z) { return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, region, random); }
        };
        plans = context.config().plans();
        for (CorridorPlan plan : plans) {
            plan.build(terrain);
            LogUtils.getLogger().info("[UC-ROAD] {}: {} blocks, {} profile samples, {} bridge samples, steepest step {} half blocks",
                plan.id(), Math.round(plan.length()), plan.samples(), plan.bridgeSamples(), plan.maxProfileStep());
        }
        LogUtils.getLogger().info("[UC-ROAD] Planned {} road(s) from terrain in {} ms", plans.size(), (System.nanoTime() - started) / 1_000_000);
        PLANS.put(server, plans);
        return plans;
    }

    @Override public boolean place(FeaturePlaceContext<CorridorRoadConfig> context) {
        var level = context.level();
        if (level.getLevel().dimension() != Level.OVERWORLD) return false;
        int x0 = context.origin().getX() & ~15, z0 = context.origin().getZ() & ~15;
        boolean near = false;
        for (CorridorPlan plan : context.config().plans()) {
            near |= plan.nearChunk(x0, z0, 16);
            if (near) break;
        }
        if (!near) return false;
        List<CorridorPlan> plans = plansFor(context);
        boolean placed = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<BlockPos> rails = new java.util.ArrayList<>();
        // The road is rebuilt over this chunk and its eight neighbours (the region a feature may write). Every chunk
        // therefore repairs whatever its own trees or structures pushed across a border after a neighbour had already
        // finished its run, so the result does not depend on the order chunks happen to be generated in. The blocks
        // written are identical each time, so repeating them is harmless.
        for (int dx = -16; dx < 32; dx++) {
            for (int dz = -16; dz < 32; dz++) {
                int x = x0 + dx, z = z0 + dz;
                CorridorPlan chosen = null;
                CorridorPlan.Hit hit = null;
                for (CorridorPlan plan : plans) {
                    CorridorPlan.Hit candidate = plan.nearest(x + 0.5, z + 0.5);
                    if (candidate != null && (hit == null || candidate.distance() < hit.distance())) { hit = candidate; chosen = plan; }
                }
                if (hit != null) placed |= column(level, chosen, hit, x, z, cursor, rails);
            }
        }
        // Walls are placed without neighbour updates, so join them into continuous parapets afterwards. Only positions
        // whose neighbours are still inside the region a feature may read are touched.
        for (BlockPos rail : rails) {
            if (rail.getX() < x0 - 15 || rail.getX() > x0 + 30 || rail.getZ() < z0 - 15 || rail.getZ() > z0 + 30) continue;
            BlockState state = level.getBlockState(rail);
            if (state.is(RAIL.getBlock())) level.setBlock(rail, Block.updateFromNeighbourShapes(state, level, rail), FLAGS);
        }
        return placed;
    }

    private static boolean column(net.minecraft.world.level.WorldGenLevel level, CorridorPlan plan, CorridorPlan.Hit hit,
                                  int x, int z, BlockPos.MutableBlockPos p, List<BlockPos> rails) {
        int half = plan.deckHalfAt(hit.s());
        int deckY = CorridorPlan.deckBlockY(half);
        if (deckY <= level.getMinBuildHeight() + 2 || deckY >= level.getMaxBuildHeight() - 8) return false;
        double u = Math.abs(hit.u()), deckEdge = plan.deckHalfWidth();
        boolean bridge = plan.bridgeAt(hit.s());
        boolean onDeck = u < deckEdge, onRail = !onDeck && bridge && u < deckEdge + 1.0;
        if (onDeck || onRail) {
            // Locate the natural support before touching anything.
            int ground = deckY - 1;
            boolean wet = false;
            while (ground > level.getMinBuildHeight()) {
                BlockState state = level.getBlockState(p.set(x, ground, z));
                if (!state.getFluidState().isEmpty()) wet = true;
                if (isGround(state)) break;
                ground--;
            }
            int missing = deckY - 1 - ground;
            if (missing > 0) {
                boolean viaduct = bridge || wet || missing > 3;
                if (viaduct) {
                    level.setBlock(p.set(x, deckY - 1, z), MASONRY, FLAGS);
                    if (plan.pierAt(hit.s(), hit.u())) {
                        for (int y = ground + 1; y < deckY - 1; y++) level.setBlock(p.set(x, y, z), MASONRY, FLAGS);
                    }
                } else {
                    for (int y = ground + 1; y < deckY; y++) level.setBlock(p.set(x, y, z), MASONRY, FLAGS);
                }
            }
            if (onDeck) {
                level.setBlock(p.set(x, deckY, z), CorridorPlan.isSlab(half) ? DECK_SLAB : DECK, FLAGS);
            } else {
                // Full block under the parapet so it stands tall enough to stop a jump even beside a slab.
                level.setBlock(p.set(x, deckY, z), MASONRY, FLAGS);
                level.setBlock(p.set(x, deckY + 1, z), RAIL, FLAGS);
                rails.add(new BlockPos(x, deckY + 1, z));
                if (Math.floorMod((long) Math.floor(hit.s()), 16L) == 0) level.setBlock(p.set(x, deckY + 2, z), LAMP, FLAGS);
            }
        }
        if (u >= deckEdge + 1.6) return onDeck || onRail;
        int clearFrom = onRail ? deckY + (bridge ? 3 : 2) : deckY + 1;
        // Four free blocks of headroom above the deck for walkers and riders.
        for (int y = clearFrom; y <= deckY + 4; y++) clear(level, p.set(x, y, z));
        // Open cutting: remove anything standing above the headroom until two free blocks are met.
        int free = 0;
        for (int y = deckY + 5; y < Math.min(level.getMaxBuildHeight() - 1, deckY + 40) && free < 2; y++) {
            BlockState state = level.getBlockState(p.set(x, y, z));
            if (state.isAir()) free++;
            else if (!state.getFluidState().isEmpty()) break;
            else { free = 0; level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS); }
        }
        return true;
    }

    private static void clear(net.minecraft.world.level.WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.isAir() && state.getFluidState().isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
    }

    /** Terrain a deck may rest on: not air, water, foliage or a replaceable plant. */
    private static boolean isGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty() && !state.canBeReplaced()
            && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
    }
}
