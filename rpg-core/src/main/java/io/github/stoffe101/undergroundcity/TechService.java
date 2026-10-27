package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
import java.util.UUID;

/**
 * The Technician's trade (D-158): every city building has electrics and a network, reached at one <b>utility panel</b> beside its street door, and people pay to have them put right, installed
 * or upgraded.
 *
 * <ul>
 * <li><b>Panels.</b> In a new archipelago world Core sets a panel into the wall beside a building's street door the first time somebody comes near (a full block for a wall block, so nothing
 * falls); in any other world the street door itself is the access point. A panel's lamp shows whether the systems are well.</li>
 * <li><b>Faults</b> come now and then to buildings of a city somebody is in (at most {@link #MAX_FAULTS} at a time) and are put right by somebody else after {@link #FAULT_TICKS} of active play if
 * no player comes ({@link #SHOP_FAULT_TICKS} for a shop). While a shop's till is down it stays open but buys nothing from suppliers; while another building's systems are down its cameras are off and its alarm stays armed (D-165). Each fault is a <b>service call</b> on the service board.</li>
 * <li><b>A repair</b> is the trace puzzle ({@link TechPuzzles.Trace}) at the panel with a Technician's Toolkit; it pays the first Technician who gets it right, once, through the work-order
 * ledger's single commit, and puts the building right.</li>
 * <li><b>Install and upgrade jobs</b> are the patch puzzle ({@link TechPuzzles.Patch}): a monitored alarm armed and watched for {@link #MONITOR_TICKS}, a network that does not fault for
 * {@link #UPGRADE_TICKS}.</li>
 * </ul>
 *
 * <p>Server-authoritative: the puzzles' answers stay here, the client is sent what it may see and sends moves. Bounded: a handful of faults and jobs per city, panels placed a couple of buildings
 * at a time near players, nothing read from a chunk that is not loaded.
 */
public final class TechService {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** At most this many open faults in a city, of which at most {@link #MAX_SECURITY_FAULTS} are a building's cameras (D-165: a city is never half blind). */
    static final int MAX_FAULTS = 2, MAX_SECURITY_FAULTS = 1, MAX_INSTALLS = 2, MAX_UPGRADES = 1, MAX_QUIET = 1;
    /** Back-channel work (D-166): how long a quietened alarm line stays quiet and a looped building's cameras show an empty room, in active ticks. */
    static final long QUIET_TICKS = 10 * 1200L, QUIET_LOOP_TICKS = 20 * 1200L;
    /** Active ticks: how long a fault waits for a player (a shop's half as long, so no shop stays shut for long), the gap between faults in a city, how long a job stays open, a monitored alarm, an upgraded network. */
    static final long FAULT_TICKS = 20 * 1200L, SHOP_FAULT_TICKS = 20 * 1200L, FAULT_GAP_MIN = 8 * 1200L, FAULT_GAP_MAX = 15 * 1200L, JOB_TICKS = 90 * 1200L, MONITOR_TICKS = 8 * 72_000L, UPGRADE_TICKS = 4 * 72_000L;
    /** How often the service looks round (server ticks), and how many panels it may place near one player per look. */
    static final int CYCLE = 100, PANELS_PER_PLAYER = 2, PANEL_CHUNKS = 2;

    /** Registered on the Forge bus for its clock. */
    TechService() {}

    // ------------------------------------------------------------------------------------------------ the record

    /** A building's access point: its panel (or {@code null} where its street door stands in for one), its street door, and what it is. */
    record Panel(long key, BlockPos pos, BlockPos door, String city, int tier, String category, String label, String address) {
        BlockPos access() { return pos != null ? pos : door; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("key", key); if (pos != null) t.putLong("pos", pos.asLong()); t.putLong("door", door.asLong()); t.putString("city", city); t.putInt("tier", tier);
            t.putString("category", category); t.putString("label", label); t.putString("address", address);
            return t;
        }

        static Panel load(CompoundTag t) {
            return new Panel(t.getLong("key"), t.contains("pos") ? BlockPos.of(t.getLong("pos")) : null, BlockPos.of(t.getLong("door")), t.getString("city"), t.getInt("tier"),
                t.getString("category"), t.getString("label"), t.getString("address"));
        }
    }

    /** An open fault, a service call: {@code shop} faults stop a shop paying suppliers, the others turn a building's cameras off (D-165). */
    record Fault(UUID id, long key, boolean shop, long since) {
        CompoundTag save() { CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putLong("key", key); t.putBoolean("shop", shop); t.putLong("since", since); return t; }
        static Fault load(CompoundTag t) { return new Fault(t.getUUID("id"), t.getLong("key"), t.getBoolean("shop"), t.getLong("since")); }
    }

    /** An open job: {@code install} a monitored alarm or {@code upgrade} a network, at a building, for a fee. */
    record Job(UUID id, String kind, long key, int credits, int xp, long until) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag(); t.putUUID("id", id); t.putString("kind", kind); t.putLong("key", key); t.putInt("credits", credits); t.putInt("xp", xp); t.putLong("until", until);
            return t;
        }
        static Job load(CompoundTag t) { return new Job(t.getUUID("id"), t.getString("kind"), t.getLong("key"), t.getInt("credits"), t.getInt("xp"), t.getLong("until")); }
    }

    static Systems systems(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Systems::load, Systems::new, Systems.DATA_KEY); }

    /** Every building's access point, the open faults and jobs, and what was installed or upgraded until when. Saved. */
    static final class Systems extends SavedData {
        static final String DATA_KEY = "underground_city_tech";
        final Map<Long, Panel> panels = new LinkedHashMap<>();
        final Map<Long, Fault> faults = new LinkedHashMap<>();
        final Map<UUID, Job> jobs = new LinkedHashMap<>();
        final Map<Long, Long> monitored = new HashMap<>(), upgraded = new HashMap<>();
        /** Buildings whose alarm line a Technician quietened off the books (D-166), until when. */
        final Map<Long, Long> quieted = new HashMap<>();
        final Map<String, Long> nextFault = new HashMap<>();
        long repairs, installs, upgrades, expired, quiets;

        static Systems load(CompoundTag root) {
            Systems s = new Systems();
            for (Tag t : root.getList("panels", Tag.TAG_COMPOUND)) { Panel p = Panel.load((CompoundTag) t); s.panels.put(p.key(), p); }
            for (Tag t : root.getList("faults", Tag.TAG_COMPOUND)) { Fault f = Fault.load((CompoundTag) t); s.faults.put(f.key(), f); }
            for (Tag t : root.getList("jobs", Tag.TAG_COMPOUND)) { Job j = Job.load((CompoundTag) t); s.jobs.put(j.id(), j); }
            CompoundTag m = root.getCompound("monitored"), u = root.getCompound("upgraded"), n = root.getCompound("next_fault");
            for (String k : m.getAllKeys()) s.monitored.put(Long.parseLong(k), m.getLong(k));
            for (String k : u.getAllKeys()) s.upgraded.put(Long.parseLong(k), u.getLong(k));
            for (String k : n.getAllKeys()) s.nextFault.put(k, n.getLong(k));
            CompoundTag q = root.getCompound("quieted");
            for (String k : q.getAllKeys()) s.quieted.put(Long.parseLong(k), q.getLong(k));
            s.quiets = root.getLong("quiets");
            s.repairs = root.getLong("repairs"); s.installs = root.getLong("installs"); s.upgrades = root.getLong("upgrades"); s.expired = root.getLong("expired");
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag p = new ListTag(), f = new ListTag(), j = new ListTag();
            panels.values().forEach(x -> p.add(x.save()));
            faults.values().forEach(x -> f.add(x.save()));
            jobs.values().forEach(x -> j.add(x.save()));
            root.put("panels", p); root.put("faults", f); root.put("jobs", j);
            CompoundTag m = new CompoundTag(), u = new CompoundTag(), n = new CompoundTag();
            monitored.forEach((k, v) -> m.putLong(Long.toString(k), v));
            upgraded.forEach((k, v) -> u.putLong(Long.toString(k), v));
            nextFault.forEach(n::putLong);
            root.put("monitored", m); root.put("upgraded", u); root.put("next_fault", n);
            CompoundTag q = new CompoundTag();
            quieted.forEach((k, v) -> q.putLong(Long.toString(k), v));
            root.put("quieted", q); root.putLong("quiets", quiets);
            root.putLong("repairs", repairs); root.putLong("installs", installs); root.putLong("upgrades", upgrades); root.putLong("expired", expired);
            return root;
        }
    }

    // ------------------------------------------------------------------------------------------------ what other systems ask

    /**
     * True while a shop's till and card network are down. Since D-165 the shop stays open and its shopkeeper still sells over the counter, but it buys nothing from suppliers
     * ({@link ShopSupply}) and its customers buy nothing off the shelf until a Technician puts it right: a lost day's trade, not a shut door.
     */
    static boolean shopDown(ServerLevel level, CityBuildings.Building b) {
        Fault f = systems(level.getServer()).faults.get(b.key());
        return f != null && f.shop();
    }

    /** True while a building's security systems are faulted: its cameras see nothing. Its alarm stays armed (D-165), so a fault is a gap in the watching, never an open house. */
    static boolean securityDown(ServerLevel level, CityBuildings.Building b) {
        Fault f = systems(level.getServer()).faults.get(b.key());
        return f != null && !f.shop();
    }

    /** True while a building has a monitored alarm fitted by a Technician: armed, and the police come sooner. */
    static boolean monitored(ServerLevel level, CityBuildings.Building b) {
        Long until = systems(level.getServer()).monitored.get(b.key());
        return until != null && LockService.clock(level.getServer()) < until;
    }

    /** True while a building's alarm line was quietened off the books by a Technician (D-166): its alarm does not call anybody. */
    static boolean quieted(ServerLevel level, CityBuildings.Building b) {
        Long until = systems(level.getServer()).quieted.get(b.key());
        return until != null && LockService.clock(level.getServer()) < until;
    }

    /** True while a building's network was upgraded lately: it does not fault. */
    static boolean upgraded(ServerLevel level, long key) {
        Long until = systems(level.getServer()).upgraded.get(key);
        return until != null && LockService.clock(level.getServer()) < until;
    }

    // ------------------------------------------------------------------------------------------------ panels

    /** Whether this world places panels: a new archipelago world, as the roadside places (anything older keeps its buildings as they are and uses the street door). */
    static boolean placesPanels(ServerLevel level) { return RoadsidePlaces.enabled(level); }

    /** A building's street door: the lower half of the first exterior door on its ground floors, and the side it opens onto the street. */
    record StreetDoor(BlockPos lower, Direction out) {}

    static Optional<StreetDoor> streetDoor(ServerLevel level, CityBuildings.Building b) {
        for (int y = b.streetY(); y <= b.streetY() + 3; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos pos = new BlockPos(b.chunkX() * 16 + x, y, b.chunkZ() * 16 + z);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            Direction facing = state.getValue(DoorBlock.FACING);
            boolean front = BuildingGuard.leadsOut(level, pos, facing), back = BuildingGuard.leadsOut(level, pos, facing.getOpposite());
            if (!front && !back) continue;
            return Optional.of(new StreetDoor(pos.immutable(), front ? facing : facing.getOpposite()));
        }
        return Optional.empty();
    }

    /**
     * Where a panel can go beside a street door: a whole, solid block of the building's wall at the height of the door's upper half, on either side, inside the building's own chunk, with open
     * air in front of it on the street side. Null when there is none (the door is then the access point).
     */
    static BlockPos panelSpot(ServerLevel level, CityBuildings.Building b, StreetDoor door) {
        // the side without a drive housing below first (the Smith's machine, D-182), so the two are not stacked where the wall has room for both
        for (int pass = 0; pass < 2; pass++) for (Direction side : new Direction[] {door.out().getClockWise(), door.out().getCounterClockWise()}) {
            BlockPos spot = door.lower().above().relative(side);
            if (!b.contains(spot) || pass == 0 && level.getBlockState(spot.below()).is(UndergroundCity.MACHINE_DRIVE.get())) continue;
            BlockState wall = level.getBlockState(spot);
            if (!wall.isSolidRender(level, spot) || wall.hasBlockEntity() || wall.getBlock() instanceof DoorBlock) continue;
            if (!level.getBlockState(spot.relative(door.out())).isAir()) continue;
            return spot.immutable();
        }
        return null;
    }

    /** Records (and in a new archipelago world sets in place) the access point of a building somebody is near. Returns whether anything was done. */
    static boolean ensurePanel(ServerLevel level, CityBuildings.Building b) {
        Systems s = systems(level.getServer());
        if (s.panels.containsKey(b.key()) || !b.locked() || Thievery.tier(b) <= 0 || BuildingGuard.protectedAt(level, new BlockPos(b.chunkX() * 16 + 8, b.streetY(), b.chunkZ() * 16 + 8)).isEmpty()) return false;
        Optional<StreetDoor> door = streetDoor(level, b);
        if (door.isEmpty()) return false;
        BlockPos spot = placesPanels(level) ? panelSpot(level, b, door.get()) : null;
        if (spot != null) {
            level.setBlock(spot, UndergroundCity.UTILITY_PANEL.get().defaultBlockState().setValue(TechBlocks.UtilityPanel.FACING, door.get().out()), 3);
        }
        String category = b.security() == null ? "public" : CityBuildings.INSTANCE.category(b.security().id());
        s.panels.put(b.key(), new Panel(b.key(), spot, door.get().lower(), b.city(), Thievery.tier(b), category, Thievery.label(b), CityHomes.address(level.getSeed(), b)));
        s.setDirty();
        return true;
    }

    /** Shows a building's state on its panel's lamp (when the panel's chunk is loaded). */
    static void lamp(ServerLevel level, Panel panel, TechBlocks.UtilityPanel.State state) {
        if (panel.pos() == null || !level.isLoaded(panel.pos())) return;
        BlockState here = level.getBlockState(panel.pos());
        if (here.getBlock() instanceof TechBlocks.UtilityPanel && here.getValue(TechBlocks.UtilityPanel.STATE) != state) level.setBlock(panel.pos(), here.setValue(TechBlocks.UtilityPanel.STATE, state), 3);
    }

    // ------------------------------------------------------------------------------------------------ the clock

    private static int clock;

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++clock % CYCLE != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null || !WorldLayout.regional(level.getChunkSource().getGenerator())) return;
        try { look(server, level); }
        catch (RuntimeException error) { LOGGER.error("[UC] the Technician's service failed a pass", error); }
    }

    /** One pass: panels near players, faults and jobs in the cities somebody is in, and the end of old ones. */
    static void look(MinecraftServer server, ServerLevel level) {
        Systems s = systems(server);
        long now = LockService.clock(server);
        Map<String, Integer> playersIn = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer || player.level() != level || player.isSpectator()) continue;
            CityRegions.INSTANCE.cityAt(level, player.blockPosition()).ifPresent(city -> playersIn.merge(city, 1, Integer::sum));
            int placed = 0;
            int cx = player.chunkPosition().x, cz = player.chunkPosition().z;
            for (int dx = -PANEL_CHUNKS; dx <= PANEL_CHUNKS && placed < PANELS_PER_PLAYER; dx++) for (int dz = -PANEL_CHUNKS; dz <= PANEL_CHUNKS && placed < PANELS_PER_PLAYER; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null || !CityBuildings.INSTANCE.known(cx + dx, cz + dz) && dx * dx + dz * dz > 2
                    || !CityBuildings.INSTANCE.askable(level, cx + dx, cz + dz)) continue;   // never waits on Lost Cities (D-167)
                Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz);
                if (b.isPresent() && ensurePanel(level, b.get())) placed++;
            }
        }
        // old faults are put right by somebody else; old jobs are taken by somebody else
        for (Iterator<Fault> it = s.faults.values().iterator(); it.hasNext(); ) {
            Fault f = it.next();
            if (now - f.since() < (f.shop() ? SHOP_FAULT_TICKS : FAULT_TICKS)) continue;
            it.remove();
            s.expired++;
            Panel p = s.panels.get(f.key());
            if (p != null) lamp(level, p, TechBlocks.UtilityPanel.State.OK);
            s.setDirty();
        }
        if (s.jobs.values().removeIf(j -> now > j.until())) s.setDirty();
        Random random = new Random(now * 31 + level.getSeed());
        for (String city : playersIn.keySet()) {
            Long due = s.nextFault.get(city);
            if (due == null) { s.nextFault.put(city, now + FAULT_GAP_MIN + random.nextInt((int) (FAULT_GAP_MAX - FAULT_GAP_MIN))); s.setDirty(); continue; }
            if (now >= due) {
                s.nextFault.put(city, now + FAULT_GAP_MIN + random.nextInt((int) (FAULT_GAP_MAX - FAULT_GAP_MIN)));
                s.setDirty();
                fault(server, level, city, random, now);
            }
            jobs(s, level, city, random, now);
        }
    }

    /** A new fault in a building of {@code city} that has an access point and is not faulted or freshly upgraded. Returns it, or null. */
    static Fault fault(MinecraftServer server, ServerLevel level, String city, Random random, long now) {
        Systems s = systems(server);
        if (s.faults.values().stream().filter(f -> city.equals(s.panels.containsKey(f.key()) ? s.panels.get(f.key()).city() : "")).count() >= MAX_FAULTS) return null;
        long cameras = s.faults.values().stream().filter(f -> !f.shop() && city.equals(s.panels.containsKey(f.key()) ? s.panels.get(f.key()).city() : "")).count();
        List<Panel> candidates = new ArrayList<>();
        for (Panel p : s.panels.values())
            if (p.city().equals(city) && !s.faults.containsKey(p.key()) && !upgraded(level, p.key()) && ("commercial".equals(p.category()) || cameras < MAX_SECURITY_FAULTS)) candidates.add(p);
        if (candidates.isEmpty()) return null;
        return open(server, level, candidates.get(random.nextInt(candidates.size())), now);
    }

    /** Opens a fault at panel {@code p} (a shop's if it is a commercial building) and tells the Technicians in its city. Also the scenario's way to raise a service call at once. */
    static Fault open(MinecraftServer server, ServerLevel level, Panel p, long now) {
        Systems s = systems(server);
        boolean shop = "commercial".equals(p.category());
        Fault f = new Fault(UUID.randomUUID(), p.key(), shop, now);
        s.faults.put(p.key(), f);
        s.setDirty();
        lamp(level, p, TechBlocks.UtilityPanel.State.FAULT);
        String what = shop ? "the till and card network are down" : "the cameras are down";
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isTechnician(player) && CityRegions.INSTANCE.cityAt(level, player.blockPosition()).map(p.city()::equals).orElse(false))
                Notice.send(player, Notice.Kind.ORDER, "Service call", Casing.capitalise(p.label()) + ", " + p.address() + ": " + what + ". " + repairFee(p.tier()) + " credits.");
        }
        LOGGER.info("[UC-TECH] fault at {} ({}, tier {}, {})", p.address(), p.city(), p.tier(), shop ? "shop" : "security");
        return f;
    }

    /** Keeps a city's open install and upgrade jobs topped up. */
    static void jobs(Systems s, ServerLevel level, String city, Random random, long now) {
        int installs = 0, upgrades = 0, quiet = 0;
        for (Job j : s.jobs.values()) {
            Panel p = s.panels.get(j.key());
            if (p == null || !p.city().equals(city)) continue;
            if (j.kind().equals("install")) installs++; else if (j.kind().equals("quiet")) quiet++; else upgrades++;
        }
        List<Panel> panels = new ArrayList<>();
        for (Panel p : s.panels.values()) if (p.city().equals(city)) panels.add(p);
        if (panels.isEmpty()) return;
        for (int guard = 0; guard < 6 && (installs < MAX_INSTALLS || upgrades < MAX_UPGRADES); guard++) {
            Panel p = panels.get(random.nextInt(panels.size()));
            boolean taken = s.jobs.values().stream().anyMatch(j -> j.key() == p.key());
            if (taken || s.faults.containsKey(p.key())) continue;
            if (installs < MAX_INSTALLS && p.tier() >= 2 && !monitoredKey(s, p.key(), now) && !"commercial".equals(p.category())) {
                newJob(s, "install", p, installFee(p.tier()), installXp(p.tier()), now);
                installs++;
            } else if (upgrades < MAX_UPGRADES && p.tier() >= 3 && ("office".equals(p.category()) || "industrial".equals(p.category()) || "commercial".equals(p.category()))) {
                newJob(s, "upgrade", p, upgradeFee(p.tier()), upgradeXp(p.tier()), now);
                upgrades++;
            }
        }
        // back-channel work (D-166): now and then somebody wants a building's feeds looped and its alarm line quiet for a while; never a building with a monitored alarm
        if (quiet < MAX_QUIET && random.nextInt(3) == 0) {
            Panel p = panels.get(random.nextInt(panels.size()));
            boolean taken = s.jobs.values().stream().anyMatch(j -> j.key() == p.key());
            if (!taken && p.tier() >= 3 && !monitoredKey(s, p.key(), now) && !s.faults.containsKey(p.key())) newJob(s, "quiet", p, quietFee(p.tier()), quietXp(p.tier()), now);
        }
    }

    /** What a back-channel job pays and teaches (D-166): more than legal work of the same tier, for the risk of being seen at the panel. */
    static int quietFee(int tier) { return 70 + 30 * Math.max(1, tier); }
    static int quietXp(int tier) { return 60 + 20 * Math.max(1, tier); }

    private static boolean monitoredKey(Systems s, long key, long now) { Long until = s.monitored.get(key); return until != null && now < until; }

    static Job newJob(Systems s, String kind, Panel p, int credits, int xp, long now) {
        Job j = new Job(UUID.randomUUID(), kind, p.key(), credits, xp, now + JOB_TICKS);
        s.jobs.put(j.id(), j);
        s.setDirty();
        return j;
    }

    /** What a repair of a building of this tier pays (before the career's contract-rate skills). */
    static int repairFee(int tier) { return 30 + 12 * Math.max(1, tier); }
    static int repairXp(int tier) { return 80 + 30 * Math.max(1, tier); }
    static int installFee(int tier) { return 45 + 18 * Math.max(1, tier); }
    static int installXp(int tier) { return 110 + 35 * Math.max(1, tier); }
    static int upgradeFee(int tier) { return 60 + 22 * Math.max(1, tier); }
    static int upgradeXp(int tier) { return 140 + 40 * Math.max(1, tier); }

    static boolean isTechnician(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).map(c -> "technician".equals(c.career)).orElse(false);
    }

    /** For a log line. */
    static String status(MinecraftServer server) {
        Systems s = systems(server);
        return "panels " + s.panels.size() + ", faults " + s.faults.size() + ", jobs " + s.jobs.size() + "; repairs " + s.repairs + ", installs " + s.installs + ", upgrades " + s.upgrades + ", expired " + s.expired;
    }
}
