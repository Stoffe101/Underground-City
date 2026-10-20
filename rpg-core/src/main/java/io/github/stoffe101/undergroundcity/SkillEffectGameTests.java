package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The skill trees' effects, each shown to be consumed by the system that owns it (D-098): the validator's rules, the contract rate on a real delivery, the
 * Sable Leaf yield on a real harvest, and the trade discount on a home's upgrades (the last one lives in {@code PropertyGameTests}, which owns the fixture).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SkillEffectGameTests {
    private SkillEffectGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static void invalid(String why, List<SkillNode> tree) {
        try { SkillRules.validateTree("farmer", tree); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("The validator accepted " + why);
    }

    private static SkillNode node(String name, String branch, int cost, int level, List<String> requires, int legal, int underground, int yield, int upgrade) {
        return new SkillNode("farmer." + name, "farmer", branch, "Title " + name, "A description of " + name, cost, level, requires.stream().map(r -> "farmer." + r).toList(),
            0, List.of(), 0, "", legal, underground, yield, upgrade);
    }

    private static SkillNode node(String name, String branch, int cost, int level, List<String> requires) { return node(name, branch, cost, level, requires, 0, 0, 0, 0); }

    /** The smallest tree the validator accepts: a root, a legal and an underground node. */
    private static List<SkillNode> base() {
        return List.of(node("root", "core", 1, 2, List.of()), node("law", "legal", 2, 3, List.of("root")), node("crime", "underground", 2, 4, List.of("root")));
    }

    private static List<SkillNode> with(SkillNode... extra) {
        java.util.List<SkillNode> all = new java.util.ArrayList<>(base());
        all.addAll(List.of(extra));
        return all;
    }

    @GameTest(template = "empty")
    public static void theValidatorEnforcesTheShapeAndTheCeilings(GameTestHelper helper) {
        SkillRules.validateTree("farmer", base());
        SkillRules.validateTree("farmer", with(node("mix", "crossover", 2, 5, List.of("law", "crime"))));
        invalid("a legal skill that needs a crime", with(node("bad", "legal", 2, 5, List.of("crime"))));
        invalid("a foundation skill that needs a crime", with(node("bad", "core", 2, 5, List.of("crime"))));
        invalid("a crossover with one prerequisite", with(node("bad", "crossover", 2, 5, List.of("law"))));
        invalid("a crossover with two legal prerequisites", with(node("a", "legal", 1, 4, List.of("root")), node("bad", "crossover", 2, 5, List.of("law", "a"))));
        invalid("a crossover with two underground prerequisites", with(node("b", "underground", 1, 5, List.of("root")), node("bad", "crossover", 2, 6, List.of("crime", "b"))));
        invalid("a node paying 7% more on legal contracts", with(node("rich", "legal", 2, 5, List.of("law"), 7, 0, 0, 0)));
        invalid("a tree paying 14% more on legal contracts", with(node("a", "legal", 1, 5, List.of("law"), 6, 0, 0, 0), node("b", "legal", 1, 6, List.of("a"), 6, 0, 0, 0), node("c", "legal", 1, 7, List.of("b"), 2, 0, 0, 0)));
        invalid("a tree paying 14% more on underground contracts", with(node("a", "underground", 1, 5, List.of("crime"), 0, 6, 0, 0), node("b", "underground", 1, 6, List.of("a"), 0, 6, 0, 0), node("c", "underground", 1, 7, List.of("b"), 0, 2, 0, 0)));
        invalid("a node with a 31% yield", with(node("rich", "utility", 2, 5, List.of("root"), 0, 0, 31, 0)));
        invalid("a tree with 45% yield", with(node("a", "utility", 1, 5, List.of("root"), 0, 0, 30, 0), node("b", "utility", 1, 6, List.of("a"), 0, 0, 15, 0)));
        invalid("a node giving 16% off home upgrades", with(node("rich", "utility", 2, 5, List.of("root"), 0, 0, 0, 16)));
        invalid("a tree giving 30% off home upgrades", with(node("a", "utility", 1, 5, List.of("root"), 0, 0, 0, 15), node("b", "utility", 1, 6, List.of("a"), 0, 0, 0, 15)));
        invalid("a tree that costs more than the whole career earns", with(node("big", "legal", 4, 5, List.of("law")), node("big2", "legal", 4, 6, List.of("big")), node("big3", "legal", 4, 7, List.of("big2")),
            node("big4", "legal", 4, 8, List.of("big3")), node("big5", "legal", 4, 9, List.of("big4")), node("big6", "legal", 4, 10, List.of("big5")), node("big7", "legal", 4, 11, List.of("big6"))));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyShippedTreeHasTheDesignedShape(GameTestHelper helper) {
        for (String career : ProgressionRules.CAREERS) {
            List<SkillNode> tree = SkillCatalog.INSTANCE.tree(career);
            check(tree.size() >= 12 && tree.size() <= SkillNode.MAX_NODES_PER_TREE, career + " has " + tree.size() + " skills");
            check(tree.stream().anyMatch(n -> n.branch().equals("crossover")), career + " has no crossover skill");
            check(tree.stream().filter(n -> n.requires().size() >= 2).count() >= 3, career + " has fewer than three multi-prerequisite skills");
            Set<String> all = tree.stream().map(SkillNode::id).collect(java.util.stream.Collectors.toSet());
            check(SkillRules.legalPayoutPercent(tree, all) == 8 && SkillRules.undergroundPayoutPercent(tree, all) == 9, career + " pays " + SkillRules.legalPayoutPercent(tree, all) + "/" + SkillRules.undergroundPayoutPercent(tree, all));
            check(tree.stream().anyMatch(n -> n.branch().equals("utility")), career + " has no utility skill");
            // a character who never commits a crime still reaches every legal, foundation and utility skill: nothing of them needs an underground one
            for (SkillNode n : tree) {
                if (n.branch().equals("underground") || n.branch().equals("crossover")) continue;
                check(!SkillRules.hasAncestorIn(n, "underground", SkillCatalog.INSTANCE.index()), n.id() + " needs a crime");
            }
        }
        check(SkillCatalog.INSTANCE.tree("farmer").stream().anyMatch(n -> n.yieldPercent() > 0), "the Farmer has the grafting skill");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void payoutIsAWholeCreditRateThatNeverRoundsUp(GameTestHelper helper) {
        List<SkillNode> tree = SkillCatalog.INSTANCE.tree("farmer");
        Set<String> none = Set.of(), one = Set.of("farmer.estate_wholesale"), both = Set.of("farmer.estate_wholesale", "farmer.farm_cooperative");
        check(SkillRules.payout(30, false, tree, none) == 30 && SkillRules.payout(30, true, tree, both) == 30, "no skill, no bonus, and legal skills do not pay underground");
        check(SkillRules.payout(30, false, tree, one) == 31, "30 credits at 4% is 31 (1.2 rounds down): " + SkillRules.payout(30, false, tree, one));
        check(SkillRules.payout(30, false, tree, both) == 32, "30 credits at 8% is 32 (2.4 rounds down)");
        check(SkillRules.payout(24, false, tree, one) == 24, "24 credits at 4% is 24 (0.96 rounds down to nothing)");
        check(SkillRules.payout(1000, false, tree, both) == 1080, "a large contract pays the full rate");
        check(SkillRules.payout(220, true, tree, Set.of("farmer.back_lot_network", "farmer.dual_books")) == 220 + 19, "underground 9% of 220 is 19 (19.8 rounds down)");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDeliveryPaysTheContractRateOnceAndTheJournalHoldsIt(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_rate"));
        player.getInventory().clearContent();
        CitizenState state = CitizenState.get(player.server);
        state.register(player.getUUID(), "valewood", "farmer");
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElseThrow();
        citizen.careerXp.put("farmer", ProgressionRules.xpAtLevel(12));
        state.unlockSkill(player.getUUID(), "farmer.estate_wholesale");
        state.unlockSkill(player.getUUID(), "farmer.farm_cooperative");
        QShopEconomy.applyBalance(player, 0);
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        WorkOrder harvest = WorkOrderCatalog.INSTANCE.find("underground_city:harvest");
        int expected = harvest.credits() + harvest.credits() * 8 / 100;
        check(expected > harvest.credits(), "the test needs an order whose 8% is at least a credit");
        check(TerminalData.snapshot(player, helper.absolutePos(new BlockPos(1, 1, 1))).jobs().stream().noneMatch(row -> row.id().equals(harvest.id())), "setup: the apprentice order is withdrawn at level 12");
        UUID nonce = ledger.accept(player.getUUID(), harvest);   // an order accepted earlier is still deliverable above its ceiling
        player.getInventory().setItem(0, new ItemStack(Items.WHEAT, 16));
        WorkOrderService.complete(player, ledger, nonce);
        check(QShopEconomy.balance(player) == expected, "the delivery paid " + QShopEconomy.balance(player) + ", expected " + expected + " (" + harvest.credits() + " plus 8%)");
        // recovery after a settled delivery is a no-op: the prepared record carried an absolute wallet target
        WorkOrderService.recover(player, ledger);
        check(QShopEconomy.balance(player) == expected, "a second recovery changed the wallet");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ the Sable Leaf yield

    @GameTest(template = "empty")
    public static void extraBalesFollowTheRateExactly(GameTestHelper helper) {
        check(RestrictedCropBlock.extraBales(0, 0.0) == 0, "no skill, no extra bale");
        check(RestrictedCropBlock.extraBales(30, 0.0) == 1 && RestrictedCropBlock.extraBales(30, 0.74) == 1, "30% of 2.5 is 0.75: a roll under 0.75 gives one more");
        check(RestrictedCropBlock.extraBales(30, 0.75) == 0 && RestrictedCropBlock.extraBales(30, 0.99) == 0, "a roll of 0.75 or more gives none");
        check(RestrictedCropBlock.extraBales(40, 0.99) == 1, "40% of 2.5 is exactly one bale, whatever the roll");
        check(RestrictedCropBlock.extraBales(-5, 0.0) == 0, "a negative rate gives nothing");
        helper.succeed();
    }

    private static int harvest(GameTestHelper helper, FakePlayer player, int count) {
        ServerLevel level = helper.getLevel();
        Block crop = ForgeRegistries.BLOCKS.getValue(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "sable_leaf_crop"));
        Item bale = UndergroundCity.item("sable_leaf_bale");
        int best = 0, total = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = helper.absolutePos(new BlockPos(i % 8, 2, i / 8));
            level.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
            BlockState mature = crop.defaultBlockState().setValue(RestrictedCropBlock.AGE, 3);
            level.setBlock(pos, mature, 2);
            check(level.getBlockState(pos).is(crop), "setup: the mature plant stands");
            player.gameMode.destroyBlock(pos);
            int got = 0;
            for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5))) {
                if (entity.getItem().is(bale)) got += entity.getItem().getCount();
                entity.discard();
            }
            best = Math.max(best, got);
            total += got;
            level.setBlockAndUpdate(pos.below(), Blocks.AIR.defaultBlockState());
        }
        return best * 1000 + total;   // packed: the best single harvest and the total
    }

    @GameTest(template = "empty")
    public static void graftingAddsBalesToARealHarvestAndNothingElseDoes(GameTestHelper helper) {
        FakePlayer plain = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_plain"));
        FakePlayer grafter = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_graft"));
        CitizenState state = CitizenState.get(plain.server);
        state.register(plain.getUUID(), "valewood", "farmer");
        state.register(grafter.getUUID(), "valewood", "farmer");
        state.unlockSkill(grafter.getUUID(), "farmer.selective_grafting");
        check(SkillService.yieldPercent(grafter) == 30 && SkillService.yieldPercent(plain) == 0, "the skill gives the grafter 30% and the other farmer nothing");
        int without = harvest(helper, plain, 40), with = harvest(helper, grafter, 40);
        check(without / 1000 <= 3 && without % 1000 >= 40 * 2, "without the skill every harvest is the loot table's two or three bales: best " + without / 1000 + ", total " + without % 1000);
        check(with / 1000 == 4, "with the skill some harvest of forty reached four bales: best " + with / 1000);
        check(with % 1000 > without % 1000 + 10, "the grafter's forty harvests gave clearly more bales (" + with % 1000 + " against " + without % 1000 + ")");
        check(with % 1000 < 40 * 4, "the bonus never doubled the harvest");
        helper.succeed();
    }
}
