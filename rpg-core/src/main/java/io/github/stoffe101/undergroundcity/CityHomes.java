package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Homes and premises in the furnished cities (D-127). A furnished building's sidecar names its units (a flat, a house, a workshop: an interior box inside its walls); the property ladder's city
 * template for that kind of building gives the terms and the share of such buildings that are to let. Which units are to let is a pure function of the world seed, the building and the unit, so every
 * player sees the same street, and a unit that is to let has nobody living in it: the household of the building leaves it empty ({@link HouseholdService}), and no stash is placed in it.
 *
 * <p>A unit becomes an ordinary registered home ({@link CrewLedger.Residence}, id {@code city:<type>:<chunk>:<unit>}) the first time it matters: when a player near it opens the Home page, or
 * touches the door of its building. From then on it is rented, bought, billed, guarded and moved out of exactly like the Row's flats ({@link PropertyService}, {@link ResidenceGuard}): rent runs in
 * active household time only and a lapse never takes anything. A tenant of a flat holds a key to the building's street door (it opens for them as it would from inside, {@link BuildingGuard}); a
 * house's own door is in its shell and is the home's door.
 */
public final class CityHomes {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String PREFIX = "city:";
    /** How far round a player the Home page looks for units, in chunks, and how many Lost Cities lookups one look may make. */
    static final int RADIUS_CHUNKS = 4, LOOKUPS = 6;

    private static final List<String> VALEWOOD = List.of("Mill Lane", "Orchard Row", "Brook Street", "Elm Walk", "Church Lane", "Meadow Close", "Barley Road", "Willow Way", "Hayward Street", "Pond Lane");
    private static final List<String> IRONHAVEN = List.of("Foundry Street", "Coke Yard", "Rail Terrace", "Anvil Row", "Smelter Lane", "Gasworks Road", "Pit Street", "Chain Walk", "Boiler Street", "Rivet Lane");
    private static final List<String> BLACKWATER = List.of("Harbour Walk", "Quay Street", "Neon Row", "Tidewater Road", "Signal Street", "Dockside Lane", "Glass Avenue", "Pier Road", "Ferry Street", "Lantern Way");

    private CityHomes() {}

    // ------------------------------------------------------------------------------------------------ ids

    /** {@code city:<namespace>/<path>:<chunkX>_<chunkZ>:<unit>}. */
    static String id(CityBuildings.Building b, BuildingSpots.Unit u) { return PREFIX + b.type().replace(':', '/') + ":" + b.chunkX() + "_" + b.chunkZ() + ":" + u.id(); }

    /** The building type of a city unit's id ({@code namespace:path}), or empty for any other id. */
    static Optional<String> typeOf(String residenceId) {
        if (residenceId == null || !residenceId.startsWith(PREFIX)) return Optional.empty();
        String rest = residenceId.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        return colon <= 0 ? Optional.empty() : Optional.of(rest.substring(0, colon).replace('/', ':'));
    }

    /** The prefix every unit id of one building starts with. */
    static String buildingPrefix(CityBuildings.Building b) { return PREFIX + b.type().replace(':', '/') + ":" + b.chunkX() + "_" + b.chunkZ() + ":"; }

    // ------------------------------------------------------------------------------------------------ which units are to let

    /** 0 to 1, a pure function of the world seed, the building and the unit (its own salt, so it is independent of the stash and the alarm rolls). */
    static double roll(long seed, long buildingKey, int unit) {
        long z = buildingKey * 0x9E3779B97F4A7C15L + seed * 0xD1B54A32D192ED03L + (unit + 101) * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) / (double) (1L << 53);
    }

    /** True when unit {@code index} of this building is to let in this world. A single-unit building is let whole or not at all. */
    static boolean toLet(long seed, CityBuildings.Building b, int index) {
        Optional<PropertyCatalog.CityTemplate> template = PropertyCatalog.INSTANCE.cityTemplate(b.type());
        return template.isPresent() && roll(seed, b.key(), index) * 100.0 < template.get().sharePercent();
    }

    /** True when any unit of this building is to let in this world: part of it is somebody's home or premises, which the guard leaves to its tenant (D-127, D-169). */
    static boolean anyLet(long seed, CityBuildings.Building b) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        if (plan == null) return false;
        for (int i = 0; i < plan.units().size(); i++) if (toLet(seed, b, i)) return true;
        return false;
    }

    /** The unit of this building that contains a point of the building's own coordinates (x, z 0 to 15, y above the street), or -1. */
    static int unitAt(BuildingSpots.Plan plan, int x, int y, int z) {
        for (int i = 0; i < plan.units().size(); i++) if (plan.units().get(i).contains(x, y, z)) return i;
        return -1;
    }

    /** True when a spot of the building's plan lies in a unit that is to let: nobody of the household lives or works there (D-127). */
    static boolean spotLet(long seed, CityBuildings.Building b, BuildingSpots.Plan plan, BuildingSpots.Spot spot) {
        int unit = unitAt(plan, spot.x(), spot.y(), spot.z());
        return unit >= 0 && toLet(seed, b, unit);
    }

    /** True when an absolute position inside the building lies in one of its units that is to let. */
    static boolean letAt(ServerLevel level, CityBuildings.Building b, BlockPos pos) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        if (plan == null || plan.units().isEmpty()) return false;
        int unit = unitAt(plan, pos.getX() - b.chunkX() * 16, pos.getY() - b.streetY(), pos.getZ() - b.chunkZ() * 16);
        return unit >= 0 && toLet(level.getSeed(), b, unit);
    }

    // ------------------------------------------------------------------------------------------------ names

    static String street(String city, long h) {
        List<String> names = switch (city) { case "valewood" -> VALEWOOD; case "ironhaven" -> IRONHAVEN; default -> BLACKWATER; };
        return names.get((int) Math.floorMod(h >>> 9, (long) names.size()));
    }

    /** The building's street address ("12 Mill Lane"), the same in every run: every city building has one, whether or not anything in it is to let (D-157 lists places by it). */
    static String address(long seed, CityBuildings.Building b) {
        long h = PopulationPlan.mix(seed ^ b.key() * 31);
        return (1 + Math.floorMod(h >>> 3, 98L)) + " " + street(b.city(), h);
    }

    /** "12 Mill Lane" for a house, "Flat 3, 12 Mill Lane" for a flat: the same address for the same building in every run. */
    static String title(long seed, CityBuildings.Building b, BuildingSpots.Plan plan, BuildingSpots.Unit u) {
        String address = address(seed, b);
        String title = plan.units().size() > 1 ? u.title() + ", " + address : address;
        return title.length() > 40 ? title.substring(0, 40) : title;
    }

    // ------------------------------------------------------------------------------------------------ registration

    /** Registers the to-let units of one building that are not registered yet. Returns how many were registered now. */
    static int ensureBuilding(ServerLevel level, CityBuildings.Building b) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        Optional<PropertyCatalog.CityTemplate> template = PropertyCatalog.INSTANCE.cityTemplate(b.type());
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (plan == null || plan.units().isEmpty() || template.isEmpty() || ledger == null || ledger.faulted()) return 0;
        int registered = 0;
        String dimension = level.dimension().location().toString();
        for (int i = 0; i < plan.units().size(); i++) {
            if (!toLet(level.getSeed(), b, i)) continue;
            BuildingSpots.Unit u = plan.units().get(i);
            String id = id(b, u);
            if (ledger.residence(id) != null) continue;
            BlockPos min = new BlockPos(b.chunkX() * 16 + u.x0(), b.streetY() + u.y0(), b.chunkZ() * 16 + u.z0());
            BlockPos max = new BlockPos(b.chunkX() * 16 + u.x1(), b.streetY() + u.y1(), b.chunkZ() * 16 + u.z1());
            PropertyRules.Terms terms = template.get().terms();
            int price = terms.buyPrice() > 0 ? terms.buyPrice() : Math.max(1, terms.rent() * 20);
            int factor = PropertyService.cityFactor(level, new BlockPos((min.getX() + max.getX()) / 2, min.getY(), (min.getZ() + max.getZ()) / 2));
            try {
                ledger.registerResidence(id, title(level.getSeed(), b, plan, u), terms.category(), dimension, "city", min, max, price, 0, factor);
                registered++;
            } catch (RuntimeException error) {
                LOGGER.warn("[UC] city unit {} could not be registered: {}", id, error.getMessage());
            }
        }
        return registered;
    }

    /** Registers the to-let units of the furnished buildings round a player (loaded chunks only; a bounded number of new Lost Cities lookups). */
    static int ensureNear(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (PropertyCatalog.INSTANCE.cityTemplates().isEmpty() || BuildingSpots.INSTANCE.size() == 0) return 0;
        ChunkPos here = player.chunkPosition();
        int budget = LOOKUPS, registered = 0;
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
            int cx = here.x + dx, cz = here.z + dz;
            if (level.getChunkSource().getChunkNow(cx, cz) == null || !CityBuildings.INSTANCE.askable(level, cx, cz)) continue;   // never waits on Lost Cities (D-167)
            if (!CityBuildings.INSTANCE.known(cx, cz) && budget-- <= 0) continue;
            Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
            if (b.isPresent()) registered += ensureBuilding(level, b.get());
        }
        return registered;
    }

    // ------------------------------------------------------------------------------------------------ keys

    /** How many units of this building a player or a Crew holds: their homes, which no burglary of the building reaches (D-157 says so at the door). */
    static int heldUnits(net.minecraft.server.MinecraftServer server, CityBuildings.Building b) {
        CrewLedger ledger = CrewService.ledger(server);
        if (ledger == null || ledger.faulted()) return 0;
        String prefix = buildingPrefix(b);
        int n = 0;
        for (CrewLedger.Residence r : ledger.allResidences()) if (r.id.startsWith(prefix) && r.owned()) n++;
        return n;
    }

    /** True when the player lives in (holds the door rights of) a unit of this building that somebody holds: they have a key to its street doors. */
    static boolean holdsKey(ServerPlayer player, CityBuildings.Building b) {
        CrewLedger ledger = CrewService.ledger(player.server);
        if (ledger == null || ledger.faulted()) return false;
        String prefix = buildingPrefix(b);
        UUID uuid = player.getUUID();
        for (CrewLedger.Residence r : ledger.allResidences()) {
            if (!r.id.startsWith(prefix) || !r.owned()) continue;
            if (ResidenceGuard.allowed(ledger, r, uuid, r.dimension, r.min, ResidenceGuard.Act.DOOR)) return true;
        }
        return false;
    }
}
