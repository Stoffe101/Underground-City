package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The server's own answer to "may this player craft this at TaCZ's Gun Smith Table?". TaCZ Blueprints (an addon) hides, in the client's screen, every gun
 * recipe the player has not unlocked with a blueprint item, but the server accepts any craft packet. This class applies the addon's own rule on the
 * server, from the addon's own configuration and the player's own unlocked set, so a hidden recipe cannot be crafted by a modified client either.
 *
 * <p>The rule, taken from the addon (0.1.3): a recipe whose result is a gun is
 * refused while the gun table is gated ({@code enableGunTable = false}) unless the gun is in the always-enabled list or in the player's unlocked set
 * (either spelling of its id counts, because the addon's blueprint item and its screen spell it differently);
 * an attachment or ammunition recipe is refused while its table is gated unless its item is in the matching enabled list. Anything else is allowed.
 * Everything is reached by reflection, so the Core compiles and runs without either mod and a changed addon makes the gate step aside (with one loud log
 * line) instead of breaking the crafting table.
 */
public final class BlueprintGate {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean broken;

    private BlueprintGate() {}

    /** Called by the table's {@code doCraft} before anything is taken: false refuses the craft. */
    public static boolean allowsCraft(ResourceLocation recipeId, Player player) {
        if (!(player instanceof ServerPlayer) || broken || recipeId == null) return true;
        if (!ModList.get().isLoaded("taczblueprints") || !ModList.get().isLoaded("tacz")) return true;
        try {
            Optional<? extends Recipe<?>> recipe = player.level().getRecipeManager().byKey(recipeId);
            if (recipe.isEmpty()) return true;      // TaCZ itself refuses an unknown recipe
            Method output = recipe.get().getClass().getMethod("getOutput");
            Object result = output.invoke(recipe.get());
            if (!(result instanceof ItemStack stack) || stack.isEmpty()) return true;
            boolean allowed = allows(stack, player);
            if (!allowed) {
                LOGGER.info("[UC] refused a Gun Smith Table craft of {} for {}: no blueprint", recipeId, player.getGameProfile().getName());
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal("You need a blueprint for that before the table will build it."));
                    Notice.send(serverPlayer, Notice.Kind.WARN, "No blueprint", "The table will not build this without its blueprint.");
                }
            }
            return allowed;
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            LOGGER.error("[UC] the blueprint gate could not read TaCZ / TaCZ Blueprints (a version it was not written for?): it steps aside and the table is ungated", error);
            return true;
        }
    }

    /** The decision itself, for the result stack of a recipe. Public for the tests. */
    public static boolean allows(ItemStack result, Player player) throws ReflectiveOperationException {
        Class<?> config = Class.forName("com.raiiiden.taczblueprints.config.BlueprintConfig");
        Object server = config.getField("SERVER").get(null);
        Item item = result.getItem();
        ResourceLocation registered = ForgeRegistries.ITEMS.getKey(item);
        String itemKey = registered == null ? null : registered.toString();
        if (isInstance("com.tacz.guns.api.item.IGun", item)) {
            if (flag(server, "enableGunTable")) return true;
            // The blueprint item stores a gun as "namespace:id" and the addon's screen looks it up as "namespace:gun/id": both spellings are one gun.
            Set<String> spellings = gunSpellings(result, itemKey);
            if (spellings.isEmpty()) return false;
            List<String> always = list(server, "getEnabledGuns");
            Set<String> unlocked = unlocked(player);
            for (String spelling : spellings) if (always.contains(spelling) || unlocked.contains(spelling)) return true;
            return false;
        }
        if (isInstance("com.tacz.guns.api.item.IAttachment", item)) {
            if (flag(server, "enableAttachmentTable")) return true;
            List<String> enabled = list(server, "getEnabledAttachments");
            return !enabled.isEmpty() && itemKey != null && enabled.contains(itemKey);
        }
        if (isInstance("com.tacz.guns.api.item.IAmmo", item)) {
            if (flag(server, "enableAmmoTable")) return true;
            List<String> enabled = list(server, "getEnabledAmmo");
            return !enabled.isEmpty() && itemKey != null && enabled.contains(itemKey);
        }
        return true;
    }

    /**
     * The name of a gun whose blueprint the player carries but has not read (right-clicked), or null. The addon's table shows only learned guns, so a new Gunsmith
     * who opens the table with the kit's blueprint still in the pack sees an empty pistol tab (gunsmith run 2); this is what tells them why.
     */
    static String unread(Player player) {
        if (broken || !ModList.get().isLoaded("taczblueprints")) return null;
        try {
            Set<String> learned = unlocked(player);
            for (ItemStack stack : player.getInventory().items) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (stack.isEmpty() || id == null || !id.getNamespace().equals("taczblueprints") || stack.getTag() == null || stack.getTag().getString("GunId").isEmpty()) continue;
                boolean known = false;
                for (String spelling : gunSpellings(stack, null)) known |= learned.contains(spelling);
                if (!known) return stack.getHoverName().getString();
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    /**
     * TaCZ Blueprints (0.1.3) records a gun learned from a blueprint under the blueprint's {@code GunId} ({@code tacz:glock_17}), but its table screen shows a gun only when
     * {@code tacz:gun/glock_17} is learned, so no gun learned from a blueprint ever appeared on the table (gunsmith run 4). This records every learned gun under both
     * spellings and sends the addon's own sync packet so the screen sees them. Server thread; returns how many spellings were added (0 when the addon is absent or changed).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int learnBothSpellings(ServerPlayer player) {
        if (broken || !ModList.get().isLoaded("taczblueprints")) return 0;
        try {
            Field field = Class.forName("com.raiiiden.taczblueprints.capability.GunUnlocksProvider").getField("UNLOCKS");
            Object unlocks = player.getCapability((Capability) field.get(null)).resolve().orElse(null);
            if (unlocks == null) return 0;
            Method all = unlocks.getClass().getMethod("getUnlockedGuns"), unlock = unlocks.getClass().getMethod("unlockGun", String.class);
            Set<String> learned = new java.util.HashSet<>((Set<String>) all.invoke(unlocks));
            int added = 0;
            for (String id : learned) for (String spelling : spellings(id)) if (!learned.contains(spelling)) { unlock.invoke(unlocks, spelling); added++; }
            if (added > 0) {
                Class<?> packet = Class.forName("com.raiiiden.taczblueprints.network.SyncUnlockedGunsPacket");
                Object sync = packet.getConstructor(Set.class).newInstance(new java.util.HashSet<>((Set<String>) all.invoke(unlocks)));
                Class.forName("com.raiiiden.taczblueprints.network.ModNetworking").getMethod("sendToPlayer", ServerPlayer.class, packet).invoke(null, player, sync);
                LOGGER.info("[UC] recorded {} learned gun(s) of {} under TaCZ Blueprints' table spelling too", added, player.getGameProfile().getName());
            }
            return added;
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            LOGGER.error("[UC] could not reconcile TaCZ Blueprints' two spellings of a learned gun (a version it was not written for?): the gate steps aside", error);
            return 0;
        }
    }

    /** Every spelling of a gun's id the two mods use: {@code ns:id} and {@code ns:gun/id}, from its {@code GunId} (or, failing that, its item). */
    static Set<String> gunSpellings(ItemStack stack, String itemKey) {
        CompoundTag tag = stack.getTag();
        String gunId = tag != null && tag.contains("GunId") ? tag.getString("GunId") : null;
        return spellings(gunId != null && !gunId.isEmpty() ? gunId : itemKey);
    }

    static Set<String> spellings(String source) {
        ResourceLocation id = source == null ? null : ResourceLocation.tryParse(source);
        if (id == null) return Set.of();
        String bare = id.getPath().startsWith("gun/") ? id.getPath().substring(4) : id.getPath();
        return Set.of(new ResourceLocation(id.getNamespace(), bare).toString(), new ResourceLocation(id.getNamespace(), "gun/" + bare).toString());
    }

    private static boolean isInstance(String className, Object value) throws ClassNotFoundException {
        return Class.forName(className).isInstance(value);
    }

    private static boolean flag(Object server, String field) throws ReflectiveOperationException {
        Object value = server.getClass().getField(field).get(server);
        return (Boolean) value.getClass().getMethod("get").invoke(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Object server, String method) throws ReflectiveOperationException {
        return (List<String>) server.getClass().getMethod(method).invoke(server);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Set<String> unlocked(Player player) throws ReflectiveOperationException {
        Field field = Class.forName("com.raiiiden.taczblueprints.capability.GunUnlocksProvider").getField("UNLOCKS");
        Capability capability = (Capability) field.get(null);
        Object unlocks = player.getCapability(capability).resolve().orElse(null);
        if (unlocks == null) return Set.of();
        return (Set<String>) unlocks.getClass().getMethod("getUnlockedGuns").invoke(unlocks);
    }
}
