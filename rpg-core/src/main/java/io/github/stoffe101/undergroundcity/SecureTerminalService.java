package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The Technician's interactive job (D-050). Using a secured terminal needs the Bypass Tooling skill and consumes
 * one Bypass Kit; the server then picks a hidden five-step signal sequence, sends it to the client to flash, and
 * accepts exactly one answer, only after the sequence has finished and before the deadline. A correct answer
 * recovers one Encrypted Ledger (finite stock, slow refill); a wrong or late answer trips the alarm: the terminal
 * locks for three active minutes and the city's heat rises by one. There is no button that pays out.
 */
public final class SecureTerminalService {
    public static final int LENGTH = 5, SYMBOLS = 4;
    /** Ticks each symbol is shown (including its gap), and how long the player has once the sequence ends. */
    public static final int SHOW_TICKS_PER_SYMBOL = 14, INPUT_TICKS = 20 * 12;

    /**
     * Two clocks, on purpose: {@code now} is the server tick for the short in-memory session (answer window), while
     * the terminal's stock and lockout are persisted against the ledger's active-play clock, which survives restarts.
     */
    record Session(UUID player, BlockPos pos, byte[] sequence, long start, long showUntil, long deadline, String city) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    SecureTerminalService() {}

    /** Hidden sequence for an attempt: a pure function of the player, the terminal and its attempt counter. */
    static byte[] sequence(UUID player, BlockPos pos, int attempt) {
        byte[] result = new byte[LENGTH];
        long seed = LawMath.mix(player.getMostSignificantBits(), player.getLeastSignificantBits(), pos.asLong(), attempt);
        for (int i = 0; i < LENGTH; i++) result[i] = (byte) (LawMath.mix(seed, i) >>> 33 & 3);
        return result;
    }

    /** Starts an attempt. Throws IllegalStateException with the reason when the terminal refuses. */
    public static void begin(ServerPlayer player, SecureTerminalBlockEntity terminal, long now) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID())
            .orElseThrow(() -> new IllegalStateException("Register as a citizen first."));
        if (!SkillService.has(citizen, "technician.bypass_tooling")) throw new IllegalStateException("Locked. Only a Technician with Bypass Tooling can work this terminal.");
        if (SESSIONS.containsKey(player.getUUID())) throw new IllegalStateException("You are already working a terminal.");
        if (SESSIONS.values().stream().anyMatch(other -> other.pos().equals(terminal.getBlockPos()))) throw new IllegalStateException("Someone is already working this terminal.");
        long clock = WorkOrderLedger.get(player.server).clock();
        if (terminal.lockedAt(clock)) throw new IllegalStateException("The terminal is locked down after an alarm. Come back in a few minutes.");
        if (terminal.ledgers(clock) < 1) throw new IllegalStateException("Nothing left to recover: the drive refills slowly while the server is active.");
        if (WorkOrderLedger.get(player.server).faulted()) throw new IllegalStateException("Storage is unavailable until the server restarts.");
        Item kit = kit();
        Inventory inventory = player.getInventory();
        if (inventory.countItem(kit) < 1) throw new IllegalStateException("You need a Bypass Kit to work a secured terminal.");
        if (!roomFor(inventory)) throw new IllegalStateException("Free an inventory slot first: you must be able to carry what you recover.");
        for (ItemStack stack : inventory.items) {
            if (stack.is(kit)) { stack.shrink(1); break; } // the kit is spent the moment the attempt starts
        }
        BlockPos pos = terminal.getBlockPos();
        byte[] sequence = sequence(player.getUUID(), pos, terminal.nextAttempt());
        long showUntil = now + (long) LENGTH * SHOW_TICKS_PER_SYMBOL;
        String city = CityRegions.INSTANCE.cityAt(player.level(), pos).orElse("");
        SESSIONS.put(player.getUUID(), new Session(player.getUUID(), pos.immutable(), sequence, now, showUntil, showUntil + INPUT_TICKS, city));
        if (!(player instanceof FakePlayer)) TerminalNetwork.openChallenge(player, pos, sequence);
        player.sendSystemMessage(Component.literal("Signal trace started. Watch the sequence, then repeat it before the lock resets."));
    }

    private static Item kit() { return UndergroundCity.item("bypass_kit"); }

    private static boolean roomFor(Inventory inventory) {
        Inventory copy = new Inventory(inventory.player);
        copy.load(inventory.save(new ListTag()));
        return copy.add(new ItemStack(UndergroundCity.item("data_ledger")));
    }

    /** The client's single answer. Anything but the exact sequence, inside the window, trips the alarm. */
    public static void answer(ServerPlayer player, BlockPos pos, byte[] answer, long now) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.pos().equals(pos)) return; // no session, or another terminal: ignore, nothing was risked
        SESSIONS.remove(player.getUUID());
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 144) { alarm(player, session, now, "You walked away from the terminal."); return; }
        boolean inTime = now >= session.showUntil() && now <= session.deadline();
        if (inTime && Arrays.equals(session.sequence(), answer) && succeed(player, session, now)) return;
        alarm(player, session, now, answer.length == 0 ? "You abandoned the trace."
            : inTime ? "Wrong sequence." : now < session.showUntil() ? "Answered before the trace finished." : "Too slow.");
    }

    private static boolean succeed(ServerPlayer player, Session session, long now) {
        if (!(player.level().getBlockEntity(session.pos()) instanceof SecureTerminalBlockEntity terminal)) return false;
        long clock = WorkOrderLedger.get(player.server).clock();
        if (terminal.ledgers(clock) < 1) return false; // drained while the trace ran: nothing to recover
        terminal.take(clock);
        player.getInventory().add(new ItemStack(UndergroundCity.item("data_ledger")));
        player.sendSystemMessage(Component.literal("Trace complete. You recovered an Encrypted Ledger. Take it to a buyer, and mind the checkpoints."));
        return true;
    }

    private static void alarm(ServerPlayer player, Session session, long now, String why) {
        if (player.level().getBlockEntity(session.pos()) instanceof SecureTerminalBlockEntity terminal) terminal.lock(WorkOrderLedger.get(player.server).clock());
        String where = session.city().isEmpty() ? "" : " in " + TerminalScreenText.capitalize(session.city());
        if (!session.city().isEmpty()) WorkOrderLedger.get(player.server).addHeat(player.getUUID(), session.city(), 1);
        player.sendSystemMessage(Component.literal(why + " The alarm trips" + where + ": the terminal locks down and the guard will be watching for you."));
    }

    /** Server tick: an unanswered session expires with the same consequences as a wrong answer. */
    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty() || event.getServer().getTickCount() % 10 != 0) return;
        long now = event.getServer().getTickCount();
        for (Iterator<Session> it = SESSIONS.values().iterator(); it.hasNext(); ) {
            Session session = it.next();
            if (now <= session.deadline()) continue;
            it.remove();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(session.player());
            if (player != null && !(player instanceof FakePlayer)) alarm(player, session, now, "Too slow.");
        }
    }

    @SubscribeEvent
    public void loggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) { SESSIONS.remove(event.getEntity().getUUID()); }

    static boolean hasSession(UUID player) { return SESSIONS.containsKey(player); }
    static void clearForTest() { SESSIONS.clear(); }
}
