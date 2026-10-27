package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The regenerating resource sites of a world (D-123): data under {@code data/underground_city/resource_sites/}. A site is a built district (the pit itself is {@link DistrictBuilder}'s) and
 * the list of vein cells inside it that grow back: each is a place, relative to the district's origin, and the ore block that belongs there. {@link ResourceSites} does the growing back.
 * A vein must be a registered block (the tests check that it is an ore and lies inside the district); the numbers that bound what a site can ever yield (at most 64 veins, a regrowth of no less than five active minutes) are
 * checked here, so a data mistake is a load error and not a source of free materials.
 */
public final class ResourceSiteCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceSiteCatalog INSTANCE = new ResourceSiteCatalog();
    public static final int MAX_VEINS = 64, MIN_INTERVAL_MINUTES = 5, MAX_INTERVAL_MINUTES = 240;

    /** One vein cell: its place relative to the district's origin and the ore it grows. */
    public record Vein(BlockPos at, Block block) {}

    /** A site: the district it lives in, how long a mined vein takes to grow back (in active minutes) and its veins. */
    public record Site(String id, String title, String district, int intervalMinutes, List<Vein> veins) {
        public long intervalTicks() { return intervalMinutes * 1200L; }
        /** The most ore the site can give in an active hour if somebody mines every vein the moment it is back. */
        public double oresPerHourCeiling() { return veins.size() * 60.0 / intervalMinutes; }
    }

    private volatile Map<String, Site> sites = Map.of();
    private ResourceSiteCatalog() { super(new Gson(), "resource_sites"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Site> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Site site = parse(entry.getKey(), entry.getValue().getAsJsonObject());
            next.put(site.id(), site);
        }
        sites = Map.copyOf(next);
        LOGGER.info("Loaded {} regenerating resource sites", sites.size());
    }

    static Site parse(ResourceLocation key, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported resource site schema: " + key);
        String id = json.get("id").getAsString();
        if (!id.matches("[a-z_]{3,40}") || !id.equals(key.getPath())) throw new IllegalArgumentException("Bad resource site id " + key);
        int interval = json.get("interval_minutes").getAsInt();
        if (interval < MIN_INTERVAL_MINUTES || interval > MAX_INTERVAL_MINUTES) throw new IllegalArgumentException(id + ": interval_minutes must be between " + MIN_INTERVAL_MINUTES + " and " + MAX_INTERVAL_MINUTES);
        List<Vein> veins = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        for (JsonElement element : json.getAsJsonArray("veins")) {
            JsonObject v = element.getAsJsonObject();
            var at = v.getAsJsonArray("at");
            BlockPos pos = new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt());
            ResourceLocation blockId = ResourceLocation.parse(v.get("block").getAsString());
            Block block = ForgeRegistries.BLOCKS.getValue(blockId);
            if (block == null || !ForgeRegistries.BLOCKS.containsKey(blockId)) throw new IllegalArgumentException(id + ": unknown block " + blockId);
            // that it is an ore is checked by a GameTest, not here: block tags are bound after this listener has run on the first load
            if (!seen.add(pos)) throw new IllegalArgumentException(id + ": two veins at " + pos.toShortString());
            veins.add(new Vein(pos, block));
        }
        if (veins.isEmpty() || veins.size() > MAX_VEINS) throw new IllegalArgumentException(id + ": a site has between 1 and " + MAX_VEINS + " veins");
        return new Site(id, json.get("title").getAsString(), json.get("district").getAsString(), interval, List.copyOf(veins));
    }

    public Site find(String id) { return sites.get(id); }
    public List<Site> all() { return List.copyOf(sites.values()); }

    /** Test seam. */
    void replaceForTest(Map<String, Site> replacement) { sites = Map.copyOf(replacement); }
}
