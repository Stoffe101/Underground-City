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
                      double witnessChance, String summary, Patrol patrol) {
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

    /**
     * The police on the beat (D-155): how many officers walk a beat while somebody is in the city, how many seconds pass between two beats, and how long a beat lasts. Optional: a city without one
     * has no patrol.
     */
    public record Patrol(int officers, int everySeconds, int beatSeconds) {}

    /**
     * An offence the police respond to (D-189): the charge read out at an arrest, the fine (paid up to what the wallet holds), the heat an arrest adds, how many seconds a witness's call takes to
     * bring officers, and whether an officer who sees a first, minor one gives a warning instead of an arrest. Each city may set its own in its law file ({@code offences}); what it leaves out has
     * the defaults below.
     */
    public record Offence(String id, String charge, int fine, int heat, int reportSeconds, boolean warnFirst) {
        public Offence {
            if (!id.matches("[a-z_]{3,32}") || charge.isBlank() || fine < 0 || fine > 1000 || heat < 0 || heat > 10 || reportSeconds < 5 || reportSeconds > 600)
                throw new IllegalArgumentException("Invalid offence " + id);
        }
    }

    public static final String ASSAULT = "assault", ASSAULT_OFFICER = "assault_officer", BREAK_IN = "break_in", ATTEMPTED_BREAK_IN = "attempted_break_in", THEFT = "theft",
        CRIMINAL_DAMAGE = "criminal_damage", TRESPASS = "trespass";

    /** The defaults: what every city charges unless its law says otherwise. */
    static final Map<String, Offence> DEFAULT_OFFENCES = Map.of(
        ASSAULT, new Offence(ASSAULT, "assault", 40, 2, 25, true),
        ASSAULT_OFFICER, new Offence(ASSAULT_OFFICER, "assaulting a police officer", 120, 4, 5, false),
        BREAK_IN, new Offence(BREAK_IN, "breaking and entering", 80, 3, 60, false),
        ATTEMPTED_BREAK_IN, new Offence(ATTEMPTED_BREAK_IN, "trying to break in", 50, 2, 60, false),
        THEFT, new Offence(THEFT, "burglary", 90, 3, 45, false),
        CRIMINAL_DAMAGE, new Offence(CRIMINAL_DAMAGE, "criminal damage", 60, 2, 45, false),
        TRESPASS, new Offence(TRESPASS, "trespassing", 25, 1, 60, true));

    private volatile Map<String, Law> laws = Map.of();
    private volatile Map<String, Map<String, Offence>> offences = Map.of();
    private CityLaws() { super(new Gson(), "city_laws"); }

    /** The offence as the city's law sets it, or the default. */
    public Offence offence(String city, String id) {
        Map<String, Offence> own = offences.get(city);
        Offence o = own == null ? null : own.get(id);
        if (o != null) return o;
        Offence d = DEFAULT_OFFENCES.get(id);
        if (d == null) throw new IllegalArgumentException("Unknown offence " + id);
        return d;
    }

    private static Map<String, Offence> offences(String city, JsonObject json) {
        Map<String, Offence> out = new LinkedHashMap<>();
        if (!json.has("offences")) return out;
        for (var entry : json.getAsJsonObject("offences").entrySet()) {
            Offence base = DEFAULT_OFFENCES.get(entry.getKey());
            if (base == null) throw new IllegalArgumentException("Unknown offence in the law of " + city + ": " + entry.getKey());
            JsonObject o = entry.getValue().getAsJsonObject();
            out.put(entry.getKey(), new Offence(base.id(), o.has("charge") ? o.get("charge").getAsString() : base.charge(), o.has("fine") ? o.get("fine").getAsInt() : base.fine(),
                o.has("heat") ? o.get("heat").getAsInt() : base.heat(), o.has("report_seconds") ? o.get("report_seconds").getAsInt() : base.reportSeconds(),
                o.has("warn_first") ? o.get("warn_first").getAsBoolean() : base.warnFirst()));
        }
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Law> next = new LinkedHashMap<>();
        Map<String, Map<String, Offence>> nextOffences = new LinkedHashMap<>();
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
                json.get("witness_chance").getAsDouble(), json.get("summary").getAsString(), patrol(json));
            validate(law);
            next.put(city, law);
            nextOffences.put(city, Map.copyOf(offences(city, json)));
        }
        for (String city : ProgressionRules.CITIES) if (!next.containsKey(city)) throw new IllegalArgumentException("Missing city law for " + city);
        laws = Map.copyOf(next);
        offences = Map.copyOf(nextOffences);
        LOGGER.info("Loaded {} Underground City city laws", laws.size());
    }

    private static Patrol patrol(JsonObject json) {
        if (!json.has("patrol")) return null;
        JsonObject patrol = json.getAsJsonObject("patrol");
        return new Patrol(patrol.get("officers").getAsInt(), patrol.get("every_seconds").getAsInt(), patrol.get("beat_seconds").getAsInt());
    }

    static void validate(Law law) {
        boolean ok = law.permitFine >= 0 && law.permitFine <= 500 && law.prohibitedFine >= law.permitFine && law.prohibitedFine <= 500
            && law.lowChance >= 0 && law.lowChance <= law.inspectionChance && law.inspectionChance <= law.highChance && law.highChance <= law.maxChance
            && law.maxChance <= 0.95 && law.heatStep >= 0 && law.heatStep <= 0.15 && law.witnessChance >= 0 && law.witnessChance <= 0.8
            && !law.summary.isBlank() && law.summary.length() <= 200
            && (law.patrol == null || (law.patrol.officers() >= 0 && law.patrol.officers() <= 3 && law.patrol.everySeconds() >= 60 && law.patrol.everySeconds() <= 1800
                && law.patrol.beatSeconds() >= 30 && law.patrol.beatSeconds() <= 600));
        if (!ok) throw new IllegalArgumentException("Invalid city law for " + law.city);
    }

    public Law find(String city) { return laws.get(city); }
    public Map<String, Law> all() { return laws; }

    /** Test seam. */
    void replaceForTest(Map<String, Law> replacement) { laws = Map.copyOf(replacement); }
}
