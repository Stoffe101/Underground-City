package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Opt-in measurement of the generated corridor roads and bridges of whichever layout the game carries ({@code -Duc.roadCheck=<report.json>}, disposable dedicated server only). It runs the
 * same {@link WorldRoadVerifier} as the world-integration job (which only ever surveyed the legacy prototype) against the archipelago: the chunks along every route are generated on worker
 * threads and the real blocks are read along each centreline (deck, headroom, steps, parapets, water under the bridges, vegetation over the carriageway). It writes numbers; it builds nothing.
 */
public final class RoadCheck {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT = System.getProperty("uc.roadCheck");
    private WorldRoadVerifier verifier;
    private boolean started, finished;

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank()) return;
        started = true;
    }

    /** What is left to measure once the roads are surveyed, one item a tick: generating the chunks of every stop, place and road end in a single tick kept them all in memory until the tick ended (the October 23 check ran out of memory at the last road end). */
    private java.util.ArrayDeque<Runnable> work;
    private JsonObject report;

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank() || finished || !started || event.phase != TickEvent.Phase.END) return;
        if (verifier == null) verifier = new WorldRoadVerifier();
        if (work == null) {
            if (verifier.step(event.getServer())) return;
            plan(event.getServer());
            return;
        }
        if (!work.isEmpty()) { work.poll().run(); return; }
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the road check", error); }
        LOGGER.info("[UC-ROADS] road check written to {}", OUTPUT);
        finished = true;
        event.getServer().halt(false);
    }

    /** Queues the measurements after the survey: the shelters, the roadside places, the road ends, and (where the world has them) each end's approach and the ends again. */
    private void plan(MinecraftServer server) {
        work = new java.util.ArrayDeque<>();
        report = new JsonObject();
        report.addProperty("layout", WorldLayout.INSTANCE.current().map(WorldLayout.Layout::id).orElse("none"));
        report.add("roads", verifier.report());
        ServerLevel level = server.overworld();
        JsonArray shelters = new JsonArray();
        report.add("shelters", shelters);
        List<CorridorPlan> all = DevCommands.plans(server);
        for (CorridorPlan plan : all) {
            List<Double> ideal = WaysideShelters.positions(plan, WaysideShelters.MAX_GAP, WaysideShelters.EDGE);
            for (int i = 0; i < ideal.size(); i++) { final int stop = i; work.add(() -> shelter(server, all, plan, ideal, stop, shelters)); }
        }
        // D-138: the roadside places, where the world enabled them, served as a player's arrival would
        if (RoadsidePlaces.enabled(level)) {
            JsonArray places = new JsonArray();
            report.add("roadside_places", places);
            work.add(() -> roadside(server, places));
        } else report.addProperty("roadside_places", "not enabled in this world");
        JsonArray ends = new JsonArray();
        report.add("ends", ends);
        int pairs = endCount(server);
        for (int i = 0; i < pairs; i++) { final int only = i; work.add(() -> ends(server, ends, only)); }
        // D-137: build each end's approach as a player's first visit would, then measure again
        if (RoadApproaches.enabled(level)) {
            JsonArray built = new JsonArray(), after = new JsonArray();
            report.add("approaches", built);
            report.add("ends_after_approaches", after);
            for (CorridorPlan plan : CorridorRoadFeature.ready(level)) for (CorridorPlan.End end : plan.ends()) work.add(() -> {
                var result = RoadApproaches.build(level, plan, end);
                built.add(plan.id() + " at " + (int) end.x() + " " + (int) end.z() + ": " + result + "; " + pits(level, end, result));
                LOGGER.info("[UC-ROADS] approach built at {} {} {}: {}", plan.id(), (int) end.x(), (int) end.z(), result);
            });
            for (int i = 0; i < pairs; i++) { final int only = i; work.add(() -> ends(server, after, only)); }
        } else report.addProperty("approaches", "not enabled in this world (it was not started as a new archipelago world)");
        LOGGER.info("[UC-ROADS] the roads are surveyed; {} more measurement(s) to make, one a tick", work.size());
    }

    /**
     * Pits in a ramp just built: block columns of its band that stand two or more blocks below every neighbour of theirs in the band, which a person who steps in cannot step out of
     * (journey run 55's walker fell into a five-deep shaft the old ramp had skipped).
     */
    private static String pits(ServerLevel level, CorridorPlan.End end, RoadApproaches.Result result) {
        var cells = RoadApproaches.band(end, 0, result.cityAt(), RoadApproaches.HALF + 0.5);
        java.util.Map<Long, Integer> stand = new java.util.HashMap<>();
        var p = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (var cell : cells) stand.put(net.minecraft.core.BlockPos.asLong(cell.x(), 0, cell.z()), RoadApproaches.top(level, cell.x(), cell.z(), result.deckY(), p));
        int pits = 0;
        StringBuilder where = new StringBuilder();
        for (var cell : cells) {
            int h = stand.get(net.minecraft.core.BlockPos.asLong(cell.x(), 0, cell.z()));
            if (h == Integer.MIN_VALUE) continue;
            int neighbours = 0, higher = 0;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Integer n = stand.get(net.minecraft.core.BlockPos.asLong(cell.x() + d[0], 0, cell.z() + d[1]));
                if (n == null || n == Integer.MIN_VALUE) continue;
                neighbours++;
                if (n >= h + 2) higher++;
            }
            if (neighbours >= 2 && higher == neighbours) { pits++; if (pits <= 5) where.append(' ').append(cell.x()).append(',').append(h).append(',').append(cell.z()); }
        }
        return cells.size() + " columns in the band, " + pits + " pits" + (pits > 0 ? " at" + where : "");
    }

    /**
     * Where each road meets its city (a scripted journey found the Valewood end of the Ironhaven road facing a two-block bank of earth): along the centreline, from four blocks out on the deck
     * until the city's own built ground (or {@link #END_REACH} blocks into the city circle), the height a person would stand at in each column, and the largest step up and down between neighbouring columns. A step up of more
     * than one block cannot be walked.
     */
    static final int END_REACH = 120;

    private static List<CorridorPlan> endPlans(MinecraftServer server) {
        ServerLevel level = server.overworld();
        return CorridorRoadFeature.ready(level).isEmpty() ? DevCommands.plans(server) : CorridorRoadFeature.ready(level);
    }

    /** How many road ends {@link #ends} measures (two a road). */
    private static int endCount(MinecraftServer server) { return endPlans(server).size() * 2; }

    /** Measures the {@code only}-th road end (two a road, in order) into {@code out}. */
    private static void ends(MinecraftServer server, JsonArray out, int only) {
        ServerLevel level = server.overworld();
        List<CorridorPlan> plans = endPlans(server);
        int index = -1;
        for (CorridorPlan plan : plans) {
            double first = -1, last = -1;
            for (double at = 0; at <= plan.length(); at += 1) if (plan.openAt(at)) { if (first < 0) first = at; last = at; }
            for (int end = 0; end < 2; end++) {
                if (++index != only || first < 0) continue;
                double edge = end == 0 ? first : last, inward = end == 0 ? -1 : 1;
                JsonObject entry = new JsonObject();
                entry.addProperty("road", plan.id());
                entry.addProperty("end", end == 0 ? "start" : "finish");
                JsonArray heights = new JsonArray();
                Integer previous = null;
                int maxUp = 0, maxDown = 0, deck = Integer.MIN_VALUE;
                String upAt = "";
                for (int k = -4; k <= END_REACH; k++) {
                    // inside the circle the centreline continues straight on from the edge
                    double[] a = plan.pointAt(edge), b = plan.pointAt(edge - inward * 4);
                    double dx = a[0] - b[0], dz = a[1] - b[1], len = Math.max(1e-6, Math.hypot(dx, dz));
                    double[] at = k <= 0 ? plan.pointAt(edge + inward * k) : new double[]{a[0] + dx / len * k, a[1] + dz / len * k};
                    int x = (int) Math.floor(at[0]), z = (int) Math.floor(at[1]);
                    level.getChunk(x >> 4, z >> 4);
                    if (k == -4 && plan.built()) deck = CorridorPlan.deckBlockY(plan.deckHalfAt(edge + inward * k)) + 1;
                    int start = (deck == Integer.MIN_VALUE ? level.getSeaLevel() + 20 : deck + 16);
                    Integer stand = null;
                    for (int y = start; y > start - 40 && stand == null; y--) {
                        var pos = new net.minecraft.core.BlockPos(x, y, z);
                        if (level.getBlockState(pos).blocksMotion() && !level.getBlockState(pos.above()).blocksMotion() && !level.getBlockState(pos.above(2)).blocksMotion()) stand = y + 1;
                    }
                    heights.add(stand == null ? -1 : stand);
                    if (stand != null && previous != null) {
                        int step = stand - previous;
                        if (step > maxUp) { maxUp = step; upAt = x + " " + stand + " " + z; }
                        maxDown = Math.max(maxDown, -step);
                    }
                    if (stand != null) previous = stand;
                    // the city's own built ground has been reached: from here on a person follows its streets, and a wall further along the line is walked round, not climbed
                    // the ramp's own paving (the road's deck, D-196) is the road, not the city: the walk goes on along it
                    if (k > 0 && stand != null && !RoadApproaches.paving(level.getBlockState(new net.minecraft.core.BlockPos(x, stand - 1, z)))
                        && (!RoadApproaches.natural(level.getBlockState(new net.minecraft.core.BlockPos(x, stand - 1, z)))
                        || RoadApproaches.cityOwned(level, new net.minecraft.core.BlockPos(x, stand - 1, z)))) { entry.addProperty("city_ground_at", k); break; }
                }
                entry.addProperty("deck_stand", deck);
                // a wall where the straight line meets the city is walked round, not climbed: where the line has a step, ask the player's own path finder for a way on into the
                // city from the last ground before it (to a point 32 blocks further along the line, anywhere within 12 blocks of it)
                if (maxUp > 1) {
                    double[] a = plan.pointAt(edge), b = plan.pointAt(edge - inward * 4);
                    double dx = a[0] - b[0], dz = a[1] - b[1], len = Math.max(1e-6, Math.hypot(dx, dz));
                    int reached = entry.has("city_ground_at") ? entry.get("city_ground_at").getAsInt() : END_REACH;
                    double fx = a[0] + dx / len * (reached - 2), fz = a[1] + dz / len * (reached - 2), tx = a[0] + dx / len * (reached + 32), tz = a[1] + dz / len * (reached + 32);
                    for (int cx = ((int) Math.min(fx, tx) - 48) >> 4; cx <= ((int) Math.max(fx, tx) + 48) >> 4; cx++)
                        for (int cz = ((int) Math.min(fz, tz) - 48) >> 4; cz <= ((int) Math.max(fz, tz) + 48) >> 4; cz++) level.getChunk(cx, cz);
                    int fy = previous == null ? deck : previous;
                    var route = PlayerPath.find(level, new net.minecraft.world.phys.Vec3(fx, fy, fz), new net.minecraft.world.phys.Vec3(tx, fy, tz), 12.0, 16.0);
                    entry.addProperty("path_into_city", route.isEmpty() ? "none" : route.size() + " waypoints");
                    LOGGER.info("[UC-ROADS] {} {} end: a way round the step into the city: {}", plan.id(), end == 0 ? "start" : "finish", route.isEmpty() ? "none" : route.size() + " waypoints");
                }
                entry.addProperty("max_step_up", maxUp);
                entry.addProperty("max_step_down", maxDown);
                entry.addProperty("worst_up_at", upAt);
                entry.addProperty("walkable", maxUp <= 1);
                entry.add("stand_heights_from_4_out", heights);
                out.add(entry);
                LOGGER.info("[UC-ROADS] {} {} end: steps up to {} (at {}), down to {}; walkable {}", plan.id(), end == 0 ? "start" : "finish", maxUp, upAt, maxDown, maxUp <= 1);
            }
        }
    }

    /**
     * The wayside stops of every corridor, served as if a player stood at each one (the same {@link WaysideShelters#serve} the level tick runs): which were built and where, and which were
     * given up for want of level, dry ground. The chunks around each stop are generated first, as a player's own approach would. It builds in the disposable measurement world only.
     */
    private static void shelter(MinecraftServer server, List<CorridorPlan> plans, CorridorPlan plan, List<Double> ideal, int i, JsonArray out) {
        ServerLevel level = server.overworld();
        PlacedSites state = PlacedSites.get(level, PlacedSites.SHELTERS);
        int radius = WaysideShelters.SEARCH + 40;
        double[] at = plan.pointAt(ideal.get(i));
        for (int cx = ((int) at[0] - radius) >> 4; cx <= ((int) at[0] + radius) >> 4; cx++) for (int cz = ((int) at[1] - radius) >> 4; cz <= ((int) at[1] + radius) >> 4; cz++) level.getChunk(cx, cz);
        long started = System.nanoTime();
        WaysideShelters.serve(level, plans, plan, ideal, at[0], at[1], state);
        String key = plan.id() + "#" + i;
        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("kind", WaysideShelters.kindOf(plan.id(), i));
        entry.addProperty("ideal", Math.round(at[0]) + ", " + Math.round(at[1]));
        Long where = state.where.get(key);
        entry.addProperty("built", where != null);
        if (where != null) entry.addProperty("origin", net.minecraft.core.BlockPos.of(where).toShortString());
        else entry.addProperty("given_up", state.done.contains(key));
        entry.addProperty("millis", (System.nanoTime() - started) / 1_000_000L);
        out.add(entry);
    }

    /**
     * Each roadside place (D-138) served at its candidate spots in order, with the chunks around each generated first as a player's own approach would: where it was built, which way it
     * faces, how uneven its lot was, its driveway's length and steepest step, and how long the decision and the build took on the server thread.
     */
    private void roadside(MinecraftServer server, JsonArray out) {
        ServerLevel level = server.overworld();
        List<CorridorPlan> plans = CorridorRoadFeature.ready(level);   // the built plans (bridges, water, deck heights)
        PlacedSites state = PlacedSites.get(level, RoadsidePlaces.KEY);
        // a measurement world measured again: a place given up last time is looked for afresh (this tool runs only in a disposable world)
        for (RoadsidePlaces.Place place : RoadsidePlaces.CATALOG.all()) if (!state.where.containsKey(place.id())) { state.done.remove(place.id()); state.failed.remove(place.id()); }
        RoadsidePlaces.resetForTest();
        java.util.List<Runnable> attempts = new java.util.ArrayList<>();
        for (CorridorPlan plan : plans) {
            List<RoadsidePlaces.Place> places = RoadsidePlaces.CATALOG.onRoad(plan.id());
            var candidates = RoadsidePlaces.plan(plan, places);
            for (RoadsidePlaces.Place place : places) {
                List<Double> spots = candidates.get(place.id());
                JsonObject entry = new JsonObject();
                entry.addProperty("place", place.id());
                entry.addProperty("road", plan.id());
                entry.addProperty("candidates", spots.size());
                JsonArray tries = new JsonArray();
                entry.add("tries", tries);
                attempts.add(() -> attempt(server, plans, place, spots, 0, state, entry, tries, out));
            }
        }
        for (int k = attempts.size() - 1; k >= 0; k--) work.addFirst(attempts.get(k));
    }

    /**
     * A place going up a slice per tick: its lot's chunks kept in the world (a player standing there would keep them; the check has nobody), one look a tick, until it stands, then the place's
     * record as {@link #attempt} would write it. Gives up after ten minutes of ticks, which a slice of at most 15 ms never needs.
     */
    private void waitBuilt(MinecraftServer server, List<CorridorPlan> plans, RoadsidePlaces.Place place, List<Double> spots, int i, PlacedSites state, JsonObject entry, JsonArray tries, JsonArray out, double[] at, int ticks) {
        ServerLevel level = server.overworld();
        if (RoadsidePlaces.building(place.id()) != null && ticks < 12_000) {
            int radius = RoadsidePlaces.SEARCH + 32;
            for (int cx = ((int) at[0] - radius) >> 4; cx <= ((int) at[0] + radius) >> 4; cx++) for (int cz = ((int) at[1] - radius) >> 4; cz <= ((int) at[1] + radius) >> 4; cz++) level.getChunk(cx, cz);
            work.addFirst(() -> waitBuilt(server, plans, place, spots, i, state, entry, tries, out, at, ticks + 1));
            return;
        }
        entry.addProperty("ticks_to_build", ticks);
        // built: the record; given up part-way (its lot was refused as it went up): the next spot, as the unsliced check would
        attempt(server, plans, place, spots, state.done.contains(place.id()) || i >= spots.size() ? spots.size() + 1 : i + 1, state, entry, tries, out);
    }

    /** One candidate spot of a place, with the chunks around it generated first as a player's own approach would; the next spot is queued at the front if this one did not settle it. */
    private void attempt(MinecraftServer server, List<CorridorPlan> plans, RoadsidePlaces.Place place, List<Double> spots, int i, PlacedSites state, JsonObject entry, JsonArray tries, JsonArray out) {
        ServerLevel level = server.overworld();
        int radius = RoadsidePlaces.SEARCH + 80;
        if (i <= spots.size() && !state.done.contains(place.id())) {
            double[] at = plans.stream().filter(p -> p.id().equals(place.road())).findFirst().map(p -> p.pointAt(spots.isEmpty() ? 0 : spots.get(Math.min(i, spots.size() - 1)))).orElse(new double[]{0, 0});
            for (int cx = ((int) at[0] - radius) >> 4; cx <= ((int) at[0] + radius) >> 4; cx++) for (int cz = ((int) at[1] - radius) >> 4; cz <= ((int) at[1] + radius) >> 4; cz++) level.getChunk(cx, cz);
            // built as a player's arrival builds it: a slice per tick (D-148), the check waiting until it stands; the log line of the build says how many slices and the longest
            CorridorPlan road = plans.stream().filter(p -> p.id().equals(place.road())).findFirst().orElse(null);
            RoadsidePlaces.Outcome outcome = road == null ? null : RoadsidePlaces.serve(level, plans, place, spots, at[0], at[1], state, RoadsidePlaces.SEARCH + 32, RoadsidePlaces.deck(road), true);
            if (outcome != null) tries.add(outcome.toString());
            if (RoadsidePlaces.building(place.id()) != null) { work.addFirst(() -> waitBuilt(server, plans, place, spots, i, state, entry, tries, out, at, 0)); return; }
            if (i < spots.size() && !state.done.contains(place.id())) { work.addFirst(() -> attempt(server, plans, place, spots, i + 1, state, entry, tries, out)); return; }
        }
        Long where = state.where.get(place.id());
        entry.addProperty("built", where != null);
        if (where != null) entry.addProperty("origin", net.minecraft.core.BlockPos.of(where).toShortString());
        out.add(entry);
        LOGGER.info("[UC-ROADS] roadside place {}: {}", place.id(), entry);
    }
}
