package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.ToastPlacement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The game's corner notices (toasts) are drawn where the status stack stands by default, the top right: a real client's screenshot showed two of them over the status card. Forge has no event
 * around the toast pass, so this shifts the pose for it ({@link ToastPlacement}). Client only; named twice (official name for development, Searge name {@code m_94920_} for a production client),
 * as {@link GroundPathNavigationMixin} explains; {@code defaultRequire} is 0, so a game without either name keeps its toasts where they were.
 */
@Mixin(value = ToastComponent.class, remap = false)
public abstract class ToastComponentMixin {
    @Inject(method = {"render(Lnet/minecraft/client/gui/GuiGraphics;)V", "m_94920_(Lnet/minecraft/client/gui/GuiGraphics;)V"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void underground_city$outOfTheStatusStacksWay(GuiGraphics graphics, CallbackInfo ci) {
        if (ToastPlacement.hidden()) { ci.cancel(); return; }   // nothing drawn over a timed screen; a toast's clock keeps running, so a short one is simply missed (its words are in the chat)
        ToastPlacement.begin(graphics);
    }

    @Inject(method = {"render(Lnet/minecraft/client/gui/GuiGraphics;)V", "m_94920_(Lnet/minecraft/client/gui/GuiGraphics;)V"}, at = @At("RETURN"), remap = false)
    private void underground_city$putBack(GuiGraphics graphics, CallbackInfo ci) { ToastPlacement.end(graphics); }
}
