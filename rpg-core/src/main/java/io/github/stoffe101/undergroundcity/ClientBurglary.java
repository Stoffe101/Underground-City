package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The client's half of burglary (D-144): it opens the casing card when the server sends a report, and draws the search bar under the crosshair while the server says a search is going on. It
 * keeps nothing the server did not send and decides nothing.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientBurglary {
    private static String label = "";
    private static long startedAt, endsAt;
    private static int fadeOut;

    private ClientBurglary() {}

    static void casing(Casing.Report report) { Minecraft.getInstance().setScreen(new CasingScreen(report)); }

    static void search(String text, int ticks, int state) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        if (state == 0) { label = text; startedAt = now; endsAt = now + Math.max(1, ticks); fadeOut = 0; }
        else { endsAt = 0; fadeOut = state == 1 ? 8 : 0; }
    }

    /** Test and capture seam: whether a bar is showing. */
    static boolean searching() { Minecraft mc = Minecraft.getInstance(); return mc.level != null && mc.level.getGameTime() < endsAt; }

    @SubscribeEvent
    public static void overlay(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "search_bar", (gui, graphics, partialTick, width, height) -> draw(graphics, width, height, partialTick));
    }

    private static void draw(GuiGraphics g, int width, int height, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.options.hideGui || mc.screen != null) return;
        long now = mc.level.getGameTime();
        boolean active = now < endsAt;
        if (!active && fadeOut <= 0) return;
        if (!active) fadeOut--;
        float share = active ? Math.min(1f, (now - startedAt + partial) / (float) Math.max(1, endsAt - startedAt)) : 1f;
        int w = 120, x = width / 2 - w / 2, y = height / 2 + 14;
        g.fill(x - 1, y - 1, x + w + 1, y + 6, 0xc005080c);
        g.fill(x, y, x + Math.round(w * share), y + 5, active ? UcUi.AMBER : UcUi.OK);
        String text = active ? label : "Done";
        g.drawString(mc.font, text, width / 2 - mc.font.width(text) / 2, y + 9, 0xffeaf2f8, true);
    }
}
