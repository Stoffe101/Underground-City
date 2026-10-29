package io.github.stoffe101.undergroundcity;

/**
 * Deterministic dice for inspections and witnesses. A roll is a pure function of its inputs, so a relog,
 * a chunk reload or a retried packet cannot re-roll an outcome: the seed always includes a counter or an
 * accepted-order nonce that is consumed exactly once in the ledger.
 */
final class LawMath {
    private LawMath() {}

    /** SplitMix64 finaliser. */
    static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long mix(long... parts) {
        long seed = 0x5851F42D4C957F2DL;
        for (long part : parts) seed = mix(seed ^ part);
        return seed;
    }

    /** Uniform in [0, 1). */
    static double unit(long seed) { return (mix(seed) >>> 11) * 0x1.0p-53; }

    static boolean roll(double chance, long seed) { return chance > 0 && unit(seed) < chance; }

    static long hash(String text) {
        long h = 1125899906842597L;
        for (int i = 0; i < text.length(); i++) h = 31 * h + text.charAt(i);
        return h;
    }

    /**
     * Chance a checkpoint searches a traveller: the post's base figure for its risk level, plus the city's heat
     * step for each point of heat there, minus the traveller's concealment skills, clamped to the city's maximum
     * and never below two percent (nobody is perfectly invisible).
     */
    static double inspectionChance(CityLaws.Law law, String risk, int heat, double skillModifier) {
        double chance = law.baseChance(risk) + law.heatStep() * Math.max(0, heat) + Math.min(0, skillModifier);
        return Math.max(0.02, Math.min(law.maxChance(), chance));
    }

    /**
     * The fine for an offence by somebody with {@code priors} convictions in the city within two active hours (twelfth pass): half as much again for each, at most two and a half times, never over
     * an offence's ceiling of 1000. A first offence pays the city's own figure.
     */
    static int escalatedFine(int fine, int priors) {
        int p = Math.max(0, Math.min(3, priors));
        return (int) Math.min(1000L, (long) fine * (2 + p) / 2);
    }

    /** The heat an arrest adds for a repeat offender: one more for each prior conviction, at most three more. */
    static int escalatedHeat(int heat, int priors) { return heat + Math.max(0, Math.min(3, priors)); }

    /** Chance a hand-off in a city is seen by someone who matters: heat adds three points each, concealment halves in. */
    static double witnessChance(CityLaws.Law law, int heat, double skillModifier) {
        double chance = law.witnessChance() + 0.03 * Math.max(0, heat) + Math.min(0, skillModifier) * 0.5;
        return Math.max(0.0, Math.min(0.8, chance));
    }
}
