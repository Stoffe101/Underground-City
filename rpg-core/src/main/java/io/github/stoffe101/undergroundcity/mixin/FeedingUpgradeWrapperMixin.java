package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.BackpackGate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A backpack standing in the world ticks its upgrades with no wearer, and Sophisticated Core's Feeding upgrade then feeds every player within three blocks from the pack's
 * contents. In a Crew home that is an outsider eating the Crew's stored food. The Feeding upgrade therefore works only in a pack somebody wears (D-092, the Crew storage rule).
 * Pseudo: nothing happens when the mod is absent.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.feeding.FeedingUpgradeWrapper", remap = false)
public abstract class FeedingUpgradeWrapperMixin {
    @Inject(method = "tick(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$wornPacksOnly(Entity wearer, Level level, BlockPos pos, CallbackInfo ci) {
        if (wearer == null && !BackpackGate.placedBackpacksFeedBystanders()) ci.cancel();
    }
}
