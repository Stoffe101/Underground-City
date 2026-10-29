package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who sees a crime, and what they do about it (D-189). The police respond only to what somebody notices ({@link PoliceResponse}); this class is the noticing for the crimes the first human
 * playtest found had no consequence at all:
 * <ul>
 *   <li><b>Assault.</b> A blow to a person of a city (a townsperson, a resident, a shopkeeper or a district's named citizen). An officer on the beat who sees it gives a warning for a first, minor
 *       one and pursues on a second; a passer-by in sight calls the police (officers at the scene in the city's report time); with nobody else about, the person struck goes to report it
 *       themselves more often than not, and later. A blow to an officer is a serious offence: that officer pursues at once. Blows in quick succession are one incident, never one call each.</li>
 *   <li><b>A lock being picked in sight.</b> A person in the street who can see the thief at the door, while the lock is being worked, may notice (the city's witness chance, a share of it a check).</li>
 *   <li><b>An intruder seen inside.</b> A member of the household who sees a stranger in their home by day, or wakes to one close by at night, calls the police; so does a shopkeeper who finds one
 *       after closing.</li>
 *   <li><b>A search heard, or a sensor tripped.</b> Going through furniture makes noise: a household member at home may hear it, and a wired building's motion sensors may catch it (more often the
 *       better the building).</li>
 *   <li><b>Damage.</b> Breaking things in a building ({@link BurglaryDamage}) is loud: neighbours may hear, a broken window trips a wired alarm.</li>
 * </ul>
 * Nothing here is omniscient: every one of these needs a person (or a sensor) where the crime happens, and a line of sight where one is natural. Nothing is kept across a restart.
 */
public final class CrimeWatch {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Blows by one person on one other within this many ticks are one incident. */
    static final long INCIDENT_TICKS = 20 * 30;
    /** A warning from an officer counts for this long (game ticks): within it, the next offence is pursued. */
    static final long WARNING_TICKS = 20 * 60 * 10;
    /** How far a passer-by sees a blow, and how far a person struck runs before they report it themselves. */
    static final double ASSAULT_SIGHT = 20;
    /**
     * The chance the person struck reports it when nobody else saw, and how much later than a passer-by's call. The owner's brief of the twelfth pass: a victim's report must not depend on somebody
     * else being about. It was 0.6 (D-189); now a person who is struck always goes to the police, later than a passer-by would call.
     */
    static final double VICTIM_REPORTS = 1.0;
    static final int VICTIM_DELAY_FACTOR = 2;

    private static final Map<String, Long> INCIDENTS = new HashMap<>();
    private static final Map<UUID, Long> WARNED = new HashMap<>();
    /** Lock sessions that a passer-by has already reported (one report per session). */
    private static final Set<UUID> LOCKS_SEEN = new HashSet<>();
    static long assaults, assaultsReported, assaultsUnreported, warnings, lockWitnessed, intrudersSeen, searchesHeard, sensorsTripped, damageReported;

    // ------------------------------------------------------------------------------------------------ assault

    @SubscribeEvent
    public void attacked(LivingAttackEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer striker) || striker.isCreative() || striker.isSpectator()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        LivingEntity victim = event.getEntity();
        String city;
        boolean officer = false;
        if (victim instanceof TownspersonEntity body) {
            city = body.city();
            officer = body.police();
        } else if (victim instanceof Villager villager && CityNpcs.isCityNpc(villager) && !CityNpcs.onTheRoad(villager)) {
            city = CityRegions.INSTANCE.cityAt(level, villager.blockPosition()).orElse("");
        } else return;
        if (city == null || city.isEmpty() || CityLaws.INSTANCE.find(city) == null) {
            PoliceTrace.note(striker, city, "refused", "a blow to " + victim.getName().getString() + " outside any city's law (city '" + city + "')");
            return;
        }
        // a machine that strikes (a mod's fake player) carries the heat as a person would, and nobody calls the police on a machine
        if (striker instanceof FakePlayer) { if (!officer) heat(striker, city, PopulationService.HEAT_FOR_STRIKING); return; }
        try { assault(striker, level, victim, city, officer); }
        catch (RuntimeException error) { LOGGER.error("Responding to an assault failed", error); }
    }

    /** One blow by {@code striker} on {@code victim} in {@code city}. Visible for the tests. */
    static void assault(ServerPlayer striker, ServerLevel level, LivingEntity victim, String city, boolean onOfficer) {
        long now = level.getGameTime();
        String key = striker.getUUID() + ">" + victim.getUUID();
        Long first = INCIDENTS.get(key);
        boolean fresh = first == null || now - first > INCIDENT_TICKS;
        if (fresh) INCIDENTS.put(key, now);
        String name = victim.getName().getString();
        if (onOfficer && victim instanceof TownspersonEntity officer) {
            // striking an officer: that officer pursues at once, whatever else is going on
            if (fresh) {
                heat(striker, city, CityLaws.INSTANCE.offence(city, CityLaws.ASSAULT_OFFICER).heat());
                PoliceResponse.seenOnBeat(striker, level, city, striker.blockPosition(), officer, CityLaws.INSTANCE.offence(city, CityLaws.ASSAULT_OFFICER), "striking an officer");
            }
            return;
        }
        if (!fresh) {
            // a further blow in the same incident: only an officer who sees it (after a warning) changes anything
            TownspersonEntity onBeat = PolicePatrols.seer(level, striker);
            if (onBeat != null) PoliceResponse.seenOnBeat(striker, level, city, striker.blockPosition(), onBeat, CityLaws.INSTANCE.offence(city, CityLaws.ASSAULT), "hitting " + name);
            return;
        }
        assaults++;
        CityLaws.Offence offence = CityLaws.INSTANCE.offence(city, CityLaws.ASSAULT);
        heat(striker, city, PopulationService.HEAT_FOR_STRIKING);
        PoliceTrace.note(striker, city, "crime", "struck " + name + " at " + striker.blockPosition().toShortString());
        TownspersonEntity onBeat = PolicePatrols.seer(level, striker);
        if (onBeat != null) {
            Long warned = WARNED.get(striker.getUUID());
            // a warning is for somebody with a clean record here: no prior conviction in the city and no warrant out (twelfth pass: escalation for repeat offences)
            if (offence.warnFirst() && (warned == null || now - warned > WARNING_TICKS) && cleanRecord(striker, city)) {
                WARNED.put(striker.getUUID(), now);
                warnings++;
                onBeat.getLookControl().setLookAt(striker);
                striker.sendSystemMessage(Component.literal(onBeat.getName().getString() + ": Hands to yourself. That is your warning.").withStyle(ChatFormatting.GOLD));
                Notice.send(striker, Notice.Kind.WARN, "Warned by the police", "An officer saw that. Another offence in " + PopulationService.capitalise(city) + " and you will be arrested.");
                LOGGER.info("[UC-POLICE] {} warned by {} in {} for {}", striker.getGameProfile().getName(), onBeat.getName().getString(), city, offence.id());
                PoliceTrace.note(striker, city, "warning", onBeat.getName().getString() + " saw it and warned them (a first minor offence, clean record)");
                return;
            }
            PoliceResponse.seenOnBeat(striker, level, city, striker.blockPosition(), onBeat, offence, "hitting " + name);
            assaultsReported++;
            return;
        }
        int seers = bystanders(level, striker, victim);
        if (seers > 0) {
            assaultsReported++;
            PoliceTrace.note(striker, city, "witnessed", seers + " passer(s)-by in sight; one calls the police");
            PoliceResponse.reportStreetCrime(striker, level, city, striker.blockPosition(), offence, "witness", offence.reportSeconds());
            Notice.send(striker, Notice.Kind.HEAT, "Somebody saw that", "A passer-by is calling the police. Be gone before they come.");
            striker.sendSystemMessage(Component.literal("Somebody saw you strike " + name + " and runs off to call the police.").withStyle(ChatFormatting.RED));
            return;
        }
        // nobody else about: the person struck may go to the police themselves, later (decided once for the incident, so a test or a reload gives the same answer)
        long seed = LawMath.mix(striker.getUUID().getMostSignificantBits(), victim.getUUID().getLeastSignificantBits(), now / INCIDENT_TICKS, 0xA55A17L);
        if (LawMath.roll(VICTIM_REPORTS, seed)) {
            assaultsReported++;
            PoliceTrace.note(striker, city, "witnessed", "nobody else in sight; " + name + " goes to the police (officers in " + offence.reportSeconds() * VICTIM_DELAY_FACTOR + " s)");
            PoliceResponse.reportStreetCrime(striker, level, city, striker.blockPosition(), offence, "the victim", offence.reportSeconds() * VICTIM_DELAY_FACTOR);
            Notice.send(striker, Notice.Kind.HEAT, "They will report you", "Nobody else saw, but " + name + " is going to the police.");
        } else {
            assaultsUnreported++;
            PoliceTrace.note(striker, city, "undetected", "nobody else in sight and " + name + " did not report it");
            Notice.send(striker, Notice.Kind.INFO, "Nobody else saw that", name + " ran off. People remember a face.");
        }
    }

    /** How many people other than the victim (and the police) can see the striker from within {@link #ASSAULT_SIGHT}: townspeople and the districts' named citizens. */
    static int bystanders(ServerLevel level, ServerPlayer striker, LivingEntity victim) {
        AABB box = striker.getBoundingBox().inflate(ASSAULT_SIGHT);
        int n = level.getEntitiesOfClass(TownspersonEntity.class, box, p -> p != victim && p.isAlive() && !p.police() && p.distanceTo(striker) <= ASSAULT_SIGHT && p.hasLineOfSight(striker)).size();
        n += level.getEntitiesOfClass(Villager.class, box, v -> v != victim && v.isAlive() && CityNpcs.isCityNpc(v) && v.distanceTo(striker) <= ASSAULT_SIGHT && v.hasLineOfSight(striker)).size();
        return n;
    }

    /** No prior conviction in the city within two active hours and no warrant out there: a first minor offence may be let off with a warning. */
    static boolean cleanRecord(ServerPlayer player, String city) {
        try {
            WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
            return ledger.faulted() || (ledger.priors(player.getUUID(), city) == 0 && ledger.warrant(player.getUUID(), city) == null);
        } catch (RuntimeException error) { return true; }
    }

    private static void heat(ServerPlayer player, String city, int amount) {
        try { WorkOrderLedger.get(player.server).addHeat(player.getUUID(), city, amount); }
        catch (RuntimeException error) { LOGGER.warn("Heat for an offence could not be recorded: {}", error.toString()); }
    }

    // ------------------------------------------------------------------------------------------------ watching: locks being picked, intruders inside

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        int t = server.getTickCount();
        try {
            if (t % 40 == 7) watchLocks(server);
            if (t % 20 == 11) watchIntruders(server);
            if (t % 1200 == 0) prune(server.overworld().getGameTime());
        } catch (RuntimeException error) { LOGGER.error("The crime watch failed", error); }
    }

    /** A passer-by who can see a lock being worked at a city building's door may notice: a share of the city's witness chance at each look. */
    static void watchLocks(MinecraftServer server) {
        for (var entry : LockService.doorSessions()) {
            UUID id = entry.getKey();
            if (LOCKS_SEEN.contains(id)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || player.isCreative() || !(player.level() instanceof ServerLevel level)) continue;
            LockService.Target target = entry.getValue();
            CityLaws.Law law = CityLaws.INSTANCE.find(target.city());
            if (law == null) continue;
            double radius = PoliceResponse.witnessRadius(player);
            List<TownspersonEntity> seers = level.getEntitiesOfClass(TownspersonEntity.class, player.getBoundingBox().inflate(radius),
                p -> p.isAlive() && !p.police() && p.distanceTo(player) <= radius && p.hasLineOfSight(player));
            if (seers.isEmpty()) continue;
            CitizenState.Citizen citizen = CitizenState.get(server).find(id).orElse(null);
            double each = LawMath.witnessChance(law, WorkOrderLedger.get(server).heat(id, target.city()), citizen == null ? 0 : SkillService.inspectionModifier(citizen)) / 3.0;
            if (Thievery.has(player, "thief.ghost")) each *= PoliceResponse.GHOST_WITNESS_FACTOR;
            double chance = 1 - Math.pow(1 - each, Math.min(3, seers.size()));
            if (level.random.nextDouble() >= chance) continue;
            Optional<CityBuildings.Building> building = CityBuildings.INSTANCE.at(level, target.pos());
            if (building.isEmpty()) continue;
            LOCKS_SEEN.add(id);
            lockWitnessed++;
            PoliceTrace.note(player, target.city(), "witnessed", seers.get(0).getName().getString() + " saw them working at a lock at " + target.pos().toShortString());
            player.sendSystemMessage(Component.literal(seers.get(0).getName().getString() + " has seen you working at the lock and hurries off.").withStyle(ChatFormatting.RED));
            PoliceResponse.raise(player, level, building.get(), target.pos(), false, false, true);
        }
        LOCKS_SEEN.removeIf(id -> LockService.doorSessions().stream().noneMatch(e -> e.getKey().equals(id)));
    }

    /**
     * A stranger inside a closed building (not open to the public, not theirs, not a home they hold a key to) who is seen by somebody who lives or works there: by day within 16 blocks in sight, at
     * night (they are in bed) only within 6. The household calls the police; a response already out is joined.
     */
    static void watchIntruders(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer || player.isCreative() || player.isSpectator() || !(player.level() instanceof ServerLevel level)) continue;
            BlockPos at = player.blockPosition();
            if (!CityBuildings.INSTANCE.known(at.getX() >> 4, at.getZ() >> 4)) continue;   // only a building whose plan is known: never a lookup from here
            Optional<CityBuildings.Building> found = CityBuildings.INSTANCE.at(level, at);
            if (found.isEmpty() || !found.get().locked() || BuildingGuard.exposed(level, at.above(2))) continue;   // under the building's roof, not on the pavement of its chunk
            CityBuildings.Building b = found.get();
            if (!intruder(level, player, b)) continue;
            if (PoliceResponse.outFor(player, level, b.city()) != null) continue;
            int hour = PopulationPlan.hourOf(HouseholdService.dayTime(level));
            boolean night = hour >= 22 || hour < 7;
            double sight = night ? 6 : 16;
            TownspersonEntity seer = null;
            for (TownspersonEntity p : level.getEntitiesOfClass(TownspersonEntity.class, player.getBoundingBox().inflate(sight),
                p -> p.isAlive() && !p.police() && p.homeKey() == b.key() && p.distanceTo(player) <= sight && p.hasLineOfSight(player))) { seer = p; break; }
            if (seer == null) continue;
            intrudersSeen++;
            PoliceTrace.note(player, b.city(), "witnessed", seer.getName().getString() + (night ? " woke to" : " saw") + " an intruder inside at " + at.toShortString());
            player.sendSystemMessage(Component.literal(seer.getName().getString() + (night ? " wakes, sees you and shouts for help." : " sees a stranger in the place and calls the police.")).withStyle(ChatFormatting.RED));
            PoliceResponse.raise(player, level, b, at, false, false, true);
        }
    }

    /** True when this player has no business inside this building now: not open to the public, no key (a home of theirs in it), and not inside a home of their own. */
    static boolean intruder(ServerLevel level, ServerPlayer player, CityBuildings.Building b) {
        if (BuildingGuard.openToPublic(level, b, player) || CityHomes.holdsKey(player, b)) return false;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger != null && !ledger.faulted()) {
            CrewLedger.Residence r = ledger.residenceAt(level.dimension().location().toString(), player.blockPosition());
            if (r != null && r.owned() && ResidenceGuard.rights(ledger, r, player.getUUID()).access()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------ searches and damage

    /**
     * A search of furniture the household did not see (D-189): a wired building's motion sensors may catch it (one in ten per tier of the building, when its alarm is armed and not jammed or
     * quietened), and a household member at home may hear it (three in ten by day, one in seven at night, within 20 blocks, through walls). Either raises the response.
     */
    static void searched(ServerPlayer player, ServerLevel level, CityBuildings.Building b, BlockPos at) {
        if (PoliceResponse.outFor(player, level, b.city()) != null) return;
        RandomSourceSeed rs = new RandomSourceSeed(level, player, at);
        CityBuildings.Response response = b.security() == null ? null : b.security().response();
        if (response != null && !AlarmPanels.jammed(level, b) && PoliceResponse.armed(level, b, response) && rs.next() < 0.1 * Math.max(1, Thievery.tier(b))) {
            sensorsTripped++;
            PoliceTrace.note(player, b.city(), "reported", "a motion sensor caught a search at " + at.toShortString());
            Notice.send(player, Notice.Kind.HEAT, "A motion sensor", "Something on the wall blinked red. The alarm is ringing.");
            PoliceResponse.raise(player, level, b, at, true, false, false);
            return;
        }
        int hour = PopulationPlan.hourOf(HouseholdService.dayTime(level));
        double hear = hour >= 22 || hour < 7 ? 0.15 : 0.3;
        boolean home = !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(at).inflate(20), p -> p.isAlive() && !p.police() && p.homeKey() == b.key()).isEmpty();
        if (home && rs.next() < hear) {
            searchesHeard++;
            PoliceTrace.note(player, b.city(), "witnessed", "somebody at home heard a search at " + at.toShortString());
            player.sendSystemMessage(Component.literal("Somebody in the building heard you, and is on the telephone.").withStyle(ChatFormatting.RED));
            PoliceResponse.raise(player, level, b, at, false, false, true);
        }
    }

    /**
     * Something broken inside a building during a burglary (D-190): breaking glass trips a wired alarm for certain; other damage one time in four. Neighbours within 16 blocks may hear it (a third
     * of the time, glass three in five) whatever the walls; an officer on the beat who sees it pursues. Returns true when the police were called.
     */
    static boolean damaged(ServerPlayer player, ServerLevel level, CityBuildings.Building b, BlockPos at, boolean glass) {
        if (PoliceResponse.outFor(player, level, b.city()) != null) return false;
        TownspersonEntity onBeat = PolicePatrols.seer(level, player);
        if (onBeat != null) {
            PoliceResponse.seenOnBeat(player, level, b.city(), at, onBeat, CityLaws.INSTANCE.offence(b.city(), CityLaws.CRIMINAL_DAMAGE), "smashing things in a building");
            damageReported++;
            return true;
        }
        RandomSourceSeed rs = new RandomSourceSeed(level, player, at);
        CityBuildings.Response response = b.security() == null ? null : b.security().response();
        if (response != null && !AlarmPanels.jammed(level, b) && PoliceResponse.armed(level, b, response) && (glass || rs.next() < 0.25)) {
            damageReported++;
            Notice.send(player, Notice.Kind.HEAT, glass ? "Glass-break alarm" : "An alarm went off", "The building's alarm heard that. The police are on their way.");
            PoliceResponse.raise(player, level, b, at, true, false, false);
            return true;
        }
        boolean neighbours = !level.getEntitiesOfClass(TownspersonEntity.class, new AABB(at).inflate(16), p -> p.isAlive() && !p.police()).isEmpty();
        if (neighbours && rs.next() < (glass ? 0.6 : 0.33)) {
            damageReported++;
            player.sendSystemMessage(Component.literal(glass ? "Glass breaks loudly. Somebody outside heard it." : "That was loud. Somebody nearby heard it.").withStyle(ChatFormatting.RED));
            PoliceResponse.raise(player, level, b, at, false, false, true);
            return true;
        }
        return false;
    }

    /** A small random source for one decision: the level's own (a test fixes the outcome with {@link #forceRollsForTest}). */
    private static final class RandomSourceSeed {
        private final ServerLevel level;
        RandomSourceSeed(ServerLevel level, ServerPlayer player, BlockPos at) { this.level = level; }
        double next() { Double fixed = FIXED; return fixed != null ? fixed : level.random.nextDouble(); }
    }
    private static volatile Double FIXED;

    /** Test seam: every roll of the search and damage checks returns this (null for real rolls). */
    static void forceRollsForTest(Double value) { FIXED = value; }

    private static void prune(long now) {
        INCIDENTS.values().removeIf(t -> now - t > INCIDENT_TICKS * 4);
        WARNED.values().removeIf(t -> now - t > WARNING_TICKS);
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { LOCKS_SEEN.remove(event.getEntity().getUUID()); }

    static String status() {
        return "assaults " + assaults + " (reported " + assaultsReported + ", not reported " + assaultsUnreported + ", warnings " + warnings + "); locks seen " + lockWitnessed + "; intruders seen " + intrudersSeen
            + "; searches heard " + searchesHeard + ", sensors tripped " + sensorsTripped + "; damage reported " + damageReported;
    }

    /** Test seam. */
    static void resetForTest() {
        INCIDENTS.clear(); WARNED.clear(); LOCKS_SEEN.clear(); FIXED = null;
        assaults = assaultsReported = assaultsUnreported = warnings = lockWitnessed = intrudersSeen = searchesHeard = sensorsTripped = damageReported = 0;
    }
}
