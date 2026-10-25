package io.github.stoffe101.undergroundcity;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Property intelligence (D-157): what a person can tell about a building from its street door, and what they remember of the places they have looked at.
 *
 * <ul>
 * <li><b>A glance</b> ({@link #peek}): looking at a city building's street door, anybody reads what it is (a house, a workshop), how well off it is (its tier), whether it is somebody's home
 * (a player's, which is never an NPC burglary target), to let, open for business or not a target at all, and whether they could try its lock with what they carry. The client asks only
 * for the door under its crosshair and shows a small card under it while they look; nothing is drawn over buildings nobody is looking at.</li>
 * <li><b>Casing</b> ({@link Casing}) reads more, in layers by Thief skill, and an estimate of the risk <i>now</i> ({@link #risk}), apart from the building's tier: an armed alarm, people in the
 * street who could see the door, people inside, cameras, an officer on the beat, a household watchful after a break-in.</li>
 * <li><b>The book</b> ({@link Book}): every building a player has glanced at or cased is remembered for them, with what they learned and when, never what they did not learn and never what is
 * inside. The Places screen lists it, filters it and points the way to one. Saved, bounded, per player.</li>
 * </ul>
 *
 * <p>Everything is computed on the server from what the game already knows; the client is sent words and small numbers.
 */
public final class PropertyIntel {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** What a door is, as the glance card says. */
    public enum Status { TARGET, OPEN, YOURS, PLAYER_HOME, TO_LET, NOT_A_TARGET }

    /**
     * What a glance at a street door tells. {@code access}: 0 the player could try the lock now, 1 it needs a Pro Pick Set (or the skill to work one), 2 it needs a Thief skill, 3 they carry
     * nothing to pick it with. {@code layer} is how much the player's last casing of the place told them (-1 never cased).
     */
    public record Peek(BlockPos door, Status status, String category, String label, String address, String city, int tier, int access, int layer, boolean beenIn, boolean playerHomes,
                       int opensAt, int shutsAt) {
        /** A glance at a building that keeps no business hours. */
        public Peek(BlockPos door, Status status, String category, String label, String address, String city, int tier, int access, int layer, boolean beenIn, boolean playerHomes) {
            this(door, status, category, label, address, city, tier, access, layer, beenIn, playerHomes, -1, -1);
        }
    }

    /** How likely a break-in now is to be noticed, in words and as a fraction, with the reasons (the first few are shown). */
    public record Risk(double chance, String word, List<String> reasons) {}

    /** Registered on the Forge bus for its commands and the survey's clock. */
    PropertyIntel() {}

    // ------------------------------------------------------------------------------------------------ the glance

    /** The longest a glance reaches (the client asks within 6 blocks; a little more for the round trip). */
    static final double PEEK_REACH = 8.0;

    /** What a glance at the door at {@code pos} tells this player, or empty when it is no city building's street door (or too far). Records the place in their book when it is a target. */
    static Optional<Peek> peek(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock) || player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > PEEK_REACH) return Optional.empty();
        BlockPos lower = BuildingGuard.lowerOf(pos, state);
        BlockState lowerState = lower.equals(pos) ? state : level.getBlockState(lower);
        if (!(lowerState.getBlock() instanceof DoorBlock)) return Optional.empty();
        Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.at(level, lower);
        // a registered home (a Row flat, a let house, a vacant listing) answers to its own guard: whose it is, never a target
        CrewLedger ledger = CrewService.ledger(level.getServer());
        CrewLedger.Residence residence = ledger == null || ledger.faulted() ? null : ledger.residenceAt(level.dimension().location().toString(), lower);
        if (residence != null) {
            boolean mine = residence.owned() && ResidenceGuard.allowed(ledger, residence, player.getUUID(), residence.dimension, residence.min, ResidenceGuard.Act.DOOR);
            Status status = mine ? Status.YOURS : residence.owned() ? Status.PLAYER_HOME : Status.TO_LET;
            String city = found.map(CityBuildings.Building::city).orElse(CityRegions.INSTANCE.cityAt(level, lower).orElse(""));
            return Optional.of(new Peek(lower, status, "residential", "home", residence.title == null ? "" : residence.title, city, 0, 0, -1, false, false));
        }
        if (found.isEmpty() || !BuildingGuard.exterior(level, lower, lowerState)) return Optional.empty();
        CityBuildings.Building b = found.get();
        CityBuildings.SecurityClass security = b.security();
        String category = security == null ? "public" : CityBuildings.INSTANCE.category(security.id());
        String label = security == null || security.label().isEmpty() ? "building" : security.label();
        String address = CityHomes.address(level.getSeed(), b);
        int tier = Thievery.tier(b);
        // a building whose people keep business hours (a shop, a bar, a club, a works) says them on the card (D-185)
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(b.type());
        BusinessHours.Span hours = plan != null && (!plan.indexesOf("counter").isEmpty() || !plan.indexesOf("desk").isEmpty()) ? BusinessHours.of(plan) : null;
        int opens = hours == null ? -1 : hours.from(), shuts = hours == null ? -1 : hours.to();
        if (tier <= 0 || !b.locked()) return Optional.of(new Peek(lower, Status.NOT_A_TARGET, category, label, address, b.city(), 0, 0, -1, false, false, opens, shuts));
        boolean beenIn = Thievery.records(level.getServer()).seen(player.getUUID(), "entered", b.key());
        boolean playerHomes = CityHomes.heldUnits(level.getServer(), b) > 0;
        if (CityHomes.holdsKey(player, b)) return Optional.of(new Peek(lower, Status.YOURS, category, label, address, b.city(), tier, 0, -1, beenIn, playerHomes, opens, shuts));
        Status status = BuildingGuard.openToPublic(level, b, null) ? Status.OPEN : Status.TARGET;
        int access = access(player, security);
        Book book = book(level.getServer());
        Entry known = book.get(player.getUUID(), b.key());
        book.seen(player.getUUID(), b, lower, address, category, label, LockService.clock(level.getServer()));
        return Optional.of(new Peek(lower, status, category, label, address, b.city(), tier, access, known == null ? -1 : known.layer(), beenIn, playerHomes, opens, shuts));
    }

    /** Whether this player could try a class's street-door lock now (see {@link Peek#access}). */
    static int access(ServerPlayer player, CityBuildings.SecurityClass security) {
        LockpickSession.Profile profile = LockProfiles.INSTANCE.find(security.lockProfile());
        if (profile == null) return 3;
        String requirement = LockService.requirement(player, profile, new LockService.Target(LockService.Kind.DOOR, BlockPos.ZERO, profile.id(), "", security.id(), "", 1));
        if (requirement != null) return profile.requiresTool().isEmpty() ? 2 : 1;
        return LockService.worksProSet(player) || LockService.picks(player) > 0 ? 0 : 3;
    }

    /** The glance card's short words for access. */
    static String accessWords(int access) {
        return switch (access) { case 0 -> "You could try it"; case 1 -> "Needs a Pro Pick Set"; case 2 -> "Needs a Thief skill"; default -> "No lockpick on you"; };
    }

    /** The words of a category for a person ("Residential"). */
    static String categoryWord(String category) {
        return switch (category == null ? "" : category) {
            case "residential" -> "Residential"; case "commercial" -> "Commercial"; case "industrial" -> "Industrial"; case "office" -> "Offices"; case "special" -> "High-value"; default -> "Public";
        };
    }

    /** The tier as a roman numeral of six ("III / VI"): the glance card shows a word and a number, never colour alone. */
    static String roman(int tier) { return new String[] {"-", "I", "II", "III", "IV", "V", "VI"}[Math.max(0, Math.min(6, tier))]; }

    // ------------------------------------------------------------------------------------------------ the risk now

    /** Thresholds of the risk words. */
    static final double LOW = 0.12, MODERATE = 0.30, HIGH = 0.55;

    static String riskWord(double chance) { return chance < LOW ? "Low" : chance < MODERATE ? "Moderate" : chance < HIGH ? "High" : "Very high"; }

    /**
     * An estimate of how likely a break-in at this door, now, is to be noticed, from the same rules that decide it ({@link PoliceResponse#noticed}, {@link SecurityDevices}, {@link PolicePatrols}):
     * the building's alarm (armed, slippable by a clean pick, jammed), the people in the street who could see the door and the city's witness chance with the thief's heat, people at home,
     * cameras, an officer on the beat in sight or near, and a household watchful after a break-in. It is an estimate: the real rolls are made when it happens.
     */
    static Risk risk(ServerPlayer player, ServerLevel level, CityBuildings.Building b, BlockPos door, int inside, int cameras, boolean watchful) {
        List<String> reasons = new ArrayList<>();
        double keep = 1.0;
        CityBuildings.Response response = b.security() == null ? null : b.security().response();
        boolean ghost = Thievery.has(player, "thief.ghost");
        boolean jammed = AlarmPanels.jammed(level, b);
        if (response != null && PoliceResponse.armed(level, b, response) && !jammed) {
            boolean slippable = response.alarmChance() < (ghost ? 1.0 : PoliceResponse.QUIET_BYPASS_MAX_CHANCE);
            double p = slippable ? 0.12 : AlarmPanels.panelOf(b).isPresent() ? 0.55 : 0.95;
            keep *= 1 - p;
            reasons.add(slippable ? "Armed alarm (a clean pick slips it)" : AlarmPanels.panelOf(b).isPresent() ? "Armed alarm, panel by the door" : "Armed alarm that will ring");
        }
        LockpickSession.Profile profile = b.security() == null ? null : LockProfiles.INSTANCE.find(b.security().lockProfile());
        if (profile != null && profile.alarmChance() > 0) keep *= 1 - profile.alarmChance() * 0.2;   // a snapped pick can ring a lock's own alarm
        CityLaws.Law law = CityLaws.INSTANCE.find(b.city());
        if (law != null) {
            double radius = PoliceResponse.witnessRadius(player);
            List<TownspersonEntity> seers = level.getEntitiesOfClass(TownspersonEntity.class, new AABB(door).inflate(radius),
                person -> person.isAlive() && !person.police() && person.homeKey() != b.key() && person.distanceTo(player) <= radius + 1 && person.hasLineOfSight(player));
            if (!seers.isEmpty()) {
                CitizenState.Citizen citizen = CitizenState.get(level.getServer()).find(player.getUUID()).orElse(null);
                double each = LawMath.witnessChance(law, WorkOrderLedger.get(level.getServer()).heat(player.getUUID(), b.city()), citizen == null ? 0 : SkillService.inspectionModifier(citizen));
                if (ghost) each *= PoliceResponse.GHOST_WITNESS_FACTOR;
                if (watchful) each = Math.min(0.95, each * 1.4);
                keep *= Math.pow(1 - each, Math.min(3, seers.size()));
                reasons.add(seers.size() == 1 ? "Seen from the street by 1" : "Seen from the street by " + seers.size());
            }
        }
        if (inside > 0) {
            keep *= 1 - Math.min(watchful ? 0.75 : 0.6, (watchful ? 0.4 : 0.3) * inside);
            reasons.add(inside == 1 ? "Somebody at home" : inside + " people in");
        }
        if (cameras > 0 && !SecurityDevices.looped(level, b) && !jammed) {
            double p = Math.min(0.8, 0.45 + 0.15 * (cameras - 1));
            if (Thievery.has(player, "thief.camera_sense")) p *= 0.6;
            keep *= 1 - p;
            reasons.add(cameras == 1 ? "A camera inside" : cameras + " cameras inside");
        }
        if (PolicePatrols.seer(level, player) != null) { keep = 0; reasons.add(0, "An officer can see you now"); }
        else if (PolicePatrols.officersNear(level, player.blockPosition(), PolicePatrols.WARN_RANGE) > 0) { keep *= 0.65; reasons.add("An officer is close"); }
        if (watchful) reasons.add("Watchful after a break-in");
        double chance = Math.max(0, Math.min(1, 1 - keep));
        if (reasons.isEmpty()) reasons.add("Nothing would notice a clean entry");
        return new Risk(chance, riskWord(chance), List.copyOf(reasons));
    }

    /** Searchable pieces of furniture and storage in the building (an expert's count; the upper half of a two-block piece is not counted twice). Loaded blocks only. */
    static int searchable(ServerLevel level, CityBuildings.Building b) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(b.chunkX() * 16, b.minY(), b.chunkZ() * 16, b.chunkX() * 16 + 15, b.maxY() - 1, b.chunkZ() * 16 + 15)) {
            if (!level.isLoaded(pos)) return n;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            boolean container = state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
            if (!container && BurglarySearch.LOADER.kindOf(state) == null) continue;
            if (!BurglarySearch.anchor(state, pos).equals(pos)) continue;
            if (CityHomes.letAt(level, b, pos)) continue;   // a unit to let or let is somebody's home, not loot
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------ the book

    /** How many places a player's book keeps; the oldest unmarked are forgotten first. */
    static final int MAX_PLACES = 400;

    /** One remembered place. {@code layer} -1 seen only, 0 to 3 how much the last casing told. */
    public record Entry(long key, String city, BlockPos door, String address, String category, String label, int tier, long firstSeen, long lastSeen, int layer, long casedAt,
                        boolean marked, boolean watchful, String lock, int alarm, int cameras, String risk) {
        Entry withSeen(long now) { return new Entry(key, city, door, address, category, label, tier, firstSeen, now, layer, casedAt, marked, watchful, lock, alarm, cameras, risk); }
        Entry withMarked(boolean on) { return new Entry(key, city, door, address, category, label, tier, firstSeen, lastSeen, layer, casedAt, on, watchful, lock, alarm, cameras, risk); }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putLong("key", key); t.putString("city", city); t.putLong("door", door.asLong()); t.putString("address", address); t.putString("category", category); t.putString("label", label);
            t.putInt("tier", tier); t.putLong("first", firstSeen); t.putLong("last", lastSeen); t.putInt("layer", layer); t.putLong("cased", casedAt); t.putBoolean("marked", marked);
            t.putBoolean("watchful", watchful); t.putString("lock", lock); t.putInt("alarm", alarm); t.putInt("cameras", cameras); t.putString("risk", risk);
            return t;
        }

        static Entry load(CompoundTag t) {
            return new Entry(t.getLong("key"), t.getString("city"), BlockPos.of(t.getLong("door")), t.getString("address"), t.getString("category"), t.getString("label"), t.getInt("tier"),
                t.getLong("first"), t.getLong("last"), t.contains("layer") ? t.getInt("layer") : -1, t.getLong("cased"), t.getBoolean("marked"), t.getBoolean("watchful"), t.getString("lock"),
                t.contains("alarm") ? t.getInt("alarm") : -1, t.contains("cameras") ? t.getInt("cameras") : -1, t.getString("risk"));
        }
    }

    static Book book(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Book::load, Book::new, Book.DATA_KEY); }

    /** Every player's remembered places. Saved; bounded per player. */
    static final class Book extends SavedData {
        static final String DATA_KEY = "underground_city_places";
        private final Map<UUID, LinkedHashMap<Long, Entry>> places = new HashMap<>();

        Entry get(UUID player, long key) {
            Map<Long, Entry> mine = places.get(player);
            return mine == null ? null : mine.get(key);
        }

        List<Entry> of(UUID player) { return places.containsKey(player) ? List.copyOf(places.get(player).values()) : List.of(); }

        /** A glance: remembers the place (tier and what it is) if it is new, else notes the time. */
        void seen(UUID player, CityBuildings.Building b, BlockPos door, String address, String category, String label, long now) {
            LinkedHashMap<Long, Entry> mine = places.computeIfAbsent(player, p -> new LinkedHashMap<>());
            Entry old = mine.get(b.key());
            if (old != null) {
                if (now - old.lastSeen() > 200) { mine.put(b.key(), old.withSeen(now)); setDirty(); }   // a glance every few seconds would rewrite the file for nothing
                return;
            }
            mine.put(b.key(), new Entry(b.key(), b.city(), door, address, category, label, Thievery.tier(b), now, now, -1, 0, false, false, "", -1, -1, ""));
            trim(mine);
            setDirty();
        }

        /** A casing: what it told, kept until the next casing of the place. */
        void cased(UUID player, CityBuildings.Building b, BlockPos door, String address, String category, String label, long now, int layer, boolean watchful, String lock, int alarm, int cameras, String risk) {
            LinkedHashMap<Long, Entry> mine = places.computeIfAbsent(player, p -> new LinkedHashMap<>());
            Entry old = mine.get(b.key());
            mine.put(b.key(), new Entry(b.key(), b.city(), door, address, category, label, Thievery.tier(b), old == null ? now : old.firstSeen(), now, layer, now, old != null && old.marked(),
                watchful, lock, alarm, cameras, risk));
            trim(mine);
            setDirty();
        }

        boolean mark(UUID player, long key, boolean on) {
            Map<Long, Entry> mine = places.get(player);
            Entry old = mine == null ? null : mine.get(key);
            if (old == null) return false;
            mine.put(key, old.withMarked(on));
            setDirty();
            return true;
        }

        boolean forget(UUID player, long key) {
            Map<Long, Entry> mine = places.get(player);
            if (mine == null || mine.remove(key) == null) return false;
            setDirty();
            return true;
        }

        private static void trim(LinkedHashMap<Long, Entry> mine) {
            while (mine.size() > MAX_PLACES) {
                Long oldest = null;
                long when = Long.MAX_VALUE;
                for (Entry e : mine.values()) if (!e.marked() && e.lastSeen() < when) { when = e.lastSeen(); oldest = e.key(); }
                if (oldest == null) oldest = mine.keySet().iterator().next();
                mine.remove(oldest);
            }
        }

        static Book load(CompoundTag root) {
            Book book = new Book();
            ListTag players = root.getList("players", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                CompoundTag p = players.getCompound(i);
                if (!p.hasUUID("player")) continue;
                LinkedHashMap<Long, Entry> mine = new LinkedHashMap<>();
                ListTag list = p.getList("places", Tag.TAG_COMPOUND);
                for (int j = 0; j < list.size(); j++) { Entry e = Entry.load(list.getCompound(j)); mine.put(e.key(), e); }
                book.places.put(p.getUUID("player"), mine);
            }
            return book;
        }

        @Override
        public CompoundTag save(CompoundTag root) {
            ListTag players = new ListTag();
            for (var mine : places.entrySet()) {
                CompoundTag p = new CompoundTag();
                p.putUUID("player", mine.getKey());
                ListTag list = new ListTag();
                for (Entry e : mine.getValue().values()) list.add(e.save());
                p.put("places", list);
                players.add(p);
            }
            root.put("players", players);
            return root;
        }
    }

    // ------------------------------------------------------------------------------------------------ the list a player sees

    /** One row of the Places screen: the remembered place, and what is true for the player now (could they try it, have they been in). */
    public record Row(Entry entry, boolean suitable, boolean beenIn) {}

    /** The rows of a player's book, newest first. */
    static List<Row> rows(ServerPlayer player) {
        MinecraftServer server = player.server;
        List<Row> out = new ArrayList<>();
        for (Entry e : book(server).of(player.getUUID())) {
            boolean beenIn = Thievery.records(server).seen(player.getUUID(), "entered", e.key());
            out.add(new Row(e, suitableLock(player, e.tier()), beenIn));
        }
        out.sort((a, b) -> Long.compare(b.entry().lastSeen(), a.entry().lastSeen()));
        return out;
    }

    /** Whether the player could try a door of this tier's usual lock now: a tier-5 or tier-6 door asks for a Pro Pick Set and Professional Tools; anything below for a lockpick. */
    static boolean suitableLock(ServerPlayer player, int tier) {
        if (tier <= 0) return false;
        if (tier >= 5) return LockService.worksProSet(player);
        return LockService.worksProSet(player) || LockService.picks(player) > 0;
    }

    // ------------------------------------------------------------------------------------------------ the survey (operators)

    /** A survey of every building plan in the cities, a slice per tick (asking Lost Cities about a chunk the first time costs tens of milliseconds, so never all at once). */
    static final class Survey {
        final Deque<long[]> queue = new ArrayDeque<>();
        final Map<String, Map<String, Integer>> counts = new TreeMap<>();
        final long started = System.nanoTime();
        long workNanos, worstSliceNanos;
        int chunks, buildings;
        final String requester;
        Survey(String requester) { this.requester = requester; }

        void count(String city, String bucket) { counts.computeIfAbsent(city, c -> new TreeMap<>()).merge(bucket, 1, Integer::sum); }
    }

    private static volatile Survey survey;
    /** Time a survey may use per server tick. */
    static final long SURVEY_SLICE_NANOS = 15_000_000L;

    static String startSurvey(MinecraftServer server, String requester) {
        if (survey != null) return "A survey is already running (" + survey.queue.size() + " chunks to go).";
        Survey s = new Survey(requester);
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) {
            if (!region.dimension().equals("minecraft:overworld")) continue;
            int r = region.cityRadius() / 16 + 1;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if ((dx * 16.0) * (dx * 16.0) + (dz * 16.0) * (dz * 16.0) > (double) region.cityRadius() * region.cityRadius()) continue;
                s.queue.add(new long[] {region.centerChunkX() + dx, region.centerChunkZ() + dz});
            }
        }
        survey = s;
        return "Surveying " + s.queue.size() + " city chunks, a slice per tick.";
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Survey s = survey;
        if (s == null) return;
        ServerLevel level = event.getServer().overworld();
        long start = System.nanoTime();
        while (!s.queue.isEmpty() && System.nanoTime() - start < SURVEY_SLICE_NANOS) {
            long[] c = s.queue.peek();
            if (!CityBuildings.INSTANCE.askable(level, (int) c[0], (int) c[1])) break;   // Lost Cities is generating: the survey goes on next tick (D-167)
            s.queue.poll();
            s.chunks++;
            Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.ofChunk(level, (int) c[0], (int) c[1]);
            if (found.isEmpty()) continue;
            CityBuildings.Building b = found.get();
            s.buildings++;
            String city = b.city();
            CityBuildings.SecurityClass security = b.security();
            int tier = Thievery.tier(b);
            s.count(city, "buildings");
            s.count(city, "class:" + (security == null ? "none" : security.id()));
            s.count(city, "tier:" + tier);
            s.count(city, "category:" + (security == null ? "none" : CityBuildings.INSTANCE.category(security.id())));
            s.count(city, "lock:" + (security == null || !b.locked() ? "none" : security.lockProfile()));
            if (security != null && security.response() != null) s.count(city, "alarmed");
            if (b.type().startsWith(UndergroundCity.MOD_ID + ":")) s.count(city, "furnished");
            if (BuildingSpots.INSTANCE.forType(b.type()) != null) s.count(city, "inhabited");
            if (PropertyCatalog.INSTANCE.cityTemplate(b.type()).isPresent()) s.count(city, "rentable");
            if (ShopCounters.INSTANCE.forBuilding(b.type()).isPresent()) s.count(city, "shop");
            if (tier > 0 && b.locked()) s.count(city, "targets");
            s.count(city, "type:" + b.type().replace(UndergroundCity.MOD_ID + ":", "") + "/" + b.floors());
        }
        long spent = System.nanoTime() - start;
        s.workNanos += spent;
        s.worstSliceNanos = Math.max(s.worstSliceNanos, spent);
        if (!s.queue.isEmpty()) return;
        survey = null;
        finish(event.getServer(), s);
    }

    private static void finish(MinecraftServer server, Survey s) {
        JsonObject root = new JsonObject();
        root.addProperty("chunks", s.chunks);
        root.addProperty("buildings", s.buildings);
        root.addProperty("work_ms", s.workNanos / 1_000_000L);
        root.addProperty("worst_slice_ms", s.worstSliceNanos / 1_000_000L);
        root.addProperty("wall_seconds", (System.nanoTime() - s.started) / 1_000_000_000L);
        JsonObject cities = new JsonObject();
        for (var city : s.counts.entrySet()) {
            JsonObject c = new JsonObject();
            for (var bucket : city.getValue().entrySet()) c.addProperty(bucket.getKey(), bucket.getValue());
            cities.add(city.getKey(), c);
        }
        root.add("cities", cities);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        try {
            Path out = server.getWorldPath(LevelResource.ROOT).resolve("underground_city").resolve("property-survey.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, json, StandardCharsets.UTF_8);
        } catch (Exception error) {
            LOGGER.warn("[UC-PLACES] survey could not be written: {}", error.toString());
        }
        for (var city : s.counts.entrySet()) {
            Map<String, Integer> c = city.getValue();
            StringBuilder tiers = new StringBuilder();
            for (int t = 0; t <= 6; t++) tiers.append(t == 0 ? "" : " ").append(t).append(':').append(c.getOrDefault("tier:" + t, 0));
            LOGGER.info("[UC-PLACES] survey {}: {} buildings, {} targets, tiers {}", city.getKey(), c.getOrDefault("buildings", 0), c.getOrDefault("targets", 0), tiers);
        }
        LOGGER.info("[UC-PLACES] survey done: {} chunks, {} buildings, {} ms of work, worst slice {} ms", s.chunks, s.buildings, s.workNanos / 1_000_000L, s.worstSliceNanos / 1_000_000L);
        ServerPlayer requester = s.requester.isEmpty() ? null : server.getPlayerList().getPlayerByName(s.requester);
        if (requester != null) requester.sendSystemMessage(Component.literal("Property survey done: " + s.buildings + " buildings in " + s.chunks + " chunks."));
    }

    static boolean surveying() { return survey != null; }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("uc_places").requires(source -> source.hasPermission(2));
        root.then(Commands.literal("survey").executes(ctx -> {
            String who = ctx.getSource().getEntity() instanceof ServerPlayer p ? p.getGameProfile().getName() : "";
            String said = startSurvey(ctx.getSource().getServer(), who);
            ctx.getSource().sendSuccess(() -> Component.literal(said), false);
            return 1;
        }));
        root.then(Commands.literal("book").then(Commands.argument("player", StringArgumentType.word()).executes(ctx -> {
            ServerPlayer target = ctx.getSource().getServer().getPlayerList().getPlayerByName(StringArgumentType.getString(ctx, "player"));
            if (target == null) { ctx.getSource().sendFailure(Component.literal("No such player online.")); return 0; }
            List<Entry> entries = book(ctx.getSource().getServer()).of(target.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal(target.getGameProfile().getName() + " remembers " + entries.size() + " places."), false);
            for (Entry e : entries.subList(0, Math.min(12, entries.size())))
                ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, " %s, %s: %s %s tier %d, cased layer %d%s", e.address(), e.city(), e.category(), e.label(), e.tier(), e.layer(), e.marked() ? " (marked)" : "")), false);
            return 1;
        })));
        event.getDispatcher().register(root);
    }

    /** For a log line: every glance and casing counted since the start. */
    static long peeks, casings;
    static String status() { return "glances " + peeks + ", casings " + casings + (survey != null ? ", survey running" : ""); }

    /** Test seam. */
    static void resetForTest() { survey = null; peeks = casings = 0; }
}
