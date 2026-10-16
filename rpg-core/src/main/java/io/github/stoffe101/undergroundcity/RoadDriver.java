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
    /** Steer from the vehicle's own yaw instead of its measured movement: right for a boat, which turns as fast as the keys say and would swing wildly on a delayed heading. */
    private boolean steerByYaw, faceForward;
    /** A speed (blocks per second) above which the throttle is released; NaN for none. A scripted vehicle that outruns the chunks the container's server can send stops dead at the edge of the loaded world. */
    private double cruise = Double.NaN;
    private boolean throttle = true;
    private int lowSpeedTicks, unseatedTicks;
    /**
     * For a car: the vehicle's own velocity and orientation (the sensors; null when unreadable). Immersive Vehicles turns the wheels by a fixed number of
     * degrees for every tick a steering key is held and straightens them for every tick it is not, so holding a key does not mean "turning" but "turning
     * a little more every tick"; a controller that holds a key until the heading is right swings from lock to lock. This one steers the yaw rate.
     */
    private java.util.function.Supplier<double[]> velocity;
    private static final int TAP_PERIOD = 10;
    private static final double TAP_THRESHOLD = 4.0;
    private int tapTicksLeft, tapDirection;
    private double wheel, yawRate;
    /** Which of the vehicle's four local axes is its front, found by comparing each with the direction it actually moves in (the model's own convention is not documented). */
    private final double[] axisScore = new double[4];
    private int axisSamples = -1, frontAxis = -1;
    private double lastHeading = Double.NaN;
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

    RoadDriver steerByYaw() { this.steerByYaw = true; return this; }

    /** Release the throttle above this speed (blocks per second) and press it again below. */
    RoadDriver cruiseAt(double blocksPerSecond) { this.cruise = blocksPerSecond; return this; }

    /** Keep the player's own view along the vehicle's heading, a little downward, so the frames show what a passenger sees ahead and not the deck overhead. */
    RoadDriver faceForward() { this.faceForward = true; return this; }

    /** Steer a wheeled vehicle from its own orientation and yaw rate (see the field). */
    RoadDriver steerByWheelModel(java.util.function.Supplier<double[]> velocity) { this.velocity = velocity; return this; }

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
        if (ride == null) {
            // A vehicle that crosses into a chunk the client has just received can be re-created for a moment; give the mount time to come back.
            if (++unseatedTicks > 100) { finish(true, "the player is not riding anything"); return true; }
            return false;
        }
        unseatedTicks = 0;
        if (faceForward) { Playtest.viewDriver = this; applyView(); }
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
        if (velocity != null) {
            // sensors: motion x, motion z (blocks per tick), then the world heading of the vehicle's local +Z, -Z, +X and -X axes, in degrees
            double[] sensor = velocity.get();
            double motionHeading = Double.NaN;
            if (sensor != null && Math.hypot(sensor[0], sensor[1]) > 0.02) motionHeading = Math.toDegrees(Math.atan2(-sensor[0], sensor[1]));
            if (sensor != null && frontAxis < 0 && !Double.isNaN(motionHeading) && speed > 1.5) {
                axisSamples = Math.max(0, axisSamples) + 1;
                for (int axis = 0; axis < 4; axis++) axisScore[axis] += Math.abs(((sensor[2 + axis] - motionHeading) % 360 + 540) % 360 - 180);
                if (axisSamples >= 12) {
                    int best = 0;
                    for (int axis = 1; axis < 4; axis++) if (axisScore[axis] < axisScore[best]) best = axis;
                    frontAxis = best;
                    Playtest.log(label + ": the vehicle's front is local axis " + new String[]{"+Z", "-Z", "+X", "-X"}[best] + " (mean difference from its movement " + String.format("%.0f", axisScore[best] / axisSamples) + " degrees over " + axisSamples + " ticks)");
                }
            }
            double have = sensor != null && frontAxis >= 0 ? sensor[2 + frontAxis] : Double.NaN;
            if (!Double.isNaN(have)) {
                // Immersive Vehicles sends the keys to the server and shows what the server's physics did, which in this container (software rendering, an overloaded
                // server) arrives a good half second late, and a quad turns far more than its wheel angle suggests. A controller that reacts to what it sees swings
                // wildly; one that taps a key for a tick or two and then waits for the effect to show does not. So: every TAP_PERIOD ticks look at the heading error and,
                // if it is large enough, tap the key that reduces it.
                double lookAhead = Math.max(10.0, speed * 1.5);
                double[] target = plan.pointAt(Math.min(plan.length(), progress + lookAhead));
                double want = Math.toDegrees(Math.atan2(-(target[0] - position.x), target[1] - position.z));
                double error = ((want - have) % 360 + 540) % 360 - 180;
                double raw = Double.isNaN(lastHeading) ? 0 : ((have - lastHeading) % 360 + 540) % 360 - 180;
                lastHeading = have;
                yawRate = 0.5 * yawRate + 0.5 * raw;
                if (ticks % TAP_PERIOD == 0) {
                    tapDirection = error > TAP_THRESHOLD ? 1 : error < -TAP_THRESHOLD ? -1 : 0;
                    tapTicksLeft = tapDirection == 0 ? 0 : Math.abs(error) > 4 * TAP_THRESHOLD ? 3 : 2;
                }
                right = tapTicksLeft > 0 && tapDirection > 0;
                left = tapTicksLeft > 0 && tapDirection < 0;
                if (tapTicksLeft > 0) tapTicksLeft--;
                lastError = error; lastWant = want; lastHave = have;
            } else {
                // not calibrated yet: keep the wheels straight while the vehicle gets going
                right = false; left = false;
            }
            wheel = yawRate;
        } else if (steerByYaw || heading != null) {
            double[] target = plan.pointAt(Math.min(plan.length(), progress + LOOK_AHEAD));
            double want = Math.toDegrees(Math.atan2(-(target[0] - position.x), target[1] - position.z));
            double have = steerByYaw ? ride.getYRot() : Math.toDegrees(Math.atan2(-heading.x, heading.z));
            double error = ((want - have) % 360 + 540) % 360 - 180;
            double threshold = steerByYaw ? 4.0 : TURN_THRESHOLD_DEGREES;
            right = error > threshold;
            left = error < -threshold;
            lastError = error; lastWant = want; lastHave = have;
        }
        lowSpeedTicks = speed < 1.0 ? lowSpeedTicks + 1 : 0;
        if (lowSpeedTicks == 60) diagnose(mc, position);
        if (ticks <= 600 && ticks % 20 == 0) {
            Playtest.log(String.format("%s: t=%d s=%.0f speed %.1f, %.1f off, want %.0f have %.0f error %.0f, keys %s%s%s, yaw rate %.2f deg/tick, entity yaw %.0f", label, ticks, progress, speed, hit.distance(), lastWant, lastHave, lastError,
                "w", right ? "d" : "", left ? "a" : "", wheel, ride.getYRot()));
        }
        if (!Double.isNaN(cruise)) throttle = speed < (throttle ? cruise + 1.0 : cruise - 1.0);
        hold("w", throttle);
        hold("d", right);
        hold("a", left);
        if (ticks >= nextLog) {
            Playtest.log(label + ": s=" + (int) progress + " of " + (int) targetS + ", " + String.format("%.1f", speed) + " blocks/s, " + String.format("%.1f", hit.distance())
                + " off the centreline, y=" + String.format("%.1f", position.y) + (plan.bridgeAt(hit.s()) ? ", on a bridge" : ""));
            nextLog += 100;
        }
        return false;
    }

    private int viewLogs, framesMeasured;
    /** How far the camera ends up from where the player's own pitch points (a vehicle's camera can add its own tilt, e.g. under acceleration); measured after each frame. */
    private volatile double cameraOffset;

    /** Points the player's own view along the vehicle's heading and a little downward, allowing for any tilt the vehicle's camera adds. Called every client tick and again just before every frame. */
    void applyView() {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (!faceForward || player == null || player.getVehicle() == null) return;
        float pitch = (float) Math.max(-90, Math.min(90, 12.0 - cameraOffset));
        player.setYRot(player.getVehicle().getYRot());
        player.yRotO = player.getYRot();
        player.setXRot(pitch);
        player.xRotO = pitch;
    }

    /** After a frame is drawn: how far the camera's pitch is from the player's. Logged now and then so a view that does not look where it should can be understood. */
    void measureView() {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (!faceForward || player == null || player.getVehicle() == null) return;
        double offset = mc.gameRenderer.getMainCamera().getXRot() - player.getXRot();
        cameraOffset = Double.isNaN(offset) ? 0 : offset;
        if (++framesMeasured % 120 == 1 && viewLogs++ < 12) {
            Playtest.log(label + ": player pitch " + String.format("%.0f", player.getXRot()) + ", camera pitch " + String.format("%.0f", mc.gameRenderer.getMainCamera().getXRot())
                + ", vehicle pitch " + String.format("%.0f", player.getVehicle().getXRot()) + ", camera offset " + String.format("%.0f", cameraOffset));
        }
    }

    /** What is around a vehicle that has stopped moving: the blocks at and around it (water, solid, air) at three heights, so a pier, a shore or a rail shows up. */
    private void diagnose(Minecraft mc, Vec3 position) {
        BlockPos centre = BlockPos.containing(position);
        StringBuilder text = new StringBuilder(label + ": slow at " + centre.toShortString() + " (s=" + (int) progress + "); '~' water, '#' solid, '.' air, 'o' you; rows north to south, y-1 / y / y+1:\n");
        for (int dy = -1; dy <= 1; dy++) {
            text.append("  y").append(dy >= 0 ? "+" : "").append(dy).append('\n');
            for (int dz = -4; dz <= 4; dz++) {
                text.append("    ");
                for (int dx = -4; dx <= 4; dx++) {
                    BlockPos at = centre.offset(dx, dy, dz);
                    var state = mc.level.getBlockState(at);
                    text.append(dx == 0 && dz == 0 && dy == 0 ? 'o' : !state.getFluidState().isEmpty() ? '~' : state.getCollisionShape(mc.level, at).isEmpty() ? '.' : '#');
                }
                text.append('\n');
            }
        }
        Playtest.log(text.toString());
        Playtest.screenshotNextFrame("uc-" + label.split(" ")[0] + "-slow");
    }

    void release() { hold("w", false); hold("a", false); hold("d", false); }

    private void hold(String key, boolean want) {
        boolean now = switch (key) { case "w" -> w; case "a" -> a; default -> d; };
        if (want == now) return;
        if (want) Playtest.keyDown(key); else Playtest.keyUp(key);
        switch (key) { case "w" -> w = want; case "a" -> a = want; default -> d = want; }
    }

    private void finish(boolean fail, String reason) {
        if (Playtest.viewDriver == this) Playtest.viewDriver = null;
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
