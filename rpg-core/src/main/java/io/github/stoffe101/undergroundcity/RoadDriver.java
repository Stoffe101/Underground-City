package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

/**
 * A scripted driver for the vehicle checks: it holds the real W key and taps A and D (through the X11 input helper, which is what Immersive
 * Vehicles reads) to keep a car on the generated road, steering from the heading it measures from the vehicle's own movement. It reports
 * what a passenger would notice: how long the trip took, how fast the vehicle went, how far it strayed from the centreline and how long it
 * spent on bridge decks. Client-side and test-only; it does not know any vehicle's internals.
 */
final class RoadDriver {
    private static final double LOOK_AHEAD = 12.0, TURN_THRESHOLD_DEGREES = 5.0;
    private static final int STANDING_FAIL_TICKS = 400;

    /** What the driver steers along: a generated road, or a straight line across water. */
    interface Route {
        CorridorPlan.Hit project(double x, double z);
        double[] pointAt(double s);
        boolean bridgeAt(double s);
        double length();
        double reach();
    }

    static Route of(CorridorPlan plan) {
        return new Route() {
            public CorridorPlan.Hit project(double x, double z) { return plan.project(x, z); }
            public double[] pointAt(double s) { return plan.pointAt(s); }
            public boolean bridgeAt(double s) { return plan.bridgeAt(s); }
            public double length() { return plan.length(); }
            public double reach() { return plan.reach(); }
        };
    }

    /** The road's own centreline shifted sideways by {@code offset} blocks (to the left of the direction of travel), so a boat can sail under a deck beside its piers. */
    static Route beside(CorridorPlan plan, double offset, double halfWidth) {
        return new Route() {
            private double[] shifted(double s) {
                double[] at = plan.pointAt(s), ahead = plan.pointAt(Math.min(plan.length(), s + 2)), behind = plan.pointAt(Math.max(0, s - 2));
                double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], length = Math.hypot(tx, tz);
                return length < 1e-6 ? at : new double[]{at[0] - tz / length * offset, at[1] + tx / length * offset};
            }
            public CorridorPlan.Hit project(double x, double z) {
                double s = plan.project(x, z).s();
                double[] on = shifted(s);
                return new CorridorPlan.Hit(s, 0, Math.hypot(x - on[0], z - on[1]));
            }
            public double[] pointAt(double s) { return shifted(s); }
            public boolean bridgeAt(double s) { return plan.bridgeAt(s); }
            public double length() { return plan.length(); }
            public double reach() { return halfWidth; }
        };
    }

    /** A straight line from one point to another on the ground plane; {@code halfWidth} is how far from it counts as still on course. */
    static Route line(double x0, double z0, double x1, double z1, double halfWidth) {
        double length = Math.hypot(x1 - x0, z1 - z0), ux = (x1 - x0) / length, uz = (z1 - z0) / length;
        return new Route() {
            public CorridorPlan.Hit project(double x, double z) {
                double s = Math.max(0, Math.min(length, (x - x0) * ux + (z - z0) * uz));
                return new CorridorPlan.Hit(s, 0, Math.hypot(x - (x0 + ux * s), z - (z0 + uz * s)));
            }
            public double[] pointAt(double s) { double c = Math.max(0, Math.min(length, s)); return new double[]{x0 + ux * c, z0 + uz * c}; }
            public boolean bridgeAt(double s) { return false; }
            public double length() { return length; }
            public double reach() { return halfWidth; }
        };
    }

    private final Route plan;
    private final double targetS;
    private final String label;
    private double progress, maxDeviation, topSpeed;
    private boolean w, a, d, done, failed;
    private long startGameTime = -1;
    private int bridgeTicks, standingTicks, nextLog = 100, ticks, offRoadTicks;
    private final ArrayDeque<Vec3> trail = new ArrayDeque<>();
    private String summary = "";
    private final java.util.TreeMap<Double, Runnable> milestones = new java.util.TreeMap<>();

    /** Runs {@code action} once, the first time the vehicle's progress along the route reaches {@code s}. */
    RoadDriver at(double s, Runnable action) { milestones.put(s, action); return this; }

    RoadDriver(Route plan, double targetS, String label) { this.plan = plan; this.targetS = targetS; this.label = label; }

    boolean failed() { return failed; }
    String summary() { return summary; }
    double progress() { return progress; }

    /** Called once per client tick while driving; returns true once the drive is over (arrived or failed). */
    boolean tick() {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (done) return true;
        if (player == null || mc.level == null) return false;
        var ride = player.getVehicle();
        if (ride == null) { finish(true, "the player is not riding anything"); return true; }
        if (startGameTime < 0) { startGameTime = mc.level.getGameTime(); Playtest.focusWindow(); }
        ticks++;
        Vec3 position = ride.position();
        trail.addLast(position);
        if (trail.size() > 11) trail.removeFirst();
        CorridorPlan.Hit hit = plan.project(position.x, position.z);
        progress = Math.max(progress, hit.s());
        maxDeviation = Math.max(maxDeviation, hit.distance());
        if (plan.bridgeAt(hit.s())) bridgeTicks++;
        if (hit.distance() > plan.reach()) offRoadTicks++;
        while (!milestones.isEmpty() && progress >= milestones.firstKey()) milestones.pollFirstEntry().getValue().run();
        if (progress >= targetS) { finish(false, "reached the target distance"); return true; }
        if (position.y < mc.level.getMinBuildHeight() + 2) { finish(true, "fell out of the world near " + BlockPos.containing(position).toShortString()); return true; }
        double speed = 0;
        Vec3 heading = null;
        if (trail.size() > 5) {
            Vec3 move = trail.peekLast().subtract(trail.peekFirst());
            speed = Math.hypot(move.x, move.z) / ((trail.size() - 1) / 20.0);
            if (speed > 0.4) heading = move;
        }
        topSpeed = Math.max(topSpeed, speed);
        standingTicks = speed < 0.2 ? standingTicks + 1 : 0;
        if (standingTicks >= STANDING_FAIL_TICKS) {
            finish(true, "the vehicle did not move for " + standingTicks / 20 + " s at " + BlockPos.containing(position).toShortString() + " (road s=" + (int) progress + ")");
            return true;
        }
        boolean right = false, left = false;
        double lastError = Double.NaN, lastWant = Double.NaN, lastHave = Double.NaN;
        if (heading != null) {
            double[] target = plan.pointAt(Math.min(plan.length(), progress + LOOK_AHEAD));
            double want = Math.toDegrees(Math.atan2(-(target[0] - position.x), target[1] - position.z));
            double have = Math.toDegrees(Math.atan2(-heading.x, heading.z));
            double error = ((want - have) % 360 + 540) % 360 - 180;
            right = error > TURN_THRESHOLD_DEGREES;
            left = error < -TURN_THRESHOLD_DEGREES;
            lastError = error; lastWant = want; lastHave = have;
        }
        if (ticks <= 600 && ticks % 20 == 0) {
            Playtest.log(String.format("%s: t=%d s=%.0f speed %.1f, %.1f off, want %.0f have %.0f error %.0f, keys %s%s%s, entity yaw %.0f", label, ticks, progress, speed, hit.distance(), lastWant, lastHave, lastError,
                "w", right ? "d" : "", left ? "a" : "", ride.getYRot()));
        }
        hold("w", true);
        hold("d", right);
        hold("a", left);
        if (ticks >= nextLog) {
            Playtest.log(label + ": s=" + (int) progress + " of " + (int) targetS + ", " + String.format("%.1f", speed) + " blocks/s, " + String.format("%.1f", hit.distance())
                + " off the centreline, y=" + String.format("%.1f", position.y) + (plan.bridgeAt(hit.s()) ? ", on a bridge" : ""));
            nextLog += 100;
        }
        return false;
    }

    void release() { hold("w", false); hold("a", false); hold("d", false); }

    private void hold(String key, boolean want) {
        boolean now = switch (key) { case "w" -> w; case "a" -> a; default -> d; };
        if (want == now) return;
        if (want) Playtest.keyDown(key); else Playtest.keyUp(key);
        switch (key) { case "w" -> w = want; case "a" -> a = want; default -> d = want; }
    }

    private void finish(boolean fail, String reason) {
        release();
        done = true;
        failed = fail;
        long elapsed = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime() - startGameTime;
        summary = String.format("%s: %s. %d of %d blocks in %d s of game time, top speed %.1f blocks/s, %d s on bridges, %d s outside the road's reach, at most %.1f blocks off the centreline",
            label, reason, (int) progress, (int) targetS, elapsed / 20, topSpeed, bridgeTicks / 20, offRoadTicks / 20, maxDeviation);
        Playtest.log(summary);
        if (fail) Playtest.fail(summary);
    }
}
