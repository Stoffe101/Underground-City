package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Locale;

/**
 * The Technician's blocks (D-158). None keeps state of its own: what is broken, breached or installed is the server's ({@link TechService}); the panel's lamp only shows it.
 */
final class TechBlocks {
    private TechBlocks() {}

    /** A facing block: its front looks at whoever placed it. */
    abstract static class Facing extends HorizontalDirectionalBlock {
        Facing(Properties properties) { super(properties); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
        @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
        @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    }

    /**
     * A building's utility panel: a steel hatch set into the wall beside its street door, the one access point to the building's electrics and network. Its lamp is green while all is well and red
     * while a fault is open (a service call). Placed by Core beside the street doors of city buildings in a new archipelago world, never by a player in survival, and part of the protected building
     * around it.
     */
    static final class UtilityPanel extends Facing {
        enum State implements StringRepresentable {
            OK, FAULT;
            @Override public String getSerializedName() { return name().toLowerCase(Locale.ROOT); }
        }

        static final EnumProperty<State> STATE = EnumProperty.create("state", State.class);

        UtilityPanel(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STATE, State.OK));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, STATE); }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp && level instanceof ServerLevel server) TechWork.usePanel(sp, server, pos);
            return InteractionResult.CONSUME;
        }
    }

    /** A personal computer on a desk: the Technician's desktop (service calls and the bench's designs). Anybody may place one at home; it is a workstation of the home. */
    static final class Computer extends Facing {
        private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);
        Computer(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp) TechNetwork.openComputer(sp, pos);
            return InteractionResult.CONSUME;
        }
    }

    /** An electronics bench: where circuit boards, kits and the Technician's tools are put together from parts ({@link TechDesigns}). */
    static final class Bench extends Facing {
        Bench(Properties properties) { super(properties); registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH)); }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

        @Override
        public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (player instanceof ServerPlayer sp) TechNetwork.openBench(sp, pos);
            return InteractionResult.CONSUME;
        }
    }
}
