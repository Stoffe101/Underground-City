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

    @Override
    public CompoundTag save(CompoundTag root) {
        root.putInt("schema_version", 1);
        ListTag entries = new ListTag();
        for (Map.Entry<UUID, Citizen> element : citizens.entrySet()) {
            Citizen citizen = element.getValue();
            CompoundTag record = new CompoundTag();
            record.putUUID("player", element.getKey());
            record.putString("city", citizen.city);
            record.putString("career", citizen.career);
            record.put("career_xp", writeXp(citizen.careerXp));
            record.put("general_xp", writeXp(citizen.generalXp));
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

        private Citizen(String city, String career) {
            this.city = city;
            this.career = career;
        }

        public int careerXp() { return careerXp.getOrDefault(career, 0); }
        public int careerLevel() { return ProgressionRules.levelForXp(careerXp()); }
        public int generalLevel(String skill) {
            return ProgressionRules.levelForXp(generalXp.getOrDefault(skill, 0));
        }
    }
}
