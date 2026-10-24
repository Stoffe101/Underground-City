package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/**
 * A building's alarm panel (D-128): a keypad on the wall just inside the street door of a furnished building whose security class has an alarm. Its light shows what the panel is doing, and the
 * server sets it, never the client: dark when this building's alarm is not wired, red when it is armed, flashing amber while somebody who came in through a door has the entry delay to work it, and
 * blue while it is jammed. A Technician with Bypass Tooling and a Bypass Kit works it ({@link AlarmPanels}); anybody else learns only what the light says. Placed by the furnished buildings, never
 * craftable or breakable in survival, without a block entity (the state is all it keeps; what is jammed is the server's).
 */
public final class AlarmPanelBlock extends HorizontalDirectionalBlock {
    public enum Light implements StringRepresentable {
        OFF, ARMED, ENTRY, JAMMED;
        @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
    }

    public static final EnumProperty<Light> LIGHT = EnumProperty.create("light", Light.class);
    /** A plate 6 wide, 8 high and 1 deep on the wall behind the block's cell (the side it faces away from). */
    private static final VoxelShape NORTH = Block.box(5, 3, 15, 11, 11, 16), SOUTH = Block.box(5, 3, 0, 11, 11, 1),
        EAST = Block.box(0, 3, 5, 1, 11, 11), WEST = Block.box(15, 3, 5, 16, 11, 11);

    public AlarmPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIGHT, Light.OFF));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, LIGHT); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) { case SOUTH -> SOUTH; case EAST -> EAST; case WEST -> WEST; default -> NORTH; };
    }

    @Override public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer) AlarmPanels.use(serverPlayer, pos);
        return InteractionResult.CONSUME;
    }
}
