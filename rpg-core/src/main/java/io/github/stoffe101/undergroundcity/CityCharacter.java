package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * What each city looks like (D-117): a city style, and which buildings stand in which part of the city.
 *
 * <p>Why this exists. The first probe of the generated cities (October 21, 98 chunks of Valewood and Ironhaven) found that Lost Cities 7.5.5 gives a predefined city's own style to exactly one chunk, the
 * centre one (its {@code City.getCityStyle} passes the chunk being asked about to the predefined-city lookup, not the city centre it is looping over), and picks the world style's default, the standard
 * style, for every other chunk: 97 of 98 chunks had the standard style, and the per-city style files of D-104 never shaped a street or a building. That is why the three cities read alike.
 *
 * <p>Lost Cities' own public API has the hook for it: {@code LostCityEvent.CharacteristicsEvent} is posted for every chunk after the building was chosen and lets a mod change the style and the building of the
 * chunk before anything is built. This class is that listener. A city's data file ({@code data/underground_city/city_character/<city>.json}) names the style and, for each ring of the city (a fraction of its radius),
 * a weighted list of buildings; a chunk of a city that Lost Cities decided to build on gets one of them, chosen from the world seed and the chunk position only, so the choice is the same every time the chunk is
 * asked about (the planning query and the generation) and never depends on which player is near.
 *
 * <p>It acts only on chunks Lost Cities itself decided are buildings of a city in the overworld, never on a multi-chunk building, never when no data file exists for the city and never when the building is not
 * registered (it then logs once and leaves Lost Cities' choice). It reads Lost Cities by reflection like {@link LostCitiesLink}: nothing here is a compile-time dependency.
 */
public final class CityCharacter extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CityCharacter INSTANCE = new CityCharacter();

    public record Pick(ResourceLocation building, double weight) {}
    /** A ring: chunks whose distance from the city centre is at most {@code within} times the city radius, nearest ring first. */
    public record Zone(double within, List<Pick> picks) {
        double total() { double sum = 0; for (Pick pick : picks) sum += pick.weight; return sum; }
    }
    public record Character(String city, ResourceLocation style, List<Zone> zones) {}

    private volatile Map<String, Character> characters = Map.of();
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CityCharacter() { super(new Gson(), "city_character"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Character> next = new LinkedHashMap<>();
        for (var entry : data.entrySet()) {
            Character parsed = parse(entry.getKey(), entry.getValue().getAsJsonObject());
            if (next.put(parsed.city, parsed) != null) throw new IllegalArgumentException("Two city-character files for " + parsed.city);
        }
        characters = Map.copyOf(next);
        LOGGER.info("[UC-CITY] city character loaded for {}", characters.keySet());
    }

    static Character parse(ResourceLocation file, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported city-character schema: " + file);
        String city = json.get("city").getAsString();
        if (!ProgressionRules.validCity(city)) throw new IllegalArgumentException("Invalid city in " + file);
        ResourceLocation style = ResourceLocation.parse(json.get("style").getAsString());
        List<Zone> zones = new ArrayList<>();
        double last = 0;
        JsonArray array = json.getAsJsonArray("zones");
        if (array == null || array.isEmpty()) throw new IllegalArgumentException("A city character needs at least one zone: " + file);
        for (JsonElement element : array) {
            JsonObject zone = element.getAsJsonObject();
            double within = zone.get("within").getAsDouble();
            if (within <= last || within > 1.0 + 1e-9) throw new IllegalArgumentException("Zones must grow from 0 to 1: " + file);
            last = within;
            List<Pick> picks = new ArrayList<>();
            for (JsonElement pick : zone.getAsJsonArray("buildings")) {
                JsonObject object = pick.getAsJsonObject();
                double weight = object.get("weight").getAsDouble();
                if (weight <= 0 || weight > 1000) throw new IllegalArgumentException("Bad weight in " + file);
                picks.add(new Pick(ResourceLocation.parse(object.get("id").getAsString()), weight));
            }
            if (picks.isEmpty()) throw new IllegalArgumentException("An empty zone in " + file);
            zones.add(new Zone(within, List.copyOf(picks)));
        }
        if (last < 1.0 - 1e-9) throw new IllegalArgumentException("The last zone must reach the edge of the city: " + file);
        return new Character(city, style, List.copyOf(zones));
    }

    public Map<String, Character> all() { return characters; }

    // ------------------------------------------------------------------------------------------------ the choice (pure)

    /** The building for a chunk: the zone of its distance fraction, then a weighted pick that depends on the seed and the chunk only. */
    static ResourceLocation choose(Character character, double distanceFraction, long seed, int chunkX, int chunkZ) {
        Zone zone = character.zones.get(character.zones.size() - 1);
        for (Zone candidate : character.zones) if (distanceFraction <= candidate.within + 1e-9) { zone = candidate; break; }
        SplittableRandom random = new SplittableRandom(seed * 6364136223846793005L + chunkX * 1442695040888963407L + chunkZ * 0x9E3779B97F4A7C15L);
        double roll = random.nextDouble() * zone.total();
        for (Pick pick : zone.picks) { roll -= pick.weight; if (roll < 0) return pick.building; }
        return zone.picks.get(zone.picks.size() - 1).building;
    }

    /** Which city's circle holds the chunk and how far out in it the chunk is (0 at the centre, 1 at the edge); empty outside every circle. */
    record Placement(CityRegions.Region region, double fraction) {}

    static java.util.Optional<Placement> placement(List<CityRegions.Region> regions, int chunkX, int chunkZ) {
        double x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        Placement best = null;
        for (CityRegions.Region region : regions) {
            if (!region.dimension().equals("minecraft:overworld")) continue;
            double dx = x - region.centerX(), dz = z - region.centerZ();
            double fraction = Math.sqrt(dx * dx + dz * dz) / region.cityRadius();
            if (fraction <= 1.0 && (best == null || fraction < best.fraction)) best = new Placement(region, fraction);
        }
        return java.util.Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------------------------------------ the hook into Lost Cities (reflection)

    private static Method getCharacteristics, getChunkX, getChunkZ, getWorld, getLostInfo, getBuildings, getCityStyles, registryGet;
    private static Field cityStyleId, cityStyle, buildingTypeId, buildingType, multiBuilding, isCity;

    /** Registers the listener on the Forge bus when Lost Cities is installed. Safe to call when it is not (it does nothing). */
    public static void register(IEventBus forgeBus) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("lostcities")) return;
        try {
            Class<?> event = Class.forName("mcjty.lostcities.api.LostCityEvent$CharacteristicsEvent");
            Class<?> base = Class.forName("mcjty.lostcities.api.LostCityEvent");
            Class<?> characteristics = Class.forName("mcjty.lostcities.api.LostChunkCharacteristics");
            Class<?> information = Class.forName("mcjty.lostcities.api.ILostCityInformation");
            Class<?> registry = Class.forName("mcjty.lostcities.api.ILostCityAssetRegistry");
            getCharacteristics = event.getMethod("getCharacteristics");
            getChunkX = base.getMethod("getChunkX");
            getChunkZ = base.getMethod("getChunkZ");
            getWorld = base.getMethod("getWorld");
            getLostInfo = Class.forName("mcjty.lostcities.api.ILostCities").getMethod("getLostInfo", net.minecraft.world.level.Level.class);
            getBuildings = information.getMethod("getBuildings");
            getCityStyles = information.getMethod("getCityStyles");
            registryGet = registry.getMethod("get", net.minecraft.world.level.CommonLevelAccessor.class, ResourceLocation.class);
            cityStyleId = characteristics.getField("cityStyleId");
            cityStyle = characteristics.getField("cityStyle");
            buildingTypeId = characteristics.getField("buildingTypeId");
            buildingType = characteristics.getField("buildingType");
            multiBuilding = characteristics.getField("multiBuilding");
            isCity = characteristics.getField("isCity");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<net.minecraftforge.eventbus.api.Event> eventClass = (Class) event;
            forgeBus.addListener(EventPriority.NORMAL, false, eventClass, INSTANCE::onCharacteristics);
            LOGGER.info("[UC-CITY] listening to Lost Cities' characteristics event");
        } catch (ReflectiveOperationException error) {
            LOGGER.error("[UC-CITY] The Lost Cities API changed; cities keep Lost Cities' own styles and buildings", error);
        }
    }

    private void onCharacteristics(net.minecraftforge.eventbus.api.Event event) {
        Map<String, Character> loaded = characters;
        if (loaded.isEmpty()) return;
        try {
            Object ch = getCharacteristics.invoke(event);
            if (!isCity.getBoolean(ch)) return;
            int chunkX = (Integer) getChunkX.invoke(event), chunkZ = (Integer) getChunkZ.invoke(event);
            var placement = placement(CityRegions.INSTANCE.all(), chunkX, chunkZ);
            if (placement.isEmpty()) return;
            Character character = loaded.get(placement.get().region.id());
            if (character == null) return;
            WorldGenLevel world = (WorldGenLevel) getWorld.invoke(event);
            net.minecraft.server.level.ServerLevel level = world.getLevel();
            Object information = getLostInfo.invoke(INSTANCE_API(), level);
            if (information == null) return;
            Object styles = getCityStyles.invoke(information);
            Object style = registryGet.invoke(styles, level, character.style);
            if (style == null) { warnOnce("style " + character.style, "city style " + character.style + " is not registered; " + character.city + " keeps Lost Cities' style"); }
            else { cityStyle.set(ch, style); cityStyleId.set(ch, character.style); }
            // only a single-chunk building that Lost Cities itself decided to build; a multi-chunk building, a street or a park is left alone
            if (buildingType.get(ch) == null || multiBuilding.get(ch) != null) return;
            ResourceLocation chosen = choose(character, placement.get().fraction, world.getSeed(), chunkX, chunkZ);
            Object building = registryGet.invoke(getBuildings.invoke(information), level, chosen);
            if (building == null) { warnOnce("building " + chosen, "building " + chosen + " is not registered; the chunk keeps Lost Cities' choice"); return; }
            buildingType.set(ch, building);
            buildingTypeId.set(ch, chosen);
        } catch (ReflectiveOperationException | RuntimeException error) {
            warnOnce("error " + error.getClass().getName(), "the city character failed once and is skipped for the chunk: " + error);
        }
    }

    private static Object INSTANCE_API() { return LostCitiesLink.implementation(); }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) LOGGER.warn("[UC-CITY] {}", message);
    }

    /** Names a building the data refers to but a set of registered ids does not contain (a test and a data-check seam). */
    static Set<ResourceLocation> missing(Character character, Set<ResourceLocation> registered) {
        Set<ResourceLocation> out = new HashSet<>();
        for (Zone zone : character.zones) for (Pick pick : zone.picks) if (!registered.contains(pick.building)) out.add(pick.building);
        return out;
    }
}
