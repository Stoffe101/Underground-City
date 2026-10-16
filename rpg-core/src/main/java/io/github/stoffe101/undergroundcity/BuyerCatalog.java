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

/** Named buyers with a fixed location. A contract with a buyer can only be delivered at a terminal inside that city. */
public final class BuyerCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final BuyerCatalog INSTANCE = new BuyerCatalog();
    public static final int MAX_TRUST = 5;

    public record Buyer(String id, String title, String city, boolean underground, String blurb) {}

    private volatile Map<String, Buyer> buyers = Map.of();
    private BuyerCatalog() { super(new Gson(), "buyers"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Buyer> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported buyer schema: " + entry.getKey());
            String id = entry.getKey().getPath();
            String legality = json.get("legality").getAsString();
            Buyer buyer = new Buyer(id, json.get("title").getAsString(), json.get("city").getAsString(), legality.equals("underground"), json.get("blurb").getAsString());
            if (!id.matches("[a-z_]{3,40}") || !ProgressionRules.validCity(buyer.city) || !(legality.equals("legal") || legality.equals("underground"))
                || buyer.title.isBlank() || buyer.title.length() > 48 || buyer.blurb.length() > 160) {
                throw new IllegalArgumentException("Invalid buyer " + entry.getKey());
            }
            next.put(id, buyer);
        }
        if (next.size() > 32) throw new IllegalArgumentException("At most 32 buyers supported");
        buyers = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City buyers", buyers.size());
    }

    public Buyer find(String id) { return buyers.get(id); }
    public List<Buyer> inCity(String city) {
        return buyers.values().stream().filter(b -> b.city().equals(city)).sorted(java.util.Comparator.comparing(Buyer::id)).toList();
    }
    public List<Buyer> all() { return buyers.values().stream().sorted(java.util.Comparator.comparing(Buyer::id)).toList(); }
}
