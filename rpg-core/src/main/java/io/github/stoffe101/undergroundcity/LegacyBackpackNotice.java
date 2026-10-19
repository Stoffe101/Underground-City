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

    @SubscribeEvent
    public static void missing(MissingMappingsEvent event) {
        if (detected) return;
        boolean found = false;
        for (var mapping : event.getAllMappings(ForgeRegistries.Keys.ITEMS)) found |= concerns(mapping.getKey());
        for (var mapping : event.getAllMappings(ForgeRegistries.Keys.BLOCKS)) found |= concerns(mapping.getKey());
        if (!found) return;
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
