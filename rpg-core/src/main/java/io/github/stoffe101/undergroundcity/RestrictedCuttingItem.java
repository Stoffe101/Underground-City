package io.github.stoffe101.undergroundcity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;

/**
 * A cannabis seed. Anyone may carry one (carrying is what the law cares about), but only a Farmer who has
 * learned Restricted Cultivation can raise it: the server refuses to plant it for anyone else. Holding the skill
 * is not a crime and nothing here flags the player; consequences come only from carried contraband at inspections.
 */
public final class RestrictedCuttingItem extends ItemNameBlockItem {
    public static final String SKILL = "farmer.restricted_cultivation";

    public RestrictedCuttingItem(Block block, Properties properties) { super(block, properties); }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (context.getLevel().isClientSide) return InteractionResult.CONSUME; // the server decides; no client-side guess
        if (context.getPlayer() instanceof ServerPlayer player && !SkillService.has(player, SKILL)) {
            player.sendSystemMessage(Component.literal("You have not learned Restricted Cultivation (a Farmer skill), so you cannot raise this seed."));
            return InteractionResult.FAIL;
        }
        return super.place(context);
    }
}
