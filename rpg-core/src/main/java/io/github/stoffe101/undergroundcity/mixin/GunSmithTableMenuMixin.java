package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.BlueprintGate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side gate on TaCZ's Gun Smith Table. TaCZ Blueprints hides the recipes a player has no blueprint for, but only in the client's screen: the
 * craft packet itself is not checked, so a modified client could build any gun. This runs on the server before {@code doCraft} takes anything from the
 * player and refuses a craft the blueprint rules would not have offered (see {@link BlueprintGate}). Pseudo: nothing happens when TaCZ is absent.
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.inventory.GunSmithTableMenu", remap = false)
public abstract class GunSmithTableMenuMixin {
    @Inject(method = "doCraft", at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$gateByBlueprint(ResourceLocation recipeId, Player player, CallbackInfo ci) {
        if (!BlueprintGate.allowsCraft(recipeId, player)) ci.cancel();
    }
}
