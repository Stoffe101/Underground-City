package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What the people who keep a city's shops remember of the players who trade with them (tenth pass, D-202): how often a player bought or sold over a kind of counter in a city (the
 * Blackwater bar, the Valewood bakery), and how often they filled its shelf when it was short. Three trades make a regular: the keepers greet them as one, and a regular hears when that
 * counter's shelf runs short whatever their trade ({@link Shortages}). Somebody who filled a short shelf is thanked for it. The memory is the world's ({@value #KEY}), at most
 * {@link #LIMIT} people-and-counters, the least recent forgotten first; it changes no price.
 */
final class Regulars extends SavedData {
    static final String KEY = "underground_city_regulars";
    static final int REGULAR = 3, LIMIT = 4096;

    record Known(int trades, int helped, long last) {
        boolean regular() { return trades >= REGULAR; }
    }

    final Map<String, Known> known = new LinkedHashMap<>(16, 0.75f, true);

    static Regulars get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Regulars::load, Regulars::new, KEY); }

    static String key(String city, String outlet, UUID player) { return city + "|" + outlet + "|" + player; }

    /** A trade over a counter: a purchase or a sale; {@code helped} when the sale filled a short shelf. */
    static Known traded(MinecraftServer server, String city, String outlet, UUID player, boolean helped) {
        Regulars r = get(server);
        String k = key(city, outlet, player);
        Known before = r.known.getOrDefault(k, new Known(0, 0, 0));
        Known after = new Known(before.trades() + 1, before.helped() + (helped ? 1 : 0), LockService.clock(server));
        r.known.put(k, after);
        while (r.known.size() > LIMIT) r.known.remove(r.known.keySet().iterator().next());
        r.setDirty();
        return after;
    }

    static Known of(MinecraftServer server, String city, String outlet, UUID player) { return get(server).known.getOrDefault(key(city, outlet, player), new Known(0, 0, 0)); }

    /** What a keeper says first to somebody they know: thanks for a shortage filled, a regular's greeting, or nothing. */
    static String greeting(MinecraftServer server, String city, String outlet, UUID player, String name) {
        Known k = of(server, city, outlet, player);
        if (k.helped() > 0) return "You filled our shelf when we were short, " + name + ". We don't forget that. ";
        if (k.regular()) return "Good to see you again, " + name + ". ";
        return "";
    }

    static Regulars load(CompoundTag tag) {
        Regulars r = new Regulars();
        for (Tag t : tag.getList("known", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            r.known.put(c.getString("key"), new Known(c.getInt("trades"), c.getInt("helped"), c.getLong("last")));
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        known.forEach((k, v) -> {
            CompoundTag c = new CompoundTag();
            c.putString("key", k); c.putInt("trades", v.trades()); c.putInt("helped", v.helped()); c.putLong("last", v.last());
            list.add(c);
        });
        tag.put("known", list);
        tag.putInt("schema_version", 1);
        return tag;
    }
}
