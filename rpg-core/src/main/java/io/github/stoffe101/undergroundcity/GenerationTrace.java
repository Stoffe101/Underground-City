package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a city's blocks changed after Lost Cities built them, and what changed them (opt-in, the city probe's {@code -PucCpTrace=true} on a disposable server only; D-196).
 *
 * <p>The moment Lost Cities' placed feature returns for a city chunk (it writes its blocks into the chunk as it finishes, after its own "generated" event), the blocks from {@link CityGround#BELOW_FOUNDATION} under
 * the lowest floor to well above the roof are copied. While the world decorates, {@link CityGround} reports every write it guards, with the placed feature that made it and
 * whether it was refused. When the probe reads the finished chunk, every cell that differs from the copy is a change made after Lost Cities: by a feature (named), by an ore
 * blob (named too: the ore feature writes into the chunk's sections, and the guard is asked for each cell it would take), or by the running server (no feature recorded).
 * Memory is a few hundred kilobytes per chunk, which is why this exists only for a probe.
 */
final class GenerationTrace {
    static final boolean ON = Boolean.getBoolean("uc.cityProbe.trace");
    private static final Map<Long, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    /** Per chunk, the cells a guarded write touched: position, feature, refused. */
    private static final Map<Long, Map<Long, String>> WRITES = new ConcurrentHashMap<>();
    private static final Map<String, int[]> REFUSED_BY_FEATURE = new ConcurrentHashMap<>();

    private record Snapshot(int street, int bottom, int top, BlockState[] states) {}

    private GenerationTrace() {}

    /** Copies a chunk as Lost Cities left it (called as its placed feature returns, {@link CityGround#featureEnded}); only a city chunk whose plan is recorded. */
    static void snapshot(WorldGenLevel world, int chunkX, int chunkZ) {
        LostCitiesLink.ChunkSample sample = LostCitiesLink.recordedSample(ChunkPos.asLong(chunkX, chunkZ));
        if (sample == null || !sample.city()) return;
        int street = LostCitiesLink.streetY(sample.cityLevel());
        if (street == Integer.MIN_VALUE) return;
        ChunkAccess chunk = world.getChunk(chunkX, chunkZ);
        int bottom = CityGround.lowestProtected(street, sample.cellars());
        int top = Math.min(world.getMaxBuildHeight() - 1, street + Math.max(1, sample.floors()) * 6 + 14);
        BlockState[] states = new BlockState[(top - bottom + 1) * 256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int y = bottom; y <= top; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            states[i++] = chunk.getBlockState(pos.set((chunkX << 4) + x, y, (chunkZ << 4) + z));
        SNAPSHOTS.put(ChunkPos.asLong(chunkX, chunkZ), new Snapshot(street, bottom, top, states));
    }

    static void write(BlockPos pos, String feature, BlockState state, boolean refused) {
        String name = feature.isEmpty() ? "unnamed" : feature;
        REFUSED_BY_FEATURE.computeIfAbsent(name + (refused ? " (refused)" : " (let through)"), k -> new int[1])[0]++;
        WRITES.computeIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), k -> new ConcurrentHashMap<>()).put(pos.asLong(), name + (refused ? "|refused" : "|placed"));
    }

    /** Guarded writes by feature over the whole run. */
    static JsonObject totals() {
        JsonObject out = new JsonObject();
        new TreeMap<>(REFUSED_BY_FEATURE).forEach((k, v) -> out.addProperty(k, v[0]));
        return out;
    }

    /**
     * The changes of a finished chunk since Lost Cities built it, by zone (relative to the street height: the street's own block, the four above it, below, above), by block kind
     * (before then after) and by what made them. Null when the chunk was never copied (not a city chunk, or generated before the trace began).
     */
    static JsonObject diff(LevelChunk chunk, boolean street) {
        long key = chunk.getPos().toLong();
        Snapshot snap = SNAPSHOTS.get(key);
        if (snap == null) return null;
        Map<Long, String> writes = WRITES.getOrDefault(key, Map.of());
        Map<String, Integer> byZone = new TreeMap<>(), byKind = new TreeMap<>(), byCause = new TreeMap<>();
        JsonArray samples = new JsonArray();
        char[] columns = new char[256];
        java.util.Arrays.fill(columns, '.');
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0, changed = 0;
        for (int y = snap.bottom(); y <= snap.top(); y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            BlockState before = snap.states()[i++];
            pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
            BlockState after = chunk.getBlockState(pos);
            if (after == before) continue;
            changed++;
            String zone = zone(y - snap.street(), street);
            String kind = id(before) + " -> " + id(after);
            String cause = writes.getOrDefault(pos.asLong(), "server (no feature)|after generation");
            byZone.merge(zone, 1, Integer::sum);
            // a column's mark: s a change in the street's own layer, h above the street or the lot's ground, f only underneath
            char mark = zone.equals("street_surface") ? 's' : zone.equals("foundation") || zone.equals("under_street") ? 'f' : 'h';
            int column = z * 16 + x;
            if (columns[column] == '.' || mark == 's' || (mark == 'h' && columns[column] == 'f')) columns[column] = mark;
            byKind.merge(zone + ": " + kind, 1, Integer::sum);
            byCause.merge(cause.substring(0, cause.indexOf('|')), 1, Integer::sum);
            if (samples.size() < 10) {
                JsonObject s = new JsonObject();
                s.addProperty("at", pos.getX() + "," + y + "," + pos.getZ());
                s.addProperty("zone", zone);
                s.addProperty("change", kind);
                s.addProperty("cause", cause);
                samples.add(s);
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("cells_compared", snap.states().length);
        out.addProperty("cells_changed", changed);
        JsonObject zones = new JsonObject(); byZone.forEach(zones::addProperty); out.add("by_zone", zones);
        JsonObject kinds = new JsonObject(); byKind.forEach(kinds::addProperty); out.add("by_kind", kinds);
        JsonObject causes = new JsonObject(); byCause.forEach(causes::addProperty); out.add("by_cause", causes);
        out.add("samples", samples);
        out.addProperty("changed_columns", new String(columns));
        return out;
    }

    /** The zone of a cell {@code rel} blocks above the street height ({@code street} chunks: a street, a square or a park; otherwise a building's lot). */
    static String zone(int rel, boolean street) {
        if (street) {
            // Lost Cities' street block stands at the street height itself; a person walks on top of it
            if (rel == 0) return "street_surface";
            if (rel >= 1 && rel <= 4) return "street_headroom";
            return rel < 0 ? "under_street" : "above_street";
        }
        return rel < 0 ? "foundation" : rel == 0 ? "ground_floor" : "building";
    }

    private static String id(BlockState state) {
        var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key == null ? "?" : key.toString().replace("minecraft:", "");
    }
}
