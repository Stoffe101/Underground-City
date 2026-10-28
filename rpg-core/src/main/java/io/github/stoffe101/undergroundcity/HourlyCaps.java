package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Hourly caps that a restart and a re-login no longer reset (eleventh pass): what each player has earned of a capped thing (a general skill, a city's good name) in its current
 * hour, kept with the world. The hour is one of the world's active time ({@link WorkOrderLedger#clock()}, which runs while somebody plays and is saved): a restart or logging out
 * and in again leaves a cap where it was, and time with nobody playing neither fills nor empties one, so being away never costs anything.
 */
final class HourlyCaps extends SavedData {
    static final String NAME = "underground_city_hourly_caps";
    static final long HOUR_TICKS = 72_000;
    /** key ("player|thing") -> {when its hour began on the active clock, what was earned in it} */
    final Map<String, long[]> windows = new HashMap<>();

    static HourlyCaps get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(HourlyCaps::load, HourlyCaps::new, NAME);
    }

    /** The world's active clock, the hour's measure. */
    static long clock(MinecraftServer server) { return WorkOrderLedger.get(server).clock(); }

    /** What may still be earned of {@code cap} for {@code key} now; an hour that has run out starts again. */
    int room(String key, int cap, long now) {
        long[] w = window(key, now);
        return (int) Math.max(0, cap - w[1]);
    }

    /** Counts {@code amount} against {@code key}'s hour. */
    void spend(String key, int amount, long now) {
        if (amount <= 0) return;
        window(key, now)[1] += amount;
        setDirty();
    }

    private long[] window(String key, long now) {
        long[] w = windows.computeIfAbsent(key, k -> new long[] {now, 0});
        if (now - w[0] >= HOUR_TICKS || now < w[0]) { w[0] = now; w[1] = 0; setDirty(); }
        return w;
    }

    static HourlyCaps load(CompoundTag tag) {
        HourlyCaps caps = new HourlyCaps();
        ListTag list = tag.getList("windows", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag w = list.getCompound(i);
            caps.windows.put(w.getString("k"), new long[] {w.getLong("start"), w.getLong("earned")});
        }
        return caps;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        windows.forEach((key, w) -> {
            CompoundTag t = new CompoundTag();
            t.putString("k", key);
            t.putLong("start", w[0]);
            t.putLong("earned", w[1]);
            list.add(t);
        });
        tag.put("windows", list);
        tag.putInt("schema_version", 1);
        return tag;
    }
}
