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
 * Which run-time road sites have been dealt with in this world (waymarkers, wayside shelters): placed, or given up because nothing fitted. A site is
 * keyed by its road and its index along it; the world remembers it so that a post a player breaks stays broken and a site is never decided twice.
 */
final class PlacedSites extends SavedData {
    /** The saved-data key of the waymarkers: it keeps its original name so worlds that already have markers keep them. */
    static final String MARKERS = "underground_city_road_markers";
    static final String SHELTERS = "underground_city_wayside_shelters";
    private static final int LIMIT = 4096;

    final Set<String> done = new HashSet<>();
    /** Where each built site stands (a block position as a long), for tools and tests; a key in {@link #done} without an entry here was given up. */
    final Map<String, Long> where = new HashMap<>();

    static PlacedSites get(ServerLevel level, String key) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(PlacedSites::load, PlacedSites::new, key);
    }

    static PlacedSites load(CompoundTag root) {
        PlacedSites sites = new PlacedSites();
        ListTag list = root.getList("done", Tag.TAG_STRING);
        for (int i = 0; i < list.size() && i < LIMIT; i++) sites.done.add(list.getString(i));
        ListTag built = root.getList("built", Tag.TAG_COMPOUND);
        for (int i = 0; i < built.size() && i < LIMIT; i++) sites.where.put(built.getCompound(i).getString("key"), built.getCompound(i).getLong("at"));
        return sites;
    }

    @Override public CompoundTag save(CompoundTag root) {
        ListTag list = new ListTag();
        for (String key : done) list.add(StringTag.valueOf(key));
        root.put("done", list);
        ListTag built = new ListTag();
        where.forEach((key, at) -> { CompoundTag entry = new CompoundTag(); entry.putString("key", key); entry.putLong("at", at); built.add(entry); });
        root.put("built", built);
        return root;
    }
}
