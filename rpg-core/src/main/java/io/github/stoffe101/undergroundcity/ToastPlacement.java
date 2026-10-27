package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Moves the game's corner notices out of the status stack's way while the stack is drawn ({@link HudLayout#toastShift}): the whole toast column is shifted before the game draws it and put
 * back afterwards ({@code mixin.ToastComponentMixin}). On a screen the stack is not drawn and the toasts stay in their corner, as the game places them, unless that corner is the
 * screen's panel ({@link #hidden}).
 */
@OnlyIn(Dist.CLIENT)
public final class ToastPlacement {
    private ToastPlacement() {}

    private static boolean pushed, clipped;

    /**
     * True while no toast may be drawn at all: a timed screen (the lock, the trace) is open, and a notice over a pad costs the round; or a screen is open whose panel the toast column would
     * cover (a counter, a terminal, a chest on a small window), where a notice hid prices and buy buttons. Our notices wait meanwhile ({@link ClientNotice}); the game's own are in the chat.
     */
    public static boolean hidden() { return ClientNotice.held(Minecraft.getInstance()); }

    /** True when the open screen has a panel the toast column would stand over. */
    static boolean coversPanel(Minecraft mc) {
        if (mc.screen == null) return false;
        int w = mc.getWindow().getGuiScaledWidth();
        if (mc.screen instanceof UcScreen screen) return HudLayout.toastsCoverPanel(w, screen.panelLeft(), screen.panelWidth());
        if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)
            return HudLayout.toastsCoverPanel(w, screen.getGuiLeft(), screen.getXSize());
        return false;
    }

    /** Called before the game draws its toasts (not when {@link #hidden}). */
    public static void begin(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        int[] stack = ClientHud.drawn();
        // the stack is drawn only when no screen is open, so on any screen the toasts keep their corner
        int[] shift = stack == null || mc.screen != null ? new int[] {0, 0} : HudLayout.toastShift(g.guiWidth(), g.guiHeight(), stack[0], stack[1], stack[2], stack[3]);
        g.pose().pushPose();
        g.pose().translate(shift[0], shift[1], 0);
        pushed = true;
        // moved beside the stack, a toast still slides in from the window's edge, across the stack (caught at 1280x720 in the advanced burglary run): it is cut at the stack's left edge, so
        // it comes out from behind the stack instead
        clipped = shift[0] < 0;
        if (clipped) g.enableScissor(0, 0, stack[0], g.guiHeight());
    }

    /** Called after it has drawn them (on every way out of its method). */
    public static void end(GuiGraphics g) {
        if (!pushed) return;
        pushed = false;
        if (clipped) { clipped = false; g.disableScissor(); }
        g.pose().popPose();
    }
}
