package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Real rendered screens with controlled snapshot fixtures; no gameplay claims. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT)
public final class VisualCaptureHarness {
    private static final boolean ENABLED=Boolean.getBoolean("uc.visualCapture");
    private record Case(String name,TerminalScreen.Page page,TerminalData data,int shop) {}
    private static List<Case> cases;
    private static int current,frames;
    private static boolean started,completed;
    private VisualCaptureHarness() {}
    private static TerminalData fixture(int level,int held,boolean active,boolean online) {
        return new TerminalData("valewood","farmer",level,level==1?0:125,60,online,
            active?UUID.fromString("00000000-0000-0000-0000-000000000001"):null,
            List.of(new TerminalData.Row("underground_city:bread","Bake the canteen bread order",
                "minecraft:bread",8,held,2,36,180,4,active)));
    }
    private static void start(Minecraft mc) {
        int scale=Integer.getInteger("uc.visualCapture.scale",3);
        if(scale<1||scale>4) throw new IllegalArgumentException("Bad GUI scale");
        mc.options.guiScale().set(scale); mc.resizeDisplay();
        cases=new ArrayList<>();
        for(var page:TerminalScreen.Page.values()) cases.add(new Case("uc-terminal-"+page.name().toLowerCase(java.util.Locale.ROOT),page,fixture(2,8,false,true),0));
        cases.add(new Case("uc-contract-accepted",TerminalScreen.Page.CONTRACTS,fixture(2,8,true,true),0));
        cases.add(new Case("uc-contract-missing",TerminalScreen.Page.CONTRACTS,fixture(2,2,true,true),0));
        cases.add(new Case("uc-contract-locked",TerminalScreen.Page.CONTRACTS,fixture(1,0,false,true),0));
        cases.add(new Case("uc-contract-unregistered",TerminalScreen.Page.CONTRACTS,TerminalData.offline(),0));
        cases.add(new Case("uc-contract-offline",TerminalScreen.Page.CONTRACTS,fixture(2,8,false,false),0));
        if(!QShopEconomy.available()) throw new IllegalStateException("Visual coverage requires installed Q Shop 1.8.4");
        cases.add(new Case("uc-qshop-main",null,null,1));
        cases.add(new Case("uc-qshop-low-funds",null,null,2));
        cases.add(new Case("uc-qshop-trade-modal",null,null,3));
        started=true; show(mc);
    }
    private static void show(Minecraft mc) {
        Case capture=cases.get(current);
        if(capture.shop>0) {
            Screen screen=QShopVisualCases.shop(capture.shop==1);
            mc.setScreen(screen);
            if(capture.shop==3) QShopVisualCases.openLowFundsModal(screen);
        } else {
            TerminalScreen screen=new TerminalScreen(BlockPos.ZERO,capture.data);
            mc.setScreen(screen); screen.showPage(capture.page);
        }
        frames=0;
    }
    @SubscribeEvent public static void tick(TickEvent.RenderTickEvent event) {
        if(!ENABLED||started||completed||event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        if(mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen) start(mc);
    }
    @SubscribeEvent public static void render(ScreenEvent.Render.Post event) {
        if(!ENABLED||!started||completed) return;
        Minecraft mc=Minecraft.getInstance();
        if(current>=cases.size()) {
            try {
                Path dir=mc.gameDirectory.toPath().resolve("screenshots");
                for(Case capture:cases) if(!Files.isRegularFile(dir.resolve(capture.name+".png")) || Files.size(dir.resolve(capture.name+".png"))<1024) return;
                Files.writeString(mc.gameDirectory.toPath().resolve("visual-capture-complete.txt"),
                    "screens="+cases.size()+"\ngui_scale="+Integer.getInteger("uc.visualCapture.scale",3)+"\n");
                completed=true; mc.stop();
            } catch(java.io.IOException error) { throw new IllegalStateException("Visual capture failed",error); }
            return;
        }
        if(++frames<12) return;
        String filename=cases.get(current).name+".png";
        Screenshot.grab(mc.gameDirectory,filename,mc.getMainRenderTarget(),
            response->System.out.println("[UC-VISUAL] Captured "+filename));
        current++;
        if(current<cases.size()) show(mc);
    }
}
