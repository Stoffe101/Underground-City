package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Waymarkers: a stone pillar with a lantern on top and a wall sign on each of its two faces along the road, every {@link #SPACING} blocks of each
 * inter-city road, on the right-hand verge. The sign a traveller reads says where the road leads, how far that is and how long it takes on foot (D-078): the accepted travel pacing is a few minutes between
 * cities, and a road with nothing on it gives no sense of progress.
 *
 * <p>Posts are placed <b>at run time</b>, when a player comes within {@link #ACTIVATE} blocks of one, and only once each (the world remembers which
 * were placed, so a post a player breaks stays broken). That keeps world generation, and so seed 5908, exactly as it was, and gives worlds that
 * already exist their markers. Where a post cannot stand (water, a bridge, a building, a tree) it slides along the road up to {@link #SEARCH}
 * blocks, and is given up if nothing fits. Spacing is the only tuning; names come from the road's id (two city ids joined by an underscore).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class RoadMarkers {
    public static final int SPACING = 250, EDGE = 90, SEARCH = 48, ACTIVATE = 112, CHECK_TICKS = 100;
    /** Blocks per second of an ordinary walk (4.317 in the game); sprinting and vehicles are faster, the sign quotes the slow case. */
    static final double WALK = 4.3;

    /** One planned post: where it stands, which way its front faces, and what each side says. */
    record Spot(String road, int index, double s, BlockPos base, Direction forward, List<String> front, List<String> back) {
        String key() { return road + "#" + index; }
        /** The sign read by someone heading for the road's far city: on the pillar's face toward the near city, looking back at them. */
        BlockPos frontSign() { return base.above().relative(forward.getOpposite()); }
        /** The sign read by someone heading for the near city. */
        BlockPos backSign() { return base.above().relative(forward); }
    }

    private RoadMarkers() {}

    // ------------------------------------------------------------------------------------------ pure planning

    /** The arc positions of a road's markers: every {@code spacing} blocks of open road, never within {@code edge} blocks of a city circle. */
    static List<Double> positions(CorridorPlan plan, int spacing, int edge) {
        double first = firstOpen(plan), last = lastOpen(plan);
        List<Double> result = new ArrayList<>();
        for (int k = 1; ; k++) {
            double s = first + (double) k * spacing;
            if (s > last - edge) break;
            if (s >= first + edge) result.add(s);
        }
        return result;
    }

    static double firstOpen(CorridorPlan plan) {
        for (double s = 0; s <= plan.length(); s += CorridorPlan.STEP) if (plan.openAt(s)) return s;
        return 0;
    }

    static double lastOpen(CorridorPlan plan) {
        for (double s = plan.length(); s >= 0; s -= CorridorPlan.STEP) if (plan.openAt(s)) return s;
        return plan.length();
    }

    /** Whole minutes on foot, never less than one. */
    static int minutes(double metres) { return Math.max(1, (int) Math.round(metres / WALK / 60.0)); }

    /** "ironhaven_valewood" gives {"IRONHAVEN", "VALEWOOD"}; anything else gives null and the road gets no markers. */
    static String[] ends(String roadId) {
        String[] parts = roadId.split("_");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null;
        return new String[]{parts[0].toUpperCase(Locale.ROOT), parts[1].toUpperCase(Locale.ROOT)};
    }

    /** The four lines of one face: where it leads, how far, how long. */
    static List<String> face(String city, double metres) {
        int rounded = (int) (Math.round(metres / 10.0) * 10);
        return List.of("TO " + city, String.format(Locale.ROOT, "%,d m", rounded), minutes(metres) + " min on foot", "");
    }

    /** The compass direction nearest to a direction of travel (x is east, z is south). */
    static Direction direction(double tx, double tz) {
        return Math.abs(tx) >= Math.abs(tz) ? (tx >= 0 ? Direction.EAST : Direction.WEST) : (tz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    // ------------------------------------------------------------------------------------------ the world

    /** Where marker {@code index} of a road stands, or empty when nothing fits within {@link #SEARCH} blocks of its ideal spot. */
    static Optional<Spot> plan(ServerLevel level, CorridorPlan plan, int index, double ideal) {
        String[] ends = ends(plan.id());
        if (ends == null) return Optional.empty();
        double first = firstOpen(plan), last = lastOpen(plan);
        for (int shift = 0; shift <= SEARCH; shift += 8) {
            for (int sign : shift == 0 ? new int[]{1} : new int[]{1, -1}) {
                double s = ideal + sign * shift;
                if (s < first + 8 || s > last - 8) continue;
                double[] at = plan.pointAt(s), behind = plan.pointAt(Math.max(0, s - 3)), ahead = plan.pointAt(Math.min(plan.length(), s + 3));
                double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], length = Math.hypot(tx, tz);
                if (length < 1e-6) continue;
                tx /= length; tz /= length;
                double out = plan.reach() + 1.5;
                int x = (int) Math.floor(at[0] - tz * out), z = (int) Math.floor(at[1] + tx * out); // the right-hand side of the direction of travel (x is east, z is south)
                if (!level.isLoaded(new BlockPos(x, level.getSeaLevel(), z))) continue;
                CorridorPlan.Hit hit = plan.project(x + 0.5, z + 0.5);
                if (hit != null && hit.distance() < plan.reach() + 0.5) continue; // never on the road itself
                BlockPos base = DevCommands.surface(level, x, z);
                if (!fits(level, base)) continue;
                double toEnd = last - s, toStart = s - first;
                Direction forward = direction(tx, tz);
                Spot spot = new Spot(plan.id(), index, s, base, forward, face(ends[1], toEnd), face(ends[0], toStart));
                if (!roomForSigns(level, spot)) continue;
                return Optional.of(spot);
            }
        }
        return Optional.empty();
    }

    /** True when every corner and the middle of the square around a point is in a loaded chunk. */
    static boolean areaLoaded(ServerLevel level, double x, double z, int radius) {
        int sea = level.getSeaLevel();
        for (int dx : new int[]{-radius, 0, radius}) for (int dz : new int[]{-radius, 0, radius}) {
            if (!level.isLoaded(new BlockPos((int) Math.floor(x) + dx, sea, (int) Math.floor(z) + dz))) return false;
        }
        return true;
    }

    /** The two cells the signs hang in are free too. */
    private static boolean roomForSigns(ServerLevel level, Spot spot) {
        for (BlockPos at : new BlockPos[]{spot.frontSign(), spot.backSign()}) {
            var state = level.getBlockState(at);
            if (!state.isAir() && !state.canBeReplaced()) return false;
        }
        return true;
    }

    /** Three free cells over solid, dry ground: not water, not ice (a post on a frozen sea stands on a sheet that its own lantern would melt), and no water in the three blocks under it. */
    private static boolean fits(ServerLevel level, BlockPos base) {
        BlockPos below = base.below();
        if (!level.getFluidState(base).isEmpty() || level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return false;
        for (int depth = 1; depth <= 3; depth++) {
            var under = level.getBlockState(base.below(depth));
            if (!under.getFluidState().isEmpty() || under.is(net.minecraft.tags.BlockTags.ICE)) return false;
        }
        for (int i = 0; i < 3; i++) {
            var state = level.getBlockState(base.above(i));
            if (!state.isAir() && !state.canBeReplaced()) return false;
        }
        return true;
    }

    /** Builds the pillar, its lantern and the two signs. */
    static void build(ServerLevel level, Spot spot) {
        BlockPos base = spot.base();
        level.setBlock(base, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(2), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        sign(level, spot.frontSign(), spot.forward().getOpposite(), spot.front());
        sign(level, spot.backSign(), spot.forward(), spot.back());
    }

    private static void sign(ServerLevel level, BlockPos at, Direction facing, List<String> lines) {
        level.setBlock(at, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, facing), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            sign.setText(text(lines), true);
            sign.setWaxed(true);
            sign.setChanged();
            level.sendBlockUpdated(at, sign.getBlockState(), sign.getBlockState(), Block.UPDATE_ALL);
        }
    }

    private static SignText text(List<String> lines) {
        Component[] words = new Component[4];
        for (int i = 0; i < 4; i++) words[i] = Component.literal(i < lines.size() ? lines.get(i) : "");
        return new SignText(words, words, DyeColor.BLACK, false);
    }

    /** Places the post of every marker of a road that a player is near and that was not placed or given up before. Returns how many were built. */
    static int serve(ServerLevel level, CorridorPlan plan, List<Double> ideal, double px, double pz, PlacedSites state) {
        int built = 0;
        for (int i = 0; i < ideal.size(); i++) {
            String key = plan.id() + "#" + i;
            if (state.done.contains(key)) continue;
            double[] at = plan.pointAt(ideal.get(i));
            if (Math.hypot(at[0] - px, at[1] - pz) > ACTIVATE) continue;
            if (!areaLoaded(level, at[0], at[1], SEARCH + 8)) continue; // not yet: nothing is decided until the whole search area is in the world
            Optional<Spot> spot = plan(level, plan, i, ideal.get(i));
            if (spot.isPresent()) { build(level, spot.get()); state.where.put(key, spot.get().base().asLong()); built++; }
            state.done.add(key);
            state.setDirty();
        }
        return built;
    }

    // ------------------------------------------------------------------------------------------ events

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % CHECK_TICKS != 0 || level.players().isEmpty()) return;
        List<CorridorPlan> plans = DevCommands.plans(level.getServer());
        if (plans.isEmpty()) return;
        PlacedSites state = PlacedSites.get(level, PlacedSites.MARKERS);
        for (CorridorPlan plan : plans) {
            List<Double> ideal = positions(plan, SPACING, EDGE); // the plans are rebuilt on every call, so there is nothing to cache by
            for (ServerPlayer player : level.players()) serve(level, plan, ideal, player.getX(), player.getZ(), state);
        }
    }
}
