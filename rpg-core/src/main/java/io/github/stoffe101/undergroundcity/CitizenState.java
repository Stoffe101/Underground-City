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
        private final java.util.Set<String> skills = new java.util.TreeSet<>();
        private final Map<String, Integer> journey = new java.util.TreeMap<>();

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
    }
}
