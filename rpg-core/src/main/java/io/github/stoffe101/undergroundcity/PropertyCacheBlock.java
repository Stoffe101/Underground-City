package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Household safe: a map object placed and registered by operators, never craftable or breakable
 * by survival players (a smash-and-grab would bypass the lock, evidence and restock rules).
 */
public final class PropertyCacheBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public PropertyCacheBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PropertyCacheBlockEntity(pos, state); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)
            || !(level.getBlockEntity(pos) instanceof PropertyCacheBlockEntity cache)) return InteractionResult.PASS;
        if (WorkOrderLedger.get(serverPlayer.server).faulted()) {
            serverPlayer.sendSystemMessage(Component.literal("Property storage is unavailable until the server is restarted."));
            return InteractionResult.CONSUME;
        }
        long now = WorkOrderLedger.get(serverPlayer.server).clock();
        PropertyCacheBlockEntity.Entry entry = cache.entry(serverLevel, serverPlayer, now);
        if (entry.kind() == PropertyCacheBlockEntity.Entry.Kind.REFUSED) {
            serverPlayer.sendSystemMessage(Component.literal(entry.text()));
            return InteractionResult.CONSUME;
        }
        if (entry.kind() == PropertyCacheBlockEntity.Entry.Kind.PICK) {
            var city = CityRegions.INSTANCE.nearest(serverLevel, pos).orElse("");
            String refusal = LockService.begin(serverPlayer, serverLevel, new LockService.Target(LockService.Kind.SAFE, pos.immutable(), entry.profile(), city, "safe", cache.label(), entry.picksNeeded()));
            if (refusal != null) serverPlayer.sendSystemMessage(Component.literal(refusal));
            return InteractionResult.CONSUME;
        }
        serverPlayer.openMenu(cache);
        return InteractionResult.CONSUME;
    }
}
