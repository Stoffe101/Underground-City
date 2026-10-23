package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Moves the game's corner notices out of the status stack's way while the stack is drawn ({@link HudLayout#toastShift}): the whole toast column is shifted before the game draws it and put
 * back afterwards ({@code mixin.ToastComponentMixin}). On a screen (a terminal, the inventory) the stack is not drawn and the toasts stay in their corner, as the game places them.
 */
@OnlyIn(Dist.CLIENT)
public final class ToastPlacement {
    private ToastPlacement() {}

    private static boolean pushed;

    /** Called before the game draws its toasts. */
    public static void begin(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int[] stack = ClientHud.drawn();
        int[] shift = stack == null || mc.screen != null && !(mc.screen instanceof ChatScreen) ? new int[] {0, 0}
            : HudLayout.toastShift(g.guiWidth(), g.guiHeight(), stack[0], stack[1], stack[2], stack[3]);
        g.pose().pushPose();
        g.pose().translate(shift[0], shift[1], 0);
        pushed = true;
    }

    /** Called after it has drawn them (on every way out of its method). */
    public static void end(GuiGraphics g) {
        if (!pushed) return;
        pushed = false;
        g.pose().popPose();
    }
}
