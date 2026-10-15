package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Vanilla datapack reload hook for household safe policies; invalid data aborts replacement. */
public final class PropertyPolicies extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PropertyPolicies INSTANCE = new PropertyPolicies();
    private volatile Map<String, PropertyPolicy> policies = Map.of();
    private PropertyPolicies() { super(new Gson(), "property_policies"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, PropertyPolicy> next = new TreeMap<>();
        data.forEach((key, value) -> {
            PropertyPolicy policy = PropertyPolicy.fromJson(key, value.getAsJsonObject());
            next.put(policy.id(), policy);
        });
        if (next.size() > 32) throw new IllegalArgumentException("At most 32 property policies supported");
        policies = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City property policies", policies.size());
    }

    public PropertyPolicy find(String id) { return policies.get(id); }
    public List<String> ids() { return policies.keySet().stream().sorted().toList(); }
}
