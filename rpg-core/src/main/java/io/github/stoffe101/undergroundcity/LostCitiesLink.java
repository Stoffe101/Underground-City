package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Read-only diagnostic bridge to The Lost Cities' documented IMC API (mcjty.lostcities.api).
 * Reflection keeps Core free of a compile-time dependency; it does nothing unless Lost Cities is
 * installed. Chunk queries use the planner's own deterministic data and never generate terrain.
 *
 * <p><b>Recorded plans (D-167).</b> Lost Cities answers every plan question inside one lock per dimension, and its world-generation workers hold that lock while they work
 * out the plans of the chunks they are generating. A question from the server thread while a new city area is generating therefore waits on the workers: that was the
 * arrival hitch (up to 600 ms in one lookup, measured by {@link TickSampler}). So the overworld's plan of each chunk near a city is recorded where Lost Cities has just
 * worked it out, in its own post-generation events on the worker that generated the chunk (the same API call it makes there, answered from its cache), and kept with the
 * world ({@link Store}); {@link #chunk} answers from the record and asks Lost Cities only about a chunk it has not recorded. The record is the plan the chunk was actually
 * generated with, so it never goes stale. Nothing here touches the world from a worker: the event hands over the coordinates, and the plan is Lost Cities' own data.
 */
public final class LostCitiesLink {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record ChunkSample(boolean city, int highwayLevel, String building, String buildingId, int floors, int cellars, int cityLevel, String style) {
        /** The three-argument form older callers used (the survey and the launch smoke only read whether it is a city and which building type). */
        public ChunkSample(boolean city, int highwayLevel, String building) { this(city, highwayLevel, building, "", 0, 0, 0, ""); }
    }

    private static volatile Object api;
    private static Method getLostInfo, getChunkInfo, isCity, maxHighwayLevel, buildingType, buildingId, numFloors, numCellars, cityLevel, realHeight, cityStyle, styleName;
    private static Method eventWorld, eventChunkX, eventChunkZ;
    /** The overworld's ILostCityInformation, resolved on the server thread when the level loads; the workers' recording uses only this. */
    private static volatile Object overworldInfo;
    private static volatile long overworldSeed;
    private static volatile Store store;
    private static final Map<Long, ChunkSample> RECORDED = new ConcurrentHashMap<>();
    private static final int RECORD_CAP = 200_000;
    /** Blocks beyond a city's circle whose chunks are still recorded (the street service looks a few chunks round a player at the city's edge). */
    private static final int RECORD_MARGIN = 96;
    private static volatile boolean recordingBroken;
    /** When Lost Cities last generated an overworld chunk (any chunk, near a city or not): its workers take the plan lock while they generate. */
    private static volatile long lastGenerated;
    private static volatile boolean everGenerated;
    /** How long after the last generated chunk Lost Cities counts as still generating. */
    private static final long QUIET_NANOS = 2_000_000_000L;
    /** Off ({@code -Duc.lostcities.record=false}, or {@code -PucPtRecord=false} in a scripted run) only to measure the hitch without the record. */
    private static final boolean RECORDING = !"false".equals(System.getProperty("uc.lostcities.record", System.getProperty("uc.playtest.record", "true")));
    static volatile long recordedHits, apiLookups, recordedOnWorkers, deferred;

    private LostCitiesLink() {}

    public static void register(IEventBus modBus) {
        if (!ModList.get().isLoaded("lostcities")) return;
        modBus.addListener((InterModEnqueueEvent event) -> InterModComms.sendTo("lostcities", "getLostCities",
            () -> (Function<Object, Void>) implementation -> { api = implementation; return null; }));
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;
        forgeBus.addListener(LostCitiesLink::levelLoaded);
        forgeBus.addListener(LostCitiesLink::levelUnloaded);
        try {
            Class<?> base = Class.forName("mcjty.lostcities.api.LostCityEvent");
            eventWorld = base.getMethod("getWorld");
            eventChunkX = base.getMethod("getChunkX");
            eventChunkZ = base.getMethod("getChunkZ");
            for (String name : new String[] {"mcjty.lostcities.api.LostCityEvent$PostGenCityChunkEvent", "mcjty.lostcities.api.LostCityEvent$PostGenOutsideChunkEvent"}) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Class<net.minecraftforge.eventbus.api.Event> eventClass = (Class) Class.forName(name);
                forgeBus.addListener(EventPriority.LOWEST, false, eventClass, LostCitiesLink::generated);
            }
        } catch (ReflectiveOperationException error) {
            recordingBroken = true;
            LOGGER.error("[UC-CITY] Lost Cities' generation events changed: plans are asked of Lost Cities directly (the arrival hitch can come back)", error);
        }
    }

    public static boolean ready() { return api != null; }

    /**
     * Lets go of what Lost Cities keeps about the chunks it has planned, as Lost Cities itself does when a server stops; it works the plans out again, the same, when asked. Its
     * per-chunk building information remembers its four neighbours once asked for them, so the entries of its timed cache that expire stay reachable through the ones that do not,
     * and the whole of a generated area stays in memory (ninth pass, measured during pre-generation: 102,264 of them, with a palette map each, about 330 MB after 13,000 chunks).
     * Only {@link ChunkPregen} calls this, between its batches.
     */
    static void forgetPlans() {
        if (forgetBroken || !ModList.get().isLoaded("lostcities")) return;
        try {
            Class.forName("mcjty.lostcities.worldgen.lost.BuildingInfo").getMethod("cleanCache").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            forgetBroken = true;
            LOGGER.warn("[UC-CITY] Lost Cities' plan cache could not be cleared, so a long pre-generation needs more memory ({})", error.toString());
        }
    }

    private static boolean forgetBroken;

    /** Lost Cities' ILostCities implementation (null until it answered the interop request); for the listeners that call it by reflection. */
    static Object implementation() { return api; }

    /**
     * Lost Cities' information for the overworld, resolved on the server thread when the level loaded (null before that, for another level or another world). A listener on the
     * generation workers uses this rather than asking Lost Cities: asking from inside Lost Cities' own generation takes its lifecycle lock a second time, and when the lifecycle
     * has been marked dirty (Lost Cities does that when the player leaves a single-player world) the second call waits for a write lock its own thread's read lock blocks, so the
     * worker never finishes and the server never stops (homes run 3, eighth pass).
     */
    static Object overworldInfo(ServerLevel level) {
        return level.dimension() == Level.OVERWORLD && level.getSeed() == overworldSeed ? overworldInfo : null;
    }

    /** The street height of a Lost Cities city level in this level (Lost Cities' own answer, not a constant of ours); -1 when the API is not ready. */
    public static int realHeight(ServerLevel level, int cityLevel) {
        Object implementation = api;
        if (implementation == null) return -1;
        try {
            handles();
            Object information = getLostInfo.invoke(implementation, (Level) level);
            return information == null ? -1 : (Integer) realHeight.invoke(information, cityLevel);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("The Lost Cities API changed; update the survey bridge", error);
        }
    }

    /** The plan of a chunk: the recorded one when there is one (no lock taken), else Lost Cities' answer (server thread). */
    public static Optional<ChunkSample> chunk(ServerLevel level, int chunkX, int chunkZ) {
        Object implementation = api;
        if (implementation == null) return Optional.empty();
        if (RECORDING && level.dimension() == Level.OVERWORLD && level.getSeed() == overworldSeed) {
            ChunkSample recorded = RECORDED.get(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
            if (recorded != null) { recordedHits++; return Optional.of(recorded); }
        }
        try {
            handles();
            Object information = getLostInfo.invoke(implementation, (Level) level);
            if (information == null) return Optional.empty();
            apiLookups++;
            ChunkSample answer = sample(information, chunkX, chunkZ);
            // a loaded chunk's plan is the one it was generated with: keep it, so the question is never asked again (a chunk Lost Cities' generation feature did not run in,
            // the sea round an island city, is never recorded on a worker)
            if (RECORDING && level.dimension() == Level.OVERWORLD && level.getSeed() == overworldSeed && RECORDED.size() < RECORD_CAP
                && level.getChunkSource().getChunkNow(chunkX, chunkZ) != null && nearCity(chunkX, chunkZ)) {
                RECORDED.put(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ), answer);
                Store kept = store;
                if (kept != null) kept.setDirty();
            }
            return Optional.of(answer);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("The Lost Cities API changed; update the survey bridge", error);
        }
    }

    /** True when the plan of this overworld chunk was recorded (asking about it takes no lock). */
    public static boolean recorded(ServerLevel level, int chunkX, int chunkZ) {
        return level.dimension() == Level.OVERWORLD && level.getSeed() == overworldSeed && RECORDED.containsKey(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
    }

    /**
     * True when asking about this chunk now cannot wait on Lost Cities' generation workers: its plan is recorded, or Lost Cities has generated nothing for a while. The street
     * population, the households and the door watch ask only then and try again later; whatever must be right at once (a block broken in a building) asks regardless.
     */
    public static boolean askable(ServerLevel level, int chunkX, int chunkZ) {
        return !RECORDING || !everGenerated || recorded(level, chunkX, chunkZ) || System.nanoTime() - lastGenerated > QUIET_NANOS;
    }

    /** How many plans are recorded, and how questions were answered this session (for the street service's status and the arrival measurement). */
    public static String recordStatus() {
        return "plans recorded " + RECORDED.size() + " (" + recordedOnWorkers + " on the generation workers this session), answered from the record " + recordedHits + ", asked of Lost Cities " + apiLookups + ", put off while it generated " + deferred + ", the city character asked Lost Cities directly " + CityCharacter.askedDirectly + (recordingBroken || !RECORDING ? "; recording is off" : "");
    }

    private static synchronized void handles() throws ReflectiveOperationException {
        if (getLostInfo != null) return;
        Class<?> root = Class.forName("mcjty.lostcities.api.ILostCities");
        Class<?> info = Class.forName("mcjty.lostcities.api.ILostCityInformation");
        Class<?> chunk = Class.forName("mcjty.lostcities.api.ILostChunkInfo");
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
        getLostInfo = root.getMethod("getLostInfo", Level.class);   // last: the others are set once this is
    }

    private static ChunkSample sample(Object information, int chunkX, int chunkZ) throws ReflectiveOperationException {
        Object sample = getChunkInfo.invoke(information, chunkX, chunkZ);
        Object id = buildingId.invoke(sample);
        Object style = cityStyle.invoke(sample);
        return new ChunkSample((Boolean) isCity.invoke(sample), (Integer) maxHighwayLevel.invoke(sample),
            (String) buildingType.invoke(sample), id == null ? "" : id.toString(), (Integer) numFloors.invoke(sample), (Integer) numCellars.invoke(sample), (Integer) cityLevel.invoke(sample),
            style == null ? "" : String.valueOf(styleName.invoke(style)));
    }

    // ------------------------------------------------------------------------------------------------ recording on the generation workers

    /**
     * Lost Cities' post-generation event, on the worker that generated the chunk: records the chunk's plan when it is near a city. The plan was worked out a moment ago for
     * this chunk, so the question is answered from Lost Cities' cache; the worker already competes for the same lock, the server thread does not wait for it.
     */
    private static void generated(net.minecraftforge.eventbus.api.Event event) {
        Object information = overworldInfo;
        if (information == null || recordingBroken || !RECORDING || RECORDED.size() >= RECORD_CAP) return;
        try {
            if (!(eventWorld.invoke(event) instanceof WorldGenLevel world) || world.getLevel().dimension() != Level.OVERWORLD) return;
            lastGenerated = System.nanoTime();
            everGenerated = true;
            int chunkX = (Integer) eventChunkX.invoke(event), chunkZ = (Integer) eventChunkZ.invoke(event);
            if (!nearCity(chunkX, chunkZ)) return;
            RECORDED.put(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ), sample(information, chunkX, chunkZ));
            recordedOnWorkers++;
            Store kept = store;
            if (kept != null) kept.setDirty();   // a flag the next save reads on the server thread
        } catch (ReflectiveOperationException | RuntimeException error) {
            recordingBroken = true;
            LOGGER.error("[UC-CITY] recording a chunk's plan failed: plans are asked of Lost Cities directly from now on", error);
        }
    }

    /** Within a city's circle or {@link #RECORD_MARGIN} blocks of it (Core's own region data, which the characteristics listener reads on the workers too). */
    private static boolean nearCity(int chunkX, int chunkZ) {
        double x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) {
            if (!region.dimension().equals("minecraft:overworld")) continue;
            double dx = x - (region.centerChunkX() * 16 + 8), dz = z - (region.centerChunkZ() * 16 + 8);
            double reach = region.cityRadius() + RECORD_MARGIN;
            if (dx * dx + dz * dz <= reach * reach) return true;
        }
        return false;
    }

    private static void levelLoaded(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        RECORDED.clear();
        recordedHits = apiLookups = recordedOnWorkers = deferred = 0;
        overworldSeed = level.getSeed();
        Store loaded = level.getDataStorage().computeIfAbsent(Store::load, Store::new, Store.NAME);
        RECORDED.putAll(loaded.plans);
        loaded.plans.clear();
        store = loaded;
        Object implementation = api;
        if (implementation == null) return;
        try {
            handles();
            overworldInfo = getLostInfo.invoke(implementation, (Level) level);
            LOGGER.info("[UC-CITY] {} chunk plans loaded with the world; new ones are recorded as Lost Cities generates them", RECORDED.size());
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.error("[UC-CITY] could not resolve Lost Cities' information for the overworld: plans are asked of it directly", error);
        }
    }

    private static void levelUnloaded(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        overworldInfo = null;
        overworldSeed = 0;
        store = null;
        everGenerated = false;
        RECORDED.clear();
    }

    /** Test seam: the save and load of the record, as a restart does it, for these samples (returns what came back). */
    static Map<Long, ChunkSample> storeRoundTripForTest(Map<Long, ChunkSample> samples) {
        Map<Long, ChunkSample> before = new java.util.HashMap<>(RECORDED);
        try {
            RECORDED.clear();
            RECORDED.putAll(samples);
            CompoundTag saved = new Store().save(new CompoundTag());
            return Store.load(saved).plans;
        } finally {
            RECORDED.clear();
            RECORDED.putAll(before);
        }
    }

    /** The recorded plans, kept with the world in {@code data/underground_city_city_plans.dat}: read once when the level loads, and written from the live record by the level's own save (on the server thread) after a worker recorded something. */
    static final class Store extends SavedData {
        static final String NAME = "underground_city_city_plans";
        final Map<Long, ChunkSample> plans = new java.util.HashMap<>();

        Store() {}

        static Store load(CompoundTag tag) {
            Store store = new Store();
            ListTag list = tag.getList("plans", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag p = list.getCompound(i);
                store.plans.put(p.getLong("k"), new ChunkSample(p.getBoolean("c"), p.getInt("h"), p.getString("b"), p.getString("i"), p.getInt("f"), p.getInt("n"), p.getInt("l"), p.getString("s")));
            }
            return store;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Map.Entry<Long, ChunkSample> entry : RECORDED.entrySet()) {
                ChunkSample s = entry.getValue();
                CompoundTag p = new CompoundTag();
                p.putLong("k", entry.getKey());
                p.putBoolean("c", s.city());
                if (s.highwayLevel() != 0) p.putInt("h", s.highwayLevel());
                if (s.building() != null && !s.building().isEmpty()) p.putString("b", s.building());
                if (!s.buildingId().isEmpty()) p.putString("i", s.buildingId());
                if (s.floors() != 0) p.putInt("f", s.floors());
                if (s.cellars() != 0) p.putInt("n", s.cellars());
                if (s.cityLevel() != 0) p.putInt("l", s.cityLevel());
                if (!s.style().isEmpty()) p.putString("s", s.style());
                list.add(p);
            }
            tag.put("plans", list);
            tag.putInt("schema_version", 1);
            return tag;
        }
    }
}
