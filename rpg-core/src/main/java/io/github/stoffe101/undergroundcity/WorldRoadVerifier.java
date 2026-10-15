package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Part of the disposable-world survey: forces the chunks along every built road to generate (asynchronously, so the
 * server tick never blocks), then walks the REAL blocks of each centreline, comparing them with what a walker, horse or
 * boat needs: a deck block, three free blocks of headroom, no step above half a block between neighbours, parapets on
 * bridges and open water with clearance under them. It reports measurements; it never builds anything.
 */
final class WorldRoadVerifier {
    private static final long TIMEOUT_MILLIS = 12 * 60_000L;
    private static final TicketType<ChunkPos> ROAD_TICKET = TicketType.create("underground_city_road_survey",
        java.util.Comparator.comparingLong(ChunkPos::toLong));
    private enum Phase { INIT, LOADING, DONE }
    private Phase phase = Phase.INIT;
    private final Set<Long> needed = new LinkedHashSet<>();
    private final JsonObject result = new JsonObject();
    private long started, lastLog;

    /** Returns true while chunks are still generating. */
    boolean step(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (phase == Phase.INIT) {
            started = System.currentTimeMillis();
            var feature = server.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "corridor_roads"));
            if (feature == null || !(feature.config() instanceof CorridorRoadConfig config)) {
                throw new IllegalStateException("The corridor road feature is not part of the loaded datapack");
            }
            for (CorridorPlan plan : config.plans()) enumerate(plan);
            // ServerLevel.setChunkForced generates synchronously on the server thread and trips the watchdog; a ticket
            // lets the worker threads generate the chunks while ticks continue.
            for (long key : needed) {
                ChunkPos chunk = new ChunkPos(key);
                level.getChunkSource().addRegionTicket(ROAD_TICKET, chunk, 0, chunk);
            }
            System.out.println("[UC-WORLD] Generating " + needed.size() + " chunks along the roads");
            phase = Phase.LOADING;
            return true;
        }
        if (phase == Phase.LOADING) {
            int missing = 0;
            for (long key : needed) if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) missing++;
            if (missing > 0) {
                if (System.currentTimeMillis() - lastLog > 30_000) {
                    lastLog = System.currentTimeMillis();
                    System.out.println("[UC-WORLD] Road chunks still generating: " + missing + " of " + needed.size());
                }
                if (System.currentTimeMillis() - started > TIMEOUT_MILLIS) throw new IllegalStateException("Road chunks did not finish generating: " + missing + " missing");
                return true;
            }
            verify(level);
            phase = Phase.DONE;
        }
        return false;
    }

    JsonObject report() { return result; }

    /** Chunks under the deck, the parapets and the cleared band along the whole open centreline. */
    private void enumerate(CorridorPlan plan) {
        for (double s = 0; s <= plan.length(); s += 2) {
            if (!plan.openAt(s)) continue;
            double[] a = plan.pointAt(Math.max(0, s - 1)), b = plan.pointAt(Math.min(plan.length(), s + 1));
            double dx = b[0] - a[0], dz = b[1] - a[1], norm = Math.hypot(dx, dz);
            if (norm == 0) continue;
            double nx = -dz / norm, nz = dx / norm;
            double[] c = plan.pointAt(s);
            for (double offset = -5; offset <= 5; offset += 2.5) {
                needed.add(ChunkPos.asLong((int) Math.floor(c[0] + nx * offset) >> 4, (int) Math.floor(c[1] + nz * offset) >> 4));
            }
        }
    }

    private static void problem(JsonArray problems, String kind, double s, int x, int y, int z, BlockState state) {
        if (problems.size() >= 12) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("kind", kind);
        entry.addProperty("s", Math.round(s));
        entry.addProperty("at", x + "," + y + "," + z);
        entry.addProperty("block", net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
        problems.add(entry);
    }

    private void verify(ServerLevel level) {
        List<CorridorPlan> plans = CorridorRoadFeature.plans(level);
        if (plans.isEmpty()) throw new IllegalStateException("Road chunks generated but the feature never built a plan");
        JsonArray routes = new JsonArray();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (CorridorPlan plan : plans) {
            int samples = 0, deckMissing = 0, headroomBlocked = 0, bigSteps = 0, bridgeSamples = 0, waterSamples = 0;
            int railExpected = 0, railMissing = 0, minClearance = Integer.MAX_VALUE;
            int waterRun = 0, longestWater = 0, bridgeRun = 0, longestBridge = 0, lowestDeck = Integer.MAX_VALUE, highestDeck = Integer.MIN_VALUE;
            double previousStand = Double.NaN;
            JsonArray problems = new JsonArray();
            double runStartS = -1, bestFrom = -1, bestTo = -1;
            int bestRun = 0, landRun = 0, bestLand = 0;
            double landFrom = -1, bestLandFrom = -1, bestLandTo = -1;
            StringBuilder strip = new StringBuilder();
            for (double s = 0; s <= plan.length(); s += 1) {
                if (!plan.openAt(s)) { previousStand = Double.NaN; waterRun = 0; bridgeRun = 0; continue; }
                double[] c = plan.pointAt(s);
                int x = (int) Math.floor(c[0]), z = (int) Math.floor(c[1]);
                CorridorPlan.Hit hit = plan.nearest(x + 0.5, z + 0.5);
                if (hit == null) continue;
                int half = plan.deckHalfAt(hit.s());
                int deckY = CorridorPlan.deckBlockY(half);
                boolean slab = CorridorPlan.isSlab(half);
                BlockState deck = level.getBlockState(pos.set(x, deckY, z));
                if (!(slab ? deck.is(Blocks.POLISHED_ANDESITE_SLAB) : deck.is(Blocks.POLISHED_ANDESITE))) {
                    deckMissing++;
                    previousStand = Double.NaN;
                    problem(problems, "deck_missing", s, x, deckY, z, deck);
                    continue;
                }
                samples++;
                lowestDeck = Math.min(lowestDeck, deckY);
                highestDeck = Math.max(highestDeck, deckY);
                boolean free = true;
                for (int y = deckY + 1; y <= deckY + 3; y++) {
                    BlockState above = level.getBlockState(pos.set(x, y, z));
                    if (above.blocksMotion() || !above.getFluidState().isEmpty()) free = false;
                }
                if (!free) {
                    headroomBlocked++;
                    for (int y = deckY + 1; y <= deckY + 3; y++) {
                        BlockState above = level.getBlockState(pos.set(x, y, z));
                        if (above.blocksMotion() || !above.getFluidState().isEmpty()) { problem(problems, "headroom", s, x, y, z, above); break; }
                    }
                }
                double stand = deckY + (slab ? 0.5 : 1.0);
                if (!Double.isNaN(previousStand) && Math.abs(stand - previousStand) > 0.5 + 1e-9) {
                    bigSteps++;
                    problem(problems, "step", s, x, deckY, z, deck);
                }
                previousStand = stand;
                boolean bridgeHere = plan.bridgeAt(hit.s());
                if (((int) s) % 8 == 0) strip.append(bridgeHere ? 'B' : 'r');
                if (bridgeHere) {
                    bridgeSamples++;
                    bridgeRun++;
                    longestBridge = Math.max(longestBridge, bridgeRun);
                    // Look under the deck beside the centre line, where no pier stands.
                    double[] a = plan.pointAt(Math.max(0, s - 1)), b = plan.pointAt(Math.min(plan.length(), s + 1));
                    double dx = b[0] - a[0], dz = b[1] - a[1], norm = Math.max(1e-9, Math.hypot(dx, dz));
                    double nx = -dz / norm, nz = dx / norm;
                    int sideX = (int) Math.floor(c[0] + nx * 2.5);
                    int sideZ = (int) Math.floor(c[1] + nz * 2.5);
                    boolean water = false;
                    int y = deckY - 2;
                    while (y > level.getMinBuildHeight()) {
                        BlockState below = level.getBlockState(pos.set(sideX, y, sideZ));
                        if (!below.getFluidState().isEmpty()) { water = true; break; }
                        if (below.blocksMotion()) break;
                        y--;
                    }
                    if (water) {
                        if (((int) s) % 8 == 0) strip.setCharAt(strip.length() - 1, 'W');
                        waterSamples++;
                        if (waterRun == 0) runStartS = s;
                        waterRun++;
                        longestWater = Math.max(longestWater, waterRun);
                        if (waterRun > bestRun) { bestRun = waterRun; bestFrom = runStartS; bestTo = s; }
                        minClearance = Math.min(minClearance, deckY - 2 - y);
                    } else {
                        waterRun = 0;
                    }
                    for (int side = -1; side <= 1; side += 2) {
                        int railX = (int) Math.floor(c[0] + nx * 4.0 * side), railZ = (int) Math.floor(c[1] + nz * 4.0 * side);
                        CorridorPlan.Hit railHit = plan.nearest(railX + 0.5, railZ + 0.5);
                        if (railHit == null || Math.abs(railHit.u()) < plan.deckHalfWidth() || Math.abs(railHit.u()) >= plan.deckHalfWidth() + 1.0
                            || !plan.bridgeAt(railHit.s())) continue;
                        railExpected++;
                        int railDeckY = CorridorPlan.deckBlockY(plan.deckHalfAt(railHit.s()));
                        if (!level.getBlockState(pos.set(railX, railDeckY + 1, railZ)).is(Blocks.STONE_BRICK_WALL)) railMissing++;
                    }
                    landRun = 0;
                } else {
                    waterRun = 0;
                    bridgeRun = 0;
                    if (landRun == 0) landFrom = s;
                    landRun++;
                    if (landRun > bestLand) { bestLand = landRun; bestLandFrom = landFrom; bestLandTo = s; }
                }
            }
            JsonObject route = new JsonObject();
            route.addProperty("id", plan.id());
            route.addProperty("length_blocks", Math.round(plan.length()));
            route.addProperty("samples_checked", samples + deckMissing);
            route.addProperty("sample_spacing_blocks", 1);
            route.addProperty("deck_missing", deckMissing);
            route.addProperty("headroom_blocked", headroomBlocked);
            route.addProperty("steps_over_half_block", bigSteps);
            route.addProperty("bridge_samples", bridgeSamples);
            route.addProperty("longest_bridge_blocks", longestBridge);
            route.addProperty("water_samples_below_deck", waterSamples);
            route.addProperty("longest_water_span_blocks", longestWater);
            route.addProperty("min_free_blocks_over_water", minClearance == Integer.MAX_VALUE ? -1 : minClearance);
            route.addProperty("parapets_expected", railExpected);
            route.addProperty("parapets_missing", railMissing);
            route.addProperty("deck_y_min", lowestDeck == Integer.MAX_VALUE ? -1 : lowestDeck);
            route.addProperty("deck_y_max", highestDeck == Integer.MIN_VALUE ? -1 : highestDeck);
            route.addProperty("profile_max_step_half_blocks", plan.maxProfileStep());
            // Where the road meets the city circles, for hand testing: walk from the first point to the last.
            JsonArray runs = new JsonArray();
            double runFrom = -1;
            for (double s = 0; s <= plan.length() + 1; s += 1) {
                boolean isOpen = s <= plan.length() && plan.openAt(s);
                if (isOpen && runFrom < 0) runFrom = s;
                if (!isOpen && runFrom >= 0) {
                    double[] from = plan.pointAt(runFrom), to = plan.pointAt(s - 1);
                    JsonObject run = new JsonObject();
                    run.addProperty("from", Math.round(from[0]) + "," + Math.round(from[1]));
                    run.addProperty("to", Math.round(to[0]) + "," + Math.round(to[1]));
                    run.addProperty("length_blocks", Math.round(s - 1 - runFrom));
                    runs.add(run);
                    runFrom = -1;
                }
            }
            route.add("open_runs", runs);
            route.addProperty("strip_8_blocks_per_char", strip.toString());
            if (bestLand > 0) {
                // The longest stretch built on the ground, for a walker's-eye frame of the plain road.
                double mid = (bestLandFrom + bestLandTo) / 2;
                double[] c = plan.pointAt(mid), a = plan.pointAt(Math.max(0, mid - 2)), b = plan.pointAt(Math.min(plan.length(), mid + 2));
                JsonObject land = new JsonObject();
                land.addProperty("x", Math.round(c[0] * 10) / 10.0);
                land.addProperty("z", Math.round(c[1] * 10) / 10.0);
                land.addProperty("deck_y", CorridorPlan.deckBlockY(plan.deckHalfAt(mid)) + 1);
                land.addProperty("heading_dx", Math.round((b[0] - a[0]) * 1000) / 1000.0);
                land.addProperty("heading_dz", Math.round((b[1] - a[1]) * 1000) / 1000.0);
                land.addProperty("span_blocks", bestLand);
                route.add("land_showcase", land);
            }
            if (bestRun > 0) {
                // Where a camera should look: the middle of the widest water crossing, with the direction of travel.
                double mid = (bestFrom + bestTo) / 2;
                double[] c = plan.pointAt(mid), a = plan.pointAt(Math.max(0, mid - 2)), b = plan.pointAt(Math.min(plan.length(), mid + 2));
                JsonObject showcase = new JsonObject();
                showcase.addProperty("x", Math.round(c[0] * 10) / 10.0);
                showcase.addProperty("z", Math.round(c[1] * 10) / 10.0);
                showcase.addProperty("deck_y", CorridorPlan.deckBlockY(plan.deckHalfAt(mid)) + 1);
                showcase.addProperty("heading_dx", Math.round((b[0] - a[0]) * 1000) / 1000.0);
                showcase.addProperty("heading_dz", Math.round((b[1] - a[1]) * 1000) / 1000.0);
                showcase.addProperty("span_blocks", bestRun);
                route.add("showcase", showcase);
            }
            route.add("first_problems", problems);
            route.addProperty("continuous", deckMissing == 0 && headroomBlocked == 0 && bigSteps == 0 && samples > 0);
            routes.add(route);
            System.out.println("[UC-WORLD] Road evidence: " + route);
        }
        result.add("routes", routes);
        result.addProperty("chunks_generated", needed.size());
        result.addProperty("seconds", (System.currentTimeMillis() - started) / 1000);
        result.addProperty("method", "forced chunk generation, then blocks read along each centreline every 2 blocks");
    }
}
