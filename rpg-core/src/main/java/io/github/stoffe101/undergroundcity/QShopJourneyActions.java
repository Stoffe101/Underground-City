package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import java.nio.file.Files;

/** Loaded only by the explicitly enabled journey with the provider installed. */
final class QShopJourneyActions {
    private static int stage,ticks;
    private QShopJourneyActions() {}
    static void tick(Minecraft mc) {
        if(mc.player==null || ++ticks%20!=0) return;
        if(mc.screen instanceof TerminalScreen screen) {
            if(stage==0 || stage==1) {
                if(stage==1 && screen.snapshot().active()==null) return;
                screen.showPage(TerminalScreen.Page.CONTRACTS);
                screen.clickPrimary();stage++;
                System.out.println("[UC-JOURNEY] clicked terminal action stage="+stage);
            } else if(stage==2) {
                if(screen.snapshot().active()!=null || screen.snapshot().xp()<125) return;
                screen.showPage(TerminalScreen.Page.IMPORTS);
                screen.clickShop();stage++;
            }
        } else if(mc.screen instanceof CivilianShopScreen screen) {
            // Core's civilian counter stands in front of the provider's screen for Core's own shops (CivilianShopHook); a press sends the provider's own trade request and the server
            // carries it out. The counter buys at once (no confirmation dialog), and the refreshed shop arrives as a new screen, so each step waits for the next one-second tick.
            if(stage==3) {
                if(screen.clickEntry(0)) { stage=5; System.out.println("[UC-JOURNEY] pressed the civilian counter's first Buy stage="+stage); }
            } else if(stage==5) {
                if(screen.clickEntry(5)) { stage=7; System.out.println("[UC-JOURNEY] pressed the civilian counter's delivery stage="+stage); }
            }
        }
        // The fixture server reopens the terminal after the provider trades; buy one regional item with a real click.
        if(stage==7 && mc.screen instanceof TerminalScreen screen && !screen.snapshot().offers().isEmpty()
                && screen.snapshot().xp()>=125) {
            screen.showPage(TerminalScreen.Page.IMPORTS);
            if(!screen.clickBuy(0,false)) throw new IllegalStateException("Regional BUY 1 button did not accept the click");
            stage++;
            System.out.println("[UC-JOURNEY] clicked regional exchange BUY 1; offers="+screen.snapshot().offers().size()
                +" region="+screen.snapshot().region());
            try {Files.writeString(mc.gameDirectory.toPath().resolve("journey-client-actions.txt"),
                "real_terminal_accept,real_terminal_deliver,qshop_buy,qshop_sell,regional_exchange_buy\n");}
            catch(java.io.IOException error) {throw new IllegalStateException(error);}
        }
    }
    private static void clickShop(net.minecraft.client.gui.screens.Screen screen,double x,double y) {
        screen.mouseClicked(com.qshop.client.QShopScreenInput.toScaledCoordinate(x,screen.width),
            com.qshop.client.QShopScreenInput.toScaledCoordinate(y,screen.height),0);
    }
}
