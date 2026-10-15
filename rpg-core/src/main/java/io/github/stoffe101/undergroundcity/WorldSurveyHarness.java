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
    private static JsonObject nativeMap;
    // Corridor terrain stage
    private static final int CORRIDOR_SAMPLE_SPACING=16,CORRIDOR_SAMPLES_PER_TICK=24;
    private record CorridorSample(int x,int z,int surfaceY,boolean water,int waterDepth,String biome,double along) {}
    private static final List<JsonObject> corridorRoutes=new ArrayList<>();
    private static List<JsonObject> pendingRoutes;
    private static JsonObject activeRoute;
    private static List<int[]> activeSamples;
    private static List<CorridorSample> collected;
    private static double[] waypointArcLength;
    private static int sampleIndex;
    private static boolean corridorsLoaded;
    private static WorldRoadVerifier roads;
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
                if(!report.has("native_map") && nativeMap==null) nativeMap=analyse(System.nanoTime()-scanStarted);
                report.add("native_map",nativeMap);
            } else {
                report.addProperty("native_map_error","Lost Cities IMC API was not available");
            }
            if(corridorStep(server,folder)) return;
            report.add("corridor_terrain",corridorReport());
            if(Files.isRegularFile(folder.resolve("corridors.json"))) {
                if(roads==null) roads=new WorldRoadVerifier();
                if(roads.step(server)) return;
                report.add("roads",roads.report());
            }
            report.addProperty("status","three_city_structures_observed");
        } catch(Exception error) {
            report.addProperty("status","failed");report.addProperty("error",error.toString());
            com.mojang.logging.LogUtils.getLogger().error("Native city survey failed",error);
        }
        report.add("observations",observations);
        report.addProperty("qshop_credits_ready",QShopEconomy.available() && QShopEconomy.configured(server));
        // Population and furnished interiors are never inferred from a block survey; roads are measured above.
        report.addProperty("population_verified",false);
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
    private static final java.util.Map<String,java.util.Map<String,Integer>> buildingTypes=new java.util.TreeMap<>();
    private static final int DENSE_RADIUS=12;
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
        // 3) every chunk in a 25x25 window around each city centre, with a building-type histogram.
        for(int index=0;index<anchors.size();index++) {
            Anchor anchor=anchors.get(index);
            for(int dz=-DENSE_RADIUS;dz<=DENSE_RADIUS;dz++) for(int dx=-DENSE_RADIUS;dx<=DENSE_RADIUS;dx++) {
                samples.add(new int[]{anchor.chunkX+dx,anchor.chunkZ+dz,index+1});
            }
        }
        // 4) a coarse lattice over the whole survey box for any stray highway or city.
        for(int z=minZ;z<=maxZ;z+=LATTICE_STRIDE) for(int x=minX;x<=maxX;x+=LATTICE_STRIDE) samples.add(new int[]{x,z});
        // Dense windows first so each chunk is attributed to its city's building histogram before any other sample caches it.
        List<int[]> ordered=new ArrayList<>(samples);
        ordered.sort(java.util.Comparator.comparingInt(sample->sample.length>2?0:1));
        samples.clear();samples.addAll(ordered);
        scanRow=0;scanStarted=System.nanoTime();
        System.out.println("[UC-WORLD] Planning "+samples.size()+" sampled Lost Cities chunk queries (ring + corridor + dense city windows + lattice) then highway flood-fill");
    }

    private static int query(net.minecraft.server.level.ServerLevel level,int x,int z) { return query(level,x,z,0); }

    private static int query(net.minecraft.server.level.ServerLevel level,int x,int z,int denseAnchor) {
        Integer cached=known.get(key(x,z));
        if(cached!=null) return cached;
        var sample=LostCitiesLink.chunk(level,x,z).orElseThrow();
        int code=sample.highwayLevel()>=0?2:(sample.city()?1:0);
        known.put(key(x,z),code);
        if(denseAnchor>0 && sample.city()) {
            String type=sample.building()==null?"(street or open city chunk)":sample.building();
            buildingTypes.computeIfAbsent(anchors.get(denseAnchor-1).id,k->new java.util.TreeMap<>()).merge(type,1,Integer::sum);
        }
        return code;
    }

    /** Returns true while work remains. Bounded queries per server tick keep the watchdog quiet. */
    private static boolean scanStep(net.minecraft.server.level.ServerLevel level) {
        for(int n=0;n<QUERIES_PER_TICK;n++) {
            int[] at=samples.poll();
            if(at!=null) {
                sampledCount++;
                if(query(level,at[0],at[1],at.length>2?at[2]:0)==2) frontier.add(at);
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
        JsonObject composition=new JsonObject();
        buildingTypes.forEach((city,counts)->{
            JsonObject entry=new JsonObject();int total=counts.values().stream().mapToInt(Integer::intValue).sum();
            entry.addProperty("city_chunks_in_25x25_window",total);entry.addProperty("distinct_building_types",counts.size());
            JsonObject top=new JsonObject();
            counts.entrySet().stream().sorted((a,b)->b.getValue()-a.getValue()).limit(14).forEach(e->top.addProperty(e.getKey(),e.getValue()));
            entry.add("most_common",top);composition.add(city,entry);
        });
        result.add("building_composition",composition);
        System.out.println("[UC-WORLD] Native map evidence: "+result);
        return result;
    }

    // ----------------------------------------------------------------- corridor terrain stage
    /** Samples real generated terrain along each planned route. Returns true while work remains. */
    private static boolean corridorStep(net.minecraft.server.MinecraftServer server,java.nio.file.Path folder) throws java.io.IOException {
        var file=folder.resolve("corridors.json");
        if(!Files.isRegularFile(file)) return false; // optional input staged by the workflow
        if(!corridorsLoaded) {
            corridorsLoaded=true;
            var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            pendingRoutes=new ArrayList<>();
            for(var route:root.getAsJsonArray("routes")) pendingRoutes.add(route.getAsJsonObject());
        }
        var level=server.overworld();
        if(activeRoute==null) {
            if(pendingRoutes.isEmpty()) return false;
            activeRoute=pendingRoutes.remove(0);beginRoute();
        }
        for(int n=0;n<CORRIDOR_SAMPLES_PER_TICK && sampleIndex<activeSamples.size();n++,sampleIndex++) {
            int[] at=activeSamples.get(sampleIndex);
            collected.add(sampleTerrain(level,at[0],at[1],at[2]));
        }
        if(sampleIndex<activeSamples.size()) return true;
        corridorRoutes.add(evaluateRoute());
        System.out.println("[UC-WORLD] Corridor terrain evidence: "+corridorRoutes.get(corridorRoutes.size()-1));
        activeRoute=null;
        return !pendingRoutes.isEmpty();
    }

    private static void beginRoute() {
        var waypoints=activeRoute.getAsJsonArray("waypoints");
        List<double[]> points=new ArrayList<>();
        for(var point:waypoints) points.add(new double[]{point.getAsJsonArray().get(0).getAsDouble(),point.getAsJsonArray().get(1).getAsDouble()});
        waypointArcLength=new double[points.size()];
        activeSamples=new ArrayList<>();collected=new ArrayList<>();sampleIndex=0;
        double along=0,carry=0;
        activeSamples.add(new int[]{(int)points.get(0)[0],(int)points.get(0)[1],0});
        for(int i=1;i<points.size();i++) {
            double[] a=points.get(i-1),b=points.get(i);
            double length=Math.hypot(b[0]-a[0],b[1]-a[1]);
            double position=CORRIDOR_SAMPLE_SPACING-carry;
            while(position<=length) {
                double t=position/length;
                activeSamples.add(new int[]{(int)Math.round(a[0]+(b[0]-a[0])*t),(int)Math.round(a[1]+(b[1]-a[1])*t),(int)Math.round(along+position)});
                position+=CORRIDOR_SAMPLE_SPACING;
            }
            carry=(carry+length)%CORRIDOR_SAMPLE_SPACING;
            along+=length;waypointArcLength[i]=along;
        }
    }

    /**
     * Natural terrain baseline from the chunk generator's own noise (no chunk generation, never blocks the server
     * thread): ocean-floor height versus surface height gives water and depth; within cities Lost Cities builds on top.
     */
    private static CorridorSample sampleTerrain(net.minecraft.server.level.ServerLevel level,int x,int z,int along) {
        var cache=level.getChunkSource();
        var generator=cache.getGenerator();var random=cache.randomState();
        int surface=generator.getBaseHeight(x,z,net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,level,random);
        int floor=generator.getBaseHeight(x,z,net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,random);
        boolean water=surface>floor;
        String biome=level.getBiome(new BlockPos(x,Math.max(surface-1,level.getMinBuildHeight()),z)).unwrapKey()
            .map(key->key.location().getPath()).orElse("unknown");
        return new CorridorSample(x,z,surface,water,water?surface-floor:0,biome,along);
    }

    private static JsonObject evaluateRoute() {
        JsonObject out=new JsonObject();
        out.addProperty("id",activeRoute.get("id").getAsString());out.addProperty("kind",activeRoute.get("kind").getAsString());
        out.addProperty("samples",collected.size());
        out.addProperty("length_blocks",Math.round(waypointArcLength[waypointArcLength.length-1]));
        int water=0,steep=0,maxStep=0,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE,longestWater=0,run=0,deepest=0;
        java.util.Map<String,Integer> biomes=new java.util.TreeMap<>();
        for(int i=0;i<collected.size();i++) {
            var sample=collected.get(i);
            biomes.merge(sample.biome,1,Integer::sum);
            minY=Math.min(minY,sample.surfaceY);maxY=Math.max(maxY,sample.surfaceY);
            if(sample.water) {water++;run++;longestWater=Math.max(longestWater,run);deepest=Math.max(deepest,sample.waterDepth);} else run=0;
            if(i>0) {int step=Math.abs(sample.surfaceY-collected.get(i-1).surfaceY);maxStep=Math.max(maxStep,step);if(step>4) steep++;}
        }
        out.addProperty("water_samples",water);out.addProperty("longest_water_run_blocks",(long)longestWater*CORRIDOR_SAMPLE_SPACING);
        out.addProperty("deepest_water_blocks",deepest);out.addProperty("steep_steps_over_4_blocks",steep);
        out.addProperty("max_surface_step_blocks",maxStep);out.addProperty("surface_y_min",minY);out.addProperty("surface_y_max",maxY);
        JsonObject biomeCounts=new JsonObject();biomes.forEach(biomeCounts::addProperty);out.add("biome_samples",biomeCounts);
        JsonArray crossings=new JsonArray();
        if(activeRoute.has("crossings")) for(var element:activeRoute.getAsJsonArray("crossings")) {
            var crossing=element.getAsJsonObject();var range=crossing.getAsJsonArray("between_waypoints");
            double from=waypointArcLength[range.get(0).getAsInt()],to=waypointArcLength[range.get(1).getAsInt()];
            int crossWater=0,crossRun=0,crossLongest=0,crossStep=0,crossCount=0;int previous=Integer.MIN_VALUE;
            for(var sample:collected) {
                if(sample.along<from || sample.along>to) continue;
                crossCount++;
                if(sample.water) {crossWater++;crossRun++;crossLongest=Math.max(crossLongest,crossRun);} else crossRun=0;
                if(previous!=Integer.MIN_VALUE) crossStep=Math.max(crossStep,Math.abs(sample.surfaceY-previous));
                previous=sample.surfaceY;
            }
            JsonObject entry=new JsonObject();entry.addProperty("id",crossing.get("id").getAsString());
            entry.addProperty("samples",crossCount);entry.addProperty("water_samples",crossWater);
            entry.addProperty("longest_water_run_blocks",(long)crossLongest*CORRIDOR_SAMPLE_SPACING);
            entry.addProperty("max_surface_step_blocks",crossStep);
            entry.addProperty("water_span_needs_bridge",crossLongest>=3);
            entry.addProperty("ravine_or_cliff_over_10_blocks",crossStep>=10);
            crossings.add(entry);
        }
        out.add("crossings",crossings);
        return out;
    }

    private static JsonObject corridorReport() {
        JsonObject result=new JsonObject();
        result.addProperty("method","chunk-generator noise (ocean floor vs surface height) sampled every "+CORRIDOR_SAMPLE_SPACING+" blocks along each blueprint route plus biome; natural terrain baseline before any city or road is built");
        result.addProperty("terrain_verified_for_construction",false);
        JsonArray array=new JsonArray();corridorRoutes.forEach(array::add);result.add("routes",array);
        return result;
    }
}
