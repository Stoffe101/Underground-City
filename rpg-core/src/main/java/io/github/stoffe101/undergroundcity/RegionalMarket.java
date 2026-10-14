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
        String city = CityRegions.INSTANCE.cityAt(player.level(), pos)
            .orElseThrow(() -> new IllegalStateException("No regional exchange here. Use a terminal inside Ironhaven, Valewood or Blackwater."));
        ledger.noteVisit(player.getUUID(), city);
        RegionalStock stock = RegionalCatalog.INSTANCE.find(commodity);
        if (stock == null || !stock.city().equals(city)) {
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
        long cost = Math.multiplyExact((long) quantity, (long) stock.unitPrice());
        double balance = QShopEconomy.balance(player);
        if (balance < cost) throw new IllegalStateException("This order costs " + cost + " credits");
        ledger.preparePurchase(player.getUUID(), nonce, stock, quantity, after.save(new ListTag()), balance - cost, citizen.careerXp());
        WorkOrderService.recover(player, ledger);
        player.sendSystemMessage(Component.literal("Bought " + quantity + " x " + stock.registryItem().getDescription().getString()
            + " from the " + city + " exchange for " + cost + " credits."));
    }

    /** Offers visible at a terminal. Empty outside every city region. */
    static List<TerminalData.Offer> offers(WorkOrderLedger ledger, String city) {
        if (city == null || city.isEmpty()) return List.of();
        return RegionalCatalog.INSTANCE.forCity(city).stream().map(stock -> new TerminalData.Offer(stock.id(),
            stock.item().toString(), stock.unitPrice(), ledger.regionalUnits(stock), stock.capacity(), stock.maxOrder())).toList();
    }
}
