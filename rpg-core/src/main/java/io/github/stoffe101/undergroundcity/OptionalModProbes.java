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

    /**
     * Puts a real Immersive Vehicles vehicle into the world the way a player does (its item used on a block), fits a real crate part in the vehicle's
     * crate slot and fills the crate. Returns the vehicle's Minecraft entity, or null with the reasons in {@code problems}. Everything is reached by
     * reflection; the stacks are given to the crate through the mod's own item wrapper.
     */
    static Entity placeVehicleWithCrate(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos ground, String vehicleItem, String crateItem,
                                        List<ItemStack> contents, List<String> problems) {
        try {
            net.minecraft.world.item.Item vehicle = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(vehicleItem));
            net.minecraft.world.item.Item crate = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(crateItem));
            if (vehicle == null || vehicle == Items.AIR || crate == null || crate == Items.AIR) { problems.add("Item not registered: " + vehicleItem + " / " + crateItem); return null; }
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(vehicle));
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(ground), net.minecraft.core.Direction.UP, ground, false);
            player.getMainHandItem().useOn(new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
            Entity placed = null;
            for (Entity each : player.serverLevel().getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(ground).inflate(6))) {
                if (each.getClass().getName().equals("mcinterface1201.BuilderEntityExisting")) { placed = each; break; }
            }
            if (placed == null) { problems.add("The vehicle item did not place a vehicle"); return null; }
            ClassLoader loader = placed.getClass().getClassLoader();
            Object mts = field(placed.getClass(), "entity").get(placed);
            Class<?> wrapperItem = Class.forName("mcinterface1201.WrapperItemStack", false, loader);
            var wrap = wrapperItem.getDeclaredConstructor(ItemStack.class);
            wrap.setAccessible(true);
            Object wrapperPlayer = Class.forName("mcinterface1201.WrapperPlayer", false, loader).getMethod("getWrapperFor", net.minecraft.world.entity.player.Player.class).invoke(null, player);
            // the crate slot: the vehicle's first part slot that takes an interactable_crate
            Object definition = field(mts.getClass(), "definition").get(mts);
            List<?> slots = (List<?>) field(definition.getClass(), "parts").get(definition);
            int index = -1;
            for (int i = 0; i < slots.size() && index < 0; i++) {
                Object types = field(slots.get(i).getClass(), "types").get(slots.get(i));
                if (((List<?>) types).contains("interactable_crate")) index = i;
            }
            if (index < 0) { problems.add("The vehicle has no crate slot"); return placed; }
            Object stack = wrap.newInstance(new ItemStack(crate));
            Class<?> iStack = Class.forName("minecrafttransportsimulator.mcinterface.IWrapperItemStack", false, loader);
            Class<?> iPlayer = Class.forName("minecrafttransportsimulator.mcinterface.IWrapperPlayer", false, loader);
            Object part = null;
            for (var method : mts.getClass().getMethods()) {
                if (method.getName().equals("addPartFromStack") && method.getParameterCount() == 5) { part = method.invoke(mts, stack, wrapperPlayer, index, true, false); break; }
            }
            if (part == null) { problems.add("The crate part was not accepted by slot " + index); return placed; }
            Object inventory = field(part.getClass(), "inventory").get(part);
            if (inventory == null) { problems.add("The crate part has no inventory"); return placed; }
            var setStack = inventory.getClass().getMethod("setStack", iStack, int.class);
            for (int i = 0; i < contents.size(); i++) setStack.invoke(inventory, wrap.newInstance(contents.get(i)), i);
            return placed;
        } catch (ReflectiveOperationException | RuntimeException error) {
            problems.add("Immersive Vehicles could not be driven by reflection: " + error);
            return null;
        }
    }

    /** The parts of a placed vehicle (an {@code AEntityF_Multipart}), by simple class name, as the mod's own objects. */
    static List<Object> vehicleParts(Entity placed, String simpleName) throws ReflectiveOperationException {
        Object mts = field(placed.getClass(), "entity").get(placed);
        List<Object> found = new ArrayList<>();
        for (Object part : (List<?>) field(mts.getClass(), "allParts").get(mts)) if (part.getClass().getSimpleName().equals(simpleName)) found.add(part);
        return found;
    }

    private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field found = c.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) { /* look in the superclass */ }
        }
        throw new NoSuchFieldException(name + " in " + type.getName());
    }

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

    /**
     * Crafts one TaCZ Gun Smith Table recipe the way the server handles a client's craft request (the menu's own {@code doCraft}, which
     * checks and consumes the materials and hands over the result). {@code kind} is "gun" or "ammo". Returns null on success, else the reason.
     */
    static String craftAtTaczTable(net.minecraft.server.level.ServerPlayer player, String kind, String resultId) {
        String key = kind.equals("gun") ? "GunId" : "AmmoId";
        // a gun the Gunsmith builds from a parts kit (D-168) is built by Core's kit recipe: the licensed one when there is one, else the unmarked one; TaCZ's own otherwise
        ResourceLocation recipeId = null;
        int best = -1;
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : player.level().getRecipeManager().getRecipes()) {
            if (!String.valueOf(recipe.getType()).equals("tacz:gun_smith_table_crafting")) continue;
            CompoundTag tag = recipe.getResultItem(player.level().registryAccess()).getTag();
            if (tag == null || !tag.getString(key).equals(resultId)) continue;
            boolean core = recipe.getId().getNamespace().equals(UndergroundCity.MOD_ID);
            int rank = core ? (recipe.getId().getPath().endsWith("_unmarked") ? 1 : 2) : 0;
            if (rank > best) { best = rank; recipeId = recipe.getId(); }
        }
        if (recipeId == null) return "TaCZ has no table recipe for " + kind + " " + resultId;
        return craftRecipeAtTaczTable(player, kind, recipeId);
    }

    /** Crafts one named Gun Smith Table recipe ({@code kind} picks the table: "gun" or "ammo") the way the server handles a client's request. Returns null on success, else the reason. */
    static String craftRecipeAtTaczTable(net.minecraft.server.level.ServerPlayer player, String kind, ResourceLocation recipeId) {
        try {
            if (player.level().getRecipeManager().byKey(recipeId).isEmpty()) return "No table recipe " + recipeId;
            // only this craft's drops: every test player stands at the world's spawn, and a hosted firearms run once picked up another test's leftovers there, which failed a
            // check that a refused craft takes nothing from the inventory
            net.minecraft.world.phys.AABB feet = player.getBoundingBox().inflate(2.0);
            java.util.Set<java.util.UUID> lying = new java.util.HashSet<>();
            for (net.minecraft.world.entity.item.ItemEntity drop : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, feet)) lying.add(drop.getUUID());
            Class<?> menuClass = Class.forName("com.tacz.guns.inventory.GunSmithTableMenu");
            Object menu = menuClass.getConstructor(int.class, net.minecraft.world.entity.player.Inventory.class, ResourceLocation.class)
                .newInstance(1, player.getInventory(), ResourceLocation.fromNamespaceAndPath("tacz", kind.equals("gun") ? "gun_smith_table" : "ammo_workbench"));
            menuClass.getMethod("doCraft", ResourceLocation.class, net.minecraft.world.entity.player.Player.class).invoke(menu, recipeId, player);
            // TaCZ does not insert the result: it drops it at the player's feet with no pickup delay, and a connected player collects it on
            // the next tick. A test player never ticks, so touch the drops the way the world would.
            for (net.minecraft.world.entity.item.ItemEntity drop : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, feet)) {
                if (!lying.contains(drop.getUUID())) drop.playerTouch(player);
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return "The table craft failed: " + error;
        }
    }

    /** The guns whose Gun Smith Table recipe builds a plain gun, with no attachment already fitted (what a contract that takes plain factory guns accepts). */
    static java.util.Set<String> plainTableGuns(net.minecraft.world.level.Level level) {
        java.util.Set<String> plain = new java.util.TreeSet<>();
        for (net.minecraft.world.item.crafting.Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            if (!String.valueOf(recipe.getType()).equals("tacz:gun_smith_table_crafting")) continue;
            CompoundTag tag = recipe.getResultItem(level.registryAccess()).getTag();
            if (tag == null || !tag.contains("GunId", net.minecraft.nbt.Tag.TAG_STRING)) continue;
            boolean fitted = tag.getAllKeys().stream().anyMatch(key -> key.startsWith("Attachment") && !key.equals("AttachmentLock"));
            if (!fitted) plain.add(tag.getString("GunId"));
        }
        return plain;
    }

    /** Test seam: turns the addon's "gun table is open to everyone" switch (its {@code enableGunTable}) on or off and returns what it was; null when the addon is absent. */
    static Boolean setGunTableOpen(boolean open) {
        try {
            Object server = Class.forName("com.raiiiden.taczblueprints.config.BlueprintConfig").getField("SERVER").get(null);
            Object value = server.getClass().getField("enableGunTable").get(server);
            Boolean previous = (Boolean) value.getClass().getMethod("get").invoke(value);
            value.getClass().getMethod("set", Object.class).invoke(value, open);
            return previous;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    /** The guns TaCZ Blueprints says this player has unlocked (empty when the mod or its capability is absent). */
    @SuppressWarnings("unchecked")
    static java.util.Set<String> unlockedGuns(net.minecraft.world.entity.player.Player player) {
        try {
            Class<?> provider = Class.forName("com.raiiiden.taczblueprints.capability.GunUnlocksProvider");
            var optional = (net.minecraftforge.common.util.LazyOptional<?>) provider.getMethod("get", net.minecraft.world.entity.player.Player.class).invoke(null, player);
            Object capability = optional.resolve().orElse(null);
            if (capability == null) return java.util.Set.of();
            Object guns = capability.getClass().getMethod("getUnlockedGuns").invoke(capability);
            return new java.util.HashSet<>((java.util.Collection<String>) guns);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return java.util.Set.of("(error: " + error + ")");
        }
    }

    /** How many stacks of {@code item} carry the given string tag value (a TaCZ gun is modern_kinetic_gun with GunId, ammo is ammo with AmmoId). */
    static int countTagged(net.minecraft.world.entity.player.Player player, String item, String key, String value) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty() || !String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())).equals(item)) continue;
            if (stack.getTag() != null && stack.getTag().getString(key).equals(value)) total += stack.getCount();
        }
        return total;
    }

    /** A one-line summary of what a player carries and what lies on the ground around them, for test failure messages. */
    static String describeInventory(net.minecraft.world.entity.player.Player player) {
        StringBuilder text = new StringBuilder("carrying");
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.isEmpty()) text.append(' ').append(stack.getCount()).append('x').append(ForgeRegistries.ITEMS.getKey(stack.getItem())).append(stack.getTag() == null ? "" : stack.getTag().toString());
        }
        text.append("; on the ground");
        for (net.minecraft.world.entity.item.ItemEntity drop : player.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, player.getBoundingBox().inflate(4.0))) {
            text.append(' ').append(drop.getItem().getCount()).append('x').append(ForgeRegistries.ITEMS.getKey(drop.getItem().getItem())).append(drop.getItem().getTag() == null ? "" : drop.getItem().getTag().toString())
                .append(" (pickup delay ").append(drop.hasPickUpDelay()).append(')');
        }
        return text.toString();
    }

    /** How many items and entity types a namespace registered; zero means the mod (or its content packs) did not register. */
    static int items(String namespace) { return (int) ForgeRegistries.ITEMS.getKeys().stream().filter(key -> key.getNamespace().equals(namespace)).count(); }
    /** Items of one namespace whose path starts with a prefix (content packs register as mts:<pack>.<name>). */
    static int itemsWithPrefix(String namespace, String prefix) {
        return (int) ForgeRegistries.ITEMS.getKeys().stream().filter(key -> key.getNamespace().equals(namespace) && key.getPath().startsWith(prefix)).count();
    }
    static int entities(String namespace) { return (int) ForgeRegistries.ENTITY_TYPES.getKeys().stream().filter(key -> key.getNamespace().equals(namespace)).count(); }
}
