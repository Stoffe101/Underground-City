package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exceptional finds (D-051): the rare firearm in a wealthy resident's safe, the schematic in a corporate
 * safe, the heirloom seed packet on an estate. Defined per property policy and rolled ONCE, when a safe is
 * registered, with a seed fixed by its position: restocking never rolls them and re-registering the same safe
 * cannot re-roll them, so rare equipment is finite by construction and cannot be farmed.
 * Entries name real registry items and optional NBT (a TaCZ gun is {@code tacz:modern_kinetic_gun} with a
 * {@code GunId}); an entry whose mod is not installed is dropped when the data loads, so this works in a
 * Core-only world and never produces a parse error the way a loot table naming a missing item would.
 */
public final class ExceptionalLoot extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ExceptionalLoot INSTANCE = new ExceptionalLoot();

    public record Entry(ResourceLocation item, String nbt, int weight, int min, int max) {
        ItemStack stack(long seed) {
            int count = min == max ? min : min + (int) (LawMath.unit(seed ^ 0xC0FFEEL) * (max - min + 1));
            ItemStack stack = new ItemStack(ForgeRegistries.ITEMS.getValue(item), Math.max(1, count));
            if (!nbt.isEmpty()) {
                try { stack.setTag(TagParser.parseTag(nbt)); }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalArgumentException("Bad NBT for " + item, error); }
            }
            return stack;
        }
    }

    public record Table(String policy, double chance, List<Entry> entries) {}

    private volatile Map<String, Table> tables = Map.of();
    private ExceptionalLoot() { super(new Gson(), "exceptional_loot"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Table> next = new LinkedHashMap<>();
        for (var file : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            JsonObject json = file.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported exceptional-loot schema: " + file.getKey());
            String policy = json.get("policy").getAsString();
            double chance = json.get("chance").getAsDouble();
            if (!policy.contains(":") || chance < 0 || chance > 0.5) throw new IllegalArgumentException("Invalid exceptional table " + file.getKey());
            List<Entry> entries = new ArrayList<>();
            int skipped = 0;
            for (JsonElement element : json.getAsJsonArray("entries")) {
                JsonObject entry = element.getAsJsonObject();
                String mod = entry.has("requires_mod") ? entry.get("requires_mod").getAsString() : "";
                ResourceLocation item = ResourceLocation.parse(entry.get("item").getAsString());
                if ((!mod.isEmpty() && !ModList.get().isLoaded(mod)) || !ForgeRegistries.ITEMS.containsKey(item)
                    || ForgeRegistries.ITEMS.getValue(item) == Items.AIR) {
                    if (item.getNamespace().equals(UndergroundCity.MOD_ID)) throw new IllegalArgumentException("Unknown Core item " + item + " in " + file.getKey());
                    skipped++;
                    continue;
                }
                int weight = entry.get("weight").getAsInt();
                int min = entry.has("count") ? entry.getAsJsonArray("count").get(0).getAsInt() : 1;
                int max = entry.has("count") ? entry.getAsJsonArray("count").get(1).getAsInt() : min;
                if (weight < 1 || weight > 100 || min < 1 || max < min || max > 64) throw new IllegalArgumentException("Invalid entry " + item + " in " + file.getKey());
                Entry built = new Entry(item, entry.has("nbt") ? entry.get("nbt").getAsString() : "", weight, min, max);
                built.stack(0); // fail at load time, not at the first burglary, if the NBT is malformed
                entries.add(built);
            }
            if (!entries.isEmpty()) next.put(policy, new Table(policy, chance, List.copyOf(entries)));
            if (skipped > 0) LOGGER.info("Exceptional loot for {}: {} entries skipped (mod not installed)", policy, skipped);
        }
        tables = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City exceptional-loot tables", tables.size());
    }

    public Table find(String policy) { return tables.get(policy); }

    /**
     * The deterministic exceptional find for a safe, or an empty stack. The seed must depend only on the safe's
     * position (never on time or a counter) so that clearing and re-registering the safe yields the same answer.
     */
    public ItemStack roll(String policy, long seed) {
        Table table = tables.get(policy);
        if (table == null || !LawMath.roll(table.chance(), LawMath.mix(seed, 0xE5CEF71L))) return ItemStack.EMPTY;
        int total = table.entries().stream().mapToInt(Entry::weight).sum();
        int pick = (int) (LawMath.unit(LawMath.mix(seed, 0x91C6L)) * total);
        for (Entry entry : table.entries()) {
            if (pick < entry.weight()) return entry.stack(LawMath.mix(seed, 0x57ACL));
            pick -= entry.weight();
        }
        return ItemStack.EMPTY;
    }

    /** Test seam. */
    void replaceForTest(Map<String, Table> replacement) { tables = Map.copyOf(replacement); }
}
