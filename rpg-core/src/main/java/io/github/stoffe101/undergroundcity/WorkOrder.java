package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** A server-authored delivery of crafted/harvested goods, not a client price quote. */
public record WorkOrder(String id, String title, String career, int level,
                        ResourceLocation item, int count, int credits, int xp,
                        int capacity, int refillTicks) {
    public static WorkOrder fromJson(ResourceLocation id, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported work-order schema: " + id);
        }
        return validate(new WorkOrder(id.toString(), json.get("title").getAsString(),
            json.get("career").getAsString(), json.get("level").getAsInt(),
            ResourceLocation.parse(json.get("item").getAsString()), json.get("count").getAsInt(),
            json.get("credits").getAsInt(), json.get("xp").getAsInt(),
            json.get("capacity").getAsInt(), json.get("refill_ticks").getAsInt()));
    }

    private static WorkOrder validate(WorkOrder order) {
        if (order.id.length() > 128 || !ProgressionRules.validCareer(order.career) || order.title.isBlank()
            || order.title.length() > 80 || order.level < 1 || order.level > 30
            || order.count < 1 || order.count > 64 || order.credits < 1 || order.credits > 1000
            || order.xp < 1 || order.xp > 1000 || order.capacity < 1 || order.capacity > 16
            || order.refillTicks < 1200 || order.refillTicks > 72000
            || !ForgeRegistries.ITEMS.containsKey(order.item)
            || ForgeRegistries.ITEMS.getValue(order.item) == net.minecraft.world.item.Items.AIR) {
            throw new IllegalArgumentException("Invalid work order " + order.id);
        }
        return order;
    }

    public Item deliveryItem() { return ForgeRegistries.ITEMS.getValue(item); }

    public boolean matches(ItemStack stack) {
        // Never silently consume enchanted, damaged, named or mod-NBT equipment.
        return stack.is(deliveryItem()) && !stack.hasTag() && !stack.isDamaged();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id); tag.putString("title", title); tag.putString("career", career);
        tag.putString("item", item.toString()); tag.putInt("level", level); tag.putInt("count", count);
        tag.putInt("credits", credits); tag.putInt("xp", xp); tag.putInt("capacity", capacity);
        tag.putInt("refill_ticks", refillTicks);
        return tag;
    }

    public static WorkOrder load(CompoundTag tag) {
        return validate(new WorkOrder(tag.getString("id"), tag.getString("title"),
            tag.getString("career"), tag.getInt("level"), ResourceLocation.parse(tag.getString("item")),
            tag.getInt("count"), tag.getInt("credits"), tag.getInt("xp"),
            tag.getInt("capacity"), tag.getInt("refill_ticks")));
    }
}
