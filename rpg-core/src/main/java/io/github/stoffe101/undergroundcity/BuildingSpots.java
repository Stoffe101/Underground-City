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
    /** One interior box in the building's own coordinates (y above the street), inclusive. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        boolean contains(int x, int y, int z) { return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1; }
        boolean inShell(int x, int y, int z) { return x >= x0 - 1 && x <= x1 + 1 && y >= y0 - 1 && y <= y1 + 1 && z >= z0 - 1 && z <= z1 + 1; }
        boolean valid() { return x0 >= 0 && x1 <= 15 && z0 >= 0 && z1 <= 15 && x0 <= x1 && y0 <= y1 && z0 <= z1 && y0 >= 0 && y1 <= 250; }
    }

    /**
     * A home or premises of the building that can be let or sold (D-127): its interior, one box or several (D-206: a flat whose rooms are not one rectangle), in the building's own
     * coordinates. The first box's corners are the unit's {@code x0} to {@code z1}.
     */
    public record Unit(String id, String title, List<Box> boxes) {
        public Unit(String id, String title, int x0, int y0, int z0, int x1, int y1, int z1) { this(id, title, List.of(new Box(x0, y0, z0, x1, y1, z1))); }
        public int x0() { return boxes.get(0).x0(); } public int y0() { return boxes.get(0).y0(); } public int z0() { return boxes.get(0).z0(); }
        public int x1() { return boxes.get(0).x1(); } public int y1() { return boxes.get(0).y1(); } public int z1() { return boxes.get(0).z1(); }
        boolean contains(int x, int y, int z) { for (Box b : boxes) if (b.contains(x, y, z)) return true; return false; }
        /** In the one-block shell of any of its boxes, or inside one. */
        boolean inShell(int x, int y, int z) { for (Box b : boxes) if (b.inShell(x, y, z)) return true; return false; }
    }

    public record Plan(String id, String use, String city, List<Spot> spots, List<Unit> units) {
        public Plan(String id, String use, String city, List<Spot> spots) { this(id, use, city, spots, List.of()); }

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
            List<Unit> units = new ArrayList<>();
            if (json.has("units")) for (JsonElement element : json.getAsJsonArray("units")) {
                JsonObject u = element.getAsJsonObject();
                // "boxes" lists every box of a unit that is not one rectangle (D-206); "box" is the one box of any other
                List<Box> boxes = new ArrayList<>();
                if (u.has("boxes")) for (JsonElement b : u.getAsJsonArray("boxes")) boxes.add(box(b.getAsJsonArray()));
                else boxes.add(box(u.getAsJsonArray("box")));
                Unit unit = new Unit(u.get("id").getAsString(), u.get("title").getAsString(), List.copyOf(boxes));
                if (!unit.id().matches("[a-z0-9_]{2,24}") || boxes.isEmpty() || boxes.size() > 64 || !boxes.stream().allMatch(Box::valid)) {
                    throw new IllegalArgumentException("Bad unit " + unit.id() + " in " + entry.getKey());
                }
                units.add(unit);
            }
            if (units.size() > 12) throw new IllegalArgumentException(entry.getKey() + " has more than 12 units");
            next.put(entry.getKey().toString(), new Plan(entry.getKey().toString(), json.has("use") ? json.get("use").getAsString() : "", json.has("city") ? json.get("city").getAsString() : "", List.copyOf(spots), List.copyOf(units)));
        }
        plans = Map.copyOf(next);
        LOGGER.info("Loaded the spots of {} furnished buildings", plans.size());
    }

    private static Box box(com.google.gson.JsonArray a) { return new Box(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt(), a.get(4).getAsInt(), a.get(5).getAsInt()); }

    /** The plan of a building of the type Lost Cities reports for it ({@code namespace:id}, as {@link CityBuildings.Building#type()}), or null when it has none (a stock building). */
    public Plan forType(String type) { return type == null ? null : plans.get(type); }
    public int size() { return plans.size(); }

    /** Test seam. */
    void replaceForTest(Map<String, Plan> replacement) { plans = Map.copyOf(replacement); }
    /** Test seam: the plans now loaded, to put back after a test. */
    Map<String, Plan> plansForTest() { return plans; }
}
