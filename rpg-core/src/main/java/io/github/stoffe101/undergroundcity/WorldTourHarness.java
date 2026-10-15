package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
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
    /**
     * Two views per city (straight down for layout/streets, an oblique skyline) and, when the survey measured
     * a bridge, two views per built road: the widest crossing from the side and a walker's view along its deck.
     */
    private record Shot(String subject,String kind,JsonObject showcase) {
        boolean road() { return showcase!=null; }
        String file() { return "uc-world-"+(road()?"road-":"")+subject+"-"+kind+".png"; }
    }
    private static List<Shot> shotList;

    private static List<Shot> shots(java.nio.file.Path folder) {
        if(shotList!=null) return shotList;
        List<Shot> list=new java.util.ArrayList<>();
        for(String city:CITIES) {list.add(new Shot(city,"top",null));list.add(new Shot(city,"oblique",null));}
        var survey=folder.resolve("world-survey.json");
        if(Files.isRegularFile(survey)) {
            try {
                var roads=JsonParser.parseString(Files.readString(survey)).getAsJsonObject().getAsJsonObject("roads");
                if(roads!=null) for(var route:roads.getAsJsonArray("routes")) {
                    var entry=route.getAsJsonObject();
                    if(!entry.has("showcase")) continue;
                    list.add(new Shot(entry.get("id").getAsString(),"bridge-oblique",entry.getAsJsonObject("showcase")));
                    list.add(new Shot(entry.get("id").getAsString(),"deck",entry.getAsJsonObject("showcase")));
                    if(entry.has("land_showcase")) list.add(new Shot(entry.get("id").getAsString(),"land",entry.getAsJsonObject("land_showcase")));
                }
            } catch(java.io.IOException|RuntimeException error) {
                throw new IllegalStateException("Cannot read road showcase from the survey report",error);
            }
        }
        shotList=List.copyOf(list);
        return shotList;
    }
    // Software rendering and Lost Cities generation are slow: wait for real terrain, but always capture
    // (and let the hosted checker reject blank frames) before the stage limit.
    private static final int MIN_LOADED_CHUNKS=60,MIN_RENDERED_SECTIONS=30,STABLE_TICKS=100;
    private static int lastSections,stableTicks;
    private static final long READY_WAIT_NANOS=240_000_000_000L,STAGE_LIMIT_NANOS=330_000_000_000L;
    private static boolean opening,positioned,captureRequested,captured,finished;
    private static int current,ticks;
    private static double targetX,targetZ;
    private static long stageStarted=System.nanoTime(),lastReport;
    private WorldTourHarness() {}

    private static int renderedSections(Minecraft mc) {
        try { return mc.levelRenderer==null?0:mc.levelRenderer.countRenderedChunks(); }
        catch(NullPointerException notReady) { return 0; }
    }

    /** The chunk mesh dispatcher does not exist until the first level frame; treat that as "not idle yet". */
    private static boolean meshesIdle(Minecraft mc) {
        try { return mc.levelRenderer!=null && mc.levelRenderer.hasRenderedAllChunks(); }
        catch(NullPointerException notReady) { return false; }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if(!ENABLED || finished || event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        var folder=mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if(!folder.getFileName().toString().equals("run-world-tour")) throw new IllegalStateException("World tour requires disposable directory");
        long now=System.nanoTime();
        if(now-lastReport>30_000_000_000L) {
            lastReport=now;
            String state="shot="+shots(folder).get(current).file()+", ticks="+ticks+", positioned="+positioned
                +", captured="+captured+", screen="+(mc.screen==null?"none":mc.screen.getClass().getName())
                +", player="+(mc.player!=null)+", server="+(mc.getSingleplayerServer()!=null)
                +", loadedChunks="+(mc.level==null?0:mc.level.getChunkSource().getLoadedChunksCount())
                +", meshesIdle="+meshesIdle(mc);
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
            opening=true;stageStarted=now;mc.options.guiScale().set(3);mc.options.renderDistance().set(8);
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
            Screenshot.grab(mc.gameDirectory,shots(folder).get(current).file(),mc.getMainRenderTarget(),
                response->System.out.println("[UC-WORLD-VISUAL] "+response.getString()));
        }
        if(!captured || mc.screen!=null) return;
        String name=shots(folder).get(current).file();
        try {
            var file=folder.resolve("screenshots").resolve(name);
            if(!Files.isRegularFile(file) || Files.size(file)<1024) return;
            captured=false;positioned=false;ticks=0;stageStarted=now;lastSections=0;stableTicks=0;
            if(++current==shots(folder).size()) {
                Files.writeString(folder.resolve("world-tour-complete.txt"),"actual_survey_world=true\nviewports=1920x1080_gui3\ncities=3\nshots="
                    +shots(folder).size()+"\nroad_shots="+shots(folder).stream().filter(Shot::road).count()+"\n");
                finished=true;mc.stop();
            }
        } catch(java.io.IOException error) {throw new IllegalStateException("World tour screenshot failed",error);}
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(!ENABLED || finished || captured || captureRequested || event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();var server=mc.getSingleplayerServer();
        if(mc.player==null || mc.level==null || server==null || mc.screen!=null) return;
        if(!positioned) {
            Shot shot=shots(mc.gameDirectory.toPath().toAbsolutePath().normalize()).get(current);
            if(shot.road()) { positionRoadShot(mc,server,shot);positioned=true;ticks=0;return; }
            // Definitions are server datapack resources, not client assets.
            double centerX,centerZ;
            try(var reader=server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                UndergroundCity.MOD_ID,"lostcities/predefinedcities/"+shot.subject()+".json")).orElseThrow().openAsReader()) {
                var data=JsonParser.parseReader(reader).getAsJsonObject();
                centerX=data.get("chunkx").getAsInt()*16+8;centerZ=data.get("chunkz").getAsInt()*16+8;
            } catch(java.io.IOException error) {throw new IllegalStateException(error);}
            boolean top=shot.kind().equals("top");
            targetX=top?centerX:centerX-75;targetZ=top?centerZ:centerZ-75;
            float yaw=top?0:-45,pitch=top?90:32;
            var playerId=mc.player.getUUID();double x=targetX,z=targetZ,cx=centerX,cz=centerZ;String shotName=shot.subject()+"-"+shot.kind();
            server.execute(()->{
                var player=server.getPlayerList().getPlayer(playerId);
                if(player==null) throw new IllegalStateException("World-tour player missing");
                player.setGameMode(GameType.SPECTATOR);server.overworld().setDayTime(6000);
                server.overworld().setWeatherParameters(6000,0,false,false);
                // Height is read on the server thread so a not-yet-loaded chunk cannot stall the client.
                // Tallest real block within 48 blocks of the centre. Lost Cities chunks may carry unprimed heightmaps
                // (they read -64), so scan the blocks themselves from the sky downward.
                int ground=server.overworld().getMinBuildHeight();
                var column=new net.minecraft.core.BlockPos.MutableBlockPos();
                // Around the centre and around the camera: a tall building beside the camera otherwise fills the oblique frame.
                for(double[] around:new double[][]{{cx,cz},{x,z}}) for(int dx=-48;dx<=48;dx+=24) for(int dz=-48;dz<=48;dz+=24) {
                    for(int y=server.overworld().getMaxBuildHeight()-1;y>server.overworld().getMinBuildHeight();y--) {
                        if(!server.overworld().getBlockState(column.set((int)around[0]+dx,y,(int)around[1]+dz)).isAir()) {ground=Math.max(ground,y);break;}
                    }
                }
                int cameraY=ground+45; // high enough to clear the rooftops beside the camera (a lower oblique view stared at one roof)
                var probe=new net.minecraft.core.BlockPos.MutableBlockPos((int)Math.floor(x),cameraY,(int)Math.floor(z));
                while(cameraY<server.overworld().getMaxBuildHeight()-2 && !server.overworld().getBlockState(probe.setY(cameraY)).isAir()) cameraY++;
                System.out.println("[UC-WORLD-VISUAL] camera for "+shotName+": ground="+ground+" y="+cameraY);
                player.teleportTo(server.overworld(),x,cameraY,z,yaw,pitch);
            });
            positioned=true;ticks=0;return;
        }
        if(Math.abs(mc.player.getX()-targetX)>2 || Math.abs(mc.player.getZ()-targetZ)>2) return;
        if(++ticks<160 || !mc.level.getChunkSource().hasChunk((int)Math.floor(targetX/16),(int)Math.floor(targetZ/16))) return;
        var shot=shots(mc.gameDirectory.toPath().toAbsolutePath().normalize()).get(current);
        // A road frame is only meaningful once the client really holds the deck blocks it is aimed at: the middle of the
        // crossing.
        if(shot.road() && !deckLoaded(mc,shot) && System.nanoTime()-stageStarted<READY_WAIT_NANOS) return;
        // A queue-idle flag alone is unreliable: meshes may not even be scheduled yet. Require a minimum number of
        // chunks in the frustum whose count has stopped changing for several seconds of client ticks.
        int sections=renderedSections(mc);
        stableTicks=sections==lastSections?stableTicks+1:0;lastSections=sections;
        boolean terrainReady=mc.level.getChunkSource().getLoadedChunksCount()>=MIN_LOADED_CHUNKS && meshesIdle(mc)
            && sections>=(shot.road() && shot.kind().equals("bridge-oblique")?100:MIN_RENDERED_SECTIONS) && stableTicks>=STABLE_TICKS;
        if(!terrainReady && System.nanoTime()-stageStarted<READY_WAIT_NANOS) return;
        System.out.println("[UC-WORLD-VISUAL] Capturing "+shots(mc.gameDirectory.toPath().toAbsolutePath().normalize()).get(current).file()+": loadedChunks="
            +mc.level.getChunkSource().getLoadedChunksCount()+", renderedSections="+sections+", stableTicks="+stableTicks
            +", camera="+String.format(java.util.Locale.ROOT,"%.1f,%.1f,%.1f",mc.player.getX(),mc.player.getY(),mc.player.getZ())
            +", terrainReady="+terrainReady+(shot.road()?", deckLoaded="+deckLoaded(mc,shot):""));
        captureRequested=true;
    }

    private static boolean deckLoaded(Minecraft mc,Shot shot) {
        var data=shot.showcase();
        int x=(int)Math.floor(data.get("x").getAsDouble()),z=(int)Math.floor(data.get("z").getAsDouble()),deck=data.get("deck_y").getAsInt();
        // The plan interpolates between samples, so the block can sit one layer off the surveyed middle.
        for(int dy=-2;dy<=0;dy++) {
            var state=mc.level.getBlockState(new net.minecraft.core.BlockPos(x,deck+dy,z));
            if(state.is(net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE) || state.is(net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE_SLAB)) return true;
        }
        return false;
    }

    /**
     * Camera for a measured bridge: either beside the middle of the widest crossing looking at the deck, or standing on
     * the deck sixty blocks before that middle looking along the road. Positions come from the survey report, so no
     * coordinate is hardcoded here.
     */
    private static void positionRoadShot(Minecraft mc,net.minecraft.server.MinecraftServer server,Shot shot) {
        var data=shot.showcase();
        double cx=data.get("x").getAsDouble(),cz=data.get("z").getAsDouble(),deck=data.get("deck_y").getAsInt();
        double hx=data.get("heading_dx").getAsDouble(),hz=data.get("heading_dz").getAsDouble(),norm=Math.max(1e-6,Math.hypot(hx,hz));
        hx/=norm;hz/=norm;
        double x,y,z;float yaw,pitch;
        if(shot.kind().equals("deck") || shot.kind().equals("land")) {
            x=cx-hx*60;z=cz-hz*60;y=deck+3.0;
            yaw=(float)Math.toDegrees(Math.atan2(-hx,hz));pitch=7;
        } else {
            // Low and far enough that the horizon is in frame; a high, steep camera mostly shows the unrendered sky
            // beyond the render distance.
            double nx=-hz,nz=hx;
            x=cx+nx*60;z=cz+nz*60;y=deck+13;
            double dx=cx-x,dz=cz-z,dy=deck-y;
            yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));pitch=(float)Math.toDegrees(Math.atan2(-dy,Math.hypot(dx,dz)));
        }
        targetX=x;targetZ=z;
                var playerId=mc.player.getUUID();double fx=x,fy=y,fz=z;float fyaw=yaw,fpitch=pitch;String name=shot.file();
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(playerId);
            if(player==null) throw new IllegalStateException("World-tour player missing");
            player.setGameMode(GameType.SPECTATOR);server.overworld().setDayTime(6000);
            server.overworld().setWeatherParameters(6000,0,false,false);
            System.out.println("[UC-WORLD-VISUAL] camera for "+name+": "+String.format(java.util.Locale.ROOT,"%.1f,%.1f,%.1f yaw=%.0f pitch=%.0f",fx,fy,fz,fyaw,fpitch));
            player.teleportTo(server.overworld(),fx,fy,fz,fyaw,fpitch);
        });
    }
}
