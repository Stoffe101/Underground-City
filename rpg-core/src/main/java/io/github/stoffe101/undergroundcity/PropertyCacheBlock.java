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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Household safe: a map object placed and registered by operators, never craftable or breakable
 * by survival players (a smash-and-grab would bypass the lock, evidence and restock rules).
 */
public final class PropertyCacheBlock extends BaseEntityBlock {
    public PropertyCacheBlock(Properties properties) { super(properties); }

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
        String refusal = cache.access(serverLevel, serverPlayer, WorkOrderLedger.get(serverPlayer.server).clock());
        if (refusal != null) {
            serverPlayer.sendSystemMessage(Component.literal(refusal));
            return InteractionResult.CONSUME;
        }
        serverPlayer.openMenu(cache);
        return InteractionResult.CONSUME;
    }
}
