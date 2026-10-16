package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Client entry points for the registration screen (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
public final class ClientRegistration {
    private ClientRegistration() {}

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof RegistrationScreen)) mc.setScreen(new RegistrationScreen());
    }

    public static void result(boolean ok, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RegistrationScreen screen) screen.result(ok, message);
        else if (mc.player != null) mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(message), false);
    }
}
