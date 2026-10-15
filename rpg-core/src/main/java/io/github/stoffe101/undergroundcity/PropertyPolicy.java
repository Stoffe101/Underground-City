package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/** Data-driven household safe restock and security profile (generated from content/property). */
public record PropertyPolicy(String id, int capacity, int initialStock, int intervalTicks,
                             int itemsPerInterval, int lockpicks, ResourceLocation lootTable) {
    public static PropertyPolicy fromJson(ResourceLocation id, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported property-policy schema: " + id);
        }
        PropertyPolicy policy = new PropertyPolicy(id.toString(), json.get("capacity").getAsInt(),
            json.get("initial_stock").getAsInt(), json.get("interval_ticks").getAsInt(),
            json.get("items_per_interval").getAsInt(), json.get("lockpicks").getAsInt(),
            ResourceLocation.parse(json.get("loot_table").getAsString()));
        if (policy.id.length() > 128 || policy.capacity < 1 || policy.capacity > PropertyCacheBlockEntity.SLOTS
            || policy.initialStock < 0 || policy.initialStock > policy.capacity
            || policy.intervalTicks < 1200 || policy.intervalTicks > 720_000
            || policy.itemsPerInterval < 1 || policy.itemsPerInterval > 4
            || policy.lockpicks < 1 || policy.lockpicks > 8) {
            throw new IllegalArgumentException("Invalid property policy " + id);
        }
        return policy;
    }
}
