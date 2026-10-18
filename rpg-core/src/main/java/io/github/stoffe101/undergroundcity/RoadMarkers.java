package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Waymarkers: a stone post with a sign every {@link #SPACING} blocks along each inter-city road, on the right-hand verge. Each sign says, on the side a
 * traveller sees, where the road leads, how far that is and how long it takes on foot (D-078): the accepted travel pacing is a few minutes between
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
    record Spot(String road, int index, double s, BlockPos base, int rotation, List<String> front, List<String> back) {
        String key() { return road + "#" + index; }
    }

    private static final java.util.Map<CorridorPlan, List<Double>> IDEAL = new java.util.WeakHashMap<>();

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

    /** Standing-sign rotation (0 to 15) of a face whose text is read by someone walking in the direction (tx, tz): its front looks back at them. */
    static int rotation(double tx, double tz) {
        double yaw = Math.toDegrees(Math.atan2(tx, -tz));
        return Math.floorMod((int) Math.round(yaw / 22.5), 16);
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
                return Optional.of(new Spot(plan.id(), index, s, base, rotation(tx, tz), face(ends[1], toEnd), face(ends[0], toStart)));
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

    /** Three free cells over solid, dry ground. */
    private static boolean fits(ServerLevel level, BlockPos base) {
        BlockPos below = base.below();
        if (!level.getFluidState(below).isEmpty() || !level.getFluidState(base).isEmpty() || level.getBlockState(below).getCollisionShape(level, below).isEmpty()) return false;
        for (int i = 0; i < 3; i++) {
            var state = level.getBlockState(base.above(i));
            if (!state.isAir() && !state.canBeReplaced()) return false;
        }
        return true;
    }

    /** Builds the post and writes both faces of its sign. */
    static void build(ServerLevel level, Spot spot) {
        BlockPos base = spot.base();
        level.setBlock(base, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(base.above(), Blocks.STONE_BRICK_WALL.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos at = base.above(2);
        level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, spot.rotation()), Block.UPDATE_ALL);
        if (level.getBlockEntity(at) instanceof SignBlockEntity sign) {
            sign.setText(text(spot.front()), true);
            sign.setText(text(spot.back()), false);
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
    static int serve(ServerLevel level, CorridorPlan plan, List<Double> ideal, double px, double pz, State state) {
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
        State state = State.get(level);
        for (CorridorPlan plan : plans) {
            List<Double> ideal = IDEAL.computeIfAbsent(plan, p -> positions(p, SPACING, EDGE));
            for (ServerPlayer player : level.players()) serve(level, plan, ideal, player.getX(), player.getZ(), state);
        }
    }

    /** Which markers have been dealt with (placed, or given up because nothing fitted). */
    static final class State extends SavedData {
        private static final String KEY = "underground_city_road_markers";
        final Set<String> done = new HashSet<>();
        /** Where each built post stands (the base block), for tools and tests; a key in {@link #done} without an entry here was given up. */
        final java.util.Map<String, Long> where = new java.util.HashMap<>();

        static State get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(State::load, State::new, KEY);
        }

        static State load(CompoundTag root) {
            State state = new State();
            ListTag list = root.getList("done", Tag.TAG_STRING);
            for (int i = 0; i < list.size() && i < 4096; i++) state.done.add(list.getString(i));
            ListTag built = root.getList("built", Tag.TAG_COMPOUND);
            for (int i = 0; i < built.size() && i < 4096; i++) state.where.put(built.getCompound(i).getString("key"), built.getCompound(i).getLong("at"));
            return state;
        }

        @Override public CompoundTag save(CompoundTag root) {
            ListTag list = new ListTag();
            for (String key : done) list.add(StringTag.valueOf(key));
            root.put("done", list);
            ListTag built = new ListTag();
            where.forEach((key, at) -> { CompoundTag entry = new CompoundTag(); entry.putString("key", key); entry.putLong("at", at); built.add(entry); });
            root.put("built", built);
            return root;
        }
    }
}
