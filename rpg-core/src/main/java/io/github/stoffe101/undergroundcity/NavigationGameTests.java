package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * {@link io.github.stoffe101.undergroundcity.mixin.GroundPathNavigationMixin} (D-096): a walking creature that wants a path to a chunk nobody has loaded gets "no path" at once instead of making the
 * server thread wait for the chunk to be generated. The first test is the one that shows it (without the mixin the chunk is generated on the spot and the call takes a long time); the second is
 * the control that ordinary pathing in loaded chunks is unchanged.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NavigationGameTests {
    private NavigationGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 600, batch = "uc_navigation")
    public static void aWalkingCreatureDoesNotLoadAChunkToFindAPath(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos far = new BlockPos(7_000_016, 80, 7_000_016);
        ChunkPos farChunk = new ChunkPos(far);
        check(level.getChunkSource().getChunkNow(farChunk.x, farChunk.z) == null, "the precondition failed: the far chunk is already loaded");
        Villager villager = EntityType.VILLAGER.create(level);
        BlockPos here = helper.absolutePos(new BlockPos(0, 1, 0));
        villager.moveTo(here.getX() + 0.5, here.getY(), here.getZ() + 0.5);
        long began = System.nanoTime();
        Path path = villager.getNavigation().createPath(far, 1);
        long millis = (System.nanoTime() - began) / 1000000;
        check(path == null, "a path was found into a chunk that is not loaded");
        check(level.getChunkSource().getChunkNow(farChunk.x, farChunk.z) == null, "asking for the path loaded (or generated) the far chunk");
        check(millis < 250, "the path request took " + millis + " ms; it must answer without waiting for a chunk");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "uc_navigation")
    public static void aWalkingCreatureStillFindsAPathInLoadedChunks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkPos centre = new ChunkPos(5000, 5000);
        level.getChunkSource().addRegionTicket(TicketType.FORCED, centre, 2, centre);
        try {
            level.getChunk(centre.x, centre.z);
            BlockPos start = new BlockPos(centre.getMinBlockX() + 3, 120, centre.getMinBlockZ() + 3);
            for (int x = 0; x <= 10; x++) for (int z = 0; z <= 3; z++) {
                level.setBlock(start.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y <= 3; y++) level.setBlock(start.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            Villager villager = EntityType.VILLAGER.create(level);
            villager.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
            villager.setOnGround(true);   // a navigation only plans a path for a creature that stands on something
            Path path = villager.getNavigation().createPath(start.offset(8, 0, 0), 1);
            check(path != null && path.canReach(), "ordinary pathing over a loaded stone floor found no path: " + path);
            helper.succeed();
        } finally {
            level.getChunkSource().removeRegionTicket(TicketType.FORCED, centre, 2, centre);
        }
    }
}
