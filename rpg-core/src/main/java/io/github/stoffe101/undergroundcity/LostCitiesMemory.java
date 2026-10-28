package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What Lost Cities keeps about the chunks it planned, and the one thing Core does about it in play (tenth pass, D-197; {@code docs/technical/lost-cities-memory.md}).
 *
 * <p>Lost Cities keeps each chunk's plan ({@code BuildingInfo}) in a timed cache that forgets an entry five minutes after it was last asked for. A plan, once asked for its
 * neighbour to the west, east, north or south, keeps that neighbour in a field for good ({@code getXmin} and the three like it read the field and fill it the first time; nothing
 * else reads it, and nothing clears it but the server stopping). An entry the cache forgets therefore stays in memory while any plan that is still cached links to it, and that plan's
 * neighbours link to theirs: the whole of an area that was ever generated stays reachable from the few plans near a player (ninth pass: 102,264 of them after a pre-generation of
 * 13,000 chunks, about 330 MB).
 *
 * <p>Every {@link #PERIOD_TICKS} ticks, a slice at a time ({@link #SLICE} plans a tick), the links of the plans in the cache are dropped. The cache is not cleared and no plan is
 * taken out of it: a plan asks the cache again the next time it needs a neighbour and gets the same object while that one is cached, or a new one worked out the same way
 * (Lost Cities' plans depend only on the seed and the profile). Only the two getters read the links, each into a local before using it, so a link dropped while a generation worker
 * reads it is answered from the cache there and then. Nothing is cleared while Lost Cities generates; nothing is cleared at all. Off with {@code -Duc.lostcities.unchain=false}, to
 * measure the retention it removes.
 */
public final class LostCitiesMemory {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final boolean UNCHAIN = !"false".equals(System.getProperty("uc.lostcities.unchain", "true"));
    static final int PERIOD_TICKS = 1200, SLICE = 4000;
    private static final String[] LINKS = {"xmin", "xmax", "zmin", "zmax"};
    private static volatile boolean broken;
    private static Map<?, ?> buildings, characteristics, levels;
    private static Field entryValue;
    private static Field[] links;
    private static Iterator<?> pass;
    private static int ticks;
    static final AtomicLong linksDropped = new AtomicLong(), passes = new AtomicLong();

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !UNCHAIN || broken || !ModList.get().isLoaded("lostcities")) return;
        try {
            if (pass == null) {
                if (++ticks < PERIOD_TICKS) return;
                ticks = 0;
                resolve();
                pass = new ArrayList<>(buildings.values()).iterator();
                passes.incrementAndGet();
            }
            for (int n = 0; n < SLICE && pass.hasNext(); n++) unchain(entryValue.get(pass.next()));
            if (!pass.hasNext()) pass = null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            pass = null;
            LOGGER.error("[UC-CITY] Lost Cities' plan cache changed: its plans' links are kept from now on (memory grows with the area explored)", error);
        }
    }

    private static void unchain(Object plan) throws IllegalAccessException {
        if (plan == null) return;
        for (Field link : links) if (link.get(plan) != null) { link.set(plan, null); linksDropped.incrementAndGet(); }
    }

    private static synchronized void resolve() throws ReflectiveOperationException {
        if (buildings != null) return;
        Class<?> info = Class.forName("mcjty.lostcities.worldgen.lost.BuildingInfo");
        Map<?, ?> b = cache(info, "BUILDING_INFO_MAP"), c = cache(info, "CITY_INFO_MAP"), l = cache(info, "CITY_LEVEL_CACHE");
        Field value = Class.forName("mcjty.lostcities.varia.TimedCache$Entry").getDeclaredField("value");
        value.setAccessible(true);
        Field[] fields = new Field[LINKS.length];
        for (int i = 0; i < LINKS.length; i++) {
            fields[i] = info.getDeclaredField(LINKS[i]);
            if (fields[i].getType() != info) throw new NoSuchFieldException(LINKS[i] + " is no longer a plan");
            fields[i].setAccessible(true);
        }
        entryValue = value;
        links = fields;
        characteristics = c;
        levels = l;
        buildings = b;   // last: the others are set once this is
    }

    private static Map<?, ?> cache(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object timed = field.get(null);
        Field map = timed.getClass().getDeclaredField("cache");
        map.setAccessible(true);
        return (Map<?, ?>) map.get(timed);
    }

    /** One line for the samples: the cache's entries and what the unchaining has done ("not read" when Lost Cities is absent or its cache could not be read). */
    public static String status() {
        if (!ModList.get().isLoaded("lostcities")) return "lost cities absent";
        try {
            resolve();
            return "lost cities cache: " + buildings.size() + " plans, " + characteristics.size() + " characteristics, " + levels.size() + " levels; unchaining "
                + (UNCHAIN && !broken ? "on (" + passes.get() + " passes, " + linksDropped.get() + " links dropped)" : "off");
        } catch (ReflectiveOperationException | RuntimeException error) {
            return "lost cities cache not read: " + error;
        }
    }

    /** Live instances of a class after a full collection, by the JVM's own class histogram (expensive: a full GC; a measurement only), or -1. */
    static long[] liveInstances(String... classNames) {
        long[] out = new long[classNames.length * 2];
        java.util.Arrays.fill(out, -1);
        try {
            var server = java.lang.management.ManagementFactory.getPlatformMBeanServer();
            String histogram = (String) server.invoke(new javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] {new String[0]}, new String[] {String[].class.getName()});
            for (String line : histogram.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                for (int i = 0; i < classNames.length; i++) {
                    if (parts[3].equals(classNames[i])) { out[i * 2] = Long.parseLong(parts[1]); out[i * 2 + 1] = Long.parseLong(parts[2]); }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("[UC-PERF] class histogram failed: {}", error.toString());
        }
        return out;
    }
}
