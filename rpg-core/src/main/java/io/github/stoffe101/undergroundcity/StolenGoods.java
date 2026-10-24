package io.github.stoffe101.undergroundcity;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The mark on goods that came out of a safe somebody forced (D-122): a string tag naming the city they were taken in, and a line in the item's tooltip so the carrier can see it. It is evidence,
 * not a currency: a checkpoint or an arrest treats a marked stack as prohibited in every city, and nothing else about the item changes. The tag is only ever put on stacks that were inside a
 * safe at the moment a lock gave way, so honest goods are never marked, and it does not survive the item being used up or crafted into something else.
 */
public final class StolenGoods {
    public static final String KEY = "uc_stolen";
    /** Who took the goods (D-143): only their own taking earns a thief XP at a fence, so handing goods to a partner or back never pays twice. */
    public static final String THIEF = "uc_thief";
    /** The category name a seizure uses for marked goods (not a contraband-catalogue entry: it never depends on the city's law). */
    public static final String CATEGORY = "stolen_goods";

    private StolenGoods() {}

    /** Marks a stack as taken in {@code city} (an empty city is recorded as "unknown"). Idempotent; does nothing to an empty stack. */
    public static void mark(ItemStack stack, String city) { mark(stack, city, null); }

    /** The same, naming who took it. A stack already marked keeps its first mark (and its first thief). */
    public static void mark(ItemStack stack, String city, java.util.UUID thief) {
        if (stack.isEmpty() || is(stack)) return;
        String where = city == null || city.isEmpty() ? "unknown" : city;
        stack.getOrCreateTag().putString(KEY, where);
        if (thief != null) stack.getOrCreateTag().putUUID(THIEF, thief);
        CompoundTag display = stack.getOrCreateTagElement("display");
        ListTag lore = display.contains("Lore", Tag.TAG_LIST) ? display.getList("Lore", Tag.TAG_STRING) : new ListTag();
        lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("Stolen goods (" + TerminalScreenText.capitalize(where) + ")").withStyle(ChatFormatting.RED, ChatFormatting.ITALIC))));
        display.put("Lore", lore);
    }

    public static boolean is(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(KEY, Tag.TAG_STRING);
    }

    /** Who took the goods, or null (goods marked before D-143, or by no one in particular). */
    public static java.util.UUID thief(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.hasUUID(THIEF) ? tag.getUUID(THIEF) : null;
    }

    /** The city the goods were taken in, or "" when the stack is not marked. */
    public static String city(ItemStack stack) {
        return is(stack) ? stack.getTag().getString(KEY) : "";
    }
}
