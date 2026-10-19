package io.github.stoffe101.undergroundcity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in ({@code -Duc.stallProbe=true}) reproduction and measurement of the long server ticks that Lost Cities causes after bulk chunk generation (docs/technical/lost-cities-stalls.md).
 * It asks the chunk system for a square of chunks around a centre the way a pregenerator or a large {@code /forceload} does, then reports, from the server thread's own point of view,
 * how long every tick took, how many were longer than 1, 5, 30 and 60 seconds (60 s is what the production watchdog kills), how long the chunks took to arrive, and, from a sampler
 * thread that looks at the server thread's stack every 100 ms while a tick is running long, where the time went.
 *
 * <p>It never changes the server's watchdog: the server it runs on is started with {@code max-tick-time=-1} only so that a stall can be measured instead of ending the run, and every
 * report says how many ticks would have been killed (&gt; 60 s). Properties: {@code uc.stallProbe.radius} (chunks, default 8), {@code .center} (chunk "x,z", default "0,0"),
 * {@code .mode} ({@code bulk}: all tickets in one tick; {@code paced}: {@code .pertick} chunks every {@code .every} ticks), {@code .settle} (ticks to keep measuring after the last chunk arrived, default 1200).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class StallProbe {
    private static final boolean ENABLED = Boolean.getBoolean("uc.stallProbe");
    private static final int RADIUS = Integer.getInteger("uc.stallProbe.radius", 8);
    private static final String CENTER = System.getProperty("uc.stallProbe.center", "0,0");
    private static final boolean BULK = !"paced".equals(System.getProperty("uc.stallProbe.mode", "bulk"));
    private static final int PER_TICK = Integer.getInteger("uc.stallProbe.pertick", 4);
    /** Paced mode asks for {@code pertick} chunks every this many ticks (7 with one chunk each is about what a walking player's view distance needs). */
    private static final int EVERY = Math.max(1, Integer.getInteger("uc.stallProbe.every", 1));
    private static final int SETTLE = Integer.getInteger("uc.stallProbe.settle", 1200);
    private static final int WARMUP = 100;
    private static final long TIMEOUT_MS = Long.getLong("uc.stallProbe.timeoutms", 25 * 60_000L);

    private static MinecraftServer server;
    private static volatile long tickStarted;          // nanoTime of the running tick's start, 0 between ticks
    private static int ticks, requested, nextToRequest, settled;
    private static long requestedAt, allFullAt, maxTickNanos, overBudgetNanos, startedAt;
    private static final long[] OVER = new long[4];      // ticks over 1 s, 5 s, 30 s, 60 s
    private static final List<ChunkPos> TODO = new ArrayList<>();
    private static int stage;                            // 0 warm-up, 1 requesting, 2 waiting for the chunks, 3 settling, 4 done
    private static final Map<String, int[]> SAMPLES = new LinkedHashMap<>();
    private static final Map<String, String> FULL_STACK = new LinkedHashMap<>();

    private StallProbe() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!ENABLED) return;
        server = event.getServer();
        startedAt = System.currentTimeMillis();
        Thread serverThread = Thread.currentThread();   // ServerStartedEvent is posted on the server thread
        Thread sampler = new Thread(() -> sample(serverThread), "uc-stall-sampler");
        sampler.setDaemon(true);
        sampler.start();
        System.out.println("[UC-STALL-PROBE] armed: radius " + RADIUS + " chunks around " + CENTER + ", " + (BULK ? "bulk" : "paced " + PER_TICK + " per tick") + ", settle " + SETTLE + " ticks");
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || server == null || stage >= 4) return;
        if (event.phase == TickEvent.Phase.START) {
            tickStarted = System.nanoTime();
            return;
        }
        long spent = System.nanoTime() - tickStarted;
        tickStarted = 0;
        ticks++;
        maxTickNanos = Math.max(maxTickNanos, spent);
        if (spent > 50_000_000L) overBudgetNanos += spent - 50_000_000L;
        if (spent > 1_000_000_000L) OVER[0]++;
        if (spent > 5_000_000_000L) OVER[1]++;
        if (spent > 30_000_000_000L) OVER[2]++;
        if (spent > 60_000_000_000L) OVER[3]++;
        if (spent > 1_000_000_000L) System.out.println("[UC-STALL-PROBE] tick " + ticks + " took " + spent / 1_000_000 + " ms (stage " + stage + ", " + full() + "/" + requested + " chunks full)");
        ServerLevel level = server.overworld();
        switch (stage) {
            case 0 -> {
                if (ticks >= WARMUP) {
                    String[] parts = CENTER.split(",");
                    int cx = Integer.parseInt(parts[0].trim()), cz = Integer.parseInt(parts[1].trim());
                    for (int dx = -RADIUS; dx <= RADIUS; dx++) for (int dz = -RADIUS; dz <= RADIUS; dz++) TODO.add(new ChunkPos(cx + dx, cz + dz));
                    TODO.sort(Comparator.comparingInt(p -> Math.abs(p.x - cx) + Math.abs(p.z - cz)));   // nearest first, like a pregenerator spiralling out
                    requestedAt = System.currentTimeMillis();
                    stage = 1;
                    System.out.println("[UC-STALL-PROBE] requesting " + TODO.size() + " chunks");
                }
            }
            case 1 -> {
                int budget = BULK ? TODO.size() : (ticks % EVERY == 0 ? PER_TICK : 0);
                for (int i = 0; i < budget && nextToRequest < TODO.size(); i++) {
                    ChunkPos pos = TODO.get(nextToRequest++);
                    level.getChunkSource().addRegionTicket(TicketType.FORCED, pos, 2, pos);
                    requested++;
                }
                if (nextToRequest >= TODO.size()) stage = 2;
            }
            case 2 -> {
                if (ticks % 20 == 0 && full() >= requested) {
                    allFullAt = System.currentTimeMillis();
                    stage = 3;
                    System.out.println("[UC-STALL-PROBE] all " + requested + " chunks are full after " + (allFullAt - requestedAt) / 1000 + " s");
                } else if (System.currentTimeMillis() - requestedAt > TIMEOUT_MS) {
                    System.out.println("[UC-STALL-PROBE] gave up waiting: " + full() + "/" + requested + " chunks after " + TIMEOUT_MS / 1000 + " s");
                    stage = 3;
                }
            }
            case 3 -> {
                if (++settled >= SETTLE) {
                    stage = 4;
                    report();
                    server.halt(false);
                }
            }
            default -> { }
        }
    }

    private static int full() {
        ServerLevel level = server.overworld();
        int count = 0;
        for (int i = 0; i < nextToRequest; i++) {
            ChunkPos pos = TODO.get(i);
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) != null) count++;
        }
        return count;
    }

    /** Looks at the server thread every 100 ms; while one tick has run for more than 1.5 s, counts which frames it is in. */
    private static void sample(Thread serverThread) {
        while (stage < 4) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            long started = tickStarted;
            if (started == 0 || System.nanoTime() - started < 1_500_000_000L) continue;
            StackTraceElement[] stack = serverThread.getStackTrace();
            if (stack.length == 0) continue;
            StringBuilder signature = new StringBuilder();
            int shown = 0;
            for (StackTraceElement frame : stack) {
                String name = frame.getClassName();
                if (name.startsWith("mcjty.lostcities") || name.startsWith("net.minecraft.server.level.ServerChunkCache") || name.startsWith("net.minecraft.world.level.block.SaplingBlock")
                    || name.startsWith("net.minecraft.util.thread.BlockableEventLoop") || name.startsWith("net.minecraft.world.level.levelgen.feature.TreeFeature") || name.contains("Lock")) {
                    signature.append(name.substring(name.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(" < ");
                    if (++shown >= 6) break;
                }
            }
            String key = shown == 0 ? stack[0].getClassName() + "." + stack[0].getMethodName() : signature.toString();
            synchronized (SAMPLES) {
                SAMPLES.computeIfAbsent(key, k -> new int[1])[0]++;
                FULL_STACK.computeIfAbsent(key, k -> {
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < Math.min(stack.length, 40); i++) text.append("      at ").append(stack[i]).append('\n');
                    return text.toString();
                });
            }
        }
    }

    /** Leaves, logs and saplings in the requested chunks: what a configuration that skips the tree growth changes in the world (counted after the last long tick, so it is not part of the measurement). */
    private static int[] vegetation() {
        int[] counts = new int[3];
        ServerLevel level = server.overworld();
        for (ChunkPos pos : TODO) {
            var chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) continue;
            var cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                var section = chunk.getSection(i);
                if (section.hasOnlyAir()) continue;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    var state = section.getBlockState(x, y, z);
                    if (state.isAir()) continue;
                    if (state.is(net.minecraft.tags.BlockTags.LEAVES)) counts[0]++;
                    else if (state.is(net.minecraft.tags.BlockTags.LOGS)) counts[1]++;
                    else if (state.is(net.minecraft.tags.BlockTags.SAPLINGS)) counts[2]++;
                }
            }
        }
        return counts;
    }

    private static void report() {
        int[] vegetation = vegetation();
        String json = String.format(java.util.Locale.ROOT,
            "{\"radius\":%d,\"mode\":\"%s\",\"chunks_requested\":%d,\"chunks_full\":%d,\"seconds_to_all_chunks\":%d,\"ticks\":%d,\"max_tick_ms\":%d,\"over_budget_total_ms\":%d,"
                + "\"ticks_over_1s\":%d,\"ticks_over_5s\":%d,\"ticks_over_30s\":%d,\"ticks_over_60s_watchdog_would_kill\":%d,\"wall_seconds\":%d,"
                + "\"leaf_blocks\":%d,\"log_blocks\":%d,\"sapling_blocks\":%d}",
            RADIUS, BULK ? "bulk" : "paced", requested, full(), allFullAt == 0 ? -1 : (allFullAt - requestedAt) / 1000, ticks, maxTickNanos / 1_000_000, overBudgetNanos / 1_000_000,
            OVER[0], OVER[1], OVER[2], OVER[3], (System.currentTimeMillis() - startedAt) / 1000, vegetation[0], vegetation[1], vegetation[2]);
        System.out.println("[UC-STALL-PROBE] RESULT " + json);
        synchronized (SAMPLES) {
            SAMPLES.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(5).forEach(entry -> {
                System.out.println("[UC-STALL-PROBE] where long ticks spent their time: " + entry.getValue()[0] + " samples (100 ms each) in " + entry.getKey());
                System.out.println("[UC-STALL-PROBE] first stack seen:\n" + FULL_STACK.get(entry.getKey()));
            });
        }
    }
}
