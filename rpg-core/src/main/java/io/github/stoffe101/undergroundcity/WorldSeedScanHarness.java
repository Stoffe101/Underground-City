package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Diagnostic seed ranking, never part of gameplay. Design target (D-046): dry city sites, and on the two built roads
 * a few real water gaps (large rivers or straits) that a bridge crosses and a boat can also use, with land elsewhere.
 * Stage 1 is a cheap climate-sampler ranking of many seeds; stage 2 re-scores the best candidates with the chunk
 * generator's own surface and ocean-floor heights along the real road centrelines. It never changes a world. Enable
 * with -Duc.seedScan=start,count[,finalists] in the disposable survey directory.
 */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID)
public final class WorldSeedScanHarness {
    private static final String SPEC=System.getProperty("uc.seedScan");
    private static final int SPACING=16;
    /** Vanilla climate: continentalness below -0.19 is ocean; above -0.11 is inland. Values are quantised x10000. */
    private static final long OCEAN=-1900,INLAND=-1100;
    /** A bridge span worth building and sailing under is neither a stream nor a sea. */
    private static final int GAP_MIN=48,GAP_MAX=320;
    private static final double WATER_MAX=0.35,WATER_MIN=0.06;
    private record Anchor(String id,double x,double z,int radius) {}
    private record Cheap(long seed,int wetAnchors,double score,double ocean,int longest) {}
    private record Accurate(long seed,int wetAnchors,double score,List<double[]> routeStats,List<List<Integer>> runs) {}
    private static List<Anchor> anchors;
    private static List<CorridorPlan> routes;
    /** Sample positions: five points per anchor, then the open centreline of each built road every 16 blocks. */
    private static List<double[]> positions;
    private static int[] routeStart,routeEnd;
    private static final List<Cheap> cheap=new ArrayList<>();
    private static final List<Accurate> accurate=new ArrayList<>();
    private static List<Long> finalists;
    private static int finalistIndex,sampleIndex;
    private static int[] surface,floor;
    private static RandomState current;
    private static long next,end;
    private static int finalistCount;
    private static boolean initialised,finished;
    private WorldSeedScanHarness() {}

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if(SPEC==null || finished || event.phase!=TickEvent.Phase.END) return;
        var server=event.getServer();
        var folder=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
        if(!folder.getFileName().toString().equals("run-world-survey")) throw new IllegalStateException("Seed scan requires the disposable survey directory");
        try {
            if(!initialised) {
                initialised=true;
                String[] parts=SPEC.split(",");
                next=Long.parseLong(parts[0].trim());end=next+Math.min(Long.parseLong(parts[1].trim()),50000);
                finalistCount=parts.length>2?Integer.parseInt(parts[2].trim()):300;
                load(server,folder);
            }
            long deadline=System.nanoTime()+30_000_000L;
            var level=server.overworld();var generator=level.getChunkSource().getGenerator();
            if(!(generator instanceof NoiseBasedChunkGenerator noise)) throw new IllegalStateException("Seed scan needs a noise-based generator, found "+generator.getClass());
            var settings=noise.generatorSettings().value();
            var noiseGetter=server.registryAccess().registryOrThrow(Registries.NOISE).asLookup();
            if(next<end) {
                while(next<end && System.nanoTime()<deadline) {
                    cheap.add(evaluateCheap(RandomState.create(settings,noiseGetter,next),next));
                    next++;
                }
                if(next<end) return;
                cheap.sort(Comparator.comparingDouble(Cheap::score));
                finalists=new ArrayList<>();
                for(int i=0;i<Math.min(finalistCount,cheap.size());i++) finalists.add(cheap.get(i).seed);
                // The previous reference seed and the first candidate are always re-scored for comparison.
                for(long reference:new long[]{20261014L,3130L}) if(!finalists.contains(reference)) finalists.add(reference);
                System.out.println("[UC-SEEDS] Stage 1 done: "+cheap.size()+" seeds ranked; re-scoring "+finalists.size()+" with real terrain heights");
                surface=new int[positions.size()];floor=new int[positions.size()];
            }
            while(finalistIndex<finalists.size() && System.nanoTime()<deadline) {
                long seed=finalists.get(finalistIndex);
                if(sampleIndex==0 || current==null) current=RandomState.create(settings,noiseGetter,seed);
                var random=current;
                while(sampleIndex<positions.size() && System.nanoTime()<deadline) {
                    double[] p=positions.get(sampleIndex);
                    int x=(int)Math.floor(p[0]),z=(int)Math.floor(p[1]);
                    surface[sampleIndex]=noise.getBaseHeight(x,z,Heightmap.Types.WORLD_SURFACE_WG,level,random);
                    floor[sampleIndex]=noise.getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,level,random);
                    sampleIndex++;
                }
                if(sampleIndex<positions.size()) return;
                accurate.add(evaluateAccurate(seed));
                finalistIndex++;sampleIndex=0;
                if(finalistIndex%25==0) System.out.println("[UC-SEEDS] Stage 2: "+finalistIndex+" of "+finalists.size());
            }
            if(finalistIndex<finalists.size()) return;
            report(folder);
        } catch(Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Seed scan failed",error);
        }
        finished=true;server.halt(false);
    }

    private static void load(net.minecraft.server.MinecraftServer server,java.nio.file.Path folder) throws java.io.IOException {
        anchors=new ArrayList<>();
        List<CorridorPlan.City> circles=new ArrayList<>();
        for(String id:List.of("ironhaven","valewood","blackwater")) {
            try(var reader=server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID,
                "lostcities/predefinedcities/"+id+".json")).orElseThrow().openAsReader()) {
                var json=JsonParser.parseReader(reader).getAsJsonObject();
                var anchor=new Anchor(id,json.get("chunkx").getAsInt()*16+8,json.get("chunkz").getAsInt()*16+8,json.get("radius").getAsInt());
                anchors.add(anchor);circles.add(new CorridorPlan.City(anchor.x,anchor.z,anchor.radius));
            }
        }
        var root=JsonParser.parseString(Files.readString(folder.resolve("corridors.json"))).getAsJsonObject();
        var kinds=new java.util.HashSet<String>();
        for(var kind:root.getAsJsonObject("road_generation").getAsJsonArray("build_route_kinds")) kinds.add(kind.getAsString());
        routes=new ArrayList<>();
        for(var element:root.getAsJsonArray("routes")) {
            var route=element.getAsJsonObject();
            if(!kinds.contains(route.get("kind").getAsString())) continue;
            var waypoints=route.getAsJsonArray("waypoints");
            double[] xs=new double[waypoints.size()],zs=new double[waypoints.size()];
            for(int i=0;i<xs.length;i++) {xs[i]=waypoints.get(i).getAsJsonArray().get(0).getAsDouble();zs[i]=waypoints.get(i).getAsJsonArray().get(1).getAsDouble();}
            routes.add(new CorridorPlan(route.get("id").getAsString(),xs,zs,7,circles));
        }
        positions=new ArrayList<>();
        for(Anchor anchor:anchors) {
            double[][] offsets={{0,0},{anchor.radius/2.0,0},{-anchor.radius/2.0,0},{0,anchor.radius/2.0},{0,-anchor.radius/2.0}};
            for(double[] o:offsets) positions.add(new double[]{anchor.x+o[0],anchor.z+o[1]});
        }
        routeStart=new int[routes.size()];routeEnd=new int[routes.size()];
        for(int r=0;r<routes.size();r++) {
            routeStart[r]=positions.size();
            for(double s=0;s<=routes.get(r).length();s+=SPACING) if(routes.get(r).openAt(s)) positions.add(routes.get(r).pointAt(s));
            routeEnd[r]=positions.size();
        }
    }

    private static long continentalness(RandomState random,double x,double z) {
        return random.sampler().sample((int)Math.floor(x)>>2,64>>2,(int)Math.floor(z)>>2).continentalness();
    }

    /** Stage 1: anchors on land, roads not mostly sea. Rivers are invisible here, which is why stage 2 exists. */
    private static Cheap evaluateCheap(RandomState random,long seed) {
        int wet=0;
        for(int a=0;a<anchors.size();a++) {
            int dry=0;
            for(int k=0;k<5;k++) if(continentalness(random,positions.get(a*5+k)[0],positions.get(a*5+k)[1])>INLAND) dry++;
            if(dry<4) wet++;
        }
        double score=wet*1_000_000.0,oceanTotal=0;int longestAll=0;
        for(int r=0;r<routes.size();r++) {
            int samples=0,ocean=0,run=0,longest=0;
            for(int i=routeStart[r];i<routeEnd[r];i++) {
                samples++;
                if(continentalness(random,positions.get(i)[0],positions.get(i)[1])<OCEAN) {ocean++;run++;longest=Math.max(longest,run);} else run=0;
            }
            double fraction=samples==0?0:(double)ocean/samples;
            if(fraction>WATER_MAX) score+=(fraction-WATER_MAX)*3000;
            if(longest*SPACING>GAP_MAX) score+=(longest*SPACING-GAP_MAX)*10;
            oceanTotal+=fraction;longestAll=Math.max(longestAll,longest*SPACING);
        }
        return new Cheap(seed,wet,score,oceanTotal/Math.max(1,routes.size()),longestAll);
    }

    /** Stage 2: real surface versus ocean-floor heights. Water is surface above floor. */
    private static Accurate evaluateAccurate(long seed) {
        int wet=0;
        for(int a=0;a<anchors.size();a++) {
            int water=0;
            for(int k=0;k<5;k++) if(surface[a*5+k]>floor[a*5+k]) water++;
            if(water>=2) wet++;
        }
        double score=wet*1_000_000.0;
        List<double[]> stats=new ArrayList<>();List<List<Integer>> allRuns=new ArrayList<>();
        for(int r=0;r<routes.size();r++) {
            int samples=0,waterSamples=0,run=0,steep=0,deepest=0;
            List<Integer> runs=new ArrayList<>();
            for(int i=routeStart[r];i<routeEnd[r];i++) {
                samples++;
                boolean water=surface[i]>floor[i];
                if(water) {waterSamples++;run++;deepest=Math.max(deepest,surface[i]-floor[i]);}
                if(!water || i+1==routeEnd[r]) {if(run>0) runs.add(run*SPACING);run=0;}
                if(i>routeStart[r] && Math.abs(surface[i]-surface[i-1])>=8) steep++;
            }
            double fraction=samples==0?0:(double)waterSamples/samples;
            if(fraction>WATER_MAX) score+=(fraction-WATER_MAX)*4000;
            if(fraction<WATER_MIN) score+=(WATER_MIN-fraction)*3000;
            int bridgeable=0;
            for(int length:runs) {
                if(length>GAP_MAX) score+=(length-GAP_MAX)*10;
                if(length>=GAP_MIN && length<=GAP_MAX) bridgeable++;
            }
            if(bridgeable==0) score+=400;
            if(runs.size()>5) score+=(runs.size()-5)*150;
            score+=steep*8;
            stats.add(new double[]{samples,waterSamples,fraction,steep,deepest,bridgeable});
            allRuns.add(runs);
        }
        return new Accurate(seed,wet,score,stats,allRuns);
    }

    private static void report(java.nio.file.Path folder) throws java.io.IOException {
        accurate.sort(Comparator.comparingDouble(Accurate::score));
        JsonObject report=new JsonObject();
        report.addProperty("method","stage 1: climate-sampler continentalness for "+cheap.size()+" seeds; stage 2: chunk-generator surface vs ocean-floor height every "+SPACING+" blocks on the open centreline of each built road, plus five points per city; ranking only, verify with the generation survey");
        report.addProperty("design_target","dry city sites; per built road "+Math.round(WATER_MIN*100)+"-"+Math.round(WATER_MAX*100)+"% water in bridgeable gaps of "+GAP_MIN+"-"+GAP_MAX+" blocks (large rivers or straits crossed by bridge or boat); land elsewhere");
        report.addProperty("seeds_ranked",cheap.size());report.addProperty("seeds_rescored",accurate.size());
        JsonArray top=new JsonArray();
        for(int i=0;i<Math.min(15,accurate.size());i++) top.add(describe(accurate.get(i)));
        report.add("best",top);
        JsonObject references=new JsonObject();
        for(Accurate result:accurate) if(result.seed==20261014L || result.seed==3130L) references.add(Long.toString(result.seed),describe(result));
        report.add("references",references);
        Files.writeString(folder.resolve("seed-scan.json"),report+"\n");
        System.out.println("[UC-SEEDS] Best: "+(accurate.isEmpty()?"none":accurate.get(0).seed+" score "+accurate.get(0).score));
    }

    private static JsonObject describe(Accurate result) {
        JsonObject entry=new JsonObject();
        entry.addProperty("seed",result.seed);entry.addProperty("wet_cities",result.wetAnchors);entry.addProperty("score",Math.round(result.score*10)/10.0);
        JsonArray per=new JsonArray();
        for(int r=0;r<routes.size();r++) {
            double[] v=result.routeStats.get(r);JsonObject stats=new JsonObject();
            stats.addProperty("route",routes.get(r).id());stats.addProperty("samples",(int)v[0]);
            stats.addProperty("water_fraction",Math.round(v[2]*1000)/1000.0);stats.addProperty("steep_steps",(int)v[3]);
            stats.addProperty("deepest_water",(int)v[4]);stats.addProperty("bridgeable_gaps",(int)v[5]);
            JsonArray runs=new JsonArray();result.runs.get(r).forEach(runs::add);stats.add("water_runs_blocks",runs);
            per.add(stats);
        }
        entry.add("routes",per);
        return entry;
    }
}
