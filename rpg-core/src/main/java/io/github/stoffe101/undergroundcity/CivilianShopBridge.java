package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import com.qshop.client.ShopScreen;
import com.qshop.currency.Currency;
import com.qshop.net.ClientShopEntry;
import com.qshop.net.ClientTab;
import com.qshop.net.OpenShopPacket;
import com.qshop.net.QShopNetwork;
import com.qshop.net.TradePacket;
import com.qshop.shop.ShopEntryType;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The only place the civilian counter touches Q Shop's client classes (it is loaded only when Q Shop 1.8.4 is present). Q Shop opens a shop by constructing its own screen from the packet the
 * server sent; the screen keeps the packet in a package-private field and no hook exists for another screen to take its place, so the packet is read from it by reflection, for the one pinned version
 * (the caller checks it), and converted into {@link CivilianShopData}. Anything unexpected (a missing field, a shop with barter or command entries, an editing session) leaves Q Shop's own screen
 * on screen: the counter is a better picture of the shop, never a requirement. A press sends Q Shop's own {@link TradePacket}; the server checks and carries out the trade exactly as for Q Shop's screen.
 */
@OnlyIn(Dist.CLIENT)
final class CivilianShopBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Only Core's own shops (their ids start with this) get the Underground City counter; any other Q Shop shop is left as its author made it. */
    static final String CORE_SHOP_PREFIX = "uc_";
    private static Field packetField;
    private static boolean failed;

    private CivilianShopBridge() {}

    /** The counter that should replace {@code screen}, or null when it should stay as it is. */
    static Screen replacement(Screen screen) {
        if (failed || !(screen instanceof ShopScreen)) return null;
        try {
            if (packetField == null) {
                packetField = ShopScreen.class.getDeclaredField("data");
                packetField.setAccessible(true);
            }
            OpenShopPacket packet = (OpenShopPacket) packetField.get(screen);
            if (packet == null || !packet.shopId.startsWith(CORE_SHOP_PREFIX) || packet.editing) return null;
            CivilianShopData data = convert(packet);
            return data == null ? null : new CivilianShopScreen(data, CivilianShopBridge::trade);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failed = true; // once: a different Q Shop build than the pinned one; the native screen serves every shop from here on
            LOGGER.warn("[UC-SHOP] the civilian counter cannot read Q Shop's shop screen ({}); Q Shop's own screen is used", error.toString());
            return null;
        }
    }

    /** Converts what Q Shop sent; null when the shop has anything the counter does not show (barter or command entries), so the native screen is used for it. */
    static CivilianShopData convert(OpenShopPacket packet) {
        String currencyName = packet.shopCurrency;
        for (Currency currency : packet.currencies) if (currency.id.equals(packet.shopCurrency)) currencyName = currency.displayName;
        double balance = packet.balances.getOrDefault(packet.shopCurrency, 0.0);
        List<CivilianShopData.Tab> tabs = new ArrayList<>();
        for (ClientTab tab : packet.tabs) {
            if (!tab.requirementsMet) continue;
            List<CivilianShopData.Entry> entries = new ArrayList<>();
            for (ClientShopEntry entry : tab.entries) {
                if (!entry.requirementsMet) continue;
                if (entry.type != ShopEntryType.BUY && entry.type != ShopEntryType.SELL) return null;
                if (!entry.currencyId.isEmpty() && !entry.currencyId.equals(packet.shopCurrency)) return null;
                int globalLeft = entry.globalLimit > 0 ? Math.max(0, entry.globalLimit - entry.usedGlobal) : -1;
                int playerLeft = entry.playerLimit > 0 ? Math.max(0, entry.playerLimit - entry.usedPlayer) : -1;
                var shown = entry.displayItem.isEmpty() ? entry.item : entry.displayItem;
                String name = entry.displayName.isEmpty() ? shown.getHoverName().getString() : entry.displayName;
                entries.add(new CivilianShopData.Entry(entry.serverIndex, entry.type == ShopEntryType.SELL, name, entry.item.isEmpty() ? shown : entry.item, entry.price, globalLeft, playerLeft, entry.resetName));
            }
            tabs.add(new CivilianShopData.Tab(tab.serverIndex, tab.name, tab.icon, entries));
        }
        // A shop with one tab that mixes goods for sale and goods wanted (Core's own starter shop) reads better as two sections: what you can buy, and what you can deliver. Both keep the
        // provider's tab index and each entry its own index, so a press names exactly what the provider's screen would.
        if (tabs.size() == 1) {
            CivilianShopData.Tab only = tabs.get(0);
            List<CivilianShopData.Entry> buys = only.entries().stream().filter(e -> !e.sell()).toList(), sells = only.entries().stream().filter(CivilianShopData.Entry::sell).toList();
            if (!buys.isEmpty() && !sells.isEmpty()) {
                tabs = List.of(new CivilianShopData.Tab(only.serverIndex(), "Goods", buys.get(0).stack().copy(), buys), new CivilianShopData.Tab(only.serverIndex(), "Deliver", sells.get(0).stack().copy(), sells));
            }
        }
        return new CivilianShopData(packet.shopId, packet.shopName, currencyName, balance, tabs);
    }

    /** Q Shop's own trade request; the server validates the shop, the entry, the limits, the wallet and the inventory. */
    static void trade(String shopId, int tabIndex, int entryIndex, int units) {
        QShopNetwork.sendToServer(new TradePacket(shopId, tabIndex, entryIndex, units));
    }
}
