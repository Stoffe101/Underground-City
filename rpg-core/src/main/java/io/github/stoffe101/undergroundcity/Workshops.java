package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workshops of the trades (D-163): data ({@code data/underground_city/workshops/*.json}), one file a station. A station belongs to a career, comes in up to three tiers (a better tier runs more
 * at once, works faster and opens more recipes, and is reached by fitting real parts to it), and has recipes: what goes in, what comes out, how long it takes in active play, the tier and the skill it
 * needs, and whether what it makes is graded (Standard, Fine or Reserve). {@link WorkshopBlock} is the station in the world; the server holds every batch.
 */
public final class Workshops extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Workshops INSTANCE = new Workshops();

    /** The tag an item made at a graded recipe carries: 0 Standard (no tag), 1 Fine, 2 Reserve. */
    public static final String QUALITY = "uc_quality";
    static final String[] GRADES = {"Standard", "Fine", "Reserve"};
    /** What a buyer pays for each grade, against Standard. */
    static final double[] GRADE_PRICE = {1.0, 1.25, 1.6};

    /** A tier of a station: how many batches run at once, how fast, and (from tier 2) the parts and skill it takes to fit. */
    public record Tier(int tier, String name, int batch, double speed, Map<ResourceLocation, Integer> parts, String skill) {}

    /** How a graded recipe's grade is decided: a base, a step at tier 2 and at tier 3, a step for each skill held, the grade of what went in when {@code fromInput} (ageing), and a ceiling. */
    public record Grading(int base, int tier2, int tier3, Map<String, Integer> skills, boolean fromInput, int max) {
        static final Grading NONE = new Grading(0, 0, 0, Map.of(), false, 0);
    }

    /** One recipe. {@code seconds} of active play for one batch at tier 1 (0 makes it at once); {@code mod} names a mod whose items it needs (it is hidden without it). */
    public record Recipe(String id, String title, Map<ResourceLocation, Integer> inputs, ResourceLocation output, int count, int seconds, int tier, String skill, int xp, Grading grading,
                         String legality, String mod, String note) {
        boolean graded() { return grading.max() > 0; }
    }

    public record Station(String id, String career, String title, String note, List<Tier> tiers, List<Recipe> recipes) {
        Tier tier(int t) { return tiers.get(Math.max(0, Math.min(tiers.size() - 1, t - 1))); }
        Recipe recipe(String id) { for (Recipe r : recipes) if (r.id().equals(id)) return r; return null; }
    }

    private volatile Map<String, Station> stations = Map.of();

    private Workshops() { super(new Gson(), "workshops"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Station> next = new LinkedHashMap<>();
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
            JsonObject json = file.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported workshop schema: " + file.getKey());
            Station station = parse(json);
            if (next.put(station.id(), station) != null) throw new IllegalArgumentException("Two workshops are called " + station.id());
        });
        stations = Map.copyOf(next);
        LOGGER.info("Loaded {} workshop stations with {} recipes", stations.size(), stations.values().stream().mapToInt(s -> s.recipes().size()).sum());
    }

    static Station parse(JsonObject json) {
        String id = json.get("id").getAsString();
        List<Tier> tiers = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("tiers")) {
            JsonObject t = e.getAsJsonObject();
            JsonObject up = t.has("upgrade") ? t.getAsJsonObject("upgrade") : new JsonObject();
            Tier tier = new Tier(t.get("tier").getAsInt(), t.get("name").getAsString(), t.get("batch").getAsInt(), t.get("speed").getAsDouble(),
                up.has("parts") ? counts(up.getAsJsonObject("parts"), id) : Map.of(), up.has("skill") ? up.get("skill").getAsString() : "");
            if (tier.tier() != tiers.size() + 1 || tier.batch() < 1 || tier.batch() > 8 || tier.speed() < 1 || tier.speed() > 4 || tier.tier() > 1 && tier.parts().isEmpty())
                throw new IllegalArgumentException("Invalid tier " + tier.tier() + " of workshop " + id);
            tiers.add(tier);
        }
        if (tiers.isEmpty() || tiers.size() > 3) throw new IllegalArgumentException("A workshop has one to three tiers: " + id);
        List<Recipe> recipes = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("recipes")) {
            JsonObject r = e.getAsJsonObject();
            Grading grading = Grading.NONE;
            if (r.has("grading")) {
                JsonObject g = r.getAsJsonObject("grading");
                Map<String, Integer> skills = new LinkedHashMap<>();
                if (g.has("skills")) for (var s : g.getAsJsonObject("skills").entrySet()) skills.put(s.getKey(), s.getValue().getAsInt());
                grading = new Grading(g.has("base") ? g.get("base").getAsInt() : 0, g.has("tier2") ? g.get("tier2").getAsInt() : 0, g.has("tier3") ? g.get("tier3").getAsInt() : 0,
                    Map.copyOf(skills), g.has("from_input") && g.get("from_input").getAsBoolean(), g.has("max") ? g.get("max").getAsInt() : 2);
            }
            Recipe recipe = new Recipe(r.get("id").getAsString(), r.get("title").getAsString(), counts(r.getAsJsonObject("inputs"), id), ResourceLocation.parse(r.get("output").getAsString()),
                r.get("count").getAsInt(), r.get("seconds").getAsInt(), r.has("tier") ? r.get("tier").getAsInt() : 1, r.has("skill") ? r.get("skill").getAsString() : "",
                r.has("xp") ? r.get("xp").getAsInt() : 0, grading, r.has("legality") ? r.get("legality").getAsString() : "legal", r.has("mod") ? r.get("mod").getAsString() : "",
                r.has("note") ? r.get("note").getAsString() : "");
            if (recipe.inputs().isEmpty() || recipe.count() < 1 || recipe.count() > 16 || recipe.seconds() < 0 || recipe.seconds() > 3 * 3600 || recipe.tier() < 1 || recipe.tier() > tiers.size()
                || recipe.xp() < 0 || recipe.xp() > 200 || recipe.grading().max() > 2 || !(recipe.legality().equals("legal") || recipe.legality().equals("underground")))
                throw new IllegalArgumentException("Invalid recipe " + recipe.id() + " of workshop " + id);
            recipes.add(recipe);
        }
        return new Station(id, json.get("career").getAsString(), json.get("title").getAsString(), json.has("note") ? json.get("note").getAsString() : "", List.copyOf(tiers), List.copyOf(recipes));
    }

    private static Map<ResourceLocation, Integer> counts(JsonObject json, String where) {
        Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
        for (var in : json.entrySet()) {
            int n = in.getValue().getAsInt();
            if (n < 1 || n > 64) throw new IllegalArgumentException("A count must be 1 to 64 in workshop " + where + ": " + in.getKey());
            out.put(ResourceLocation.parse(in.getKey()), n);
        }
        return Map.copyOf(out);
    }

    public Station station(String id) { return stations.get(id); }
    public Map<String, Station> all() { return stations; }

    /** Test seam: replaces the stations (null restores nothing; tests put back what they took). */
    void replaceForTest(Map<String, Station> next) { stations = Map.copyOf(next); }

    // ------------------------------------------------------------------------------------------------ grades

    /** The grade an item carries (0 Standard, 1 Fine, 2 Reserve). */
    public static int grade(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(QUALITY) ? Math.max(0, Math.min(2, stack.getTag().getInt(QUALITY))) : 0;
    }

    /** Marks a stack with a grade (Standard leaves it untagged, so it stacks with anything else of its kind). */
    public static ItemStack graded(ItemStack stack, int grade) {
        if (grade > 0) stack.getOrCreateTag().putInt(QUALITY, Math.min(2, grade));
        return stack;
    }

    /** The grade a batch comes out at: from the recipe's grading, the station's tier, the skills held and (for ageing) the grade of what went in. */
    static int gradeFor(Recipe recipe, int tier, java.util.function.Predicate<String> hasSkill, int inputGrade) {
        if (!recipe.graded()) return 0;
        Grading g = recipe.grading();
        int grade = g.base() + (tier >= 2 ? g.tier2() : 0) + (tier >= 3 ? g.tier3() : 0) + (g.fromInput() ? inputGrade : 0);
        for (var s : g.skills().entrySet()) if (hasSkill.test(s.getKey())) grade += s.getValue();
        return Math.max(0, Math.min(g.max(), grade));
    }

    /** Ticks of active play a batch takes at a tier. */
    static long ticksFor(Recipe recipe, Tier tier) { return Math.max(0, Math.round(recipe.seconds() * 20 / tier.speed())); }
}
