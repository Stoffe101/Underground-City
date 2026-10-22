package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Selling stolen goods to a fence (D-125): the one way marked goods ({@link StolenGoods}) turn into credits. A fence is a person in a furnished building of their city, about only at their hours; they
 * buy only goods marked stolen and only what has a worth in {@link FenceCatalog}, at a share of that worth (less, and with more heat, for goods taken in the fence's own city), out of a budget their
 * network refills slowly in active play. The goods are gone when sold: nothing is laundered back into honest goods, and an honest stack is never touched or bought.
 *
 * <p>The sale is a prepared record in the work-order journal ({@link WorkOrderLedger#prepareSale}): the budget, the inventory without the sold goods, the wallet after the price and any heat are decided
 * and written to disk before a single item or credit moves, and recovery replays them once. The client chooses only which line to sell and sends a nonce; every number is the server's.
 */
public final class FenceService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How close the seller must be to the fence. */
    static final double REACH_SQUARED = 4.5 * 4.5;
    /** The most the underground payout skills add to a fence's rate, in points. */
    static final int SKILL_POINTS_MAX = 10;

    private FenceService() {}

    /** One line the fence would buy: an item taken in one city, how many the seller carries, and the price of each. */
    public record Row(String item, String fromCity, int count, int unit) {
        long total() { return (long) count * unit; }
    }

    /** What the fence offers right now. {@code budget} is what the network can still pay; {@code about} is false outside the fence's hours. */
    public record Quote(String city, String title, String blurb, int ratePercent, int sameCityRatePercent, int budget, boolean about, List<Row> rows) {
        long total() { return rows.stream().mapToLong(Row::total).sum(); }
    }

    /** The share of an item's worth this citizen is paid for goods taken in {@code fromCity}, in percent, never above {@link FenceCatalog#MAX_RATE_PERCENT}. */
    static int rate(FenceCatalog.Fence fence, String fromCity, CitizenState.Citizen citizen) {
        int base = fence.city().equals(fromCity) ? fence.sameCityRatePercent() : fence.ratePercent();
        int skill = citizen == null ? 0 : Math.min(SKILL_POINTS_MAX, SkillRules.undergroundPayoutPercent(SkillCatalog.INSTANCE.tree(citizen.career), citizen.skills()));
        return Math.min(FenceCatalog.MAX_RATE_PERCENT, base + skill);
    }

    /** What one unit fetches: the worth times the rate, rounded down, at least 1 for anything a fence buys at all. */
    static int unitPrice(int worth, int ratePercent) { return worth <= 0 ? 0 : Math.max(1, worth * ratePercent / 100); }

    /** The stolen goods a player carries in their own inventory (never a backpack, a home or a container), grouped by item and by the city they were taken in, priced for this fence. */
    static List<Row> rows(Inventory inventory, FenceCatalog.Fence fence, CitizenState.Citizen citizen) {
        Map<String, Row> grouped = new LinkedHashMap<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !StolenGoods.is(stack)) continue;
            String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            int worth = FenceCatalog.INSTANCE.worth(item);
            if (worth <= 0) continue;
            String from = StolenGoods.city(stack);
            String key = item + "|" + from;
            Row before = grouped.get(key);
            int unit = unitPrice(worth, rate(fence, from, citizen));
            grouped.put(key, new Row(item, from, (before == null ? 0 : before.count()) + stack.getCount(), unit));
        }
        return List.copyOf(grouped.values());
    }

    static Quote quote(ServerPlayer player, FenceCatalog.Fence fence) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        int hour = PopulationPlan.hourOf(HouseholdService.dayTime(player.serverLevel()));
        return new Quote(fence.city(), fence.title(), fence.blurb(), rate(fence, "", citizen), rate(fence, fence.city(), citizen), ledger.faulted() ? 0 : ledger.fenceBudget(fence), fence.about(hour), rows(player.getInventory(), fence, citizen));
    }

    /** The fence of a body, when the body is one: a household person with the fence's role, alive, in a city that has a fence network. */
    static FenceCatalog.Fence fenceOf(TownspersonEntity body) {
        if (body == null || !body.isAlive() || !body.role().equals("fence")) return null;
        return FenceCatalog.INSTANCE.forCity(body.city()).orElse(null);
    }

    /** Opens the fence's screen for a player who spoke to them. */
    static void open(ServerPlayer player, TownspersonEntity body) {
        FenceCatalog.Fence fence = fenceOf(body);
        if (fence == null) return;
        FenceNetwork.open(player, body.getId(), quote(player, fence));
    }

    /**
     * Sells the player's stolen {@code item} taken in {@code fromCity} (or everything they carry that the fence buys, for {@code item} "*") to the fence {@code entityId}. As many units as the network's
     * budget covers are sold; a sale the budget cannot cover at all, a fence who is gone or not about, a seller out of reach and a repeated nonce change nothing.
     */
    static void sell(ServerPlayer player, int entityId, String item, String fromCity, UUID nonce) {
        if (!TerminalNetwork.requestSlot(player)) return;
        TownspersonEntity body = player.level().getEntity(entityId) instanceof TownspersonEntity t ? t : null;
        FenceCatalog.Fence fence = fenceOf(body);
        try {
            if (fence == null || player.distanceToSqr(body) > REACH_SQUARED) throw new IllegalStateException("Nobody here buys that.");
            if (player.isCreative() || player.isSpectator()) throw new IllegalStateException("A fence deals only with people in survival.");
            if (!QShopEconomy.available() || !QShopEconomy.configured(player.server)) throw new IllegalStateException("Install Q Shop 1.8.4 to trade.");
            CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElseThrow(() -> new IllegalStateException("Register as a citizen first."));
            WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
            if (ledger.player(player.getUUID()).contains("pending")) WorkOrderService.recover(player, ledger);
            settle(player, ledger, citizen, fence, item, fromCity, nonce, PopulationPlan.hourOf(HouseholdService.dayTime(player.serverLevel())));
        } catch (RuntimeException error) {
            player.sendSystemMessage(Component.literal(error.getMessage() == null ? "The sale fell through." : error.getMessage()));
            WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
            if (ledger.faulted() || ledger.player(player.getUUID()).contains("pending")) {
                LOGGER.error("Fence sale recovery required for {}", player.getUUID(), error);
                player.connection.disconnect(Component.literal("A sale was interrupted. Reconnect for recovery; restart the server if storage failed."));
                return;
            }
        }
        if (fence != null && body != null && body.isAlive()) FenceNetwork.open(player, entityId, quote(player, fence));
    }

    /** The sale itself, once the fence and the seller are known to be where they should be. Visible for the GameTests. */
    static long settle(ServerPlayer player, WorkOrderLedger ledger, CitizenState.Citizen citizen, FenceCatalog.Fence fence, String item, String fromCity, UUID nonce, int hour) {
        if (!fence.about(hour)) throw new IllegalStateException("The fence is not dealing at this hour.");
        if (ledger.hasSale(player.getUUID(), nonce)) throw new IllegalStateException("That sale was already made.");
        List<Row> rows = rows(player.getInventory(), fence, citizen);
        List<Row> chosen = new ArrayList<>();
        for (Row r : rows) if (item.equals("*") || (r.item().equals(item) && r.fromCity().equals(fromCity))) chosen.add(r);
        if (chosen.isEmpty()) throw new IllegalStateException("You are not carrying anything this fence buys. Goods in a backpack must be taken out first.");
        int budget = ledger.fenceBudget(fence);
        // as many units as the budget covers, line by line; a line the budget only partly covers is sold in part
        Map<String, Integer> units = new LinkedHashMap<>();
        long credits = 0;
        boolean home = false;
        for (Row r : chosen) {
            int n = (int) Math.min(r.count(), (budget - credits) / r.unit());
            if (n <= 0) continue;
            units.put(r.item() + "|" + r.fromCity(), n);
            credits += (long) n * r.unit();
            home |= r.fromCity().equals(fence.city());
        }
        if (credits <= 0) throw new IllegalStateException("The fence cannot find the credits for that right now. Their network pays out a little at a time; come back after a while of play.");
        // plan on a COPY: only stolen stacks of the sold lines lose units
        Inventory after = new Inventory(player);
        after.load(player.getInventory().save(new ListTag()));
        Map<String, Integer> left = new LinkedHashMap<>(units);
        for (int i = 0; i < after.getContainerSize(); i++) {
            ItemStack stack = after.getItem(i);
            if (stack.isEmpty() || !StolenGoods.is(stack)) continue;
            String key = BuiltInRegistries.ITEM.getKey(stack.getItem()) + "|" + StolenGoods.city(stack);
            Integer want = left.get(key);
            if (want == null || want <= 0) continue;
            int take = Math.min(want, stack.getCount());
            stack.shrink(take);
            left.put(key, want - take);
        }
        if (left.values().stream().anyMatch(n -> n > 0)) throw new IllegalStateException("Your pockets changed; open the fence again.");
        double balance = QShopEconomy.balance(player);
        Map<String, Integer> heat = home && fence.sameCityHeat() > 0 ? Map.of(fence.city(), ledger.heatAfter(player.getUUID(), fence.city(), fence.sameCityHeat())) : Map.of();
        ledger.prepareSale(player.getUUID(), nonce, fence, credits, after.save(new ListTag()), balance + credits, citizen.careerXp(), heat);
        WorkOrderService.recover(player, ledger);
        CitizenState.get(player.server).bumpJourney(player.getUUID(), "fenced");
        int sold = units.values().stream().mapToInt(Integer::intValue).sum();
        player.sendSystemMessage(Component.literal("Sold " + sold + " stolen item" + (sold == 1 ? "" : "s") + " to " + fence.title() + " for " + credits + " credits."
            + (home ? " Selling " + TerminalScreenText.capitalize(fence.city()) + "'s own goods back in " + TerminalScreenText.capitalize(fence.city()) + " gets noticed." : "")));
        Notice.send(player, home ? Notice.Kind.HEAT : Notice.Kind.LOOT, "Sold to the fence", credits + " credits for " + sold + " item" + (sold == 1 ? "" : "s") + (home ? ". Heat rising." : "."));
        return credits;
    }
}
