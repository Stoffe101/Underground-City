package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * One shared physical household inventory (both players see the same items). Stock is topped up
 * lazily when opened, one policy-sized step per active interval, into EMPTY slots only. Opening a
 * locked safe costs lockpicks, raises resident vigilance and posts a PropertyForcedEntryEvent.
 */
public final class PropertyCacheBlockEntity extends RandomizableContainerBlockEntity implements WorldlyContainer {
    public static final int SLOTS = 9;
    /** Active ticks that a forced safe stays open for its intruders. */
    public static final long FORCED_WINDOW_TICKS = 1200;
    private static final Logger LOGGER = LogUtils.getLogger();
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private String policy = "";
    private long last, vigilanceAnchor, forcedUntil = -1;
    private int vigilance, generation, breakins;
    private UUID lastIntruder;

    public PropertyCacheBlockEntity(BlockPos pos, BlockState state) {
        super(UndergroundCity.PROPERTY_CACHE_ENTITY.get(), pos, state);
    }

    // ----------------------------------------------------------------------- state
    public String policyId() { return policy; }
    public boolean registered() { return !policy.isEmpty(); }
    public int vigilance() { return vigilance; }
    public int breakins() { return breakins; }
    public long last() { return last; }
    public boolean forcedAt(long now) { return now < forcedUntil; }
    public UUID lastIntruder() { return lastIntruder; }
    public int stock() {
        int count = 0;
        for (ItemStack stack : items) if (!stack.isEmpty()) count++;
        return count;
    }

    /** Operator registration: assigns the policy and places its initial stock. */
    public void register(ServerLevel level, PropertyPolicy newPolicy, long now) {
        if (registered()) throw new IllegalStateException("Already registered as " + policy);
        policy = newPolicy.id(); last = now; vigilance = 0; vigilanceAnchor = now; forcedUntil = -1; generation = 0; breakins = 0;
        generate(level, newPolicy, newPolicy.initialStock());
        setChanged();
    }

    /** Operator unregistration: empties the safe so a mistake cannot leave orphan stock. */
    public void clear() {
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        policy = ""; last = 0; vigilance = 0; vigilanceAnchor = 0; forcedUntil = -1; generation = 0; breakins = 0; lastIntruder = null;
        setChanged();
    }

    /** Lazily applies elapsed ACTIVE time. Returns the number of stacks added. */
    public int restock(ServerLevel level, long now) {
        PropertyPolicy active = PropertyPolicies.INSTANCE.find(policy);
        if (active == null) return 0;
        long[] decayed = PropertyRestock.decayVigilance(vigilance, vigilanceAnchor, now, active.intervalTicks());
        vigilance = (int) decayed[0]; vigilanceAnchor = decayed[1];
        PropertyRestock.Result result = PropertyRestock.reconcile(stock(), active.capacity(), last, now,
            active.intervalTicks(), active.itemsPerInterval(), vigilance);
        int added = generate(level, active, result.add());
        // Slots occupied by players can leave the shelf partially stocked; the clock still advances.
        last = result.last();
        setChanged();
        return added;
    }

    private int generate(ServerLevel level, PropertyPolicy active, int count) {
        var table = level.getServer().getLootData().getLootTable(active.lootTable());
        int added = 0;
        for (int attempt = 0; attempt < count * 4 && added < count; attempt++) {
            // Deterministic: reloads and re-opens cannot re-roll a result before a restock actually happens.
            long seed = seed(worldPosition, generation++);
            LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(worldPosition))
                .create(LootContextParamSets.CHEST);
            for (ItemStack stack : table.getRandomItems(params, seed)) {
                if (added >= count || stack.isEmpty()) break;
                int slot = firstEmptySlot();
                if (slot < 0) return added; // never overwrite anything
                items.set(slot, stack); added++;
            }
        }
        if (table == net.minecraft.world.level.storage.loot.LootTable.EMPTY) LOGGER.warn("Property loot table {} is missing", active.lootTable());
        return added;
    }

    /** SplitMix64 of (position, sequence): consecutive sequence numbers must not correlate. */
    static long seed(BlockPos pos, int sequence) {
        long z = pos.asLong() * 0x9E3779B97F4A7C15L + sequence * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private int firstEmptySlot() {
        for (int slot = 0; slot < items.size(); slot++) if (items.get(slot).isEmpty()) return slot;
        return -1;
    }

    /**
     * Server-side access decision. Returns null when the safe may open now, otherwise the refusal text.
     * Forced entry consumes lockpicks exactly once per forced window.
     */
    public String access(ServerLevel level, ServerPlayer player, long now) {
        PropertyPolicy active = PropertyPolicies.INSTANCE.find(policy);
        if (active == null) return registered() ? "This safe's profile is unavailable." : "This safe is not part of a property yet. Ask an operator to register it.";
        if (CitizenState.get(player.server).find(player.getUUID()).isEmpty()) return "Register as a citizen first.";
        restock(level, now);
        if (forcedAt(now)) return null;
        int have = player.getInventory().countItem(UndergroundCity.item("lockpick"));
        if (have < active.lockpicks()) return "Locked. Forcing this safe needs " + active.lockpicks() + " lockpick" + (active.lockpicks() == 1 ? "" : "s") + ".";
        int remaining = active.lockpicks();
        for (ItemStack stack : player.getInventory().items) {
            if (remaining > 0 && stack.is(UndergroundCity.item("lockpick"))) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take); remaining -= take;
            }
        }
        forcedUntil = now + FORCED_WINDOW_TICKS;
        vigilance = Math.min(PropertyRestock.MAX_VIGILANCE, vigilance + 1);
        vigilanceAnchor = now; breakins++; lastIntruder = player.getUUID();
        setChanged();
        MinecraftForge.EVENT_BUS.post(new PropertyForcedEntryEvent(player.getUUID(), worldPosition, policy, vigilance, now));
        return null;
    }

    // ------------------------------------------------------------------ container
    @Override protected Component getDefaultName() { return Component.translatable("container.underground_city.property_cache"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1, id, inventory, this, 1); }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> replacement) { items = replacement; }
    @Override public int getContainerSize() { return SLOTS; }

    // No hopper, pipe or other automation may pull from or push into a household safe.
    @Override public int[] getSlotsForFace(Direction side) { return new int[0]; }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) { return false; }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) { return false; }
    @Override public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        return cap == ForgeCapabilities.ITEM_HANDLER ? LazyOptional.empty() : super.getCapability(cap, side);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (!trySaveLootTable(tag)) net.minecraft.world.ContainerHelper.saveAllItems(tag, items);
        tag.putInt("schema_version", 1);
        tag.putString("policy", policy); tag.putLong("last", last); tag.putLong("vigilance_anchor", vigilanceAnchor);
        tag.putLong("forced_until", forcedUntil); tag.putInt("vigilance", vigilance); tag.putInt("generation", generation);
        tag.putInt("breakins", breakins);
        if (lastIntruder != null) tag.putUUID("last_intruder", lastIntruder);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        net.minecraft.world.ContainerHelper.loadAllItems(tag, items);
        policy = tag.getString("policy"); last = tag.getLong("last"); vigilanceAnchor = tag.getLong("vigilance_anchor");
        forcedUntil = tag.contains("forced_until") ? tag.getLong("forced_until") : -1;
        vigilance = Math.max(0, Math.min(PropertyRestock.MAX_VIGILANCE, tag.getInt("vigilance")));
        generation = Math.max(0, tag.getInt("generation")); breakins = Math.max(0, tag.getInt("breakins"));
        lastIntruder = tag.hasUUID("last_intruder") ? tag.getUUID("last_intruder") : null;
    }
}
