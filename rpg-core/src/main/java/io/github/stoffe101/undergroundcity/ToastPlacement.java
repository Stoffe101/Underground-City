package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    /** True while no toast may be drawn at all: a timed screen (the lock, the trace) is open, and a notice over a pad costs the round. */
    public static boolean hidden() { return ClientNotice.timed(Minecraft.getInstance()); }

    /** Called before the game draws its toasts (not when {@link #hidden}). */
    public static void begin(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int[] stack = ClientHud.drawn();
        // the stack is drawn only when no screen is open, so on any screen the toasts keep their corner
        int[] shift = stack == null || mc.screen != null ? new int[] {0, 0} : HudLayout.toastShift(g.guiWidth(), g.guiHeight(), stack[0], stack[1], stack[2], stack[3]);
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
