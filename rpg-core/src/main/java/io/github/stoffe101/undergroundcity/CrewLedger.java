package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/**
 * The single durable record of Crews: who belongs to which Crew with which role, invitations, the Crew bank and its history, who owns
 * which residence, and the one in-flight money transaction a player can have. It lives in one file, written atomically and fsynced
 * ({@code data/underground_city_crews.dat}), because a membership change, a bank movement and a change of ownership must never be
 * able to disagree after a crash. Nothing here touches a wallet or a player: that is {@link CrewService}'s job, in the order
 * "wallet first, ledger second" described in docs/technical/crew-economy.md. All methods run on the server thread.
 *
 * Money in the bank is a count of Q Shop credits that have left a wallet. It is never a second currency: the sum of all wallets, all
 * banks and all owed payouts is conserved by every operation here (the GameTests check it after random operations and simulated crashes).
 */
public final class CrewLedger {
    public static final int SCHEMA = 1;
    private static final Map<MinecraftServer, CrewLedger> INSTANCES = new WeakHashMap<>();

    public static CrewLedger get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, key -> new CrewLedger(
            key.getWorldPath(LevelResource.ROOT).resolve("data/underground_city_crews.dat"), System::currentTimeMillis));
    }

    /** Test seam: makes {@code replacement} the ledger of this server and returns the one it displaced (or null). Never used outside GameTests. */
    static CrewLedger swap(MinecraftServer server, CrewLedger replacement) {
        CrewLedger previous = INSTANCES.get(server);
        if (replacement == null) INSTANCES.remove(server); else INSTANCES.put(server, replacement);
        return previous;
    }

    // ------------------------------------------------------------------------------------------------------------ model

    public static final class Member {
        public final UUID id;
        public String name;
        public Role role;
        public long joined, lastSeen;
        Member(UUID id, String name, Role role, long now) { this.id = id; this.name = name; this.role = role; this.joined = now; this.lastSeen = now; }
    }

    /** One line of a Crew's history. */
    public record Entry(long time, UUID actor, String actorName, String kind, long amount, String note) {}

    public static final class Crew {
        public final UUID id;
        public String name;
        public UUID founder;
        public final long created;
        public final Map<UUID, Member> members = new LinkedHashMap<>();
        public final Map<Role, EnumSet<Perm>> perms = new EnumMap<>(Role.class);
        public Mode mode = Mode.BANK;
        public final Set<UUID> consent = new LinkedHashSet<>();
        public long bank;
        public final ArrayDeque<Entry> log = new ArrayDeque<>();

        Crew(UUID id, String name, UUID founder, long created) {
            this.id = id; this.name = name; this.founder = founder; this.created = created;
            perms.put(Role.FOUNDER, CrewRules.defaultPerms(Role.FOUNDER));
            perms.put(Role.MANAGER, CrewRules.defaultPerms(Role.MANAGER));
            perms.put(Role.MEMBER, CrewRules.defaultPerms(Role.MEMBER));
        }

        public Role roleOf(UUID player) { Member m = members.get(player); return m == null ? null : m.role; }

        /** What this player may do in this Crew. The founder always may everything. */
        public boolean can(UUID player, Perm perm) {
            Role role = roleOf(player);
            if (role == null) return false;
            return role == Role.FOUNDER || perms.get(role).contains(perm);
        }

        /** SHARED applies only while every member has agreed; otherwise the Crew behaves as BANK, so nobody's income moves without consent. */
        public Mode effectiveMode() {
            if (mode != Mode.SHARED) return mode;
            return consent.containsAll(members.keySet()) ? Mode.SHARED : Mode.BANK;
        }

        void record(long now, UUID actor, String actorName, String kind, long amount, String note) {
            log.addFirst(new Entry(now, actor, actorName == null ? "" : actorName, kind, amount, note == null ? "" : note));
            while (log.size() > CrewRules.LOG_MAX) log.removeLast();
        }
    }

    public record Invite(UUID id, UUID crew, String crewName, UUID invitee, String inviteeName, UUID by, String byName, long created) {}

    public enum OwnerType { NONE, PLAYER, CREW }

    /** A registered region of the world that can be leased: the interior is what a tenant may change, the one-block shell around it is city infrastructure. */
    public static final class Residence {
        public final String id, title, kind, dimension, district;
        public final BlockPos min, max;
        public final int price, starterPrice;
        public OwnerType ownerType = OwnerType.NONE;
        public UUID owner;
        public long since;
        /** Set while a wallet-paid lease is in flight, so nobody else can take the unit meanwhile. */
        public UUID reservedBy;
        /** Players who may still walk in (and open their own private containers) until the given time, for instance somebody who has just left. */
        public final Map<UUID, Long> guests = new LinkedHashMap<>();
        /** Containers that one tenant keeps private: block position to claimant. */
        public final Map<Long, UUID> claims = new LinkedHashMap<>();

        Residence(String id, String title, String kind, String dimension, String district, BlockPos min, BlockPos max, int price, int starterPrice) {
            this.id = id; this.title = title; this.kind = kind; this.dimension = dimension; this.district = district;
            this.min = min.immutable(); this.max = max.immutable(); this.price = price; this.starterPrice = starterPrice;
        }

        public boolean insideInterior(String dim, BlockPos p) {
            return dim.equals(dimension) && p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY()
                && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }

        /** The interior plus the one-block shell of walls, floor and ceiling around it. */
        public boolean insideShell(String dim, BlockPos p) {
            return dim.equals(dimension) && p.getX() >= min.getX() - 1 && p.getX() <= max.getX() + 1 && p.getY() >= min.getY() - 1 && p.getY() <= max.getY() + 1
                && p.getZ() >= min.getZ() - 1 && p.getZ() <= max.getZ() + 1;
        }

        /** Squared distance from a block position to the nearest block of the interior (0 inside). */
        public double distanceSqr(BlockPos p) {
            double dx = Math.max(Math.max(min.getX() - p.getX(), 0), p.getX() - max.getX());
            double dy = Math.max(Math.max(min.getY() - p.getY(), 0), p.getY() - max.getY());
            double dz = Math.max(Math.max(min.getZ() - p.getZ(), 0), p.getZ() - max.getZ());
            return dx * dx + dy * dy + dz * dz;
        }

        public boolean owned() { return ownerType != OwnerType.NONE; }
        public boolean sameGeometry(String dim, BlockPos lo, BlockPos hi) { return dim.equals(dimension) && lo.equals(min) && hi.equals(max); }
    }

    public enum Kind { DEPOSIT, WITHDRAW, LEASE_WALLET, CLAIM_OWED }

    /**
     * The one in-flight transaction a player may have. {@code before}/{@code after} are the wallet balances on either side of the change; after a
     * crash the wallet is compared with them to learn how far the transaction got (see {@link CrewService#recover}).
     */
    public record Pending(UUID tx, UUID player, Kind kind, UUID crew, long amount, double before, double after, String residence, boolean starter) {}

    // ------------------------------------------------------------------------------------------------------------ state

    private final Path path;
    private final LongSupplier clock;
    private boolean faulted;
    final Map<UUID, Crew> crews = new LinkedHashMap<>();
    private final Map<UUID, UUID> crewOf = new HashMap<>();
    final Map<UUID, Invite> invites = new LinkedHashMap<>();
    final Map<UUID, Long> owed = new LinkedHashMap<>();
    final Map<String, Residence> residences = new LinkedHashMap<>();
    final Set<UUID> starterUsed = new LinkedHashSet<>();
    final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final LinkedHashSet<UUID> seen = new LinkedHashSet<>();
    private final Map<Long, List<Residence>> chunkIndex = new HashMap<>();

    /** {@code path} may be null for a purely in-memory ledger (nothing is written). */
    public CrewLedger(Path path, LongSupplier clock) {
        this.path = path;
        this.clock = clock;
        if (path != null && Files.exists(path)) {
            try {
                CompoundTag root = NbtIo.read(path.toFile());
                if (root == null) throw new IOException("Empty Crew ledger");
                if (root.getInt("schema_version") != SCHEMA) throw new IOException("Unsupported Crew ledger schema " + root.getInt("schema_version"));
                load(root);
            } catch (IOException | RuntimeException error) {
                throw new IllegalStateException("Cannot read the Crew ledger; restore the backup of data/underground_city_crews.dat", error);
            }
        }
        reindex();
    }

    public boolean faulted() { return faulted; }
    long now() { return clock.getAsLong(); }

    private void checkHealthy() {
        if (faulted) throw new IllegalStateException("Crew storage failed; restart the server so the ledger can be recovered");
    }

    // ------------------------------------------------------------------------------------------------------------ queries

    public Crew crew(UUID id) { return crews.get(id); }
    public Crew crewOf(UUID player) { UUID id = crewOf.get(player); return id == null ? null : crews.get(id); }
    public List<Crew> allCrews() { return List.copyOf(crews.values()); }
    public Pending pendingFor(UUID player) { return pending.get(player); }
    public Map<UUID, Pending> allPending() { return Map.copyOf(pending); }
    public long owedTo(UUID player) { return owed.getOrDefault(player, 0L); }
    public boolean starterAvailable(UUID player) { return !starterUsed.contains(player); }
    public List<Residence> allResidences() { return List.copyOf(residences.values()); }
    public Residence residence(String id) { return residences.get(id); }

    public List<Invite> invitesFor(UUID invitee) {
        long now = now();
        List<Invite> list = new ArrayList<>();
        for (Invite invite : invites.values()) if (invite.invitee().equals(invitee) && now - invite.created() < CrewRules.INVITE_TTL_MILLIS) list.add(invite);
        return list;
    }

    public List<Invite> invitesFrom(UUID crew) {
        long now = now();
        List<Invite> list = new ArrayList<>();
        for (Invite invite : invites.values()) if (invite.crew().equals(crew) && now - invite.created() < CrewRules.INVITE_TTL_MILLIS) list.add(invite);
        return list;
    }

    /** Sum of every bank and every owed payout: the credits held by the ledger rather than by a wallet. */
    public long totalHeld() {
        long total = 0;
        for (Crew crew : crews.values()) total += crew.bank;
        for (long value : owed.values()) total += value;
        return total;
    }

    /** Credits taken out of a bank or an owed balance but not yet confirmed in a wallet (withdrawals and claims in flight). */
    public long totalInFlight() {
        long total = 0;
        for (Pending p : pending.values()) if (p.kind() == Kind.WITHDRAW || p.kind() == Kind.CLAIM_OWED) total += p.amount();
        return total;
    }

    public List<Residence> residencesOf(OwnerType type, UUID owner) {
        List<Residence> list = new ArrayList<>();
        for (Residence r : residences.values()) if (r.ownerType == type && owner.equals(r.owner)) list.add(r);
        return list;
    }

    // ------------------------------------------------------------------------------------------------------------ membership

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private Crew requireCrew(UUID player) {
        Crew crew = crewOf(player);
        require(crew != null, "You are not in a Crew.");
        return crew;
    }

    private void requireNoPending(Crew crew) {
        for (Pending p : pending.values()) if (crew.id.equals(p.crew()) || crew.members.containsKey(p.player())) {
            throw new IllegalStateException("A money transfer is still being recovered. Try again in a moment.");
        }
    }

    public Crew create(UUID founder, String founderName, String rawName) {
        checkHealthy();
        require(crewOf(founder) == null, "You already belong to a Crew. Leave it first.");
        String name = CrewRules.cleanName(rawName);
        String key = CrewRules.nameKey(name);
        for (Crew other : crews.values()) require(!CrewRules.nameKey(other.name).equals(key), "That Crew name is taken.");
        long now = now();
        Crew crew = new Crew(UUID.randomUUID(), name, founder, now);
        crew.members.put(founder, new Member(founder, founderName, Role.FOUNDER, now));
        crew.record(now, founder, founderName, "CREATED", 0, "");
        crews.put(crew.id, crew);
        crewOf.put(founder, crew.id);
        // Invitations to the new founder from other Crews no longer apply.
        invites.values().removeIf(invite -> invite.invitee().equals(founder));
        persist();
        return crew;
    }

    public Invite invite(UUID actor, UUID invitee, String inviteeName, String actorName) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.can(actor, Perm.INVITE), "You are not allowed to invite players.");
        require(crewOf(invitee) == null, inviteeName + " already belongs to a Crew.");
        require(!actor.equals(invitee), "You cannot invite yourself.");
        long now = now();
        invites.values().removeIf(old -> now - old.created() >= CrewRules.INVITE_TTL_MILLIS);
        require(crew.members.size() + invitesFrom(crew.id).size() < CrewRules.MAX_MEMBERS, "The Crew is full (" + CrewRules.MAX_MEMBERS + " places, including open invitations).");
        require(invitesFrom(crew.id).size() < CrewRules.MAX_OPEN_INVITES, "Too many open invitations. Wait for answers or withdraw some.");
        for (Invite existing : invitesFrom(crew.id)) require(!existing.invitee().equals(invitee), inviteeName + " has already been invited.");
        Invite invite = new Invite(UUID.randomUUID(), crew.id, crew.name, invitee, inviteeName, actor, actorName, now);
        invites.put(invite.id(), invite);
        persist();
        return invite;
    }

    public Crew accept(UUID inviteId, UUID player, String playerName) {
        checkHealthy();
        Invite invite = invites.get(inviteId);
        require(invite != null && invite.invitee().equals(player), "That invitation is no longer open.");
        long now = now();
        require(now - invite.created() < CrewRules.INVITE_TTL_MILLIS, "That invitation has expired.");
        require(crewOf(player) == null, "You already belong to a Crew.");
        Crew crew = crews.get(invite.crew());
        require(crew != null, "That Crew no longer exists.");
        require(crew.members.size() < CrewRules.MAX_MEMBERS, "That Crew is full.");
        crew.members.put(player, new Member(player, playerName, Role.MEMBER, now));
        crewOf.put(player, crew.id);
        invites.values().removeIf(other -> other.invitee().equals(player));
        crew.record(now, player, playerName, "JOINED", 0, "");
        persist();
        return crew;
    }

    public void decline(UUID inviteId, UUID player) {
        checkHealthy();
        Invite invite = invites.get(inviteId);
        require(invite != null && invite.invitee().equals(player), "That invitation is no longer open.");
        invites.remove(inviteId);
        persist();
    }

    public void withdrawInvite(UUID actor, UUID inviteId) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        Invite invite = invites.get(inviteId);
        require(invite != null && invite.crew().equals(crew.id), "That invitation is no longer open.");
        require(crew.can(actor, Perm.INVITE) || invite.by().equals(actor), "You are not allowed to withdraw invitations.");
        invites.remove(inviteId);
        persist();
    }

    public void leave(UUID player, String playerName) {
        checkHealthy();
        Crew crew = requireCrew(player);
        require(crew.roleOf(player) != Role.FOUNDER, crew.members.size() == 1
            ? "You are the only member. Disband the Crew instead." : "Hand over leadership first, then leave.");
        requireNoPending(crew);
        removeMember(crew, player, "LEFT", player, playerName, "");
        persist();
    }

    public void kick(UUID actor, String actorName, UUID target) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.can(actor, Perm.MANAGE_MEMBERS), "You are not allowed to remove members.");
        Member victim = crew.members.get(target);
        require(victim != null, "That player is not in your Crew.");
        require(crew.roleOf(actor).outranks(victim.role), "You can only remove members who rank below you.");
        requireNoPending(crew);
        removeMember(crew, target, "KICKED", actor, actorName, victim.name);
        persist();
    }

    private void removeMember(Crew crew, UUID player, String kind, UUID actor, String actorName, String note) {
        long now = now();
        Member gone = crew.members.remove(player);
        crewOf.remove(player);
        crew.consent.remove(player);
        // Somebody who stored things in a private container of a Crew home may still fetch them for a few days.
        for (Residence r : residencesOf(OwnerType.CREW, crew.id)) {
            if (r.claims.containsValue(player)) r.guests.put(player, now + CrewRules.RETRIEVAL_MILLIS);
        }
        crew.record(now, actor, actorName, kind, 0, note.isEmpty() ? gone.name : note);
    }

    public void setRole(UUID actor, String actorName, UUID target, Role role) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can change roles.");
        require(role == Role.MANAGER || role == Role.MEMBER, "Use 'transfer leadership' to hand over the Crew.");
        Member member = crew.members.get(target);
        require(member != null && member.role != Role.FOUNDER, "That player cannot be changed.");
        if (member.role == role) return;
        member.role = role;
        crew.record(now(), actor, actorName, "ROLE", 0, member.name + " is now " + role.title);
        persist();
    }

    public void transfer(UUID actor, String actorName, UUID target) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can hand over leadership.");
        Member next = crew.members.get(target);
        require(next != null && !target.equals(actor), "Choose another member of your Crew.");
        crew.members.get(actor).role = Role.MANAGER;
        next.role = Role.FOUNDER;
        crew.founder = target;
        crew.record(now(), actor, actorName, "TRANSFER", 0, next.name + " leads the Crew");
        persist();
    }

    /** A manager (or, with none, the longest-standing member) may take over a Crew whose founder has not been seen for 30 days. */
    public void claimLeadership(UUID player, String playerName) {
        checkHealthy();
        Crew crew = requireCrew(player);
        Member founder = crew.members.get(crew.founder);
        long now = now();
        require(founder != null && !founder.id.equals(player), "You already lead this Crew.");
        require(now - founder.lastSeen >= CrewRules.FOUNDER_INACTIVE_MILLIS, "The founder was seen " + CrewRules.ago(now - founder.lastSeen) + ". Leadership can be claimed after 30 days away.");
        boolean managers = crew.members.values().stream().anyMatch(m -> m.role == Role.MANAGER);
        require(!managers || crew.roleOf(player) == Role.MANAGER, "A manager has to take over first.");
        founder.role = Role.MANAGER;
        crew.members.get(player).role = Role.FOUNDER;
        crew.founder = player;
        crew.record(now, player, playerName, "TRANSFER", 0, playerName + " took over a Crew whose founder was away");
        persist();
    }

    public void rename(UUID actor, String actorName, String rawName) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can rename the Crew.");
        String name = CrewRules.cleanName(rawName);
        String key = CrewRules.nameKey(name);
        for (Crew other : crews.values()) require(other == crew || !CrewRules.nameKey(other.name).equals(key), "That Crew name is taken.");
        crew.name = name;
        crew.record(now(), actor, actorName, "RENAMED", 0, name);
        persist();
    }

    public void setPermission(UUID actor, String actorName, Role role, Perm perm, boolean allowed) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can change what a role may do.");
        require(role == Role.MANAGER || role == Role.MEMBER, "The founder always may everything.");
        EnumSet<Perm> set = crew.perms.get(role);
        if (allowed == set.contains(perm)) return;
        if (allowed) set.add(perm); else set.remove(perm);
        crew.record(now(), actor, actorName, "PERM", 0, role.title + (allowed ? " may: " : " may not: ") + perm.title);
        persist();
    }

    // ------------------------------------------------------------------------------------------------------------ finance modes

    public void setMode(UUID actor, String actorName, Mode mode) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can change how the Crew handles money.");
        if (mode == crew.mode) return;
        requireNoPending(crew);
        if (mode == Mode.SEPARATE) require(crew.bank == 0, "Pay out the Crew bank (" + crew.bank + " credits) before choosing separate money.");
        crew.mode = mode;
        crew.consent.clear();
        if (mode == Mode.SHARED) crew.consent.add(actor);
        crew.record(now(), actor, actorName, "MODE", 0, mode.title);
        persist();
    }

    /** A member agrees (or stops agreeing) to fully shared finances. Credits never move because of this. */
    public void setConsent(UUID player, String playerName, boolean agree) {
        checkHealthy();
        Crew crew = requireCrew(player);
        require(crew.mode == Mode.SHARED, "The Crew has not proposed fully shared finances.");
        if (agree == crew.consent.contains(player)) return;
        if (agree) crew.consent.add(player); else crew.consent.remove(player);
        crew.record(now(), player, playerName, "CONSENT", 0, agree ? "agreed to shared finances" : "withdrew from shared finances");
        persist();
    }

    /** Splits the bank equally among the members (the remainder to the founder) into owed payouts that each member claims into their own wallet. */
    public long payOut(UUID actor, String actorName) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can pay out the bank.");
        requireNoPending(crew);
        long total = crew.bank;
        require(total > 0, "The bank is empty.");
        distribute(crew);
        crew.record(now(), actor, actorName, "PAYOUT", total, "split between " + crew.members.size() + " members");
        persist();
        return total;
    }

    private void distribute(Crew crew) {
        int n = crew.members.size();
        long share = crew.bank / n, rest = crew.bank - share * n;
        for (Member member : crew.members.values()) {
            long amount = share + (member.id.equals(crew.founder) ? rest : 0);
            if (amount > 0) owed.merge(member.id, amount, Math::addExact);
        }
        crew.bank = 0;
    }

    // ------------------------------------------------------------------------------------------------------------ disband

    /** Ends the Crew: the bank is split into owed payouts, homes pass to the founder personally, invitations vanish. Returns the credits paid out. */
    public long disband(UUID actor, String actorName) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        require(crew.roleOf(actor) == Role.FOUNDER, "Only the founder can disband the Crew.");
        requireNoPending(crew);
        long total = crew.bank;
        if (total > 0) distribute(crew);
        for (Residence r : residencesOf(OwnerType.CREW, crew.id)) {
            r.ownerType = OwnerType.PLAYER;
            r.owner = crew.founder;
            r.guests.clear();
        }
        invites.values().removeIf(invite -> invite.crew().equals(crew.id));
        for (UUID member : crew.members.keySet()) crewOf.remove(member);
        crews.remove(crew.id);
        persist();
        return total;
    }

    // ------------------------------------------------------------------------------------------------------------ presence

    /** Keeps the name and last-seen time of a member fresh (login and logout). */
    public void touch(UUID player, String name) {
        Crew crew = crewOf(player);
        if (crew == null || faulted) return;
        Member member = crew.members.get(player);
        if (member == null) return;
        member.name = name;
        member.lastSeen = now();
        persist();
    }

    // ------------------------------------------------------------------------------------------------------------ money

    public boolean seenTx(UUID tx) { return seen.contains(tx); }

    private void remember(UUID tx) {
        seen.add(tx);
        while (seen.size() > CrewRules.SEEN_MAX) { var it = seen.iterator(); it.next(); it.remove(); }
    }

    private void requireFreshTx(UUID tx, UUID player) {
        require(tx != null && !seen.contains(tx), "That transfer was already processed.");
        require(!pending.containsKey(player), "Finish the money recovery first.");
    }

    /** Deposit step 1: records the intent. The bank is credited by {@link #settle}, after the wallet has been debited and saved. */
    public Pending beginDeposit(UUID tx, UUID player, long amount, double walletBefore) {
        checkHealthy();
        Crew crew = requireCrew(player);
        requireFreshTx(tx, player);
        CrewRules.cleanAmount(amount);
        require(crew.effectiveMode() != Mode.SEPARATE, "This Crew keeps money separate. Pay for Crew purchases yourself, or ask the founder to switch on a Crew bank.");
        require(crew.bank + amount <= CrewRules.MAX_BANK, "The bank cannot hold that much.");
        require(walletBefore >= amount, "You only have " + (long) Math.floor(walletBefore) + " credits.");
        Pending p = new Pending(tx, player, Kind.DEPOSIT, crew.id, amount, walletBefore, walletBefore - amount, "", false);
        pending.put(player, p);
        remember(tx);
        persist();
        return p;
    }

    /** Withdrawal step 1: takes the credits out of the bank at once as a hold; {@link #cancel} puts them back if the wallet never received them. */
    public Pending beginWithdraw(UUID tx, UUID player, long amount, double walletBefore) {
        checkHealthy();
        Crew crew = requireCrew(player);
        requireFreshTx(tx, player);
        CrewRules.cleanAmount(amount);
        require(crew.effectiveMode() != Mode.SEPARATE, "This Crew has no bank.");
        require(crew.can(player, Perm.WITHDRAW), "You are not allowed to withdraw from the Crew bank.");
        require(crew.bank >= amount, "The bank only holds " + crew.bank + " credits.");
        require(walletBefore + amount <= 1_000_000_000d, "Your wallet cannot hold that much.");
        crew.bank -= amount;
        Pending p = new Pending(tx, player, Kind.WITHDRAW, crew.id, amount, walletBefore, walletBefore + amount, "", false);
        pending.put(player, p);
        remember(tx);
        persist();
        return p;
    }

    /** Claiming a payout owed after a disband or a pay-out: a hold on the owed balance, like a withdrawal. */
    public Pending beginClaim(UUID tx, UUID player, double walletBefore) {
        checkHealthy();
        requireFreshTx(tx, player);
        long amount = owedTo(player);
        require(amount > 0, "Nothing is owed to you.");
        require(walletBefore + amount <= 1_000_000_000d, "Your wallet cannot hold that much.");
        owed.remove(player);
        Pending p = new Pending(tx, player, Kind.CLAIM_OWED, null, amount, walletBefore, walletBefore + amount, "", false);
        pending.put(player, p);
        remember(tx);
        persist();
        return p;
    }

    /** True when leasing this unit would spend the player's one starter discount. */
    public boolean usesStarter(UUID player, Residence r) {
        return r.starterPrice >= 0 && r.starterPrice < r.price && !starterUsed.contains(player);
    }

    /** The price this player would pay for a residence right now (the starter price while the entitlement is unspent). */
    public int priceFor(UUID player, Residence r) { return usesStarter(player, r) ? r.starterPrice : r.price; }

    public static final int MAX_PERSONAL_HOMES = 2, MAX_CREW_HOMES = 4;

    private Residence requireLeasable(String id, UUID forPlayerOrCrew, boolean crew) {
        Residence r = residences.get(id);
        require(r != null, "That home does not exist.");
        require(!r.owned(), "That home is already taken.");
        require(r.reservedBy == null, "Somebody is leasing that home right now.");
        if (crew) require(residencesOf(OwnerType.CREW, forPlayerOrCrew).size() < MAX_CREW_HOMES, "The Crew already holds " + MAX_CREW_HOMES + " homes.");
        else require(residencesOf(OwnerType.PLAYER, forPlayerOrCrew).size() < MAX_PERSONAL_HOMES, "You already hold " + MAX_PERSONAL_HOMES + " homes of your own.");
        return r;
    }

    /** Pays a Crew lease from the bank: one atomic ledger write, nothing to recover. Returns the price. */
    public int leaseForCrewFromBank(UUID tx, UUID actor, String actorName, String residenceId) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        requireFreshTx(tx, actor);
        require(crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to lease homes for the Crew.");
        require(crew.can(actor, Perm.SPEND), "You are not allowed to spend Crew money.");
        require(crew.effectiveMode() != Mode.SEPARATE, "This Crew has no bank. Lease with your own credits instead.");
        Residence r = requireLeasable(residenceId, crew.id, true);
        int price = priceFor(actor, r);
        boolean starter = usesStarter(actor, r);
        require(crew.bank >= price, "The bank holds " + crew.bank + " credits. This home costs " + price + ".");
        requireNoPending(crew);
        crew.bank -= price;
        if (starter) starterUsed.add(actor);
        r.ownerType = OwnerType.CREW; r.owner = crew.id; r.since = now();
        crew.record(now(), actor, actorName, "LEASE", price, r.title);
        remember(tx);
        persist();
        return price;
    }

    /** Lease paid from a wallet, step 1: reserves the unit and records the price. Ownership is assigned by {@link #settle}. */
    public Pending beginLeaseFromWallet(UUID tx, UUID actor, boolean forCrew, String residenceId, double walletBefore) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Crew crew = forCrew ? requireCrew(actor) : crewOf(actor);
        if (forCrew) {
            require(crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to lease homes for the Crew.");
            requireNoPending(crew);
        }
        Residence r = requireLeasable(residenceId, forCrew ? crew.id : actor, forCrew);
        int price = priceFor(actor, r);
        boolean starter = usesStarter(actor, r);
        require(walletBefore >= price, "You only have " + (long) Math.floor(walletBefore) + " credits. This home costs " + price + ".");
        r.reservedBy = tx;
        Pending p = new Pending(tx, actor, Kind.LEASE_WALLET, forCrew ? crew.id : null, price, walletBefore, walletBefore - price, residenceId, starter);
        pending.put(actor, p);
        remember(tx);
        persist();
        return p;
    }

    /** The wallet side happened and is saved: completes the transaction. Idempotent for a player with nothing in flight. */
    public void settle(UUID player) {
        checkHealthy();
        Pending p = pending.get(player);
        if (p == null) return;
        long now = now();
        Crew crew = p.crew() == null ? null : crews.get(p.crew());
        Member actor = crew == null ? null : crew.members.get(player);
        String actorName = actor == null ? "" : actor.name;
        switch (p.kind()) {
            case DEPOSIT -> {
                if (crew == null) owed.merge(player, p.amount(), Math::addExact); // the Crew ended meanwhile: the credits are owed back
                else {
                    crew.bank = Math.addExact(crew.bank, p.amount());
                    crew.record(now, player, actorName, "DEPOSIT", p.amount(), "");
                }
            }
            case WITHDRAW -> { if (crew != null) crew.record(now, player, actorName, "WITHDRAW", p.amount(), ""); }
            case CLAIM_OWED -> { }
            case LEASE_WALLET -> {
                Residence r = residences.get(p.residence());
                if (r == null || !p.tx().equals(r.reservedBy) || (p.crew() != null && crew == null)) {
                    owed.merge(player, p.amount(), Math::addExact); // the reservation was lost: give the price back
                } else {
                    r.reservedBy = null;
                    if (p.crew() != null) { r.ownerType = OwnerType.CREW; r.owner = p.crew(); } else { r.ownerType = OwnerType.PLAYER; r.owner = player; }
                    r.since = now;
                    if (p.starter()) starterUsed.add(player);
                    if (crew != null) crew.record(now, player, actorName, "LEASE", p.amount(), r.title);
                }
            }
        }
        pending.remove(player);
        persist();
    }

    /** The wallet side never happened: undoes the ledger side (a withdrawal's hold returns to the bank, a reservation is released). */
    public void cancel(UUID player) {
        checkHealthy();
        Pending p = pending.get(player);
        if (p == null) return;
        switch (p.kind()) {
            case DEPOSIT -> { }
            case WITHDRAW -> {
                Crew crew = crews.get(p.crew());
                if (crew != null) crew.bank = Math.addExact(crew.bank, p.amount());
                else owed.merge(player, p.amount(), Math::addExact); // the Crew ended meanwhile: the credits are owed back
            }
            case CLAIM_OWED -> owed.merge(player, p.amount(), Math::addExact);
            case LEASE_WALLET -> {
                Residence r = residences.get(p.residence());
                if (r != null && p.tx().equals(r.reservedBy)) r.reservedBy = null;
            }
        }
        pending.remove(player);
        persist();
    }

    // ------------------------------------------------------------------------------------------------------------ residences

    /** Registers (or refreshes) a leasable region. A unit that someone holds keeps its owner; a changed geometry under an owner is refused. */
    public Residence registerResidence(String id, String title, String kind, String dimension, String district, BlockPos min, BlockPos max, int price, int starterPrice) {
        checkHealthy();
        require(id.matches("[a-z0-9_:.\\-/]{3,64}"), "Bad residence id " + id);
        require(price >= 1 && price <= 1_000_000 && starterPrice <= price, "Bad residence price");
        BlockPos lo = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        BlockPos hi = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        Residence old = residences.get(id);
        if (old != null) {
            if (old.sameGeometry(dimension, lo, hi)) return old;
            require(!old.owned() && old.reservedBy == null, "Residence " + id + " is held by somebody and cannot move.");
        }
        for (Residence other : residences.values()) {
            if (other.id.equals(id) || !other.dimension.equals(dimension)) continue;
            // Two shells may touch (neighbouring flats share a wall), but no interior may reach into another unit's shell.
            require(!boxesIntersect(lo.getX() - 1, lo.getY() - 1, lo.getZ() - 1, hi.getX() + 1, hi.getY() + 1, hi.getZ() + 1,
                    other.min.getX(), other.min.getY(), other.min.getZ(), other.max.getX(), other.max.getY(), other.max.getZ())
                && !boxesIntersect(other.min.getX() - 1, other.min.getY() - 1, other.min.getZ() - 1, other.max.getX() + 1, other.max.getY() + 1, other.max.getZ() + 1,
                    lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ()), "Residence " + id + " overlaps " + other.id);
        }
        Residence r = new Residence(id, title, kind, dimension, district, lo, hi, price, starterPrice);
        residences.put(id, r);
        reindex();
        persist();
        return r;
    }

    private static boolean boxesIntersect(int ax0, int ay0, int az0, int ax1, int ay1, int az1, int bx0, int by0, int bz0, int bx1, int by1, int bz1) {
        return ax0 <= bx1 && ax1 >= bx0 && ay0 <= by1 && ay1 >= by0 && az0 <= bz1 && az1 >= bz0;
    }

    /** Removes an unleased residence (a district was undone). A held one is never removed. */
    public void unregisterResidence(String id) {
        checkHealthy();
        Residence r = residences.get(id);
        if (r == null) return;
        require(!r.owned() && r.reservedBy == null, "Residence " + id + " is held by somebody. Release it before removing the district.");
        residences.remove(id);
        reindex();
        persist();
    }

    public boolean claimContainer(UUID actor, String residenceId, BlockPos pos) {
        checkHealthy();
        Residence r = residences.get(residenceId);
        require(r != null && r.insideInterior(r.dimension, pos), "That container is not inside the home.");
        long key = pos.asLong();
        require(!r.claims.containsKey(key), "That container is already private.");
        long mine = r.claims.values().stream().filter(actor::equals).count();
        require(mine < CrewRules.MAX_CLAIMS_PER_PLAYER, "You can keep " + CrewRules.MAX_CLAIMS_PER_PLAYER + " private containers per home.");
        r.claims.put(key, actor);
        persist();
        return true;
    }

    public void releaseClaim(UUID actor, String residenceId, BlockPos pos) {
        checkHealthy();
        Residence r = residences.get(residenceId);
        require(r != null, "That home does not exist.");
        UUID claimant = r.claims.get(pos.asLong());
        require(claimant != null, "That container is not private.");
        require(claimant.equals(actor), "Only the one who claimed it can share it again.");
        r.claims.remove(pos.asLong());
        persist();
    }

    /** The residence whose shell contains the position, or null. */
    public Residence residenceAt(String dimension, BlockPos pos) {
        if (chunkIndex.isEmpty()) return null;
        List<Residence> list = chunkIndex.get(chunkKey(pos.getX() >> 4, pos.getZ() >> 4));
        if (list == null) return null;
        Residence shell = null;
        for (Residence r : list) {
            if (r.insideInterior(dimension, pos)) return r;
            if (shell == null && r.insideShell(dimension, pos)) shell = r;
        }
        return shell;
    }

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private void reindex() {
        chunkIndex.clear();
        for (Residence r : residences.values()) {
            for (int cx = (r.min.getX() - 1) >> 4; cx <= (r.max.getX() + 1) >> 4; cx++)
                for (int cz = (r.min.getZ() - 1) >> 4; cz <= (r.max.getZ() + 1) >> 4; cz++)
                    chunkIndex.computeIfAbsent(chunkKey(cx, cz), key -> new ArrayList<>()).add(r);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ persistence

    private CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", SCHEMA);
        ListTag crewList = new ListTag();
        for (Crew crew : crews.values()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("id", crew.id); c.putString("name", crew.name); c.putUUID("founder", crew.founder); c.putLong("created", crew.created);
            c.putString("mode", crew.mode.name()); c.putLong("bank", crew.bank);
            ListTag consent = new ListTag();
            for (UUID id : crew.consent) consent.add(net.minecraft.nbt.NbtUtils.createUUID(id));
            c.put("consent", consent);
            CompoundTag perms = new CompoundTag();
            for (Role role : new Role[]{Role.MANAGER, Role.MEMBER}) {
                ListTag list = new ListTag();
                for (Perm perm : crew.perms.get(role)) list.add(StringTag.valueOf(perm.name()));
                perms.put(role.name(), list);
            }
            c.put("perms", perms);
            ListTag members = new ListTag();
            for (Member m : crew.members.values()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("id", m.id); t.putString("name", m.name); t.putString("role", m.role.name()); t.putLong("joined", m.joined); t.putLong("seen", m.lastSeen);
                members.add(t);
            }
            c.put("members", members);
            ListTag log = new ListTag();
            for (Entry e : crew.log) {
                CompoundTag t = new CompoundTag();
                t.putLong("t", e.time()); if (e.actor() != null) t.putUUID("a", e.actor()); t.putString("an", e.actorName()); t.putString("k", e.kind()); t.putLong("m", e.amount()); t.putString("n", e.note());
                log.add(t);
            }
            c.put("log", log);
            crewList.add(c);
        }
        root.put("crews", crewList);
        ListTag inviteList = new ListTag();
        for (Invite i : invites.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", i.id()); t.putUUID("crew", i.crew()); t.putString("crew_name", i.crewName()); t.putUUID("invitee", i.invitee()); t.putString("invitee_name", i.inviteeName());
            t.putUUID("by", i.by()); t.putString("by_name", i.byName()); t.putLong("created", i.created());
            inviteList.add(t);
        }
        root.put("invites", inviteList);
        ListTag owedList = new ListTag();
        owed.forEach((id, amount) -> { CompoundTag t = new CompoundTag(); t.putUUID("p", id); t.putLong("m", amount); owedList.add(t); });
        root.put("owed", owedList);
        ListTag starter = new ListTag();
        for (UUID id : starterUsed) starter.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        root.put("starter_used", starter);
        ListTag seenList = new ListTag();
        for (UUID id : seen) seenList.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        root.put("seen_tx", seenList);
        ListTag pendingList = new ListTag();
        for (Pending p : pending.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("tx", p.tx()); t.putUUID("player", p.player()); t.putString("kind", p.kind().name()); if (p.crew() != null) t.putUUID("crew", p.crew());
            t.putLong("amount", p.amount()); t.putDouble("before", p.before()); t.putDouble("after", p.after()); t.putString("residence", p.residence()); t.putBoolean("starter", p.starter());
            pendingList.add(t);
        }
        root.put("pending", pendingList);
        ListTag residenceList = new ListTag();
        for (Residence r : residences.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", r.id); t.putString("title", r.title); t.putString("kind", r.kind); t.putString("dimension", r.dimension); t.putString("district", r.district);
            t.putLong("min", r.min.asLong()); t.putLong("max", r.max.asLong()); t.putInt("price", r.price); t.putInt("starter_price", r.starterPrice);
            t.putString("owner_type", r.ownerType.name()); if (r.owner != null) t.putUUID("owner", r.owner); t.putLong("since", r.since);
            if (r.reservedBy != null) t.putUUID("reserved_by", r.reservedBy);
            ListTag guests = new ListTag();
            r.guests.forEach((id, until) -> { CompoundTag g = new CompoundTag(); g.putUUID("p", id); g.putLong("until", until); guests.add(g); });
            t.put("guests", guests);
            ListTag claims = new ListTag();
            r.claims.forEach((pos, id) -> { CompoundTag g = new CompoundTag(); g.putLong("pos", pos); g.putUUID("p", id); claims.add(g); });
            t.put("claims", claims);
            residenceList.add(t);
        }
        root.put("residences", residenceList);
        return root;
    }

    private void load(CompoundTag root) {
        for (Tag raw : root.getList("crews", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) raw;
            Crew crew = new Crew(c.getUUID("id"), c.getString("name"), c.getUUID("founder"), c.getLong("created"));
            crew.mode = Mode.valueOf(c.getString("mode"));
            crew.bank = c.getLong("bank");
            if (crew.bank < 0) throw new IllegalStateException("Negative bank for " + crew.name);
            for (Tag t : c.getList("consent", Tag.TAG_INT_ARRAY)) crew.consent.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
            CompoundTag perms = c.getCompound("perms");
            for (Role role : new Role[]{Role.MANAGER, Role.MEMBER}) {
                EnumSet<Perm> set = EnumSet.noneOf(Perm.class);
                for (Tag t : perms.getList(role.name(), Tag.TAG_STRING)) set.add(Perm.valueOf(t.getAsString()));
                crew.perms.put(role, set);
            }
            for (Tag raw2 : c.getList("members", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) raw2;
                Member m = new Member(t.getUUID("id"), t.getString("name"), Role.valueOf(t.getString("role")), t.getLong("joined"));
                m.lastSeen = t.getLong("seen");
                crew.members.put(m.id, m);
                crewOf.put(m.id, crew.id);
            }
            if (!crew.members.containsKey(crew.founder) || crew.members.get(crew.founder).role != Role.FOUNDER) throw new IllegalStateException("Crew " + crew.name + " has no founder");
            for (Tag raw2 : c.getList("log", Tag.TAG_COMPOUND)) {
                CompoundTag t = (CompoundTag) raw2;
                crew.log.addLast(new Entry(t.getLong("t"), t.hasUUID("a") ? t.getUUID("a") : null, t.getString("an"), t.getString("k"), t.getLong("m"), t.getString("n")));
            }
            crews.put(crew.id, crew);
        }
        for (Tag raw : root.getList("invites", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Invite i = new Invite(t.getUUID("id"), t.getUUID("crew"), t.getString("crew_name"), t.getUUID("invitee"), t.getString("invitee_name"), t.getUUID("by"), t.getString("by_name"), t.getLong("created"));
            invites.put(i.id(), i);
        }
        for (Tag raw : root.getList("owed", Tag.TAG_COMPOUND)) { CompoundTag t = (CompoundTag) raw; owed.put(t.getUUID("p"), t.getLong("m")); }
        for (Tag t : root.getList("starter_used", Tag.TAG_INT_ARRAY)) starterUsed.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        for (Tag t : root.getList("seen_tx", Tag.TAG_INT_ARRAY)) seen.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        for (Tag raw : root.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Pending p = new Pending(t.getUUID("tx"), t.getUUID("player"), Kind.valueOf(t.getString("kind")), t.hasUUID("crew") ? t.getUUID("crew") : null,
                t.getLong("amount"), t.getDouble("before"), t.getDouble("after"), t.getString("residence"), t.getBoolean("starter"));
            pending.put(p.player(), p);
        }
        for (Tag raw : root.getList("residences", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Residence r = new Residence(t.getString("id"), t.getString("title"), t.getString("kind"), t.getString("dimension"), t.getString("district"),
                BlockPos.of(t.getLong("min")), BlockPos.of(t.getLong("max")), t.getInt("price"), t.getInt("starter_price"));
            r.ownerType = OwnerType.valueOf(t.getString("owner_type"));
            r.owner = t.hasUUID("owner") ? t.getUUID("owner") : null;
            r.since = t.getLong("since");
            r.reservedBy = t.hasUUID("reserved_by") ? t.getUUID("reserved_by") : null;
            for (Tag g : t.getList("guests", Tag.TAG_COMPOUND)) r.guests.put(((CompoundTag) g).getUUID("p"), ((CompoundTag) g).getLong("until"));
            for (Tag g : t.getList("claims", Tag.TAG_COMPOUND)) r.claims.put(((CompoundTag) g).getLong("pos"), ((CompoundTag) g).getUUID("p"));
            residences.put(r.id, r);
        }
        // Referential checks: a held residence points at a real owner, a pending transaction at a player with a real record.
        for (Residence r : residences.values()) {
            if (r.ownerType == OwnerType.CREW && !crews.containsKey(r.owner)) throw new IllegalStateException("Residence " + r.id + " belongs to a Crew that does not exist");
            if (r.ownerType != OwnerType.NONE && r.owner == null) throw new IllegalStateException("Residence " + r.id + " has no owner");
        }
    }

    /** Writes the whole ledger atomically and flushes it to disk; a failure makes the ledger refuse everything until a restart. */
    public void persist() {
        checkHealthy();
        if (path == null) return;
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                DataOutputStream out = new DataOutputStream(Channels.newOutputStream(channel));
                NbtIo.write(save(), out);
                out.flush();
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            WorkOrderLedger.forceDirectory(path.getParent());
        } catch (IOException error) {
            faulted = true; // never carry on after an uncertain durable write
            throw new IllegalStateException("Cannot durably save the Crew ledger", error);
        }
    }

    /** Test seam: the ledger exactly as a restart would read it from disk. */
    public CrewLedger reloaded() { return new CrewLedger(path, clock); }

    /** Test seam and diagnostics: an unmodifiable view of residences by owner. */
    public Map<String, Residence> residencesView() { return Collections.unmodifiableMap(residences); }
}
