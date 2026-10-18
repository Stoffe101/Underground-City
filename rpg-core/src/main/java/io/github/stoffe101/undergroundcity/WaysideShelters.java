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
import java.util.Optional;

/**
 * Wayside shelters (D-079): a small open stop (a pavilion with a campfire, or a well under a roof) with water and a road warden, built beside each inter-city road a few hundred blocks
 * from the nearest city, so that a walk of four minutes has somewhere to stop and someone to ask. The accepted pacing wants "something useful every
 * 300 to 450 blocks" on the road, optional and never compulsory (D-048); this is the smallest thing that is useful: a place to rest and a person who
 * answers from the real data (where the road leads and how far, where the next post is, what each end's law says).
 *
 * <p>Like the waymarkers ({@link RoadMarkers}) shelters are placed <b>at run time</b>, when a player comes within {@link #ACTIVATE} blocks of one, and
 * once each (the world remembers it, see {@link PlacedSites}). That keeps world generation, and so seed 5908, exactly as it was. A stop is one of the
 * {@link #KINDS} (a repeatable district), so it is built by {@link DistrictBuilder} with all its safeguards: it refuses a lot that contains a container, saves
 * what it replaced so {@code /uc_district undo <kind> confirm} can restore it, and cannot overlap another. The site is searched for
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
    /** A build that throws is tried again after {@link #RETRY_TICKS} (a minute) up to this many times in all; then it is reported to the operator until {@code /uc_shelters retry}. A warden is only looked for when a player is this near. */
    public static final int MAX_ATTEMPTS = 3, RETRY_TICKS = 1200, WARDEN_RANGE = 48;
    /** Most ground height difference across a lot (the district builds its own foundation, three blocks deep, and clears seven blocks of air); a checkpoint zone's keep-out margin. */
    static final int MAX_RELIEF = 3, ZONE_GAP = 20;
    /** The right-hand verge is where the waymarkers and checkpoint posts stand, so a shelter prefers the left; this many blocks of walking is what the right costs. */
    static final double RIGHT_PENALTY = 4.0;
    /** A warden answers for the road they stand within this many blocks of. */
    static final double NEAR_ROAD = 250.0;


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
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(kindOf(plan.id(), i));
            if (district == null) continue;
            int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
            double[] at = plan.pointAt(ideal.get(i));
            double distance = Math.hypot(at[0] - px, at[1] - pz);
            if (distance > ACTIVATE) continue;
            if (!RoadMarkers.areaLoaded(level, at[0], at[1], loadedRadius)) continue; // not yet: nothing is decided until the whole search area is in the world
            if (state.done.contains(key)) {
                if (state.where.containsKey(key)) built += verify(level, plans, plan, ideal, i, key, district, state, distance, width, depth);
                continue;
            }
            PlacedSites.Failure earlier = state.failed.get(key);
            if (earlier != null && level.getGameTime() - earlier.lastTime() < RETRY_TICKS) continue; // a failed attempt waits a minute before the next
            built += place(level, plans, plan, ideal.get(i), key, district, state, width, depth);
        }
        return built;
    }

    /** Looks for a site and builds the stop there, with the failure policy: a site that refuses is skipped, an unexpected failure is counted and retried, a stop with no ground is given up. Returns 1 if a stop was built. */
    private static int place(ServerLevel level, List<CorridorPlan> plans, CorridorPlan plan, double ideal, String key, DistrictCatalog.District district, PlacedSites state, int width, int depth) {
        long started = System.nanoTime();
        BlockPos placed = null;
        boolean failed = false;
        int tried = 0;
        for (Site site : sites(ground(level, plans), plan, ideal, width, depth)) {
            if (tried++ == TRIES) break;
            if (builtOver(level, site.origin(), district)) continue;
            // Recorded before the build: the builder saves the world's data as it finishes, so a server that dies right after it still knows the stop is done and does not build a second one beside it.
            // (Whether the blocks reached the disk is checked when a player next comes near: see verify.)
            state.done.add(key);
            state.where.put(key, site.origin().asLong());
            state.setDirty();
            try {
                DistrictBuilder.build(level, district, site.origin());
                placed = site.origin();
                break;
            } catch (IllegalStateException refused) {
                LOGGER.info("[UC] wayside shelter {}: site {} refused ({})", key, site.origin().toShortString(), refused.getMessage());
            } catch (RuntimeException error) {
                // The builder has put the ground back (and removed what it placed) before rethrowing. This is a fault, not a lack of ground, so it is not recorded as "given up".
                LOGGER.error("[UC] wayside shelter {}: building at {} failed", key, site.origin().toShortString(), error);
                state.where.remove(key);
                state.fail(key, level.getGameTime(), error.toString());
                failed = true;
                break;
            }
            state.where.remove(key);
        }
        if (placed != null) {
            state.failed.remove(key);
            state.verified.add(key); // just built: nothing to check until the next start
            state.setDirty();
            LOGGER.info("[UC] wayside shelter {} built at {} as {} ({} ms to search for the site and build it)", key, placed.toShortString(), district.id(), (System.nanoTime() - started) / 1_000_000L);
            return 1;
        }
        if (failed) {
            PlacedSites.Failure f = state.failed.get(key);
            if (f.attempts() >= MAX_ATTEMPTS) {
                state.done.add(key); // stops being retried by itself; listed as failed until an operator clears it
                LOGGER.error("[UC] wayside shelter {}: {} attempts failed (last: {}); it will not be tried again until /uc_shelters retry", key, f.attempts(), f.reason());
            } else {
                state.done.remove(key);
                LOGGER.warn("[UC] wayside shelter {}: attempt {} of {} failed; another in {} seconds", key, f.attempts(), MAX_ATTEMPTS, RETRY_TICKS / 20);
            }
        } else {
            state.done.add(key);
            LOGGER.info("[UC] wayside shelter {}: no level, dry, unbuilt ground within {} blocks of its spot; given up ({} ms)", key, SEARCH, (System.nanoTime() - started) / 1_000_000L);
        }
        state.setDirty();
        return 0;
    }

    /**
     * The first time a player comes near a stop that the world records as standing, since the world was loaded: is it really there? The record is saved the moment the
     * build finishes, the blocks only when the chunks are next saved, so a server that died in between leaves a record of a shelter that never reached the disk.
     * <ul>
     *   <li>No record in the district builder (an operator removed it with {@code /uc_district undo}): left alone, it was removed on purpose.</li>
     *   <li>More than half of its blocks gone: it never reached the disk (or the lot was wiped). It is removed properly and built again, counted like any failed build.</li>
     *   <li>A few blocks missing: a player's changes; left alone.</li>
     *   <li>The warden missing (and the lot's entities are loaded and a player is close): put back, as {@code /uc_district repopulate} does.</li>
     * </ul>
     * Returns 1 when the stop was built again.
     */
    private static int verify(ServerLevel level, List<CorridorPlan> plans, CorridorPlan plan, List<Double> ideal, int index, String key, DistrictCatalog.District district,
                              PlacedSites state, double distance, int width, int depth) {
        BlockPos origin = BlockPos.of(state.where.get(key));
        if (!state.verified.contains(key)) {
            if (!DistrictBuilder.hasInstance(level, district, origin)) {
                state.verified.add(key);
                LOGGER.info("[UC] wayside shelter {} at {} has no record in the district builder; it was removed on purpose (/uc_district undo) and stays removed", key, origin.toShortString());
                return 0;
            }
            DistrictBuilder.Survey survey = DistrictBuilder.survey(level, district, origin);
            if (survey.lost()) {
                LOGGER.error("[UC] wayside shelter {} is recorded at {} but only {} of its {} blocks are there (the blocks never reached the disk, or the lot was wiped); removing the remains and building it again",
                    key, origin.toShortString(), survey.matching(), survey.expected());
                try {
                    DistrictBuilder.undoAt(level, district, origin);
                } catch (RuntimeException error) {
                    LOGGER.error("[UC] wayside shelter {}: the remains could not be removed", key, error);
                    state.verified.add(key);
                    state.fail(key, level.getGameTime(), "the remains could not be removed: " + error);
                    return 0;
                }
                state.done.remove(key);
                state.where.remove(key);
                state.setDirty();
                return place(level, plans, plan, ideal.get(index), key, district, state, width, depth);
            }
            state.verified.add(key);
        }
        if (distance <= WARDEN_RANGE && level.isPositionEntityTicking(origin.offset(district.max().getX() / 2, 0, district.max().getZ() / 2))) {
            try {
                int back = DistrictBuilder.repopulateAt(level, district, origin);
                if (back > 0) LOGGER.warn("[UC] wayside shelter {}: the warden was missing and has been put back", key);
            } catch (RuntimeException error) {
                LOGGER.info("[UC] wayside shelter {}: the warden could not be checked now ({})", key, error.getMessage());
            }
        }
        return 0;
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
            List<Double> ideal = positions(plan, MAX_GAP, EDGE); // the plans are rebuilt on every call, so there is nothing to cache by; this is a few hundred steps along a road
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
                    String line = key + ": about " + Math.round(at[0]) + ", " + Math.round(at[1]) + " - " + status(level, state, key, kindOf(plan.id(), i));
                    ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    lines++;
                }
            }
            return lines;
        }));
        // Clears a failed or given-up stop so that the next player near it tries again. A stop that stands is never touched (remove it with /uc_district undo first).
        root.then(Commands.literal("retry").executes(ctx -> retry(ctx.getSource(), null))
            .then(Commands.argument("key", com.mojang.brigadier.arguments.StringArgumentType.string()).executes(ctx -> retry(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "key")))));
        event.getDispatcher().register(root);
    }

    /** What an operator is told about a stop. */
    static String status(ServerLevel level, PlacedSites state, String key, String kind) {
        Long where = state.where.get(key);
        PlacedSites.Failure failure = state.failed.get(key);
        if (where != null) {
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(kind);
            BlockPos origin = BlockPos.of(where);
            if (district != null && !DistrictBuilder.hasInstance(level, district, origin)) return "removed on purpose at " + origin.toShortString() + " (no district record; /uc_shelters retry will not rebuild a recorded stop)";
            return "built at " + origin.toShortString();
        }
        if (failure != null) {
            return (state.done.contains(key) ? "FAILED " + failure.attempts() + " times, no longer retried: " : "failed " + failure.attempts() + " of " + MAX_ATTEMPTS + ", will retry: ") + failure.reason() + " (/uc_shelters retry " + key + ")";
        }
        return state.done.contains(key) ? "given up (no suitable ground; /uc_shelters retry " + key + " to look again)" : "waiting for a player";
    }

    static int retry(net.minecraft.commands.CommandSourceStack source, String only) {
        int cleared = retry(PlacedSites.get(source.getServer().overworld(), PlacedSites.SHELTERS), only);
        source.sendSuccess(() -> Component.literal(cleared == 0 ? "Nothing to retry: every stop is built or waiting." : cleared + " stop(s) will be tried again when a player is near."), true);
        return cleared;
    }

    /** Forgets that these stops failed or were given up, so the next player near them tries again; a stop that stands is never touched. Returns how many were cleared. */
    static int retry(PlacedSites state, String only) {
        int cleared = 0;
        for (String key : new ArrayList<>(state.done)) {
            if (state.where.containsKey(key) || (only != null && !only.equals(key))) continue; // a stop that stands is not retried
            state.done.remove(key);
            state.failed.remove(key);
            cleared++;
        }
        for (String key : new ArrayList<>(state.failed.keySet())) {
            if (only != null && !only.equals(key)) continue;
            state.failed.remove(key);
            cleared++;
        }
        state.setDirty();
        return cleared;
    }
}
