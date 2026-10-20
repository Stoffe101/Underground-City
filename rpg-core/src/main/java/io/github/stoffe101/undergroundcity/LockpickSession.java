package io.github.stoffe101.undergroundcity;

import java.util.Random;

/**
 * One lock being picked: the whole game of it, on the server, as pure arithmetic (no Minecraft types, so it is tested directly and the screen cannot disagree with it).
 *
 * <p>The lock has {@code pins}. For each pin there is a hidden sweet spot on a bar from 0 to 1. The player slides a pick along the bar (the mouse, or the arrow keys) and presses
 * (the left mouse button or space). Pressing on the sweet spot, steadily, for {@code holdTicks} sets that pin and moves on to the next; pressing anywhere else strains the pick, more the
 * further off it is, and a pick that is strained past its limit snaps. Letting go lets the strain ease. The lock also has a time limit. What the client is told is a coarse {@link #feel}
 * (how close the pick is, in four steps, sometimes wrong by one on the middle steps so a lock cannot be solved by a binary search of the bar) and the progress; <b>the sweet spots never
 * leave the server</b>. A small hand tremor moves the effective position of the pick, so a pin cannot be set with the mouse parked and the button held.
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

    private final Profile profile;
    private final double[] sweet;
    private final Random noise;
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
        noise = new Random(seed ^ 0x5DEECE66DL);
        timeLeft = profile.timeLimitTicks();
    }

    public State state() { return state; }
    public boolean active() { return state == State.ACTIVE; }
    public Profile profile() { return profile; }
    public int pin() { return pin; }
    public int pinsSet() { return pin; }
    public int timeLeft() { return timeLeft; }
    public int feel() { return feel; }
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
        double effective = position + profile.tremor() * Math.sin(tick * 0.37 + sweet[0] * 9.0) * (pressing ? 1.0 : 0.4);
        double distance = Math.abs(effective - sweet[pin]);
        int truth = distance <= profile.tolerance() ? ON : distance <= profile.tolerance() * 2.6 ? CLOSE : distance <= 0.28 ? NEAR : FAR;
        // Not pressing, the pick can tell "close" at best: the sweet spot only gives (ON) under a press, so the player must judge the middle of the close band and press there.
        // The two middle steps can also be off by one, so the bar cannot be bisected.
        feel = !pressing && truth == ON ? CLOSE : truth;
        if ((feel == NEAR || feel == CLOSE) && noise.nextDouble() < profile.feelNoise()) feel = feel == NEAR ? CLOSE : NEAR;
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
