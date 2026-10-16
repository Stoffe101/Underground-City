package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

/**
 * Development worlds only ({@code -Duc.devBootstrap=true}, set by the Gradle development launch): in a brand-new world, builds Founders Row on level, dry
 * ground near the spawn point and moves the spawn to the lane's gate, so that a new player arrives at the district, gets the first-login
 * screen and can walk to the registrar. Never touches an existing world (game time above zero), never runs on a production server, and the
 * build is the same reversible one an operator would run ({@code /uc_district undo founders_row confirm}).
 */
public final class DevBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String DISTRICT = "founders_row";
    static final int SEARCH_RADIUS = 160, MAX_RELIEF = 3;

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("uc.devBootstrap")) return;
        ServerLevel level = event.getServer().overworld();
        if (level.getGameTime() > 100) { LOGGER.info("[UC-DEV] existing world: Founders Row is not placed automatically (use /uc_district build founders_row)"); return; }
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
            @Override public int height(int x, int z) { return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random); }
            @Override public boolean wet(int x, int z) {
                return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, random) > height(x, z) || height(x, z) <= level.getSeaLevel() + 1;
            }
            @Override public boolean reserved(int x, int z) {
                // Inside a city circle Lost Cities owns the ground; keep well clear of the circle and of the roads' region of influence.
                return CityRegions.INSTANCE.all().stream().anyMatch(city -> city.dimension().equals(level.dimension().location().toString())
                    && Math.hypot(x - city.centerX(), z - city.centerZ()) < city.radius() + 48);
            }
        };
    }

    static BlockPos place(ServerLevel level, DistrictCatalog.District district) {
        int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
        BlockPos spawn = level.getSharedSpawnPos();
        var site = DistrictSites.find(ground(level), spawn.getX(), spawn.getZ(), width, depth, MAX_RELIEF, SEARCH_RADIUS)
            .orElseThrow(() -> new IllegalStateException("no level, dry ground within " + SEARCH_RADIUS + " blocks of the spawn point"));
        BlockPos origin = new BlockPos(site.x(), site.y(), site.z());
        // The lot's chunks must exist before the builder looks at them; loading them here is the one-time cost of the first start.
        for (int cx = (origin.getX() + district.min().getX()) >> 4; cx <= (origin.getX() + district.max().getX()) >> 4; cx++) {
            for (int cz = (origin.getZ() + district.min().getZ()) >> 4; cz <= (origin.getZ() + district.max().getZ()) >> 4; cz++) level.getChunk(cx, cz);
        }
        DistrictBuilder.build(level, district, origin);
        // The gate is the middle of the lane's far (south) end; stand a few blocks outside it, facing the lane.
        BlockPos gate = origin.offset(12, 0, depth + 2);
        level.setDefaultSpawnPos(gate, 180.0f);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS).set(0, level.getServer()); // arrive exactly at the gate, not up to ten blocks off
        LOGGER.info("[UC-DEV] Founders Row built at {} (relief {}), spawn moved to the gate at {}", origin.toShortString(), site.relief(), gate.toShortString());
        return origin;
    }
}
