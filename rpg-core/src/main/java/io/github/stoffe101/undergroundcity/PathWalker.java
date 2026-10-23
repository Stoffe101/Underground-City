package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A scripted pedestrian that goes where a person following the on-screen arrow would go: the route is planned on the integrated server with a player's own
 * collision ({@link PlayerPath}; the game's mob pathfinding walks through open trapdoor shutters that stop a person), and the walking is the real keyboard: W held, the
 * heading turned toward the next waypoint each tick (what moving the mouse does), Space tapped where the route steps up, and a right click on a
 * closed wooden door the route passes through. It plans in legs of at most {@link #LEG} blocks and plans again when it stops making progress, so it
 * reaches places a straight line cannot (round a fence, through a doorway, up a lane). Client-side and test-only; it decides nothing about the game.
 */
final class PathWalker {
    private static final double LEG = 40.0, NODE_REACHED = 0.7;
    private static final int STUCK_TICKS = 50, MAX_REPLANS = 14, MAX_LEGS = 120;

    private final Supplier<Vec3> target;
    private final double within, withinY;
    private final String label;
    private List<Vec3> nodes = new ArrayList<>();
    private int next, ticks, sinceProgress, replans, legs, jumps, doors, planWait, jumpHold;
    private double best = Double.MAX_VALUE;
    private int bestNode = -1;
    private boolean done, failed, keysDown, planning;
    private Vec3 stuckAt;
    private int sidestep;
    private String summary = "";
    private volatile List<Vec3> planned;

    PathWalker(String label, Supplier<Vec3> target, double within) { this(label, target, within, Double.MAX_VALUE); }

    /**
     * A walk that has arrived only within {@code withinY} blocks of the target's height too: "beside the road's start" four blocks under its deck is not on the road (a journey's walker
     * reached the road's first stretch through a gully under it, by flat distance, and could not climb on).
     */
    PathWalker(String label, Supplier<Vec3> target, double within, double withinY) { this.label = label; this.target = target; this.within = within; this.withinY = withinY; }

    boolean done() { return done; }
    boolean failed() { return failed; }
    String summary() { return summary; }

    private static double flat(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }

    /** Plans the next leg on the server thread; the client picks the result up on a later tick. */
    private void plan() {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        Vec3 from = mc.player.position(), goal = target.get();
        if (server == null || goal == null) { planned = List.of(); return; }
        planning = true;
        planned = null;
        Vec3 aim = goal;
        double d = flat(from, goal);
        // a leg on the way aims at the walker's own height: aiming at the goal's (a quarry 20 blocks down) pulled a walker into a pit, and aiming at no height at all let one climb
        // down into a ravine 45 blocks deep; the last leg alone goes to the goal's height
        if (d > LEG) aim = from.add(goal.subtract(from).multiply(LEG / d, 0, LEG / d));
        final Vec3 leg = aim;
        final boolean last = d <= LEG;
        server.execute(() -> {
            List<Vec3> out = new ArrayList<>();
            try {
                // a route a person can walk: the player's own collision, not a mob's (see PlayerPath)
                out.addAll(PlayerPath.find(server.overworld(), from, leg, last ? Math.max(0.8, within - 0.5) : 3.0, last ? Math.min(3.0, withinY) : 3.0));
            } catch (RuntimeException error) {
                Playtest.log(label + ": planning failed: " + error);
            }
            planned = out;
        });
    }

    private void release() {
        if (jumpHold > 0) { Playtest.keyUp("space"); jumpHold = 0; }
        if (sidestep > 0) { Playtest.keyUp("a"); Playtest.keyUp("d"); sidestep = 0; }
        if (!keysDown) return;
        Playtest.keyUp("w");
        keysDown = false;
    }

    /** A jump is the Space key held for a few ticks: the game reads whether it is down at a tick, so a press and release in one instant jumps nothing. */
    private void jump() {
        if (jumpHold > 0) return;
        Playtest.keyDown("space");
        jumpHold = 4;
        jumps++;
    }

    private void finish(boolean fail, String why) {
        release();
        done = true; failed = fail;
        summary = label + ": " + why + " after " + ticks + " ticks (" + String.format(java.util.Locale.ROOT, "%.0f", ticks / 20.0) + " s), " + legs + " legs, " + replans + " re-plans when stuck, " + jumps + " jumps, " + doors + " doors";
        Playtest.log(summary);
    }

    /** Called once per client tick while the walk is the active step. */
    void tick() {
        var mc = Minecraft.getInstance();
        var me = mc.player;
        if (me == null || done) return;
        ticks++;
        if (jumpHold > 0 && --jumpHold == 0) Playtest.keyUp("space");
        Vec3 goal = target.get();
        if (goal == null) { finish(true, "the target is gone"); return; }
        double left = flat(me.position(), goal);
        if (left <= within && Math.abs(me.getY() - goal.y) <= withinY) { finish(false, String.format(java.util.Locale.ROOT, "arrived %.1f blocks from the target", left)); return; }
        if (planning) {
            if (planned == null) { if (++planWait > 200) { planning = false; planWait = 0; } return; }
            nodes = planned; next = 0; planning = false; planWait = 0; bestNode = -1; best = Double.MAX_VALUE; sinceProgress = 0;
            // a "route" that ends where the player already stands (the planner found nothing closer from here) is no route either
            // (whenever the walk has not arrived: a margin here once left a walker 2.5 blocks from a tree, on the margin's edge, planning 121 empty legs in 21 seconds)
            boolean nowhere = !nodes.isEmpty() && flat(nodes.get(nodes.size() - 1), me.position()) < 0.75 && left > within;
            if (nodes.isEmpty() || nowhere) {
                // no route found: walk straight at the target for a while, as a person would when the arrow points through open ground
                if (nowhere) Playtest.log(label + ": no route from " + me.blockPosition().toShortString() + " (feet in " + mc.level.getBlockState(me.blockPosition()).getBlock() + ", on "
                    + mc.level.getBlockState(me.blockPosition().below()).getBlock() + "); walking straight at the target");
                nodes = List.of(goal);
            }
            if (legs <= 2 || replans > 0 && replans % 4 == 0) {
                StringBuilder route = new StringBuilder();
                for (int i = 0; i < nodes.size() && i < 12; i++) route.append(' ').append(BlockPos.containing(nodes.get(i)).toShortString().replace(" ", ""));
                Playtest.log(label + ": leg " + legs + " (re-plans " + replans + ") has " + nodes.size() + " waypoints, " + String.format(java.util.Locale.ROOT, "%.1f", left) + " blocks to go; route" + route + (nodes.size() > 12 ? " ..." : ""));
            }
        }
        if (nodes.isEmpty() || next >= nodes.size()) {
            // the end of a leg (or no route yet): plan the next one. Legs are not failures; a walk of a few hundred blocks takes a dozen of them.
            if (++legs > MAX_LEGS) { finish(true, String.format(java.util.Locale.ROOT, "gave up %.1f blocks from the target at %s after %d legs", left, me.blockPosition().toShortString(), legs)); return; }
            release();
            plan();
            return;
        }
        Vec3 node = nodes.get(next);
        while (flat(me.position(), node) < NODE_REACHED && next < nodes.size() - 1) node = nodes.get(++next);
        if (flat(me.position(), node) < NODE_REACHED && next == nodes.size() - 1) { next++; return; }
        // a closed door on the way: face it and open it with a right click, as a person does
        BlockPos nodeBlock = BlockPos.containing(node);
        for (BlockPos at : new BlockPos[]{nodeBlock, nodeBlock.above()}) {
            BlockState state = mc.level.getBlockState(at);
            boolean closed = state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)
                || state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock && !state.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN);
            if (closed && flat(me.position(), Vec3.atCenterOf(at)) < 2.2) {
                Vec3 eye = me.getEyePosition();
                Vec3 centre = Vec3.atCenterOf(at);
                me.setYRot((float) Math.toDegrees(Math.atan2(-(centre.x - eye.x), centre.z - eye.z)));
                me.setXRot((float) -Math.toDegrees(Math.atan2(centre.y - eye.y, flat(eye, centre))));
                if (ticks % 6 == 0) { Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); doors++; }
                return;
            }
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(node.x - me.getX()), node.z - me.getZ()));
        me.setYRot(yaw);
        me.yHeadRot = yaw;
        me.setXRot(6f);
        if (sidestep > 0) {
            String key = replans % 2 == 0 ? "a" : "d";
            if (sidestep == 10) Playtest.keyDown(key);
            if (--sidestep == 0) Playtest.keyUp(key);
        }
        if (!keysDown) { Playtest.focusWindow(); Playtest.keyDown("w"); keysDown = true; }
        if (node.y > me.getY() + 0.6 && me.onGround()) jump();
        // progress is moving along the route (a later waypoint, or closer to the current one), not getting closer to the target: a route round a building goes away from it first
        double toNode = flat(me.position(), node);
        if (next > bestNode) { bestNode = next; best = toNode; sinceProgress = 0; }
        else if (toNode < best - 0.3) { best = toNode; sinceProgress = 0; }
        else if (++sinceProgress > STUCK_TICKS) {
            sinceProgress = 0;
            if (me.horizontalCollision && me.onGround()) jump();
            // still in the same place after a re-plan: a step to one side once the new route is walked, as a person shoulders past (left, then right, by turns)
            boolean side = replans > 0 && stuckAt != null && stuckAt.distanceTo(me.position()) < 0.5;
            stuckAt = me.position();
            if (++replans > MAX_REPLANS) { finish(true, String.format(java.util.Locale.ROOT, "stuck %.1f blocks from the target at %s", left, me.blockPosition().toShortString())); return; }
            release();
            plan();
            if (side) sidestep = 10;
        }
        if (ticks % 200 == 0) Playtest.log(label + String.format(java.util.Locale.ROOT, ": %.1f blocks to go, at %s", left, me.blockPosition().toShortString()));
    }

    /** Stops holding keys (when the step is abandoned). */
    void stop() { release(); }
}
