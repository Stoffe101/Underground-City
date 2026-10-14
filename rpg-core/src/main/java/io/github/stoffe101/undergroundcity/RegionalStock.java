package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * One finite shared supplier line sold only inside {@code city}. Stock is shared by
 * both players and replenishes only while a human is online (see WorkOrderLedger).
 * The {@code item} may be rebound by datapack to a compatible mod item.
 */
public record RegionalStock(String id, String city, ResourceLocation item, int unitPrice, int initial,
                            int capacity, int unitsPerInterval, int intervalTicks, int maxOrder) {
    public static RegionalStock fromJson(ResourceLocation id, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported regional-stock schema: " + id);
        }
        return validate(new RegionalStock(id.toString(), json.get("city").getAsString(),
            ResourceLocation.parse(json.get("item").getAsString()), json.get("unit_price").getAsInt(),
            json.get("initial").getAsInt(), json.get("capacity").getAsInt(),
            json.get("units_per_interval").getAsInt(), json.get("interval_ticks").getAsInt(),
            json.get("max_order").getAsInt()));
    }

    static RegionalStock validate(RegionalStock stock) {
        if (stock.id.length() > 128 || !ProgressionRules.validCity(stock.city)
            || stock.unitPrice < 1 || stock.unitPrice > 10_000
            || stock.capacity < 1 || stock.capacity > 256 || stock.initial < 0 || stock.initial > stock.capacity
            || stock.unitsPerInterval < 1 || stock.unitsPerInterval > 64
            || stock.intervalTicks < 1200 || stock.intervalTicks > 720_000
            || stock.maxOrder < 1 || stock.maxOrder > 64
            || !ForgeRegistries.ITEMS.containsKey(stock.item)
            || ForgeRegistries.ITEMS.getValue(stock.item) == Items.AIR) {
            throw new IllegalArgumentException("Invalid regional stock " + stock.id);
        }
        return stock;
    }

    public Item registryItem() { return ForgeRegistries.ITEMS.getValue(item); }
}
