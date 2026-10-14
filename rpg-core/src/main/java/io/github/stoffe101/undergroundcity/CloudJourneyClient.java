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

/** Real mouse input into Core and the installed provider. Loopback cloud tests only. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT)
public final class CloudJourneyClient {
    private static final boolean ENABLED=Boolean.getBoolean("uc.journey.client");
    private static boolean connecting;
    private static int stage,ticks;
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
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || ++ticks%20!=0) return;
        if(mc.screen instanceof TerminalScreen screen) {
            int top=(screen.height-Math.min(268,screen.height-16))/2;
            int buttonY=top+Math.min(268,screen.height-16)-47;
            if(stage==0 || stage==1) {
                if(stage==1 && screen.snapshot().active()==null) return;
                screen.mouseClicked(screen.width/2,buttonY,0);stage++;
                System.out.println("[UC-JOURNEY] clicked terminal action stage="+stage);
            } else if(stage==2) {
                if(screen.snapshot().active()!=null || screen.snapshot().xp()<125) return;
                screen.showPage(TerminalScreen.Page.IMPORTS);
                screen.mouseClicked(screen.width/2,buttonY,0);stage++;
            }
        } else if(mc.screen instanceof com.qshop.client.ShopScreen screen) {
            int left=(screen.width-302)/2+52,top=(screen.height-200)/2;
            if(stage==3 || stage==5) {
                int entry=stage==3?0:5;
                clickShop(screen,left+11+16+entry*33,top+40+15);stage++;
            } else if(stage==4 || stage==6) {
                int dialogX=left+50,dialogY=top+45;
                clickShop(screen,dialogX+41,dialogY+121);stage++;
                System.out.println("[UC-JOURNEY] clicked provider confirmation stage="+stage);
                if(stage==7) {
                    try {Files.writeString(mc.gameDirectory.toPath().resolve("journey-client-actions.txt"),"real_terminal_accept,real_terminal_deliver,qshop_buy,qshop_sell\n");}
                    catch(java.io.IOException error) {throw new IllegalStateException(error);}
                }
            }
        }
    }
    private static void clickShop(net.minecraft.client.gui.screens.Screen screen,double x,double y) {
        screen.mouseClicked(com.qshop.client.QShopScreenInput.toScaledCoordinate(x,screen.width),
            com.qshop.client.QShopScreenInput.toScaledCoordinate(y,screen.height),0);
    }
}
