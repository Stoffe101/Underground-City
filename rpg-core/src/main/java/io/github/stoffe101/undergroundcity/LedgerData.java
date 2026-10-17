package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** What the Public Ledger shows: the legal postings of every trade, read-only. Built by the server from the catalog and the ledger of demand. */
public record LedgerData(String city, String region, String career, int level, List<Posting> postings) {
    /** One posting. {@code demand} is the share of the order still open (0 when sold out), {@code capacity} what a full board holds. */
    public record Posting(String career, String title, int level, int credits, int xp, int demand, int capacity, boolean visits) {}

    static final int MAX_POSTINGS = 40;

    static LedgerData snapshot(ServerPlayer player, BlockPos pos) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        String region = CityRegions.INSTANCE.cityAt(player.level(), pos).orElse("");
        List<Posting> postings = new ArrayList<>();
        for (WorkOrder order : WorkOrderCatalog.INSTANCE.all().stream()
            .sorted(java.util.Comparator.comparing(WorkOrder::career).thenComparingInt(WorkOrder::level).thenComparing(WorkOrder::id)).toList()) {
            if (order.underground()) continue; // underground work is never posted in public
            postings.add(new Posting(order.career(), order.title(), order.level(), order.credits(), order.xp(), ledger.available(order), order.capacity(), order.foreignVisits() > 0));
        }
        return new LedgerData(region, region, citizen == null ? "" : citizen.career, citizen == null ? 0 : citizen.careerLevel(),
            postings.stream().limit(MAX_POSTINGS).toList());
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(city, 32); buf.writeUtf(region, 32); buf.writeUtf(career, 32); buf.writeVarInt(level);
        buf.writeVarInt(postings.size());
        for (Posting p : postings) {
            buf.writeUtf(p.career, 32); buf.writeUtf(p.title, 80); buf.writeVarInt(p.level); buf.writeVarInt(p.credits); buf.writeVarInt(p.xp);
            buf.writeVarInt(p.demand); buf.writeVarInt(p.capacity); buf.writeBoolean(p.visits);
        }
    }

    static LedgerData read(FriendlyByteBuf buf) {
        String city = buf.readUtf(32), region = buf.readUtf(32), career = buf.readUtf(32);
        int level = buf.readVarInt();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_POSTINGS) throw new IllegalArgumentException("Invalid ledger size");
        List<Posting> postings = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            postings.add(new Posting(buf.readUtf(32), buf.readUtf(80), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        return new LedgerData(city, region, career, level, List.copyOf(postings));
    }
}
