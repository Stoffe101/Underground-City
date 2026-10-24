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
 * What an Electronics Bench can put together (D-158): data ({@code data/underground_city/tech_designs/*.json}), each design its parts, what it makes and the Technician skill it needs. The bench
 * shows them all, with what is missing; the server makes one only from parts the player carries.
 */
public final class TechDesigns extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TechDesigns INSTANCE = new TechDesigns();

    /** One design. {@code inputs} are item ids and counts, in order. */
    public record Design(String id, String title, ResourceLocation output, int count, Map<ResourceLocation, Integer> inputs, String skill, String note) {}

    private volatile List<Design> designs = List.of();

    private TechDesigns() { super(new Gson(), "tech_designs"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        List<Design> next = new ArrayList<>();
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(file -> {
            JsonObject json = file.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported tech-design schema: " + file.getKey());
            for (JsonElement e : json.getAsJsonArray("designs")) {
                JsonObject d = e.getAsJsonObject();
                Map<ResourceLocation, Integer> inputs = new LinkedHashMap<>();
                for (var in : d.getAsJsonObject("inputs").entrySet()) {
                    int count = in.getValue().getAsInt();
                    if (count < 1 || count > 64) throw new IllegalArgumentException("A design's part count must be 1 to 64: " + d.get("id"));
                    inputs.put(ResourceLocation.parse(in.getKey()), count);
                }
                int count = d.get("count").getAsInt();
                if (inputs.isEmpty() || count < 1 || count > 16) throw new IllegalArgumentException("Invalid tech design " + d.get("id"));
                next.add(new Design(d.get("id").getAsString(), d.get("title").getAsString(), ResourceLocation.parse(d.get("output").getAsString()), count, Map.copyOf(inputs),
                    d.has("skill") ? d.get("skill").getAsString() : "", d.has("note") ? d.get("note").getAsString() : ""));
            }
        });
        designs = List.copyOf(next);
        LOGGER.info("Loaded {} electronics bench designs", designs.size());
    }

    public List<Design> all() { return designs; }

    public Design find(String id) { for (Design d : designs) if (d.id().equals(id)) return d; return null; }
}
