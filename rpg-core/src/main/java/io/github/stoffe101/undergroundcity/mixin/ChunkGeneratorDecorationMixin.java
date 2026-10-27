package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.CityGround;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link CityGround} when a chunk's decoration (every feature step, Lost Cities' first) begins and ends on this worker thread (D-196). Named twice, the development and the
 * production name, as in {@link ChunkGeneratorStructuresMixin}.
 */
@Mixin(value = ChunkGenerator.class, remap = false)
public abstract class ChunkGeneratorDecorationMixin {
    private static final String NAMES = "Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V";

    @Inject(method = {"applyBiomeDecoration(" + NAMES, "m_213609_(" + NAMES}, at = @At("HEAD"), remap = false)
    private void underground_city$decorationStarts(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        CityGround.decorationStarted((ChunkGenerator) (Object) this, level, chunk.getPos());
    }

    @Inject(method = {"applyBiomeDecoration(" + NAMES, "m_213609_(" + NAMES}, at = @At("RETURN"), remap = false)
    private void underground_city$decorationEnds(WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        CityGround.decorationEnded();
    }
}
