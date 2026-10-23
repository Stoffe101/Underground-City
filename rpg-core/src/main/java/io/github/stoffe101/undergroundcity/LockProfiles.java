package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.Map;
import java.util.TreeMap;

/** The kinds of lock (latch, deadbolt, commercial, high security, safe) as data: {@code data/underground_city/lock_profiles/<id>.json}. Invalid data stops the reload. */
public final class LockProfiles extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final LockProfiles INSTANCE = new LockProfiles();
    private volatile Map<String, LockpickSession.Profile> profiles = Map.of();

    private LockProfiles() { super(new Gson(), "lock_profiles"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, LockpickSession.Profile> next = new TreeMap<>();
        data.forEach((key, value) -> next.put(key.getPath(), parse(key.getPath(), value.getAsJsonObject())));
        if (!next.containsKey("deadbolt")) throw new IllegalArgumentException("lock_profiles must define deadbolt (the default)");
        profiles = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City lock profiles", profiles.size());
    }

    static LockpickSession.Profile parse(String id, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported lock-profile schema: " + id);
        return new LockpickSession.Profile(id, json.get("pins").getAsInt(), json.get("tolerance").getAsDouble(), json.get("hold_ticks").getAsInt(), json.get("strain_limit").getAsDouble(),
            json.get("strain_per_tick").getAsDouble(), json.get("ease_per_tick").getAsDouble(), json.get("time_limit_ticks").getAsInt(), json.get("feel_noise").getAsDouble(),
            json.get("tremor").getAsDouble(), json.get("lockout_ticks").getAsInt(), json.get("alarm_chance").getAsDouble(),
            json.has("spools") ? json.get("spools").getAsInt() : 0, json.has("requires_tool") ? json.get("requires_tool").getAsString() : "",
            json.has("requires_skill") ? json.get("requires_skill").getAsString() : "", json.has("rakeable") ? json.get("rakeable").getAsDouble() : 0);
    }

    /** The named profile, or the deadbolt when a name is not known (a stale data reference must never leave a door with no lock at all). */
    public LockpickSession.Profile find(String id) {
        LockpickSession.Profile found = profiles.get(id);
        return found != null ? found : profiles.get("deadbolt");
    }

    /** Test seam. */
    void replaceForTest(Map<String, LockpickSession.Profile> replacement) { profiles = Map.copyOf(replacement); }
}
