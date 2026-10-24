package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * What a generated city building is, as far as the rules of the world are concerned (D-105).
 *
 * <p>Lost Cities places buildings by a plan it can answer for any chunk without generating it (is it a city chunk, which building, how many floors and cellars, which city level). This class asks
 * that plan, and turns the answer into a {@link Building}: a protected volume (the chunk, from below its deepest cellar to above its roof) and a security class that says how hard its doors are to
 * open and what is inside. The classes and their rules are data ({@code data/underground_city/building_security/classes.json}); the mapping from Lost Cities' generic buildings to "workshop",
 * "apartment block" or "high-value premises" is a <b>heuristic by city, building type and height</b>, said so plainly in the documents: Lost Cities does not know what a building is for.
 *
 * <p>The plan is deterministic, so nothing here is persisted: an answer is recomputed or taken from a small cache. What is persisted (door states, which buildings have been furnished) is keyed by
 * block position elsewhere ({@link PropertyLocks}).
 */
public final class CityBuildings extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CityBuildings INSTANCE = new CityBuildings();
    /** Lost Cities' floor height in blocks. */
    public static final int FLOOR = 6;
    /** Lost Cities' default ground level (a fallback when its API cannot answer). */
    private static final int GROUND_FALLBACK = 71;

    /**
     * How a building answers a break-in that somebody notices (D-122): the chance its alarm is armed (decided once per building, by a hash, so a thief who has learned a building keeps what they learned),
     * how long the police take to arrive in active seconds, how many officers come, and how long they stay and look before they give up.
     */
    public record Response(double alarmChance, int dispatchSeconds, int officers, int windowSeconds) {
        public Response {
            if (alarmChance < 0 || alarmChance > 1) throw new IllegalArgumentException("alarm chance must be between 0 and 1");
            if (dispatchSeconds < 10 || dispatchSeconds > 600) throw new IllegalArgumentException("dispatch seconds must be between 10 and 600");
            if (officers < 1 || officers > 4) throw new IllegalArgumentException("officers must be between 1 and 4");
            if (windowSeconds < 30 || windowSeconds > 600) throw new IllegalArgumentException("window seconds must be between 30 and 600");
        }
    }

    /**
     * A class of building: how its doors are locked, what its stash holds and how it answers a noticed break-in ({@code response} is null for a building with no alarm of its own). Its
     * <b>tier</b> (D-144: 1 shabby to 6 exceptional, 0 not a target) says how well off it is, and so how hard it is and how much is worth taking; {@code safeProfile} is the lock of its safe.
     */
    public record SecurityClass(String id, String lockProfile, boolean lockedExterior, String stashPolicy, double stashChance, String label, Response response, int tier, String safeProfile) {
        public SecurityClass {
            if (tier < 0 || tier > 6) throw new IllegalArgumentException("tier of " + id + " must be 0 to 6");
            if (safeProfile == null || safeProfile.isEmpty()) safeProfile = defaultSafe(tier);
        }
        public SecurityClass(String id, String lockProfile, boolean lockedExterior, String stashPolicy, double stashChance, String label, Response response) {
            this(id, lockProfile, lockedExterior, stashPolicy, stashChance, label, response, lockedExterior ? defaultTier(lockProfile) : 0, "");
        }
        public SecurityClass(String id, String lockProfile, boolean lockedExterior, String stashPolicy, double stashChance, String label) { this(id, lockProfile, lockedExterior, stashPolicy, stashChance, label, null); }
    }

    /** The tier a class has when its data does not say: read from its lock. */
    static int defaultTier(String lockProfile) {
        return switch (lockProfile == null ? "" : lockProfile) { case "latch" -> 1; case "deadbolt" -> 2; case "commercial" -> 3; case "secured" -> 4; case "high_security" -> 5; default -> 2; };
    }

    /** The safe of a tier: a plain household safe up to tier 3, a wall safe (a safecracker's) at 4 and 5, a vault safe at 6. */
    static String defaultSafe(int tier) { return tier >= 6 ? "vault_safe" : tier >= 4 ? "wall_safe" : "safe"; }

    /** One rule of the heuristic: a city (or "*"), a building type prefix (or "") and a floor range choose a class; the first rule that fits wins. */
    public record Rule(String city, String typePrefix, int floorsMin, int floorsMax, String securityClass, int priority) {
        public Rule(String city, String typePrefix, int floorsMin, int floorsMax, String securityClass) { this(city, typePrefix, floorsMin, floorsMax, securityClass, 0); }
    }

    /** A generated building: its chunk, what Lost Cities says it is, and the volume that belongs to it. */
    public record Building(String city, int chunkX, int chunkZ, String type, String buildingId, int floors, int cellars, int streetY, SecurityClass security) {
        /** The lowest block that is part of the building (the floor of the deepest cellar and the foundation under it). */
        public int minY() { return streetY - FLOOR * (cellars + 1) - 2; }
        /** One above the highest block that is part of it (the roof and its parapet). */
        public int maxY() { return streetY + FLOOR * Math.max(1, floors) + 8; }
        public boolean contains(BlockPos pos) { return pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ && pos.getY() >= minY() && pos.getY() < maxY(); }
        /** A stable number for this building (for the hashes that choose its stash and its occupants). */
        public long key() { return (long) chunkX * 0x9E3779B97F4A7C15L + chunkZ * 0xBF58476D1CE4E5B9L; }
        public boolean locked() { return security != null && security.lockedExterior(); }
    }

    /**
     * A class's category (residential, commercial, industrial, office, special, public) and how far its tier may move by neighbourhood (D-157): a building near its city's centre tends to be
     * better off than its class, one at the edge worse, and a hash of the building decides the rest, so the same street has a range of targets. {@code min == max} keeps the class's tier.
     */
    public record Grade(String category, int min, int max) {
        public Grade {
            if (min < 0 || max > 6 || min > max) throw new IllegalArgumentException("a grade must be 0 <= min <= max <= 6");
        }
    }

    /** What a graded building of a category is locked with, holds and answers with at a tier (D-157): the security follows the tier, so a tier is what a thief meets at the door. */
    public record TierSecurity(String lockProfile, String stashPolicy, double stashChance, Response response) {}

    private volatile Map<String, SecurityClass> classes = Map.of();
    private volatile Map<String, Grade> grades = Map.of();
    private volatile Map<String, Map<Integer, TierSecurity>> tierSecurity = Map.of();
    private volatile Map<String, Double> cityBias = Map.of();
    private volatile List<Rule> rules = List.of();
    private volatile BiFunction<Integer, Integer, Optional<Building>> override;
    private final Map<Long, Optional<Building>> cache = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Optional<Building>> eldest) { return size() > 4096; }
    });

    private CityBuildings() { super(new Gson(), "building_security"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, SecurityClass> nextClasses = new TreeMap<>();
        Map<String, Grade> nextGrades = new TreeMap<>();
        Map<String, Map<Integer, TierSecurity>> nextTiers = new TreeMap<>();
        Map<String, Double> nextBias = new TreeMap<>();
        List<Rule> nextRules = new ArrayList<>();
        // files in name order and rules by descending priority, ties in file order: the answer must not depend on a hash map's order (D-117: a furnished building's own rule outranks the generic fallback)
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entryOf -> {
            var key = entryOf.getKey();
            var element = entryOf.getValue();
            JsonObject json = element.getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported building-security schema: " + key);
            if (json.has("classes")) for (var entry : json.getAsJsonObject("classes").entrySet()) {
                JsonObject c = entry.getValue().getAsJsonObject();
                String lock = c.has("lock_profile") ? c.get("lock_profile").getAsString() : "deadbolt";
                boolean locked = c.has("locked_exterior") && c.get("locked_exterior").getAsBoolean();
                nextClasses.put(entry.getKey(), new SecurityClass(entry.getKey(), lock, locked, c.has("stash_policy") ? c.get("stash_policy").getAsString() : "",
                    c.has("stash_chance") ? c.get("stash_chance").getAsDouble() : 0, c.has("label") ? c.get("label").getAsString() : entry.getKey(), response(c),
                    c.has("tier") ? c.get("tier").getAsInt() : locked ? defaultTier(lock) : 0, c.has("safe_profile") ? c.get("safe_profile").getAsString() : ""));
                SecurityClass made = nextClasses.get(entry.getKey());
                String category = c.has("category") ? c.get("category").getAsString() : made.tier() <= 0 ? "public" : "residential";
                int min = made.tier(), max = made.tier();
                if (c.has("grade")) { var range = c.getAsJsonArray("grade"); min = range.get(0).getAsInt(); max = range.get(1).getAsInt(); }
                if (made.tier() > 0 && (made.tier() < min || made.tier() > max)) throw new IllegalArgumentException("The grade of " + entry.getKey() + " must contain its tier");
                nextGrades.put(entry.getKey(), new Grade(category, min, max));
            }
            if (json.has("city_grade_bias")) for (var bias : json.getAsJsonObject("city_grade_bias").entrySet()) {
                double value = bias.getValue().getAsDouble();
                if (value < -1.0 || value > 1.0) throw new IllegalArgumentException("A city's grade bias must be between -1 and 1: " + bias.getKey());
                nextBias.put(bias.getKey(), value);
            }
            if (json.has("tier_security")) for (var byCategory : json.getAsJsonObject("tier_security").entrySet()) {
                Map<Integer, TierSecurity> tiers = nextTiers.computeIfAbsent(byCategory.getKey(), k -> new TreeMap<>());
                for (var byTier : byCategory.getValue().getAsJsonObject().entrySet()) {
                    JsonObject t = byTier.getValue().getAsJsonObject();
                    int tier = Integer.parseInt(byTier.getKey());
                    if (tier < 1 || tier > 6) throw new IllegalArgumentException("tier_security tiers are 1 to 6: " + byCategory.getKey() + " " + tier);
                    tiers.put(tier, new TierSecurity(t.get("lock_profile").getAsString(), t.get("stash_policy").getAsString(), t.get("stash_chance").getAsDouble(), response(t)));
                }
            }
            if (json.has("rules")) for (var entry : json.getAsJsonArray("rules")) {
                JsonObject r = entry.getAsJsonObject();
                nextRules.add(new Rule(r.has("city") ? r.get("city").getAsString() : "*", r.has("type_prefix") ? r.get("type_prefix").getAsString() : "",
                    r.has("floors_min") ? r.get("floors_min").getAsInt() : 0, r.has("floors_max") ? r.get("floors_max").getAsInt() : 99, r.get("class").getAsString(), r.has("priority") ? r.get("priority").getAsInt() : 0));
            }
        });
        for (Rule rule : nextRules) if (!nextClasses.containsKey(rule.securityClass())) throw new IllegalArgumentException("A building rule names the unknown class " + rule.securityClass());
        for (SecurityClass c : nextClasses.values()) if (c.stashChance() < 0 || c.stashChance() > 1) throw new IllegalArgumentException("Stash chance of " + c.id() + " must be between 0 and 1");
        for (var g : nextGrades.entrySet()) {
            if (g.getValue().min() == g.getValue().max()) continue;
            for (int t = g.getValue().min(); t <= g.getValue().max(); t++)
                if (t != nextClasses.get(g.getKey()).tier() && !nextTiers.getOrDefault(g.getValue().category(), Map.of()).containsKey(t))
                    throw new IllegalArgumentException("The grade of " + g.getKey() + " reaches tier " + t + ", which " + g.getValue().category() + " has no security for");
        }
        classes = Map.copyOf(nextClasses);
        grades = Map.copyOf(nextGrades);
        tierSecurity = Map.copyOf(nextTiers);
        cityBias = Map.copyOf(nextBias);
        rules = List.copyOf(ordered(nextRules));
        cache.clear();
        LOGGER.info("Loaded {} building security classes and {} rules", classes.size(), rules.size());
    }

    private static Response response(JsonObject c) {
        if (!c.has("response")) return null;
        JsonObject r = c.getAsJsonObject("response");
        return new Response(r.get("alarm_chance").getAsDouble(), r.get("dispatch_seconds").getAsInt(), r.get("officers").getAsInt(), r.get("window_seconds").getAsInt());
    }

    /** Rules by descending priority; equal priorities keep their order (the file order). */
    static List<Rule> ordered(List<Rule> rules) {
        List<Rule> copy = new ArrayList<>(rules);
        copy.sort(java.util.Comparator.comparingInt(Rule::priority).reversed());
        return copy;
    }

    public SecurityClass securityClass(String id) { return classes.get(id); }

    /** The category of a class (residential, commercial, industrial, office, special, public). */
    public String category(String classId) {
        Grade grade = classId == null ? null : grades.get(classId);
        return grade == null ? "residential" : grade.category();
    }

    /** The grade of a class, if it has one. */
    public Optional<Grade> grade(String classId) { return Optional.ofNullable(classId == null ? null : grades.get(classId)); }

    /**
     * How far a building's tier moves from its class's (D-157), from where it stands ({@code r}: 0 at its city's centre, 1 at the edge) and a hash of it in [0, 1): near the centre it is often
     * one better, at the edge often one worse, two either way only at the extremes. Pure, so a test can pin the distribution.
     */
    static int gradeOffset(double r, double noise) { return gradeOffset(r, noise, 0); }

    /** The same with a city's lean ({@code city_grade_bias}): Blackwater's docks and back streets are poorer than its towers, Valewood's middle better off than its lanes. */
    static int gradeOffset(double r, double noise, double bias) {
        double score = (0.5 - Math.min(1.2, Math.max(0, r))) * 2.0 + (noise - 0.5) * 1.6 + bias;
        return score >= 1.45 ? 2 : score >= 0.7 ? 1 : score <= -1.45 ? -2 : score <= -0.7 ? -1 : 0;
    }

    /** The class a building actually has once its neighbourhood is counted: the same class, or one with the tier, lock, stash and response of its graded tier. */
    SecurityClass graded(SecurityClass base, String city, int cx, int cz, long seed) {
        if (base == null || base.tier() <= 0) return base;
        Grade grade = grades.get(base.id());
        if (grade == null || grade.min() == grade.max()) return base;
        CityRegions.Region region = CityRegions.INSTANCE.region(city).orElse(null);
        if (region == null) return base;
        double r = Math.hypot(cx - region.centerChunkX(), cz - region.centerChunkZ()) * 16.0 / Math.max(16, region.cityRadius());
        long h = PopulationPlan.mix(seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cz * 0xC2B2AE3D27D4EB4FL) ^ 0x6A09E667L);
        double noise = (h >>> 11) / (double) (1L << 53);
        int tier = Math.max(grade.min(), Math.min(grade.max(), base.tier() + gradeOffset(r, noise, cityBias.getOrDefault(city, 0.0))));
        if (tier == base.tier()) return base;
        TierSecurity t = tierSecurity.getOrDefault(grade.category(), Map.of()).get(tier);
        if (t == null) return base;
        return new SecurityClass(base.id(), t.lockProfile(), base.lockedExterior(), t.stashPolicy(), t.stashChance(), base.label(), t.response(), tier, "");
    }
    public Map<String, SecurityClass> classes() { return classes; }

    /** The class a building of this type and height in this city belongs to: the first rule that fits, else none (an unclassed building is protected but has no lock and no stash). */
    public SecurityClass classify(String city, String type, int floors) {
        String kind = type == null ? "" : type;
        for (Rule rule : rules) {
            boolean cityFits = rule.city().equals("*") || rule.city().equals(city);
            boolean typeFits = rule.typePrefix().isEmpty() || kind.startsWith(rule.typePrefix());
            if (cityFits && typeFits && floors >= rule.floorsMin() && floors <= rule.floorsMax()) return classes.get(rule.securityClass());
        }
        return null;
    }

    /** The building whose protected volume contains the position, if the position is in a Lost Cities city building at all. Never generates a chunk. */
    public Optional<Building> at(ServerLevel level, BlockPos pos) {
        Optional<Building> building = ofChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
        return building.filter(b -> pos.getY() >= b.minY() && pos.getY() < b.maxY());
    }

    /** True when the plan of a chunk is already known, so asking about it costs nothing (a first question to Lost Cities costs tens of milliseconds). */
    public boolean known(int cx, int cz) { return override != null || cache.containsKey(ChunkPos.asLong(cx, cz)); }

    /** The building plan of a chunk (cached). Empty for a chunk that is not a city building. */
    public Optional<Building> ofChunk(ServerLevel level, int cx, int cz) {
        BiFunction<Integer, Integer, Optional<Building>> seam = override;
        if (seam != null) return seam.apply(cx, cz);
        if (!WorldLayout.regional(level.getChunkSource().getGenerator()) || !LostCitiesLink.ready()) return Optional.empty();
        long key = ChunkPos.asLong(cx, cz);
        Optional<Building> cached = cache.get(key);
        if (cached != null) return cached;
        Optional<Building> computed = compute(level, cx, cz);
        cache.put(key, computed);
        return computed;
    }

    private Optional<Building> compute(ServerLevel level, int cx, int cz) {
        // cheap exit for the countryside: only chunks inside a city region's own circle are asked of Lost Cities at all
        var region = CityRegions.INSTANCE.cityAt(level, new BlockPos(cx * 16 + 8, level.getSeaLevel(), cz * 16 + 8));
        if (region.isEmpty()) return Optional.empty();
        Optional<LostCitiesLink.ChunkSample> sample = LostCitiesLink.chunk(level, cx, cz);
        if (sample.isEmpty() || !sample.get().city()) return Optional.empty();
        LostCitiesLink.ChunkSample s = sample.get();
        String type = s.building() == null ? "" : s.building();
        if (type.isEmpty() && s.buildingId().isEmpty()) return Optional.empty();   // a street, a park or open ground: nothing stands here to protect
        int street = LostCitiesLink.realHeight(level, s.cityLevel());
        if (street < 0) street = GROUND_FALLBACK + s.cityLevel() * FLOOR;
        SecurityClass security = graded(classify(region.get(), type, s.floors()), region.get(), cx, cz, level.getSeed());
        return Optional.of(new Building(region.get(), cx, cz, type, s.buildingId(), s.floors(), s.cellars(), street, security));
    }

    /** Test seam: answer every building question from a function (null to stop). */
    void overrideForTest(BiFunction<Integer, Integer, Optional<Building>> seam) { override = seam; cache.clear(); }

    /** Test seam. */
    void replaceForTest(Map<String, SecurityClass> nextClasses, List<Rule> nextRules) { classes = Map.copyOf(nextClasses); rules = List.copyOf(nextRules); cache.clear(); }

    /** Test seam: the grades and the tier table as loaded (to restore after {@link #replaceGradesForTest}). */
    Map<String, Grade> gradesForTest() { return grades; }
    Map<String, Map<Integer, TierSecurity>> tierSecurityForTest() { return tierSecurity; }
    void replaceGradesForTest(Map<String, Grade> nextGrades, Map<String, Map<Integer, TierSecurity>> nextTiers) { grades = Map.copyOf(nextGrades); tierSecurity = Map.copyOf(nextTiers); cache.clear(); }
}
