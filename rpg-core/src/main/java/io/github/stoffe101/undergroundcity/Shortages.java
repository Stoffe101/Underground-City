package io.github.stoffe101.undergroundcity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * A shop running short (tenth pass, D-201): a line a player's trade can fill (the bar's wine, the bakery's flour, the builders' yard's fasteners) is <i>short</i> once its customers
 * have bought its shelf down to a quarter of what it holds. While it is short the shop pays a quarter more a unit for the goods that bring the shelf back to half (never as much as it
 * sells them for, so buying from it to sell back can never pay), and the players of that trade who are in the game are told once when it goes short. The shelf is the real one the
 * customers buy from, so filling it ends the shortage; nothing is paid for a shelf nobody emptied, and a shelf refills only as fast as the city's customers empty it.
 */
final class Shortages {
    static final double PREMIUM = 0.25;
    /** A line goes short at most this often, as far as anybody is told (an hour of the server's time). */
    static final long TELL_EVERY_TICKS = 72_000;
    private static final Map<String, Long> TOLD = new HashMap<>();

    private Shortages() {}

    /** The shelf count at or below which a line is short: a quarter of what it holds (a shelf of fewer than four never is). */
    static int mark(RegionalStock stock) { return stock.capacity() >= 4 ? stock.capacity() / 4 : -1; }

    static boolean isShort(RegionalStock stock, int units) { return units <= mark(stock); }

    /** How many of {@code quantity} units sold now earn the premium: those that bring a short shelf back up to half. */
    static int premiumUnits(RegionalStock stock, int units, int quantity) {
        if (!isShort(stock, units)) return 0;
        return Math.max(0, Math.min(quantity, stock.capacity() / 2 - units));
    }

    /** What the shop pays a unit while it is short: a quarter more, always below its own price. */
    static int shortPays(RegionalStock stock, int pays) { return (int) Math.min(stock.unitPrice() - 1L, Math.round(pays * (1 + PREMIUM))); }

    /** What the counter shows it pays a unit now. */
    static int paysNow(RegionalStock stock, int pays, int units) { return pays > 0 && isShort(stock, units) ? shortPays(stock, pays) : pays; }

    /** A customer bought from a line: when that took it short, the players of its trade who are in the game are told (once an hour a line at most). */
    static void drawnDown(MinecraftServer server, RegionalStock stock, int before, int after) {
        if (isShort(stock, before) || !isShort(stock, after)) return;
        RegionalCatalog.Supply terms = RegionalCatalog.INSTANCE.supply(stock.id());
        if (terms == null || terms.career().isEmpty()) return;
        long now = server.getTickCount();
        Long last = TOLD.get(stock.id());
        if (last != null && now - last < TELL_EVERY_TICKS) return;
        TOLD.put(stock.id(), now);
        String shop = shopTitle(stock), item = stock.registryItem().getDescription().getString();
        String city = TerminalScreenText.capitalize(stock.city());
        int units = Math.max(1, stock.capacity() / 2 - after);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CitizenState.Citizen c = CitizenState.get(server).find(player.getUUID()).orElse(null);
            if (c == null || !terms.career().equals(c.career) && !regular(server, stock, player.getUUID())) continue;
            Notice.send(player, Notice.Kind.INFO, shop + " is short", city + ". " + item + ": it pays " + shortPays(stock, terms.pays()) + " a unit (a quarter more) for the next " + units + ".");
        }
    }

    /** True when the player is a regular at a counter that sells this line ({@link Regulars}): a regular hears of its shortages whatever their trade. */
    static boolean regular(MinecraftServer server, RegionalStock stock, java.util.UUID player) {
        for (String outlet : stock.outlets()) if (RegionalStock.isShop(outlet) && Regulars.of(server, stock.city(), outlet, player).regular()) return true;
        return false;
    }

    /** The name of the shop whose counter sells a line ("The Bar"), or "A shop". */
    static String shopTitle(RegionalStock stock) {
        for (ShopCounters.Shop shop : ShopCounters.INSTANCE.all().values()) if (shop.city().equals(stock.city()) && stock.soldAt(shop.outlet())) return shop.title();
        return "A shop";
    }

    /** Test seam. */
    static void clearForTest() { TOLD.clear(); }
}
