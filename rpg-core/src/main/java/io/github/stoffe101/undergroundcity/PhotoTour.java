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
    /** A building-relative shot names a furnished building and one of its spots instead of coordinates: {@code building} (its type), {@code nth} (which instance, nearest to the city centre first), {@code spot} (a kind) and {@code spot_nth}, a camera {@code back} blocks in front of the spot, {@code eye} blocks above the floor (the camera, not the feet), looking at it. */
    private record Shot(String name, double x, Double y, double z, double dy, float yaw, float pitch, int settle, boolean gui, int hour,
                        String building, int nth, String spot, int spotNth, double back, double eye, Float yawOverride, String place, double side, boolean safe) {}

    /** Where a safe camera stands: its feet, its view, and how many places were tried before it. */
    private record Camera(double x, double feetY, double z, float yaw, float pitch, int tried) {}

    private static List<Shot> shots;
    private static int current, ticks, lastSections, stableTicks;
    private static boolean positioned, captureRequested, captured, finished;
    /** A roadside place (D-138) is built only when somebody comes near its spot: the camera waits by the road until it stands. */
    private static volatile boolean placeBuilt;
    private static int placeTicks;
    private static long stageStarted = System.nanoTime();
    private static volatile double targetX, targetZ;
    private static volatile boolean aimed;
    /** A shot whose building this world did not generate near its city is skipped and named in the log (private tour 13 stopped at its eighth of 21 frames); how many were. */
    private static volatile boolean skipShot;
    private static int skipped;
    /** Where the instances of each building type were found (nearest to the city centre first), so a second shot of a building does not search again. */
    private static final java.util.Map<String, List<CityBuildings.Building>> FOUND = new java.util.HashMap<>();

    private PhotoTour() {}

    private static List<Shot> shots() {
        if (shots != null) return shots;
        List<Shot> list = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(Path.of(LIST))).getAsJsonObject();
            JsonArray array = root.getAsJsonArray("shots");
            for (var element : array) {
                JsonObject o = element.getAsJsonObject();
                list.add(new Shot(o.get("name").getAsString(), o.has("x") ? o.get("x").getAsDouble() : 0, o.has("y") ? o.get("y").getAsDouble() : null, o.has("z") ? o.get("z").getAsDouble() : 0,
                    o.has("dy") ? o.get("dy").getAsDouble() : 4, o.has("yaw") ? o.get("yaw").getAsFloat() : 0, o.has("pitch") ? o.get("pitch").getAsFloat() : 10,
                    o.has("settle") ? o.get("settle").getAsInt() : 160, o.has("gui") && o.get("gui").getAsBoolean(), o.has("hour") ? o.get("hour").getAsInt() : 6000,
                    o.has("building") ? o.get("building").getAsString() : null, o.has("nth") ? o.get("nth").getAsInt() : 0, o.has("spot") ? o.get("spot").getAsString() : "door",
                    o.has("spot_nth") ? o.get("spot_nth").getAsInt() : 0, o.has("back") ? o.get("back").getAsDouble() : 3.0, o.has("eye") ? o.get("eye").getAsDouble() : 1.62,
                    o.has("yaw") ? o.get("yaw").getAsFloat() : null, o.has("place") ? o.get("place").getAsString() : null, o.has("side") ? o.get("side").getAsDouble() : 0,
                    !o.has("safe") || o.get("safe").getAsBoolean()));
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot read the photo tour list " + LIST, error);
        }
        if (list.isEmpty()) throw new IllegalStateException("The photo tour list is empty");
        return shots = List.copyOf(list);
    }

    /** Finds the nth instance of a furnished building by asking Lost Cities, ring by ring around its city's centre (on the server thread; a first lookup of a chunk costs tens of milliseconds). */
    private static CityBuildings.Building locate(net.minecraft.server.level.ServerLevel level, Shot shot) {
        List<CityBuildings.Building> list = FOUND.computeIfAbsent(shot.building(), k -> new ArrayList<>());
        if (list.size() > shot.nth()) return list.get(shot.nth());
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(shot.building());
        if (plan == null) throw new IllegalStateException("No furnished building " + shot.building() + " is loaded (is the furnished package in this run?)");
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(plan.city())).findFirst().orElseThrow(() -> new IllegalStateException("No city " + plan.city()));
        list.clear();
        for (int ring = 0; ring <= 28 && list.size() <= shot.nth(); ring++) {
            for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                var found = CityBuildings.INSTANCE.ofChunk(level, region.centerChunkX() + dx, region.centerChunkZ() + dz);
                if (found.isPresent() && shot.building().equals(found.get().type())) list.add(found.get());
            }
        }
        if (list.size() <= shot.nth()) {
            System.out.println("[UC-PHOTO] " + shot.name() + ": found only " + list.size() + " of " + shot.building() + " within 28 chunks of " + plan.city() + "; skipping it");
            return null;
        }
        return list.get(shot.nth());
    }

    /**
     * A camera with a clear view of a spot (eleventh pass; seven of private tour 13's twenty frames showed a wall or the inside of a block): the place asked for first ({@code back}
     * blocks along the spot's facing, {@code eye} above its floor), then nearer, then from the seven other directions round it and at a person's eye height, until the camera stands
     * in open space with room round it (and, for a spot inside, under a roof) and nothing that hides what is behind it lies on the line to the spot. Null when no place is clear.
     */
    static Camera safeCamera(net.minecraft.server.level.ServerLevel level, double tx, double ty, double tz, net.minecraft.core.Direction facing, double back, double eye, double floor, boolean indoor) {
        double base = Math.toRadians(facing.toYRot());   // the facing as Minecraft's yaw: its step is x = -sin, z = cos (south is 0, west 90)
        int tried = 0;
        for (int turn : new int[] {0, 45, -45, 90, -90, 135, -135, 180}) {
            double angle = base + Math.toRadians(turn);
            double dx = -Math.sin(angle), dz = Math.cos(angle);
            for (double distance = Math.max(1.5, back); distance >= 1.5; distance -= 1.0) {
                for (double height : new double[] {eye, 1.62, 2.4}) {
                    double cx = tx + dx * distance, cz = tz + dz * distance, cy = floor + height;
                    tried++;
                    if (!open(level, BlockPos.containing(cx, cy, cz)) || !open(level, BlockPos.containing(cx, cy - 1.0, cz)) && height < 2) continue;
                    if (!roomy(level, cx, cy, cz) || indoor && !roofed(level, cx, cy, cz)) continue;
                    if (!clear(level, cx, cy, cz, tx, ty, tz)) continue;
                    double horizontal = Math.sqrt((tx - cx) * (tx - cx) + (tz - cz) * (tz - cz));
                    float yaw = (float) Math.toDegrees(Math.atan2(-(tx - cx), tz - cz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(ty - cy, horizontal));
                    return new Camera(cx, cy - 1.62, cz, yaw, pitch, tried);
                }
            }
        }
        return null;
    }

    /** A block a camera may stand in: nothing to collide with (air, a carpet's top, a torch). */
    private static boolean open(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Nearly half a block of room round the eye on every side: a camera against a wall shows the wall across half the frame (private tour 14's bath frame). */
    private static boolean roomy(net.minecraft.server.level.ServerLevel level, double cx, double cy, double cz) {
        return open(level, BlockPos.containing(cx + 0.45, cy, cz)) && open(level, BlockPos.containing(cx - 0.45, cy, cz))
            && open(level, BlockPos.containing(cx, cy, cz + 0.45)) && open(level, BlockPos.containing(cx, cy, cz - 0.45));
    }

    /** Something solid over the camera within eight blocks: an interior frame is taken from inside, never from the garden through a window (private tour 14's bath frame). */
    private static boolean roofed(net.minecraft.server.level.ServerLevel level, double cx, double cy, double cz) {
        BlockPos eye = BlockPos.containing(cx, cy, cz);
        for (int up = 1; up <= 8; up++) if (!open(level, eye.above(up))) return true;
        return false;
    }

    /**
     * Nothing that hides what is behind it on the line from the camera to the target (sampled every fifth of a block, the target's own block excluded): a block drawn solid, a pane or a
     * set of bars, or anything that fills its block (glass). Furniture that does not fill its block does not hide it.
     */
    private static boolean clear(net.minecraft.server.level.ServerLevel level, double cx, double cy, double cz, double tx, double ty, double tz) {
        BlockPos target = BlockPos.containing(tx, ty, tz);
        double length = Math.sqrt((tx - cx) * (tx - cx) + (ty - cy) * (ty - cy) + (tz - cz) * (tz - cz));
        int steps = Math.max(1, (int) Math.ceil(length / 0.2));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            BlockPos at = BlockPos.containing(cx + (tx - cx) * t, cy + (ty - cy) * t, cz + (tz - cz) * t);
            if (at.equals(target)) return true;
            var state = level.getBlockState(at);
            if (state.canOcclude() && state.isSolidRender(level, at) || state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
                || net.minecraft.world.level.block.Block.isShapeFullBlock(state.getCollisionShape(level, at))) return false;
        }
        return true;
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
            captured = false; positioned = false; ticks = 0; stageStarted = System.nanoTime(); lastSections = 0; stableTicks = 0; placeBuilt = false; placeTicks = 0;
            if (++current == shots().size()) finish(mc);
        } catch (IOException error) { throw new IllegalStateException("Photo tour screenshot failed", error); }
    }

    private static void finish(Minecraft mc) {
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve("photo-tour-complete.txt"), "shots=" + shots().size() + "\nskipped=" + skipped + "\n");
        } catch (IOException error) { throw new IllegalStateException("Photo tour could not record its end", error); }
        System.out.println("[UC-PHOTO] tour complete: " + (shots().size() - skipped) + " frames, " + skipped + " skipped");
        finished = true; mc.stop();
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (LIST == null || finished || captured || captureRequested || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (mc.player == null || mc.level == null || server == null) return;
        // the first thing a new citizen sees is the registration screen; a photographer is not a citizen, so any screen that opened by itself is closed
        if (mc.screen != null) { mc.setScreen(null); return; }
        Shot shot = shots().get(current);
        if (skipShot) {
            skipShot = false; skipped++;
            positioned = false; aimed = false; ticks = 0; stageStarted = System.nanoTime(); placeBuilt = false; placeTicks = 0;
            if (++current == shots().size()) finish(mc);
            return;
        }
        if (shot.place() != null && !placeBuilt) {
            if (placeTicks == 0) stageStarted = System.nanoTime();   // the stage starts now, not when the class loaded (photo tour 24's first place timed out while its world was created)
            if (placeTicks++ % 40 == 0) {
                var playerId = mc.player.getUUID();
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(playerId);
                    var level = server.overworld();
                    if (player == null) return;
                    player.setGameMode(GameType.SPECTATOR);
                    level.setDayTime(shot.hour());
                    // a photographer's world is a disposable one: give it the roadside places if its start did not
                    if (!RoadsidePlaces.enabled(level)) RoadsidePlaces.enable(level);
                    if (PlacedSites.get(level, RoadsidePlaces.KEY).where.containsKey(shot.place())) { placeBuilt = true; return; }
                    var place = RoadsidePlaces.CATALOG.find(shot.place());
                    if (place == null) throw new IllegalStateException("No roadside place " + shot.place());
                    if (PlacedSites.get(level, RoadsidePlaces.KEY).done.contains(shot.place())) { System.out.println("[UC-PHOTO] " + shot.name() + ": " + shot.place() + " was given up; skipping it"); placeBuilt = true; return; }
                    // stand above its first candidate spot (or its share of the road while the road is not planned yet) and let it be built
                    double[] at = null;
                    for (CorridorPlan plan : CorridorRoadFeature.ready(level)) if (plan.id().equals(place.road())) {
                        var spots = RoadsidePlaces.plan(plan, RoadsidePlaces.CATALOG.onRoad(plan.id())).get(place.id());
                        if (spots != null && !spots.isEmpty()) at = plan.pointAt(spots.get(0));
                    }
                    if (at == null) for (CorridorPlan plan : DevCommands.plans(server)) if (plan.id().equals(place.road())) {
                        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan);
                        at = plan.pointAt(first + place.at() * (last - first));
                    }
                    if (at == null) throw new IllegalStateException("No road " + place.road());
                    var generator = level.getChunkSource().getGenerator();
                    int y = generator.getBaseHeight((int) at[0], (int) at[1], Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()) + 24;
                    player.teleportTo(level, at[0], y, at[1], 0, 60);
                });
            }
            if (System.nanoTime() - stageStarted > STAGE_LIMIT_NANOS) { System.out.println("[UC-PHOTO] " + shot.name() + ": " + shot.place() + " was not built in time"); placeBuilt = true; }
            return;
        }
        if (!positioned) {
            targetX = shot.x(); targetZ = shot.z(); aimed = false;
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
                double x = shot.x(), z = shot.z(), y;
                float yaw = shot.yaw();
                Float pitchOf = null;
                if (shot.place() != null && PlacedSites.get(level, RoadsidePlaces.KEY).where.containsKey(shot.place())) {
                    // a roadside place: from in front of it (its front looks at the road), turned {@code side} degrees round it, {@code back} blocks beyond its front edge, {@code eye} above its ground
                    var place = RoadsidePlaces.CATALOG.find(shot.place());
                    var district = RoadsidePlaces.builtVariant(level, place);
                    BlockPos origin = BlockPos.of(PlacedSites.get(level, RoadsidePlaces.KEY).where.get(shot.place()));
                    String facingName = place.variants().entrySet().stream().filter(e -> e.getValue().equals(district.id())).map(java.util.Map.Entry::getKey).findFirst().orElse("north");
                    net.minecraft.core.Direction front = net.minecraft.core.Direction.byName(facingName);
                    double cx = origin.getX() + (district.min().getX() + district.max().getX() + 1) / 2.0, cz = origin.getZ() + (district.min().getZ() + district.max().getZ() + 1) / 2.0;
                    int[] size = place.size(facingName);
                    double half = (front.getAxis() == net.minecraft.core.Direction.Axis.Z ? size[1] : size[0]) / 2.0;
                    double angle = Math.toRadians(shot.side()), fx = front.getStepX(), fz = front.getStepZ();
                    double rx = fx * Math.cos(angle) - fz * Math.sin(angle), rz = fx * Math.sin(angle) + fz * Math.cos(angle);
                    x = cx + rx * (half + shot.back()); z = cz + rz * (half + shot.back());
                    y = origin.getY() + shot.eye() - 1.62;
                    yaw = (float) Math.toDegrees(Math.atan2(-(cx - x), cz - z));
                    System.out.println("[UC-PHOTO] " + shot.name() + ": " + shot.place() + " at " + origin.toShortString() + " facing " + facingName);
                } else if (shot.building() != null) {
                    var building = locate(level, shot);
                    if (building == null) { skipShot = true; return; }
                    var plan = BuildingSpots.INSTANCE.forType(shot.building());
                    var spots = plan.indexesOf(shot.spot());
                    if (spots.size() <= shot.spotNth()) throw new IllegalStateException(shot.building() + " has no " + shot.spot() + " number " + shot.spotNth());
                    var spot = plan.spots().get(spots.get(shot.spotNth()));
                    net.minecraft.core.Direction facing = net.minecraft.core.Direction.byName(spot.facing());
                    if (facing == null || facing.getAxis().isVertical()) facing = net.minecraft.core.Direction.SOUTH;
                    double tx = building.chunkX() * 16 + spot.x() + 0.5, tz = building.chunkZ() * 16 + spot.z() + 0.5, floor = building.streetY() + spot.y();
                    System.out.println("[UC-PHOTO] " + shot.name() + ": " + shot.building() + " #" + shot.nth() + " at chunk " + building.chunkX() + "," + building.chunkZ() + " street y " + building.streetY() + ", spot " + spot.kind() + " " + spot.x() + "," + spot.y() + "," + spot.z());
                    // the chunks round the spot first: a camera is judged by the blocks it would see, so they must be there
                    for (int cx = (int) Math.floor(tx / 16) - 1; cx <= (int) Math.floor(tx / 16) + 1; cx++) for (int cz = (int) Math.floor(tz / 16) - 1; cz <= (int) Math.floor(tz / 16) + 1; cz++) level.getChunk(cx, cz);
                    Camera camera = shot.safe() ? safeCamera(level, tx, floor + 0.8, tz, facing, shot.back(), shot.eye(), floor, !shot.spot().equals("door")) : null;
                    if (camera != null) {
                        x = camera.x(); z = camera.z(); y = camera.feetY(); yaw = camera.yaw(); pitchOf = camera.pitch();
                        System.out.println("[UC-PHOTO] " + shot.name() + ": a clear view after " + camera.tried() + " place(s) tried");
                    } else {
                        x = tx + facing.getStepX() * shot.back();
                        z = tz + facing.getStepZ() * shot.back();
                        // the player's position is their feet; the camera is 1.62 above them, so a camera {@code eye} above the floor needs the feet at eye - 1.62 above it
                        y = floor + shot.eye() - 1.62;
                        if (shot.yawOverride() == null) yaw = facing.getOpposite().toYRot();
                        if (shot.safe()) System.out.println("[UC-PHOTO] " + shot.name() + ": no clear view of the spot was found; the frame is taken from in front of it as asked");
                    }
                    for (int cx = (int) Math.floor(x / 16) - 1; cx <= (int) Math.floor(x / 16) + 1; cx++) for (int cz = (int) Math.floor(z / 16) - 1; cz <= (int) Math.floor(z / 16) + 1; cz++) level.getChunk(cx, cz);
                } else if (shot.y() != null) y = shot.y();
                else {
                    var generator = level.getChunkSource().getGenerator();
                    y = generator.getBaseHeight((int) x, (int) z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()) + shot.dy();
                }
                targetX = x; targetZ = z;
                float pitch = pitchOf != null ? pitchOf : shot.pitch();
                System.out.println("[UC-PHOTO] camera for " + shot.name() + ": " + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f yaw=%.0f pitch=%.0f", x, y, z, yaw, pitch));
                player.teleportTo(level, x, y, z, yaw, pitch);
                aimed = true;
            });
            positioned = true; ticks = 0; stageStarted = System.nanoTime();
            return;
        }
        if (!aimed || Math.abs(mc.player.getX() - targetX) > 2 || Math.abs(mc.player.getZ() - targetZ) > 2) return;
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
