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
    private final JsonArray chunks = new JsonArray();
    private final Map<String, int[]> totals = new TreeMap<>();
    private long started;
    private boolean prepared, finished;

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank()) return;
        started = System.nanoTime();
        // which data packs carry the same file, lowest priority first: the answer to "whose citystyle does Lost Cities read?"
        for (String path : System.getProperty("uc.cityProbe.stack", "").split(",")) {
            if (path.isBlank()) continue;
            var location = net.minecraft.resources.ResourceLocation.tryParse(path.trim());
            if (location == null) continue;
            var stack = event.getServer().getResourceManager().getResourceStack(location);
            LOGGER.info("[UC-PROBE] resource stack of {}: {}", location, stack.stream().map(r -> r.sourcePackId()).toList());
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
            try { chunks.add(probe(level, at[0], at[1])); }
            catch (RuntimeException error) { LOGGER.error("[UC-PROBE] chunk {},{} failed: {}", at[0], at[1], error.toString()); }
        }
        if (!queue.isEmpty()) return;
        JsonObject report = new JsonObject();
        report.addProperty("generator", level.getChunkSource().getGenerator().getClass().getName());
        report.addProperty("seconds", (System.nanoTime() - started) / 1_000_000_000L);
        JsonObject summary = new JsonObject();
        totals.forEach((key, counts) -> { JsonObject o = new JsonObject(); o.addProperty("chunks", counts[0]); o.addProperty("with_vegetation_on_structure", counts[1]); o.addProperty("with_tree_in_city", counts[2]); summary.add(key, o); });
        report.add("summary", summary);
        report.add("chunks", chunks);
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the city probe", error); }
        LOGGER.info("[UC-PROBE] {} chunks probed in {} s, written to {}", chunks.size(), (System.nanoTime() - started) / 1_000_000_000L, OUTPUT);
        finished = true;
        event.getServer().halt(false);
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
        dump(level, chunk, sample, street, type);
        int[] total = totals.computeIfAbsent(key, k -> new int[3]);
        total[0]++; if (onStructure > 0) total[1]++; if (trees > 0) total[2]++;
        return out;
    }

    // ----------------------------------------------------------------------------------------------------- a text picture of a chunk

    private static final String DUMP = System.getProperty("uc.cityProbe.dump");
    private static final String DUMP_PREFIX = System.getProperty("uc.cityProbe.dumpprefix", "");
    private static final String GLYPHS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!$%&*+,-./:;<=>?@[]^_`{|}~";

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
