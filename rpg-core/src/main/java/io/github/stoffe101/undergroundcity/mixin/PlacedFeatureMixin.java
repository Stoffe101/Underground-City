package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.CityGround;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Which placed feature is writing, for {@link CityGround} (D-196); the outermost one decides. Named twice, as in {@link ChunkGeneratorStructuresMixin}. */
@Mixin(value = PlacedFeature.class, remap = false)
public abstract class PlacedFeatureMixin {
    private static final String NAMES = "Lnet/minecraft/world/level/levelgen/placement/PlacementContext;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z";

    @Inject(method = {"placeWithContext(" + NAMES, "m_226368_(" + NAMES}, at = @At("HEAD"), remap = false)
    private void underground_city$featureStarts(PlacementContext context, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        CityGround.featureStarted((PlacedFeature) (Object) this, context);
    }

    @Inject(method = {"placeWithContext(" + NAMES, "m_226368_(" + NAMES}, at = @At("RETURN"), remap = false)
    private void underground_city$featureEnds(PlacementContext context, RandomSource random, BlockPos origin, CallbackInfoReturnable<Boolean> cir) {
        CityGround.featureEnded();
    }
}
