package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A building's machine drive housing (eighth pass, the Smith's engineering): a riveted steel guard set into the wall beside the street door, with the flywheel showing through a slot. It keeps no
 * state of its own: whether the machine runs is {@link MachineService}'s, and the block only shows it (a green lamp, or a red tag and smoke). A right click opens the drive to a Smith with an
 * Engineer's Toolkit, and tells anybody else what it is. Placed by Core, never by a player, and part of the protected building around it.
 */
final class MachineBlock extends HorizontalDirectionalBlock {
    static final BooleanProperty RUNNING = BooleanProperty.create("running");

    MachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(RUNNING, true));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, RUNNING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp && level instanceof ServerLevel server) SmithWork.useMachine(sp, server, pos);
        return InteractionResult.CONSUME;
    }

    /** A stopped machine smokes from its slot; a running one now and then throws a spark off the flywheel. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        Direction out = state.getValue(FACING);
        double x = pos.getX() + 0.5 + out.getStepX() * 0.55, y = pos.getY() + 0.55, z = pos.getZ() + 0.5 + out.getStepZ() * 0.55;
        if (!state.getValue(RUNNING)) {
            if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, x + (random.nextDouble() - 0.5) * 0.4, y + 0.2, z + (random.nextDouble() - 0.5) * 0.4, 0, 0.03, 0);
        } else if (random.nextInt(40) == 0) {
            level.addParticle(ParticleTypes.CRIT, x, y, z, out.getStepX() * 0.05, 0.02, out.getStepZ() * 0.05);
        }
    }
}
