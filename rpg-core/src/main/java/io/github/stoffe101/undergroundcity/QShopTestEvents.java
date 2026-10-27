package io.github.stoffe101.undergroundcity;

import com.qshop.api.CurrencyChangedEvent;
import com.qshop.api.CurrencyService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;

/**
 * Posts the provider's own currency events for a GameTest. Kept in a class of its own because a test class that named {@link CurrencyChangedEvent} in a method would fail to load in a run
 * without Q Shop (the verifier loads the event class to check it is an event); a test calls into this one only after it has seen that Q Shop is loaded.
 */
final class QShopTestEvents {
    private QShopTestEvents() {}

    /** A balance change with the given source ({@code "trade"} or {@code "command"}). */
    static void post(ServerPlayer player, double before, double after, String source) {
        var reason = source.equals("trade") ? CurrencyService.SOURCE_TRADE : CurrencyService.SOURCE_COMMAND;
        MinecraftForge.EVENT_BUS.post(new CurrencyChangedEvent(player, "credits", before, after, reason, null));
    }
}
