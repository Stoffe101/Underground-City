package io.github.stoffe101.undergroundcity;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;

/**
 * The lockpicking game ({@link LockpickSession}) as arithmetic. The solver below is an honest player: it moves the pick, reads only what the client is told ({@code feel}, the state, the
 * progress) and presses where it judges the middle of the close band to be. It never reads a sweet spot, so "a pin can be set without knowing where it is" is not an accident of the test.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LockpickSessionGameTests {
    private LockpickSessionGameTests() {}

    private static LockpickSession.Profile profile(String id) { return LockProfiles.parse(id, profileJson(id)); }

    private static com.google.gson.JsonObject profileJson(String id) {
        try (var in = LockpickSessionGameTests.class.getResourceAsStream("/data/underground_city/lock_profiles/" + id + ".json")) {
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    /**
     * Sweeps the bar once at a hand's pace (about four seconds end to end) reading the feel, smooths the readings (the middle steps are deliberately wrong now and then) and presses in the middle of the
     * longest stretch that mostly reads "close"; repeats for every pin. Returns the final state. A real sweep costs time on the lock's clock, so this solver is not given a faster hand than a person has.
     */
    static LockpickSession.State solve(LockpickSession session, boolean careless) { return solve(session, careless, session::tick); }

    /** One server tick of a lock being worked: the position of the pick and whether the button is down. */
    interface Hand { void tick(double position, boolean pressing); }

    /** The same honest player, but the ticks go through {@code hand} (for example the server's own per-tick handling, with its endings), the feel is still read from the session. */
    static LockpickSession.State solve(LockpickSession session, boolean careless, Hand hand) { return solve(session, careless, hand, 0.012); }

    /** {@code step} is how far the hand slides per tick while reading the bar: 0.012 is four seconds end to end, 0.005 is ten. */
    static LockpickSession.State solve(LockpickSession session, boolean careless, Hand hand, double step) {
        int guard = 0;
        while (session.active() && guard++ < 6000) {
            int pin = session.pin();
            List<Double> positions = new ArrayList<>();
            List<Boolean> close = new ArrayList<>();
            for (double p = 0.0; p <= 1.0 && session.active() && session.pin() == pin; p += step) {
                hand.tick(p, false);
                positions.add(p);
                close.add(session.feel() >= LockpickSession.CLOSE);
            }
            if (!session.active()) break;
            double[] best = null;
            double runStart = -1, last = -1;
            for (int i = 0; i < positions.size(); i++) {
                int hits = 0, total = 0;
                for (int j = Math.max(0, i - 3); j <= Math.min(positions.size() - 1, i + 3); j++) { total++; if (close.get(j)) hits++; }
                boolean smoothed = hits * 2 > total;
                if (smoothed) { if (runStart < 0) runStart = positions.get(i); last = positions.get(i); }
                else if (runStart >= 0) { if (best == null || last - runStart > best[1] - best[0]) best = new double[] {runStart, last}; runStart = -1; }
            }
            if (runStart >= 0 && (best == null || last - runStart > best[1] - best[0])) best = new double[] {runStart, last};
            if (best == null) continue;
            double aim = careless ? best[0] : (best[0] + best[1]) / 2;
            // a person reads the screen while pressing: when a spool pin turns back ("It turned back") they ease off and look for the spot again
            for (int t = 0; t < 40 && session.active() && session.pin() == pin; t++) { hand.tick(aim, true); if (session.justTurned()) break; }
        }
        return session.state();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aCarefulPlayerOpensEveryKindOfLockByReadingTheFeel(GameTestHelper helper) {
        for (String id : new String[] {"latch", "deadbolt", "commercial", "high_security", "safe"}) {
            int opened = 0, trials = 30;
            for (int seed = 0; seed < trials; seed++) {
                LockpickSession session = new LockpickSession(profile(id), 1000L + seed);
                if (solve(session, false) == LockpickSession.State.OPENED) opened++;
            }
            // a hand at a person's pace opens every kind of lock; how hard a lock is shows in how long a careful hand may take (the next test), not in a reading that lies from tick to tick
            check(opened >= trials * 0.8, id + ": a careful player opened only " + opened + " of " + trials + " locks");
        }
        helper.succeed();
    }

    /**
     * The first version made a high-security lock hard by drawing a new random error into the reading every tick, which a person sees as a flickering screen. Its difficulty is now the time it takes:
     * five pins and a tight band, so a hand that reads the bar slowly and carefully (ten seconds a pass) opens a deadbolt but runs out of time on a high-security lock.
     */
    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aSlowCarefulHandOpensADeadboltButRunsOutOfTimeOnAHighSecurityLock(GameTestHelper helper) {
        int deadbolt = 0, high = 0, trials = 30;
        for (int seed = 0; seed < trials; seed++) {
            LockpickSession a = new LockpickSession(profile("deadbolt"), 300L + seed), b = new LockpickSession(profile("high_security"), 300L + seed);
            if (solve(a, false, a::tick, 0.005) == LockpickSession.State.OPENED) deadbolt++;
            if (solve(b, false, b::tick, 0.005) == LockpickSession.State.OPENED) high++;
        }
        check(deadbolt >= trials * 0.9, "a slow careful hand opened only " + deadbolt + " of " + trials + " deadbolts");
        check(high <= trials / 3, "a slow careful hand opened " + high + " of " + trials + " high-security locks: it is no harder than a deadbolt");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aCarelessPlayerSnapsPicksAndHarderLocksSnapMore(GameTestHelper helper) {
        int[] opened = new int[2];
        String[] ids = {"deadbolt", "high_security"};
        for (int k = 0; k < 2; k++) for (int seed = 0; seed < 40; seed++) {
            LockpickSession session = new LockpickSession(profile(ids[k]), 77L + seed);
            if (solve(session, true) == LockpickSession.State.OPENED) opened[k]++;
        }
        check(opened[0] < 40, "pressing at the edge of the band opened every deadbolt: the game is a button");
        check(opened[1] <= opened[0], "a high-security lock was easier to open carelessly than a deadbolt (" + opened[1] + " against " + opened[0] + ")");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aCleanPickIsAQuietOneAndACarelessPickIsNot(GameTestHelper helper) {
        // D-122: a door picked with the pick never above LockService.QUIET_STRAIN_PERCENT of its strain limit does not trip an alarm built into the building
        int quietCareful = 0, quietCareless = 0, openedCareful = 0, openedCareless = 0, trials = 40;
        int worstCareful = 0;
        for (int seed = 0; seed < trials; seed++) {
            LockpickSession careful = new LockpickSession(profile("deadbolt"), 4000L + seed), careless = new LockpickSession(profile("deadbolt"), 4000L + seed);
            if (solve(careful, false) == LockpickSession.State.OPENED) { openedCareful++; if (careful.peakStrainPercent() < LockService.QUIET_STRAIN_PERCENT) quietCareful++; worstCareful = Math.max(worstCareful, careful.peakStrainPercent()); }
            if (solve(careless, true) == LockpickSession.State.OPENED) { openedCareless++; if (careless.peakStrainPercent() < LockService.QUIET_STRAIN_PERCENT) quietCareless++; }
        }
        check(openedCareful >= trials * 8 / 10, "setup: the careful solver opened only " + openedCareful + " of " + trials);
        check(quietCareful >= openedCareful * 7 / 10, "a careful hand's picks are rarely quiet: " + quietCareful + " of " + openedCareful + " opened were quiet (worst peak " + worstCareful + " percent)");
        check(quietCareless * 2 <= Math.max(1, openedCareless), "a careless hand's picks are often quiet: " + quietCareless + " of " + openedCareless + " opened");
        // the measure is the most the pick was ever strained, not what it ended at
        LockpickSession session = new LockpickSession(profile("deadbolt"), 8L);
        double away = session.sweetSpot() < 0.5 ? 0.97 : 0.03;
        for (int t = 0; t < 12; t++) session.tick(away, true);
        int peak = session.peakStrainPercent();
        for (int t = 0; t < 60; t++) session.tick(away, false);
        check(peak > 0 && session.strainPercent() < peak && session.peakStrainPercent() == peak, "the peak is not the most the pick was strained: peak " + peak + ", now " + session.strainPercent() + ", peak after easing " + session.peakStrainPercent());
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void pressingAwayFromTheSweetSpotBreaksThePickAndNeverOpensTheLock(GameTestHelper helper) {
        LockpickSession session = new LockpickSession(profile("deadbolt"), 5L);
        double away = session.sweetSpot() < 0.5 ? 0.97 : 0.03;
        int ticks = 0;
        while (session.active() && ticks++ < 400) session.tick(away, true);
        check(session.state() == LockpickSession.State.BROKEN && session.pickBroke(), "the pick did not snap: " + session.state());
        check(ticks < 120, "strain took " + ticks + " ticks to snap a pick pressed far from the spot");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void parkingTheMouseOnTheSpotWithoutHoldingNeverSetsAPin(GameTestHelper helper) {
        LockpickSession session = new LockpickSession(profile("latch"), 9L);
        double spot = session.sweetSpot();
        for (int tick = 0; tick < 3000 && session.active(); tick++) session.tick(spot, false);
        check(session.pinsSet() == 0, "a pin was set with no press");
        check(session.state() == LockpickSession.State.TIMED_OUT, "the lock did not time out: " + session.state());
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aShortPressOnTheSpotDoesNotSetThePinButALongOneDoes(GameTestHelper helper) {
        LockpickSession session = new LockpickSession(profile("deadbolt"), 21L);
        double spot = session.sweetSpot();
        for (int tick = 0; tick < session.profile().holdTicks() - 3; tick++) session.tick(spot, true);
        check(session.pinsSet() == 0, "the pin was set before the hold time");
        session.tick(spot, false);
        for (int tick = 0; tick < session.profile().holdTicks() - 3; tick++) session.tick(spot, true);
        check(session.pinsSet() == 0, "letting go did not reset the hold");
        for (int tick = 0; tick < 6; tick++) session.tick(spot, true);
        check(session.pinsSet() == 1, "a full hold on the spot did not set the pin (tremor allowed for: " + session.state() + ")");
        helper.succeed();
    }

    private static final String[] LADDER = {"latch", "deadbolt", "commercial", "high_security", "safe"};

    /** The first human playtest called the screen jittery. The cause that could be measured: the reading changed by itself, many times a second, with the pick held still. */
    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aPickHeldStillReadsTheSameFeelEveryTick(GameTestHelper helper) {
        for (String id : LADDER) for (int seed = 0; seed < 12; seed++) for (double at = 0.02; at < 1.0; at += 0.07) {
            LockpickSession session = new LockpickSession(profile(id), 500L + seed);
            session.tick(at, false);
            int first = session.feel(), changes = 0;
            for (int t = 0; t < 120; t++) { session.tick(at, false); if (session.feel() != first) changes++; }
            check(changes == 0, id + " seed " + seed + ": the feel of a pick held still at " + at + " changed " + changes + " times in six seconds");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void slidingTheBarTowardTheSpotNeverMakesTheReadingWorseAndAwayNeverBetter(GameTestHelper helper) {
        for (String id : LADDER) for (int seed = 0; seed < 20; seed++) {
            LockpickSession probe = new LockpickSession(profile(id), 900L + seed);
            double spot = probe.sweetSpot();
            int worse = 0, better = 0, previous = -1;
            LockpickSession toward = new LockpickSession(profile(id), 900L + seed);
            for (double p = 0.0; p < spot; p += 0.004) {
                toward.tick(p, false);
                if (previous >= 0 && toward.feel() < previous) worse++;
                previous = toward.feel();
            }
            previous = -1;
            LockpickSession away = new LockpickSession(profile(id), 900L + seed);
            for (double p = spot; p <= 1.0; p += 0.004) {
                away.tick(p, false);
                if (previous >= 0 && away.feel() > previous) better++;
                previous = away.feel();
            }
            check(worse == 0, id + " seed " + seed + ": the reading got worse " + worse + " times while the pick slid toward the spot");
            check(better == 0, id + " seed " + seed + ": the reading got better " + better + " times while the pick slid away from the spot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void theTremorIsSlowVisibleAndOnlyWhileThePickIsPressed(GameTestHelper helper) {
        check(LockpickSession.tremorOffset(0.01, 1.0, 40, false) == 0.0, "a pick that is not pressed shakes");
        double max = 0, peak = 0;
        int crossings = 0;
        double last = 0;
        for (int t = 0; t < 300; t++) {
            double offset = LockpickSession.tremorOffset(0.01, 0.4, t, true);
            peak = Math.max(peak, Math.abs(offset));
            if (t > 0 && Math.signum(offset) != Math.signum(last)) crossings++;
            last = offset;
            max = Math.max(max, Math.abs(offset - (t > 0 ? last : 0)));
        }
        check(peak > 0.0099 && peak <= 0.01 + 1e-9, "the tremor does not reach its amplitude, or passes it: " + peak);
        check(crossings <= 21, "the tremor swings " + crossings + " times in fifteen seconds (more than once every 1.4 seconds): too fast to see or play against");
        // the server's own use of it is the same function the screen draws
        LockpickSession session = new LockpickSession(profile("deadbolt"), 31L);
        double spot = session.sweetSpot();
        session.tick(spot, true);
        check(Math.abs(LockpickSession.tremorOffset(session.profile().tremor(), session.tremorPhase(), session.ticks(), true)) <= session.profile().tremor() + 1e-9, "the screen's tremor is not the server's");
        helper.succeed();
    }

    /** A late position must not turn into a strain: the position and the press travel in one message, so a pick that is late is late as a whole. */
    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aLateInputIsLateAsAWholeAndNeverPressesOnAnOldPosition(GameTestHelper helper) {
        for (int delay : new int[] {0, 2, 5, 10}) {
            LockpickSession session = new LockpickSession(profile("deadbolt"), 61L);
            double spot = session.sweetSpot(), far = spot < 0.5 ? 0.9 : 0.1;
            java.util.ArrayDeque<double[]> wire = new java.util.ArrayDeque<>();
            double[] seen = {far, 0};
            // the player sits at a far place, then moves to the spot and presses in the same moment (one message); the server hears it `delay` ticks later
            for (int t = 0; t < 12 + delay + 40 && session.active() && session.pinsSet() == 0; t++) {
                double[] sent = t < 12 ? new double[] {far, 0} : new double[] {spot, 1};
                wire.addLast(sent);
                if (wire.size() > delay) seen = wire.pollFirst();
                session.tick(seen[0], seen[1] > 0);
            }
            check(session.pinsSet() == 1, "with " + delay + " ticks of delay the pin was not set (" + session.state() + ", strain " + session.strainPercent() + ")");
            check(session.strainPercent() == 0, "with " + delay + " ticks of delay a press landed on the old position (strain " + session.strainPercent() + ")");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void aLockIsReproducibleFromItsSeedAndDifferentSeedsDiffer(GameTestHelper helper) {
        var a = new LockpickSession(profile("commercial"), 123L).sweetSpots();
        var b = new LockpickSession(profile("commercial"), 123L).sweetSpots();
        var c = new LockpickSession(profile("commercial"), 124L).sweetSpots();
        check(java.util.Arrays.equals(a, b), "the same seed gave two different locks");
        check(!java.util.Arrays.equals(a, c), "two seeds gave the same lock");
        check(a.length == 4, "a commercial lock has four pins");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_lockpick")
    public static void theProfilesGetHarderStepByStep(GameTestHelper helper) {
        String[] ladder = {"latch", "deadbolt", "commercial", "high_security"};
        for (int i = 1; i < ladder.length; i++) {
            var easier = profile(ladder[i - 1]); var harder = profile(ladder[i]);
            check(harder.pins() > easier.pins(), ladder[i] + " does not have more pins than " + ladder[i - 1]);
            check(harder.tolerance() < easier.tolerance(), ladder[i] + " is not tighter than " + ladder[i - 1]);
            check(harder.alarmChance() >= easier.alarmChance() && harder.lockoutTicks() >= easier.lockoutTicks(), ladder[i] + " is not riskier to fail");
        }
        check(profile("safe").pins() >= 4, "a household safe has at least four pins");
        helper.succeed();
    }
}
