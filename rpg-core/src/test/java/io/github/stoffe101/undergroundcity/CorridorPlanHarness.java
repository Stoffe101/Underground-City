package io.github.stoffe101.undergroundcity;

import java.util.List;

/** Pure Java 17 tests for the road geometry: run without Minecraft or Forge. */
public final class CorridorPlanHarness {
    /** Flat ground at 70, a lake from x=300 to x=500 (surface 63, floor 41), a 40-block hill around x=800. */
    private static final CorridorPlan.Terrain WORLD = new CorridorPlan.Terrain() {
        @Override public int surface(int x, int z) {
            if (x >= 300 && x <= 500) return 63;
            return floor(x, z);
        }
        @Override public int floor(int x, int z) {
            if (x >= 300 && x <= 500) return 41;
            if (x >= 760 && x <= 840) return 70 + 40 - Math.abs(x - 800);
            return 70;
        }
    };

    public static void main(String[] args) {
        // A city circle at the start: the road only exists beyond it.
        CorridorPlan plan = new CorridorPlan("test", new double[]{0, 600, 1200}, new double[]{0, 0, 0}, 7,
            List.of(new CorridorPlan.City(0, 0, 100)));
        plan.build(WORLD);
        check(Math.abs(plan.length() - 1200) < 1e-9, "Length must follow the polyline");
        check(!plan.openAt(50), "Road must not enter a city circle");
        check(plan.openAt(150), "Road must exist outside the city");
        check(plan.nearest(50, 0) == null, "No hit inside the city circle");
        var onLine = plan.nearest(300, 0.0);
        check(onLine != null && Math.abs(onLine.s() - 300) < 1e-9 && onLine.distance() == 0, "Centre-line hit expected");
        var beside = plan.nearest(300, 2.0);
        check(beside != null && Math.abs(Math.abs(beside.u()) - 2.0) < 1e-9, "Lateral offset must be measured");
        check(plan.nearest(300, 20) == null, "Columns beyond the road reach are untouched");
        var left = plan.nearest(300, 2.0);
        var right = plan.nearest(300, -2.0);
        check(Math.signum(left.u()) == -Math.signum(right.u()), "Sides have opposite signs");

        // Slope limit: no two neighbouring samples differ by more than the limit.
        check(plan.maxProfileStep() <= CorridorPlan.MAX_RISE_HALF, "Profile step " + plan.maxProfileStep());
        for (double s = 101; s < 1199; s += 1) {
            int difference = Math.abs(plan.deckHalfAt(s + 1) - plan.deckHalfAt(s));
            check(difference <= 1, "Deck jumps more than half a block at s=" + s + " (" + difference + ")");
        }
        // Bridge clearance over the lake: four free blocks between the water and the beam under the deck.
        for (double s = 305; s <= 495; s += 5) {
            int deckY = CorridorPlan.deckBlockY(plan.deckHalfAt(s));
            check(deckY - 1 - 63 >= CorridorPlan.BRIDGE_CLEARANCE, "Boat clearance lost at s=" + s + " deckY=" + deckY);
            check(plan.bridgeAt(s), "Lake must be flagged as a bridge at s=" + s);
        }
        check(!plan.bridgeAt(200), "Flat land is not a bridge");
        check(!plan.bridgeAt(1100), "Flat land after the hill is not a bridge");
        // Flat ground: the deck sits flush with the surface (top at y=70, a full block at y=69).
        int flat = plan.deckHalfAt(200);
        check(flat == 140, "Flat deck should be flush, got half=" + flat);
        check(CorridorPlan.deckBlockY(flat) == 69 && !CorridorPlan.isSlab(flat), "Flush full block expected");
        check(CorridorPlan.isSlab(141) && CorridorPlan.deckBlockY(141) == 70, "Odd heights are bottom slabs");
        // The hill is cut but never by more than the cutting limit.
        int top = plan.deckHalfAt(800);
        check(top >= 2 * (110 - CorridorPlan.MAX_CUT), "Cut deeper than the limit, half=" + top);
        // Determinism: a rebuilt plan is identical.
        CorridorPlan again = new CorridorPlan("test", new double[]{0, 600, 1200}, new double[]{0, 0, 0}, 7,
            List.of(new CorridorPlan.City(0, 0, 100)));
        again.build(WORLD);
        for (double s = 150; s <= 1200; s += 7) check(plan.deckHalfAt(s) == again.deckHalfAt(s), "Profile must be deterministic");
        check(plan.bridgeSamples() >= 40, "Lake bridge expected, samples=" + plan.bridgeSamples());

        // Smoothing never lowers a hard minimum and bounds every neighbour difference, for awkward inputs.
        int[] target = {140, 140, 140, 400, 140, 140, 20, 140};
        int[] minimum = {100, 100, 100, 400, 100, 100, 20, 100};
        boolean[] open = {true, true, true, true, true, true, true, true};
        int[] smooth = CorridorPlan.smooth(target, minimum, open);
        for (int i = 0; i < smooth.length; i++) {
            check(smooth[i] >= minimum[i], "Minimum violated at " + i);
            if (i > 0) check(Math.abs(smooth[i] - smooth[i - 1]) <= CorridorPlan.MAX_RISE_HALF, "Step too large at " + i);
        }
        // A gap in the open flags separates runs: nothing is produced inside the city sample.
        int[] split = CorridorPlan.smooth(new int[]{140, 140, 999, 140, 140}, new int[]{100, 100, 0, 100, 100},
            new boolean[]{true, true, false, true, true});
        check(split[2] == 0 && split[0] == 140 && split[3] == 140, "Closed samples are ignored");

        // Chunk rejection is conservative: every chunk with road columns is reported near.
        for (int x = 160; x < 1100; x += 16) check(plan.nearChunk(x - Math.floorMod(x, 16), -8), "Chunk beside the road missed at x=" + x);
        check(!plan.nearChunk(160, 400), "Distant chunk must be rejected");
        System.out.println("CorridorPlan tests passed.");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
