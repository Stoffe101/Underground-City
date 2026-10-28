package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** The first aid kit (D-203): three hearts back, used up, then thirty seconds before the next; refused at full health; and the exchanges sell it. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FirstAidGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    @GameTest(template = "empty")
    public static void aKitGivesThreeHeartsOnceInThirtySecondsAndNeverAtFullHealth(GameTestHelper helper) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_first_aid"));
        player.setGameMode(GameType.SURVIVAL);
        FirstAidKitItem kit = (FirstAidKitItem) UndergroundCity.FIRST_AID_KIT.get();
        ItemStack stack = new ItemStack(kit, 2);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        player.setHealth(player.getMaxHealth());
        check(kit.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult() == InteractionResult.FAIL, "a kit was used at full health");
        player.setHealth(8);
        check(kit.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).getResult().consumesAction(), "a hurt player could not start using a kit");
        kit.finishUsingItem(stack, helper.getLevel(), player);
        check(Math.abs(player.getHealth() - (8 + FirstAidKitItem.HEAL)) < 1e-4, "the kit healed to " + player.getHealth());
        check(stack.getCount() == 1, "the kit was not used up: " + stack.getCount());
        check(player.getCooldowns().isOnCooldown(kit), "no cooldown after a kit");
        // every city's exchange sells it, and its wayside desks dearer
        for (String city : new String[] {"valewood", "ironhaven", "blackwater"}) {
            RegionalStock town = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":survival/first_aid_kit_" + city);
            RegionalStock road = RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":survival/first_aid_kit_wayside_" + city);
            check(town != null && town.soldAt(RegionalStock.CITY) && road != null && road.soldAt(RegionalStock.WAYSIDE) && road.unitPrice() > town.unitPrice(), city + " does not sell the kit as planned");
        }
        helper.succeed();
    }
    /** The supply chain's harvest (duo run 4 picked up nothing): the server's own right click on a grown bush, and a grown beetroot broken by hand in survival, drop what the crop gives. */
    @GameTest(template = "empty")
    public static void aRightClickedBushAndABrokenBeetrootDropTheirCrop(GameTestHelper helper) {
        ServerPlayer farmer = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_harvest"));
        farmer.setGameMode(GameType.SURVIVAL);
        farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.WOODEN_HOE));
        net.minecraft.core.BlockPos ground = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)), bush = ground.above(), soil = ground.east(), beet = soil.above();
        helper.getLevel().setBlock(ground, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        helper.getLevel().setBlock(bush, net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3), 3);
        helper.getLevel().setBlock(soil, net.minecraft.world.level.block.Blocks.FARMLAND.defaultBlockState(), 3);
        helper.getLevel().setBlock(beet, net.minecraft.world.level.block.Blocks.BEETROOTS.defaultBlockState().setValue(net.minecraft.world.level.block.BeetrootBlock.AGE, net.minecraft.world.level.block.BeetrootBlock.MAX_AGE), 3);
        InteractionResult used = farmer.gameMode.useItemOn(farmer, helper.getLevel(), farmer.getMainHandItem(), InteractionHand.MAIN_HAND,
            new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(bush), net.minecraft.core.Direction.SOUTH, bush, false));
        int age = helper.getLevel().getBlockState(bush).getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE);
        boolean broke = farmer.gameMode.destroyBlock(beet);
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(ground).inflate(3);
        java.util.List<net.minecraft.world.entity.item.ItemEntity> drops = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box);
        String what = drops.stream().map(d -> d.getItem().getCount() + " " + d.getItem().getItem()).collect(java.util.stream.Collectors.joining(", "));
        check(used.consumesAction() && age == 1, "the right click did not pick the bush: " + used + ", age " + age);
        check(broke, "the beetroot was not broken");
        check(drops.stream().anyMatch(d -> d.getItem().is(net.minecraft.world.item.Items.SWEET_BERRIES)) && drops.stream().anyMatch(d -> d.getItem().is(net.minecraft.world.item.Items.BEETROOT)), "the drops are: " + what);
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        helper.succeed();
    }
}
