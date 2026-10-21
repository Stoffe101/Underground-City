package io.github.stoffe101.undergroundcity;

import java.util.Random;

/**
 * One lock being picked: the whole game of it, on the server, as pure arithmetic (no Minecraft types, so it is tested directly and the screen cannot disagree with it).
 *
 * <p>The lock has {@code pins}. For each pin there is a hidden sweet spot on a bar from 0 to 1. The player slides a pick along the bar (the mouse, or the arrow keys) and presses
 * (the left mouse button or space). Pressing on the sweet spot, steadily, for {@code holdTicks} sets that pin and moves on to the next; pressing anywhere else strains the pick, more the
 * further off it is, and a pick that is strained past its limit snaps. Letting go lets the strain ease. The lock also has a time limit. What the client is told is a coarse {@link #feel}
 * (how close the pick is, in four steps, sometimes wrong by one on the middle steps so a lock cannot be solved by a binary search of the bar) and the progress; <b>the sweet spots never
 * leave the server</b>. A small hand tremor moves the effective position of the pick while the button is held, so a pin cannot be set with the mouse parked and the button held. The tremor is a slow wobble
 * (one swing in a second and a half) that the screen draws too ({@link #tremorOffset}): it is a feature of the lock that the player can see and play against, and not noise. Nothing else in what the
 * player reads changes unless the pick moves: the feel is a function of where the pick is (shifted by a smooth, fixed error that differs from lock to lock, see {@link #readError}), so a pick that is
 * held still reads the same every tick. (The first version drew a new random error every tick and let the tremor move the reading while the button was up; the first human playtest called the screen jittery.)
 *
 * <p>It is a skill game and not a button: a careful player with a good pick opens a three-pin deadbolt in ten to twenty seconds; a hurried one snaps picks. Nothing about it is random
 * once it has started (it is seeded), so a test can replay a session exactly.
 */
public final class LockpickSession {
    public enum State { ACTIVE, OPENED, BROKEN, TIMED_OUT, ABORTED }

    /** The numbers of one kind of lock. Data: {@code data/underground_city/lock_profiles/<id>.json}. */
    public record Profile(String id, int pins, double tolerance, int holdTicks, double strainLimit, double strainPerTick, double easePerTick, int timeLimitTicks, double feelNoise,
                          double tremor, int lockoutTicks, double alarmChance) {
        public Profile {
            if (pins < 1 || pins > 8) throw new IllegalArgumentException("pins");
            if (tolerance <= 0.01 || tolerance > 0.3) throw new IllegalArgumentException("tolerance");
            if (holdTicks < 2 || strainLimit <= 0 || strainPerTick <= 0 || timeLimitTicks < 40) throw new IllegalArgumentException("timing");
        }
    }

    /** What the pick feels like: 0 far, 1 near, 2 close, 3 on the sweet spot. */
    public static final int FAR = 0, NEAR = 1, CLOSE = 2, ON = 3;

    /** Radians the hand's tremor advances each server tick: one swing in 30 ticks (a second and a half), slow enough to be seen and played against. */
    public static final double TREMOR_RATE = 0.21;

    /** The tremor's offset of the pick, in bar units, at a server tick; zero when the button is up. The screen draws the same offset so what is shown is what the server uses. */
    public static double tremorOffset(double amplitude, double phase, double tick, boolean pressing) { return pressing ? amplitude * Math.sin(tick * TREMOR_RATE + phase) : 0.0; }

    private final Profile profile;
    private final double[] sweet;
    private final double phase, errorA, errorB;
    private State state = State.ACTIVE;
    private int pin, held, timeLeft, tick;
    private double strain;
    private int feel;
    private boolean broke;

    public LockpickSession(Profile profile, long seed) {
        this.profile = profile;
        Random random = new Random(seed);
        sweet = new double[profile.pins()];
        // spread them over the bar so that two neighbouring pins are never the same place (that would be one pin and a free second)
        for (int i = 0; i < sweet.length; i++) {
            double candidate;
            int guard = 0;
            do { candidate = 0.06 + random.nextDouble() * 0.88; guard++; } while (guard < 50 && i > 0 && Math.abs(candidate - sweet[i - 1]) < 0.18);
            sweet[i] = candidate;
        }
        // the tremor's phase and the error of the reading are drawn apart from the sweet spots, so that neither can be used to find one
        Random apart = new Random(seed ^ 0x5DEECE66DL);
        phase = apart.nextDouble() * Math.PI * 2;
        errorA = apart.nextDouble() * Math.PI * 2;
        errorB = apart.nextDouble() * Math.PI * 2;
        timeLeft = profile.timeLimitTicks();
    }

    public State state() { return state; }
    public boolean active() { return state == State.ACTIVE; }
    public Profile profile() { return profile; }
    public int pin() { return pin; }
    public int pinsSet() { return pin; }
    public int timeLeft() { return timeLeft; }
    public int feel() { return feel; }
    /** The tremor's phase for the screen (independent of every sweet spot). */
    public double tremorPhase() { return phase; }
    /** Server ticks this lock has been worked (the screen keeps its wobble in step with it). */
    public int ticks() { return tick; }

    /**
     * How much the reading is off at a place on the bar, in bar units: a smooth curve (swings 0.2 and 0.09 of the bar long, never steeper than the bar itself for any lock, so sliding the pick toward the spot never makes the reading worse) scaled by the lock's feel noise and tolerance. It is fixed for a lock, so the
     * same place always reads the same, and it moves the edges of the near and close bands by up to a fifth of the tolerance, so the bar cannot be bisected by reading them.
     */
    private double readError(double at) {
        return profile.feelNoise() * profile.tolerance() * (Math.sin(at * 31.0 + errorA) + 0.5 * Math.sin(at * 71.0 + errorB)) / 1.5;
    }
    /** Strain as a share of the limit, 0 to 100. */
    public int strainPercent() { return (int) Math.min(100, Math.round(100 * strain / profile.strainLimit())); }
    public boolean pickBroke() { return broke; }

    /** The player gave up (Escape, walked away). The pick is not lost. */
    public void abort() { if (state == State.ACTIVE) state = State.ABORTED; }

    /**
     * One server tick. {@code position} is the pick's place on the bar (0 to 1) and {@code pressing} whether the button is held. Returns the state after this tick.
     */
    public State tick(double position, boolean pressing) {
        if (state != State.ACTIVE) return state;
        tick++;
        double effective = position + tremorOffset(profile.tremor(), phase, tick, pressing);
        double distance = Math.abs(effective - sweet[pin]);
        boolean on = distance <= profile.tolerance();
        // what the player reads comes from where the pick is, shifted by the lock's fixed error; the sweet spot itself (the press) is exact
        double read = Math.abs(effective - sweet[pin] + readError(effective));
        int truth = on ? ON : read <= profile.tolerance() * 2.6 ? CLOSE : read <= 0.28 ? NEAR : FAR;
        // Not pressing, the pick can tell "close" at best: the sweet spot only gives (ON) under a press, so the player must judge the middle of the close band and press there.
        feel = !pressing && truth == ON ? CLOSE : truth;
        if (pressing) {
            if (truth == ON) {
                held++;
                strain = Math.max(0, strain - profile.easePerTick() * 0.5);
                if (held >= profile.holdTicks()) {
                    pin++;
                    held = 0;
                    if (pin >= sweet.length) { state = State.OPENED; return state; }
                }
            } else {
                held = 0;
                // further off, more strain; right beside the sweet spot hardly any
                double off = Math.min(1.0, (distance - profile.tolerance()) / 0.35);
                strain += profile.strainPerTick() * (0.35 + 1.2 * off);
                if (strain >= profile.strainLimit()) { state = State.BROKEN; broke = true; return state; }
            }
        } else {
            held = 0;
            strain = Math.max(0, strain - profile.easePerTick());
        }
        if (--timeLeft <= 0) state = State.TIMED_OUT;
        return state;
    }

    /** Test seam: where the current pin's sweet spot is. The server never sends this to a client. */
    double sweetSpot() { return sweet[Math.min(pin, sweet.length - 1)]; }
    /** Test seam: every sweet spot. */
    double[] sweetSpots() { return sweet.clone(); }
}
