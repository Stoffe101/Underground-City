package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Household safe: contextual loot, gradual shared restock, lockpick-forced entry and persistence. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PropertyGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final long MINUTE = 1200;

    private static FakePlayer citizen(GameTestHelper helper, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_p_" + career));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "ironhaven", career);
        return player;
    }

    private static PropertyCacheBlockEntity cache(GameTestHelper helper, int x) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, 2, 0));
        helper.getLevel().setBlockAndUpdate(pos, UndergroundCity.PROPERTY_CACHE.get().defaultBlockState());
        return (PropertyCacheBlockEntity) helper.getLevel().getBlockEntity(pos);
    }

    private static PropertyPolicy policy(String path) {
        PropertyPolicy policy = PropertyPolicies.INSTANCE.find(UndergroundCity.MOD_ID + ":" + path);
        check(policy != null, "Missing property policy " + path);
        return policy;
    }

    @GameTest(template = "empty")
    public static void policiesAndContextualLootAreRealAndScarce(GameTestHelper helper) {
        check(PropertyPolicies.INSTANCE.ids().size() == 5, "Five property policies expected");
        Set<String> rareCore = Set.of("machined_parts", "optical_components", "encrypted_design", "refined_alloy", "control_circuits", "security_fabric");
        var server = helper.getLevel().getServer();
        for (String name : List.of("apartment_common", "apartment_affluent", "office", "industrial", "illegal_cache")) {
            PropertyPolicy policy = policy(name);
            var table = server.getLootData().getLootTable(policy.lootTable());
            check(table != net.minecraft.world.level.storage.loot.LootTable.EMPTY, "Loot table missing for " + name);
            int core = 0, total = 0;
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < 1500; index++) {
                long seed = PropertyCacheBlockEntity.seed(new BlockPos(index, 64, 7), index);
                LootParams params = new LootParams.Builder(helper.getLevel()).withParameter(LootContextParams.ORIGIN, Vec3.ZERO).create(LootContextParamSets.CHEST);
                for (ItemStack stack : table.getRandomItems(params, seed)) {
                    check(!stack.isEmpty() && stack.getCount() <= stack.getMaxStackSize(), "Invalid generated stack in " + name);
                    total++;
                    seen.add(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
                    if (stack.getItem().builtInRegistryHolder().key().location().getNamespace().equals(UndergroundCity.MOD_ID)) core++;
                }
            }
            check(total >= 1500, name + " should always yield a stack");
            if (name.equals("apartment_common")) check(core == 0, "Ordinary homes must never yield specialist regional parts");
            else check(core > 0 && core * 100 / total <= 25, name + " imports must be rare but possible: " + core + "/" + total);
            check(seen.size() >= 4, name + " should be varied");
            if (name.equals("apartment_common") || name.equals("apartment_affluent")) {
                check(!seen.contains("underground_city:machined_parts") && !seen.contains("underground_city:optical_components")
                    && !seen.contains("underground_city:encrypted_design"), "Weapon-part stand-ins must not drop in civilian homes");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void restockArithmeticMatchesTheDesignTable(GameTestHelper helper) {
        PropertyPolicy apartment = policy("apartment_common");
        long interval = apartment.intervalTicks();
        check(interval == 40 * MINUTE, "Apartments restock every 40 active minutes");
        int[][] table = {{15, 0}, {40, 1}, {120, 3}, {240, 6}};
        for (int[] row : table) {
            var result = PropertyRestock.reconcile(0, apartment.capacity(), 0, row[0] * MINUTE, apartment.intervalTicks(), apartment.itemsPerInterval(), 0);
            check(result.add() == row[1], "After " + row[0] + " active minutes expected " + row[1] + " got " + result.add());
        }
        // Full shelves bank no backlog: after a theft only one interval returns.
        var full = PropertyRestock.reconcile(6, 6, 0, 1000 * interval, interval > Integer.MAX_VALUE ? 1 : (int) interval, 1, 0);
        check(full.add() == 0 && full.last() == 1000 * interval, "Full shelf must reset its clock");
        var afterTheft = PropertyRestock.reconcile(5, 6, full.last(), full.last() + interval, (int) interval, 1, 0);
        check(afterTheft.add() == 1, "One interval after a theft restores exactly one item");
        // Long absences are capped by max backlog and capacity.
        var capped = PropertyRestock.reconcile(0, 100, 0, 500 * interval, (int) interval, 1, 0);
        check(capped.add() == PropertyRestock.MAX_BACKLOG_INTERVALS, "Backlog is bounded");
        // Vigilance lengthens the interval by 25% per level.
        check(PropertyRestock.effectiveInterval(48_000, 2) == 72_000, "Vigilance 2 should be +50%");
        check(PropertyRestock.reconcile(0, 6, 0, 48_000, 48_000, 1, 1).add() == 0, "Vigilance slows restock");
        check(PropertyRestock.reconcile(0, 6, 0, 60_000, 48_000, 1, 1).add() == 1, "Vigilance 1 restocks after 50 minutes");
        long[] stayed = PropertyRestock.decayVigilance(2, 0, 3 * interval, (int) interval);
        check(stayed[0] == 2, "Vigilance should not decay before four base intervals");
        long[] decayed = PropertyRestock.decayVigilance(2, 0, 4 * interval, (int) interval);
        check(decayed[0] == 1 && decayed[1] == 4 * interval, "One level decays per four intervals");
        check(PropertyRestock.decayVigilance(1, 0, 40 * interval, (int) interval)[0] == 0, "Vigilance returns to zero");
        for (Runnable bad : new Runnable[] {
            () -> PropertyRestock.reconcile(-1, 6, 0, 1, 1, 1, 0), () -> PropertyRestock.reconcile(0, 6, 5, 1, 1, 1, 0),
            () -> PropertyRestock.effectiveInterval(1, 9)}) {
            try { bad.run(); throw new AssertionError("Invalid timing accepted"); } catch (IllegalArgumentException expected) { }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void registrationRestockProtectionAndPersistence(GameTestHelper helper) {
        PropertyCacheBlockEntity cache = cache(helper, 0);
        PropertyPolicy apartment = policy("apartment_common");
        long start = 5000;
        check(!cache.registered() && cache.stock() == 0, "A freshly placed safe must be inert");
        cache.register(helper.getLevel(), apartment, start);
        check(cache.stock() == apartment.initialStock(), "Initial stock should be " + apartment.initialStock() + " got " + cache.stock());
        try { cache.register(helper.getLevel(), policy("office"), start); throw new AssertionError("Re-registration replaced existing stock"); }
        catch (IllegalStateException expected) { }
        // Take everything (theft), then confirm gradual recovery.
        for (int slot = 0; slot < cache.getContainerSize(); slot++) cache.setItem(slot, ItemStack.EMPTY);
        check(cache.restock(helper.getLevel(), start + 15 * MINUTE) == 0 && cache.stock() == 0, "Nothing returns after 15 active minutes");
        check(cache.restock(helper.getLevel(), start + 40 * MINUTE) == 1 && cache.stock() == 1, "One item returns after 40 active minutes");
        check(cache.restock(helper.getLevel(), start + 40 * MINUTE) == 0, "Re-opening in the same instant must not re-roll or restock");
        check(cache.restock(helper.getLevel(), start + 120 * MINUTE) == 2 && cache.stock() == 3, "Three items after 120 active minutes");
        // Player-placed objects are never overwritten and occupied slots stay put.
        ItemStack keepsake = new ItemStack(Items.DIAMOND, 3); keepsake.setHoverName(net.minecraft.network.chat.Component.literal("Keepsake"));
        for (int slot = 0; slot < cache.getContainerSize(); slot++) if (cache.getItem(slot).isEmpty()) cache.setItem(slot, keepsake.copy());
        check(cache.stock() == cache.getContainerSize(), "Fixture should fill the safe");
        List<ItemStack> before = new ArrayList<>();
        for (int slot = 0; slot < cache.getContainerSize(); slot++) before.add(cache.getItem(slot).copy());
        check(cache.restock(helper.getLevel(), start + 1000 * MINUTE) == 0, "A full safe must accept nothing");
        for (int slot = 0; slot < cache.getContainerSize(); slot++) check(ItemStack.matches(before.get(slot), cache.getItem(slot)), "Restock overwrote an occupied slot");
        // Full NBT persistence (items + policy + clocks + vigilance) survives a save/reload.
        CompoundTag saved = cache.saveWithFullMetadata();
        PropertyCacheBlockEntity restored = new PropertyCacheBlockEntity(cache.getBlockPos(), cache.getBlockState());
        restored.load(saved);
        check(restored.policyId().equals(cache.policyId()) && restored.last() == cache.last() && restored.vigilance() == cache.vigilance(), "Safe state lost on reload");
        for (int slot = 0; slot < cache.getContainerSize(); slot++) check(ItemStack.matches(cache.getItem(slot), restored.getItem(slot)), "Inventory lost on reload");
        // Unregister empties it so operator mistakes cannot leave orphaned stock.
        cache.clear();
        check(!cache.registered() && cache.stock() == 0, "Clear must unregister and empty the safe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void forcedEntryCostsLockpicksRaisesVigilanceAndPostsEvent(GameTestHelper helper) {
        List<PropertyForcedEntryEvent> events = new ArrayList<>();
        Consumer<PropertyForcedEntryEvent> listener = events::add;
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.NORMAL, false, PropertyForcedEntryEvent.class, listener);
        try {
            PropertyCacheBlockEntity cache = cache(helper, 1);
            FakePlayer burglar = citizen(helper, "gunsmith");
            check(cache.access(helper.getLevel(), burglar, 0).contains("not part of a property"), "An unregistered safe must stay closed");
            cache.register(helper.getLevel(), policy("apartment_common"), 100);
            // Unregistered (non-citizen) players are rejected before any state changes.
            FakePlayer stranger = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_stranger"));
            stranger.getInventory().clearContent(); stranger.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 4));
            check(cache.access(helper.getLevel(), stranger, 200).contains("citizen"), "Non-citizens must not force safes");
            check(stranger.getInventory().countItem(UndergroundCity.item("lockpick")) == 4 && cache.breakins() == 0, "Rejected access changed state");
            // Citizen without lockpicks: refused, nothing consumed, no event.
            String refusal = cache.access(helper.getLevel(), burglar, 200);
            check(refusal != null && refusal.contains("1 lockpick"), "Locked safe should state its lockpick cost");
            check(cache.breakins() == 0 && events.isEmpty() && cache.vigilance() == 0, "Refused access must not count as a break-in");
            // Paying exactly the policy cost opens it once.
            burglar.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 3));
            check(cache.access(helper.getLevel(), burglar, 300) == null, "A lockpick should force an apartment safe");
            check(burglar.getInventory().countItem(UndergroundCity.item("lockpick")) == 2, "Exactly one lockpick should be consumed");
            check(cache.breakins() == 1 && cache.vigilance() == 1 && cache.lastIntruder().equals(burglar.getUUID()), "Break-in not recorded");
            check(events.size() == 1 && events.get(0).player().equals(burglar.getUUID()) && events.get(0).policy().endsWith("apartment_common"), "Forced-entry event missing");
            // Inside the forced window no further cost; after it expires the safe is locked again.
            check(cache.access(helper.getLevel(), burglar, 300 + PropertyCacheBlockEntity.FORCED_WINDOW_TICKS - 1) == null, "Window should stay open");
            check(burglar.getInventory().countItem(UndergroundCity.item("lockpick")) == 2 && events.size() == 1, "Reopening inside the window was charged");
            check(cache.access(helper.getLevel(), burglar, 300 + PropertyCacheBlockEntity.FORCED_WINDOW_TICKS) == null
                && burglar.getInventory().countItem(UndergroundCity.item("lockpick")) == 1 && cache.vigilance() == 2 && events.size() == 2,
                "Expired window should cost another pick and escalate vigilance");
            // Higher security needs more picks, and a short supply is never partially consumed.
            PropertyCacheBlockEntity vault = cache(helper, 2);
            vault.register(helper.getLevel(), policy("apartment_affluent"), 100);
            FakePlayer thief = citizen(helper, "technician");
            thief.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 2));
            check(vault.access(helper.getLevel(), thief, 200) != null && thief.getInventory().countItem(UndergroundCity.item("lockpick")) == 2, "Short on picks must consume nothing");
            thief.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 1));
            check(vault.access(helper.getLevel(), thief, 200) == null && thief.getInventory().countItem(UndergroundCity.item("lockpick")) == 0, "High security should cost three picks");
        } finally { MinecraftForge.EVENT_BUS.unregister(listener); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void vigilanceSlowsRepeatBurglaryAndDecays(GameTestHelper helper) {
        PropertyCacheBlockEntity cache = cache(helper, 3);
        PropertyPolicy apartment = policy("apartment_common");
        cache.register(helper.getLevel(), apartment, 0);
        FakePlayer burglar = citizen(helper, "gunsmith");
        burglar.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 8));
        long now = 10;
        for (int attempt = 0; attempt < 3; attempt++) {
            check(cache.access(helper.getLevel(), burglar, now) == null, "Forced entry " + attempt);
            for (int slot = 0; slot < cache.getContainerSize(); slot++) cache.setItem(slot, ItemStack.EMPTY);
            now += PropertyCacheBlockEntity.FORCED_WINDOW_TICKS + 1;
        }
        check(cache.vigilance() == 3, "Three break-ins should reach maximum vigilance");
        long base = apartment.intervalTicks();
        long anchor = cache.last();
        check(cache.restock(helper.getLevel(), anchor + base) == 0, "Restock must be slower while the residents are vigilant");
        check(cache.restock(helper.getLevel(), anchor + base * 7 / 4) == 1, "Maximum vigilance lengthens the interval by 75%");
        // Four quiet base intervals calm one level at a time.
        long calm = now + 4 * base * 3 + 1;
        cache.restock(helper.getLevel(), calm);
        check(cache.vigilance() < 3, "Vigilance did not decay during quiet active play");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedInventoryAndNoAutomationOrSurvivalBreaking(GameTestHelper helper) {
        PropertyCacheBlockEntity cache = cache(helper, 4);
        cache.register(helper.getLevel(), policy("illegal_cache"), 0);
        int stock = cache.stock();
        check(stock == policy("illegal_cache").initialStock() && stock > 0, "Cache should start stocked");
        FakePlayer a = citizen(helper, "gunsmith"), b = citizen(helper, "technician");
        a.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 4));
        check(cache.access(helper.getLevel(), a, 50) == null, "Player A forced the cache");
        // One physical inventory: what A removes is gone for B, with no duplication.
        ItemStack taken = cache.removeItem(0, 64);
        check(!taken.isEmpty() && cache.stock() == stock - 1, "A removal must reduce the shared stock");
        check(cache.access(helper.getLevel(), b, 60) == null, "Inside the forced window B shares the open safe");
        check(cache.stock() == stock - 1, "B sees the same physical inventory");
        // No hoppers, pipes or item-handler automation.
        for (Direction side : Direction.values()) {
            check(cache.getSlotsForFace(side).length == 0 && !cache.canTakeItemThroughFace(0, taken, side) && !cache.canPlaceItemThroughFace(0, taken, side),
                "Automation access on side " + side);
            check(!cache.getCapability(ForgeCapabilities.ITEM_HANDLER, side).isPresent(), "Item-handler capability exposed on " + side);
        }
        // Survival players cannot smash and grab: the block is unbreakable and drops nothing.
        var level = helper.getLevel();
        BlockPos pos = cache.getBlockPos();
        check(level.getBlockState(pos).getDestroySpeed(level, pos) < 0, "Safe must be unbreakable by survival players");
        helper.succeed();
    }
}
