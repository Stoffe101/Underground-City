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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Real mouse input into Core and the installed provider. Loopback cloud tests only. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT)
public final class CloudJourneyClient {
    private static final boolean ENABLED=Boolean.getBoolean("uc.journey.client");
    /** A login that the server drops before the player is in (seen once on a hosted runner, a ConcurrentModificationException while both clients were logging in) is tried again; a third failure, or any disconnect after the player has joined, still ends the run. */
    private static final int ATTEMPTS=3;
    private static final long RETRY_NANOS=5_000_000_000L;
    private static boolean connecting, joined;
    private static int attempt;
    private static long retryAt;
    /** What every dropped login said, kept so that a retry is never silent: the runner reads {@code journey-client-connection.json} and reports it. */
    private static final List<String> DROPPED = new ArrayList<>();
    private CloudJourneyClient() {}
    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if(!ENABLED||event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        if(PerfSessionServer.ENABLED) PerfSessionClient.frame(mc);
        SurvivalJourneyActions.render(mc);
        if(!mc.gameDirectory.toPath().toAbsolutePath().normalize().getFileName().toString().startsWith("run-journey-client")) throw new IllegalStateException("Journey client requires disposable game directory");
        if(!connecting && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen) && System.nanoTime()-retryAt>=0) {
            connecting=true;attempt++;mc.options.guiScale().set(3);mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.resizeDisplay();
            ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25565"),new ServerData("Cloud journey","127.0.0.1:25565",false),false);
        }
        if(mc.level!=null && !joined) { joined=true; writeConnectionReport(mc); }
        if(connecting && mc.screen instanceof DisconnectedScreen screen) {
            if(joined) { mc.stop(); return; } // the server went away after the player was in: the end of a phase, as before
            System.out.println("[UC-JOURNEY] connection attempt "+attempt+" of "+ATTEMPTS+" ended on the disconnect screen: "+screen.getNarrationMessage().getString());
            DROPPED.add(screen.getNarrationMessage().getString());
            writeConnectionReport(mc);
            if(attempt>=ATTEMPTS) { mc.stop(); return; }
            connecting=false;retryAt=System.nanoTime()+RETRY_NANOS;mc.setScreen(new TitleScreen());
        }
    }
    private static void writeConnectionReport(Minecraft mc) {
        var report=new com.google.gson.JsonObject();
        report.addProperty("attempts",attempt);
        report.addProperty("joined",joined);
        var dropped=new com.google.gson.JsonArray();
        DROPPED.forEach(dropped::add);
        report.add("dropped_logins",dropped);
        try {Files.writeString(mc.gameDirectory.toPath().resolve("journey-client-connection.json"),report+"\n");}
        catch(java.io.IOException error) {throw new IllegalStateException(error);}
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED||event.phase!=TickEvent.Phase.END) return;
        if(!QShopEconomy.available()) throw new IllegalStateException("Journey requires installed Q Shop 1.8.4");
        if(PerfSessionServer.ENABLED) { PerfSessionClient.tick(Minecraft.getInstance()); return; }   // the two-player performance session: a walk, no screens
        if(DuoSessionServer.ENABLED) { DuoSessionClient.tick(Minecraft.getInstance()); return; }   // the ninth pass's two-player session: the first-login screen, then the server drives
        if(CrewJourneyServer.ENABLED) {
            // the combined journey's backpack and exchange steps use the screen once this client's Crew steps are done (SurvivalJourneyActions)
            if(SurvivalJourneyActions.takesOver(Minecraft.getInstance())) SurvivalJourneyActions.tick(Minecraft.getInstance());
            else CrewJourneyActions.tick(Minecraft.getInstance());
        }
        else QShopJourneyActions.tick(Minecraft.getInstance());
    }
}
