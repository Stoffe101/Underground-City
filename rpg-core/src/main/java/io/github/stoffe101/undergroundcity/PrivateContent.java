package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Privately licensed content (docs/legal/private-content.md, D-116) enters the game from a folder <b>outside</b> the repository, as always-on data and resource packs, and from nowhere else.
 *
 * <p>The folder is named by {@code -Duc.private.content=<folder>} (Gradle: {@code -PucPrivateContent=}) or the {@code UC_PRIVATE_CONTENT_DIR} environment variable; with neither, nothing happens and the game is
 * the public game. Every sub-folder with an {@code uc-private-pack.json} is a candidate. It is loaded only if the manifest is valid, says {@code private_only: true}, names who gave the permission and
 * what it covers, and the folder has a {@code pack.mcmeta}; otherwise it is refused and the reason is logged. A folder that lies inside a git work tree is refused unless it is the ignored
 * {@code private-content} folder of that tree. Nothing is copied: the pack is read from where it is, so removing the folder removes the content, and no world, save or jar ever holds it.
 *
 * <p>The log says which packs were loaded and how many files they hold; it never prints a name inside a pack, a block, a palette or any content, so a log or a CI artifact cannot expose it.
 * Public CI has no private content and runs a synthetic pack ({@code scripts/private_pack_tool.py fixture}) to prove this path works.
 */
public final class PrivateContent {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MANIFEST = "uc-private-pack.json";
    static final String PROPERTY = "uc.private.content", ENVIRONMENT = "UC_PRIVATE_CONTENT_DIR";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,48}");

    /** A valid private pack: where it is, what it provides and who gave the permission. */
    public record Pack(String id, String title, Path dir, boolean data, boolean assets, String holder, String summary, int files) {}

    /** What one scan found: the packs that will load and one line for each folder that was refused. */
    public record Scan(List<Pack> packs, List<String> refused) {}

    private static volatile Scan cached;

    private PrivateContent() {}

    public static void register(IEventBus modBus) { modBus.addListener(PrivateContent::addPacks); }

    /** The folder the game was told to read private content from, if any. */
    static Optional<Path> configuredRoot() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) value = System.getenv(ENVIRONMENT);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value).toAbsolutePath().normalize());
    }

    /** The packs of the configured folder, scanned once per run. */
    public static Scan scan() {
        Scan scan = cached;
        if (scan == null) {
            Optional<Path> root = configuredRoot();
            scan = root.isPresent() ? discover(root.get()) : new Scan(List.of(), List.of());
            cached = scan;
            if (root.isPresent()) {
                LOGGER.info("[UC-PRIVATE] private content folder configured: {} pack(s) will load, {} folder(s) refused", scan.packs().size(), scan.refused().size());
                for (Pack pack : scan.packs()) LOGGER.info("[UC-PRIVATE] pack '{}' ({} files; data {}, assets {}); permission held by {}", pack.id(), pack.files(), pack.data(), pack.assets(), pack.holder());
                for (String line : scan.refused()) LOGGER.warn("[UC-PRIVATE] refused: {}", line);
            }
        }
        return scan;
    }

    /** Every valid pack directly under {@code root}; a folder that is not valid is named in {@code refused} with the reason. */
    static Scan discover(Path root) {
        List<Pack> packs = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (!Files.isDirectory(root)) { refused.add(root.getFileName() + ": the configured private content folder does not exist"); return new Scan(packs, refused); }
        String checkout = checkoutProblem(root);
        if (checkout != null) { refused.add(root.getFileName() + ": " + checkout); return new Scan(packs, refused); }
        try (Stream<Path> children = Files.list(root)) {
            for (Path dir : children.filter(Files::isDirectory).sorted().toList()) {
                if (!Files.isRegularFile(dir.resolve(MANIFEST))) continue;
                Object result = read(dir);
                if (result instanceof Pack pack) packs.add(pack);
                else refused.add(dir.getFileName() + ": " + result);
            }
        } catch (IOException error) {
            refused.add(root.getFileName() + ": cannot be listed (" + error.getClass().getSimpleName() + ")");
        }
        return new Scan(packs, refused);
    }

    /**
     * Why a folder inside a git work tree may not hold private content, or {@code null}. The one exception is the tree's own {@code private-content} folder, which .gitignore and the
     * private-content guard keep out of every commit.
     */
    static String checkoutProblem(Path root) {
        Path folder = root.toAbsolutePath().normalize();
        for (Path up = folder; up != null; up = up.getParent()) {
            if (Files.exists(up.resolve(".git"))) {
                Path relative = up.relativize(folder);
                boolean ignoredFolder = relative.getNameCount() > 0 && relative.getName(0).toString().equals("private-content");
                return ignoredFolder ? null : "it is inside a git work tree; private content lives outside every checkout (or in that checkout's ignored private-content folder)";
            }
        }
        return null;
    }

    /** The pack in a folder, or the reason it is refused (a String). */
    private static Object read(Path dir) {
        JsonObject manifest;
        try {
            manifest = JsonParser.parseString(Files.readString(dir.resolve(MANIFEST), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException error) {
            return "the manifest cannot be read as JSON";
        }
        if (!manifest.has("schema_version") || manifest.get("schema_version").getAsInt() != 1) return "unsupported manifest schema";
        String id = manifest.has("id") ? manifest.get("id").getAsString() : "";
        if (!ID.matcher(id).matches()) return "the id must be lowercase letters, digits, - and _";
        if (!manifest.has("private_only") || !manifest.get("private_only").getAsBoolean()) return "the manifest does not say private_only: true";
        JsonObject permission = manifest.has("permission") && manifest.get("permission").isJsonObject() ? manifest.getAsJsonObject("permission") : null;
        String holder = permission != null && permission.has("holder") ? permission.get("holder").getAsString().trim() : "";
        String summary = permission != null && permission.has("summary") ? permission.get("summary").getAsString().trim() : "";
        if (holder.isEmpty() || summary.isEmpty()) return "the manifest does not name who gave the permission and what it covers";
        if (!Files.isRegularFile(dir.resolve("pack.mcmeta"))) return "there is no pack.mcmeta";
        JsonObject provides = manifest.has("provides") && manifest.get("provides").isJsonObject() ? manifest.getAsJsonObject("provides") : new JsonObject();
        boolean data = !provides.has("data") || provides.get("data").getAsBoolean(), assets = provides.has("assets") && provides.get("assets").getAsBoolean();
        String title = manifest.has("title") ? manifest.get("title").getAsString() : id;
        int files = 0;
        try (Stream<Path> walk = Files.walk(dir)) { files = (int) walk.filter(Files::isRegularFile).count(); } catch (IOException ignored) { }
        return new Pack(id, title, dir, data, assets, holder, summary, files);
    }

    private static void addPacks(AddPackFindersEvent event) {
        PackType type = event.getPackType();
        if (type != PackType.SERVER_DATA && type != PackType.CLIENT_RESOURCES) return;
        for (Pack pack : scan().packs()) {
            if (type == PackType.SERVER_DATA ? !pack.data() : !pack.assets()) continue;
            event.addRepositorySource(consumer -> {
                var created = net.minecraft.server.packs.repository.Pack.readMetaAndCreate("underground_city_private/" + pack.id(), Component.literal("Private: " + pack.title()), true,
                    id -> new PathPackResources(id, pack.dir(), false), type, net.minecraft.server.packs.repository.Pack.Position.TOP, PackSource.DEFAULT);
                if (created == null) LOGGER.warn("[UC-PRIVATE] pack '{}' has an unreadable pack.mcmeta and was not loaded", pack.id());
                else consumer.accept(created);
            });
        }
    }

    /** Test seam: forget the scan. */
    static void resetForTest() { cached = null; }
}
