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
import java.util.List;
import java.util.Map;

/**
 * Inspection posts on the inter-city roads. Positions come from the corridor blueprint
 * (generated into {@code data/underground_city/checkpoints}); nothing here is a hard-coded coordinate.
 * A post is a zone, not a wall: a traveller who leaves the road and walks around it is not searched,
 * which is the deliberate smuggler's trail (and costs time and exposure in the wilderness).
 */
public final class CheckpointCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CheckpointCatalog INSTANCE = new CheckpointCatalog();

    public record Checkpoint(String id, String title, String jurisdiction, String dimension, double x, double z, int radius, String risk) {
        double distanceSquared(double px, double pz) { double dx = px - x, dz = pz - z; return dx * dx + dz * dz; }
    }

    private volatile Map<String, Checkpoint> posts = Map.of();
    private CheckpointCatalog() { super(new Gson(), "checkpoints"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Checkpoint> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported checkpoint schema: " + entry.getKey());
            Checkpoint post = new Checkpoint(entry.getKey().getPath(), json.get("title").getAsString(), json.get("jurisdiction").getAsString(),
                json.get("dimension").getAsString(), json.get("x").getAsDouble(), json.get("z").getAsDouble(), json.get("radius").getAsInt(), json.get("risk").getAsString());
            if (!ProgressionRules.validCity(post.jurisdiction) || post.radius < 8 || post.radius > 96 || Math.abs(post.x) > 3.0e7 || Math.abs(post.z) > 3.0e7
                || !(post.risk.equals("low") || post.risk.equals("inspection") || post.risk.equals("high"))) {
                throw new IllegalArgumentException("Invalid checkpoint " + entry.getKey());
            }
            ResourceLocation.parse(post.dimension);
            next.put(post.id, post);
        }
        if (next.size() > 24) throw new IllegalArgumentException("At most 24 checkpoints supported");
        posts = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City checkpoints", posts.size());
    }

    public Checkpoint find(String id) { return posts.get(id); }
    public List<Checkpoint> all() { return posts.values().stream().sorted(java.util.Comparator.comparing(Checkpoint::id)).toList(); }

    /** The post whose zone contains the position in that dimension, nearest first. */
    public Checkpoint at(String dimension, double x, double z) {
        Checkpoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Checkpoint post : posts.values()) {
            if (!post.dimension.equals(dimension)) continue;
            double d = post.distanceSquared(x, z);
            if (d <= (double) post.radius * post.radius && d < bestDistance) { best = post; bestDistance = d; }
        }
        return best;
    }

    /** Test seam. */
    void replaceForTest(List<Checkpoint> replacement) {
        Map<String, Checkpoint> next = new LinkedHashMap<>();
        replacement.forEach(post -> next.put(post.id(), post));
        posts = Map.copyOf(next);
    }
}
