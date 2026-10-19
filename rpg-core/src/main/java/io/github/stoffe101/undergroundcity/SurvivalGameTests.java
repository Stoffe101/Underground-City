package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The opt-in survival set (D-085, backpack replaced by Sophisticated Backpacks and Sophisticated Core in D-092: Thirst Was Taken 2, Farmer's Delight, Comforts). When the set is not loaded each test passes
 * at once, as the Vinery and TaCZ tests do; the hosted job {@code dev-pack-smoke} runs them with {@code -PucPack=survival}.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurvivalGameTests {
    private SurvivalGameTests() {}

    private static boolean loaded() {
        return SurvivalSet.loaded();
    }

    @GameTest(template = "empty")
    public static void theSurvivalSetRegistersItsItemsAndCoreStillLoadsItsOwnData(GameTestHelper helper) {
        if (!loaded()) { helper.succeed(); return; }
        List<String> missing = new ArrayList<>();
        for (String id : List.of("sophisticatedbackpacks:backpack", "sophisticatedbackpacks:iron_backpack", "sophisticatedbackpacks:upgrade_base", "sophisticatedbackpacks:pickup_upgrade", "thirstwastaken2:waterskin", "thirstwastaken2:copper_canteen", "thirstwastaken2:iron_flask", "thirstwastaken2:copper_pipe",
            "farmersdelight:cooking_pot", "farmersdelight:cutting_board", "farmersdelight:stove", "comforts:sleeping_bag_red", "comforts:hammock_red", "comforts:rope_and_nail")) {
            if (!ForgeRegistries.ITEMS.containsKey(ResourceLocation.parse(id)) || ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(id)) == Items.AIR) missing.add("item " + id);
        }
        for (String id : List.of("thirstwastaken2:copper_distiller", "farmersdelight:cooking_pot", "comforts:sleeping_bag_red", "sophisticatedbackpacks:backpack", "sophisticatedbackpacks:iron_backpack")) {
            if (!ForgeRegistries.BLOCKS.containsKey(ResourceLocation.parse(id))) missing.add("block " + id);
        }
        check(missing.isEmpty(), "Things the survival set should register are missing: " + missing);
        // the set adds goods, never a second authority over what Core owns: no second currency, no second wallet, no registration screen of its own
        check(!ModList.get().isLoaded("lightmanscurrency") && !ModList.get().isLoaded("create"), "Nothing in the survival set may bring in a second economy or Create");
        // one backpack authority (D-092): the retired provider is never loaded beside the new one
        check(!ModList.get().isLoaded(SurvivalSet.LEGACY_BACKPACK_MOD), "Traveler's Backpack must not be loaded next to Sophisticated Backpacks");
        check(WorkOrderCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":greenhouse_wholesale") != null && RegionalCatalog.INSTANCE.find(UndergroundCity.MOD_ID + ":machined_parts") != null,
            "Core's own orders and regional stock still load next to the set");
        helper.succeed();
    }

    /**
     * D-079: resting is never an automatic change of respawn. A vanilla bed at night sets the respawn point the moment a player lies down; a Comforts sleeping bag must not.
     * The vanilla bed is the control: if it did not set the spawn here, the test could not tell anything about the bag and fails.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void aComfortsSleepingBagDoesNotMoveTheRespawnPointWhereAVanillaBedDoes(GameTestHelper helper) {
        if (!loaded()) { helper.succeed(); return; }
        ServerLevel level = helper.getLevel();
        long before = level.getDayTime();
        FakePlayer vanilla = player(helper, "uc_sleep_vanilla", "farmer"), bagger = player(helper, "uc_sleep_bag", "farmer");
        level.setDayTime(14000); // dusk: a bed can be used once the level has noticed (the sky brightness is updated on the next ticks, so the beds are used after a short wait)
        helper.runAfterDelay(10, () -> {
            try {
                check(vanilla.getRespawnPosition() == null && bagger.getRespawnPosition() == null, "A fresh fake player has no respawn point");
                lieDown(helper, vanilla, Blocks.RED_BED, new BlockPos(1, 1, 1));
                check(vanilla.isSleeping() && vanilla.getRespawnPosition() != null,
                    "Control: a vanilla bed puts the player to sleep and sets the respawn point (otherwise this test says nothing about the bag): sleeping " + vanilla.isSleeping() + ", respawn " + vanilla.getRespawnPosition());
                Block bag = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse("comforts:sleeping_bag_red"));
                lieDown(helper, bagger, bag, new BlockPos(5, 1, 1));
                // the bag must really have been used, or "no respawn point" would prove nothing
                check(bagger.isSleeping(), "The player did not lie down in the Comforts sleeping bag, so its effect on the respawn point cannot be judged (the vanilla route says: "
                    + bagger.startSleepInBed(helper.absolutePos(new BlockPos(5, 1, 1))).left() + "; day time " + level.getDayTime() + ", isDay " + level.isDay() + ", thundering " + level.isThundering() + ", difficulty " + level.getDifficulty() + ")");
                check(bagger.getRespawnPosition() == null, "A Comforts sleeping bag set the respawn point to " + bagger.getRespawnPosition());
                helper.succeed();
            } catch (Throwable failure) {
                helper.fail(String.valueOf(failure.getMessage())); // an AssertionError thrown from a delayed callback would otherwise take the whole test server down
            } finally {
                level.setDayTime(before);
                vanilla.stopSleeping();
                bagger.stopSleeping();
            }
        });
    }

    /** Places a two-part bed of {@code block} (head to the south of the foot) and has the player use it. */
    private static void lieDown(GameTestHelper helper, FakePlayer player, Block block, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos foot = helper.absolutePos(relative), head = foot.south();
        // the tests are packed closely, so whatever a neighbour left standing above this spot would block the sleeper ("obstructed"): clear the room the bed needs
        for (int dx = -1; dx <= 1; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 0; dy <= 3; dy++) level.setBlock(foot.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
        for (BlockPos floor : List.of(foot.below(), head.below())) level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
        BlockState base = block.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlock(foot, base.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(head, base.setValue(BedBlock.PART, BedPart.HEAD), 3);
        player.moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() - 0.5);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(foot), Direction.UP, foot, false);
        level.getBlockState(foot).use(level, player, InteractionHand.MAIN_HAND, hit);
    }
}
