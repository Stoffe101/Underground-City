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
    static LockpickSession.State solve(LockpickSession session, boolean careless, Hand hand) {
        final double step = 0.012;
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
            for (int t = 0; t < 40 && session.active() && session.pin() == pin; t++) hand.tick(aim, true);
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
            // an ordinary lock yields to a careful hand almost every time; a high-security one is meant to fail now and then even for a careful one (measured: about 4 in 5)
            double floor = id.equals("high_security") ? 0.6 : 0.8;
            check(opened >= trials * floor, id + ": a careful player opened only " + opened + " of " + trials + " locks");
            if (id.equals("high_security")) check(opened < trials, "a high-security lock opened for a careful player every time: it is no harder than a deadbolt");
        }
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
