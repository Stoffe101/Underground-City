package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * What happens after somebody breaks into a city building and somebody notices (D-122). The police are not psychic: a response starts only from an <b>observed</b> event, and there are exactly two:
 * the building's <b>alarm</b> (a building class may have one; whether this building's is armed is a hash of the building and the world, so a thief who has learned a building keeps what they learned,
 * and a snapped pick can ring one too), and a <b>witness</b> (a person of the city who was out in the street within sight of the door and whose chance, from the city's law and the thief's heat,
 * came up). A dispatch has an arrival time (the class's, or a neighbour's call), and when it is up officers walk in from a street near the building and look for the thief for a bounded time. An officer
 * who reaches the thief arrests them: the thief's pockets and packs are searched for goods marked as stolen (and what the city's law forbids them), the city's fine is paid up to what the wallet holds,
 * and the city's heat rises. Every one of those changes goes through the checkpoint search's crash-safe prepared record ({@link CheckpointService#arrest}).
 *
 * <p>Deliberate limits, said in the documents too: it runs only while the thief is online and alive (nobody is arrested, fined or searched while away, and a dispatch is forgotten when the server
 * restarts); officers never enter a building, never strike anyone and cannot be hurt; creative and spectator players are never policed; there is no jail, no warrant and no pursuit by vehicle.
 */
public final class PoliceResponse {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Server ticks between two passes (one second). */
    static final int CYCLE = 20;
    static final int MAX_DISPATCHES = 24;
    /** How far from the door a person of the city can see a break-in, in blocks. */
    static final int WITNESS_RADIUS = 20;
    /** A neighbour's call: the response a witnessed break-in gets when the building has no alarm of its own. */
    static final CityBuildings.Response NEIGHBOUR = new CityBuildings.Response(0, 150, 1, 120);
    static final int RING_SECONDS = 20, RING_EVERY_TICKS = 40, ALARM_SALT = 3;
    /** Officers close in from this far, speak from this far, and arrest from this far; the thief is lost beyond the escape range. */
    static final double CHASE_RANGE = 40, CHALLENGE_RANGE = 14, ARREST_RANGE = 3.0, ESCAPE_RANGE = 110;
    /**
     * How far an officer sees (D-146), how long the officers search after they last saw the thief before they give up (half that for a thief with Clean Getaway), how far the search spreads round
     * the last place seen, and how far the officers must be for a thief out of sight to count as away.
     */
    static final double SIGHT = 32, SEARCH_SPREAD = 8, LOST_DISTANCE = 20;
    static final int GIVE_UP_SECONDS = 30;
    static final int ARREST_HEAT = 3, LEAVE_TICKS = 160, MAX_TRIES = 5, CANDIDATE_SPOTS = 6;
    /**
     * How fast officers run after somebody they can see, as a multiple of an officer's walking speed (twelfth pass). A mob's ground speed goes with the square of speed times attribute, so an officer
     * at the old 1.25 (0.375) ran about 6.1 blocks a second, faster than a sprinting player (5.6): nobody in the open ever got away by running. At 1.13 (0.34) an officer runs about 5.0: a walking
     * thief (4.3) is caught, a sprinting one gains half a block a second and still has to break the line of sight to be lost. {@code PoliceChaseGameTests} measures it.
     */
    static final double CHASE_SPEED = 1.13, SEARCH_SPEED = 1.05;
    /** How often (seconds) the route of the officer nearest a suspect in sight is checked, and after how many checks in a row that do not reach a second unit is sent from a street that does. */
    static final int ROUTE_CHECK_SECONDS = 3, ROUTE_MISSES_FOR_HELP = 3;
    /** A clean pick does not trip an armed alarm whose chance is below this (an alarm in every building of its class is too good to slip past). */
    static final double QUIET_BYPASS_MAX_CHANCE = 0.9;

    @FunctionalInterface
    interface SpotFinder { Optional<BlockPos> find(ServerLevel level, ChunkPos chunk, RandomSource random, List<ServerPlayer> players); }

    /** Where officers appear: a street spot near the building (a test replaces it with a spot in its arena). */
    static volatile SpotFinder spots = PopulationService::findSpot;

    /** One response: who it is for, where, when the officers arrive and what they have done. Held in memory only. */
    static final class Dispatch {
        ServerPlayer player;
        final UUID burglar;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final String city, securityClass, why;
        final BlockPos door;
        final long buildingKey;
        /** What the officers will charge at an arrest, and what it costs (D-189): a break-in by default, an assault or criminal damage when that is what was reported. */
        CityLaws.Offence offence;
        /** False for a response to something other than a break-in (an assault): slipping it is not worth Thief XP. */
        boolean burglary = true;
        long arriveAt, windowEnds, ringUntil, nextRing;
        final int officersWanted, windowSeconds;
        final List<UUID> officers = new ArrayList<>();
        boolean arrived, challenged;
        int tries;
        /** Where an officer last saw the thief, and when (active ticks); null until somebody has (D-146: officers chase what they can see, then search where it was). */
        BlockPos lastSeen;
        long lastSeenAt;
        int searchStep;
        /** True once somebody who saw the offender's face is part of this (a witness, the victim, a household, an officer): only then does a warrant outlive the response (twelfth pass). */
        boolean identified;
        /** Where it stands, for the trace: reported (officers on their way), investigating, pursuit. */
        String state = "reported";
        /** Route checks of the nearest officer that did not reach a suspect in sight, in a row, and whether a second unit has been sent. */
        int routeMisses;
        boolean reinforced;

        Dispatch(ServerPlayer player, ServerLevel level, BlockPos door, String city, String securityClass, long buildingKey, String why, long arriveAt, CityBuildings.Response response) {
            this.player = player; burglar = player.getUUID(); dimension = level.dimension(); this.door = door; this.city = city; this.securityClass = securityClass; this.buildingKey = buildingKey;
            this.why = why; this.arriveAt = arriveAt; officersWanted = response.officers(); windowSeconds = response.windowSeconds();
            offence = CityLaws.INSTANCE.offence(city, why.startsWith("patrol: at") || why.startsWith("patrol: going") ? CityLaws.ATTEMPTED_BREAK_IN : CityLaws.BREAK_IN);
        }
    }

    private static final List<Dispatch> DISPATCHES = new ArrayList<>();
    // measurements for /uc_police status
    static long raised, byAlarm, byWitness, byPatrol, arrivals, arrests, escapes, noSpot, seizedStacks;
    /** Twelfth pass: warrants recorded, wanted people an officer on the beat recognised, arrests on a warrant (beat or checkpoint), second units sent when a route did not reach. */
    static long warrantsIssued, recognised, arrestsOnWarrant, reinforcements;

    // ------------------------------------------------------------------------------------------------ noticing a break-in

    @SubscribeEvent
    public void breach(BuildingBreachEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = event.actor() != null ? event.actor() : server == null ? null : server.getPlayerList().getPlayer(event.player());
        if (player == null) return;
        try { noticed(player, event); }
        catch (RuntimeException error) { LOGGER.error("Police response to a break-in failed", error); }
    }

    /** Decides whether anybody noticed this break-in and, if so, raises (or joins) a dispatch. Visible for the tests. */
    static void noticed(ServerPlayer player, BuildingBreachEvent event) {
        if (player.isCreative() || player.isSpectator() || !(player.level() instanceof ServerLevel level)) return;
        Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.at(level, event.door());
        if (found.isEmpty()) return;
        CityBuildings.Building building = found.get();
        CityBuildings.Response response = building.security() == null ? null : building.security().response();
        boolean lockAlarm = event.alarm();
        // a building open to the public has its alarm off while its people are in (D-159): a staff door picked by day is for its witnesses to notice
        boolean wired = response != null && event.outcome() == BuildingBreachEvent.Outcome.OPENED && !BuildingGuard.openToPublic(level, building, null) && armed(level, building, response);
        // entering quietly: a clean pick does not trip an alarm that can be slipped past, and tells the thief something was there to slip past
        // a Ghost (D-143) slips all but a certain alarm
        boolean slipped = wired && event.quiet() && response.alarmChance() < (Thievery.has(player, "thief.ghost") ? 1.0 : QUIET_BYPASS_MAX_CHANCE);
        if (slipped) Notice.send(player, Notice.Kind.INFO, "Quiet entry", "Something in here is wired to an alarm. " + ("keypad".equals(event.securityClass())
            ? "Its own code did not set it off." : "Your pick was clean and it did not hear you."));
        boolean armed = lockAlarm || (wired && !slipped);
        // the building's alarm panel (D-128): a jammed panel calls nobody; an armed one with a panel by the door gives the entry delay to work it instead of ringing at once
        if (armed && AlarmPanels.jammed(level, building)) {
            armed = false;
            Notice.send(player, Notice.Kind.INFO, "Quiet entry", "The alarm panel by the door is jammed. Nothing rings.");
        } else if (armed && !lockAlarm && AlarmPanels.startEntry(player, level, building, event.door())) armed = false;
        // an officer on the beat who sees it happen is there already (D-155): the response starts with them, for this thief alone
        TownspersonEntity onBeat = PolicePatrols.seer(level, player);
        if (onBeat != null) {
            if (armed) raise(player, level, building, event.door(), true, lockAlarm, false);
            PolicePatrols.release(onBeat);
            seenByPatrol(player, level, building, event.door(), onBeat, "breaking in");
            return;
        }
        boolean seen = witnessed(level, player, event);
        if (!armed && !seen) return;
        raise(player, level, building, event.door(), armed, lockAlarm, seen);
    }

    /** Raises (or joins) the dispatch for a noticed break-in: {@code armed} when the building's alarm rang, {@code lockAlarm} when it was a snapped pick, {@code seen} when a witness saw it. */
    static void raise(ServerPlayer player, ServerLevel level, CityBuildings.Building building, BlockPos door, boolean armed, boolean lockAlarm, boolean seen) {
        CityBuildings.Response response = building.security() == null ? null : building.security().response();
        CityBuildings.Response used = response != null ? response : NEIGHBOUR;
        long now = LockService.clock(level.getServer());
        // an alarm brings the class's own response; only a neighbour's call is slower (the building's alarm, if it has one, may be quicker than the neighbour)
        long delay = (armed ? used.dispatchSeconds() : Math.max(used.dispatchSeconds(), NEIGHBOUR.dispatchSeconds())) * 20L;
        String why = armed && seen ? "alarm and witness" : armed ? "alarm" : "witness";
        for (Dispatch existing : DISPATCHES) {
            if (existing.burglar.equals(player.getUUID()) && existing.buildingKey == building.key() && existing.dimension == level.dimension()) {
                existing.arriveAt = Math.min(existing.arriveAt, now + delay);   // a second noise brings them sooner, never later
                if (armed) ring(level, existing, now);
                if (seen) identify(level, player, existing, "a witness");
                PoliceTrace.note(player, existing.city, "reported", "joined the response already out (" + why + ")");
                return;
            }
        }
        if (DISPATCHES.size() >= MAX_DISPATCHES) { refused(player, building.city(), DISPATCHES.size() + " dispatches are already out"); return; }
        Dispatch dispatch = new Dispatch(player, level, door, building.city(), building.security() == null ? "" : building.security().id(), building.key(), why, now + delay, used);
        DISPATCHES.add(dispatch);
        raised++;
        PoliceTrace.note(player, dispatch.city, "dispatch", dispatch.offence.id() + " at " + door.toShortString() + " (" + why + "), officers in " + delay / 20 + " s, " + used.officers() + " officer(s)");
        if (seen) identify(level, player, dispatch, armed ? "the alarm and a witness" : "a witness");
        if (armed) { byAlarm++; ring(level, dispatch, now); }
        if (seen) byWitness++;
        // somebody who lives or works in the building and is in it now is a witness too (the households of D-124): a night break-in of an occupied house is seen from the bed
        boolean resident = seen && !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(door).inflate(WITNESS_RADIUS), person -> person.homeKey() == building.key() && person.isAlive()).isEmpty();
        Notice.send(player, Notice.Kind.HEAT, armed ? (lockAlarm ? "The alarm is ringing" : "An alarm went off") : resident ? "A resident saw you" : "Somebody saw you",
            "The police are on their way. Get out of sight before they arrive.");
        player.sendSystemMessage(Component.literal(armed ? (lockAlarm ? "The pick snapped and the alarm is ringing." : "An alarm sounds inside the building.") : resident ? "Somebody is at home, and has seen you." : "A passer-by sees you at the door and hurries off.")
            .withStyle(ChatFormatting.RED));
    }

    /**
     * An officer on the beat saw this thief {@code doing} something to a city building (D-155: at its lock, going through it, breaking in): the response starts at once, with that officer there and
     * the thief in sight, and joins a response already raised for the same thief and building.
     */
    static void seenByPatrol(ServerPlayer player, ServerLevel level, CityBuildings.Building building, BlockPos door, TownspersonEntity officer, String doing) {
        long now = LockService.clock(level.getServer());
        Dispatch dispatch = null;
        for (Dispatch existing : DISPATCHES) {
            if (existing.burglar.equals(player.getUUID()) && existing.buildingKey == building.key() && existing.dimension == level.dimension()) { dispatch = existing; break; }
        }
        if (dispatch == null) {
            if (DISPATCHES.size() >= MAX_DISPATCHES) { LOGGER.warn("Police response not raised: {} dispatches are already out", DISPATCHES.size()); return; }
            CityBuildings.Response response = building.security() == null ? null : building.security().response();
            dispatch = new Dispatch(player, level, door, building.city(), building.security() == null ? "" : building.security().id(), building.key(), "patrol: " + doing, now, response != null ? response : NEIGHBOUR);
            DISPATCHES.add(dispatch);
            raised++;
        }
        byPatrol++;
        dispatch.state = "pursuit";
        identify(level, player, dispatch, "an officer on the beat");
        PoliceTrace.note(player, dispatch.city, "pursuit", officer.getName().getString() + " on the beat saw them " + doing + ", " + Math.round(officer.distanceTo(player)) + " blocks away");
        if (!dispatch.arrived) { dispatch.arrived = true; arrivals++; dispatch.windowEnds = now + dispatch.windowSeconds * 20L; }
        dispatch.officers.add(officer.getUUID());
        dispatch.lastSeen = player.blockPosition();
        dispatch.lastSeenAt = now;
        officer.errand(player.blockPosition(), CHASE_SPEED);
        officer.leaveIn(dispatch.windowSeconds * 20L + 400L);
        Notice.send(player, Notice.Kind.HEAT, "The police saw you", "An officer on the beat is coming for you. Run, or hide.");
        player.sendSystemMessage(Component.literal(officer.getName().getString() + " is on the beat and saw you " + doing + ".").withStyle(ChatFormatting.RED));
    }

    /** The response to a crime in the street (an assault): two officers who look round the scene for two minutes. */
    static final CityBuildings.Response STREET = new CityBuildings.Response(0, 20, 2, 120);

    /** The dispatch already out for this player in this city, if any: a second crime joins it (and its worse charge stands) instead of raising another (D-189). */
    static Dispatch outFor(ServerPlayer player, ServerLevel level, String city) {
        for (Dispatch existing : DISPATCHES) if (existing.burglar.equals(player.getUUID()) && existing.city.equals(city) && existing.dimension == level.dimension()) return existing;
        return null;
    }

    /** Keeps the worse of two charges (the one with the larger fine). */
    private static void charge(Dispatch d, CityLaws.Offence offence) {
        if (offence.fine() > d.offence.fine()) d.offence = offence;
    }

    /**
     * Somebody who saw the offender's face is part of this response (twelfth pass): the police know whom they are looking for, so a warrant is recorded at once and outlives the response itself. A
     * logout, a restart or an escape no longer wipes a witnessed crime: an officer on the beat who sees them, or a checkpoint of the city, arrests them for it while it lasts. An alarm alone saw nobody.
     */
    static void identify(ServerLevel level, ServerPlayer player, Dispatch d, String by) {
        d.identified = true;
        try {
            WorkOrderLedger ledger = WorkOrderLedger.get(level.getServer());
            if (ledger.faulted() || CitizenState.get(level.getServer()).find(player.getUUID()).isEmpty()) return;
            ledger.issueWarrant(player.getUUID(), d.city, d.offence.id(), d.offence.fine(), d.offence.heat(), by);
            warrantsIssued++;
            PoliceTrace.note(player, d.city, "warrant", "issued or renewed for " + d.offence.id() + " (seen by " + by + "), lasts " + WorkOrderLedger.WARRANT_TICKS / 1200 + " active minutes");
        } catch (RuntimeException error) { LOGGER.warn("A warrant could not be recorded: {}", error.toString()); }
    }

    /** A response that could not be raised: said in the log (it used to be the only trace of a dropped report) and in the trace. */
    private static void refused(ServerPlayer player, String city, String why) {
        LOGGER.warn("Police response not raised for {} in {}: {}", player.getGameProfile().getName(), city, why);
        PoliceTrace.note(player, city, "refused", why);
    }

    /**
     * An officer on the beat recognises somebody wanted in their city (twelfth pass): a pursuit for the warrant's charge, as if they had seen it happen, unless a response is already out for them.
     * Returns true when a pursuit started.
     */
    static boolean wantedSeen(ServerPlayer player, ServerLevel level, String city, TownspersonEntity officer, WorkOrderLedger.Warrant warrant) {
        if (outFor(player, level, city) != null) return false;
        CityLaws.Offence offence = CityLaws.INSTANCE.offence(city, warrant.offence());
        recognised++;
        PoliceTrace.note(player, city, "pursuit", officer.getName().getString() + " on the beat recognised them: wanted for " + warrant.offence() + " (seen by " + warrant.source() + ")");
        seenOnBeat(player, level, city, player.blockPosition(), officer, offence, "wanted for " + offence.charge());
        return true;
    }

    /**
     * Somebody reported a crime in the street (D-189): an assault a passer-by saw, or one the victim went to report. Officers come to the scene after {@code delaySeconds} and look for the
     * offender as for a break-in (they chase only what they see). A response already out for this player in this city is joined: it comes no later, and the worse charge stands.
     * Returns the dispatch, or null when none could be raised.
     */
    static Dispatch reportStreetCrime(ServerPlayer player, ServerLevel level, String city, BlockPos scene, CityLaws.Offence offence, String why, int delaySeconds) {
        long now = LockService.clock(level.getServer());
        Dispatch existing = outFor(player, level, city);
        if (existing != null) {
            if (!existing.arrived) existing.arriveAt = Math.min(existing.arriveAt, now + delaySeconds * 20L);
            charge(existing, offence);
            identify(level, player, existing, why);
            PoliceTrace.note(player, city, "reported", offence.id() + " (" + why + ") joined the response already out");
            return existing;
        }
        if (DISPATCHES.size() >= MAX_DISPATCHES) { refused(player, city, DISPATCHES.size() + " dispatches are already out"); return null; }
        Dispatch d = new Dispatch(player, level, scene, city, "", PopulationPlan.mix(scene.asLong() ^ now), why, now + delaySeconds * 20L, STREET);
        d.offence = offence;
        d.burglary = false;
        DISPATCHES.add(d);
        raised++; byWitness++;
        LOGGER.info("[UC-POLICE] {} reported in {} at {} ({}): officers in {} s", offence.id(), city, scene.toShortString(), why, delaySeconds);
        PoliceTrace.note(player, city, "dispatch", offence.id() + " reported by " + why + " at " + scene.toShortString() + ", officers in " + delaySeconds + " s");
        identify(level, player, d, why);
        return d;
    }

    /**
     * An officer on the beat saw this player commit a crime in the street (an assault, D-189), or was struck: the response starts at once with that officer, the offender in sight. A response
     * already out for this player in this city is joined and takes the worse charge.
     */
    static void seenOnBeat(ServerPlayer player, ServerLevel level, String city, BlockPos scene, TownspersonEntity officer, CityLaws.Offence offence, String doing) {
        long now = LockService.clock(level.getServer());
        Dispatch d = outFor(player, level, city);
        if (d == null) {
            if (DISPATCHES.size() >= MAX_DISPATCHES) { LOGGER.warn("Police response not raised: {} dispatches are already out", DISPATCHES.size()); return; }
            d = new Dispatch(player, level, scene, city, "", PopulationPlan.mix(scene.asLong() ^ now), "patrol: " + doing, now, STREET);
            d.offence = offence;
            d.burglary = false;
            DISPATCHES.add(d);
            raised++;
        } else charge(d, offence);
        byPatrol++;
        d.state = "pursuit";
        identify(level, player, d, "an officer");
        PoliceTrace.note(player, city, "pursuit", officer.getName().getString() + " saw them " + doing + " (" + d.offence.id() + "), " + Math.round(officer.distanceTo(player)) + " blocks away");
        if (!d.arrived) { d.arrived = true; arrivals++; d.windowEnds = now + d.windowSeconds * 20L; }
        if (!d.officers.contains(officer.getUUID())) d.officers.add(officer.getUUID());
        d.lastSeen = player.blockPosition();
        d.lastSeenAt = now;
        d.challenged = true;
        PolicePatrols.release(officer);
        officer.errand(player.blockPosition(), CHASE_SPEED);
        officer.leaveIn(d.windowSeconds * 20L + 400L);
        officer.getLookControl().setLookAt(player);
        Notice.send(player, Notice.Kind.HEAT, "The police saw you", "An officer is coming for you. Run, or give yourself up.");
        player.sendSystemMessage(Component.literal(officer.getName().getString() + ": Police! Stay where you are. You are wanted for " + d.offence.charge() + ".").withStyle(ChatFormatting.GOLD));
        LOGGER.info("[UC-POLICE] {} seen by {} in {}: {}", player.getGameProfile().getName(), officer.getName().getString(), city, offence.id());
    }

    /** True when a response is out for this player (anywhere): for the tests and the status display. */
    static boolean wanted(UUID player) {
        for (Dispatch d : DISPATCHES) if (d.burglar.equals(player)) return true;
        return false;
    }

    /** True when this building's alarm is armed: a hash of the building and the world against the class's chance, the same answer every time. */
    static boolean armed(ServerLevel level, CityBuildings.Building building, CityBuildings.Response response) {
        if (TechService.quieted(level, building)) return false;        // its alarm line was quietened off the books (D-166) for a few minutes
        if (TechService.monitored(level, building)) return true;       // a monitored alarm a Technician fitted is always armed
        return BuildingStash.roll(level, building, ALARM_SALT) < response.alarmChance();
    }

    /** How far a crouched thief with Soft Steps is noticed, and how much less likely a Ghost is to be noticed at all (D-143). */
    static final double SOFT_STEPS_RADIUS = 6, GHOST_WITNESS_FACTOR = 0.75;

    static double witnessRadius(ServerPlayer player) { return player.isCrouching() && Thievery.has(player, "thief.soft_steps") ? SOFT_STEPS_RADIUS : WITNESS_RADIUS; }

    /** One roll for the whole group of people in sight of the door: the city's witness chance, raised by the thief's heat there, once per person who could see. */
    static boolean witnessed(ServerLevel level, ServerPlayer player, BuildingBreachEvent event) {
        CityLaws.Law law = CityLaws.INSTANCE.find(event.city());
        if (law == null) return false;
        // a thief with Soft Steps who is crouched is noticed only close by (D-143); a wall still hides anybody
        double radius = witnessRadius(player);
        List<TownspersonEntity> seers = level.getEntitiesOfClass(TownspersonEntity.class, new AABB(event.door()).inflate(radius),
            person -> person.isAlive() && !person.police() && person.distanceTo(player) <= radius + 1 && person.hasLineOfSight(player));
        if (seers.isEmpty()) return false;
        WorkOrderLedger ledger = WorkOrderLedger.get(level.getServer());
        CitizenState.Citizen citizen = CitizenState.get(level.getServer()).find(player.getUUID()).orElse(null);
        double each = LawMath.witnessChance(law, ledger.heat(player.getUUID(), event.city()), citizen == null ? 0 : SkillService.inspectionModifier(citizen));
        if (Thievery.has(player, "thief.ghost")) each *= GHOST_WITNESS_FACTOR;
        double chance = 1 - Math.pow(1 - each, Math.min(3, seers.size()));
        UUID id = player.getUUID();
        return LawMath.roll(chance, LawMath.mix(id.getMostSignificantBits(), id.getLeastSignificantBits(), event.door().asLong(), event.activeTick(), 0x5E3A11L));
    }

    private static void ring(ServerLevel level, Dispatch dispatch, long now) {
        dispatch.ringUntil = Math.max(dispatch.ringUntil, now + RING_SECONDS * 20L);
        if (dispatch.nextRing <= now) dispatch.nextRing = now;
    }

    // ------------------------------------------------------------------------------------------------ the response itself

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 0) return;
        try { pass(event.getServer()); }
        catch (RuntimeException error) { LOGGER.error("Police response pass failed", error); }
    }

    /** One pass (once a second): rings alarms, brings officers when they are due, sends them after the thief, arrests, and lets a response end. Visible for the tests. */
    static void pass(MinecraftServer server) {
        if (DISPATCHES.isEmpty()) return;
        long now = LockService.clock(server);
        for (Iterator<Dispatch> it = DISPATCHES.iterator(); it.hasNext(); ) {
            Dispatch dispatch = it.next();
            boolean finished;
            try { finished = step(server, dispatch, now); }
            catch (RuntimeException error) { LOGGER.error("Police response failed; it is dropped", error); dismiss(server, dispatch); finished = true; }
            if (finished) it.remove();
        }
    }

    private static boolean step(MinecraftServer server, Dispatch d, long now) {
        ServerPlayer player = resolve(server, d);
        ServerLevel level = server.getLevel(d.dimension);
        if (player == null || level == null || !player.isAlive() || player.isCreative() || player.isSpectator()) {
            PoliceTrace.note(player == null ? d.burglar.toString().substring(0, 8) : player.getGameProfile().getName(), d.city, "dropped",
                player == null ? "the offender is not online" : !player.isAlive() ? "the offender died" : "the offender is in creative or spectator mode");
            dismiss(server, d);
            return true;
        }
        if (now < d.ringUntil && now >= d.nextRing) {
            d.nextRing = now + RING_EVERY_TICKS;
            level.playSound(null, d.door, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0f, 0.6f);
        }
        if (!d.arrived) {
            if (now < d.arriveAt) return false;
            if (!arrive(level, player, d)) {
                PoliceTrace.note(player, d.city, "arrival", "no street spot to arrive at near " + d.door.toShortString() + " (try " + (d.tries + 1) + " of " + MAX_TRIES + ")");
                if (++d.tries >= MAX_TRIES) {
                    noSpot++;
                    LOGGER.info("Police could not find a street near {} to arrive at; the response is dropped (nobody is arrested)", d.door);
                    PoliceTrace.note(player, d.city, "dropped", "no street near the scene in " + MAX_TRIES + " tries" + (d.identified ? "; the warrant stands" : ""));
                    return true;
                }
                return false;
            }
            d.arrived = true; arrivals++;
            d.state = "investigating";
            d.windowEnds = now + d.windowSeconds * 20L;
            // a word that they are here and searching, once: what a person would hear (sirens, a car door), not where they are
            if (player.level() == level && player.distanceToSqr(d.door.getX() + 0.5, d.door.getY(), d.door.getZ() + 0.5) <= ESCAPE_RANGE * ESCAPE_RANGE)
                Notice.send(player, Notice.Kind.WARN, "Police searching", d.burglary ? "Officers are at the building, looking round it. Keep out of their sight." : "Officers are at the scene, looking for you. Keep out of their sight.");
            LOGGER.info("[UC-POLICE] {} officer(s) arrived for {} in {} ({}), {} blocks from the offender", d.officers.size(), d.offence.id(), d.city, d.why, Math.round(Math.sqrt(player.distanceToSqr(d.door.getX() + 0.5, d.door.getY(), d.door.getZ() + 0.5))));
            return false;
        }
        List<TownspersonEntity> officers = officers(level, d);
        // (the notice's longer wording was cut off at three lines in the advanced burglary run)
        if (officers.isEmpty() || now >= d.windowEnds) {
            escaped(player, d, officers.isEmpty() ? "no officer left (they despawned or were removed)" : "the search window of " + d.windowSeconds + " s ended");
            Notice.send(player, Notice.Kind.INFO, "The police have gone", d.identified ? "They found nobody, but they know who they are looking for." : "They found nobody. Your heat in " + PopulationService.capitalise(d.city) + " stays up a while.");
            dismiss(server, d);
            return true;
        }
        boolean sameLevel = player.level() == level;
        double nearest = Double.MAX_VALUE;
        TownspersonEntity closest = null;
        boolean seen = false;
        // officers are not psychic (D-146): they chase the thief only while one of them can see them, and otherwise go to where the thief was last seen (the door, until somebody has) and search round it
        for (TownspersonEntity officer : officers) {
            double distance = sameLevel ? officer.distanceTo(player) : Double.MAX_VALUE;
            if (distance < nearest) { nearest = distance; closest = officer; }
            if (sameLevel && distance <= SIGHT && officer.hasLineOfSight(player)) seen = true;
        }
        if (seen) { d.lastSeen = player.blockPosition(); d.lastSeenAt = now; }
        if (seen && !"pursuit".equals(d.state)) { d.state = "pursuit"; PoliceTrace.note(player, d.city, "pursuit", "an officer sees them, " + Math.round(nearest) + " blocks away"); }
        else if (!seen && "pursuit".equals(d.state) && now - d.lastSeenAt >= 40) { d.state = "investigating"; PoliceTrace.note(player, d.city, "search", "out of sight; searching where they were last seen, " + (d.lastSeen == null ? "the scene" : d.lastSeen.toShortString())); }
        BlockPos searchAt = d.lastSeen != null ? d.lastSeen : d.door;
        for (int i = 0; i < officers.size(); i++) {
            TownspersonEntity officer = officers.get(i);
            if (seen && sameLevel && officer.distanceTo(player) <= CHASE_RANGE) officer.errand(player.blockPosition(), CHASE_SPEED);
            else if (officer.blockPosition().distSqr(searchAt) > 9) officer.errand(searchAt, SEARCH_SPEED);
            else officer.errand(searchPoint(level, searchAt, d, i), 0.9);   // at the last place seen: look round it
        }
        if (closest != null && seen && sameLevel && now % (ROUTE_CHECK_SECONDS * 20L) < CYCLE) checkRoute(level, player, d, closest, now);
        long giveUp = (Thievery.has(player, "thief.clean_getaway") ? GIVE_UP_SECONDS / 2 : GIVE_UP_SECONDS) * 20L;
        boolean lost = !seen && (d.lastSeen == null ? now - d.arriveAt >= giveUp : now - d.lastSeenAt >= giveUp) && nearest > LOST_DISTANCE;
        if (!sameLevel || lost || (nearest > ESCAPE_RANGE && player.distanceToSqr(d.door.getX() + 0.5, d.door.getY(), d.door.getZ() + 0.5) > ESCAPE_RANGE * ESCAPE_RANGE)) {
            escaped(player, d, !sameLevel ? "left the dimension" : lost ? "out of sight for " + giveUp / 20 + " s and " + Math.round(nearest) + " blocks from the nearest officer" : "more than " + (int) ESCAPE_RANGE + " blocks from the officers and the scene");
            Notice.send(player, Notice.Kind.INFO, "You got away", "The police lost you. Keep your head down.");
            dismiss(server, d);
            return true;
        }
        if (closest != null && nearest <= CHALLENGE_RANGE && !d.challenged && seen) {
            d.challenged = true;
            closest.getLookControl().setLookAt(player);
            player.sendSystemMessage(Component.literal(closest.getName().getString() + ": Police! Stay where you are.").withStyle(ChatFormatting.GOLD));
            Notice.send(player, Notice.Kind.WARN, "Police", "An officer is coming for you. Run, or hide.");
            PoliceTrace.note(player, d.city, "pursuit", closest.getName().getString() + " challenged them at " + Math.round(nearest) + " blocks");
        }
        if (closest != null && nearest <= ARREST_RANGE && Math.abs(closest.getY() - player.getY()) <= 2.5 && closest.hasLineOfSight(player)) return arrest(server, player, closest, d);
        return false;
    }

    /** A thief slipped a response: counted, traced with the reason, and worth Thief XP once (by the building's tier). A warrant, if somebody saw them, stands. */
    private static void escaped(ServerPlayer player, Dispatch d, String why) {
        escapes++;
        PoliceTrace.note(player, d.city, "escape", why + (d.identified ? "; still wanted (a warrant for " + d.offence.id() + ")" : "; nobody saw who it was"));
        if (!d.burglary) return;
        CityBuildings.SecurityClass c = CityBuildings.INSTANCE.securityClass(d.securityClass);
        Thievery.escaped(player, d.buildingKey * 31 + d.arriveAt, c == null ? 1 : Math.max(1, c.tier()));
    }

    /**
     * Whether the nearest officer's route reaches a suspect they can see (twelfth pass). A route that ends short (a wall, a fence, a roof) left the officer standing until the window closed, and nothing
     * said so. Now it is traced, and after {@link #ROUTE_MISSES_FOR_HELP} checks in a row a second unit walks in from a street whose route does reach (once per response). Nobody is ever put next to the
     * suspect: when no street reaches either, the officers search as far as their routes go, and the suspect may get away.
     */
    private static void checkRoute(ServerLevel level, ServerPlayer player, Dispatch d, TownspersonEntity officer, long now) {
        Path path = officer.getNavigation().getPath();
        boolean reaches = path == null ? officer.distanceTo(player) <= ARREST_RANGE + 1 : path.canReach() || path.getTarget().distManhattan(player.blockPosition()) <= 2;
        if (reaches) { if (d.routeMisses > 0) PoliceTrace.note(player, d.city, "route", officer.getName().getString() + "'s route reaches them again"); d.routeMisses = 0; return; }
        d.routeMisses++;
        String end = path == null ? "no route at all" : "the route ends at " + path.getTarget().toShortString() + ", " + Math.round(Math.sqrt(path.getTarget().distSqr(player.blockPosition()))) + " blocks short";
        PoliceTrace.note(player, d.city, "route", officer.getName().getString() + ": " + end + " (" + d.routeMisses + " in a row)");
        if (d.reinforced || d.routeMisses < ROUTE_MISSES_FOR_HELP) return;
        d.reinforced = true;
        RandomSource random = RandomSource.create(PopulationPlan.mix(d.buildingKey ^ now));
        List<BlockPos> candidates = new ArrayList<>();
        ChunkPos home = new ChunkPos(player.blockPosition());
        for (int dx = -2; dx <= 2 && candidates.size() < CANDIDATE_SPOTS; dx++) for (int dz = -2; dz <= 2 && candidates.size() < CANDIDATE_SPOTS; dz++) {
            spots.find(level, new ChunkPos(home.x + dx, home.z + dz), random, List.of(player)).filter(spot -> !candidates.contains(spot)).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) candidates.addAll(fallbackSpots(level, player, player.blockPosition()));
        BlockPos at = candidates.isEmpty() ? null : reachableSpot(level, player, d, candidates, true);
        if (at == null) { PoliceTrace.note(player, d.city, "route", "no street near them has a route that reaches; the officers search as far as they can go"); return; }
        TownspersonEntity second = UndergroundCity.TOWNSPERSON.get().create(level);
        if (second == null) return;
        second.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0f);
        dress(level, second, d.city, PopulationPlan.mix(d.buildingKey + 7), random);
        second.errand(player.blockPosition(), CHASE_SPEED);
        second.leaveIn(Math.max(400L, d.windowEnds - now) + 400L);
        if (level.addFreshEntity(second)) {
            d.officers.add(second.getUUID());
            reinforcements++;
            PoliceTrace.note(player, d.city, "route", "a second unit walks in from " + at.toShortString() + ", " + Math.round(Math.sqrt(player.distanceToSqr(at.getX() + 0.5, at.getY(), at.getZ() + 0.5))) + " blocks away, whose route reaches");
        }
    }

    /** Where an officer looks next round the last place seen: a fixed walk of points a few blocks out, different for each officer, on standing ground. */
    static BlockPos searchPoint(ServerLevel level, BlockPos around, Dispatch d, int officer) {
        int step = (d.searchStep++ / 3) + officer * 5;
        double angle = step * 2.399963;   // the golden angle: points spread evenly round the place
        double r = SEARCH_SPREAD * (0.4 + 0.6 * ((step * 0.618034) % 1.0));
        BlockPos guess = around.offset((int) Math.round(Math.cos(angle) * r), 0, (int) Math.round(Math.sin(angle) * r));
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos p = guess.offset(0, dy, 0);
            if (Chunks.ready(level, p) && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP)) return p;
        }
        return around;
    }

    private static ServerPlayer resolve(MinecraftServer server, Dispatch d) {
        ServerPlayer player = d.player;
        if (player.isRemoved() || player.hasDisconnected()) {
            player = server.getPlayerList().getPlayer(d.burglar);   // a respawned player is a new object
            if (player != null) d.player = player;
        }
        return player;
    }

    /** Puts the officers on the ground at street spots near the building. False when no street spot was found this time (a wider search is made on the next pass). */
    private static boolean arrive(ServerLevel level, ServerPlayer player, Dispatch d) {
        ChunkPos home = new ChunkPos(d.door);
        int radius = 1 + d.tries;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) chunks.add(new ChunkPos(home.x + dx, home.z + dz));
        chunks.sort(java.util.Comparator.comparingInt(c -> Math.abs(c.x - home.x) + Math.abs(c.z - home.z)));
        RandomSource random = RandomSource.create(PopulationPlan.mix(d.buildingKey ^ player.getUUID().getLeastSignificantBits() ^ d.arriveAt));
        List<ServerPlayer> thief = List.of(player);
        List<BlockPos> candidates = new ArrayList<>();
        for (ChunkPos chunk : chunks) {
            Optional<BlockPos> spot = spots.find(level, chunk, random, thief);
            if (spot.isPresent() && !candidates.contains(spot.get())) { candidates.add(spot.get()); if (candidates.size() >= CANDIDATE_SPOTS) break; }
        }
        if (candidates.isEmpty()) candidates.addAll(fallbackSpots(level, player, d.door));
        if (candidates.isEmpty()) return false;
        // where the offender cannot see them appear, when there is such a spot (officers walk in from round a corner; they never appear in front of anybody)
        candidates.sort(java.util.Comparator.comparingInt(c -> inView(level, player, c) ? 1 : 0));
        BlockPos first = reachableSpot(level, player, d, candidates);
        for (int i = 0; i < d.officersWanted; i++) {
            BlockPos at = i == 0 ? first : spots.find(level, new ChunkPos(first), random, thief).orElse(first);
            TownspersonEntity officer = UndergroundCity.TOWNSPERSON.get().create(level);
            if (officer == null) continue;
            officer.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0f);
            dress(level, officer, d.city, PopulationPlan.mix(d.buildingKey + i), random);
            officer.errand(d.door, 1.0);
            officer.leaveIn(d.windowSeconds * 20L + 400L);
            if (level.addFreshEntity(officer)) d.officers.add(officer.getUUID());
        }
        if (!d.officers.isEmpty()) PoliceTrace.note(player, d.city, "arrival", d.officers.size() + " officer(s) on foot from " + first.toShortString() + ", " + Math.round(Math.sqrt(player.distanceToSqr(first.getX() + 0.5, first.getY(), first.getZ() + 0.5))) + " blocks from them"
            + (inView(level, player, first) ? " (in their view: no spot out of sight was found)" : ", out of their sight"));
        return !d.officers.isEmpty();
    }

    /**
     * Open ground round the scene when no street spot of the street population can be found (a scene outside Lost Cities' streets, a plaza, the countryside at a city's edge): a ring of points
     * 18 to 40 blocks out, each the nearest safe cell under the sky ({@link CityArrival#safeStand}), in loaded chunks only and at least 14 blocks from the offender. A triggered alarm or a
     * witness's call must bring officers: a response used to be dropped after five passes without a street (D-189).
     */
    static List<BlockPos> fallbackSpots(ServerLevel level, ServerPlayer player, BlockPos scene) {
        List<BlockPos> out = new ArrayList<>();
        for (int r = 18; r <= 40 && out.size() < CANDIDATE_SPOTS; r += 6) {
            for (int a = 0; a < 12 && out.size() < CANDIDATE_SPOTS; a++) {
                double angle = a * Math.PI / 6 + r * 0.37;
                BlockPos aim = scene.offset((int) Math.round(Math.cos(angle) * r), 0, (int) Math.round(Math.sin(angle) * r));
                Optional<BlockPos> stand = CityArrival.safeStand(level, aim, 2, 6, 6);
                if (stand.isPresent() && player.distanceToSqr(stand.get().getX() + 0.5, stand.get().getY(), stand.get().getZ() + 0.5) >= 14 * 14 && !out.contains(stand.get())) out.add(stand.get());
            }
        }
        return out;
    }

    /** True when the offender could see a person standing at {@code at} (a clear line from their eyes to the person's head, within 48 blocks). */
    static boolean inView(ServerLevel level, ServerPlayer player, BlockPos at) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition(), head = net.minecraft.world.phys.Vec3.atCenterOf(at.above());
        if (eye.distanceToSqr(head) > 48 * 48) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, head, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /** Makes {@code officer} one of the city's police: a name from the city's guards on its roster (chosen by {@code salt}) and a guard's outfit. */
    static void dress(ServerLevel level, TownspersonEntity officer, String city, long salt, RandomSource random) {
        List<PopulationPlan.Person> guards = new ArrayList<>();
        for (PopulationPlan.Person person : PopulationService.roster(level, city)) if (person.role().equals("guard")) guards.add(person);
        PopulationPlan.Person who = guards.isEmpty() ? null : guards.get(Math.floorMod((int) (salt >>> 8), guards.size()));
        int skin = PopulationPlan.outfitsOf("guard", city)[0] + PopulationPlan.OUTFITS * random.nextInt(PopulationPlan.SKINS / PopulationPlan.OUTFITS);
        officer.becomeOfficer(city, who == null ? "Officer" : who.name(), skin);
    }

    /**
     * The first of the street spots from which an officer can walk all the way to the thief, or the nearest spot when none can. A real client showed why this matters: the nearest street spot was behind the
     * building, an officer's path to the thief in front of it ended at a wall, and the officer stood there until the window closed.
     */
    private static BlockPos reachableSpot(ServerLevel level, ServerPlayer player, Dispatch d, List<BlockPos> candidates) {
        BlockPos found = reachableSpot(level, player, d, candidates, false);
        return found == null ? candidates.get(0) : found;
    }

    /** As above; with {@code strict}, null when no spot's route reaches (a second unit is sent only from a street that does). The arrival is traced either way. */
    private static BlockPos reachableSpot(ServerLevel level, ServerPlayer player, Dispatch d, List<BlockPos> candidates, boolean strict) {
        if (candidates.size() == 1 && !strict) return candidates.get(0);
        TownspersonEntity probe = UndergroundCity.TOWNSPERSON.get().create(level);
        if (probe == null) return strict ? null : candidates.get(0);
        probe.becomeOfficer(d.city, "Officer", 0);
        probe.setOnGround(true);   // a body that has not ticked yet is not "on the ground" and its navigation would refuse to plan
        BlockPos target = player.blockPosition();
        for (BlockPos at : candidates) {
            probe.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
            Path path = probe.getNavigation().createPath(target, 0);
            if (path != null && path.canReach()) return at;
        }
        PoliceTrace.note(player, d.city, "route", "none of " + candidates.size() + " street spot(s) has a route that reaches them" + (strict ? "" : "; the officers come from the nearest and search"));
        return strict ? null : candidates.get(0);
    }

    private static List<TownspersonEntity> officers(ServerLevel level, Dispatch d) {
        List<TownspersonEntity> out = new ArrayList<>();
        for (Iterator<UUID> it = d.officers.iterator(); it.hasNext(); ) {
            UUID id = it.next();
            if (level.getEntity(id) instanceof TownspersonEntity officer && officer.isAlive()) out.add(officer); else it.remove();
        }
        return out;
    }

    /** The arrest: searched, fined, heat raised, all through one prepared record. Returns true when the response is over. */
    private static boolean arrest(MinecraftServer server, ServerPlayer player, TownspersonEntity officer, Dispatch d) {
        CitizenState.Citizen citizen = CitizenState.get(server).find(player.getUUID()).orElse(null);
        CityLaws.Law law = CityLaws.INSTANCE.find(d.city);
        WorkOrderLedger ledger = WorkOrderLedger.get(server);
        String who = officer.getName().getString();
        if (citizen == null || law == null) {
            // somebody who never registered has no record to search or wallet to fine: taken aside, warned and let go, and the response ends (it used to wait for ever, D-189)
            LockService.interrupt(player, "You are under arrest.");
            BurglarySearch.stop(player);
            player.sendSystemMessage(Component.literal(who + ": You are not on the city's books. Register at Founders Row, and keep out of trouble.").withStyle(ChatFormatting.GOLD));
            arrests++;
            dismiss(server, d);
            return true;
        }
        if (ledger.faulted() || ledger.player(player.getUUID()).contains("pending")) return false;   // try again next second
        // an arrested thief's hands are empty: a lock or a search still going is put away (patrol runs 3 and 4: the hand clicked again after an officer on the beat had put the first
        // lock away, and the arrest left the new one open)
        LockService.interrupt(player, "You are under arrest.");
        BurglarySearch.stop(player);
        String charge = d.offence.charge();
        // a repeat offender pays more (twelfth pass): half the fine again for each conviction in the city within two active hours, at most two and a half times, and the heat goes up with it
        int priors = ledger.priors(player.getUUID(), d.city);
        int fine = LawMath.escalatedFine(d.offence.fine(), priors), heatAdded = LawMath.escalatedHeat(d.offence.heat(), priors);
        player.sendSystemMessage(Component.literal(who + ": Police. You are under arrest for " + charge + (priors > 0 ? ", and not for the first time" : "") + ".").withStyle(ChatFormatting.GOLD));
        CheckpointService.Result result = CheckpointService.arrest(player, citizen, "police:" + d.offence.id() + ":" + (d.securityClass.isEmpty() ? "street" : d.securityClass) + ":" + d.door.toShortString(), d.city, law, ledger, fine, heatAdded);
        arrests++; seizedStacks += result.stacksSeized();
        String city = PopulationService.capitalise(d.city);
        int heat = ledger.heat(player.getUUID(), d.city);
        player.sendSystemMessage(Component.literal(who + " searches you and takes " + result.stacksSeized() + " stack" + (result.stacksSeized() == 1 ? "" : "s") + " of stolen or forbidden goods. Fine for " + charge + ": " + result.fine() + " credits. Heat in "
            + city + " is now " + heat + "; checkpoint searches become more likely until it cools."));
        LOGGER.info("[UC-POLICE] arrest of {} in {} for {}: {} stacks seized, fined {}, heat {}", player.getGameProfile().getName(), d.city, d.offence.id(), result.stacksSeized(), result.fine(), heat);
        PoliceTrace.note(player, d.city, "arrest", who + " for " + d.offence.id() + (priors > 0 ? " (" + priors + " prior conviction(s): fine " + fine + " of " + d.offence.fine() + ")" : "") + ": fined " + result.fine() + " (asked " + fine + "), "
            + result.stacksSeized() + " stack(s) seized, heat " + heat + " (" + d.why + ")");
        if (d.why.contains("wanted for")) arrestsOnWarrant++;
        Notice.send(player, Notice.Kind.HEAT, "Arrested", result.stacksSeized() + (result.stacksSeized() == 1 ? " stack" : " stacks") + " taken, " + result.fine() + " credits fined. Heat " + heat + " in " + city + ".");
        CityStanding.arrested(server, player, d.city);   // an arrest is talked about (D-204)
        dismiss(server, d);
        return true;
    }

    /** Ends a response: the officers stand down and leave a few seconds later (never left standing in a street for good). */
    private static void dismiss(MinecraftServer server, Dispatch d) {
        ServerLevel level = server.getLevel(d.dimension);
        if (level == null) return;
        for (UUID id : d.officers) {
            if (level.getEntity(id) instanceof TownspersonEntity officer) { officer.errand(null, 1.0); officer.leaveIn(LEAVE_TICKS); }
        }
        d.officers.clear();
    }

    @SubscribeEvent
    public void loggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        // nobody is arrested while away: the response ends the moment its thief leaves (and is said so at the next login, while this server runs)
        for (Iterator<Dispatch> it = DISPATCHES.iterator(); it.hasNext(); ) {
            Dispatch dispatch = it.next();
            if (dispatch.burglar.equals(event.getEntity().getUUID())) {
                LEFT_WHILE_WANTED.put(dispatch.burglar, dispatch.city);
                PoliceTrace.note(event.getEntity().getGameProfile().getName(), dispatch.city, "dropped", "the offender logged out during the response" + (dispatch.identified ? "; the warrant stands" : "; nobody saw who it was"));
                dismiss(server, dispatch); it.remove();
            }
        }
    }

    /** Who left while the police were after them, and in which city: told once at their next login (memory only; a restart forgets it, as it forgets the response). */
    private static final java.util.Map<UUID, String> LEFT_WHILE_WANTED = new java.util.HashMap<>();

    @SubscribeEvent
    public void loggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        String city = LEFT_WHILE_WANTED.remove(player.getUUID());
        List<WorkOrderLedger.Warrant> warrants = List.of();
        try { WorkOrderLedger ledger = WorkOrderLedger.get(player.server); if (!ledger.faulted()) warrants = ledger.warrants(player.getUUID()); }
        catch (RuntimeException error) { LOGGER.warn("Warrants could not be read at login: {}", error.toString()); }
        if (!warrants.isEmpty()) {
            // a warrant survives a logout and a restart (twelfth pass): said once at login, with how long it has left
            WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
            StringBuilder where = new StringBuilder();
            for (WorkOrderLedger.Warrant w : warrants) {
                if (where.length() > 0) where.append(", ");
                where.append(PopulationService.capitalise(w.city())).append(" (").append(CityLaws.INSTANCE.offence(w.city(), w.offence()).charge()).append(", ").append(Math.max(1, (w.expires() - ledger.clock()) / 1200)).append(" min)");
            }
            Notice.send(player, Notice.Kind.HEAT, "You are wanted", "The police are looking for you in " + where + ". Officers and checkpoints there will arrest you on sight.");
            for (WorkOrderLedger.Warrant w : warrants) PoliceTrace.note(player, w.city(), "warrant", "logged in while wanted for " + w.offence() + " (" + Math.max(1, (w.expires() - ledger.clock()) / 1200) + " active minutes left)");
        } else if (city != null) Notice.send(player, Notice.Kind.INFO, "The police stood down", "They were after you in " + PopulationService.capitalise(city) + " when you left. Your heat there is still up.");
    }

    // ------------------------------------------------------------------------------------------------ status and seams

    static String status() {
        return String.format(Locale.ROOT, "dispatches out %d; raised %d (alarm %d, witness %d, patrol %d); officers arrived %d; arrests %d (%d stacks seized, %d on a warrant); escapes %d; no street found %d; warrants issued %d, recognised on the beat %d; second units %d",
            DISPATCHES.size(), raised, byAlarm, byWitness, byPatrol, arrivals, arrests, seizedStacks, arrestsOnWarrant, escapes, noSpot, warrantsIssued, recognised, reinforcements);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_police").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status() + "; " + CrimeWatch.status() + "; " + BurglaryDamage.status(context.getSource().getServer())), false);
                return Command.SINGLE_SUCCESS;
            }))
            // the police trace (twelfth pass): the latest steps of every case, and whether they are written to the log as they happen
            .then(Commands.literal("trace")
                .executes(context -> {
                    List<String> lines = PoliceTrace.recent(20);
                    context.getSource().sendSuccess(() -> Component.literal("[UC] police trace (" + (PoliceTrace.on ? "logging" : "kept in memory only") + "), latest " + lines.size() + ":"), false);
                    for (String line : lines) context.getSource().sendSuccess(() -> Component.literal("  " + line), false);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("on").executes(context -> { PoliceTrace.on = true; context.getSource().sendSuccess(() -> Component.literal("[UC] the police trace is written to the log"), true); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("off").executes(context -> { PoliceTrace.on = false; context.getSource().sendSuccess(() -> Component.literal("[UC] the police trace is kept in memory only"), true); return Command.SINGLE_SUCCESS; }))));
    }

    /** Test seam: forget everything. */
    static void resetForTest() {
        DISPATCHES.clear(); LEFT_WHILE_WANTED.clear(); spots = PopulationService::findSpot;
        raised = byAlarm = byWitness = byPatrol = arrivals = arrests = escapes = noSpot = seizedStacks = warrantsIssued = recognised = arrestsOnWarrant = reinforcements = 0;
    }
    static int dispatches() { return DISPATCHES.size(); }
    static Dispatch dispatchAt(int index) { return DISPATCHES.get(index); }
}
