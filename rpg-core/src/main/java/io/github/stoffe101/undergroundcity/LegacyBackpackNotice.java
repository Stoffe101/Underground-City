package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says so, loudly, before Forge throws away a world's Traveler's Backpacks (D-092, {@code docs/technical/backpack-migration.md}).
 *
 * <p>The backpack provider was replaced. Measured on a production-style server (October 19, 2026; {@code scripts/run_backpack_migration_check.py}): a world that was saved with Traveler's
 * Backpack and is opened without it starts <b>without any question</b>; Forge logs "missing registry entries" and, by the time the server thread starts, has already rewritten the world's
 * {@code level.dat} without the mod. At the next save <b>every Traveler's Backpack item in a chest or an inventory, every placed pack and every dropped pack is gone for good</b>; opening the
 * world again with the mod restores nothing, opening a backup does. Nothing here converts anything (a remapping would turn a pack into an empty one: the contents live in the old mod's own data).
 *
 * <p>Forge asks every mod about registry entries a world names but the game no longer has ({@link MissingMappingsEvent}) before it rewrites anything. This class only <em>listens</em>: it
 * leaves every mapping at Forge's default, logs an error and remembers, so that every operator who logs in is told too. The world may hold no Traveler's item at all (the registry list names
 * every item of a mod that was installed), so the wording says what happens <em>if</em> there is one.
 *
 * <p><b>Fail closed (D-103).</b> Warning is not enough: a server that starts unattended loses the packs five minutes later. So by default this class <em>refuses to load</em> a world
 * whose registry list names the retired mod. <b>Measured (October 20, 2026):</b> throwing an exception from the event does not do it, because vanilla's level reading catches any
 * {@code Exception}, logs it and carries on, so the first version of this guard logged its refusal and the server still reached "Done" and saved the world without the packs. The refusal
 * now ends the process itself ({@link #stop}) before a chunk or a player file is read. The way past it is deliberate and documented:
 * start with {@code -Duc.acceptLegacyBackpackLoss=true} (or the environment variable {@code UC_ACCEPT_LEGACY_BACKPACK_LOSS=true}). Even then it first <b>copies the whole world folder</b>
 * to {@code uc-backups/} beside the game directory and refuses if it cannot. Nothing is converted. After one save the world no longer names the mod and the guard stops firing.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class LegacyBackpackNotice {
    static final String LEGACY_MOD = "travelersbackpack";
    private static final Logger LOG = LoggerFactory.getLogger("UndergroundCity/LegacyBackpacks");
    private static volatile boolean detected;

    private LegacyBackpackNotice() {}

    /** Is this missing registry name one of the retired provider's, while that provider really is not installed? */
    static boolean concerns(ResourceLocation id) {
        return id != null && LEGACY_MOD.equals(id.getNamespace()) && !ModList.get().isLoaded(LEGACY_MOD);
    }

    static boolean detected() { return detected; }

    static final String OVERRIDE_PROPERTY = "uc.acceptLegacyBackpackLoss", OVERRIDE_ENVIRONMENT = "UC_ACCEPT_LEGACY_BACKPACK_LOSS";

    /** What to do when the world names the retired mod. */
    enum Verdict { LOAD, REFUSE, BACK_UP_AND_LOAD }

    /** The decision, kept pure so it can be tested: nothing named, nothing to guard; named and not overridden, refuse; named and overridden, back up first. */
    static Verdict verdict(boolean worldNamesTheRetiredMod, boolean overridden) {
        if (!worldNamesTheRetiredMod) return Verdict.LOAD;
        return overridden ? Verdict.BACK_UP_AND_LOAD : Verdict.REFUSE;
    }

    static boolean overridden() {
        return Boolean.getBoolean(OVERRIDE_PROPERTY) || "true".equalsIgnoreCase(System.getenv(OVERRIDE_ENVIRONMENT));
    }

    /** The world folder being opened, or null if it cannot be told yet (the dedicated server has no server object when Forge asks about registries). */
    static java.nio.file.Path worldFolder() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) return server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        // A server started with --world or --universe keeps its world somewhere server.properties does not say; guessing could back up (and then "protect") the wrong folder, so the override refuses instead.
        String command = System.getProperty("sun.java.command", "");
        if (command.contains("--world") || command.contains("--universe")) return null;
        java.nio.file.Path game = FMLPaths.GAMEDIR.get();
        String level = "world";
        java.nio.file.Path properties = game.resolve("server.properties");
        try {
            if (java.nio.file.Files.isRegularFile(properties)) {
                for (String line : java.nio.file.Files.readAllLines(properties)) if (line.startsWith("level-name=")) level = line.substring("level-name=".length()).trim();
            }
        } catch (java.io.IOException unreadable) { return null; }
        java.nio.file.Path folder = game.resolve(level);
        return java.nio.file.Files.isDirectory(folder) ? folder : null;
    }

    /** Copies the world to {@code uc-backups/<name>-before-legacy-backpack-loss-<time>} and returns that folder; throws if it cannot. */
    static java.nio.file.Path backUp(java.nio.file.Path world) throws java.io.IOException {
        java.nio.file.Path target = FMLPaths.GAMEDIR.get().resolve("uc-backups").resolve(world.getFileName() + "-before-legacy-backpack-loss-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()));
        java.nio.file.Files.createDirectories(target);
        try (var walk = java.nio.file.Files.walk(world)) {
            for (java.nio.file.Path source : (Iterable<java.nio.file.Path>) walk::iterator) {
                java.nio.file.Path copy = target.resolve(world.relativize(source).toString());
                // The server holds a lock on session.lock while it runs (on Windows a locked file cannot be read, and Minecraft's own backups leave it out): it holds no world data.
                if (source.getFileName() != null && source.getFileName().toString().equals("session.lock")) continue;
                if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(copy);
                else java.nio.file.Files.copy(source, copy, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        return target;
    }

    /** Thrown on a client (single-player), where ending the process from here would close the game without a word: an Error is not caught by vanilla's reading of the world, so the game stops with a crash report. */
    static final class LegacyBackpackRefusal extends Error {
        LegacyBackpackRefusal(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Ends the start of the game; never returns. A dedicated server exits at once (nothing of the world has been read or written yet, and the server's own shutdown hook is not
     * registered this early, so nothing is saved). Anything that merely throws would be swallowed by vanilla's level reading, which is exactly what the first version did.
     */
    private static void stop(String message, Throwable cause) {
        LOG.error("[UC-BACKPACKS] {}", message);
        if (FMLEnvironment.dist.isDedicatedServer()) {
            LOG.error("[UC-BACKPACKS] The server is stopping now without loading the world.");
            System.exit(1);
        }
        throw new LegacyBackpackRefusal(message, cause);
    }

    @SubscribeEvent
    public static void missing(MissingMappingsEvent event) {
        if (detected) return;
        boolean found = false;
        for (var mapping : event.getAllMappings(ForgeRegistries.Keys.ITEMS)) found |= concerns(mapping.getKey());
        for (var mapping : event.getAllMappings(ForgeRegistries.Keys.BLOCKS)) found |= concerns(mapping.getKey());
        Verdict verdict = verdict(found, overridden());
        if (verdict == Verdict.LOAD) return;
        if (verdict == Verdict.REFUSE) {
            LOG.error("[UC-BACKPACKS] REFUSING TO LOAD THIS WORLD: it was saved with Traveler's Backpack, which is not installed, and Forge would delete every Traveler's Backpack in it at the next save.");
            LOG.error("[UC-BACKPACKS] Nothing has been read or changed. Copy the world folder first. To keep the contents, follow docs/technical/backpack-migration.md (start once with the retired provider and empty the packs).");
            LOG.error("[UC-BACKPACKS] To load the world anyway and lose the packs, start with -D" + OVERRIDE_PROPERTY + "=true (or set " + OVERRIDE_ENVIRONMENT + "=true). A backup is made first.");
            stop("Refusing to load a world that holds Traveler's Backpack data without the mod (Underground City, D-103). See the log lines above and docs/technical/backpack-migration.md.", null);
        }
        java.nio.file.Path world = worldFolder();
        if (world == null) {
            LOG.error("[UC-BACKPACKS] The override is set but the world folder cannot be found to back it up, so the world is NOT loaded. Copy the world by hand and start the server from its folder.");
            stop("Cannot back up the world before overriding the Traveler's Backpack guard (Underground City, D-103).", null);
        }
        try {
            java.nio.file.Path backup = backUp(world);
            LOG.error("[UC-BACKPACKS] Override accepted. The world was copied to {} before anything is loaded. Traveler's Backpack items WILL be removed at the next save; nothing is converted.", backup);
        } catch (java.io.IOException failed) {
            LOG.error("[UC-BACKPACKS] The backup of {} failed ({}), so the world is NOT loaded.", world, failed.toString());
            stop("The backup before overriding the Traveler's Backpack guard failed (Underground City, D-103).", failed);
        }
        detected = true;
        LOG.error("[UC-BACKPACKS] THIS WORLD WAS SAVED WITH TRAVELER'S BACKPACK, WHICH IS NOT INSTALLED.");
        LOG.error("[UC-BACKPACKS] If it holds any Traveler's Backpack (in a chest or an inventory, placed in the world, or dropped), Forge removes it permanently at the next save");
        LOG.error("[UC-BACKPACKS] (about five minutes after the start, or when the server stops). Nothing is converted. Stop the server now, copy the world folder, and read docs/technical/backpack-migration.md.");
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!detected || !(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.server.isSingleplayerOwner(player.getGameProfile()) && !player.hasPermissions(2)) return;
        player.sendSystemMessage(Component.literal("[Underground City] This world was saved with Traveler's Backpack, which is not installed. Any Traveler's Backpack in it is removed for good at the next save. "
            + "Stop the server and read docs/technical/backpack-migration.md before anything is saved.").withStyle(ChatFormatting.RED));
    }
}
