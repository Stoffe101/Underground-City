package io.github.stoffe101.undergroundcity;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The furniture set's furniture stays furniture (D-192). MrCrayfish's legacy furniture (namespace {@code cfm}) has mail boxes and a post box that send items to another player's mail box
 * anywhere in the world, past every checkpoint search and stolen-goods mark; their use is refused, so they stand in a building as decoration. Nothing else of the furniture mods is touched.
 */
public final class FurnitureGuard {
    /** True for a block whose use would move items across the world: a {@code cfm} mail box or post box. */
    static boolean deliversMail(Block block) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        return id != null && id.getNamespace().equals("cfm") && (id.getPath().endsWith("mail_box") || id.getPath().equals("post_box"));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void use(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !deliversMail(event.getLevel().getBlockState(event.getPos()).getBlock())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        player.displayClientMessage(Component.literal("Nobody collects from this box: the post does not run in this city."), true);
    }
}
