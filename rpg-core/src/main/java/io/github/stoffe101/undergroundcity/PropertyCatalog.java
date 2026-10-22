package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.stoffe101.undergroundcity.PropertyRules.Rules;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import io.github.stoffe101.undergroundcity.PropertyRules.Terms;
import io.github.stoffe101.undergroundcity.PropertyRules.Upgrade;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The property ladder as data (D-099): the global rent rules, the listings (what each registered home costs and allows) and the upgrades. Read from
 * {@code data/underground_city/properties/*.json}; {@code ladder.json} carries the rules and upgrades, any file may carry listings, so a street can ship its
 * own. A bad file aborts the reload and keeps the previous catalog. A registered home with no listing keeps the old rules (a one-time price, no rent,
 * no upkeep, no storage limit beyond the four private containers), which is also how homes from older worlds and from tests behave.
 */
public final class PropertyCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PropertyCatalog INSTANCE = new PropertyCatalog();

    private volatile Rules rules = Rules.DEFAULT;
    private volatile Map<String, Terms> listings = Map.of();
    private volatile Map<String, Upgrade> upgrades = Map.of();
    private volatile Map<String, String> categoryTitles = Map.of();
    /** The furnished cities' listing templates (D-127), by building type: the share of a kind of building that is to let and the terms of each of its units. */
    public record CityTemplate(String building, int sharePercent, Terms terms) {}
    private volatile Map<String, CityTemplate> cityTemplates = Map.of();

    private PropertyCatalog() { super(new Gson(), "properties"); }

    public Optional<CityTemplate> cityTemplate(String buildingType) { return Optional.ofNullable(cityTemplates.get(buildingType)); }
    /** Test seam: replaces the city templates and returns the previous ones. */
    Map<String, CityTemplate> installCityTemplatesForTest(Map<String, CityTemplate> next) { Map<String, CityTemplate> previous = cityTemplates; cityTemplates = Map.copyOf(next); return previous; }
    public Map<String, CityTemplate> cityTemplates() { return cityTemplates; }

    public Rules rules() { return rules; }
    public Optional<Terms> find(String residenceId) {
        Terms listed = listings.get(residenceId);
        if (listed != null || residenceId == null || !residenceId.startsWith(CityHomes.PREFIX)) return Optional.ofNullable(listed);
        // a unit of a furnished city building: the terms of its building's template, under the unit's own id
        return CityHomes.typeOf(residenceId).flatMap(this::cityTemplate).map(t -> t.terms().withId(residenceId));
    }
    public Map<String, Terms> all() { return listings; }
    public Upgrade upgrade(String id) { return upgrades.get(id); }
    public Map<String, Upgrade> upgrades() { return upgrades; }
    public String categoryTitle(String category) { return categoryTitles.getOrDefault(category, category); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Rules nextRules = rules;
        Map<String, Terms> nextListings = new LinkedHashMap<>();
        Map<String, Upgrade> nextUpgrades = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        Map<String, CityTemplate> nextTemplates = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported property schema: " + entry.getKey());
            if (json.has("rules")) nextRules = parseRules(json.getAsJsonObject("rules"));
            if (json.has("categories")) for (JsonElement c : json.getAsJsonArray("categories")) titles.put(c.getAsJsonObject().get("id").getAsString(), c.getAsJsonObject().get("title").getAsString());
            if (json.has("upgrades")) {
                for (JsonElement element : json.getAsJsonArray("upgrades")) {
                    Upgrade upgrade = parseUpgrade(element.getAsJsonObject());
                    if (nextUpgrades.put(upgrade.id(), upgrade) != null) throw new IllegalArgumentException("Upgrade listed twice: " + upgrade.id());
                }
            }
            if (json.has("city_listings")) {
                for (JsonElement element : json.getAsJsonArray("city_listings")) {
                    JsonObject t = element.getAsJsonObject().deepCopy();
                    String building = t.get("building").getAsString();
                    int share = t.get("share_percent").getAsInt();
                    if (share < 0 || share > 100) throw new IllegalArgumentException("Bad share for " + building);
                    t.addProperty("id", "template/" + building.replace(':', '/'));
                    t.addProperty("title", titles.getOrDefault(t.get("category").getAsString(), t.get("category").getAsString()));
                    if (nextTemplates.put(building, new CityTemplate(building, share, parseTerms(t, nextRules))) != null) throw new IllegalArgumentException("Building templated twice: " + building);
                }
            }
            if (json.has("listings")) {
                for (JsonElement element : json.getAsJsonArray("listings")) {
                    Terms terms = parseTerms(element.getAsJsonObject(), nextRules);
                    if (nextListings.put(terms.id(), terms) != null) throw new IllegalArgumentException("Home listed twice: " + terms.id());
                }
            }
        }
        if (nextListings.size() > 256) throw new IllegalArgumentException("At most 256 listings supported");
        rules = nextRules;
        listings = Map.copyOf(nextListings);
        upgrades = java.util.Collections.unmodifiableMap(nextUpgrades);   // file order: the screens and the upgrade request count the same list
        categoryTitles = Map.copyOf(titles);
        cityTemplates = Map.copyOf(nextTemplates);
        LOGGER.info("Loaded {} Underground City property listings, {} city building templates and {} upgrades", listings.size(), cityTemplates.size(), upgrades.size());
    }

    static Rules parseRules(JsonObject json) {
        Map<String, Integer> factors = new LinkedHashMap<>();
        if (json.has("city_price_factor")) {
            for (var e : json.getAsJsonObject("city_price_factor").entrySet()) factors.put(e.getKey(), (int) Math.round(e.getValue().getAsDouble() * 100));
        }
        Rules rules = new Rules(json.get("cycle_minutes").getAsInt(), json.get("prepay_max_cycles").getAsInt(), json.get("grace_cycles").getAsInt(),
            json.get("notice_cycles").getAsInt(), json.get("deposit_cycles").getAsInt(), json.get("sell_back_percent").getAsInt(), json.get("welcome_motel_cycles").getAsInt(), factors);
        if (rules.cycleMinutes() < 5 || rules.cycleMinutes() > 600 || rules.prepayMaxCycles() < 1 || rules.prepayMaxCycles() > 24 || rules.graceCycles() < 1 || rules.graceCycles() > 12
            || rules.noticeCycles() < 1 || rules.noticeCycles() > 6 || rules.depositCycles() < 0 || rules.depositCycles() > 6 || rules.sellBackPercent() < 0 || rules.sellBackPercent() >= 100
            || rules.welcomeMotelCycles() < 0 || rules.welcomeMotelCycles() > 12) {
            throw new IllegalArgumentException("Invalid property rules");
        }
        for (int f : factors.values()) if (f < 50 || f > 200) throw new IllegalArgumentException("A city price factor must be between 0.5 and 2.0");
        return rules;
    }

    static Upgrade parseUpgrade(JsonObject json) {
        Set<String> facilities = new LinkedHashSet<>();
        if (json.has("add_facilities")) for (JsonElement f : json.getAsJsonArray("add_facilities")) facilities.add(f.getAsString());
        Upgrade upgrade = new Upgrade(json.get("id").getAsString(), json.get("title").getAsString(), json.get("cost").getAsInt(),
            json.has("add_containers") ? json.get("add_containers").getAsInt() : 0, json.has("add_private_claims") ? json.get("add_private_claims").getAsInt() : 0,
            facilities, json.has("summary") ? json.get("summary").getAsString() : "");
        if (!upgrade.id().matches("[a-z_]{3,32}") || upgrade.title().isBlank() || upgrade.title().length() > 32 || upgrade.cost() < 1 || upgrade.cost() > 100_000
            || upgrade.addContainers() < 0 || upgrade.addContainers() > 16 || upgrade.addClaims() < 0 || upgrade.addClaims() > 4 || upgrade.summary().length() > 200) {
            throw new IllegalArgumentException("Invalid property upgrade " + upgrade.id());
        }
        for (String facility : upgrade.addFacilities()) if (!PropertyRules.KNOWN_FACILITIES.contains(facility)) throw new IllegalArgumentException("Unknown facility " + facility);
        return upgrade;
    }

    static Terms parseTerms(JsonObject json, Rules rules) {
        Set<Tenure> tenures = new LinkedHashSet<>();
        for (JsonElement t : json.getAsJsonArray("tenures")) {
            switch (t.getAsString()) {
                case "rent" -> tenures.add(Tenure.RENT);
                case "buy" -> tenures.add(Tenure.OWN);
                default -> throw new IllegalArgumentException("Unknown tenure " + t.getAsString());
            }
        }
        Set<String> facilities = new LinkedHashSet<>();
        for (JsonElement f : json.getAsJsonArray("facilities")) facilities.add(f.getAsString());
        Terms terms = new Terms(json.get("id").getAsString(), json.get("title").getAsString(), json.get("category").getAsString(), json.has("tier") ? json.get("tier").getAsInt() : 0,
            json.has("area") ? json.get("area").getAsString() : "", tenures, json.get("rent").getAsInt(), json.get("deposit").getAsInt(), json.get("buy_price").getAsInt(),
            json.get("upkeep").getAsInt(), json.get("max_containers").getAsInt(), json.get("private_claims").getAsInt(), facilities, json.has("summary") ? json.get("summary").getAsString() : "");
        if (!terms.id().matches("[a-z0-9_:.\\-/]{3,64}") || terms.title().isBlank() || terms.title().length() > 40 || terms.tenures().isEmpty()
            || terms.rent() < 0 || terms.rent() > 100_000 || terms.deposit() < 0 || terms.deposit() > terms.rent() * 6 || terms.buyPrice() < 0 || terms.buyPrice() > 1_000_000
            || terms.upkeep() < 0 || terms.upkeep() > terms.rent() * 2 + 1 || terms.maxContainers() < 0 || terms.maxContainers() > 64 || terms.privateClaims() < 0 || terms.privateClaims() > 8
            || terms.summary().length() > 200) {
            throw new IllegalArgumentException("Invalid property listing " + terms.id());
        }
        if (terms.tenures().contains(Tenure.RENT) && terms.rent() < 1) throw new IllegalArgumentException(terms.id() + " can be rented but has no rent");
        if (terms.tenures().contains(Tenure.OWN) && terms.buyPrice() < 1) throw new IllegalArgumentException(terms.id() + " can be bought but has no price");
        for (String facility : terms.facilities()) if (!PropertyRules.KNOWN_FACILITIES.contains(facility)) throw new IllegalArgumentException("Unknown facility " + facility + " in " + terms.id());
        return terms;
    }

    /** Test seam: replaces the catalog for the duration of a GameTest. Production only ever loads from data. */
    public void install(Rules newRules, Map<String, Terms> newListings, Map<String, Upgrade> newUpgrades) {
        rules = newRules; listings = Map.copyOf(newListings); upgrades = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(newUpgrades));
    }

    /** Titles for the screens. */
    public List<String> categoriesInOrder() { return new ArrayList<>(categoryTitles.keySet()); }
}
