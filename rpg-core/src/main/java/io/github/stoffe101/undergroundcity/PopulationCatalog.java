package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The data of the cities' people (D-108): for each city how many people, in what roles and with what names, and what each role says at which time of day. One file per city
 * ({@code data/underground_city/population/<city>.json}) and one file of common lines ({@code common.json}); a datapack can change a name or a line without moving anybody.
 */
public final class PopulationCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PopulationCatalog INSTANCE = new PopulationCatalog();

    /** A city's people: how many, how far from the centre they live, the role mix, the names and a few lines about the city itself. */
    record Def(String city, int count, int reachChunks, PopulationPlan.Mix mix, PopulationPlan.Names names, List<String> flavour) {}

    /** What a role says by period (morning, day, evening) and when it has nothing particular to say. */
    private volatile Map<String, Def> cities = Map.of();
    private volatile Map<String, Map<String, List<String>>> lines = Map.of();

    private PopulationCatalog() { super(new Gson(), "population"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Def> nextCities = new TreeMap<>();
        Map<String, Map<String, List<String>>> nextLines = new TreeMap<>();
        data.forEach((key, element) -> {
            JsonObject json = element.getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported population schema: " + key);
            if (json.has("city")) {
                String city = json.get("city").getAsString();
                if (!ProgressionRules.validCity(city)) throw new IllegalArgumentException("Invalid city in " + key);
                JsonObject mix = json.getAsJsonObject("mix"), names = json.getAsJsonObject("names");
                nextCities.put(city, new Def(city, json.get("count").getAsInt(), json.get("reach_chunks").getAsInt(),
                    new PopulationPlan.Mix(mix.get("resident").getAsInt(), mix.get("worker").getAsInt(), mix.get("courier").getAsInt(), mix.get("guard").getAsInt(), mix.has("visitor") ? mix.get("visitor").getAsInt() : 0),
                    new PopulationPlan.Names(strings(names.getAsJsonArray("first")), strings(names.getAsJsonArray("last"))), strings(json.getAsJsonArray("flavour"))));
            }
            if (json.has("lines")) {
                for (var role : json.getAsJsonObject("lines").entrySet()) {
                    Map<String, List<String>> byPeriod = new TreeMap<>();
                    for (var period : role.getValue().getAsJsonObject().entrySet()) byPeriod.put(period.getKey(), strings(period.getValue().getAsJsonArray()));
                    nextLines.put(role.getKey(), byPeriod);
                }
            }
        });
        for (Def def : nextCities.values()) {
            if (def.count() < 0 || def.count() > 1200 || def.reachChunks() < 1 || def.reachChunks() > 16) throw new IllegalArgumentException("Invalid population size or reach for " + def.city());
        }
        cities = Map.copyOf(nextCities);
        lines = Map.copyOf(nextLines);
        LOGGER.info("Loaded the people of {} cities and lines for {} roles", cities.size(), lines.size());
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsString());
        return List.copyOf(out);
    }

    Def city(String id) { return cities.get(id); }
    java.util.Set<String> cityIds() { return cities.keySet(); }

    /** The lines a role has for a period ("morning", "day", "evening"); never empty (falls back to the role's "any" lines, then to a plain greeting). */
    List<String> lines(String role, String period) {
        Map<String, List<String>> byPeriod = lines.get(role);
        if (byPeriod == null) return List.of("Good day.");
        List<String> specific = byPeriod.get(period);
        if (specific != null && !specific.isEmpty()) return specific;
        List<String> any = byPeriod.get("any");
        return any == null || any.isEmpty() ? List.of("Good day.") : any;
    }

    /** Test seam. */
    void replaceForTest(Map<String, Def> nextCities, Map<String, Map<String, List<String>>> nextLines) { cities = Map.copyOf(nextCities); lines = Map.copyOf(nextLines); }
}
