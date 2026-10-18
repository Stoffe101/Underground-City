package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which run-time road sites have been dealt with in this world (waymarkers, wayside shelters): placed, given up because nothing fitted, or failed. A site is
 * keyed by its road and its index along it; the world remembers it so that a post a player breaks stays broken and a site is never decided twice.
 *
 * <p>Nothing here may hide a construction that is not really there. A shelter that <em>failed</em> to build is kept apart from one that was given up for lack
 * of ground and from one that stands: it is retried a few times, then reported to the operator until {@code /uc_shelters retry} clears it. A shelter that
 * is recorded as standing is checked against the world the first time a player comes near it after a start (see {@link WaysideShelters}).
 */
final class PlacedSites extends SavedData {
    /** The saved-data key of the waymarkers: it keeps its original name so worlds that already have markers keep them. */
    static final String MARKERS = "underground_city_road_markers";
    static final String SHELTERS = "underground_city_wayside_shelters";
    private static final int LIMIT = 4096;

    final Set<String> done = new HashSet<>();
    /** Where each built site stands (a block position as a long), for tools and tests; a key in {@link #done} without an entry here was given up. */
    final Map<String, Long> where = new HashMap<>();
    /** A build that threw: how many times, when (game time of the last attempt) and why. A key here is retried after a pause until {@link WaysideShelters#MAX_ATTEMPTS}, then reported until an operator clears it. */
    record Failure(int attempts, long lastTime, String reason) {}
    final Map<String, Failure> failed = new HashMap<>();
    /** Stops already checked against the world since this world was loaded (not saved: every start checks each stop once). */
    final Set<String> verified = new HashSet<>();

    void fail(String key, long now, String reason) {
        Failure before = failed.get(key);
        failed.put(key, new Failure(before == null ? 1 : before.attempts() + 1, now, reason == null ? "" : reason.length() > 200 ? reason.substring(0, 200) : reason));
        setDirty();
    }

    static PlacedSites get(ServerLevel level, String key) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(PlacedSites::load, PlacedSites::new, key);
    }

    static PlacedSites load(CompoundTag root) {
        PlacedSites sites = new PlacedSites();
        ListTag list = root.getList("done", Tag.TAG_STRING);
        for (int i = 0; i < list.size() && i < LIMIT; i++) sites.done.add(list.getString(i));
        ListTag built = root.getList("built", Tag.TAG_COMPOUND);
        for (int i = 0; i < built.size() && i < LIMIT; i++) sites.where.put(built.getCompound(i).getString("key"), built.getCompound(i).getLong("at"));
        ListTag failures = root.getList("failed", Tag.TAG_COMPOUND); // absent in saves from before failures were kept apart: none
        for (int i = 0; i < failures.size() && i < LIMIT; i++) {
            CompoundTag t = failures.getCompound(i);
            sites.failed.put(t.getString("key"), new Failure(t.getInt("attempts"), t.getLong("at"), t.getString("reason")));
        }
        return sites;
    }

    @Override public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (String key : done) list.add(StringTag.valueOf(key));
        root.put("done", list);
        ListTag built = new ListTag();
        where.forEach((key, at) -> { CompoundTag entry = new CompoundTag(); entry.putString("key", key); entry.putLong("at", at); built.add(entry); });
        root.put("built", built);
        ListTag failures = new ListTag();
        failed.forEach((key, f) -> {
            CompoundTag t = new CompoundTag();
            t.putString("key", key); t.putInt("attempts", f.attempts()); t.putLong("at", f.lastTime()); t.putString("reason", f.reason());
            failures.add(t);
        });
        root.put("failed", failures);
        return root;
    }
}
