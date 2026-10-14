package io.github.stoffe101.undergroundcity;

import java.util.Set;

/**
 * Pure, side-independent progression rules. No Minecraft references, so these
 * can be tested without loading Forge or a world.
 */
public final class ProgressionRules {
    public static final int MAX_XP = 2_000_000;
    public static final Set<String> CITIES = Set.of("valewood", "ironhaven", "blackwater");
    public static final Set<String> CAREERS = Set.of("farmer", "smith", "gunsmith", "technician");
    public static final Set<String> GENERAL_SKILLS = Set.of("driving", "fitness", "shooting", "trading", "social");

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

    /** Early provisional 30-level curve. Rebalance against actual contracts. */
    public static int levelForXp(int xp) {
        if (xp < 0) throw new IllegalArgumentException("Negative XP");
        return Math.min(30, 1 + (int) Math.floor(Math.sqrt(xp / 125.0)));
    }
}
