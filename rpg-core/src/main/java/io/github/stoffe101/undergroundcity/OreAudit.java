package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.Tags;
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
 * Opt-in measurement of the natural ore of a generated world ({@code -Duc.oreAudit=<report.json>}, disposable dedicated server only; {@code scripts/run_ore_audit.py}). It generates a grid of chunks around each
 * city and around points in the countryside, one or two per server tick, and counts every ore block it finds by kind: that is the evidence that a world with the scarcity package (D-120) really has no natural
 * ore, whatever mod put an ore feature into a biome, and the baseline that a world without it does. A block counts as an ore when it is in the {@code forge:ores} tag or its id ends in {@code _ore}, {@code _debris}
 * or is ancient debris. It is a probe, not a gate: it writes numbers.
 *
 * <p>Properties: {@code uc.oreAudit.radius} (chunks around each sample point, default 4), {@code uc.oreAudit.spread} (blocks between a city centre and its countryside points, default 260).
 */
public final class OreAudit {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT = System.getProperty("uc.oreAudit");
    private final List<int[]> queue = new ArrayList<>();
    private final Map<String, int[]> perKind = new TreeMap<>();
    private final Map<String, Integer> perArea = new TreeMap<>();
    private final JsonArray points = new JsonArray();
    private long started;
    private int chunks;
    private boolean prepared, finished;

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (OUTPUT != null && !OUTPUT.isBlank()) started = System.nanoTime();
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank() || finished || started == 0 || event.phase != TickEvent.Phase.END) return;
        ServerLevel level = event.getServer().overworld();
        if (!prepared) { prepare(); prepared = true; }
        for (int n = 0; n < 2 && !queue.isEmpty(); n++) {
            int[] at = queue.remove(queue.size() - 1);
            try { scan(level, at[0], at[1], at[2]); }
            catch (RuntimeException error) { LOGGER.error("[UC-ORE] chunk {},{} failed: {}", at[0], at[1], error.toString()); }
        }
        if (!queue.isEmpty()) return;
        JsonObject report = new JsonObject();
        report.addProperty("generator", level.getChunkSource().getGenerator().getClass().getName());
        report.addProperty("seconds", (System.nanoTime() - started) / 1_000_000_000L);
        report.addProperty("chunks_scanned", chunks);
        long total = 0;
        JsonObject kinds = new JsonObject();
        for (var entry : perKind.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("blocks", entry.getValue()[0]);
            o.addProperty("lowest_y", entry.getValue()[1]);
            o.addProperty("highest_y", entry.getValue()[2]);
            kinds.add(entry.getKey(), o);
            total += entry.getValue()[0];
        }
        report.addProperty("ore_blocks_total", total);
        report.add("by_kind", kinds);
        JsonObject areas = new JsonObject();
        perArea.forEach(areas::addProperty);
        report.add("ore_blocks_by_area", areas);
        report.add("points", points);
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the ore audit", error); }
        LOGGER.info("[UC-ORE] {} chunks scanned, {} ore blocks, written to {}", chunks, total, OUTPUT);
        finished = true;
        event.getServer().halt(false);
    }

    private void prepare() {
        int radius = Integer.getInteger("uc.oreAudit.radius", 4);
        int spread = Integer.getInteger("uc.oreAudit.spread", 260);
        int[][] offsets = {{0, 0}, {spread, 0}, {-spread, 0}, {0, spread}, {0, -spread}};
        for (var region : CityRegions.INSTANCE.all()) {
            int index = 0;
            for (int[] offset : offsets) {
                int cx = region.centerChunkX() + Math.floorDiv(offset[0], 16), cz = region.centerChunkZ() + Math.floorDiv(offset[1], 16);
                String area = region.id() + (index == 0 ? ":city" : ":countryside" + index);
                JsonObject point = new JsonObject();
                point.addProperty("area", area); point.addProperty("centre_chunk_x", cx); point.addProperty("centre_chunk_z", cz);
                points.add(point);
                perArea.put(area, 0);
                for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) queue.add(new int[] {cx + dx, cz + dz, areaIndex(area)});
                index++;
            }
        }
        LOGGER.info("[UC-ORE] {} chunks to scan", queue.size());
    }

    private final List<String> areaNames = new ArrayList<>();

    private int areaIndex(String area) {
        int at = areaNames.indexOf(area);
        if (at >= 0) return at;
        areaNames.add(area);
        return areaNames.size() - 1;
    }

    private static boolean ore(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Tags.Blocks.ORES)) return true;
        var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (key == null) return false;
        String path = key.getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris") || path.endsWith("_debris");
    }

    private void scan(ServerLevel level, int cx, int cz, int area) {
        LevelChunk chunk = level.getChunk(cx, cz);
        chunks++;
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = 0;
        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    BlockState state = chunk.getBlockState(pos);
                    if (!ore(state)) continue;
                    found++;
                    String name = String.valueOf(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
                    int[] counts = perKind.computeIfAbsent(name, k -> new int[] {0, Integer.MAX_VALUE, Integer.MIN_VALUE});
                    counts[0]++; counts[1] = Math.min(counts[1], y); counts[2] = Math.max(counts[2], y);
                }
            }
        }
        perArea.merge(areaNames.get(area), found, Integer::sum);
    }
}
