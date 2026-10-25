package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.CrewLedger.Crew;
import io.github.stoffe101.undergroundcity.CrewLedger.OwnerType;
import io.github.stoffe101.undergroundcity.CrewLedger.Residence;
import io.github.stoffe101.undergroundcity.CrewRules.Perm;
import io.github.stoffe101.undergroundcity.CrewRules.Role;
import io.github.stoffe101.undergroundcity.PropertyRules.Rules;
import io.github.stoffe101.undergroundcity.PropertyRules.Tenure;
import io.github.stoffe101.undergroundcity.PropertyRules.Terms;
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
                       List<LogRow> log, List<HomeRow> homes, List<LeaseRow> nearby, List<UpgradeRow> upgrades, RulesRow rules) {

    /** The shape before the property economy: the new parts take their defaults (used by the review captures and the failure views). */
    public CrewView(boolean storageOk, String problem, UUID self, String selfName, boolean citizen, boolean moneyReady, double wallet, long owed, boolean starter,
                    boolean inCrew, UUID crewId, String crewName, String role, int myPerms, String mode, String effectiveMode, boolean consentSelf, int consentCount,
                    long bank, int managerPerms, int memberPerms, boolean canClaimLead, boolean disbandArmed,
                    List<MemberRow> members, List<InviteRow> incoming, List<InviteRow> outgoing, List<Candidate> candidates,
                    List<LogRow> log, List<HomeRow> homes, List<LeaseRow> nearby) {
        this(storageOk, problem, self, selfName, citizen, moneyReady, wallet, owed, starter, inCrew, crewId, crewName, role, myPerms, mode, effectiveMode, consentSelf, consentCount,
            bank, managerPerms, memberPerms, canClaimLead, disbandArmed, members, incoming, outgoing, candidates, log, homes, nearby, List.of(), RulesRow.DEFAULT);
    }

    public record MemberRow(UUID id, String name, String role, String career, int level, boolean online, String seen) {}
    public record InviteRow(UUID id, String crew, String who, String ago) {}
    public record Candidate(String name, String career) {}
    public record LogRow(String ago, String who, String kind, long amount, String note) {}
    /** How a held home is kept: the server's numbers and words for the Home page. Nothing here is decided by the client. */
    public record Tenancy(String tenure, String standing, String category, String area, int charge, int deposit, int minutes, int prepaid, int arrears, int owed, boolean autopay,
                          boolean manage, boolean crewHome, int containers, int maxContainers, int claimsMine, int claimsMax, String facilities, String upgrades, String residents,
                          boolean legacy, int refund, List<String> receipts) {
        public static final Tenancy NONE = new Tenancy("", "CURRENT", "", "", 0, 0, 0, 0, 0, 0, false, false, false, 0, 0, 0, 0, "", "", "", false, 0, List.of());
        public boolean rented() { return tenure.equals(Tenure.RENT.name()); }
        public boolean billed() { return charge > 0; }
    }
    public record HomeRow(String id, String title, String kind, String owner, String where, int mine, boolean here, Tenancy tenancy) {
        public HomeRow(String id, String title, String kind, String owner, String where, int mine, boolean here) { this(id, title, kind, owner, where, mine, here, Tenancy.NONE); }
    }
    /** What a home that nobody holds offers, in the numbers this player would pay in this city. */
    public record Offer(boolean canRent, boolean canBuy, int rent, int deposit, int upkeep, boolean welcome, String category, String summary, String facilities, int containers, int claims, int factor) {
        public static final Offer LEGACY = new Offer(false, true, 0, 0, 0, false, "", "", "", 0, 0, 100);
    }
    /** The cash a home asks for on the day you move in: nothing for the welcome hours, else the deposit and the first hour, or the price when it can only be bought. */
    static long moveInCost(LeaseRow row) {
        Offer o = row.offer();
        if (o.canRent()) return o.welcome() ? 0 : (long) o.deposit() + o.rent();
        return row.price();
    }

    /** The order of the homes on offer: the city's free welcome first, then the least cash to move in, then the nearest. */
    /** Homes this close (blocks from the player to the home's box) are the building the player stands at or in, and are listed first. */
    static final int HERE_BLOCKS = 12;
    static final java.util.Comparator<LeaseRow> MOVE_IN_ORDER = java.util.Comparator.<LeaseRow>comparingInt(row -> row.offer().welcome() ? 0 : 1)
        .thenComparingLong(CrewView::moveInCost).thenComparingInt(LeaseRow::distance).thenComparing(LeaseRow::id);

    public record LeaseRow(String id, String title, String kind, int price, boolean starter, int distance, Offer offer) {
        public LeaseRow(String id, String title, String kind, int price, boolean starter, int distance) { this(id, title, kind, price, starter, distance, Offer.LEGACY); }
    }
    public record UpgradeRow(String id, String title, int cost, String summary) {}
    public record RulesRow(int cycleMinutes, int prepayMax, int grace, int notice, int sellBackPercent, int welcomeCycles) {
        public static final RulesRow DEFAULT = new RulesRow(60, 6, 2, 1, 60, 2);
    }

    public static final int MAX_MEMBERS = 8, MAX_INVITES = 6, MAX_CANDIDATES = 12, MAX_LOG = 14, MAX_HOMES = 8, MAX_NEARBY = 16, MAX_UPGRADES = 6, MAX_RECEIPTS = 5;

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
            false, 0, 0, 0, 0, false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), RulesRow.DEFAULT);
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
        // the to-let flats, houses and premises of the furnished buildings round the player become listings the first time somebody looks (D-127)
        try { CityHomes.ensureNear(player); } catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(CrewView.class).warn("[UC] city homes near {} could not be listed: {}", player.getGameProfile().getName(), error.toString()); }
        String dimension = player.level().dimension().location().toString();
        Rules rules = PropertyCatalog.INSTANCE.rules();
        List<Residence> held = new ArrayList<>(ledger.residencesOf(OwnerType.PLAYER, player.getUUID()));
        if (crew != null) held.addAll(ledger.residencesOf(OwnerType.CREW, crew.id));
        for (Residence r : held) {
            if (homes.size() >= MAX_HOMES) break;
            int mine = (int) r.claims.values().stream().filter(player.getUUID()::equals).count();
            boolean here = r.dimension.equals(dimension) && r.insideShell(dimension, player.blockPosition());
            homes.add(new HomeRow(r.id, r.title, r.kind, r.ownerType == OwnerType.CREW ? "Crew" : "You", r.min.getX() + " " + r.min.getY() + " " + r.min.getZ(), mine, here,
                tenancy(ledger, player, r, crew, rules, mine)));
        }
        for (Residence r : ledger.allResidences()) {
            if (r.owned() || r.reservedBy != null || !r.dimension.equals(dimension)) continue;
            double distance = Math.sqrt(r.distanceSqr(player.blockPosition()));
            if (distance > 64) continue;
            Terms terms = PropertyCatalog.INSTANCE.find(r.id).orElse(null);
            Offer offer = Offer.LEGACY;
            int price = ledger.priceFor(player.getUUID(), r);
            if (terms != null) {
                CrewLedger.Quote quote = terms.canRent() ? ledger.rentQuote(player.getUUID(), r, terms, rules) : null;
                price = terms.canBuy() ? ledger.buyPrice(r, terms) : 0;
                offer = new Offer(terms.canRent(), terms.canBuy(), quote == null ? 0 : quote.rent(), quote == null ? 0 : quote.deposit(), PropertyRules.scaled(terms.upkeep(), r.factorPct),
                    quote != null && quote.welcome(), PropertyCatalog.INSTANCE.categoryTitle(terms.category()), terms.summary(), String.join(", ", terms.facilities()),
                    terms.maxContainers(), terms.privateClaims(), r.factorPct);
            }
            nearby.add(new LeaseRow(r.id, r.title, r.kind, price, terms == null && ledger.usesStarter(player.getUUID(), r), (int) distance, offer));
        }
        // what a player can move into now comes first: a journey run found the free welcome rooms on the third page, behind homes a new player cannot pay for; but the homes of the building the
        // player stands at come before everything, or premises, a loft or the villa in a street of cheaper flats never reached the list at all (premises run 1, seventh pass)
        nearby.sort(java.util.Comparator.<LeaseRow>comparingInt(row -> row.distance() <= HERE_BLOCKS ? 0 : 1).thenComparing(MOVE_IN_ORDER));
        if (nearby.size() > MAX_NEARBY) nearby.subList(MAX_NEARBY, nearby.size()).clear();
        List<UpgradeRow> upgrades = new ArrayList<>();
        int tradeDiscount = SkillService.upgradeDiscountPercent(player);
        for (PropertyRules.Upgrade up : PropertyCatalog.INSTANCE.upgrades().values()) if (upgrades.size() < MAX_UPGRADES) {
            PropertyRules.Upgrade priced = up.discounted(tradeDiscount);
            upgrades.add(new UpgradeRow(up.id(), up.title(), priced.cost(), up.summary() + (priced.cost() < up.cost() ? " Your trade saves you " + (up.cost() - priced.cost()) + " credits." : "")));
        }
        RulesRow rulesRow = new RulesRow(rules.cycleMinutes(), rules.prepayMaxCycles(), rules.graceCycles(), rules.noticeCycles(), rules.sellBackPercent(), rules.welcomeMotelCycles());
        long owed = ledger.owedTo(player.getUUID());
        boolean starter = ledger.starterAvailable(player.getUUID());
        if (crew == null) {
            return new CrewView(true, "", player.getUUID(), player.getGameProfile().getName(), citizen, money, wallet, owed, starter, false, new UUID(0, 0), "", "", 0,
                "BANK", "BANK", false, 0, 0, 0, 0, false, false, List.of(), incoming, List.of(), List.of(), List.of(), homes, nearby, upgrades, rulesRow);
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
            bits(crew.perms.get(Role.MANAGER)), bits(crew.perms.get(Role.MEMBER)), away, disbandArmed, members, incoming, outgoing, candidates, log, homes, nearby, upgrades, rulesRow);
    }

    private static final java.util.Comparator<String> WORDS = String::compareTo;

    /** The server's account of how a held home is kept, for its Home page card. */
    private static Tenancy tenancy(CrewLedger ledger, ServerPlayer viewer, Residence r, Crew crew, Rules rules, int claimsMine) {
        Terms terms = PropertyCatalog.INSTANCE.find(r.id).orElse(null);
        String residents;
        if (r.ownerType == OwnerType.CREW && crew != null) {
            List<String> names = new ArrayList<>();
            for (CrewLedger.Member m : crew.members.values()) names.add(m.name);
            residents = String.join(", ", names);
        } else residents = "You";
        int owed = (int) Math.min(Integer.MAX_VALUE, r.owedCredits());
        int refund = 0;
        if (r.rented()) refund = PropertyRules.depositBack(r.deposit, owed) + Math.max(0, r.prepaid - r.freeCycles) * r.charge;
        else if (r.tenure == Tenure.OWN) refund = PropertyRules.sellBack(r.paidPrice, owed, rules) + Math.max(0, r.prepaid - r.freeCycles) * r.charge;
        List<String> receipts = new ArrayList<>();
        long now = ledger.now();
        for (CrewLedger.Entry e : r.receipts) {
            if (receipts.size() >= MAX_RECEIPTS) break;
            receipts.add(CrewRules.ago(now - e.time()) + "  " + e.kind() + (e.amount() > 0 ? " " + e.amount() : "") + (e.note().isEmpty() ? "" : "  " + e.note()));
        }
        String upgrades = String.join(",", r.upgrades);
        int containerCap = PropertyService.maxContainers(r);
        int used = 0;
        net.minecraft.server.level.ServerLevel level = viewer.server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(r.dimension)));
        if (level != null && viewer.level() == level && r.distanceSqr(viewer.blockPosition()) < 96.0 * 96.0) used = PropertyService.containerCount(level, r);
        return new Tenancy(r.tenure.name(), r.standing(rules).name(), terms == null ? "" : PropertyCatalog.INSTANCE.categoryTitle(terms.category()), terms == null ? "" : terms.area(), r.charge, r.deposit,
            PropertyRules.minutesToNextCycle(r.progress, rules), r.prepaid, r.arrears + r.due, owed, r.autopay, ledger.canManage(viewer.getUUID(), r), r.ownerType == OwnerType.CREW,
            used, containerCap == Integer.MAX_VALUE ? 0 : containerCap, claimsMine, PropertyService.privateClaims(r), String.join(", ", PropertyService.facilities(r).stream().sorted(WORDS).toList()),
            upgrades, residents, r.legacy, refund, receipts);
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
        for (HomeRow h : homes) {
            b.writeUtf(h.id(), 64); b.writeUtf(h.title(), 40); b.writeUtf(h.kind(), 16); b.writeUtf(h.owner(), 8); b.writeUtf(h.where(), 40); b.writeVarInt(h.mine()); b.writeBoolean(h.here());
            Tenancy t = h.tenancy();
            b.writeUtf(t.tenure(), 8); b.writeUtf(t.standing(), 8); b.writeUtf(t.category(), 40); b.writeUtf(t.area(), 40); b.writeVarInt(t.charge()); b.writeVarInt(t.deposit());
            b.writeVarInt(t.minutes()); b.writeVarInt(t.prepaid()); b.writeVarInt(t.arrears()); b.writeVarInt(t.owed()); b.writeBoolean(t.autopay()); b.writeBoolean(t.manage()); b.writeBoolean(t.crewHome());
            b.writeVarInt(t.containers()); b.writeVarInt(t.maxContainers()); b.writeVarInt(t.claimsMine()); b.writeVarInt(t.claimsMax());
            b.writeUtf(t.facilities(), 120); b.writeUtf(t.upgrades(), 120); b.writeUtf(t.residents(), 120); b.writeBoolean(t.legacy()); b.writeVarInt(t.refund());
            b.writeVarInt(t.receipts().size());
            for (String line : t.receipts()) b.writeUtf(line, 100);
        }
        b.writeVarInt(nearby.size());
        for (LeaseRow n : nearby) {
            b.writeUtf(n.id(), 64); b.writeUtf(n.title(), 40); b.writeUtf(n.kind(), 16); b.writeVarInt(n.price()); b.writeBoolean(n.starter()); b.writeVarInt(n.distance());
            Offer o = n.offer();
            b.writeBoolean(o.canRent()); b.writeBoolean(o.canBuy()); b.writeVarInt(o.rent()); b.writeVarInt(o.deposit()); b.writeVarInt(o.upkeep()); b.writeBoolean(o.welcome());
            b.writeUtf(o.category(), 40); b.writeUtf(o.summary(), 200); b.writeUtf(o.facilities(), 120); b.writeVarInt(o.containers()); b.writeVarInt(o.claims()); b.writeVarInt(o.factor());
        }
        b.writeVarInt(upgrades.size());
        for (UpgradeRow u : upgrades) { b.writeUtf(u.id(), 32); b.writeUtf(u.title(), 32); b.writeVarInt(u.cost()); b.writeUtf(u.summary(), 200); }
        b.writeVarInt(rules.cycleMinutes()); b.writeVarInt(rules.prepayMax()); b.writeVarInt(rules.grace()); b.writeVarInt(rules.notice()); b.writeVarInt(rules.sellBackPercent()); b.writeVarInt(rules.welcomeCycles());
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
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_HOMES); i < n; i++) {
            String id = b.readUtf(64), title = b.readUtf(40), kind = b.readUtf(16), owner = b.readUtf(8), where = b.readUtf(40);
            int mine = b.readVarInt();
            boolean here = b.readBoolean();
            String tenure = b.readUtf(8), standing = b.readUtf(8), category = b.readUtf(40), area = b.readUtf(40);
            int charge = b.readVarInt(), deposit = b.readVarInt(), minutes = b.readVarInt(), prepaid = b.readVarInt(), arrears = b.readVarInt(), owedCredits = b.readVarInt();
            boolean autopay = b.readBoolean(), manage = b.readBoolean(), crewHome = b.readBoolean();
            int containers = b.readVarInt(), maxContainers = b.readVarInt(), claimsMine = b.readVarInt(), claimsMax = b.readVarInt();
            String facilities = b.readUtf(120), upgradeIds = b.readUtf(120), residents = b.readUtf(120);
            boolean legacy = b.readBoolean();
            int refund = b.readVarInt();
            List<String> receipts = new ArrayList<>();
            for (int k = 0, m = Math.min(b.readVarInt(), MAX_RECEIPTS); k < m; k++) receipts.add(b.readUtf(100));
            homes.add(new HomeRow(id, title, kind, owner, where, mine, here, new Tenancy(tenure, standing, category, area, charge, deposit, minutes, prepaid, arrears, owedCredits, autopay, manage, crewHome,
                containers, maxContainers, claimsMine, claimsMax, facilities, upgradeIds, residents, legacy, refund, List.copyOf(receipts))));
        }
        List<LeaseRow> nearby = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_NEARBY); i < n; i++) {
            String id = b.readUtf(64), title = b.readUtf(40), kind = b.readUtf(16);
            int price = b.readVarInt();
            boolean starterFlag = b.readBoolean();
            int distance = b.readVarInt();
            Offer offer = new Offer(b.readBoolean(), b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(40), b.readUtf(200), b.readUtf(120), b.readVarInt(), b.readVarInt(), b.readVarInt());
            nearby.add(new LeaseRow(id, title, kind, price, starterFlag, distance, offer));
        }
        List<UpgradeRow> upgrades = new ArrayList<>();
        for (int i = 0, n = Math.min(b.readVarInt(), MAX_UPGRADES); i < n; i++) upgrades.add(new UpgradeRow(b.readUtf(32), b.readUtf(32), b.readVarInt(), b.readUtf(200)));
        RulesRow rulesRow = new RulesRow(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        return new CrewView(storageOk, problem, self, selfName, citizen, moneyReady, wallet, owed, starter, inCrew, crewId, crewName, role, myPerms, mode, effective, consentSelf, consentCount,
            bank, managerPerms, memberPerms, canClaim, armed, List.copyOf(members), incoming, outgoing, List.copyOf(candidates), List.copyOf(log), List.copyOf(homes), List.copyOf(nearby), List.copyOf(upgrades), rulesRow);
    }
}
