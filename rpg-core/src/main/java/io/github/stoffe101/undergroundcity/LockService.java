package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The server's half of picking a lock (D-105): who may start, the {@link LockpickSession} that runs one tick per server tick on the player's last reported input, and what each ending
 * does to the world. The client only reports where the pick is and whether the button is down, and draws what it is told ({@link LockNetwork}); the sweet spots never leave this class.
 *
 * <p>Endings: <b>opened</b>, a door stays unlocked for {@link #BREACH_TICKS} of active time (for everybody; it is then shut again by {@link #sweep}), the building is furnished with its stash,
 * the pick wears now and then, heat rises and a {@link BuildingBreachEvent} is posted; <b>snapped</b>, the pick is lost, the lock is jammed for the profile's lockout and an alarm may sound
 * (more heat); <b>time ran out</b> or <b>walked away</b>, nothing is lost. One session per player. Nothing here runs unless somebody is picking, and the sweep is a scan of a short list once a second.
 */
public final class LockService {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** How long a picked door stays unlocked, in active ticks (90 seconds): time to slip in, and for a friend to follow. */
    public static final long BREACH_TICKS = 1800;
    /** The chance a pick wears out in a lock it opened (a snapped pick is always lost). */
    public static final double WEAR_ON_SUCCESS = 0.35;
    /** A picker who steps further than this from the lock (squared blocks) gives up. */
    static final double REACH_SQ = 36.0;
    static final int HEAT_OPENED = 1, HEAT_ALARM = 2;

    public enum Kind { DOOR, SAFE }

    /** What is being picked: a door of a city building, or a Household Safe. */
    public record Target(Kind kind, BlockPos pos, String profile, String city, String securityClass, String label, int picksNeeded) {}

    /** One player's lock in progress. */
    static final class Active {
        final UUID player;
        final Target target;
        final LockpickSession session;
        double position = 0.5;
        boolean pressing;
        int lastFeel = -1, lastPins = -1, lastStrain = -1, quiet;
        Active(UUID player, Target target, LockpickSession session) { this.player = player; this.target = target; this.session = session; }
    }

    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    private static int sweepClock;

    // ------------------------------------------------------------------------------------------------ starting

    /** The same lock gives the same pins every time it is picked: retrying is learning, not rerolling. */
    static long seedFor(ServerLevel level, BlockPos pos) {
        long z = pos.asLong() * 0x9E3779B97F4A7C15L + level.getSeed() * 0xBF58476D1CE4E5B9L + 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static boolean isPick(ItemStack stack) { return stack.is(UndergroundCity.item("lockpick")); }
    static int picks(Player player) { return player.getInventory().countItem(UndergroundCity.item("lockpick")); }

    /** Takes {@code count} lockpicks from the player's inventory. Returns how many were really taken. */
    static int consumePicks(Player player, int count) {
        int taken = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (taken >= count) break;
            if (isPick(stack)) { int take = Math.min(count - taken, stack.getCount()); stack.shrink(take); taken += take; }
        }
        return taken;
    }

    static long clock(MinecraftServer server) { return WorkOrderLedger.get(server).clock(); }

    /** True while the player has a lock in their hands; a session that has ended and not yet been swept away does not count. */
    public static boolean busy(UUID player) { Active active = ACTIVE.get(player); return active != null && active.session.active(); }
    public static int activeSessions() { return ACTIVE.size(); }

    /** Starts a pick. Returns the reason it cannot start, or {@code null} when the player now has a lock in their hands (the client has been told to show it). */
    public static String begin(ServerPlayer player, ServerLevel level, Target target) {
        if (busy(player.getUUID())) return "You are already working a lock.";
        if (picks(player) < target.picksNeeded()) {
            return target.picksNeeded() <= 1 ? "Locked. A lockpick would open it." : "Locked. Forcing this needs " + target.picksNeeded() + " lockpicks.";
        }
        if (WorkOrderLedger.get(level.getServer()).faulted()) return "Locks are unavailable until the server is restarted.";
        long now = clock(level.getServer());
        long jam = PropertyLocks.get(level.getServer()).jamRemaining(target.pos(), now);
        if (jam > 0) return "A snapped pick is jammed in this lock. Try again in about " + Math.max(1, (jam + 19) / 20) + " seconds.";
        LockpickSession.Profile profile = LockProfiles.INSTANCE.find(target.profile());
        Active active = new Active(player.getUUID(), target, new LockpickSession(profile, seedFor(level, target.pos())));
        ACTIVE.put(player.getUUID(), active);
        LockNetwork.open(player, target.label(), active.session);
        return null;
    }

    // ------------------------------------------------------------------------------------------------ input and ticks

    /** The client's report: where the pick is (0 to 1) and whether the button is held. Bounded and last-wins; a stranger's packet changes nothing. */
    public static void input(ServerPlayer player, double position, boolean pressing) {
        Active active = ACTIVE.get(player.getUUID());
        if (active == null || Double.isNaN(position)) return;
        active.position = Math.max(0.0, Math.min(1.0, position));
        active.pressing = pressing;
    }

    public static void abort(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        if (active != null) active.session.abort();
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (!ACTIVE.isEmpty()) tickSessions(server);
        if (++sweepClock >= 20) { sweepClock = 0; sweep(server); }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        Active active = ACTIVE.remove(event.getEntity().getUUID());
        if (active != null) active.session.abort();
    }

    private static void tickSessions(MinecraftServer server) {
        for (Iterator<Active> it = ACTIVE.values().iterator(); it.hasNext(); ) {
            Active active = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(active.player);
            if (player == null || !player.isAlive() || player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD
                || player.distanceToSqr(active.target.pos().getX() + 0.5, active.target.pos().getY() + 0.5, active.target.pos().getZ() + 0.5) > REACH_SQ) {
                it.remove();
                if (player != null) LockNetwork.close(player, "You stepped away from the lock.");
                continue;
            }
            tickOne(server, player, active);
            if (!active.session.active()) it.remove();
        }
    }

    /** One server tick of one session; the ending, if any, is applied here. Visible so the tests can drive a session without a connection. */
    static void tickOne(MinecraftServer server, ServerPlayer player, Active active) {
        LockpickSession session = active.session;
        session.tick(active.position, active.pressing);
        if (session.active()) {
            boolean changed = session.feel() != active.lastFeel || session.pinsSet() != active.lastPins || session.strainPercent() != active.lastStrain;
            if (changed || ++active.quiet >= 5) {
                active.quiet = 0;
                active.lastFeel = session.feel(); active.lastPins = session.pinsSet(); active.lastStrain = session.strainPercent();
                LockNetwork.progress(player, session);
            }
            return;
        }
        finish(server, player, active);
    }

    // ------------------------------------------------------------------------------------------------ endings

    private static void finish(MinecraftServer server, ServerPlayer player, Active active) {
        LockpickSession session = active.session;
        Target target = active.target;
        ServerLevel level = player.serverLevel();
        long now = clock(server);
        RandomSource random = level.getRandom();
        switch (session.state()) {
            case OPENED -> {
                LockNetwork.finished(player, LockpickSession.State.OPENED, "Click. It is open.");
                if (target.kind() == Kind.DOOR) openedDoor(server, level, player, target, now, random);
                else openedSafe(level, player, target, now, random);
            }
            case BROKEN -> {
                consumePicks(player, 1);
                PropertyLocks.get(server).jam(target.pos(), now + session.profile().lockoutTicks());
                boolean alarm = random.nextDouble() < session.profile().alarmChance();
                LockNetwork.finished(player, LockpickSession.State.BROKEN, alarm ? "The pick snapped, and something rang inside." : "The pick snapped in the lock.");
                level.playSound(null, target.pos(), SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 0.8f, 0.9f);
                if (alarm) {
                    level.playSound(null, target.pos(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 1.6f, 0.7f);
                    heat(server, player, target.city(), HEAT_ALARM);
                    Notice.send(player, Notice.Kind.HEAT, "An alarm rang", "Somebody may have heard it. Leave the area.");
                }
                if (target.kind() == Kind.DOOR) MinecraftForge.EVENT_BUS.post(new BuildingBreachEvent(player.getUUID(), target.pos(), target.city(), target.securityClass(), BuildingBreachEvent.Outcome.SNAPPED, now, alarm, player));
            }
            case TIMED_OUT -> LockNetwork.finished(player, LockpickSession.State.TIMED_OUT, "You ran out of time. The lock is as it was.");
            case ABORTED -> LockNetwork.finished(player, LockpickSession.State.ABORTED, "");
            default -> { }
        }
    }

    private static void heat(MinecraftServer server, ServerPlayer player, String city, int delta) {
        if (city == null || city.isEmpty()) return;
        try { WorkOrderLedger.get(server).addHeat(player.getUUID(), city, delta); }
        catch (RuntimeException error) { LOGGER.warn("Heat from a break-in could not be recorded: {}", error.toString()); }
    }

    private static void wear(ServerPlayer player, RandomSource random) {
        if (random.nextDouble() < WEAR_ON_SUCCESS && consumePicks(player, 1) == 1) Notice.send(player, Notice.Kind.INFO, "Worn pick", "The pick bent in that lock and is no use again.");
    }

    private static void openedDoor(MinecraftServer server, ServerLevel level, ServerPlayer player, Target target, long now, RandomSource random) {
        BlockState state = level.getBlockState(target.pos());
        if (state.getBlock() instanceof DoorBlock door) {
            PropertyLocks.get(server).breach(target.pos(), now + BREACH_TICKS);
            door.setOpen(player, level, state, target.pos(), true);
        }
        wear(player, random);
        heat(server, player, target.city(), HEAT_OPENED);
        // the building's stash is decided once, the first time anybody gets in
        CityBuildings.INSTANCE.at(level, target.pos()).ifPresent(building -> BuildingStash.furnish(level, building, target.pos(), now));
        MinecraftForge.EVENT_BUS.post(new BuildingBreachEvent(player.getUUID(), target.pos(), target.city(), target.securityClass(), BuildingBreachEvent.Outcome.OPENED, now, false, player));
        Notice.send(player, Notice.Kind.HEAT, "Lock picked", "The door is open for a moment. Whoever lives here will notice.");
    }

    private static void openedSafe(ServerLevel level, ServerPlayer player, Target target, long now, RandomSource random) {
        if (level.getBlockEntity(target.pos()) instanceof PropertyCacheBlockEntity safe) {
            safe.forcedOpenBy(level, player, now);
            wear(player, random);
            player.openMenu(safe);
        }
    }

    // ------------------------------------------------------------------------------------------------ re-locking

    /** Once a second: shuts every door whose unlocked time has run out (unless somebody is standing in it), and forgets what is finished. */
    static void sweep(MinecraftServer server) {
        PropertyLocks locks = PropertyLocks.get(server);
        long now = clock(server);
        if (locks.remembered() == 0) return;
        ServerLevel level = server.overworld();
        for (BlockPos pos : locks.dueToClose(now)) {
            if (!level.isLoaded(pos)) continue;   // shut when its chunk next loads and the sweep runs again
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock door)) { locks.closed(pos); continue; }
            if (!level.getEntitiesOfClass(Player.class, new AABB(pos).inflate(1.2, 1.0, 1.2)).isEmpty()) continue;
            if (state.getValue(DoorBlock.OPEN)) {
                door.setOpen(null, level, state, pos, false);
                level.playSound(null, pos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.7f, 1.0f);
            }
            locks.closed(pos);
        }
        locks.prune(now);
    }

    /** Test seam: forget every session. */
    static void resetForTest() { ACTIVE.clear(); }

    /** Test seam: the session of a player, if any. */
    static Active activeFor(UUID player) { return ACTIVE.get(player); }

    /** Test seam: a fake player has no connection, so nothing is sent to it. */
    static boolean hasConnection(ServerPlayer player) { return !(player instanceof FakePlayer); }
}
