package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Small synchronous write-ahead journal for two players. A prepared record holds
 * absolute inventory/wallet/XP targets until both playerdata and citizen data are
 * saved. Recovery runs before the player can interact. No second wallet lives here.
 */
public final class WorkOrderLedger {
    private static final String PURCHASE = "purchase";
    private static final Map<MinecraftServer, WorkOrderLedger> INSTANCES = new WeakHashMap<>();
    private final Path path;
    private CompoundTag root;
    private boolean faulted;

    public static WorkOrderLedger get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, key -> new WorkOrderLedger(
            key.getWorldPath(LevelResource.ROOT).resolve("data/underground_city_work_orders.dat")));
    }

    WorkOrderLedger(Path path) {
        this.path = path;
        try {
            root = Files.exists(path) ? NbtIo.read(path.toFile()) : new CompoundTag();
            if (root == null) throw new IOException("Empty work-order journal");
            if (root.contains("schema_version") && root.getInt("schema_version") != 1) {
                throw new IOException("Unsupported work-order schema");
            }
            root.putInt("schema_version", 1);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read work-order journal; restore backup", error);
        }
    }

    CompoundTag player(UUID id) { return root.getCompound("players").getCompound(id.toString()); }
    long clock() { return root.getLong("active_ticks"); }
    boolean faulted() { return faulted; }

    void advance(long ticks) {
        if (ticks < 0) throw new IllegalArgumentException("Negative active time");
        root.putLong("active_ticks", Math.addExact(clock(), ticks));
    }

    int available(WorkOrder order) {
        CompoundTag stocks = root.getCompound("demand");
        CompoundTag stock = stocks.getCompound(order.id());
        long now = clock();
        int units = stock.contains("units") ? Math.min(order.capacity(), stock.getInt("units")) : order.capacity();
        long last = stock.contains("last_tick") ? stock.getLong("last_tick") : now;
        if (units < 0 || last > now) throw new IllegalStateException("Invalid saved employer demand");
        if (units == order.capacity()) last = now; // full stock cannot bank a hidden backlog
        long intervals = (now - last) / order.refillTicks();
        if (intervals > 0) {
            units = (int) Math.min(order.capacity(), units + intervals);
            last = units == order.capacity() ? now : last + intervals * order.refillTicks();
        }
        stock.putInt("units", units); stock.putLong("last_tick", last);
        stocks.put(order.id(), stock); root.put("demand", stocks);
        return units;
    }

    UUID accept(UUID player, WorkOrder order) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.hasUUID("order") || record.contains("pending")) {
            throw new IllegalStateException("Finish or cancel your current order first");
        }
        if (available(order) == 0) throw new IllegalStateException("Employer demand is full; return after active play");
        CompoundTag stock = root.getCompound("demand").getCompound(order.id());
        stock.putInt("units", stock.getInt("units") - 1);
        UUID nonce = UUID.randomUUID();
        record.putUUID("order", nonce);
        record.put("job", order.save()); // accepted terms survive reloads and datapack edits
        putPlayer(player, record);
        persist();
        return nonce;
    }

    void cancel(UUID player, UUID nonce) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending") || !record.hasUUID("order") || !record.getUUID("order").equals(nonce)) {
            throw new IllegalStateException("Order has changed or was already completed; refresh the terminal");
        }
        try {
            WorkOrder order = WorkOrder.load(record.getCompound("job"));
            int units = available(order);
            root.getCompound("demand").getCompound(order.id()).putInt("units", Math.min(order.capacity(), units + 1));
        } catch (IllegalArgumentException unavailable) {
            // The accepted terms reference an item or schema that no longer exists (mod/datapack removed).
            // Free the player without touching shared employer demand.
        }
        record.remove("order"); record.remove("job");
        putPlayer(player, record); persist();
    }

    WorkOrder checkActive(UUID player, UUID nonce) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending") || !record.hasUUID("order") || !record.getUUID("order").equals(nonce)) {
            throw new IllegalStateException("Order has changed or was already completed; refresh the terminal");
        }
        return WorkOrder.load(record.getCompound("job"));
    }

    void prepare(UUID player, UUID nonce, ListTag inventoryAfter, double balanceAfter, int xpAfter) {
        checkActive(player, nonce);
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0 || xpAfter < 0) throw new IllegalArgumentException("Invalid settlement");
        CompoundTag record = player(player);
        CompoundTag pending = new CompoundTag();
        pending.put("inventory", inventoryAfter.copy()); pending.putDouble("balance", balanceAfter);
        pending.putInt("xp", xpAfter);
        record.put("pending", pending);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    void settle(UUID player) {
        checkHealthy();
        CompoundTag record = player(player);
        if (!record.contains("pending")) throw new IllegalStateException("No prepared settlement");
        CompoundTag pending = record.getCompound("pending");
        if (PURCHASE.equals(pending.getString("kind"))) {
            // A regional purchase never touches an accepted order or career XP.
            record.putInt("purchases", Math.addExact(record.getInt("purchases"), 1));
            record.remove("pending");
        } else {
            record.putInt("earned_xp_target", pending.getInt("xp"));
            record.putInt("completed", Math.addExact(record.getInt("completed"), 1));
            record.remove("pending"); record.remove("order"); record.remove("job");
        }
        putPlayer(player, record); persist();
    }

    // ---------------------------------------------------------------- regional exchange

    /** Replenished shared units for a supplier line. Does not persist by itself. */
    int regionalUnits(RegionalStock stock) {
        CompoundTag stocks = root.getCompound("regional");
        CompoundTag state = stocks.getCompound(stock.id());
        long now = clock();
        int units = state.contains("units") ? Math.min(stock.capacity(), state.getInt("units")) : stock.initial();
        long last = state.contains("last_tick") ? state.getLong("last_tick") : now;
        if (units < 0 || last > now) throw new IllegalStateException("Invalid saved supplier stock");
        if (units >= stock.capacity()) last = now; // a full shelf cannot bank a hidden backlog
        long intervals = (now - last) / stock.intervalTicks();
        if (intervals > 0) {
            long gained = Math.multiplyExact(intervals, (long) stock.unitsPerInterval());
            units = (int) Math.min(stock.capacity(), units + gained);
            last = units >= stock.capacity() ? now : last + intervals * stock.intervalTicks();
        }
        state.putInt("units", units); state.putLong("last_tick", last);
        stocks.put(stock.id(), state); root.put("regional", stocks);
        return units;
    }

    /** Idempotently records that a registered citizen used an exchange inside {@code city}. */
    boolean noteVisit(UUID player, String city) {
        if (faulted || !ProgressionRules.validCity(city)) return false;
        CompoundTag record = player(player);
        ListTag visited = record.getList("visited", Tag.TAG_STRING);
        for (int i = 0; i < visited.size(); i++) if (visited.getString(i).equals(city)) return false;
        visited.add(net.minecraft.nbt.StringTag.valueOf(city));
        record.put("visited", visited);
        putPlayer(player, record); persist();
        return true;
    }

    int foreignVisits(UUID player, String homeCity) {
        ListTag visited = player(player).getList("visited", Tag.TAG_STRING);
        int count = 0;
        for (int i = 0; i < visited.size(); i++) if (!visited.getString(i).equals(homeCity)) count++;
        return count;
    }

    boolean hasPurchase(UUID player, UUID purchase) {
        ListTag recent = player(player).getList("recent_purchases", Tag.TAG_STRING);
        for (int i = 0; i < recent.size(); i++) if (recent.getString(i).equals(purchase.toString())) return true;
        return false;
    }

    /**
     * Durably commits the shared-stock decrement, the purchase nonce and the absolute
     * inventory/wallet targets together, BEFORE the live player or Q Shop wallet changes.
     */
    void preparePurchase(UUID player, UUID purchase, RegionalStock stock, int quantity,
                         ListTag inventoryAfter, double balanceAfter, int xpNow) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Finish recovery before buying");
        if (hasPurchase(player, purchase)) throw new IllegalStateException("Purchase was already processed");
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0 || xpNow < 0) throw new IllegalArgumentException("Invalid settlement");
        int units = regionalUnits(stock);
        if (quantity < 1 || quantity > stock.maxOrder() || quantity > units) {
            throw new IllegalStateException("Supplier stock changed; refresh the terminal");
        }
        root.getCompound("regional").getCompound(stock.id()).putInt("units", units - quantity);
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", PURCHASE); pending.putString("commodity", stock.id()); pending.putInt("quantity", quantity);
        pending.put("inventory", inventoryAfter.copy()); pending.putDouble("balance", balanceAfter);
        pending.putInt("xp", xpNow);
        record.put("pending", pending);
        ListTag recent = record.getList("recent_purchases", Tag.TAG_STRING);
        recent.add(net.minecraft.nbt.StringTag.valueOf(purchase.toString()));
        while (recent.size() > 8) recent.remove(0);
        record.put("recent_purchases", recent);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    private void putPlayer(UUID id, CompoundTag value) {
        CompoundTag players = root.getCompound("players");
        players.put(id.toString(), value); root.put("players", players);
    }

    private void checkHealthy() {
        if (faulted) throw new IllegalStateException("Work-order storage failed; server restart and recovery required");
    }

    void persist() {
        checkHealthy();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
                NbtIo.write(root, out); out.flush(); channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(path.getParent());
        } catch (IOException error) {
            faulted = true; // do not continue after an uncertain durable write
            throw new IllegalStateException("Cannot durably save work orders", error);
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        // Java cannot open Windows directory handles as FileChannels. Keep file
        // fsync + atomic replacement there; POSIX also syncs the parent entry.
        if (System.getProperty("os.name", "").startsWith("Windows")) return;
        try (FileChannel parent = FileChannel.open(directory, StandardOpenOption.READ)) {
            parent.force(true);
        }
    }
}
