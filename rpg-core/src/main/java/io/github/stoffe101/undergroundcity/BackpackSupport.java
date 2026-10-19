package io.github.stoffe101.undergroundcity;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.Optional;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.ModReflect.call;
import static io.github.stoffe101.undergroundcity.ModReflect.callOn;
import static io.github.stoffe101.undergroundcity.ModReflect.cls;
import static io.github.stoffe101.undergroundcity.ModReflect.item;

/**
 * Sophisticated Backpacks reached by reflection (it is an optional, all-rights-reserved runtime mod: Core has no compile-time dependency on it, D-092). Shared by the
 * backpack GameTests and the combined journey. Each call fails loudly with an exception when the mod's API is not what is expected, never quietly.
 */
final class BackpackSupport {
    private BackpackSupport() {}

    static final String ITEM = SurvivalSet.BACKPACK;
    private static final String CAPABILITY_CLASS = "net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper";

    static ItemStack stack(String registryName) { return new ItemStack(item(registryName)); }

    /** The mod's own storage wrapper of a backpack stack (items, upgrades, contents UUID). */
    @SuppressWarnings("unchecked")
    static Object wrapper(ItemStack backpack) throws Exception {
        Capability<Object> capability = (Capability<Object>) call(cls(ITEM, CAPABILITY_CLASS), "getCapabilityInstance");
        return backpack.getCapability(capability).resolve().orElseThrow(() -> new IllegalStateException(backpack + " carries no backpack wrapper"));
    }

    /** The backpack's item slots as a handler (the object the GUI and the automation both use). */
    static IItemHandlerModifiable inventory(ItemStack backpack) throws Exception { return (IItemHandlerModifiable) callOn(wrapper(backpack), "getInventoryHandler"); }

    /** The backpack's upgrade slots. */
    static IItemHandlerModifiable upgrades(ItemStack backpack) throws Exception {
        Object wrapper = wrapper(backpack);
        callOn(wrapper, "getInventoryHandler");   // the two handlers are built together; asked first, the upgrade handler of a brand-new pack has no slots yet
        return (IItemHandlerModifiable) callOn(wrapper, "getUpgradeHandler");
    }

    @SuppressWarnings("unchecked")
    static Optional<UUID> contentsUuid(ItemStack backpack) throws Exception { return (Optional<UUID>) callOn(wrapper(backpack), "getContentsUuid"); }

    /** Number of item slots and of upgrade slots the item reports (these follow the server configuration). */
    static int slots(ItemStack backpack) throws Exception { return (Integer) callOn(backpack.getItem(), "getNumberOfSlots"); }
    static int upgradeSlots(ItemStack backpack) throws Exception { return (Integer) callOn(backpack.getItem(), "getNumberOfUpgradeSlots"); }

    /** A backpack is worn in the chest slot. */
    static void wear(Player player, ItemStack backpack) { player.setItemSlot(EquipmentSlot.CHEST, backpack); }
    static boolean worn(Player player) { return player.getItemBySlot(EquipmentSlot.CHEST).getItem() == item(ITEM) || player.getItemBySlot(EquipmentSlot.CHEST).getItem().getClass() == item(ITEM).getClass(); }

    /** Puts the whole stack into the handler (any free or matching slot); false when something was left over. */
    static boolean insertAll(IItemHandlerModifiable handler, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !rest.isEmpty(); slot++) rest = handler.insertItem(slot, rest, false);
        return rest.isEmpty();
    }

    /** How many items of a kind a handler holds in all of its slots. */
    static int count(IItemHandlerModifiable handler, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) if (handler.getStackInSlot(slot).is(item)) total += handler.getStackInSlot(slot).getCount();
        return total;
    }
}
