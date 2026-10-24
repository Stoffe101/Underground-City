package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Client entry point for notices (kept apart so the server never loads client classes). A notice that arrives while a timed screen is open (the lock, the trace), or a screen whose panel
 * the notices would cover (a counter, a terminal on a small window), is held until it closes: real clients' screenshots showed two notices over the trace's pads while the signal was
 * flashing, and three over a counter's buy buttons right after a delivery.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
public final class ClientNotice {
    private ClientNotice() {}

    private static final List<UcToast> HELD = new ArrayList<>();
    /** More than this many held notices is a flood, not news: the oldest are dropped. */
    private static final int MAX_HELD = 6;

    /** The last notice's title (for the scenarios). */
    static volatile String lastTitle = "";

    static void show(Notice.Kind kind, String title, String body) {
        lastTitle = title;
        UcToast toast = new UcToast(kind, title, body);
        if (held(Minecraft.getInstance())) {
            HELD.add(toast);
            if (HELD.size() > MAX_HELD) HELD.remove(0);
            return;
        }
        Minecraft.getInstance().getToasts().addToast(toast);
    }

    /** True while the screen is one where every pixel and every moment counts. */
    static boolean timed(Minecraft mc) { return mc.screen instanceof ChallengeScreen || mc.screen instanceof LockpickScreen || mc.screen instanceof TraceScreen || mc.screen instanceof PatchScreen; }

    /** True while a notice must wait: a timed screen, or a screen whose panel the notices would cover (prices and buy buttons on a counter at 1280x720). */
    static boolean held(Minecraft mc) { return timed(mc) || ToastPlacement.coversPanel(mc); }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || HELD.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (held(mc)) return;
        for (UcToast toast : HELD) mc.getToasts().addToast(toast);
        HELD.clear();
    }
}
