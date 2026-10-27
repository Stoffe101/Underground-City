package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the city laws can call contraband (D-053). Entries match real items by registry ID and, for
 * mod items that share one ID (TaCZ guns and attachments), by one NBT string key. Entries for mods that
 * are not installed simply never match, so this data is safe in a Core-only world. A specific entry
 * ({@code values} or {@code contains}) always beats a {@code catch_all}, so an unknown gun from another
 * gun pack is treated as the restricted class. An entry may instead name an item {@code tag}: a datapack tag can list a whole mod's range
 * of items with {@code "required": false}, so one entry covers every spirit of a distilling mod without the mod being installed.
 */
public final class ContrabandCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ContrabandCatalog INSTANCE = new ContrabandCatalog();

    /** {@code overrides}: this entry decides the category before any other (an unmarked build of a civilian model is restricted, D-168). */
    public record Entry(ResourceLocation item, String nbtKey, Set<String> values, String contains, boolean catchAll, TagKey<Item> tag, boolean overrides) {
        public Entry(ResourceLocation item, String nbtKey, Set<String> values, String contains, boolean catchAll) { this(item, nbtKey, values, contains, catchAll, null, false); }
        public Entry(ResourceLocation item, String nbtKey, Set<String> values, String contains, boolean catchAll, TagKey<Item> tag) { this(item, nbtKey, values, contains, catchAll, tag, false); }
        boolean specific() { return !catchAll; }
        boolean matches(ItemStack stack) {
            if (tag != null) return stack.is(tag);
            if (!ForgeRegistries.ITEMS.getKey(stack.getItem()).equals(item)) return false;
            if (nbtKey.isEmpty()) return true;
            CompoundTag nbt = stack.getTag();
            if (nbt == null || !nbt.contains(nbtKey, Tag.TAG_STRING)) return catchAll && values.isEmpty() && contains.isEmpty();
            String value = nbt.getString(nbtKey);
            if (catchAll) return true;
            if (!values.isEmpty() && values.contains(value)) return true;
            return !contains.isEmpty() && value.contains(contains);
        }
    }

    public record Category(String id, String title, List<Entry> entries) {}

    private volatile Map<String, Category> categories = Map.of();
    private ContrabandCatalog() { super(new Gson(), "contraband"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Category> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) {
                throw new IllegalArgumentException("Unsupported contraband schema: " + entry.getKey());
            }
            String id = json.get("category").getAsString();
            if (!id.matches("[a-z_]{3,40}") || !id.equals(entry.getKey().getPath())) throw new IllegalArgumentException("Bad contraband category " + entry.getKey());
            List<Entry> entries = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("entries")) {
                JsonObject e = element.getAsJsonObject();
                if (e.has("tag")) {
                    entries.add(new Entry(null, "", Set.of(), "", false, TagKey.create(Registries.ITEM, ResourceLocation.parse(e.get("tag").getAsString()))));
                    continue;
                }
                ResourceLocation item = ResourceLocation.parse(e.get("item").getAsString());
                // Core's own items must exist; third-party IDs may be absent (mod not installed).
                if (item.getNamespace().equals(UndergroundCity.MOD_ID) && !ForgeRegistries.ITEMS.containsKey(item)) {
                    throw new IllegalArgumentException("Unknown Core item in contraband " + id + ": " + item);
                }
                java.util.Set<String> values = new java.util.HashSet<>();
                if (e.has("values")) for (JsonElement v : e.getAsJsonArray("values")) values.add(v.getAsString());
                entries.add(new Entry(item, e.has("nbt_key") ? e.get("nbt_key").getAsString() : "", Set.copyOf(values),
                    e.has("contains") ? e.get("contains").getAsString() : "", e.has("catch_all") && e.get("catch_all").getAsBoolean(), null, e.has("overrides") && e.get("overrides").getAsBoolean()));
            }
            if (entries.isEmpty() || entries.size() > 16) throw new IllegalArgumentException("Contraband " + id + " needs 1-16 entries");
            next.put(id, new Category(id, json.get("title").getAsString(), List.copyOf(entries)));
        }
        if (next.size() > 16) throw new IllegalArgumentException("At most 16 contraband categories supported");
        categories = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City contraband categories", categories.size());
    }

    public Map<String, Category> all() { return categories; }
    public Category category(String id) { return categories.get(id); }

    /** The contraband category of a stack, or null when it is an ordinary item. */
    public String classify(ItemStack stack) {
        if (stack.isEmpty()) return null;
        String fallback = null;
        for (Category category : categories.values())
            for (Entry entry : category.entries()) if (entry.overrides() && entry.matches(stack)) return category.id();
        for (Category category : categories.values()) {
            for (Entry entry : category.entries()) {
                if (!entry.matches(stack)) continue;
                if (entry.specific()) return category.id();
                fallback = category.id();
            }
        }
        return fallback;
    }

    /** Test seam. */
    void replaceForTest(Map<String, Category> replacement) { categories = Map.copyOf(replacement); }
}
