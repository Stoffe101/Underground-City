package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.CityRegions;
import io.github.stoffe101.undergroundcity.WorldLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lake in or near a city of the archipelago (ninth pass, measured).
 *
 * <p>Lost Cities builds a city in the {@code raw_generation} step. The game's lakes come in the later {@code lakes} step, and a lake digs its bowl wherever its spot falls. In a Valewood
 * probe, a surface lava lake lay in a house's garden: lava in place of the paving and the ground under it, and the fence, a terrace's steps and its glass above it gone (24 of the
 * 24 blocks the building audit found missing in 68 building chunks, once Lost Cities' own dropped blocks were fixed). Lava beside wooden houses is also a fire.
 *
 * <p>A lake whose 16 by 16 square touches the ring round a city (the same ring in which no vanilla structure starts, {@link CityRegions#excludesStructures}) is not placed; every other
 * lake is generated as the game decides. Only the archipelago's own terrain ({@link WorldLayout#regional}); the legacy layout is untouched. The method is named twice for the
 * development and production names, as in {@link ChunkGeneratorStructuresMixin}.
 */
@SuppressWarnings("deprecation")
@Mixin(value = LakeFeature.class, remap = false)
public abstract class LakeFeatureMixin {
    @Inject(method = {"place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z", "m_142674_(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z"},
            at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$noLakeInACity(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        if (!WorldLayout.regional(context.chunkGenerator())) return;
        BlockPos origin = context.origin();
        for (int dx : new int[] {0, 15}) for (int dz : new int[] {0, 15}) {
            if (CityRegions.INSTANCE.excludesStructures(new ChunkPos(origin.offset(dx, 0, dz)))) { cir.setReturnValue(false); return; }
        }
    }
}
