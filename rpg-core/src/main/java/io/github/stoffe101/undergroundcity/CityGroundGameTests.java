package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * The city ground guard (D-196): which cells of a finished city chunk the game's later decoration may still write, and that its four hooks are in the game. What it keeps out of a
 * real generated city is measured by the city probe's generation trace, not here (a GameTest server has no Lost Cities and is not the archipelago).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CityGroundGameTests {
    private CityGroundGameTests() {}

    @GameTest(template = "empty", batch = "uc_city_ground")
    public static void aFinishedCityChunkKeepsItsBlocksAndTakesOnlyGardenPlants(GameTestHelper helper) {
        var level = helper.getLevel();
        // two cells of one chunk, a block apart, whichever side of the test area the chunk border falls on
        BlockPos soil = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos paving = soil.offset((soil.getX() & 15) < 14 ? 2 : -2, 0, 0);
        level.setBlockAndUpdate(soil, Blocks.GRASS_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(paving, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        level.setBlockAndUpdate(soil.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(paving.above(), Blocks.AIR.defaultBlockState());
        long chunk = ChunkPos.asLong(soil.getX() >> 4, soil.getZ() >> 4);
        int street = soil.getY() + 1;
        var city = new LostCitiesLink.ChunkSample(true, 0, "", "", 0, 0, 0, "");
        LostCitiesLink.withRecordForTest(chunk, city, street, 6, () -> {
            BlockState poppy = Blocks.POPPY.defaultBlockState(), mushroom = Blocks.BROWN_MUSHROOM.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState();
            check(CityGround.verdict(level, soil.above(), poppy) == CityGround.Verdict.GREENERY, "a flower on a garden's grass was refused");
            check(CityGround.verdict(level, paving.above(), poppy) == CityGround.Verdict.KEPT, "a flower on paving was let through");
            check(CityGround.verdict(level, soil.above(), mushroom) == CityGround.Verdict.KEPT, "a mushroom was let into a city");
            check(CityGround.verdict(level, soil.above(), Blocks.OAK_SAPLING.defaultBlockState()) == CityGround.Verdict.KEPT, "a sapling was let into a city");
            check(CityGround.verdict(level, paving, dirt) == CityGround.Verdict.KEPT, "a blob of dirt was let into the paving");
            check(CityGround.verdict(level, soil.above(), Blocks.OAK_LEAVES.defaultBlockState()) == CityGround.Verdict.KEPT, "a tree's leaves were let into a city");
            // the top of a tall plant stands on its own bottom
            BlockState tallBottom = Blocks.TALL_GRASS.defaultBlockState(), tallTop = tallBottom.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
            level.setBlock(soil.above(), tallBottom, 2);
            check(CityGround.verdict(level, soil.above(2), tallTop) == CityGround.Verdict.GREENERY, "the top of tall grass on its own bottom was refused");
            check(tallTop.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER, "the test's tall grass is not an upper half");
            // the protected volume begins three blocks under the street (no cellar) and six further down for each cellar
            BlockPos deep = new BlockPos(paving.getX(), street - CityGround.BELOW_FOUNDATION - 1, paving.getZ());
            check(CityGround.verdict(level, deep, dirt) == CityGround.Verdict.OUTSIDE, "the ground under a city's foundation was guarded");
            check(CityGround.verdict(level, deep.above(), dirt) == CityGround.Verdict.KEPT, "the foundation itself was not guarded");
            return null;
        });
        var cellars = new LostCitiesLink.ChunkSample(true, 0, "x:house", "x:house", 2, 1, 0, "");
        LostCitiesLink.withRecordForTest(chunk, cellars, street, 6, () -> {
            BlockPos cellarFloor = new BlockPos(paving.getX(), street - 6 - CityGround.BELOW_FOUNDATION, paving.getZ());
            check(CityGround.verdict(level, cellarFloor, Blocks.GRAVEL.defaultBlockState()) == CityGround.Verdict.KEPT, "a cellar's floor was not guarded");
            check(CityGround.verdict(level, cellarFloor.below(), Blocks.GRAVEL.defaultBlockState()) == CityGround.Verdict.OUTSIDE, "the ground under a cellar was guarded");
            return null;
        });
        // a park's tree: over the grass of a chunk with no building, never over the paving, never in a building's lot
        level.setBlockAndUpdate(soil.above(), Blocks.AIR.defaultBlockState());
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        LostCitiesLink.withRecordForTest(chunk, city, soil.getY(), 6, () -> {
            check(CityGround.verdict(level, soil.above(3), leaves) == CityGround.Verdict.GREENERY, "a tree over a park's grass was refused");
            check(CityGround.verdict(level, paving.above(3), leaves) == CityGround.Verdict.KEPT, "a tree's leaves over the paving were let through");
            return null;
        });
        var lot = new LostCitiesLink.ChunkSample(true, 0, "x:house", "x:house", 2, 0, 0, "");
        LostCitiesLink.withRecordForTest(chunk, lot, soil.getY(), 6, () -> {
            check(CityGround.verdict(level, soil.above(3), leaves) == CityGround.Verdict.KEPT, "a tree was let into a building's lot");
            return null;
        });
        var countryside = new LostCitiesLink.ChunkSample(false, 0, "");
        LostCitiesLink.withRecordForTest(chunk, countryside, street, 6, () -> {
            check(CityGround.verdict(level, paving, Blocks.DIRT.defaultBlockState()) == CityGround.Verdict.OUTSIDE, "a chunk outside the city was guarded");
            return null;
        });
        check(CityGround.verdict(level, paving, Blocks.DIRT.defaultBlockState()) == CityGround.Verdict.OUTSIDE, "a chunk with no recorded plan was guarded");
        helper.succeed();
    }

    /** The mixin configuration does not require its injections (defaultRequire 0), so a renamed method would silently leave the guard out; the handlers must be in the classes. */
    @GameTest(template = "empty", batch = "uc_city_ground")
    public static void theGuardsHooksAreInTheGame(GameTestHelper helper) {
        check(handled(ChunkGenerator.class, "underground_city$decorationStarts") && handled(ChunkGenerator.class, "underground_city$decorationEnds"), "the decoration hooks are missing");
        check(handled(PlacedFeature.class, "underground_city$featureStarts") && handled(PlacedFeature.class, "underground_city$featureEnds"), "the placed feature hooks are missing");
        check(handled(WorldGenRegion.class, "underground_city$keepOutOfTheCity"), "the region's write hook is missing");
        check(handled(OreFeature.class, "underground_city$noOreInACity"), "the ore hook is missing");
        check(CityGround.GUARD, "the guard is off outside a measurement");
        helper.succeed();
    }

    private static boolean handled(Class<?> type, String handler) {
        return Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.getName().contains(handler));
    }
}
