package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
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
 * Diagnostic seed ranking. For each candidate seed it builds a fresh noise RandomState and asks the live chunk generator
 * for ocean-floor versus surface height at the three city anchors and along the blueprint corridors, without generating
 * chunks. It never changes a world. Enable with -Duc.seedScan=start,count in the disposable survey directory.
 */
@Mod.EventBusSubscriber(modid=UndergroundCity.MOD_ID)
public final class WorldSeedScanHarness {
    private static final String SPEC=System.getProperty("uc.seedScan");
    private static final int SPACING=16;
    /** Vanilla climate: continentalness below -0.19 is ocean; above -0.11 is inland. Values are quantised x10000. */
    private static final long OCEAN=-1900,INLAND=-1100;
    private record Anchor(String id,double x,double z,int radius) {}
    private record Result(long seed,boolean anchorsDry,int waterSamples,int longestRunBlocks,int deepest,List<String> wetAnchors,
                          List<int[]> routeStats,double score) {}
    private static List<Anchor> anchors;
    private static List<List<double[]>> routes;
    private static List<String> routeIds;
    /** Per route: {fromArc,toArc} blocks along the polyline for each proposed bridge/viaduct segment. */
    private static List<List<double[]>> crossingRanges;
    /** A bridge or boat crossing should be a real but bridgeable gap: neither absent nor a sea. */
    private static final int GAP_MIN=96,GAP_MAX=320;
    private static final List<Result> results=new ArrayList<>();
    private static long next,end;
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
                next=Long.parseLong(parts[0].trim());end=next+Math.min(Long.parseLong(parts[1].trim()),20000);
                anchors=new ArrayList<>();
                for(String id:List.of("ironhaven","valewood","blackwater")) {
                    try(var reader=server.getResourceManager().getResource(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID,
                        "lostcities/predefinedcities/"+id+".json")).orElseThrow().openAsReader()) {
                        var json=JsonParser.parseReader(reader).getAsJsonObject();
                        anchors.add(new Anchor(id,json.get("chunkx").getAsInt()*16+8,json.get("chunkz").getAsInt()*16+8,json.get("radius").getAsInt()));
                    }
                }
                routes=new ArrayList<>();routeIds=new ArrayList<>();crossingRanges=new ArrayList<>();
                var root=JsonParser.parseString(Files.readString(folder.resolve("corridors.json"))).getAsJsonObject();
                for(var route:root.getAsJsonArray("routes")) {
                    List<double[]> points=new ArrayList<>();
                    for(var point:route.getAsJsonObject().getAsJsonArray("waypoints")) {
                        points.add(new double[]{point.getAsJsonArray().get(0).getAsDouble(),point.getAsJsonArray().get(1).getAsDouble()});
                    }
                    routes.add(points);routeIds.add(route.getAsJsonObject().get("id").getAsString());
                    double[] arc=new double[points.size()];
                    for(int i=1;i<points.size();i++) arc[i]=arc[i-1]+Math.hypot(points.get(i)[0]-points.get(i-1)[0],points.get(i)[1]-points.get(i-1)[1]);
                    List<double[]> ranges=new ArrayList<>();
                    if(route.getAsJsonObject().has("crossings")) for(var crossing:route.getAsJsonObject().getAsJsonArray("crossings")) {
                        var between=crossing.getAsJsonObject().getAsJsonArray("between_waypoints");
                        ranges.add(new double[]{arc[between.get(0).getAsInt()],arc[between.get(1).getAsInt()]});
                    }
                    crossingRanges.add(ranges);
                }
            }
            long deadline=System.nanoTime()+30_000_000L;
            var level=server.overworld();var generator=level.getChunkSource().getGenerator();
            if(!(generator instanceof NoiseBasedChunkGenerator noise)) throw new IllegalStateException("Seed scan needs a noise-based generator, found "+generator.getClass());
            var settings=noise.generatorSettings().value();
            var noiseGetter=server.registryAccess().registryOrThrow(Registries.NOISE).asLookup();
            while(next<end && System.nanoTime()<deadline) {
                results.add(evaluate(level,noise,RandomState.create(settings,noiseGetter,next),next));
                next++;
            }
            if(next<end) return;
            results.sort(Comparator.comparingDouble(Result::score));
            JsonObject report=new JsonObject();
            report.addProperty("method","fresh RandomState per seed; climate sampler continentalness (ocean below -0.19, inland above -0.11) at anchors (centre plus four points at half radius) and every "+SPACING+" blocks along the corridors; a cheap ranking only, verify winners with the height survey");
            report.addProperty("seeds_evaluated",results.size());
            report.addProperty("design_target","dry anchors; one bridgeable water gap of "+GAP_MIN+"-"+GAP_MAX+" blocks inside each proposed bridge segment; land everywhere else");
            JsonArray top=new JsonArray();
            for(int i=0;i<Math.min(25,results.size());i++) {
                Result r=results.get(i);JsonObject entry=new JsonObject();
                entry.addProperty("seed",r.seed);entry.addProperty("anchors_dry",r.anchorsDry);entry.addProperty("wet_anchors",String.join(",",r.wetAnchors));
                entry.addProperty("corridor_water_samples",r.waterSamples);entry.addProperty("longest_water_run_blocks",r.longestRunBlocks);
                entry.addProperty("ocean_depth_index",r.deepest);entry.addProperty("score",r.score);
                JsonArray per=new JsonArray();
                for(int route=0;route<r.routeStats.size();route++) {
                    JsonObject stats=new JsonObject();int[] v=r.routeStats.get(route);
                    stats.addProperty("route",routeIds.get(route));stats.addProperty("water_samples",v[0]);stats.addProperty("longest_run_blocks",v[1]);stats.addProperty("samples",v[2]);stats.addProperty("bridge_segment_gap_blocks",v[3]);per.add(stats);
                }
                entry.add("routes",per);top.add(entry);
            }
            report.add("best",top);
            JsonObject baseline=new JsonObject();
            results.stream().filter(r->r.seed==20261014L).findFirst().ifPresent(r->{baseline.addProperty("seed",r.seed);baseline.addProperty("anchors_dry",r.anchorsDry);
                baseline.addProperty("corridor_water_samples",r.waterSamples);baseline.addProperty("longest_water_run_blocks",r.longestRunBlocks);baseline.addProperty("score",r.score);});
            report.add("current_reference_seed",baseline);
            Files.writeString(folder.resolve("seed-scan.json"),report+"\n");
            System.out.println("[UC-SEEDS] Evaluated "+results.size()+" seeds; best="+(results.isEmpty()?"none":results.get(0).seed+" score "+results.get(0).score));
        } catch(Exception error) {
            com.mojang.logging.LogUtils.getLogger().error("Seed scan failed",error);
        }
        finished=true;server.halt(false);
    }

    private static long continentalness(RandomState random,double x,double z) {
        return random.sampler().sample((int)Math.floor(x)>>2,64>>2,(int)Math.floor(z)>>2).continentalness();
    }

    private static Result evaluate(net.minecraft.server.level.ServerLevel level,NoiseBasedChunkGenerator generator,RandomState random,long seed) {
        List<String> wet=new ArrayList<>();
        for(Anchor anchor:anchors) {
            int dryPoints=0;
            double[][] offsets={{0,0},{anchor.radius/2.0,0},{-anchor.radius/2.0,0},{0,anchor.radius/2.0},{0,-anchor.radius/2.0}};
            for(double[] o:offsets) if(continentalness(random,anchor.x+o[0],anchor.z+o[1])>INLAND) dryPoints++;
            if(dryPoints<4) wet.add(anchor.id);
        }
        int water=0,longest=0,deepest=0;List<int[]> perRoute=new ArrayList<>();
        double gapPenalty=0,strayWaterSamples=0;
        for(int routeIndex=0;routeIndex<routes.size();routeIndex++) {
            List<double[]> route=routes.get(routeIndex);List<double[]> ranges=crossingRanges.get(routeIndex);
            int routeWater=0,routeLongest=0,run=0,samples=0;
            int[] crossingRun=new int[ranges.size()],crossingLongest=new int[ranges.size()];
            double along=0;
            for(int i=1;i<route.size();i++) {
                double[] a=route.get(i-1),b=route.get(i);double length=Math.hypot(b[0]-a[0],b[1]-a[1]);
                for(double position=(i==1?0:SPACING);position<=length;position+=SPACING) {
                    double t=position/length;
                    long continents=continentalness(random,a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t);
                    boolean ocean=continents<OCEAN;
                    double arc=along+position;
                    int crossing=-1;
                    for(int c=0;c<ranges.size();c++) if(arc>=ranges.get(c)[0] && arc<=ranges.get(c)[1]) crossing=c;
                    samples++;
                    if(ocean) {
                        routeWater++;run++;routeLongest=Math.max(routeLongest,run);deepest=(int)Math.max(deepest,(OCEAN-continents)/100);
                        if(crossing>=0) {crossingRun[crossing]++;crossingLongest[crossing]=Math.max(crossingLongest[crossing],crossingRun[crossing]);} else strayWaterSamples++;
                    } else {
                        run=0;
                        if(crossing>=0) crossingRun[crossing]=0;
                    }
                }
                along+=length;
            }
            for(int c=0;c<ranges.size();c++) {
                int gap=crossingLongest[c]*SPACING;
                if(gap==0) gapPenalty+=4000;                       // no water: the bridge would not be pivotal
                else if(gap<GAP_MIN) gapPenalty+=(GAP_MIN-gap)*10;  // a puddle
                else if(gap>GAP_MAX) gapPenalty+=(gap-GAP_MAX)*10;  // a sea, not a bridge
            }
            water+=routeWater;longest=Math.max(longest,routeLongest*SPACING);
            perRoute.add(new int[]{routeWater,routeLongest*SPACING,samples,ranges.isEmpty()?-1:crossingLongest[0]*SPACING});
        }
        boolean anchorsDry=wet.isEmpty();
        // Lower is better: wet anchors dominate, then missing/oversized bridge gaps, then ocean outside the bridge segments.
        double score=(wet.size()*1_000_000.0)+gapPenalty+strayWaterSamples*40.0;
        return new Result(seed,anchorsDry,water,longest,deepest,wet,perRoute,score);
    }
}
