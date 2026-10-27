package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.LostCitiesTerrainFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lost Cities 7.5.5 leaves the ground one block above a city street on the street (tenth pass, D-196; {@link LostCitiesTerrainFix}). Pseudo and not required, as
 * {@link LostCitiesChunkDriverMixin}: without Lost Cities, or with a version that has no such methods, nothing is touched, and the city probe's paved-road count shows it.
 */
@Pseudo
@Mixin(targets = "mcjty.lostcities.worldgen.LostCityTerrainFeature", remap = false)
public abstract class LostCitiesTerrainMixin {
    @Inject(method = "doCityChunk", at = @At("HEAD"), remap = false, require = 0)
    private void underground_city$cityChunkStarts(CallbackInfo ci) {
        LostCitiesTerrainFix.enter();
    }

    @Inject(method = "doCityChunk", at = @At("RETURN"), remap = false, require = 0)
    private void underground_city$cityChunkEnds(CallbackInfo ci) {
        LostCitiesTerrainFix.leave();
    }

    @Inject(method = "moveDown(IIII)I", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void underground_city$lowerTheLastBlock(int x, int z, int level, int maxHeight, CallbackInfoReturnable<Integer> cir) {
        int lowered = LostCitiesTerrainFix.lowered(this, x, z, level, cir.getReturnValueI());
        if (lowered != cir.getReturnValueI()) cir.setReturnValue(lowered);
    }
}
