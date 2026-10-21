package io.github.stoffe101.undergroundcity;

import com.mojang.brigadier.Command;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a city's roster ({@link PopulationPlan}) into the few real entities a player can see (D-108), and takes them away again.
 *
 * <p>The budget is the design. Once a second, for each of at most a few players, this looks at the people whose district is near the player and who, at this hour, are out in the street; it
 * puts at most one of them on the ground per second at a spot that is outdoors, at street level, loaded and out of the player's face, and it removes anybody who has nobody near or whose
 * time outdoors is over. At most {@link #MAX_PER_PLAYER} people are around any one player and {@link #MAX_ACTIVE} in the world; nothing is spawned into a chunk that is not already loaded and
 * nothing is saved. Everything about who is out is arithmetic, so a city with nobody in it costs nothing, and nobody is "simulated off-screen" beyond the clock.
 *
 * <p>What it is not (stated in the documents too): these people have no jobs that change the economy, no homes the player can enter, no memory beyond greeting a returning player, and no police
 * response; a blow to one is a crime that raises the striker's heat in that city and nothing more.
 */
public final class PopulationService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Server ticks between two passes (one second). */
    static final int CYCLE = 20;
    static final int MAX_PER_PLAYER = 12, MAX_ACTIVE = 40, NEAR_CHUNKS = 6, FAR_BLOCKS = 96, MIN_SPOT_DISTANCE = 14, MAX_SPOT_DISTANCE = 64, PLAYERS_PER_PASS = 4;
    static final int HEAT_FOR_STRIKING = 2;

    /** Active bodies: entity uuid to person id. */
    private static final Map<UUID, Long> ACTIVE = new HashMap<>();
    private static final Map<String, List<PopulationPlan.Person>> ROSTERS = new HashMap<>();
    private static final Map<UUID, java.util.Set<Long>> MET = new HashMap<>();

    // measurements, for /uc_population status and the performance notes
    static long passes, nanosTotal, nanosWorst, spawned, removed, spotMisses, activePeak;
    /** Where a pass spends its time: the first pass, the Lost Cities plan lookups (count, total, worst). */
    static long nanosFirst, lcCalls, lcNanos, lcWorst;

    // ------------------------------------------------------------------------------------------------ rosters

    static List<PopulationPlan.Person> roster(ServerLevel level, String city) {
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(city);
        if (def == null) return List.of();
        String key = city + ":" + level.getSeed() + ":" + def.count() + ":" + def.reachChunks();
        return ROSTERS.computeIfAbsent(key, k -> PopulationPlan.roster(city, level.getSeed(), def.count(), def.mix(), def.names(), def.reachChunks()));
    }

    static void clearCaches() { ROSTERS.clear(); ACTIVE.clear(); MET.clear(); STREETS.clear(); RETRY_AFTER.clear(); passes = nanosTotal = nanosWorst = spawned = removed = spotMisses = activePeak = 0; nanosFirst = lcCalls = lcNanos = lcWorst = 0; }

    // ------------------------------------------------------------------------------------------------ the pass

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CYCLE != 11) return;
        ServerLevel level = event.getServer().overworld();
        if (level.players().isEmpty() && ACTIVE.isEmpty()) return;
        long start = System.nanoTime();
        try { pass(level); }
        catch (RuntimeException error) { LOGGER.warn("[UC-POPULATION] a pass failed and was skipped: {}", error.toString()); }
        long took = System.nanoTime() - start;
        if (passes == 0) nanosFirst = took;
        passes++; nanosTotal += took; nanosWorst = Math.max(nanosWorst, took);
    }

    static void pass(ServerLevel level) {
        if (!LostCitiesLink.ready()) return;
        removeOutOfDate(level);
        List<ServerPlayer> players = new ArrayList<>(level.players());
        players.removeIf(p -> p.isSpectator() || !p.isAlive());
        if (players.isEmpty()) return;
        // a different few players each second, so a crowded server does not pay for everybody at once
        int start = (int) (level.getGameTime() / CYCLE % players.size());
        for (int i = 0; i < Math.min(PLAYERS_PER_PASS, players.size()); i++) topUp(level, players.get((start + i) % players.size()), players);
        activePeak = Math.max(activePeak, ACTIVE.size());
    }

    /** Removes bodies that are gone, far from everybody, or whose person has gone indoors. */
    static void removeOutOfDate(ServerLevel level) {
        long day = PopulationPlan.dayOf(level.getDayTime());
        int hour = PopulationPlan.hourOf(level.getDayTime());
        for (Iterator<Map.Entry<UUID, Long>> it = ACTIVE.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (!(level.getEntity(entry.getKey()) instanceof TownspersonEntity body) || !body.isAlive()) { it.remove(); continue; }
            Player nearest = level.getNearestPlayer(body, FAR_BLOCKS);
            boolean far = nearest == null;
            boolean indoors = false;
            if (!far) {
                PopulationPlan.Person who = find(level, body);
                indoors = who != null && PopulationPlan.place(who, level.getSeed(), hour, day) == PopulationPlan.Place.HOME;
                // somebody whose time outdoors is over leaves once nobody is looking closely, not in front of a player's nose
                if (indoors && nearest.distanceToSqr(body) < MIN_SPOT_DISTANCE * MIN_SPOT_DISTANCE) indoors = false;
            }
            if (far || indoors) { body.discard(); it.remove(); removed++; }
        }
    }

    static PopulationPlan.Person find(ServerLevel level, TownspersonEntity body) {
        for (PopulationPlan.Person p : roster(level, body.city())) if (p.id() == body.person()) return p;
        return null;
    }

    private static int near(ServerLevel level, ServerPlayer player) {
        int count = 0;
        for (UUID id : ACTIVE.keySet()) if (level.getEntity(id) instanceof TownspersonEntity body && body.distanceToSqr(player) <= (double) FAR_BLOCKS * FAR_BLOCKS) count++;
        return count;
    }

    private static void topUp(ServerLevel level, ServerPlayer player, List<ServerPlayer> everyone) {
        if (ACTIVE.size() >= MAX_ACTIVE || near(level, player) >= MAX_PER_PLAYER) return;
        Optional<String> inCity = CityRegions.INSTANCE.cityAt(level, player.blockPosition());
        if (inCity.isEmpty()) return;
        String city = inCity.get();
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(city);
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst();
        if (def == null || region.isEmpty()) return;
        long day = PopulationPlan.dayOf(level.getDayTime());
        int hour = PopulationPlan.hourOf(level.getDayTime());
        ChunkPos here = player.chunkPosition();
        java.util.Set<Long> alreadyOut = new java.util.HashSet<>(ACTIVE.values());
        List<PopulationPlan.Person> candidates = new ArrayList<>();
        for (PopulationPlan.Person who : roster(level, city)) {
            if (alreadyOut.contains(who.id()) || PopulationPlan.place(who, level.getSeed(), hour, day) == PopulationPlan.Place.HOME) continue;
            Long retryAfter = RETRY_AFTER.get(who.id());
            if (retryAfter != null && retryAfter > level.getGameTime()) continue;
            int dx = region.get().centerChunkX() + who.districtX() - here.x, dz = region.get().centerChunkZ() + who.districtZ() - here.z;
            if (Math.max(Math.abs(dx), Math.abs(dz)) <= NEAR_CHUNKS) candidates.add(who);
        }
        if (candidates.isEmpty()) return;
        // a stable order within the hour (the same people appear in the same order), different from hour to hour
        long salt = PopulationPlan.mix(level.getSeed() + day * 24 + hour);
        candidates.sort(Comparator.comparingLong(p -> PopulationPlan.mix(p.id() ^ salt)));
        PopulationPlan.Person chosen = candidates.get(0);
        ChunkPos district = new ChunkPos(region.get().centerChunkX() + chosen.districtX(), region.get().centerChunkZ() + chosen.districtZ());
        Optional<BlockPos> spot = findSpot(level, district, RandomSource.create(PopulationPlan.mix(chosen.id() ^ salt)), everyone);
        if (spot.isEmpty()) { spotMisses++; if (RETRY_AFTER.size() >= 4096) RETRY_AFTER.clear(); RETRY_AFTER.put(chosen.id(), level.getGameTime() + RETRY_TICKS); return; }
        TownspersonEntity body = UndergroundCity.TOWNSPERSON.get().create(level);
        if (body == null) return;
        body.moveTo(spot.get().getX() + 0.5, spot.get().getY(), spot.get().getZ() + 0.5, level.random.nextFloat() * 360f, 0f);
        body.become(chosen, spot.get());
        if (level.addFreshEntity(body)) { ACTIVE.put(body.getUUID(), chosen.id()); spawned++; }
    }

    // ------------------------------------------------------------------------------------------------ where to stand

    private static final Map<Long, Integer> STREETS = new HashMap<>();
    private static long streetsSeed;
    /** A person whose spot search found nothing is not tried again for this many ticks; the next candidate is tried instead. */
    static final long RETRY_TICKS = 300;
    private static final Map<Long, Long> RETRY_AFTER = new HashMap<>();

    /**
     * The street level of a city chunk, or -1 when the chunk is not city (or Lost Cities cannot say): Lost Cities' own answer, asked once per chunk. The plan is fixed by the seed, and one lookup
     * costs tens of milliseconds, so asking again every second was nearly the whole cost of a pass (measured: 98 percent of it in a real street, 50 lookups of 37 ms in 74 passes).
     */
    static int streetOf(ServerLevel level, ChunkPos chunk) {
        if (streetsSeed != level.getSeed()) { STREETS.clear(); RETRY_AFTER.clear(); streetsSeed = level.getSeed(); }
        Integer known = STREETS.get(chunk.toLong());
        if (known != null) return known;
        long started = System.nanoTime();
        var sample = LostCitiesLink.chunk(level, chunk.x, chunk.z);
        int street = sample.isPresent() && sample.get().city() ? LostCitiesLink.realHeight(level, sample.get().cityLevel()) : -1;
        long took = System.nanoTime() - started;
        lcCalls++; lcNanos += took; lcWorst = Math.max(lcWorst, took);
        if (STREETS.size() >= 4096) STREETS.clear();
        STREETS.put(chunk.toLong(), street);
        return street;
    }

    /**
     * A spot in the district's chunk where a person could be standing in a street: loaded, at the street level Lost Cities gave this chunk, solid and sturdy underfoot, two blocks of air above, open to the
     * sky (so not inside a building and not under a bridge), not in water, and between {@link #MIN_SPOT_DISTANCE} and {@link #MAX_SPOT_DISTANCE} blocks from every player (outside their nose, inside their sight).
     * Never loads a chunk.
     */
    static Optional<BlockPos> findSpot(ServerLevel level, ChunkPos chunk, RandomSource random, List<ServerPlayer> players) {
        if (level.getChunkSource().getChunkNow(chunk.x, chunk.z) == null) return Optional.empty();
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
            case "worker" -> body.homeKey() != 0 ? "Works here" : "Worker";
            case "courier" -> "Courier";
            case "guard" -> "City guard";
            case "police" -> "Police";
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
        TerminalNetwork.sendDialogue(player, body.getId(), body.getName().getString(), subtitle(body), body.city(), text, List.of(new Dialogue.Choice("bye", "Goodbye", true, "")));
    }

    /** The one thing a conversation with a townsperson offers: closing it. */
    static void choose(ServerPlayer player, TownspersonEntity body, String id) {
        if (player.distanceToSqr(body) > 100.0) return;
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

    static String status(MinecraftServer server) {
        double average = passes == 0 ? 0 : nanosTotal / (double) passes / 1000.0;
        return String.format(Locale.ROOT, "people out now %d (peak %d, cap %d total / %d per player); passes %d, mean %.0f us, worst %d us, first %d us; Lost Cities lookups %d (mean %.0f us, worst %d us); spawned %d, removed %d, spots missed %d; players met kept %d",
            ACTIVE.size(), activePeak, MAX_ACTIVE, MAX_PER_PLAYER, passes, average, nanosWorst / 1000, nanosFirst / 1000, lcCalls, lcCalls == 0 ? 0.0 : lcNanos / (double) lcCalls / 1000.0, lcWorst / 1000, spawned, removed, spotMisses, MET.size());
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("uc_population").requires(source -> source.hasPermission(2))
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal("[UC] " + status(context.getSource().getServer())), false);
                return Command.SINGLE_SUCCESS;
            })));
    }

    /** Test seam: how many bodies the service thinks are out. */
    static int activeCount() { return ACTIVE.size(); }

    /** Test seam: registers a body spawned by a test. */
    static void adopt(TownspersonEntity body, long person) { ACTIVE.put(body.getUUID(), person); }
}
