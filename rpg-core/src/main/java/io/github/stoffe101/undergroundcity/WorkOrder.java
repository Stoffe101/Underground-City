package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A server-authored delivery of crafted/harvested goods, not a client price quote.
 * Schema 1 orders list one item; schema 2 lists up to four requirements and may
 * require visits to rival-city exchanges (D-023). Accepted terms are snapshotted.
 */
public record WorkOrder(String id, String title, String career, int level,
                        List<Requirement> requirements, int credits, int xp,
                        int capacity, int refillTicks, int foreignVisits) {
    public record Requirement(ResourceLocation item, int count) {
        public Item registryItem() { return ForgeRegistries.ITEMS.getValue(item); }
    }

    public static WorkOrder fromJson(ResourceLocation id, JsonObject json) {
        int schema = json.has("schema_version") ? json.get("schema_version").getAsInt() : 0;
        List<Requirement> requirements = new ArrayList<>();
        int visits = 0;
        if (schema == 1) {
            requirements.add(new Requirement(ResourceLocation.parse(json.get("item").getAsString()), json.get("count").getAsInt()));
        } else if (schema == 2) {
            JsonArray array = json.getAsJsonArray("requirements");
            for (var element : array) {
                JsonObject entry = element.getAsJsonObject();
                requirements.add(new Requirement(ResourceLocation.parse(entry.get("item").getAsString()), entry.get("count").getAsInt()));
            }
            visits = json.has("foreign_visits") ? json.get("foreign_visits").getAsInt() : 0;
        } else {
            throw new IllegalArgumentException("Unsupported work-order schema: " + id);
        }
        return validate(new WorkOrder(id.toString(), json.get("title").getAsString(),
            json.get("career").getAsString(), json.get("level").getAsInt(), List.copyOf(requirements),
            json.get("credits").getAsInt(), json.get("xp").getAsInt(),
            json.get("capacity").getAsInt(), json.get("refill_ticks").getAsInt(), visits));
    }

    private static WorkOrder validate(WorkOrder order) {
        if (order.id.length() > 128 || !ProgressionRules.validCareer(order.career) || order.title.isBlank()
            || order.title.length() > 80 || order.level < 1 || order.level > 30
            || order.requirements.isEmpty() || order.requirements.size() > 4
            || order.credits < 1 || order.credits > 1000
            || order.xp < 1 || order.xp > 1000 || order.capacity < 1 || order.capacity > 16
            || order.refillTicks < 1200 || order.refillTicks > 72000
            || order.foreignVisits < 0 || order.foreignVisits > 2) {
            throw new IllegalArgumentException("Invalid work order " + order.id);
        }
        Set<ResourceLocation> seen = new HashSet<>();
        for (Requirement requirement : order.requirements) {
            if (requirement.count < 1 || requirement.count > 64 || !seen.add(requirement.item)
                || !ForgeRegistries.ITEMS.containsKey(requirement.item)
                || ForgeRegistries.ITEMS.getValue(requirement.item) == net.minecraft.world.item.Items.AIR) {
                throw new IllegalArgumentException("Invalid work order requirement in " + order.id + ": " + requirement);
            }
        }
        return order;
    }

    WorkOrder withId(String newId) {
        return new WorkOrder(newId, title, career, level, requirements, credits, xp, capacity, refillTicks, foreignVisits);
    }

    /** True if the stack is a plain, undamaged delivery of ANY listed requirement. */
    public boolean matchesAny(ItemStack stack) {
        return requirements.stream().anyMatch(requirement -> matches(requirement, stack));
    }

    public boolean matches(Requirement requirement, ItemStack stack) {
        // Never silently consume enchanted, damaged, named or mod-NBT equipment.
        if (!stack.is(requirement.registryItem()) || stack.isDamaged()) return false;
        CompoundTag tag = stack.getTag();
        boolean freshVanillaTool = stack.isDamageableItem() && tag != null
            && tag.getAllKeys().equals(Set.of("Damage"))
            && tag.contains("Damage", Tag.TAG_INT) && tag.getInt("Damage") == 0;
        if (stack.hasTag() && !freshVanillaTool) return false;
        return stack.serializeNBT().getCompound("ForgeCaps").isEmpty();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id); tag.putString("title", title); tag.putString("career", career);
        tag.putInt("level", level); tag.putInt("credits", credits); tag.putInt("xp", xp);
        tag.putInt("capacity", capacity); tag.putInt("refill_ticks", refillTicks);
        tag.putInt("foreign_visits", foreignVisits);
        ListTag list = new ListTag();
        for (Requirement requirement : requirements) {
            CompoundTag entry = new CompoundTag();
            entry.putString("item", requirement.item.toString()); entry.putInt("count", requirement.count);
            list.add(entry);
        }
        tag.put("reqs", list);
        return tag;
    }

    /** Reads both current snapshots and pre-schema-2 single-item snapshots from older saves. */
    public static WorkOrder load(CompoundTag tag) {
        List<Requirement> requirements = new ArrayList<>();
        if (tag.contains("reqs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("reqs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                requirements.add(new Requirement(ResourceLocation.parse(entry.getString("item")), entry.getInt("count")));
            }
        } else {
            requirements.add(new Requirement(ResourceLocation.parse(tag.getString("item")), tag.getInt("count")));
        }
        return validate(new WorkOrder(tag.getString("id"), tag.getString("title"),
            tag.getString("career"), tag.getInt("level"), List.copyOf(requirements),
            tag.getInt("credits"), tag.getInt("xp"),
            tag.getInt("capacity"), tag.getInt("refill_ticks"), tag.getInt("foreign_visits")));
    }
}
