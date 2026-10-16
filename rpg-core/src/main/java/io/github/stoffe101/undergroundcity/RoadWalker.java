package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A scripted pedestrian for the road walk test: it holds the real W key (through the X11 input helper) and only turns to face the
 * road ahead, so the physics are those of a player on foot: steps, auto-jump, slopes, bridges and chunk loading are all exercised.
 * It reports what a person would feel: how long the trip took in game ticks, where it waited for the world to generate and where it
 * got stuck. Client-side and test-only.
 */
final class RoadWalker {
    private static final double LOOK_AHEAD = 4.0, ARRIVAL = 6.0;
    private static final int STUCK_JUMP_TICKS = 40, STUCK_FAIL_TICKS = 400;

    private final CorridorPlan plan;
    private final boolean sprint;
    private double progress;
    private boolean forward, sprinting;
    private long startGameTime = -1;
    private int ownTicks;
    private int waitingForChunks, bridgeTicks, jumps, stuckTicks, nextMilestone = 200, walkingTicks, swimTicks;
    private double maxDeviation, lastX = Double.NaN, lastZ = Double.NaN, movedWindow;
    private int windowTicks;
    private boolean done, failed;
    private String summary = "";

    RoadWalker(CorridorPlan plan, boolean sprint) { this.plan = plan; this.sprint = sprint; }

    boolean done() { return done; }
    String summary() { return summary; }

    /** Called once per client tick while the walk is the active step. */
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null || mc.level == null || done) return;
        if (startGameTime < 0) { startGameTime = 0; Playtest.focusWindow(); }
        ownTicks++;
        Vec3 position = player.position();
        CorridorPlan.Hit hit = plan.project(position.x, position.z);
        progress = Math.max(progress, hit.s());
        maxDeviation = Math.max(maxDeviation, hit.distance());
        if (progress >= plan.length() - ARRIVAL) { finish(false, "reached the far end"); return; }
        if (position.y < mc.level.getMinBuildHeight() + 2) { finish(true, "fell out of the world near " + BlockPos.containing(position).toShortString()); return; }
        // Face the road ahead; the walking itself is the real key.
        double[] target = plan.pointAt(Math.min(plan.length(), progress + LOOK_AHEAD));
        float yaw = (float) Math.toDegrees(Math.atan2(-(target[0] - position.x), target[1] - position.z));
        player.setYRot(yaw);
        player.yHeadRot = yaw;
        player.setXRot(8f);
        // Wait while the chunks ahead have not arrived (a person on a slow machine would stand at the edge of the world too).
        double[] ahead = plan.pointAt(Math.min(plan.length(), progress + 8));
        boolean loaded = mc.level.getChunkSource().hasChunk((int) Math.floor(ahead[0]) >> 4, (int) Math.floor(ahead[1]) >> 4);
        if (!loaded) {
            waitingForChunks++;
            hold(false);
            return;
        }
        hold(true);
        walkingTicks++;
        if (plan.bridgeAt(progress)) bridgeTicks++;
        if (player.isInWater()) swimTicks++;
        // Progress bookkeeping: jump when the way is blocked, give up when it stays blocked.
        windowTicks++;
        if (!Double.isNaN(lastX)) movedWindow += Math.hypot(position.x - lastX, position.z - lastZ);
        lastX = position.x; lastZ = position.z;
        if (windowTicks >= 20) {
            if (movedWindow < 0.6) stuckTicks += windowTicks; else stuckTicks = 0;
            movedWindow = 0; windowTicks = 0;
            if (stuckTicks >= STUCK_JUMP_TICKS) { Playtest.tap("space"); jumps++; }
            if (stuckTicks == STUCK_JUMP_TICKS) diagnose(mc, position);
            if (stuckTicks >= STUCK_FAIL_TICKS) {
                Playtest.screenshot("uc-walk-stuck");
                finish(true, "stuck for " + stuckTicks / 20 + " s at " + BlockPos.containing(position).toShortString() + " (road s=" + (int) progress + " of " + (int) plan.length()
                    + ", deviation " + String.format("%.1f", hit.distance()) + ")");
                return;
            }
        }
        if (progress >= nextMilestone) {
            Playtest.log(plan.id() + ": " + (int) progress + " blocks in " + ownTicks / 20 + " s (waited " + waitingForChunks / 20 + " s for chunks)");
            nextMilestone += 200;
        }
    }

    /** What is around a walker that has stopped: the blocks at its feet and head, and the four blocks around it, with their collision. */
    private void diagnose(Minecraft mc, Vec3 position) {
        BlockPos feet = BlockPos.containing(position);
        StringBuilder text = new StringBuilder("stuck at " + feet.toShortString() + " (s=" + (int) progress + "): ");
        for (int dy = -1; dy <= 2; dy++) {
            text.append("y").append(dy >= 0 ? "+" : "").append(dy).append(" [");
            for (var offset : new BlockPos[]{BlockPos.ZERO, feet.east().subtract(feet), feet.west().subtract(feet), feet.north().subtract(feet), feet.south().subtract(feet)}) {
                BlockPos at = feet.offset(offset).above(dy);
                var state = mc.level.getBlockState(at);
                text.append(state.getBlock().getDescriptionId().replace("block.minecraft.", "")).append(state.getCollisionShape(mc.level, at).isEmpty() ? "(-)" : "(#)").append(' ');
            }
            text.append("] ");
        }
        Playtest.log(text.toString());
    }

    private void hold(boolean walk) {
        if (walk != forward) { if (walk) Playtest.keyDown("w"); else Playtest.keyUp("w"); forward = walk; }
        boolean wantSprint = walk && sprint;
        if (wantSprint != sprinting) { if (wantSprint) Playtest.keyDown("ctrl"); else Playtest.keyUp("ctrl"); sprinting = wantSprint; }
    }

    private void finish(boolean fail, String reason) {
        hold(false);
        done = true;
        failed = fail;
        long ticks = ownTicks;
        long moving = Math.max(1, walkingTicks);
        summary = String.format("%s: %s. %d of %d blocks, %d s in game time (%d s of it waiting for chunks), %.2f blocks/s while moving, %d s on bridges, %d s swimming, %d jumps, at most %.1f blocks off the centreline",
            plan.id(), reason, (int) progress, (int) plan.length(), ticks / 20, waitingForChunks / 20, progress / (moving / 20.0), bridgeTicks / 20, swimTicks / 20, jumps, maxDeviation);
        Playtest.log(summary);
        if (fail) Playtest.fail(summary);
    }

    boolean failed() { return failed; }
}
