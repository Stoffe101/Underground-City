package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client entry points for the registration screen (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
public final class ClientRegistration {
    private ClientRegistration() {}

    /**
     * Scripted clients that drive the world on their own (the world tour, the launch smoke, the journey and visual-capture harnesses) are never
     * stopped by the first-login prompt: it would sit over every frame they take. The scripted-player harness ({@code uc.playtest}) opts in, and so does the ninth pass's
     * two-player session ({@link DuoSessionClient}), whose clients answer the real screen (its first run waited four minutes for a screen this kept shut).
     */
    static boolean suppressedByHarness() {
        if (DuoSessionServer.ENABLED) return false;
        for (String property : new String[]{"uc.worldTour", "uc.launchSmoke", "uc.journey.client", "uc.journey.phase", "uc.worldSurvey", "uc.seedScan", "uc.visualCapture"}) {
            if (System.getProperty(property) != null) return true;
        }
        return false;
    }

    public static void open(java.util.Map<String, java.util.List<String>> kits) {
        Minecraft mc = Minecraft.getInstance();
        if (suppressedByHarness()) return;
        if (!(mc.screen instanceof RegistrationScreen)) mc.setScreen(new RegistrationScreen(kits));
    }

    public static void result(boolean ok, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RegistrationScreen screen) screen.result(ok, message);
        else if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(message), false);
    }
}
