package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.CityGround;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A feature's write into a finished city chunk's built volume is refused (D-196, {@link CityGround}). Named twice, as in {@link ChunkGeneratorStructuresMixin}. */
@Mixin(value = WorldGenRegion.class, remap = false)
public abstract class WorldGenRegionMixin {
    private static final String NAMES = "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z";

    @Inject(method = {"setBlock(" + NAMES, "m_6933_(" + NAMES}, at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$keepOutOfTheCity(BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (CityGround.refuses((WorldGenRegion) (Object) this, pos, state)) cir.setReturnValue(false);
    }
}
