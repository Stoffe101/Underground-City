package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
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
 *
 * <p>A property follows the dwelling, not the chunk (D-206). A part of a multi-chunk building lets what {@link CityDwellings} says that building holds: a house over two chunks is one home
 * whose boxes lie in both, a block of flats one home a flat with its corridors and stairs in none. Such a home's id names the multi-chunk building and its corner, so every chunk of it agrees
 * on it; a multi-chunk building with no dwellings lets nothing, and a part's own units are never let one chunk at a time. A door belongs to the home whose interior lies just inside it; any
 * other door of the building (a shared entrance, a corridor door) opens for every tenant of the building.
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

    /** {@code city:<namespace>/<multi>:<cornerX>_<cornerZ>:<dwelling>}: the same id from every chunk of the building. */
    static String dwellingId(CityBuildings.Building b, CityDwellings.Dwelling d) {
        return PREFIX + b.multi().id().replace(':', '/') + ":" + b.cornerX() + "_" + b.cornerZ() + ":" + d.id();
    }

    // ------------------------------------------------------------------------------------------------ places

    /**
     * A home or premises that can be let, with its interior in world coordinates: a unit of a single lot, or a dwelling of the multi-chunk building a chunk is part of (all of its rooms, in
     * every chunk of it). {@code index} is its place in its plan or dwellings file, which the roll that lets it uses.
     */
    record Place(String id, String title, int index, List<CrewLedger.Box> boxes) {
        boolean contains(BlockPos p) { for (CrewLedger.Box b : boxes) if (b.inside(p)) return true; return false; }
        boolean inShell(BlockPos p) { for (CrewLedger.Box b : boxes) if (b.inShell(p)) return true; return false; }
    }

    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The key the city templates are under for this building: the multi-chunk building's id for a part of one (its dwellings are let on its terms), the lot's own type otherwise. */
    static String templateKey(CityBuildings.Building b) { return b.multiPart() ? b.multi().id() : b.type(); }

    /** The places of a building: a single lot's units, a multi-chunk building's dwellings (every one of them, wherever its rooms lie), or none (D-206). */
    static List<Place> places(long seed, CityBuildings.Building b) {
        if (b.multiPart()) {
            CityDwellings.Building d = CityDwellings.INSTANCE.forMulti(b.multi().id());
            if (d == null) return List.of();
            if (d.w() != b.multi().w() || d.h() != b.multi().h() || !d.part(b.multi().x(), b.multi().z()).equals(b.type())) {
                if (WARNED.add(b.multi().id())) LOGGER.warn("[UC] {} has dwellings for another layout than the one Lost Cities built; nothing in it is let", b.multi().id());
                return List.of();
            }
            List<Place> out = new ArrayList<>();
            String address = address(seed, b);
            for (int i = 0; i < d.dwellings().size(); i++) {
                CityDwellings.Dwelling dw = d.dwellings().get(i);
                List<CrewLedger.Box> boxes = new ArrayList<>();
                for (CityDwellings.Room r : dw.rooms()) {
                    int ox = (b.cornerX() + r.x()) * 16, oz = (b.cornerZ() + r.z()) * 16;
                    boxes.add(new CrewLedger.Box(new BlockPos(ox + r.x0(), b.streetY() + r.y0(), oz + r.z0()), new BlockPos(ox + r.x1(), b.streetY() + r.y1(), oz + r.z1())));
                }
                String title = d.dwellings().size() > 1 ? dw.title() + ", " + address : address;
                out.add(new Place(dwellingId(b, dw), title.length() > 40 ? title.substring(0, 40) : title, i, List.copyOf(boxes)));
            }
            return out;
        }
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        if (plan == null || plan.units().isEmpty()) return List.of();
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < plan.units().size(); i++) {
            BuildingSpots.Unit u = plan.units().get(i);
            List<CrewLedger.Box> boxes = new ArrayList<>();
            for (BuildingSpots.Box x : u.boxes()) {
                boxes.add(new CrewLedger.Box(new BlockPos(b.chunkX() * 16 + x.x0(), b.streetY() + x.y0(), b.chunkZ() * 16 + x.z0()), new BlockPos(b.chunkX() * 16 + x.x1(), b.streetY() + x.y1(), b.chunkZ() * 16 + x.z1())));
            }
            out.add(new Place(id(b, u), title(seed, b, plan, u), i, List.copyOf(boxes)));
        }
        return out;
    }

    /**
     * Whether every chunk of a multi-chunk building that Core already knows is the part the dwellings file names for its place, of the same building with the same corner (D-206): a
     * home is registered across chunks only when they agree. A chunk not known yet is asked of Lost Cities when that costs nothing now; one that cannot be asked defers the registration.
     */
    static boolean instanceAgrees(ServerLevel level, CityBuildings.Building b) {
        if (!b.multiPart()) return true;
        CityDwellings.Building d = CityDwellings.INSTANCE.forMulti(b.multi().id());
        if (d == null) return false;
        for (int x = 0; x < d.w(); x++) for (int z = 0; z < d.h(); z++) {
            int cx = b.cornerX() + x, cz = b.cornerZ() + z;
            if (cx == b.chunkX() && cz == b.chunkZ()) continue;
            if (!CityBuildings.INSTANCE.known(cx, cz) && !CityBuildings.INSTANCE.askable(level, cx, cz)) return false;
            Optional<CityBuildings.Building> other = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
            if (other.isEmpty() || !other.get().multiPart() || !other.get().multi().id().equals(b.multi().id()) || other.get().cornerX() != b.cornerX() || other.get().cornerZ() != b.cornerZ()
                || !other.get().type().equals(d.part(x, z))) {
                if (WARNED.add("parts " + b.multi().id() + " " + b.cornerX() + " " + b.cornerZ())) {
                    LOGGER.warn("[UC] the chunks of {} at corner {} {} are not the parts its dwellings name; nothing in it is let", b.multi().id(), b.cornerX(), b.cornerZ());
                }
                return false;
            }
        }
        return true;
    }

    /** The building type of a city unit's id ({@code namespace:path}), or empty for any other id. */
    static Optional<String> typeOf(String residenceId) {
        if (residenceId == null || !residenceId.startsWith(PREFIX)) return Optional.empty();
        String rest = residenceId.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        return colon <= 0 ? Optional.empty() : Optional.of(rest.substring(0, colon).replace('/', ':'));
    }

    /** The prefix every unit id of one building starts with: for a part of a multi-chunk building, of the whole building (D-206). */
    static String buildingPrefix(CityBuildings.Building b) {
        if (b.multiPart()) return PREFIX + b.multi().id().replace(':', '/') + ":" + b.cornerX() + "_" + b.cornerZ() + ":";
        return PREFIX + b.type().replace(':', '/') + ":" + b.chunkX() + "_" + b.chunkZ() + ":";
    }

    // ------------------------------------------------------------------------------------------------ which units are to let

    /** 0 to 1, a pure function of the world seed, the building and the unit (its own salt, so it is independent of the stash and the alarm rolls). */
    static double roll(long seed, long buildingKey, int unit) {
        long z = buildingKey * 0x9E3779B97F4A7C15L + seed * 0xD1B54A32D192ED03L + (unit + 101) * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) / (double) (1L << 53);
    }

    /**
     * True when place {@code index} of this building (a unit of a lot, a dwelling of a multi-chunk building) is to let in this world. A single-unit building is let whole or not at all; the
     * dwellings of a multi-chunk building are rolled from the building's corner, so every chunk of it agrees.
     */
    static boolean toLet(long seed, CityBuildings.Building b, int index) {
        Optional<PropertyCatalog.CityTemplate> template = PropertyCatalog.INSTANCE.cityTemplate(templateKey(b));
        if (LET_ALL_FOR_PROBE) return template.isPresent();
        return template.isPresent() && roll(seed, b.multiPart() ? b.instanceKey() : b.key(), index) * 100.0 < template.get().sharePercent();
    }

    /** A disposable probe world's switch (-PucCpLetall=true, run_city_probe.py --let-all): every place with a template is to let, so the probe registers all of them. */
    static final boolean LET_ALL_FOR_PROBE = Boolean.getBoolean("uc.cityProbe.letall");

    /** True when any place of this building is to let in this world: part of it is somebody's home or premises, which the guard leaves to its tenant (D-127, D-169). */
    static boolean anyLet(long seed, CityBuildings.Building b) {
        for (Place place : places(seed, b)) if (toLet(seed, b, place.index())) return true;
        return false;
    }

    /** The unit of this building that contains a point of the building's own coordinates (x, z 0 to 15, y above the street), or -1. */
    static int unitAt(BuildingSpots.Plan plan, int x, int y, int z) {
        for (int i = 0; i < plan.units().size(); i++) if (plan.units().get(i).contains(x, y, z)) return i;
        return -1;
    }

    /** True when a spot of the building's plan lies in a place that is to let: nobody of the household lives or works there (D-127). */
    static boolean spotLet(long seed, CityBuildings.Building b, BuildingSpots.Plan plan, BuildingSpots.Spot spot) {
        return letAt(seed, b, new BlockPos(b.chunkX() * 16 + spot.x(), b.streetY() + spot.y(), b.chunkZ() * 16 + spot.z()));
    }

    /** True when an absolute position inside the building lies in one of its places that is to let. */
    static boolean letAt(ServerLevel level, CityBuildings.Building b, BlockPos pos) { return letAt(level.getSeed(), b, pos); }

    static boolean letAt(long seed, CityBuildings.Building b, BlockPos pos) {
        for (Place place : places(seed, b)) if (place.contains(pos)) return toLet(seed, b, place.index());
        return false;
    }

    /**
     * The place whose own door this is (D-206): the place whose interior is just inside the door and not just outside it, or null for a door that belongs to none (a shared entrance,
     * a corridor door), which opens for every tenant of the building.
     */
    static Place placeOfDoor(long seed, CityBuildings.Building b, BlockPos lower, net.minecraft.world.level.block.state.BlockState door) {
        if (!(door.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) return null;
        net.minecraft.core.Direction facing = door.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
        BlockPos front = lower.relative(facing), back = lower.relative(facing.getOpposite());
        for (Place place : places(seed, b)) {
            boolean f = place.contains(front), k = place.contains(back);
            if (f != k) return place;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ names

    static String street(String city, long h) {
        List<String> names = switch (city) { case "valewood" -> VALEWOOD; case "ironhaven" -> IRONHAVEN; default -> BLACKWATER; };
        return names.get((int) Math.floorMod(h >>> 9, (long) names.size()));
    }

    /** The building's street address ("12 Mill Lane"), the same in every run: every city building has one, whether or not anything in it is to let (D-157 lists places by it). */
    static String address(long seed, CityBuildings.Building b) {
        long h = PopulationPlan.mix(seed ^ b.instanceKey() * 31);
        return (1 + Math.floorMod(h >>> 3, 98L)) + " " + street(b.city(), h);
    }

    /** "12 Mill Lane" for a house, "Flat 3, 12 Mill Lane" for a flat: the same address for the same building in every run. */
    static String title(long seed, CityBuildings.Building b, BuildingSpots.Plan plan, BuildingSpots.Unit u) {
        String address = address(seed, b);
        String title = plan.units().size() > 1 ? u.title() + ", " + address : address;
        return title.length() > 40 ? title.substring(0, 40) : title;
    }

    // ------------------------------------------------------------------------------------------------ registration

    /**
     * Registers the to-let places of one building that are not registered yet. Returns how many were registered now. A dwelling of a multi-chunk building is registered with its rooms in
     * every chunk of it, once those chunks agree that they are its parts ({@link #instanceAgrees}).
     */
    static int ensureBuilding(ServerLevel level, CityBuildings.Building b) {
        Optional<PropertyCatalog.CityTemplate> template = PropertyCatalog.INSTANCE.cityTemplate(templateKey(b));
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (template.isEmpty() || ledger == null || ledger.faulted()) return 0;
        List<Place> places = places(level.getSeed(), b);
        if (places.isEmpty()) return 0;
        boolean pending = false;
        for (Place place : places) if (toLet(level.getSeed(), b, place.index()) && ledger.residence(place.id()) == null) pending = true;
        if (!pending || !instanceAgrees(level, b)) return 0;
        int registered = 0;
        String dimension = level.dimension().location().toString();
        for (Place place : places) {
            if (!toLet(level.getSeed(), b, place.index()) || ledger.residence(place.id()) != null) continue;
            PropertyRules.Terms terms = template.get().terms();
            int price = terms.buyPrice() > 0 ? terms.buyPrice() : Math.max(1, terms.rent() * 20);
            CrewLedger.Box first = place.boxes().get(0);
            int factor = PropertyService.cityFactor(level, new BlockPos((first.min().getX() + first.max().getX()) / 2, first.min().getY(), (first.min().getZ() + first.max().getZ()) / 2));
            try {
                ledger.registerResidence(place.id(), place.title(), terms.category(), dimension, "city", place.boxes(), price, 0, factor);
                registered++;
            } catch (RuntimeException error) {
                LOGGER.warn("[UC] city unit {} could not be registered: {}", place.id(), error.getMessage());
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

    /**
     * What a vacant listing's locked street door tells a passer-by (D-188), or null when nothing of the building is to let and free: "To let: 12 Mill Lane." for a house, "Flats to let at 12 Mill
     * Lane." for a block with a free flat. Nobody is let in by it; the Home page leases the place.
     */
    static String listing(ServerLevel level, CityBuildings.Building b) {
        List<Place> places = places(level.getSeed(), b);
        if (places.isEmpty()) return null;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        int free = 0;
        for (Place place : places) {
            if (!toLet(level.getSeed(), b, place.index())) continue;
            CrewLedger.Residence r = ledger == null ? null : ledger.residence(place.id());
            if (r == null || !r.owned()) free++;
        }
        if (free == 0) return null;
        String where = address(level.getSeed(), b);
        return (places.size() > 1 ? "Flats to let at " + where : "To let: " + where) + ". Locked until you hold the lease: press K, Home, Find a home.";
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

    /**
     * True when the player may open this door of the building with a key (D-206): a door that is a place's own (its interior just inside) opens for whoever holds that place's door
     * rights; any other door of the building, a shared entrance or a corridor's, for every tenant of the building (D-127).
     */
    static boolean holdsKey(ServerPlayer player, CityBuildings.Building b, BlockPos lower, net.minecraft.world.level.block.state.BlockState door) {
        Place own = placeOfDoor(player.serverLevel().getSeed(), b, lower, door);
        if (own == null) return holdsKey(player, b);
        CrewLedger ledger = CrewService.ledger(player.server);
        if (ledger == null || ledger.faulted()) return false;
        CrewLedger.Residence r = ledger.residence(own.id());
        return r != null && r.owned() && ResidenceGuard.allowed(ledger, r, player.getUUID(), r.dimension, r.min, ResidenceGuard.Act.DOOR);
    }

    /** True when the player lives in (holds the door rights of) a unit of this building that somebody holds: they have a key to its shared street doors. */
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
