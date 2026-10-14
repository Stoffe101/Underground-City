package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Real mouse input into Core and the installed provider. Loopback cloud tests only. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT)
public final class CloudJourneyClient {
    private static final boolean ENABLED=Boolean.getBoolean("uc.journey.client");
    private static boolean connecting;
    private CloudJourneyClient() {}
    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if(!ENABLED||event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString().startsWith("run-journey-client")) throw new IllegalStateException("Journey client requires disposable game directory");
        if(!connecting && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            connecting=true;mc.options.guiScale().set(3);mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.resizeDisplay();
            ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25565"),new ServerData("Cloud journey","127.0.0.1:25565",false),false);
        }
        if(connecting && mc.screen instanceof DisconnectedScreen) mc.stop();
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED||event.phase!=TickEvent.Phase.END) return;
        if(!QShopEconomy.available()) throw new IllegalStateException("Journey requires installed Q Shop 1.8.4");
        QShopJourneyActions.tick(Minecraft.getInstance());
    }
}
