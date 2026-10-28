package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Opt-in measurement of real generated city chunks ({@code -Duc.cityProbe=<report.json>}, disposable dedicated server only). It generates the chunks around each city centre one per
 * server tick (so the watchdog is never the thing that stops it), asks Lost Cities what each chunk is, and reads the real blocks: where vegetation stands and on what, where the doors,
 * windows and containers are, and how high the built volume really reaches. This is the evidence behind two October 20 decisions: which vegetation is wrong in a city (the owner's
 * "saplings, trees and vegetation inside or on top of buildings and roads") and what a protected building's volume is (D-105). It is a probe, not a gate: it writes numbers, it never fails a build.
 *
 * <p>Properties: {@code uc.cityProbe.radius} (chunks around each city centre, default 6), {@code uc.cityProbe.cities} (comma list), {@code uc.cityProbe.extra} (a list of {@code x:z} chunk
 * coordinates, for example the arrival site).
 */
public final class CityProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT = System.getProperty("uc.cityProbe");
    private final List<int[]> queue = new ArrayList<>();
    /** Every chunk probed, for the homes report (-PucCpHomes=true). */
    private final List<int[]> probed = new ArrayList<>();
    private final JsonArray chunks = new JsonArray();
    private final Map<String, int[]> totals = new TreeMap<>();
    private long started;
    private boolean prepared, finished;

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank()) return;
        started = System.nanoTime();
        // the registered blocks of some namespaces with every block state property and its values: the ground truth that a palette is checked against (scripts/validate_city_blocks.py)
        String registryOut = System.getProperty("uc.cityProbe.registry", "");
        if (!registryOut.isBlank()) writeRegistry(Path.of(registryOut), System.getProperty("uc.cityProbe.namespaces", "modernlife,minecraft"));
        // which data packs carry the same file, lowest priority first: the answer to "whose citystyle does Lost Cities read?"
        for (String path : System.getProperty("uc.cityProbe.stack", "").split(",")) {
            if (path.isBlank()) continue;
            var location = net.minecraft.resources.ResourceLocation.tryParse(path.trim());
            if (location == null) continue;
            var stack = event.getServer().getResourceManager().getResourceStack(location);
            LOGGER.info("[UC-PROBE] resource stack of {}: {}", location, stack.stream().map(r -> r.sourcePackId()).toList());
        }
    }

    private static void writeRegistry(Path file, String namespaces) {
        java.util.Set<String> wanted = new java.util.HashSet<>(java.util.Arrays.asList(namespaces.split(",")));
        JsonObject all = new JsonObject();
        for (var entry : ForgeRegistries.BLOCKS.getEntries()) {
            var key = entry.getKey().location();
            if (!wanted.contains(key.getNamespace())) continue;
            JsonObject properties = new JsonObject();
            for (var property : entry.getValue().getStateDefinition().getProperties()) {
                JsonArray values = new JsonArray();
                for (var value : property.getPossibleValues()) values.add(net.minecraft.Util.getPropertyName(property, value));
                properties.add(property.getName(), values);
            }
            all.add(key.toString(), properties);
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, all + "\n", StandardCharsets.UTF_8);
            LOGGER.info("[UC-PROBE] wrote {} registered blocks to {}", all.size(), file);
        } catch (IOException error) {
            LOGGER.error("[UC-PROBE] could not write the block registry: {}", error.toString());
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank() || finished || started == 0 || event.phase != TickEvent.Phase.END) return;
        ServerLevel level = event.getServer().overworld();
        if (!LostCitiesLink.ready()) return;
        if (!prepared) { prepare(level); prepared = true; }
        for (int n = 0; n < 2 && !queue.isEmpty(); n++) {
            int[] at = queue.remove(queue.size() - 1);
            probed.add(at);
            try { chunks.add(probe(level, at[0], at[1])); }
            catch (RuntimeException error) { LOGGER.error("[UC-PROBE] chunk {},{} failed: {}", at[0], at[1], error.toString()); }
        }
        if (!queue.isEmpty()) return;
        JsonObject report = new JsonObject();
        report.addProperty("generator", level.getChunkSource().getGenerator().getClass().getName());
        report.addProperty("seconds", (System.nanoTime() - started) / 1_000_000_000L);
        report.addProperty("flattened_lots_left_empty", CityCharacter.flattenedLeftEmpty);
        report.addProperty("railway_lots_left_empty", CityCharacter.railwayLotsLeftEmpty);
        report.addProperty("multi_building_chunks_kept", CityCharacter.multiKept);
        report.addProperty("multi_building_chunks_swapped_for_the_citys", CityCharacter.multiSwapped);
        report.addProperty("multi_building_chunks_flattened_for_a_railway", CityCharacter.multiRailFlattened);
        JsonObject summary = new JsonObject();
        totals.forEach((key, counts) -> { JsonObject o = new JsonObject(); o.addProperty("chunks", counts[0]); o.addProperty("with_vegetation_on_structure", counts[1]); o.addProperty("with_tree_in_city", counts[2]); summary.add(key, o); });
        report.add("summary", summary);
        if (Boolean.parseBoolean(System.getProperty("uc.cityProbe.homes", "false"))) report.add("homes", homes(level));
        report.addProperty("city_ground", CityGround.status());
        if (GenerationTrace.ON) report.add("guarded_writes_by_feature", GenerationTrace.totals());
        JsonArray legend = new JsonArray();
        SURFACE_LEGEND.forEach(legend::add);
        report.add("surface_legend", legend);
        report.add("chunks", chunks);
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the city probe", error); }
        LOGGER.info("[UC-PROBE] {} chunks probed in {} s, written to {}", chunks.size(), (System.nanoTime() - started) / 1_000_000_000L, OUTPUT);
        finished = true;
        event.getServer().halt(false);
    }

    /**
     * The homes of the probed buildings (D-206), counts only: every to-let place registered as the Home page would, then what the ledger holds. A home of several boxes, one over
     * several chunks, and whether each multi-chunk building's chunks agreed on one corner and one layout (a home is registered across chunks only then).
     */
    private JsonObject homes(ServerLevel level) {
        JsonObject out = new JsonObject();
        int buildings = 0, multiParts = 0, multiPartsWithPlaces = 0, registered = 0;
        java.util.Set<String> instances = new java.util.HashSet<>(), lettable = new java.util.HashSet<>();
        for (int[] at : probed) {
            var found = CityBuildings.INSTANCE.ofChunk(level, at[0], at[1]);
            if (found.isEmpty()) continue;
            buildings++;
            CityBuildings.Building b = found.get();
            if (b.multiPart()) {
                multiParts++;
                String instance = b.multi().id() + "@" + b.cornerX() + "," + b.cornerZ();
                instances.add(instance);
                if (!CityHomes.places(level.getSeed(), b).isEmpty()) { multiPartsWithPlaces++; lettable.add(instance); }
            }
            try { registered += CityHomes.ensureBuilding(level, b); }
            catch (RuntimeException error) { LOGGER.warn("[UC-PROBE] homes of a building at {},{} failed: {}", at[0], at[1], error.toString()); }
        }
        CrewLedger ledger = CrewService.ledger(level.getServer());
        int homes = 0, manyBoxes = 0, manyChunks = 0, maxBoxes = 0, outsideTheirBuilding = 0;
        if (ledger != null) {
            for (CrewLedger.Residence r : ledger.allResidences()) {
                if (!r.id.startsWith(CityHomes.PREFIX)) continue;
                homes++;
                if (r.boxes.size() > 1) manyBoxes++;
                if (r.chunks().size() > 1) manyChunks++;
                maxBoxes = Math.max(maxBoxes, r.boxes.size());
                // every chunk a home lies in is a chunk of the building it names (its multi-chunk building's corner, or the lot itself)
                String prefix = r.id.substring(0, r.id.lastIndexOf(':') + 1);
                for (var c : r.chunks()) {
                    var there = CityBuildings.INSTANCE.ofChunk(level, c.x, c.z);
                    if (there.isEmpty() || !CityHomes.buildingPrefix(there.get()).equals(prefix)) { outsideTheirBuilding++; break; }
                }
            }
        }
        out.addProperty("buildings", buildings);
        out.addProperty("multi_chunk_parts", multiParts);
        out.addProperty("multi_chunk_buildings", instances.size());
        out.addProperty("multi_chunk_buildings_with_dwellings", lettable.size());
        out.addProperty("multi_chunk_parts_with_dwellings", multiPartsWithPlaces);
        out.addProperty("registered_now", registered);
        out.addProperty("city_homes", homes);
        out.addProperty("homes_of_several_boxes", manyBoxes);
        out.addProperty("homes_over_several_chunks", manyChunks);
        out.addProperty("most_boxes_in_one_home", maxBoxes);
        out.addProperty("homes_reaching_a_chunk_of_another_building", outsideTheirBuilding);
        LOGGER.info("[UC-PROBE] homes: {}", out);
        return out;
    }

    private void prepare(ServerLevel level) {
        int radius = Integer.getInteger("uc.cityProbe.radius", 6);
        String[] cities = System.getProperty("uc.cityProbe.cities", "ironhaven,valewood,blackwater").split(",");
        for (var region : CityRegions.INSTANCE.all()) {
            boolean wanted = false;
            for (String id : cities) wanted |= id.trim().equals(region.id());
            if (!wanted) continue;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) queue.add(new int[] {region.centerChunkX() + dx, region.centerChunkZ() + dz});
        }
        for (String pair : System.getProperty("uc.cityProbe.extra", "").split(",")) {
            String[] part = pair.trim().split(":");
            if (part.length == 2) queue.add(new int[] {Integer.parseInt(part[0]), Integer.parseInt(part[1])});
        }
        LOGGER.info("[UC-PROBE] {} chunks to probe", queue.size());
    }

    // ----------------------------------------------------------------------------------------------------- one chunk

    private static boolean soil(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.MOSS_REPLACEABLE)
            || state.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK) || state.is(net.minecraft.world.level.block.Blocks.GRAVEL) || state.is(net.minecraft.world.level.block.Blocks.CLAY);
    }

    private static String category(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof SaplingBlock) return "sapling";
        if (state.is(BlockTags.LEAVES)) return "leaves";
        if (state.is(BlockTags.LOGS)) return "log";
        if (block instanceof VineBlock) return "vine";
        if (state.is(BlockTags.FLOWERS)) return "flower";
        if (block instanceof BushBlock && !state.is(BlockTags.CROPS) && !state.is(BlockTags.SAPLINGS)) return "plant";
        return null;
    }

    private JsonObject probe(ServerLevel level, int cx, int cz) {
        // Lost Cities' plan for the chunk BEFORE the chunk exists, then the same question after it was generated: if the two ever differ the city would have a hole that nobody planned
        boolean planned = LostCitiesLink.chunk(level, cx, cz).map(LostCitiesLink.ChunkSample::city).orElse(false);
        LevelChunk chunk = level.getChunk(cx, cz);
        var sample = LostCitiesLink.chunk(level, cx, cz).orElseThrow();
        String type = sample.building() == null ? "" : sample.building();
        JsonObject out = new JsonObject();
        out.addProperty("chunk_x", cx); out.addProperty("chunk_z", cz);
        out.addProperty("planned_city_before_generation", planned);
        out.addProperty("city", sample.city()); out.addProperty("type", type); out.addProperty("building", sample.buildingId()); out.addProperty("style", sample.style());
        out.addProperty("floors", sample.floors()); out.addProperty("cellars", sample.cellars()); out.addProperty("city_level", sample.cityLevel());
        out.addProperty("rail", CityCharacter.railAt(level, cx, cz));
        out.addProperty("flattened_multi", CityCharacter.FLATTENED.contains(ChunkPos.asLong(cx, cz)));
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        int topSolid = minY, bottomBuilt = maxY, doors = 0, glass = 0, containers = 0, onStructure = 0, trees = 0;
        Map<String, Integer> counts = new TreeMap<>();
        JsonArray samples = new JsonArray();
        JsonArray doorList = new JsonArray();
        int street = LostCitiesLink.realHeight(level, sample.cityLevel());
        out.addProperty("street_y", street);
        int covered = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkPos base = chunk.getPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            for (int y = maxY - 1; y >= minY; y--) {
                pos.set(base.getMinBlockX() + x, y, base.getMinBlockZ() + z);
                BlockState state = chunk.getBlockState(pos);
                if (state.isAir()) continue;
                String kind = category(state);
                if (kind != null) {
                    counts.merge(kind, 1, Integer::sum);
                    BlockState below = chunk.getBlockState(pos.below());
                    boolean natural = soil(below) || category(below) != null || below.is(BlockTags.LEAVES) || below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK) || below.is(BlockTags.DIRT);
                    boolean flagged = !natural && !below.isAir();
                    if (flagged) onStructure++;
                    if ((kind.equals("log") || kind.equals("leaves")) && sample.city() && !type.isEmpty()) trees++;
                    if ((flagged || kind.equals("sapling")) && samples.size() < 8) {
                        JsonObject s = new JsonObject();
                        s.addProperty("x", pos.getX()); s.addProperty("y", y); s.addProperty("z", pos.getZ()); s.addProperty("kind", kind);
                        s.addProperty("block", String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())));
                        s.addProperty("below", String.valueOf(ForgeRegistries.BLOCKS.getKey(below.getBlock())));
                        samples.add(s);
                    }
                    continue;
                }
                if (state.is(BlockTags.LEAVES)) continue;
                if (topSolid == minY) topSolid = y;
                bottomBuilt = Math.min(bottomBuilt, y);
                Block block = state.getBlock();
                if (block instanceof DoorBlock && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                    doors++;
                    if (doorList.size() < 12) {
                        JsonObject d = new JsonObject();
                        var facing = state.getValue(DoorBlock.FACING);
                        d.addProperty("x", pos.getX()); d.addProperty("y", y); d.addProperty("z", pos.getZ());
                        d.addProperty("block", String.valueOf(ForgeRegistries.BLOCKS.getKey(block)));
                        d.addProperty("facing", facing.getName()); d.addProperty("open", state.getValue(DoorBlock.OPEN)); d.addProperty("hinge", state.getValue(DoorBlock.HINGE).getSerializedName());
                        d.addProperty("rel_street", y - street);
                        for (var side : new net.minecraft.core.Direction[] {facing, facing.getOpposite()}) {
                            BlockPos beside = pos.relative(side).above();
                            boolean loaded = level.hasChunkAt(beside);
                            d.addProperty("sky_" + (side == facing ? "front" : "back"), loaded && BuildingGuard.exposed(level, beside));
                        }
                        doorList.add(d);
                    }
                }
                else if (state.is(net.minecraftforge.common.Tags.Blocks.GLASS) || state.is(net.minecraftforge.common.Tags.Blocks.GLASS_PANES)) glass++;
                else if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) containers++;
            }
        }
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            pos.set(base.getMinBlockX() + x, street + 4, base.getMinBlockZ() + z);
            if (!chunk.getBlockState(pos).isAir()) covered++;
        }
        out.addProperty("covered_columns_at_street_plus_4", covered);
        out.add("door_list", doorList);
        out.addProperty("top_solid_y", topSolid);
        out.addProperty("doors", doors); out.addProperty("glass", glass); out.addProperty("containers", containers);
        JsonObject veg = new JsonObject();
        counts.forEach(veg::addProperty);
        out.add("vegetation", veg);
        out.addProperty("vegetation_on_structure", onStructure);
        out.add("samples", samples);
        String key = (sample.city() ? "city:" : "outside:") + (type.isEmpty() ? "none" : type);
        surface(chunk, street, out);
        if (GenerationTrace.ON) {
            JsonObject trace = GenerationTrace.diff(chunk, type.isEmpty());
            if (trace != null) out.add("after_lost_cities", trace);
        }
        dump(level, chunk, sample, street, type);
        out.add("counters", counters(level, cx, cz));
        int[] total = totals.computeIfAbsent(key, k -> new int[3]);
        total[0]++; if (onStructure > 0) total[1]++; if (trees > 0) total[2]++;
        return out;
    }

    // ----------------------------------------------------------------------------------------------------- the ground a person walks on

    /** Every block kind met as a column's surface, in the order met (the report's {@code surface_legend}; a chunk's {@code surface} holds indices into it). */
    private static final List<String> SURFACE_LEGEND = new ArrayList<>();
    private static final Map<String, Integer> SURFACE_INDEX = new java.util.HashMap<>();

    /**
     * For each of the 256 columns, the top block from three under the street height to four above it (what a person on the street walks on or bumps into) and its height above the
     * street: {@code surface} (indices into the legend, -1 for nothing) and {@code surface_rel} (one digit per column, the height plus 3, a dot for nothing). Read by
     * {@code scripts/city_surface_map.py}, which draws a city's ground as a picture (D-196).
     */
    private static void surface(LevelChunk chunk, int street, JsonObject out) {
        JsonArray kinds = new JsonArray(), layer = new JsonArray(), over = new JsonArray();
        StringBuilder rel = new StringBuilder(256), overRel = new StringBuilder(256);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkPos base = chunk.getPos();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int found = -1, at = 0;
            for (int y = street + 4; y >= street - 3; y--) {
                BlockState state = chunk.getBlockState(pos.set(base.getMinBlockX() + x, y, base.getMinBlockZ() + z));
                if (state.isAir()) continue;
                found = legendIndex(state);
                at = y - street;
                break;
            }
            kinds.add(found);
            rel.append(found < 0 ? '.' : (char) ('0' + at + 3));
            // the street's own layer (Lost Cities lays its street block at the street height) and the first thing standing in the four blocks over it
            BlockState ground = chunk.getBlockState(pos.set(base.getMinBlockX() + x, street, base.getMinBlockZ() + z));
            layer.add(ground.isAir() ? -1 : legendIndex(ground));
            int first = -1, firstAt = 0;
            for (int y = street + 1; y <= street + 4 && first < 0; y++) {
                BlockState state = chunk.getBlockState(pos.set(base.getMinBlockX() + x, y, base.getMinBlockZ() + z));
                if (!state.isAir()) { first = legendIndex(state); firstAt = y - street; }
            }
            over.add(first);
            overRel.append(first < 0 ? '.' : (char) ('0' + firstAt));
        }
        out.add("surface", kinds);
        out.addProperty("surface_rel", rel.toString());
        out.add("street_layer", layer);
        out.add("over_street", over);
        out.addProperty("over_street_rel", overRel.toString());
    }

    private static int legendIndex(BlockState state) {
        String name = String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock())).replace("minecraft:", "");
        return SURFACE_INDEX.computeIfAbsent(name, n -> { SURFACE_LEGEND.add(n); return SURFACE_LEGEND.size() - 1; });
    }

    // ----------------------------------------------------------------------------------------------------- counters a customer can reach

    /**
     * Every spot a shop's keeper serves at in this chunk's furnished building: where the keeper stands, and whether a customer on the public floor can speak to them, i.e. a public cell within
     * three blocks with nothing solid between the eyes (a survival player reaches a person three blocks off). Smith run 3 found the warehouse's keeper standing outside its wall (D-178).
     */
    static JsonArray counters(ServerLevel level, int cx, int cz) {
        JsonArray list = new JsonArray();
        CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
        if (b == null) return list;
        var shop = ShopCounters.INSTANCE.forBuilding(b.type());
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        if (shop.isEmpty() || plan == null) return list;
        StaffZones.Zones zones = StaffZones.of(level, b);
        for (int i = 0; i < plan.spots().size(); i++) {
            if (!plan.spots().get(i).kind().equals(shop.get().spot())) continue;
            JsonObject c = new JsonObject();
            c.addProperty("building", b.type()); c.addProperty("spot", i);
            BlockPos stand = HouseholdService.standFor(level, b, plan, i).orElse(null);
            int within = 0, seen = 0;
            if (stand != null) {
                c.addProperty("stand", stand.getX() + "," + stand.getY() + "," + stand.getZ());
                c.addProperty("stand_public", zones.isPublic(stand));
                c.addProperty("stand_outside", zones.isOutside(stand) || !StaffZones.roofed(level, stand));
                net.minecraft.world.phys.Vec3 chest = net.minecraft.world.phys.Vec3.atBottomCenterOf(stand).add(0, 1.2, 0);
                for (long key : zones.publicCells()) {
                    BlockPos cell = BlockPos.of(key);
                    if (cell.equals(stand) || Math.abs(cell.getY() - stand.getY()) > 1 || cell.distToCenterSqr(net.minecraft.world.phys.Vec3.atCenterOf(stand)) > 3.2 * 3.2) continue;
                    within++;
                    net.minecraft.world.phys.Vec3 eye = net.minecraft.world.phys.Vec3.atBottomCenterOf(cell).add(0, 1.62, 0);
                    if (level.clip(new net.minecraft.world.level.ClipContext(eye, chest, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, null))
                        .getType() == net.minecraft.world.phys.HitResult.Type.MISS) seen++;
                }
            }
            c.addProperty("public_within_reach", within); c.addProperty("public_in_sight", seen);
            c.addProperty("reachable", stand != null && seen > 0);
            list.add(c);
        }
        return list;
    }

    // ----------------------------------------------------------------------------------------------------- a text picture of a chunk

    private static final String DUMP = System.getProperty("uc.cityProbe.dump");
    private static final String DUMP_PREFIX = System.getProperty("uc.cityProbe.dumpprefix", "");
    /**
     * One glyph per block kind, in the order the kinds are met; the last one stands for every kind past the end. None of them is the dot of air, the {@code =} of the legend or the {@code ?}
     * a reader prints for a glyph it does not know (ninth pass: the alphabet held the dot, so the 71st kind of block in a chunk was written as air, and the building audit counted it as missing).
     */
    private static final String GLYPHS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!$%&*+,-/:;<>@[]^_`{|}~";

    /**
     * For a chunk whose building type starts with {@code -PucCpDumpprefix=}, writes {@code <type>_<chunkX>_<chunkZ>.txt} to {@code -PucCpDump=<folder>}: the blocks of the chunk layer by layer from just under the street to
     * above the roof, one glyph per block kind (air is a dot), with the legend at the top. It is how a generated building is read without a client: where the doors are, which side the street is, what stands in each room.
     */
    private static void dump(ServerLevel level, LevelChunk chunk, LostCitiesLink.ChunkSample sample, int street, String type) {
        if (DUMP == null || DUMP.isBlank() || type.isEmpty() || !type.contains(DUMP_PREFIX)) return;
        try {
            Path dir = Path.of(DUMP);
            Files.createDirectories(dir);
            Map<String, Character> legend = new java.util.LinkedHashMap<>();
            StringBuilder body = new StringBuilder();
            ChunkPos base = chunk.getPos();
            int top = street + Math.max(1, sample.floors()) * 6 + 10, bottom = street - 3;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = bottom; y <= top; y++) {
                body.append("--- y ").append(y).append(" (street ").append(y - street >= 0 ? "+" : "").append(y - street).append(")\n");
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        pos.set(base.getMinBlockX() + x, y, base.getMinBlockZ() + z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) { body.append('.'); continue; }
                        String name = String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
                        Character glyph = legend.get(name);
                        if (glyph == null) { glyph = GLYPHS.charAt(Math.min(legend.size(), GLYPHS.length() - 1)); legend.put(name, glyph); }
                        body.append(glyph);
                    }
                    body.append('\n');
                }
            }
            StringBuilder head = new StringBuilder("# " + type + " chunk " + base.x + "," + base.z + " floors " + sample.floors() + " cellars " + sample.cellars() + " street y " + street + "\n# x grows east (columns), z grows south (rows)\n# legend:");
            legend.forEach((name, glyph) -> head.append(' ').append(glyph).append('=').append(name.replace("minecraft:", "")));
            head.append("\n");
            Files.writeString(dir.resolve(type.replace(':', '_') + "_" + base.x + "_" + base.z + ".txt"), head + body.toString(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            LOGGER.warn("[UC-PROBE] could not write a chunk dump: {}", error.toString());
        }
    }
}
