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
 * locked safe is a lockpicking game on the server (a snapped pick costs a lockpick), raises resident vigilance and posts a PropertyForcedEntryEvent.
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
    /** True when registration placed an exceptional find in this safe (never granted again, see ExceptionalLoot). */
    private boolean exceptional;
    /** True once somebody has opened this safe after registration placed an exceptional find: the "something special" cue is spent. */
    private boolean revealed;
    private UUID lastIntruder;
    /** The lock of a safe placed in a city building (D-144: a wall safe or a vault safe in a wealthy place), "" for the policy's; and the building's tier (0 when not in one). */
    private String lock = "";
    private int tier;

    public PropertyCacheBlockEntity(BlockPos pos, BlockState state) {
        super(UndergroundCity.PROPERTY_CACHE_ENTITY.get(), pos, state);
    }

    // ----------------------------------------------------------------------- state
    public String policyId() { return policy; }
    public boolean registered() { return !policy.isEmpty(); }
    public int vigilance() { return vigilance; }
    public int breakins() { return breakins; }
    public boolean exceptional() { return exceptional; }
    public long last() { return last; }
    public boolean forcedAt(long now) { return now < forcedUntil; }
    public UUID lastIntruder() { return lastIntruder; }
    public int tier() { return tier; }
    /** The safe's kind in a burglar's words, by its lock. */
    public String label() { return switch (lock) { case "wall_safe" -> "Wall safe"; case "vault_safe" -> "Vault safe"; default -> "Household safe"; }; }

    /** A safe in a city building takes the building's lock and tier (D-144). */
    public void fitFor(int tier, String lockProfile) { this.tier = Math.max(0, Math.min(6, tier)); this.lock = lockProfile == null ? "" : lockProfile; setChanged(); }
    public int stock() {
        int count = 0;
        for (ItemStack stack : items) if (!stack.isEmpty()) count++;
        return count;
    }

    /** Operator registration: assigns the policy and places its initial stock. */
    public void register(ServerLevel level, PropertyPolicy newPolicy, long now) {
        if (registered()) throw new IllegalStateException("Already registered as " + policy);
        revealed = false;
        policy = newPolicy.id(); last = now; vigilance = 0; vigilanceAnchor = now; forcedUntil = -1; generation = 0; breakins = 0;
        // The exceptional find is rolled once, first, from a seed that depends only on the safe's position:
        // restocking never rolls it and clearing then re-registering the same safe gives the same answer.
        ItemStack rare = ExceptionalLoot.INSTANCE.roll(newPolicy.id(), seed(worldPosition, 0x45584345));
        exceptional = !rare.isEmpty() && firstEmptySlot() >= 0;
        if (exceptional) items.set(firstEmptySlot(), rare);
        generate(level, newPolicy, Math.max(0, newPolicy.initialStock() - (exceptional ? 1 : 0)));
        setChanged();
    }

    /** Operator unregistration: empties the safe so a mistake cannot leave orphan stock. */
    public void clear() {
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        policy = ""; last = 0; vigilance = 0; vigilanceAnchor = 0; forcedUntil = -1; generation = 0; breakins = 0; lastIntruder = null; exceptional = false; revealed = false;
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

    /** What a click on the safe does: open it, refuse with a reason, or start a lockpick on it. */
    public record Entry(Kind kind, String text, String profile, int picksNeeded) {
        public enum Kind { OPEN, REFUSED, PICK }
        static Entry refused(String text) { return new Entry(Kind.REFUSED, text, "", 0); }
    }

    /**
     * Server-side access decision, taken before anything is spent. A safe that was forced recently stays open for its window ({@link #FORCED_WINDOW_TICKS}), to whoever comes next as well;
     * a closed one is picked by the player on the lockpicking screen ({@link LockService}), which needs the policy's number of lockpicks in the inventory and costs one when the pick snaps.
     */
    public Entry entry(ServerLevel level, ServerPlayer player, long now) {
        PropertyPolicy active = PropertyPolicies.INSTANCE.find(policy);
        if (active == null) return Entry.refused(registered() ? "This safe's profile is unavailable." : "This safe is not part of a property yet. Ask an operator to register it.");
        if (CitizenState.get(player.server).find(player.getUUID()).isEmpty()) return Entry.refused("Register as a citizen first.");
        restock(level, now);
        String profile = lock.isEmpty() ? active.lockProfile() : lock;
        if (forcedAt(now)) { cue(level, player, false); return new Entry(Entry.Kind.OPEN, "", profile, 0); }
        return new Entry(Entry.Kind.PICK, "", profile, active.lockpicks());
    }

    /** The lock gave way: the safe stays open for its window, its residents grow more watchful and the break-in is posted for witness and evidence systems. */
    public void forcedOpenBy(ServerLevel level, ServerPlayer player, long now) {
        forcedUntil = now + FORCED_WINDOW_TICKS;
        vigilance = Math.min(PropertyRestock.MAX_VIGILANCE, vigilance + 1);
        vigilanceAnchor = now; breakins++; lastIntruder = player.getUUID();
        // what is in the safe when its lock gives way is stolen goods for whoever carries it out (D-122); stock that arrives later is not marked until the next forcing
        String city = CityRegions.INSTANCE.cityAt(level, worldPosition).orElse("");
        for (ItemStack stack : items) StolenGoods.mark(stack, city, player.getUUID());
        setChanged();
        MinecraftForge.EVENT_BUS.post(new PropertyForcedEntryEvent(player.getUUID(), worldPosition, policy, vigilance, now));
        cue(level, player, true);
    }

    /**
     * Feedback for a burglary: the lock giving way, and, exactly once per safe, the cue that a registered exceptional find is inside. The cue is
     * a flag on the safe, not a roll, so it changes nothing about how rare the find is or who can take it.
     */
    private void cue(ServerLevel level, ServerPlayer player, boolean forced) {
        if (forced) {
            level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.IRON_TRAPDOOR_OPEN, net.minecraft.sounds.SoundSource.BLOCKS, 0.9f, 0.6f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0, worldPosition.getZ() + 0.5, 8, 0.25, 0.1, 0.25, 0.1);
            Notice.send(player, Notice.Kind.WARN, "Lock forced", "The residents are on alert. Whatever you take is marked as stolen.");
        }
        if (exceptional && !revealed) {
            revealed = true;
            setChanged();
            level.playSound(null, worldPosition, net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, net.minecraft.sounds.SoundSource.BLOCKS, 0.9f, 1.0f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, worldPosition.getX() + 0.5, worldPosition.getY() + 1.1, worldPosition.getZ() + 0.5, 14, 0.3, 0.2, 0.3, 0.02);
            Notice.send(player, Notice.Kind.LOOT, "A rare find", "Worth more than the rest. Take it and go.");
        }
    }

    // ------------------------------------------------------------------ container
    @Override protected Component getDefaultName() { return Component.translatable("container.underground_city.property_cache"); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) {
        ChestMenu menu = new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1, id, inventory, this, 1);
        // A safe is somebody else's store, not the burglar's: things can be taken out, never put in (D-125). Otherwise a player could put their own goods into a forced safe and have them marked
        // stolen the next time it is forced, and turn anything into fence money. The server's slots refuse a placement; the client's ordinary chest screen is corrected by the server.
        for (int i = 0; i < SLOTS; i++) {
            net.minecraft.world.inventory.Slot old = menu.slots.get(i);
            net.minecraft.world.inventory.Slot takeOnly = new net.minecraft.world.inventory.Slot(this, old.getContainerSlot(), old.x, old.y) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
            };
            takeOnly.index = i;
            menu.slots.set(i, takeOnly);
        }
        return menu;
    }
    /** Nothing is ever put into a safe from outside (hoppers already cannot; this covers anything that asks). */
    @Override public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
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
        tag.putInt("breakins", breakins); tag.putBoolean("exceptional", exceptional); tag.putBoolean("revealed", revealed);
        if (lastIntruder != null) tag.putUUID("last_intruder", lastIntruder);
        if (!lock.isEmpty()) tag.putString("lock", lock);
        if (tier > 0) tag.putInt("tier", tier);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        net.minecraft.world.ContainerHelper.loadAllItems(tag, items);
        policy = tag.getString("policy"); last = tag.getLong("last"); vigilanceAnchor = tag.getLong("vigilance_anchor");
        forcedUntil = tag.contains("forced_until") ? tag.getLong("forced_until") : -1;
        vigilance = Math.max(0, Math.min(PropertyRestock.MAX_VIGILANCE, tag.getInt("vigilance")));
        generation = Math.max(0, tag.getInt("generation")); breakins = Math.max(0, tag.getInt("breakins")); exceptional = tag.getBoolean("exceptional"); revealed = tag.getBoolean("revealed");
        lastIntruder = tag.hasUUID("last_intruder") ? tag.getUUID("last_intruder") : null;
        lock = tag.getString("lock"); tier = Math.max(0, Math.min(6, tag.getInt("tier")));
    }
}
