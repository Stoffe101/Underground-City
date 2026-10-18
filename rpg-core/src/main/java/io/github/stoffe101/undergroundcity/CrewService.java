package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.Pending;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything a Crew does that touches a player: authorisation, the wallet side of money transfers, recovery after a crash, and the
 * notifications. The bookkeeping is {@link CrewLedger}; the rules are {@link CrewRules}. Every method runs on the server thread and
 * takes only server-known facts (a client may name a target player or an amount, never a role, a balance or a permission).
 *
 * <h2>The money protocol (wallet first, ledger second; see docs/technical/crew-economy.md)</h2>
 * <ol>
 * <li>Validate everything and read the wallet.</li>
 * <li>Write the prepare record ({@code begin*}): fsynced. For a withdrawal or a claim this also takes the credits out of the bank as a hold.</li>
 * <li>Set the wallet to the recorded absolute value and save the player file, reading it back.</li>
 * <li>Settle ({@code settle}): the ledger side (a deposit reaches the bank, a lease becomes ownership) and the removal of the prepare record
 *     are one atomic file write.</li>
 * </ol>
 * After a crash the wallet is compared with the recorded {@code before} and {@code after}: equal to {@code after} settles, equal to
 * {@code before} cancels (a withdrawal's hold returns to the bank), anything else is left for an operator and blocks only that player.
 * Recovery therefore only ever <em>reads</em> a wallet, never writes an offline one.
 */
public final class CrewService {
    private static final Logger LOG = LogUtils.getLogger();

    /** The wallet as the Crew sees it. Production uses Q Shop; tests use {@link MemoryWallet} to cover every crash point without the provider. */
    interface Wallet {
        boolean ready(MinecraftServer server);
        double balance(ServerPlayer player);
        void set(ServerPlayer player, double value);
        /** The balance of a player who may be offline, or null when it cannot be read. */
        Double offline(MinecraftServer server, UUID player);
    }

    static final class QShopWallet implements Wallet {
        @Override public boolean ready(MinecraftServer server) { return QShopEconomy.available() && QShopEconomy.configured(server); }
        @Override public double balance(ServerPlayer player) { return QShopEconomy.balance(player); }
        @Override public void set(ServerPlayer player, double value) { QShopEconomy.applyBalance(player, value); }
        @Override public Double offline(MinecraftServer server, UUID player) { return QShopEconomy.balanceOffline(server, player); }
    }

    /** Test double: balances held in a map, optionally mirrored by the live provider is not needed. */
    static final class MemoryWallet implements Wallet {
        final Map<UUID, Double> balances = new HashMap<>();
        @Override public boolean ready(MinecraftServer server) { return true; }
        @Override public double balance(ServerPlayer player) { return balances.getOrDefault(player.getUUID(), 0d); }
        @Override public void set(ServerPlayer player, double value) { balances.put(player.getUUID(), value); }
        @Override public Double offline(MinecraftServer server, UUID player) { return balances.get(player); }
    }

    /** Stands in for the process dying at a named point of a transfer. An {@link Error} on purpose: nothing in the service catches it, so the state is exactly what a crash leaves. */
    static final class SimulatedCrash extends Error {
        SimulatedCrash(String stage) { super("simulated crash at " + stage); }
    }

    static volatile Wallet wallet = new QShopWallet();
    /** Tests set this to {@code after_begin}, {@code after_wallet} or {@code after_save}; production never does. */
    static volatile String crashAt = "";
    private static final Set<MinecraftServer> BROKEN = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public CrewService() {}

    private static void crash(String stage) { if (stage.equals(crashAt)) throw new SimulatedCrash(stage); }

    // ------------------------------------------------------------------------------------------------------------ access

    /** The ledger, or null when its file could not be read (solo play and every other system carry on; the Crew screen says why). */
    public static CrewLedger ledger(MinecraftServer server) {
        if (BROKEN.contains(server)) return null;
        try {
            return CrewLedger.get(server);
        } catch (RuntimeException error) {
            BROKEN.add(server);
            LOG.error("The Crew ledger could not be loaded; Crews are unavailable until data/underground_city_crews.dat is restored", error);
            return null;
        }
    }

    static CrewLedger need(MinecraftServer server) {
        CrewLedger ledger = ledger(server);
        if (ledger == null) throw new IllegalStateException("Crew storage is unavailable. Ask the host to restore data/underground_city_crews.dat from a backup.");
        return ledger;
    }

    /** True when the Crew bank can be used right now (provider installed and configured, ledger readable and healthy). */
    public static boolean moneyReady(MinecraftServer server) {
        CrewLedger ledger = ledger(server);
        return ledger != null && !ledger.faulted() && wallet.ready(server);
    }

    private static CitizenState.Citizen citizen(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).orElseThrow(() -> new IllegalStateException("Register as a citizen first."));
    }

    private static void requireMoney(ServerPlayer player) {
        need(player.server);
        if (!wallet.ready(player.server)) throw new IllegalStateException("The Crew bank needs Q Shop 1.8.4 with the credits currency. Ask the host.");
        if (WorkOrderLedger.get(player.server).faulted()) throw new IllegalStateException("Work-order storage failed; restart the server.");
        if (WorkOrderLedger.get(player.server).player(player.getUUID()).contains("pending")) throw new IllegalStateException("Finish the work-order recovery first (open a terminal).");
    }

    // ------------------------------------------------------------------------------------------------------------ membership

    public static Crew create(ServerPlayer player, String name) {
        citizen(player);
        Crew crew = need(player.server).create(player.getUUID(), player.getGameProfile().getName(), name);
        Notice.send(player, Notice.Kind.STEP, "Crew founded", crew.name + " is yours to lead. Invite a friend from the Crew screen.");
        return crew;
    }

    public static void invite(ServerPlayer actor, String targetName) {
        citizen(actor);
        ServerPlayer target = actor.server.getPlayerList().getPlayerByName(targetName);
        if (target == null || target instanceof FakePlayer) throw new IllegalStateException(targetName + " is not online.");
        if (CitizenState.get(actor.server).find(target.getUUID()).isEmpty()) throw new IllegalStateException(targetName + " has not registered as a citizen yet.");
        CrewLedger.Invite invite = need(actor.server).invite(actor.getUUID(), target.getUUID(), target.getGameProfile().getName(), actor.getGameProfile().getName());
        Notice.send(target, Notice.Kind.INFO, "Crew invitation", actor.getGameProfile().getName() + " invites you to " + invite.crewName() + ". Press K to answer.");
        target.sendSystemMessage(Component.literal(actor.getGameProfile().getName() + " invited you to the Crew \"" + invite.crewName() + "\". Open the Crew screen (K) to accept or decline."));
    }

    public static Crew accept(ServerPlayer player, UUID invite) {
        citizen(player);
        Crew crew = need(player.server).accept(invite, player.getUUID(), player.getGameProfile().getName());
        Notice.send(player, Notice.Kind.STEP, "Welcome to " + crew.name, "You keep your own career, wallet and skills.");
        broadcast(player.server, crew, player.getUUID(), Notice.Kind.INFO, "New Crew member", player.getGameProfile().getName() + " joined " + crew.name + ".");
        if (crew.mode == Mode.SHARED && !crew.consent.contains(player.getUUID())) {
            player.sendSystemMessage(Component.literal(crew.name + " proposes fully shared finances. Your income stays yours until you agree on the Money tab."));
        }
        return crew;
    }

    public static void decline(ServerPlayer player, UUID invite) { need(player.server).decline(invite, player.getUUID()); }
    public static void withdrawInvite(ServerPlayer player, UUID invite) { need(player.server).withdrawInvite(player.getUUID(), invite); }

    public static void leave(ServerPlayer player) {
        CrewLedger ledger = need(player.server);
        Crew crew = ledger.crewOf(player.getUUID());
        ledger.leave(player.getUUID(), player.getGameProfile().getName());
        if (crew != null) broadcast(player.server, crew, player.getUUID(), Notice.Kind.INFO, "Crew member left", player.getGameProfile().getName() + " left " + crew.name + ".");
        Notice.send(player, Notice.Kind.INFO, "You left the Crew", "Your wallet, career and personal homes are untouched.");
    }

    public static void kick(ServerPlayer actor, UUID target) {
        CrewLedger ledger = need(actor.server);
        Crew crew = ledger.crewOf(actor.getUUID());
        ledger.kick(actor.getUUID(), actor.getGameProfile().getName(), target);
        ServerPlayer gone = actor.server.getPlayerList().getPlayer(target);
        if (gone != null) Notice.send(gone, Notice.Kind.WARN, "Removed from the Crew", "You were removed from " + (crew == null ? "your Crew" : crew.name) + ". Your own wallet, career and homes are untouched.");
        if (crew != null) broadcast(actor.server, crew, actor.getUUID(), Notice.Kind.INFO, "Crew member removed", "A member was removed from " + crew.name + ".");
    }

    public static void setRole(ServerPlayer actor, UUID target, Role role) { need(actor.server).setRole(actor.getUUID(), actor.getGameProfile().getName(), target, role); }

    public static void transfer(ServerPlayer actor, UUID target) {
        CrewLedger ledger = need(actor.server);
        Crew crew = ledger.crewOf(actor.getUUID());
        ledger.transfer(actor.getUUID(), actor.getGameProfile().getName(), target);
        if (crew != null) broadcast(actor.server, crew, null, Notice.Kind.INFO, "New Crew leader", crew.members.get(target).name + " now leads " + crew.name + ".");
    }

    public static void claimLeadership(ServerPlayer player) {
        CrewLedger ledger = need(player.server);
        ledger.claimLeadership(player.getUUID(), player.getGameProfile().getName());
        Crew crew = ledger.crewOf(player.getUUID());
        if (crew != null) broadcast(player.server, crew, null, Notice.Kind.INFO, "New Crew leader", player.getGameProfile().getName() + " now leads " + crew.name + ".");
    }

    public static void rename(ServerPlayer actor, String name) { need(actor.server).rename(actor.getUUID(), actor.getGameProfile().getName(), name); }
    public static void setPermission(ServerPlayer actor, Role role, Perm perm, boolean allowed) { need(actor.server).setPermission(actor.getUUID(), actor.getGameProfile().getName(), role, perm, allowed); }

    public static void setMode(ServerPlayer actor, Mode mode) {
        CrewLedger ledger = need(actor.server);
        ledger.setMode(actor.getUUID(), actor.getGameProfile().getName(), mode);
        Crew crew = ledger.crewOf(actor.getUUID());
        if (crew != null && mode == Mode.SHARED) {
            broadcast(actor.server, crew, actor.getUUID(), Notice.Kind.WARN, "Shared finances proposed",
                "Your income stays yours until you agree on the Money tab. No credits move.");
        }
    }

    public static void setConsent(ServerPlayer player, boolean agree) { need(player.server).setConsent(player.getUUID(), player.getGameProfile().getName(), agree); }

    public static long payOut(ServerPlayer actor) {
        long total = need(actor.server).payOut(actor.getUUID(), actor.getGameProfile().getName());
        claimOwedQuietly(actor);
        return total;
    }

    /** Disbands the Crew. The caller has already checked the typed confirmation. Returns the credits paid out (owed to the members). */
    public static long disband(ServerPlayer actor) {
        CrewLedger ledger = need(actor.server);
        Crew crew = ledger.crewOf(actor.getUUID());
        if (crew == null) throw new IllegalStateException("You are not in a Crew.");
        List<UUID> members = List.copyOf(crew.members.keySet());
        String name = crew.name;
        long total = ledger.disband(actor.getUUID(), actor.getGameProfile().getName());
        for (UUID id : members) {
            ServerPlayer online = actor.server.getPlayerList().getPlayer(id);
            if (online == null) continue;
            Notice.send(online, Notice.Kind.WARN, "Crew disbanded", name + " no longer exists." + (total > 0 ? " Your share of the bank is waiting for you." : ""));
            claimOwedQuietly(online);
        }
        return total;
    }

    private static void broadcast(MinecraftServer server, Crew crew, UUID except, Notice.Kind kind, String title, String body) {
        for (UUID id : crew.members.keySet()) {
            if (id.equals(except)) continue;
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) Notice.send(online, kind, title, body);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ money

    /** Runs a transfer that needs the wallet. {@code begin} receives the wallet balance and writes the prepare record. */
    private static void transact(ServerPlayer player, java.util.function.DoubleFunction<Pending> begin) {
        requireMoney(player);
        CrewLedger ledger = need(player.server);
        double before = wallet.balance(player);
        Pending pending = begin.apply(before);
        crash("after_begin");
        try {
            wallet.set(player, pending.after());
            crash("after_wallet");
            PlayerStore.saveVerified(player);
            crash("after_save");
        } catch (SimulatedCrash crash) {
            throw crash;
        } catch (RuntimeException error) {
            rollback(player, ledger, pending, error);
            throw new IllegalStateException("The transfer could not be completed and was cancelled. Nothing was lost.");
        }
        ledger.settle(player.getUUID());
    }

    private static void rollback(ServerPlayer player, CrewLedger ledger, Pending pending, RuntimeException cause) {
        LOG.error("Crew transfer {} failed after its prepare record; rolling back", pending.tx(), cause);
        try {
            wallet.set(player, pending.before());
            PlayerStore.saveVerified(player);
            ledger.cancel(player.getUUID());
        } catch (RuntimeException second) {
            LOG.error("Rollback of Crew transfer {} failed; recovery will decide from the wallet", pending.tx(), second);
            player.connection.disconnect(Component.literal("A Crew transfer was interrupted. Reconnect so it can be recovered."));
        }
    }

    public static void deposit(ServerPlayer player, UUID tx, long amount) {
        citizen(player);
        transact(player, balance -> need(player.server).beginDeposit(tx, player.getUUID(), amount, balance));
        Notice.send(player, Notice.Kind.INFO, "Deposited", amount + " credits are in the Crew bank.");
    }

    public static void withdraw(ServerPlayer player, UUID tx, long amount) {
        citizen(player);
        transact(player, balance -> need(player.server).beginWithdraw(tx, player.getUUID(), amount, balance));
        Notice.send(player, Notice.Kind.INFO, "Withdrawn", amount + " credits are in your wallet.");
    }

    /** Moves any payout owed to the player (after a disband or a pay-out) into their wallet. */
    public static void claimOwed(ServerPlayer player, UUID tx) {
        citizen(player);
        long amount = need(player.server).owedTo(player.getUUID());
        transact(player, balance -> need(player.server).beginClaim(tx, player.getUUID(), balance));
        Notice.send(player, Notice.Kind.INFO, "Payout received", amount + " credits from your Crew are in your wallet.");
    }

    private static void claimOwedQuietly(ServerPlayer player) {
        try {
            if (moneyReady(player.server) && need(player.server).owedTo(player.getUUID()) > 0) claimOwed(player, UUID.randomUUID());
        } catch (RuntimeException error) {
            LOG.warn("Could not pay out to {} now: {}", player.getGameProfile().getName(), error.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------------------ fully shared finances

    /**
     * Fully shared finances, income side: when the Crew's agreement is in force (every member has consented) the pay of a work order goes into the
     * bank straight after it reached the wallet. It is an ordinary deposit with a transaction id taken from the order, so a replay cannot sweep twice,
     * and a failure of any kind leaves the credits in the player's own wallet. Returns the credits swept.
     */
    public static long sweepIncome(ServerPlayer player, long credits, UUID order) {
        try {
            CrewLedger ledger = ledger(player.server);
            Crew crew = ledger == null ? null : ledger.crewOf(player.getUUID());
            if (crew == null || crew.effectiveMode() != Mode.SHARED || credits < 1 || !moneyReady(player.server)) return 0;
            UUID tx = new UUID(order.getMostSignificantBits() ^ 0x5348415245444CL, order.getLeastSignificantBits());
            transact(player, balance -> ledger.beginDeposit(tx, player.getUUID(), credits, balance));
            Notice.send(player, Notice.Kind.INFO, "Shared finances", credits + " credits went into the Crew bank.");
            return credits;
        } catch (CrewService.SimulatedCrash crash) {
            throw crash;
        } catch (RuntimeException error) {
            LOG.warn("Shared income of {} stayed in their wallet: {}", player.getGameProfile().getName(), error.getMessage());
            return 0;
        }
    }

    /**
     * Fully shared finances, spending side: before a terminal purchase the bank pays first, by moving up to the price into the buyer's wallet (an
     * ordinary withdrawal that needs no WITHDRAW right because every member agreed). The purchase then runs exactly as it always does. Returns the
     * credits moved; zero when the agreement is not in force or the bank is empty.
     */
    public static long topUpForPurchase(ServerPlayer player, long cost) {
        try {
            CrewLedger ledger = ledger(player.server);
            Crew crew = ledger == null ? null : ledger.crewOf(player.getUUID());
            if (crew == null || crew.effectiveMode() != Mode.SHARED || cost < 1 || crew.bank < 1 || !moneyReady(player.server)) return 0;
            long pull = Math.min(crew.bank, cost);
            transact(player, balance -> ledger.beginWithdraw(UUID.randomUUID(), player.getUUID(), pull, balance, true));
            return pull;
        } catch (CrewService.SimulatedCrash crash) {
            throw crash;
        } catch (RuntimeException error) {
            LOG.warn("The Crew bank could not pay for {}'s purchase: {}", player.getGameProfile().getName(), error.getMessage());
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------------------------ homes

    /** Leases a registered home near the player, for themselves or their Crew, from the bank or from their own wallet. */
    public static void lease(ServerPlayer player, UUID tx, String residenceId, boolean forCrew, boolean fromBank) {
        citizen(player);
        CrewLedger ledger = need(player.server);
        Residence r = ledger.residence(residenceId);
        if (r == null) throw new IllegalStateException("That home does not exist.");
        if (!r.dimension.equals(player.level().dimension().location().toString()) || r.distanceSqr(player.blockPosition()) > 64.0 * 64.0) {
            throw new IllegalStateException("Go to the home first (within 64 blocks) to lease it.");
        }
        if (fromBank) {
            requireMoney(player);
            if (!forCrew) throw new IllegalStateException("The Crew bank pays for Crew homes only.");
            int price = ledger.leaseForCrewFromBank(tx, player.getUUID(), player.getGameProfile().getName(), residenceId);
            Notice.send(player, Notice.Kind.STEP, "Home leased", r.title + " now belongs to the Crew (" + price + " credits from the bank).");
        } else {
            transact(player, balance -> ledger.beginLeaseFromWallet(tx, player.getUUID(), forCrew, residenceId, balance));
            Notice.send(player, Notice.Kind.STEP, "Home leased", r.title + " is " + (forCrew ? "the Crew's now." : "yours now."));
        }
        Crew crew = ledger.crewOf(player.getUUID());
        if (forCrew && crew != null) broadcast(player.server, crew, player.getUUID(), Notice.Kind.INFO, "New Crew home", r.title + " is now a Crew home.");
    }

    /** Marks the container the player is looking at as theirs alone; the home guard then keeps everyone else out of it. */
    public static void claimContainer(ServerPlayer player, BlockPos pos) {
        citizen(player);
        CrewLedger ledger = need(player.server);
        Residence r = ledger.residenceAt(player.level().dimension().location().toString(), pos);
        if (r == null || !r.insideInterior(r.dimension, pos)) throw new IllegalStateException("Look at a container inside your home.");
        if (!ResidenceGuard.allowed(ledger, r, player.getUUID(), r.dimension, pos, ResidenceGuard.Act.CONTAINER)) throw new IllegalStateException("You cannot use that container.");
        if (!(player.level().getBlockEntity(pos) instanceof Container) || player.level().getBlockEntity(pos) instanceof PropertyCacheBlockEntity) {
            throw new IllegalStateException("Only chests, barrels and similar containers can be made private.");
        }
        ledger.claimContainer(player.getUUID(), r.id, pos);
    }

    public static void releaseContainer(ServerPlayer player, BlockPos pos) {
        CrewLedger ledger = need(player.server);
        Residence r = ledger.residenceAt(player.level().dimension().location().toString(), pos);
        if (r == null) throw new IllegalStateException("Look at your private container.");
        ledger.releaseClaim(player.getUUID(), r.id, pos);
    }

    // ------------------------------------------------------------------------------------------------------------ recovery

    private static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }

    /**
     * Decides every transaction a crash left in flight, from the wallet. Returns how many were settled or cancelled. A transaction whose wallet
     * matches neither side is left untouched and logged: it blocks only that player's Crew transfers and needs an operator.
     */
    public static int recover(MinecraftServer server, Wallet reader) {
        CrewLedger ledger = ledger(server);
        if (ledger == null || ledger.faulted()) return 0;
        int decided = 0;
        for (Pending p : List.copyOf(ledger.allPending().values())) {
            ServerPlayer online = server.getPlayerList().getPlayer(p.player());
            Double current = online != null ? Double.valueOf(reader.balance(online)) : reader.offline(server, p.player());
            if (current == null) {
                LOG.warn("Crew transfer {} of {} cannot be decided yet: the wallet cannot be read (provider missing?)", p.tx(), p.player());
                continue;
            }
            if (near(current, p.after())) {
                ledger.settle(p.player());
                LOG.info("Crew recovery: transfer {} ({} {}) had reached the wallet; settled", p.tx(), p.kind(), p.amount());
                decided++;
            } else if (near(current, p.before())) {
                ledger.cancel(p.player());
                LOG.info("Crew recovery: transfer {} ({} {}) never reached the wallet; cancelled", p.tx(), p.kind(), p.amount());
                decided++;
            } else {
                LOG.error("Crew recovery: transfer {} for {} matches neither wallet value (now {}, before {}, after {}); leaving it for an operator", p.tx(), p.player(), current, p.before(), p.after());
            }
        }
        return decided;
    }

    // ------------------------------------------------------------------------------------------------------------ events

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        CrewLedger ledger = ledger(event.getServer());
        if (ledger == null) return;
        if (!ledger.allPending().isEmpty()) {
            if (wallet.ready(event.getServer())) recover(event.getServer(), wallet);
            else LOG.warn("{} Crew transfer(s) are waiting for the wallet provider before they can be recovered", ledger.allPending().size());
        }
        LOG.info("Crew ledger ready: {} Crews, {} homes registered", ledger.allCrews().size(), ledger.allResidences().size());
    }

    @SubscribeEvent
    public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        CrewLedger ledger = ledger(player.server);
        if (ledger == null) return;
        try {
            if (ledger.pendingFor(player.getUUID()) != null && wallet.ready(player.server)) recover(player.server, wallet);
            ledger.touch(player.getUUID(), player.getGameProfile().getName());
            Crew crew = ledger.crewOf(player.getUUID());
            if (crew != null) {
                player.sendSystemMessage(Component.literal("Crew: " + crew.name + " (" + crew.roleOf(player.getUUID()).title + ", " + crew.members.size() + " members). Press K for the Crew screen."));
            } else if (!ledger.invitesFor(player.getUUID()).isEmpty()) {
                Notice.send(player, Notice.Kind.INFO, "Crew invitation", "You have a Crew invitation. Press K to answer.");
            }
            if (ledger.owedTo(player.getUUID()) > 0) claimOwedQuietly(player);
        } catch (RuntimeException error) {
            LOG.error("Crew login handling failed for {}", player.getGameProfile().getName(), error);
        }
    }

    @SubscribeEvent
    public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
        CrewLedger ledger = ledger(player.server);
        if (ledger == null || ledger.faulted()) return;
        try { ledger.touch(player.getUUID(), player.getGameProfile().getName()); } catch (RuntimeException error) { LOG.error("Crew logout handling failed", error); }
    }

    /** Names of members for an operator's status line. */
    static String describe(Crew crew) {
        Set<String> names = new HashSet<>();
        crew.members.values().forEach(m -> names.add(m.name + " (" + m.role.title + ")"));
        return crew.name + " [" + crew.id + "] bank=" + crew.bank + " mode=" + crew.mode + " members=" + names;
    }
}
