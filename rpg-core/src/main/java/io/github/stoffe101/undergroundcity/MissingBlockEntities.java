package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Beds that Lost Cities draws have no block entity, and a bed is drawn by its block entity alone: the October 23 photographs showed the tenement bedrooms' beds as plain grey blocks
 * (D-141). Lost Cities writes a building's blocks straight into the generating chunk, which does not make the block entities that vanilla's own placement would, and the server
 * makes a missing one only when something asks for it, after the chunk has gone to the client without it.
 *
 * <p>So when a chunk is first generated, before it is sent to anybody, each bed in it gets the block entity it should have had. A chunk's sections are skipped unless their palette
 * holds a bed, so a chunk with none costs a palette look per section. Only new chunks: a chunk already saved is left exactly as it is (an existing world's beds stay as they were
 * until an operator decides otherwise).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class MissingBlockEntities {
    private static final Logger LOGGER = LogUtils.getLogger();
    static volatile int made, chunks;

    private MissingBlockEntities() {}

    static boolean wanted(BlockState state) { return state.getBlock() instanceof BedBlock; }

    @SubscribeEvent
    public static void loaded(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel) || !(event.getChunk() instanceof LevelChunk chunk)) return;
        int n = repair(chunk);
        if (n > 0) { made += n; chunks++; LOGGER.debug("[UC] {} bed block entities made in new chunk {}", n, chunk.getPos()); }
    }

    /** Makes the missing block entity of every bed in the chunk. Returns how many were made. Visible for the GameTests. */
    static int repair(LevelChunk chunk) {
        int n = 0;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.maybeHas(MissingBlockEntities::wanted)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!wanted(section.getBlockState(x, y, z))) continue;
                pos.set(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z);
                if (chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) != null) continue;
                if (chunk.getBlockEntity(pos.immutable(), LevelChunk.EntityCreationType.IMMEDIATE) != null) n++;
            }
        }
        return n;
    }
}
