package io.github.stoffe101.undergroundcity.mixin;

import io.github.stoffe101.undergroundcity.BackpackGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server-side gate on Sophisticated Backpacks' Deposit and Restock upgrades. The mod's inventory-interaction packet names a block position and the mod then
 * deposits into or restocks from whatever container is there, with no reach or rights check (see {@link BackpackGate}). Pseudo: nothing happens when the mod is absent.
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.util.InventoryInteractionHelper", remap = false)
public abstract class InventoryInteractionHelperMixin {
    @Inject(method = "tryInventoryInteraction(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/player/Player;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void underground_city$gateByHomeRights(BlockPos pos, Level level, ItemStack backpack, Direction face, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!BackpackGate.allowsContainerInteraction(pos, level, player)) cir.setReturnValue(false);
    }
}
