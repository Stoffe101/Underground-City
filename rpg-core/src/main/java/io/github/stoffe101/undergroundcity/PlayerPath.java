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

    /** Where the feet can be: open, or a thing a person simply walks over (a carpet, a thin layer of snow), which the game steps onto without a jump. A rug is not a wall. */
    private static boolean passable(ServerLevel level, BlockPos feet) {
        if (clear(level, feet)) return true;
        BlockState state = level.getBlockState(feet);
        if (!state.getFluidState().isEmpty()) return false;
        VoxelShape shape = state.getCollisionShape(level, feet);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 0.1875;
    }

    /**
     * A floor a person stands on: a collision shape whose top reaches at least half a block and covers at least a quarter of the block (slabs, stairs and full blocks; not a carpet over air,
     * not a fence top, not the thin top of iron bars or a pane, which a journey's planner once offered as a step out of a trench and the walker tried fifteen times).
     */
    private static boolean floor(ServerLevel level, BlockPos below) {
        BlockState state = level.getBlockState(below);
        if (!state.getFluidState().isEmpty() || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) return false;
        VoxelShape shape = state.getCollisionShape(level, below);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        if (top < 0.5) return false;
        double area = 0;
        for (var box : shape.toAabbs()) if (box.maxY >= top - 1e-6) area += (box.maxX - box.minX) * (box.maxZ - box.minZ);
        return area >= 0.25;
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        if (!level.isLoaded(feet) || !level.isLoaded(feet.below())) return false;
        return passable(level, feet) && clear(level, feet.above()) && floor(level, feet.below());
    }

    /**
     * Where a search from a player standing at {@code from} begins: the block their feet are in when a person can stand there, else the nearest standable block that their body overlaps (a
     * player can stand half in a block whose thing is thin: beside a railing of iron bars, a journey's planner began on top of the bars and routed straight through them), else {@link #settle}.
     */
    private static BlockPos start(ServerLevel level, Vec3 from) {
        BlockPos feet = BlockPos.containing(from.x, from.y + 0.01, from.z);
        if (standable(level, feet)) return feet;
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (double ox : new double[]{-0.3, 0.3}) for (double oz : new double[]{-0.3, 0.3}) {
            BlockPos p = BlockPos.containing(from.x + ox, from.y + 0.01, from.z + oz);
            if (p.equals(feet) || !standable(level, p)) continue;
            double d = Math.hypot(p.getX() + 0.5 - from.x, p.getZ() + 0.5 - from.z);
            if (d < best) { best = d; nearest = p; }
        }
        return nearest != null ? nearest : settle(level, feet);
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
     * Waypoints (block-bottom centres) from {@code from} to within {@code within} blocks (flat distance) of {@code to}. When the goal cannot be reached inside the node budget the route goes to the
     * cell nearest the goal on the search's open frontier, the places it had not finished exploring, so a caller can walk as far as it goes and plan again from there; only when the frontier is
     * empty (everything reachable was explored) is it the nearest cell reached. An empty list when not even the start can be stood in. The frontier matters: the nearest cell reached can be
     * the bottom of a dead end (a journey's player dropped into a one-block trench between a building and a cliff, closed by iron bars at both ends, because it was the nearest point to a road
     * 225 blocks away), and a dead end's cells are all explored, so it is never on the frontier.
     */
    static List<Vec3> find(ServerLevel level, Vec3 from, Vec3 to, double within) { return find(level, from, to, within, 3.0); }

    /** As above, with the goal reached only within {@code withinY} blocks of its height (a road's deck, not the gully under it). */
    static List<Vec3> find(ServerLevel level, Vec3 from, Vec3 to, double within, double withinY) {
        BlockPos start = start(level, from);
        if (start == null) return List.of();
        double tx = to.x, tz = to.z, ty = to.y;
        double yWeight = withinY > 8 ? 0 : 0.5;   // a leg on the way is drawn to its column, not to a height
        // somebody standing in the way is walked round where there is room, as a person would (a journey's walker pushed for forty seconds against whoever stood by the employer's desk),
        // and pushed past where there is not (closing their cells left a walker with no way out of an office whose one corridor the employer stood in): their cells cost more
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        for (var entity : level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new net.minecraft.world.phys.AABB(start).inflate(24))) {
            BlockPos at = entity.blockPosition();
            if (Math.hypot(at.getX() + 0.5 - to.x, at.getZ() + 0.5 - to.z) > 1.5) { occupied.add(at.asLong()); occupied.add(at.above().asLong()); }
        }
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, BlockPos> parent = new HashMap<>();
        open.add(new Node(start, 0, h(start, tx, ty, tz, yWeight)));
        best.put(start.asLong(), 0.0);
        BlockPos closest = start;
        double closestH = h(start, tx, ty, tz, yWeight);
        int expanded = 0;
        boolean reached = false;
        while (!open.isEmpty() && expanded++ < MAX_NODES) {
            Node node = open.poll();
            BlockPos at = node.pos();
            if (node.g() > best.getOrDefault(at.asLong(), Double.MAX_VALUE) + 1e-9) continue;
            double flat = Math.hypot(at.getX() + 0.5 - tx, at.getZ() + 0.5 - tz);
            double hh = h(at, tx, ty, tz, yWeight);
            if (hh < closestH) { closestH = hh; closest = at; }
            if (flat <= within && Math.abs(at.getY() - ty) <= withinY) { closest = at; reached = true; break; }
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
                        if (!passable(level, a) || !clear(level, a.above()) || !passable(level, b) || !clear(level, b.above())) continue;
                        if (openable(level.getBlockState(next)) || openable(level.getBlockState(at))) continue;
                    }
                    double cost = (diagonal ? 1.414 : 1.0) + (dy == 1 ? 0.8 : dy < 0 ? 0.3 * -dy : 0) + (openable(level.getBlockState(next)) ? 1.5 : 0) + (occupied.contains(next.asLong()) ? 6.0 : 0);
                    double g = node.g() + cost;
                    if (g < best.getOrDefault(next.asLong(), Double.MAX_VALUE)) {
                        best.put(next.asLong(), g);
                        parent.put(next.asLong(), at);
                        open.add(new Node(next, g, g + h(next, tx, ty, tz, yWeight)));
                    }
                    break;   // one vertical choice per horizontal step: the first that stands (level, then up, then down)
                }
            }
        }
        // the frontier only when the goal's column itself was out of reach: when the search stood above or below it (a quarry face four blocks under the step a person stands on), the
        // nearest cell reached is right, and the frontier nearer that height led a journey's walker down a cave beside the pit to y 25
        boolean columnReached = Math.hypot(closest.getX() + 0.5 - tx, closest.getZ() + 0.5 - tz) <= within + 1.0;
        if (!reached && !columnReached) {
            BlockPos frontier = null;
            double frontierH = Double.MAX_VALUE;
            for (Node node : open) {
                if (node.g() > best.getOrDefault(node.pos().asLong(), Double.MAX_VALUE) + 1e-9) continue;
                double hh = h(node.pos(), tx, ty, tz, yWeight);
                if (hh < frontierH) { frontierH = hh; frontier = node.pos(); }
            }
            if (frontier != null) closest = frontier;
        }
        List<Vec3> route = new ArrayList<>();
        for (BlockPos p = closest; p != null; p = parent.get(p.asLong())) route.add(Vec3.atBottomCenterOf(p));
        Collections.reverse(route);
        return route;
    }

    private static double h(BlockPos p, double tx, double ty, double tz, double yWeight) {
        return Math.hypot(p.getX() + 0.5 - tx, p.getZ() + 0.5 - tz) + Math.abs(p.getY() - ty) * yWeight;
    }
}
