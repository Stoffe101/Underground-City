package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Police on the beat (D-155). While somebody is in a city, now and then an officer (two in Ironhaven) walks a beat of a few street spots near them for a couple of minutes and goes, at the
 * city's own pace ({@link CityLaws.Patrol}). An officer on the beat sees as officers do (D-146): within {@link #SIGHT} blocks and with a line of sight, so a wall or a shut door hides a thief.
 * Somebody they see at work on a city building's lock or safe, or breaking in, brings that officer at once, for that person alone: a partner standing beside them is nobody's business. A thief
 * at a lock is told once when an officer on the beat is near and cannot see them yet, since the lock screen hides the street.
 *
 * <p>Bounded: one patrol per city and only while somebody is in it, its officers unsaved and sent away when nobody is near, nothing held over a restart, and never a chunk loaded.
 */
public final class PolicePatrols {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Server ticks between two passes (one second). */
    static final int CYCLE = 20;
    /** How many street spots a beat has, and within how many chunks of the person it is laid out round. */
    static final int BEAT_SPOTS = 5, SPOT_CHUNKS = 3;
    /** How near an officer comes to a spot before walking on to the next, how fast they walk, and how far from everybody a patrol may be before it goes. */
    static final double REACHED = 2.0, SPEED = 0.7, FORGET = 96;
    /** How far an officer on the beat notices somebody at a lock (less than a chasing officer's {@link PoliceResponse#SIGHT}: they are walking, not hunting), and a crouched thief with Soft Steps. */
    static final double SIGHT = 24, SOFT_STEPS_SIGHT = 12;
    /** How near an officer who cannot see a thief at a lock comes before the thief is told. */
    static final double WARN_RANGE = 40;

    /** One patrol of a city: its officers, the spots of its beat and which spot each officer walks to next. Held in memory only. */
    static final class Patrol {
        final String city;
        final ResourceKey<Level> dimension;
        final List<BlockPos> beat;
        final long endsAt;
        final List<UUID> officers = new ArrayList<>();
        final List<Integer> next = new ArrayList<>();
        final Set<UUID> warned = new HashSet<>();

        Patrol(String city, ResourceKey<Level> dimension, List<BlockPos> beat, long endsAt) { this.city = city; this.dimension = dimension; this.beat = beat; this.endsAt = endsAt; }

        void remove(UUID officer) {
            int i = officers.indexOf(officer);
            if (i >= 0) { officers.remove(i); next.remove(i); }
        }
    }

    /** What a person is doing that an officer would stop: a lock or a search, at a city building. */
    record Work(CityBuildings.Building building, BlockPos at, boolean lock) {}

    private static final Map<String, Patrol> OUT = new LinkedHashMap<>();
    /** When the next patrol of each city with somebody in it is due (active ticks). */
    private static final Map<String, Long> NEXT = new HashMap<>();
    // measurements for /uc_police patrols
    static long started, caught, warnings, noSpot;

    /** Where officers appear and walk: a street spot (a test replaces it with spots in its arena). */
    static volatile PoliceResponse.SpotFinder spots = PopulationService::findSpot;

    /** Which city a place is in (a test puts its arena in one). */
    @FunctionalInterface
    interface CityAt { Optional<String> at(ServerLevel level, BlockPos pos); }
    static volatile CityAt cityAt = (level, pos) -> CityRegions.INSTANCE.cityAt(level, pos);

    /** Who is online (a test's players are not on the server's list). */
    static volatile java.util.function.Function<MinecraftServer, List<ServerPlayer>> online = server -> server.getPlayerList().getPlayers();

    // ------------------------------------------------------------------------------------------------ the passes

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 7) return;
        try { pass(event.getServer()); }
        catch (RuntimeException error) { LOGGER.error("Police patrol pass failed", error); }
    }

    /** One pass (once a second): walks and watches with every patrol out, ends the ones that are over and sends out the ones that are due. Visible for the tests. */
    static void pass(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long now = LockService.clock(server);
        Map<String, List<ServerPlayer>> present = new LinkedHashMap<>();
        for (ServerPlayer player : online.apply(server)) {
            if (!policed(player) || player.level() != level) continue;
            cityAt.at(level, player.blockPosition()).ifPresent(city -> present.computeIfAbsent(city, k -> new ArrayList<>()).add(player));
        }
        for (Iterator<Patrol> it = OUT.values().iterator(); it.hasNext(); ) {
            Patrol patrol = it.next();
            boolean over;
            try { over = step(server, level, patrol, now); }
            catch (RuntimeException error) { LOGGER.error("Police patrol failed; it is sent home", error); over = true; }
            if (over) { end(level, patrol); it.remove(); NEXT.put(patrol.city, now + pace(patrol.city) * 20L); }
        }
        // a city nobody is in forgets its next patrol, so that the first one after somebody arrives comes after a while and not at once
        NEXT.keySet().removeIf(city -> !present.containsKey(city) && !OUT.containsKey(city));
        for (var entry : present.entrySet()) {
            String city = entry.getKey();
            CityLaws.Law law = CityLaws.INSTANCE.find(city);
            if (law == null || law.patrol() == null || law.patrol().officers() <= 0 || OUT.containsKey(city)) continue;
            Long due = NEXT.get(city);
            if (due == null) { NEXT.put(city, now + law.patrol().everySeconds() * 10L); continue; }   // half the pace after somebody arrives
            if (now < due) continue;
            Patrol patrol = start(level, city, law.patrol(), entry.getValue(), now);
            if (patrol == null) { noSpot++; NEXT.put(city, now + 10 * 20L); }
            else OUT.put(city, patrol);
        }
    }

    static boolean policed(ServerPlayer player) { return player.isAlive() && !player.isCreative() && !player.isSpectator(); }

    private static int pace(String city) {
        CityLaws.Law law = CityLaws.INSTANCE.find(city);
        return law == null || law.patrol() == null ? 600 : law.patrol().everySeconds();
    }

    /** Lays out a beat round one of the people in the city and puts its officers on it, or returns null when the streets round them gave too few spots this time. Visible for the tests. */
    static Patrol start(ServerLevel level, String city, CityLaws.Patrol pace, List<ServerPlayer> players, long now) {
        ServerPlayer around = players.get((int) Math.floorMod(now / CYCLE, (long) players.size()));
        RandomSource random = RandomSource.create(PopulationPlan.mix(now ^ around.getUUID().getLeastSignificantBits()));
        ChunkPos home = new ChunkPos(around.blockPosition());
        List<BlockPos> beat = new ArrayList<>();
        for (int tries = 0; tries < BEAT_SPOTS * 6 && beat.size() < BEAT_SPOTS; tries++) {
            ChunkPos chunk = new ChunkPos(home.x + random.nextInt(2 * SPOT_CHUNKS + 1) - SPOT_CHUNKS, home.z + random.nextInt(2 * SPOT_CHUNKS + 1) - SPOT_CHUNKS);
            spots.find(level, chunk, random, players).filter(spot -> !beat.contains(spot) && cityAt.at(level, spot).map(city::equals).orElse(false)).ifPresent(beat::add);
        }
        if (beat.size() < 2) return null;
        Patrol patrol = new Patrol(city, level.dimension(), beat, now + pace.beatSeconds() * 20L);
        for (int i = 0; i < pace.officers(); i++) {
            BlockPos at = beat.get(i % beat.size());
            TownspersonEntity officer = UndergroundCity.TOWNSPERSON.get().create(level);
            if (officer == null) continue;
            officer.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0f);
            PoliceResponse.dress(level, officer, city, PopulationPlan.mix(now + i), random);
            int first = (i + 1) % beat.size();
            officer.errand(beat.get(first), SPEED);
            officer.leaveIn(pace.beatSeconds() * 20L + 600L);   // a backstop: gone even if this patrol were forgotten
            if (level.addFreshEntity(officer)) { patrol.officers.add(officer.getUUID()); patrol.next.add(first); }
        }
        if (patrol.officers.isEmpty()) return null;
        started++;
        return patrol;
    }

    /** One pass of one patrol: walks the beat and watches. True when the patrol is over. */
    private static boolean step(MinecraftServer server, ServerLevel level, Patrol patrol, long now) {
        List<TownspersonEntity> officers = officers(level, patrol);
        if (officers.isEmpty() || now >= patrol.endsAt) return true;
        List<ServerPlayer> players = new ArrayList<>();
        boolean anyoneNear = false;
        for (ServerPlayer player : online.apply(server)) {
            if (player.level() != level) continue;
            players.add(player);
            for (TownspersonEntity officer : officers) if (officer.distanceTo(player) <= FORGET) { anyoneNear = true; break; }
        }
        if (!anyoneNear) return true;
        for (int i = 0; i < officers.size(); i++) {
            TownspersonEntity officer = officers.get(i);
            int spot = patrol.next.get(i);
            if (officer.blockPosition().distSqr(patrol.beat.get(spot)) <= REACHED * REACHED) { spot = (spot + 1) % patrol.beat.size(); patrol.next.set(i, spot); }
            officer.errand(patrol.beat.get(spot), SPEED);
        }
        for (ServerPlayer player : players) {
            if (!policed(player)) continue;
            Work work = atWork(level, player);
            if (work == null) continue;
            TownspersonEntity seer = seer(officers, player);
            if (seer != null) {
                catchAtWork(level, patrol, seer, player, work);
                return patrol.officers.isEmpty();
            }
            if (patrol.warned.contains(player.getUUID())) continue;
            for (TownspersonEntity officer : officers) {
                if (officer.distanceTo(player) > WARN_RANGE) continue;
                patrol.warned.add(player.getUUID());
                warnings++;
                Notice.send(player, Notice.Kind.WARN, "Footsteps", "An officer on the beat is close by and cannot see you yet.");
                break;
            }
        }
        return false;
    }

    /** What the person is at, if it is a city building's lock or a search in one; null otherwise. */
    static Work atWork(ServerLevel level, ServerPlayer player) {
        LockService.Active lock = LockService.activeFor(player.getUUID());
        if (lock != null && lock.session.active()) {
            Optional<CityBuildings.Building> building = CityBuildings.INSTANCE.at(level, lock.target.pos());
            if (building.isPresent()) return new Work(building.get(), lock.target.pos(), true);
        }
        BurglarySearch.Active search = BurglarySearch.activeFor(player.getUUID());
        if (search != null) {
            Optional<CityBuildings.Building> building = CityBuildings.INSTANCE.at(level, search.pos());
            if (building.isPresent()) return new Work(building.get(), search.pos(), false);
        }
        return null;
    }

    /** How far an officer on the beat notices this person: less for a crouched thief with Soft Steps (D-143). */
    static double sight(ServerPlayer player) { return player.isCrouching() && Thievery.has(player, "thief.soft_steps") ? SOFT_STEPS_SIGHT : SIGHT; }

    private static TownspersonEntity seer(List<TownspersonEntity> officers, ServerPlayer player) {
        double sight = sight(player);
        for (TownspersonEntity officer : officers) if (officer.level() == player.level() && officer.distanceTo(player) <= sight && officer.hasLineOfSight(player)) return officer;
        return null;
    }

    /** The officer on the beat who sees this person now, if any (for a break-in the moment it happens). */
    static TownspersonEntity seer(ServerLevel level, ServerPlayer player) {
        for (Patrol patrol : OUT.values()) {
            if (patrol.dimension != level.dimension()) continue;
            TownspersonEntity seer = seer(officers(level, patrol), player);
            if (seer != null) return seer;
        }
        return null;
    }

    /** Takes an officer off the beat (they are on a response now). */
    static void release(TownspersonEntity officer) {
        for (Patrol patrol : OUT.values()) patrol.remove(officer.getUUID());
    }

    private static void catchAtWork(ServerLevel level, Patrol patrol, TownspersonEntity officer, ServerPlayer player, Work work) {
        patrol.remove(officer.getUUID());
        caught++;
        // the lock screen hides the street: it is put away (nothing is lost, as when the thief lets go) so the thief sees the officer coming
        if (work.lock()) LockService.interrupt(player, "An officer on the beat has seen you at the lock.");
        PoliceResponse.seenByPatrol(player, level, work.building(), work.at(), officer, work.lock() ? "at the lock" : "going through the place");
    }

    private static List<TownspersonEntity> officers(ServerLevel level, Patrol patrol) {
        List<TownspersonEntity> out = new ArrayList<>();
        for (int i = patrol.officers.size() - 1; i >= 0; i--) {
            if (level.getEntity(patrol.officers.get(i)) instanceof TownspersonEntity officer && officer.isAlive()) out.add(0, officer);
            else { patrol.officers.remove(i); patrol.next.remove(i); }
        }
        return out;
    }

    /** Ends a patrol: its officers walk off and are gone a few seconds later. */
    private static void end(ServerLevel level, Patrol patrol) {
        for (UUID id : patrol.officers) {
            if (level.getEntity(id) instanceof TownspersonEntity officer) { officer.errand(null, 1.0); officer.leaveIn(PoliceResponse.LEAVE_TICKS); }
        }
        patrol.officers.clear(); patrol.next.clear();
    }

    // ------------------------------------------------------------------------------------------------ status and seams

    static String status() {
        StringBuilder out = new StringBuilder(String.format(Locale.ROOT, "patrols out %d; started %d; caught somebody at work %d; footsteps warnings %d; no street found %d", OUT.size(), started, caught, warnings, noSpot));
        for (Patrol patrol : OUT.values()) out.append("; ").append(patrol.city).append(": ").append(patrol.officers.size()).append(" officer(s), ").append(patrol.beat.size()).append(" spots");
        return out.toString();
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_police").requires(source -> source.hasPermission(2))
            .then(Commands.literal("patrols").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status()), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    /** Test seam: forget everything (the officers of a patrol in a test are the test's to remove). */
    static void resetForTest() {
        OUT.clear(); NEXT.clear();
        spots = PopulationService::findSpot; cityAt = (level, pos) -> CityRegions.INSTANCE.cityAt(level, pos); online = server -> server.getPlayerList().getPlayers();
        started = caught = warnings = noSpot = 0;
    }
    static Patrol patrolOf(String city) { return OUT.get(city); }
    static void putForTest(Patrol patrol) { OUT.put(patrol.city, patrol); }
    static void dueForTest(String city, long at) { NEXT.put(city, at); }
    static Long dueOf(String city) { return NEXT.get(city); }
}
