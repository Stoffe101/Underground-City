package io.github.stoffe101.undergroundcity;

/**
 * Pure arithmetic for gradual shared-property restocking (design: gradual-property-restock.md).
 * One authoritative inventory per property; full shelves never bank a backlog; resident
 * vigilance lengthens the interval so repeat burglary does not pay better.
 */
public final class PropertyRestock {
    public static final int MAX_BACKLOG_INTERVALS = 8, MAX_VIGILANCE = 3;
    private PropertyRestock() {}

    public record Result(int add, long last) {}

    /** Each vigilance level adds 25% to the restock interval. */
    public static long effectiveInterval(int intervalTicks, int vigilance) {
        if (intervalTicks < 1 || vigilance < 0 || vigilance > MAX_VIGILANCE) throw new IllegalArgumentException("Invalid restock timing");
        return (long) intervalTicks * (4 + vigilance) / 4;
    }

    public static Result reconcile(int stock, int capacity, long last, long now, int intervalTicks,
                                   int itemsPerInterval, int vigilance) {
        if (stock < 0 || capacity < 1 || itemsPerInterval < 1 || now < last) throw new IllegalArgumentException("Invalid saved property stock");
        if (stock >= capacity) return new Result(0, now); // no hidden backlog while full
        long interval = effectiveInterval(intervalTicks, vigilance);
        long intervals = Math.min(MAX_BACKLOG_INTERVALS, (now - last) / interval);
        if (intervals == 0) return new Result(0, last);
        long add = Math.min((long) capacity - stock, intervals * itemsPerInterval);
        long newLast = stock + add >= capacity ? now : last + intervals * interval;
        return new Result((int) add, newLast);
    }

    /** Vigilance falls one level per four base intervals of active play. Returns {level, anchorTick}. */
    public static long[] decayVigilance(int vigilance, long anchor, long now, int intervalTicks) {
        if (vigilance <= 0) return new long[] {0, now};
        long steps = (now - anchor) / (4L * intervalTicks);
        if (steps <= 0) return new long[] {vigilance, anchor};
        long remaining = Math.max(0, vigilance - steps);
        return new long[] {remaining, remaining == 0 ? now : anchor + steps * 4L * intervalTicks};
    }
}
