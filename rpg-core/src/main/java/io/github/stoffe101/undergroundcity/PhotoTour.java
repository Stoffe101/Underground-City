package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in photographer for generated worlds ({@code -Duc.photoTour=<shots.json>}): in a world the development launcher created (or opened), flies a spectator camera to each listed
 * viewpoint, waits until the real client holds and has meshed the terrain, and takes the screenshot the way a player would see it (the framebuffer, hidden GUI by default). It exists
 * because geometry and block counts do not say whether a world looks right; the frames do. Nothing here changes a world: the player is a spectator and the weather and time are set.
 *
 * <p>The shot list is a JSON file: {@code {"shots": [{"name": "arrival-north", "x": -361, "z": -223, "dy": 6, "yaw": 90, "pitch": 12, "settle": 160, "gui": false, "hour": 6000}]}}.
 * {@code y} is optional (default: the generator's surface height at the spot plus {@code dy}, so a viewpoint need not know the terrain).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
public final class PhotoTour {
    private static final String LIST = System.getProperty("uc.photoTour");
    private static final long STAGE_LIMIT_NANOS = Long.getLong("uc.photoTour.limitSeconds", 420L) * 1_000_000_000L;
    private record Shot(String name, double x, Double y, double z, double dy, float yaw, float pitch, int settle, boolean gui, int hour) {}

    private static List<Shot> shots;
    private static int current, ticks, lastSections, stableTicks;
    private static boolean positioned, captureRequested, captured, finished;
    private static long stageStarted = System.nanoTime();
    private static double targetX, targetZ;

    private PhotoTour() {}

    private static List<Shot> shots() {
        if (shots != null) return shots;
        List<Shot> list = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(LIST))).getAsJsonObject();
            JsonArray array = root.getAsJsonArray("shots");
            for (var element : array) {
                JsonObject o = element.getAsJsonObject();
                list.add(new Shot(o.get("name").getAsString(), o.get("x").getAsDouble(), o.has("y") ? o.get("y").getAsDouble() : null, o.get("z").getAsDouble(),
                    o.has("dy") ? o.get("dy").getAsDouble() : 4, o.has("yaw") ? o.get("yaw").getAsFloat() : 0, o.has("pitch") ? o.get("pitch").getAsFloat() : 10,
                    o.has("settle") ? o.get("settle").getAsInt() : 160, o.has("gui") && o.get("gui").getAsBoolean(), o.has("hour") ? o.get("hour").getAsInt() : 6000));
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot read the photo tour list " + LIST, error);
        }
        if (list.isEmpty()) throw new IllegalStateException("The photo tour list is empty");
        return shots = List.copyOf(list);
    }

    private static int renderedSections(Minecraft mc) {
        try { return mc.levelRenderer == null ? 0 : mc.levelRenderer.countRenderedChunks(); } catch (NullPointerException notReady) { return 0; }
    }

    private static boolean meshesIdle(Minecraft mc) {
        try { return mc.levelRenderer != null && mc.levelRenderer.hasRenderedAllChunks(); } catch (NullPointerException notReady) { return false; }
    }

    /** Capture at the END of a render tick, when the framebuffer holds the finished frame. */
    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (LIST == null || finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (captureRequested && mc.screen == null && mc.player != null && mc.level != null) {
            captureRequested = false; captured = true;
            Shot shot = shots().get(current);
            Screenshot.grab(mc.gameDirectory, shot.name() + ".png", mc.getMainRenderTarget(), response -> System.out.println("[UC-PHOTO] " + response.getString()));
        }
        if (!captured || mc.screen != null) return;
        try {
            var file = mc.gameDirectory.toPath().resolve("screenshots").resolve(shots().get(current).name() + ".png");
            if (!Files.isRegularFile(file) || Files.size(file) < 1024) return;
            captured = false; positioned = false; ticks = 0; stageStarted = System.nanoTime(); lastSections = 0; stableTicks = 0;
            if (++current == shots().size()) {
                Files.writeString(mc.gameDirectory.toPath().resolve("photo-tour-complete.txt"), "shots=" + shots().size() + "\n");
                System.out.println("[UC-PHOTO] tour complete: " + shots().size() + " frames");
                finished = true; mc.stop();
            }
        } catch (IOException error) { throw new IllegalStateException("Photo tour screenshot failed", error); }
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (LIST == null || finished || captured || captureRequested || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) return;
        // the first thing a new citizen sees is the registration screen; a photographer is not a citizen, so any screen that opened by itself is closed
        if (mc.screen != null) { mc.setScreen(null); return; }
        Shot shot = shots().get(current);
        if (!positioned) {
            targetX = shot.x(); targetZ = shot.z();
            var playerId = mc.player.getUUID();
            if (current == 0) { mc.options.hideGui = !shot.gui(); mc.options.pauseOnLostFocus = false; mc.options.framerateLimit().set(30); }
            mc.options.hideGui = !shot.gui();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                if (player == null) throw new IllegalStateException("Photo tour player missing");
                var level = server.overworld();
                player.setGameMode(GameType.SPECTATOR);
                level.setDayTime(shot.hour());
                level.setWeatherParameters(6000, 0, false, false);
                double y;
                if (shot.y() != null) y = shot.y();
                else {
                    var generator = level.getChunkSource().getGenerator();
                    y = generator.getBaseHeight((int) shot.x(), (int) shot.z(), Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()) + shot.dy();
                }
                System.out.println("[UC-PHOTO] camera for " + shot.name() + ": " + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f yaw=%.0f pitch=%.0f", shot.x(), y, shot.z(), shot.yaw(), shot.pitch()));
                player.teleportTo(level, shot.x(), y, shot.z(), shot.yaw(), shot.pitch());
            });
            positioned = true; ticks = 0; stageStarted = System.nanoTime();
            return;
        }
        if (Math.abs(mc.player.getX() - targetX) > 2 || Math.abs(mc.player.getZ() - targetZ) > 2) return;
        if (++ticks < shot.settle() || !mc.level.getChunkSource().hasChunk((int) Math.floor(targetX / 16), (int) Math.floor(targetZ / 16))) return;
        int sections = renderedSections(mc);
        stableTicks = sections == lastSections ? stableTicks + 1 : 0; lastSections = sections;
        boolean ready = mc.level.getChunkSource().getLoadedChunksCount() >= 40 && meshesIdle(mc) && sections >= 20 && stableTicks >= 100;
        if (!ready && System.nanoTime() - stageStarted < STAGE_LIMIT_NANOS) return;
        System.out.println("[UC-PHOTO] capturing " + shot.name() + ": loadedChunks=" + mc.level.getChunkSource().getLoadedChunksCount() + ", renderedSections=" + sections
            + ", ready=" + ready + ", camera=" + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        captureRequested = true;
    }
}
