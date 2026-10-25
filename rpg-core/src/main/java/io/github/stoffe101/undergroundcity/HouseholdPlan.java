package io.github.stoffe101.undergroundcity;

import java.util.ArrayList;
import java.util.List;

/**
 * Who lives and works in a furnished building and where they are at a given hour (D-124): pure arithmetic of the building, the world seed and the clock, with no Minecraft types, so a test can prove
 * that a household is the same household every time, that a night finds the residents in their beds and a working day finds the shopkeeper at the counter, and that nothing needs saving.
 *
 * <p>A building has a resident for each bed spot (at most {@link #MAX_RESIDENTS}) and a worker for each counter or desk spot (at most {@link #MAX_WORKERS}); a shop with a counter and a bed has the
 * shopkeeper who lives above it as both. Residents are not the roster's people ({@link PopulationPlan}): they have their own names, drawn from the same city pool, and live only indoors.
 */
final class HouseholdPlan {
    private HouseholdPlan() {}

    static final int MAX_RESIDENTS = 4, MAX_WORKERS = 2;

    /** {@code spot} is the index of the spot where they sleep (residents) or serve (workers) in the building's plan. */
    record Resident(long id, String name, int skin, String role, int spot, int slot) {}

    /** The people of a building: the same every time for the same city, world seed and building. */
    static List<Resident> residents(String city, long worldSeed, long buildingKey, BuildingSpots.Plan plan, PopulationPlan.Names names) {
        List<Resident> out = new ArrayList<>();
        if (plan == null || names == null) return out;
        int slot = 0;
        for (int bed : plan.indexesOf("bed")) {
            if (out.size() >= MAX_RESIDENTS) break;
            out.add(make(city, worldSeed, buildingKey, slot++, "resident", bed, names));
        }
        int workers = 0;
        for (String kind : List.of("counter", "desk")) {
            for (int post : plan.indexesOf(kind)) {
                if (workers >= MAX_WORKERS) break;
                out.add(make(city, worldSeed, buildingKey, slot++, "worker", post, names));
                workers++;
            }
        }
        // a fence keeps the building's back office at night (D-125): one at most, a person of their own
        List<Integer> fences = plan.indexesOf("fence");
        if (!fences.isEmpty()) out.add(make(city, worldSeed, buildingKey, slot, "fence", fences.get(0), names));
        return List.copyOf(out);
    }

    private static Resident make(String city, long worldSeed, long buildingKey, int slot, String role, int spot, PopulationPlan.Names names) {
        long h = PopulationPlan.mix(worldSeed ^ PopulationPlan.cityKey(city) ^ PopulationPlan.mix(buildingKey + slot * 0x9E3779B1L));
        String name = names.first().get((int) Math.floorMod(h >>> 8, (long) names.first().size())) + " " + names.last().get((int) Math.floorMod(h >>> 24, (long) names.last().size()));
        int[] outfits = PopulationPlan.outfitsOf(role, city);
        int skin = outfits[(int) Math.floorMod(h >>> 40, (long) outfits.length)] + PopulationPlan.OUTFITS * (int) Math.floorMod(h >>> 48, (long) (PopulationPlan.SKINS / PopulationPlan.OUTFITS));
        return new Resident(PopulationPlan.mix(h + 1), name, skin, role, spot, slot);
    }

    /** A fence deals from nine at night to three in the morning (the fences' data says the same; a GameTest holds them together). */
    static final int FENCE_FROM = 21, FENCE_TO = 3;
    static boolean fenceHours(int hour) { return hour >= FENCE_FROM || hour < FENCE_TO; }

    /** Not here: the person is out for the day, away or at work elsewhere, and is not an entity at all. */
    static final int AWAY = -1;

    /**
     * The spot of the plan where {@code who} stands at {@code hour} on {@code day}, or {@link #AWAY}. Residents sleep at their bed from ten at night to seven (one night in ten they are away), eat at the
     * stove until nine, are out for most of the working day (a third of them stay in, at a seat or by the bed) and sit at home in the evening; workers are at their counter or desk in the
     * building's business hours ({@link BusinessHours}: a shop's eight to six, a club's six in the evening to three in the morning, D-185) and away otherwise. Where a building has no seat or
     * stove the bed stands in for it.
     */
    static int where(Resident who, BuildingSpots.Plan plan, long worldSeed, int hour, long day) {
        long h = PopulationPlan.mix(who.id() ^ worldSeed ^ (day * 0x2545F4914F6CDD1DL));
        double roll = (h >>> 11) / (double) (1L << 53);
        if (who.role().equals("worker")) return BusinessHours.of(plan).open(hour) && roll < 0.95 ? who.spot() : AWAY;
        if (who.role().equals("fence")) return fenceHours(hour) && roll < 0.85 ? who.spot() : AWAY;
        boolean night = hour >= 22 || hour < 7;
        if (night) return roll < 0.9 ? who.spot() : AWAY;
        if (hour < 9) return roll < 0.8 ? first(plan, "stove", who.spot()) : AWAY;
        if (hour < 17) return roll < 0.35 ? pick(plan, "seat", who) : AWAY;
        return roll < 0.85 ? pick(plan, "seat", who) : AWAY;
    }

    private static int first(BuildingSpots.Plan plan, String kind, int fallback) {
        List<Integer> spots = plan.indexesOf(kind);
        return spots.isEmpty() ? fallback : spots.get(0);
    }

    private static int pick(BuildingSpots.Plan plan, String kind, Resident who) {
        List<Integer> spots = plan.indexesOf(kind);
        return spots.isEmpty() ? who.spot() : spots.get(Math.floorMod(who.slot(), spots.size()));
    }

    /** How many of a household are in at {@code hour} (an average over a few days), for tests and the status command. */
    static double averageIn(List<Resident> people, BuildingSpots.Plan plan, long worldSeed, int hour) {
        if (people.isEmpty()) return 0;
        int in = 0, total = 0;
        for (long day = 0; day < 20; day++) for (Resident who : people) { total++; if (where(who, plan, worldSeed, hour, day) != AWAY) in++; }
        return in / (double) total;
    }
}
