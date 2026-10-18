package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vanilla datapack reload hook for finite regional supplier lines; invalid data aborts replacement. */
public final class RegionalCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final RegionalCatalog INSTANCE = new RegionalCatalog();
    private volatile Map<String, RegionalStock> stocks = Map.of();
    private RegionalCatalog() { super(new Gson(), "regional_stock"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, RegionalStock> next = new LinkedHashMap<>();
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RegionalStock stock = RegionalStock.fromJson(entry.getKey(), entry.getValue().getAsJsonObject());
            next.put(stock.id(), stock);
        });
        if (next.size() > 64) throw new IllegalArgumentException("At most 64 regional stock lines supported");
        stocks = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City regional supplier lines", stocks.size());
    }

    public RegionalStock find(String id) { return stocks.get(id); }

    public List<RegionalStock> forCity(String city) {
        return stocks.values().stream().filter(stock -> stock.city().equals(city))
            .sorted(Comparator.comparing(RegionalStock::id)).toList();
    }

    /** Test seam: every line, so a test can put the catalog back after replacing it. */
    List<RegionalStock> allForTest() { return List.copyOf(stocks.values()); }

    /** Test seam: replaces the catalog without a datapack reload. */
    void replaceForTest(List<RegionalStock> replacement) {
        Map<String, RegionalStock> next = new LinkedHashMap<>();
        replacement.forEach(stock -> next.put(stock.id(), stock));
        stocks = Map.copyOf(next);
    }
}
