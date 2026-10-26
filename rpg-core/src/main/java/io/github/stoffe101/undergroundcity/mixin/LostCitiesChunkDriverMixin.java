package io.github.stoffe101.undergroundcity.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lost Cities 7.5.5 drops blocks it has just placed (ninth pass, measured: 374 of the 430 carpets of the C70 houses in a Valewood probe).
 *
 * <p>Its chunk writer, {@code ChunkDriver}, keeps the blocks it writes in a cache and puts them into the chunk at the end. Each block it places updates the shape of its four
 * neighbours, so that fences, panes, walls and stairs connect. But the update asks the chunk itself, which does not hold the cached blocks yet. A carpet next to a newly placed
 * block therefore sees air below it, even though Lost Cities has just written a beam there, and becomes air. The same goes for any block that needs a block under or behind it.
 *
 * <p>Here, an update that would turn a block Lost Cities placed into air leaves the block as it was; any other change of shape is kept. Lost Cities only places blocks there on
 * purpose, and its building data, not a stale read of the chunk, says what stands where. The method is named twice because the development runtime uses the official names and a
 * production server the Searge name ({@code m_60728_}), as in {@link GroundPathNavigationMixin}. Pseudo: nothing happens without Lost Cities, and {@code defaultRequire} is 0, so
 * another version that does not have this code is simply not touched; the building audit ({@code scripts/building_audit.py}) shows whether blocks go missing again.
 */
@Pseudo
@Mixin(targets = "mcjty.lostcities.worldgen.ChunkDriver", remap = false)
public abstract class LostCitiesChunkDriverMixin {
    @Redirect(method = "updateAdjacent", remap = false, require = 0,
              at = @At(value = "INVOKE", remap = false,
                       target = "Lnet/minecraft/world/level/block/state/BlockState;updateShape(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState underground_city$keepPlacedBlock(BlockState neighbour, Direction direction, BlockState placed, LevelAccessor level, BlockPos at, BlockPos from) {
        return kept(neighbour, neighbour.updateShape(direction, placed, level, at, from));
    }

    @Redirect(method = "updateAdjacent", remap = false, require = 0,
              at = @At(value = "INVOKE", remap = false,
                       target = "Lnet/minecraft/world/level/block/state/BlockState;m_60728_(Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState underground_city$keepPlacedBlockSrg(BlockState neighbour, Direction direction, BlockState placed, LevelAccessor level, BlockPos at, BlockPos from) {
        return kept(neighbour, neighbour.updateShape(direction, placed, level, at, from));
    }

    private static BlockState kept(BlockState before, BlockState shaped) {
        return shaped.isAir() && !before.isAir() ? before : shaped;
    }
}
