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

/** Vanilla datapack reload hook for the four career skill trees; invalid data aborts the replacement. */
public final class SkillCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final SkillCatalog INSTANCE = new SkillCatalog();
    private volatile Map<String, List<SkillNode>> trees = Map.of();
    private volatile Map<String, SkillNode> byId = Map.of();
    private SkillCatalog() { super(new Gson(), "skill_trees"); }

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
            licences, effects.has("exchange_discount") ? effects.get("exchange_discount").getAsDouble() : 0);
    }

    public List<SkillNode> tree(String career) { return trees.getOrDefault(career, List.of()); }
    public SkillNode find(String id) { return byId.get(id); }
    public Map<String, SkillNode> index() { return byId; }
}
