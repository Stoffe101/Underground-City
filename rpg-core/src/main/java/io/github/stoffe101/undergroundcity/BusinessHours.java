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

/**
 * When each kind of business is open (eighth pass, D-185): shops by day, cafes from breakfast to evening, restaurants from lunch to late, pubs and bars from noon past midnight, clubs from
 * evening to three in the morning, industrial suppliers from seven to seven. A span may cross midnight (a club's 18 to 3). The hours are when the people who work a building are at their
 * posts ({@link HouseholdPlan}), so a shop's door, its public floor, its counter and its cameras all follow them. Read from {@code data/underground_city/business_hours/hours.json}; a datapack
 * can change them.
 */
final class BusinessHours {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** From {@code from} o'clock up to (not including) {@code to}; {@code from > to} crosses midnight. */
    record Span(int from, int to) {
        boolean open(int hour) { return from < to ? hour >= from && hour < to : hour >= from || hour < to; }
        String text() { return String.format(java.util.Locale.ROOT, "%02d:00 to %02d:00", from, to); }
    }

    record Rules(Map<String, Span> kinds, Map<String, String> buildings, Map<String, String> uses, String fallback) {
        static final Rules DEFAULT = new Rules(Map.of("shop", new Span(8, 18)), Map.of(), Map.of(), "shop");

        /** The kind of business a furnished building is: by its type first, then its use, else the fallback. */
        String kindOf(String type, String use) {
            String k = buildings.get(type);
            if (k == null) k = uses.get(use);
            return k == null || !kinds.containsKey(k) ? fallback : k;
        }

        Span spanOf(String type, String use) { Span s = kinds.get(kindOf(type, use)); return s == null ? new Span(8, 18) : s; }
    }

    static volatile Rules RULES = Rules.DEFAULT;

    private BusinessHours() {}

    /** The hours of the building a plan is the plan of. */
    static Span of(BuildingSpots.Plan plan) { return RULES.spanOf(plan.id(), plan.use()); }

    /** The hours of a furnished building by its type, or null when Core has no plan of it. */
    static Span of(String type) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(type);
        return plan == null ? null : of(plan);
    }

    static Rules parse(JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported business hours schema");
        Map<String, Span> kinds = new LinkedHashMap<>();
        for (var e : json.getAsJsonObject("kinds").entrySet()) {
            var pair = e.getValue().getAsJsonArray();
            int from = pair.get(0).getAsInt(), to = pair.get(1).getAsInt();
            if (from < 0 || from > 23 || to < 0 || to > 23 || from == to) throw new IllegalArgumentException("Business hours " + e.getKey() + " must be two different hours 0 to 23");
            kinds.put(e.getKey(), new Span(from, to));
        }
        Map<String, String> buildings = new LinkedHashMap<>(), uses = new LinkedHashMap<>();
        for (var e : json.getAsJsonObject("buildings").entrySet()) buildings.put(ResourceLocation.parse(e.getKey()).toString(), e.getValue().getAsString());
        for (var e : json.getAsJsonObject("uses").entrySet()) uses.put(e.getKey(), e.getValue().getAsString());
        String fallback = json.get("fallback").getAsString();
        for (String k : buildings.values()) if (!kinds.containsKey(k)) throw new IllegalArgumentException("A building names an unknown kind of business: " + k);
        for (String k : uses.values()) if (!kinds.containsKey(k)) throw new IllegalArgumentException("A use names an unknown kind of business: " + k);
        if (!kinds.containsKey(fallback)) throw new IllegalArgumentException("The fallback kind of business is unknown: " + fallback);
        return new Rules(Map.copyOf(kinds), Map.copyOf(buildings), Map.copyOf(uses), fallback);
    }

    static final class Loader extends SimpleJsonResourceReloadListener {
        static final Loader INSTANCE = new Loader();
        private Loader() { super(new Gson(), "business_hours"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            JsonElement element = data.get(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "hours"));
            RULES = element == null ? Rules.DEFAULT : parse(element.getAsJsonObject());
            LOGGER.info("Loaded business hours for {} kinds of business", RULES.kinds().size());
        }
    }
}
