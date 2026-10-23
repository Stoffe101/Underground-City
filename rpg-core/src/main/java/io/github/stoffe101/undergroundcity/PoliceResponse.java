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
    static final int ARREST_HEAT = 3, LEAVE_TICKS = 160, MAX_TRIES = 5, CANDIDATE_SPOTS = 6;
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
        long arriveAt, windowEnds, ringUntil, nextRing;
        final int officersWanted, windowSeconds;
        final List<UUID> officers = new ArrayList<>();
        boolean arrived, challenged;
        int tries;

        Dispatch(ServerPlayer player, ServerLevel level, BlockPos door, String city, String securityClass, long buildingKey, String why, long arriveAt, CityBuildings.Response response) {
            this.player = player; burglar = player.getUUID(); dimension = level.dimension(); this.door = door; this.city = city; this.securityClass = securityClass; this.buildingKey = buildingKey;
            this.why = why; this.arriveAt = arriveAt; officersWanted = response.officers(); windowSeconds = response.windowSeconds();
        }
    }

    private static final List<Dispatch> DISPATCHES = new ArrayList<>();
    // measurements for /uc_police status
    static long raised, byAlarm, byWitness, arrivals, arrests, escapes, noSpot, seizedStacks;

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
        boolean wired = response != null && event.outcome() == BuildingBreachEvent.Outcome.OPENED && armed(level, building, response);
        // entering quietly: a clean pick does not trip an alarm that can be slipped past, and tells the thief something was there to slip past
        boolean slipped = wired && event.quiet() && response.alarmChance() < QUIET_BYPASS_MAX_CHANCE;
        if (slipped) Notice.send(player, Notice.Kind.INFO, "Quiet entry", "Something in here is wired to an alarm. " + ("keypad".equals(event.securityClass())
            ? "Its own code did not set it off." : "Your pick was clean and it did not hear you."));
        boolean armed = lockAlarm || (wired && !slipped);
        // the building's alarm panel (D-128): a jammed panel calls nobody; an armed one with a panel by the door gives the entry delay to work it instead of ringing at once
        if (armed && AlarmPanels.jammed(level, building)) {
            armed = false;
            Notice.send(player, Notice.Kind.INFO, "Quiet entry", "The alarm panel by the door is jammed. Nothing rings.");
        } else if (armed && !lockAlarm && AlarmPanels.startEntry(player, level, building, event.door())) armed = false;
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
                return;
            }
        }
        if (DISPATCHES.size() >= MAX_DISPATCHES) { LOGGER.warn("Police response not raised: {} dispatches are already out", DISPATCHES.size()); return; }
        Dispatch dispatch = new Dispatch(player, level, door, building.city(), building.security() == null ? "" : building.security().id(), building.key(), why, now + delay, used);
        DISPATCHES.add(dispatch);
        raised++;
        if (armed) { byAlarm++; ring(level, dispatch, now); }
        if (seen) byWitness++;
        // somebody who lives or works in the building and is in it now is a witness too (the households of D-124): a night break-in of an occupied house is seen from the bed
        boolean resident = seen && !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(door).inflate(WITNESS_RADIUS), person -> person.homeKey() == building.key() && person.isAlive()).isEmpty();
        Notice.send(player, Notice.Kind.HEAT, armed ? (lockAlarm ? "The alarm is ringing" : "An alarm went off") : resident ? "A resident saw you" : "Somebody saw you",
            "The police are on their way. Get out of sight before they arrive.");
        player.sendSystemMessage(Component.literal(armed ? (lockAlarm ? "The pick snapped and the alarm is ringing." : "An alarm sounds inside the building.") : resident ? "Somebody is at home, and has seen you." : "A passer-by sees you at the door and hurries off.")
            .withStyle(ChatFormatting.RED));
    }

    /** True when this building's alarm is armed: a hash of the building and the world against the class's chance, the same answer every time. */
    static boolean armed(ServerLevel level, CityBuildings.Building building, CityBuildings.Response response) {
        return BuildingStash.roll(level, building, ALARM_SALT) < response.alarmChance();
    }

    /** One roll for the whole group of people in sight of the door: the city's witness chance, raised by the thief's heat there, once per person who could see. */
    static boolean witnessed(ServerLevel level, ServerPlayer player, BuildingBreachEvent event) {
        CityLaws.Law law = CityLaws.INSTANCE.find(event.city());
        if (law == null) return false;
        List<TownspersonEntity> seers = level.getEntitiesOfClass(TownspersonEntity.class, new AABB(event.door()).inflate(WITNESS_RADIUS),
            person -> person.isAlive() && !person.police() && person.hasLineOfSight(player));
        if (seers.isEmpty()) return false;
        WorkOrderLedger ledger = WorkOrderLedger.get(level.getServer());
        CitizenState.Citizen citizen = CitizenState.get(level.getServer()).find(player.getUUID()).orElse(null);
        double each = LawMath.witnessChance(law, ledger.heat(player.getUUID(), event.city()), citizen == null ? 0 : SkillService.inspectionModifier(citizen));
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
        if (player == null || level == null || !player.isAlive() || player.isCreative() || player.isSpectator()) { dismiss(server, d); return true; }
        if (now < d.ringUntil && now >= d.nextRing) {
            d.nextRing = now + RING_EVERY_TICKS;
            level.playSound(null, d.door, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3.0f, 0.6f);
        }
        if (!d.arrived) {
            if (now < d.arriveAt) return false;
            if (!arrive(level, player, d)) {
                if (++d.tries >= MAX_TRIES) { noSpot++; LOGGER.info("Police could not find a street near {} to arrive at; the response is dropped (nobody is arrested)", d.door); return true; }
                return false;
            }
            d.arrived = true; arrivals++;
            d.windowEnds = now + d.windowSeconds * 20L;
            return false;
        }
        List<TownspersonEntity> officers = officers(level, d);
        if (officers.isEmpty() || now >= d.windowEnds) { escapes++; Notice.send(player, Notice.Kind.INFO, "The police have gone", "They looked for you and found nobody. Your heat in " + PopulationService.capitalise(d.city) + " stays up for a while."); dismiss(server, d); return true; }
        boolean sameLevel = player.level() == level;
        double nearest = Double.MAX_VALUE;
        TownspersonEntity closest = null;
        for (TownspersonEntity officer : officers) {
            double distance = sameLevel ? officer.distanceTo(player) : Double.MAX_VALUE;
            if (distance < nearest) { nearest = distance; closest = officer; }
            if (sameLevel && distance <= CHASE_RANGE) officer.errand(player.blockPosition(), 1.25);
            else officer.errand(d.door, 1.0);
        }
        if (!sameLevel || (nearest > ESCAPE_RANGE && player.distanceToSqr(d.door.getX() + 0.5, d.door.getY(), d.door.getZ() + 0.5) > ESCAPE_RANGE * ESCAPE_RANGE)) {
            escapes++;
            Notice.send(player, Notice.Kind.INFO, "You got away", "The police lost you. Keep your head down.");
            dismiss(server, d);
            return true;
        }
        if (closest != null && nearest <= CHALLENGE_RANGE && !d.challenged) {
            d.challenged = true;
            closest.getLookControl().setLookAt(player);
            player.sendSystemMessage(Component.literal(closest.getName().getString() + ": Police! Stay where you are.").withStyle(ChatFormatting.GOLD));
            Notice.send(player, Notice.Kind.WARN, "Police", "An officer is coming for you. Run, or hide.");
        }
        if (closest != null && nearest <= ARREST_RANGE && Math.abs(closest.getY() - player.getY()) <= 2.5) return arrest(server, player, closest, d);
        return false;
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
        if (candidates.isEmpty()) return false;
        BlockPos first = reachableSpot(level, player, d, candidates);
        List<PopulationPlan.Person> guards = new ArrayList<>();
        for (PopulationPlan.Person person : PopulationService.roster(level, d.city)) if (person.role().equals("guard")) guards.add(person);
        for (int i = 0; i < d.officersWanted; i++) {
            BlockPos at = i == 0 ? first : spots.find(level, new ChunkPos(first), random, thief).orElse(first);
            TownspersonEntity officer = UndergroundCity.TOWNSPERSON.get().create(level);
            if (officer == null) continue;
            officer.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0f);
            PopulationPlan.Person who = guards.isEmpty() ? null : guards.get(Math.floorMod((int) (PopulationPlan.mix(d.buildingKey + i) >>> 8), guards.size()));
            int skin = PopulationPlan.outfitsOf("guard", d.city)[0] + PopulationPlan.OUTFITS * random.nextInt(PopulationPlan.SKINS / PopulationPlan.OUTFITS);
            officer.becomeOfficer(d.city, who == null ? "Officer" : who.name(), skin);
            officer.errand(d.door, 1.0);
            officer.leaveIn(d.windowSeconds * 20L + 400L);
            if (level.addFreshEntity(officer)) d.officers.add(officer.getUUID());
        }
        return !d.officers.isEmpty();
    }

    /**
     * The first of the street spots from which an officer can walk all the way to the thief, or the nearest spot when none can. A real client showed why this matters: the nearest street spot was behind the
     * building, an officer's path to the thief in front of it ended at a wall, and the officer stood there until the window closed.
     */
    private static BlockPos reachableSpot(ServerLevel level, ServerPlayer player, Dispatch d, List<BlockPos> candidates) {
        if (candidates.size() == 1) return candidates.get(0);
        TownspersonEntity probe = UndergroundCity.TOWNSPERSON.get().create(level);
        if (probe == null) return candidates.get(0);
        probe.becomeOfficer(d.city, "Officer", 0);
        probe.setOnGround(true);   // a body that has not ticked yet is not "on the ground" and its navigation would refuse to plan
        BlockPos target = player.blockPosition();
        for (BlockPos at : candidates) {
            probe.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
            Path path = probe.getNavigation().createPath(target, 0);
            if (path != null && path.canReach()) return at;
        }
        return candidates.get(0);
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
        if (citizen == null || law == null || ledger.faulted() || ledger.player(player.getUUID()).contains("pending")) return false;   // try again next second
        String who = officer.getName().getString();
        player.sendSystemMessage(Component.literal(who + ": Police. You are under arrest for breaking and entering.").withStyle(ChatFormatting.GOLD));
        CheckpointService.Result result = CheckpointService.arrest(player, citizen, "police:" + d.securityClass + ":" + d.door.toShortString(), d.city, law, ledger, law.fine(CityLaws.PROHIBITED), ARREST_HEAT);
        arrests++; seizedStacks += result.stacksSeized();
        String city = PopulationService.capitalise(d.city);
        int heat = ledger.heat(player.getUUID(), d.city);
        player.sendSystemMessage(Component.literal(who + " searches you and takes " + result.stacksSeized() + " stack" + (result.stacksSeized() == 1 ? "" : "s") + " of stolen or forbidden goods. Fine: " + result.fine() + " credits. Heat in "
            + city + " is now " + heat + "; checkpoint searches become more likely until it cools."));
        Notice.send(player, Notice.Kind.HEAT, "Arrested", result.stacksSeized() + (result.stacksSeized() == 1 ? " stack" : " stacks") + " taken, " + result.fine() + " credits fined. Heat " + heat + " in " + city + ".");
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
        // nobody is arrested while away: the response ends the moment its thief leaves
        for (Iterator<Dispatch> it = DISPATCHES.iterator(); it.hasNext(); ) {
            Dispatch dispatch = it.next();
            if (dispatch.burglar.equals(event.getEntity().getUUID())) { dismiss(server, dispatch); it.remove(); }
        }
    }

    // ------------------------------------------------------------------------------------------------ status and seams

    static String status() {
        return String.format(Locale.ROOT, "dispatches out %d; raised %d (alarm %d, witness %d); officers arrived %d; arrests %d (%d stacks seized); escapes %d; no street found %d", DISPATCHES.size(), raised, byAlarm, byWitness, arrivals, arrests, seizedStacks, escapes, noSpot);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_police").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status()), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    /** Test seam: forget everything. */
    static void resetForTest() { DISPATCHES.clear(); spots = PopulationService::findSpot; raised = byAlarm = byWitness = arrivals = arrests = escapes = noSpot = seizedStacks = 0; }
    static int dispatches() { return DISPATCHES.size(); }
    static Dispatch dispatchAt(int index) { return DISPATCHES.get(index); }
}
