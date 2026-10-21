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
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the road check", error); }
        LOGGER.info("[UC-ROADS] road check written to {}", OUTPUT);
        finished = true;
        event.getServer().halt(false);
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
