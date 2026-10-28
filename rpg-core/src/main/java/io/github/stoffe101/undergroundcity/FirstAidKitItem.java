package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A first aid kit (tenth pass, D-203): the game's medical item, bought, never free. Held for a moment and used, it gives back three hearts at once. It cannot be used at full health,
 * and a person can patch themselves up once in thirty seconds. It cures nothing else and heals nobody else. It is sold by every city's exchange and, dearer, at the wayside desks
 * ({@code content/gameplay/survival-supply.json}).
 */
public final class FirstAidKitItem extends Item {
    static final float HEAL = 6.0f;
    static final int USE_TICKS = 32, COOLDOWN_TICKS = 600;

    public FirstAidKitItem(Properties properties) { super(properties); }

    @Override public int getUseDuration(ItemStack stack) { return USE_TICKS; }

    @Override public UseAnim getUseAnimation(ItemStack stack) { return UseAnim.BOW; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getHealth() >= player.getMaxHealth()) {
            if (!level.isClientSide()) player.displayClientMessage(Component.literal("You are not hurt."), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!level.isClientSide() && entity instanceof ServerPlayer player && player.getHealth() < player.getMaxHealth()) {
            player.heal(HEAL);
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
            level.playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.8f, 1.2f);
            player.displayClientMessage(Component.literal("Patched up: +3 hearts."), true);
            if (!player.getAbilities().instabuild) stack.shrink(1);
        }
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.literal("Hold to use: three hearts back.").withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Once in thirty seconds; not at full health.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
