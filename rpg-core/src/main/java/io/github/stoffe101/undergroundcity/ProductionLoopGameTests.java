package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * One whole production-to-profit loop, run through the game's own code paths (D-098): goods bought at the Valewood exchange, crafted with the vanilla crafting
 * recipes into Cultivation Nutrient and Sable Leaf cuttings, planted by right-click (which asks for the Restricted Cultivation skill), fed by right-click, grown by the
 * block's own random tick, harvested by a player (which asks the grafting skill for extra bales), carried to the licensed buyer in Blackwater and delivered, with the
 * credits and the career XP checked at the end. It is the loop that {@code scripts/economy_model.py} prices; the audit line it prints is what the model's
 * numbers are compared with. Time is not simulated (random ticks are forced), so it proves that the chain is closed and pays, not how long it takes a person.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionLoopGameTests {
    private ProductionLoopGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static Item core(String path) { return UndergroundCity.item(path); }

    private static BlockHitResult hit(BlockPos pos, Direction face) { return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), face, pos, false); }

    private static void city(GameTestHelper helper, BlockPos pos, String city) {
        CityRegions.INSTANCE.replaceForTest(List.of(new CityRegions.Region(city, helper.getLevel().dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4, 64)));
    }

    private static FakePlayer farmer(GameTestHelper helper, String name, boolean grafting) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState state = CitizenState.get(player.server);
        state.register(player.getUUID(), "valewood", "farmer");
        state.addCareerXp(player.getUUID(), ProgressionRules.xpAtLevel(10));
        for (String skill : List.of("farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.controlled_crop_licence")) state.unlockSkill(player.getUUID(), skill);
        if (grafting) state.unlockSkill(player.getUUID(), "farmer.selective_grafting");
        return player;
    }

    /** Crafts one batch with the vanilla recipe for {@code expected} out of the first matching items in the inventory; the inputs are taken, the product is added. */
    private static void craft(GameTestHelper helper, FakePlayer player, Item expected, ItemStack... inputs) {
        TransientCraftingContainer grid = new TransientCraftingContainer(player.inventoryMenu, 3, 3);
        for (int i = 0; i < inputs.length; i++) {
            check(player.getInventory().countItem(inputs[i].getItem()) >= 1, "setup: " + ForgeRegistries.ITEMS.getKey(inputs[i].getItem()) + " in the inventory");
            grid.setItem(i, inputs[i].copy());
        }
        var recipe = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, helper.getLevel()).orElseThrow(() -> new AssertionError("no recipe for " + ForgeRegistries.ITEMS.getKey(expected)));
        ItemStack result = recipe.assemble(grid, helper.getLevel().registryAccess());
        check(result.is(expected), "the recipe made " + result + ", expected " + ForgeRegistries.ITEMS.getKey(expected));
        for (ItemStack input : inputs) {
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                if (player.getInventory().items.get(slot).is(input.getItem())) { player.getInventory().items.get(slot).shrink(1); break; }
            }
        }
        player.getInventory().add(result);
    }

    private static void hold(FakePlayer player, Item item) {
        player.getInventory().selected = 0;
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            if (player.getInventory().items.get(slot).is(item)) {
                ItemStack stack = player.getInventory().items.get(slot);
                player.getInventory().items.set(slot, ItemStack.EMPTY);
                ItemStack previous = player.getInventory().items.get(0);
                player.getInventory().items.set(0, stack);
                if (!previous.isEmpty()) player.getInventory().add(previous);
                return;
            }
        }
        throw new AssertionError("setup: " + ForgeRegistries.ITEMS.getKey(item) + " in the inventory");
    }

    private record Outcome(long spent, long paid, int bales, int randomTicks, int cuttingsLeft, int xpGained) {}

    /** Everything stage one builds and stage two uses. The light engine needs a few ticks to light the plots, so the loop runs in two stages. */
    private static final class Loop {
        ServerLevel level;
        Path journal;
        List<CityRegions.Region> originalRegions;
        WorkOrderLedger ledger;
        BlockPos terminal;
        FakePlayer player;
        CitizenState.Citizen citizen;
        BlockPos[] soil = new BlockPos[3], plots = new BlockPos[3];
        long spent;
        int xpBefore;

        void close() {
            CityRegions.INSTANCE.replaceForTest(originalRegions);
            try { Files.deleteIfExists(journal); } catch (java.io.IOException ignored) { /* a scratch file */ }
        }
    }

    /** Stage one: trade, make, and lay out three plots of moist farmland with glowstone beside them. */
    private static Loop begin(GameTestHelper helper, String name, boolean grafting) {
        Loop loop = new Loop();
        loop.level = helper.getLevel();
        loop.journal = loop.level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/loop-" + UUID.randomUUID() + ".dat");
        loop.originalRegions = CityRegions.INSTANCE.all();
        try {
            loop.ledger = new WorkOrderLedger(loop.journal);
            loop.terminal = helper.absolutePos(new BlockPos(0, 2, 0));
            FakePlayer player = loop.player = farmer(helper, name, grafting);
            CitizenState.Citizen citizen = loop.citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow();
            QShopEconomy.applyBalance(player, 600);
            loop.xpBefore = citizen.careerXp();

            // 1. trade: five catalysts and two seed stocks at the Valewood exchange (a batch of nutrient is one catalyst; a batch of cuttings is one catalyst and one seed stock)
            city(helper, loop.terminal, "valewood");
            RegionalMarket.purchase(player, loop.terminal, loop.ledger, citizen, UndergroundCity.MOD_ID + ":agro_catalyst", 5, UUID.randomUUID());
            RegionalMarket.purchase(player, loop.terminal, loop.ledger, citizen, UndergroundCity.MOD_ID + ":rare_seeds", 2, UUID.randomUUID());
            loop.spent = 600 - (long) QShopEconomy.balance(player);
            check(loop.spent > 0, "the goods cost credits");
            player.getInventory().add(new ItemStack(Items.BONE_MEAL, 3));
            player.getInventory().add(new ItemStack(Items.GLASS_BOTTLE, 3));
            player.getInventory().add(new ItemStack(Items.WHEAT_SEEDS, 2));

            // 2. make: three batches of nutrient (nine doses) and two of cuttings (four cuttings)
            for (int i = 0; i < 3; i++) craft(helper, player, core("cultivation_nutrient"), new ItemStack(core("agro_catalyst")), new ItemStack(Items.BONE_MEAL), new ItemStack(Items.GLASS_BOTTLE));
            for (int i = 0; i < 2; i++) craft(helper, player, core("sable_cutting"), new ItemStack(core("rare_seeds")), new ItemStack(core("agro_catalyst")), new ItemStack(Items.WHEAT_SEEDS));
            check(player.getInventory().countItem(core("cultivation_nutrient")) == 9 && player.getInventory().countItem(core("sable_cutting")) == 4, "nine doses and four cuttings");

            // 3. lay out the plots: moist farmland, glowstone beside each (the plant needs light 12; the light engine lights them over the next ticks)
            for (int i = 0; i < 3; i++) {
                BlockPos soil = loop.soil[i] = helper.absolutePos(new BlockPos(2 + i * 3, 1, 2));
                loop.plots[i] = soil.above();
                loop.level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmBlock.MOISTURE, 7));
                loop.level.setBlockAndUpdate(soil.east(), Blocks.GLOWSTONE.defaultBlockState());
                loop.level.setBlockAndUpdate(soil.west(), Blocks.GLOWSTONE.defaultBlockState());
                loop.level.setBlockAndUpdate(loop.plots[i], Blocks.AIR.defaultBlockState());
            }
            return loop;
        } catch (RuntimeException | AssertionError error) {
            loop.close();
            throw error;
        }
    }

    /** Stage two: plant, feed, grow, harvest, deliver. */
    private static Outcome finish(GameTestHelper helper, Loop loop) {
        ServerLevel level = loop.level;
        FakePlayer player = loop.player;
        Block crop = ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "sable_leaf_crop"));
        int randomTicks = 0;
        for (int i = 0; i < 3; i++) {
            hold(player, core("sable_cutting"));
            var planted = player.gameMode.useItemOn(player, level, player.getInventory().getItem(0), InteractionHand.MAIN_HAND, hit(loop.soil[i], Direction.UP));
            check(level.getBlockState(loop.plots[i]).is(crop), "the cutting was planted by right-click (the Restricted Cultivation skill allows it): result " + planted + ", block " + level.getBlockState(loop.plots[i])
                + ", soil " + level.getBlockState(loop.soil[i]) + ", light " + level.getRawBrightness(loop.plots[i], 0) + ", skill " + SkillService.has(player, RestrictedCuttingItem.SKILL));
        }
        for (BlockPos plot : loop.plots) {
            int guard = 0;
            while (level.getBlockState(plot).getValue(RestrictedCropBlock.AGE) < 3) {
                BlockState state = level.getBlockState(plot);
                check(++guard < 6000, "the plant at " + plot + " never grew (light " + level.getRawBrightness(plot, 0) + ")");
                if (!state.getValue(RestrictedCropBlock.NOURISHED)) {
                    hold(player, core("cultivation_nutrient"));
                    player.gameMode.useItemOn(player, level, player.getInventory().getItem(0), InteractionHand.MAIN_HAND, hit(plot, Direction.UP));
                    check(level.getBlockState(plot).getValue(RestrictedCropBlock.NOURISHED), "one dose of nutrient fed the plant");
                } else {
                    state.randomTick(level, plot, level.random);
                    randomTicks++;
                }
            }
        }
        check(player.getInventory().countItem(core("cultivation_nutrient")) == 0, "three plants took exactly the nine doses");

        // 4. harvest: a player breaking each mature plant; the bales are picked up
        Item bale = core("sable_leaf_bale"), cutting = core("sable_cutting");
        for (BlockPos plot : loop.plots) {
            player.gameMode.destroyBlock(plot);
            for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, new AABB(plot).inflate(1.5))) {
                player.getInventory().add(drop.getItem().copy());
                drop.discard();
            }
        }
        int bales = player.getInventory().countItem(bale);
        check(bales >= 6, "three mature plants gave at least the six bales of the licensed order: " + bales);

        // 5. sell: the licensed supply contract, delivered at the dispensary in Blackwater (the order needs the Controlled-Crop Licence)
        city(helper, loop.terminal, "blackwater");
        WorkOrder order = WorkOrderCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":licensed_cultivar_supply");
        long before = (long) QShopEconomy.balance(player);
        WorkOrderService.accept(player, loop.ledger, loop.citizen, order.id());
        WorkOrderService.complete(player, loop.ledger, loop.ledger.player(player.getUUID()).getUUID("order"), loop.terminal);
        long paid = (long) QShopEconomy.balance(player) - before;
        check(player.getInventory().countItem(bale) == bales - 6, "the delivery took exactly six bales");
        return new Outcome(loop.spent, paid, bales, randomTicks, player.getInventory().countItem(cutting), loop.citizen.careerXp() - loop.xpBefore);
    }

    private static void judge(Outcome o, boolean grafting) {
        WorkOrder order = WorkOrderCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":licensed_cultivar_supply");
        // a cutting is worth what its batch cost: half a catalyst and half a seed stock
        RegionalStock catalyst = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":agro_catalyst"), seeds = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":rare_seeds");
        double cuttingValue = (catalyst.unitPrice() + seeds.unitPrice()) / 2.0;
        double spentNet = o.spent - o.cuttingsLeft * cuttingValue;   // the cuttings left over go into the next cycle
        double perBale = spentNet / o.bales;
        System.out.println("[UC-LOOP-AUDIT] grafting=" + grafting + ": bought goods for " + o.spent + " credits, harvested " + o.bales + " bales from 3 plants (" + o.randomTicks + " random ticks), "
            + o.cuttingsLeft + " cuttings left (worth " + Math.round(o.cuttingsLeft * cuttingValue) + "), delivered 6 for " + o.paid + " and " + o.xpGained + " XP: cost per bale " + String.format("%.1f", perBale)
            + ", profit on the delivery " + Math.round(o.paid - 6 * perBale) + " (the surplus " + (o.bales - 6) + " bales are unsold)");
        check(o.paid == order.credits(), "the delivery paid the order's credits (this farmer holds no contract-rate skill): " + o.paid + " of " + order.credits());
        check(o.xpGained >= order.xp(), "the delivery paid its career XP: " + o.xpGained);
        check(perBale > 8 && perBale < 30, "a bale costs between 8 and 30 credits to grow (the model prices it at 18): " + perBale);
        check(o.paid - 6 * perBale > 0, "growing six bales and selling them licensed is a profit: " + (o.paid - 6 * perBale));
        // the licence cannot be dodged by buying the goods and delivering them unchanged: nothing the exchange sells is a bale
        check(RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":sable_leaf_bale") == null, "the exchange does not sell bales");
    }

    private static void runLoop(GameTestHelper helper, boolean grafting) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        Loop loop = begin(helper, grafting ? "uc_loop_g" : "uc_loop", grafting);
        helper.runAfterDelay(40, () -> {
            try {
                judge(finish(helper, loop), grafting);
                helper.succeed();
            } catch (AssertionError | RuntimeException error) {
                helper.fail(String.valueOf(error.getMessage()));
            } finally {
                loop.close();
            }
        });
    }

    // each loop is its own batch: it swaps the city circles for several ticks, and nothing else may run beside it
    @GameTest(template = "empty", batch = "uc_loop_plain", timeoutTicks = 400)
    public static void theSableLeafLoopRunsFromExchangeGoodsToAPaidDeliveryAtAProfit(GameTestHelper helper) { runLoop(helper, false); }

    @GameTest(template = "empty", batch = "uc_loop_grafting", timeoutTicks = 400)
    public static void theSameLoopWithTheGraftingSkillGivesAtLeastTheSameAndUsuallyMore(GameTestHelper helper) { runLoop(helper, true); }

    @GameTest(template = "empty")
    public static void aFarmerWithoutRestrictedCultivationCannotRaiseTheCrop(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_loop_no"));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", "farmer");
        BlockPos soil = helper.absolutePos(new BlockPos(3, 1, 4));
        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(soil.above(), Blocks.AIR.defaultBlockState());
        player.getInventory().setItem(0, new ItemStack(core("sable_cutting"), 2));
        player.gameMode.useItemOn(player, level, player.getInventory().getItem(0), InteractionHand.MAIN_HAND, hit(soil, Direction.UP));
        check(level.getBlockState(soil.above()).isAir() && player.getInventory().countItem(core("sable_cutting")) == 2, "an unskilled farmer planted the controlled crop");
        helper.succeed();
    }
}
