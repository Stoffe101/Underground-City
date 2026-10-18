package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.List;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The survival set's mechanics, played through the mods' own code (D-091): thirst that drains with effort and is restored by drinking, water whose purity
 * decides whether drinking makes you ill, canteens that hold a few servings, boiling water safe, a backpack that holds your things through a save and a load,
 * and a cooking pot that turns ingredients into a meal you can eat. The mods are reached by reflection (Core has no compile-time dependency on them) and each
 * test passes at once when the set is not loaded, like the Vinery and TaCZ tests; the hosted pack job {@code survival} runs them.
 *
 * <p>What this is not: it does not show that any of it is pleasant, that a human finds the water, or how the HUD reads in play. It drives the same public
 * methods and items the mods use for a player, on the dedicated test server, with scripted players.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurvivalMechanicsGameTests {
    private SurvivalMechanicsGameTests() {}

    private static boolean loaded() {
        return ModList.get().isLoaded("travelersbackpack") && ModList.get().isLoaded("thirstwastaken2") && ModList.get().isLoaded("farmersdelight") && ModList.get().isLoaded("comforts");
    }

    // ------------------------------------------------------------------------------------------------------------ reflection helpers (see ModReflect)

    private static Class<?> cls(String itemId, String name) throws Exception { return ModReflect.cls(itemId, name); }
    private static Object call(Class<?> type, String method, Object... args) throws Exception { return ModReflect.call(type, method, args); }
    private static Object callOn(Object target, String method, Object... args) throws Exception { return ModReflect.callOn(target, method, args); }
    private static int staticInt(Class<?> type, String field) throws Exception { return ModReflect.staticInt(type, field); }
    private static Item item(String id) { return ModReflect.item(id); }

    // ------------------------------------------------------------------------------------------------------------ thirst

    private static final String THIRST = "thirstwastaken2:waterskin";

    private static int thirst(Player player) throws Exception { return (Integer) call(cls(THIRST, "com.thirstwastaken2.api.ThirstApi"), "thirst", player); }

    private static void makeNormalDifficulty(ServerLevel level) { if (level.getDifficulty() == Difficulty.PEACEFUL) level.getServer().setDifficulty(Difficulty.NORMAL, true); }

    @GameTest(template = "empty")
    public static void thirstDrainsWithEffortAndDrinkingReplenishesIt(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        makeNormalDifficulty(level);
        ServerPlayer p = player(helper, "uc_thirsty", "farmer");
        Class<?> api = cls(THIRST, "com.thirstwastaken2.api.ThirstApi"), manager = cls(THIRST, "com.thirstwastaken2.data.ThirstManager");
        int max = (Integer) call(api, "maxThirst");
        check((Boolean) call(api, "isEnabled", p), "thirst is switched on for a survival player");
        check(thirst(p) == max, "a new player starts quenched: " + thirst(p) + " of " + max);
        // effort: the vanilla way a player tires (the game calls this when you sprint, jump, mine or are hurt) reaches the thirst bar through the mod's hook
        int last = max;
        for (int i = 0; i < 400; i++) {
            p.causeFoodExhaustion(1.0f);
            call(manager, "tickPlayer", p);
            int now = thirst(p);
            check(now <= last, "thirst went up by itself at step " + i + ": " + last + " -> " + now);
            last = now;
        }
        check(last < max, "400 units of effort did not make the player thirsty: " + last + " of " + max);
        // drinking restores it: a bottle of clean water, as the mod computes its value
        int before = thirst(p);
        ItemStack bottle = (ItemStack) call(api, "waterBottle", staticInt(cls(THIRST, "com.thirstwastaken2.purity.WaterPurity"), "BOILED"));
        int[] values = (int[]) call(api, "thirstValues", bottle);
        check(values.length >= 1 && values[0] > 0, "a clean water bottle should restore thirst: " + java.util.Arrays.toString(values));
        bottle.finishUsingItem(level, p);
        int after = thirst(p);
        check(after > before && after <= max, "drinking did not restore thirst: " + before + " -> " + after + " (the bottle promises " + values[0] + ")");
        check(after == Math.min(max, before + values[0]), "drinking restored " + (after - before) + " but the bottle promises " + values[0]);
        // being thirsty has a cost: with the bar low the player cannot sprint (the mod's rule), and drinking gives it back
        Object empty = callOn(call(cls(THIRST, "com.thirstwastaken2.data.ThirstData"), "full"), "withLevels", 1, 0);
        call(manager, "set", p, empty);
        check(!(Boolean) call(manager, "allowsSprinting", p), "a nearly dry player may still sprint");
        call(manager, "set", p, call(cls(THIRST, "com.thirstwastaken2.data.ThirstData"), "full"));
        check((Boolean) call(manager, "allowsSprinting", p), "a quenched player may not sprint");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cleanWaterIsSafeAndDirtyWaterMakesYouIll(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        makeNormalDifficulty(level);
        ServerPlayer p = player(helper, "uc_ill", "farmer");
        Class<?> api = cls(THIRST, "com.thirstwastaken2.api.ThirstApi"), purity = cls(THIRST, "com.thirstwastaken2.purity.WaterPurity");
        int dirty = staticInt(purity, "MIN"), boiled = staticInt(purity, "BOILED");
        check(dirty < boiled, "the dirtiest water is below boiled water");
        int illFromClean = 0, illFromDirty = 0;
        for (int i = 0; i < 300; i++) {
            p.removeAllEffects();
            ItemStack clean = (ItemStack) call(api, "waterBottle", boiled);
            call(purity, "applyEffects", p, clean);
            if (!p.getActiveEffects().isEmpty()) illFromClean++;
            p.removeAllEffects();
            ItemStack bad = (ItemStack) call(api, "waterBottle", dirty);
            call(purity, "applyEffects", p, bad);
            if (!p.getActiveEffects().isEmpty()) illFromDirty++;
        }
        check(illFromClean == 0, "boiled water made the player ill " + illFromClean + " times in 300 drinks");
        check(illFromDirty > 20, "dirty water made the player ill only " + illFromDirty + " times in 300 drinks; it is supposed to be a real risk");
        // the purity travels with the bottle through a save and a load
        ItemStack bottle = (ItemStack) call(api, "waterBottle", boiled);
        ItemStack reloaded = ItemStack.of(bottle.save(new CompoundTag()));
        check((Integer) call(api, "purity", reloaded) == boiled, "the purity of a bottle did not survive a save and a load");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ canteens

    @GameTest(template = "empty")
    public static void aCanteenHoldsServingsOfWaterAndEachDrinkTakesOne(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        makeNormalDifficulty(level);
        ServerPlayer p = player(helper, "uc_canteen", "farmer");
        Class<?> skin = cls(THIRST, "com.thirstwastaken2.item.WaterskinItem"), manager = cls(THIRST, "com.thirstwastaken2.data.ThirstManager"), purity = cls(THIRST, "com.thirstwastaken2.purity.WaterPurity");
        int boiled = staticInt(purity, "BOILED"), dirty = staticInt(purity, "MIN");
        for (String id : List.of("thirstwastaken2:waterskin", "thirstwastaken2:copper_canteen", "thirstwastaken2:iron_flask")) {
            ItemStack flask = new ItemStack(item(id));
            int capacity = (Integer) call(skin, "capacity", flask);
            check(capacity >= 4 && (Integer) call(skin, "servings", flask) == 0, id + " is empty and holds at least 4 servings, holds " + capacity);
            check((Boolean) call(skin, "addWater", flask, boiled, capacity), id + " could not be filled");
            check((Integer) call(skin, "servings", flask) == capacity, id + " is not full after filling");
            check(!(Boolean) call(skin, "addWater", flask, boiled, 1), id + " took more water than it holds");
            // drink it down serving by serving; the thirst bar rises and the flask stays in hand, one serving lighter
            call(manager, "set", p, callOn(call(cls(THIRST, "com.thirstwastaken2.data.ThirstData"), "full"), "withLevels", 2, 0));
            int bar = thirst(p);
            for (int serving = capacity; serving >= 1; serving--) {
                ItemStack back = flask.finishUsingItem(level, p);
                flask = back;
                check((Integer) call(skin, "servings", flask) == serving - 1, id + ": serving count after a drink should be " + (serving - 1) + " but is " + call(skin, "servings", flask));
            }
            check(thirst(p) > bar, id + ": drinking all of it did not quench the player: " + bar + " -> " + thirst(p));
            check(!flask.isEmpty() && flask.getItem() == item(id), id + " was used up instead of staying as an empty flask");
            // dirty plus clean is dirty (the worse quality wins), so topping up a clean flask from a puddle spoils it
            call(skin, "addWater", flask, boiled, 1);
            call(skin, "addWater", flask, dirty, 1);
            ItemStack reloaded = ItemStack.of(flask.save(new CompoundTag()));
            check((Integer) call(skin, "servings", reloaded) == 2, id + " lost its water in a save and a load");
            check(call(purity, "quality", reloaded).equals(call(purity, "quality", flask)), id + " lost its water quality in a save and a load");
        }
        helper.succeed();
    }

    /** Boiling: a dirty bottle in a furnace comes out safe (the mod's own recipe, run through the furnace's own tick). */
    @GameTest(template = "empty")
    public static void boilingDirtyWaterInAFurnaceMakesItSafeToDrink(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        Class<?> api = cls(THIRST, "com.thirstwastaken2.api.ThirstApi"), purity = cls(THIRST, "com.thirstwastaken2.purity.WaterPurity");
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
        BlockEntity entity = level.getBlockEntity(pos);
        check(entity instanceof AbstractFurnaceBlockEntity, "a furnace was placed");
        AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) entity;
        ItemStack dirtyBottle = (ItemStack) call(api, "waterBottle", staticInt(purity, "MIN"));
        furnace.setItem(0, dirtyBottle);
        furnace.setItem(1, new ItemStack(Items.COAL));
        BlockState state = level.getBlockState(pos);
        for (int tick = 0; tick < 400; tick++) AbstractFurnaceBlockEntity.serverTick(level, pos, state, furnace);
        ItemStack out = furnace.getItem(2);
        check(!out.isEmpty(), "the furnace produced nothing from a dirty water bottle in 400 ticks (input now " + furnace.getItem(0) + ")");
        check((Integer) call(api, "purity", out) >= staticInt(purity, "BOILED"), "boiled water came out with purity " + call(api, "purity", out));
        check(out.is(Items.POTION), "the boiled water is still a water bottle: " + out);
        helper.succeed();
    }

    /**
     * Acquiring water the way a player does: stand at a pond with a flask in hand and use it. The test posts the game's own right-click-item event, so it also
     * proves the mod's handler is wired to the game, and compares the water that came in with what the mod says that water is.
     */
    @GameTest(template = "empty")
    public static void aFlaskFilledFromAPondHoldsThatPondsWater(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, "uc_pond", "farmer");
        Class<?> skin = cls(THIRST, "com.thirstwastaken2.item.WaterskinItem"), purity = cls(THIRST, "com.thirstwastaken2.purity.WaterPurity");
        BlockPos water = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(water.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
        for (BlockPos around : List.of(water.north(), water.south(), water.east(), water.west())) level.setBlockAndUpdate(around, Blocks.STONE.defaultBlockState());
        p.moveTo(water.getX() + 0.5, water.getY() + 1.0, water.getZ() + 0.5, 0f, 90f);       // standing over the pond, looking straight down
        ItemStack flask = new ItemStack(item("thirstwastaken2:copper_canteen"));
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, flask);
        check((Integer) call(skin, "servings", flask) == 0, "the flask starts empty");
        var result = p.gameMode.useItem(p, level, flask, net.minecraft.world.InteractionHand.MAIN_HAND);
        ItemStack held = p.getMainHandItem();
        int servings = (Integer) call(skin, "servings", held);
        check(servings > 0, "using a flask at a pond filled nothing (result " + result + "): the mod's handler is not reached by the game's right-click or the pond is not recognised");
        check(call(purity, "quality", held).equals(call(purity, "sampleAt", level, water)), "the flask holds " + call(purity, "quality", held) + " but the pond is " + call(purity, "sampleAt", level, water));
        // a second use tops it up until it is full, and a full flask takes no more
        for (int i = 0; i < 12; i++) p.gameMode.useItem(p, level, p.getMainHandItem(), net.minecraft.world.InteractionHand.MAIN_HAND);
        int capacity = (Integer) call(skin, "capacity", p.getMainHandItem());
        check((Integer) call(skin, "servings", p.getMainHandItem()) == capacity, "the flask is not full after repeated use: " + call(skin, "servings", p.getMainHandItem()) + " of " + capacity);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ backpack

    private static final String BACKPACK = "travelersbackpack:standard";

    @GameTest(template = "empty")
    public static void aBackpackIsWornHoldsItemsAndKeepsThemThroughASaveAndLoad(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerPlayer p = player(helper, "uc_packer", "farmer");
        Class<?> util = cls(BACKPACK, "com.tiviacz.travelersbackpack.capability.CapabilityUtils");
        check(!(Boolean) call(util, "isWearingBackpack", p), "a new player wears no backpack");
        call(util, "equipBackpack", p, new ItemStack(item(BACKPACK)));
        check((Boolean) call(util, "isWearingBackpack", p), "the backpack was not worn after equipping it");
        ItemStackHandler storage = ((ItemStackHandler) callOn(call(util, "getBackpackWrapper", p), "getStorage"));
        check(storage.getSlots() >= 20, "a backpack should hold a real load: " + storage.getSlots() + " slots");
        ItemStack leftover = storage.insertItem(0, new ItemStack(Items.DIAMOND, 7), false);
        storage.insertItem(1, new ItemStack(Items.BREAD, 12), false);
        check(leftover.isEmpty() && storage.getStackInSlot(0).getCount() == 7 && storage.getStackInSlot(1).getCount() == 12, "items did not go into the backpack");
        // the player is saved the way the server saves them, and a new body is loaded from it (what a reconnect or a restart does)
        CompoundTag saved = p.serializeNBT();
        ServerPlayer again = player(helper, "uc_packer2", "farmer");
        again.deserializeNBT(saved);
        check((Boolean) call(util, "isWearingBackpack", again), "the backpack is no longer worn after a save and a load");
        ItemStackHandler restored = ((ItemStackHandler) callOn(call(util, "getBackpackWrapper", again), "getStorage"));
        check(restored.getStackInSlot(0).is(Items.DIAMOND) && restored.getStackInSlot(0).getCount() == 7 && restored.getStackInSlot(1).is(Items.BREAD) && restored.getStackInSlot(1).getCount() == 12,
            "the backpack's contents did not survive a save and a load: " + restored.getStackInSlot(0) + ", " + restored.getStackInSlot(1));
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ cooking

    @GameTest(template = "empty")
    public static void aCookingPotTurnsIngredientsIntoAMealThatFeeds(GameTestHelper helper) throws Exception {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        ServerPlayer p = player(helper, "uc_cook", "farmer");
        BlockPos pot = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(pot.below(), Blocks.MAGMA_BLOCK.defaultBlockState());       // a heat source under the pot
        Block block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse("farmersdelight:cooking_pot"));
        level.setBlockAndUpdate(pot, block.defaultBlockState());
        BlockEntity entity = level.getBlockEntity(pot);
        check(entity != null, "the cooking pot has no block entity");
        Class<?> type = entity.getClass();
        ItemStackHandler inventory = (ItemStackHandler) callOn(entity, "getInventory");
        check((Boolean) callOn(entity, "isHeated"), "the pot is not heated by the magma block under it");
        for (int slot = 0; slot < 3; slot++) inventory.setStackInSlot(slot, new ItemStack(Items.BEETROOT));
        inventory.setStackInSlot(staticInt(type, "CONTAINER_SLOT"), new ItemStack(Items.BOWL));
        BlockState state = level.getBlockState(pot);
        Method tick = null;
        for (Method m : type.getMethods()) if (m.getName().equals("cookingTick")) tick = m;
        check(tick != null, "cookingTick not found");
        for (int i = 0; i < 260; i++) tick.invoke(null, level, pot, state, entity);
        ItemStack meal = inventory.getStackInSlot(staticInt(type, "OUTPUT_SLOT"));
        check(meal.is(Items.BEETROOT_SOUP), "three beetroots and a bowl over a heat source did not make beetroot soup in 260 ticks: output " + meal + ", display " + inventory.getStackInSlot(staticInt(type, "MEAL_DISPLAY_SLOT")));
        check(inventory.getStackInSlot(0).isEmpty() && inventory.getStackInSlot(1).isEmpty() && inventory.getStackInSlot(2).isEmpty(), "the ingredients were not used up");
        // eating it feeds the player
        p.getFoodData().setFoodLevel(4);
        meal.finishUsingItem(level, p);
        check(p.getFoodData().getFoodLevel() > 4, "eating the soup did not feed the player: " + p.getFoodData().getFoodLevel());
        helper.succeed();
    }
}
