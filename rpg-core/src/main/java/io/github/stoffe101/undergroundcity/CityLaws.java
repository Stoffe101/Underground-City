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
 * Per-city law (D-053): for each contraband category the status is {@code legal}, {@code permit}
 * (legal if the citizen holds the licence skill) or {@code prohibited}; plus how often the guard searches
 * at a gate, how much a seizure costs, and how likely a hand-off is to be seen. Possession alone is never
 * detected: these numbers only matter at an inspection or a witnessed hand-off.
 */
public final class CityLaws extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CityLaws INSTANCE = new CityLaws();

    public static final String LEGAL = "legal", PERMIT = "permit", PROHIBITED = "prohibited";

    public record Law(String city, Map<String, String> categories, int permitFine, int prohibitedFine,
                      double lowChance, double inspectionChance, double highChance, double heatStep, double maxChance,
                      double witnessChance, String summary) {
        public String status(String category) { return categories.getOrDefault(category, LEGAL); }

        public double baseChance(String risk) {
            return switch (risk) {
                case "low" -> lowChance;
                case "high" -> highChance;
                default -> inspectionChance;
            };
        }

        public int fine(String status) { return status.equals(PROHIBITED) ? prohibitedFine : status.equals(PERMIT) ? permitFine : 0; }
    }

    private volatile Map<String, Law> laws = Map.of();
    private CityLaws() { super(new Gson(), "city_laws"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Law> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported city-law schema: " + entry.getKey());
            String city = json.get("city").getAsString();
            if (!ProgressionRules.validCity(city) || !entry.getKey().getPath().equals(city)) throw new IllegalArgumentException("City law must be named after its city: " + entry.getKey());
            Map<String, String> categories = new LinkedHashMap<>();
            for (var category : json.getAsJsonObject("categories").entrySet()) {
                String status = category.getValue().getAsString();
                if (!category.getKey().matches("[a-z_]{3,40}") || !(status.equals(LEGAL) || status.equals(PERMIT) || status.equals(PROHIBITED))) {
                    throw new IllegalArgumentException("Bad law entry for " + city + ": " + category.getKey());
                }
                categories.put(category.getKey(), status);
            }
            JsonObject inspection = json.getAsJsonObject("inspection"), fines = json.getAsJsonObject("fines");
            Law law = new Law(city, Map.copyOf(categories), fines.get(PERMIT).getAsInt(), fines.get(PROHIBITED).getAsInt(),
                inspection.get("low").getAsDouble(), inspection.get("inspection").getAsDouble(), inspection.get("high").getAsDouble(),
                inspection.get("heat_step").getAsDouble(), inspection.get("max").getAsDouble(),
                json.get("witness_chance").getAsDouble(), json.get("summary").getAsString());
            validate(law);
            next.put(city, law);
        }
        for (String city : ProgressionRules.CITIES) if (!next.containsKey(city)) throw new IllegalArgumentException("Missing city law for " + city);
        laws = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City city laws", laws.size());
    }

    static void validate(Law law) {
        boolean ok = law.permitFine >= 0 && law.permitFine <= 500 && law.prohibitedFine >= law.permitFine && law.prohibitedFine <= 500
            && law.lowChance >= 0 && law.lowChance <= law.inspectionChance && law.inspectionChance <= law.highChance && law.highChance <= law.maxChance
            && law.maxChance <= 0.95 && law.heatStep >= 0 && law.heatStep <= 0.15 && law.witnessChance >= 0 && law.witnessChance <= 0.8
            && !law.summary.isBlank() && law.summary.length() <= 200;
        if (!ok) throw new IllegalArgumentException("Invalid city law for " + law.city);
    }

    public Law find(String city) { return laws.get(city); }
    public Map<String, Law> all() { return laws; }

    /** Test seam. */
    void replaceForTest(Map<String, Law> replacement) { laws = Map.copyOf(replacement); }
}
