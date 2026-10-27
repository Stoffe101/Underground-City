package io.github.stoffe101.undergroundcity;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * What the civilian shop screen shows, in Core's own types. Q Shop owns the shop, its stock limits, the wallet and every trade; the packet it sends to open a shop is converted into
 * this once, by {@link CivilianShopBridge}, so the screen (and its review captures) never touch Q Shop's classes and nothing here can change a price or a balance.
 *
 * @param id       Q Shop's shop id (the trade packet names it; the server decides everything else)
 * @param balance  the player's balance of the shop's currency when the shop was opened, for display only
 */
record CivilianShopData(String id, String name, String currency, double balance, List<Tab> tabs) {
    /** A category of goods: its Q Shop tab index (the trade names it) and its entries. */
    record Tab(int serverIndex, String name, ItemStack icon, List<Entry> entries) {}

    /**
     * One thing on sale or wanted: {@code sell} is true when the shop buys it from the player. {@code globalLeft} and {@code playerLeft} are how many more lots the shop-wide and the
     * player's own limits allow ({@code -1} when no limit applies); {@code reset} is Q Shop's period name (NEVER, DAILY, WEEKLY, MONTHLY).
     */
    record Entry(int serverIndex, boolean sell, String name, ItemStack stack, double price, int globalLeft, int playerLeft, String reset) {
        /** Items in one lot. */
        int count() { return Math.max(1, stack.getCount()); }

        /** How many more lots may be traded now, or {@code -1} when nothing limits it. */
        int left() {
            if (globalLeft < 0) return playerLeft;
            if (playerLeft < 0) return globalLeft;
            return Math.min(globalLeft, playerLeft);
        }

        /** "3 left today", "12 left", or "" when nothing limits the entry. */
        String limitText() {
            int left = left();
            if (left < 0) return "";
            String period = switch (reset == null ? "" : reset.toUpperCase(Locale.ROOT)) {
                case "DAILY" -> " today";
                case "WEEKLY" -> " this week";
                case "MONTHLY" -> " this month";
                default -> "";
            };
            return left + " left" + period;
        }
    }

    /** A price as the screen prints it: whole numbers whole, anything else with two places. */
    static String fmt(double value) { return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value); }

    boolean empty() {
        for (Tab tab : tabs) if (!tab.entries().isEmpty()) return false;
        return true;
    }
}
