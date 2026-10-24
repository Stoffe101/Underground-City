package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Data-defined city circles generated from content/region-blueprints/cities.json. A placed
 * terminal inside a circle is server-confirmed to be in that city; this is geography
 * evidence, not proof that roads or buildings exist (see world survey documentation).
 */
public final class CityRegions extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CityRegions INSTANCE = new CityRegions();

    /** {@code radius} is the civic region (law, exchange, "In Ironhaven"); {@code cityRadius} is the Lost Cities circle, equal to it in the legacy layout. */
    public record Region(String id, String dimension, int centerChunkX, int centerChunkZ, int radius, int cityRadius) {
        Region(String id, String dimension, int centerChunkX, int centerChunkZ, int radius) { this(id, dimension, centerChunkX, centerChunkZ, radius, radius); }
        static Region fromJson(ResourceLocation id, JsonObject json) {
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported city-region schema: " + id);
            }
            var chunk = json.getAsJsonArray("center_chunk");
            Region region = new Region(json.get("id").getAsString(), json.get("dimension").getAsString(),
                chunk.get(0).getAsInt(), chunk.get(1).getAsInt(), json.get("radius_blocks").getAsInt(),
                json.has("city_radius_blocks") ? json.get("city_radius_blocks").getAsInt() : json.get("radius_blocks").getAsInt());
            if (!ProgressionRules.validCity(region.id) || region.radius < 16 || region.radius > 4096
                || Math.abs(region.centerChunkX) > 1_000_000 || Math.abs(region.centerChunkZ) > 1_000_000) {
                throw new IllegalArgumentException("Invalid city region " + id);
            }
            ResourceLocation.parse(region.dimension);
            return region;
        }

        double centerX() { return centerChunkX * 16 + 8; }
        double centerZ() { return centerChunkZ * 16 + 8; }
        double distanceSquared(BlockPos pos) {
            double dx = pos.getX() + 0.5 - centerX(), dz = pos.getZ() + 0.5 - centerZ();
            return dx * dx + dz * dz;
        }
    }

    private volatile List<Region> regions = List.of();
    private CityRegions() { super(new Gson(), "regions"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        List<Region> next = data.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> Region.fromJson(entry.getKey(), entry.getValue().getAsJsonObject())).toList();
        if (next.size() > 16) throw new IllegalArgumentException("At most 16 city regions supported");
        regions = List.copyOf(next);
        LOGGER.info("Loaded {} Underground City city regions", regions.size());
    }

    /** Nearest-center city containing the position in this dimension, if any. */
    public Optional<String> cityAt(Level level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        return regions.stream().filter(region -> region.dimension.equals(dimension))
            .filter(region -> region.distanceSquared(pos) <= (double) region.radius * region.radius)
            .min(java.util.Comparator.comparingDouble(region -> region.distanceSquared(pos)))
            .map(Region::id);
    }

    /** How far beyond a city's circle no vanilla structure may start (D-106): a village is about 100 blocks across, so a start this far out cannot reach the circle. */
    public static final int STRUCTURE_MARGIN = 160;

    /** True when a structure start in this chunk would be inside, or close enough to reach, a city's circle: the archipelago generates none there (see {@code ChunkGeneratorStructuresMixin}). */
    public boolean excludesStructures(net.minecraft.world.level.ChunkPos chunk) {
        double x = chunk.getMiddleBlockX() + 0.5, z = chunk.getMiddleBlockZ() + 0.5;
        for (Region region : regions) {
            if (!region.dimension.equals("minecraft:overworld")) continue;
            double reach = region.cityRadius + STRUCTURE_MARGIN;
            double dx = x - region.centerX(), dz = z - region.centerZ();
            if (dx * dx + dz * dz <= reach * reach) return true;
        }
        return false;
    }

    /** The city whose centre is nearest to the position, wherever it is in the dimension (a wayside shelter belongs to the city nearest to it). */
    public Optional<String> nearest(Level level, BlockPos pos) {
        String dimension = level.dimension().location().toString();
        return regions.stream().filter(region -> region.dimension.equals(dimension))
            .min(java.util.Comparator.comparingDouble(region -> region.distanceSquared(pos))).map(Region::id);
    }

    List<Region> all() { return regions; }

    /** The region of a city id, if loaded. */
    public Optional<Region> region(String id) { return regions.stream().filter(region -> region.id.equals(id)).findFirst(); }

    /** Test seam: replaces the loaded circles without a datapack reload. */
    void replaceForTest(List<Region> replacement) { regions = List.copyOf(replacement); }
}
