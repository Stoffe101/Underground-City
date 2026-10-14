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
            active?UUID.fromString("00000000-0000-0000-0000-000000000001"):null,0,"",
            List.of(new TerminalData.Row("underground_city:bread","Bake the canteen bread order",2,36,180,4,active,0,
                List.of(new TerminalData.Req("minecraft:bread",8,held)))),List.of());
    }
    private static final List<TerminalData.Offer> IRONHAVEN_OFFERS=List.of(
        new TerminalData.Offer("underground_city:machined_parts","underground_city:machined_parts",18,16,36,8),
        new TerminalData.Offer("underground_city:refined_alloy","underground_city:refined_alloy",28,14,30,8),
        new TerminalData.Offer("underground_city:security_fabric","underground_city:security_fabric",22,0,24,8));
    private static TerminalData exchange(String region,double balance) {
        return new TerminalData("valewood","farmer",3,520,balance,true,null,region.isEmpty()?0:1,region,
            List.of(),region.isEmpty()?List.of():IRONHAVEN_OFFERS);
    }
    private static TerminalData regionalContract(boolean accepted,int held,int visits) {
        return new TerminalData("valewood","farmer",3,520,230,true,
            accepted?UUID.fromString("00000000-0000-0000-0000-000000000002"):null,visits,"valewood",
            List.of(new TerminalData.Row("underground_city:greenhouse_service","Service the greenhouse irrigation line",3,150,300,2,accepted,1,
                List.of(new TerminalData.Req("underground_city:irrigation_service_kit",4,held)))),List.of());
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
        cases.add(new Case("uc-exchange-ironhaven",TerminalScreen.Page.IMPORTS,exchange("ironhaven",120),0));
        cases.add(new Case("uc-exchange-low-funds",TerminalScreen.Page.IMPORTS,exchange("ironhaven",20),0));
        cases.add(new Case("uc-imports-outside-city",TerminalScreen.Page.IMPORTS,exchange("",120),0));
        cases.add(new Case("uc-contract-regional",TerminalScreen.Page.CONTRACTS,regionalContract(true,2,1),0));
        cases.add(new Case("uc-contract-visit-locked",TerminalScreen.Page.CONTRACTS,regionalContract(false,0,0),0));
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
