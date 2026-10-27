package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world server-authoritative citizen records.
 * Save schema 1: registered identity + progression counters.
 */
public final class CitizenState extends SavedData {
    private static final String DATA_KEY = "underground_city_citizens";
    private final Map<UUID, Citizen> citizens = new HashMap<>();

    public static CitizenState get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(CitizenState::load, CitizenState::new, DATA_KEY);
    }

    public static CitizenState load(CompoundTag root) {
        CitizenState state = new CitizenState();
        ListTag entries = root.getList("citizens", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            if (!entry.hasUUID("player")) continue;
            UUID uuid = entry.getUUID("player");
            String city = entry.getString("city");
            String career = entry.getString("career");
            if (!ProgressionRules.validCity(city) || !ProgressionRules.validCareer(career)) continue;
            Citizen citizen = new Citizen(city, career);
            readXp(entry.getCompound("career_xp"), citizen.careerXp, ProgressionRules.CAREERS);
            readXp(entry.getCompound("general_xp"), citizen.generalXp, ProgressionRules.GENERAL_SKILLS);
            ListTag skills = entry.getList("skills", Tag.TAG_STRING);
            for (int s = 0; s < skills.size() && s < 64; s++) {
                String id = skills.getString(s);
                if (id.matches("[a-z]+\\.[a-z_]{2,40}")) citizen.skills.add(id);
            }
            // points an operator granted or took (D-191): kept apart from the points levels give, and absent in an older save
            CompoundTag bonus = entry.getCompound("admin_points");
            citizen.careerBonus = Math.max(-MAX_BONUS, Math.min(MAX_BONUS, bonus.getInt("career")));
            citizen.thiefBonus = Math.max(-MAX_BONUS, Math.min(MAX_BONUS, bonus.getInt("thief")));
            // a city's good name (D-204): absent in an older save, which loads with none
            CompoundTag standing = entry.getCompound("standing");
            for (String key : standing.getAllKeys()) if (ProgressionRules.validCity(key)) citizen.standing.put(key, Math.max(0, Math.min(CityStanding.MAX, standing.getInt(key))));
            CompoundTag journey = entry.getCompound("journey");
            for (String key : journey.getAllKeys()) {
                if (key.matches("[a-z_]{2,24}") && citizen.journey.size() < 32) citizen.journey.put(key, Math.max(0, Math.min(9999, journey.getInt(key))));
            }
            state.citizens.put(uuid, citizen);
        }
        return state;
    }

    private static void readXp(CompoundTag tag, Map<String, Integer> destination, java.util.Set<String> allowed) {
        for (String key : allowed) {
            if (tag.contains(key, Tag.TAG_INT)) {
                destination.put(key, Math.min(ProgressionRules.MAX_XP, Math.max(0, tag.getInt(key))));
            }
        }
    }

    public Optional<Citizen> find(UUID uuid) {
        return Optional.ofNullable(citizens.get(uuid));
    }

    /** Every registered account's UUID (for the identity audit; no names are stored here). */
    public java.util.Set<UUID> ids() { return java.util.Set.copyOf(citizens.keySet()); }

    public boolean register(UUID uuid, String city, String career) {
        if (citizens.containsKey(uuid) || !ProgressionRules.validCity(city)
                || !ProgressionRules.validCareer(career)) {
            return false;
        }
        citizens.put(uuid, new Citizen(city, career));
        setDirty();
        return true;
    }

    public int addCareerXp(UUID uuid, int amount) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null) throw new IllegalArgumentException("Citizen not registered");
        int previous = citizen.careerXp.getOrDefault(citizen.career, 0);
        int updated = ProgressionRules.totalXp(previous, amount);
        citizen.careerXp.put(citizen.career, updated);
        setDirty();
        return updated;
    }

    public int addGeneralXp(UUID uuid, String skill, int amount) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || !ProgressionRules.GENERAL_SKILLS.contains(skill)) {
            throw new IllegalArgumentException("Unknown citizen or skill");
        }
        int updated = ProgressionRules.totalXp(citizen.generalXp.getOrDefault(skill, 0), amount);
        citizen.generalXp.put(skill, updated);
        setDirty();
        return updated;
    }

    /** Adds thievery XP (D-143). Returns the new total. */
    public int addThiefXp(UUID uuid, int amount) { return addGeneralXp(uuid, ProgressionRules.THIEVERY, amount); }

    /** Records a skill. Prerequisites and points are checked by SkillService before this is called. */
    void unlockSkill(UUID uuid, String skillId) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null) throw new IllegalArgumentException("Citizen not registered");
        if (citizen.skills.size() >= 64) throw new IllegalStateException("Skill limit reached");
        if (citizen.skills.add(skillId)) setDirty();
    }

    /** Records that a guided-journey milestone happened (a flag: set once, never undone). */
    public void markJourney(UUID uuid, String key) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || citizen.journey.getOrDefault(key, 0) > 0 || citizen.journey.size() >= 32) return;
        citizen.journey.put(key, 1);
        setDirty();
    }

    /** Moves a city's good name for a citizen by {@code delta}, within 0 and {@link CityStanding#MAX}. Returns the new value (-1 for nobody). */
    public int changeStanding(UUID uuid, String city, int delta) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || !ProgressionRules.validCity(city)) return -1;
        int now = Math.max(0, Math.min(CityStanding.MAX, citizen.standing.getOrDefault(city, 0) + delta));
        citizen.standing.put(city, now);
        setDirty();
        return now;
    }

    /** Counts a guided-journey milestone (deliveries made, for instance). */
    public void bumpJourney(UUID uuid, String key) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || citizen.journey.size() >= 32 && !citizen.journey.containsKey(key)) return;
        citizen.journey.merge(key, 1, (a, b) -> Math.min(9999, a + b));
        setDirty();
    }

    /** Keeps the highest value seen for a counter (a best score, say). Returns true when the value improved. */
    public boolean raiseJourney(UUID uuid, String key, int value) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || citizen.journey.size() >= 32 && !citizen.journey.containsKey(key)) return false;
        int clamped = Math.max(0, Math.min(9999, value));
        if (clamped <= citizen.journey.getOrDefault(key, 0)) return false;
        citizen.journey.put(key, clamped);
        setDirty();
        return true;
    }

    /** How far an operator's points may move a citizen's earned points either way (D-191). */
    public static final int MAX_BONUS = 100;

    /** Sets the career XP outright (an operator's command, D-191): clamped to 0 and the maximum. Returns the new total. */
    public int setCareerXp(UUID uuid, int value) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null) throw new IllegalArgumentException("Citizen not registered");
        int clamped = Math.max(0, Math.min(ProgressionRules.MAX_XP, value));
        citizen.careerXp.put(citizen.career, clamped);
        setDirty();
        return clamped;
    }

    /** Sets a general skill's XP (Thievery among them) outright: clamped to 0 and the maximum. Returns the new total. */
    public int setGeneralXp(UUID uuid, String skill, int value) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || !ProgressionRules.GENERAL_SKILLS.contains(skill)) throw new IllegalArgumentException("Unknown citizen or skill");
        int clamped = Math.max(0, Math.min(ProgressionRules.MAX_XP, value));
        citizen.generalXp.put(skill, clamped);
        setDirty();
        return clamped;
    }

    /** Sets an operator's career or Thief points (D-191), within {@link #MAX_BONUS} either way. */
    public void setBonusPoints(UUID uuid, boolean thief, int value) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null) throw new IllegalArgumentException("Citizen not registered");
        int clamped = Math.max(-MAX_BONUS, Math.min(MAX_BONUS, value));
        if (thief) citizen.thiefBonus = clamped; else citizen.careerBonus = clamped;
        setDirty();
    }

    /** Restore a journaled reward after a crash without awarding it twice. */
    void restoreCareerXp(UUID uuid, int target) {
        Citizen citizen = citizens.get(uuid);
        if (citizen == null || target < 0 || target > ProgressionRules.MAX_XP) {
            throw new IllegalArgumentException("Invalid career recovery");
        }
        citizen.careerXp.put(citizen.career, Math.max(citizen.careerXp(), target));
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("schema_version", 2);
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, Citizen> element : citizens.entrySet()) {
            Citizen citizen = element.getValue();
            CompoundTag record = new CompoundTag();
            record.putUUID("player", element.getKey());
            record.putString("city", citizen.city);
            record.putString("career", citizen.career);
            record.put("career_xp", writeXp(citizen.careerXp));
            record.put("general_xp", writeXp(citizen.generalXp));
            ListTag skills = new ListTag();
            citizen.skills.stream().sorted().forEach(id -> skills.add(net.minecraft.nbt.StringTag.valueOf(id)));
            record.put("skills", skills);
            CompoundTag journey = new CompoundTag();
            citizen.journey.forEach(journey::putInt);
            record.put("journey", journey);
            if (!citizen.standing.isEmpty()) {
                CompoundTag standing = new CompoundTag();
                citizen.standing.forEach(standing::putInt);
                record.put("standing", standing);
            }
            if (citizen.careerBonus != 0 || citizen.thiefBonus != 0) {
                CompoundTag bonus = new CompoundTag();
                bonus.putInt("career", citizen.careerBonus);
                bonus.putInt("thief", citizen.thiefBonus);
                record.put("admin_points", bonus);
            }
            entries.add(record);
        }
        root.put("citizens", entries);
        return root;
    }

    private static CompoundTag writeXp(Map<String, Integer> source) {
        CompoundTag tag = new CompoundTag();
        source.forEach(tag::putInt);
        return tag;
    }

    public static final class Citizen {
        public final String city;
        public final String career;
        public final Map<String, Integer> careerXp = new HashMap<>();
        public final Map<String, Integer> generalXp = new HashMap<>();
        /** Each city's good name for this citizen, 0 to {@link CityStanding#MAX} (D-204). */
        public final Map<String, Integer> standing = new java.util.TreeMap<>();
        private final java.util.Set<String> skills = new java.util.TreeSet<>();
        private final Map<String, Integer> journey = new java.util.TreeMap<>();
        /** Points an operator granted (or, below zero, took) on top of what levels give (D-191): separate from the levels, so no level or unlock requirement moves with them. */
        private int careerBonus, thiefBonus;
        public int careerBonus() { return careerBonus; }
        public int thiefBonus() { return thiefBonus; }

        private Citizen(String city, String career) {
            this.city = city;
            this.career = career;
        }

        public java.util.Set<String> skills() { return java.util.Collections.unmodifiableSet(skills); }
        public int careerXp() { return careerXp.getOrDefault(career, 0); }
        public int journey(String key) { return journey.getOrDefault(key, 0); }
        public int careerLevel() { return ProgressionRules.levelForXp(careerXp()); }
        public int generalLevel(String skill) {
            return ProgressionRules.levelForXp(generalXp.getOrDefault(skill, 0));
        }
        /** Thievery XP and the Thief level it gives (D-143): a track of its own, never the career's. */
        public int thiefXp() { return generalXp.getOrDefault(ProgressionRules.THIEVERY, 0); }
        public int thiefLevel() { return ProgressionRules.thiefLevelForXp(thiefXp()); }
    }
}
