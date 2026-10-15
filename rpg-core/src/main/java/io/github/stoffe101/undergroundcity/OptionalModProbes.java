package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks against optional third-party mods that need no compile-time dependency: they look things up by registry id
 * and use vanilla interfaces only. Shared by the GameTests and by the production-style boot check so both prove the
 * same thing. Each returns the list of problems; empty means the check passed.
 */
final class OptionalModProbes {
    private OptionalModProbes() {}

    /** Modern Life's motorboat must keep a 27-slot hold, named/damaged/tagged stacks included, through an NBT round trip. */
    static List<String> boatCargoRoundTrip(ServerLevel level) {
        List<String> problems = new ArrayList<>();
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("modernlife", "motorboat"));
        if (type == null || type == EntityType.PIG) return List.of("modernlife:motorboat is not registered");
        Entity boat = type.create(level), restored = type.create(level);
        if (!(boat instanceof Container cargo) || !(restored instanceof Container again)) return List.of("The motorboat does not expose a Container");
        if (cargo.getContainerSize() != 27) problems.add("Cargo hold size " + cargo.getContainerSize());
        ItemStack named = new ItemStack(Items.IRON_SWORD);
        named.setHoverName(net.minecraft.network.chat.Component.literal("Dray's crate"));
        named.setDamageValue(37);
        ItemStack tagged = new ItemStack(Items.PAPER, 3);
        tagged.getOrCreateTag().putString("GunId", "tacz:glock_17");
        cargo.setItem(0, named); cargo.setItem(13, tagged); cargo.setItem(26, new ItemStack(Items.WHEAT, 64));
        CompoundTag saved = boat.saveWithoutId(new CompoundTag());
        restored.load(saved);
        for (int slot = 0; slot < Math.min(27, again.getContainerSize()); slot++) {
            if (!ItemStack.matches(cargo.getItem(slot), again.getItem(slot))) problems.add("Cargo slot " + slot + " changed over a save and load");
        }
        if (!again.getItem(0).hasCustomHoverName() || again.getItem(0).getDamageValue() != 37) problems.add("Named and damaged cargo did not survive exactly");
        if (again.getItem(13).getTag() == null || !again.getItem(13).getTag().getString("GunId").equals("tacz:glock_17")) problems.add("NBT on a cargo stack was lost");
        return problems;
    }

    /** How many items and entity types a namespace registered; zero means the mod (or its content packs) did not register. */
    static int items(String namespace) { return (int) ForgeRegistries.ITEMS.getKeys().stream().filter(key -> key.getNamespace().equals(namespace)).count(); }
    /** Items of one namespace whose path starts with a prefix (content packs register as mts:<pack>.<name>). */
    static int itemsWithPrefix(String namespace, String prefix) {
        return (int) ForgeRegistries.ITEMS.getKeys().stream().filter(key -> key.getNamespace().equals(namespace) && key.getPath().startsWith(prefix)).count();
    }
    static int entities(String namespace) { return (int) ForgeRegistries.ENTITY_TYPES.getKeys().stream().filter(key -> key.getNamespace().equals(namespace)).count(); }
}
