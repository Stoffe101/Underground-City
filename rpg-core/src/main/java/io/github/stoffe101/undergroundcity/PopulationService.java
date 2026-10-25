package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
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
 * The people in the streets (D-108, rebuilt as D-162): turns a city's roster ({@link PopulationPlan}) into the real bodies a player sees, with somewhere to go.
 *
 * <p><b>How many.</b> Every chunk round a player weighs what stands in it at this hour ({@link PopulationDensity}: homes, shops, offices, works, bars, open street, and each city's lean), and the
 * service keeps that many people round the player, within the density setting's budget (server config {@code population.density}: quiet, normal or busy). A busy Blackwater street at noon holds
 * forty people or more; a Valewood lane at three in the morning almost nobody.
 *
 * <p><b>Coming and going.</b> Nobody appears in front of anybody. A new person steps out of a building's street door (it opens for them and shuts behind) or is put on the street where no player
 * can see the spot; a person whose walk is over goes in at a door, and anybody nobody is near is taken away. Locked doors open for their own people only when no player stands close enough to
 * slip in behind.
 *
 * <p><b>Somewhere to go.</b> Each person has an errand fitted to their role and the hour: a worker to a works, an office or a shop; a resident to the shops, home, or a stroll; a courier from door to
 * door; a visitor to the bars and clubs after dark; a guard to a post. They walk there through the streets (bounded paths, at most one new path a body every two seconds), stand and talk when two
 * meet, look in at a shop front, and go into open shops, where they browse the public floor and come out again.
 *
 * <p><b>What it costs.</b> Two passes a second; bodies exist only within {@link #FAR_BLOCKS} of a player, in chunks already loaded, and are never saved (the roster, which is arithmetic, decides
 * afresh who is about); first questions to Lost Cities and door searches are budgeted per pass. {@code /uc_population status} reports the counts and the cost.
 */
public final class PopulationService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Server ticks between two passes (half a second). */
    static final int CYCLE = 10;
    static final int NEAR_CHUNKS = 8, FAR_BLOCKS = 80, WINDOW = 3, MIN_SPOT_DISTANCE = 14, MAX_SPOT_DISTANCE = 64, PLAYERS_PER_PASS = 4, SPAWNS_PER_PLAYER = 2;
    /** First questions to Lost Cities, and door searches (a scan of a building's ground floors), allowed per pass. */
    static final int LOOKUPS_PER_PASS = 3, DOOR_SCANS_PER_PASS = 4;
    /** How close a player may stand to a locked door before its people will not open it (nobody slips in behind a resident). */
    static final double TAILGATE_DISTANCE = 6;
    static final int HEAT_FOR_STRIKING = 2;

    /** The density setting, read from the server config (normal when it cannot be read, as in a GameTest world). */
    static PopulationDensity.Preset preset() {
        try { return SimulationWorld.populationDensity(); } catch (RuntimeException e) { return PopulationDensity.Preset.NORMAL; }
    }

    /** Test seam: a density setting that wins over the config (null to stop). */
    static volatile PopulationDensity.Preset presetOverride;

    static PopulationDensity.Preset activePreset() { PopulationDensity.Preset p = presetOverride; return p != null ? p : preset(); }

    // ------------------------------------------------------------------------------------------------ a person on the street

    enum Phase { WALK, LINGER, CHAT, ENTER, BROWSE, LEAVE, POST }

    /** What a body is doing: its person, its errand, where it is going and until when. Never saved. */
    static final class Walker {
        final long person;
        final String city, role;
        Phase phase = Phase.WALK;
        String errand = "";
        BlockPos goal;
        TechService.StreetDoor door;
        long building, until, lastMoved;
        UUID partner;
        /** The outlet of the shop being browsed ({@code shop:<kind>}), or null: a customer leaving it may have bought something off its shelf (D-164). */
        String shop;
        Vec3 lastPos = Vec3.ZERO;
        int stuck, legs;

        Walker(long person, String city, String role) { this.person = person; this.city = city; this.role = role; }
    }

    private static final Map<UUID, Walker> ACTIVE = new HashMap<>();
    private static final Map<String, List<PopulationPlan.Person>> ROSTERS = new HashMap<>();
    private static final Map<UUID, java.util.Set<Long>> MET = new HashMap<>();

    // measurements, for /uc_population status and the performance notes
    static long passes, nanosTotal, nanosWorst, spawned, spawnedAtDoors, removed, entered, browsed, chats, spotMisses, activePeak, stuckGivenUp;
    /** Where a pass spends its time: the first pass, the Lost Cities plan lookups (count, total, worst). */
    static long nanosFirst, lcCalls, lcNanos, lcWorst;

    // ------------------------------------------------------------------------------------------------ rosters

    static List<PopulationPlan.Person> roster(ServerLevel level, String city) {
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(city);
        if (def == null) return List.of();
        String key = city + ":" + level.getSeed() + ":" + def.count() + ":" + def.reachChunks();
        return ROSTERS.computeIfAbsent(key, k -> PopulationPlan.roster(city, level.getSeed(), def.count(), def.mix(), def.names(), def.reachChunks()));
    }

    static void clearCaches() {
        ROSTERS.clear(); ACTIVE.clear(); MET.clear(); STREETS.clear(); OPEN.clear(); RETRY_AFTER.clear(); AREAS.clear(); DOORS.clear();
        passes = nanosTotal = nanosWorst = spawned = spawnedAtDoors = removed = entered = browsed = chats = spotMisses = activePeak = stuckGivenUp = 0;
        nanosFirst = lcCalls = lcNanos = lcWorst = shelfPurchases = 0;
        stepNanos = topUpNanos = worstSteps = worstTopUp = worstLookups = worstPeople = 0;
    }

    // ------------------------------------------------------------------------------------------------ the pass

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 3) return;
        ServerLevel level = event.getServer().overworld();
        if (level.players().isEmpty() && ACTIVE.isEmpty()) return;
        long start = System.nanoTime(), calls = lcCalls;
        stepNanos = topUpNanos = 0;
        inPass = true;
        try { pass(level); }
        catch (RuntimeException error) { LOGGER.warn("[UC-POPULATION] a pass failed and was skipped: {}", error.toString()); }
        finally { inPass = false; }
        long took = System.nanoTime() - start;
        if (passes == 0) nanosFirst = took;
        if (took > nanosWorst) { worstSteps = stepNanos; worstTopUp = topUpNanos; worstLookups = lcCalls - calls; worstPeople = ACTIVE.size(); }
        passes++; nanosTotal += took; nanosWorst = Math.max(nanosWorst, took);
    }

    private static int lookups, scans;
    /** True while a pass runs: its Lost Cities lookups come out of {@link #LOOKUPS_PER_PASS}. */
    private static boolean inPass;
    /** Where the time of a pass went (the people's steps, the top-up round players), and of the worst pass so far, with its Lost Cities lookups and how many people were out. */
    static long stepNanos, topUpNanos, worstSteps, worstTopUp, worstLookups, worstPeople;

    static void pass(ServerLevel level) {
        if (!LostCitiesLink.ready()) return;
        lookups = LOOKUPS_PER_PASS;
        scans = DOOR_SCANS_PER_PASS;
        List<ServerPlayer> players = new ArrayList<>(level.players());
        players.removeIf(p -> p.isSpectator() || !p.isAlive());
        long now = level.getGameTime();
        int hour = PopulationPlan.hourOf(level.getDayTime());
        long day = PopulationPlan.dayOf(level.getDayTime());
        long stepsFrom = System.nanoTime();
        for (Iterator<Map.Entry<UUID, Walker>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(level.getEntity(entry.getKey()) instanceof TownspersonEntity body) || !body.isAlive()) { it.remove(); continue; }
            if (players.isEmpty() || level.getNearestPlayer(body, FAR_BLOCKS) == null) { body.discard(); it.remove(); removed++; continue; }
            try {
                if (!step(level, body, entry.getValue(), players, now, hour, day)) { body.discard(); it.remove(); }
            } catch (RuntimeException error) {
                body.discard(); it.remove(); removed++;
            }
        }
        stepNanos = System.nanoTime() - stepsFrom;
        if (players.isEmpty()) return;
        // a different few players each pass, so a crowded server does not pay for everybody at once
        long topUpFrom = System.nanoTime();
        int first = (int) (now / CYCLE % players.size());
        for (int i = 0; i < Math.min(PLAYERS_PER_PASS, players.size()); i++) topUp(level, players.get((first + i) % players.size()), players, now, hour, day);
        topUpNanos = System.nanoTime() - topUpFrom;
        activePeak = Math.max(activePeak, ACTIVE.size());
    }

    /** Removes bodies that are gone, far from everybody, or whose person has gone indoors while nobody was looking. Kept for the GameTests and the status command. */
    static void removeOutOfDate(ServerLevel level) {
        for (Iterator<Map.Entry<UUID, Walker>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(level.getEntity(entry.getKey()) instanceof TownspersonEntity body) || !body.isAlive()) { it.remove(); continue; }
            if (level.getNearestPlayer(body, FAR_BLOCKS) == null) { body.discard(); it.remove(); removed++; }
        }
    }

    static PopulationPlan.Person find(ServerLevel level, TownspersonEntity body) {
        for (PopulationPlan.Person p : roster(level, body.city())) if (p.id() == body.person()) return p;
        return null;
    }

    /** People of this service within {@code radius} of a player. */
    static int near(ServerLevel level, Player player, double radius) {
        int count = 0;
        for (UUID id : ACTIVE.keySet()) if (level.getEntity(id) instanceof TownspersonEntity body && body.distanceToSqr(player) <= radius * radius) count++;
        return count;
    }

    // ------------------------------------------------------------------------------------------------ one step of a person's errand

    /** One step of a body's errand; false when it is done (gone in at a door, or given up out of sight). */
    static boolean step(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players, long now, int hour, long day) {
        boolean moving = w.phase == Phase.WALK || w.phase == Phase.ENTER || w.phase == Phase.BROWSE && w.until == 0 || w.phase == Phase.LEAVE;
        if (moving) {
            if (body.position().distanceToSqr(w.lastPos) > 1.0) { w.lastPos = body.position(); w.lastMoved = now; }
            else if (now - w.lastMoved > 100) {
                // no progress for five seconds: another errand, and after three of those somewhere to go in, or away out of sight
                w.lastMoved = now;
                if (++w.stuck >= 3) { stuckGivenUp++; return goIn(level, body, w, players, now, hour) || !seen(level, players, body.blockPosition()) ? quietly(level, body, w, players) : rest(body, w, now, 200); }
                if (w.phase == Phase.ENTER) return false;
                return newErrand(level, body, w, players, now, hour);
            }
        }
        PopulationPlan.Person who = find(level, body);
        boolean timeToGo = who != null && w.phase != Phase.ENTER && w.phase != Phase.POST && !w.errand.equals("home") && PopulationPlan.place(who, level.getSeed(), hour, day) == PopulationPlan.Place.HOME;
        if (timeToGo && (w.phase == Phase.LINGER || w.phase == Phase.WALK && w.legs > 0)) return goIn(level, body, w, players, now, hour) || quietly(level, body, w, players);
        switch (w.phase) {
            case WALK -> {
                if (w.goal == null) return newErrand(level, body, w, players, now, hour);
                if (!near(body, w.goal, 2.0)) return true;
                return arrive(level, body, w, players, now, hour);
            }
            case LINGER, CHAT -> {
                if (w.phase == Phase.CHAT && level.getEntity(w.partner) instanceof TownspersonEntity other && other.isAlive()) {
                    body.getLookControl().setLookAt(other, 30f, 30f);
                    if (level.random.nextInt(5) == 0) body.swing(InteractionHand.MAIN_HAND);   // talking with their hands
                }
                if (now < w.until) return true;
                w.partner = null;
                body.hold(false);
                return next(level, body, w, players, now, hour);
            }
            case ENTER -> {
                body.stepThrough(w.goal);
                if (w.door != null) HouseholdService.openDoorBriefly(level, w.door.lower(), 20);
                if (near(body, w.goal, 0.8) || now >= w.until) { entered++; return false; }
                return true;
            }
            case BROWSE -> {
                if (w.until == 0) {
                    if (near(body, w.goal, 1.5) || now - w.lastMoved > 80) { w.until = now + 300 + level.random.nextInt(600); body.hold(true); }
                    return true;
                }
                if (level.random.nextInt(6) == 0) body.getLookControl().setLookAt(Vec3.atCenterOf(w.goal.relative(Direction.from2DDataValue(level.random.nextInt(4)), 2)));
                if (now < w.until) return true;
                body.hold(false);
                if (w.shop != null) { if (ShopSupply.tillRefusal(level, w.goal) == null) buyFromShelf(level, w.city, w.shop); w.shop = null; }
                w.phase = Phase.LEAVE;
                w.goal = w.door.lower().relative(w.door.out().getOpposite());
                body.errand(w.goal, 0.6);
                return true;
            }
            case LEAVE -> {
                BlockPos inside = w.door.lower().relative(w.door.out().getOpposite()), outside = w.door.lower().relative(w.door.out());
                if (near(body, inside, 1.6) || near(body, w.door.lower(), 1.2)) { HouseholdService.openDoorBriefly(level, w.door.lower(), 30); body.stepThrough(outside.relative(w.door.out())); }
                if (near(body, outside.relative(w.door.out()), 1.2) || near(body, outside, 0.9)) { w.door = null; return next(level, body, w, players, now, hour); }
                return true;
            }
            case POST -> { return true; }
        }
        return true;
    }

    /** A customer bought this much off the shelves (D-164), for the status command and the notes. */
    static long shelfPurchases;
    /** One customer in two who browsed a shop buys one thing from it on the way out. */
    static final int BUY_ONE_IN = 2;

    /**
     * A customer leaving a shop buys one unit of something its counter sells, from the city's shared shelf (D-164): the shelf a Vintner or a Smith fills empties again in active play, so the shop
     * buys from them again. Only lines a supplier can fill are bought this way; the shop's everyday lines refill on their own.
     */
    static void buyFromShelf(ServerLevel level, String city, String outlet) {
        if (level.random.nextInt(BUY_ONE_IN) != 0) return;
        List<RegionalStock> lines = RegionalCatalog.INSTANCE.forOutlet(city, outlet).stream().filter(st -> RegionalCatalog.INSTANCE.supply(st.id()) != null).toList();
        if (lines.isEmpty()) return;
        WorkOrderLedger ledger = WorkOrderLedger.get(level.getServer());
        try { if (ledger.drawDown(lines.get(level.random.nextInt(lines.size())), 1) > 0) shelfPurchases++; }
        catch (RuntimeException error) { LOGGER.warn("A customer's purchase off a shelf was not saved: {}", error.toString()); }   // a ledger that cannot write never stops the street
    }

    private static boolean near(TownspersonEntity body, BlockPos pos, double r) {
        double dx = body.getX() - (pos.getX() + 0.5), dz = body.getZ() - (pos.getZ() + 0.5), dy = body.getY() - pos.getY();
        return dx * dx + dz * dz <= r * r && Math.abs(dy) < 2.1;
    }

    private static boolean rest(TownspersonEntity body, Walker w, long now, int ticks) {
        w.phase = Phase.LINGER; w.until = now + ticks; body.errand(null, 0); body.hold(true);
        return true;
    }

    /** Arrived where the errand went. */
    private static boolean arrive(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players, long now, int hour) {
        w.stuck = 0;
        switch (w.errand) {
            case "shop" -> {
                Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, w.door.lower().getX() >> 4, w.door.lower().getZ() >> 4);
                if (b.isPresent() && BuildingGuard.openToPublic(level, b.get(), null)) {
                    StaffZones.Zones zones = StaffZones.of(level, b.get());
                    BlockPos floor = randomCell(zones.publicCells(), level.random, w.door.lower());
                    if (floor != null) {
                        HouseholdService.openDoorBriefly(level, w.door.lower(), 30);
                        body.stepThrough(w.door.lower().relative(w.door.out().getOpposite()));
                        w.phase = Phase.BROWSE; w.goal = floor; w.until = 0; w.lastMoved = now;
                        w.shop = ShopCounters.INSTANCE.forBuilding(b.get().type()).filter(shop -> shop.city().equals(w.city)).map(ShopCounters.Shop::outlet).orElse(null);
                        body.errand(floor, 0.5);
                        browsed++;
                        return true;
                    }
                }
                // shut, or nowhere to stand inside: look in at the window a while
                body.getLookControl().setLookAt(Vec3.atCenterOf(w.door.lower()));
                return rest(body, w, now, 80 + level.random.nextInt(160));
            }
            case "venue", "work" -> {
                // after dark a bar's crowd is half outside it, and a night shift takes its break at the works' door: two in three stand at the door a while, with whoever else is there,
                // then go on (measured in crowd run 4: at 22:00 Blackwater's bars had 18 people within 64 blocks and 1.6 in sight, because every visitor stepped straight inside)
                boolean night = hour >= 20 || hour < 5;
                if (night && level.random.nextInt(3) != 0) return chatOrStand(level, body, w, now, 400, 900);
                return enter(level, body, w, players, now) || rest(body, w, now, 60 + level.random.nextInt(100));
            }
            case "home" -> { return enter(level, body, w, players, now) || rest(body, w, now, 60 + level.random.nextInt(100)); }
            case "deliver" -> {
                body.getLookControl().setLookAt(Vec3.atCenterOf(w.door.lower()));
                return rest(body, w, now, 50 + level.random.nextInt(60));
            }
            default -> {
                // a stroll ends: talk to somebody standing near, or stand a while
                return chatOrStand(level, body, w, now, 160, 100);
            }
        }
    }

    /** Talks to somebody standing near (for at least {@code chat} ticks), or stands a while (at least {@code stand} ticks). */
    private static boolean chatOrStand(ServerLevel level, TownspersonEntity body, Walker w, long now, int chat, int stand) {
        Walker other = idleNear(level, body, w, 5);
        if (other != null && level.getEntity(uuidOf(other)) instanceof TownspersonEntity partner) {
            long until = now + chat + level.random.nextInt(chat * 2 + 20);
            w.phase = Phase.CHAT; w.until = until; w.partner = partner.getUUID(); body.errand(null, 0); body.hold(true);
            other.phase = Phase.CHAT; other.until = until; other.partner = body.getUUID(); partner.errand(null, 0); partner.hold(true);
            chats++;
            return true;
        }
        return rest(body, w, now, stand + level.random.nextInt(stand * 3));
    }

    /** Goes in at the errand's door: the door opens a moment (never with a player close enough to follow) and the body steps through and is gone. */
    private static boolean enter(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players, long now) {
        if (w.door == null) return false;
        for (ServerPlayer p : players) if (p.distanceToSqr(Vec3.atCenterOf(w.door.lower())) < TAILGATE_DISTANCE * TAILGATE_DISTANCE) return false;
        HouseholdService.openDoorBriefly(level, w.door.lower(), 20);
        w.phase = Phase.ENTER;
        w.goal = w.door.lower().relative(w.door.out().getOpposite());
        w.until = now + 50;
        w.lastMoved = now;
        body.stepThrough(w.goal);
        return true;
    }

    /** The next thing after an errand: another, or (after a few) in at a door or away out of sight. */
    private static boolean next(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players, long now, int hour) {
        if (++w.legs >= 4 && level.random.nextInt(2) == 0) return goIn(level, body, w, players, now, hour) || quietly(level, body, w, players) || newErrand(level, body, w, players, now, hour);
        return newErrand(level, body, w, players, now, hour);
    }

    /** Sends a body in at the nearest door that is theirs to go in at (home for a resident, any for the rest). */
    private static boolean goIn(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players, long now, int hour) {
        List<Candidate> doors = doorsNear(level, body.blockPosition(), 0, 32, w.role.equals("resident") ? EnumSetHolder.HOMES : EnumSetHolder.ANY);
        if (doors.isEmpty()) return false;
        doors.sort(Comparator.comparingDouble(c -> c.door().lower().distSqr(body.blockPosition())));
        Candidate c = doors.get(0);
        w.errand = "home"; w.door = c.door(); w.building = c.building().key(); w.phase = Phase.WALK; w.goal = c.door().lower().relative(c.door().out()); w.lastMoved = now;
        body.hold(false);
        body.errand(w.goal, 0.55);
        return true;
    }

    /** Takes a body away where nobody sees it go; false (and it stays) while somebody could. */
    private static boolean quietly(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players) {
        if (seen(level, players, body.blockPosition())) return true;
        removed++;
        return false;
    }

    /**
     * A new errand for the role and the hour. A worker in working hours makes for a works, an office or a shop; a resident for the shops, home or a stroll; a courier for another door; a visitor,
     * after dark, for a bar or a club; and anybody without a door in reach for a stroll down the street.
     */
    static boolean newErrand(ServerLevel level, TownspersonEntity body, Walker w, List<ServerPlayer> players, long now, int hour) {
        body.hold(false);
        int roll = level.random.nextInt(100);
        boolean evening = hour >= 18 || hour < 3;
        String kind = switch (w.role) {
            case "worker" -> roll < 45 ? "work" : roll < 65 ? "shop" : "stroll";
            case "courier" -> roll < 75 ? "deliver" : "stroll";
            case "visitor" -> evening ? (roll < 55 ? "venue" : roll < 75 ? "stroll" : "shop") : (roll < 50 ? "shop" : "stroll");
            default -> roll < 38 ? "shop" : roll < 58 ? "home" : "stroll";
        };
        java.util.EnumSet<PopulationDensity.Area> wanted = switch (kind) {
            case "work" -> EnumSetHolder.WORK;
            case "shop" -> EnumSetHolder.SHOPS;
            case "home" -> EnumSetHolder.HOMES;
            case "venue" -> EnumSetHolder.VENUES;
            case "deliver" -> EnumSetHolder.ANY;
            default -> null;
        };
        w.stuck = Math.max(0, w.stuck - 1);
        w.lastMoved = now;
        if (wanted != null) {
            List<Candidate> doors = doorsNear(level, body.blockPosition(), 10, 44, wanted);
            if (kind.equals("shop")) doors.removeIf(c -> !BuildingGuard.openToPublic(level, c.building(), null) && level.random.nextInt(3) != 0);
            ServerPlayer near = nearest(players, body.blockPosition(), 64);
            if (near != null && doors.size() > 1 && level.random.nextInt(10) < TOWARDS_PLAYER) {
                List<Candidate> inView = new ArrayList<>();
                for (Candidate c : doors) if (inLine(level, near, c.door().lower().relative(c.door().out()))) inView.add(c);
                if (!inView.isEmpty()) doors = inView;
            }
            if (!doors.isEmpty()) {
                Candidate c = doors.get(level.random.nextInt(doors.size()));
                w.errand = kind; w.door = c.door(); w.building = c.building().key(); w.phase = Phase.WALK; w.goal = c.door().lower().relative(c.door().out());
                body.errand(w.goal, kind.equals("deliver") ? 0.75 : 0.55);
                return true;
            }
        }
        // a stroll: somewhere along an open street a little way off; near a player, mostly along the streets round that player, where somebody is to see them (measured: before this,
        // strolls ended in yards and courts behind the buildings, and a street with forty people within 64 blocks showed two to six of them)
        BlockPos here = body.blockPosition();
        ServerPlayer watcher = nearest(players, here, 64);
        boolean towards = watcher != null && level.random.nextInt(10) < TOWARDS_PLAYER;
        List<ChunkPos> chunks = towards ? strollChunks(watcher.chunkPosition(), here) : List.of();
        if (chunks.isEmpty()) chunks = strollChunks(new ChunkPos(here), here);
        BlockPos fallback = null;
        for (int attempt = 0; attempt < (towards ? 8 : 6) && !chunks.isEmpty(); attempt++) {
            ChunkPos chunk = chunks.get(level.random.nextInt(chunks.size()));
            Optional<BlockPos> spot = streetSpot(level, chunk, level.random);
            if (towards && spot.isPresent() && !inLine(level, watcher, spot.get())) {   // somewhere the player could see them; else the first spot found, if nothing better
                if (fallback == null) fallback = spot.get();
                continue;
            }
            if (spot.isPresent()) {
                w.errand = "stroll"; w.door = null; w.phase = Phase.WALK; w.goal = spot.get();
                body.errand(w.goal, 0.5);
                return true;
            }
        }
        if (fallback != null) {
            w.errand = "stroll"; w.door = null; w.phase = Phase.WALK; w.goal = fallback;
            body.errand(w.goal, 0.5);
            return true;
        }
        return rest(body, w, now, 100 + level.random.nextInt(200));
    }

    /**
     * Out of ten errands by somebody within 64 blocks of a player, how many go somewhere that player has a line of sight to (a stroll's end, or the outside of a door): people walk where
     * somebody is to see them. Measured before this: in a grid of one-chunk blocks a street shows a player only a small share of the people round them, and an even spread left two to nine of
     * forty in sight.
     */
    static final int TOWARDS_PLAYER = 7;

    /** True when {@code player}'s eyes have a clear line to a person standing at {@code feet}, within 48 blocks, whichever way the player faces. */
    static boolean inLine(ServerLevel level, ServerPlayer player, BlockPos feet) {
        Vec3 eye = player.getEyePosition(), head = Vec3.atBottomCenterOf(feet).add(0, 1.6, 0);
        if (eye.distanceToSqr(head) > 48 * 48) return false;
        return level.clip(new ClipContext(eye, head, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    /**
     * Where a stroll from {@code here} may end (round a player when {@link #TOWARDS_PLAYER} says so): the known street chunks within two of {@code centre} (not the walker's own) whose middle is within a path's reach, the open streets (no building) if
     * there are any, else any street chunk. Only chunks Lost Cities has already answered are used.
     */
    static List<ChunkPos> strollChunks(ChunkPos centre, BlockPos here) {
        List<ChunkPos> open = new ArrayList<>(), any = new ArrayList<>();
        int hx = here.getX() >> 4, hz = here.getZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            ChunkPos c = new ChunkPos(centre.x + dx, centre.z + dz);
            if (c.x == hx && c.z == hz) continue;
            double ddx = c.getMiddleBlockX() - here.getX(), ddz = c.getMiddleBlockZ() - here.getZ();
            if (ddx * ddx + ddz * ddz > STROLL_REACH * STROLL_REACH) continue;
            Integer street = STREETS.get(c.toLong());
            if (street == null || street < 0) continue;
            (OPEN.contains(c.toLong()) ? open : any).add(c);
        }
        return open.isEmpty() ? any : open;
    }

    /** How far off a stroll may end (inside a walker's path range of 48). */
    static final int STROLL_REACH = 40;

    private static ServerPlayer nearest(List<ServerPlayer> players, BlockPos pos, double within) {
        ServerPlayer best = null;
        double bestSq = within * within;
        for (ServerPlayer p : players) {
            double d = p.distanceToSqr(Vec3.atCenterOf(pos));
            if (d <= bestSq) { best = p; bestSq = d; }
        }
        return best;
    }

    private static Walker idleNear(ServerLevel level, TownspersonEntity body, Walker me, double r) {
        for (var entry : ACTIVE.entrySet()) {
            Walker other = entry.getValue();
            if (other == me || other.phase != Phase.LINGER || other.partner != null) continue;
            if (level.getEntity(entry.getKey()) instanceof TownspersonEntity o && o.distanceToSqr(body) <= r * r) return other;
        }
        return null;
    }

    private static UUID uuidOf(Walker w) {
        for (var entry : ACTIVE.entrySet()) if (entry.getValue() == w) return entry.getKey();
        return null;
    }

    private static BlockPos randomCell(java.util.Set<Long> cells, RandomSource random, BlockPos door) {
        if (cells.isEmpty()) return null;
        List<Long> list = new ArrayList<>(cells);
        for (int attempt = 0; attempt < 8; attempt++) {
            BlockPos cell = BlockPos.of(list.get(random.nextInt(list.size())));
            if (cell.distSqr(door) >= 4) return cell;
        }
        return BlockPos.of(list.get(random.nextInt(list.size())));
    }

    // ------------------------------------------------------------------------------------------------ doors and areas

    /** The areas an errand looks for. */
    private static final class EnumSetHolder {
        static final java.util.EnumSet<PopulationDensity.Area> WORK = java.util.EnumSet.of(PopulationDensity.Area.OFFICE, PopulationDensity.Area.INDUSTRIAL, PopulationDensity.Area.COMMERCIAL);
        static final java.util.EnumSet<PopulationDensity.Area> SHOPS = java.util.EnumSet.of(PopulationDensity.Area.COMMERCIAL);
        static final java.util.EnumSet<PopulationDensity.Area> HOMES = java.util.EnumSet.of(PopulationDensity.Area.RESIDENTIAL);
        static final java.util.EnumSet<PopulationDensity.Area> VENUES = java.util.EnumSet.of(PopulationDensity.Area.NIGHTLIFE, PopulationDensity.Area.COMMERCIAL);
        static final java.util.EnumSet<PopulationDensity.Area> ANY = java.util.EnumSet.allOf(PopulationDensity.Area.class);
    }

    record Candidate(CityBuildings.Building building, TechService.StreetDoor door) {}

    /** Buildings' street doors (found once each, a few searches a pass). */
    private static final Map<Long, Optional<TechService.StreetDoor>> DOORS = new LinkedHashMap<>();
    /** What kind of area each city chunk is, or null for a chunk that is not city (water, fields). */
    private static final Map<Long, Optional<PopulationDensity.Area>> AREAS = new HashMap<>();

    static Optional<TechService.StreetDoor> doorOf(ServerLevel level, CityBuildings.Building b) {
        Optional<TechService.StreetDoor> known = DOORS.get(b.key());
        if (known != null) return known;
        if (scans <= 0 || level.getChunkSource().getChunkNow(b.chunkX(), b.chunkZ()) == null) return Optional.empty();
        scans--;
        Optional<TechService.StreetDoor> found = TechService.streetDoor(level, b);
        if (DOORS.size() >= 4096) DOORS.remove(DOORS.keySet().iterator().next());
        DOORS.put(b.key(), found);
        return found;
    }

    /** Street doors of buildings of the wanted areas between {@code min} and {@code max} blocks of {@code from}, in loaded chunks whose plan is known. */
    static List<Candidate> doorsNear(ServerLevel level, BlockPos from, double min, double max, java.util.EnumSet<PopulationDensity.Area> wanted) {
        List<Candidate> out = new ArrayList<>();
        int r = (int) Math.ceil(max / 16.0), cx = from.getX() >> 4, cz = from.getZ() >> 4;
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int x = cx + dx, z = cz + dz;
            if (!CityBuildings.INSTANCE.known(x, z) || level.getChunkSource().getChunkNow(x, z) == null) continue;
            Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, x, z);
            if (b.isEmpty() || !wanted.contains(areaOf(b.get()))) continue;
            Optional<TechService.StreetDoor> door = doorOf(level, b.get());
            if (door.isEmpty()) continue;
            double d = Math.sqrt(door.get().lower().distSqr(from));
            if (d < min || d > max) continue;
            if (!standable(level, door.get().lower().relative(door.get().out())) && !standable(level, door.get().lower().relative(door.get().out()).below())) continue;
            out.add(new Candidate(b.get(), door.get()));
        }
        return out;
    }

    static PopulationDensity.Area areaOf(CityBuildings.Building b) {
        String category = b.security() == null ? "public" : CityBuildings.INSTANCE.category(b.security().id());
        return PopulationDensity.areaOf(category, b.type());
    }

    /** The area of a city chunk (a building's, or open street), or empty for a chunk that is not city; null while it is not yet known and no lookup is left this pass. */
    static Optional<PopulationDensity.Area> areaOfChunk(ServerLevel level, int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        Optional<PopulationDensity.Area> known = AREAS.get(key);
        if (known != null) return known;
        // never for a chunk that is not loaded: Lost Cities then plans it on the spot (measured: one such lookup held a pass for 611 ms after a teleport into a city)
        if (level.getChunkSource().getChunkNow(cx, cz) == null) return null;
        if (!CityBuildings.INSTANCE.askable(level, cx, cz)) return null;   // Lost Cities is generating and this plan is not recorded: ask in a few seconds (D-167)
        if (!CityBuildings.INSTANCE.known(cx, cz) && lookups <= 0) return null;
        if (!CityBuildings.INSTANCE.known(cx, cz)) lookups--;
        Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
        Optional<PopulationDensity.Area> area;
        if (b.isPresent()) area = Optional.of(areaOf(b.get()));
        else {
            if (!STREETS.containsKey(key) && lookups-- <= 0) return null;
            area = streetOf(level, new ChunkPos(cx, cz)) >= 0 ? Optional.of(PopulationDensity.Area.STREET) : Optional.empty();
        }
        if (AREAS.size() >= 8192) AREAS.clear();
        AREAS.put(key, area);
        return area;
    }

    /** The summed weight of the chunks round a player (a square {@link #WINDOW} chunks each way). */
    static double windowWeight(ServerLevel level, ServerPlayer player, String city, int hour) {
        ChunkPos here = player.chunkPosition();
        double sum = 0;
        for (int dx = -WINDOW; dx <= WINDOW; dx++) for (int dz = -WINDOW; dz <= WINDOW; dz++) {
            Optional<PopulationDensity.Area> area = areaOfChunk(level, here.x + dx, here.z + dz);
            if (area != null && area.isPresent()) sum += PopulationDensity.weight(city, area.get(), hour);
        }
        return sum;
    }

    // ------------------------------------------------------------------------------------------------ filling the street

    private static void topUp(ServerLevel level, ServerPlayer player, List<ServerPlayer> everyone, long now, int hour, long day) {
        PopulationDensity.Preset preset = activePreset();
        Optional<String> inCity = CityRegions.INSTANCE.cityAt(level, player.blockPosition());
        if (inCity.isEmpty()) return;
        String city = inCity.get();
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(city);
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst();
        if (def == null || region.isEmpty()) return;
        int target = PopulationDensity.target(windowWeight(level, player, city, hour), preset);
        int around = near(level, player, 64);
        lastTarget = target; lastAround = around;
        if (around > target + 4) { thin(level, player, everyone, now, hour); return; }
        if (ACTIVE.size() >= preset.world) return;
        ChunkPos here = player.chunkPosition();
        java.util.Set<Long> alreadyOut = new java.util.HashSet<>();
        for (Walker w : ACTIVE.values()) alreadyOut.add(w.person);
        List<PopulationPlan.Person> candidates = new ArrayList<>();
        for (PopulationPlan.Person who : roster(level, city)) {
            if (alreadyOut.contains(who.id()) || PopulationPlan.place(who, level.getSeed(), hour, day) == PopulationPlan.Place.HOME) continue;
            Long retryAfter = RETRY_AFTER.get(who.id());
            if (retryAfter != null && retryAfter > now) continue;
            int dx = region.get().centerChunkX() + who.districtX() - here.x, dz = region.get().centerChunkZ() + who.districtZ() - here.z;
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= NEAR_CHUNKS) candidates.add(who);
        }
        if (candidates.isEmpty()) return;
        // a stable order within the hour (the same people come out in the same order), different from hour to hour
        long salt = PopulationPlan.mix(level.getSeed() + day * 24 + hour);
        candidates.sort(Comparator.comparingLong(p -> PopulationPlan.mix(p.id() ^ salt)));
        int made = 0;
        for (int i = 0; i < candidates.size() && made < SPAWNS_PER_PLAYER && around + made < target && ACTIVE.size() < preset.world; i++) {
            if (spawn(level, player, everyone, candidates.get(i), now, hour, salt)) made++;
            else if (spotMisses % 4 == 3) break;
        }
    }

    static int lastTarget, lastAround;

    /** Too many round a player (the hour moved on): a few who are out of sight go in at a door or away. */
    private static void thin(ServerLevel level, ServerPlayer player, List<ServerPlayer> everyone, long now, int hour) {
        int sent = 0;
        for (var entry : new ArrayList<>(ACTIVE.entrySet())) {
            if (sent >= 2) break;
            Walker w = entry.getValue();
            if (w.phase == Phase.ENTER || w.errand.equals("home") || w.phase == Phase.POST) continue;
            if (!(level.getEntity(entry.getKey()) instanceof TownspersonEntity body) || body.distanceToSqr(player) > 64 * 64 || seen(level, everyone, body.blockPosition())) continue;
            if (!goIn(level, body, w, everyone, now, hour)) { body.discard(); ACTIVE.remove(entry.getKey()); removed++; }
            sent++;
        }
    }

    private static boolean spawn(ServerLevel level, ServerPlayer player, List<ServerPlayer> everyone, PopulationPlan.Person who, long now, int hour, long salt) {
        RandomSource random = RandomSource.create(PopulationPlan.mix(who.id() ^ salt));
        boolean guard = who.role().equals("guard");
        // out of a door, where one is in reach and nobody stands right at it
        if (!guard && random.nextInt(10) < 6) {
            List<Candidate> doors = doorsNear(level, player.blockPosition(), 12, 52, EnumSetHolder.ANY);
            doors.removeIf(c -> tooClose(everyone, c.door().lower(), 8) || seenClose(level, everyone, c.door().lower(), 18));
            List<Candidate> inView = new ArrayList<>();
            for (Candidate c : doors) if (inLine(level, player, c.door().lower().relative(c.door().out()))) inView.add(c);
            if (!inView.isEmpty() && random.nextInt(10) < TOWARDS_PLAYER) doors = inView;   // a door on a street the player has a line to (behind them, or too far off to see who comes out)
            if (!doors.isEmpty()) {
                Candidate c = doors.get(random.nextInt(doors.size()));
                BlockPos inside = c.door().lower().relative(c.door().out().getOpposite()), outside = c.door().lower().relative(c.door().out());
                BlockPos at = standable(level, inside) ? inside : standable(level, outside) ? outside : null;
                if (at != null) {
                    TownspersonEntity body = create(level, who, at, c.door().out());
                    if (body != null) {
                        HouseholdService.openDoorBriefly(level, c.door().lower(), 30);
                        Walker w = new Walker(who.id(), who.city(), who.role());
                        w.phase = Phase.LEAVE; w.door = c.door(); w.lastMoved = now; w.lastPos = body.position();
                        ACTIVE.put(body.getUUID(), w);
                        body.stepThrough(outside.relative(c.door().out()));
                        spawned++; spawnedAtDoors++;
                        return true;
                    }
                }
            }
        }
        // or on the street where nobody can see the spot: mostly an open street of the window (a street or a square, not a building's yard), so a new person walks into view along it
        ChunkPos district = player.chunkPosition();
        List<ChunkPos> open = new ArrayList<>();
        for (int dx = -WINDOW; dx <= WINDOW; dx++) for (int dz = -WINDOW; dz <= WINDOW; dz++) if (OPEN.contains(ChunkPos.asLong(district.x + dx, district.z + dz))) open.add(new ChunkPos(district.x + dx, district.z + dz));
        BlockPos chosen = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            ChunkPos chunk = !open.isEmpty() && random.nextInt(4) != 0 ? open.get(random.nextInt(open.size())) : new ChunkPos(district.x + random.nextInt(7) - 3, district.z + random.nextInt(7) - 3);
            Optional<BlockPos> spot = findSpot(level, chunk, random, everyone);
            if (spot.isEmpty() || seen(level, everyone, spot.get())) continue;
            if (chosen == null) chosen = spot.get();
            if (inLine(level, player, spot.get()) && !seenClose(level, everyone, spot.get(), 64)) { chosen = spot.get(); break; }   // out of every player's view, but on a street this one has a line to: they walk into sight
        }
        if (chosen != null) {
            BlockPos at = chosen;
            TownspersonEntity body = create(level, who, at, Direction.from2DDataValue(random.nextInt(4)));
            if (body == null) return false;
            Walker w = new Walker(who.id(), who.city(), who.role());
            w.lastMoved = now; w.lastPos = body.position();
            ACTIVE.put(body.getUUID(), w);
            if (guard) { w.phase = Phase.POST; body.restrictTo(at, 3); }
            else newErrand(level, body, w, everyone, now, hour);
            spawned++;
            return true;
        }
        spotMisses++;
        if (RETRY_AFTER.size() >= 4096) RETRY_AFTER.clear();
        RETRY_AFTER.put(who.id(), now + RETRY_TICKS);
        return false;
    }

    private static TownspersonEntity create(ServerLevel level, PopulationPlan.Person who, BlockPos at, Direction facing) {
        TownspersonEntity body = UndergroundCity.TOWNSPERSON.get().create(level);
        if (body == null) return null;
        body.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, facing.toYRot(), 0f);
        body.become(who, at);
        body.clearRestriction();
        return level.addFreshEntity(body) ? body : null;
    }

    private static boolean tooClose(List<ServerPlayer> players, BlockPos pos, double r) {
        for (ServerPlayer p : players) if (p.distanceToSqr(Vec3.atCenterOf(pos)) < r * r) return true;
        return false;
    }

    /** True when a player could see {@code feet} (a person standing there): in front of them, within forty blocks, with nothing in between. */
    static boolean seen(ServerLevel level, List<ServerPlayer> players, BlockPos feet) { return seenClose(level, players, feet, 40); }

    static boolean seenClose(ServerLevel level, List<ServerPlayer> players, BlockPos feet, double within) {
        Vec3 head = Vec3.atBottomCenterOf(feet).add(0, 1.6, 0);
        for (ServerPlayer p : players) {
            Vec3 eye = p.getEyePosition();
            Vec3 to = head.subtract(eye);
            double d = to.length();
            if (d > within) continue;
            if (d < 3) return true;
            if (p.getLookAngle().dot(to.scale(1 / d)) < 0.35) continue;   // behind them, or off to the side
            var hit = level.clip(new ClipContext(eye, head, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ where to stand

    private static final Map<Long, Integer> STREETS = new HashMap<>();
    /** A Lost Cities lookup slower than this is logged (at most {@code 20} a session). */
    static final long SLOW_LOOKUP_NANOS = 50_000_000L;
    private static int slowLookupsLogged;
    /** The city chunks Lost Cities planned with no building: streets, squares and parks, where people are seen. Filled with {@link #STREETS}, from the same answer. */
    private static final java.util.Set<Long> OPEN = new java.util.HashSet<>();
    private static long streetsSeed;
    /** A person whose spot search failed is not tried again for this many ticks; the next candidate is tried instead. */
    static final long RETRY_TICKS = 300;
    private static final Map<Long, Long> RETRY_AFTER = new HashMap<>();

    /**
     * The street level of a city chunk, or -1 when the chunk is not city (or Lost Cities cannot say): Lost Cities' own answer, asked once per chunk. The plan is fixed by the seed, and one lookup
     * costs tens of milliseconds, so asking again every second was nearly the whole cost of a pass (measured: 98 percent of it in a real street, 50 lookups of 37 ms in 74 passes).
     */
    static int streetOf(ServerLevel level, ChunkPos chunk) {
        if (streetsSeed != level.getSeed()) { STREETS.clear(); OPEN.clear(); RETRY_AFTER.clear(); AREAS.clear(); DOORS.clear(); streetsSeed = level.getSeed(); }
        Integer known = STREETS.get(chunk.toLong());
        if (known != null) return known;
        if (!LostCitiesLink.askable(level, chunk.x, chunk.z)) { LostCitiesLink.deferred++; return -1; }   // not cached: asked again once Lost Cities is not generating (D-167)
        long started = System.nanoTime();
        var sample = LostCitiesLink.chunk(level, chunk.x, chunk.z);
        int street = sample.isPresent() && sample.get().city() ? LostCitiesLink.realHeight(level, sample.get().cityLevel()) : -1;
        boolean open = street >= 0 && (sample.get().building() == null || sample.get().building().isEmpty()) && sample.get().buildingId().isEmpty();
        long took = System.nanoTime() - started;
        lcCalls++; lcNanos += took; lcWorst = Math.max(lcWorst, took);
        if (took > SLOW_LOOKUP_NANOS && slowLookupsLogged < 20) {   // a few lines a session, for finding what makes one lookup slow (measured: up to 611 ms, on the first pass after arriving in a city)
            slowLookupsLogged++;
            LOGGER.warn("[UC-POPULATION] a Lost Cities lookup took {} ms: chunk {}, {}, loaded {}, street {}, building '{}', lookup {} this session",
                took / 1_000_000, chunk.x, chunk.z, level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null, street, sample.map(LostCitiesLink.ChunkSample::building).orElse("?"), lcCalls);
        }
        if (STREETS.size() >= 4096) { STREETS.clear(); OPEN.clear(); }
        STREETS.put(chunk.toLong(), street);
        if (open) OPEN.add(chunk.toLong());
        return street;
    }

    /**
     * A spot in the district's chunk where a person could be standing in a street: loaded, at the street level Lost Cities gave this chunk, solid and sturdy underfoot, two blocks of air above, open to the
     * sky (so not inside a building and not under a bridge), not in water, and between {@link #MIN_SPOT_DISTANCE} and {@link #MAX_SPOT_DISTANCE} blocks from every player (outside their nose, inside their sight).
     * Never loads a chunk.
     */
    static Optional<BlockPos> findSpot(ServerLevel level, ChunkPos chunk, RandomSource random, List<ServerPlayer> players) {
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return Optional.empty();
        if (inPass && !STREETS.containsKey(chunk.toLong()) && lookups-- <= 0) return Optional.empty();   // in a pass, a chunk not yet answered costs a Lost Cities lookup from the pass's budget, like the areas
        int street = streetOf(level, chunk);
        if (street < 0) return Optional.empty();
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = chunk.getMinBlockX() + random.nextInt(16), z = chunk.getMinBlockZ() + random.nextInt(16);
            for (int y = street + 2; y >= street; y--) {
                feet.set(x, y, z);
                if (!standable(level, feet) || !BuildingGuard.exposed(level, feet.above(2))) continue;
                double nearest = Double.MAX_VALUE;
                for (ServerPlayer p : players) nearest = Math.min(nearest, p.distanceToSqr(x + 0.5, y, z + 0.5));
                if (nearest < (double) MIN_SPOT_DISTANCE * MIN_SPOT_DISTANCE || nearest > (double) MAX_SPOT_DISTANCE * MAX_SPOT_DISTANCE) continue;
                return Optional.of(feet.immutable());
            }
        }
        return Optional.empty();
    }

    /** A street spot in a chunk (for a stroll's end), with no rule about players: loaded, at the street level, open to the sky, standable. Never asks Lost Cities about a chunk it has not answered. */
    static Optional<BlockPos> streetSpot(ServerLevel level, ChunkPos chunk, RandomSource random) {
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null || !STREETS.containsKey(chunk.toLong())) return Optional.empty();
        int street = STREETS.get(chunk.toLong());
        if (street < 0) return Optional.empty();
        BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = chunk.getMinBlockX() + random.nextInt(16), z = chunk.getMinBlockZ() + random.nextInt(16);
            for (int y = street + 2; y >= street; y--) {
                feet.set(x, y, z);
                if (standable(level, feet) && BuildingGuard.exposed(level, feet.above(2))) return Optional.of(feet.immutable());
            }
        }
        return Optional.empty();
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        BlockState at = level.getBlockState(feet), head = level.getBlockState(feet.above()), below = level.getBlockState(feet.below());
        if (!at.getCollisionShape(level, feet).isEmpty() || !head.getCollisionShape(level, feet.above()).isEmpty() || !at.getFluidState().isEmpty()) return false;
        if (!below.isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP) || !below.getFluidState().isEmpty()) return false;
        return !below.is(net.minecraft.tags.BlockTags.LEAVES) && !below.is(net.minecraft.tags.BlockTags.FIRE) && !below.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK) && !at.is(net.minecraft.tags.BlockTags.FIRE);
    }

    // ------------------------------------------------------------------------------------------------ speaking

    /** The period of the day a line belongs to. */
    static String period(int hour) { return hour < 12 ? "morning" : hour < 18 ? "day" : "evening"; }

    static String subtitle(TownspersonEntity body) {
        return switch (body.role()) {
            case "courier" -> "Courier";
            case "guard" -> "City guard";
            case "police" -> "Police";
            case "fence" -> "Buys things, after dark";
            case "visitor" -> "Out for the evening";
            case "worker" -> ShopCounters.shopOf(body).map(shop -> "Keeps the " + shop.title().toLowerCase(java.util.Locale.ROOT)).orElse(body.homeKey() != 0 ? "Works here" : "Worker");
            default -> body.homeKey() != 0 ? "Lives here" : "Resident";
        };
    }

    /** What this person says now: a line of their role for the time of day, now and then a line about their city, and a different greeting for somebody they have spoken to before. */
    static String say(ServerPlayer player, TownspersonEntity body, boolean returning, int spoken) {
        if (body.police()) return "On a call. Keep clear, please.";
        int hour = PopulationPlan.hourOf(player.level().getDayTime());
        List<String> own = PopulationCatalog.INSTANCE.lines(body.role(), period(hour));
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(body.city());
        long h = PopulationPlan.mix(body.person() + spoken * 7919L + PopulationPlan.dayOf(player.level().getDayTime()));
        String line = def != null && !def.flavour().isEmpty() && (h & 3) == 0 ? def.flavour().get((int) ((h >>> 2) % def.flavour().size())) : own.get((int) (Math.floorMod(h >>> 5, own.size())));
        if ((h & 3) == 1) line = CivicProjects.flavour(player.server, body.city()).orElse(line);   // people talk about what their city finished together (D-171)
        String greeting = returning ? (hour < 12 ? "Morning again, " : hour < 18 ? "Back again, " : "Evening, ") + player.getGameProfile().getName() + ". " : "";
        return greeting + line;
    }

    static void talk(ServerPlayer player, TownspersonEntity body) {
        java.util.Set<Long> met = MET.computeIfAbsent(player.getUUID(), k -> new java.util.LinkedHashSet<>());
        boolean returning = !met.add(body.person());
        while (met.size() > 64) { Long first = met.iterator().next(); met.remove(first); }
        String text = body.homeKey() != 0 ? HouseholdService.say(player, body, returning) : say(player, body, returning, met.size());
        body.getLookControl().setLookAt(player);
        body.getNavigation().stop();
        TerminalNetwork.sendDialogue(player, body.getId(), body.getName().getString(), subtitle(body), body.city(), text, choices(body));
    }

    /** What a conversation offers: a fence's trade (D-125), and always a goodbye. */
    static List<Dialogue.Choice> choices(TownspersonEntity body) {
        List<Dialogue.Choice> out = new java.util.ArrayList<>();
        ShopCounters.shopOf(body).ifPresent(shop -> out.add(new Dialogue.Choice("shop", "What do you sell?", true, "")));
        if (FenceService.fenceOf(body) != null) {
            out.add(new Dialogue.Choice("fence_offer", "Show me what you would pay", true, ""));
            out.add(new Dialogue.Choice("fence_how", "What do you buy?", true, ""));
        }
        out.add(new Dialogue.Choice("bye", "Goodbye", true, ""));
        return out;
    }

    /** A choice in a conversation with a townsperson: the fence's trade, or closing it. */
    static void choose(ServerPlayer player, TownspersonEntity body, String id) {
        // the screen is already closed on the client; answering with a close could shut a newer conversation (a second right click had replaced it: farmer run 2, D-178)
        if (id.equals("end") || player.distanceToSqr(body) > 100.0) return;
        if (id.equals("shop") && ShopCounters.shopOf(body).isPresent()) { TerminalNetwork.closeDialogue(player); ShopCounters.open(player, body); return; }
        FenceCatalog.Fence fence = FenceService.fenceOf(body);
        if (fence != null && id.equals("fence_offer")) { TerminalNetwork.closeDialogue(player); FenceService.open(player, body); return; }
        if (fence != null && id.equals("fence_how")) {
            TerminalNetwork.sendDialogue(player, body.getId(), body.getName().getString(), subtitle(body), body.city(),
                "Goods with a story. Marked, you understand, from a lock somebody forced. I pay " + fence.ratePercent() + " in the hundred of what a thing is worth, less for anything lifted here in "
                    + capitalise(fence.city()) + ": that is too close to home and people talk. Paper and bread I do not want. I only have so much cash about me; it comes in slowly.", choices(body));
            return;
        }
        TerminalNetwork.closeDialogue(player);
    }

    // ------------------------------------------------------------------------------------------------ crime

    /** A blow to a civilian: they run, the striker's heat in that city rises, and that is all (no police respond yet, and the documents say so). */
    @SubscribeEvent
    public void attacked(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof TownspersonEntity body) || !(event.getSource().getEntity() instanceof ServerPlayer striker) || striker.isCreative()) return;
        body.setLastHurtByMob(striker);
        if (!body.city().isEmpty()) {
            try { WorkOrderLedger.get(striker.server).addHeat(striker.getUUID(), body.city(), HEAT_FOR_STRIKING); }
            catch (RuntimeException error) { LOGGER.warn("Heat for striking a civilian could not be recorded: {}", error.toString()); }
            Notice.send(striker, Notice.Kind.HEAT, "Heat rising in " + capitalise(body.city()), "Striking a civilian is a crime. Nobody is on the way, yet; people remember.");
        }
    }

    static String capitalise(String id) { return id.isEmpty() ? id : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1); }

    // ------------------------------------------------------------------------------------------------ status

    /** The people round a player: how many there are within 64 blocks and how many of them the player could see from where they stand. */
    record Around(int within64, int inSight, int target) {}

    static Around around(ServerLevel level, ServerPlayer player) {
        int within = 0, inSight = 0;
        for (TownspersonEntity body : level.getEntitiesOfClass(TownspersonEntity.class, player.getBoundingBox().inflate(64), b -> b.isAlive() && !b.police())) {
            within++;
            if (player.hasLineOfSight(body)) inSight++;
        }
        String city = CityRegions.INSTANCE.cityAt(level, player.blockPosition()).orElse("");
        int target = city.isEmpty() ? 0 : PopulationDensity.target(windowWeight(level, player, city, PopulationPlan.hourOf(level.getDayTime())), activePreset());
        return new Around(within, inSight, target);
    }

    /**
     * Where the people within 64 blocks of a player are and what they are doing, for the crowd runs: street people of this service by phase and errand, and the bodies of other services
     * (households, shopkeepers) apart; whether each stands on an open street, a building's plot or a chunk not yet answered; height against the player; distance; and how many have not moved
     * for five seconds.
     */
    static String census(ServerLevel level, ServerPlayer player) {
        Map<String, Integer> phases = new java.util.TreeMap<>(), errands = new java.util.TreeMap<>();
        int others = 0, open = 0, plot = 0, unknown = 0, below = 0, even = 0, above = 0, near16 = 0, near32 = 0, far = 0, still = 0, openInSight = 0, plotInSight = 0;
        long now = level.getGameTime();
        for (TownspersonEntity body : level.getEntitiesOfClass(TownspersonEntity.class, player.getBoundingBox().inflate(64), b -> b.isAlive() && !b.police() && b.distanceTo(player) <= 64)) {
            Walker w = ACTIVE.get(body.getUUID());
            if (w == null) { others++; continue; }
            phases.merge(w.phase.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
            errands.merge(w.errand.isEmpty() ? "none" : w.errand, 1, Integer::sum);
            long chunk = ChunkPos.asLong(body.getBlockX() >> 4, body.getBlockZ() >> 4);
            boolean sight = player.hasLineOfSight(body);
            if (OPEN.contains(chunk)) { open++; if (sight) openInSight++; } else if (STREETS.containsKey(chunk)) { plot++; if (sight) plotInSight++; } else unknown++;
            double dy = body.getY() - player.getY();
            if (dy < -2) below++; else if (dy > 2) above++; else even++;
            double d = body.distanceTo(player);
            if (d < 16) near16++; else if (d < 32) near32++; else far++;
            if (now - w.lastMoved > 100) still++;
        }
        return String.format(Locale.ROOT, "street people %d (phases %s; errands %s; on an open street %d, %d of them in sight; on a building's plot %d, %d in sight; chunk unknown %d; "
            + "below the player %d, level %d, above %d; within 16 blocks %d, 16 to 32 %d, 32 to 64 %d; not moved for 5 s %d); other bodies (households, shopkeepers) %d",
            open + plot + unknown, phases, errands, open, openInSight, plot, plotInSight, unknown, below, even, above, near16, near32, far, still, others);
    }

    static String status(MinecraftServer server) {
        double average = passes == 0 ? 0 : nanosTotal / (double) passes / 1000.0;
        PopulationDensity.Preset preset = activePreset();
        StringBuilder players = new StringBuilder();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Around a = around(p.serverLevel(), p);
            players.append("; ").append(p.getGameProfile().getName()).append(": ").append(a.within64()).append(" within 64 blocks (").append(a.inSight()).append(" in sight, target ").append(a.target()).append(")");
        }
        return String.format(Locale.ROOT, "density %s; street people out now %d (peak %d, cap %d total / %d per player); passes %d, mean %.0f us, worst %d us (steps %d us, top-up %d us, %d Lost Cities lookups, %d people out), first %d us; Lost Cities lookups %d (mean %.0f us, worst %d us); spawned %d (%d out of a door), removed %d, went in at a door %d, browsed a shop %d (bought %d off a shelf), chats %d, given up %d, spots missed %d; doors known %d; %s%s",
            preset.name().toLowerCase(Locale.ROOT), ACTIVE.size(), activePeak, preset.world, preset.perPlayer, passes, average, nanosWorst / 1000, worstSteps / 1000, worstTopUp / 1000, worstLookups, worstPeople, nanosFirst / 1000, lcCalls,
            lcCalls == 0 ? 0.0 : lcNanos / (double) lcCalls / 1000.0, lcWorst / 1000, spawned, spawnedAtDoors, removed, entered, browsed, shelfPurchases, chats, stuckGivenUp, spotMisses, DOORS.size(), LostCitiesLink.recordStatus(), players);
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_population").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status(context.getSource().getServer())), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    /** Test seam: records a chunk's street level, and whether it is open street, as if Lost Cities had answered. */
    static void streetForTest(ChunkPos chunk, int street, boolean open) {
        STREETS.put(chunk.toLong(), street);
        if (open) OPEN.add(chunk.toLong()); else OPEN.remove(chunk.toLong());
    }

    /** Test seam: how many bodies the service thinks are out. */
    static int activeCount() { return ACTIVE.size(); }

    /** Test seam: registers a body spawned by a test. */
    static void adopt(TownspersonEntity body, long person) { ACTIVE.put(body.getUUID(), new Walker(person, body.city(), body.role())); }

    /** Test seam: the errand of a body, or null. */
    static Walker walkerOf(TownspersonEntity body) { return ACTIVE.get(body.getUUID()); }
}
