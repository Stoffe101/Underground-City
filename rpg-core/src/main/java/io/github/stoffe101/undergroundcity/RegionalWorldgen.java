package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The machinery of the archipelago world (D-104): the {@code underground_city:island} density function, and a data pack made at load time
 * from the game's own overworld terrain functions.
 *
 * <p>The vanilla overworld builds its hills, coasts and biomes from three noises: continentalness, erosion and the peaks-and-valleys ridge,
 * and the splines that turn them into height ({@code offset}, {@code factor}, {@code jaggedness}) refer to those functions by name. To put
 * land and sea where a plan says, without writing a terrain generator and without copying Mojang's 110 KB of splines into this repository,
 * the pack reads the vanilla functions through the game's own vanilla pack and serves copies whose references point at
 * {@code underground_city:regional/*} instead. The four functions that change meaning (continents, erosion, temperature, humidity) are
 * ours ({@code data/underground_city/worldgen/density_function/regional}, generated from the plan by {@code scripts/plan_archipelago.py}).
 * The result is the noise settings {@code underground_city:regional}, used only by the {@code underground_city:archipelago} world preset:
 * an existing world never reads any of it, so no existing save can change.
 */
public final class RegionalWorldgen {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PACK_ID = "underground_city_regional";
    static final String OVERWORLD_DIR = "worldgen/density_function/overworld/";
    static final String REGIONAL_DIR = "worldgen/density_function/regional/";
    /** The vanilla functions whose references are rebuilt (everything between the continent noise and the final density). */
    static final String[] REBUILT = {"offset", "factor", "jaggedness", "depth", "sloped_cheese", "ridges_folded"};
    private static final Pattern REFERENCE = Pattern.compile("\"minecraft:overworld/(continents|erosion|ridges|ridges_folded|depth|offset|factor|jaggedness|sloped_cheese)\"");

    private static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_TYPES = DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, UndergroundCity.MOD_ID);
    static { DENSITY_TYPES.register("island", () -> IslandField.CODEC.codec()); }

    private RegionalWorldgen() {}

    public static void register(IEventBus modBus) {
        DENSITY_TYPES.register(modBus);
        modBus.addListener(RegionalWorldgen::addPack);
    }

    private static void addPack(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) return;
        event.addRepositorySource(consumer -> consumer.accept(Pack.readMetaAndCreate(PACK_ID, Component.literal("Underground City: archipelago terrain"), true,
            id -> new DerivedPack(), PackType.SERVER_DATA, Pack.Position.BOTTOM, PackSource.BUILT_IN)));
    }

    // ------------------------------------------------------------------------------------------------ derivation (no Minecraft needed)

    /** Points a vanilla function's references at the regional copies. */
    static String rebuild(String vanillaJson) {
        return REFERENCE.matcher(vanillaJson).replaceAll("\"underground_city:regional/$1\"");
    }

    /**
     * The files of the derived pack, keyed by path under {@code data/underground_city/}, from a reader of the vanilla files (path under
     * {@code data/minecraft/}). Fails loudly when a file the derivation needs is missing: a changed vanilla layout must not give a quiet, wrong world.
     */
    static Map<String, String> derive(Function<String, String> vanilla) { return derive(vanilla, false); }

    /**
     * As above; with {@code noOreVeins} the noise settings switch off the game's noise-based ore veins (the big copper and iron veins, with their tuff and granite filler, that no biome modifier
     * can remove because they are not placed features). Used when the scarcity package ({@code underground_city_scarcity}, D-120) is installed: an ore audit of a scarcity world found 12,899
     * ore blocks left of 443,563, all of them copper at y 0 to 49 and deepslate iron at y -58 to -8, the two kinds of vein.
     */
    static Map<String, String> derive(Function<String, String> vanilla, boolean noOreVeins) {
        Map<String, String> files = new LinkedHashMap<>();
        for (String name : REBUILT) {
            String text = vanilla.apply(OVERWORLD_DIR + name + ".json");
            if (text == null) throw new IllegalStateException("The vanilla density function overworld/" + name + " is missing; the archipelago terrain cannot be derived");
            files.put(REGIONAL_DIR + name + ".json", rebuild(text));
        }
        String settings = vanilla.apply("worldgen/noise_settings/overworld.json");
        if (settings == null) throw new IllegalStateException("The vanilla overworld noise settings are missing; the archipelago terrain cannot be derived");
        JsonObject json = JsonParser.parseString(rebuild(settings)).getAsJsonObject();
        JsonObject router = json.getAsJsonObject("noise_router");
        for (String field : new String[] {"continents", "erosion", "depth", "ridges"}) {
            if (!router.has(field)) throw new IllegalStateException("The vanilla noise router has no " + field);
        }
        // the climate sampler (biomes) reads these two; the terrain splines read continents and erosion through the rebuilt functions above
        router.addProperty("temperature", "underground_city:regional/temperature");
        router.addProperty("vegetation", "underground_city:regional/humidity");
        if (noOreVeins) json.addProperty("ore_veins_enabled", false);
        files.put("worldgen/noise_settings/regional.json", json.toString());
        return files;
    }

    /** Test seam: the in-memory pack over given files. */
    static PackResources packForTest(Map<String, String> files) { return new DerivedPack(files); }

    private static String readVanilla(net.minecraft.server.packs.VanillaPackResources pack, String path) {
        IoSupplier<InputStream> supplier = pack.getResource(PackType.SERVER_DATA, ResourceLocation.fromNamespaceAndPath("minecraft", path));
        if (supplier == null) return null;
        try (InputStream in = supplier.get()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot read the vanilla file " + path, error); }
    }

    // ------------------------------------------------------------------------------------------------ the pack

    /** An in-memory data pack (namespace {@code underground_city}) holding the derived files; nothing is written to disk. */
    private static final class DerivedPack implements PackResources {
        private final Map<ResourceLocation, byte[]> files = new LinkedHashMap<>();

        /** A pack that serves exactly these files (path under data/underground_city/ to text); the tests use it, the game uses the derivation below. */
        DerivedPack(Map<String, String> given) {
            given.forEach((path, text) -> files.put(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, path), text.getBytes(StandardCharsets.UTF_8)));
        }

        DerivedPack() {
            var vanilla = ServerPacksSource.createVanillaPackSource();
            try {
                boolean scarcity = net.minecraftforge.fml.ModList.get() != null && net.minecraftforge.fml.ModList.get().isLoaded("underground_city_scarcity");
                derive(path -> readVanilla(vanilla, path), scarcity).forEach((path, text) ->
                    files.put(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, path), text.getBytes(StandardCharsets.UTF_8)));
            } finally {
                vanilla.close();
            }
            LOGGER.info("[UC-WORLD] derived {} regional terrain files from the game's overworld functions{}", files.size(), net.minecraftforge.fml.ModList.get() != null && net.minecraftforge.fml.ModList.get().isLoaded("underground_city_scarcity") ? " (noise-based ore veins off: the scarcity package is installed)" : "");
        }

        @Override public IoSupplier<InputStream> getRootResource(String... elements) {
            if (elements.length == 1 && elements[0].equals(PACK_META)) {
                byte[] meta = "{\"pack\":{\"pack_format\":15,\"description\":\"Underground City archipelago terrain, derived from the game's own overworld functions\"}}".getBytes(StandardCharsets.UTF_8);
                return () -> new ByteArrayInputStream(meta);
            }
            return null;
        }

        @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
            byte[] bytes = type == PackType.SERVER_DATA ? files.get(location) : null;
            return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
        }

        @Override public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            if (type != PackType.SERVER_DATA) return;
            // a directory, not a string prefix: listing worldgen/noise must not return worldgen/noise_settings (that broke every world's registry load)
            String directory = path.isEmpty() || path.endsWith("/") ? path : path + "/";
            files.forEach((location, bytes) -> {
                if (location.getNamespace().equals(namespace) && location.getPath().startsWith(directory)) output.accept(location, () -> new ByteArrayInputStream(bytes));
            });
        }

        @Override public Set<String> getNamespaces(PackType type) { return type == PackType.SERVER_DATA ? Set.of(UndergroundCity.MOD_ID) : Set.of(); }

        @SuppressWarnings("unchecked")
        @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
            if (!serializer.getMetadataSectionName().equals("pack")) return null;
            JsonElement element = JsonParser.parseString("{\"pack_format\":15,\"description\":\"Underground City archipelago terrain\"}");
            return serializer.fromJson(element.getAsJsonObject());
        }

        @Override public String packId() { return PACK_ID; }
        @Override public boolean isBuiltin() { return true; }
        @Override public void close() {}
    }
}
