package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The fictional Sable Leaf plant (the Farmer's controlled crop). It is a real crop block with real costs:
 * it only grows in bright light (a grow lamp or sunlight), and each of its three growth steps needs one dose
 * of Cultivation Nutrient applied by hand (a regional-exchange product), so a harvest is never free. Bone meal
 * does nothing. It yields Sable Leaf Bales, which the city laws treat as controlled goods.
 */
public final class RestrictedCropBlock extends CropBlock {
    public static final IntegerProperty AGE = BlockStateProperties.AGE_3;
    public static final BooleanProperty NOURISHED = BooleanProperty.create("nourished");
    /** Light level needed to grow; a plant still survives from level 8 like any crop. */
    public static final int LIGHT_NEEDED = 12;
    /** One growth step per this many random ticks on average. */
    static final int GROWTH_ODDS = 6;
    private static final VoxelShape[] SHAPES = {
        Block.box(0, 0, 0, 16, 2, 16), Block.box(0, 0, 0, 16, 5, 16), Block.box(0, 0, 0, 16, 8, 16), Block.box(0, 0, 0, 16, 11, 16)};

    public RestrictedCropBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AGE, 0).setValue(NOURISHED, false));
    }

    @Override public IntegerProperty getAgeProperty() { return AGE; }
    @Override public int getMaxAge() { return 3; }
    @Override protected ItemLike getBaseSeedId() { return UndergroundCity.item("sable_cutting"); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(AGE, NOURISHED); }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES[state.getValue(AGE)]; }
    @Override public boolean isValidBonemealTarget(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state, boolean client) { return false; }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(NOURISHED) || level.getRawBrightness(pos, 0) < LIGHT_NEEDED) return;
        if (random.nextInt(GROWTH_ODDS) != 0) return;
        int age = getAge(state);
        if (age < getMaxAge()) level.setBlock(pos, state.setValue(AGE, age + 1).setValue(NOURISHED, false), 2);
    }

    /** Right-click with Cultivation Nutrient: feeds the next growth step. */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!held.is(UndergroundCity.item("cultivation_nutrient")) || state.getValue(NOURISHED) || getAge(state) >= getMaxAge()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        level.setBlock(pos, state.setValue(NOURISHED, true), 2);
        if (!player.getAbilities().instabuild) held.shrink(1);
        return InteractionResult.CONSUME;
    }
}
