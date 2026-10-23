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

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank() || finished || !started || event.phase != TickEvent.Phase.END) return;
        if (verifier == null) verifier = new WorldRoadVerifier();
        if (verifier.step(event.getServer())) return;
        JsonObject report = new JsonObject();
        report.addProperty("layout", WorldLayout.INSTANCE.current().map(WorldLayout.Layout::id).orElse("none"));
        report.add("roads", verifier.report());
        report.add("shelters", shelters(event.getServer()));
        report.add("ends", ends(event.getServer()));
        // D-137: build each end's approach as a player's first visit would, then measure again
        if (RoadApproaches.enabled(event.getServer().overworld())) {
            JsonArray built = new JsonArray();
            for (CorridorPlan plan : CorridorRoadFeature.plans(event.getServer().overworld())) for (CorridorPlan.End end : plan.ends()) {
                var result = RoadApproaches.build(event.getServer().overworld(), plan, end);
                built.add(plan.id() + " at " + (int) end.x() + " " + (int) end.z() + ": " + result + "; " + pits(event.getServer().overworld(), end, result));
                LOGGER.info("[UC-ROADS] approach built at {} {} {}: {}", plan.id(), (int) end.x(), (int) end.z(), result);
            }
            report.add("approaches", built);
            report.add("ends_after_approaches", ends(event.getServer()));
        } else report.addProperty("approaches", "not enabled in this world (it was not started as a new archipelago world)");
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the road check", error); }
        LOGGER.info("[UC-ROADS] road check written to {}", OUTPUT);
        finished = true;
        event.getServer().halt(false);
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
    static final int END_REACH = 72;

    private static JsonArray ends(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<CorridorPlan> plans = CorridorRoadFeature.plans(level).isEmpty() ? DevCommands.plans(server) : CorridorRoadFeature.plans(level);
        JsonArray out = new JsonArray();
        for (CorridorPlan plan : plans) {
            double first = -1, last = -1;
            for (double at = 0; at <= plan.length(); at += 1) if (plan.openAt(at)) { if (first < 0) first = at; last = at; }
            if (first < 0) continue;
            for (int end = 0; end < 2; end++) {
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
                    if (k > 0 && stand != null && (!RoadApproaches.natural(level.getBlockState(new net.minecraft.core.BlockPos(x, stand - 1, z)))
                        || RoadApproaches.cityOwned(level, new net.minecraft.core.BlockPos(x, stand - 1, z)))) { entry.addProperty("city_ground_at", k); break; }
                }
                entry.addProperty("deck_stand", deck);
                entry.addProperty("max_step_up", maxUp);
                entry.addProperty("max_step_down", maxDown);
                entry.addProperty("worst_up_at", upAt);
                entry.addProperty("walkable", maxUp <= 1);
                entry.add("stand_heights_from_4_out", heights);
                out.add(entry);
                LOGGER.info("[UC-ROADS] {} {} end: steps up to {} (at {}), down to {}; walkable {}", plan.id(), end == 0 ? "start" : "finish", maxUp, upAt, maxDown, maxUp <= 1);
            }
        }
        return out;
    }

    /**
     * The wayside stops of every corridor, served as if a player stood at each one (the same {@link WaysideShelters#serve} the level tick runs): which were built and where, and which were
     * given up for want of level, dry ground. The chunks around each stop are generated first, as a player's own approach would. It builds in the disposable measurement world only.
     */
    private static JsonArray shelters(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<CorridorPlan> plans = DevCommands.plans(server);
        PlacedSites state = PlacedSites.get(level, PlacedSites.SHELTERS);
        JsonArray out = new JsonArray();
        int radius = WaysideShelters.SEARCH + 40;
        for (CorridorPlan plan : plans) {
            List<Double> ideal = WaysideShelters.positions(plan, WaysideShelters.MAX_GAP, WaysideShelters.EDGE);
            for (int i = 0; i < ideal.size(); i++) {
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
        }
        return out;
    }
}
