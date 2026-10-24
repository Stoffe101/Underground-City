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
 * Vanilla datapack reload hook for the five career skill trees; invalid data aborts the replacement. The Thief tree every career can follow (D-143) lives in its own folder,
 * {@code specialization_trees}, read by {@link Specializations}, and is looked up through this catalog like any other skill.
 */
public final class SkillCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final SkillCatalog INSTANCE = new SkillCatalog();
    private volatile Map<String, List<SkillNode>> trees = Map.of();
    private volatile Map<String, SkillNode> byId = Map.of();
    private volatile List<SkillNode> thief = List.of();
    private volatile Map<String, SkillNode> thiefById = Map.of();
    private SkillCatalog() { super(new Gson(), "skill_trees"); }

    /** The reload listener of the specialization trees (only the Thief tree so far). */
    public static final class Specializations extends SimpleJsonResourceReloadListener {
        public static final Specializations INSTANCE = new Specializations();
        private Specializations() { super(new Gson(), "specialization_trees"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            List<SkillNode> nodes = new ArrayList<>();
            for (var entry : data.entrySet()) {
                if (!entry.getKey().getPath().equals(SkillNode.THIEF)) throw new IllegalArgumentException("Unknown specialization tree " + entry.getKey());
                JsonObject json = entry.getValue().getAsJsonObject();
                if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported specialization schema: " + entry.getKey());
                for (JsonElement element : json.getAsJsonArray("nodes")) nodes.add(parse(SkillNode.THIEF, element.getAsJsonObject()));
            }
            if (nodes.isEmpty()) throw new IllegalArgumentException("Missing the Thief tree (specialization_trees/thief.json)");
            SkillRules.validateThiefTree(nodes);
            Map<String, SkillNode> index = new LinkedHashMap<>();
            for (SkillNode node : nodes) index.put(node.id(), node);
            SkillCatalog.INSTANCE.thief = List.copyOf(nodes);
            SkillCatalog.INSTANCE.thiefById = Map.copyOf(index);
            LOGGER.info("Loaded the Thief tree ({} skills)", nodes.size());
        }
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, List<SkillNode>> nextTrees = new LinkedHashMap<>();
        Map<String, SkillNode> nextIndex = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported skill-tree schema: " + entry.getKey());
            }
            String career = json.get("career").getAsString();
            if (!ProgressionRules.validCareer(career) || !entry.getKey().getPath().equals(career)) {
                throw new IllegalArgumentException("Skill tree file must be named after its career: " + entry.getKey());
            }
            List<SkillNode> nodes = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("nodes")) nodes.add(parse(career, element.getAsJsonObject()));
            SkillRules.validateTree(career, nodes);
            nextTrees.put(career, List.copyOf(nodes));
            for (SkillNode node : nodes) {
                if (nextIndex.put(node.id(), node) != null) throw new IllegalArgumentException("Skill id used by two trees: " + node.id());
            }
        }
        for (String career : ProgressionRules.CAREERS) {
            if (!nextTrees.containsKey(career)) throw new IllegalArgumentException("Missing skill tree for " + career);
        }
        trees = Map.copyOf(nextTrees);
        byId = Map.copyOf(nextIndex);
        LOGGER.info("Loaded {} Underground City skill trees ({} skills)", trees.size(), byId.size());
    }

    static SkillNode parse(String career, JsonObject json) {
        List<String> requires = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("requires")) requires.add(element.getAsString());
        JsonObject effects = json.has("effects") ? json.getAsJsonObject("effects") : new JsonObject();
        List<String> licences = new ArrayList<>();
        if (effects.has("licences")) for (JsonElement element : effects.getAsJsonArray("licences")) licences.add(element.getAsString());
        return new SkillNode(json.get("id").getAsString(), career, json.get("branch").getAsString(),
            json.get("title").getAsString(), json.get("description").getAsString(), json.get("cost").getAsInt(),
            json.get("min_level").getAsInt(), requires,
            effects.has("inspection_modifier") ? effects.get("inspection_modifier").getAsDouble() : 0,
            licences, effects.has("exchange_discount") ? effects.get("exchange_discount").getAsDouble() : 0,
            json.has("short_title") ? json.get("short_title").getAsString() : "",
            percent(effects, "legal_payout_percent"), percent(effects, "underground_payout_percent"),
            percent(effects, "yield_percent"), percent(effects, "upgrade_discount_percent"), json.has("effect") ? json.get("effect").getAsString() : "");
    }

    private static int percent(JsonObject effects, String key) { return effects.has(key) ? effects.get(key).getAsInt() : 0; }

    /** A career's tree, or the Thief tree for {@code "thief"}. */
    public List<SkillNode> tree(String career) { return SkillNode.THIEF.equals(career) ? thief : trees.getOrDefault(career, List.of()); }
    public SkillNode find(String id) { SkillNode node = byId.get(id); return node != null ? node : thiefById.get(id); }
    /** Every skill of every tree by id (the career trees and the Thief tree). */
    public Map<String, SkillNode> index() {
        if (thiefById.isEmpty()) return byId;
        Map<String, SkillNode> all = new LinkedHashMap<>(byId);
        all.putAll(thiefById);
        return all;
    }

    /** Test seam: replace the Thief tree. */
    void replaceThiefForTest(List<SkillNode> nodes) {
        Map<String, SkillNode> index = new LinkedHashMap<>();
        for (SkillNode node : nodes) index.put(node.id(), node);
        thief = List.copyOf(nodes); thiefById = Map.copyOf(index);
    }
}
