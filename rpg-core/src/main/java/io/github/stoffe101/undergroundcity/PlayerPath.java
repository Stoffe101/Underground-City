package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A route a person on foot can really walk, for the scripted player ({@link PathWalker}): an A* search over block cells that uses a player's own collision, so a thin thing at head height (an open
 * trapdoor shutter, a sign, a lantern, a pane) blocks the way as it blocks a person, which the game's mob pathfinding does not (it treats an open trapdoor as open air, and a scripted player
 * following such a route walked into the Row's shutters). A cell can be stood in when the feet and the head have no collision and the floor below holds a person; one step up costs a jump and
 * needs headroom, up to three steps down are a drop; wooden doors and fence gates are walkable (the walker opens them). Runs on the server thread, bounded by a node budget; test-only.
 */
final class PlayerPath {
    private PlayerPath() {}

    private static final int MAX_NODES = 24_000;
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private record Node(BlockPos pos, double g, double f) {}

    private static boolean openable(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && state.is(BlockTags.WOODEN_DOORS) || state.getBlock() instanceof FenceGateBlock;
    }

    private static boolean clear(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (openable(state)) return true;
        if (!state.getFluidState().isEmpty()) return false;
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** A floor a person stands on: a collision shape whose top reaches at least half a block (slabs, stairs and full blocks; not a carpet over air, not a fence top). */
    private static boolean floor(ServerLevel level, BlockPos below) {
        BlockState state = level.getBlockState(below);
        if (!state.getFluidState().isEmpty() || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) return false;
        VoxelShape shape = state.getCollisionShape(level, below);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 0.5;
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())) return false;
        return clear(level, feet) && clear(level, feet.above()) && floor(level, feet.below());
    }

    /** The standable cell at or just around the column of {@code at} (within three blocks up or down), or null. */
    static BlockPos settle(ServerLevel level, BlockPos at) {
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
            BlockPos p = at.above(dy);
            if (standable(level, p)) return p;
        }
        return null;
    }

    /**
     * Waypoints (block-bottom centres) from {@code from} to within {@code within} blocks (flat distance) of {@code to}. When the goal cannot be reached inside the node budget the route to the closest
     * cell reached is returned (an empty list when not even the start can be stood in), so a caller can walk as far as it goes and plan again from there.
     */
    static List<Vec3> find(ServerLevel level, Vec3 from, Vec3 to, double within) {
        BlockPos start = settle(level, BlockPos.containing(from));
        if (start == null) return List.of();
        double tx = to.x, tz = to.z, ty = to.y;
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, BlockPos> parent = new HashMap<>();
        open.add(new Node(start, 0, h(start, tx, ty, tz)));
        best.put(start.asLong(), 0.0);
        BlockPos closest = start;
        double closestH = h(start, tx, ty, tz);
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node node = open.poll();
            BlockPos at = node.pos();
            if (node.g() > best.getOrDefault(at.asLong(), Double.MAX_VALUE) + 1e-9) continue;
            double flat = Math.hypot(at.getX() + 0.5 - tx, at.getZ() + 0.5 - tz);
            double hh = h(at, tx, ty, tz);
            if (hh < closestH) { closestH = hh; closest = at; }
            if (flat <= within && Math.abs(at.getY() - ty) <= 3) { closest = at; break; }
            for (int[] step : STEPS) {
                boolean diagonal = step[0] != 0 && step[1] != 0;
                for (int dy : new int[]{0, 1, -1, -2, -3}) {
                    BlockPos next = at.offset(step[0], dy, step[1]);
                    if (!standable(level, next)) continue;
                    if (dy == 1 && !clear(level, at.above(2))) continue;   // a jump needs headroom where the jump starts
                    if (dy < 0) {                                            // a drop: the column in front must be open down to the landing
                        boolean shaft = true;
                        for (int k = 0; k > dy && shaft; k--) shaft = clear(level, at.offset(step[0], k, step[1])) && clear(level, at.offset(step[0], k + 1, step[1]));
                        if (!shaft) continue;
                    }
                    if (diagonal) {                                          // no cutting a corner through a wall or a door frame
                        BlockPos a = at.offset(step[0], Math.max(0, dy), 0), b = at.offset(0, Math.max(0, dy), step[1]);
                        if (!clear(level, a) || !clear(level, a.above()) || !clear(level, b) || !clear(level, b.above())) continue;
                        if (openable(level.getBlockState(next)) || openable(level.getBlockState(at))) continue;
                    }
                    double cost = (diagonal ? 1.414 : 1.0) + (dy == 1 ? 0.8 : dy < 0 ? 0.3 * -dy : 0) + (openable(level.getBlockState(next)) ? 1.5 : 0);
                    double g = node.g() + cost;
                    if (g < best.getOrDefault(next.asLong(), Double.MAX_VALUE)) {
                        best.put(next.asLong(), g);
                        parent.put(next.asLong(), at);
                        open.add(new Node(next, g, g + h(next, tx, ty, tz)));
                    }
                    break;   // one vertical choice per horizontal step: the first that stands (level, then up, then down)
                }
            }
        }
        List<Vec3> route = new ArrayList<>();
        for (BlockPos p = closest; p != null; p = parent.get(p.asLong())) route.add(Vec3.atBottomCenterOf(p));
        Collections.reverse(route);
        return route;
    }

    private static double h(BlockPos p, double tx, double ty, double tz) {
        return Math.hypot(p.getX() + 0.5 - tx, p.getZ() + 0.5 - tz) + Math.abs(p.getY() - ty) * 0.5;
    }
}
