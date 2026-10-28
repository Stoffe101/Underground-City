package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shortage contracts (eleventh pass, D-207): a shop's line that stays short (D-201) for {@link #SHORT_FOR} of active play posts a timed order on the terminals of its city, for the trade
 * that makes it. The order is an ordinary work order with one taker ({@code capacity} 1): taken at a terminal, delivered at a terminal in the shop's city before its deadline, paid once
 * through the ledger's settlement like every other order. Its goods then go onto the shop's real shelf, so the shortage is filled, and the line rests for {@link #COOLDOWN} before it can
 * post again. A contract nobody took lapses at its deadline; one taken and not delivered by then can no longer be delivered (its holder cancels it).
 *
 * <p>Limited: at most one contract a line, {@link #PER_CITY} a city and {@link #TOTAL} in all. Every clock here is the ledger's active clock, which moves only while a real player is online:
 * nothing is posted, nothing lapses and nothing rests while everybody is away. The record lives in the work-order journal, so it survives a restart with the orders it names.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class ShortageContracts {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    static final String PREFIX = UndergroundCity.MOD_ID + ":shortage/";
    /** How long a line must stay short before it posts a contract: five minutes of active play. */
    static final long SHORT_FOR = 6_000;
    /** How long a contract stays open: thirty minutes of active play from its posting. */
    static final long DEADLINE = 36_000;
    /** How long a line rests after its contract is filled or lapses: twenty minutes of active play. */
    static final long COOLDOWN = 24_000;
    /** How long a taken contract's record is kept past its deadline, so its holder is told why it can no longer be delivered. */
    static final long HELD_GRACE = 12_000;
    static final int PER_CITY = 2, TOTAL = 4, CHECK_EVERY = 200;
    /** A contract pays half as much again a unit as the shop (never as much as it charges) and half as much again career XP. */
    static final double PAY = 1.5, XP = 1.5;

    private ShortageContracts() {}

    /** One contract: the line it fills, its city, how many units, when it was posted and when it closes, and whether it was filled. */
    record Contract(String id, String line, String city, int quantity, long posted, long deadline, boolean filled) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("line", line); t.putString("city", city); t.putInt("quantity", quantity); t.putLong("posted", posted); t.putLong("deadline", deadline);
            if (filled) t.putBoolean("filled", true);
            return t;
        }

        static Contract load(String id, CompoundTag t) {
            return new Contract(id, t.getString("line"), t.getString("city"), t.getInt("quantity"), t.getLong("posted"), t.getLong("deadline"), t.getBoolean("filled"));
        }
    }

    static boolean isContract(String orderId) { return orderId != null && orderId.startsWith(PREFIX); }

    static List<Contract> contracts(WorkOrderLedger ledger) {
        CompoundTag all = ledger.shortages().getCompound("contracts");
        List<Contract> out = new ArrayList<>();
        for (String id : all.getAllKeys()) out.add(Contract.load(id, all.getCompound(id)));
        out.sort(Comparator.comparingLong(Contract::deadline).thenComparing(Contract::id));
        return out;
    }

    static Contract contract(WorkOrderLedger ledger, String id) {
        CompoundTag all = ledger.shortages().getCompound("contracts");
        return all.contains(id) ? Contract.load(id, all.getCompound(id)) : null;
    }

    private static void put(WorkOrderLedger ledger, Contract c) {
        CompoundTag shortages = ledger.shortages();
        CompoundTag all = shortages.getCompound("contracts");
        all.put(c.id(), c.save());
        shortages.put("contracts", all);
    }

    /** The order a contract offers, or null when its line, its terms or its item are gone. */
    static WorkOrder order(Contract c) {
        RegionalStock stock = RegionalCatalog.INSTANCE.find(c.line());
        RegionalCatalog.Supply terms = RegionalCatalog.INSTANCE.supply(c.line());
        if (stock == null || terms == null || terms.career().isEmpty() || stock.registryItem() == null || stock.registryItem() == net.minecraft.world.item.Items.AIR) return null;
        long unit = unitPay(stock, terms);
        if (unit <= terms.pays()) return null;   // a line too cheap for a contract to pay more than the shop (three-credit fasteners): no contract
        int credits = (int) Math.max(1, Math.min(1000, unit * c.quantity()));
        int xp = (int) Math.max(1, Math.min(1000, Math.round(terms.xp() * c.quantity() * XP)));
        String title = "Shortage: " + c.quantity() + " " + stock.registryItem().getDescription().getString() + " for " + Shortages.shopTitle(stock);
        if (title.length() > 80) title = title.substring(0, 79) + "…";
        return new WorkOrder(c.id(), title, terms.career(), 1, 0, List.of(new WorkOrder.Requirement(stock.item(), c.quantity())), credits, xp, 1, 72_000, 0, WorkOrder.Terms.DEFAULT);
    }

    /** What a contract pays a unit: half as much again as the shop pays, and always less than the shop charges, so buying from it to deliver never pays. */
    static long unitPay(RegionalStock stock, RegionalCatalog.Supply terms) { return Math.max(1, Math.min(stock.unitPrice() - 1L, Math.round(terms.pays() * PAY))); }

    /** The open contracts a citizen of {@code career} sees on a terminal, soonest deadline first. */
    static List<WorkOrder> offered(WorkOrderLedger ledger, String career) {
        long now = ledger.clock();
        List<WorkOrder> out = new ArrayList<>();
        for (Contract c : contracts(ledger)) {
            if (c.filled() || now >= c.deadline()) continue;
            WorkOrder order = order(c);
            if (order != null && order.openTo(career)) out.add(order);
        }
        return out;
    }

    /** The order of an open contract, to take it; null when it is filled, past its deadline or unknown. */
    static WorkOrder find(WorkOrderLedger ledger, String id) {
        Contract c = contract(ledger, id);
        return c == null || c.filled() || ledger.clock() >= c.deadline() ? null : order(c);
    }

    /** Why a contract's order cannot be delivered at {@code terminal} now, or null (and null for any other order). */
    static String refusal(ServerLevel level, WorkOrderLedger ledger, WorkOrder order, BlockPos terminal) {
        if (!isContract(order.id())) return null;
        Contract c = contract(ledger, order.id());
        if (c == null || c.filled() || ledger.clock() >= c.deadline()) return "This shortage contract ran out of time. Cancel it to free your order.";
        String here = terminal == null ? "" : CityRegions.INSTANCE.cityAt(level, terminal).orElse("");
        if (!here.equals(c.city())) return "Deliver it at a terminal in " + TerminalScreenText.capitalize(c.city()) + ", where the shop is.";
        return null;
    }

    /**
     * After a contract's delivery was settled: the contract is filled, the line rests, and the goods go onto the shop's shelf (never above it). The settlement paid the deliverer; this
     * pays nothing and runs once (a filled contract is left alone).
     */
    static void delivered(ServerPlayer player, WorkOrderLedger ledger, WorkOrder order) {
        if (!isContract(order.id()) || ledger.faulted()) return;
        Contract c = contract(ledger, order.id());
        if (c == null || c.filled()) return;
        long now = ledger.clock();
        put(ledger, new Contract(c.id(), c.line(), c.city(), c.quantity(), c.posted(), c.deadline(), true));
        rest(ledger, c.line(), now);
        RegionalStock stock = RegionalCatalog.INSTANCE.find(c.line());
        int added = stock == null ? 0 : ledger.topUp(stock, c.quantity());   // persists the whole journal, the filled contract with it
        if (added == 0) ledger.persist();
        String shop = stock == null ? "the shop" : Shortages.shopTitle(stock);
        player.sendSystemMessage(Component.literal("The goods go onto the shelf of " + shop + " in " + TerminalScreenText.capitalize(c.city()) + "."));
        CityStanding.earn(player, c.city(), CityStanding.SHORTAGE);   // the city thinks better of whoever filled its shortage (D-204)
        if (stock != null) for (String outlet : stock.outlets()) if (RegionalStock.isShop(outlet)) { Regulars.traded(player.server, c.city(), outlet, player.getUUID(), true); break; }
    }

    private static void rest(WorkOrderLedger ledger, String line, long now) {
        CompoundTag shortages = ledger.shortages();
        CompoundTag cooldown = shortages.getCompound("cooldown"), since = shortages.getCompound("since");
        cooldown.putLong(line, now + COOLDOWN);
        since.remove(line);
        shortages.put("cooldown", cooldown);
        shortages.put("since", since);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CHECK_EVERY != 0) return;
        if (event.getServer().getPlayerList().getPlayers().stream().allMatch(p -> p instanceof FakePlayer)) return;   // the active clock stands still
        WorkOrderLedger ledger = WorkOrderLedger.get(event.getServer());
        if (ledger.faulted()) return;
        try { review(event.getServer(), ledger); }
        catch (RuntimeException error) { LOGGER.warn("[UC] the shortage contracts were not reviewed: {}", error.toString()); }   // never stops the server's tick
    }

    /** Closes what is filled or lapsed and posts what has been short long enough; persists once if anything changed. Returns the contracts posted. */
    static List<Contract> review(MinecraftServer server, WorkOrderLedger ledger) {
        long now = ledger.clock();
        CompoundTag shortages = ledger.shortages();
        CompoundTag since = shortages.getCompound("since"), cooldown = shortages.getCompound("cooldown"), all = shortages.getCompound("contracts");
        boolean changed = false;
        Map<String, Integer> perCity = new HashMap<>();
        java.util.Set<String> lines = new java.util.HashSet<>();
        for (Contract c : contracts(ledger)) {
            boolean close = c.filled();
            if (!close && now >= c.deadline()) {
                WorkOrder order = order(c);
                boolean taken = order != null && ledger.available(order) == 0;
                close = !taken || now >= c.deadline() + HELD_GRACE;
                if (close) cooldown.putLong(c.line(), now + COOLDOWN);
            }
            if (close) {
                all.remove(c.id());
                ledger.forgetDemand(c.id());
                changed = true;
                continue;
            }
            perCity.merge(c.city(), 1, Integer::sum);
            lines.add(c.line());
        }
        int total = perCity.values().stream().mapToInt(Integer::intValue).sum();
        List<Contract> posted = new ArrayList<>();
        List<RegionalStock> stocks = new ArrayList<>(RegionalCatalog.INSTANCE.all());
        stocks.sort(Comparator.comparing(RegionalStock::id));
        for (RegionalStock stock : stocks) {
            RegionalCatalog.Supply terms = RegionalCatalog.INSTANCE.supply(stock.id());
            if (terms == null || terms.career().isEmpty()) continue;
            int units = ledger.regionalUnits(stock);
            if (!Shortages.isShort(stock, units)) {
                if (since.contains(stock.id())) { since.remove(stock.id()); changed = true; }
                continue;
            }
            if (!since.contains(stock.id())) { since.putLong(stock.id(), now); changed = true; continue; }
            if (now - since.getLong(stock.id()) < SHORT_FOR || lines.contains(stock.id()) || cooldown.getLong(stock.id()) > now) continue;
            if (total >= TOTAL || perCity.getOrDefault(stock.city(), 0) >= PER_CITY) continue;
            int quantity = Math.max(2, Math.min(Math.min(stock.maxOrder(), 64), stock.capacity() / 2 - units));
            Contract c = new Contract(PREFIX + stock.id().replace(':', '_').replace('/', '_') + "/" + now, stock.id(), stock.city(), quantity, now, now + DEADLINE, false);
            if (order(c) == null) continue;
            all.put(c.id(), c.save());
            posted.add(c);
            lines.add(stock.id());
            perCity.merge(stock.city(), 1, Integer::sum);
            total++;
            changed = true;
        }
        shortages.put("since", since); shortages.put("cooldown", cooldown); shortages.put("contracts", all);
        if (changed) ledger.persist();
        for (Contract c : posted) announce(server, c);
        return posted;
    }

    /** Tells the players of the line's trade who are in the game, once, when a contract is posted. */
    private static void announce(MinecraftServer server, Contract c) {
        WorkOrder order = order(c);
        if (order == null || server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CitizenState.Citizen citizen = CitizenState.get(server).find(player.getUUID()).orElse(null);
            if (citizen == null || !order.openTo(citizen.career)) continue;
            Notice.send(player, Notice.Kind.ORDER, "Shortage contract", TerminalScreenText.capitalize(c.city()) + ": " + order.title().replaceFirst("^Shortage: ", "") + ", " + order.credits()
                + " credits. At a terminal there, within " + DEADLINE / 1200 + " minutes of play.");
        }
    }

    /** One line a contract for the status command and the notes. */
    static List<String> status(WorkOrderLedger ledger) {
        long now = ledger.clock();
        List<String> out = new ArrayList<>();
        for (Contract c : contracts(ledger)) {
            WorkOrder order = order(c);
            out.add((order == null ? c.line() : order.title()) + " (" + c.city() + "): " + (c.filled() ? "filled" : now >= c.deadline() ? "lapsed" : Math.max(0, (c.deadline() - now) / 1200) + " min left")
                + (order != null && ledger.available(order) == 0 ? ", taken" : ""));
        }
        return out;
    }
}
