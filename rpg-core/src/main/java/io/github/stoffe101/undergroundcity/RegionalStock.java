package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One finite shared supplier line sold only inside {@code city}. Stock is shared by
 * both players and replenishes only while a human is online (see WorkOrderLedger).
 * The {@code item} may be rebound by datapack to a compatible mod item.
 */
public record RegionalStock(String id, String city, ResourceLocation item, int unitPrice, int initial,
                            int capacity, int unitsPerInterval, int intervalTicks, int maxOrder, String requiresMod, Set<String> outlets) {
    /** Where a line is sold: at an Operations Terminal inside the city, and/or at the provisions desk of a wayside shelter that belongs to the city (D-091). */
    public static final String CITY = "city", WAYSIDE = "wayside";

    /** A line sold at the city exchange only, with no outside mod behind it: every line before D-091. */
    public RegionalStock(String id, String city, ResourceLocation item, int unitPrice, int initial, int capacity, int unitsPerInterval, int intervalTicks, int maxOrder) {
        this(id, city, item, unitPrice, initial, capacity, unitsPerInterval, intervalTicks, maxOrder, "", Set.of(CITY));
    }

    public boolean soldAt(String outlet) { return outlets.contains(outlet); }

    /** A shopkeeper's counter in a furnished building (D-126): {@code shop:<kind>}. */
    public static boolean isShop(String outlet) { return outlet != null && outlet.matches("shop:[a-z_]{2,24}"); }

    /**
     * Reads one line, or returns null when the line belongs to a mod that is not installed ({@code requires_mod}): an optional set's goods are simply
     * not for sale when the set is not in the pack, instead of invalid data aborting the whole reload.
     */
    public static RegionalStock fromJson(ResourceLocation id, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported regional-stock schema: " + id);
        }
        String requires = json.has("requires_mod") ? json.get("requires_mod").getAsString() : "";
        if (!requires.isEmpty() && !ModList.get().isLoaded(requires)) return null;
        Set<String> outlets = new LinkedHashSet<>();
        if (json.has("outlets")) json.getAsJsonArray("outlets").forEach(element -> outlets.add(element.getAsString()));
        else outlets.add(CITY);
        return validate(new RegionalStock(id.toString(), json.get("city").getAsString(),
            ResourceLocation.parse(json.get("item").getAsString()), json.get("unit_price").getAsInt(),
            json.get("initial").getAsInt(), json.get("capacity").getAsInt(),
            json.get("units_per_interval").getAsInt(), json.get("interval_ticks").getAsInt(),
            json.get("max_order").getAsInt(), requires, Set.copyOf(outlets)));
    }

    static RegionalStock validate(RegionalStock stock) {
        if (stock.id.length() > 128 || !ProgressionRules.validCity(stock.city)
            || stock.unitPrice < 1 || stock.unitPrice > 10_000
            || stock.capacity < 1 || stock.capacity > 256 || stock.initial < 0 || stock.initial > stock.capacity
            || stock.unitsPerInterval < 1 || stock.unitsPerInterval > 64
            || stock.intervalTicks < 1200 || stock.intervalTicks > 720_000
            || stock.maxOrder < 1 || stock.maxOrder > 64
            || stock.outlets.isEmpty() || !stock.outlets.stream().allMatch(o -> o.equals(CITY) || o.equals(WAYSIDE) || isShop(o))
            || !ForgeRegistries.ITEMS.containsKey(stock.item)
            || ForgeRegistries.ITEMS.getValue(stock.item) == Items.AIR) {
            throw new IllegalArgumentException("Invalid regional stock " + stock.id);
        }
        return stock;
    }

    public Item registryItem() { return ForgeRegistries.ITEMS.getValue(item); }
}
