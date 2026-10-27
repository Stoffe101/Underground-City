package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The islands generated ahead of play (owner request, ninth pass): every city's circle first, then the rest of the islands' land, then the roads and their bridges over the straits, each with a
 * margin round it, generated once and saved, so that walking or travelling there later reads the world from disk instead of waiting for Lost Cities to build it. It is meant for test and
 * preparation worlds; a world already played in can use it too, as it only generates chunks that do not exist yet (an existing chunk is loaded and let go again).
 *
 * <p>An operator starts it ({@code /uc admin pregen start [islands|all|<city>] [margin]}, permission level 2; "all" is the cities' circles only), or a development launch does
 * ({@code -PucPregen=islands}, which starts it once on a world that has not had it). A dozen chunks are asked for at a time with a ticket, a tile of the map at a time (see
 * {@link #TILE}); a finished chunk keeps its ticket for a few seconds, and nothing stays loaded once it is done. Progress is saved in the world ({@code underground_city_pregen}), so a
 * stop or a restart carries on where it was.
 */
public final class ChunkPregen {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final TicketType<ChunkPos> TICKET = TicketType.create("underground_city_pregen", Comparator.comparingLong(ChunkPos::toLong), 20 * 60);
    /** Chunks asked for at once: enough to keep Lost Cities' workers busy, few enough that the server keeps up. */
    static final int IN_FLIGHT = Math.max(1, Math.min(64, Integer.getInteger("uc.pregen.inflight", 12)));
    /** A chunk not complete after this many ticks is let go and counted as skipped (it is generated when somebody comes near). */
    static final int PATIENCE = 20 * 60;
    static final int MAX_MARGIN = 256;
    /**
     * A finished chunk keeps its ticket this long, and at most {@link #LINGER_MAX} of them are kept: the next row's chunks need it as a neighbour, and unloading it at once only to load it
     * again a moment later meets a race in the game's chunk storage (1.20.1's {@code IOWorker.loadAsync} hands back the very tag still waiting to be written, so the chunk read from it
     * shares that tag while the write is in progress). Pregen run 1 met it four times in about 10,000 chunks ("Failed to store chunk", ConcurrentModificationException, one chunk
     * unreadable afterwards and generated again). Few are kept: every ticket keeps the neighbours Lost Cities needs loaded round it, eleven chunks each way, and in run 4 384 of
     * them, scattered round the rings of the old order, held some 24,000 chunks in memory until the server ran out of it.
     */
    static final int LINGER_TICKS = 20 * 10, LINGER_MAX = 64;
    /**
     * The plan goes a tile of TILE by TILE chunks at a time, nearest the centre first within it, the tiles ring by ring round the centre and round each ring in turn: what is asked for at
     * once and what lingers lie together, and a chunk let go is not needed again until the next ring comes past. Pregen run 4 asked for chunks in order of distance alone, which
     * puts the next chunk anywhere round the ring.
     */
    static final int TILE = 8;
    /** Lost Cities' plans of the chunks done are let go every so many chunks ({@link LostCitiesLink#forgetPlans}). */
    static final int FORGET_EVERY = 500;

    /** The order of the plan: 2 is {@link #sweep}'s (1, saved without it, was by distance alone). A plan saved in another order starts again from the beginning. */
    static final int ORDER = 2;

    /** What is being generated, saved with the world. */
    static final class Store extends SavedData {
        static final String NAME = "underground_city_pregen";
        String scope = "";
        int order = ORDER, margin, next, total, done, skipped;
        boolean running, finished;
        static Store load(CompoundTag tag) {
            Store s = new Store();
            s.order = tag.contains("order") ? tag.getInt("order") : 1;
            s.scope = tag.getString("scope"); s.margin = tag.getInt("margin"); s.next = tag.getInt("next"); s.total = tag.getInt("total");
            s.done = tag.getInt("done"); s.skipped = tag.getInt("skipped"); s.running = tag.getBoolean("running"); s.finished = tag.getBoolean("finished");
            return s;
        }
        @Override public CompoundTag save(CompoundTag tag) {
            tag.putInt("order", order); tag.putString("scope", scope); tag.putInt("margin", margin); tag.putInt("next", next); tag.putInt("total", total);
            tag.putInt("done", done); tag.putInt("skipped", skipped); tag.putBoolean("running", running); tag.putBoolean("finished", finished);
            return tag;
        }
    }

    static Store store(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Store::load, Store::new, Store.NAME); }

    private static List<ChunkPos> plan = List.of();
    /** The roads' chunks are added to the end of the list once the roads are planned (in a new world, a little after it opens). */
    private static boolean roadsPending;
    private static final Map<Long, Integer> FLIGHT = new LinkedHashMap<>();
    /** Finished chunks still holding their ticket, oldest first: chunk key to the tick it finished. */
    private static final Map<Long, Integer> LINGER = new LinkedHashMap<>();
    private static long startedAt;
    private static int doneAtStart, lastLogged, waitedForRoads, lastForgot;

    /**
     * The chunks of {@code scope} with {@code margin} blocks round them: "islands" is every city's circle, then the rest of the islands' land (where the world has an island plan) and the
     * roads' bridges once they are planned; "all" is every city's circle; a city's id is that city's circle. Each city's chunks come nearest its centre first, a tile at a time
     * ({@link #sweep}); the rest of the land a tile at a time round the city it is nearest.
     */
    static List<ChunkPos> chunks(ServerLevel level, String scope, int margin) {
        List<ChunkPos> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (CityRegions.Region r : CityRegions.INSTANCE.all()) {
            if (!scope.equals("all") && !scope.equals("islands") && !scope.equals(r.id())) continue;
            int reach = (Math.max(r.radius(), r.cityRadius()) + margin + 15) / 16;
            List<ChunkPos> city = new ArrayList<>();
            for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                if (dx * dx + dz * dz <= reach * reach) city.add(new ChunkPos(r.centerChunkX() + dx, r.centerChunkZ() + dz));
            }
            for (ChunkPos c : sweep(city, r.centerChunkX(), r.centerChunkZ())) if (seen.add(c.toLong())) out.add(c);
        }
        if (scope.equals("islands")) {
            IslandField.of(level).ifPresent(plan -> {
                double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (IslandPlan.Shape shape : plan.shapes()) {
                    if (shape.carve()) continue;
                    for (List<Double> at : List.of(shape.center(), shape.from(), shape.to())) {
                        if (at.size() < 2) continue;
                        box[0] = Math.min(box[0], at.get(0) - shape.radius()); box[1] = Math.min(box[1], at.get(1) - shape.radius());
                        box[2] = Math.max(box[2], at.get(0) + shape.radius()); box[3] = Math.max(box[3], at.get(1) + shape.radius());
                    }
                }
                double pad = margin + plan.shore().amplitude() + 16;
                int x0 = (int) Math.floor((box[0] - pad) / 16), z0 = (int) Math.floor((box[1] - pad) / 16), x1 = (int) Math.ceil((box[2] + pad) / 16), z1 = (int) Math.ceil((box[3] + pad) / 16);
                List<ChunkPos> land = new ArrayList<>();
                for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) {
                    double bx = cx * 16, bz = cz * 16, best = -Double.MAX_VALUE;
                    for (double[] at : new double[][]{{8, 8}, {0, 0}, {15, 0}, {0, 15}, {15, 15}}) best = Math.max(best, plan.distanceToShore(bx + at[0], bz + at[1]));
                    if (best > -margin && !seen.contains(ChunkPos.asLong(cx, cz))) land.add(new ChunkPos(cx, cz));
                }
                List<CityRegions.Region> regions = CityRegions.INSTANCE.all();
                if (regions.isEmpty()) return;
                Map<Integer, List<ChunkPos>> byCity = new java.util.TreeMap<>();
                for (ChunkPos c : land) byCity.computeIfAbsent(nearestCity(regions, c), k -> new ArrayList<>()).add(c);
                byCity.forEach((i, near) -> {
                    for (ChunkPos c : sweep(near, regions.get(i).centerChunkX(), regions.get(i).centerChunkZ())) if (seen.add(c.toLong())) out.add(c);
                });
            });
        }
        return out;
    }

    /** The roads' chunks with {@code margin} blocks round them, not already in {@code have} (the bridges over the straits are not land). Empty until the roads are planned. */
    static List<ChunkPos> roadChunks(ServerLevel level, int margin, java.util.Set<Long> have) {
        List<ChunkPos> out = new ArrayList<>();
        int reach = Math.max(1, (margin + 15) / 16);
        for (CorridorPlan road : CorridorRoadFeature.ready(level)) {
            for (double s = 0; s <= road.length(); s += 8) {
                double[] at = road.pointAt(s);
                int cx = (int) Math.floor(at[0] / 16), cz = (int) Math.floor(at[1] / 16);
                for (int dx = -reach; dx <= reach; dx++) for (int dz = -reach; dz <= reach; dz++) {
                    long key = ChunkPos.asLong(cx + dx, cz + dz);
                    if (have.add(key)) out.add(new ChunkPos(cx + dx, cz + dz));
                }
            }
        }
        return out;
    }

    /** The index of the city whose centre is nearest {@code c}. */
    private static int nearestCity(List<CityRegions.Region> regions, ChunkPos c) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < regions.size(); i++) {
            double d = Math.hypot(c.x - regions.get(i).centerChunkX(), c.z - regions.get(i).centerChunkZ());
            if (d < bestDistance) { bestDistance = d; best = i; }
        }
        return best;
    }

    /**
     * {@code chunks} in the order they are generated round a centre: by tile ({@link #TILE} chunks square, the centre in the middle of tile 0,0), the tiles by square ring and round
     * the ring from the east, and within a tile nearest the centre first. One tile follows its neighbour, so the chunks in flight and lingering are a tile or two together.
     */
    static List<ChunkPos> sweep(List<ChunkPos> chunks, int centreX, int centreZ) {
        List<ChunkPos> out = new ArrayList<>(chunks);
        int half = TILE / 2;
        out.sort(Comparator.<ChunkPos>comparingInt(c -> Math.max(Math.abs(Math.floorDiv(c.x - centreX + half, TILE)), Math.abs(Math.floorDiv(c.z - centreZ + half, TILE))))
            .thenComparingDouble(c -> {
                double a = Math.atan2(Math.floorDiv(c.z - centreZ + half, TILE), Math.floorDiv(c.x - centreX + half, TILE));
                return a < 0 ? a + 2 * Math.PI : a;
            })
            .thenComparingInt(c -> (c.x - centreX) * (c.x - centreX) + (c.z - centreZ) * (c.z - centreZ))
            .thenComparingLong(ChunkPos::toLong));
        return out;
    }

    static List<String> scopes() {
        List<String> out = new ArrayList<>(List.of("islands", "all"));
        CityRegions.INSTANCE.all().forEach(r -> out.add(r.id()));
        return out;
    }

    /** Starts (or restarts) generating {@code scope}. Returns what it will do, or why not. */
    static String start(MinecraftServer server, String scope, int margin) {
        String s = scope.toLowerCase(Locale.ROOT);
        if (!scopes().contains(s)) return "Unknown scope '" + scope + "'. Use one of " + String.join(", ", scopes()) + ".";
        int m = Math.max(0, Math.min(MAX_MARGIN, margin));
        List<ChunkPos> next = new ArrayList<>(chunks(server.overworld(), s, m));
        if (next.isEmpty()) return "No city chunks to generate (this world has no city regions).";
        stopFlight(server);
        Store store = store(server);
        store.order = ORDER; store.scope = s; store.margin = m; store.next = 0; store.total = next.size(); store.done = 0; store.skipped = 0; store.running = true; store.finished = false;
        store.setDirty();
        plan = next;
        roadsPending = s.equals("islands");
        startedAt = System.nanoTime(); doneAtStart = 0; lastLogged = 0; lastForgot = 0;
        LOGGER.info("[UC-PREGEN] generating {} chunks of {} with a margin of {} blocks, {} at a time", next.size(), s, m, IN_FLIGHT);
        String what = s.equals("islands") ? "the islands (every city first, then the rest of the land; the roads' bridges are added once they are planned)" : s.equals("all") ? "every city" : s;
        return "Generating " + next.size() + " chunks of " + what + ", and " + m + " blocks round. /uc admin pregen status shows how far it is.";
    }

    static String stop(MinecraftServer server) {
        Store store = store(server);
        if (!store.running) return "Nothing is being generated.";
        stopFlight(server);
        store.running = false; store.setDirty();
        return "Stopped at " + store.done + " of " + store.total + " chunks; /uc admin pregen start carries on from the start of the list, skipping nothing that exists (existing chunks load at once).";
    }

    static String status(MinecraftServer server) {
        Store store = store(server);
        if (store.total == 0) return "No pre-generation in this world yet.";
        double seconds = (System.nanoTime() - startedAt) / 1e9;
        String rate = store.running && seconds > 1 ? String.format(Locale.ROOT, ", %.1f chunks/s", (store.done - doneAtStart) / seconds) : "";
        return String.format(Locale.ROOT, "Pre-generation of %s: %d of %d chunks done%s%s, %d let go unfinished%s.", store.scope, store.done, store.total,
            store.running ? " (" + FLIGHT.size() + " in progress)" : store.finished ? ", finished" : ", stopped", rate, store.skipped, store.margin > 0 ? ", margin " + store.margin + " blocks" : "");
    }

    private static void stopFlight(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (long key : FLIGHT.keySet()) { ChunkPos c = new ChunkPos(key); level.getChunkSource().removeRegionTicket(TICKET, c, 0, c); }
        for (long key : LINGER.keySet()) { ChunkPos c = new ChunkPos(key); level.getChunkSource().removeRegionTicket(TICKET, c, 0, c); }
        FLIGHT.clear();
        LINGER.clear();
    }

    /** Lets go of the finished chunks that have lingered long enough, or the oldest when too many linger; all of them at the end. */
    private static void release(ServerLevel level, int now, boolean all) {
        for (Iterator<Map.Entry<Long, Integer>> it = LINGER.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            if (!all && now - e.getValue() < LINGER_TICKS && LINGER.size() <= LINGER_MAX) break;
            ChunkPos c = new ChunkPos(e.getKey());
            level.getChunkSource().removeRegionTicket(TICKET, c, 0, c);
            it.remove();
        }
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Store store = store(server);
        if (store.running) {
            // carry on where it was: the list is the same for the same scope and the same city regions
            plan = new ArrayList<>(chunks(server.overworld(), store.scope, store.margin));
            roadsPending = store.scope.equals("islands");
            store.total = plan.size();
            if (store.order != ORDER) {
                LOGGER.info("[UC-PREGEN] the saved plan is in an older order: starting it again (the chunks already made load at once)");
                store.order = ORDER; store.next = 0; store.done = 0; store.skipped = 0;
            }
            store.next = Math.min(store.next, plan.size());
            store.setDirty();
            startedAt = System.nanoTime(); doneAtStart = store.done; lastForgot = store.done;
            LOGGER.info("[UC-PREGEN] carrying on: {} of {} chunks of {}", store.done, store.total, store.scope);
            return;
        }
        String auto = System.getProperty("uc.pregen", "");
        if (!auto.isBlank() && !store.finished) LOGGER.info("[UC-PREGEN] {}", start(server, auto.trim().equals("true") ? "islands" : auto.trim(), Integer.getInteger("uc.pregen.margin", 32)));
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) { FLIGHT.clear(); LINGER.clear(); plan = List.of(); roadsPending = false; }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        Store store = store(server);
        if (!store.running || plan.isEmpty()) return;
        ServerLevel level = server.overworld();
        if (roadsPending && !CorridorRoadFeature.ready(level).isEmpty()) {
            java.util.Set<Long> have = new java.util.HashSet<>();
            plan.forEach(c -> have.add(c.toLong()));
            List<ChunkPos> roads = roadChunks(level, store.margin, have);
            plan.addAll(roads);
            store.total = plan.size(); store.setDirty();
            roadsPending = false;
            LOGGER.info("[UC-PREGEN] the roads are planned: {} more chunks along them", roads.size());
        }
        for (Iterator<Map.Entry<Long, Integer>> it = FLIGHT.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> e = it.next();
            ChunkPos c = new ChunkPos(e.getKey());
            boolean complete = level.getChunkSource().getChunkNow(c.x, c.z) != null;
            if (complete || e.getValue() >= PATIENCE) {
                it.remove();
                if (complete) { store.done++; LINGER.put(c.toLong(), server.getTickCount()); }
                else { store.skipped++; level.getChunkSource().removeRegionTicket(TICKET, c, 0, c); }
                store.setDirty();
            } else e.setValue(e.getValue() + 1);
        }
        release(level, server.getTickCount(), false);
        while (FLIGHT.size() < IN_FLIGHT && store.next < plan.size()) {
            ChunkPos c = plan.get(store.next++);
            level.getChunkSource().addRegionTicket(TICKET, c, 0, c);
            FLIGHT.put(c.toLong(), 0);
        }
        if (store.done + store.skipped >= lastLogged + 250) { lastLogged = store.done + store.skipped; LOGGER.info("[UC-PREGEN] {}", status(server)); }
        if (store.done >= lastForgot + FORGET_EVERY) { lastForgot = store.done; LostCitiesLink.forgetPlans(); }
        if (store.next >= plan.size() && FLIGHT.isEmpty() && (!roadsPending || server.getTickCount() % 200 == 0 && waitedForRoads++ > 30)) {
            release(level, server.getTickCount(), true);
            store.running = false; store.finished = true; store.setDirty();
            LOGGER.info("[UC-PREGEN] finished: {} chunks generated or loaded, {} let go unfinished, in {} s", store.done, store.skipped, (int) ((System.nanoTime() - startedAt) / 1e9));
        }
    }
}
