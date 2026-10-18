package io.github.stoffe101.undergroundcity;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Client entry points of the Crew screen: the K key, and the server's answers (kept apart so the server never loads client classes). */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientCrew {
    static final KeyMapping KEY = new KeyMapping("key.underground_city.crew", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.underground_city");
    private ClientCrew() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) { event.register(KEY); }

    /** The server sent a view. It opens the screen when asked to, and refreshes one that is already open. */
    static void show(CrewView view, boolean open, String note) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CrewScreen screen) {
            screen.update(view, note);
        } else if (open) {
            mc.setScreen(new CrewScreen(view, note));
        }
    }

    /** Polled on the client tick; the key is a convenience, never part of any rule. */
    @Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
    public static final class Poll {
        private static int ticks;
        private Poll() {}

        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            while (KEY.consumeClick()) {
                if (mc.player != null && mc.screen == null) CrewNetwork.ask(true);
            }
            // an open Crew screen asks for fresh numbers every five seconds, so a mate's deposit or a new invitation shows up on its own
            if (mc.screen instanceof CrewScreen && ++ticks % 100 == 0) CrewNetwork.ask(false);
        }
    }
}
