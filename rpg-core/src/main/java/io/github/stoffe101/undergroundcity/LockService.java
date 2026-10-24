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

    /** One player's lock in progress. {@code proSet} when the thief works it with a Pro Pick Set rather than lockpicks; {@code raked} once a rake has been tried. */
    static final class Active {
        final UUID player;
        final Target target;
        final LockpickSession session;
        final boolean proSet;
        double position = 0.5;
        boolean pressing, raked, ticked;
        int lastFeel = -1, lastPins = -1, lastStrain = -1, quiet;
        long lastTickNanos;
        Active(UUID player, Target target, LockpickSession session) { this(player, target, session, false); }
        Active(UUID player, Target target, LockpickSession session, boolean proSet) { this.player = player; this.target = target; this.session = session; this.proSet = proSet; }
    }

    /** The Pro Pick Set (D-143): a professional set that a thief with Professional Tools works instead of lockpicks; it wears with use and is not lost when a pin snaps. */
    static final String PRO_SET = "pro_pick_set";
    static final int PRO_WEAR_OPENED = 1, PRO_WEAR_SNAPPED = 4;

    static boolean isProSet(ItemStack stack) { return stack.is(UndergroundCity.item(PRO_SET)); }

    /** The Pro Pick Set the player carries, or empty. */
    static ItemStack proSet(Player player) {
        if (isProSet(player.getMainHandItem())) return player.getMainHandItem();
        for (ItemStack stack : player.getInventory().items) if (isProSet(stack)) return stack;
        return ItemStack.EMPTY;
    }

    /** True when the player can work a Pro Pick Set: they carry one and have learned Professional Tools. */
    static boolean worksProSet(ServerPlayer player) { return !proSet(player).isEmpty() && Thievery.has(player, "thief.pro_tools"); }

    /** Why this player cannot even try this lock (a tool or a skill it needs), or null. */
    static String requirement(ServerPlayer player, LockpickSession.Profile profile, Target target) {
        if (!profile.requiresSkill().isEmpty() && !Thievery.has(player, profile.requiresSkill())) {
            SkillNode node = SkillCatalog.INSTANCE.find(profile.requiresSkill());
            return "A " + describe(profile) + ". It opens only to a thief with " + (node == null ? profile.requiresSkill() : node.title()) + ".";
        }
        if (profile.requiresTool().equals(PRO_SET) && !worksProSet(player)) {
            return proSet(player).isEmpty() ? "A " + describe(profile) + ". Lockpicks are no use on it: it needs a Pro Pick Set." : "A " + describe(profile) + ". You carry a Pro Pick Set but have not learned Professional Tools.";
        }
        return null;
    }

    /** A burglar's words for a kind of lock. */
    static String describe(LockpickSession.Profile profile) {
        return switch (profile.id()) {
            case "latch" -> "simple latch"; case "deadbolt" -> "deadbolt"; case "commercial" -> "commercial lock"; case "secured" -> "secured lock with a spool pin";
            case "high_security" -> "high-security lock"; case "safe" -> "household safe"; case "wall_safe" -> "wall safe"; case "vault_safe" -> "vault safe";
            default -> "lock";
        };
    }

    /** The thief's hands on this lock: Steady Hands and Feel for the Pins, from the player's skills. */
    static LockpickSession.Hands hands(ServerPlayer player) {
        return LockpickSession.Hands.of(Thievery.has(player, "thief.steady_hands"), Thievery.has(player, "thief.feel_for_pins"));
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
        LockpickSession.Profile profile = LockProfiles.INSTANCE.find(target.profile());
        String requirement = requirement(player, profile, target);
        if (requirement != null) return requirement;
        // a Pro Pick Set replaces lockpicks for a thief who can work it; a safecracker needs one pick fewer on a safe
        boolean pro = worksProSet(player);
        int needed = target.kind() == Kind.SAFE && Thievery.has(player, "thief.safecracking") ? Math.max(1, target.picksNeeded() - 1) : target.picksNeeded();
        if (!pro && picks(player) < needed) {
            return needed <= 1 ? "Locked. A lockpick would open it." : "Locked. Forcing this needs " + needed + " lockpicks.";
        }
        if (WorkOrderLedger.get(level.getServer()).faulted()) return "Locks are unavailable until the server is restarted.";
        long now = clock(level.getServer());
        long jam = PropertyLocks.get(level.getServer()).jamRemaining(target.pos(), now);
        if (jam > 0) return "A snapped pick is jammed in this lock. Try again in about " + Math.max(1, (jam + 19) / 20) + " seconds.";
        Active active = new Active(player.getUUID(), target, new LockpickSession(profile, seedFor(level, target.pos()), hands(player)), pro);
        ACTIVE.put(player.getUUID(), active);
        LockNetwork.open(player, target.label(), active.session, profile.rakeChance() > 0 && Thievery.has(player, "thief.raking"));
        return null;
    }

    /** Odds and noise of a rake (D-143): one try per lock, only on a lock that can be raked, only for a thief who has learned Raking. */
    static final double RAKE_FAIL_JOLT = 0.3;

    /**
     * The player rakes the lock in their hands: one quick, loud try. It works at the lock's rake chance (a latch 70 percent, a deadbolt 35); otherwise the pick takes a jolt. Either way it is noisy:
     * a raked door is never a quiet entry, and a failed rake is a noise people nearby may notice. The roll is the server's.
     */
    public static void rake(ServerPlayer player) {
        Active active = ACTIVE.get(player.getUUID());
        if (active == null || !active.session.active() || active.raked) return;
        LockpickSession.Profile profile = active.session.profile();
        if (profile.rakeChance() <= 0 || !Thievery.has(player, "thief.raking")) return;
        active.raked = true;
        ServerLevel level = player.serverLevel();
        level.playSound(null, active.target.pos(), SoundEvents.CHAIN_HIT, SoundSource.PLAYERS, 1.0f, 1.4f);
        if (level.getRandom().nextDouble() < profile.rakeChance()) {
            active.session.rakedOpen();
            active.pressing = false;
            finish(level.getServer(), player, active);
            ACTIVE.remove(player.getUUID());
            return;
        }
        active.session.jolt(RAKE_FAIL_JOLT);
        if (active.target.kind() == Kind.DOOR) MinecraftForge.EVENT_BUS.post(new BuildingBreachEvent(player.getUUID(), active.target.pos(), active.target.city(), active.target.securityClass(), BuildingBreachEvent.Outcome.NOISE, clock(level.getServer()), false, player));
        if (!active.session.active()) { finish(level.getServer(), player, active); ACTIVE.remove(player.getUUID()); }
        else LockNetwork.progress(player, active.session);
    }

    // ------------------------------------------------------------------------------------------------ input and ticks

    /** The client's report: where the pick is (0 to 1) and whether the button is held. Bounded and last-wins; a stranger's packet changes nothing. */
    public static void input(ServerPlayer player, double position, boolean pressing) {
        Active active = ACTIVE.get(player.getUUID());
        if (active == null || Double.isNaN(position)) return;
        active.position = Math.max(0.0, Math.min(1.0, position));
        active.pressing = pressing;
    }

    /** Puts the lock away with nothing lost, as letting go does, and tells the client why (an officer on the beat saw the thief at it, D-155). */
    static void interrupt(ServerPlayer player, String note) {
        Active active = ACTIVE.remove(player.getUUID());
        if (active == null) return;
        active.session.abort();
        LockNetwork.close(player, note);
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

    /**
     * The least time by the clock between two ticks of one lock. A server that stalled catches up by running its ticks back to back: 45 of them in a moment, after a 2.3-second stall in the advanced
     * burglary run, under a press the thief had no time to see the end of (the pin set, the next pin's spot elsewhere) and that strained the pick to snapping. A lock under the hand runs by the clock instead.
     */
    static final long MIN_TICK_NANOS = 40_000_000L;

    /** True when this lock last ticked long enough ago by the clock to tick now ({@code now} from {@link System#nanoTime}); records the tick. */
    static boolean due(Active active, long now) {
        if (active.ticked && now - active.lastTickNanos < MIN_TICK_NANOS) return false;
        active.ticked = true; active.lastTickNanos = now;
        return true;
    }

    private static void tickSessions(MinecraftServer server) {
        long now = System.nanoTime();
        for (Iterator<Active> it = ACTIVE.values().iterator(); it.hasNext(); ) {
            Active active = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(active.player);
            if (player == null || !player.isAlive() || player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD
                || player.distanceToSqr(active.target.pos().getX() + 0.5, active.target.pos().getY() + 0.5, active.target.pos().getZ() + 0.5) > REACH_SQ) {
                it.remove();
                if (player != null) LockNetwork.close(player, "You stepped away from the lock.");
                continue;
            }
            if (!due(active, now)) continue;
            tickOne(server, player, active);
            if (!active.session.active()) it.remove();
        }
    }

    /** One server tick of one session; the ending, if any, is applied here. Visible so the tests can drive a session without a connection. */
    static void tickOne(MinecraftServer server, ServerPlayer player, Active active) {
        LockpickSession session = active.session;
        session.tick(active.position, active.pressing);
        if (session.active()) {
            boolean changed = session.feel() != active.lastFeel || session.pinsSet() != active.lastPins || session.strainPercent() != active.lastStrain || session.justTurned();
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
                LockNetwork.finished(player, LockpickSession.State.OPENED, active.raked ? "Raked open. Loud, but it is open." : "Click. It is open.");
                if (target.kind() == Kind.DOOR) openedDoor(server, level, player, target, now, random, !active.raked && session.peakStrainPercent() < quietStrain(player), active.proSet);
                else openedSafe(level, player, target, now, random, active.proSet);
            }
            case BROKEN -> {
                if (active.proSet) wearProSet(player, PRO_WEAR_SNAPPED);
                else consumePicks(player, 1);
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

    private static void wear(ServerPlayer player, RandomSource random, boolean proSet) {
        if (proSet) { wearProSet(player, PRO_WEAR_OPENED); return; }
        if (random.nextDouble() < WEAR_ON_SUCCESS && consumePicks(player, 1) == 1) Notice.send(player, Notice.Kind.INFO, "Worn pick", "The pick bent in that lock and is no use again.");
    }

    /** The Pro Pick Set takes wear; when it is worn through it is gone. */
    static void wearProSet(ServerPlayer player, int amount) {
        ItemStack set = proSet(player);
        if (set.isEmpty()) return;
        set.hurtAndBreak(amount, player, p -> Notice.send(player, Notice.Kind.INFO, "Pick set worn out", "Your Pro Pick Set is finished. A new one is a serious expense."));
    }

    /** A door picked without the pick ever coming under more than this percent of its strain limit was picked quietly (D-122): an alarm built into the building does not hear it. */
    static final int QUIET_STRAIN_PERCENT = 40;

    /** A Ghost (D-143) picks quietly with a little more strain to spare. */
    static int quietStrain(ServerPlayer player) { return Thievery.has(player, "thief.ghost") ? 55 : QUIET_STRAIN_PERCENT; }

    private static void openedDoor(MinecraftServer server, ServerLevel level, ServerPlayer player, Target target, long now, RandomSource random, boolean quiet, boolean proSet) {
        BlockState state = level.getBlockState(target.pos());
        if (state.getBlock() instanceof DoorBlock door) {
            PropertyLocks.get(server).breach(target.pos(), now + BREACH_TICKS);
            door.setOpen(player, level, state, target.pos(), true);
        }
        wear(player, random, proSet);
        heat(server, player, target.city(), HEAT_OPENED);
        // the building's stash is decided once, the first time anybody gets in; the first time this thief gets in is worth Thief XP (D-143)
        CityBuildings.INSTANCE.at(level, target.pos()).ifPresent(building -> { BuildingStash.furnish(level, building, target.pos(), now); Thievery.entered(player, building); });
        MinecraftForge.EVENT_BUS.post(new BuildingBreachEvent(player.getUUID(), target.pos(), target.city(), target.securityClass(), BuildingBreachEvent.Outcome.OPENED, now, false, player).quiet(quiet));
        Notice.send(player, Notice.Kind.HEAT, quiet ? "Lock picked cleanly" : "Lock picked", quiet ? "The door is open for a moment. A clean pick is a quiet one." : "The door is open for a moment. Whoever lives here will notice.");
    }

    private static void openedSafe(ServerLevel level, ServerPlayer player, Target target, long now, RandomSource random, boolean proSet) {
        if (level.getBlockEntity(target.pos()) instanceof PropertyCacheBlockEntity safe) {
            safe.forcedOpenBy(level, player, now);
            wear(player, random, proSet);
            Thievery.crackedSafe(player, target.pos(), safe.tier());
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
