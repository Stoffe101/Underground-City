package io.github.stoffe101.undergroundcity;

import java.util.Set;

/**
 * Pure, side-independent progression rules. No Minecraft references, so these
 * can be tested without loading Forge or a world.
 */
public final class ProgressionRules {
    public static final int MAX_XP = 2_000_000;
    public static final Set<String> CITIES = Set.of("valewood", "ironhaven", "blackwater");
    public static final Set<String> CAREERS = Set.of("farmer", "vintner", "smith", "gunsmith", "technician");
    /** The general skill every career can raise: thievery, with its own level, points and tree (D-143). Kept in the general XP record, so a save before it simply has none. */
    public static final String THIEVERY = "thievery";
    public static final Set<String> GENERAL_SKILLS = Set.of("driving", "fitness", "shooting", "trading", "social", THIEVERY);
    /** The Thief track's top level and its XP curve: level n begins at 90 x (n - 1)^2 (level 2 at 90, 5 at 1,440, 10 at 7,290, 25 at 51,840). */
    public static final int THIEF_MAX_LEVEL = 25, THIEF_XP_STEP = 90;

    private ProgressionRules() {}

    public static boolean validCity(String value) {
        return CITIES.contains(value);
    }

    public static boolean validCareer(String value) {
        return CAREERS.contains(value);
    }

    public static int totalXp(int current, int gain) {
        if (gain <= 0) {
            throw new IllegalArgumentException("XP gain must be positive");
        }
        if (current < 0) {
            throw new IllegalArgumentException("Current XP cannot be negative");
        }
        return (int) Math.min(MAX_XP, (long) current + gain);
    }

    /** The XP at which {@code level} begins (the inverse of {@link #levelForXp}); level 1 begins at 0. */
    public static int xpAtLevel(int level) {
        int n = Math.max(1, level) - 1;
        return 125 * n * n;
    }

    /** Fraction of the way from the start of the current level to the next, 0..1 (1 at the top level). */
    public static float levelProgress(int xp) {
        int level = levelForXp(xp);
        if (level >= 30) return 1f;
        int from = xpAtLevel(level), to = xpAtLevel(level + 1);
        return Math.max(0f, Math.min(1f, (xp - from) / (float) Math.max(1, to - from)));
    }

    /** The Thief level of a thievery XP total (1 to {@link #THIEF_MAX_LEVEL}). */
    public static int thiefLevelForXp(int xp) {
        if (xp < 0) throw new IllegalArgumentException("Negative XP");
        return Math.min(THIEF_MAX_LEVEL, 1 + (int) Math.floor(Math.sqrt(xp / (double) THIEF_XP_STEP)));
    }

    /** The thievery XP at which a Thief level begins. */
    public static int thiefXpAtLevel(int level) {
        int n = Math.max(1, Math.min(THIEF_MAX_LEVEL, level)) - 1;
        return THIEF_XP_STEP * n * n;
    }

    /** Early provisional 30-level curve. Rebalance against actual contracts. */
    public static int levelForXp(int xp) {
        if (xp < 0) throw new IllegalArgumentException("Negative XP");
        return Math.min(30, 1 + (int) Math.floor(Math.sqrt(xp / 125.0)));
    }
}
