package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the Crew screen is allowed to know, computed on the server for one viewer and sent whole: the client draws it and sends bounded
 * requests back, it decides nothing. Every list is capped here, so a large Crew cannot make an oversized packet.
 */
public record CrewView(boolean storageOk, String problem, UUID self, String selfName, boolean citizen, boolean moneyReady, double wallet, long owed, boolean starter,
                       boolean inCrew, UUID crewId, String crewName, String role, int myPerms, String mode, String effectiveMode, boolean consentSelf, int consentCount,
                       long bank, int managerPerms, int memberPerms, boolean canClaimLead, boolean disbandArmed,
                       List<MemberRow> members, List<InviteRow> incoming, List<InviteRow> outgoing, List<Candidate> candidates,
                       List<LogRow> log, List<HomeRow> homes, List<LeaseRow> nearby) {

    public record MemberRow(UUID id, String name, String role, String career, int level, boolean online, String seen) {}
    public record InviteRow(UUID id, String crew, String who, String ago) {}
    public record Candidate(String name, String career) {}
    public record LogRow(String ago, String who, String kind, long amount, String note) {}
    public record HomeRow(String id, String title, String kind, String owner, String where, int mine, boolean here) {}
    public record LeaseRow(String id, String title, String kind, int price, boolean starter, int distance) {}

    public static final int MAX_MEMBERS = 8, MAX_INVITES = 6, MAX_CANDIDATES = 12, MAX_LOG = 14, MAX_HOMES = 8, MAX_NEARBY = 8;

    public boolean can(Perm perm) { return role.equals(Role.FOUNDER.title) || (myPerms & (1 << perm.ordinal())) != 0; }
    public boolean founder() { return role.equals(Role.FOUNDER.title); }
    public static int bits(java.util.Collection<Perm> perms) { int bits = 0; for (Perm perm : perms) bits |= 1 << perm.ordinal(); return bits; }

    /** A view for a player when the Crew system cannot work at all (the ledger could not be read). */
    public static CrewView unavailable(ServerPlayer player, String problem) {
        return new CrewView(false, problem, player.getUUID(), player.getGameProfile().getName(), false, false, 0, 0, false, false, new UUID(0, 0), "", "", 0, "BANK", "BANK",
            false, 0, 0, 0, 0, false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** A view for review captures only: storage unavailable, no player. */
    static CrewView unavailableForCapture(String problem) {
        return new CrewView(false, problem, new UUID(0, 1), "Imogen", false, false, 0, 0, false, false, new UUID(0, 0), "", "", 0, "BANK", "BANK",
            false, 0, 0, 0, 0, false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /** The view of {@code player}'s Crew life right now. */
    public static CrewView of(ServerPlayer player, boolean disbandArmed) {
        CrewLedger ledger = CrewService.ledger(player.server);
        if (ledger == null) return unavailable(player, "Crew storage could not be read. Ask the host to restore data/underground_city_crews.dat from a backup.");
        if (ledger.faulted()) return unavailable(player, "Crew storage failed while saving. Ask the host to restart the server.");
        CitizenState citizens = CitizenState.get(player.server);
        boolean citizen = citizens.find(player.getUUID()).isPresent();
        boolean money = CrewService.moneyReady(player.server);
        double wallet = 0;
        if (money) {
            try { wallet = CrewService.wallet.balance(player); } catch (RuntimeException ignored) { money = false; }
        }
        Crew crew = ledger.crewOf(player.getUUID());
        long now = ledger.now();
        List<InviteRow> incoming = new ArrayList<>();
        for (CrewLedger.Invite invite : ledger.invitesFor(player.getUUID())) {
            if (incoming.size() < MAX_INVITES) incoming.add(new InviteRow(invite.id(), invite.crewName(), invite.byName(), CrewRules.ago(now - invite.created())));
        }
        List<Candidate> candidates = new ArrayList<>();
        if (crew != null && crew.can(player.getUUID(), Perm.INVITE)) {
            for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
                if (candidates.size() >= MAX_CANDIDATES) break;
                if (other == player || other instanceof FakePlayer || ledger.crewOf(other.getUUID()) != null) continue;
                var info = citizens.find(other.getUUID());
                if (info.isEmpty()) continue;
                candidates.add(new Candidate(other.getGameProfile().getName(), info.get().career));
            }
        }
        List<HomeRow> homes = new ArrayList<>();
        List<LeaseRow> nearby = new ArrayList<>();
        String dimension = player.level().dimension().location().toString();
        List<Residence> held = new ArrayList<>(ledger.residencesOf(OwnerType.PLAYER, player.getUUID()));
        if (crew != null) held.addAll(ledger.residencesOf(OwnerType.CREW, crew.id));
        for (Residence r : held) {
            if (homes.size() >= MAX_HOMES) break;
            int mine = (int) r.claims.values().stream().filter(player.getUUID()::equals).count();
            boolean here = r.dimension.equals(dimension) && r.insideShell(dimension, player.blockPosition());
            homes.add(new HomeRow(r.id, r.title, r.kind, r.ownerType == OwnerType.CREW ? "Crew" : "You", r.min.getX() + " " + r.min.getY() + " " + r.min.getZ(), mine, here));
        }
        for (Residence r : ledger.allResidences()) {
            if (nearby.size() >= MAX_NEARBY) break;
            if (r.owned() || r.reservedBy != null || !r.dimension.equals(dimension)) continue;
            double distance = Math.sqrt(r.distanceSqr(player.blockPosition()));
            if (distance > 64) continue;
            nearby.add(new LeaseRow(r.id, r.title, r.kind, ledger.priceFor(player.getUUID(), r), ledger.usesStarter(player.getUUID(), r), (int) distance));
        }
        long owed = ledger.owedTo(player.getUUID());
        boolean starter = ledger.starterAvailable(player.getUUID());
        if (crew == null) {
            return new CrewView(true, "", player.getUUID(), player.getGameProfile().getName(), citizen, money, wallet, owed, starter, false, new UUID(0, 0), "", "", 0,
                "BANK", "BANK", false, 0, 0, 0, 0, false, false, List.of(), incoming, List.of(), List.of(), List.of(), homes, nearby);
        }
        List<MemberRow> members = new ArrayList<>();
        for (CrewLedger.Member member : crew.members.values()) {
            if (members.size() >= MAX_MEMBERS) break;
            ServerPlayer online = player.server.getPlayerList().getPlayer(member.id);
            var info = citizens.find(member.id);
            members.add(new MemberRow(member.id, member.name, member.role.title, info.map(i -> i.career).orElse(""), info.map(CitizenState.Citizen::careerLevel).orElse(0),
                online != null, online != null ? "online" : CrewRules.ago(now - member.lastSeen)));
        }
        List<InviteRow> outgoing = new ArrayList<>();
        for (CrewLedger.Invite invite : ledger.invitesFrom(crew.id)) {
            if (outgoing.size() < MAX_INVITES) outgoing.add(new InviteRow(invite.id(), crew.name, invite.inviteeName(), CrewRules.ago(now - invite.created())));
        }
        List<LogRow> log = new ArrayList<>();
        for (CrewLedger.Entry entry : crew.log) {
            if (log.size() >= MAX_LOG) break;
            log.add(new LogRow(CrewRules.ago(now - entry.time()), entry.actorName(), entry.kind(), entry.amount(), entry.note()));
        }
        CrewLedger.Member founder = crew.members.get(crew.founder);
        boolean away = founder != null && !founder.id.equals(player.getUUID()) && now - founder.lastSeen >= CrewRules.FOUNDER_INACTIVE_MILLIS
            && (crew.roleOf(player.getUUID()) == Role.MANAGER || crew.members.values().stream().noneMatch(m -> m.role == Role.MANAGER));
        return new CrewView(true, "", player.getUUID(), player.getGameProfile().getName(), citizen, money, wallet, owed, starter, true, crew.id, crew.name,
            crew.roleOf(player.getUUID()).title, bits(crew.roleOf(player.getUUID()) == Role.FOUNDER ? java.util.EnumSet.allOf(Perm.class) : crew.perms.get(crew.roleOf(player.getUUID()))),
            crew.mode.name(), crew.effectiveMode().name(), crew.consent.contains(player.getUUID()), crew.consent.size(), crew.bank,
            bits(crew.perms.get(Role.MANAGER)), bits(crew.perms.get(Role.MEMBER)), away, disbandArmed, members, incoming, outgoing, candidates, log, homes, nearby);
    }

    // ------------------------------------------------------------------------------------------------------------ wire format

    private static void writeUuid(FriendlyByteBuf b, UUID id) { b.writeUUID(id); }

    public void write(FriendlyByteBuf b) {
        b.writeBoolean(storageOk); b.writeUtf(problem, 200); b.writeUUID(self); b.writeUtf(selfName, 32);
        b.writeBoolean(citizen); b.writeBoolean(moneyReady); b.writeDouble(wallet); b.writeVarLong(owed); b.writeBoolean(starter);
        b.writeBoolean(inCrew); b.writeUUID(crewId); b.writeUtf(crewName, 32); b.writeUtf(role, 16); b.writeVarInt(myPerms);
        b.writeUtf(mode, 16); b.writeUtf(effectiveMode, 16); b.writeBoolean(consentSelf); b.writeVarInt(consentCount);
        b.writeVarLong(bank); b.writeVarInt(managerPerms); b.writeVarInt(memberPerms); b.writeBoolean(canClaimLead); b.writeBoolean(disbandArmed);
        b.writeVarInt(members.size());
        for (MemberRow m : members) { writeUuid(b, m.id()); b.writeUtf(m.name(), 32); b.writeUtf(m.role(), 16); b.writeUtf(m.career(), 16); b.writeVarInt(m.level()); b.writeBoolean(m.online()); b.writeUtf(m.seen(), 24); }
        writeInvites(b, incoming); writeInvites(b, outgoing);
        b.writeVarInt(candidates.size());
        for (Candidate c : candidates) { b.writeUtf(c.name(), 32); b.writeUtf(c.career(), 16); }
        b.writeVarInt(log.size());
        for (LogRow l : log) { b.writeUtf(l.ago(), 24); b.writeUtf(l.who(), 32); b.writeUtf(l.kind(), 16); b.writeVarLong(l.amount()); b.writeUtf(l.note(), 80); }
        b.writeVarInt(homes.size());
        for (HomeRow h : homes) { b.writeUtf(h.id(), 64); b.writeUtf(h.title(), 40); b.writeUtf(h.kind(), 16); b.writeUtf(h.owner(), 8); b.writeUtf(h.where(), 40); b.writeVarInt(h.mine()); b.writeBoolean(h.here()); }
        b.writeVarInt(nearby.size());
        for (LeaseRow n : nearby) { b.writeUtf(n.id(), 64); b.writeUtf(n.title(), 40); b.writeUtf(n.kind(), 16); b.writeVarInt(n.price()); b.writeBoolean(n.starter()); b.writeVarInt(n.distance()); }
    }

    private static void writeInvites(FriendlyByteBuf b, List<InviteRow> list) {
        b.writeVarInt(list.size());
        for (InviteRow i : list) { b.writeUUID(i.id()); b.writeUtf(i.crew(), 32); b.writeUtf(i.who(), 32); b.writeUtf(i.ago(), 24); }
    }

    private static List<InviteRow> readInvites(FriendlyByteBuf b) {
        int n = Math.min(b.readVarInt(), MAX_INVITES);
        List<InviteRow> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new InviteRow(b.readUUID(), b.readUtf(32), b.readUtf(32), b.readUtf(24)));
        return List.copyOf(list);
    }

    public static CrewView read(FriendlyByteBuf b) {
        boolean storageOk = b.readBoolean(); String problem = b.readUtf(200); UUID self = b.readUUID(); String selfName = b.readUtf(32);
        boolean citizen = b.readBoolean(), moneyReady = b.readBoolean(); double wallet = b.readDouble(); long owed = b.readVarLong(); boolean starter = b.readBoolean();
        boolean inCrew = b.readBoolean(); UUID crewId = b.readUUID(); String crewName = b.readUtf(32), role = b.readUtf(16); int myPerms = b.readVarInt();
        String mode = b.readUtf(16), effective = b.readUtf(16); boolean consentSelf = b.readBoolean(); int consentCount = b.readVarInt();
        long bank = b.readVarLong(); int managerPerms = b.readVarInt(), memberPerms = b.readVarInt(); boolean canClaim = b.readBoolean(), armed = b.readBoolean();
        List<MemberRow> members = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_MEMBERS); i < n; i++) members.add(new MemberRow(b.readUUID(), b.readUtf(32), b.readUtf(16), b.readUtf(16), b.readVarInt(), b.readBoolean(), b.readUtf(24)));
        List<InviteRow> incoming = readInvites(b), outgoing = readInvites(b);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_CANDIDATES); i < n; i++) candidates.add(new Candidate(b.readUtf(32), b.readUtf(16)));
        List<LogRow> log = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_LOG); i < n; i++) log.add(new LogRow(b.readUtf(24), b.readUtf(32), b.readUtf(16), b.readVarLong(), b.readUtf(80)));
        List<HomeRow> homes = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_HOMES); i < n; i++) homes.add(new HomeRow(b.readUtf(64), b.readUtf(40), b.readUtf(16), b.readUtf(8), b.readUtf(40), b.readVarInt(), b.readBoolean()));
        List<LeaseRow> nearby = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_NEARBY); i < n; i++) nearby.add(new LeaseRow(b.readUtf(64), b.readUtf(40), b.readUtf(16), b.readVarInt(), b.readBoolean(), b.readVarInt()));
        return new CrewView(storageOk, problem, self, selfName, citizen, moneyReady, wallet, owed, starter, inCrew, crewId, crewName, role, myPerms, mode, effective, consentSelf, consentCount,
            bank, managerPerms, memberPerms, canClaim, armed, List.copyOf(members), incoming, outgoing, List.copyOf(candidates), List.copyOf(log), List.copyOf(homes), List.copyOf(nearby));
    }
}
