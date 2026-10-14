package io.github.stoffe101.undergroundcity;

/** Pure Java 17 tests: can run separately from Minecraft/Forge. */
public final class ProgressionRulesHarness {
    public static void main(String[] args) {
        check(ProgressionRules.validCity("valewood"), "Valid city rejected");
        check(!ProgressionRules.validCity("unknown"), "Unknown city accepted");
        check(ProgressionRules.validCareer("gunsmith"), "Valid career rejected");
        check(!ProgressionRules.validCareer("superhero"), "Unknown career accepted");
        check(ProgressionRules.levelForXp(0) == 1, "Start level not 1");
        check(ProgressionRules.levelForXp(125) == 2, "First level XP incorrect");
        check(ProgressionRules.levelForXp(2_000_000) == 30, "Level cap incorrect");
        check(ProgressionRules.totalXp(1_999_999, 100) == 2_000_000, "XP cap incorrect");
        boolean rejected = false;
        try { ProgressionRules.totalXp(0, -10); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Negative XP permitted");
        int lastLevel = 1;
        for (int xp = 0; xp < 2_000_000; xp += 113) {
            int level = ProgressionRules.levelForXp(xp);
            check(level >= lastLevel && level <= 30, "Progression monotonicity broken");
            lastLevel = level;
        }
        System.out.println("ProgressionRules tests passed.");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
