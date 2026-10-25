package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The two-player performance session (seventh pass, alpha criterion 9; {@code scripts/run_perf_session.py}): a dedicated server on a NEW furnished archipelago world and two real clients that
 * walk the streets on their own ({@link PerfSessionClient}). Phase 1 runs three segments of a third of {@code uc.perf.minutes} each (default 30): both players in Ironhaven by day, one in
 * Ironhaven and one in Valewood by day, both in Blackwater at night; each starts with a teleport to a street near the city's centre (logged; the arrival ticks are kept apart), then a timed
 * save, then a normal stop. Phase 2 starts the same world again, waits for both players to rejoin, checks that they are still the same citizens and measures two minutes more.
 *
 * <p>Every server tick's length is kept per segment (mean, p50, p95, p99, worst, ticks over 50, 100 and 250 ms), and every thirty seconds the players, the townspeople, all entities, the
 * loaded chunks and the heap in use are sampled. {@link TickSampler} runs too and logs where any tick over 100 ms spent its time. It measures; it changes nothing in the game. The clients'
 * software rendering in a virtual display shares the machine's cores with this server, so the numbers are an upper bound for a server on its own machine, not a player's experience.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class PerfSessionServer {
    static final boolean ENABLED = "perf".equals(System.getProperty("uc.journey.scenario", ""));
    static final int PHASE = Integer.getInteger("uc.journey.phase", 1);
    static final double MINUTES = Double.parseDouble(System.getProperty("uc.perf.minutes", "30"));
    static final String FARMER = "UC_Farmer", SMITH = "UC_Smith";
    private static final int ARRIVAL_TICKS = 200, SAMPLE_EVERY = 600, AFTER_RESTART_TICKS = 2400;

    private record Segment(String name, String farmerCity, String smithCity, long dayTime) {}
    private static final List<Segment> PLAN = List.of(
        new Segment("ironhaven_together_day", "ironhaven", "ironhaven", 6000),
        new Segment("two_cities_day", "ironhaven", "valewood", 8000),
        new Segment("blackwater_together_night", "blackwater", "blackwater", 18000));

    private static final Map<String, List<Long>> TICKS = new LinkedHashMap<>();
    private static final Map<String, JsonArray> SAMPLES = new LinkedHashMap<>();
    private static final JsonObject REPORT = new JsonObject();
    private static String current = "waiting";
    private static int index = -1, ticksInSegment, sinceSample;
    private static long tickStarted, segmentTicks;
    private static boolean finished;

    private PerfSessionServer() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tickStart(TickEvent.ServerTickEvent event) {
        if (ENABLED && event.phase == TickEvent.Phase.START) tickStarted = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tickEnd(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || finished || tickStarted == 0) return;
        long spent = System.nanoTime() - tickStarted;
        MinecraftServer server = event.getServer();
        ServerPlayer farmer = server.getPlayerList().getPlayerByName(FARMER), smith = server.getPlayerList().getPlayerByName(SMITH);
        if (index >= 0) TICKS.computeIfAbsent(current, k -> new ArrayList<>()).add(spent);
        try {
            if (index < 0) {
                if (farmer == null || smith == null) return;
                begin(server, farmer, smith);
                return;
            }
            ticksInSegment++;
            if (++sinceSample >= SAMPLE_EVERY) { sinceSample = 0; sample(server); }
            if (PHASE == 1) {
                if (ticksInSegment == ARRIVAL_TICKS) enter(PLAN.get(index).name());
                if (ticksInSegment >= segmentTicks) next(server, farmer, smith);
            } else {
                if (ticksInSegment == ARRIVAL_TICKS) enter("after_restart");
                if (ticksInSegment >= AFTER_RESTART_TICKS) finish(server);
            }
        } catch (RuntimeException | IOException error) {
            System.out.println("[UC-PERF] FAILED: " + error);
            REPORT.addProperty("status", "failed");
            REPORT.addProperty("error", String.valueOf(error));
            try { write(server); } catch (IOException ignored) { }
            finished = true;
            server.halt(false);
        }
    }

    private static void begin(MinecraftServer server, ServerPlayer farmer, ServerPlayer smith) {
        ServerLevel level = server.overworld();
        index = 0;
        REPORT.addProperty("scenario", "perf");
        REPORT.addProperty("phase", PHASE);
        REPORT.addProperty("minutes_planned", PHASE == 1 ? MINUTES : AFTER_RESTART_TICKS / 1200.0);
        REPORT.addProperty("connected_clients", 2);
        REPORT.addProperty("world_seed", level.getSeed());
        if (PHASE == 1) {
            for (ServerPlayer player : List.of(farmer, smith)) {
                boolean isFarmer = player == farmer;
                if (!CitizenState.get(server).register(player.getUUID(), isFarmer ? "valewood" : "ironhaven", isFarmer ? "farmer" : "smith")) throw new IllegalStateException("phase 1 needs fresh identities");
                player.setInvulnerable(true);
            }
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            System.out.println("[UC-PERF] SCRIPTED: both citizens registered by the server (the registration screen is not what is measured) and made invulnerable; the daylight cycle stopped");
            start(server, farmer, smith);
        } else {
            boolean kept = CitizenState.get(server).find(farmer.getUUID()).isPresent() && CitizenState.get(server).find(smith.getUUID()).isPresent();
            REPORT.addProperty("citizens_kept_after_restart", kept);
            if (!kept) throw new IllegalStateException("a citizen was lost across the stop and start");
            current = "after_restart_arrival";
            ticksInSegment = 0;
            System.out.println("[UC-PERF] phase 2: both players rejoined the restarted server as the same citizens");
        }
    }

    /** Starts segment {@link #index}: the clock, a teleport for each player to a street near their city's centre, the arrival ticks kept apart. */
    private static void start(MinecraftServer server, ServerPlayer farmer, ServerPlayer smith) {
        Segment s = PLAN.get(index);
        ServerLevel level = server.overworld();
        level.setDayTime(s.dayTime());
        BlockPos a = street(level, s.farmerCity()), b = street(level, s.smithCity());
        if (a == null || b == null) throw new IllegalStateException("no street found near the centre of " + s.farmerCity() + " or " + s.smithCity());
        farmer.teleportTo(level, a.getX() + 0.5, a.getY(), a.getZ() + 0.5, 0, 0);
        BlockPos bb = s.smithCity().equals(s.farmerCity()) ? b.offset(3, 0, 0) : b;
        smith.teleportTo(level, bb.getX() + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bb.getX(), bb.getZ()), bb.getZ() + 0.5, 180, 0);
        segmentTicks = Math.round(MINUTES * 1200 / PLAN.size());
        ticksInSegment = 0;
        current = s.name() + "_arrival";
        enter(current);
        System.out.println("[UC-PERF] SCRIPTED: segment " + s.name() + " begins: " + FARMER + " teleported to " + a.toShortString() + " (" + s.farmerCity() + "), " + SMITH + " to " + bb.toShortString()
            + " (" + s.smithCity() + "), the time set to " + s.dayTime() + "; " + segmentTicks / 1200.0 + " minutes");
        trackPlaces(level, farmer, smith);
    }

    /**
     * Each player tracks a different building near where they stand (PREPARED: put in their Places book and tracked by the server, as Track on the Places page does), so each client's Xaero
     * minimap shows that player's waypoint and not the other's (eighth pass: Xaero with two clients; the clients write what they track and Core's waypoint count every minute).
     */
    private static void trackPlaces(ServerLevel level, ServerPlayer farmer, ServerPlayer smith) {
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (ServerPlayer p : List.of(farmer, smith)) {
            CityBuildings.Building found = null;
            int cx = p.chunkPosition().x, cz = p.chunkPosition().z;
            for (int r = 1; r <= 4 && found == null; r++) for (int dx = -r; dx <= r && found == null; dx++) for (int dz = -r; dz <= r && found == null; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r || level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) continue;
                CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).orElse(null);
                if (b == null || taken.contains(b.key()) || b.security() == null) continue;
                var door = TechService.streetDoor(level, b);
                if (door.isEmpty()) continue;
                PropertyIntel.book(level.getServer()).seen(p.getUUID(), b, door.get().lower(), CityHomes.address(level.getSeed(), b), CityBuildings.INSTANCE.category(b.security().id()),
                    b.security().label(), LockService.clock(level.getServer()));
                var d = Navigation.build(p, "place", Long.toString(b.key()));
                if (d.isPresent()) { Navigation.track(p, d.get()); found = b; taken.add(b.key()); System.out.println("[UC-PERF] PREPARED: " + p.getGameProfile().getName() + " tracks " + d.get().title() + " at " + d.get().pos().toShortString()); }
            }
            if (found == null) System.out.println("[UC-PERF] no building with a street door near " + p.getGameProfile().getName() + " to track");
        }
    }

    private static void enter(String name) {
        current = name;
        TickSampler.segment(name);
        System.out.println("[UC-PERF] segment " + name);
    }

    private static void next(MinecraftServer server, ServerPlayer farmer, ServerPlayer smith) throws IOException {
        REPORT.add(PLAN.get(index).name() + "_status", status(server));
        index++;
        if (index < PLAN.size()) { start(server, farmer, smith); return; }
        current = "save";
        long before = System.nanoTime();
        server.saveEverything(true, true, true);
        double saveMs = (System.nanoTime() - before) / 1e6;
        REPORT.addProperty("save_all_ms", Math.round(saveMs * 10) / 10.0);
        System.out.println(String.format(Locale.ROOT, "[UC-PERF] a full save took %.1f ms", saveMs));
        finish(server);
    }

    private static void finish(MinecraftServer server) throws IOException {
        sample(server);
        if (PHASE == 2) REPORT.add("after_restart_status", status(server));
        REPORT.addProperty("status", "passed");
        write(server);
        finished = true;
        System.out.println("[UC-PERF] phase " + PHASE + " done; stopping the server normally");
        server.halt(false);
    }

    private static JsonObject status(MinecraftServer server) {
        JsonObject o = new JsonObject();
        o.addProperty("population", PopulationService.status(server));
        o.addProperty("households", HouseholdService.status());
        return o;
    }

    private static void sample(MinecraftServer server) {
        ServerLevel level = server.overworld();
        int people = 0, all = 0;
        for (var entity : level.getAllEntities()) { all++; if (entity instanceof TownspersonEntity) people++; }
        Runtime rt = Runtime.getRuntime();
        JsonObject o = new JsonObject();
        o.addProperty("tick", server.getTickCount());
        o.addProperty("players", server.getPlayerCount());
        o.addProperty("townspeople", people);
        o.addProperty("entities", all);
        o.addProperty("loaded_chunks", level.getChunkSource().getLoadedChunksCount());
        o.addProperty("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        o.addProperty("heap_max_mb", rt.maxMemory() / (1024 * 1024));
        SAMPLES.computeIfAbsent(current, k -> new JsonArray()).add(o);
        System.out.println("[UC-PERF] sample " + current + ": " + o);
    }

    private static void write(MinecraftServer server) throws IOException {
        JsonObject segments = new JsonObject();
        for (var entry : TICKS.entrySet()) segments.add(entry.getKey(), summary(entry.getValue()));
        REPORT.add("segments", segments);
        JsonObject samples = new JsonObject();
        SAMPLES.forEach(samples::add);
        REPORT.add("samples", samples);
        Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getParent();
        Files.writeString(folder.resolve("perf-session-phase" + PHASE + ".json"), REPORT + "\n");
        for (var entry : TICKS.entrySet()) System.out.println("[UC-PERF] " + entry.getKey() + ": " + summary(entry.getValue()));
    }

    static JsonObject summary(List<Long> list) {
        long[] t = list.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(t);
        JsonObject o = new JsonObject();
        o.addProperty("ticks", t.length);
        if (t.length == 0) return o;
        double mean = Arrays.stream(t).average().orElse(0) / 1e6;
        o.addProperty("mean_ms", round(mean));
        o.addProperty("p50_ms", round(pct(t, 50)));
        o.addProperty("p95_ms", round(pct(t, 95)));
        o.addProperty("p99_ms", round(pct(t, 99)));
        o.addProperty("worst_ms", round(t[t.length - 1] / 1e6));
        o.addProperty("over_50_ms", Arrays.stream(t).filter(v -> v > 50_000_000L).count());
        o.addProperty("over_100_ms", Arrays.stream(t).filter(v -> v > 100_000_000L).count());
        o.addProperty("over_250_ms", Arrays.stream(t).filter(v -> v > 250_000_000L).count());
        o.addProperty("tps_if_never_waiting", round(Math.min(20, 1000 / Math.max(mean, 1e-3))));
        return o;
    }

    private static double pct(long[] sorted, int p) { return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.ceil(p / 100.0 * sorted.length) - 1))] / 1e6; }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }

    /** A street cell near a city's centre: the nearest chunk of the city that is not a building, at its walkable height. Generates the chunks it looks at. */
    static BlockPos street(ServerLevel level, String city) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst().orElse(null);
        if (region == null) return null;
        for (int r = 0; r <= 6; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int cx = region.centerChunkX() + dx, cz = region.centerChunkZ() + dz;
            var sample = LostCitiesLink.chunk(level, cx, cz).orElse(null);
            if (sample == null || !sample.city() || sample.building() != null && !sample.building().isEmpty()) continue;
            level.getChunk(cx, cz);
            int x = cx * 16 + 8, z = cz * 16 + 8;
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        }
        return null;
    }
}
