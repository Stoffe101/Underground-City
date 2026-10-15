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
    public record ChunkSample(boolean city, int highwayLevel, String building) {}

    private static volatile Object api;
    private static Method getLostInfo, getChunkInfo, isCity, maxHighwayLevel, buildingType;
    private LostCitiesLink() {}

    public static void register(IEventBus modBus) {
        if (!ModList.get().isLoaded("lostcities")) return;
        modBus.addListener((InterModEnqueueEvent event) -> InterModComms.sendTo("lostcities", "getLostCities",
            () -> (Function<Object, Void>) implementation -> { api = implementation; return null; }));
    }

    public static boolean ready() { return api != null; }

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
            }
            Object information = getLostInfo.invoke(implementation, (Level) level);
            if (information == null) return Optional.empty();
            Object sample = getChunkInfo.invoke(information, chunkX, chunkZ);
            return Optional.of(new ChunkSample((Boolean) isCity.invoke(sample), (Integer) maxHighwayLevel.invoke(sample),
                (String) buildingType.invoke(sample)));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("The Lost Cities API changed; update the survey bridge", error);
        }
    }
}
