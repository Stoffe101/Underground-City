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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Actual generated-world survey, deliberately separate from schema/asset tests. Stage 1 generates nine
 * chunks per city and counts real window blocks. Stage 2 asks Lost Cities itself which chunks in the region
 * between the cities are city, building or highway chunks and measures whether highways connect the anchors.
 */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID)
public final class WorldSurveyHarness {
    private static final boolean ENABLED=Boolean.getBoolean("uc.worldSurvey");
    private static final List<String> CITIES=List.of("ironhaven","valewood","blackwater");
    private static final JsonArray observations=new JsonArray();
    private static final int MARGIN=24;
    private static final record Anchor(String id,int chunkX,int chunkZ,int radiusChunks) {}
    private static final List<Anchor> anchors=new ArrayList<>();
    private static int current,scanRow;
    private static int minX,maxX,minZ,maxZ;
    private static long scanStarted;
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
            if(current<CITIES.size()) {
                survey(server,CITIES.get(current));
                if(++current<CITIES.size()) return;
            }
            if(LostCitiesLink.ready()) {
                if(scanStarted==0) beginScan();
                if(scanStep(server.overworld())) return;
                report.add("native_map",analyse(System.nanoTime()-scanStarted));
            } else {
                report.addProperty("native_map_error","Lost Cities IMC API was not available");
            }
            report.addProperty("status","three_city_structures_observed");
        } catch(Exception error) {
            report.addProperty("status","failed");report.addProperty("error",error.toString());
            com.mojang.logging.LogUtils.getLogger().error("Native city survey failed",error);
        }
        report.add("observations",observations);
        report.addProperty("qshop_credits_ready",QShopEconomy.available() && QShopEconomy.configured(server));
        // Population and furnished interiors are never inferred from a block survey.
        report.addProperty("roads_bridges_population_verified",false);
        try {Files.writeString(folder.resolve("world-survey.json"),report.toString()+"\n");}
        catch(java.io.IOException error) {throw new IllegalStateException("Cannot record world survey",error);}
        finished=true;server.halt(false);
    }

    private static void survey(net.minecraft.server.MinecraftServer server,String city) throws java.io.IOException {
        var resource=server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(
            UndergroundCity.MOD_ID,"lostcities/predefinedcities/"+city+".json")).orElseThrow();
        JsonObject definition;
        try(var reader=resource.openAsReader()) {definition=JsonParser.parseReader(reader).getAsJsonObject();}
        int cx=definition.get("chunkx").getAsInt(),cz=definition.get("chunkz").getAsInt(),radius=definition.get("radius").getAsInt();
        anchors.add(new Anchor(city,cx,cz,(radius+15)/16));
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
        observation.addProperty("radius_blocks",radius);
        observation.addProperty("generated_chunks",9);observation.addProperty("actual_glass_blocks",glass);
        observation.addProperty("center_biome",biome);
        observation.addProperty("min_window_y",minY);observation.addProperty("max_window_y",maxY);
        observations.add(observation);
        if(glass<64 || maxY-minY<8) throw new IllegalStateException("No substantial generated urban structure at "+city+": "+observation);
        System.out.println("[UC-WORLD] Actual generated city evidence: "+observation);
    }

    private static final int QUERIES_PER_TICK=10,LATTICE_STRIDE=12,MAX_FLOOD_CHUNKS=4000;
    private static final java.util.Map<Long,Integer> known=new java.util.HashMap<>(); // 0 empty, 1 city/building, 2 highway
    private static final ArrayDeque<int[]> samples=new ArrayDeque<>(),frontier=new ArrayDeque<>();
    private static int sampledCount,floodQueries;

    private static long key(int x,int z) { return ((long)x<<32)^(z&0xffffffffL); }

    private static void beginScan() {
        minX=anchors.stream().mapToInt(a->a.chunkX-a.radiusChunks-MARGIN).min().orElseThrow();
        maxX=anchors.stream().mapToInt(a->a.chunkX+a.radiusChunks+MARGIN).max().orElseThrow();
        minZ=anchors.stream().mapToInt(a->a.chunkZ-a.radiusChunks-MARGIN).min().orElseThrow();
        maxZ=anchors.stream().mapToInt(a->a.chunkZ+a.radiusChunks+MARGIN).max().orElseThrow();
        // 1) a ring just outside each city: a highway serving a city must cross it.
        for(Anchor anchor:anchors) {
            int radius=anchor.radiusChunks+2;
            for(int step=0;step<64;step++) {
                double angle=step*Math.PI*2/64;
                samples.add(new int[]{anchor.chunkX+(int)Math.round(Math.cos(angle)*radius),anchor.chunkZ+(int)Math.round(Math.sin(angle)*radius)});
            }
        }
        // 2) every chunk along each straight corridor between city centres.
        for(int a=0;a<anchors.size();a++) for(int b=a+1;b<anchors.size();b++) {
            Anchor from=anchors.get(a),to=anchors.get(b);
            int length=(int)Math.ceil(Math.hypot(to.chunkX-from.chunkX,to.chunkZ-from.chunkZ));
            for(int step=0;step<=length;step++) {
                samples.add(new int[]{from.chunkX+(to.chunkX-from.chunkX)*step/length,from.chunkZ+(to.chunkZ-from.chunkZ)*step/length});
            }
        }
        // 3) a coarse lattice over the whole survey box for any stray highway or city.
        for(int z=minZ;z<=maxZ;z+=LATTICE_STRIDE) for(int x=minX;x<=maxX;x+=LATTICE_STRIDE) samples.add(new int[]{x,z});
        scanRow=0;scanStarted=System.nanoTime();
        System.out.println("[UC-WORLD] Planning "+samples.size()+" sampled Lost Cities chunk queries (ring + corridor + lattice) then highway flood-fill");
    }

    private static int query(net.minecraft.server.level.ServerLevel level,int x,int z) {
        return known.computeIfAbsent(key(x,z),k->{
            var sample=LostCitiesLink.chunk(level,x,z).orElseThrow();
            return sample.highwayLevel()>=0?2:(sample.city()?1:0);
        });
    }

    /** Returns true while work remains. Bounded queries per server tick keep the watchdog quiet. */
    private static boolean scanStep(net.minecraft.server.level.ServerLevel level) {
        for(int n=0;n<QUERIES_PER_TICK;n++) {
            int[] at=samples.poll();
            if(at!=null) {
                sampledCount++;
                if(query(level,at[0],at[1])==2) frontier.add(at);
                continue;
            }
            at=frontier.poll();
            if(at==null) return false;
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx=at[0]+d[0],nz=at[1]+d[1];
                if(known.containsKey(key(nx,nz)) || known.size()>MAX_FLOOD_CHUNKS+sampledCount) continue;
                floodQueries++;
                if(query(level,nx,nz)==2) frontier.add(new int[]{nx,nz});
            }
        }
        return true;
    }

    private static JsonObject analyse(long nanos) {
        int highwayChunks=0,cityChunks=0,cityChunksOutside=0;
        java.util.Map<Long,Integer> component=new java.util.HashMap<>();
        List<int[]> components=new ArrayList<>(); // {size,minX,maxX,minZ,maxZ}
        for(var entry:known.entrySet()) {
            int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();
            if(entry.getValue()==1) {
                cityChunks++;
                boolean inside=false;
                for(Anchor anchor:anchors) {
                    long dx=x-anchor.chunkX,dz=z-anchor.chunkZ;
                    if(dx*dx+dz*dz<=(long)anchor.radiusChunks*anchor.radiusChunks) inside=true;
                }
                if(!inside) cityChunksOutside++;
            }
            if(entry.getValue()==2) highwayChunks++;
        }
        for(var entry:known.entrySet()) {
            if(entry.getValue()!=2 || component.containsKey(entry.getKey())) continue;
            int id=components.size()+1;int[] stats={0,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE};
            ArrayDeque<int[]> queue=new ArrayDeque<>();
            queue.add(new int[]{(int)(entry.getKey()>>32),(int)(long)entry.getKey()});component.put(entry.getKey(),id);
            while(!queue.isEmpty()) {
                int[] at=queue.poll();stats[0]++;
                stats[1]=Math.min(stats[1],at[0]);stats[2]=Math.max(stats[2],at[0]);
                stats[3]=Math.min(stats[3],at[1]);stats[4]=Math.max(stats[4],at[1]);
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    long k=key(at[0]+d[0],at[1]+d[1]);
                    if(known.getOrDefault(k,0)==2 && !component.containsKey(k)) {component.put(k,id);queue.add(new int[]{at[0]+d[0],at[1]+d[1]});}
                }
            }
            components.add(stats);
        }
        JsonObject result=new JsonObject();
        result.addProperty("method","sampled Lost Cities chunk plans: city rings, corridor lines, 12-chunk lattice, highway flood-fill");
        result.addProperty("sampled_chunks",sampledCount);result.addProperty("flood_fill_queries",floodQueries);
        result.addProperty("distinct_chunks_queried",known.size());result.addProperty("scan_millis",nanos/1_000_000);
        result.addProperty("scan_min_chunk_x",minX);result.addProperty("scan_max_chunk_x",maxX);
        result.addProperty("scan_min_chunk_z",minZ);result.addProperty("scan_max_chunk_z",maxZ);
        result.addProperty("city_chunks_seen",cityChunks);result.addProperty("city_chunks_outside_declared_radii",cityChunksOutside);
        result.addProperty("highway_chunks_found",highwayChunks);result.addProperty("highway_components",components.size());
        JsonArray list=new JsonArray();
        for(int i=0;i<components.size() && i<32;i++) {
            int[] c=components.get(i);JsonObject entry=new JsonObject();
            entry.addProperty("id",i+1);entry.addProperty("chunks",c[0]);
            entry.addProperty("min_x",c[1]);entry.addProperty("max_x",c[2]);entry.addProperty("min_z",c[3]);entry.addProperty("max_z",c[4]);
            list.add(entry);
        }
        result.add("components",list);
        JsonObject contacts=new JsonObject();JsonObject nearest=new JsonObject();
        List<java.util.Set<Integer>> touched=new ArrayList<>();
        for(Anchor anchor:anchors) {
            java.util.Set<Integer> ids=new java.util.TreeSet<>();int best=Integer.MAX_VALUE;
            for(var entry:known.entrySet()) {
                if(entry.getValue()!=2) continue;
                int dx=(int)(entry.getKey()>>32)-anchor.chunkX,dz=(int)(long)entry.getKey()-anchor.chunkZ;
                int distance=(int)Math.round(Math.sqrt((double)dx*dx+(double)dz*dz));
                best=Math.min(best,distance);
                if(distance<=anchor.radiusChunks+8) ids.add(component.get(entry.getKey()));
            }
            touched.add(ids);
            JsonArray array=new JsonArray();ids.forEach(array::add);contacts.add(anchor.id,array);
            nearest.addProperty(anchor.id,best==Integer.MAX_VALUE?-1:best);
        }
        result.add("components_within_city_radius_plus_8_chunks",contacts);
        result.add("nearest_highway_distance_chunks",nearest);
        JsonArray connected=new JsonArray();
        for(int a=0;a<anchors.size();a++) for(int b=a+1;b<anchors.size();b++) {
            boolean shared=touched.get(a).stream().anyMatch(touched.get(b)::contains);
            JsonObject pair=new JsonObject();pair.addProperty("from",anchors.get(a).id);pair.addProperty("to",anchors.get(b).id);
            pair.addProperty("connected_by_one_highway_component",shared);connected.add(pair);
        }
        result.add("pairs",connected);
        System.out.println("[UC-WORLD] Native map evidence: "+result);
        return result;
    }
}
