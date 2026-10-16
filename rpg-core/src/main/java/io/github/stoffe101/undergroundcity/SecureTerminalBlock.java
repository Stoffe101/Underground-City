package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
 * A secured data terminal in an office or laboratory: the Technician's underground job site. Placed by operators
 * (or the district builder), never craftable or breakable in survival, like the Household Safe. Using it starts the
 * challenge in {@link SecureTerminalService}; it holds a small, slowly refilling stock of recoverable ledgers.
 */
public final class SecureTerminalBlock extends BaseEntityBlock {
    public SecureTerminalBlock(Properties properties) { super(properties); }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new SecureTerminalBlockEntity(pos, state); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !(level.getBlockEntity(pos) instanceof SecureTerminalBlockEntity terminal)) return InteractionResult.PASS;
        try {
            SecureTerminalService.begin(serverPlayer, terminal, serverPlayer.server.getTickCount());
        } catch (IllegalStateException refused) {
            serverPlayer.sendSystemMessage(Component.literal(refused.getMessage()));
        }
        return InteractionResult.CONSUME;
    }
}
