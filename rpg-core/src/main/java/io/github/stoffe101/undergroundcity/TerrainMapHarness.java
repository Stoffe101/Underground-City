package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opt-in terrain survey ({@code -Duc.terrainMap=<csv file>}, disposable dedicated server only): asks the world's own chunk generator and biome source, with no chunk
 * generated and no structure placed, for the ocean-floor height, the surface height and the biome of every column on a grid, and writes them as CSV. It is the cheap,
 * honest half of the world evidence (real noise, real climate sampler, real biome tables, not a geometry sketch); {@code scripts/render_terrain_map.py} draws it, and the
 * real client frames and the block survey are the other half. Region, step and the file are properties: {@code uc.terrainMap.x0|x1|z0|z1|step}.
 */
public final class TerrainMapHarness {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT = System.getProperty("uc.terrainMap");

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank()) return;
        var server = event.getServer();
        ServerLevel level = server.overworld();
        int x0 = Integer.getInteger("uc.terrainMap.x0", -900), x1 = Integer.getInteger("uc.terrainMap.x1", 2500);
        int z0 = Integer.getInteger("uc.terrainMap.z0", -900), z1 = Integer.getInteger("uc.terrainMap.z1", 2300);
        int step = Integer.getInteger("uc.terrainMap.step", 16);
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        var sampler = random.sampler();
        var biomeSource = generator.getBiomeSource();
        long started = System.nanoTime();
        long columns = 0;
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(OUTPUT), StandardCharsets.UTF_8)) {
            out.write("# generator=" + generator.getClass().getName() + " seed=" + server.getWorldData().worldGenOptions().seed() + "\n");
            out.write("x,z,floor,surface,biome\n");
            for (int z = z0; z <= z1; z += step) {
                for (int x = x0; x <= x1; x += step) {
                    int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
                    int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random);
                    var biome = biomeSource.getNoiseBiome(x >> 2, Math.max(level.getMinBuildHeight() >> 2, (surface - 1) >> 2), z >> 2, sampler);
                    out.write(x + "," + z + "," + floor + "," + surface + "," + biome.unwrapKey().map(key -> key.location().toString()).orElse("?") + "\n");
                    columns++;
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot write the terrain map", error);
        }
        LOGGER.info("[UC-TERRAIN] {} columns sampled in {} ms, written to {} (spawn {})", columns, (System.nanoTime() - started) / 1_000_000, OUTPUT, level.getSharedSpawnPos().toShortString());
        server.halt(false);
    }
}
