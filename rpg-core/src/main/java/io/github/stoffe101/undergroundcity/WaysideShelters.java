package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Wayside shelters (D-079): a small open pavilion with a campfire, water and a road warden, built beside each inter-city road a few hundred blocks
 * from the nearest city, so that a walk of four minutes has somewhere to stop and someone to ask. The accepted pacing wants "something useful every
 * 300 to 450 blocks" on the road, optional and never compulsory (D-048); this is the smallest thing that is useful: a place to rest and a person who
 * answers from the real data (where the road leads and how far, where the next post is, what each end's law says).
 *
 * <p>Like the waymarkers ({@link RoadMarkers}) shelters are placed <b>at run time</b>, when a player comes within {@link #ACTIVATE} blocks of one, and
 * once each (the world remembers it, see {@link PlacedSites}). That keeps world generation, and so seed 5908, exactly as it was. A shelter is the
 * {@code wayside_shelter} district, so it is built by {@link DistrictBuilder} with all its safeguards: it refuses a lot that contains a container, saves
 * what it replaced so {@code /uc_district undo wayside_shelter confirm} can restore it, and cannot overlap another. The site is searched for
 * ground that is level, dry, outside every city and checkpoint zone and clear of anything built; where nothing fits within {@link #SEARCH} blocks of the
 * ideal spot, that shelter is given up.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class WaysideShelters {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** The kinds of wayside stop, all the same size and with the same warden: which one stands at a spot is decided by its road and index, so it never changes. */
    static final List<String> KINDS = List.of("wayside_shelter", "wayside_well");
    /** Most blocks between shelters on open road (the design asks for something useful every 300 to 450), the nearest a shelter may stand to either city circle, how far a site may slide along the road, the step of that slide. */
    public static final int MAX_GAP = 450, EDGE = 150, SEARCH = 64, SHIFT = 16;
    public static final int ACTIVATE = 112, CHECK_TICKS = 100, TRIES = 6;
    /** Most ground height difference across a lot (the district builds its own foundation, three blocks deep, and clears seven blocks of air); a checkpoint zone's keep-out margin. */
    static final int MAX_RELIEF = 3, ZONE_GAP = 20;
    /** The right-hand verge is where the waymarkers and checkpoint posts stand, so a shelter prefers the left; this many blocks of walking is what the right costs. */
    static final double RIGHT_PENALTY = 4.0;
    /** A warden answers for the road they stand within this many blocks of. */
    static final double NEAR_ROAD = 250.0;

    private static final Map<CorridorPlan, List<Double>> IDEAL = new WeakHashMap<>();

    private WaysideShelters() {}

    // ------------------------------------------------------------------------------------------ pure planning

    /** Which kind of stop stands at a spot of a road: the same answer in every world and every run, and consecutive stops on one road are always different kinds. */
    static String kindOf(String road, int index) {
        return KINDS.get(Math.floorMod(road.hashCode() + index, KINDS.size()));
    }

    /**
     * Distances along a road at which its shelters should stand: evenly spread over the open road with no more than {@code maxGap} blocks between one
     * shelter and the next (or the city at either end), at least two stretches so that a road of any length has a shelter in the middle, and never
     * within {@code edge} blocks of a city circle. Roads shorter than twice {@code edge} get none.
     */
    static List<Double> positions(CorridorPlan plan, int maxGap, int edge) {
        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan), open = last - first;
        if (open < 2.0 * edge) return List.of();
        int stretches = Math.max(2, (int) Math.ceil(open / maxGap));
        List<Double> result = new ArrayList<>();
        for (int k = 1; k < stretches; k++) {
            double s = first + open * k / stretches;
            if (s >= first + edge && s <= last - edge) result.add(s);
        }
        return result;
    }

    /** A candidate lot: its north-west corner (y is the standing level) and how good it is (lower is better: relief weighs most, then distance from the ideal spot). */
    record Site(BlockPos origin, int shift, int side, int relief, double score) {}

    /** Every lot beside the road within {@link #SEARCH} blocks of {@code ideal} that is level enough, best first. Pure: the ground is supplied. */
    static List<Site> sites(DistrictSites.Ground ground, CorridorPlan plan, double ideal, int width, int depth) {
        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan);
        List<Site> found = new ArrayList<>();
        for (int shift = 0; shift <= SEARCH; shift += SHIFT) {
            for (int along : shift == 0 ? new int[]{1} : new int[]{1, -1}) {
                double s = ideal + along * shift;
                if (s < first + 40 || s > last - 40) continue;
                for (int side : new int[]{-1, 1}) {
                    Optional<CheckpointPosts.Lot> lot = CheckpointPosts.lotBeside(plan, s, width, depth, side);
                    if (lot.isEmpty()) continue;
                    BlockPos corner = lot.get().origin();
                    DistrictSites.Site site = DistrictSites.evaluate(ground, corner.getX(), corner.getZ(), width, depth, shift);
                    if (site == null || site.relief() > MAX_RELIEF) continue;
                    found.add(new Site(new BlockPos(corner.getX(), site.y(), corner.getZ()), shift, side, site.relief(), site.relief() * 12.0 + shift + (side > 0 ? RIGHT_PENALTY : 0.0)));
                }
            }
        }
        found.sort(Comparator.comparingDouble(Site::score));
        return found;
    }

    /** The road a point is nearest to, if any road runs within {@link #NEAR_ROAD} blocks of it. */
    private record OnRoad(CorridorPlan plan, CorridorPlan.Hit hit, String[] ends) {}

    private static Optional<OnRoad> roadAt(List<CorridorPlan> plans, double x, double z) {
        CorridorPlan best = null;
        CorridorPlan.Hit bestHit = null;
        for (CorridorPlan plan : plans) {
            CorridorPlan.Hit hit = plan.project(x, z);
            if (hit != null && (bestHit == null || hit.distance() < bestHit.distance())) { best = plan; bestHit = hit; }
        }
        if (best == null || bestHit.distance() > NEAR_ROAD) return Optional.empty();
        String[] ends = RoadMarkers.ends(best.id());
        return ends == null ? Optional.empty() : Optional.of(new OnRoad(best, bestHit, ends));
    }

    private static String city(String upper) { return TerminalScreenText.capitalize(upper.toLowerCase(Locale.ROOT)); }

    /** What the warden says when asked where the road leads: both cities, in which compass direction and how far, by the same measure as the waymarker signs. */
    static String where(List<CorridorPlan> plans, double x, double z) {
        Optional<OnRoad> road = roadAt(plans, x, z);
        if (road.isEmpty()) return "I cannot place this spot on any road I know.";
        CorridorPlan plan = road.get().plan();
        double s = road.get().hit().s();
        double[] behind = plan.pointAt(Math.max(0, s - 3)), ahead = plan.pointAt(Math.min(plan.length(), s + 3));
        Direction forward = RoadMarkers.direction(ahead[0] - behind[0], ahead[1] - behind[1]);
        double toStart = Math.max(0, s - RoadMarkers.firstOpen(plan)), toEnd = Math.max(0, RoadMarkers.lastOpen(plan) - s);
        String[] ends = road.get().ends();
        return "This is the road between " + city(ends[0]) + " and " + city(ends[1]) + ". " + city(ends[0]) + " lies " + forward.getOpposite().getName() + ", "
            + describe(toStart) + "; " + city(ends[1]) + " lies " + forward.getName() + ", " + describe(toEnd) + ".";
    }

    /** "about 380 blocks (1.5 min on foot)": the signs round to whole minutes, a person standing in front of the warden is told the half minute too. */
    private static String describe(double metres) {
        long halves = Math.round(metres / RoadMarkers.WALK / 30.0);
        String time = halves <= 1 ? "under a minute" : (halves % 2 == 0 ? String.valueOf(halves / 2) : (halves / 2) + ".5") + " min";
        return "about " + String.format(Locale.ROOT, "%,d", Math.round(metres / 10.0) * 10) + " blocks (" + time + " on foot)";
    }

    /** What the warden says when asked about the law at either end: the real summaries from the city law data. */
    static String roadLaw(List<CorridorPlan> plans, double x, double z) {
        Optional<OnRoad> road = roadAt(plans, x, z);
        if (road.isEmpty()) return "I cannot place this spot on any road I know.";
        StringBuilder text = new StringBuilder();
        for (String end : road.get().ends()) {
            CityLaws.Law law = CityLaws.INSTANCE.find(end.toLowerCase(Locale.ROOT));
            if (law != null) text.append(text.isEmpty() ? "" : " ").append(city(end)).append(": ").append(law.summary());
        }
        return text.isEmpty() ? "There is no law posted for either end." : text.toString();
    }

    // ------------------------------------------------------------------------------------------ the world

    /** The ground of a loaded level as the site search sees it. A column that is not loaded counts as reserved, so the search never loads or generates a chunk. */
    static DistrictSites.Ground ground(ServerLevel level, List<CorridorPlan> plans) {
        return new DistrictSites.Ground() {
            @Override public int height(int x, int z) { return DevCommands.surface(level, x, z).getY(); }
            /** Water or ice at the surface or in the three blocks under it: the surface scan steps over water (it has no collision) but stops on ice, so a frozen sea reads as solid ground. */
            @Override public boolean wet(int x, int z) {
                BlockPos at = DevCommands.surface(level, x, z);
                for (int depth = 0; depth <= 3; depth++) {
                    BlockState state = level.getBlockState(at.below(depth));
                    if (!state.getFluidState().isEmpty() || state.is(BlockTags.ICE)) return true;
                }
                return false;
            }
            @Override public boolean reserved(int x, int z) {
                if (!level.hasChunk(x >> 4, z >> 4)) return true;
                if (CityRegions.INSTANCE.cityAt(level, new BlockPos(x, level.getSeaLevel(), z)).isPresent()) return true;
                String dimension = level.dimension().location().toString();
                for (CheckpointCatalog.Checkpoint post : CheckpointCatalog.INSTANCE.all()) {
                    double keepOut = post.radius() + ZONE_GAP;
                    if (post.dimension().equals(dimension) && post.distanceSquared(x + 0.5, z + 0.5) < keepOut * keepOut) return true;
                }
                // Inside the deck of any road (this one is kept clear by the lot's own placement; this is for another that crosses the site).
                for (CorridorPlan plan : plans) {
                    CorridorPlan.Hit hit = plan.project(x + 0.5, z + 0.5);
                    if (hit != null && hit.distance() < plan.reach() - 2.0) return true;
                }
                return false;
            }
        };
    }

    /** True when the lot holds something people built (a house, a farm fence, a village or a mineshaft's timbers): a shelter never replaces those. */
    static boolean builtOver(ServerLevel level, BlockPos origin, DistrictCatalog.District district) {
        for (BlockPos at : BlockPos.betweenClosed(origin.offset(district.min()), origin.offset(district.max()))) {
            BlockState state = level.getBlockState(at);
            if (state.isAir()) continue;
            if (state.is(BlockTags.PLANKS) || state.is(BlockTags.WOOL) || state.is(BlockTags.DOORS) || state.is(BlockTags.BEDS) || state.is(BlockTags.STAIRS)
                || state.is(BlockTags.SLABS) || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.ALL_SIGNS)) return true;
        }
        return false;
    }

    /** Builds the shelter of every stretch of a road that a player is near and that was not built or given up before. Returns how many were built. */
    static int serve(ServerLevel level, List<CorridorPlan> plans, CorridorPlan plan, List<Double> ideal, double px, double pz, PlacedSites state) {
        return serve(level, plans, plan, ideal, px, pz, state, SEARCH + 24);
    }

    /** As above; {@code loadedRadius} is how far around the ideal spot the world must be loaded before anything is decided (a test, which forces its own chunks, passes a smaller one). */
    static int serve(ServerLevel level, List<CorridorPlan> plans, CorridorPlan plan, List<Double> ideal, double px, double pz, PlacedSites state, int loadedRadius) {
        int built = 0;
        for (int i = 0; i < ideal.size(); i++) {
            String key = plan.id() + "#" + i;
            if (state.done.contains(key)) continue;
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(kindOf(plan.id(), i));
            if (district == null) continue;
            int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
            double[] at = plan.pointAt(ideal.get(i));
            if (Math.hypot(at[0] - px, at[1] - pz) > ACTIVATE) continue;
            if (!RoadMarkers.areaLoaded(level, at[0], at[1], loadedRadius)) continue; // not yet: nothing is decided until the whole search area is in the world
            long started = System.nanoTime();
            BlockPos placed = null;
            int tried = 0;
            for (Site site : sites(ground(level, plans), plan, ideal.get(i), width, depth)) {
                if (tried++ == TRIES) break;
                if (builtOver(level, site.origin(), district)) continue;
                try {
                    DistrictBuilder.build(level, district, site.origin());
                    placed = site.origin();
                    break;
                } catch (IllegalStateException refused) {
                    LOGGER.info("[UC] wayside shelter {}: site {} refused ({})", key, site.origin().toShortString(), refused.getMessage());
                }
            }
            if (placed != null) {
                state.where.put(key, placed.asLong());
                built++;
                LOGGER.info("[UC] wayside shelter {} built at {} as {} ({} ms to search for the site and build it)", key, placed.toShortString(), district.id(), (System.nanoTime() - started) / 1_000_000L);
            } else {
                LOGGER.info("[UC] wayside shelter {}: no level, dry, unbuilt ground within {} blocks of its spot; given up ({} ms)", key, SEARCH, (System.nanoTime() - started) / 1_000_000L);
            }
            state.done.add(key);
            state.setDirty();
        }
        return built;
    }

    // ------------------------------------------------------------------------------------------ events

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getGameTime() % CHECK_TICKS != CHECK_TICKS / 2 || level.players().isEmpty()) return; // out of step with the waymarkers' check
        List<CorridorPlan> plans = DevCommands.plans(level.getServer());
        if (plans.isEmpty()) return;
        PlacedSites state = PlacedSites.get(level, PlacedSites.SHELTERS);
        for (CorridorPlan plan : plans) {
            List<Double> ideal = IDEAL.computeIfAbsent(plan, p -> positions(p, MAX_GAP, EDGE));
            for (ServerPlayer player : level.players()) serve(level, plans, plan, ideal, player.getX(), player.getZ(), state);
        }
    }

    /** {@code /uc_shelters list} says where each shelter should stand and whether it was built, so an operator (or a tester) can walk to one. */
    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_shelters").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("list").executes(ctx -> {
            ServerLevel level = ctx.getSource().getServer().overworld();
            PlacedSites state = PlacedSites.get(level, PlacedSites.SHELTERS);
            int lines = 0;
            for (CorridorPlan plan : DevCommands.plans(level.getServer())) {
                List<Double> ideal = positions(plan, MAX_GAP, EDGE);
                for (int i = 0; i < ideal.size(); i++) {
                    double[] at = plan.pointAt(ideal.get(i));
                    String key = plan.id() + "#" + i;
                    Long where = state.where.get(key);
                    String status = where != null ? "built at " + BlockPos.of(where).toShortString() : state.done.contains(key) ? "given up (no suitable ground)" : "waiting for a player";
                    String line = key + ": about " + Math.round(at[0]) + ", " + Math.round(at[1]) + " - " + status;
                    ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    lines++;
                }
            }
            return lines;
        }));
        event.getDispatcher().register(root);
    }
}
