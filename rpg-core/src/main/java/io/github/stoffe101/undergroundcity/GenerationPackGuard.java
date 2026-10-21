package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Optional generation packages change what the <em>next</em> generated chunk of a world is: the furnished city buildings ({@code underground_city_furnished}, D-117) and the scarce natural
 * ore of the hybrid economy ({@code underground_city_scarcity}, D-120). A world therefore has to be <b>made</b> with them and keep them. It is the reasoning of {@link WorldLayout} and of the Traveler's
 * guard (D-103), and the owner's rule that an existing world is never given new generation rules: a world that already holds chunks must not get furnished houses beside old streets or ores
 * that stop at a chunk border, and a world made with a package must not be opened without it (the next chunks would be ordinary and the palettes of the houses already standing would be missing).
 *
 * <p>The rule, with nothing written into a world that does not already take part:
 * <ul>
 *   <li>no marker and none of the packages installed: nothing happens (every existing world, the default);</li>
 *   <li>no marker, some package installed, no generated chunk: the world is new, the marker {@code uc_generation_packs.json} is written before a chunk exists;</li>
 *   <li>no marker, some package installed, chunks exist: the game refuses to start (an existing world is never given new rules);</li>
 *   <li>a marker: the installed packages must be exactly the ones it lists, otherwise the game refuses to start.</li>
 * </ul>
 */
public final class GenerationPackGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Every optional generation package this game knows. */
    public static final List<String> PACKAGES = List.of("underground_city_furnished", "underground_city_scarcity");
    public static final String MARKER = "uc_generation_packs.json";

    public enum Verdict { OK, MARK_NEW_WORLD, EXISTING_WORLD_WOULD_GET_NEW_RULES, PACKAGES_DIFFER_FROM_MARKER }

    /** The decision, pure. {@code marker} is null when the world has no marker file. */
    static Verdict verdict(java.util.Set<String> installed, java.util.Set<String> marker, boolean hasChunks) {
        if (marker != null) return installed.equals(marker) ? Verdict.OK : Verdict.PACKAGES_DIFFER_FROM_MARKER;
        if (installed.isEmpty()) return Verdict.OK;
        return hasChunks ? Verdict.EXISTING_WORLD_WOULD_GET_NEW_RULES : Verdict.MARK_NEW_WORLD;
    }

    /** True when the overworld already has at least one region file. */
    static boolean hasGeneratedChunks(Path worldRoot) {
        Path region = worldRoot.resolve("region");
        if (!Files.isDirectory(region)) return false;
        try (Stream<Path> files = Files.list(region)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".mca"));
        } catch (IOException error) {
            // when it cannot be read it is safer to treat the world as an existing one
            return true;
        }
    }

    static java.util.Set<String> readMarker(Path file) throws IOException {
        JsonObject json = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        java.util.Set<String> packs = new TreeSet<>();
        if (json != null && json.has("packages")) json.getAsJsonArray("packages").forEach(e -> packs.add(e.getAsString()));
        return packs;
    }

    static String markerText(java.util.Set<String> installed) {
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", 1);
        JsonArray packages = new JsonArray();
        installed.forEach(packages::add);
        json.add("packages", packages);
        json.addProperty("note", "This world was created with these optional generation packages and needs exactly them to be opened (docs/technical/furnished-cities.md).");
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n";
    }

    @SubscribeEvent
    public void aboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        Path root = server.getWorldPath(LevelResource.ROOT);
        java.util.Set<String> installed = new TreeSet<>();
        for (String id : PACKAGES) if (ModList.get().isLoaded(id)) installed.add(id);
        Path file = root.resolve(MARKER);
        java.util.Set<String> marker = null;
        if (Files.isRegularFile(file)) {
            try { marker = readMarker(file); }
            catch (IOException | RuntimeException error) { refuse("The generation-package marker of this world could not be read (" + error.getMessage() + ")."); return; }
        }
        Verdict verdict = verdict(installed, marker, hasGeneratedChunks(root));
        switch (verdict) {
            case OK -> { }
            case MARK_NEW_WORLD -> {
                try {
                    Files.writeString(file, markerText(installed), StandardCharsets.UTF_8);
                    LOGGER.info("[UC-WORLD] new world: marked as made with {}", installed);
                } catch (IOException error) {
                    // an unmarked world could later be opened without the packages: refuse instead of carrying on unprotected
                    refuse("The generation-package marker could not be written into the new world (" + error.getMessage() + ").");
                }
            }
            case EXISTING_WORLD_WOULD_GET_NEW_RULES -> refuse("The optional generation packages " + installed + " are installed but this world already has generated chunks and was not made with them. Its next chunks would be generated by different rules than the chunks beside them. Start without them, or create a new world.");
            case PACKAGES_DIFFER_FROM_MARKER -> refuse("This world was made with the generation packages " + marker + " but the installed ones are " + installed + ". Its next chunks would be generated by different rules, and buildings already standing could lose their palettes. Install exactly the packages the world was made with.");
        }
    }

    private static void refuse(String message) {
        LOGGER.error("[UC-WORLD] REFUSING TO START THIS WORLD: {}", message);
        LOGGER.error("[UC-WORLD] Nothing has been read or written. See docs/technical/furnished-cities.md.");
        if (FMLEnvironment.dist.isDedicatedServer()) System.exit(1);
        throw new IllegalStateException(message);
    }
}
