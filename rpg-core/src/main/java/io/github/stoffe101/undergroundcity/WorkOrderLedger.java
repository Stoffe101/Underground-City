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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Small synchronous write-ahead journal for two players. A prepared record holds
 * absolute inventory/wallet/XP targets until both playerdata and citizen data are
 * saved. Recovery runs before the player can interact. No second wallet lives here.
 */
public final class WorkOrderLedger {
    private static final String PURCHASE = "purchase", SEIZURE = "seizure", SALE = "sale", ENCOUNTER = "encounter", SUPPLY = "supply", CREW_FILL = "crew_fill", CREW_BONUS = "crew_bonus";
    /** Who brought a Crew contract part that an NPC supplier filled (D-170). */
    static final String NPC_FILL = "npc";
    /** How many searches a person's history keeps (the latest ones; who was searched where, what was found and what it cost). */
    static final int SEARCH_HISTORY = 12;
    /** One heat step cools every five minutes of active play. */
    static final long HEAT_DECAY_TICKS = 6000;
    static final int MAX_HEAT = 8;
    /** A checkpoint searches the same traveller at most once per ten active minutes. */
    static final long CHECKPOINT_COOLDOWN_TICKS = 12000;
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
        prepare(player, nonce, inventoryAfter, balanceAfter, xpAfter, Map.of(), Map.of());
    }

    /**
     * Delivery settlement with ledger-side consequences. {@code trustAfter} (buyer to absolute trust) and
     * {@code heatAfter} (city to absolute heat) are written into the same prepared record as the inventory,
     * wallet and XP targets, so a crash replays all of them once and never twice.
     */
    void prepare(UUID player, UUID nonce, ListTag inventoryAfter, double balanceAfter, int xpAfter,
                 Map<String, Integer> trustAfter, Map<String, Integer> heatAfter) {
        prepare(player, nonce, inventoryAfter, balanceAfter, xpAfter, trustAfter, heatAfter, new CompoundTag());
    }

    /** As above, with {@code extra} fields written into the same prepared record (a Crew contract part, D-170: its settlement records the part against the Crew). */
    void prepare(UUID player, UUID nonce, ListTag inventoryAfter, double balanceAfter, int xpAfter,
                 Map<String, Integer> trustAfter, Map<String, Integer> heatAfter, CompoundTag extra) {
        checkActive(player, nonce);
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0 || xpAfter < 0) throw new IllegalArgumentException("Invalid settlement");
        CompoundTag record = player(player);
        CompoundTag pending = new CompoundTag();
        pending.put("inventory", inventoryAfter.copy()); pending.putDouble("balance", balanceAfter);
        pending.putInt("xp", xpAfter);
        putTargets(pending, trustAfter, heatAfter);
        for (String key : extra.getAllKeys()) pending.put(key, extra.get(key).copy());
        record.put("pending", pending);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    private static void putTargets(CompoundTag pending, Map<String, Integer> trustAfter, Map<String, Integer> heatAfter) {
        CompoundTag trust = new CompoundTag(), heat = new CompoundTag();
        trustAfter.forEach(trust::putInt);
        heatAfter.forEach(heat::putInt);
        if (!trust.isEmpty()) pending.put("trust", trust);
        if (!heat.isEmpty()) pending.put("heat", heat);
    }

    /** Applies the absolute trust and heat targets of a prepared record. Part of settle(), never called alone. */
    private void applyTargets(CompoundTag record, CompoundTag pending) {
        CompoundTag trust = record.getCompound("trust");
        CompoundTag target = pending.getCompound("trust");
        for (String buyer : target.getAllKeys()) trust.putInt(buyer, Math.max(0, Math.min(BuyerCatalog.MAX_TRUST, target.getInt(buyer))));
        if (!trust.isEmpty()) record.put("trust", trust);
        CompoundTag heat = record.getCompound("heat");
        CompoundTag heatTarget = pending.getCompound("heat");
        for (String city : heatTarget.getAllKeys()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("v", Math.max(0, Math.min(MAX_HEAT, heatTarget.getInt(city)))); entry.putLong("t", clock());
            heat.put(city, entry);
        }
        if (!heat.isEmpty()) record.put("heat", heat);
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
        } else if (SALE.equals(pending.getString("kind"))) {
            // A sale to a fence never touches an accepted order or career XP; it may warm the city the goods were taken in.
            record.putInt("sales", Math.addExact(record.getInt("sales"), 1));
            record.putLong("fenced_credits", Math.addExact(record.getLong("fenced_credits"), pending.getLong("credits")));
            applyTargets(record, pending);
            record.remove("pending");
        } else if (SUPPLY.equals(pending.getString("kind"))) {
            // Goods sold to a shop (D-164) never touch an accepted order; the supplier's career XP was restored by the recovery that settles it.
            record.putInt("supplies", Math.addExact(record.getInt("supplies"), 1));
            record.putLong("supply_credits", Math.addExact(record.getLong("supply_credits"), pending.getLong("credits")));
            record.remove("pending");
        } else if (ENCOUNTER.equals(pending.getString("kind"))) {
            // A roadside encounter (D-139) never touches an accepted order; its career XP was restored by the recovery that settles it.
            record.putInt("encounters", Math.addExact(record.getInt("encounters"), 1));
            record.putLong("encounter_credits", Math.addExact(record.getLong("encounter_credits"), pending.getLong("credits")));
            applyTargets(record, pending);
            record.remove("pending");
        } else if (CREW_FILL.equals(pending.getString("kind"))) {
            // A supplier filled a Crew contract part (D-170): the price left the wallet; the part is recorded, and the contract may be complete
            record.putInt("crew_fills", Math.addExact(record.getInt("crew_fills"), 1));
            recordCrewPart(player, record, pending, NPC_FILL);
            record.remove("pending");
        } else if (CREW_BONUS.equals(pending.getString("kind"))) {
            // An owed share of a finished Crew contract reached the wallet
            long credits = pending.getLong("credits");
            record.putLong("crew_bonus_owed", Math.max(0, record.getLong("crew_bonus_owed") - credits));
            record.putLong("crew_bonus_credits", Math.addExact(record.getLong("crew_bonus_credits"), credits));
            record.remove("pending");
        } else if (SEIZURE.equals(pending.getString("kind"))) {
            // A seizure never touches an accepted order or career XP either; it only cools or heats a city.
            record.putInt("seizures", Math.addExact(record.getInt("seizures"), 1));
            if (pending.contains("search", Tag.TAG_COMPOUND)) {
                ListTag history = record.getList("searches", Tag.TAG_COMPOUND);
                CompoundTag entry = pending.getCompound("search").copy();
                entry.putLong("t", clock());
                history.add(entry);
                while (history.size() > SEARCH_HISTORY) history.remove(0);
                record.put("searches", history);
            }
            applyTargets(record, pending);
            record.remove("pending");
        } else {
            applyTargets(record, pending);
            record.putInt("earned_xp_target", pending.getInt("xp"));
            record.putInt("completed", Math.addExact(record.getInt("completed"), 1));
            if (pending.hasUUID("crew")) recordCrewPart(player, record, pending, player.toString());
            if (pending.contains("civic")) recordCivic(player, pending);
            record.remove("pending"); record.remove("order"); record.remove("job");
        }
        putPlayer(player, record); persist();
    }

    // ---------------------------------------------------------------- raids (D-173)

    /** The player's raid record: the nonce of the order it belongs to, the store it names and whether its prize was taken (stale once that order is settled or cancelled). */
    CompoundTag raid(UUID player) { return player(player).getCompound("raid"); }

    void putRaid(UUID player, CompoundTag raid) {
        checkHealthy();
        CompoundTag record = player(player);
        record.put("raid", raid.copy());
        putPlayer(player, record); persist();
    }

    // ---------------------------------------------------------------- civic projects (D-171)

    /** The shared record of a civic project: how many contributions are in, by whom, whether it is finished and whether its change is built in the world. */
    CompoundTag civic(String project) { return root.getCompound("civic").getCompound(project); }

    private void putCivic(String project, CompoundTag value) {
        CompoundTag all = root.getCompound("civic");
        all.put(project, value);
        root.put("civic", all);
    }

    /** Part of a settlement: counts a contribution, and finishes the project at its goal. Never called alone. */
    private void recordCivic(UUID player, CompoundTag pending) {
        String project = pending.getString("civic");
        int goal = Math.max(1, pending.getInt("civic_goal"));
        CompoundTag state = civic(project);
        if (state.getBoolean("finished")) return;   // refused before anything was taken; a replay changes nothing
        state.putInt("count", state.getInt("count") + 1);
        CompoundTag by = state.getCompound("by");
        by.putInt(player.toString(), by.getInt(player.toString()) + 1);
        state.put("by", by);
        if (state.getInt("count") >= goal) { state.putBoolean("finished", true); state.putLong("finished_at", clock()); }
        putCivic(project, state);
    }

    /** Records that a finished project's change now stands in the world, with where (so it is built once, across restarts). */
    void markCivicBuilt(String project, CompoundTag where) {
        checkHealthy();
        CompoundTag state = civic(project);
        state.putBoolean("built", true);
        state.put("where", where.copy());
        putCivic(project, state);
        persist();
    }

    // ---------------------------------------------------------------- Crew contracts (D-170)

    private CompoundTag crewContract(UUID crew, String contract) {
        return root.getCompound("crew_contracts").getCompound(crew.toString()).getCompound(contract);
    }

    private void putCrewContract(UUID crew, String contract, CompoundTag value) {
        CompoundTag all = root.getCompound("crew_contracts");
        CompoundTag mine = all.getCompound(crew.toString());
        mine.put(contract, value);
        all.put(crew.toString(), mine);
        root.put("crew_contracts", all);
    }

    /** Who brought this part of the Crew's contract in its open cycle (a player's id, {@link #NPC_FILL}), or null. */
    String crewPartBy(UUID crew, String contract, String part) {
        CompoundTag parts = crewContract(crew, contract).getCompound("parts");
        return parts.contains(part) ? parts.getString(part) : null;
    }

    /** The active clock at which a contract the Crew finished opens again (0: open). */
    long crewOpensAt(UUID crew, String contract) { return crewContract(crew, contract).getLong("opens_at"); }

    /** How many times the Crew has finished the contract. */
    int crewCompletions(UUID crew, String contract) { return crewContract(crew, contract).getInt("cycle"); }

    /** Credits of finished Crew contracts owed to this player and not yet in their wallet. */
    long crewBonusOwed(UUID player) { return player(player).getLong("crew_bonus_owed"); }

    /**
     * Part of a settlement: records the part the prepared record names against its Crew, and when that was the last part, owes the bonus in equal shares to the members who
     * delivered parts (the remainder to whoever completed it, or the first of them) and opens the next cycle after the cooldown. Never called alone.
     */
    private void recordCrewPart(UUID player, CompoundTag record, CompoundTag pending, String who) {
        String[] ref = CrewContracts.split(pending.getString("crew_part"));
        if (ref == null || !pending.hasUUID("crew")) return;
        UUID crew = pending.getUUID("crew");
        CompoundTag state = crewContract(crew, ref[0]);
        CompoundTag parts = state.getCompound("parts");
        if (parts.contains(ref[1])) return;   // never twice (the delivery was refused before anything was taken; this keeps a replay harmless too)
        parts.putString(ref[1], who);
        state.put("parts", parts);
        ListTag all = pending.getList("crew_parts", Tag.TAG_STRING);
        boolean complete = !all.isEmpty();
        for (int i = 0; i < all.size(); i++) if (!parts.contains(all.getString(i))) complete = false;
        if (complete) {
            List<UUID> contributors = new java.util.ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                String by = parts.getString(all.getString(i));
                if (!by.equals(NPC_FILL)) {
                    UUID id = UUID.fromString(by);
                    if (!contributors.contains(id)) contributors.add(id);
                }
            }
            long bonus = Math.max(0, pending.getInt("crew_bonus"));
            if (!contributors.isEmpty() && bonus > 0) {
                long share = bonus / contributors.size(), rest = bonus - share * contributors.size();
                UUID getsRest = contributors.contains(player) ? player : contributors.get(0);
                for (UUID id : contributors) {
                    CompoundTag target = id.equals(player) ? record : player(id);
                    target.putLong("crew_bonus_owed", Math.addExact(target.getLong("crew_bonus_owed"), share + (id.equals(getsRest) ? rest : 0)));
                    if (!id.equals(player)) putPlayer(id, target);
                }
            }
            state.putInt("cycle", state.getInt("cycle") + 1);
            state.putLong("opens_at", Math.addExact(clock(), Math.max(0, pending.getLong("crew_cooldown"))));
            state.put("parts", new CompoundTag());
            state.putLong("completed_at", clock());
        }
        putCrewContract(crew, ref[0], state);
    }

    /** The prepared record of a supplier filling a Crew contract part: the price leaves the wallet (absolute target); the inventory and XP stay as they are. */
    void prepareCrewFill(UUID player, ListTag inventoryNow, double balanceAfter, int xpNow, long price, CompoundTag extra) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Another settlement is pending; try again");
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0) throw new IllegalArgumentException("Invalid settlement");
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", CREW_FILL);
        pending.put("inventory", inventoryNow.copy()); pending.putDouble("balance", balanceAfter); pending.putInt("xp", xpNow); pending.putLong("credits", price);
        for (String key : extra.getAllKeys()) pending.put(key, extra.get(key).copy());
        record.put("pending", pending);
        putPlayer(player, record); persist();
    }

    /** The prepared record of an owed Crew bonus share reaching the wallet (absolute target). */
    void prepareCrewBonus(UUID player, ListTag inventoryNow, double balanceAfter, int xpNow, long credits) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Another settlement is pending; try again");
        if (credits <= 0 || credits > record.getLong("crew_bonus_owed") || !Double.isFinite(balanceAfter) || balanceAfter < 0) throw new IllegalArgumentException("Invalid bonus settlement");
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", CREW_BONUS);
        pending.put("inventory", inventoryNow.copy()); pending.putDouble("balance", balanceAfter); pending.putInt("xp", xpNow); pending.putLong("credits", credits);
        record.put("pending", pending);
        putPlayer(player, record); persist();
    }

    // ---------------------------------------------------------------- trust, heat and inspections

    int trust(UUID player, String buyer) {
        return Math.max(0, Math.min(BuyerCatalog.MAX_TRUST, player(player).getCompound("trust").getInt(buyer)));
    }

    /** City heat after lazy cooling: one step per {@link #HEAT_DECAY_TICKS} of active play. */
    int heat(UUID player, String city) {
        CompoundTag entry = player(player).getCompound("heat").getCompound(city);
        int value = entry.getInt("v");
        if (value <= 0) return 0;
        long steps = Math.max(0, (clock() - entry.getLong("t")) / HEAT_DECAY_TICKS);
        return (int) Math.max(0, value - steps);
    }

    int heatAfter(UUID player, String city, int delta) { return Math.max(0, Math.min(MAX_HEAT, heat(player, city) + delta)); }

    /** Heat from an event that changes no inventory or wallet (a tripped alarm): applied and saved immediately. */
    void addHeat(UUID player, String city, int delta) {
        checkHealthy();
        if (!ProgressionRules.validCity(city)) return;
        int value = heatAfter(player, city, delta);
        CompoundTag record = player(player);
        CompoundTag heat = record.getCompound("heat");
        CompoundTag entry = new CompoundTag();
        entry.putInt("v", value); entry.putLong("t", clock());
        heat.put(city, entry);
        record.put("heat", heat);
        putPlayer(player, record); persist();
    }

    /**
     * Claims the next inspection roll at a checkpoint. Returns the roll number, or -1 while this traveller
     * is still inside the cooldown from the last search there (relogging cannot re-roll an inspection).
     */
    long claimInspectionRoll(UUID player, String checkpoint) {
        checkHealthy();
        CompoundTag record = player(player);
        CompoundTag rolls = record.getCompound("rolls");
        if (rolls.contains(checkpoint) && clock() - rolls.getLong(checkpoint) < CHECKPOINT_COOLDOWN_TICKS) return -1;
        rolls.putLong(checkpoint, clock());
        record.put("rolls", rolls);
        long number = record.getLong("roll_counter") + 1;
        record.putLong("roll_counter", number);
        putPlayer(player, record); persist();
        return number;
    }

    /**
     * Seizure settlement: the inventory without the confiscated stacks, the wallet after the fine (omitted when
     * there is no provider) and the new city heat, all journaled before any live state changes.
     */
    void prepareSeizure(UUID player, ListTag inventoryAfter, Double balanceAfter, int xpNow, String city, int heatAfter) {
        prepareSeizure(player, inventoryAfter, balanceAfter, xpNow, city, heatAfter, new ListTag(), null);
    }

    /**
     * The same, with the changes to backpacks the traveller carries (their slots live in the pack mod's own storage, not in the inventory, so the journal
     * carries them and settlement replays them idempotently; see {@link CarriedStorage}) and the record of the search, written to the player's history by the same
     * settlement so that "what was found, where, and why" is never out of step with what was taken.
     */
    void prepareSeizure(UUID player, ListTag inventoryAfter, Double balanceAfter, int xpNow, String city, int heatAfter, ListTag packChanges, CompoundTag searchRecord) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Finish recovery before an inspection");
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", SEIZURE); pending.put("inventory", inventoryAfter.copy()); pending.putInt("xp", xpNow);
        if (!packChanges.isEmpty()) pending.put("packs", packChanges.copy());
        if (searchRecord != null) pending.put("search", searchRecord.copy());
        if (balanceAfter != null) {
            if (!Double.isFinite(balanceAfter) || balanceAfter < 0) throw new IllegalArgumentException("Invalid settlement");
            pending.putDouble("balance", balanceAfter);
        }
        putTargets(pending, Map.of(), Map.of(city, heatAfter));
        record.put("pending", pending);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    /** A search that found nothing: it changes no inventory or wallet, so it is recorded at once (the seizure's record travels with its own settlement). */
    void recordSearch(UUID player, CompoundTag entry) {
        checkHealthy();
        CompoundTag record = player(player);
        ListTag history = record.getList("searches", Tag.TAG_COMPOUND);
        CompoundTag copy = entry.copy();
        copy.putLong("t", clock());
        history.add(copy);
        while (history.size() > SEARCH_HISTORY) history.remove(0);
        record.put("searches", history);
        putPlayer(player, record); persist();
    }

    /** The latest searches of a person, oldest first. */
    ListTag searches(UUID player) { return player(player).getList("searches", Tag.TAG_COMPOUND); }

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

    // ---------------------------------------------------------------- fences (D-125)

    /** Credits a city's fence network can still pay, after lazy refilling in active time; never above its budget, so it cannot bank a backlog. Does not persist by itself. */
    int fenceBudget(FenceCatalog.Fence fence) {
        CompoundTag all = root.getCompound("fences");
        CompoundTag state = all.getCompound(fence.city());
        long now = clock();
        int credits = state.contains("credits") ? Math.min(fence.budget(), state.getInt("credits")) : fence.budget();
        long last = state.contains("last_tick") ? state.getLong("last_tick") : now;
        if (credits < 0 || last > now) throw new IllegalStateException("Invalid saved fence budget");
        if (credits >= fence.budget()) last = now;
        long intervals = (now - last) / fence.refillTicks();
        if (intervals > 0) {
            credits = (int) Math.min(fence.budget(), credits + Math.multiplyExact(intervals, (long) fence.refillCredits()));
            last = credits >= fence.budget() ? now : last + intervals * fence.refillTicks();
        }
        state.putInt("credits", credits); state.putLong("last_tick", last);
        all.put(fence.city(), state); root.put("fences", all);
        return credits;
    }

    boolean hasSale(UUID player, UUID sale) {
        ListTag recent = player(player).getList("recent_sales", Tag.TAG_STRING);
        for (int i = 0; i < recent.size(); i++) if (recent.getString(i).equals(sale.toString())) return true;
        return false;
    }

    /**
     * Durably commits a sale to a fence: the network's budget decrement, the sale nonce, the absolute inventory (the sold stolen goods gone) and wallet targets and any heat, together, BEFORE the live
     * player or Q Shop wallet changes. Recovery replays the targets and {@link #settle} retires the record, exactly as for a purchase.
     */
    void prepareSale(UUID player, UUID sale, FenceCatalog.Fence fence, long credits, ListTag inventoryAfter, double balanceAfter, int xpNow, Map<String, Integer> heatAfter) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Finish recovery before selling");
        if (hasSale(player, sale)) throw new IllegalStateException("That sale was already made");
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0 || xpNow < 0 || credits < 1) throw new IllegalArgumentException("Invalid settlement");
        int budget = fenceBudget(fence);
        if (credits > budget) throw new IllegalStateException("The fence cannot find that many credits; come back after a while of play");
        root.getCompound("fences").getCompound(fence.city()).putInt("credits", (int) (budget - credits));
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", SALE); pending.putString("fence", fence.city()); pending.putLong("credits", credits);
        pending.put("inventory", inventoryAfter.copy()); pending.putDouble("balance", balanceAfter); pending.putInt("xp", xpNow);
        putTargets(pending, Map.of(), heatAfter);
        record.put("pending", pending);
        ListTag recent = record.getList("recent_sales", Tag.TAG_STRING);
        recent.add(net.minecraft.nbt.StringTag.valueOf(sale.toString()));
        while (recent.size() > 8) recent.remove(0);
        record.put("recent_sales", recent);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    boolean hasEncounter(UUID player, UUID instance) {
        ListTag recent = player(player).getList("recent_encounters", Tag.TAG_STRING);
        for (int i = 0; i < recent.size(); i++) if (recent.getString(i).equals(instance.toString())) return true;
        return false;
    }

    /**
     * Durably commits the outcome of a roadside encounter (D-139): the encounter's id, the absolute inventory (what was handed over gone, what was given in place), wallet and career-XP
     * targets and any heat, together, BEFORE the live player or the Q Shop wallet changes. {@code credits} is what the player was paid (negative when they paid). Recovery replays the
     * targets and {@link #settle} retires the record; an encounter already settled for this player is refused, so a repeated choice or a copied envelope never pays twice.
     */
    void prepareEncounter(UUID player, UUID instance, String encounter, long credits, ListTag inventoryAfter, double balanceAfter, int xpAfter, Map<String, Integer> heatAfter) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Finish recovery first");
        if (hasEncounter(player, instance)) throw new IllegalStateException("That was already settled");
        if (!Double.isFinite(balanceAfter) || balanceAfter < 0 || xpAfter < 0 || xpAfter > ProgressionRules.MAX_XP) throw new IllegalArgumentException("Invalid settlement");
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", ENCOUNTER); pending.putString("encounter", encounter); pending.putLong("credits", credits);
        pending.put("inventory", inventoryAfter.copy()); pending.putDouble("balance", balanceAfter); pending.putInt("xp", xpAfter);
        putTargets(pending, Map.of(), heatAfter);
        record.put("pending", pending);
        ListTag recent = record.getList("recent_encounters", Tag.TAG_STRING);
        recent.add(net.minecraft.nbt.StringTag.valueOf(instance.toString()));
        while (recent.size() > 64) recent.remove(0);
        record.put("recent_encounters", recent);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    boolean hasSupply(UUID player, UUID supply) {
        ListTag recent = player(player).getList("recent_supplies", Tag.TAG_STRING);
        for (int i = 0; i < recent.size(); i++) if (recent.getString(i).equals(supply.toString())) return true;
        return false;
    }

    /**
     * Durably commits goods sold to a shop (D-164): the shelf's shared units raised by {@code quantity} (never above its capacity), the sale's nonce, and the absolute inventory (the goods
     * gone), wallet and career-XP targets, together, BEFORE the live player or the Q Shop wallet changes. Recovery replays the targets and {@link #settle} retires the record, exactly as for a
     * purchase; the same nonce is refused a second time, so a repeated packet never pays twice or fills the shelf twice.
     */
    void prepareSupply(UUID player, UUID supply, RegionalStock stock, int quantity, long credits, ListTag inventoryAfter, double balanceAfter, int xpAfter) {
        checkHealthy();
        CompoundTag record = player(player);
        if (record.contains("pending")) throw new IllegalStateException("Finish recovery before selling");
        if (hasSupply(player, supply)) throw new IllegalStateException("That sale was already made");
        if (quantity < 1 || credits < 1 || !Double.isFinite(balanceAfter) || balanceAfter < 0 || xpAfter < 0 || xpAfter > ProgressionRules.MAX_XP) throw new IllegalArgumentException("Invalid settlement");
        int units = regionalUnits(stock);
        if (units + quantity > stock.capacity()) throw new IllegalStateException("The shop has room for " + Math.max(0, stock.capacity() - units) + " more");
        root.getCompound("regional").getCompound(stock.id()).putInt("units", units + quantity);
        CompoundTag pending = new CompoundTag();
        pending.putString("kind", SUPPLY); pending.putString("line", stock.id()); pending.putInt("quantity", quantity); pending.putLong("credits", credits);
        pending.put("inventory", inventoryAfter.copy()); pending.putDouble("balance", balanceAfter); pending.putInt("xp", xpAfter);
        record.put("pending", pending);
        ListTag recent = record.getList("recent_supplies", Tag.TAG_STRING);
        recent.add(net.minecraft.nbt.StringTag.valueOf(supply.toString()));
        while (recent.size() > 16) recent.remove(0);
        record.put("recent_supplies", recent);
        putPlayer(player, record); persist(); // BEFORE touching inventory or Q Shop
    }

    /**
     * A shop's customers buying from its shelf (D-164): takes up to {@code quantity} units from a line, never below empty, and returns how many it took. The people the population service
     * walks into an open shop do this, so a shelf a supplier filled empties again in active play. Persists once.
     */
    int drawDown(RegionalStock stock, int quantity) {
        if (faulted || quantity < 1) return 0;
        int units = regionalUnits(stock);
        int taken = Math.min(units, quantity);
        if (taken == 0) return 0;
        root.getCompound("regional").getCompound(stock.id()).putInt("units", units - taken);
        persist();
        return taken;
    }

    /** Test seam: sets a supplier line's shared units, standing in for other buyers (GameTests and the scripted journey). Never called by gameplay. */
    void setRegionalUnitsForTest(RegionalStock stock, int units) {
        checkHealthy();
        regionalUnits(stock);
        root.getCompound("regional").getCompound(stock.id()).putInt("units", Math.max(0, Math.min(stock.capacity(), units)));
        persist();
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

    /** The Crew hold named by this player's prepared purchase, or null when there is none (no prepared purchase, or one the bank does not help pay). */
    UUID preparedCrewHold(UUID player) {
        CompoundTag pending = player(player).getCompound("pending");
        return pending.hasUUID("crew_tx") ? pending.getUUID("crew_tx") : null;
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
        preparePurchase(player, purchase, stock, quantity, inventoryAfter, balanceAfter, xpNow, null, 0);
    }

    /**
     * As above, for a purchase the Crew bank pays part of (D-089). The hold's transaction id and amount are written into the same prepared record, so
     * this record is the one durable decision: while it exists the purchase rolls forward (recovery commits the hold), and a hold that no prepared record
     * names was never decided and is released. {@code balanceAfter} is the buyer's own wallet after paying only their share.
     */
    void preparePurchase(UUID player, UUID purchase, RegionalStock stock, int quantity,
                         ListTag inventoryAfter, double balanceAfter, int xpNow, UUID crewHold, long crewAmount) {
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
        if (crewHold != null) { pending.putUUID("crew_tx", crewHold); pending.putLong("crew_amount", crewAmount); }
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
