package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** A courier's sealed letter (D-139): who it is from, the city it goes to and its fee, read from its tag. Gameplay is in {@link RoadEncounters#deliver}. */
public final class EnvelopeItem extends Item {
    public EnvelopeItem(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> lines, TooltipFlag flag) {
        CompoundTag letter = stack.getTagElement("uc_letter");
        if (letter == null) { lines.add(Component.literal("Blank").withStyle(ChatFormatting.GRAY)); return; }
        lines.add(Component.literal("To: " + TerminalScreenText.capitalize(letter.getString("to"))).withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("From: " + letter.getString("from")).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal("Pays " + letter.getInt("fee") + " credits at any terminal or counter there").withStyle(ChatFormatting.DARK_GREEN));
    }
}
