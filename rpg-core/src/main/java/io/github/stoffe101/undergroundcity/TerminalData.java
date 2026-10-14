package io.github.stoffe101.undergroundcity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only server snapshot. Actions never echo prices, XP or item counts back. */
public record TerminalData(String city, String career, int level, int xp, double balance,
                           boolean economy, UUID active, List<Row> jobs) {
    public record Row(String id, String title, String item, int count, int held,
                      int level, int credits, int xp, int available, boolean accepted) {}

    public static TerminalData offline() {
        return new TerminalData("UNREGISTERED", "NONE", 1, 0, 0, false, null, List.of());
    }

    static TerminalData snapshot(ServerPlayer player) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null) return offline();
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        CompoundTag record = ledger.player(player.getUUID());
        UUID active = record.hasUUID("order") ? record.getUUID("order") : null;
        WorkOrder accepted = active == null ? null : WorkOrder.load(record.getCompound("job"));
        List<WorkOrder> jobs = new ArrayList<>(WorkOrderCatalog.INSTANCE.forCareer(citizen.career));
        if (accepted != null) {
            jobs.removeIf(job -> job.id().equals(accepted.id())); jobs.add(0, accepted);
        }
        List<Row> rows = jobs.stream().map(job -> {
            int held = player.getInventory().items.stream().filter(job::matches).mapToInt(stack -> stack.getCount()).sum();
            return new Row(job.id(), job.title(), job.item().toString(), job.count(), held, job.level(),
                job.credits(), job.xp(), ledger.available(job), accepted != null && job.id().equals(accepted.id()));
        }).toList();
        boolean economy = QShopEconomy.available() && QShopEconomy.configured(player.server) && !ledger.faulted();
        return new TerminalData(citizen.city, citizen.career, citizen.careerLevel(), citizen.careerXp(),
            economy ? QShopEconomy.balance(player) : 0, economy, active, rows);
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(city, 32); buf.writeUtf(career, 32); buf.writeVarInt(level); buf.writeVarInt(xp);
        buf.writeDouble(balance); buf.writeBoolean(economy); buf.writeBoolean(active != null);
        if (active != null) buf.writeUUID(active);
        buf.writeVarInt(jobs.size());
        for (Row row : jobs) {
            buf.writeUtf(row.id, 128); buf.writeUtf(row.title, 80); buf.writeUtf(row.item, 128);
            buf.writeVarInt(row.count); buf.writeVarInt(row.held); buf.writeVarInt(row.level);
            buf.writeVarInt(row.credits); buf.writeVarInt(row.xp); buf.writeVarInt(row.available); buf.writeBoolean(row.accepted);
        }
    }

    static TerminalData read(FriendlyByteBuf buf) {
        String city = buf.readUtf(32), career = buf.readUtf(32);
        int level = buf.readVarInt(), xp = buf.readVarInt(); double balance = buf.readDouble();
        boolean economy = buf.readBoolean(); UUID active = buf.readBoolean() ? buf.readUUID() : null;
        int size = buf.readVarInt();
        if (size < 0 || size > 33) throw new IllegalArgumentException("Invalid job snapshot size");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            rows.add(new Row(buf.readUtf(128), buf.readUtf(80), buf.readUtf(128), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        return new TerminalData(city, career, level, xp, balance, economy, active, List.copyOf(rows));
    }
}
