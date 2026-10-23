package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Registry ids that were renamed, kept working in saves made before the rename (D-142: the controlled crop is called Cannabis, and the fictional name it had before
 * is gone from the game). An old id is an <b>alias</b> of the new one: every lookup by the old name (an item stack in an inventory, a chest or a backpack, a block in a
 * saved chunk's palette, a loot table, a command) finds the new object, and the alias is written into the world's registry snapshot. A world whose snapshot still names
 * the old id also gets the old id <b>remapped</b> when Forge reports it missing, so loading such a world never asks the player about missing entries. Nothing is deleted:
 * a saved stack of the old item is the new item, with its count and tags.
 */
public final class RenamedIds {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Old path to new path, per registry. */
    static final Map<String, String> ITEMS = Map.of("sable_cutting", "cannabis_seeds", "sable_leaf_bale", "cannabis_flower");
    static final Map<String, String> BLOCKS = Map.of("sable_leaf_crop", "cannabis_plant");

    /** Registered on the Forge bus for {@link #missing}. */
    RenamedIds() {}

    static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, path); }

    /** Mod bus: the aliases go in while the registries are open. */
    static void register(IEventBus modBus) {
        modBus.addListener((RegisterEvent event) -> {
            if (event.getRegistryKey().equals(Registries.ITEM)) alias(event, ITEMS);
            else if (event.getRegistryKey().equals(Registries.BLOCK)) alias(event, BLOCKS);
        });
    }

    private static void alias(RegisterEvent event, Map<String, String> renames) {
        if (!(event.getForgeRegistry() instanceof ForgeRegistry<?> registry)) return;
        renames.forEach((from, to) -> registry.addAlias(id(from), id(to)));
    }

    /** Forge bus: a world saved with the old ids has them remapped to the new objects instead of being reported missing. */
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void missing(MissingMappingsEvent event) {
        for (var mapping : event.getMappings(ForgeRegistries.Keys.ITEMS, UndergroundCity.MOD_ID)) {
            String to = ITEMS.get(mapping.getKey().getPath());
            if (to != null) { mapping.remap(ForgeRegistries.ITEMS.getValue(id(to))); LOGGER.info("Remapped the renamed item {} to {}", mapping.getKey(), id(to)); }
        }
        for (var mapping : event.getMappings(ForgeRegistries.Keys.BLOCKS, UndergroundCity.MOD_ID)) {
            String to = BLOCKS.get(mapping.getKey().getPath());
            if (to != null) { mapping.remap(ForgeRegistries.BLOCKS.getValue(id(to))); LOGGER.info("Remapped the renamed block {} to {}", mapping.getKey(), id(to)); }
        }
    }
}
