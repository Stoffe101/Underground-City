package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The Drying Rack (D-160): flower hung in even numbers, the time left while it dries, half as much Dried Cannabis taken down once it is dry, the rack showing it, and a broken rack giving back
 * what was on it (flower before it dried, dried after). The active clock is not advanced (other tests run on it): the rack's own start is set back instead.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DryingRackGameTests {
    private DryingRackGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    @GameTest(template = "empty")
    public static void flowerHungOnARackDriesIntoHalfAsMuchInSixMinutesOfPlay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // in the test's own cell: a chunk far away is loaded for its blocks but not its entities, so a dropped crop there could not be seen
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        FakePlayer farmer = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_rack_farmer"));
        farmer.getInventory().clearContent();
        try {
            check(DryingRack.driedFrom(5) == 2 && DryingRack.driedFrom(16) == 8 && DryingRack.driedFrom(1) == 0, "two flower do not make one");
            level.setBlock(at.below(), Blocks.STONE.defaultBlockState(), 2);
            level.setBlock(at, UndergroundCity.DRYING_RACK.get().defaultBlockState(), 2);
            DryingRack.Entity rack = (DryingRack.Entity) level.getBlockEntity(at);
            check(rack != null, "the rack has no block entity");

            farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UndergroundCity.item("cannabis_flower"), 5));
            String said = rack.use(level, farmer, level.getBlockState(at));
            check(rack.count == 4 && farmer.getMainHandItem().getCount() == 1, "five flower in hand did not hang four (" + rack.count + ", " + said + ")");
            check(level.getBlockState(at).getValue(DryingRack.LOAD) == DryingRack.Load.DRYING, "the rack does not show the crop drying");
            said = rack.use(level, farmer, level.getBlockState(at));
            check(said.contains("drying") && said.contains("6 minutes"), "the rack did not say how long is left: " + said);
            check(farmer.getInventory().countItem(UndergroundCity.item("dried_cannabis")) == 0, "something came down before it was dry");

            rack.since = LockService.clock(level.getServer()) - DryingRack.DRY_TICKS;
            rack.check(level, level.getBlockState(at));
            check(level.getBlockState(at).getValue(DryingRack.LOAD) == DryingRack.Load.DRY, "the rack does not show the crop dry");
            said = rack.use(level, farmer, level.getBlockState(at));
            check(farmer.getInventory().countItem(UndergroundCity.item("dried_cannabis")) == 2 && rack.count == 0, "four dry flower did not come down as two Dried Cannabis: " + said);
            check(level.getBlockState(at).getValue(DryingRack.LOAD) == DryingRack.Load.EMPTY, "an emptied rack does not look empty");

            // a broken rack gives back what was on it
            farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UndergroundCity.item("cannabis_flower"), 6));
            rack.use(level, farmer, level.getBlockState(at));
            level.removeBlock(at, false);
            int flower = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2))) { if (e.getItem().is(UndergroundCity.item("cannabis_flower"))) flower += e.getItem().getCount(); e.discard(); }
            check(flower == 6, "a rack broken while drying gave back " + flower + " flower, not 6");
            level.setBlock(at, UndergroundCity.DRYING_RACK.get().defaultBlockState(), 2);
            DryingRack.Entity again = (DryingRack.Entity) level.getBlockEntity(at);
            farmer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(UndergroundCity.item("cannabis_flower"), 6));
            again.use(level, farmer, level.getBlockState(at));
            again.since = LockService.clock(level.getServer()) - DryingRack.DRY_TICKS;
            level.removeBlock(at, false);
            int dried = 0;
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2))) { if (e.getItem().is(UndergroundCity.item("dried_cannabis"))) dried += e.getItem().getCount(); e.discard(); }
            check(dried == 3, "a rack broken when dry gave back " + dried + " Dried Cannabis, not 3");
        } finally {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(at.below(), Blocks.AIR.defaultBlockState(), 2);
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3))) e.discard();
        }
        helper.succeed();
    }
}
