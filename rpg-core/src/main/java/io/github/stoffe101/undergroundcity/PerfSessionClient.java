package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * A client of the two-player performance session ({@link PerfSessionServer}): it walks on its own (forward held, a turn every six seconds, a jump and a turn when it walks into something)
 * so that chunks load and townspeople come and go round it as they would round a player, and it keeps the length of every frame. Each minute's frames become one line: how many, the mean,
 * the 99th percentile, the worst, and how many took over 100 and 250 ms; the lines are written to {@code perf-client.json} in the client's game folder every minute. The client renders in
 * software in a virtual display at a frame cap of 30, so its numbers say whether the game hitches, not how fast a real graphics card would draw it.
 */
@OnlyIn(Dist.CLIENT)
final class PerfSessionClient {
    private PerfSessionClient() {}

    private static int ticks, stuck, jumping, frames;
    private static float yaw = Float.NaN;
    private static long lastFrame, minuteStarted;
    private static long[] frameNanos = new long[4096];
    private static final JsonArray MINUTES = new JsonArray();

    /** Every rendered frame (render tick end). */
    static void frame(Minecraft mc) {
        if (mc.level == null || mc.player == null) { lastFrame = 0; return; }
        long now = System.nanoTime();
        if (lastFrame != 0) {
            if (frames == frameNanos.length) frameNanos = Arrays.copyOf(frameNanos, frames * 2);
            frameNanos[frames++] = now - lastFrame;
        }
        lastFrame = now;
        if (minuteStarted == 0) minuteStarted = now;
        if (now - minuteStarted >= 60_000_000_000L) { closeMinute(mc); minuteStarted = now; }
    }

    /** Every client tick: the walk. */
    static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) return;   // nothing in this session opens a screen; if one appears, the walk waits
        ticks++;
        var player = mc.player;
        if (Float.isNaN(yaw)) yaw = (mc.getUser().getName().hashCode() & 0xff) * 1.4f;
        if (ticks % 120 == 0) yaw += 47f;
        if (player.horizontalCollision) {
            if (++stuck % 20 == 0) yaw += 90f;
            jumping = 4;
        } else stuck = 0;
        player.setYRot(yaw);
        player.setXRot(0f);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(jumping-- > 0);
    }

    private static void closeMinute(Minecraft mc) {
        if (frames == 0) return;
        long[] f = Arrays.copyOf(frameNanos, frames);
        Arrays.sort(f);
        double mean = Arrays.stream(f).average().orElse(0) / 1e6;
        JsonObject m = new JsonObject();
        m.addProperty("minute", MINUTES.size() + 1);
        m.addProperty("frames", frames);
        m.addProperty("mean_frame_ms", Math.round(mean * 10) / 10.0);
        m.addProperty("fps", Math.round(1000 / Math.max(mean, 1e-3) * 10) / 10.0);
        m.addProperty("p99_frame_ms", Math.round(f[Math.min(f.length - 1, (int) Math.ceil(0.99 * f.length) - 1)] / 1e5) / 10.0);
        m.addProperty("worst_frame_ms", Math.round(f[f.length - 1] / 1e5) / 10.0);
        m.addProperty("over_100_ms", Arrays.stream(f).filter(v -> v > 100_000_000L).count());
        m.addProperty("over_250_ms", Arrays.stream(f).filter(v -> v > 250_000_000L).count());
        m.addProperty("x", Math.round(mc.player.getX()));
        m.addProperty("z", Math.round(mc.player.getZ()));
        MINUTES.add(m);
        frames = 0;
        System.out.println(String.format(Locale.ROOT, "[UC-PERF] client %s minute %d: %s", mc.getUser().getName(), MINUTES.size(), m));
        JsonObject report = new JsonObject();
        report.addProperty("player", mc.getUser().getName());
        report.addProperty("phase", PerfSessionServer.PHASE);
        report.add("minutes", MINUTES);
        try { Files.writeString(mc.gameDirectory.toPath().resolve("perf-client-phase" + PerfSessionServer.PHASE + ".json"), report + "\n"); }
        catch (java.io.IOException error) { System.out.println("[UC-PERF] could not write the client report: " + error); }
    }
}
