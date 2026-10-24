package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleToIntFunction;

/**
 * Roadside places (D-138): a diner, a farm, a salvage yard, a freight depot, a motel and a ruin beside the three inter-city roads, each a generated district with something to do
 * in it (a shopkeeper's counter, a home to rent or buy, a regrowing salvage site, a safe, one-time loot, a hidden den). The roads were reported as long stretches of nothing but W.
 *
 * <p>Like the wayside shelters ({@link WaysideShelters}), a place is built <b>at run time</b>, the first time a player comes near where it should stand, once per world, and
 * only in a world that a new archipelago start enabled ({@link #enable}); an existing save is never given one unless an operator says so ({@code /uc_roadside enable}).
 *
 * <p>Where: a place names its road and how far along the open road it belongs ({@code at}, 0 to 1). The road plan knows which stretches are bridge or water, so the candidate
 * spots are worked out without loading anything: the nearest stretches of land long enough for the lot, clear of the shelters' spots and of the places before it on the road.
 * The candidates are tried in that order, each once its ground is loaded (and only then: the search never makes the server generate a chunk); a candidate is given up only when
 * nothing on it fits, and a later one is tried only after every earlier one was given up, so the same terrain always gets the same answer whichever way a player comes.
 *
 * <p>Which way it faces: a place is drawn with its front to the north and generated in four rotations; the one whose front looks at the road is built, and a driveway is laid
 * from the middle of that front to the deck (natural ground cut and filled only, as the road-end ramps do; {@link RoadApproaches#column}). A lot whose standing level is more
 * than {@link #MAX_STEP_TO_DECK} blocks off the deck's is refused, so the driveway never needs a step a person cannot walk.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class RoadsidePlaces {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String KEY = "underground_city_roadside_places", ENABLED = "#enabled";
    /** The building key of a roadside place's shop counters ({@code shop_counters/roadside/}): {@code roadside:<shop>}. */
    static final String SHOP_PREFIX = "roadside:";

    /** True when a district is one of the roadside places' (any rotation). */
    static boolean isPlaceDistrict(String district) { return CATALOG.all().stream().anyMatch(place -> place.variants().containsValue(district)); }
    /** A player this near a candidate spot gets it decided; how often; how many lots are looked at per candidate; how uneven a lot may be; how far a site may slide along the road, in steps of. */
    static final int ACTIVATE = 144, CHECK_TICKS = 100, TRIES = 10, MAX_RELIEF = 16, SEARCH = 40, SHIFT = 8;
    /** No place within this many blocks of a city circle; this far from a shelter's spot or another place; at most this many candidate spots per place. */
    static final int EDGE = 110, KEEP = 20, CANDIDATES = 12;
    /** The most blocks between a lot's standing level and the road's: over a driveway of a dozen blocks that is a gentle slope. */
    static final int MAX_STEP_TO_DECK = 5;
    /** A place's people are looked for (and put back if lost) when a player is this near. */
    static final int KEEPER_RANGE = 48;

    private RoadsidePlaces() {}

    // ------------------------------------------------------------------------------------------ the catalog
    /** One place: its road, where along it, its lot size as drawn (front to the north) and the district of each rotation. */
    public record Place(String id, String title, String road, double at, String kind, int width, int depth, Map<String, String> variants) {
        /** The lot of the variant whose front faces {@code facing}: width (x) and depth (z). */
        int[] size(String facing) { return facing.equals("north") || facing.equals("south") ? new int[]{width, depth} : new int[]{depth, width}; }
        /** The block a driveway is surfaced with: gravel at a yard, a worn path at a farm or a ruin, asphalt at a diner, a motel or a depot. */
        BlockState surface() {
            return switch (kind) {
                case "industry" -> Blocks.GRAVEL.defaultBlockState();
                case "farm", "ruin" -> Blocks.DIRT_PATH.defaultBlockState();
                default -> Blocks.GRAY_CONCRETE.defaultBlockState();
            };
        }
    }

    public static final class Catalog extends SimpleJsonResourceReloadListener {
        private volatile Map<String, Place> places = Map.of();

        private Catalog() { super(new Gson(), "roadside_places"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            Map<String, Place> next = new LinkedHashMap<>();
            try {
                for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    Place place = parse(entry.getValue().getAsJsonObject());
                    next.put(place.id(), place);
                }
                places = Map.copyOf(next);
                LOGGER.info("Loaded {} Underground City roadside places", places.size());
            } catch (RuntimeException error) {
                LOGGER.error("Roadside place data is invalid; keeping the previous places", error);
            }
        }

        static Place parse(JsonObject json) {
            if (json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("roadside place: schema_version must be 1");
            String id = json.get("id").getAsString();
            double at = json.get("at").getAsDouble();
            if (!id.matches("[a-z0-9_]{3,40}") || at < 0 || at > 1) throw new IllegalArgumentException("roadside place " + id + ": bad id or position");
            var size = json.getAsJsonArray("size");
            Map<String, String> variants = new LinkedHashMap<>();
            for (String facing : new String[]{"north", "east", "south", "west"}) variants.put(facing, json.getAsJsonObject("variants").get(facing).getAsString());
            return new Place(id, json.get("title").getAsString(), json.get("road").getAsString(), at, json.get("kind").getAsString(), size.get(0).getAsInt(), size.get(1).getAsInt(), Map.copyOf(variants));
        }

        public List<Place> all() { return List.copyOf(places.values()); }
        public Place find(String id) { return places.get(id); }
        List<Place> onRoad(String road) { return places.values().stream().filter(p -> p.road().equals(road)).toList(); }
    }

    public static final Catalog CATALOG = new Catalog();

    /** A new archipelago world started by this version gets the places; an older one never does by itself. */
    static void enable(ServerLevel level) {
        PlacedSites state = PlacedSites.get(level, KEY);
        if (state.done.add(ENABLED)) state.setDirty();
    }

    static boolean enabled(ServerLevel level) { return PlacedSites.get(level, KEY).done.contains(ENABLED); }

    // ------------------------------------------------------------------------------------------ pure planning
    /** True where the road runs on land between {@code from} and {@code to}: no bridge, no water under the deck. */
    static boolean land(CorridorPlan plan, double from, double to) {
        for (double t = from; t <= to; t += 4.0) if (plan.bridgeAt(t) || plan.waterAt(t)) return false;
        return true;
    }

    /**
     * The spots along {@code plan} where {@code place} may stand, best first: the nearest to its share of the open road on a stretch of land long enough for its lot, at least
     * {@link #EDGE} blocks from either city circle and clear of {@code avoid} (the shelters' spots and the places before it on this road).
     */
    static List<Double> candidates(CorridorPlan plan, Place place, List<Double> avoid) { return candidates(plan, place, avoid, (x, z, r) -> false); }

    /** Whether a point is within {@code margin} blocks of a city's region (where the law, the exchange and "In Ironhaven" begin). */
    @FunctionalInterface interface NearCity { boolean test(double x, double z, double margin); }

    /** The cities of the loaded data, for {@link #candidates}: the civic region's circle, which is wider than the road's city circle. */
    static NearCity cities() {
        return (x, z, margin) -> CityRegions.INSTANCE.all().stream().anyMatch(r -> r.dimension().equals("minecraft:overworld")
            && Math.hypot(x - (r.centerChunkX() * 16 + 8), z - (r.centerChunkZ() * 16 + 8)) < r.radius() + margin);
    }

    /** As above, never within {@code need} blocks of a city's region (the October 23 road check found half the spots near a road's ends inside one). */
    static List<Double> candidates(CorridorPlan plan, Place place, List<Double> avoid, NearCity near) {
        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan);
        double target = first + place.at() * (last - first);
        double need = Math.hypot(place.width(), place.depth()) / 2.0 + 12.0;
        List<Double> out = new ArrayList<>();
        for (int i = 0; out.size() < CANDIDATES && i < 600; i++) {
            double s = target + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 8.0;
            if (s < first + EDGE || s > last - EDGE) continue;
            if (!land(plan, s - need, s + need)) continue;
            double[] point = plan.pointAt(s);
            if (near.test(point[0], point[1], need + 16)) continue;
            if (avoid.stream().anyMatch(a -> Math.abs(a - s) < KEEP + need)) continue;
            if (out.stream().anyMatch(o -> Math.abs(o - s) < 48)) continue;
            out.add(s);
        }
        return out;
    }

    /** The candidates of every place on a road, in catalog order, each place keeping clear of the shelters and of the first choice of the places before it. */
    static Map<String, List<Double>> plan(CorridorPlan plan, List<Place> places) { return plan(plan, places, cities()); }

    static Map<String, List<Double>> plan(CorridorPlan plan, List<Place> places, NearCity near) {
        List<Double> avoid = new ArrayList<>(WaysideShelters.positions(plan, WaysideShelters.MAX_GAP, WaysideShelters.EDGE));
        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (Place place : places) {
            List<Double> found = candidates(plan, place, avoid, near);
            out.put(place.id(), found);
            if (!found.isEmpty()) avoid.add(found.get(0));
        }
        return out;
    }

    /** The nearest compass direction of a vector (x is east, z is south). */
    static String facing(double dx, double dz) {
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north");
    }

    /** A lot beside the road: the corner (y is the standing level), which way its front faces, how uneven and how good (lower is better). */
    record Site(BlockPos origin, String facing, int relief, double score, double s, int side) {}

    /** The top block of the road's deck at each arc position, from the plan's built profile. */
    static DoubleToIntFunction deck(CorridorPlan plan) { return s -> CorridorPlan.deckBlockY(plan.deckHalfAt(s)); }

    /**
     * Every lot for {@code place} within {@link #SEARCH} blocks of {@code ideal}, on either side, level enough and near the deck's height ({@code deck} gives the deck's top block
     * at an arc position), best first. Pure: the ground is supplied.
     */
    static List<Site> sites(DistrictSites.Ground ground, CorridorPlan plan, Place place, double ideal, DoubleToIntFunction deck) {
        double first = RoadMarkers.firstOpen(plan), last = RoadMarkers.lastOpen(plan);
        List<Site> found = new ArrayList<>();
        for (int shift = 0; shift <= SEARCH; shift += SHIFT) {
            for (int along : shift == 0 ? new int[]{1} : new int[]{1, -1}) {
                double s = ideal + along * shift;
                if (s < first + 40 || s > last - 40) continue;
                double[] behind = plan.pointAt(Math.max(0, s - 3)), ahead = plan.pointAt(Math.min(plan.length(), s + 3));
                double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], length = Math.hypot(tx, tz);
                if (length < 1e-6) continue;
                tx /= length; tz /= length;
                int stand = deck.applyAsInt(s) + 1;
                for (int side : new int[]{-1, 1}) {
                    double nx = -tz * side, nz = tx * side;            // from the road toward the lot
                    String facing = facing(-nx, -nz);                   // the front looks back at the road
                    int[] size = place.size(facing);
                    Optional<CheckpointPosts.Lot> lot = CheckpointPosts.lotBeside(plan, s, size[0], size[1], side);
                    if (lot.isEmpty()) continue;
                    BlockPos corner = lot.get().origin();
                    DistrictSites.Site site = DistrictSites.evaluate(ground, corner.getX(), corner.getZ(), size[0], size[1], shift);
                    if (site == null || site.relief() > MAX_RELIEF || Math.abs(site.y() - stand) > MAX_STEP_TO_DECK) continue;
                    found.add(new Site(new BlockPos(corner.getX(), site.y(), corner.getZ()), facing, site.relief(), site.relief() * 12.0 + shift + Math.abs(site.y() - stand) * 4.0, s, side));
                }
            }
        }
        found.sort(Comparator.comparingDouble(Site::score));
        return found;
    }

    /** Why no lot fits near {@code ideal}: how many lots were refused for each reason, and the best relief and step to the deck among those that were not reserved or wet. */
    static String explain(DistrictSites.Ground ground, CorridorPlan plan, Place place, double ideal, DoubleToIntFunction deck) { return explain(ground, plan, place, ideal, deck, null); }

    /** The reasons a refused lot was refused, by kind (the first column that refused it decides). */
    static final class Reasons extends java.util.TreeMap<String, Integer> {
        final ServerLevel level; final List<CorridorPlan> plans;
        Reasons(ServerLevel level, List<CorridorPlan> plans) { this.level = level; this.plans = plans; }
    }

    /** The first reason a lot's sampled columns give for refusing it, as the site search samples them. */
    static String refusal(ServerLevel level, List<CorridorPlan> plans, int x0, int z0, int width, int depth) {
        DistrictSites.Ground shelters = WaysideShelters.ground(level, plans);
        List<int[]> lots = builtLots(level);
        for (int x = x0 - DistrictSites.MARGIN; x <= x0 + width + DistrictSites.MARGIN; x += DistrictSites.SAMPLE) {
            for (int z = z0 - DistrictSites.MARGIN; z <= z0 + depth + DistrictSites.MARGIN; z += DistrictSites.SAMPLE) {
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return "unloaded";
                if (CityRegions.INSTANCE.cityAt(level, new BlockPos(x, level.getSeaLevel(), z)).isPresent()) return "city";
                for (int[] lot : lots) if (x >= lot[0] && x <= lot[2] && z >= lot[1] && z <= lot[3]) return "built lot";
                for (CorridorPlan other : plans) { CorridorPlan.Hit hit = other.project(x + 0.5, z + 0.5); if (hit != null && hit.distance() < other.reach() - 2.0) return "road"; }
                if (shelters.reserved(x, z)) return "checkpoint";
                if (shelters.wet(x, z)) return "water";
            }
        }
        return "a corner";
    }

    static String explain(DistrictSites.Ground ground, CorridorPlan plan, Place place, double ideal, DoubleToIntFunction deck, Reasons why) {
        int lots = 0, blocked = 0, rough = 0, high = 0;
        int bestRelief = Integer.MAX_VALUE, bestStep = Integer.MAX_VALUE;
        for (int shift = 0; shift <= SEARCH; shift += SHIFT) for (int along : shift == 0 ? new int[]{1} : new int[]{1, -1}) {
            double s = ideal + along * shift;
            double[] behind = plan.pointAt(Math.max(0, s - 3)), ahead = plan.pointAt(Math.min(plan.length(), s + 3));
            double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], length = Math.hypot(tx, tz);
            if (length < 1e-6) continue;
            tx /= length; tz /= length;
            int stand = deck.applyAsInt(s) + 1;
            for (int side : new int[]{-1, 1}) {
                String facing = facing(tz * side, -tx * side);
                int[] size = place.size(facing);
                var lot = CheckpointPosts.lotBeside(plan, s, size[0], size[1], side);
                if (lot.isEmpty()) continue;
                lots++;
                DistrictSites.Site site = DistrictSites.evaluate(ground, lot.get().origin().getX(), lot.get().origin().getZ(), size[0], size[1], shift);
                if (site == null) {
                    blocked++;
                    if (why != null) why.merge(refusal(why.level, why.plans, lot.get().origin().getX(), lot.get().origin().getZ(), size[0], size[1]), 1, Integer::sum);
                    continue;
                }
                bestRelief = Math.min(bestRelief, site.relief());
                bestStep = Math.min(bestStep, Math.abs(site.y() - stand));
                if (site.relief() > MAX_RELIEF) rough++;
                else if (Math.abs(site.y() - stand) > MAX_STEP_TO_DECK) high++;
            }
        }
        return lots + " lots: " + blocked + " reserved, unloaded or wet" + (why == null ? "" : " " + why) + ", " + rough + " too uneven, " + high + " too far above or below the deck (best relief " + (bestRelief == Integer.MAX_VALUE ? "-" : bestRelief)
            + ", best step " + (bestStep == Integer.MAX_VALUE ? "-" : bestStep) + ")";
    }

    // ------------------------------------------------------------------------------------------ the world
    /** Built lots (shelters and places) as x0, z0, x1, z1 with a margin: nothing else is put on them. */
    static List<int[]> builtLots(ServerLevel level) {
        List<int[]> lots = new ArrayList<>();
        PlacedSites shelters = PlacedSites.get(level, PlacedSites.SHELTERS);
        for (var built : shelters.where.entrySet()) {
            int hash = built.getKey().lastIndexOf('#');
            if (hash < 1) continue;
            try {
                DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(WaysideShelters.kindOf(built.getKey().substring(0, hash), Integer.parseInt(built.getKey().substring(hash + 1))));
                if (district != null) lots.add(box(BlockPos.of(built.getValue()), district, 6));
            } catch (NumberFormatException ignored) { }
        }
        PlacedSites places = PlacedSites.get(level, KEY);
        for (var built : places.where.entrySet()) {
            Place place = CATALOG.find(built.getKey());
            Construction going = BUILDING.get(built.getKey());
            DistrictCatalog.District district = going != null ? going.district : place == null ? null : builtVariant(level, place);
            if (district != null) lots.add(box(BlockPos.of(built.getValue()), district, 6));
        }
        return lots;
    }

    private static int[] box(BlockPos origin, DistrictCatalog.District district, int margin) {
        return new int[]{origin.getX() + district.min().getX() - margin, origin.getZ() + district.min().getZ() - margin, origin.getX() + district.max().getX() + margin, origin.getZ() + district.max().getZ() + margin};
    }

    /** The district of the rotation of {@code place} that this world built, or null. */
    static DistrictCatalog.District builtVariant(ServerLevel level, Place place) {
        for (String id : place.variants().values()) if (DistrictBuilder.isBuilt(level, id)) return DistrictCatalog.INSTANCE.find(id);
        return null;
    }

    /**
     * The ground as the site search sees it: the wayside shelters' rules (no city, no checkpoint zone, no road deck), plus every built lot, and a column whose chunk is not generated
     * and loaded right now ({@code getChunkNow}) counts as reserved, so the search never makes the server wait for a chunk.
     */
    static DistrictSites.Ground ground(ServerLevel level, List<CorridorPlan> plans) {
        DistrictSites.Ground shelters = WaysideShelters.ground(level, plans);
        List<int[]> lots = builtLots(level);
        return new DistrictSites.Ground() {
            @Override public int height(int x, int z) { return shelters.height(x, z); }
            @Override public boolean wet(int x, int z) { return shelters.wet(x, z); }
            @Override public boolean reserved(int x, int z) {
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return true;
                for (int[] lot : lots) if (x >= lot[0] && x <= lot[2] && z >= lot[1] && z <= lot[3]) return true;
                return shelters.reserved(x, z);
            }
        };
    }

    /** True when every chunk within {@code radius} blocks of (x, z) is generated and loaded now. */
    static boolean loaded(ServerLevel level, double x, double z, int radius) {
        for (int cx = ((int) Math.floor(x) - radius) >> 4; cx <= ((int) Math.floor(x) + radius) >> 4; cx++) {
            for (int cz = ((int) Math.floor(z) - radius) >> 4; cz <= ((int) Math.floor(z) + radius) >> 4; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        return true;
    }

    /** Which candidate of each place has been given up in this run (not saved: the same terrain gives the same answer after a restart). */
    private static final Map<String, Integer> NEXT = new HashMap<>();

    /** What one pass over a place did, for the road check and the log. */
    record Outcome(String place, String status, BlockPos origin, String facing, int relief, int driveway, int drivewayStep, long millis) {}

    /** Looks at one place for a player at (px, pz): builds it if its turn has come and a site fits. Returns what happened, or null when nothing was decided. */
    static Outcome serve(ServerLevel level, List<CorridorPlan> plans, Place place, List<Double> candidates, double px, double pz, PlacedSites state, int loadedRadius) {
        CorridorPlan road = plans.stream().filter(p -> p.id().equals(place.road())).findFirst().orElse(null);
        return road == null ? null : serve(level, plans, place, candidates, px, pz, state, loadedRadius, deck(road));
    }

    /** As above, with the deck's height supplied (a test's synthetic road has no built profile). Builds the place at once (the road check and the GameTests). */
    static Outcome serve(ServerLevel level, List<CorridorPlan> plans, Place place, List<Double> candidates, double px, double pz, PlacedSites state, int loadedRadius, DoubleToIntFunction deck) {
        return serve(level, plans, place, candidates, px, pz, state, loadedRadius, deck, false);
    }

    /** As above; with {@code sliced} the place goes up a slice per tick ({@link Construction}, D-148) and the outcome is "building". */
    static Outcome serve(ServerLevel level, List<CorridorPlan> plans, Place place, List<Double> candidates, double px, double pz, PlacedSites state, int loadedRadius, DoubleToIntFunction deck, boolean sliced) {
        if (state.done.contains(place.id()) || BUILDING.containsKey(place.id())) return null;
        String marker = marker(state, place.id());
        if (marker != null) return resume(level, plans, place, state, marker, deck, sliced);
        PlacedSites.Failure earlier = state.failed.get(place.id());
        if (earlier != null && level.getGameTime() - earlier.lastTime() < WaysideShelters.RETRY_TICKS) return null;
        CorridorPlan plan = plans.stream().filter(p -> p.id().equals(place.road())).findFirst().orElse(null);
        if (plan == null) return null;
        int index = NEXT.getOrDefault(place.id(), 0);
        if (index >= candidates.size()) {
            state.done.add(place.id());
            state.setDirty();
            LOGGER.info("[UC] roadside place {}: no candidate spot on {} had a lot that fits; given up", place.id(), place.road());
            return new Outcome(place.id(), "given up", null, "", 0, 0, 0, 0);
        }
        double[] at = plan.pointAt(candidates.get(index));
        if (Math.hypot(at[0] - px, at[1] - pz) > ACTIVATE) return null;
        if (!loaded(level, at[0], at[1], loadedRadius)) return null;   // not yet: nothing is decided until the whole search area is in the world
        long started = System.nanoTime();
        DistrictSites.Ground ground = ground(level, plans);
        int tried = 0;
        for (Site site : sites(ground, plan, place, candidates.get(index), deck)) {
            if (tried++ == TRIES) break;
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(place.variants().get(site.facing()));
            if (district == null || WaysideShelters.builtOver(level, site.origin(), district)) continue;
            DistrictBuilder.Job job;
            try {
                job = DistrictBuilder.begin(level, district, site.origin(), false);
            } catch (IllegalStateException refused) {
                LOGGER.info("[UC] roadside place {}: site {} refused ({})", place.id(), site.origin().toShortString(), refused.getMessage());
                continue;
            }
            state.where.put(place.id(), site.origin().asLong());
            state.done.add(place.id() + BUILDING_MARK + site.facing());
            state.setDirty();
            Construction construction = new Construction(place, plan, plans, site.origin(), site.facing(), site.relief(), district, job, deck, started);
            if (sliced) {
                BUILDING.put(place.id(), construction);
                return new Outcome(place.id(), "building", site.origin(), site.facing(), site.relief(), 0, 0, (System.nanoTime() - started) / 1_000_000L);
            }
            construction.step(level, state, Long.MAX_VALUE);
            return construction.outcome();
        }
        NEXT.put(place.id(), index + 1);
        LOGGER.info("[UC] roadside place {}: no lot fits at candidate {} (s {}): {}; trying the next", place.id(), index, Math.round(candidates.get(index)), explain(ground, plan, place, candidates.get(index), deck, new Reasons(level, plans)));
        return new Outcome(place.id(), "candidate " + index + " has no lot", null, "", 0, 0, 0, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * Levels a lot's natural ground to its standing level before the place is built on it: a hill in the lot is cut away to the sky (a building's own clearing reaches only its
     * roof), a dip is filled with earth (its foundation reaches only a few blocks down). What people built and water are left as they are. Returns the columns changed.
     */
    static int levelLot(ServerLevel level, DistrictCatalog.District district, BlockPos origin) {
        int changed = 0, ground = origin.getY() - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = origin.getX() + district.min().getX(); x <= origin.getX() + district.max().getX(); x++)
            for (int z = origin.getZ() + district.min().getZ(); z <= origin.getZ() + district.max().getZ(); z++)
                if (RoadApproaches.column(level, x, z, ground, null, p) > 0) changed++;
        return changed;
    }

    /** How wide the band of graded ground round a built lot is. */
    static final int SKIRT = 8;

    /**
     * Blends the land round a built lot into it, so that it neither stands on a plinth nor sits in a pit: in a band {@link #SKIRT} blocks wide round the lot, each natural column is
     * cut or filled toward the lot's ground, fully at the lot's edge and not at all at the band's outer edge, and grassed where its top is new. What people built, water and the
     * road (within its reach) are left alone, as the ramps leave them ({@link RoadApproaches#column}). Returns how many columns changed.
     */
    static int skirt(ServerLevel level, List<CorridorPlan> plans, DistrictCatalog.District district, BlockPos origin) {
        int x0 = origin.getX() + district.min().getX(), x1 = origin.getX() + district.max().getX();
        int z0 = origin.getZ() + district.min().getZ(), z1 = origin.getZ() + district.max().getZ();
        int ground = origin.getY() - 1, changed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int x = x0 - SKIRT; x <= x1 + SKIRT; x++) for (int z = z0 - SKIRT; z <= z1 + SKIRT; z++) if (skirtColumn(level, plans, x, z, x0, x1, z0, z1, ground, grass, p)) changed++;
        return changed;
    }

    /** One column of {@link #skirt}: whether it changed. */
    static boolean skirtColumn(ServerLevel level, List<CorridorPlan> plans, int x, int z, int x0, int x1, int z0, int z1, int ground, BlockState grass, BlockPos.MutableBlockPos p) {
        int dx = x < x0 ? x0 - x : x > x1 ? x - x1 : 0, dz = z < z0 ? z0 - z : z > z1 ? z - z1 : 0;
        int d = Math.max(dx, dz);
        if (d == 0 || d > SKIRT || level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) return false;
        for (CorridorPlan plan : plans) if (plan.nearest(x + 0.5, z + 0.5) != null) return false;
        int land = RoadApproaches.top(level, x, z, ground, p);
        if (land == Integer.MIN_VALUE) return false;
        int target = (int) Math.round(ground + (land - ground) * (d / (SKIRT + 1.0)));
        return target != land && RoadApproaches.column(level, x, z, target, grass, p) > 0;
    }

    /**
     * A driveway from the middle of the lot's front to the road's deck: three blocks wide, natural ground cut and filled to a straight slope (what is built, and the deck itself,
     * are left as they are), surfaced with the place's own surface. Returns its length and its steepest step between neighbouring columns of its middle line.
     */
    static int[] driveway(ServerLevel level, CorridorPlan plan, Place place, String facing, BlockPos origin, DoubleToIntFunction deck) {
        int[] size = place.size(facing);
        double fx, fz;
        switch (facing) {
            case "north" -> { fx = origin.getX() + size[0] / 2.0; fz = origin.getZ(); }
            case "south" -> { fx = origin.getX() + size[0] / 2.0; fz = origin.getZ() + size[1]; }
            case "east" -> { fx = origin.getX() + size[0]; fz = origin.getZ() + size[1] / 2.0; }
            default -> { fx = origin.getX(); fz = origin.getZ() + size[1] / 2.0; }
        }
        CorridorPlan.Hit hit = plan.project(fx, fz);
        if (hit == null) return new int[]{0, 0};
        double[] road = plan.pointAt(hit.s());
        double dx = road[0] - fx, dz = road[1] - fz, distance = Math.hypot(dx, dz);
        if (distance < 1e-6) return new int[]{0, 0};
        double length = distance - plan.deckHalfWidth() + 0.5;
        if (length <= 0.5) return new int[]{0, 0};
        CorridorPlan.End ray = new CorridorPlan.End(fx, fz, 0, dx / distance, dz / distance);
        int lotTop = origin.getY() - 1, deckTop = deck.applyAsInt(hit.s());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        Map<Integer, Integer> middle = new HashMap<>();
        for (RoadApproaches.Cell cell : RoadApproaches.band(ray, 0, length, 1.5)) {
            int target = lotTop + (int) Math.round((deckTop - lotTop) * Math.min(1.0, cell.k() / length));
            RoadApproaches.column(level, cell.x(), cell.z(), target, Math.abs(cell.u()) <= 1.5 ? place.surface() : null, p);
            if (Math.abs(cell.u()) <= 0.5) middle.put((int) Math.floor(cell.k()), target);
        }
        int steepest = 0;
        Integer previous = lotTop;
        for (int k = 0; k <= (int) length; k++) {
            Integer h = middle.get(k);
            if (h == null) continue;
            steepest = Math.max(steepest, Math.abs(h - previous));
            previous = h;
        }
        return new int[]{(int) Math.ceil(length), steepest};
    }

    // ------------------------------------------------------------------------------------------ building a slice at a time (D-148)

    /** The longest a roadside place's construction may take in one tick. Harrow Farm, the largest, took 448 ms in a single tick before (the October 23 road check). */
    static final long SLICE_NANOS = 15_000_000L;
    /** Places going up now, by place id. In memory only: the world's marker ({@link #BUILDING_MARK}) lets a restart resume one. */
    private static final Map<String, Construction> BUILDING = new LinkedHashMap<>();
    /** The marker, in the world's roadside record, of a place under way: the place id, this, and its facing. */
    static final String BUILDING_MARK = "#building:";

    private static String marker(PlacedSites state, String place) {
        for (String done : state.done) if (done.startsWith(place + BUILDING_MARK)) return done;
        return null;
    }

    /** A place that was under way when the server last stopped short of finishing it: begun again on the same lot (its blocks are placed again; nothing is placed twice). */
    private static Outcome resume(ServerLevel level, List<CorridorPlan> plans, Place place, PlacedSites state, String marker, DoubleToIntFunction deck, boolean sliced) {
        String facing = marker.substring(marker.indexOf(BUILDING_MARK) + BUILDING_MARK.length());
        Long where = state.where.get(place.id());
        CorridorPlan plan = plans.stream().filter(p -> p.id().equals(place.road())).findFirst().orElse(null);
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(place.variants().getOrDefault(facing, ""));
        if (where == null || plan == null || district == null) { state.done.remove(marker); state.where.remove(place.id()); state.setDirty(); return null; }
        BlockPos origin = BlockPos.of(where);
        if (!level.hasChunksAt(origin.offset(district.min()), origin.offset(district.max()))) return null;   // not yet: its lot is not all in the world
        if (DistrictBuilder.isBuilt(level, district.id())) {
            state.done.remove(marker); state.done.add(place.id()); state.setDirty();
            return new Outcome(place.id(), "built", origin, facing, 0, 0, 0, 0);
        }
        DistrictBuilder.Job job;
        try {
            job = DistrictBuilder.begin(level, district, origin, true);
        } catch (IllegalStateException refused) {
            LOGGER.warn("[UC] roadside place {}: the construction cut off at {} cannot be resumed ({}); it will be looked for again", place.id(), origin.toShortString(), refused.getMessage());
            state.done.remove(marker); state.where.remove(place.id()); state.fail(place.id(), level.getGameTime(), refused.getMessage()); state.setDirty();
            return new Outcome(place.id(), "failed", origin, facing, 0, 0, 0, 0);
        }
        LOGGER.info("[UC] roadside place {}: resuming its construction at {} (cut off when the server stopped)", place.id(), origin.toShortString());
        Construction construction = new Construction(place, plan, plans, origin, facing, 0, district, job, deck, System.nanoTime());
        if (sliced) { BUILDING.put(place.id(), construction); return new Outcome(place.id(), "building", origin, facing, 0, 0, 0, 0); }
        construction.step(level, state, Long.MAX_VALUE);
        return construction.outcome();
    }

    /**
     * A roadside place going up (D-148): its lot levelled, the place built, its people placed, the ground round it graded and the driveway laid, in that order, a slice per tick of at
     * most {@link #SLICE_NANOS}, so the first traveller to come near feels no hitch. Nothing here loads or generates a chunk: a slice waits while the lot is not all in the world.
     */
    static final class Construction {
        final Place place;
        final CorridorPlan plan;
        final List<CorridorPlan> plans;
        final BlockPos origin;
        final String facing;
        final int relief;
        final DistrictCatalog.District district;
        final DistrictBuilder.Job job;
        final DoubleToIntFunction deck;
        private final int x0, x1, z0, z1, ground;
        private final long begunAt;
        private int phase, column, levelled, graded, slices;
        private int[] drive = {0, 0};
        private long spentNanos, worstNanos;
        private String status = "building";

        Construction(Place place, CorridorPlan plan, List<CorridorPlan> plans, BlockPos origin, String facing, int relief, DistrictCatalog.District district, DistrictBuilder.Job job,
                     DoubleToIntFunction deck, long begunAt) {
            this.place = place; this.plan = plan; this.plans = plans; this.origin = origin; this.facing = facing; this.relief = relief; this.district = district; this.job = job;
            this.deck = deck; this.begunAt = begunAt;
            x0 = origin.getX() + district.min().getX(); x1 = origin.getX() + district.max().getX();
            z0 = origin.getZ() + district.min().getZ(); z1 = origin.getZ() + district.max().getZ();
            ground = origin.getY() - 1;
        }

        boolean finished() { return phase >= 5; }
        String status() { return status; }
        int slices() { return slices; }
        long worstMillis() { return worstNanos / 1_000_000L; }

        Outcome outcome() {
            return new Outcome(place.id(), status, origin, facing, relief, drive[0], drive[1], (System.nanoTime() - begunAt) / 1_000_000L);
        }

        /** One slice of the work, within {@code budget} nanoseconds (Long.MAX_VALUE: all of it). Returns true when the construction is over, built or failed. */
        boolean step(ServerLevel level, PlacedSites state, long budget) {
            if (phase >= 5) return true;
            if (!job.loaded()) return false;   // the lot's chunks went out of the world: wait for them rather than load them
            long t0 = System.nanoTime(), until = budget == Long.MAX_VALUE ? Long.MAX_VALUE : t0 + budget;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
            try {
                while (phase < 5 && System.nanoTime() < until) {
                    switch (phase) {
                        case 0 -> {   // the lot's natural ground levelled to its standing level
                            int depth = z1 - z0 + 1, columns = (x1 - x0 + 1) * depth;
                            for (int n = 0; n < 8 && column < columns; n++, column++) if (RoadApproaches.column(level, x0 + column / depth, z0 + column % depth, ground, null, p) > 0) levelled++;
                            if (column >= columns) { phase = 1; column = 0; }
                        }
                        case 1 -> { if (job.step(budget == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1, until - System.nanoTime()))) phase = 2; }
                        case 2 -> { job.finish(); phase = 3; }
                        case 3 -> {   // the band round the lot graded into it
                            int depth = z1 - z0 + 1 + 2 * SKIRT, columns = (x1 - x0 + 1 + 2 * SKIRT) * depth;
                            for (int n = 0; n < 16 && column < columns; n++, column++)
                                if (skirtColumn(level, plans, x0 - SKIRT + column / depth, z0 - SKIRT + column % depth, x0, x1, z0, z1, ground, grass, p)) graded++;
                            if (column >= columns) { phase = 4; column = 0; }
                        }
                        case 4 -> { drive = driveway(level, plan, place, facing, origin, deck); phase = 5; }
                        default -> { }
                    }
                }
            } catch (RuntimeException error) {
                job.abort();
                phase = 5;
                status = "failed";
                LOGGER.error("[UC] roadside place {}: building at {} failed", place.id(), origin.toShortString(), error);
                state.done.removeIf(d -> d.startsWith(place.id() + BUILDING_MARK));
                state.where.remove(place.id());
                state.fail(place.id(), level.getGameTime(), error.toString());
                PlacedSites.Failure f = state.failed.get(place.id());
                if (f.attempts() >= WaysideShelters.MAX_ATTEMPTS) state.done.add(place.id());
                state.setDirty();
                return true;
            } finally {
                long took = System.nanoTime() - t0;
                spentNanos += took;
                worstNanos = Math.max(worstNanos, took);
                slices++;
            }
            if (phase < 5) return false;
            status = "built";
            state.failed.remove(place.id());
            state.done.removeIf(d -> d.startsWith(place.id() + BUILDING_MARK));
            state.done.add(place.id());
            state.setDirty();
            LOGGER.info("[UC] roadside place {} built at {} facing {} (relief {}, {} columns of its lot levelled, {} graded round it, driveway {} blocks, steepest step {}) in {} slice(s) over {} ms; {} ms of work, the longest slice {} ms",
                place.id(), origin.toShortString(), facing, relief, levelled, graded, drive[0], drive[1], slices, (System.nanoTime() - begunAt) / 1_000_000L, spentNanos / 1_000_000L, worstNanos / 1_000_000L);
            return true;
        }
    }

    /** Works the places going up, within one slice's budget in all. */
    private static void construct(ServerLevel level) {
        PlacedSites state = PlacedSites.get(level, KEY);
        long until = System.nanoTime() + SLICE_NANOS;
        for (var it = BUILDING.values().iterator(); it.hasNext(); ) {
            long left = until - System.nanoTime();
            if (left <= 0) break;
            if (it.next().step(level, state, left)) it.remove();
        }
    }

    /** A server that stops finishes what is going up (its lots are in the world) rather than leave it half built; what cannot be finished is resumed at the next start. */
    @SubscribeEvent
    public static void stopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        if (BUILDING.isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        PlacedSites state = PlacedSites.get(level, KEY);
        for (Construction c : BUILDING.values()) {
            try { c.step(level, state, Long.MAX_VALUE); }
            catch (RuntimeException error) { LOGGER.warn("[UC] roadside place {}: could not be finished as the server stopped ({}); it resumes at the next start", c.place.id(), error.toString()); }
        }
        BUILDING.clear();
    }

    // ------------------------------------------------------------------------------------------ events
    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!BUILDING.isEmpty()) construct(level);
        if (level.getGameTime() % CHECK_TICKS != 17 || level.players().isEmpty() || !enabled(level)) return;
        List<CorridorPlan> plans = CorridorRoadFeature.ready(level);   // the built plans: bridges, water and deck heights are known
        if (plans.isEmpty()) return;
        PlacedSites state = PlacedSites.get(level, KEY);
        for (CorridorPlan plan : plans) {
            List<Place> places = CATALOG.onRoad(plan.id());
            if (places.isEmpty()) continue;
            Map<String, List<Double>> candidates = plan(plan, places);
            for (Place place : places) {
                for (ServerPlayer player : level.players()) {
                    CorridorPlan road = plans.stream().filter(p -> p.id().equals(place.road())).findFirst().orElse(null);
                    if (road != null && serve(level, plans, place, candidates.get(place.id()), player.getX(), player.getZ(), state, SEARCH + 32, deck(road), true) != null) break;
                }
                keepPeople(level, place, state);
            }
        }
    }

    /** Puts back a place's people if a player is near and one is lost (they are invulnerable; this is for an operator's kill or a chunk accident). */
    private static void keepPeople(ServerLevel level, Place place, PlacedSites state) {
        Long where = state.where.get(place.id());
        if (where == null) return;
        BlockPos origin = BlockPos.of(where);
        DistrictCatalog.District district = builtVariant(level, place);
        if (district == null) return;
        BlockPos middle = origin.offset((district.min().getX() + district.max().getX()) / 2, 0, (district.min().getZ() + district.max().getZ()) / 2);
        boolean near = level.players().stream().anyMatch(p -> p.distanceToSqr(middle.getX(), middle.getY(), middle.getZ()) < KEEPER_RANGE * KEEPER_RANGE);
        if (!near || !loaded(level, middle.getX(), middle.getZ(), 40)) return;
        // every person's own spot must have its entities in the world, or one who is there but not yet loaded would be counted as lost and doubled
        for (DistrictCatalog.Op op : district.npcOps()) if (!level.isPositionEntityTicking(origin.offset(op.a()))) return;
        try {
            int back = DistrictBuilder.repopulateAt(level, district, origin);
            if (back > 0) LOGGER.warn("[UC] roadside place {}: {} of its people were missing and have been put back", place.id(), back);
        } catch (RuntimeException error) {
            LOGGER.info("[UC] roadside place {}: its people could not be checked now ({})", place.id(), error.getMessage());
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_roadside").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("list").executes(ctx -> {
            ServerLevel level = ctx.getSource().getServer().overworld();
            PlacedSites state = PlacedSites.get(level, KEY);
            List<CorridorPlan> plans = CorridorRoadFeature.ready(level);   // the built plans: bridges, water and deck heights are known
            int lines = 0;
            ctx.getSource().sendSuccess(() -> Component.literal(enabled(level) ? "Roadside places are enabled in this world." : "Roadside places are NOT enabled in this world (/uc_roadside enable)."), false);
            for (CorridorPlan plan : plans) {
                Map<String, List<Double>> candidates = plan(plan, CATALOG.onRoad(plan.id()));
                for (var entry : candidates.entrySet()) {
                    Long where = state.where.get(entry.getKey());
                    String spot = entry.getValue().isEmpty() ? "no land on this road fits it" : "first spot about " + Math.round(plan.pointAt(entry.getValue().get(0))[0]) + ", " + Math.round(plan.pointAt(entry.getValue().get(0))[1]);
                    String line = entry.getKey() + " (" + plan.id() + "): " + (where != null ? "built at " + BlockPos.of(where).toShortString()
                        : state.done.contains(entry.getKey()) ? "given up" : "waiting for a player; " + spot);
                    ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    lines++;
                }
            }
            return lines;
        }));
        // An operator's explicit choice to give an existing world the places (they are built where nothing was built, never over a player's work).
        root.then(Commands.literal("enable").then(Commands.literal("confirm").executes(ctx -> {
            enable(ctx.getSource().getServer().overworld());
            ctx.getSource().sendSuccess(() -> Component.literal("Roadside places will be built in this world as players come near their spots."), true);
            return 1;
        })));
        root.then(Commands.literal("retry").then(Commands.argument("place", StringArgumentType.word())
            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CATALOG.all().stream().map(Place::id), builder))
            .executes(ctx -> {
                String id = StringArgumentType.getString(ctx, "place");
                PlacedSites state = PlacedSites.get(ctx.getSource().getServer().overworld(), KEY);
                if (state.where.containsKey(id)) { ctx.getSource().sendFailure(Component.literal(id + " stands; remove it with /uc_district undo first.")); return 0; }
                state.done.remove(id); state.failed.remove(id); NEXT.remove(id); state.setDirty();
                ctx.getSource().sendSuccess(() -> Component.literal(id + " will be looked for again when a player is near."), true);
                return 1;
            })));
        event.getDispatcher().register(root);
    }

    /** Test seam. */
    static void resetForTest() { NEXT.clear(); BUILDING.clear(); }

    /** Test seam: the construction of a place going up, or null. */
    static Construction building(String place) { return BUILDING.get(place); }
}
