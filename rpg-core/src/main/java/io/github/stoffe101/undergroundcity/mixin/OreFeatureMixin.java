package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.CityGround;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * An ore or a blob of dirt, gravel, granite, diorite or andesite takes no cell of a finished city chunk's built volume (D-196, {@link CityGround}): the ore feature writes into the
 * chunk's sections directly, so the region's {@code setBlock} never sees it, and this is the one question it asks for every cell. Named twice, as in {@link ChunkGeneratorStructuresMixin}.
 */
@Mixin(value = OreFeature.class, remap = false)
public abstract class OreFeatureMixin {
    private static final String NAMES = "Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Function;Lnet/minecraft/util/RandomSource;"
        + "Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration;Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration$TargetBlockState;"
        + "Lnet/minecraft/core/BlockPos$MutableBlockPos;)Z";

    @Inject(method = {"canPlaceOre(" + NAMES, "m_225186_(" + NAMES}, at = @At("RETURN"), cancellable = true, remap = false)
    private static void underground_city$noOreInACity(BlockState state, Function<BlockPos, BlockState> getter, RandomSource random, OreConfiguration config,
                                                      OreConfiguration.TargetBlockState target, BlockPos.MutableBlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && CityGround.refusesOre(pos, state)) cir.setReturnValue(false);
    }
}
