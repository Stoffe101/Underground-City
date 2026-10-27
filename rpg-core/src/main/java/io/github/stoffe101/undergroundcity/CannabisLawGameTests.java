package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Public cultivation (D-160): which cities prohibit the crop, what is in the open, a seizure (the plant gone with nothing dropped, the grower's heat up), nothing taken from a grower who is away,
 * and the record of who planted a plant forgotten when it is harvested. Whether an officer on the beat sees a plant in a real city is a real client's to show.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CannabisLawGameTests {
    private CannabisLawGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @GameTest(template = "empty")
    public static void aPlantInTheOpenIsSeizedOnlyWhileItsGrowerIsAbout(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        check(CannabisLaw.prohibited("valewood") && CannabisLaw.prohibited("ironhaven") && !CannabisLaw.prohibited("blackwater"), "the cities' laws on the crop are not as the data says");
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        // other tests build roads, places and shelters high in the sky beside their own arenas, and the grid moves with the batches (CI, a private-pack run, October 27:
        // something stood over this column): the plant goes above whatever already stands in it, so nothing is over it but what this test puts there
        int x = arena.getX() + 4000, z = arena.getZ();
        level.getChunk(x >> 4, z >> 4);
        int y = Math.max(level.getMaxBuildHeight() - 160, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
        check(y + 5 < level.getMaxBuildHeight(), "no free sky over the test column");
        BlockPos plant = new BlockPos(x, y + 1, z);
        FakePlayer grower = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_law_grower"));
        try {
            level.setBlock(plant.below(), Blocks.FARMLAND.defaultBlockState(), 2);
            level.setBlock(plant, UndergroundCity.CANNABIS_PLANT.get().defaultBlockState(), 2);
            check(CannabisLaw.inTheOpen(level, plant), "a plant under the open sky is not in the open");
            level.setBlock(plant.above(3), Blocks.GLASS.defaultBlockState(), 2);
            check(!CannabisLaw.inTheOpen(level, plant), "a plant under a glass roof is in the open");
            level.setBlock(plant.above(3), Blocks.AIR.defaultBlockState(), 2);

            CannabisLaw.plantings(level.getServer()).put(plant.asLong(), new CannabisLaw.Planting(grower.getUUID(), 0));
            check(!CannabisLaw.look(level, plant), "a plant was seized while its grower was away (not in the player list)");
            check(level.getBlockState(plant).getBlock() instanceof RestrictedCropBlock, "the plant is gone");

            int before = WorkOrderLedger.get(level.getServer()).heat(grower.getUUID(), "valewood");
            CannabisLaw.seize(level, plant, grower.getUUID(), "valewood");
            check(level.getBlockState(plant).isAir(), "a seized plant is still there");
            check(WorkOrderLedger.get(level.getServer()).heat(grower.getUUID(), "valewood") == before + CannabisLaw.HEAT, "a seizure gave no heat");
            check(!CannabisLaw.plantings(level.getServer()).byPos.containsKey(plant.asLong()), "a seized plant is still on the record");
            check(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(plant).inflate(2)).isEmpty(), "a seized plant dropped something");

            // a harvest (or any removal) forgets who planted it
            level.setBlock(plant, UndergroundCity.CANNABIS_PLANT.get().defaultBlockState(), 2);
            CannabisLaw.plantings(level.getServer()).put(plant.asLong(), new CannabisLaw.Planting(grower.getUUID(), 0));
            level.setBlock(plant, Blocks.AIR.defaultBlockState(), 2);
            check(!CannabisLaw.plantings(level.getServer()).byPos.containsKey(plant.asLong()), "a removed plant is still on the record");
        } finally {
            for (int dy = -1; dy <= 3; dy++) level.setBlock(plant.above(dy), Blocks.AIR.defaultBlockState(), 2);
            CannabisLaw.plantings(level.getServer()).byPos.remove(plant.asLong());
        }
        helper.succeed();
    }
}
