package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.Command;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The people who live and work in the furnished buildings (D-124): a resident for each bed, a worker for each counter or desk, standing where the building's own spots say at the hour the clock says, and
 * remembering a break-in. It is the indoor twin of {@link PopulationService} and has the same discipline: who is where is arithmetic ({@link HouseholdPlan}), a body exists only while a player is near and
 * the chunk is loaded, bodies are never saved, and nothing is simulated that nobody can see. At most {@link #MAX_PER_PLAYER} bodies stand near any one player and {@link #MAX_ACTIVE} in the world; one is added a
 * second; each pass asks Lost Cities about at most {@link #LOOKUPS_PER_PASS} chunks it has not been asked about before.
 *
 * <p>What changes in the world: a resident who is at home is a witness of a break-in ({@link PoliceResponse} counts any city person in sight of the door, and a resident in the building is one), so a night
 * burglary of an occupied house is likelier to bring the police than a midday one of an empty one, and a shop is empty at night and staffed by day; the household remembers a forced door in a saved record
 * (bounded to {@link #MEMORY_LIMIT} buildings) and says so when spoken to, and a resident who saw the thief says so to the thief's face for three active hours.
 *
 * <p>Coming and going (D-129): when the clock moves a person from home to out (or from out to home) while a player is near, they do not simply vanish or appear. A leaver comes out of the
 * building's street door (it opens for them as it would for anybody inside) and walks off down the street; somebody coming home walks up the street to the door, which opens, and goes in. The
 * moment of the hour a person leaves or comes is theirs (spread over the first forty minutes), a person is seen to commute only in the ten minutes after that moment and only once, and a commuter
 * is gone within a minute and a half whatever happens. Nobody commutes past a building nobody is near.
 *
 * <p>What it is not: residents do not walk about the city, open doors other than their street door on the way out or in, buy or sell, own anything the Crew ledger knows of, or respond beyond a
 * line of speech; a building that was never furnished by a package with spots has nobody in it; nobody is simulated while their building is not loaded.
 */
public final class HouseholdService {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int CYCLE = 20, RADIUS_CHUNKS = 2, MAX_PER_PLAYER = 8, MAX_ACTIVE = 24, FAR_BLOCKS = 80, LOOKUPS_PER_PASS = 3, PLAYERS_PER_PASS = 4, MEMORY_LIMIT = 512;
    /** How long a household remembers a forced door, in active ticks (three hours). */
    static final long MEMORY_TICKS = 3L * 72000;

    /** Active bodies: entity uuid to who they are and where they live. */
    private record Active(long building, String type, String city, long person, int chunkX, int chunkZ, int streetY, long worldSeed) {}
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    /** A body on its way out of its building ({@code entering} false) or home to it: the street door (its lower half) and where the body is walking to. */
    record Commute(boolean entering, BlockPos door, BlockPos target) {}
    private static final Map<UUID, Commute> COMMUTERS = new HashMap<>();
    /** Who has already been seen to commute this hour ({@code person ^ hour}), bounded. */
    private static final java.util.Set<Long> COMMUTED = new java.util.LinkedHashSet<>();
    /** A person leaves or comes home at a moment of the first forty minutes of the hour, and is seen to only in the ten minutes after it. */
    static final int COMMUTE_SPREAD_MINUTES = 40, COMMUTE_WINDOW_MINUTES = 10;
    static long commutersOut, commutersIn;

    static long passes, nanosTotal, nanosWorst, spawned, removed, spotMisses, lookups, activePeak;

    // ------------------------------------------------------------------------------------------------ memory of a forced door

    /** What a household remembers of the last forced door: who, when (active ticks), and whether anybody at home saw them. */
    record Mem(UUID intruder, long tick, boolean seen) {}

    static final class Memory extends SavedData {
        static final String KEY = "underground_city_household_memory";
        final Map<Long, Mem> byBuilding = new LinkedHashMap<>();

        static Memory load(CompoundTag root) {
            Memory memory = new Memory();
            ListTag list = root.getList("buildings", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && i < MEMORY_LIMIT; i++) {
                CompoundTag t = list.getCompound(i);
                if (t.hasUUID("who")) memory.byBuilding.put(t.getLong("key"), new Mem(t.getUUID("who"), t.getLong("tick"), t.getBoolean("seen")));
            }
            return memory;
        }

        @Override public CompoundTag save(CompoundTag root) {
            ListTag list = new ListTag();
            byBuilding.forEach((key, mem) -> {
                CompoundTag t = new CompoundTag();
                t.putLong("key", key); t.putUUID("who", mem.intruder()); t.putLong("tick", mem.tick()); t.putBoolean("seen", mem.seen());
                list.add(t);
            });
            root.put("buildings", list);
            return root;
        }

        void remember(long building, Mem mem) {
            byBuilding.remove(building);
            byBuilding.put(building, mem);   // most recent last
            while (byBuilding.size() > MEMORY_LIMIT) byBuilding.remove(byBuilding.keySet().iterator().next());
            setDirty();
        }
    }

    static Memory memory(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Memory::load, Memory::new, Memory.KEY);
    }

    @SubscribeEvent
    public void breach(BuildingBreachEvent event) {
        ServerPlayer actor = event.actor();
        if (actor == null || !(actor.level() instanceof ServerLevel level) || event.outcome() == BuildingBreachEvent.Outcome.NOISE) return;
        try {
            Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.at(level, event.door());
            if (found.isEmpty() || BuildingSpots.INSTANCE.forType(found.get().type()) == null) return;   // only furnished buildings have households
            long key = found.get().key();
            boolean seen = !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(event.door()).inflate(24), person -> person.homeKey() == key && person.isAlive() && person.hasLineOfSight(actor)).isEmpty();
            memory(level).remember(key, new Mem(actor.getUUID(), LockService.clock(level.getServer()), seen));
        } catch (RuntimeException error) {
            LOGGER.error("A household could not note a break-in", error);
        }
    }

    // ------------------------------------------------------------------------------------------------ what they say

    private static final List<String> ACCUSE = List.of("You. I know your face. You were in my house. Get away from me.", "I saw you at my door. The police know what you look like.", "Don't you come near me. I saw what you did to my lock.");
    private static final List<String> BROKEN_INTO = List.of("Somebody forced our lock. We've had the police round, and a locksmith.", "I'm not leaving the door on the latch any more, not since the break-in.", "They picked the lock and nobody saw a thing. Keep an eye on your own door.");
    private static final List<String> HOME_MORNING = List.of("Kettle's on. Early start for most round here.", "Morning. The bread comes at seven if you want it still warm.", "I'll be at work soon. The street fills up by nine.");
    private static final List<String> HOME_DAY = List.of("Day off, or I'd not be home. Mind the step.", "Quiet in the day. Everyone's at work or at the market.", "I keep the door locked now. Everyone does, here.");
    private static final List<String> HOME_EVENING = List.of("Supper's nearly ready. It's a long day, the city.", "Evenings are the best of it. Everyone's home and the lights come on.", "You're out late. Take care on the road home.");
    private static final List<String> WORK = List.of("Can I help you? We're open until six.", "What's on the shelves is what the city's suppliers sent. When it's gone, it's gone till the next delivery.", "Busy day. Mind the counter.");
    private static final List<String> FENCE = List.of("Keep your voice down. If it's marked, I might be interested. If it's not, I'm not.", "Late, isn't it. Shut the door behind you.", "I don't know you. That's how I like it. What have you got?");

    /** What this resident or worker says now: a grudge if they saw a thief and it is the thief, a remark if their door was forced, otherwise a line of their day. */
    static String say(ServerPlayer player, TownspersonEntity body, boolean returning) {
        ServerLevel level = player.serverLevel();
        Mem mem = memory(level).byBuilding.get(body.homeKey());
        long now = LockService.clock(level.getServer());
        boolean recent = mem != null && now - mem.tick() >= 0 && now - mem.tick() < MEMORY_TICKS;
        long h = PopulationPlan.mix(body.person() ^ PopulationPlan.dayOf(dayTime(level)) * 31 + (returning ? 7 : 0));
        List<String> lines;
        if (recent && mem.seen() && mem.intruder().equals(player.getUUID())) lines = ACCUSE;
        else if (recent) lines = BROKEN_INTO;
        else if (body.role().equals("fence")) lines = FENCE;
        else if (body.role().equals("worker")) {
            // a shopkeeper whose supplied shelf runs low asks for it (D-164): the opportunity comes from the shop, not from a board
            String wanted = wanted(level, body);
            if (wanted != null) return wanted;
            lines = WORK;
        }
        else {
            int hour = PopulationPlan.hourOf(dayTime(level));
            lines = hour < 12 ? HOME_MORNING : hour < 18 ? HOME_DAY : HOME_EVENING;
        }
        return lines.get((int) Math.floorMod(h >>> 8, (long) lines.size()));
    }

    /**
     * What a shopkeeper is short of (D-164): the supplied line of their counter with the emptiest shelf, when it is below a third full, as a line asking for it with what they pay; or null.
     * Read from the same shared shelf a supplier fills and the city's customers empty.
     */
    static String wanted(ServerLevel level, TownspersonEntity body) {
        var shop = ShopCounters.shopOf(body);
        if (shop.isEmpty()) return null;
        WorkOrderLedger ledger = WorkOrderLedger.get(level.getServer());
        RegionalStock lowest = null;
        double share = 1.0;
        for (RegionalStock line : RegionalCatalog.INSTANCE.forOutlet(shop.get().city(), shop.get().outlet())) {
            if (RegionalCatalog.INSTANCE.supply(line.id()) == null) continue;
            double s = ledger.regionalUnits(line) / (double) line.capacity();
            if (s < share) { share = s; lowest = line; }
        }
        if (lowest == null || share >= 1.0 / 3) return null;
        String item = lowest.registryItem().getDescription().getString();
        int pays = RegionalCatalog.INSTANCE.supply(lowest.id()).pays();
        return "We're nearly out of " + item + ". Bring some to the counter and I'll pay " + pays + " a unit.";
    }

    // ------------------------------------------------------------------------------------------------ the bodies

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 0) return;
        if (BuildingSpots.INSTANCE.size() == 0 || event.getServer().getPlayerList().getPlayerCount() == 0) return;
        try { pass(event.getServer().overworld()); }
        catch (RuntimeException error) { LOGGER.error("Household pass failed", error); }
    }

    /** The clock the schedule reads: the world's, unless a test has set one of its own. */
    static volatile Long dayTimeOverride;
    static long dayTime(ServerLevel level) { Long set = dayTimeOverride; return set != null ? set : level.getDayTime(); }

    static void pass(ServerLevel level) {
        List<ServerPlayer> players = new ArrayList<>(level.players());
        players.removeIf(p -> p.isSpectator() || !p.isAlive());
        pass(level, players);
    }

    /** One pass for the given players (creative ones included: an operator walking about sees the people too). Visible for the tests, whose fake players are not in the level's player list. */
    static void pass(ServerLevel level, List<ServerPlayer> players) {
        long started = System.nanoTime();
        removeOutOfDate(level, players);
        if (!players.isEmpty()) {
            int start = (int) (level.getGameTime() / CYCLE % players.size());
            for (int i = 0; i < Math.min(PLAYERS_PER_PASS, players.size()); i++) {
                ServerPlayer player = players.get((start + i) % players.size());
                commute(level, player, players);
                topUp(level, player);
            }
        }
        long took = System.nanoTime() - started;
        passes++; nanosTotal += took; nanosWorst = Math.max(nanosWorst, took); activePeak = Math.max(activePeak, ACTIVE.size());
    }

    private static ServerPlayer nearest(List<ServerPlayer> players, TownspersonEntity body, double within) {
        ServerPlayer best = null;
        double bestDistance = within * within;
        for (ServerPlayer p : players) {
            double d = p.distanceToSqr(body);
            if (p.level() == body.level() && d <= bestDistance) { best = p; bestDistance = d; }
        }
        return best;
    }

    /** Removes bodies that are gone, far from everybody, or whose person has gone out and is not being looked at closely. */
    static void removeOutOfDate(ServerLevel level, List<ServerPlayer> players) {
        for (Iterator<Map.Entry<UUID, Active>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Active a = entry.getValue();
            if (!(level.getEntity(entry.getKey()) instanceof TownspersonEntity body) || !body.isAlive()) { it.remove(); COMMUTERS.remove(entry.getKey()); continue; }
            ServerPlayer nearest = nearest(players, body, FAR_BLOCKS);
            Commute commute = COMMUTERS.get(entry.getKey());
            if (commute != null) {
                if (!commuteStep(level, body, commute, nearest)) { body.discard(); it.remove(); COMMUTERS.remove(entry.getKey()); removed++; }
                continue;
            }
            boolean away = false;
            if (nearest != null) {
                HouseholdPlan.Resident who = resident(a);
                BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(a.type());
                away = who == null || plan == null || whereNow(who, plan, a.worldSeed(), dayTime(level)) == HouseholdPlan.AWAY;
                if (away && nearest.distanceToSqr(body) < 12 * 12) away = false;   // nobody leaves in front of a player's nose
            }
            if (nearest == null || away) { body.discard(); it.remove(); removed++; }
        }
    }

    private static HouseholdPlan.Resident resident(Active a) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(a.type());
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(a.city());
        if (plan == null || def == null) return null;
        for (HouseholdPlan.Resident who : HouseholdPlan.residents(a.city(), a.worldSeed(), a.building(), plan, def.names())) if (who.id() == a.person()) return who;
        return null;
    }

    private static int near(ServerLevel level, ServerPlayer player) {
        int count = 0;
        for (UUID id : ACTIVE.keySet()) if (level.getEntity(id) instanceof TownspersonEntity body && body.distanceToSqr(player) <= (double) FAR_BLOCKS * FAR_BLOCKS) count++;
        return count;
    }

    private record Candidate(CityBuildings.Building building, BuildingSpots.Plan plan, HouseholdPlan.Resident who, int spot) {}

    private static void topUp(ServerLevel level, ServerPlayer player) {
        if (ACTIVE.size() >= MAX_ACTIVE || near(level, player) >= MAX_PER_PLAYER) return;
        long day = PopulationPlan.dayOf(dayTime(level));
        int hour = PopulationPlan.hourOf(dayTime(level));
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Active a : ACTIVE.values()) out.add(a.person());
        ChunkPos here = player.chunkPosition();
        int budget = LOOKUPS_PER_PASS;
        List<Candidate> candidates = new ArrayList<>();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
            int cx = here.x + dx, cz = here.z + dz;
            if (level.getChunkSource().getChunkNow(cx, cz) == null || !CityBuildings.INSTANCE.askable(level, cx, cz)) continue;
            if (!CityBuildings.INSTANCE.known(cx, cz)) { if (budget-- <= 0) continue; lookups++; }
            Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
            if (found.isEmpty()) continue;
            CityBuildings.Building building = found.get();
            BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(building.type());
            PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(building.city());
            if (plan == null || def == null) continue;
            FenceService.Directory.note(level, building, plan);   // a fence's building, once anybody has been near it, is somewhere a Fence Contact can point (D-143)
            for (HouseholdPlan.Resident who : HouseholdPlan.residents(building.city(), level.getSeed(), building.key(), plan, def.names())) {
                if (out.contains(who.id())) continue;
                if (CityHomes.spotLet(level.getSeed(), building, plan, plan.spots().get(who.spot()))) continue;   // their flat or house is to let: nobody of the household lives there (D-127)
                int spot = whereNow(who, plan, level.getSeed(), dayTime(level));
                if (spot != HouseholdPlan.AWAY) candidates.add(new Candidate(building, plan, who, spot));
            }
        }
        if (candidates.isEmpty()) return;
        long salt = PopulationPlan.mix(level.getSeed() + day * 24 + hour);
        candidates.sort(Comparator.comparingLong(c -> PopulationPlan.mix(c.who().id() ^ salt)));
        Candidate chosen = candidates.get(0);
        Optional<BlockPos> stand = standAt(level, chosen);
        if (stand.isEmpty()) { spotMisses++; return; }
        TownspersonEntity body = UndergroundCity.TOWNSPERSON.get().create(level);
        if (body == null) return;
        PopulationPlan.Person person = new PopulationPlan.Person(chosen.who().id(), chosen.who().name(), chosen.who().skin(), chosen.who().role(), chosen.building().city(), 0, 0);
        body.moveTo(stand.get().getX() + 0.5, stand.get().getY(), stand.get().getZ() + 0.5, (float) (PopulationPlan.mix(chosen.who().id()) % 360), 0f);
        body.becomeResident(person, stand.get(), chosen.building().key());
        if (level.addFreshEntity(body)) {
            ACTIVE.put(body.getUUID(), new Active(chosen.building().key(), chosen.building().type(), chosen.building().city(), chosen.who().id(), chosen.building().chunkX(), chosen.building().chunkZ(), chosen.building().streetY(), level.getSeed()));
            spawned++;
        }
    }

    // ------------------------------------------------------------------------------------------------ coming and going (D-129)

    /** The minute of {@code hour} at which this person leaves or comes home when the hour moves them. */
    static int commuteMinute(long person, long day, int hour) {
        return (int) Math.floorMod(PopulationPlan.mix(person ^ PopulationPlan.mix(day * 24 + hour)) >>> 7, (long) COMMUTE_SPREAD_MINUTES);
    }

    /**
     * Where a person is at the world time {@code time}: the plan's answer for the hour, except that in an hour that moves them out or home they are still where they were until their own minute of it
     * (so a leaver is there to be seen leaving, and somebody coming home is not already at home before they have walked in).
     */
    static int whereNow(HouseholdPlan.Resident who, BuildingSpots.Plan plan, long worldSeed, long time) {
        long day = PopulationPlan.dayOf(time), before = PopulationPlan.dayOf(time - 1000);
        int hour = PopulationPlan.hourOf(time), hourBefore = PopulationPlan.hourOf(time - 1000);
        int now = HouseholdPlan.where(who, plan, worldSeed, hour, day), then = HouseholdPlan.where(who, plan, worldSeed, hourBefore, before);
        if ((now == HouseholdPlan.AWAY) == (then == HouseholdPlan.AWAY)) return now;
        int minute = (int) (Math.floorMod(time, 1000L) * 60 / 1000);
        return minute < commuteMinute(who.id(), day, hour) ? then : now;
    }

    /** The street door of a plan: its first door spot, or null. */
    private static BuildingSpots.Spot streetDoor(BuildingSpots.Plan plan) {
        for (BuildingSpots.Spot spot : plan.spots()) if (spot.kind().equals("door")) return spot;
        return null;
    }

    private static net.minecraft.core.Direction facing(BuildingSpots.Spot spot) {
        net.minecraft.core.Direction d = net.minecraft.core.Direction.byName(spot.facing());
        return d == null || d.getAxis().isVertical() ? net.minecraft.core.Direction.SOUTH : d;
    }

    /** Starts at most one commute round this player: somebody of a building near them whose hour has just moved them out or home. */
    private static void commute(ServerLevel level, ServerPlayer player, List<ServerPlayer> players) {
        if (ACTIVE.size() >= MAX_ACTIVE || near(level, player) >= MAX_PER_PLAYER) return;
        long time = dayTime(level);
        long day = PopulationPlan.dayOf(time), before = PopulationPlan.dayOf(time - 1000);
        int hour = PopulationPlan.hourOf(time), hourBefore = PopulationPlan.hourOf(time - 1000);
        int minute = (int) (Math.floorMod(time, 1000L) * 60 / 1000);
        java.util.Set<Long> out = new java.util.HashSet<>();
        for (Active a : ACTIVE.values()) out.add(a.person());
        ChunkPos here = player.chunkPosition();
        for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
            int cx = here.x + dx, cz = here.z + dz;
            if (level.getChunkSource().getChunkNow(cx, cz) == null || !CityBuildings.INSTANCE.known(cx, cz)) continue;   // the household pass asks Lost Cities; this one never does
            Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
            if (found.isEmpty()) continue;
            CityBuildings.Building building = found.get();
            BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(building.type());
            PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(building.city());
            BuildingSpots.Spot door = plan == null ? null : streetDoor(plan);
            if (door == null || def == null) continue;
            for (HouseholdPlan.Resident who : HouseholdPlan.residents(building.city(), level.getSeed(), building.key(), plan, def.names())) {
                if (out.contains(who.id()) || CityHomes.spotLet(level.getSeed(), building, plan, plan.spots().get(who.spot()))) continue;
                int now = HouseholdPlan.where(who, plan, level.getSeed(), hour, day), then = HouseholdPlan.where(who, plan, level.getSeed(), hourBefore, before);
                boolean leaving = then != HouseholdPlan.AWAY && now == HouseholdPlan.AWAY, coming = then == HouseholdPlan.AWAY && now != HouseholdPlan.AWAY;
                if (!leaving && !coming) continue;
                long key = who.id() ^ PopulationPlan.mix(day * 24 + hour);
                int at = commuteMinute(who.id(), day, hour);
                if (minute < at || minute >= at + COMMUTE_WINDOW_MINUTES || COMMUTED.contains(key)) continue;
                COMMUTED.add(key);
                while (COMMUTED.size() > 512) COMMUTED.remove(COMMUTED.iterator().next());
                if (startCommute(level, building, door, who, coming, players)) return;
            }
        }
    }

    /** A place a person can stand on the street in front of the door: a street spot of the chunk ahead if there is one, else the nearest floor two to six steps out from the door. */
    private static Optional<BlockPos> streetTarget(ServerLevel level, BlockPos outside, net.minecraft.core.Direction out, long person, List<ServerPlayer> players) {
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(person);
        for (int ahead : new int[]{12, 24}) {
            Optional<BlockPos> spot = PopulationService.findSpot(level, new ChunkPos(outside.relative(out, ahead)), random, players);
            if (spot.isPresent() && spot.get().distSqr(outside) >= 16) return spot;
        }
        for (int k = 2; k <= 6; k++) for (int dy : new int[]{0, -1, 1}) {
            BlockPos p = outside.relative(out, k).above(dy);
            if (PopulationService.standable(level, p)) return Optional.of(p);
        }
        return Optional.empty();
    }

    private static boolean startCommute(ServerLevel level, CityBuildings.Building building, BuildingSpots.Spot doorSpot, HouseholdPlan.Resident who, boolean coming, List<ServerPlayer> players) {
        net.minecraft.core.Direction out = facing(doorSpot);
        BlockPos door = new BlockPos(building.chunkX() * 16 + doorSpot.x(), building.streetY() + doorSpot.y(), building.chunkZ() * 16 + doorSpot.z());
        BlockPos inside = door.relative(out.getOpposite()), outside = door.relative(out);
        BlockPos outsideFeet = PopulationService.standable(level, outside) ? outside : PopulationService.standable(level, outside.below()) ? outside.below() : null;
        Optional<BlockPos> street = streetTarget(level, outsideFeet != null ? outsideFeet : outside, out, who.id(), players);
        if (street.isEmpty() || outsideFeet == null || (!coming && !PopulationService.standable(level, inside))) { spotMisses++; return false; }
        BlockPos from = coming ? street.get() : inside, to = coming ? outsideFeet : street.get();
        TownspersonEntity body = UndergroundCity.TOWNSPERSON.get().create(level);
        if (body == null) return false;
        PopulationPlan.Person person = new PopulationPlan.Person(who.id(), who.name(), who.skin(), who.role(), building.city(), 0, 0);
        body.moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, out.getOpposite().toYRot() + (coming ? 0 : 180), 0f);
        body.becomeCommuter(person, to, building.key());
        if (!level.addFreshEntity(body)) return false;
        ACTIVE.put(body.getUUID(), new Active(building.key(), building.type(), building.city(), who.id(), building.chunkX(), building.chunkZ(), building.streetY(), level.getSeed()));
        COMMUTERS.put(body.getUUID(), new Commute(coming, door, to));
        spawned++;
        if (coming) commutersIn++; else { commutersOut++; openDoor(level, door); }
        return true;
    }

    /** Opens a street door for somebody of the household on the way out or in, as a person inside opens it to leave ({@link BuildingGuard}); it shuts again a few seconds later. */
    static void openDoor(ServerLevel level, BlockPos door) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(door);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock block)) return;
        PropertyLocks.get(level.getServer()).exitOpen(door, LockService.clock(level.getServer()) + BuildingGuard.EXIT_TICKS);
        if (!state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) block.setOpen(null, level, state, door, true);
    }

    /**
     * Opens a street door for a person of the street going in or coming out (D-162): it shuts again after {@code ticks}, far sooner than a door somebody leaving by opens, so nobody slips in
     * behind. The street service only asks when no player stands close to the door.
     */
    static void openDoorBriefly(ServerLevel level, BlockPos door, int ticks) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(door);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock block)) return;
        PropertyLocks.get(level.getServer()).exitOpen(door, LockService.clock(level.getServer()) + ticks);
        if (!state.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) block.setOpen(null, level, state, door, true);
    }

    /** One step of a commute; false when the body is done (home through the door, or out of sight down the street). */
    private static boolean commuteStep(ServerLevel level, TownspersonEntity body, Commute commute, ServerPlayer nearest) {
        if (nearest == null) return false;                                                     // nobody to see them: they are simply out, or in
        double left = body.distanceToSqr(commute.target().getX() + 0.5, commute.target().getY(), commute.target().getZ() + 0.5);
        if (commute.entering()) {
            if (body.distanceToSqr(commute.door().getX() + 0.5, commute.door().getY(), commute.door().getZ() + 0.5) <= 2.6 * 2.6) { openDoor(level, commute.door()); return false; }
            return true;
        }
        if (body.distanceToSqr(commute.door().getX() + 0.5, commute.door().getY(), commute.door().getZ() + 0.5) <= 2.0 * 2.0) openDoor(level, commute.door());   // keep it open while they pass
        if (left <= 1.8 * 1.8) {
            body.errand(null, 1.0);                                                             // there: they stroll, and go once nobody is close
            return nearest.distanceToSqr(body) <= 24 * 24;
        }
        return true;
    }

    /** The place a person stands for a spot: the spot itself if somebody can stand there (a seat), else the nearest free floor beside it (a counter, a bed, a stove). Never outside the building's chunk. */
    static Optional<BlockPos> standAt(ServerLevel level, Candidate c) {
        BuildingSpots.Spot spot = c.plan().spots().get(c.spot());
        int baseX = c.building().chunkX() * 16 + spot.x(), baseZ = c.building().chunkZ() * 16 + spot.z(), y = c.building().streetY() + spot.y();
        int[][] around = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] d : around) {
            BlockPos feet = new BlockPos(baseX + d[0], y, baseZ + d[1]);
            if (feet.getX() >> 4 != c.building().chunkX() || feet.getZ() >> 4 != c.building().chunkZ()) continue;
            if (PopulationService.standable(level, feet)) return Optional.of(feet);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ status and seams

    static String status() {
        double average = passes == 0 ? 0 : nanosTotal / (double) passes / 1000.0;
        return String.format(Locale.ROOT, "indoors now %d (peak %d, cap %d total / %d per player), commuting now %d (out %d, home %d); passes %d, mean %.0f us, worst %d us; Lost Cities lookups %d; spawned %d, removed %d, spots missed %d; furnished plans %d",
            ACTIVE.size(), activePeak, MAX_ACTIVE, MAX_PER_PLAYER, COMMUTERS.size(), commutersOut, commutersIn, passes, average, nanosWorst / 1000, lookups, spawned, removed, spotMisses, BuildingSpots.INSTANCE.size());
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_household").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status()), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    static int activeCount() { return ACTIVE.size(); }

    /** The furnished building type ({@code namespace:id}) a household body belongs to, or null when it is not one of the bodies this service keeps. */
    static String typeOf(TownspersonEntity body) { Active a = ACTIVE.get(body.getUUID()); return a == null ? null : a.type(); }

    /** True when the body is a household person whose post (the spot they were placed for) is a spot of this kind in their building's plan, a counter or a desk (D-126). */
    static boolean servesAt(TownspersonEntity body, String kind) {
        Active a = ACTIVE.get(body.getUUID());
        if (a == null || COMMUTERS.containsKey(body.getUUID())) return false;   // on the way to or from work: not behind a counter
        HouseholdPlan.Resident who = resident(a);
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(a.type());
        return who != null && plan != null && who.spot() >= 0 && who.spot() < plan.spots().size() && plan.spots().get(who.spot()).kind().equals(kind);
    }
    static void resetForTest() { ACTIVE.clear(); COMMUTERS.clear(); COMMUTED.clear(); dayTimeOverride = null; passes = nanosTotal = nanosWorst = spawned = removed = spotMisses = lookups = activePeak = commutersOut = commutersIn = 0; }
    static int commuterCount() { return COMMUTERS.size(); }
    static Commute commuteOf(TownspersonEntity body) { return COMMUTERS.get(body.getUUID()); }
}
