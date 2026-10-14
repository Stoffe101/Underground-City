package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.nio.file.Files;
import java.util.List;

/** Actual cloned survey-world images, opt-in and confined to a disposable instance. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID,value=Dist.CLIENT)
public final class WorldTourHarness {
    private static final boolean ENABLED=Boolean.getBoolean("uc.worldTour");
    private static final List<String> CITIES=List.of("ironhaven","valewood","blackwater");
    // Software rendering and Lost Cities generation are slow: wait for real terrain, but always capture
    // (and let the hosted checker reject blank frames) before the stage limit.
    private static final int MIN_LOADED_CHUNKS=60;
    private static final long READY_WAIT_NANOS=240_000_000_000L,STAGE_LIMIT_NANOS=330_000_000_000L;
    private static boolean opening,positioned,captureRequested,captured,finished;
    private static int current,ticks;
    private static double targetX,targetZ;
    private static long stageStarted=System.nanoTime(),lastReport;
    private WorldTourHarness() {}

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if(!ENABLED || finished || event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        var folder=mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if(!folder.getFileName().toString().equals("run-world-tour")) throw new IllegalStateException("World tour requires disposable directory");
        long now=System.nanoTime();
        if(now-lastReport>30_000_000_000L) {
            lastReport=now;
            String state="city="+CITIES.get(current)+", ticks="+ticks+", positioned="+positioned
                +", captured="+captured+", screen="+(mc.screen==null?"none":mc.screen.getClass().getName())
                +", player="+(mc.player!=null)+", server="+(mc.getSingleplayerServer()!=null)
                +", loadedChunks="+(mc.level==null?0:mc.level.getChunkSource().getLoadedChunksCount())
                +", meshesIdle="+(mc.levelRenderer!=null && mc.levelRenderer.hasRenderedAllChunks());
            if(mc.screen!=null) state+=", buttons="+mc.screen.children().stream()
                .filter(child->child instanceof Button).map(child->((Button)child).getMessage().getString()).toList();
            System.out.println("[UC-WORLD-VISUAL] "+state);
            try {Files.writeString(folder.resolve("world-tour-state.txt"),state+"\n");}
            catch(java.io.IOException error) {throw new IllegalStateException(error);}
        }
        if(opening && now-stageStarted>STAGE_LIMIT_NANOS) {
            System.err.println("[UC-WORLD-VISUAL] Stage timed out; inspect world-tour-state.txt");
            finished=true;mc.stop();return;
        }
        if(!opening && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            if(!Files.isRegularFile(folder.resolve("saves/world/level.dat"))) throw new IllegalStateException("Native survey-world clone missing");
            opening=true;stageStarted=now;mc.options.guiScale().set(3);mc.options.renderDistance().set(6);
            mc.options.pauseOnLostFocus=false;mc.options.framerateLimit().set(30);mc.options.hideGui=true;mc.resizeDisplay();
            mc.createWorldOpenFlows().loadLevel(mc.screen,"world");
            return;
        }
        // Only the cloned disposable world can reach this opt-in confirmation.
        if(opening && mc.player==null && mc.screen!=null) {
            for(var child:mc.screen.children()) if(child instanceof Button button) {
                String label=button.getMessage().getString();
                if(label.equals("Proceed") || label.equals("I know what I'm doing!")
                    || (mc.screen instanceof ConfirmScreen && (label.equals("Yes") || label.equals("Continue")))) {
                    System.out.println("[UC-WORLD-VISUAL] Confirmed cloned-world load: "+label);
                    button.onPress();break;
                }
            }
        }
        if(captureRequested && mc.screen==null && mc.player!=null && mc.level!=null) {
            // Client ticks run after the target is cleared but before terrain is
            // rendered. Read the completed framebuffer at render-tick END.
            captureRequested=false;captured=true;
            Screenshot.grab(mc.gameDirectory,"uc-world-"+CITIES.get(current)+".png",mc.getMainRenderTarget(),
                response->System.out.println("[UC-WORLD-VISUAL] "+response.getString()));
        }
        if(!captured || mc.screen!=null) return;
        String name="uc-world-"+CITIES.get(current)+".png";
        try {
            var file=folder.resolve("screenshots").resolve(name);
            if(!Files.isRegularFile(file) || Files.size(file)<1024) return;
            captured=false;positioned=false;ticks=0;stageStarted=now;
            if(++current==CITIES.size()) {
                Files.writeString(folder.resolve("world-tour-complete.txt"),"actual_survey_world=true\nviewports=1920x1080_gui3\ncities=3\n");
                finished=true;mc.stop();
            }
        } catch(java.io.IOException error) {throw new IllegalStateException("World tour screenshot failed",error);}
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED || finished || captured || captureRequested || event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();
        if(mc.player==null || mc.level==null || server==null || mc.screen!=null) return;
        if(!positioned) {
            String city=CITIES.get(current);
            // Definitions are server datapack resources, not client assets.
            try(var reader=server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                UndergroundCity.MOD_ID,"lostcities/predefinedcities/"+city+".json")).orElseThrow().openAsReader()) {
                var data=JsonParser.parseReader(reader).getAsJsonObject();
                targetX=data.get("chunkx").getAsInt()*16+40;targetZ=data.get("chunkz").getAsInt()*16+40;
            } catch(java.io.IOException error) {throw new IllegalStateException(error);}
            var playerId=mc.player.getUUID();double x=targetX,z=targetZ;
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(playerId);
                if(player==null) throw new IllegalStateException("World-tour player missing");
                player.setGameMode(GameType.SPECTATOR);server.overworld().setDayTime(6000);
                server.overworld().setWeatherParameters(6000,0,false,false);
                // Keep the ground within terrain fog distance from this aerial camera.
                player.teleportTo(server.overworld(),x,150,z,135,55);
            });
            positioned=true;ticks=0;return;
        }
        if(Math.abs(mc.player.getX()-targetX)>2 || Math.abs(mc.player.getZ()-targetZ)>2) return;
        if(++ticks<160 || !mc.level.getChunkSource().hasChunk((int)Math.floor(targetX/16),(int)Math.floor(targetZ/16))) return;
        boolean terrainReady=mc.level.getChunkSource().getLoadedChunksCount()>=MIN_LOADED_CHUNKS
            && mc.levelRenderer.hasRenderedAllChunks();
        if(!terrainReady && System.nanoTime()-stageStarted<READY_WAIT_NANOS) return;
        System.out.println("[UC-WORLD-VISUAL] Capturing "+CITIES.get(current)+": loadedChunks="
            +mc.level.getChunkSource().getLoadedChunksCount()+", terrainReady="+terrainReady);
        captureRequested=true;
    }
}
