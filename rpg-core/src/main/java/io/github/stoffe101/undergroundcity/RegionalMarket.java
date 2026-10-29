package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Finite shared regional exchange. Every purchase is server-priced, bounded by shared stock,
 * limited to the city containing the placed terminal, paid from the single Q Shop wallet, and
 * committed through the same fsynced prepared-settlement journal as work-order deliveries.
 * The client chooses only a catalog ID, a bounded quantity and a replay nonce.
 */
public final class RegionalMarket {
    private RegionalMarket() {}

    /** Entry point for the terminal packet. */
    public static void buy(ServerPlayer player, BlockPos pos, String commodity, int quantity, UUID nonce) {
        WorkOrderService.guarded(player, pos, (ledger, citizen) -> purchase(player, pos, ledger, citizen, commodity, quantity, nonce));
    }

    static void purchase(ServerPlayer player, BlockPos pos, WorkOrderLedger ledger, CitizenState.Citizen citizen,
                         String commodity, int quantity, UUID nonce) {
        Exchanges.Exchange exchange = Exchanges.at(player.level(), pos)
            .orElseThrow(() -> new IllegalStateException("No exchange here. Use a terminal inside Ironhaven, Valewood or Blackwater, or at the provisions desk of a wayside shelter."));
        String city = exchange.city();
        if (!exchange.wayside() && !exchange.roadside()) ledger.noteVisit(player.getUUID(), city);   // only a terminal inside the city counts as a visit (a roadside shop is on the road)
        RegionalStock stock = RegionalCatalog.INSTANCE.find(commodity);
        if (stock == null || !stock.city().equals(city) || !stock.soldAt(exchange.outlet())) {
            throw new IllegalStateException("This exchange does not stock that item");
        }
        if (quantity < 1 || quantity > stock.maxOrder()) {
            throw new IllegalStateException("Order between 1 and " + stock.maxOrder() + " units");
        }
        if (ledger.hasPurchase(player.getUUID(), nonce)) throw new IllegalStateException("Purchase was already processed");
        if (quantity > ledger.regionalUnits(stock)) throw new IllegalStateException("Only " + ledger.regionalUnits(stock)
            + " units left; stock replenishes during active play");
        // Plan on a COPY; insufficient room leaves the live inventory untouched.
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        ItemStack bought = new ItemStack(stock.registryItem(), quantity);
        after.add(bought);
        if (!bought.isEmpty()) throw new IllegalStateException("Not enough inventory space for that order");
        long cost = Math.multiplyExact((long) quantity, (long) unitPrice(stock, citizen));
        // Holds left behind by an interrupted purchase are decided first, so the bank shows what it really has.
        CrewService.resolveHolds(player.server, ledger);
        // Everything that can refuse the purchase is decided BEFORE a single credit moves: the bank's share is only planned here, and the buyer's own
        // wallet must cover the rest. A refusal therefore leaves the wallet, the bank, the stock and the inventory exactly as they were.
        CrewLedger.Share share = CrewService.planPurchase(player, cost);
        long personal = cost - share.credits();
        double balance = QShopEconomy.balance(player);
        if (balance < personal) {
            throw new IllegalStateException(shortfall(cost, share, personal, balance));
        }
        UUID hold = share.credits() > 0 ? CrewService.reservePurchase(player, nonce, share.credits(),
            quantity + " x " + stock.registryItem().getDescription().getString()) : null;
        try {
            CrewService.checkpoint("purchase_after_reserve");
            ledger.preparePurchase(player.getUUID(), nonce, stock, quantity, after.save(new ListTag()), balance - personal, citizen.careerXp(), hold, share.credits());
        } catch (RuntimeException error) {
            // The journal decides: a hold that no prepared record names goes back to the bank; if the journal's own write was uncertain the hold stays
            // untouched for the next start. Never a blind refund.
            if (hold != null) CrewService.resolveHolds(player.server, ledger);
            throw error;
        }
        CrewService.checkpoint("purchase_after_prepare");
        WorkOrderService.recover(player, ledger);
        CitizenState.get(player.server).markJourney(player.getUUID(), "bought");
        EconomyTelemetry.record(player, "purchase", "good", stock.id(), "units", quantity, "credits", cost, "bank", share.credits(), "city", city);
        if (exchange.counter()) Regulars.traded(player.server, city, exchange.outlet(), player.getUUID(), false);   // the keepers remember who buys from them (D-202)
        player.sendSystemMessage(Component.literal("Bought " + quantity + " x " + stock.registryItem().getDescription().getString()
            + " from " + exchange.seller() + " for " + cost + " credits"
            + (share.credits() > 0 ? " (" + share.credits() + " from the Crew bank, " + personal + " from your wallet)." : ".")));
    }

    /** The refusal text when the buyer's own wallet cannot cover its part: it says what the bank would have paid and why it cannot help, if it cannot. */
    static String shortfall(long cost, CrewLedger.Share share, long personal, double balance) {
        StringBuilder text = new StringBuilder("This order costs ").append(cost).append(" credits");
        if (share.credits() > 0) text.append(": the Crew bank would pay ").append(share.credits()).append(" and your wallet the other ").append(personal);
        text.append(". You have ").append((long) Math.floor(balance)).append(" in your wallet.");
        if (!share.reason().isEmpty()) text.append(' ').append(share.reason());
        return text.toString();
    }

    /**
     * The list price less the citizen's exchange discount from utility skills, Trading and (for a vehicle) Driving (at most 10% together), never below 1 credit; a shop line costs more while the machine of the building
     * that keeps it supplied is broken down (eighth pass, {@link MachineService#short_}).
     */
    static int unitPrice(RegionalStock stock, CitizenState.Citizen citizen) {
        double discount = citizen == null ? 0 : GeneralSkills.exchangeDiscount(citizen, stock);   // career skills, Trading (D-174) and, at a vehicle dealer, Driving (D-200): at most 10% together
        int price = (int) Math.max(1, Math.round(stock.unitPrice() * (1.0 - discount)));
        return MachineService.short_(stock) ? MachineService.marked(price) : price;
    }

    /** Offers visible at a terminal, priced for this citizen. Empty outside every city region. */
    static List<TerminalData.Offer> offers(WorkOrderLedger ledger, String city, CitizenState.Citizen citizen) { return offers(ledger, city, RegionalStock.CITY, citizen); }

    /** Offers of one outlet of a city (its exchange or the provisions desks of its wayside shelters). */
    static List<TerminalData.Offer> offers(WorkOrderLedger ledger, String city, String outlet, CitizenState.Citizen citizen) { return offers(ledger, city, outlet, citizen, null); }

    /** As above, with what a shop pays for each line it buys (D-164) and how many of it {@code player} carries. */
    static List<TerminalData.Offer> offers(WorkOrderLedger ledger, String city, String outlet, CitizenState.Citizen citizen, net.minecraft.server.level.ServerPlayer player) {
        if (city == null || city.isEmpty()) return List.of();
        return RegionalCatalog.INSTANCE.forOutlet(city, outlet).stream().map(stock -> {
            RegionalCatalog.Supply supply = RegionalStock.isShop(outlet) ? RegionalCatalog.INSTANCE.supply(stock.id()) : null;
            int units = ledger.regionalUnits(stock);
            return new TerminalData.Offer(stock.id(), stock.item().toString(), unitPrice(stock, citizen), units, stock.capacity(), stock.maxOrder(),
                supply == null ? 0 : Shortages.paysNow(stock, supply.pays(), units), supply == null || player == null ? 0 : ShopSupply.carried(player, stock));
        }).toList();
    }
}
