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
    private volatile Map<String, Supply> supplies = Map.of();
    private RegionalCatalog() { super(new Gson(), "regional_stock"); }

    /**
     * What a shop pays a supplier for one unit of a line it also sells (D-164): a Vintner's wine at the pub, a Smith's fasteners at the builders' yard. {@code pays} is below the line's
     * price (a counter never buys back for more than it sells), {@code xp} is the career XP a supplier of {@code career} earns a unit (none for anybody else).
     */
    public record Supply(int pays, int xp, String career) {}

    static final int MAX_LINES = 192;

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, RegionalStock> next = new LinkedHashMap<>();
        Map<String, Supply> nextSupply = new LinkedHashMap<>();
        int[] skipped = {0};
        data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RegionalStock stock = RegionalStock.fromJson(entry.getKey(), entry.getValue().getAsJsonObject());
            if (stock == null) { skipped[0]++; return; }   // an optional mod's goods while that mod is not installed
            next.put(stock.id(), stock);
            var json = entry.getValue().getAsJsonObject();
            if (json.has("supply")) {
                var sj = json.getAsJsonObject("supply");
                Supply supply = new Supply(sj.get("pays").getAsInt(), sj.has("xp") ? sj.get("xp").getAsInt() : 0, sj.has("career") ? sj.get("career").getAsString() : "");
                if (supply.pays() < 1 || supply.pays() >= stock.unitPrice() || supply.xp() < 0 || supply.xp() > 50 || stock.outlets().stream().noneMatch(RegionalStock::isShop))
                    throw new IllegalArgumentException("Invalid supply terms for " + stock.id() + ": a shop line, paying 1 to less than its price");
                nextSupply.put(stock.id(), supply);
            }
        });
        if (next.size() > MAX_LINES) throw new IllegalArgumentException("At most " + MAX_LINES + " regional stock lines supported");
        stocks = Map.copyOf(next);
        supplies = Map.copyOf(nextSupply);
        LOGGER.info("Loaded {} Underground City regional supplier lines ({} left out because their mod is not installed)", stocks.size(), skipped[0]);
    }

    public RegionalStock find(String id) { return stocks.get(id); }

    /** What the shop pays a supplier for a line, or null when it buys none (D-164). */
    public Supply supply(String id) { return supplies.get(id); }

    /** Test seam: replaces the supply terms. */
    void replaceSupplyForTest(Map<String, Supply> replacement) { supplies = Map.copyOf(replacement); }
    Map<String, Supply> suppliesForTest() { return supplies; }

    /** Every line, in id order. */
    public java.util.Collection<RegionalStock> all() { return stocks.values(); }

    /** What the exchange inside {@code city} sells. */
    public List<RegionalStock> forCity(String city) { return forOutlet(city, RegionalStock.CITY); }

    /** What one kind of outlet of {@code city} sells: its exchange, or the provisions desks of the wayside shelters that belong to it. */
    public List<RegionalStock> forOutlet(String city, String outlet) {
        return stocks.values().stream().filter(stock -> stock.city().equals(city) && stock.soldAt(outlet))
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
