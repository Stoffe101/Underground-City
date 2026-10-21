package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The durable state of the doors of city buildings (D-105): which ones a lockpicker has opened and until when, which ones a snapped pick has jammed, and which buildings have already been
 * furnished with their stash. A door that nobody has touched has no entry at all (the plan, not the save, says it is locked), so the save grows with burglary and not with the city.
 *
 * <p>All times are on the <b>active</b> clock ({@link WorkOrderLedger#clock()}), which only runs while somebody is online: a door opened just before the last player left is still open when they
 * come back, nothing re-locks or jams while the world is empty, and nobody is punished for being away (the same rule as rent and restocking). Entries are keyed by the lower half of the door.
 */
public final class PropertyLocks extends SavedData {
    public static final String NAME = "underground_city_property_locks";
    /** A cap on remembered doors; the oldest entries that have nothing left to say are dropped first. */
    static final int MAX_DOORS = 2048;
    static final int SCHEMA = 2;

    /**
     * One door's remembered state. {@code closed} is false while an opened door still has to be shut again by the sweep, which does it at {@code closeAt}: that is the end of a picked door's window,
     * or a few seconds after somebody inside opened the door to leave (the door was never unlocked for anybody outside; it only stands open while it is shut again).
     */
    public record Door(long breachedUntil, long jammedUntil, boolean closed, int breaches, long closeAt) {}

    private final Map<Long, Door> doors = new LinkedHashMap<>();
    private final Set<Long> furnished = new HashSet<>();

    public static PropertyLocks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PropertyLocks::load, PropertyLocks::new, NAME);
    }

    // ------------------------------------------------------------------------------------------------ doors

    public Door door(BlockPos pos) { return doors.get(pos.asLong()); }
    public boolean breached(BlockPos pos, long now) { Door d = doors.get(pos.asLong()); return d != null && now < d.breachedUntil(); }
    /** Active ticks left before the lock may be tried again after a snapped pick (0 when it may). */
    public long jamRemaining(BlockPos pos, long now) { Door d = doors.get(pos.asLong()); return d == null ? 0 : Math.max(0, d.jammedUntil() - now); }

    /** The door was picked: it stays unlocked until {@code until}. */
    public void breach(BlockPos pos, long until) {
        Door d = doors.get(pos.asLong());
        doors.put(pos.asLong(), new Door(until, d == null ? 0 : d.jammedUntil(), false, (d == null ? 0 : d.breaches()) + 1, until));
        trim();
        setDirty();
    }

    /** Somebody inside opened the door to leave: it may stand open until {@code until}, then it is shut again. It stays locked against anybody on the outside. */
    public void exitOpen(BlockPos pos, long until) {
        Door d = doors.get(pos.asLong());
        long closeAt = d != null && !d.closed() ? Math.max(until, d.closeAt()) : until;
        doors.put(pos.asLong(), new Door(d == null ? 0 : d.breachedUntil(), d == null ? 0 : d.jammedUntil(), false, d == null ? 0 : d.breaches(), closeAt));
        trim();
        setDirty();
    }

    /** True when this door is being looked after by the sweep (picked, or opened from inside, and not shut again yet): it standing open is not a fault. */
    public boolean openOnPurpose(BlockPos pos) { Door d = doors.get(pos.asLong()); return d != null && !d.closed(); }

    /** A pick snapped in the lock: nobody can try it again until {@code until}. */
    public void jam(BlockPos pos, long until) {
        Door d = doors.get(pos.asLong());
        doors.put(pos.asLong(), new Door(d == null ? 0 : d.breachedUntil(), until, d == null || d.closed(), d == null ? 0 : d.breaches(), d == null ? 0 : d.closeAt()));
        trim();
        setDirty();
    }

    /** Doors whose unlocked time has run out and that have not been shut again yet. */
    public List<BlockPos> dueToClose(long now) {
        List<BlockPos> due = new ArrayList<>();
        for (var entry : doors.entrySet()) if (!entry.getValue().closed() && now >= entry.getValue().closeAt()) due.add(BlockPos.of(entry.getKey()));
        return due;
    }

    /** The sweep shut this door (or found it gone): it is locked again. */
    public void closed(BlockPos pos) {
        Door d = doors.get(pos.asLong());
        if (d == null) return;
        doors.put(pos.asLong(), new Door(d.breachedUntil(), d.jammedUntil(), true, d.breaches(), d.closeAt()));
        setDirty();
    }

    public int remembered() { return doors.size(); }

    /** Forgets doors with nothing left to say, then, if still over the cap, the oldest ones. */
    private void trim() {
        if (doors.size() <= MAX_DOORS) return;
        Iterator<Map.Entry<Long, Door>> it = doors.entrySet().iterator();
        while (doors.size() > MAX_DOORS && it.hasNext()) {
            Door d = it.next().getValue();
            if (d.closed()) it.remove();
        }
    }

    /** Housekeeping: a closed door whose jam is over carries nothing worth saving. */
    public void prune(long now) {
        if (doors.values().removeIf(d -> d.closed() && now >= d.breachedUntil() && now >= d.jammedUntil() && now >= d.closeAt())) setDirty();
    }

    // ------------------------------------------------------------------------------------------------ buildings

    /** True when the building has already been considered for a stash (placed or not), so a stash is decided once per building, ever. */
    public boolean furnished(long buildingKey) { return furnished.contains(buildingKey); }
    public void markFurnished(long buildingKey) { if (furnished.add(buildingKey)) setDirty(); }
    public int furnishedCount() { return furnished.size(); }

    // ------------------------------------------------------------------------------------------------ save

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema_version", SCHEMA);
        ListTag list = new ListTag();
        for (var entry : doors.entrySet()) {
            CompoundTag d = new CompoundTag();
            d.putLong("pos", entry.getKey());
            d.putLong("breached_until", entry.getValue().breachedUntil());
            d.putLong("jammed_until", entry.getValue().jammedUntil());
            d.putBoolean("closed", entry.getValue().closed());
            d.putInt("breaches", entry.getValue().breaches());
            d.putLong("close_at", entry.getValue().closeAt());
            list.add(d);
        }
        tag.put("doors", list);
        tag.put("furnished", new LongArrayTag(furnished.stream().mapToLong(Long::longValue).toArray()));
        return tag;
    }

    public static PropertyLocks load(CompoundTag tag) {
        PropertyLocks locks = new PropertyLocks();
        if (tag.contains("schema_version") && tag.getInt("schema_version") > SCHEMA) {
            throw new IllegalStateException("The property-lock save was written by a newer version (schema " + tag.getInt("schema_version") + ")");
        }
        for (Tag raw : tag.getList("doors", Tag.TAG_COMPOUND)) {
            CompoundTag d = (CompoundTag) raw;
            long breachedUntil = Math.max(0, d.getLong("breached_until"));
            // schema 1 had no separate close time: a picked door was shut when its window ended
            long closeAt = d.contains("close_at") ? Math.max(0, d.getLong("close_at")) : breachedUntil;
            locks.doors.put(d.getLong("pos"), new Door(breachedUntil, Math.max(0, d.getLong("jammed_until")), d.getBoolean("closed"), Math.max(0, d.getInt("breaches")), closeAt));
        }
        for (long key : tag.getLongArray("furnished")) locks.furnished.add(key);
        return locks;
    }
}
