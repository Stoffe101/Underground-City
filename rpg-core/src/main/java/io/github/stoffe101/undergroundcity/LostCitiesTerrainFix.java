package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The grass blocks on the cities' paved streets (tenth pass, D-196; measured by the city probe: 3,848 street cells of three new cities had a grass block standing on the paving).
 *
 * <p>Before Lost Cities lays a city chunk, {@code doCityChunk} brings every column's natural ground to the city's ground level with {@code moveDown(x, z, ground + 1, top)}: it walks
 * down from the sky past air and plants to the first solid block and, when that block is higher than {@code ground + 1}, moves the top six layers down so that the surface ends at
 * the ground level. When the first solid block is exactly at {@code ground + 1} it answers "nothing to move" (its test is {@code y <= ground + 1}), and the street block is then
 * laid at the ground level under it: the grass of every patch of land that stood one block above the city stays on the street, in strips along the contour.
 *
 * <p>For that one case, in the archipelago only and during generation (the city ground guard's decoration state, {@link CityGround#decoratingRegional}), the block is moved down
 * onto the ground level as {@code moveDown} does for higher ground, the cell above becomes air with the plants that stood on it, and the column's new top is answered so that
 * {@code moveUp} is not called. A column whose ground is at the city's level or lower keeps it, but the plants standing over the city's ground are taken off, as {@code moveDown}
 * does on higher ground (a neighbour decorated before the chunk can have planted them; the street would be laid under them). Every other caller of {@code moveDown} (the terrain
 * shaping round a city) is left as Lost Cities makes it. Reflection,
 * resolved once: Core has no compile-time dependency on Lost Cities; if its methods change, the fix turns itself off and says so.
 */
public final class LostCitiesTerrainFix {
    private static final int NONE = Short.MIN_VALUE;
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static volatile boolean broken;
    private static Method getDriver, isFoliageOrEmpty, isEmpty, current, block, getBlock;
    static final AtomicLong lowered = new AtomicLong(), plantsCleared = new AtomicLong();

    private LostCitiesTerrainFix() {}

    public static void enter() { DEPTH.get()[0]++; }

    public static void leave() { int[] d = DEPTH.get(); if (d[0] > 0) d[0]--; }

    /** What {@code moveDown} should answer for a column of a city chunk: its own answer, or the new top when the ground stood exactly one block above the city's. */
    public static int lowered(Object terrain, int x, int z, int level, int answer) {
        if (answer != NONE || broken || DEPTH.get()[0] == 0 || !CityGround.decoratingRegional()) return answer;
        try {
            resolve(terrain);
            Object driver = getDriver.invoke(terrain);
            BlockState top = (BlockState) getBlock.invoke(driver, x, level, z);
            if ((Boolean) isFoliageOrEmpty.invoke(null, top)) {
                // the ground is at the city's level or lower (moveUp raises it); a plant standing over the city's ground goes, as moveDown takes the plants off higher ground: a
                // neighbour decorated before this chunk can have planted a patch here, and the street would be laid under it (the city probe found flowers on the paving)
                if (clearPlants(driver, x, level, z) > 0) plantsCleared.incrementAndGet();
                return answer;
            }
            current.invoke(driver, x, level - 1, z);
            block.invoke(driver, top);
            current.invoke(driver, x, level, z);
            block.invoke(driver, Blocks.AIR.defaultBlockState());
            clearPlants(driver, x, level + 1, z);
            lowered.incrementAndGet();
            return level - 1;
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            LogUtils.getLogger().error("[UC-CITY] Lost Cities' terrain methods changed: the ground one block above a street is left on it again", error);
            return answer;
        }
    }

    /** Plants (Lost Cities' own idea of foliage: leaves, flowers, grass, vines) from {@code y} up, until air or anything solid; how many went. */
    private static int clearPlants(Object driver, int x, int y, int z) throws ReflectiveOperationException {
        int n = 0;
        for (int at = y; at < y + 8; at++) {
            BlockState state = (BlockState) getBlock.invoke(driver, x, at, z);
            if ((Boolean) isEmpty.invoke(null, state) || !(Boolean) isFoliageOrEmpty.invoke(null, state)) break;
            current.invoke(driver, x, at, z);
            block.invoke(driver, Blocks.AIR.defaultBlockState());
            n++;
        }
        return n;
    }

    private static synchronized void resolve(Object terrain) throws ReflectiveOperationException {
        if (getBlock != null) return;
        Class<?> feature = terrain.getClass();
        Method driverGetter = feature.getMethod("getDriver");
        Method foliage = feature.getDeclaredMethod("isFoliageOrEmpty", BlockState.class);
        foliage.setAccessible(true);
        Method empty = feature.getMethod("isEmpty", BlockState.class);
        Class<?> driver = driverGetter.getReturnType();
        current = driver.getMethod("current", int.class, int.class, int.class);
        block = driver.getMethod("block", BlockState.class);
        isFoliageOrEmpty = foliage;
        isEmpty = empty;
        getDriver = driverGetter;
        getBlock = driver.getMethod("getBlock", int.class, int.class, int.class);   // last: the others are set once this is
    }
}
