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
 *
 * <p><b>Spool pins</b> (D-145) make a better lock different, not just longer: a spool pin <i>false-sets</i>. Pressed on its spot it gives halfway, then the pick feels the core turn back and the spot
 * is somewhere else a short way along the bar; the player must ease off and find it again (the screen says "It turned back"). The thief's hands change the game too ({@link Hands}): Steady Hands halves
 * the tremor and slows the strain, and Feel for the Pins removes the reading's error and announces a spool pin before it catches.
 */
public final class LockpickSession {
    public enum State { ACTIVE, OPENED, BROKEN, TIMED_OUT, ABORTED }

    /**
     * The numbers of one kind of lock. Data: {@code data/underground_city/lock_profiles/<id>.json}. {@code spools} is how many of its pins false-set; {@code requiresTool} and {@code requiresSkill}
     * (empty for none) are what a burglar needs before the lock is even tried; {@code rakeChance} is the chance a rake opens it (0: it cannot be raked).
     */
    public record Profile(String id, int pins, double tolerance, int holdTicks, double strainLimit, double strainPerTick, double easePerTick, int timeLimitTicks, double feelNoise,
                          double tremor, int lockoutTicks, double alarmChance, int spools, String requiresTool, String requiresSkill, double rakeChance) {
        public Profile {
            if (pins < 1 || pins > 8) throw new IllegalArgumentException("pins");
            if (tolerance <= 0.01 || tolerance > 0.3) throw new IllegalArgumentException("tolerance");
            if (holdTicks < 2 || strainLimit <= 0 || strainPerTick <= 0 || timeLimitTicks < 40) throw new IllegalArgumentException("timing");
            if (spools < 0 || spools >= pins) throw new IllegalArgumentException("spools must be fewer than the pins");
            if (rakeChance < 0 || rakeChance > 0.9) throw new IllegalArgumentException("rake chance");
            requiresTool = requiresTool == null ? "" : requiresTool;
            requiresSkill = requiresSkill == null ? "" : requiresSkill;
        }
        public Profile(String id, int pins, double tolerance, int holdTicks, double strainLimit, double strainPerTick, double easePerTick, int timeLimitTicks, double feelNoise,
                       double tremor, int lockoutTicks, double alarmChance) {
            this(id, pins, tolerance, holdTicks, strainLimit, strainPerTick, easePerTick, timeLimitTicks, feelNoise, tremor, lockoutTicks, alarmChance, 0, "", "", 0);
        }
    }

    /** What the thief's own hands bring to a lock (D-143): a factor on the tremor and on the strain, and whether the reading is exact and spool pins are announced. */
    public record Hands(double tremorFactor, double strainFactor, boolean exactFeel) {
        public static final Hands UNTRAINED = new Hands(1.0, 1.0, false);
        /** The hands of a thief with these skills. */
        public static Hands of(boolean steady, boolean feel) { return new Hands(steady ? 0.5 : 1.0, steady ? 0.8 : 1.0, feel); }
    }

    /** How far along the bar a spool pin's spot moves when it turns back, at least. */
    static final double SPOOL_JUMP = 0.14;

    /** What the pick feels like: 0 far, 1 near, 2 close, 3 on the sweet spot. */
    public static final int FAR = 0, NEAR = 1, CLOSE = 2, ON = 3;

    /** Radians the hand's tremor advances each server tick: one swing in 30 ticks (a second and a half), slow enough to be seen and played against. */
    public static final double TREMOR_RATE = 0.21;

    /** The tremor's offset of the pick, in bar units, at a server tick; zero when the button is up. The screen draws the same offset so what is shown is what the server uses. */
    public static double tremorOffset(double amplitude, double phase, double tick, boolean pressing) { return pressing ? amplitude * Math.sin(tick * TREMOR_RATE + phase) : 0.0; }

    private final Profile profile;
    private final Hands hands;
    private final double[] sweet, spoolJump;
    private final boolean[] spool, turned;
    private final double phase, errorA, errorB;
    private State state = State.ACTIVE;
    private int pin, held, timeLeft, tick, spoolEvents;
    private double strain, peakStrain;
    private int feel;
    private boolean broke, justTurned;

    public LockpickSession(Profile profile, long seed) { this(profile, seed, Hands.UNTRAINED); }

    public LockpickSession(Profile profile, long seed, Hands hands) {
        this.profile = profile;
        this.hands = hands == null ? Hands.UNTRAINED : hands;
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
        // which pins are spools (never the first: a thief always gets one clean pin to learn the lock's feel), and which way each one turns back
        spool = new boolean[sweet.length];
        turned = new boolean[sweet.length];
        spoolJump = new double[sweet.length];
        Random spools = new Random(seed ^ 0x9E3779B97F4A7C15L);
        for (int placed = 0, guard = 0; placed < profile.spools() && guard < 64; guard++) {
            int i = 1 + spools.nextInt(Math.max(1, sweet.length - 1));
            if (spool[i]) continue;
            spool[i] = true; placed++;
            double jump = SPOOL_JUMP + spools.nextDouble() * 0.08;
            spoolJump[i] = sweet[i] + jump <= 0.94 ? jump : sweet[i] - jump >= 0.06 ? -jump : (sweet[i] > 0.5 ? -jump : jump);
        }
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
    /** The most strain the pick has been under at any moment of this lock, as a percent of its limit: how cleanly it has been worked (a quiet pick never goes far above the floor). */
    public int peakStrainPercent() { return (int) Math.min(100, Math.round(100 * Math.max(peakStrain, strain) / profile.strainLimit())); }
    public int strainPercent() { return (int) Math.min(100, Math.round(100 * strain / profile.strainLimit())); }
    public boolean pickBroke() { return broke; }
    /** True when the current pin is a spool pin that has not turned back yet and the thief's hands can tell (Feel for the Pins). */
    public boolean spoolAhead() { return hands.exactFeel() && state == State.ACTIVE && pin < spool.length && spool[pin] && !turned[pin]; }
    /** True on the tick a spool pin turned back (the screen says so once). */
    public boolean justTurned() { return justTurned; }
    /** How many spool pins have turned back in this lock so far. */
    public int spoolEvents() { return spoolEvents; }
    public Hands hands() { return hands; }

    /** The player gave up (Escape, walked away). The pick is not lost. */
    public void abort() { if (state == State.ACTIVE) state = State.ABORTED; }

    /** A rake that worked: the lock is open at once, every pin at once (D-143, Raking). */
    void rakedOpen() { if (state == State.ACTIVE) { pin = sweet.length; state = State.OPENED; } }

    /** A rake that did not work: the pick takes a jolt of this share of its limit, and may snap. */
    void jolt(double share) {
        if (state != State.ACTIVE) return;
        strain += profile.strainLimit() * share * hands.strainFactor();
        peakStrain = Math.max(peakStrain, strain);
        if (strain >= profile.strainLimit()) { state = State.BROKEN; broke = true; }
    }

    /**
     * One server tick. {@code position} is the pick's place on the bar (0 to 1) and {@code pressing} whether the button is held. Returns the state after this tick.
     */
    public State tick(double position, boolean pressing) {
        if (state != State.ACTIVE) return state;
        tick++;
        justTurned = false;
        double effective = position + tremorOffset(profile.tremor() * hands.tremorFactor(), phase, tick, pressing);
        double distance = Math.abs(effective - sweet[pin]);
        boolean on = distance <= profile.tolerance();
        // what the player reads comes from where the pick is, shifted by the lock's fixed error (none for a thief who can feel the pins truly); the sweet spot itself (the press) is exact
        double read = Math.abs(effective - sweet[pin] + (hands.exactFeel() ? 0 : readError(effective)));
        int truth = on ? ON : read <= profile.tolerance() * 2.6 ? CLOSE : read <= 0.28 ? NEAR : FAR;
        // Not pressing, the pick can tell "close" at best: the sweet spot only gives (ON) under a press, so the player must judge the middle of the close band and press there.
        feel = !pressing && truth == ON ? CLOSE : truth;
        if (pressing) {
            if (truth == ON) {
                held++;
                strain = Math.max(0, strain - profile.easePerTick() * 0.5);
                if (spool[pin] && !turned[pin] && held >= Math.max(2, profile.holdTicks() / 2)) {
                    // a spool pin false-sets: the core turns back and its spot is a little way along the bar now; the press is lost and the pick takes a jolt
                    turned[pin] = true;
                    sweet[pin] = Math.max(0.04, Math.min(0.96, sweet[pin] + spoolJump[pin]));
                    held = 0;
                    strain += profile.strainPerTick() * 4 * hands.strainFactor();
                    peakStrain = Math.max(peakStrain, strain);
                    justTurned = true; spoolEvents++;
                    feel = NEAR;
                    if (strain >= profile.strainLimit()) { state = State.BROKEN; broke = true; return state; }
                    if (--timeLeft <= 0) state = State.TIMED_OUT;
                    return state;
                }
                if (held >= profile.holdTicks()) {
                    pin++;
                    held = 0;
                    if (pin >= sweet.length) { state = State.OPENED; return state; }
                }
            } else {
                held = 0;
                // further off, more strain; right beside the sweet spot hardly any
                double off = Math.min(1.0, (distance - profile.tolerance()) / 0.35);
                strain += profile.strainPerTick() * (0.35 + 1.2 * off) * hands.strainFactor();
                peakStrain = Math.max(peakStrain, strain);
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
