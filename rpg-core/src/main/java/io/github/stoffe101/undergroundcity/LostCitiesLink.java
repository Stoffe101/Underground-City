package io.github.stoffe101.undergroundcity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Function;

/**
 * Read-only diagnostic bridge to The Lost Cities' documented IMC API (mcjty.lostcities.api).
 * Reflection keeps Core free of a compile-time dependency; it does nothing unless Lost Cities is
 * installed. Chunk queries use the planner's own deterministic data and never generate terrain.
 */
public final class LostCitiesLink {
    public record ChunkSample(boolean city, int highwayLevel, String building, String buildingId, int floors, int cellars, int cityLevel, String style) {
        /** The three-argument form older callers used (the survey and the launch smoke only read whether it is a city and which building type). */
        public ChunkSample(boolean city, int highwayLevel, String building) { this(city, highwayLevel, building, "", 0, 0, 0, ""); }
    }

    private static volatile Object api;
    private static Method getLostInfo, getChunkInfo, isCity, maxHighwayLevel, buildingType, buildingId, numFloors, numCellars, cityLevel, realHeight, cityStyle, styleName;
    private LostCitiesLink() {}

    public static void register(IEventBus modBus) {
        if (!ModList.get().isLoaded("lostcities")) return;
        modBus.addListener((InterModEnqueueEvent event) -> InterModComms.sendTo("lostcities", "getLostCities",
            () -> (Function<Object, Void>) implementation -> { api = implementation; return null; }));
    }

    public static boolean ready() { return api != null; }

    /** Lost Cities' ILostCities implementation (null until it answered the interop request); for the listeners that call it by reflection. */
    static Object implementation() { return api; }

    /** The street height of a Lost Cities city level in this level (Lost Cities' own answer, not a constant of ours); -1 when the API is not ready. */
    public static int realHeight(ServerLevel level, int cityLevel) {
        Object implementation = api;
        if (implementation == null) return -1;
        try {
            if (realHeight == null) chunk(level, 0, 0);   // loads the reflective handles
            Object information = getLostInfo.invoke(implementation, (Level) level);
            return information == null ? -1 : (Integer) realHeight.invoke(information, cityLevel);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("The Lost Cities API changed; update the survey bridge", error);
        }
    }

    public static Optional<ChunkSample> chunk(ServerLevel level, int chunkX, int chunkZ) {
        Object implementation = api;
        if (implementation == null) return Optional.empty();
        try {
            if (getLostInfo == null) {
                Class<?> root = Class.forName("mcjty.lostcities.api.ILostCities");
                Class<?> info = Class.forName("mcjty.lostcities.api.ILostCityInformation");
                Class<?> chunk = Class.forName("mcjty.lostcities.api.ILostChunkInfo");
                getLostInfo = root.getMethod("getLostInfo", Level.class);
                getChunkInfo = info.getMethod("getChunkInfo", int.class, int.class);
                isCity = chunk.getMethod("isCity");
                maxHighwayLevel = chunk.getMethod("getMaxHighwayLevel");
                buildingType = chunk.getMethod("getBuildingType");
                buildingId = chunk.getMethod("getBuildingId");
                numFloors = chunk.getMethod("getNumFloors");
                numCellars = chunk.getMethod("getNumCellars");
                cityLevel = chunk.getMethod("getCityLevel");
                cityStyle = chunk.getMethod("getCityStyle");
                styleName = Class.forName("mcjty.lostcities.api.ILostCityCityStyle").getMethod("getName");
                realHeight = info.getMethod("getRealHeight", int.class);
            }
            Object information = getLostInfo.invoke(implementation, (Level) level);
            if (information == null) return Optional.empty();
            Object sample = getChunkInfo.invoke(information, chunkX, chunkZ);
            Object id = buildingId.invoke(sample);
            Object style = cityStyle.invoke(sample);
            return Optional.of(new ChunkSample((Boolean) isCity.invoke(sample), (Integer) maxHighwayLevel.invoke(sample),
                (String) buildingType.invoke(sample), id == null ? "" : id.toString(), (Integer) numFloors.invoke(sample), (Integer) numCellars.invoke(sample), (Integer) cityLevel.invoke(sample), style == null ? "" : String.valueOf(styleName.invoke(style))));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("The Lost Cities API changed; update the survey bridge", error);
        }
    }
}
