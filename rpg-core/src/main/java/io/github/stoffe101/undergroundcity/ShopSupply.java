package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.UUID;

/**
 * Selling goods to a shop (D-164): a shopkeeper whose counter sells a line a trade makes (the pub's berry wine, the builders' yard's fasteners, the bakery's flour) also buys it from whoever
 * brings it, at a price below what it charges, while its shelf has room. The goods go onto the shared shelf that the counter sells from and that the city's customers buy from in active play
 * ({@link WorkOrderLedger#drawDown}); the seller is paid through the same fsynced prepared settlement as every other trade, and earns career XP when the line is of their trade. Fine and Reserve
 * goods are paid more ({@link Workshops#GRADE_PRICE}); the shelf holds them as ordinary units. The client chooses only a line, a bounded quantity and a replay nonce.
 */
public final class ShopSupply {
    private ShopSupply() {}

    /** Entry point for the terminal packet. */
    public static void sell(ServerPlayer player, BlockPos pos, String line, int quantity, UUID nonce) {
        WorkOrderService.guarded(player, pos, (ledger, citizen) -> supply(player, pos, ledger, citizen, line, quantity, nonce));
    }

    /** Goods a shop accepts: the line's item, undamaged, with no tag but a workshop grade (named, enchanted or stolen goods are never taken silently). */
    static boolean acceptable(RegionalStock stock, ItemStack stack) {
        if (stack.isEmpty() || !stack.is(stock.registryItem()) || stack.isDamaged()) return false;
        CompoundTag tag = stack.getTag();
        if (tag != null && !tag.getAllKeys().isEmpty() && !tag.getAllKeys().equals(Set.of(Workshops.QUALITY))) return false;
        return stack.serializeNBT().getCompound("ForgeCaps").isEmpty();
    }

    /** How many of a line's goods the player carries that the shop would take. */
    static int carried(ServerPlayer player, RegionalStock stock) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) if (acceptable(stock, s)) n += s.getCount();
        return n;
    }

    /** Why the shop whose counter stands at {@code pos} cannot buy now, or null: its till and card network are down until a Technician comes (D-165). */
    static String tillRefusal(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        return CityBuildings.INSTANCE.ofChunk(level, pos.getX() >> 4, pos.getZ() >> 4).map(b -> tillRefusal(level, b)).orElse(null);
    }

    static String tillRefusal(net.minecraft.server.level.ServerLevel level, CityBuildings.Building building) {
        return TechService.shopDown(level, building) ? "The till and card network are down, so the shop cannot pay a supplier. A Technician has been called." : null;
    }

    static void supply(ServerPlayer player, BlockPos pos, WorkOrderLedger ledger, CitizenState.Citizen citizen, String line, int quantity, UUID nonce) {
        Exchanges.Exchange exchange = Exchanges.at(player.level(), pos).orElseThrow(() -> new IllegalStateException("Sell to a shop over its counter, while its shopkeeper is there."));
        if (!exchange.counter()) throw new IllegalStateException("Only a shop counter buys goods; an exchange sells and does not buy.");
        String till = tillRefusal(player.serverLevel(), pos);
        if (till != null) throw new IllegalStateException(till);
        RegionalStock stock = RegionalCatalog.INSTANCE.find(line);
        RegionalCatalog.Supply terms = RegionalCatalog.INSTANCE.supply(line);
        if (stock == null || terms == null || !stock.city().equals(exchange.city()) || !stock.soldAt(exchange.outlet())) throw new IllegalStateException("This shop does not buy that.");
        if (quantity < 1 || quantity > stock.maxOrder()) throw new IllegalStateException("Sell between 1 and " + stock.maxOrder() + " at a time");
        if (ledger.hasSupply(player.getUUID(), nonce)) throw new IllegalStateException("That sale was already made");
        int shelf = ledger.regionalUnits(stock), room = stock.capacity() - shelf;
        int premiumLeft = Shortages.premiumUnits(stock, shelf, quantity);   // a short shelf pays a quarter more for the goods that bring it back to half (D-201)
        if (room <= 0) throw new IllegalStateException("The shelf is full; the shop buys again when its customers have bought some.");
        if (quantity > room) throw new IllegalStateException("The shop has room for " + room + " more.");
        // Plan the removal on a COPY; too few goods leave the live inventory untouched.
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        double weight = 0, premiumWeight = 0;
        int needed = quantity, premiumUnits = premiumLeft;
        for (int slot = 0; slot < after.items.size() && needed > 0; slot++) {
            ItemStack s = after.items.get(slot);
            if (!acceptable(stock, s)) continue;
            int take = Math.min(needed, s.getCount());
            weight += take * Workshops.GRADE_PRICE[Workshops.grade(s)];
            int premium = Math.min(take, premiumLeft);
            premiumWeight += premium * Workshops.GRADE_PRICE[Workshops.grade(s)];
            premiumLeft -= premium;
            s.shrink(take);
            needed -= take;
        }
        if (needed > 0) throw new IllegalStateException("You carry " + (quantity - needed) + " the shop would take; named, damaged or stolen goods are never bought here.");
        long credits = Math.max(1, Math.round(terms.pays() * (weight - premiumWeight) + Shortages.shortPays(stock, terms.pays()) * premiumWeight));
        double balance = QShopEconomy.balance(player) + credits;
        if (balance > 1_000_000_000) throw new IllegalStateException("Wallet limit reached");
        boolean ownTrade = !terms.career().isEmpty() && terms.career().equals(citizen.career);
        int xpAfter = ownTrade ? ProgressionRules.totalXp(citizen.careerXp(), terms.xp() * quantity) : citizen.careerXp();
        int levelBefore = citizen.careerLevel();
        ledger.prepareSupply(player.getUUID(), nonce, stock, quantity, credits, after.save(new ListTag()), balance, xpAfter);
        WorkOrderService.recover(player, ledger);
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "shop_supplied");
        String what = quantity + " x " + stock.registryItem().getDescription().getString();
        long shortBonus = premiumUnits == 0 ? 0 : Math.round((Shortages.shortPays(stock, terms.pays()) - terms.pays()) * premiumWeight);
        long gradeBonus = credits - shortBonus - (long) terms.pays() * quantity;
        String bonus = (gradeBonus > 0 ? " (" + gradeBonus + " of it for the grade)" : "") + (shortBonus > 0 ? " (" + shortBonus + " of it because the shelf was short)" : "");
        player.sendSystemMessage(Component.literal("Sold " + what + " to " + exchange.seller() + " for " + credits + " credits" + bonus + (ownTrade ? ", +" + terms.xp() * quantity + " career XP." : ".")));
        Notice.send(player, Notice.Kind.DELIVERED, "Sold to the shop", what + ": +" + credits + " credits");
        if (citizen.careerLevel() > levelBefore) Notice.send(player, Notice.Kind.SKILL, "Level " + citizen.careerLevel(), TerminalScreenText.capitalize(citizen.career) + " level up. A skill point is waiting.");
        GeneralSkills.suppliedShop(player, credits);   // Trading (D-174), after the settlement
        Regulars.traded(player.server, exchange.city(), exchange.outlet(), player.getUUID(), premiumUnits > 0);   // and the keepers remember who filled their shelf (D-202)
    }
}
