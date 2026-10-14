package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Actual Minecraft framebuffer captures for the 5 real terminal tabs.
 * Runs ONLY when -Duc.visualCapture=true in a temporary CI client.
 * Third-party mod GUIs not installed in this client are NOT claimed as covered.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID, value=Dist.CLIENT)
public final class VisualCaptureHarness {
    private static final boolean ENABLED = Boolean.getBoolean("uc.visualCapture");
    private static final TerminalScreen.Page[] PAGES = TerminalScreen.Page.values();
    private static boolean started;
    private static int current;
    private static int stableFrames;
    private static int renderCount;
    private static boolean completed;

    private VisualCaptureHarness() {}

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || completed || started || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof TitleScreen)) return;
        int scale = Integer.getInteger("uc.visualCapture.scale", 3);
        if (scale < 1 || scale > 4) throw new IllegalArgumentException("Bad GUI scale");
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
        started = true;
        mc.setScreen(new TerminalScreen());
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!ENABLED || !started || completed || !(event.getScreen() instanceof TerminalScreen screen)) return;
        Minecraft mc = Minecraft.getInstance();
        if (current >= PAGES.length) {
            try {
                Path dir = mc.gameDirectory.toPath().resolve("screenshots");
                boolean ready = true;
                for (TerminalScreen.Page page : PAGES) {
                    String filename = "uc-terminal-" + page.name().toLowerCase(java.util.Locale.ROOT) + ".png";
                    Path png = dir.resolve(filename);
                    if (!Files.isRegularFile(png) || Files.size(png) < 1024) ready = false;
                }
                if (ready) {
                    Path marker = mc.gameDirectory.toPath().resolve("visual-capture-complete.txt");
                    Files.writeString(marker, "terminal_pages=" + PAGES.length +
                        "\ngui_scale=" + Integer.getInteger("uc.visualCapture.scale", 3) +
                        "\nframe_count=" + renderCount + "\n");
                    completed = true;
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Visual capture file failure", e);
            }
            return;
        }
        renderCount++;
        // First render at new tab, then wait a few frames before capture.
        if (stableFrames == 0) {
            screen.showPage(PAGES[current]);
        }
        if (++stableFrames < 12) return;
        String filename = "uc-terminal-" + PAGES[current].name().toLowerCase(java.util.Locale.ROOT) + ".png";
        Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(),
            response -> System.out.println("[UC-VISUAL] Captured " + filename + ": " + response.getString()));
        current++;
        stableFrames = 0;
    }
}
