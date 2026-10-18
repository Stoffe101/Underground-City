package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Opt-in ({@code -Duc.perfProbe=true}) measurement on a dedicated server, for the sprint report's performance line: how much compute one server tick
 * costs on an idle world, and how much it costs once Founders Row stands and its citizens (villagers with their AI on) are loaded. It measures the
 * work of a tick (from the start of {@code ServerTickEvent} to its end, so the sleep that pads a tick to 50 ms is not counted), prints one
 * {@code [UC-PERF]} line per fact and stops the server. It changes nothing in a normal run, and its numbers describe the machine they ran on only.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class PerfProbe {
    private static final boolean ENABLED = Boolean.getBoolean("uc.perfProbe");
    private static final int WARMUP = 300, WINDOW = 600;
    private static int ticks;
    private static long started;
    private static final long[] window = new long[WINDOW];
    private static int stage; // 0 warm-up, 1 baseline, 2 build, 3 with the district, 4 done
    private static int inStage;

    private PerfProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!ENABLED || stage >= 4) return;
        MinecraftServer server = event.getServer();
        if (event.phase == TickEvent.Phase.START) { started = System.nanoTime(); return; }
        long spent = System.nanoTime() - started;
        ticks++;
        switch (stage) {
            case 0 -> { if (ticks >= WARMUP) { stage = 1; inStage = 0; } }
            case 1 -> {
                window[inStage++] = spent;
                if (inStage == WINDOW) { report("idle world", window); stage = 2; }
            }
            case 2 -> {
                ServerLevel level = server.overworld();
                var district = DistrictCatalog.INSTANCE.find("founders_row");
                BlockPos ground = DevCommands.surface(level, level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getZ());
                long t0 = System.nanoTime();
                var result = DistrictBuilder.build(level, district, ground);
                System.out.println("[UC-PERF] founders_row built in " + (System.nanoTime() - t0) / 1_000_000 + " ms: " + result.blocks() + " blocks, " + result.citizens() + " citizens, "
                    + result.terminals() + " terminals, " + result.safes() + " safes");
                stage = 3; inStage = -100; // let the citizens settle for 100 ticks before measuring
            }
            case 3 -> {
                if (inStage++ < 0) return;
                window[inStage - 1] = spent;
                if (inStage == WINDOW) {
                    report("with Founders Row and its citizens", window);
                    long villagers = server.overworld().getAllEntities().spliterator().estimateSize();
                    System.out.println("[UC-PERF] entities loaded: " + villagers);
                    stage = 4;
                    server.halt(false);
                }
            }
            default -> { }
        }
    }

    private static void report(String what, long[] samples) {
        long[] sorted = samples.clone();
        java.util.Arrays.sort(sorted);
        double mean = 0;
        for (long s : samples) mean += s;
        mean /= samples.length;
        System.out.printf(java.util.Locale.ROOT, "[UC-PERF] %s: %d ticks, mean %.2f ms, median %.2f ms, p95 %.2f ms, p99 %.2f ms, max %.2f ms%n", what, samples.length,
            mean / 1e6, sorted[sorted.length / 2] / 1e6, sorted[(int) (sorted.length * 0.95)] / 1e6, sorted[(int) (sorted.length * 0.99)] / 1e6, sorted[sorted.length - 1] / 1e6);
    }
}
