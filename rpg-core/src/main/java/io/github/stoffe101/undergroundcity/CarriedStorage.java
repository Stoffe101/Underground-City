package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a traveller carries that a checkpoint guard may search, and how a seized stack leaves it (D-051, D-100).
 *
 * <p>The guard searches what is on the person: the pockets, the hands, the armour slots (a backpack is worn in the chest slot) and what those items
 * carry: a Sophisticated Backpack's slots, a shulker box and a bundle. It never searches storage the traveller is not carrying: a chest in a house, another
 * player's pack, a container merely within reach. Before this class the search read only the 36 inventory slots and the off hand, so a worn backpack, an armour
 * slot, a backpack in a pocket and a shulker box all hid contraband (found in the October 20 audit and closed with tests).
 *
 * <p>A search works on a <em>copy</em> of the inventory that is journaled and replayed (see {@code WorkOrderLedger.prepareSeizure}). Items carried in nested NBT
 * (shulker boxes, bundles) are changed in that copy and so travel with the journaled inventory. A backpack's slots are not inventory NBT: they live in the mod's
 * own saved data under the pack's contents id, so a stack taken from a pack is recorded as a {@link Removal} (pack id, slot, the stack expected there, what
 * replaces it) and applied during settlement by {@link #apply}, which is idempotent: a replay after a crash finds the slot already changed and does nothing. The
 * copy's own handler is never written to, because it points at the live storage.
 *
 * <p>Sophisticated Backpacks is reached by reflection only (it is a runtime-only, all-rights-reserved download, D-092). When the mod is absent nothing here
 * touches it; when it is present but its internals are not what this class expects, packs are skipped with one log line and the search carries on without them.
 */
public final class CarriedStorage {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean packBroken;

    private CarriedStorage() {}

    private record Pack(UUID contents, IItemHandlerModifiable inventory) {}

    /**
     * A change to one slot of a backpack, recorded so that it can be replayed exactly once. {@code replacement} is empty when the whole stack goes, or the carrier
     * (a shulker box, a bundle) with one item taken out. When {@code live} is set (a pack in a vehicle's hold, which no journal covers) a change is applied at once.
     */
    public static final class Removal {
        final Pack pack;
        final int slot;
        final ItemStack expected;
        ItemStack replacement = ItemStack.EMPTY;
        /** The whole stack goes (a change to a carrier inside it no longer matters). */
        boolean whole;

        Removal(Pack pack, int slot, ItemStack expected) { this.pack = pack; this.slot = slot; this.expected = expected.copy(); }

        void changed(boolean live) {
            if (live && ItemStack.matches(pack.inventory.getStackInSlot(slot), expected)) pack.inventory.setStackInSlot(slot, replacement.copy());
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("pack", pack.contents); tag.putInt("slot", slot);
            tag.put("expected", saveStack(expected));
            tag.put("replacement", saveStack(replacement));
            return tag;
        }
    }

    /** One stack found on the traveller: where it was, what it is, and how to take it. */
    public static final class Found {
        public final ItemStack stack;
        public final String where;
        private final Runnable remove;
        private final Removal removal;

        Found(ItemStack stack, String where, Runnable remove, Removal removal) { this.stack = stack; this.where = where; this.remove = remove; this.removal = removal; }

        /** Takes the whole stack off the traveller: out of the inventory copy, or into the removals the journal will replay. */
        public void take() { remove.run(); }

        Removal removal() { return removal; }
    }

    // ------------------------------------------------------------------------------------------------------------ what the traveller carries

    /** Every stack in the copy of the traveller's inventory, including what the carriers in it hold. */
    public static List<Found> scan(Inventory copy) {
        List<Found> found = new ArrayList<>();
        scanSlots(copy.items, "your pockets", found);
        scanSlots(copy.armor, "what you wear", found);
        scanSlots(copy.offhand, "your hands", found);
        return found;
    }

    private static void scanSlots(List<ItemStack> slots, String where, List<Found> out) {
        for (int slot = 0; slot < slots.size(); slot++) {
            ItemStack stack = slots.get(slot);
            if (stack.isEmpty()) continue;
            int index = slot;
            out.add(new Found(stack, where, () -> slots.set(index, ItemStack.EMPTY), null));
            carried(stack, out, false);
        }
    }

    /**
     * What a carrier in the traveller's pockets holds. A pack's stacks are never changed in the copy (it points at live storage): taking one records a
     * {@link Removal}. A shulker box or bundle inside a pack is handled on a working copy that replaces it in its slot.
     */
    private static void carried(ItemStack carrier, List<Found> out, boolean live) {
        if (nbtCarrier(carrier)) {
            nested(carrier, null, out, live);
            return;
        }
        backpack(carrier).ifPresent(pack -> {
            for (int slot = 0; slot < pack.inventory.getSlots(); slot++) {
                ItemStack inside = pack.inventory.getStackInSlot(slot);
                if (inside.isEmpty()) continue;
                Removal removal = new Removal(pack, slot, inside);
                ItemStack working = inside.copy();
                out.add(new Found(working.copy(), live ? "the backpack" : "your backpack", () -> { removal.whole = true; removal.replacement = ItemStack.EMPTY; removal.changed(live); }, removal));
                if (nbtCarrier(working)) nested(working, removal, out, live);
            }
        });
    }

    /** A stack in a vehicle's hold, taken by the hold itself. */
    public static Found inHold(ItemStack stack, String where, Runnable remove) { return new Found(stack, where, remove, null); }

    private static boolean nbtCarrier(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock || stack.getItem() instanceof BundleItem;
    }

    /** The items of a shulker box or a bundle, each removable from the carrier; when the carrier sits in a pack, the change is recorded on that pack slot. */
    private static void nested(ItemStack carrier, Removal inPack, List<Found> out, boolean live) {
        boolean shulker = carrier.getItem() instanceof BlockItem;
        ListTag items = shulker ? entityItems(carrier) : carrier.getTag() == null ? new ListTag() : carrier.getTag().getList("Items", Tag.TAG_COMPOUND);
        String where = (inPack != null ? "your backpack, in " : "") + (shulker ? "a shulker box" : "a bundle");
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            ItemStack inside = ItemStack.of(entry);
            if (inside.isEmpty()) continue;
            int slotByte = entry.getByte("Slot") & 255;
            out.add(new Found(inside, where, () -> {
                if (shulker) removeFromShulker(carrier, slotByte); else removeFromBundle(carrier, inside);
                if (inPack != null && !inPack.whole) { inPack.replacement = carrier; inPack.changed(live); }
            }, inPack));
        }
    }

    private static ListTag entityItems(ItemStack box) {
        CompoundTag entity = box.getTagElement("BlockEntityTag");
        return entity == null ? new ListTag() : entity.getList("Items", Tag.TAG_COMPOUND);
    }

    private static void removeFromShulker(ItemStack box, int slotByte) {
        CompoundTag entity = box.getTagElement("BlockEntityTag");
        if (entity == null) return;
        ListTag items = entity.getList("Items", Tag.TAG_COMPOUND);
        for (int i = items.size() - 1; i >= 0; i--) if ((items.getCompound(i).getByte("Slot") & 255) == slotByte) items.remove(i);
        if (items.isEmpty()) entity.remove("Items");
        if (entity.isEmpty()) box.removeTagKey("BlockEntityTag");
    }

    private static void removeFromBundle(ItemStack bundle, ItemStack inside) {
        CompoundTag tag = bundle.getTag();
        if (tag == null) return;
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            if (ItemStack.matches(ItemStack.of(items.getCompound(i)), inside)) { items.remove(i); break; }
        }
        if (items.isEmpty()) tag.remove("Items");
    }

    /**
     * The contents of a backpack lying in a vehicle's hold. A vehicle is not part of the journaled inventory, so a stack taken from such a pack leaves it at once,
     * like the hold's own stacks (see {@link VehicleCargo}).
     */
    public static List<Found> scanHold(ItemStack holdStack) {
        List<Found> found = new ArrayList<>();
        if (!holdStack.isEmpty() && !nbtCarrier(holdStack)) carried(holdStack, found, true);
        return found;
    }

    // ------------------------------------------------------------------------------------------------------------ the journal

    /** The distinct pack changes of the stacks that were taken, as the list a prepared seizure record carries. */
    public static ListTag journal(List<Found> taken) {
        ListTag list = new ListTag();
        List<Removal> seen = new ArrayList<>();
        for (Found f : taken) {
            Removal r = f.removal();
            if (r != null && !seen.contains(r)) { seen.add(r); list.add(r.save()); }
        }
        return list;
    }

    /**
     * A stack as the journal keeps it. {@code ItemStack.save} writes the count as a single signed byte, so a slot of a Sophisticated Backpack with a stack upgrade (128 of a 64-stacking item)
     * would be read back as -128 and never match the live slot: the seizure would be fined, announced and never carried out. The count is therefore also kept as a whole number.
     */
    static CompoundTag saveStack(ItemStack stack) {
        CompoundTag tag = stack.save(new CompoundTag());
        tag.putInt("UcCount", stack.getCount());
        return tag;
    }

    static ItemStack loadStack(CompoundTag tag) {
        ItemStack stack = ItemStack.of(tag);
        // the byte count of a stack of 128 or more reads back as zero or negative, which the game treats as an empty stack although the item is still named: restore the real count
        // (ItemStack.getItem() already answers air for such a stack, so the item is read from the saved id)
        String id = tag.getString("id");
        if (tag.contains("UcCount", net.minecraft.nbt.Tag.TAG_INT) && !id.isEmpty() && !id.equals("minecraft:air")) stack.setCount(tag.getInt("UcCount"));
        return stack;
    }

    /**
     * Replays a prepared seizure's pack changes on the packs the player carries. For each: find the pack by its contents id; if the slot still holds the stack that
     * was seen there, put the replacement in; if it holds anything else (a replay found the change already made), leave it alone. Idempotent.
     */
    public static int apply(ServerPlayer player, ListTag removals) {
        int changed = 0;
        for (int i = 0; i < removals.size(); i++) {
            CompoundTag tag = removals.getCompound(i);
            UUID id = tag.getUUID("pack");
            int slot = tag.getInt("slot");
            ItemStack expected = loadStack(tag.getCompound("expected")), replacement = loadStack(tag.getCompound("replacement"));
            Optional<Pack> live = findCarried(player, id);
            if (live.isEmpty()) { LOGGER.warn("[UC] a prepared seizure names a backpack ({}) the player no longer carries; slot {} was not changed", id, slot); continue; }
            if (!ItemStack.matches(live.get().inventory.getStackInSlot(slot), expected)) continue;
            live.get().inventory.setStackInSlot(slot, replacement.copy());
            changed++;
        }
        return changed;
    }

    private static Optional<Pack> findCarried(ServerPlayer player, UUID contents) {
        Inventory inventory = player.getInventory();
        List<ItemStack> all = new ArrayList<>(inventory.items);
        all.addAll(inventory.armor);
        all.addAll(inventory.offhand);
        for (ItemStack stack : all) {
            Optional<Pack> pack = backpack(stack);
            if (pack.isPresent() && pack.get().contents.equals(contents)) return pack;
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------------------ Sophisticated Backpacks

    /** True when the stack is one of Sophisticated Backpacks' packs (not an upgrade or any other item of the mod). */
    public static boolean isBackpackItem(ItemStack stack) {
        if (stack.isEmpty() || packBroken || !ModList.get().isLoaded("sophisticatedbackpacks")) return false;
        var key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null && key.getNamespace().equals("sophisticatedbackpacks") && stack.getItem().getClass().getSimpleName().contains("Backpack");
    }

    private static Optional<Pack> backpack(ItemStack stack) {
        if (!isBackpackItem(stack)) return Optional.empty();
        try {
            Optional<UUID> id = BackpackSupport.contentsUuid(stack);
            if (id.isEmpty()) return Optional.empty();           // a pack that has never held anything has no storage to search
            return Optional.of(new Pack(id.get(), BackpackSupport.inventory(stack)));
        } catch (Exception error) {
            // An upgrade item of the same mod has no wrapper, which is normal; a pack whose API changed is not, and is reported once.
            if (stack.getItem().getClass().getSimpleName().equals("BackpackItem") && !packBroken) {
                packBroken = true;
                LOGGER.warn("[UC] Sophisticated Backpacks' storage could not be read ({}); checkpoint searches skip backpacks until this is looked at", error.toString());
            }
            return Optional.empty();
        }
    }
}
