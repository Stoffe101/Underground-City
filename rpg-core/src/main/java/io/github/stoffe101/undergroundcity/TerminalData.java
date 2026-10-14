package io.github.stoffe101.undergroundcity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only server snapshot. Actions never echo prices, XP or item counts back. */
public record TerminalData(String city, String career, int level, int xp, double balance,
                           boolean economy, UUID active, int foreignVisits, String region,
                           List<Row> jobs, List<Offer> offers) {
    public record Req(String item, int count, int held) {}
    public record Row(String id, String title, int level, int credits, int xp, int available,
                      boolean accepted, int foreignVisits, List<Req> reqs) {}
    public record Offer(String id, String item, int price, int stock, int capacity, int maxOrder) {}

    static final int MAX_ROWS = 33, MAX_REQS = 4, MAX_OFFERS = 16;

    public static TerminalData offline() {
        return new TerminalData("UNREGISTERED", "NONE", 1, 0, 0, false, null, 0, "", List.of(), List.of());
    }

    static TerminalData snapshot(ServerPlayer player, BlockPos pos) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null) return offline();
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        String region = CityRegions.INSTANCE.cityAt(player.level(), pos).orElse("");
        if (!region.isEmpty()) ledger.noteVisit(player.getUUID(), region); // physically at a placed terminal inside the city
        CompoundTag record = ledger.player(player.getUUID());
        UUID active = record.hasUUID("order") ? record.getUUID("order") : null;
        WorkOrder accepted = null;
        boolean unavailable = false;
        if (active != null) {
            try { accepted = WorkOrder.load(record.getCompound("job")); }
            catch (IllegalArgumentException removedItem) { unavailable = true; } // item/mod removed after acceptance
        }
        final WorkOrder acceptedOrder = accepted;
        List<WorkOrder> jobs = new ArrayList<>(WorkOrderCatalog.INSTANCE.forCareer(citizen.career));
        if (acceptedOrder != null) {
            jobs.removeIf(job -> job.id().equals(acceptedOrder.id())); jobs.add(0, acceptedOrder);
        }
        List<Row> rows = jobs.stream().limit(MAX_ROWS).map(job -> new Row(job.id(), job.title(), job.level(),
            job.credits(), job.xp(), ledger.available(job), acceptedOrder != null && job.id().equals(acceptedOrder.id()),
            job.foreignVisits(), job.requirements().stream().map(req -> new Req(req.item().toString(), req.count(),
                player.getInventory().items.stream().filter(stack -> job.matches(req, stack)).mapToInt(stack -> stack.getCount()).sum())).toList())).toList();
        if (unavailable) {
            // Lets the player cancel an order whose accepted terms can no longer be loaded.
            List<Row> withNotice = new ArrayList<>();
            withNotice.add(new Row("underground_city:unavailable", "Order unavailable - cancel to continue", 1, 0, 0, 0, true, 0, List.of()));
            withNotice.addAll(rows);
            rows = withNotice.stream().limit(MAX_ROWS).toList();
        }
        boolean economy = QShopEconomy.available() && QShopEconomy.configured(player.server) && !ledger.faulted();
        return new TerminalData(citizen.city, citizen.career, citizen.careerLevel(), citizen.careerXp(),
            economy ? QShopEconomy.balance(player) : 0, economy, active, ledger.foreignVisits(player.getUUID(), citizen.city),
            region, rows, RegionalMarket.offers(ledger, region));
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(city, 32); buf.writeUtf(career, 32); buf.writeVarInt(level); buf.writeVarInt(xp);
        buf.writeDouble(balance); buf.writeBoolean(economy); buf.writeBoolean(active != null);
        if (active != null) buf.writeUUID(active);
        buf.writeVarInt(foreignVisits); buf.writeUtf(region, 32);
        buf.writeVarInt(jobs.size());
        for (Row row : jobs) {
            buf.writeUtf(row.id, 128); buf.writeUtf(row.title, 80); buf.writeVarInt(row.level);
            buf.writeVarInt(row.credits); buf.writeVarInt(row.xp); buf.writeVarInt(row.available);
            buf.writeBoolean(row.accepted); buf.writeVarInt(row.foreignVisits);
            buf.writeVarInt(row.reqs.size());
            for (Req req : row.reqs) { buf.writeUtf(req.item, 128); buf.writeVarInt(req.count); buf.writeVarInt(req.held); }
        }
        buf.writeVarInt(offers.size());
        for (Offer offer : offers) {
            buf.writeUtf(offer.id, 128); buf.writeUtf(offer.item, 128); buf.writeVarInt(offer.price);
            buf.writeVarInt(offer.stock); buf.writeVarInt(offer.capacity); buf.writeVarInt(offer.maxOrder);
        }
    }

    static TerminalData read(FriendlyByteBuf buf) {
        String city = buf.readUtf(32), career = buf.readUtf(32);
        int level = buf.readVarInt(), xp = buf.readVarInt(); double balance = buf.readDouble();
        boolean economy = buf.readBoolean(); UUID active = buf.readBoolean() ? buf.readUUID() : null;
        int visits = buf.readVarInt(); String region = buf.readUtf(32);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ROWS) throw new IllegalArgumentException("Invalid job snapshot size");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String id = buf.readUtf(128), title = buf.readUtf(80);
            int rowLevel = buf.readVarInt(), credits = buf.readVarInt(), rowXp = buf.readVarInt(), available = buf.readVarInt();
            boolean accepted = buf.readBoolean(); int rowVisits = buf.readVarInt();
            int reqCount = buf.readVarInt();
            if (reqCount < 0 || reqCount > MAX_REQS) throw new IllegalArgumentException("Invalid requirement count");
            List<Req> reqs = new ArrayList<>();
            for (int r = 0; r < reqCount; r++) reqs.add(new Req(buf.readUtf(128), buf.readVarInt(), buf.readVarInt()));
            rows.add(new Row(id, title, rowLevel, credits, rowXp, available, accepted, rowVisits, List.copyOf(reqs)));
        }
        int offerCount = buf.readVarInt();
        if (offerCount < 0 || offerCount > MAX_OFFERS) throw new IllegalArgumentException("Invalid offer count");
        List<Offer> offers = new ArrayList<>();
        for (int i = 0; i < offerCount; i++) {
            offers.add(new Offer(buf.readUtf(128), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new TerminalData(city, career, level, xp, balance, economy, active, visits, region, List.copyOf(rows), List.copyOf(offers));
    }
}
