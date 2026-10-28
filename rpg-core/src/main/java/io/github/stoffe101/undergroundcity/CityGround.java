package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the game's later decoration out of what Lost Cities built (tenth pass, D-196; {@code docs/technical/city-ground.md}).
 *
 * <p>Lost Cities builds a city's streets and buildings in the first decoration step of a chunk ({@code raw_generation}, its own {@code lostcities} placed feature). Every later step
 * of the same chunk, and every step of a neighbour generated afterwards (a feature may write into the eight chunks around its own), then treats the paving, the floors and the roofs
 * as natural ground: the underground blobs of dirt, gravel, granite, diorite and andesite and the ores replace the stone of a ground floor (OreFeature writes straight into the
 * chunk's sections, not through the region), mushrooms stand on paving and roofs (a mushroom's light check passes while nothing is lit yet), and springs, glow lichen, trees and
 * the rest land wherever their spot falls. The generation trace measured it ({@link GenerationTrace}).
 *
 * <p>Once a chunk's plan is recorded ({@link LostCitiesLink#recordedSample}, written the moment Lost Cities finished the chunk), a city chunk's built volume is everything from
 * {@link #BELOW_FOUNDATION} blocks under its lowest cellar floor up to the sky. In the archipelago ({@link WorldLayout#regional}), during decoration, a write into that volume by any
 * feature but Lost Cities' and Core's own (the corridor roads stop at a city's circle and repair only themselves) is refused, with two exceptions: a plant (short and tall grass, ferns, flowers) placed on natural soil, which is how the game's own
 * greenery fills a garden, and a tree in a chunk with no building over grass, never over the paving ({@link #parkTree}). Mushrooms, saplings and berry bushes are not exceptions. Outside a city chunk, and in the countryside, generation is exactly the game's. A chunk
 * whose plan is not recorded yet is not guarded: Lost Cities writes its own volume over anything a neighbour put there first. Nothing here ever changes an existing chunk.
 */
public final class CityGround {
    /** Blocks under the lowest floor (the street, or the bottom cellar) that still belong to the city: the floor's own slab and what it rests on. */
    static final int BELOW_FOUNDATION = 3;
    /** Off only on a disposable measurement server ({@code -Duc.cityGround=off}, or {@code -PucCpGuard=off} for the city probe), to measure what the guard keeps out. */
    static final boolean GUARD = !"off".equals(System.getProperty("uc.cityGround", System.getProperty("uc.cityProbe.guard", "on")));
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    static final AtomicLong refusedWrites = new AtomicLong(), refusedOres = new AtomicLong(), allowedPlants = new AtomicLong(), vergesFinished = new AtomicLong();

    private static final class State {
        boolean decorating;
        int depth;
        boolean exempt, lostCities;
        String feature = "";
        WorldGenLevel level, region;
        int chunkX, chunkZ;
    }

    private CityGround() {}

    // ------------------------------------------------------------------------------------------------ entry points (mixins)

    /** {@code ChunkGenerator.applyBiomeDecoration} began: guarded only for the archipelago's overworld. */
    public static void decorationStarted(ChunkGenerator generator, WorldGenLevel level, ChunkPos chunk) {
        State state = STATE.get();
        state.decorating = WorldLayout.regional(generator) && level.getLevel().dimension() == Level.OVERWORLD;
        state.depth = 0;
        state.exempt = state.lostCities = false;
        state.feature = "";
        state.level = GenerationTrace.ON ? level : null;
        state.region = state.decorating ? level : null;
        state.chunkX = chunk.x;
        state.chunkZ = chunk.z;
    }

    public static void decorationEnded() {
        State state = STATE.get();
        state.decorating = false;
        state.depth = 0;
        state.level = state.region = null;
    }

    /** A placed feature starts; the outermost one decides (a patch of flowers places its flower through a placed feature of its own). */
    public static void featureStarted(PlacedFeature placed, PlacementContext context) {
        State state = STATE.get();
        if (!state.decorating || state.depth++ > 0) return;
        String owner = placed.feature().value().feature().getClass().getName();
        state.lostCities = owner.startsWith("mcjty.lostcities.");
        state.exempt = state.lostCities || owner.startsWith("io.github.stoffe101.undergroundcity.");
        state.feature = GenerationTrace.ON ? name(placed, context) : "";
    }

    public static void featureEnded() {
        State state = STATE.get();
        if (!state.decorating || state.depth == 0 || --state.depth > 0) return;
        if (state.lostCities) {
            if (state.region != null) finishVerges(state.region, state.chunkX, state.chunkZ);
            // Lost Cities writes its blocks into the chunk only as its feature finishes, after its own "generated" event: the trace copies the chunk here
            if (state.level != null) GenerationTrace.snapshot(state.level, state.chunkX, state.chunkZ);
        }
        state.exempt = state.lostCities = false;
        state.feature = "";
    }

    /** {@code WorldGenRegion.setBlock}: true when this write must not happen. */
    public static boolean refuses(LevelReader region, BlockPos pos, BlockState written) {
        State state = STATE.get();
        if (!state.decorating || state.depth == 0 || state.exempt) return false;
        Verdict verdict = verdict(region, pos, written);
        if (verdict == Verdict.OUTSIDE) return false;
        if (verdict == Verdict.GREENERY) {
            allowedPlants.incrementAndGet();
            if (GenerationTrace.ON) GenerationTrace.write(pos, state.feature, written, false);
            return false;
        }
        if (GenerationTrace.ON) GenerationTrace.write(pos, state.feature, written, GUARD);
        if (!GUARD) return false;
        refusedWrites.incrementAndGet();
        return true;
    }

    /** What the rule says about a feature writing {@code written} at {@code pos}: not a city's cell, a city's cell that takes a garden plant, or a city's cell that keeps what it has. */
    enum Verdict { OUTSIDE, GREENERY, KEPT }

    static Verdict verdict(LevelReader region, BlockPos pos, BlockState written) {
        LostCitiesLink.ChunkSample sample = LostCitiesLink.recordedSample(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        if (sample == null || !sample.city()) return Verdict.OUTSIDE;
        int street = LostCitiesLink.streetY(sample.cityLevel());
        if (street == Integer.MIN_VALUE || pos.getY() < lowestProtected(street, sample.cellars())) return Verdict.OUTSIDE;
        boolean open = sample.building() == null || sample.building().isEmpty();
        return greenery(region, pos, written) || (open && parkTree(region, pos, written, street)) ? Verdict.GREENERY : Verdict.KEPT;
    }

    /**
     * A tree's log or leaves in a city chunk with no building (a street's verge, a square, a park), into empty air above the street, in a column whose ground at the street height
     * is grass or earth: Lost Cities' own park saplings are taken out in the archipelago ({@code avoidFoliage}, D-106), so the game's trees are the only ones a park or a verge has.
     * Over the paving, and anywhere in a building's lot, they are kept out: no canopy over a carriageway or a roof.
     */
    static boolean parkTree(LevelReader region, BlockPos pos, BlockState written, int street) {
        if (!written.is(BlockTags.LEAVES) && !written.is(BlockTags.LOGS)) return false;
        if (pos.getY() <= street || !region.getBlockState(pos).isAir()) return false;
        return region.getBlockState(new BlockPos(pos.getX(), street, pos.getZ())).is(BlockTags.DIRT);
    }

    /** {@code OreFeature.canPlaceOre} said yes (the blob would replace {@code replaced} at {@code pos}): true when that cell is a city's. */
    public static boolean refusesOre(BlockPos pos, BlockState replaced) {
        State state = STATE.get();
        if (!state.decorating || state.exempt) return false;
        int floor = protectedFrom(pos.getX() >> 4, pos.getZ() >> 4);
        if (floor == Integer.MIN_VALUE || pos.getY() < floor) return false;
        if (GenerationTrace.ON) GenerationTrace.write(pos, state.feature.isEmpty() ? "ore" : state.feature, replaced, GUARD);
        if (!GUARD) return false;
        refusedOres.incrementAndGet();
        return true;
    }

    /**
     * The verges of a new street chunk (a street, a square, a park: a city chunk with no building), right after Lost Cities built it: a cell at the street height that is bare
     * natural rock, gravel, sand, ore or earth under open air becomes grass. Lost Cities' street base keeps whatever ground the levelling left there ({@code structure_void} in its
     * palette), and where it lowered a slope the layer it moved down was the slope's own surface: patches of stone, gravel and granite beside the paving (the city probe: 236 to
     * 474 street-height cells a city). The paving, the street's furniture, gardens, ponds and every building chunk are untouched; only a chunk being generated is changed.
     */
    static void finishVerges(WorldGenLevel region, int chunkX, int chunkZ) {
        LostCitiesLink.ChunkSample sample = LostCitiesLink.recordedSample(ChunkPos.asLong(chunkX, chunkZ));
        if (sample == null || !sample.city() || (sample.building() != null && !sample.building().isEmpty())) return;
        int street = LostCitiesLink.streetY(sample.cityLevel());
        if (street == Integer.MIN_VALUE) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState grass = net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            pos.set((chunkX << 4) + x, street, (chunkZ << 4) + z);
            BlockState ground = region.getBlockState(pos);
            if (!bareGround(ground) || !region.getBlockState(pos.above()).isAir()) continue;
            region.setBlock(pos, grass, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            vergesFinished.incrementAndGet();
        }
    }

    /** Natural ground that is not grass: earth, rock, gravel, sand, ore. */
    static boolean bareGround(BlockState state) {
        if (state.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || state.is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) return false;
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL)
            || state.is(net.minecraftforge.common.Tags.Blocks.ORES) || state.is(net.minecraft.world.level.block.Blocks.CLAY);
    }

    /** True on a worker decorating an archipelago overworld chunk (Lost Cities builds its cities inside this). */
    public static boolean decoratingRegional() { return STATE.get().decorating; }

    // ------------------------------------------------------------------------------------------------ the rule

    /** The lowest protected y of a chunk, or {@link Integer#MIN_VALUE} when the chunk is not a city chunk whose plan is recorded. */
    static int protectedFrom(int chunkX, int chunkZ) {
        LostCitiesLink.ChunkSample sample = LostCitiesLink.recordedSample(ChunkPos.asLong(chunkX, chunkZ));
        if (sample == null || !sample.city()) return Integer.MIN_VALUE;
        int street = LostCitiesLink.streetY(sample.cityLevel());
        if (street == Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return lowestProtected(street, sample.cellars());
    }

    /** From {@link #BELOW_FOUNDATION} blocks under the lowest floor: the street, or each cellar one storey (six blocks) further down. */
    static int lowestProtected(int street, int cellars) {
        return street - 6 * Math.max(0, cellars) - BELOW_FOUNDATION;
    }

    /** The one exception: grass, a fern or a flower on natural soil (or the top of a tall one on its own bottom), into an empty cell. */
    static boolean greenery(LevelReader region, BlockPos pos, BlockState written) {
        if (!(written.getBlock() instanceof BushBlock) || written.getBlock() instanceof MushroomBlock || written.getBlock() instanceof SaplingBlock
            || written.getBlock() instanceof SweetBerryBushBlock || !written.getFluidState().isEmpty()) return false;
        BlockState here = region.getBlockState(pos);
        if (!here.isAir() && !here.canBeReplaced()) return false;
        BlockState below = region.getBlockState(pos.below());
        if (written.getBlock() instanceof DoublePlantBlock && written.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && written.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return below.is(written.getBlock());
        return below.is(BlockTags.DIRT);
    }

    private static String name(PlacedFeature placed, PlacementContext context) {
        var key = context.getLevel().registryAccess().registry(Registries.PLACED_FEATURE).flatMap(r -> r.getResourceKey(placed));
        if (key.isPresent()) return key.get().location().toString();
        return placed.feature().unwrapKey().map(k -> k.location().toString()).orElse(placed.feature().value().feature().getClass().getSimpleName());
    }

    static String status() {
        return "city ground guard " + (GUARD ? "on" : "OFF (measurement)") + ": refused " + refusedWrites.get() + " writes and " + refusedOres.get() + " ore cells, let "
            + allowedPlants.get() + " plants onto garden soil; street columns lowered onto the city ground " + LostCitiesTerrainFix.lowered.get() + ", columns cleared of plants " + LostCitiesTerrainFix.plantsCleared.get()
            + ", verge cells grassed " + vergesFinished.get();
    }
}
