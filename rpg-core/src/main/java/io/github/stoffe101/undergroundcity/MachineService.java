package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The cities' machinery (eighth pass, the Smith's engineering; design in docs/design/smith-engineer.md). Every industrial building and the shops that run on a machine ({@code machinery/rules.json})
 * have a <b>drive</b>: in a new archipelago world Core sets a drive housing into the wall beside the street door the first time somebody comes near; in any other world the street door stands in for
 * it, as for the Technician's panel.
 *
 * <ul>
 * <li><b>Breakdowns</b> come now and then to a city somebody is in ({@link #MAX_OPEN} at a time per city, one every {@link #GAP_MIN} to {@link #GAP_MAX} of active play). The machine stops and
 * smokes; Smiths in the city get a service call. Nobody is punished for not coming: after {@link #WAIT_TICKS} the building's own fitter puts it right.</li>
 * <li><b>While a machine is down</b>, the shop it keeps supplied runs short (its lines cost {@code shortage_percent} more, {@link #shortage}); an industrial building simply stands idle.</li>
 * <li><b>A machine a player puts right</b> ({@link SmithWork}) runs well for {@link #RUNS_WELL_TICKS} (no breakdown there), and an industrial building runs a catch-up shift: its city's exchange
 * lines of metal goods are topped up by a share of their capacity, once.</li>
 * </ul>
 *
 * <p>Bounded like the Technician's service: a couple of machines recorded near each player per pass, never a chunk read that is not loaded, never a wait on Lost Cities.
 */
public final class MachineService {
    private static final Logger LOGGER = LogUtils.getLogger();

    static final int MAX_OPEN = 2, CYCLE = 100, MACHINES_PER_PLAYER = 2, NEAR_CHUNKS = 2;
    /** Active ticks: how long a breakdown waits for a player, the gap between breakdowns in a city, how long a machine a player put right runs well. */
    static final long WAIT_TICKS = 25 * 1200L, GAP_MIN = 10 * 1200L, GAP_MAX = 16 * 1200L, RUNS_WELL_TICKS = 120 * 1200L;

    MachineService() {}

    // ------------------------------------------------------------------------------------------------ the rules (data)

    record Rules(Map<ResourceLocation, String[]> buildings, List<String> industrial, Set<ResourceLocation> restockItems, double restockShare, int shortagePercent) {
        static final Rules EMPTY = new Rules(Map.of(), List.of("conveyor"), Set.of(), 0.25, 20);
    }

    static volatile Rules RULES = Rules.EMPTY;

    /** Reads {@code data/underground_city/machinery/rules.json}. */
    static final class Loader extends SimpleJsonResourceReloadListener {
        static final Loader INSTANCE = new Loader();
        private Loader() { super(new Gson(), "machinery"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            JsonElement element = data.get(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "rules"));
            if (element == null) { RULES = Rules.EMPTY; return; }
            RULES = parse(element.getAsJsonObject());
            LOGGER.info("Loaded the machinery rules: {} shop machines, {} industrial kinds", RULES.buildings().size(), RULES.industrial().size());
        }
    }

    static Rules parse(JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported machinery schema");
        Map<ResourceLocation, String[]> buildings = new LinkedHashMap<>();
        for (JsonElement e : json.getAsJsonArray("buildings")) {
            JsonObject b = e.getAsJsonObject();
            String shop = b.get("shop").getAsString();
            if (!RegionalStock.isShop(shop)) throw new IllegalArgumentException("A machine's shop must be an outlet like shop:name: " + shop);
            buildings.put(ResourceLocation.parse(b.get("building").getAsString()), new String[] {b.get("machine").getAsString(), shop});
        }
        List<String> industrial = new ArrayList<>();
        for (JsonElement e : json.getAsJsonArray("industrial")) industrial.add(e.getAsString());
        if (industrial.isEmpty()) throw new IllegalArgumentException("The machinery rules name no industrial machine");
        Set<ResourceLocation> restock = new java.util.LinkedHashSet<>();
        for (JsonElement e : json.getAsJsonArray("restock_items")) restock.add(ResourceLocation.parse(e.getAsString()));
        double share = json.get("restock_share").getAsDouble();
        int shortage = json.get("shortage_percent").getAsInt();
        if (share <= 0 || share > 1 || shortage < 0 || shortage > 100) throw new IllegalArgumentException("Invalid machinery restock share or shortage");
        return new Rules(Map.copyOf(buildings), List.copyOf(industrial), Set.copyOf(restock), share, shortage);
    }

    // ------------------------------------------------------------------------------------------------ the record

    /** A building's machine: its drive housing (or {@code null} where the street door stands in for it), its street door, what it is, and the shop it keeps supplied ("" for an industrial building). */
    record Machine(long key, BlockPos pos, BlockPos door, String city, int tier, String name, String shop, String label, String address) {
        BlockPos access() { return pos != null ? pos : door; }
        boolean industrial() { return shop.isEmpty(); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("key", key); if (pos != null) t.putLong("pos", pos.asLong()); t.putLong("door", door.asLong()); t.putString("city", city); t.putInt("tier", tier);
            t.putString("name", name); t.putString("shop", shop); t.putString("label", label); t.putString("address", address);
            return t;
        }

        static Machine load(CompoundTag t) {
            return new Machine(t.getLong("key"), t.contains("pos") ? BlockPos.of(t.getLong("pos")) : null, BlockPos.of(t.getLong("door")), t.getString("city"), t.getInt("tier"),
                t.getString("name"), t.getString("shop"), t.getString("label"), t.getString("address"));
        }
    }

    /** An open breakdown, a service call. */
    record Breakdown(UUID id, long key, long since) {
        CompoundTag save() { CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putLong("key", key); t.putLong("since", since); return t; }
        static Breakdown load(CompoundTag t) { return new Breakdown(t.getUUID("id"), t.getLong("key"), t.getLong("since")); }
    }

    static Works works(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Works::load, Works::new, Works.DATA_KEY); }

    /** Every building's machine, the open breakdowns, the machines running well after a player's repair, and when each city's next breakdown is due. Saved. */
    static final class Works extends SavedData {
        static final String DATA_KEY = "underground_city_machinery";
        final Map<Long, Machine> machines = new LinkedHashMap<>();
        final Map<Long, Breakdown> breakdowns = new LinkedHashMap<>();
        final Map<Long, Long> runsWell = new HashMap<>();
        final Map<String, Long> next = new HashMap<>();
        long repairs, expired;

        static Works load(CompoundTag root) {
            Works w = new Works();
            for (Tag t : root.getList("machines", Tag.TAG_COMPOUND)) { Machine m = Machine.load((CompoundTag) t); w.machines.put(m.key(), m); }
            for (Tag t : root.getList("breakdowns", Tag.TAG_COMPOUND)) { Breakdown b = Breakdown.load((CompoundTag) t); w.breakdowns.put(b.key(), b); }
            CompoundTag well = root.getCompound("runs_well"), due = root.getCompound("next");
            for (String k : well.getAllKeys()) w.runsWell.put(Long.parseLong(k), well.getLong(k));
            for (String k : due.getAllKeys()) w.next.put(k, due.getLong(k));
            w.repairs = root.getLong("repairs"); w.expired = root.getLong("expired");
            return w;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag m = new ListTag(), b = new ListTag();
            machines.values().forEach(x -> m.add(x.save()));
            breakdowns.values().forEach(x -> b.add(x.save()));
            root.put("machines", m); root.put("breakdowns", b);
            CompoundTag well = new CompoundTag(), due = new CompoundTag();
            runsWell.forEach((k, v) -> well.putLong(Long.toString(k), v));
            next.forEach(due::putLong);
            root.put("runs_well", well); root.put("next", due);
            root.putLong("repairs", repairs); root.putLong("expired", expired);
            return root;
        }
    }

    // ------------------------------------------------------------------------------------------------ what other systems ask

    /** True while a building's machine is broken down. */
    static boolean broken(MinecraftServer server, long key) { return works(server).breakdowns.containsKey(key); }

    /** True while a machine a player put right runs well (no breakdown comes to it). */
    static boolean runsWell(MinecraftServer server, long key) {
        Long until = works(server).runsWell.get(key);
        return until != null && LockService.clock(server) < until;
    }

    /** True while the machine of the building that keeps {@code outlet} supplied in {@code city} is broken down: the shop runs short and its lines cost more ({@link RegionalMarket}). */
    static boolean shortage(MinecraftServer server, String outlet, String city) {
        if (server == null || outlet == null || !RegionalStock.isShop(outlet)) return false;
        Works w = works(server);
        for (Breakdown b : w.breakdowns.values()) {
            Machine m = w.machines.get(b.key());
            if (m != null && outlet.equals(m.shop()) && m.city().equals(city)) return true;
        }
        return false;
    }

    /** The shops running short now, as "city|outlet": refreshed by every pass of the service and every change of a breakdown, read by the price of a shop line ({@link RegionalMarket}). */
    private static volatile Set<String> SHORT = Set.of();

    /** True while a shop line's shop runs short (a shop line belongs to one city and is sold at its shop outlets). */
    static boolean short_(RegionalStock stock) {
        Set<String> now = SHORT;
        if (now.isEmpty()) return false;
        for (String outlet : stock.outlets()) if (now.contains(stock.city() + "|" + outlet)) return true;
        return false;
    }

    /** The cities whose works stand idle now (an industrial machine broken down), refreshed with {@link #SHORT}. */
    private static volatile Set<String> STALLED = Set.of();

    /**
     * True while a line's city has a works standing idle and the line is one of the goods its works make ({@code restock_items}): the line gains nothing in active time until the machine runs
     * again ({@link WorkOrderLedger#regionalUnits}). What it had gained before the breakdown was banked when the breakdown opened.
     */
    static boolean stalled(RegionalStock stock) {
        Set<String> now = STALLED;
        return !now.isEmpty() && now.contains(stock.city()) && RULES.restockItems().contains(stock.item());
    }

    /** The lines a works of {@code city} keeps supplied: the ones it stops while idle and tops up after a repair. */
    static List<RegionalStock> worksLines(String city) {
        List<RegionalStock> out = new ArrayList<>();
        for (RegionalStock stock : RegionalCatalog.INSTANCE.forCity(city)) if (RULES.restockItems().contains(stock.item())) out.add(stock);
        return out;
    }

    /** Rebuilds {@link #SHORT} and {@link #STALLED} from the open breakdowns. */
    static void refreshShort(MinecraftServer server) {
        Works w = works(server);
        Set<String> next = new java.util.HashSet<>(), idle = new java.util.HashSet<>();
        for (Breakdown b : w.breakdowns.values()) {
            Machine m = w.machines.get(b.key());
            if (m == null) continue;
            if (m.industrial()) idle.add(m.city());
            else next.add(m.city() + "|" + m.shop());
        }
        SHORT = Set.copyOf(next);
        STALLED = Set.copyOf(idle);
    }

    /** Forgets the shops running short and the works standing idle (a server stopping, a test). */
    static void clearShort() { SHORT = Set.of(); STALLED = Set.of(); }

    /** A price with the shortage mark-up applied (rounded, at least 1). */
    static int marked(int price) { return Math.max(1, (int) Math.round(price * (100 + RULES.shortagePercent()) / 100.0)); }

    // ------------------------------------------------------------------------------------------------ machines in the world

    /** What a building's machine is called and the shop it supplies, or null when the building runs on none. */
    static String[] machineFor(CityBuildings.Building b) {
        String[] shop = RULES.buildings().get(ResourceLocation.parse(b.type()));
        if (shop != null) return shop;
        String category = b.security() == null ? "public" : CityBuildings.INSTANCE.category(b.security().id());
        if (!"industrial".equals(category)) return null;
        List<String> kinds = RULES.industrial();
        return new String[] {kinds.get(Math.floorMod(Long.hashCode(b.key() * 0x9E3779B97F4A7C15L), kinds.size())), ""};
    }

    /** Whether this world places drive housings: a new archipelago world (any older world keeps its buildings as they are and uses the street door). */
    static boolean placesMachines(ServerLevel level) { return TechService.placesPanels(level); }

    /**
     * Where a drive housing can go beside a street door: a whole, solid block of the building's wall at the height of the door's lower half, on either side, inside the building's own chunk, with
     * open air in front of it on the street side. Null when there is none (the door is then the access point).
     */
    static BlockPos machineSpot(ServerLevel level, CityBuildings.Building b, TechService.StreetDoor door) {
        for (Direction side : new Direction[] {door.out().getCounterClockWise(), door.out().getClockWise()}) {
            BlockPos spot = door.lower().relative(side);
            if (!b.contains(spot)) continue;
            BlockState wall = level.getBlockState(spot);
            if (!wall.isSolidRender(level, spot) || wall.hasBlockEntity() || wall.getBlock() instanceof DoorBlock) continue;
            if (!level.getBlockState(spot.relative(door.out())).isAir() || !level.getBlockState(spot.relative(door.out()).above()).isAir()) continue;
            return spot.immutable();
        }
        return null;
    }

    /** Records (and in a new archipelago world sets in place) the machine of a building somebody is near. Returns whether anything was done. */
    static boolean ensureMachine(ServerLevel level, CityBuildings.Building b) {
        Works w = works(level.getServer());
        if (w.machines.containsKey(b.key())) return false;
        String[] what = machineFor(b);
        if (what == null || BuildingGuard.protectedAt(level, new BlockPos(b.chunkX() * 16 + 8, b.streetY(), b.chunkZ() * 16 + 8)).isEmpty()) return false;
        Optional<TechService.StreetDoor> door = TechService.streetDoor(level, b);
        if (door.isEmpty()) return false;
        BlockPos spot = placesMachines(level) ? machineSpot(level, b, door.get()) : null;
        if (spot != null) level.setBlock(spot, UndergroundCity.MACHINE_DRIVE.get().defaultBlockState().setValue(MachineBlock.FACING, door.get().out()), 3);
        w.machines.put(b.key(), new Machine(b.key(), spot, door.get().lower(), b.city(), Math.max(1, Thievery.tier(b)), what[0], what[1], Thievery.label(b), CityHomes.address(level.getSeed(), b)));
        w.setDirty();
        return true;
    }

    /** Shows whether a machine runs on its housing (when the housing's chunk is loaded). */
    static void show(ServerLevel level, Machine m, boolean running) {
        if (m.pos() == null || !level.isLoaded(m.pos())) return;
        BlockState here = level.getBlockState(m.pos());
        if (here.getBlock() instanceof MachineBlock && here.getValue(MachineBlock.RUNNING) != running) level.setBlock(m.pos(), here.setValue(MachineBlock.RUNNING, running), 3);
    }

    // ------------------------------------------------------------------------------------------------ the clock

    private static int clock;

    @SubscribeEvent
    public void stopping(net.minecraftforge.event.server.ServerStoppingEvent event) { clearShort(); }

    /** The shops short and the works idle from the saved breakdowns, before a line is read after a restart. */
    @SubscribeEvent
    public void started(net.minecraftforge.event.server.ServerStartedEvent event) { refreshShort(event.getServer()); }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++clock % CYCLE != 37 % CYCLE) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null || !WorldLayout.regional(level.getChunkSource().getGenerator())) return;
        try { look(server, level); }
        catch (RuntimeException error) { LOGGER.error("[UC] the machinery service failed a pass", error); }
    }

    /** One pass: machines near players, breakdowns in the cities somebody is in, and the end of old ones. */
    static void look(MinecraftServer server, ServerLevel level) {
        Works w = works(server);
        long now = LockService.clock(server);
        Map<String, Integer> playersIn = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer || player.level() != level || player.isSpectator()) continue;
            CityRegions.INSTANCE.cityAt(level, player.blockPosition()).ifPresent(city -> playersIn.merge(city, 1, Integer::sum));
            int placed = 0;
            int cx = player.chunkPosition().x, cz = player.chunkPosition().z;
            for (int dx = -NEAR_CHUNKS; dx <= NEAR_CHUNKS && placed < MACHINES_PER_PLAYER; dx++) for (int dz = -NEAR_CHUNKS; dz <= NEAR_CHUNKS && placed < MACHINES_PER_PLAYER; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null || !CityBuildings.INSTANCE.known(cx + dx, cz + dz) && dx * dx + dz * dz > 2
                    || !CityBuildings.INSTANCE.askable(level, cx + dx, cz + dz)) continue;   // never waits on Lost Cities (D-167)
                Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz);
                if (b.isPresent() && ensureMachine(level, b.get())) placed++;
            }
        }
        // a breakdown nobody came to is put right by the building's own fitter
        for (Iterator<Breakdown> it = w.breakdowns.values().iterator(); it.hasNext(); ) {
            Breakdown b = it.next();
            if (now - b.since() < WAIT_TICKS) continue;
            it.remove();
            w.expired++;
            Machine m = w.machines.get(b.key());
            if (m != null && m.industrial()) WorkOrderLedger.get(server).bank(worksLines(m.city()));   // still idle until refreshShort: the lines' clock restarts from now, with nothing gained
            if (m != null) show(level, m, true);
            Navigation.finished(server, "machine", b.id().toString(), null, null, "The building's own fitter put that machine right.");
            w.setDirty();
        }
        if (w.runsWell.values().removeIf(until -> now >= until)) w.setDirty();
        refreshShort(server);
        Random random = new Random(now * 37 + level.getSeed());
        for (String city : playersIn.keySet()) {
            Long due = w.next.get(city);
            if (due == null) { w.next.put(city, now + GAP_MIN + random.nextInt((int) (GAP_MAX - GAP_MIN))); w.setDirty(); continue; }
            if (now >= due) {
                w.next.put(city, now + GAP_MIN + random.nextInt((int) (GAP_MAX - GAP_MIN)));
                w.setDirty();
                breakdown(server, level, city, random, now);
            }
        }
    }

    /** A new breakdown at a machine of {@code city} that is running and not running well after a repair. Returns it, or null. */
    static Breakdown breakdown(MinecraftServer server, ServerLevel level, String city, Random random, long now) {
        Works w = works(server);
        if (w.breakdowns.values().stream().filter(b -> w.machines.containsKey(b.key()) && w.machines.get(b.key()).city().equals(city)).count() >= MAX_OPEN) return null;
        List<Machine> candidates = new ArrayList<>();
        for (Machine m : w.machines.values()) if (m.city().equals(city) && !w.breakdowns.containsKey(m.key()) && !runsWell(server, m.key())) candidates.add(m);
        if (candidates.isEmpty()) return null;
        return open(server, level, candidates.get(random.nextInt(candidates.size())), now);
    }

    /** Opens a breakdown at machine {@code m} and tells the Smiths in its city. Also the scenario's and the tests' way to raise a service call at once. */
    static Breakdown open(MinecraftServer server, ServerLevel level, Machine m, long now) {
        Works w = works(server);
        // the works' lines keep what they gained up to now; from now they gain nothing until the machine runs
        if (m.industrial() && !STALLED.contains(m.city())) WorkOrderLedger.get(server).bank(worksLines(m.city()));
        Breakdown b = new Breakdown(UUID.randomUUID(), m.key(), now);
        w.breakdowns.put(m.key(), b);
        w.setDirty();
        show(level, m, false);
        refreshShort(server);
        String what = m.industrial() ? "the " + m.name() + " has stopped and the works stands idle" : "the " + m.name() + " has stopped and the shop is running short";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isSmith(player) && CityRegions.INSTANCE.cityAt(level, player.blockPosition()).map(m.city()::equals).orElse(false))
                Notice.send(player, Notice.Kind.ORDER, "Service call", Casing.capitalise(m.label()) + ", " + m.address() + ": " + what + ". " + fee(m.tier()) + " credits. Use your toolkit to track it.");
        }
        LOGGER.info("[UC-SMITH] breakdown at {} ({}, tier {}, {})", m.address(), m.city(), m.tier(), m.name());
        return b;
    }

    /**
     * A machine put right by a player ({@link SmithWork}): the breakdown closed, the machine running and running well, and for an industrial building a catch-up shift that tops up its city's
     * exchange lines of metal goods by a share of their capacity. Returns how many units the shift put on the shelves.
     */
    static int repaired(MinecraftServer server, ServerLevel level, Machine m) {
        Works w = works(server);
        if (m.industrial()) WorkOrderLedger.get(server).bank(worksLines(m.city()));   // while still idle: the lines' clock restarts from now, with nothing gained
        w.breakdowns.remove(m.key());
        w.runsWell.put(m.key(), LockService.clock(server) + RUNS_WELL_TICKS);
        w.repairs++;
        w.setDirty();
        show(level, m, true);
        refreshShort(server);
        if (!m.industrial()) return 0;
        WorkOrderLedger ledger = WorkOrderLedger.get(server);
        int added = 0;
        for (RegionalStock stock : worksLines(m.city())) added += ledger.topUp(stock, Math.max(1, (int) Math.round(stock.capacity() * RULES.restockShare())));
        return added;
    }

    /** What a repair of a machine of this tier pays (before the career's contract-rate skills) and teaches. */
    static int fee(int tier) { return 35 + 14 * Math.max(1, tier); }
    static int xp(int tier) { return 90 + 30 * Math.max(1, tier); }

    static boolean isSmith(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).map(c -> "smith".equals(c.career)).orElse(false);
    }

    /** For a log line. */
    static String status(MinecraftServer server) {
        Works w = works(server);
        return "machines " + w.machines.size() + ", breakdowns " + w.breakdowns.size() + "; repairs " + w.repairs + ", fixed by the fitter " + w.expired;
    }
}
