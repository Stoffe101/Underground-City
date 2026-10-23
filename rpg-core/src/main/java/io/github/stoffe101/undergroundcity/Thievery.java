package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/**
 * The Thief track (D-143): thievery XP, what earns it, and the records that keep it from being farmed.
 *
 * <p>XP comes only from something that took a risk and moved the world: the <b>first</b> time a player gets into a building (by its tier), a safe they crack for the first time, stolen goods
 * of their own taking that a fence buys, a dispatch they slip, and an alarm panel or camera they beat for the first time in a building. A snapped pick, a timed-out lock, an alarm set off on
 * purpose, a second visit to the same building, goods handed from one player to another, and the same safe again earn nothing, so careful success always beats reckless failure. The records are
 * per player, saved, and bounded (the oldest are forgotten first), so a crash, a reconnect or a second player changes nothing.
 */
public final class Thievery {
    /** XP per tier for getting into a building for the first time, cracking a safe for the first time and slipping a police response. */
    static final int ENTRY_XP_PER_TIER = 25, SAFE_XP_PER_TIER = 40, ESCAPE_XP_PER_TIER = 20, BYPASS_XP = 15;
    /** A fence sale of goods you took yourself: one XP per this many credits. */
    static final int SALE_CREDITS_PER_XP = 3;
    /** Bounds of the per-player records. */
    static final int MAX_REMEMBERED = 4096;

    /** The six words a burglar uses for how well off a place is, by tier (index 0 is "not a target"). */
    static final String[] TIER_WORDS = {"Not a target", "Shabby", "Modest", "Comfortable", "Well-off", "Wealthy", "Exceptional"};

    private Thievery() {}

    static String tierWord(int tier) { return TIER_WORDS[Math.max(0, Math.min(TIER_WORDS.length - 1, tier))]; }

    /** True when the player has learned a Thief skill. */
    static boolean has(ServerPlayer player, String skill) { return player != null && SkillService.has(player, skill); }

    // ------------------------------------------------------------------------------------------------ awards

    /** Adds XP and tells the player, with a level-up notice when a level was reached. Returns the XP given (0 for an unregistered player or a non-positive amount). */
    static int award(ServerPlayer player, int amount, String why) { return award(player, amount, why, true); }

    /** The same; with {@code tell} false the caller puts the XP into its own notice (a search says what was found and the XP in one line). */
    static int award(ServerPlayer player, int amount, String why, boolean tell) {
        if (amount <= 0 || player == null) return 0;
        CitizenState state = CitizenState.get(player.server);
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElse(null);
        if (citizen == null) return 0;
        int before = citizen.thiefLevel();
        state.addThiefXp(player.getUUID(), amount);
        int after = citizen.thiefLevel();
        if (tell) Notice.send(player, Notice.Kind.SKILL, "+" + amount + " Thief XP", why);
        if (after > before) Notice.send(player, Notice.Kind.SKILL, "Thief level " + after, "A Thief point to spend on the Thief page of a terminal.");
        return amount;
    }

    /** First time this player gets into this building: XP by its tier. */
    static int entered(ServerPlayer player, CityBuildings.Building building) {
        int tier = tier(building);
        if (tier <= 0 || !records(player.server).first(player.getUUID(), "entered", building.key())) return 0;
        return award(player, ENTRY_XP_PER_TIER * tier, "First time inside a " + tierWord(tier).toLowerCase(java.util.Locale.ROOT) + " " + label(building) + ".");
    }

    /** First time this player opens this safe. */
    static int crackedSafe(ServerPlayer player, net.minecraft.core.BlockPos safe, int tier) {
        if (!records(player.server).first(player.getUUID(), "safes", safe.asLong())) return 0;
        return award(player, SAFE_XP_PER_TIER * Math.max(1, tier), "A safe cracked.");
    }

    /** First time this player beats an alarm panel or a camera of this building. */
    static int bypassed(ServerPlayer player, long buildingKey, String what) {
        if (!records(player.server).first(player.getUUID(), "bypassed", buildingKey * 31 + what.hashCode())) return 0;
        return award(player, BYPASS_XP, "The " + what + " beaten.");
    }

    /** A police response for this player ended without an arrest. {@code dispatchKey} identifies the response, so one escape is paid once. */
    static int escaped(ServerPlayer player, long dispatchKey, int tier) {
        if (!records(player.server).first(player.getUUID(), "escapes", dispatchKey)) return 0;
        return award(player, ESCAPE_XP_PER_TIER * Math.max(1, tier), "You got away.");
    }

    /** A fence paid {@code credits} for goods this player took themselves (goods somebody else took, or handed over, earn nothing). */
    static int fenced(ServerPlayer player, long credits) {
        return award(player, (int) Math.min(400, credits / SALE_CREDITS_PER_XP), "Stolen goods turned into credits.");
    }

    /** The tier of a building (0 when it is not a burglary target). */
    static int tier(CityBuildings.Building building) { return building == null || building.security() == null ? 0 : building.security().tier(); }

    static String label(CityBuildings.Building building) {
        String label = building.security() == null ? "" : building.security().label();
        return label.isEmpty() ? "building" : label;
    }

    // ------------------------------------------------------------------------------------------------ records

    static Records records(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Records::load, Records::new, Records.DATA_KEY);
    }

    /** Per player: which buildings they have been into, which safes they have cracked, which responses they slipped, which devices they beat. Saved; each set keeps its newest entries. */
    static final class Records extends SavedData {
        static final String DATA_KEY = "underground_city_thievery";
        private final Map<UUID, Map<String, LinkedHashSet<Long>>> sets = new HashMap<>();

        /** Records the key and returns true if it was not there before. */
        boolean first(UUID player, String set, long key) {
            LinkedHashSet<Long> seen = sets.computeIfAbsent(player, p -> new HashMap<>()).computeIfAbsent(set, s -> new LinkedHashSet<>());
            if (!seen.add(key)) return false;
            while (seen.size() > MAX_REMEMBERED) { Long oldest = seen.iterator().next(); seen.remove(oldest); }
            setDirty();
            return true;
        }

        boolean seen(UUID player, String set, long key) {
            Map<String, LinkedHashSet<Long>> mine = sets.get(player);
            return mine != null && mine.getOrDefault(set, new LinkedHashSet<>()).contains(key);
        }

        static Records load(CompoundTag root) {
            Records records = new Records();
            ListTag players = root.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag entry = players.getCompound(i);
                if (!entry.hasUUID("player")) continue;
                Map<String, LinkedHashSet<Long>> mine = new HashMap<>();
                CompoundTag lists = entry.getCompound("sets");
                for (String name : lists.getAllKeys()) {
                    if (!name.matches("[a-z_]{2,24}")) continue;
                    LinkedHashSet<Long> set = new LinkedHashSet<>();
                    for (long value : lists.getLongArray(name)) if (set.size() < MAX_REMEMBERED) set.add(value);
                    mine.put(name, set);
                }
                records.sets.put(entry.getUUID("player"), mine);
            }
            return records;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            root.putInt("schema_version", 1);
            ListTag players = new ListTag();
            sets.forEach((player, mine) -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("player", player);
                CompoundTag lists = new CompoundTag();
                mine.forEach((name, set) -> lists.putLongArray(name, set.stream().mapToLong(Long::longValue).toArray()));
                entry.put("sets", lists);
                players.add(entry);
            });
            root.put("players", players);
            return root;
        }

        /** Test seam. */
        void clear() { sets.clear(); setDirty(); }
    }
}
