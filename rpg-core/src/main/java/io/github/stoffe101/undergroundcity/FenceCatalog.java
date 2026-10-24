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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The underground buyers of stolen goods (D-125), as data: {@code data/<ns>/fences/<city>.json} is a city's fence network (who they are, what share of an item's worth they pay, how many credits they
 * can find in a stretch of active play, the heat of selling goods back in the city they were taken from), and {@code fences/values.json} is what a fence thinks things are worth. An item with no value
 * is not bought at all: a fence is not a bin for whatever a burglar carries, and nothing that costs nothing to come by (stone, dirt, logs) can be turned into credits this way.
 */
public final class FenceCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final FenceCatalog INSTANCE = new FenceCatalog();

    /** The most a fence ever pays for an item, as a share of its worth, whatever the skills (so a fenced item is always worth less than an honest one). */
    static final int MAX_RATE_PERCENT = 60;
    /** The most for a fence-only good (D-144): it has no honest price to undercut, so a skilled thief may get more of its worth. */
    static final int MAX_FENCE_ONLY_PERCENT = 80;

    /**
     * A city's fence network. {@code ratePercent} of the worth for goods taken in another city, {@code sameCityRatePercent} (and {@code sameCityHeat} more heat) for goods taken in this one; the network
     * can pay {@code budget} credits at most, and finds {@code refillCredits} more every {@code refillMinutes} of active play. The fence is about between {@code fromHour} and {@code toHour}.
     */
    public record Fence(String city, String title, String blurb, int ratePercent, int sameCityRatePercent, int sameCityHeat, int budget, int refillCredits, int refillMinutes, int fromHour, int toHour,
                        java.util.Set<String> specialties) {
        public Fence(String city, String title, String blurb, int ratePercent, int sameCityRatePercent, int sameCityHeat, int budget, int refillCredits, int refillMinutes, int fromHour, int toHour) {
            this(city, title, blurb, ratePercent, sameCityRatePercent, sameCityHeat, budget, refillCredits, refillMinutes, fromHour, toHour, java.util.Set.of());
        }
        public Fence { specialties = specialties == null ? java.util.Set.of() : java.util.Set.copyOf(specialties); }
        long refillTicks() { return refillMinutes * 1200L; }
        /** True for goods this fence wants most (D-144): a thief with Specialist Buyers is paid a premium for them. */
        boolean wants(String item) { return specialties.contains(item); }
        /** True at an hour of the day (0 to 23) when the fence is about. A span that passes midnight (21 to 3) is read as such. */
        boolean about(int hour) { return fromHour <= toHour ? hour >= fromHour && hour < toHour : hour >= fromHour || hour < toHour; }
    }

    private volatile Map<String, Fence> fences = Map.of();
    private volatile Map<String, Integer> values = Map.of();
    private volatile java.util.Set<String> fenceOnly = java.util.Set.of();

    private FenceCatalog() { super(new Gson(), "fences"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Fence> nextFences = new LinkedHashMap<>();
        Map<String, Integer> nextValues = new LinkedHashMap<>();
        java.util.Set<String> nextOnly = new java.util.LinkedHashSet<>();
        try {
            for (var entry : data.entrySet()) {
                JsonObject json = entry.getValue().getAsJsonObject();
                if (json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException(entry.getKey() + ": schema_version must be 1");
                if (json.has("values")) {
                    for (var v : json.getAsJsonObject("values").entrySet()) {
                        int worth = v.getValue().getAsInt();
                        if (worth < 1 || worth > 2000) throw new IllegalArgumentException(entry.getKey() + ": the worth of " + v.getKey() + " must be 1 to 2000 credits");
                        nextValues.put(v.getKey(), worth);
                    }
                    if (json.has("fence_only")) for (JsonElement id : json.getAsJsonArray("fence_only")) nextOnly.add(id.getAsString());
                    continue;
                }
                Fence fence = parse(json);
                nextFences.put(fence.city(), fence);
            }
            fences = Map.copyOf(nextFences);
            values = Map.copyOf(nextValues);
            fenceOnly = java.util.Set.copyOf(nextOnly);
            LOGGER.info("Loaded {} Underground City fence networks and {} fenced item values", fences.size(), values.size());
        } catch (RuntimeException error) {
            LOGGER.error("Fence data is invalid; keeping the previous fences", error);
        }
    }

    static Fence parse(JsonObject json) {
        String city = json.get("city").getAsString();
        if (!ProgressionRules.validCity(city)) throw new IllegalArgumentException("unknown city " + city);
        int rate = json.get("rate_percent").getAsInt(), same = json.get("same_city_rate_percent").getAsInt();
        if (rate < 5 || rate > MAX_RATE_PERCENT || same < 0 || same > rate) throw new IllegalArgumentException(city + ": rates must be 5 to " + MAX_RATE_PERCENT + " percent, the home-city rate no higher");
        int budget = json.get("budget").getAsInt(), refill = json.get("refill_credits").getAsInt(), minutes = json.get("refill_minutes").getAsInt();
        if (budget < 1 || budget > 5000 || refill < 1 || refill > budget || minutes < 1) throw new IllegalArgumentException(city + ": budget 1 to 5000, refill at most the budget, at least a minute apart");
        int heat = json.has("same_city_heat") ? json.get("same_city_heat").getAsInt() : 1;
        if (heat < 0 || heat > 3) throw new IllegalArgumentException(city + ": same_city_heat must be 0 to 3");
        int from = json.get("from_hour").getAsInt(), to = json.get("to_hour").getAsInt();
        if (from < 0 || from > 23 || to < 0 || to > 24 || from == to) throw new IllegalArgumentException(city + ": hours must be 0 to 24 and not empty");
        java.util.Set<String> specialties = new java.util.LinkedHashSet<>();
        if (json.has("specialties")) for (JsonElement id : json.getAsJsonArray("specialties")) specialties.add(id.getAsString());
        return new Fence(city, json.get("title").getAsString(), json.has("blurb") ? json.get("blurb").getAsString() : "", rate, same, heat, budget, refill, minutes, from, to, specialties);
    }

    public Optional<Fence> forCity(String city) { return Optional.ofNullable(fences.get(city)); }
    public Map<String, Fence> all() { return fences; }

    /** What a fence thinks one of this item is worth, or 0 when a fence will not buy it. */
    public int worth(String itemId) { return values.getOrDefault(itemId, 0); }
    public Map<String, Integer> values() { return values; }
    /** True for goods that have no honest price at all (D-144). */
    public boolean fenceOnly(String itemId) { return fenceOnly.contains(itemId); }

    /** Test seam: replaces the catalogue (GameTests run without reloading data packs). */
    void setForTest(Map<String, Fence> f, Map<String, Integer> v) { fences = Map.copyOf(f); values = Map.copyOf(v); }
}
