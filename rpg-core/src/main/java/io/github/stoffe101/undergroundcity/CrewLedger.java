package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewRules.Mode;
import io.github.stoffe101.undergroundcity.PropertyRules.Rules;
import io.github.stoffe101.undergroundcity.PropertyRules.Standing;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import io.github.stoffe101.undergroundcity.PropertyRules.Terms;
import io.github.stoffe101.undergroundcity.PropertyRules.Upgrade;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
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
    /** 2: homes carry a tenancy (rent or ownership, the billing clock, upgrades, receipts) and a welcome record; schema 1 files are read and migrated (owned homes become legacy ownership with no charge). */
    public static final int SCHEMA = 2;
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

    /** One interior box of a home, inclusive. */
    public record Box(BlockPos min, BlockPos max) {
        public Box {
            BlockPos lo = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
            BlockPos hi = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
            min = lo; max = hi;
        }
        boolean inside(BlockPos p) { return p.getX() >= min.getX() && p.getX() <= max.getX() && p.getY() >= min.getY() && p.getY() <= max.getY() && p.getZ() >= min.getZ() && p.getZ() <= max.getZ(); }
        boolean inShell(BlockPos p) {
            return p.getX() >= min.getX() - 1 && p.getX() <= max.getX() + 1 && p.getY() >= min.getY() - 1 && p.getY() <= max.getY() + 1 && p.getZ() >= min.getZ() - 1 && p.getZ() <= max.getZ() + 1;
        }
        double distanceSqr(BlockPos p) {
            double dx = Math.max(Math.max(min.getX() - p.getX(), 0), p.getX() - max.getX());
            double dy = Math.max(Math.max(min.getY() - p.getY(), 0), p.getY() - max.getY());
            double dz = Math.max(Math.max(min.getZ() - p.getZ(), 0), p.getZ() - max.getZ());
            return dx * dx + dy * dy + dz * dz;
        }
        long volume() { return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1); }
    }

    /** At most this many boxes make one home (D-206): the rooms of a house over several chunks, or of a flat that is not one rectangle. */
    public static final int MAX_BOXES = 160;

    /**
     * A registered region of the world that can be leased: the interior is what a tenant may change, the one-block shell around it is city infrastructure. The interior is one box, or several
     * (D-206: a property follows the dwelling, not the chunk): a house over two chunks is one home whose boxes lie in both, and two flats that share a wall are two homes whose boxes never
     * reach into each other's shell. {@code min} and {@code max} are the first box, which holds the home's entrance where the plan names one.
     */
    public static final class Residence {
        public final String id, title, kind, dimension, district;
        public final BlockPos min, max;
        public final List<Box> boxes;
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

        // ---- the tenancy (schema 2): how the home is held and what it costs to keep. Rent and ownership are different things (D-099).
        /** NONE for an unheld home, RENT for a tenant, OWN for a buyer or a home taken before rent existed. */
        public Tenure tenure = Tenure.NONE;
        /** Held before rent existed (an old save): ownership with no charge, ever. */
        public boolean legacy;
        /** Credits per cycle, fixed at signing: the rent for a tenant, the upkeep for an owner (0 for a legacy owner). */
        public int charge;
        /** The deposit held for a tenant, returned when they move out less what is owed. */
        public int deposit;
        /** What the owner paid, which bounds what selling back can return. */
        public int paidPrice;
        /** Active ticks counted toward the next cycle: the clock runs once per household while a resident is online. */
        public long progress;
        /** Cycles that have fallen due and wait for an automatic payment from a wallet (normally 0). */
        public int due;
        /** Cycles paid in advance (including the free welcome hours). */
        public int prepaid;
        /** Of the prepaid cycles, those the city gave (a motel's welcome stay): they are used first and never refunded. */
        public int freeCycles;
        /** Cycles that fell due and were not paid. */
        public int arrears;
        public boolean autopay;
        /** The city's price factor when the home was registered, in percent. */
        public int factorPct = 100;
        public final java.util.Set<String> upgrades = new LinkedHashSet<>();
        public UUID signedBy;
        public long signedAt;
        /** The last receipts: rent, payments, upgrades, moves. A statement the screen can show without the Crew's history. */
        public final ArrayDeque<Entry> receipts = new ArrayDeque<>();

        public boolean rented() { return tenure == Tenure.RENT; }
        public boolean billed() { return tenure != Tenure.NONE && charge > 0; }
        /** Credits owed right now: the cycles due or unpaid at the stated charge. */
        public long owedCredits() { return (long) (arrears + due) * charge; }
        public Standing standing(Rules rules) { return PropertyRules.standing(arrears, rules); }

        void receipt(long now, UUID actor, String actorName, String kind, long amount, String note) {
            receipts.addFirst(new Entry(now, actor, actorName == null ? "" : actorName, kind, amount, note == null ? "" : note));
            while (receipts.size() > CrewRules.RECEIPTS_MAX) receipts.removeLast();
        }

        Residence(String id, String title, String kind, String dimension, String district, BlockPos min, BlockPos max, int price, int starterPrice) {
            this(id, title, kind, dimension, district, List.of(new Box(min, max)), price, starterPrice);
        }

        Residence(String id, String title, String kind, String dimension, String district, List<Box> boxes, int price, int starterPrice) {
            if (boxes.isEmpty()) throw new IllegalArgumentException("A home has at least one box");
            this.id = id; this.title = title; this.kind = kind; this.dimension = dimension; this.district = district;
            this.boxes = List.copyOf(boxes);
            this.min = this.boxes.get(0).min().immutable(); this.max = this.boxes.get(0).max().immutable(); this.price = price; this.starterPrice = starterPrice;
        }

        public boolean insideInterior(String dim, BlockPos p) {
            if (!dim.equals(dimension)) return false;
            for (Box b : boxes) if (b.inside(p)) return true;
            return false;
        }

        /** The interior plus the one-block shell of walls, floor and ceiling around each of its boxes. */
        public boolean insideShell(String dim, BlockPos p) {
            if (!dim.equals(dimension)) return false;
            for (Box b : boxes) if (b.inShell(p)) return true;
            return false;
        }

        /** Squared distance from a block position to the nearest block of the interior (0 inside). */
        public double distanceSqr(BlockPos p) {
            double best = Double.MAX_VALUE;
            for (Box b : boxes) best = Math.min(best, b.distanceSqr(p));
            return best;
        }

        /** The chunks the interior lies in (not the shell): what a count of the home's belongings has to look through. */
        public List<ChunkPos> chunks() {
            java.util.LinkedHashSet<ChunkPos> out = new java.util.LinkedHashSet<>();
            for (Box b : boxes)
                for (int cx = b.min().getX() >> 4; cx <= b.max().getX() >> 4; cx++)
                    for (int cz = b.min().getZ() >> 4; cz <= b.max().getZ() >> 4; cz++) out.add(new ChunkPos(cx, cz));
            return List.copyOf(out);
        }

        /** The blocks of the interior, every box once. */
        public long volume() { long v = 0; for (Box b : boxes) v += b.volume(); return v; }

        public boolean owned() { return ownerType != OwnerType.NONE; }
        public boolean sameGeometry(String dim, BlockPos lo, BlockPos hi) { return sameGeometry(dim, List.of(new Box(lo, hi))); }
        public boolean sameGeometry(String dim, List<Box> other) { return dim.equals(dimension) && boxes.equals(other); }
    }

    /** LEASE_WALLET is the purchase of a home (the old word is kept in the file); RENT_WALLET signs a tenancy; PAY_WALLET pays cycles of rent or upkeep; UPGRADE_WALLET fits an improvement. */
    public enum Kind { DEPOSIT, WITHDRAW, LEASE_WALLET, CLAIM_OWED, RENT_WALLET, PAY_WALLET, UPGRADE_WALLET }

    /**
     * The one in-flight transaction a player may have. {@code before}/{@code after} are the wallet balances on either side of the change; after a
     * crash the wallet is compared with them to learn how far the transaction got (see {@link CrewService#recover}).
     */
    public record Pending(UUID tx, UUID player, Kind kind, UUID crew, long amount, double before, double after, String residence, boolean starter, int count, String detail) {
        public Pending(UUID tx, UUID player, Kind kind, UUID crew, long amount, double before, double after, String residence, boolean starter) {
            this(tx, player, kind, crew, amount, before, after, residence, starter, 0, "");
        }
    }

    /**
     * The Crew bank's share of one exchange purchase (D-089), taken out of the bank at once and held until the purchase is decided. The decision is the
     * work-order journal's prepared record, which names this hold: it commits the hold (the credits are spent) or, never having been written, releases it
     * (the credits go back to the bank). No credit of a hold ever reaches a wallet, so a purchase that fails cannot leave the bank's money with the buyer.
     */
    public record Hold(UUID tx, UUID player, UUID crew, long amount, String note) {}

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
    final Map<UUID, Hold> holds = new LinkedHashMap<>();
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
                int version = root.getInt("schema_version");
                if (version < 1 || version > SCHEMA) throw new IOException("Unsupported Crew ledger schema " + version + " (this build reads 1 to " + SCHEMA + "; restore the backup or use the build that wrote it)");
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
    public Hold holdFor(UUID tx) { return holds.get(tx); }
    public List<Hold> allHolds() { return List.copyOf(holds.values()); }
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

    /** Credits taken out of a bank or an owed balance but not yet confirmed in a wallet or spent (withdrawals, claims and purchase holds in flight). */
    public long totalInFlight() {
        long total = 0;
        for (Pending p : pending.values()) if (p.kind() == Kind.WITHDRAW || p.kind() == Kind.CLAIM_OWED) total += p.amount();
        for (Hold h : holds.values()) total += h.amount();
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

    /** True while a money transfer or a purchase hold of this Crew (or of one of its members) is waiting to be decided. */
    private boolean unresolved(Crew crew) {
        for (Pending p : pending.values()) if (crew.id.equals(p.crew()) || crew.members.containsKey(p.player())) return true;
        for (Hold h : holds.values()) if (crew.id.equals(h.crew()) || crew.members.containsKey(h.player())) return true;
        return false;
    }

    private void requireNoPending(Crew crew) {
        require(!unresolved(crew), "A money transfer is still being recovered. Try again in a moment.");
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
            r.autopay = false;   // the Crew bank is gone: the founder pays by hand (or switches automatic payment on from their wallet)
            r.receipt(now(), actor, actorName, "TRANSFER", 0, "the Crew disbanded; " + r.title + " is the founder's");
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

    // ---------------------------------------------------------------------------------------------------- purchases paid by the bank (D-089)

    /**
     * What the bank may pay of a purchase of {@code cost} credits for this player right now, and why not when it may not. Read-only: nothing moves.
     * The bank pays only under fully shared finances (every member consented) and only to a member who holds the SPEND right, so a member without it
     * cannot spend the Crew's money by buying things.
     */
    public record Share(long credits, String reason) {}

    public Share purchaseShare(UUID player, long cost) {
        Crew crew = crewOf(player);
        if (crew == null || cost < 1) return new Share(0, "");
        if (crew.effectiveMode() != Mode.SHARED) return new Share(0, "");
        if (!crew.can(player, Perm.SPEND)) return new Share(0, "The Crew bank is not available to you: you are not allowed to spend Crew money.");
        if (crew.bank < 1) return new Share(0, "");
        if (unresolved(crew) || pending.containsKey(player)) return new Share(0, "A Crew money transfer is still being recovered.");
        return new Share(Math.min(crew.bank, cost), "");
    }

    /**
     * Purchase step 1: takes {@code amount} out of the bank and keeps it as a hold named by {@code tx}. Everything is checked again here (the Crew's agreement,
     * the right to spend, the balance) so a stale plan cannot overdraw the bank. The credits stay in the ledger: they are not credited to any wallet.
     */
    public Hold reservePurchase(UUID tx, UUID player, long amount, String note) {
        checkHealthy();
        Crew crew = requireCrew(player);
        require(tx != null && !seen.contains(tx) && !holds.containsKey(tx), "That purchase was already processed.");
        require(amount >= 1 && amount <= CrewRules.MAX_BANK, "Bad purchase amount.");
        require(crew.effectiveMode() == Mode.SHARED, "Shared finances are not in force.");
        require(crew.can(player, Perm.SPEND), "You are not allowed to spend Crew money.");
        require(crew.bank >= amount, "The bank only holds " + crew.bank + " credits.");
        requireNoPending(crew);
        require(!pending.containsKey(player), "Finish the money recovery first.");
        crew.bank -= amount;
        Hold hold = new Hold(tx, player, crew.id, amount, note == null ? "" : note);
        holds.put(tx, hold);
        persist();
        return hold;
    }

    /** Purchase step 3: the purchase is decided, so the held credits are spent. Idempotent: returns false when there is nothing to commit (already done). */
    public boolean commitPurchase(UUID tx, String playerName) {
        checkHealthy();
        Hold hold = holds.get(tx);
        if (hold == null) return false;
        Crew crew = crews.get(hold.crew());
        holds.remove(tx);
        remember(tx);
        if (crew != null) {
            Member actor = crew.members.get(hold.player());
            crew.record(now(), hold.player(), actor != null ? actor.name : playerName, "PURCHASE", hold.amount(), hold.note());
        }
        persist();
        return true;
    }

    /** The purchase was never decided (its prepared record does not exist): the held credits go back where they came from. Idempotent. */
    public boolean releasePurchase(UUID tx) {
        checkHealthy();
        Hold hold = holds.get(tx);
        if (hold == null) return false;
        Crew crew = crews.get(hold.crew());
        if (crew != null) crew.bank = Math.addExact(crew.bank, hold.amount());
        else owed.merge(hold.player(), hold.amount(), Math::addExact); // the Crew ended meanwhile: the credits are owed back
        holds.remove(tx);
        persist();
        return true;
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

    public static final int MAX_PERSONAL_HOMES = 3, MAX_CREW_HOMES = 4;

    private Residence requireLeasable(String id, UUID forPlayerOrCrew, boolean crew) {
        Residence r = residences.get(id);
        require(r != null, "That home does not exist.");
        require(!r.owned(), "That home is already taken.");
        require(r.reservedBy == null, "Somebody is leasing that home right now.");
        if (crew) require(residencesOf(OwnerType.CREW, forPlayerOrCrew).size() < MAX_CREW_HOMES, "The Crew already holds " + MAX_CREW_HOMES + " homes.");
        else require(residencesOf(OwnerType.PLAYER, forPlayerOrCrew).size() < MAX_PERSONAL_HOMES, "You already hold " + MAX_PERSONAL_HOMES + " homes of your own.");
        return r;
    }

    /** Buys a home for the Crew from the bank: one atomic ledger write, nothing to recover. Returns the price. (The old word, lease, is kept for homes with no listing.) */
    public int leaseForCrewFromBank(UUID tx, UUID actor, String actorName, String residenceId) { return buyForCrewFromBank(tx, actor, actorName, residenceId, null); }

    /** {@code terms} is the home's listing (the price is the listed price in this city, with upkeep to follow) or null for a home with no listing (the registered price, no upkeep). */
    public int buyForCrewFromBank(UUID tx, UUID actor, String actorName, String residenceId, Terms terms) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        requireFreshTx(tx, actor);
        require(crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to buy homes for the Crew.");
        require(crew.can(actor, Perm.SPEND), "You are not allowed to spend Crew money.");
        require(crew.effectiveMode() != Mode.SEPARATE, "This Crew has no bank. Buy with your own credits instead.");
        Residence r = requireLeasable(residenceId, crew.id, true);
        require(terms == null || terms.canBuy(), "That home is not for sale. It can only be rented.");
        int price = terms != null ? buyPrice(r, terms) : priceFor(actor, r);
        boolean starter = terms == null && usesStarter(actor, r);
        require(crew.bank >= price, "The bank holds " + crew.bank + " credits. This home costs " + price + ".");
        requireNoPending(crew);
        crew.bank -= price;
        if (starter) starterUsed.add(actor);
        sign(r, Tenure.OWN, OwnerType.CREW, crew.id, actor, actorName, terms == null ? 0 : PropertyRules.scaled(terms.upkeep(), r.factorPct), 0, price, 0, false);
        crew.record(now(), actor, actorName, "LEASE", price, r.title);
        remember(tx);
        persist();
        return price;
    }

    /** Purchase from a wallet, step 1: reserves the unit and records the price. Ownership is assigned by {@link #settle}. */
    public Pending beginLeaseFromWallet(UUID tx, UUID actor, boolean forCrew, String residenceId, double walletBefore) { return beginBuyFromWallet(tx, actor, forCrew, residenceId, walletBefore, null); }

    public Pending beginBuyFromWallet(UUID tx, UUID actor, boolean forCrew, String residenceId, double walletBefore, Terms terms) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Crew crew = forCrew ? requireCrew(actor) : crewOf(actor);
        if (forCrew) {
            require(crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to buy homes for the Crew.");
            requireNoPending(crew);
        }
        Residence r = requireLeasable(residenceId, forCrew ? crew.id : actor, forCrew);
        require(terms == null || terms.canBuy(), "That home is not for sale. It can only be rented.");
        int price = terms != null ? buyPrice(r, terms) : priceFor(actor, r);
        boolean starter = terms == null && usesStarter(actor, r);
        require(walletBefore >= price, "You only have " + (long) Math.floor(walletBefore) + " credits. This home costs " + price + ".");
        r.reservedBy = tx;
        Pending p = new Pending(tx, actor, Kind.LEASE_WALLET, forCrew ? crew.id : null, price, walletBefore, walletBefore - price, residenceId, starter, 0,
            "own:" + (terms == null ? 0 : PropertyRules.scaled(terms.upkeep(), r.factorPct)));
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
                    int upkeep = p.detail().startsWith("own:") ? Integer.parseInt(p.detail().substring(4)) : 0;
                    sign(r, Tenure.OWN, p.crew() != null ? OwnerType.CREW : OwnerType.PLAYER, p.crew() != null ? p.crew() : player, player, actorName, upkeep, 0, (int) p.amount(), 0, false);
                    if (p.starter()) starterUsed.add(player);
                    if (crew != null) crew.record(now, player, actorName, "LEASE", p.amount(), r.title);
                }
            }
            case RENT_WALLET -> {
                Residence r = residences.get(p.residence());
                if (r == null || !p.tx().equals(r.reservedBy) || (p.crew() != null && crew == null)) {
                    owed.merge(player, p.amount(), Math::addExact);
                } else {
                    r.reservedBy = null;
                    String[] parts = p.detail().split(":");
                    sign(r, Tenure.RENT, p.crew() != null ? OwnerType.CREW : OwnerType.PLAYER, p.crew() != null ? p.crew() : player, player, actorName, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), 0, 1, false);
                    if (crew != null) crew.record(now, player, actorName, "RENT", p.amount(), r.title);
                }
            }
            case PAY_WALLET -> {
                Residence r = residences.get(p.residence());
                boolean same = r != null && r.billed() && (p.crew() == null ? r.ownerType == OwnerType.PLAYER && player.equals(r.owner) : r.ownerType == OwnerType.CREW && p.crew().equals(r.owner));
                if (!same) {
                    owed.merge(player, p.amount(), Math::addExact);
                } else {
                    int cycles = Math.min(p.count(), Math.max(0, payableCycles(r, PropertyCatalog.INSTANCE.rules())));
                    applyPayment(r, cycles);
                    long used = (long) cycles * r.charge;
                    if (used < p.amount()) owed.merge(player, p.amount() - used, Math::addExact); // the home needed less than was taken
                    r.receipt(now, player, actorName, "PAID", used, cycles + " h from " + (actorName.isEmpty() ? "a wallet" : actorName + "'s wallet"));
                    if (crew != null) crew.record(now, player, actorName, r.rented() ? "RENT" : "UPKEEP", used, r.title + " (" + cycles + " h)");
                }
            }
            case UPGRADE_WALLET -> {
                Residence r = residences.get(p.residence());
                Upgrade up = PropertyCatalog.INSTANCE.upgrade(p.detail());
                boolean same = r != null && up != null && r.tenure == Tenure.OWN && !r.upgrades.contains(up.id())
                    && (p.crew() == null ? r.ownerType == OwnerType.PLAYER && player.equals(r.owner) : r.ownerType == OwnerType.CREW && p.crew().equals(r.owner));
                if (!same) {
                    owed.merge(player, p.amount(), Math::addExact);
                } else {
                    r.upgrades.add(up.id());
                    sunk += p.amount();   // the price actually paid (a tradesperson's discount is already in it), not the list price
                    r.receipt(now, player, actorName, "UPGRADE", p.amount(), up.title());
                    if (crew != null) crew.record(now, player, actorName, "UPGRADE", p.amount(), r.title + ": " + up.title());
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
            case LEASE_WALLET, RENT_WALLET -> {
                Residence r = residences.get(p.residence());
                if (r != null && p.tx().equals(r.reservedBy)) r.reservedBy = null;
            }
            case PAY_WALLET, UPGRADE_WALLET -> { }
        }
        pending.remove(player);
        persist();
    }

    // ------------------------------------------------------------------------------------------------------------ the property economy (D-099)

    /** People who have had the city's welcome stay in a motel (once each, so founding Crews and renting again buy nothing). */
    final Set<UUID> welcomeUsed = new LinkedHashSet<>();

    /**
     * Credits that have left circulation through homes since this ledger was created: rent and upkeep actually consumed, purchase prices (less what a sale returned),
     * upgrades, and the part of a deposit the city kept. With the credits in wallets, banks, owed payouts, deposits and prepaid hours it adds up to the credits that
     * entered, which the GameTests check after random play. (It can dip below zero when a home bought before this existed is sold back.)
     */
    public long sunk;

    /** Credits held inside homes right now: deposits and prepaid hours the city did not give. */
    public long heldInHomes() {
        long total = 0;
        for (Residence r : residences.values()) total += r.deposit + (long) Math.max(0, r.prepaid - r.freeCycles) * r.charge;
        return total;
    }

    /** One thing the billing clock did that someone should hear about. */
    public record Event(String residence, String kind, String detail) {}

    public boolean welcomeAvailable(UUID player, Terms t, Rules rules) {
        return t != null && "motel_room".equals(t.category()) && rules.welcomeMotelCycles() > 0 && !welcomeUsed.contains(player);
    }

    /** What signing a tenancy costs this player now: the deposit and the first cycle, or nothing when the city's welcome applies. */
    public record Quote(int deposit, int rent, int total, boolean welcome) {}

    public Quote rentQuote(UUID player, Residence r, Terms t, Rules rules) {
        require(t != null && t.canRent(), "That home is not for rent.");
        int rent = PropertyRules.scaled(t.rent(), r.factorPct), deposit = PropertyRules.scaled(t.deposit(), r.factorPct);
        if (welcomeAvailable(player, t, rules)) return new Quote(0, rent, 0, true);
        return new Quote(deposit, rent, deposit + rent, false);
    }

    public int buyPrice(Residence r, Terms t) { return PropertyRules.scaled(t.buyPrice(), r.factorPct); }

    /** Who may handle the money and the paperwork of a held home: the owner, or a Crew member who may manage property for a Crew home. */
    public boolean canManage(UUID actor, Residence r) {
        if (!r.owned()) return false;
        if (r.ownerType == OwnerType.PLAYER) return actor.equals(r.owner);
        Crew crew = crews.get(r.owner);
        return crew != null && crew.can(actor, Perm.MANAGE_PROPERTY);
    }

    /** The people who live there: the owner, or every member of the owning Crew. */
    public List<UUID> residentsOf(Residence r) {
        if (!r.owned()) return List.of();
        if (r.ownerType == OwnerType.PLAYER) return List.of(r.owner);
        Crew crew = crews.get(r.owner);
        return crew == null ? List.of() : List.copyOf(crew.members.keySet());
    }

    private Residence held(String id) {
        Residence r = residences.get(id);
        require(r != null, "That home does not exist.");
        require(r.owned(), "Nobody holds that home.");
        return r;
    }

    private void requireManager(UUID actor, Residence r) {
        require(canManage(actor, r), r.ownerType == OwnerType.CREW ? "Your role may not handle this home for the Crew." : "This is not your home.");
    }

    private Crew bankCrew(UUID actor, Residence r) {
        require(r.ownerType == OwnerType.CREW, "Only a Crew home is paid from the Crew bank.");
        Crew crew = crews.get(r.owner);
        require(crew != null && crew.members.containsKey(actor), "You are not in the Crew that holds this home.");
        require(crew.can(actor, Perm.SPEND) && crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to spend Crew money on property.");
        require(crew.effectiveMode() != Mode.SEPARATE, "This Crew has no bank. Pay with your own credits instead.");
        requireNoPending(crew);
        return crew;
    }

    /** Fixes the terms of a new tenancy or ownership on a home. The caller has already taken the money. */
    private void sign(Residence r, Tenure tenure, OwnerType type, UUID owner, UUID actor, String actorName, int charge, int deposit, int paidPrice, int prepaid, boolean welcome) {
        r.ownerType = type; r.owner = owner; r.since = now();
        r.tenure = tenure; r.legacy = false;
        r.charge = charge; r.deposit = deposit; r.paidPrice = paidPrice;
        r.progress = 0; r.due = 0; r.arrears = 0; r.prepaid = prepaid; r.freeCycles = welcome ? prepaid : 0; r.autopay = false;
        r.upgrades.clear(); r.receipts.clear(); r.claims.clear(); r.guests.clear();
        r.signedBy = actor; r.signedAt = now();
        if (tenure == Tenure.OWN) sunk += paidPrice;
        if (welcome) welcomeUsed.add(actor);
        r.receipt(now(), actor, actorName, tenure == Tenure.RENT ? "RENT" : "BUY", deposit + (tenure == Tenure.RENT ? charge : paidPrice), tenure == Tenure.RENT
            ? (welcome ? "welcome stay: " + prepaid + " hours free" : "deposit " + deposit + " and the first hour") : "bought");
    }

    /** Clears a tenancy: the home is nobody's again. Claims and guests go with it; fixtures stay and stored items must have been taken out first. */
    private void release(Residence r) {
        r.ownerType = OwnerType.NONE; r.owner = null; r.since = 0; r.reservedBy = null;
        r.tenure = Tenure.NONE; r.legacy = false; r.charge = 0; r.deposit = 0; r.paidPrice = 0; r.progress = 0; r.due = 0; r.prepaid = 0; r.freeCycles = 0; r.arrears = 0; r.autopay = false;
        r.upgrades.clear(); r.receipts.clear(); r.claims.clear(); r.guests.clear(); r.signedBy = null; r.signedAt = 0;
    }

    private void applyPayment(Residence r, int cycles) {
        PropertyRules.Applied a = PropertyRules.apply(cycles, r.due, r.arrears);
        r.due -= a.due(); r.arrears -= a.arrears(); r.prepaid += a.prepaid();
        sunk += (long) (a.due() + a.arrears()) * r.charge;   // the hours already lived are consumed; the rest is held as prepaid
    }

    /** Credits go to a person as an owed payout (claimed into their wallet by the protocol that already survives crashes) or, for a Crew home, into the bank. */
    private void refund(Residence r, UUID actor, int credits, String kind, String note) {
        if (credits <= 0) return;
        if (r.ownerType == OwnerType.CREW) {
            Crew crew = crews.get(r.owner);
            if (crew != null && crew.effectiveMode() != Mode.SEPARATE) { crew.bank = Math.addExact(crew.bank, credits); crew.record(now(), actor, nameOf(crew, actor), kind, credits, note); return; }
            owed.merge(crew != null ? crew.founder : actor, (long) credits, Math::addExact);
            return;
        }
        owed.merge(r.owner, (long) credits, Math::addExact);
    }

    private String nameOf(Crew crew, UUID id) { Member m = crew.members.get(id); return m == null ? "" : m.name; }

    private void logCrew(Residence r, UUID actor, String kind, long amount, String note) {
        if (r.ownerType != OwnerType.CREW) return;
        Crew crew = crews.get(r.owner);
        if (crew != null) crew.record(now(), actor, nameOf(crew, actor), kind, amount, note);
    }

    // ---- taking a home

    /** A Crew rents a home with the bank's credits: one atomic write. The welcome stay (motels only, once per person) costs nothing. Returns the credits paid. */
    public int rentForCrewFromBank(UUID tx, UUID actor, String actorName, String residenceId, Terms terms, Rules rules) {
        checkHealthy();
        Crew crew = requireCrew(actor);
        requireFreshTx(tx, actor);
        require(crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to rent homes for the Crew.");
        Residence r = requireLeasable(residenceId, crew.id, true);
        Quote q = rentQuote(actor, r, terms, rules);
        if (q.total() > 0) {
            require(crew.can(actor, Perm.SPEND), "You are not allowed to spend Crew money.");
            require(crew.effectiveMode() != Mode.SEPARATE, "This Crew has no bank. Rent with your own credits instead.");
            require(crew.bank >= q.total(), "The bank holds " + crew.bank + " credits. This home needs " + q.total() + " (deposit " + q.deposit() + " and the first hour " + q.rent() + ").");
            requireNoPending(crew);
            crew.bank -= q.total();
        }
        sign(r, Tenure.RENT, OwnerType.CREW, crew.id, actor, actorName, q.rent(), q.deposit(), 0, q.welcome() ? rules.welcomeMotelCycles() : 1, q.welcome());
        crew.record(now(), actor, actorName, "RENT", q.total(), r.title);
        remember(tx);
        persist();
        return q.total();
    }

    /** Rent paid from a wallet, step 1. A free welcome stay needs no wallet at all and is done here ({@code null} is returned). */
    public Pending beginRentFromWallet(UUID tx, UUID actor, String actorName, boolean forCrew, String residenceId, double walletBefore, Terms terms, Rules rules) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Crew crew = forCrew ? requireCrew(actor) : crewOf(actor);
        if (forCrew) { require(crew.can(actor, Perm.MANAGE_PROPERTY), "You are not allowed to rent homes for the Crew."); requireNoPending(crew); }
        Residence r = requireLeasable(residenceId, forCrew ? crew.id : actor, forCrew);
        Quote q = rentQuote(actor, r, terms, rules);
        if (q.total() == 0) {
            sign(r, Tenure.RENT, forCrew ? OwnerType.CREW : OwnerType.PLAYER, forCrew ? crew.id : actor, actor, actorName, q.rent(), 0, 0, q.welcome() ? rules.welcomeMotelCycles() : 1, q.welcome());
            if (forCrew) crew.record(now(), actor, actorName, "RENT", 0, r.title);
            remember(tx);
            persist();
            return null;
        }
        require(walletBefore >= q.total(), "You only have " + (long) Math.floor(walletBefore) + " credits. This home needs " + q.total() + " (deposit " + q.deposit() + " and the first hour " + q.rent() + ").");
        r.reservedBy = tx;
        Pending p = new Pending(tx, actor, Kind.RENT_WALLET, forCrew ? crew.id : null, q.total(), walletBefore, walletBefore - q.total(), residenceId, false, q.rent(), "rent:" + q.rent() + ":" + q.deposit());
        pending.put(actor, p);
        remember(tx);
        persist();
        return p;
    }

    // ---- paying

    /** Payable cycles right now: what is owed plus the room under the prepayment cap. */
    public int payableCycles(Residence r, Rules rules) { return PropertyRules.payableCycles(r.due, r.arrears, r.prepaid, rules); }

    /** A Crew pays rent or upkeep from the bank: one atomic write. */
    public int payFromBank(UUID tx, UUID actor, String actorName, String residenceId, int cycles, Rules rules) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Residence r = held(residenceId);
        Crew crew = bankCrew(actor, r);
        require(r.billed(), "There is nothing to pay on this home.");
        require(cycles >= 1 && cycles <= payableCycles(r, rules), "You can pay between 1 and " + payableCycles(r, rules) + " hours on this home.");
        long credits = (long) cycles * r.charge;
        require(crew.bank >= credits, "The bank holds " + crew.bank + " credits. " + cycles + " hour" + (cycles == 1 ? "" : "s") + " cost" + (cycles == 1 ? "s " : " ") + credits + ".");
        crew.bank -= credits;
        applyPayment(r, cycles);
        r.receipt(now(), actor, actorName, "PAID", credits, cycles + " h from the Crew bank");
        crew.record(now(), actor, actorName, r.rented() ? "RENT" : "UPKEEP", credits, r.title + " (" + cycles + " h)");
        remember(tx);
        persist();
        return (int) credits;
    }

    /** Rent or upkeep paid from a wallet, step 1. Anyone in the Crew may pay a Crew home from their own wallet; only the owner pays a personal one. */
    public Pending beginPayFromWallet(UUID tx, UUID actor, String residenceId, int cycles, double walletBefore, Rules rules) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Residence r = held(residenceId);
        if (r.ownerType == OwnerType.PLAYER) require(actor.equals(r.owner), "This is not your home.");
        else { Crew crew = crews.get(r.owner); require(crew != null && crew.members.containsKey(actor), "You are not in the Crew that holds this home."); requireNoPending(crew); }
        require(r.billed(), "There is nothing to pay on this home.");
        require(cycles >= 1 && cycles <= payableCycles(r, rules), "You can pay between 1 and " + payableCycles(r, rules) + " hours on this home.");
        long credits = (long) cycles * r.charge;
        require(walletBefore >= credits, "You only have " + (long) Math.floor(walletBefore) + " credits. " + cycles + " hour" + (cycles == 1 ? "" : "s") + " cost" + (cycles == 1 ? "s " : " ") + credits + ".");
        Pending p = new Pending(tx, actor, Kind.PAY_WALLET, r.ownerType == OwnerType.CREW ? r.owner : null, credits, walletBefore, walletBefore - credits, residenceId, false, cycles, "");
        pending.put(actor, p);
        remember(tx);
        persist();
        return p;
    }

    /** Automatic payment for a home: from the Crew bank (done in the clock) or from the owner's wallet (done by the service). */
    public void setAutopay(UUID actor, String actorName, String residenceId, boolean on) {
        checkHealthy();
        Residence r = held(residenceId);
        requireManager(actor, r);
        require(r.billed(), "There is nothing to pay on this home.");
        if (on && r.ownerType == OwnerType.CREW) {
            Crew crew = crews.get(r.owner);
            require(crew.effectiveMode() != Mode.SEPARATE, "This Crew has no bank to pay from.");
            require(crew.can(actor, Perm.SPEND), "You are not allowed to spend Crew money.");
        }
        if (r.autopay == on) return;
        r.autopay = on;
        r.receipt(now(), actor, nameOfAny(actor, r), "AUTOPAY", 0, on ? "on" : "off");
        logCrew(r, actor, "AUTOPAY", 0, r.title + (on ? " paid from the bank" : " paid by hand"));
        persist();
    }

    private String nameOfAny(UUID actor, Residence r) {
        if (r.ownerType == OwnerType.CREW) { Crew crew = crews.get(r.owner); return crew == null ? "" : nameOf(crew, actor); }
        return "";
    }

    // ---- the clock

    private boolean anyOnline(Residence r, Set<UUID> online) {
        if (r.ownerType == OwnerType.PLAYER) return online.contains(r.owner);
        Crew crew = crews.get(r.owner);
        if (crew == null) return false;
        for (UUID member : crew.members.keySet()) if (online.contains(member)) return true;
        return false;
    }

    /**
     * Advances every held home's billing clock by {@code ticks} of ACTIVE time: a home's clock moves only if one of its residents is in {@code online}, and
     * once however many are. Cycles that fall due are settled in order: prepaid hours first, then the Crew bank if automatic payment is on, otherwise they
     * become arrears (a personal home with automatic payment from a wallet keeps them as {@code due} for the service to try, because only the service may touch a
     * wallet). Nothing here deletes, empties or re-lets a home. Returns what the residents should be told.
     */
    public List<Event> advance(Set<UUID> online, long ticks, Rules rules) {
        checkHealthy();
        List<Event> events = new ArrayList<>();
        boolean structural = false, progressed = false;
        long cycle = rules.cycleTicks();
        for (Residence r : residences.values()) {
            if (!r.billed() || !anyOnline(r, online)) continue;
            Standing before = r.standing(rules);
            r.progress += ticks;
            progressed = true;
            while (r.progress >= cycle) { r.progress -= cycle; r.due++; structural = true; }
            while (r.due > 0) {
                if (r.prepaid > 0) {
                    r.prepaid--; r.due--;
                    if (r.freeCycles > 0) r.freeCycles--; else sunk += r.charge;
                    r.receipt(now(), null, "", "RENT", r.charge, "hour paid in advance");
                    events.add(new Event(r.id, "PAID_AHEAD", String.valueOf(r.prepaid)));
                    continue;
                }
                if (r.autopay && r.ownerType == OwnerType.CREW) {
                    Crew crew = crews.get(r.owner);
                    if (crew != null && crew.effectiveMode() != Mode.SEPARATE && !unresolved(crew) && crew.bank >= r.charge) {
                        crew.bank -= r.charge; r.due--; sunk += r.charge;
                        r.receipt(now(), null, "", "PAID", r.charge, "automatic, from the Crew bank");
                        crew.record(now(), null, "", r.rented() ? "RENT" : "UPKEEP", r.charge, r.title + " (automatic)");
                        events.add(new Event(r.id, "PAID_BANK", String.valueOf(r.charge)));
                        continue;
                    }
                }
                if (r.autopay && r.ownerType == OwnerType.PLAYER) break;   // the service tries the owner's wallet, then calls missDue
                r.due--; r.arrears++;
                events.add(new Event(r.id, "MISSED", String.valueOf(r.arrears)));
            }
            Standing after = r.standing(rules);
            if (after != before) events.add(new Event(r.id, "STANDING", after.name()));
        }
        if (structural) persist();
        else if (progressed) dirtyProgress = true;
        return events;
    }

    private boolean dirtyProgress;

    /** Writes the clock to disk when only progress changed. Called now and then and when the last resident leaves; losing a few minutes of progress in a crash favours the tenant. */
    public void flushProgress() {
        if (!dirtyProgress || faulted) return;
        dirtyProgress = false;
        persist();
    }

    /** The service could not take a cycle from the owner's wallet (offline, not enough credits): it becomes arrears. */
    public Event missDue(String residenceId, Rules rules) {
        checkHealthy();
        Residence r = residences.get(residenceId);
        if (r == null || r.due < 1) return null;
        Standing before = r.standing(rules);
        r.due--; r.arrears++;
        persist();
        return new Event(r.id, before != r.standing(rules) ? "STANDING" : "MISSED", before != r.standing(rules) ? r.standing(rules).name() : String.valueOf(r.arrears));
    }

    // ---- leaving

    /**
     * A tenant gives the home back. The deposit returns, less what is owed; anything owed beyond the deposit is forgiven (no debt follows a person out of a
     * home, so being short on rent is never a spiral). The caller has checked that no container in the home still holds items: stored belongings are never
     * thrown away. Fixtures stay.
     */
    public int moveOut(UUID actor, String actorName, String residenceId) {
        checkHealthy();
        Residence r = held(residenceId);
        requireManager(actor, r);
        require(r.rented(), "You own this home. Sell it back instead.");
        if (r.ownerType == OwnerType.CREW) requireNoPending(crews.get(r.owner));
        int depositBack = PropertyRules.depositBack(r.deposit, (int) Math.min(Integer.MAX_VALUE, r.owedCredits()));
        int unused = Math.max(0, r.prepaid - r.freeCycles) * r.charge;   // hours paid ahead and not lived come back; the welcome hours were the city's gift
        int back = depositBack + unused;
        sunk += r.deposit - depositBack;                                   // the part of the deposit that covered what was owed
        String title = r.title;
        refund(r, actor, back, "DEPOSIT_BACK", title + " (moved out)");
        if (r.ownerType == OwnerType.CREW) logCrew(r, actor, "MOVE_OUT", back, title);
        release(r);
        persist();
        return back;
    }

    /** An owner sells the home back to the city for the stated percent of what they paid, less what is owed. The caller has checked the containers are empty. */
    public int sellBack(UUID actor, String actorName, String residenceId, Rules rules) {
        checkHealthy();
        Residence r = held(residenceId);
        requireManager(actor, r);
        require(r.tenure == Tenure.OWN, "You rent this home. Move out instead.");
        if (r.ownerType == OwnerType.CREW) requireNoPending(crews.get(r.owner));
        int sale = PropertyRules.sellBack(r.paidPrice, (int) Math.min(Integer.MAX_VALUE, r.owedCredits()), rules);
        int unused = Math.max(0, r.prepaid - r.freeCycles) * r.charge;
        int back = sale + unused;
        sunk -= sale;                                                      // what the city pays back leaves the price it once took
        String title = r.title;
        refund(r, actor, back, "SELL_BACK", title);
        if (r.ownerType == OwnerType.CREW) logCrew(r, actor, "SELL_BACK", back, title);
        release(r);
        persist();
        return back;
    }

    // ---- improvements

    private void requireUpgradeable(Residence r, Upgrade up, Rules rules) {
        require(r.tenure == Tenure.OWN, "Only a home you own can be improved. Rent is for living in a home as it is.");
        require(r.standing(rules) != Standing.LAPSED, "Pay what is owed on this home first.");
        require(!r.upgrades.contains(up.id()), up.title() + " is already fitted.");
    }

    public int upgradeFromBank(UUID tx, UUID actor, String actorName, String residenceId, Upgrade up, Rules rules) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Residence r = held(residenceId);
        Crew crew = bankCrew(actor, r);
        requireUpgradeable(r, up, rules);
        require(crew.bank >= up.cost(), "The bank holds " + crew.bank + " credits. " + up.title() + " costs " + up.cost() + ".");
        crew.bank -= up.cost();
        sunk += up.cost();
        r.upgrades.add(up.id());
        r.receipt(now(), actor, actorName, "UPGRADE", up.cost(), up.title());
        crew.record(now(), actor, actorName, "UPGRADE", up.cost(), r.title + ": " + up.title());
        remember(tx);
        persist();
        return up.cost();
    }

    public Pending beginUpgradeFromWallet(UUID tx, UUID actor, String residenceId, Upgrade up, double walletBefore, Rules rules) {
        checkHealthy();
        requireFreshTx(tx, actor);
        Residence r = held(residenceId);
        requireManager(actor, r);
        if (r.ownerType == OwnerType.CREW) requireNoPending(crews.get(r.owner));
        requireUpgradeable(r, up, rules);
        require(walletBefore >= up.cost(), "You only have " + (long) Math.floor(walletBefore) + " credits. " + up.title() + " costs " + up.cost() + ".");
        Pending p = new Pending(tx, actor, Kind.UPGRADE_WALLET, r.ownerType == OwnerType.CREW ? r.owner : null, up.cost(), walletBefore, walletBefore - up.cost(), residenceId, false, 0, up.id());
        pending.put(actor, p);
        remember(tx);
        persist();
        return p;
    }

    // ---- who holds it

    /** A member hands one of their own homes to their Crew. Nothing owed travels with it (a debt cannot be passed on), and the Crew must have room. */
    public void transferToCrew(UUID actor, String actorName, String residenceId) {
        checkHealthy();
        Residence r = held(residenceId);
        require(r.ownerType == OwnerType.PLAYER && actor.equals(r.owner), "You can hand over only a home of your own.");
        Crew crew = requireCrew(actor);
        require(crew.can(actor, Perm.MANAGE_PROPERTY), "Your role may not take homes for the Crew.");
        requireNoPending(crew);
        require(residencesOf(OwnerType.CREW, crew.id).size() < MAX_CREW_HOMES, "The Crew already holds " + MAX_CREW_HOMES + " homes.");
        require(r.arrears == 0 && r.due == 0, "Pay what is owed on this home first: a debt cannot be handed on.");
        r.ownerType = OwnerType.CREW; r.owner = crew.id; r.guests.clear(); r.autopay = false;
        r.receipt(now(), actor, actorName, "TRANSFER", 0, "to the Crew " + crew.name);
        crew.record(now(), actor, actorName, "TRANSFER", 0, r.title + " joined the Crew");
        persist();
    }

    /** The founder takes a Crew home back as a personal one. */
    public void transferToFounder(UUID actor, String actorName, String residenceId) {
        checkHealthy();
        Residence r = held(residenceId);
        require(r.ownerType == OwnerType.CREW, "That is not a Crew home.");
        Crew crew = crews.get(r.owner);
        require(crew != null && crew.roleOf(actor) == Role.FOUNDER, "Only the founder can take a Crew home back.");
        requireNoPending(crew);
        require(residencesOf(OwnerType.PLAYER, actor).size() < MAX_PERSONAL_HOMES, "You already hold " + MAX_PERSONAL_HOMES + " homes of your own.");
        require(r.arrears == 0 && r.due == 0, "Pay what is owed on this home first.");
        r.ownerType = OwnerType.PLAYER; r.owner = actor; r.guests.clear(); r.autopay = false;
        r.receipt(now(), actor, actorName, "TRANSFER", 0, "to " + actorName);
        crew.record(now(), actor, actorName, "TRANSFER", 0, r.title + " left the Crew");
        persist();
    }

    // ------------------------------------------------------------------------------------------------------------ residences

    /** Registers (or refreshes) a leasable region. A unit that someone holds keeps its owner; a changed geometry under an owner is refused. */
    public Residence registerResidence(String id, String title, String kind, String dimension, String district, BlockPos min, BlockPos max, int price, int starterPrice) {
        return registerResidence(id, title, kind, dimension, district, min, max, price, starterPrice, 100);
    }

    /** {@code factorPct} is the city's price factor at the home's position (100 outside every city circle); it applies to rent, deposit and purchase price. */
    public Residence registerResidence(String id, String title, String kind, String dimension, String district, BlockPos min, BlockPos max, int price, int starterPrice, int factorPct) {
        return registerResidence(id, title, kind, dimension, district, List.of(new Box(min, max)), price, starterPrice, factorPct);
    }

    /** A home of one or more interior boxes (D-206). No box may reach into the shell of another home's box; the boxes of one home may touch and share their shells. */
    public Residence registerResidence(String id, String title, String kind, String dimension, String district, List<Box> boxes, int price, int starterPrice, int factorPct) {
        checkHealthy();
        require(factorPct >= 50 && factorPct <= 200, "Bad price factor");
        require(id.matches("[a-z0-9_:.\\-/]{3,64}"), "Bad residence id " + id);
        require(price >= 1 && price <= 1_000_000 && starterPrice <= price, "Bad residence price");
        require(!boxes.isEmpty() && boxes.size() <= MAX_BOXES, "A home has 1 to " + MAX_BOXES + " boxes");
        List<Box> geometry = List.copyOf(boxes);
        Residence old = residences.get(id);
        if (old != null) {
            if (old.sameGeometry(dimension, geometry)) {
                if (!old.owned() && old.factorPct != factorPct) { old.factorPct = factorPct; persist(); }
                return old;
            }
            require(!old.owned() && old.reservedBy == null, "Residence " + id + " is held by somebody and cannot move.");
        }
        for (Residence other : residences.values()) {
            if (other.id.equals(id) || !other.dimension.equals(dimension)) continue;
            // Two shells may touch (neighbouring flats share a wall), but no interior may reach into another unit's shell.
            for (Box mine : geometry) for (Box theirs : other.boxes) {
                require(!boxesIntersect(mine.min().getX() - 1, mine.min().getY() - 1, mine.min().getZ() - 1, mine.max().getX() + 1, mine.max().getY() + 1, mine.max().getZ() + 1,
                        theirs.min().getX(), theirs.min().getY(), theirs.min().getZ(), theirs.max().getX(), theirs.max().getY(), theirs.max().getZ())
                    && !boxesIntersect(theirs.min().getX() - 1, theirs.min().getY() - 1, theirs.min().getZ() - 1, theirs.max().getX() + 1, theirs.max().getY() + 1, theirs.max().getZ() + 1,
                        mine.min().getX(), mine.min().getY(), mine.min().getZ(), mine.max().getX(), mine.max().getY(), mine.max().getZ()), "Residence " + id + " overlaps " + other.id);
            }
        }
        Residence r = new Residence(id, title, kind, dimension, district, geometry, price, starterPrice);
        r.factorPct = factorPct;
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
        int allowedClaims = PropertyService.privateClaims(r);
        require(mine < allowedClaims, "You can keep " + allowedClaims + " private container" + (allowedClaims == 1 ? "" : "s") + " in this home.");
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

    /** True when any registered home (interior or shell) lies within {@code radius} blocks of {@code pos}: a cheap test before the guard does heavier work (pistons). */
    public boolean residenceNear(String dimension, BlockPos pos, int radius) {
        if (chunkIndex.isEmpty()) return false;
        for (int cx = (pos.getX() - radius) >> 4; cx <= (pos.getX() + radius) >> 4; cx++) {
            for (int cz = (pos.getZ() - radius) >> 4; cz <= (pos.getZ() + radius) >> 4; cz++) {
                List<Residence> list = chunkIndex.get(chunkKey(cx, cz));
                if (list != null) for (Residence r : list) if (r.dimension.equals(dimension)) return true;
            }
        }
        return false;
    }

    private static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private void reindex() {
        chunkIndex.clear();
        for (Residence r : residences.values()) {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Box b : r.boxes)
                for (int cx = (b.min().getX() - 1) >> 4; cx <= (b.max().getX() + 1) >> 4; cx++)
                    for (int cz = (b.min().getZ() - 1) >> 4; cz <= (b.max().getZ() + 1) >> 4; cz++)
                        if (seen.add(chunkKey(cx, cz))) chunkIndex.computeIfAbsent(chunkKey(cx, cz), key -> new ArrayList<>()).add(r);
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
        ListTag welcome = new ListTag();
        for (UUID id : welcomeUsed) welcome.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        root.put("welcome_used", welcome);
        root.putLong("sunk", sunk);
        ListTag pendingList = new ListTag();
        for (Pending p : pending.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("tx", p.tx()); t.putUUID("player", p.player()); t.putString("kind", p.kind().name()); if (p.crew() != null) t.putUUID("crew", p.crew());
            t.putLong("amount", p.amount()); t.putDouble("before", p.before()); t.putDouble("after", p.after()); t.putString("residence", p.residence()); t.putBoolean("starter", p.starter());
            t.putInt("count", p.count()); t.putString("detail", p.detail());
            pendingList.add(t);
        }
        root.put("pending", pendingList);
        ListTag holdList = new ListTag();
        for (Hold h : holds.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("tx", h.tx()); t.putUUID("player", h.player()); t.putUUID("crew", h.crew()); t.putLong("amount", h.amount()); t.putString("note", h.note());
            holdList.add(t);
        }
        root.put("holds", holdList);
        ListTag residenceList = new ListTag();
        for (Residence r : residences.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", r.id); t.putString("title", r.title); t.putString("kind", r.kind); t.putString("dimension", r.dimension); t.putString("district", r.district);
            t.putLong("min", r.min.asLong()); t.putLong("max", r.max.asLong()); t.putInt("price", r.price); t.putInt("starter_price", r.starterPrice);
            if (r.boxes.size() > 1) {
                // every box, the first one too; an older Core reads min and max alone, the first box (D-206)
                long[] corners = new long[r.boxes.size() * 2];
                for (int i = 0; i < r.boxes.size(); i++) { corners[2 * i] = r.boxes.get(i).min().asLong(); corners[2 * i + 1] = r.boxes.get(i).max().asLong(); }
                t.putLongArray("boxes", corners);
            }
            t.putString("owner_type", r.ownerType.name()); if (r.owner != null) t.putUUID("owner", r.owner); t.putLong("since", r.since);
            if (r.reservedBy != null) t.putUUID("reserved_by", r.reservedBy);
            ListTag guests = new ListTag();
            r.guests.forEach((id, until) -> { CompoundTag g = new CompoundTag(); g.putUUID("p", id); g.putLong("until", until); guests.add(g); });
            t.put("guests", guests);
            ListTag claims = new ListTag();
            r.claims.forEach((pos, id) -> { CompoundTag g = new CompoundTag(); g.putLong("pos", pos); g.putUUID("p", id); claims.add(g); });
            t.put("claims", claims);
            t.putString("tenure", r.tenure.name()); t.putBoolean("legacy", r.legacy); t.putInt("charge", r.charge); t.putInt("deposit", r.deposit); t.putInt("paid_price", r.paidPrice);
            t.putLong("progress", r.progress); t.putInt("due", r.due); t.putInt("prepaid", r.prepaid); t.putInt("free", r.freeCycles); t.putInt("arrears", r.arrears); t.putBoolean("autopay", r.autopay);
            t.putInt("factor", r.factorPct); t.putLong("signed_at", r.signedAt); if (r.signedBy != null) t.putUUID("signed_by", r.signedBy);
            ListTag upgradeList = new ListTag();
            for (String id : r.upgrades) upgradeList.add(StringTag.valueOf(id));
            t.put("upgrades", upgradeList);
            ListTag receiptList = new ListTag();
            for (Entry e : r.receipts) {
                CompoundTag g = new CompoundTag();
                g.putLong("t", e.time()); if (e.actor() != null) g.putUUID("a", e.actor()); g.putString("an", e.actorName()); g.putString("k", e.kind()); g.putLong("m", e.amount()); g.putString("n", e.note());
                receiptList.add(g);
            }
            t.put("receipts", receiptList);
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
        for (Tag t : root.getList("welcome_used", Tag.TAG_INT_ARRAY)) welcomeUsed.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
        sunk = root.getLong("sunk");
        for (Tag raw : root.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Pending p = new Pending(t.getUUID("tx"), t.getUUID("player"), Kind.valueOf(t.getString("kind")), t.hasUUID("crew") ? t.getUUID("crew") : null,
                t.getLong("amount"), t.getDouble("before"), t.getDouble("after"), t.getString("residence"), t.getBoolean("starter"), t.getInt("count"), t.getString("detail"));
            pending.put(p.player(), p);
        }
        for (Tag raw : root.getList("holds", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            Hold h = new Hold(t.getUUID("tx"), t.getUUID("player"), t.getUUID("crew"), t.getLong("amount"), t.getString("note"));
            if (h.amount() < 1) throw new IllegalStateException("A purchase hold has no credits");
            holds.put(h.tx(), h);
        }
        for (Tag raw : root.getList("residences", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            // a home of several boxes (D-206) lists them all; a save made before keeps one box, its min and max
            List<Box> boxes = new ArrayList<>();
            long[] corners = t.contains("boxes", Tag.TAG_LONG_ARRAY) ? t.getLongArray("boxes") : new long[0];
            if (corners.length >= 2 && corners.length % 2 == 0 && corners.length <= 2 * MAX_BOXES) {
                for (int i = 0; i < corners.length; i += 2) boxes.add(new Box(BlockPos.of(corners[i]), BlockPos.of(corners[i + 1])));
            } else {
                boxes.add(new Box(BlockPos.of(t.getLong("min")), BlockPos.of(t.getLong("max"))));
            }
            Residence r = new Residence(t.getString("id"), t.getString("title"), t.getString("kind"), t.getString("dimension"), t.getString("district"),
                boxes, t.getInt("price"), t.getInt("starter_price"));
            r.ownerType = OwnerType.valueOf(t.getString("owner_type"));
            r.owner = t.hasUUID("owner") ? t.getUUID("owner") : null;
            r.since = t.getLong("since");
            r.reservedBy = t.hasUUID("reserved_by") ? t.getUUID("reserved_by") : null;
            for (Tag g : t.getList("guests", Tag.TAG_COMPOUND)) r.guests.put(((CompoundTag) g).getUUID("p"), ((CompoundTag) g).getLong("until"));
            for (Tag g : t.getList("claims", Tag.TAG_COMPOUND)) r.claims.put(((CompoundTag) g).getLong("pos"), ((CompoundTag) g).getUUID("p"));
            if (t.contains("tenure", Tag.TAG_STRING)) {
                r.tenure = Tenure.valueOf(t.getString("tenure")); r.legacy = t.getBoolean("legacy"); r.charge = t.getInt("charge"); r.deposit = t.getInt("deposit");
                r.paidPrice = t.getInt("paid_price"); r.progress = t.getLong("progress"); r.due = t.getInt("due"); r.prepaid = t.getInt("prepaid"); r.freeCycles = t.getInt("free"); r.arrears = t.getInt("arrears");
                r.autopay = t.getBoolean("autopay"); r.factorPct = t.contains("factor", Tag.TAG_INT) ? t.getInt("factor") : 100;
                r.signedAt = t.getLong("signed_at"); r.signedBy = t.hasUUID("signed_by") ? t.getUUID("signed_by") : null;
                for (Tag u : t.getList("upgrades", Tag.TAG_STRING)) r.upgrades.add(u.getAsString());
                for (Tag g : t.getList("receipts", Tag.TAG_COMPOUND)) {
                    CompoundTag c = (CompoundTag) g;
                    r.receipts.addLast(new Entry(c.getLong("t"), c.hasUUID("a") ? c.getUUID("a") : null, c.getString("an"), c.getString("k"), c.getLong("m"), c.getString("n")));
                }
                if (r.charge < 0 || r.deposit < 0 || r.prepaid < 0 || r.arrears < 0 || r.due < 0 || r.progress < 0) throw new IllegalStateException("Negative tenancy figures in " + r.id);
            } else if (r.ownerType != OwnerType.NONE) {
                // A home held before rent existed (schema 1): it stays owned, with no charge, ever. What was paid is not known, so selling back is bounded by the cheaper registered price.
                r.tenure = Tenure.OWN; r.legacy = true; r.paidPrice = r.starterPrice >= 0 ? r.starterPrice : r.price;
            }
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
