package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A server-authored delivery of crafted/harvested goods, not a client price quote.
 * Schema 1 orders list one item; schema 2 lists up to four requirements and may require visits to
 * rival-city exchanges (D-023); schema 3 adds the legal/underground presentation (D-049): a skill
 * gate, a named buyer in a fixed city, trust, extra reward items and gun requirements.
 * Accepted terms (including rewards) are snapshotted, so a datapack edit cannot change them.
 */
public record WorkOrder(String id, String title, String career, int level,
                        List<Requirement> requirements, int credits, int xp,
                        int capacity, int refillTicks, int foreignVisits, Terms terms) {
    /** A plain-item requirement, or a factory gun when {@code gunIds} is not empty. */
    public record Requirement(ResourceLocation item, int count, List<String> gunIds, String label) {
        public Requirement { gunIds = List.copyOf(gunIds); }
        public Requirement(ResourceLocation item, int count) { this(item, count, List.of(), ""); }
        public Item registryItem() { return ForgeRegistries.ITEMS.getValue(item); }
        public String display() { return label.isEmpty() ? item.toString() : label; }
    }

    /** Extra items granted on delivery; the stack is built from registry data only, never from the client. */
    public record Reward(ResourceLocation item, int count, String nbt) {
        public ItemStack stack() {
            ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(item), count);
            if (!nbt.isEmpty()) {
                try { stack.setTag(net.minecraft.nbt.TagParser.parseTag(nbt)); }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalArgumentException("Bad reward NBT for " + item, error); }
            }
            return stack;
        }
    }

    /** Legality and gating. {@code requiresSkill}, {@code buyer} and {@code requiresMod} are empty when unused. */
    public record Terms(boolean underground, String requiresSkill, String buyer, int minTrust, int trustGain,
                        String requiresMod, List<Reward> rewards) {
        public static final Terms DEFAULT = new Terms(false, "", "", 0, 0, "", List.of());
        public Terms { rewards = List.copyOf(rewards); }
    }

    public boolean underground() { return terms.underground(); }

    /** Null when the order is for a mod that is not installed: it is skipped, not an error. */
    public static WorkOrder fromJson(ResourceLocation id, JsonObject json) {
        int schema = json.has("schema_version") ? json.get("schema_version").getAsInt() : 0;
        List<Requirement> requirements = new ArrayList<>();
        int visits = 0;
        Terms terms = Terms.DEFAULT;
        if (schema == 1) {
            requirements.add(new Requirement(ResourceLocation.parse(json.get("item").getAsString()), json.get("count").getAsInt()));
        } else if (schema == 2 || schema == 3) {
            JsonArray array = json.getAsJsonArray("requirements");
            for (JsonElement element : array) {
                JsonObject entry = element.getAsJsonObject();
                List<String> guns = new ArrayList<>();
                if (entry.has("gun_ids")) for (JsonElement gun : entry.getAsJsonArray("gun_ids")) guns.add(gun.getAsString());
                requirements.add(new Requirement(ResourceLocation.parse(entry.get("item").getAsString()), entry.get("count").getAsInt(),
                    guns, entry.has("label") ? entry.get("label").getAsString() : ""));
            }
            visits = json.has("foreign_visits") ? json.get("foreign_visits").getAsInt() : 0;
            if (schema == 3) terms = parseTerms(json);
        } else {
            throw new IllegalArgumentException("Unsupported work-order schema: " + id);
        }
        if (!terms.requiresMod().isEmpty() && !ModList.get().isLoaded(terms.requiresMod())) return null;
        return validate(new WorkOrder(id.toString(), json.get("title").getAsString(),
            json.get("career").getAsString(), json.get("level").getAsInt(), List.copyOf(requirements),
            json.get("credits").getAsInt(), json.get("xp").getAsInt(),
            json.get("capacity").getAsInt(), json.get("refill_ticks").getAsInt(), visits, terms));
    }

    private static Terms parseTerms(JsonObject json) {
        String legality = json.has("legality") ? json.get("legality").getAsString() : "legal";
        if (!legality.equals("legal") && !legality.equals("underground")) throw new IllegalArgumentException("Unknown legality " + legality);
        List<Reward> rewards = new ArrayList<>();
        if (json.has("rewards")) {
            for (JsonElement element : json.getAsJsonArray("rewards")) {
                JsonObject reward = element.getAsJsonObject();
                rewards.add(new Reward(ResourceLocation.parse(reward.get("item").getAsString()), reward.get("count").getAsInt(),
                    reward.has("nbt") ? reward.get("nbt").getAsString() : ""));
            }
        }
        return new Terms(legality.equals("underground"), str(json, "requires_skill"), str(json, "buyer"),
            json.has("min_trust") ? json.get("min_trust").getAsInt() : 0, json.has("trust_gain") ? json.get("trust_gain").getAsInt() : 0,
            str(json, "requires_mod"), rewards);
    }

    private static String str(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsString() : ""; }

    private static WorkOrder validate(WorkOrder order) {
        Terms terms = order.terms;
        if (order.id.length() > 128 || !ProgressionRules.validCareer(order.career) || order.title.isBlank()
            || order.title.length() > 80 || order.level < 1 || order.level > 30
            || order.requirements.isEmpty() || order.requirements.size() > 4
            || order.credits < 1 || order.credits > 1000
            || order.xp < 1 || order.xp > 1000 || order.capacity < 1 || order.capacity > 16
            || order.refillTicks < 1200 || order.refillTicks > 72000
            || order.foreignVisits < 0 || order.foreignVisits > 2
            || terms.minTrust < 0 || terms.minTrust > BuyerCatalog.MAX_TRUST || terms.trustGain < 0 || terms.trustGain > 2
            || terms.rewards.size() > 3 || (terms.minTrust > 0 || terms.trustGain > 0) && terms.buyer.isEmpty()
            || !terms.requiresSkill.isEmpty() && !terms.requiresSkill.matches("[a-z]+\\.[a-z_]{2,40}")
            || !terms.buyer.isEmpty() && !terms.buyer.matches("[a-z_]{3,40}")) {
            throw new IllegalArgumentException("Invalid work order " + order.id);
        }
        Set<ResourceLocation> seen = new HashSet<>();
        for (Requirement requirement : order.requirements) {
            if (requirement.count < 1 || requirement.count > 64 || !seen.add(requirement.item)
                || !ForgeRegistries.ITEMS.containsKey(requirement.item)
                || ForgeRegistries.ITEMS.getValue(requirement.item) == net.minecraft.world.item.Items.AIR
                || requirement.gunIds.size() > 24 || requirement.label.length() > 40) {
                throw new IllegalArgumentException("Invalid work order requirement in " + order.id + ": " + requirement);
            }
        }
        for (Reward reward : terms.rewards) {
            if (reward.count < 1 || reward.count > 16 || reward.nbt.length() > 400 || !ForgeRegistries.ITEMS.containsKey(reward.item)
                || ForgeRegistries.ITEMS.getValue(reward.item) == net.minecraft.world.item.Items.AIR) {
                throw new IllegalArgumentException("Invalid work order reward in " + order.id + ": " + reward);
            }
            reward.stack();
        }
        return order;
    }

    WorkOrder withId(String newId) {
        return new WorkOrder(newId, title, career, level, requirements, credits, xp, capacity, refillTicks, foreignVisits, terms);
    }

    /** True if the stack is a plain, undamaged delivery of ANY listed requirement. */
    public boolean matchesAny(ItemStack stack) {
        return requirements.stream().anyMatch(requirement -> matches(requirement, stack));
    }

    public boolean matches(Requirement requirement, ItemStack stack) {
        if (!stack.is(requirement.registryItem())) return false;
        if (!requirement.gunIds().isEmpty()) return matchesFactoryGun(requirement, stack);
        // Never silently consume enchanted, damaged, named or mod-NBT equipment.
        if (stack.isDamaged()) return false;
        CompoundTag tag = stack.getTag();
        boolean freshVanillaTool = stack.isDamageableItem() && tag != null
            && tag.getAllKeys().equals(Set.of("Damage"))
            && tag.contains("Damage", Tag.TAG_INT) && tag.getInt("Damage") == 0;
        if (stack.hasTag() && !freshVanillaTool) return false;
        return stack.serializeNBT().getCompound("ForgeCaps").isEmpty();
    }

    /**
     * A gun requirement accepts only a plain factory weapon of a listed GunId: no custom name, enchantment or
     * installed attachment, because delivery consumes the item and a customised gun must never vanish silently.
     */
    static boolean matchesFactoryGun(Requirement requirement, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || stack.hasCustomHoverName() || stack.isEnchanted()) return false;
        if (!tag.contains("GunId", Tag.TAG_STRING) || !requirement.gunIds().contains(tag.getString("GunId"))) return false;
        for (String key : tag.getAllKeys()) {
            if (key.startsWith("Attachment") && !key.equals("AttachmentLock")) return false;
        }
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
            if (!requirement.gunIds.isEmpty()) {
                ListTag guns = new ListTag();
                requirement.gunIds.forEach(gun -> guns.add(StringTag.valueOf(gun)));
                entry.put("gun_ids", guns);
            }
            if (!requirement.label.isEmpty()) entry.putString("label", requirement.label);
            list.add(entry);
        }
        tag.put("reqs", list);
        if (terms != Terms.DEFAULT) {
            CompoundTag extra = new CompoundTag();
            extra.putBoolean("underground", terms.underground); extra.putString("skill", terms.requiresSkill); extra.putString("mod", terms.requiresMod);
            extra.putString("buyer", terms.buyer); extra.putInt("min_trust", terms.minTrust); extra.putInt("trust_gain", terms.trustGain);
            ListTag rewardList = new ListTag();
            for (Reward reward : terms.rewards) {
                CompoundTag entry = new CompoundTag();
                entry.putString("item", reward.item.toString()); entry.putInt("count", reward.count); entry.putString("nbt", reward.nbt);
                rewardList.add(entry);
            }
            extra.put("rewards", rewardList);
            tag.put("terms", extra);
        }
        return tag;
    }

    /** Reads both current snapshots and pre-schema-2 single-item snapshots from older saves. */
    public static WorkOrder load(CompoundTag tag) {
        List<Requirement> requirements = new ArrayList<>();
        if (tag.contains("reqs", Tag.TAG_LIST)) {
            ListTag list = tag.getList("reqs", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                List<String> guns = new ArrayList<>();
                ListTag gunList = entry.getList("gun_ids", Tag.TAG_STRING);
                for (int g = 0; g < gunList.size(); g++) guns.add(gunList.getString(g));
                requirements.add(new Requirement(ResourceLocation.parse(entry.getString("item")), entry.getInt("count"), guns, entry.getString("label")));
            }
        } else {
            requirements.add(new Requirement(ResourceLocation.parse(tag.getString("item")), tag.getInt("count")));
        }
        Terms terms = Terms.DEFAULT;
        if (tag.contains("terms", Tag.TAG_COMPOUND)) {
            CompoundTag extra = tag.getCompound("terms");
            List<Reward> rewards = new ArrayList<>();
            ListTag rewardList = extra.getList("rewards", Tag.TAG_COMPOUND);
            for (int i = 0; i < rewardList.size(); i++) {
                CompoundTag entry = rewardList.getCompound(i);
                rewards.add(new Reward(ResourceLocation.parse(entry.getString("item")), entry.getInt("count"), entry.getString("nbt")));
            }
            terms = new Terms(extra.getBoolean("underground"), extra.getString("skill"), extra.getString("buyer"),
                extra.getInt("min_trust"), extra.getInt("trust_gain"), extra.getString("mod"), rewards);
        }
        return validate(new WorkOrder(tag.getString("id"), tag.getString("title"),
            tag.getString("career"), tag.getInt("level"), List.copyOf(requirements),
            tag.getInt("credits"), tag.getInt("xp"),
            tag.getInt("capacity"), tag.getInt("refill_ticks"), tag.getInt("foreign_visits"), terms));
    }
}
