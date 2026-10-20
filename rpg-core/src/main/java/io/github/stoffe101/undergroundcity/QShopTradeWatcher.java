package io.github.stoffe101.undergroundcity;

import com.qshop.api.CurrencyChangedEvent;
import com.qshop.api.CurrencyService;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Marks the guided journey's "buy something" step when a citizen really buys something from a Q Shop shop (the provider tells every listener when a balance changes and why, and a trade is its
 * own reason). Opening the shop used to count; looking is not buying. Registered only when Q Shop is loaded, so the server never loads this class without its provider.
 */
public final class QShopTradeWatcher {
    @SubscribeEvent
    public void changed(CurrencyChangedEvent event) {
        if (event.getPlayer() == null || !CurrencyService.SOURCE_TRADE.equals(event.getSource()) || !"credits".equals(event.getCurrency())) return;
        if (event.getNewValue() >= event.getOldValue()) return; // a sale to the shop pays; only a purchase counts
        CitizenState.get(event.getPlayer().server).markJourney(event.getPlayerUuid(), "bought");
    }
}
