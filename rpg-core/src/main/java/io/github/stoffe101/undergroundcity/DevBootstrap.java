package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * The deliberate arrival (D-107): in a brand-new world, builds Founders Row on level, dry ground near the spawn point and moves the spawn to the lane's
 * gate, so that a new player arrives at the district, gets the first-login screen and can walk to the registrar. It runs for
 * <ul>
 *   <li>the <b>archipelago</b> layout, always (the layout names its landing, a flat plateau on Ironhaven's island: the world spawn is set there before the game prepares the spawn
 *       area, so no chunk of a city is generated for nothing, and Founders Row, the Row Flats, the Row Motel and the firing range are built around it);</li>
 *   <li>the legacy layout only for development worlds ({@code -Duc.devBootstrap=true}, set by the Gradle development launch), near the vanilla spawn point as before.</li>
 * </ul>
 * It never touches an existing world (game time above zero) and the build is the same reversible one an operator would run ({@code /uc_district undo founders_row confirm}).
 */
public final class DevBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DISTRICT = "founders_row", RANGE = "firing_range", FLATS = "row_flats", MOTEL = "row_motel", QUARRY = "old_quarry", PIT = "valewood_pit";
    /** The Valewood pit stands this far back along the road from where the road enters Valewood's circle, this far to one side of it, and no nearer than this to any road. */
    static final int PIT_BACK = 80, PIT_ASIDE = 45, PIT_SEARCH = 40, PIT_ROAD_CLEARANCE = 16;
    static final int SEARCH_RADIUS = 160, MAX_RELIEF = 3;
    /** The landing is graded (LandingGrader), so a lot may be this uneven before grading and still be the least work. */
    static final int LANDING_RELIEF = 24, LANDING_SEARCH_RADIUS = 200;
    /** The landing is searched on a grid of this many blocks (the ground is graded afterwards, so a coarse look is enough and costs a quarter of the noise columns of the default). */
    static final int LANDING_SAMPLE = 8;

    /** A new archipelago world starts at its landing, not at the vanilla spawn point (which would be inside a city). Runs once, when the world is created. */
    @SubscribeEvent
    public void createSpawn(LevelEvent.CreateSpawnPosition event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        var anchor = WorldLayout.INSTANCE.arrivalAnchor();
        if (anchor.isEmpty()) return;
        var generator = level.getChunkSource().getGenerator();
        int x = anchor.get().getX(), z = anchor.get().getZ();
        int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
        event.getSettings().setSpawn(new BlockPos(x, y, z), 180.0f);
        event.setCanceled(true);
        LOGGER.info("[UC-WORLD] new archipelago world: spawn set to the landing at {} {} {}", x, y, z);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("uc.devBootstrap") && WorldLayout.INSTANCE.arrivalAnchor().isEmpty()) return;
        ServerLevel level = event.getServer().overworld();
        if (level.getGameTime() > 100) { LOGGER.info("[UC-WORLD] existing world: Founders Row is not placed automatically (use /uc_district build founders_row)"); return; }
        DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(DISTRICT);
        if (district == null || DistrictBuilder.isBuilt(level, DISTRICT)) return;
        try {
            place(level, district);
        } catch (RuntimeException error) {
            LOGGER.error("[UC-DEV] could not place Founders Row automatically: {}", error.toString());
        }
    }

    /** The terrain as the chunk generator will make it, so no chunk is generated to look for a site. */
    static DistrictSites.Ground ground(ServerLevel level) {
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        return new DistrictSites.Ground() {
            // A column of the generator's noise costs milliseconds on the archipelago's density functions, so each column is evaluated once for the floor and once for the surface (a lot's
            // wetness and its height come from the same two answers; asking for them separately was four evaluations per column).
            private final java.util.Map<Long, int[]> columns = new java.util.HashMap<>();
            private int[] column(int x, int z) {
                return columns.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), k -> new int[]{
                    generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random), generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random)});
            }
            @Override public int height(int x, int z) { return column(x, z)[0]; }
            @Override public boolean wet(int x, int z) {
                int[] c = column(x, z);
                return c[1] > c[0] || c[0] <= level.getSeaLevel() + 1;
            }
            @Override public boolean reserved(int x, int z) {
                // Inside a city circle Lost Cities owns the ground; keep well clear of the circle and of the roads' region of influence.
                return CityRegions.INSTANCE.all().stream().anyMatch(city -> city.dimension().equals(level.dimension().location().toString())
                    && Math.hypot(x - city.centerX(), z - city.centerZ()) < city.cityRadius() + 48);
            }
        };
    }

    /**
     * The firing range goes west of the Row, its firing line meeting the gunsmith's back door (the Row's west building has it at local z 11, and the
     * range's path at its own local z 7). It is skipped, with a log line, where the ground there is not level enough for a lawn: the Row works without it.
     */
    static void placeRange(ServerLevel level, BlockPos rowOrigin, boolean graded) {
        DistrictCatalog.District range = DistrictCatalog.INSTANCE.find(RANGE);
        if (range == null) return;
        int width = range.max().getX() - range.min().getX() + 1;
        placeBeside(level, range, rowOrigin.offset(-width, 0, 4), "firing range", "west", graded);
    }

    /** The Row Flats go east of the Row: the first homes a player or a Crew can lease. Skipped, with a log line, on ground that is not level enough. */
    static void placeFlats(ServerLevel level, BlockPos rowOrigin, boolean graded) {
        DistrictCatalog.District flats = DistrictCatalog.INSTANCE.find(FLATS);
        DistrictCatalog.District row = DistrictCatalog.INSTANCE.find(DISTRICT);
        if (flats == null || row == null) return;
        int rowWidth = row.max().getX() - row.min().getX() + 1;
        placeBeside(level, flats, rowOrigin.offset(rowWidth + 3, 0, 4), "Row Flats", "east", graded);
        // The Row Motel goes beside the flats: the cheapest rooms, where a new citizen with no credits at all can sleep (the first hours are the city's).
        DistrictCatalog.District motel = DistrictCatalog.INSTANCE.find(MOTEL);
        if (motel != null) {
            int flatsWidth = flats.max().getX() - flats.min().getX() + 1;
            placeBeside(level, motel, rowOrigin.offset(rowWidth + 3 + flatsWidth + 3, 0, 4), "Row Motel", "east", graded);
        }
    }

    /**
     * The old quarry (D-123, the one regenerating resource site) goes on natural ground east of the landing, beyond the graded footprint: a level, dry lot looked for 90 blocks past the motel, within 60 blocks of that
     * point and never overlapping the footprint. Skipped, with a log line, where nothing fits: the world works without it (the cities' shelves still sell the same metals).
     */
    static void placeQuarry(ServerLevel level, BlockPos rowOrigin, Footprint footprint) {
        DistrictCatalog.District quarry = DistrictCatalog.INSTANCE.find(QUARRY);
        if (quarry == null || DistrictBuilder.isBuilt(level, QUARRY)) return;
        int width = quarry.max().getX() - quarry.min().getX() + 1, depth = quarry.max().getZ() - quarry.min().getZ() + 1;
        try {
            var found = DistrictSites.find(ground(level), rowOrigin.getX() + footprint.right() + 90, rowOrigin.getZ(), width, depth, MAX_RELIEF, 60);
            if (found.isEmpty()) { LOGGER.info("[UC-DEV] the old quarry was not placed: no level, dry ground within 60 blocks of {} {}", rowOrigin.getX() + footprint.right() + 90, rowOrigin.getZ()); return; }
            var site = found.get();
            int west = rowOrigin.getX() - footprint.left(), east = rowOrigin.getX() + footprint.right();
            boolean overlapsRow = site.x() <= east + 8 && site.x() + width >= west - 8 && site.z() <= rowOrigin.getZ() + footprint.depth() + 8 && site.z() + depth >= rowOrigin.getZ() - 8;
            if (overlapsRow) { LOGGER.info("[UC-DEV] the old quarry was not placed: the only lot found overlaps the landing"); return; }
            BlockPos origin = new BlockPos(site.x(), site.y(), site.z());
            for (int cx = (origin.getX() + quarry.min().getX()) >> 4; cx <= (origin.getX() + quarry.max().getX()) >> 4; cx++) {
                for (int cz = (origin.getZ() + quarry.min().getZ()) >> 4; cz <= (origin.getZ() + quarry.max().getZ()) >> 4; cz++) level.getChunk(cx, cz);
            }
            DistrictBuilder.build(level, quarry, origin);
            LOGGER.info("[UC-DEV] old quarry built at {} (relief {}, {} blocks from the search point)", origin.toShortString(), site.relief(), Math.round(site.distance()));
        } catch (RuntimeException error) {
            LOGGER.warn("[UC-DEV] the old quarry could not be placed: {}", error.toString());
        }
    }

    /**
     * The Valewood pit (D-134): a second, smaller regrowing site on Valewood's island, so the base metals also renew on the island with the fewest metal shelves. It goes beside the road to
     * Ironhaven, {@link #PIT_BACK} blocks back from where the road enters Valewood's circle and {@link #PIT_ASIDE} to one side, on level, dry ground that is neither a city's nor within
     * {@link #PIT_ROAD_CLEARANCE} blocks of any road. In a new archipelago world only; skipped with a log line where nothing fits, and the world works without it.
     */
    static void placeValewoodPit(ServerLevel level) {
        DistrictCatalog.District pit = DistrictCatalog.INSTANCE.find(PIT);
        if (pit == null || DistrictBuilder.isBuilt(level, PIT)) return;
        try {
            java.util.List<CorridorPlan> plans = DevCommands.plans(level.getServer());
            CorridorPlan road = plans.stream().filter(p -> p.id().equals("ironhaven_valewood") || p.id().equals("valewood_ironhaven")).findFirst().orElse(null);
            if (road == null) { LOGGER.info("[UC-DEV] the Valewood pit was not placed: no road between Ironhaven and Valewood in this world"); return; }
            // where the road meets Valewood's circle: its last open point on Valewood's side (the id names the end it runs to)
            boolean valewoodLast = road.id().endsWith("valewood");
            double edge = -1;
            for (double at = 0; at <= road.length(); at += 2) if (road.openAt(at) && (valewoodLast || edge < 0)) edge = at;
            if (edge < 0) { LOGGER.info("[UC-DEV] the Valewood pit was not placed: the road has no open stretch"); return; }
            double back = valewoodLast ? edge - PIT_BACK : edge + PIT_BACK;
            double[] point = road.pointAt(back), ahead = road.pointAt(back + 4), behind = road.pointAt(back - 4);
            double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], len = Math.max(1e-6, Math.hypot(tx, tz));
            double nx = -tz / len, nz = tx / len;   // across the road
            int width = pit.max().getX() - pit.min().getX() + 1, depth = pit.max().getZ() - pit.min().getZ() + 1;
            DistrictSites.Ground natural = ground(level);
            DistrictSites.Ground offRoad = new DistrictSites.Ground() {
                @Override public int height(int x, int z) { return natural.height(x, z); }
                @Override public boolean wet(int x, int z) { return natural.wet(x, z); }
                @Override public boolean reserved(int x, int z) {
                    if (natural.reserved(x, z)) return true;
                    for (CorridorPlan plan : plans) if (plan.project(x, z).distance() < PIT_ROAD_CLEARANCE) return true;
                    return false;
                }
            };
            DistrictSites.Site found = null;
            for (int side : new int[] {1, -1}) {
                int cx = (int) Math.round(point[0] + nx * PIT_ASIDE * side) - width / 2, cz = (int) Math.round(point[1] + nz * PIT_ASIDE * side) - depth / 2;
                found = DistrictSites.find(offRoad, cx, cz, width, depth, MAX_RELIEF, PIT_SEARCH).orElse(null);
                if (found != null) break;
            }
            if (found == null) { LOGGER.info("[UC-DEV] the Valewood pit was not placed: no level, dry ground off the road within {} blocks of {} {}", PIT_SEARCH, Math.round(point[0]), Math.round(point[1])); return; }
            BlockPos origin = new BlockPos(found.x(), found.y(), found.z());
            for (int cx = (origin.getX() + pit.min().getX()) >> 4; cx <= (origin.getX() + pit.max().getX()) >> 4; cx++) {
                for (int cz = (origin.getZ() + pit.min().getZ()) >> 4; cz <= (origin.getZ() + pit.max().getZ()) >> 4; cz++) level.getChunk(cx, cz);
            }
            DistrictBuilder.build(level, pit, origin);
            LOGGER.info("[UC-DEV] Valewood pit built at {} (relief {}, {} blocks along the road from Valewood's edge, {} blocks from the search point)", origin.toShortString(), found.relief(), PIT_BACK,
                Math.round(found.distance()));
        } catch (RuntimeException error) {
            LOGGER.warn("[UC-DEV] the Valewood pit could not be placed: {}", error.toString());
        }
    }

    private static void placeBeside(ServerLevel level, DistrictCatalog.District district, BlockPos origin, String what, String side, boolean graded) {
        if (DistrictBuilder.isBuilt(level, district.id())) return;
        int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
        var ground = ground(level);
        // The levelled landing is no longer what the generator would make, so its columns are not asked.
        for (int x = 0; x < width && !graded; x += 8) for (int z = 0; z < depth; z += 7) {
            int wx = origin.getX() + x, wz = origin.getZ() + z;
            if (ground.wet(wx, wz) || Math.abs(ground.height(wx, wz) - origin.getY()) > MAX_RELIEF + 2) {
                LOGGER.info("[UC-DEV] the {} was not placed: the ground {} of the Row is not level and dry enough at {} {}", what, side, wx, wz);
                return;
            }
        }
        try {
            for (int cx = (origin.getX() + district.min().getX()) >> 4; cx <= (origin.getX() + district.max().getX()) >> 4; cx++) {
                for (int cz = (origin.getZ() + district.min().getZ()) >> 4; cz <= (origin.getZ() + district.max().getZ()) >> 4; cz++) level.getChunk(cx, cz);
            }
            DistrictBuilder.build(level, district, origin);
            LOGGER.info("[UC-DEV] {} built at {}", what, origin.toShortString());
        } catch (RuntimeException error) {
            LOGGER.warn("[UC-DEV] the {} could not be placed: {}", what, error.toString());
        }
    }

    /** What the landing needs level: the range west of the Row, the Row, the flats and the motel east of it, and the lane's gate south of it. */
    record Footprint(int left, int right, int width, int depth) {}

    static Footprint footprint(int rowWidth, int rowDepth) {
        int left = 0, right = rowWidth;
        DistrictCatalog.District range = DistrictCatalog.INSTANCE.find(RANGE), flats = DistrictCatalog.INSTANCE.find(FLATS), motel = DistrictCatalog.INSTANCE.find(MOTEL);
        if (range != null) left = range.max().getX() - range.min().getX() + 1;
        if (flats != null) right += 3 + flats.max().getX() - flats.min().getX() + 1;
        if (motel != null) right += 3 + motel.max().getX() - motel.min().getX() + 1;
        return new Footprint(left, right, left + right, rowDepth + 8);
    }

    static BlockPos place(ServerLevel level, DistrictCatalog.District district) {
        int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
        BlockPos spawn = level.getSharedSpawnPos();
        boolean landing = WorldLayout.INSTANCE.arrivalAnchor().isPresent();
        DistrictSites.Site site;
        if (landing) {
            // The archipelago's landing is rolling plains: the whole footprint (range, Row, flats, motel and the lane's gate) is levelled once, on the flattest ground near the anchor.
            Footprint footprint = footprint(width, depth);
            long searchStart = System.nanoTime();
            // the ground is going to be graded, so the nearest dry site that is not too steep wins (relief weight zero): the search ends at the first lot instead of reading the whole radius
            site = DistrictSites.find(ground(level), spawn.getX(), spawn.getZ(), footprint.width(), footprint.depth(), LANDING_RELIEF, LANDING_SEARCH_RADIUS, 0.0, LANDING_SAMPLE)
                .orElseThrow(() -> new IllegalStateException("no dry ground within " + LANDING_SEARCH_RADIUS + " blocks of the landing"));
            LOGGER.info("[UC-DEV] landing site found at {} {} (relief {}, {} blocks from the anchor) in {} ms", site.x(), site.z(), site.relief(), Math.round(site.distance()), (System.nanoTime() - searchStart) / 1_000_000L);
            // find() returns the north-west corner of the footprint; the Row's own origin is the range's width further east.
            LandingGrader.Rect lot = new LandingGrader.Rect(site.x(), site.z(), site.x() + footprint.width() - 1, site.z() + footprint.depth() - 1);
            LandingGrader.Result graded = LandingGrader.grade(level, lot, site.y());
            LOGGER.info("[UC-DEV] landing levelled at y {}: {} columns, {} blocks changed, up to {} cut and {} filled, {} water columns left alone, {} ms (natural relief {})",
                site.y(), graded.columns(), graded.changed(), graded.maxCut(), graded.maxFill(), graded.skippedWet(), graded.millis(), site.relief());
            site = new DistrictSites.Site(site.x() + footprint.left(), site.z(), site.y(), site.relief(), site.distance());
        } else {
            site = DistrictSites.find(ground(level), spawn.getX(), spawn.getZ(), width, depth, MAX_RELIEF, SEARCH_RADIUS)
                .orElseThrow(() -> new IllegalStateException("no level, dry ground within " + SEARCH_RADIUS + " blocks of the spawn point"));
        }
        BlockPos origin = new BlockPos(site.x(), site.y(), site.z());
        // The lot's chunks must exist before the builder looks at them; loading them here is the one-time cost of the first start.
        for (int cx = (origin.getX() + district.min().getX()) >> 4; cx <= (origin.getX() + district.max().getX()) >> 4; cx++) {
            for (int cz = (origin.getZ() + district.min().getZ()) >> 4; cz <= (origin.getZ() + district.max().getZ()) >> 4; cz++) level.getChunk(cx, cz);
        }
        DistrictBuilder.build(level, district, origin);
        placeRange(level, origin, landing);
        placeFlats(level, origin, landing);
        if (landing) {
            placeQuarry(level, origin, footprint(width, depth));
            placeValewoodPit(level);
        }
        // The gate is the middle of the lane's far (south) end; stand a few blocks outside it, facing the lane.
        BlockPos gate = origin.offset(district.min().getX() + width / 2, 0, depth + 2);
        level.setDefaultSpawnPos(gate, 180.0f);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS).set(0, level.getServer()); // arrive exactly at the gate, not up to ten blocks off
        LOGGER.info("[UC-DEV] Founders Row built at {} (relief {}), spawn moved to the gate at {}", origin.toShortString(), site.relief(), gate.toShortString());
        return origin;
    }
}
