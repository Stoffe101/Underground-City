package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the road check", error); }
        LOGGER.info("[UC-ROADS] road check written to {}", OUTPUT);
        finished = true;
        event.getServer().halt(false);
    }
}
