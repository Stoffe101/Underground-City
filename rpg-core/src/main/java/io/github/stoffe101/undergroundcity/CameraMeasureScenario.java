package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What viewing a SecurityCraft camera costs the server, measured in a real client (the brief's camera question, D-151): {@code -PucPlaytest=sccamera} with the {@code securitycraft} set. Core fits
 * the trial's devices to an Ironhaven tenement (an operator's install), the player goes 800 blocks away so the camera's surroundings unload, and then views the camera remotely through
 * SecurityCraft's own {@code SecurityCameraBlock.mountCamera} (what its camera monitor calls on the server). For twenty seconds, once a second: the chunks the server holds, the chunks generated for the
 * first time, and the longest and average tick. Then {@code SecurityCamera.stopViewing}, and the same once more while the chunks are released.
 *
 * <p>Scripted: the registration, the install, the trip away and the mount (the monitor's own screen is not clicked through; the server call is the one the screen makes). It measures the server's
 * work, not a person's experience of the feed.
 */
final class CameraMeasureScenario {
    private CameraMeasureScenario() {}

    private static final class Facts {
        volatile SecurityCraftTrial.Install install;
        volatile String error;
        volatile boolean ready;
        volatile Object camera;
        volatile long mountMillis;
        volatile int viewDistance, baseline, peak, generatedWhileViewing, generatedBefore;
    }

    private static final AtomicInteger GENERATED = new AtomicInteger();
    private static volatile boolean listening;

    private static MinecraftServer server() { return Minecraft.getInstance().getSingleplayerServer(); }
    private static ServerPlayer me() { return server().getPlayerList().getPlayer(Minecraft.getInstance().player.getUUID()); }

    /** The longest and the average of the last {@code n} ticks, in milliseconds. */
    private static double[] ticks(MinecraftServer server, int n) {
        long[] times = server.tickTimes;
        int count = Math.min(n, times.length);
        long tick = server.getTickCount();
        double max = 0, sum = 0;
        for (int i = 0; i < count; i++) {
            double ms = times[(int) Math.floorMod(tick - 1 - i, times.length)] / 1_000_000.0;
            max = Math.max(max, ms);
            sum += ms;
        }
        return new double[]{max, count == 0 ? 0 : sum / count};
    }

    private static String state(String when) {
        return JourneyScenario.server(() -> {
            MinecraftServer server = server();
            ServerLevel level = server.overworld();
            double[] t = ticks(server, 20);
            return String.format(Locale.ROOT, "CAMERA %s: %d chunks held, %d generated for the first time since the start, the last second's ticks: longest %.1f ms, average %.1f ms",
                when, level.getChunkSource().getLoadedChunksCount(), GENERATED.get(), t[0], t[1]);
        });
    }

    static void build() {
        Facts f = new Facts();
        Playtest.keepScreens = true;
        if (!listening) {
            listening = true;
            MinecraftForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> { if (event.isNewChunk() && event.getLevel() instanceof ServerLevel) GENERATED.incrementAndGet(); });
        }
        Playtest.queue("the client has the ground under the player", () -> { }, Scenarios::groundBelowPlayer, 1200);
        Playtest.queue("the first-login registration screen opens by itself", () -> { }, () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 900);
        Playtest.queue("a registered Ironhaven farmer (by command)", () -> {
            Minecraft.getInstance().setScreen(null);
            Playtest.command("gamemode survival");
            Playtest.command("uc register ironhaven farmer");
        });
        Playtest.wait("let the server answer", 40);
        Playtest.queue("fit the trial's devices to an Ironhaven tenement (server thread)", () -> server().execute(() -> {
            try {
                if (!SecurityCraftTrial.loaded()) { f.error = "SecurityCraft is not loaded: run with the securitycraft set"; return; }
                ServerLevel level = server().overworld();
                DoorWalk.Spot spot = DoorWalk.find(level, true, "flat_block", b -> b.security() != null && b.security().response() != null).orElse(null);
                if (spot == null) { f.error = "no tenement with a street door near Ironhaven's centre"; return; }
                try { f.install = SecurityCraftTrial.install(level, spot.door()); }
                catch (IllegalStateException refused) { f.error = "the install was refused: " + refused.getMessage(); }
            } finally { f.ready = true; }
        }), () -> f.ready, 24000);
        Playtest.queue("the camera", () -> {
            if (f.install == null || f.install.cameras.isEmpty()) { Playtest.fail(f.error != null ? f.error : "no camera was fitted"); Playtest.finish(); return; }
            Playtest.log("CAMERA a SecurityCraft camera at " + f.install.cameras.get(0).toShortString() + " in " + f.install.city + "; the server's view distance " + JourneyScenario.server(() -> server().getPlayerList().getViewDistance()));
        });
        Playtest.queue("go 800 blocks away, so that the camera's surroundings unload (scripted)", () -> {
            BlockPos cam = f.install.cameras.get(0);
            Playtest.command("gamemode spectator");
            Playtest.command(String.format(Locale.ROOT, "tp @s %d 200 %d", cam.getX() + 800, cam.getZ()));
        });
        Playtest.wait("the camera's chunks unload and the server settles", 20 * 30);
        Playtest.queue("baseline", () -> {
            f.baseline = JourneyScenario.server(() -> server().overworld().getChunkSource().getLoadedChunksCount());
            f.generatedBefore = GENERATED.get();
            Playtest.log(state("before viewing, 800 blocks from the camera"));
        });

        Playtest.queue("view the camera remotely, as SecurityCraft's camera monitor does (SecurityCameraBlock.mountCamera on the server)", () -> {
            String outcome = JourneyScenario.server(() -> {
                ServerLevel level = server().overworld();
                BlockPos cam = f.install.cameras.get(0);
                f.viewDistance = server().getPlayerList().getViewDistance();
                try {
                    Object block = level.getBlockState(cam).getBlock();
                    var mount = block.getClass().getMethod("mountCamera", Level.class, BlockPos.class, Player.class);
                    long started = System.nanoTime();
                    mount.invoke(block, level, cam, me());
                    f.mountMillis = (System.nanoTime() - started) / 1_000_000L;
                    f.camera = me().getCamera();
                    return "mounted in " + f.mountMillis + " ms; the player's camera is now " + f.camera.getClass().getSimpleName();
                } catch (ReflectiveOperationException | RuntimeException error) { return "failed: " + error; }
            });
            Playtest.log("CAMERA " + outcome);
            if (outcome.startsWith("failed")) { Playtest.fail("the camera could not be viewed: " + outcome); Playtest.finish(); }
        });
        for (int second = 1; second <= 20; second++) {
            final int s = second;
            Playtest.wait("a second of viewing", 20);
            Playtest.queue("viewing, second " + s, () -> {
                int held = JourneyScenario.server(() -> server().overworld().getChunkSource().getLoadedChunksCount());
                f.peak = Math.max(f.peak, held);
                Playtest.log(state("viewing, second " + s));
            });
            if (second == 5) {
                Playtest.queue("screenshot of the feed", () -> Playtest.screenshotNextFrame("uc-sc-camera-feed"));
                Playtest.wait("frame", 8);
            }
        }
        Playtest.queue("stop viewing (SecurityCamera.stopViewing)", () -> {
            String outcome = JourneyScenario.server(() -> {
                try {
                    f.camera.getClass().getMethod("stopViewing", ServerPlayer.class).invoke(f.camera, me());
                    return "stopped";
                } catch (ReflectiveOperationException | RuntimeException error) { return "failed: " + error; }
            });
            f.generatedWhileViewing = GENERATED.get();
            Playtest.log("CAMERA " + outcome);
        });
        for (int second = 1; second <= 15; second++) {
            final int s = second;
            Playtest.wait("a second after", 20);
            Playtest.queue("after viewing, second " + s, () -> Playtest.log(state("after viewing, second " + s)));
        }
        Playtest.queue("summary", () -> {
            int expected = (2 * f.viewDistance + 1) * (2 * f.viewDistance + 1);
            Playtest.log(String.format(Locale.ROOT, "CAMERA summary: view distance %d, so SecurityCraft forces %d chunks round the camera; the server held %d chunks before and at most %d while one player viewed it (+%d); %d chunks were generated for the first time while it was viewed; the mount call took %d ms",
                f.viewDistance, expected, f.baseline, f.peak, f.peak - f.baseline, f.generatedWhileViewing - f.generatedBefore, f.mountMillis));
        });
    }
}
