package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A walkable way from each road's end into its city (D-137). A road stops where it meets a city's circle, and the ground between there and the city's first street is whatever the
 * terrain left: a scripted journey found the Valewood end of the Ironhaven road facing a two-block bank of earth, and the road check measured steps of two to eight blocks at four of the
 * six ends of a generated archipelago (a ditch, a bank, a pit). Nothing a person can walk.
 *
 * <p>Once per end, once somebody is within {@link #NEAR} blocks of it and every chunk it reads has been generated, the line the road was coming along is read on for up to {@link #APPROACH} blocks into the city: the deck's
 * height at the end, and the ground along the centre until the first thing the city built (a street, a wall, a building's lot). A straight ramp is laid from the deck to that height (over
 * up to sixty-four blocks, so a difference of eight blocks is at least one in five): natural ground (earth, stone, sand, gravel, clay, snow, plants, leaves, logs) above the ramp is cut away, and a dip below it is filled with
 * earth, {@link #HALF} blocks either side of the centre, with a path down the middle. Anything the city built is never touched (nor a building's lot, garden included), and a column that has one in the way is left as it is.
 *
 * <p>The build never makes the server generate a chunk. Journey runs showed the server thread stalled 4 to 43 seconds (the watchdog stops it at 60) in the same second as each ramp, the
 * Valewood ones worst: the ramp reached up to seventy blocks into a city the walking player's own loading had not generated yet, and asked for those chunks on the spot, so Lost Cities built
 * them on the server thread. Now a player within {@link #NEAR} blocks puts a short-lived ticket on the ramp's chunks ({@link #LOADING}), the world generates them on its workers as it does for
 * any player, and the ramp is laid on the first check after they are all loaded ({@link #ready}).
 *
 * <p>Only a world that a new archipelago start enabled ({@link #enable}) gets them; an existing world is never changed. Each end is built at most once and remembered in the world's saved data.
 */
public final class RoadApproaches {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String KEY = "underground_city_road_approaches", ENABLED = "#enabled";
    static final int APPROACH = 64, NEAR = 160, HALF = 3, CUT_HEIGHT = 30, SEARCH_UP = 24, SEARCH_DOWN = 16;
    private static final int FLAGS = Block.UPDATE_CLIENTS;
    /** Keeps an approach's chunks loading while somebody is near (renewed every check, gone ten seconds after the last). */
    static final TicketType<ChunkPos> LOADING = TicketType.create("underground_city_road_approach", Comparator.comparingLong(ChunkPos::toLong), 200);
    private static final BlockState FILL = Blocks.DIRT.defaultBlockState(), PATH = Blocks.DIRT_PATH.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState();

    /** A new archipelago world started by this version carries the approaches; an older one never does. */
    static void enable(ServerLevel level) {
        PlacedSites state = PlacedSites.get(level, KEY);
        if (state.done.add(ENABLED)) state.setDirty();
    }

    static boolean enabled(ServerLevel level) { return PlacedSites.get(level, KEY).done.contains(ENABLED); }

    @SubscribeEvent
    public void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || level.getGameTime() % 40 != 7) return;
        if (level.players().isEmpty() || !enabled(level)) return;
        List<CorridorPlan> plans = CorridorRoadFeature.plans(level);
        if (plans.isEmpty()) return;
        PlacedSites state = PlacedSites.get(level, KEY);
        for (CorridorPlan plan : plans) {
            if (!plan.built()) continue;
            CorridorPlan.End[] ends = plan.ends();
            for (int i = 0; i < ends.length; i++) {
                String key = plan.id() + "#" + i;
                if (state.done.contains(key)) continue;
                CorridorPlan.End end = ends[i];
                if (level.players().stream().noneMatch(p -> Math.hypot(p.getX() - end.x(), p.getZ() - end.z()) < NEAR)) continue;
                if (!ready(level, end)) {
                    for (ChunkPos chunk : chunks(end)) level.getChunkSource().addRegionTicket(LOADING, chunk, 0, chunk);
                    continue;
                }
                try {
                    Result result = build(level, plan, end);
                    state.where.put(key, BlockPos.containing(end.x(), result.deckY(), end.z()).asLong());
                    LOGGER.info("[UC-ROAD] approach into the city from {} at {} {}: {}", key, (int) end.x(), (int) end.z(), result);
                } catch (RuntimeException error) {
                    LOGGER.warn("[UC-ROAD] the approach at {} could not be built: {}", key, error.toString());
                }
                state.done.add(key);
                state.setDirty();
            }
        }
    }

    /** What a build did: the deck's height at the end, where and at what height the city's built ground began, the blocks changed and the columns left alone. */
    record Result(int deckY, int cityAt, int cityLevel, int changed, int leftAlone, int steepest) {}

    /** Reads the approach of one end and lays its ramp. Visible for the road check, which measures an end before and after. */
    static Result build(ServerLevel level, CorridorPlan plan, CorridorPlan.End end) {
        int deckY = CorridorPlan.deckBlockY(plan.deckHalfAt(end.s()));
        double px = -end.dirZ(), pz = end.dirX();
        for (ChunkPos chunk : chunks(end)) level.getChunk(chunk.x, chunk.z);   // already loaded in play (ready); the road check's own server loads them here
        // the ground along the centre, until the first thing the city built
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int cityAt = APPROACH, cityLevel = Integer.MIN_VALUE, last = deckY;
        for (int k = 1; k <= APPROACH; k++) {
            int x = (int) Math.floor(end.x() + end.dirX() * k), z = (int) Math.floor(end.z() + end.dirZ() * k);
            int top = top(level, x, z, deckY, p);
            if (top == Integer.MIN_VALUE) continue;
            last = top;
            if (!natural(level.getBlockState(p.set(x, top, z))) || cityOwned(level, p)) { cityAt = k; cityLevel = top; break; }
        }
        if (cityLevel == Integer.MIN_VALUE) cityLevel = last;
        int changed = 0, leftAlone = 0, steepest = 0, previous = deckY;
        // from the column at the road's very end (it belongs to neither the deck nor the city: the road check found a four-block hole there) to the city's first built ground
        for (int k = 0; k < cityAt; k++) {
            int target = deckY + (int) Math.round((cityLevel - deckY) * (double) k / cityAt);
            steepest = Math.max(steepest, Math.abs(target - previous));
            previous = target;
            for (int u = -HALF; u <= HALF; u++) {
                int x = (int) Math.floor(end.x() + end.dirX() * k + px * u), z = (int) Math.floor(end.z() + end.dirZ() * k + pz * u);
                int n = column(level, x, z, target, u == 0, p);
                if (n < 0) leftAlone++; else changed += n;
            }
        }
        return new Result(deckY, cityAt, cityLevel, changed, leftAlone, steepest);
    }

    /** Every chunk a build reads or changes: the columns one block beyond its sides, from just before the road's end to {@link #APPROACH} blocks in. */
    static Set<ChunkPos> chunks(CorridorPlan.End end) {
        double px = -end.dirZ(), pz = end.dirX();
        Set<ChunkPos> out = new LinkedHashSet<>();
        for (int k = -4; k <= APPROACH; k++) for (int u = -HALF - 1; u <= HALF + 1; u++) {
            out.add(new ChunkPos((int) Math.floor(end.x() + end.dirX() * k + px * u) >> 4, (int) Math.floor(end.z() + end.dirZ() * k + pz * u) >> 4));
        }
        return out;
    }

    /** Whether all of them are generated and loaded now (never waits, never generates). */
    static boolean ready(ServerLevel level, CorridorPlan.End end) {
        for (ChunkPos chunk : chunks(end)) if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return false;
        return true;
    }

    /** The highest block that holds a person in the column, searched around the deck's height (Integer.MIN_VALUE when there is none); {@code p} is left on it. */
    private static int top(ServerLevel level, int x, int z, int deckY, BlockPos.MutableBlockPos p) {
        for (int y = deckY + SEARCH_UP; y >= deckY - SEARCH_DOWN; y--) {
            BlockState state = level.getBlockState(p.set(x, y, z));
            if (!state.getFluidState().isEmpty()) return Integer.MIN_VALUE;
            if (state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) return y;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Makes one column stand at {@code target} (the top block): natural blocks above are cut, a natural dip below is filled with earth, and a path tops the middle. Returns the blocks
     * changed, or -1 when something the city built (or water) is in the way and the column is left as it was.
     */
    private static int column(ServerLevel level, int x, int z, int target, boolean middle, BlockPos.MutableBlockPos p) {
        // a building's lot is the city's, garden and all (the building guard protects the same volume from players)
        if (cityOwned(level, p.set(x, target, z))) return -1;
        // look first, change after: a column with anything built in its cut or under its fill is not touched at all
        int ground = Integer.MIN_VALUE;
        for (int y = target + CUT_HEIGHT; y >= target - SEARCH_DOWN; y--) {
            BlockState state = level.getBlockState(p.set(x, y, z));
            if (!state.getFluidState().isEmpty()) return -1;
            if (state.isAir()) continue;
            if (!natural(state)) return -1;
            if (y <= target && state.blocksMotion() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) { ground = y; break; }
        }
        if (ground == Integer.MIN_VALUE) return -1;
        int n = 0;
        for (int y = target + 1; y <= target + CUT_HEIGHT; y++) {
            BlockState state = level.getBlockState(p.set(x, y, z));
            if (!state.isAir()) { level.setBlock(p, AIR, FLAGS); n++; }
        }
        for (int y = ground + 1; y <= target; y++) { level.setBlock(p.set(x, y, z), FILL, FLAGS); n++; }
        BlockState top = level.getBlockState(p.set(x, target, z));
        if (middle && top.is(BlockTags.DIRT) && !top.is(Blocks.DIRT_PATH)) { level.setBlock(p, PATH, FLAGS); n++; }
        return n;
    }

    /** Inside a generated city building's protected volume (its whole chunk: walls, garden and yard), which the ramp never changes. */
    static boolean cityOwned(ServerLevel level, BlockPos pos) { return CityBuildings.INSTANCE.at(level, pos).isPresent(); }

    /** Ground nature made, not the city: earth, stone, sand and gravel, clay, snow, plants, leaves and logs. */
    static boolean natural(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)
            || state.is(BlockTags.SNOW) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(Blocks.DIRT_PATH) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MOSS_CARPET) || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }
}
