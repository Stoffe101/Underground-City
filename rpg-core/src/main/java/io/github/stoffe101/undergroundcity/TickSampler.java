package io.github.stoffe101.undergroundcity;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in ({@code -Duc.tickSampler=true}) measurement of where long server ticks spend their time, for the arrival hitch (docs/technical/population.md).
 * Every tick's length is kept under the segment a scenario names ({@link #segment}), so a scenario can give percentiles for walking in, staying and teleporting; and a daemon thread
 * looks at the server thread's stack every few milliseconds while a tick runs past {@link #WATCH_FROM_NANOS}, so that a tick over {@link #REPORT_OVER_NANOS} is logged with the frames
 * it was in. It only reads: it never changes the watchdog, the population or what Lost Cities does, and it costs nothing unless the property is set.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class TickSampler {
    private static final boolean ENABLED = Boolean.getBoolean("uc.tickSampler") || Boolean.getBoolean("uc.playtest.ticksampler");
    private static final long WATCH_FROM_NANOS = 30_000_000L;
    private static final long REPORT_OVER_NANOS = 100_000_000L;
    private static final int EVERY_MS = 4;
    private static final int REPORTS = 40;
    /** A tick this long is reported even after {@link #REPORTS} reports. */
    private static final long ALWAYS_OVER_NANOS = 250_000_000L;

    private static volatile Thread serverThread;
    private static volatile long tickStarted;            // nanoTime of the running tick's start, 0 between ticks
    private static volatile boolean running;
    private static final List<StackTraceElement[]> CURRENT = new ArrayList<>();   // the running tick's samples, guarded by itself
    private static final Map<String, int[]> BLOCKED = new LinkedHashMap<>();       // the running tick's samples in which the server thread waited on a lock: by holder and what it was doing, guarded by CURRENT
    private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
    private static final Map<String, List<Long>> SEGMENTS = new LinkedHashMap<>();
    private static String segment = "start";
    private static int reported;

    private TickSampler() {}

    public static boolean enabled() { return ENABLED; }

    /** Names the segment the ticks from now on belong to (server thread). */
    public static void segment(String name) {
        if (!ENABLED) return;
        segment = name;
        SEGMENTS.computeIfAbsent(name, k -> new ArrayList<>());
        System.out.println("[UC-TICKS] segment " + name);
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!ENABLED) return;
        serverThread = Thread.currentThread();   // posted on the server thread
        running = true;
        Thread sampler = new Thread(TickSampler::sample, "uc-tick-sampler");
        sampler.setDaemon(true);
        sampler.start();
        System.out.println("[UC-TICKS] sampler armed: stacks every " + EVERY_MS + " ms once a tick passes " + WATCH_FROM_NANOS / 1_000_000 + " ms, ticks over " + REPORT_OVER_NANOS / 1_000_000 + " ms reported");
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (!ENABLED) return;
        running = false;
        for (String name : SEGMENTS.keySet()) System.out.println("[UC-TICKS] " + summary(name));
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void tickStart(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.START) return;
        synchronized (CURRENT) { CURRENT.clear(); BLOCKED.clear(); }
        tickStarted = System.nanoTime();
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void tickEnd(TickEvent.ServerTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END || tickStarted == 0) return;
        long spent = System.nanoTime() - tickStarted;
        tickStarted = 0;
        SEGMENTS.computeIfAbsent(segment, k -> new ArrayList<>()).add(spent);
        if (spent < REPORT_OVER_NANOS || (reported >= REPORTS && spent < ALWAYS_OVER_NANOS)) return;
        reported++;
        List<StackTraceElement[]> samples;
        Map<String, int[]> blocked;
        synchronized (CURRENT) { samples = new ArrayList<>(CURRENT); blocked = new LinkedHashMap<>(BLOCKED); }
        System.out.println("[UC-TICKS] long tick " + spent / 1_000_000 + " ms in segment " + segment + ", " + samples.size() + " samples of " + EVERY_MS + " ms; where it was:");
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, StackTraceElement[]> first = new LinkedHashMap<>();
        for (StackTraceElement[] stack : samples) {
            String key = signature(stack);
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            first.putIfAbsent(key, stack);
        }
        counts.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(3).forEach(entry -> {
            System.out.println("[UC-TICKS]   " + entry.getValue()[0] + " samples in " + entry.getKey());
            StackTraceElement[] stack = first.get(entry.getKey());
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < Math.min(stack.length, 45); i++) text.append("[UC-TICKS]       at ").append(stack[i]).append('\n');
            System.out.print(text);
        });
        blocked.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(3).forEach(entry ->
            System.out.println("[UC-TICKS]   the server thread waited on a lock in " + entry.getValue()[0] + " samples, " + entry.getKey()));
    }

    /** The frames that tell a stall apart: the deepest few in Lost Cities, Core and the chunk system, outermost Core caller included. */
    private static String signature(StackTraceElement[] stack) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : stack) {
            String name = frame.getClassName();
            if (name.startsWith("mcjty.") || name.startsWith("io.github.stoffe101") || name.startsWith("net.minecraft.server.level.") || name.startsWith("net.minecraft.world.level.levelgen")
                || name.startsWith("net.minecraft.world.level.chunk") || name.startsWith("net.minecraft.world.entity.ai")) {
                text.append(name.substring(name.lastIndexOf('.') + 1)).append('.').append(frame.getMethodName()).append(" < ");
                if (++shown >= 8) break;
            }
        }
        return shown == 0 && stack.length > 0 ? stack[0].getClassName() + "." + stack[0].getMethodName() : text.toString();
    }

    private static void sample() {
        while (running) {
            try {
                Thread.sleep(EVERY_MS);
            } catch (InterruptedException e) {
                return;
            }
            long started = tickStarted;
            Thread thread = serverThread;
            if (started == 0 || thread == null || System.nanoTime() - started < WATCH_FROM_NANOS) continue;
            StackTraceElement[] stack = thread.getStackTrace();
            if (stack.length == 0 || tickStarted != started) continue;
            String holder = null;
            java.lang.management.ThreadInfo info = THREADS.getThreadInfo(thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockOwnerId() >= 0) {
                java.lang.management.ThreadInfo owner = THREADS.getThreadInfo(info.getLockOwnerId(), 40);
                holder = "held by " + (owner == null ? "a thread that has gone" : owner.getThreadName() + " in " + signature(owner.getStackTrace()));
            }
            synchronized (CURRENT) {
                if (CURRENT.size() < 2000) CURRENT.add(stack);
                if (holder != null) BLOCKED.computeIfAbsent(holder, k -> new int[1])[0]++;
            }
        }
    }

    /** One line: ticks, mean, p50, p95, p99, max and the counts over 50, 100 and 250 ms, for a segment (server thread). */
    public static String summary(String name) {
        List<Long> list = SEGMENTS.getOrDefault(name, List.of());
        if (list.isEmpty()) return "segment " + name + ": no ticks";
        long[] ticks = list.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(ticks);
        double mean = Arrays.stream(ticks).average().orElse(0) / 1e6;
        long over50 = Arrays.stream(ticks).filter(t -> t > 50_000_000L).count(), over100 = Arrays.stream(ticks).filter(t -> t > 100_000_000L).count(), over250 = Arrays.stream(ticks).filter(t -> t > 250_000_000L).count();
        return String.format(Locale.ROOT, "segment %s: %d ticks, mean %.1f ms, p50 %.1f, p95 %.1f, p99 %.1f, max %.1f; over 50 ms %d, over 100 ms %d, over 250 ms %d",
            name, ticks.length, mean, pct(ticks, 50), pct(ticks, 95), pct(ticks, 99), ticks[ticks.length - 1] / 1e6, over50, over100, over250);
    }

    private static double pct(long[] sorted, int percentile) {
        int index = Math.min(sorted.length - 1, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
        return sorted[Math.max(0, index)] / 1e6;
    }
}
