package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Which world layout this game directory carries (D-104). Two layouts exist: the original seed-5908 triangle ({@code legacy}, the world package
 * {@code underground_city_world}) and the archipelago ({@code underground_city_archipelago}, which ships {@code data/underground_city/layouts/archipelago.json}).
 * They cannot share a launch: Lost Cities applies every predefined city it is given to every world, so two packages would put both sets of cities in either world.
 *
 * <p>The danger is a mismatch, and it is always silent: an existing legacy world opened by an archipelago launch would generate its next chunks with the new cities and
 * roads (seams across a world somebody has played), and an archipelago world opened without its package would get no cities at all. So, in the way of the Traveler's guard (D-103),
 * the game <b>refuses to start</b> on a mismatch, before a chunk is read, and says which launch to use. {@code -Duc.layoutGuard=off} exists for the disposable survey servers that
 * measure terrain without the package (it is never a thing to set on a world that holds a player).
 */
public final class WorldLayout extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final WorldLayout INSTANCE = new WorldLayout();
    public static final ResourceLocation REGIONAL_SETTINGS = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "regional");

    /** The arrival point of a layout: the anchor of the landing and the level the ground is held at. */
    public record Arrival(String island, int x, int z, int groundY) {}
    public record Layout(String id, Arrival arrival) {}

    private volatile Layout layout;

    private WorldLayout() { super(new Gson(), "layouts"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Layout next = null;
        for (var entry : data.entrySet()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported layout schema: " + entry.getKey());
            JsonObject arrival = json.getAsJsonObject("arrival");
            var anchor = arrival.getAsJsonArray("anchor");
            next = new Layout(json.get("layout").getAsString(), new Arrival(arrival.get("island").getAsString(), anchor.get(0).getAsInt(), anchor.get(1).getAsInt(), arrival.get("ground_y").getAsInt()));
        }
        layout = next;
        LOGGER.info("[UC-WORLD] world layout carried by this game directory: {}", next == null ? "legacy (no layout package)" : next.id());
    }

    /** The archipelago layout when its world package is installed, empty for the legacy launch. */
    public Optional<Layout> current() { return Optional.ofNullable(layout); }

    /** Test seam: replaces the loaded layout without a datapack reload. */
    void replaceForTest(Layout replacement) { layout = replacement; }

    // ------------------------------------------------------------------------------------------------ the guard

    /** Is this overworld generator the archipelago terrain (the noise settings {@code underground_city:regional})? */
    public static boolean regional(ChunkGenerator generator) {
        return generator instanceof NoiseBasedChunkGenerator noise && noise.generatorSettings().is(REGIONAL_SETTINGS);
    }

    public enum Verdict { OK, REGIONAL_WITHOUT_PACKAGE, PACKAGE_WITHOUT_REGIONAL }

    /** The decision, pure: a regional overworld needs the archipelago package and the package needs a regional overworld. */
    static Verdict verdict(boolean regionalGenerator, boolean packageLoaded) {
        if (regionalGenerator && !packageLoaded) return Verdict.REGIONAL_WITHOUT_PACKAGE;
        if (!regionalGenerator && packageLoaded) return Verdict.PACKAGE_WITHOUT_REGIONAL;
        return Verdict.OK;
    }

    @SubscribeEvent
    public void aboutToStart(ServerAboutToStartEvent event) {
        if ("off".equalsIgnoreCase(System.getProperty("uc.layoutGuard"))) return;
        MinecraftServer server = event.getServer();
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
        if (stem == null) return;
        Verdict verdict = verdict(regional(stem.generator()), layout != null);
        if (verdict == Verdict.OK) return;
        String message = verdict == Verdict.REGIONAL_WITHOUT_PACKAGE
            ? "This world uses the archipelago terrain but the archipelago world package (underground_city_archipelago) is not installed, so it would have no cities and no roads. Start with the archipelago layout (-PucWorldLayout=archipelago, or the server pack made with --layout archipelago)."
            : "The archipelago world package is installed but this world uses ordinary terrain (for example a world created by an older launch). Its next chunks would get cities and roads in the wrong places. Start with the legacy layout (-PucWorldLayout=legacy) or create a new world with the Underground City: Archipelago world type.";
        LOGGER.error("[UC-WORLD] REFUSING TO START THIS WORLD: {}", message);
        LOGGER.error("[UC-WORLD] Nothing has been read or written. See docs/technical/archipelago-world.md.");
        if (FMLEnvironment.dist.isDedicatedServer()) System.exit(1);
        throw new IllegalStateException(message);
    }

    /** Where Founders Row and the world spawn go in a new archipelago world, or empty (a legacy launch keeps the development bootstrap's rule). */
    public Optional<BlockPos> arrivalAnchor() {
        Layout current = layout;
        return current == null ? Optional.empty() : Optional.of(new BlockPos(current.arrival().x(), current.arrival().groundY(), current.arrival().z()));
    }
}
