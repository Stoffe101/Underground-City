package io.github.stoffe101.undergroundcity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The vocabulary and arithmetic of renting and buying a home (D-099). Pure data and integer maths with no Minecraft types, so the ledger, the
 * service, the guard and the screens use the same words and the same numbers, and the rules can be exercised without a world.
 *
 * <p>Time is <em>active</em> time: a rent cycle is {@link Rules#cycleMinutes()} minutes during which at least one resident of the home is
 * online. The clock advances once for the household however many residents are online, and not at all while nobody is. Money is Q Shop
 * credits; a rent or a purchase is a sink, never a second currency.
 *
 * <p>Standing of a tenancy, from the cycles that have come due and were not paid ({@code arrears}):
 * <ul>
 *   <li>CURRENT: nothing owed;</li>
 *   <li>OVERDUE: 1 to {@code graceCycles} unpaid cycles (the grace period: everything works, reminders only);</li>
 *   <li>NOTICE: the cycles of final notice that follow (everything still works, the warning is loud);</li>
 *   <li>LAPSED: beyond that. The home is never emptied, re-let or deleted: the tenant keeps the door and every container and loses only the comfort
 *       of the home (building and workstations) until the arrears are paid, and may move out at any time.</li>
 * </ul>
 */
public final class PropertyRules {
    private PropertyRules() {}

    public static final int TICKS_PER_MINUTE = 1200;

    public enum Tenure { NONE, RENT, OWN }

    public enum Standing { CURRENT, OVERDUE, NOTICE, LAPSED }

    /** The global rules of the property economy. Defaults are the ones shipped in {@code data/underground_city/properties/ladder.json}. */
    public record Rules(int cycleMinutes, int prepayMaxCycles, int graceCycles, int noticeCycles, int depositCycles, int sellBackPercent,
                        int welcomeMotelCycles, Map<String, Integer> cityFactorPct) {
        public Rules { cityFactorPct = Map.copyOf(cityFactorPct); }
        public static final Rules DEFAULT = new Rules(60, 6, 2, 1, 2, 60, 2, Map.of("ironhaven", 115, "valewood", 90, "blackwater", 100));
        public long cycleTicks() { return (long) cycleMinutes * TICKS_PER_MINUTE; }
        /** Percent of the listed price in force in this city (100 outside every city circle). */
        public int factorFor(String city) { return city == null || city.isEmpty() ? 100 : cityFactorPct.getOrDefault(city, 100); }
        /** Unpaid cycles at which the home lapses. */
        public int lapseAt() { return graceCycles + noticeCycles + 1; }
    }

    /** What a home costs and allows. The numbers are the base prices; {@link #scaled} applies the city's factor at signing. */
    public record Terms(String id, String title, String category, int tier, String area, Set<Tenure> tenures, int rent, int deposit, int buyPrice, int upkeep,
                        int maxContainers, int privateClaims, Set<String> facilities, String summary) {
        public Terms { tenures = Set.copyOf(tenures); facilities = Set.copyOf(facilities); }
        public boolean canRent() { return tenures.contains(Tenure.RENT) && rent > 0; }
        public boolean canBuy() { return tenures.contains(Tenure.OWN) && buyPrice > 0; }
        public boolean has(String facility) { return facilities.contains(facility); }
        /** The same terms under another listing id (a unit of a furnished city building takes its building's template, D-127). */
        public Terms withId(String newId) { return new Terms(newId, title, category, tier, area, tenures, rent, deposit, buyPrice, upkeep, maxContainers, privateClaims, facilities, summary); }
    }

    /** A purchasable improvement of a home that is OWNED. Each one is a capability the guard enforces, not a counter. */
    public record Upgrade(String id, String title, int cost, int addContainers, int addClaims, Set<String> addFacilities, String summary) {
        public Upgrade { addFacilities = Set.copyOf(addFacilities); }

        /** The upgrade at the price a tradesperson with the skill pays: the discount is rounded down, so the city never gives away a credit it did not offer. */
        public Upgrade discounted(int percent) {
            int off = (int) ((long) cost * Math.max(0, Math.min(100, percent)) / 100);
            return new Upgrade(id, title, Math.max(1, cost - off), addContainers, addClaims, addFacilities, summary);
        }
    }

    public static int scaled(int base, int factorPct) { return (int) Math.round(base * (double) factorPct / 100.0); }

    /** The standing of a tenancy from its unpaid cycles. */
    public static Standing standing(int arrears, Rules rules) {
        if (arrears <= 0) return Standing.CURRENT;
        if (arrears <= rules.graceCycles()) return Standing.OVERDUE;
        if (arrears < rules.lapseAt()) return Standing.NOTICE;
        return Standing.LAPSED;
    }

    /** Active minutes left until the next cycle falls due, from the ticks already counted in this cycle. */
    public static int minutesToNextCycle(long progressTicks, Rules rules) {
        long left = Math.max(0, rules.cycleTicks() - progressTicks);
        return (int) ((left + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE);
    }

    /** How a payment of {@code cycles} is applied: first to cycles that have come due, then to the arrears, the rest paid in advance. */
    public record Applied(int due, int arrears, int prepaid) {}

    public static Applied apply(int cycles, int due, int arrears) {
        int toDue = Math.min(cycles, due);
        int toArrears = Math.min(cycles - toDue, arrears);
        return new Applied(toDue, toArrears, cycles - toDue - toArrears);
    }

    /** Cycles a payment can still add: what is owed (due or in arrears) plus the room left under the prepayment cap. */
    public static int payableCycles(int due, int arrears, int prepaid, Rules rules) {
        return Math.max(0, due + arrears + Math.max(0, rules.prepayMaxCycles() - prepaid));
    }

    /** What a purchased home returns when it is sold back to the city: the stated percent of what was paid, less what is owed. Never more than was paid. */
    public static int sellBack(int paidPrice, int owedCredits, Rules rules) {
        long back = (long) paidPrice * Math.min(100, Math.max(0, rules.sellBackPercent())) / 100;
        return (int) Math.max(0, back - Math.max(0, owedCredits));
    }

    /** The deposit returned when a tenant moves out: all of it, less what is owed. */
    public static int depositBack(int deposit, int owedCredits) { return Math.max(0, deposit - Math.max(0, owedCredits)); }

    /** The cheapest facilities every unit lists: a unit without a listing (a custom district) has none of the guard's extra rules. */
    public static final List<String> KNOWN_FACILITIES = List.of("bed", "storage", "crafting", "furnace", "terminal", "workshop", "plumbing", "trade");
}
