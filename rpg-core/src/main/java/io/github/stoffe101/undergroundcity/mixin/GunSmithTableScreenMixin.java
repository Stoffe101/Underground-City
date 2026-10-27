package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.GunsmithTableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client side of TaCZ's Gun Smith Table: after the screen sorts its recipes into tabs, TaCZ's own iron recipe for a gun the Gunsmith builds from a parts kit is
 * taken off the list (outside creative mode), since the server refuses it anyway ({@code GunsmithBuilds.allowsCraft}). The list then shows the kit recipes only,
 * named "Registered" and "Unmarked" by their results, instead of three entries all called the same (gunsmith run 5). Pseudo: nothing happens without TaCZ.
 */
@Pseudo
@Mixin(targets = "com.tacz.guns.client.gui.GunSmithTableScreen", remap = false)
public abstract class GunSmithTableScreenMixin {
    @Inject(method = "classifyRecipes", at = @At("TAIL"), remap = false)
    private void underground_city$hideIronRecipesForKitGuns(CallbackInfo ci) {
        GunsmithTableView.pruneIronRecipes(this);
    }
}
