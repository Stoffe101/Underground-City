package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where things are inside the furnished buildings (D-124): the spots (door, bed, stove, seat, counter, desk, stash, shelf) each building's generator wrote beside its blueprint
 * ({@code data/underground_city/city_buildings/<id>.json}). A spot is a place in the building's own coordinates: {@code x} and {@code z} within its chunk, {@code y} above the ground floor's floor.
 * Nothing here is placed in the world; {@link HouseholdService} reads the spots to decide where a resident or a shopkeeper stands.
 */
public final class BuildingSpots extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final BuildingSpots INSTANCE = new BuildingSpots();
    static final int MAX_SPOTS = 64;

    public record Spot(String kind, int x, int y, int z, String facing, String note) {}

    /** One building: its use ("house", "flats", "shop", "office", ...), its city and its spots in file order. */
    public record Plan(String id, String use, String city, List<Spot> spots) {
        public List<Integer> indexesOf(String kind) {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < spots.size(); i++) if (spots.get(i).kind().equals(kind)) out.add(i);
            return out;
        }
    }

    private volatile Map<String, Plan> plans = Map.of();
    private BuildingSpots() { super(new Gson(), "city_buildings"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Plan> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported building spots schema: " + entry.getKey());
            List<Spot> spots = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("spots")) {
                JsonObject s = element.getAsJsonObject();
                String kind = s.get("kind").getAsString();
                if (!kind.matches("[a-z_]{2,24}")) throw new IllegalArgumentException("Bad spot kind in " + entry.getKey() + ": " + kind);
                spots.add(new Spot(kind, s.get("x").getAsInt(), s.get("y").getAsInt(), s.get("z").getAsInt(), s.has("facing") ? s.get("facing").getAsString() : "", s.has("note") ? s.get("note").getAsString() : ""));
            }
            if (spots.size() > MAX_SPOTS) throw new IllegalArgumentException(entry.getKey() + " has more than " + MAX_SPOTS + " spots");
            next.put(entry.getKey().toString(), new Plan(entry.getKey().toString(), json.has("use") ? json.get("use").getAsString() : "", json.has("city") ? json.get("city").getAsString() : "", List.copyOf(spots)));
        }
        plans = Map.copyOf(next);
        LOGGER.info("Loaded the spots of {} furnished buildings", plans.size());
    }

    /** The plan of a building of the type Lost Cities reports for it ({@code namespace:id}, as {@link CityBuildings.Building#type()}), or null when it has none (a stock building). */
    public Plan forType(String type) { return type == null ? null : plans.get(type); }
    public int size() { return plans.size(); }

    /** Test seam. */
    void replaceForTest(Map<String, Plan> replacement) { plans = Map.copyOf(replacement); }
}
