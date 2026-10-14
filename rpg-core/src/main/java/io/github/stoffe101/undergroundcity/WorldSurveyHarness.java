package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import java.nio.file.Files;
import java.util.List;

/** Actual generated-block survey, deliberately separate from schema/asset tests. */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID)
public final class WorldSurveyHarness {
    private static final boolean ENABLED=Boolean.getBoolean("uc.worldSurvey");
    private static final List<String> CITIES=List.of("ironhaven","valewood","blackwater");
    private static final JsonArray observations=new JsonArray();
    private static int current;
    private static boolean finished;
    private WorldSurveyHarness() {}

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(!ENABLED || finished || event.phase!=TickEvent.Phase.END) return;
        var server=event.getServer();
        var folder=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
        if(!folder.getFileName().toString().equals("run-world-survey")) throw new IllegalStateException("World survey requires its disposable directory");
        JsonObject report=new JsonObject();
        try {
            if(!ModList.get().isLoaded("lostcities")) throw new IllegalStateException("Pinned Lost Cities is missing");
            String city=CITIES.get(current);
            var resource=server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
                UndergroundCity.MOD_ID,"lostcities/predefinedcities/"+city+".json")).orElseThrow();
            JsonObject definition;
            try(var reader=resource.openAsReader()) {definition=JsonParser.parseReader(reader).getAsJsonObject();}
            int cx=definition.get("chunkx").getAsInt(),cz=definition.get("chunkz").getAsInt();
            var level=server.overworld();var position=new BlockPos.MutableBlockPos();
            String biome=level.getBiome(new BlockPos(cx*16+8,64,cz*16+8)).unwrapKey()
                .map(key->key.location().toString()).orElse("unknown");
            int glass=0,minY=level.getMaxBuildHeight(),maxY=level.getMinBuildHeight();
            for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
                var chunk=level.getChunk(cx+dx,cz+dz); // Forces native terrain/structure generation.
                for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                    for(int y=level.getMinBuildHeight();y<level.getMaxBuildHeight();y++) {
                        position.set((cx+dx)*16+x,y,(cz+dz)*16+z);
                        var key=ForgeRegistries.BLOCKS.getKey(chunk.getBlockState(position).getBlock());
                        if(key!=null && (key.getPath().equals("glass") || key.getPath().equals("glass_pane")
                                || key.getPath().endsWith("_stained_glass") || key.getPath().endsWith("_stained_glass_pane"))) {
                            glass++;minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                        }
                    }
                }
            }
            JsonObject observation=new JsonObject();observation.addProperty("city",city);
            observation.addProperty("center_chunk_x",cx);observation.addProperty("center_chunk_z",cz);
            observation.addProperty("generated_chunks",9);observation.addProperty("actual_glass_blocks",glass);
            observation.addProperty("center_biome",biome);
            observation.addProperty("min_window_y",minY);observation.addProperty("max_window_y",maxY);
            observations.add(observation);
            if(glass<64 || maxY-minY<8) throw new IllegalStateException("No substantial generated urban structure at "+city+": "+observation);
            System.out.println("[UC-WORLD] Actual generated city evidence: "+observation);
            if(++current<CITIES.size()) return;
            report.addProperty("status","three_city_structures_observed");
        } catch(Exception error) {
            report.addProperty("status","failed");report.addProperty("error",error.toString());
            com.mojang.logging.LogUtils.getLogger().error("Native city survey failed",error);
        }
        report.add("observations",observations);
        report.addProperty("qshop_credits_ready",QShopEconomy.available() && QShopEconomy.configured(server));
        report.addProperty("roads_bridges_population_verified",false);
        try {Files.writeString(folder.resolve("world-survey.json"),report.toString()+"\n");}
        catch(java.io.IOException error) {throw new IllegalStateException("Cannot record world survey",error);}
        finished=true;server.halt(false);
    }
}
