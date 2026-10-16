package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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

/**
 * The welcome pack a citizen receives once, when registering (data/underground_city/starter_kits/&lt;career&gt;.json).
 * Items are plain registry ids, so a kit can name a mod's item and is skipped (not an error) when that mod is absent.
 * Granting is tied to the registration itself, which the citizen record makes a one-time event, so a kit is never
 * issued twice and never to someone who is already registered.
 */
public final class StarterKits extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final StarterKits INSTANCE = new StarterKits();
    static final int MAX_STACKS = 12;

    /** {@code requiresMod} (may be empty) skips the entry when that mod is absent; {@code nbt} (may be null) is applied to the stack. */
    public record Entry(ResourceLocation item, int count, String requiresMod, net.minecraft.nbt.CompoundTag nbt) {}

    private volatile Map<String, List<Entry>> kits = Map.of();
    private StarterKits() { super(new Gson(), "starter_kits"); }

    public List<Entry> kit(String career) { return kits.getOrDefault(career, List.of()); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, List<Entry>> next = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported starter kit schema: " + entry.getKey());
            String career = json.get("career").getAsString();
            if (!ProgressionRules.validCareer(career) || !career.equals(entry.getKey().getPath())) throw new IllegalArgumentException("Starter kit file must be named after its career: " + entry.getKey());
            List<Entry> items = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("items")) {
                JsonObject row = element.getAsJsonObject();
                ResourceLocation id = ResourceLocation.parse(row.get("item").getAsString());
                int count = row.get("count").getAsInt();
                if (count < 1 || count > 64) throw new IllegalArgumentException("Starter kit " + career + ": bad count for " + id);
                String requiresMod = row.has("requires_mod") ? row.get("requires_mod").getAsString() : "";
                net.minecraft.nbt.CompoundTag nbt = null;
                if (row.has("nbt")) {
                    try { nbt = net.minecraft.nbt.TagParser.parseTag(row.get("nbt").getAsString()); }
                    catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalArgumentException("Starter kit " + career + ": bad nbt for " + id, error); }
                }
                items.add(new Entry(id, count, requiresMod, nbt));
            }
            if (items.isEmpty() || items.size() > MAX_STACKS) throw new IllegalArgumentException("Starter kit " + career + " must hold 1 to " + MAX_STACKS + " stacks");
            next.put(career, List.copyOf(items));
        }
        kits = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City starter kits", kits.size());
    }

    /** The stacks a new citizen of this career receives: the Operations Terminal first, then the career kit. */
    public List<ItemStack> stacks(String career) {
        List<ItemStack> stacks = new ArrayList<>();
        stacks.add(new ItemStack(UndergroundCity.TERMINAL_ITEM.get()));
        for (Entry entry : kit(career)) {
            if (!entry.requiresMod().isEmpty() && !net.minecraftforge.fml.ModList.get().isLoaded(entry.requiresMod())) continue;
            Item item = ForgeRegistries.ITEMS.getValue(entry.item());
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            ItemStack stack = new ItemStack(item, entry.count());
            if (entry.nbt() != null) stack.setTag(entry.nbt().copy());
            stacks.add(stack);
        }
        return stacks;
    }

    /** Hands the stacks over; whatever does not fit in the inventory drops at the player's feet. */
    public int grant(ServerPlayer player, String career) {
        int given = 0;
        for (ItemStack stack : stacks(career)) {
            given++;
            if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
        }
        return given;
    }
}
