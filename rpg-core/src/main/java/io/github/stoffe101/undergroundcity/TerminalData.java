package io.github.stoffe101.undergroundcity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only server snapshot. Actions never echo prices, XP or item counts back. */
public record TerminalData(String city, String career, int level, int xp, double balance,
                           boolean economy, UUID active, int foreignVisits, String region,
                           List<Row> jobs, List<Offer> offers,
                           int skillPoints, List<SkillRow> skills, List<Contact> contacts, List<LawRow> laws,
                           int heat, String lawSummary, List<Step> journey, String outlet, Thief thief) {
    /** The Thief track (D-143): its level, XP (and the XP of the next level), unspent Thief points and its tree. */
    public record Thief(int level, int xp, int nextXp, int points, List<SkillRow> skills) {
        public static final Thief NONE = new Thief(1, 0, ProgressionRules.thiefXpAtLevel(2), 0, List.of());
    }

    /** Every snapshot before the Thief track: no Thief data. */
    public TerminalData(String city, String career, int level, int xp, double balance, boolean economy, UUID active, int foreignVisits, String region,
                        List<Row> jobs, List<Offer> offers, int skillPoints, List<SkillRow> skills, List<Contact> contacts, List<LawRow> laws,
                        int heat, String lawSummary, List<Step> journey, String outlet) {
        this(city, career, level, xp, balance, economy, active, foreignVisits, region, jobs, offers, skillPoints, skills, contacts, laws, heat, lawSummary, journey, outlet, Thief.NONE);
    }
    public record Req(String item, int count, int held, String label) {
        public Req(String item, int count, int held) { this(item, count, held, ""); }
    }
    /** Legal/underground presentation of a contract: what it needs, who buys it, what else it pays. */
    public record Extra(boolean underground, String skill, boolean skillOk, String buyer, String buyerCity,
                        int minTrust, int trust, List<String> rewards) {
        public static final Extra NONE = new Extra(false, "", true, "", "", 0, 0, List.of());
    }
    public record Row(String id, String title, int level, int credits, int xp, int available,
                      boolean accepted, int foreignVisits, List<Req> reqs, Extra extra) {
        public Row(String id, String title, int level, int credits, int xp, int available,
                   boolean accepted, int foreignVisits, List<Req> reqs) {
            this(id, title, level, credits, xp, available, accepted, foreignVisits, reqs, Extra.NONE);
        }
    }
    /** One line of a shop or exchange. {@code pays} is what a shop pays a supplier a unit (0 when it buys none; D-164) and {@code carried} how many the player carries that it would take. */
    public record Offer(String id, String item, int price, int stock, int capacity, int maxOrder, int pays, int carried) {
        public Offer(String id, String item, int price, int stock, int capacity, int maxOrder) { this(id, item, price, stock, capacity, maxOrder, 0, 0); }
    }
    /** One node of the career tree. state: 0 locked, 1 can be unlocked now, 2 unlocked. */
    public record SkillRow(String id, String branch, String title, String description, int cost, int minLevel,
                           int state, String reason, List<String> requires, String shortTitle, String effects) {
        public SkillRow(String id, String branch, String title, String description, int cost, int minLevel,
                        int state, String reason, List<String> requires, String shortTitle) {
            this(id, branch, title, description, cost, minLevel, state, reason, requires, shortTitle, "");
        }
        public SkillRow(String id, String branch, String title, String description, int cost, int minLevel,
                        int state, String reason, List<String> requires) {
            this(id, branch, title, description, cost, minLevel, state, reason, requires, title, "");
        }
    }
    /** One guided-journey milestone: done or not, with the terminal page (a {@link TerminalScreen.Page} name, or empty) that helps. */
    public record Step(String id, String title, String hint, boolean done, String page) {}
    public record Contact(String id, String title, String city, boolean underground, String blurb, int trust) {}
    /** Status is legal, permit or prohibited; licensed is true when this citizen's skills satisfy a permit. */
    public record LawRow(String category, String title, String status, boolean licensed) {}

    static final int MAX_STEPS = 12, MAX_ROWS = 33, MAX_REQS = 4, MAX_OFFERS = 16, MAX_SKILLS = 16, MAX_CONTACTS = 8, MAX_LAWS = 8, MAX_REWARDS = 3;

    public TerminalData(String city, String career, int level, int xp, double balance, boolean economy, UUID active,
                        int foreignVisits, String region, List<Row> jobs, List<Offer> offers) {
        this(city, career, level, xp, balance, economy, active, foreignVisits, region, jobs, offers, 0, List.of(), List.of(), List.of(), 0, "", List.of());
    }

    public TerminalData(String city, String career, int level, int xp, double balance, boolean economy, UUID active,
                        int foreignVisits, String region, List<Row> jobs, List<Offer> offers,
                        int skillPoints, List<SkillRow> skills, List<Contact> contacts, List<LawRow> laws, int heat, String lawSummary) {
        this(city, career, level, xp, balance, economy, active, foreignVisits, region, jobs, offers, skillPoints, skills, contacts, laws, heat, lawSummary, List.of());
    }

    /** Fixture constructor for callers that predate the outlet: a terminal in a city, or none. */
    public TerminalData(String city, String career, int level, int xp, double balance, boolean economy, UUID active,
                        int foreignVisits, String region, List<Row> jobs, List<Offer> offers,
                        int skillPoints, List<SkillRow> skills, List<Contact> contacts, List<LawRow> laws, int heat, String lawSummary, List<Step> journey) {
        this(city, career, level, xp, balance, economy, active, foreignVisits, region, jobs, offers, skillPoints, skills, contacts, laws, heat, lawSummary, journey, region.isEmpty() ? "" : RegionalStock.CITY);
    }

    /** True at the provisions desk of a wayside shelter: a shop of the nearest city's basics, with no orders, contacts or law (D-091). */
    public boolean wayside() { return outlet.equals(RegionalStock.WAYSIDE); }
    /** True at a shopkeeper's counter (D-126); the outlet then reads {@code shop:<kind>|<the shop's name>}. */
    public boolean counter() { return outlet.startsWith("shop:"); }
    /** A shop and nothing else (a wayside desk or a counter): no orders, contacts or law. */
    public boolean shopOnly() { return wayside() || counter(); }
    /** The name of the shop at a counter, or "". */
    public String shopTitle() { int bar = outlet.indexOf('|'); return counter() && bar >= 0 ? outlet.substring(bar + 1) : ""; }

    public static TerminalData offline() {
        return new TerminalData("UNREGISTERED", "NONE", 1, 0, 0, false, null, 0, "", List.of(), List.of());
    }

    static TerminalData snapshot(ServerPlayer player, BlockPos pos) {
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        if (citizen == null) return offline();
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        Exchanges.Exchange exchange = Exchanges.at(player.level(), pos).orElse(null);
        boolean wayside = exchange != null && exchange.shopOnly();
        // a wayside desk or a shop counter is only a shop: no orders, contacts or law; the region stays empty so nothing treats the player as being at a terminal of the city
        String region = exchange == null || wayside ? "" : exchange.city();
        if (!region.isEmpty()) ledger.noteVisit(player.getUUID(), region); // physically at a placed terminal inside the city
        CompoundTag record = ledger.player(player.getUUID());
        UUID active = record.hasUUID("order") ? record.getUUID("order") : null;
        WorkOrder accepted = null;
        boolean unavailable = false;
        if (active != null) {
            try { accepted = WorkOrder.load(record.getCompound("job")); }
            catch (IllegalArgumentException removedItem) { unavailable = true; } // item/mod removed after acceptance
        }
        final WorkOrder acceptedOrder = accepted;
        List<WorkOrder> jobs = new ArrayList<>(WorkOrderCatalog.INSTANCE.forCareer(citizen.career));
        jobs.removeIf(job -> job.maxLevel() > 0 && citizen.careerLevel() > job.maxLevel()); // apprentice orders fade out; an accepted one is re-added below
        if (acceptedOrder != null) {
            jobs.removeIf(job -> job.id().equals(acceptedOrder.id())); jobs.add(0, acceptedOrder);
        }
        List<Row> rows = jobs.stream().limit(wayside ? 0 : MAX_ROWS).map(job -> new Row(job.id(), job.title(), job.level(),
            SkillService.payout(citizen, job), job.xp(), ledger.available(job), acceptedOrder != null && job.id().equals(acceptedOrder.id()),
            job.foreignVisits(), job.requirements().stream().map(req -> new Req(req.item().toString(), req.count(),
                player.getInventory().items.stream().filter(stack -> job.matches(req, stack)).mapToInt(stack -> stack.getCount()).sum(), req.label())).toList(),
            extra(job, citizen, ledger, player))).toList();
        if (unavailable) {
            // Lets the player cancel an order whose accepted terms can no longer be loaded.
            List<Row> withNotice = new ArrayList<>();
            withNotice.add(new Row("underground_city:unavailable", "Order unavailable - cancel to continue", 1, 0, 0, 0, true, 0, List.of()));
            withNotice.addAll(rows);
            rows = withNotice.stream().limit(MAX_ROWS).toList();
        }
        boolean economy = QShopEconomy.available() && QShopEconomy.configured(player.server) && !ledger.faulted();
        CityLaws.Law law = region.isEmpty() ? null : CityLaws.INSTANCE.find(region);
        return new TerminalData(citizen.city, citizen.career, citizen.careerLevel(), citizen.careerXp(),
            economy ? QShopEconomy.balance(player) : 0, economy, active, ledger.foreignVisits(player.getUUID(), citizen.city),
            region, rows, exchange == null ? List.of() : RegionalMarket.offers(ledger, exchange.city(), exchange.outlet(), citizen, player),
            SkillService.pointsAvailable(citizen), skillRows(citizen), contacts(region, citizen, ledger, player), lawRows(law, citizen),
            region.isEmpty() ? 0 : ledger.heat(player.getUUID(), region), law == null ? "" : law.summary(),
            Journey.steps(citizen, ledger, player.getUUID()), exchange == null ? "" : exchange.counter() ? exchange.outlet() + "|" + exchange.title() : exchange.outlet(),
            thief(citizen));
    }

    /** The Thief track of a citizen, for the terminal's Thief page. */
    static Thief thief(CitizenState.Citizen citizen) {
        int level = citizen.thiefLevel();
        int next = level >= ProgressionRules.THIEF_MAX_LEVEL ? citizen.thiefXp() : ProgressionRules.thiefXpAtLevel(level + 1);
        return new Thief(level, citizen.thiefXp(), next, SkillService.thiefPointsAvailable(citizen), rows(citizen, SkillCatalog.INSTANCE.tree(SkillNode.THIEF)));
    }

    private static Extra extra(WorkOrder job, CitizenState.Citizen citizen, WorkOrderLedger ledger, ServerPlayer player) {
        WorkOrder.Terms terms = job.terms();
        if (terms == WorkOrder.Terms.DEFAULT) return Extra.NONE;
        SkillNode needed = terms.requiresSkill().isEmpty() ? null : SkillCatalog.INSTANCE.find(terms.requiresSkill());
        BuyerCatalog.Buyer buyer = terms.buyer().isEmpty() ? null : BuyerCatalog.INSTANCE.find(terms.buyer());
        List<String> rewards = terms.rewards().stream().limit(MAX_REWARDS).map(reward -> reward.count() + " x "
            + net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(reward.item()).getDescription().getString()).toList();
        return new Extra(job.underground(), needed == null ? terms.requiresSkill() : needed.title(),
            terms.requiresSkill().isEmpty() || SkillService.has(citizen, terms.requiresSkill()),
            buyer == null ? "" : buyer.title(), buyer == null ? "" : buyer.city(), terms.minTrust(),
            buyer == null ? 0 : ledger.trust(player.getUUID(), buyer.id()), rewards);
    }

    private static List<SkillRow> skillRows(CitizenState.Citizen citizen) { return rows(citizen, SkillCatalog.INSTANCE.tree(citizen.career)); }

    private static List<SkillRow> rows(CitizenState.Citizen citizen, List<SkillNode> nodes) {
        List<SkillNode> tree = SkillRules.displayOrder(nodes);
        List<SkillRow> rows = new ArrayList<>();
        for (SkillNode node : tree) {
            String blocker = SkillService.blocker(citizen, node);
            int state = citizen.skills().contains(node.id()) ? 2 : blocker == null ? 1 : 0;
            List<String> requires = node.requires();   // ids; the screen resolves names and draws the links
            rows.add(new SkillRow(node.id(), node.branch(), node.title(), node.description(), node.cost(), node.minLevel(),
                state, state == 2 || blocker == null ? "" : blocker, requires, node.shortTitle(), node.effectSummary()));
        }
        return rows.stream().limit(MAX_SKILLS).toList();
    }

    private static List<Contact> contacts(String region, CitizenState.Citizen citizen, WorkOrderLedger ledger, ServerPlayer player) {
        if (region.isEmpty()) return List.of();
        boolean knowsTheTrade = SkillCatalog.INSTANCE.tree(citizen.career).stream()
            .anyMatch(node -> node.branch().equals("underground") && citizen.skills().contains(node.id()));
        return BuyerCatalog.INSTANCE.inCity(region).stream().filter(buyer -> !buyer.underground() || knowsTheTrade)
            .map(buyer -> new Contact(buyer.id(), buyer.title(), buyer.city(), buyer.underground(), buyer.blurb(), ledger.trust(player.getUUID(), buyer.id())))
            .limit(MAX_CONTACTS).toList();
    }

    private static List<LawRow> lawRows(CityLaws.Law law, CitizenState.Citizen citizen) {
        if (law == null) return List.of();
        List<LawRow> rows = new ArrayList<>();
        for (var category : ContrabandCatalog.INSTANCE.all().values()) {
            rows.add(new LawRow(category.id(), category.title(), law.status(category.id()), SkillService.licensed(citizen, category.id())));
        }
        return rows.stream().limit(MAX_LAWS).toList();
    }

    void write(FriendlyByteBuf buf) {
        buf.writeUtf(city, 32); buf.writeUtf(career, 32); buf.writeVarInt(level); buf.writeVarInt(xp);
        buf.writeDouble(balance); buf.writeBoolean(economy); buf.writeBoolean(active != null);
        if (active != null) buf.writeUUID(active);
        buf.writeVarInt(foreignVisits); buf.writeUtf(region, 32);
        buf.writeVarInt(jobs.size());
        for (Row row : jobs) {
            buf.writeUtf(row.id, 128); buf.writeUtf(row.title, 80); buf.writeVarInt(row.level);
            buf.writeVarInt(row.credits); buf.writeVarInt(row.xp); buf.writeVarInt(row.available);
            buf.writeBoolean(row.accepted); buf.writeVarInt(row.foreignVisits);
            buf.writeVarInt(row.reqs.size());
            for (Req req : row.reqs) { buf.writeUtf(req.item, 128); buf.writeVarInt(req.count); buf.writeVarInt(req.held); buf.writeUtf(req.label, 40); }
            Extra extra = row.extra;
            buf.writeBoolean(extra.underground); buf.writeUtf(extra.skill, 48); buf.writeBoolean(extra.skillOk);
            buf.writeUtf(extra.buyer, 48); buf.writeUtf(extra.buyerCity, 32); buf.writeVarInt(extra.minTrust); buf.writeVarInt(extra.trust);
            buf.writeVarInt(extra.rewards.size());
            for (String reward : extra.rewards) buf.writeUtf(reward, 80);
        }
        buf.writeVarInt(offers.size());
        for (Offer offer : offers) {
            buf.writeUtf(offer.id, 128); buf.writeUtf(offer.item, 128); buf.writeVarInt(offer.price);
            buf.writeVarInt(offer.stock); buf.writeVarInt(offer.capacity); buf.writeVarInt(offer.maxOrder);
            buf.writeVarInt(offer.pays); buf.writeVarInt(Math.min(9999, offer.carried));
        }
        buf.writeVarInt(skillPoints);
        buf.writeVarInt(skills.size());
        for (SkillRow row : skills) {
            buf.writeUtf(row.id, 64); buf.writeUtf(row.branch, 16); buf.writeUtf(row.title, 48); buf.writeUtf(row.description, 180);
            buf.writeVarInt(row.cost); buf.writeVarInt(row.minLevel); buf.writeVarInt(row.state); buf.writeUtf(row.reason, 96);
            buf.writeVarInt(row.requires.size());
            for (String requirement : row.requires) buf.writeUtf(requirement, 48);
            buf.writeUtf(row.shortTitle, 48); // a node without a short title sends its full title (at most 40)
            buf.writeUtf(row.effects, 140);
        }
        buf.writeVarInt(contacts.size());
        for (Contact contact : contacts) {
            buf.writeUtf(contact.id, 48); buf.writeUtf(contact.title, 56); buf.writeUtf(contact.city, 32);
            buf.writeBoolean(contact.underground); buf.writeUtf(contact.blurb, 170); buf.writeVarInt(contact.trust);
        }
        buf.writeVarInt(laws.size());
        for (LawRow row : laws) { buf.writeUtf(row.category, 48); buf.writeUtf(row.title, 56); buf.writeUtf(row.status, 16); buf.writeBoolean(row.licensed); }
        buf.writeVarInt(heat); buf.writeUtf(lawSummary, 210);
        buf.writeVarInt(journey.size());
        for (Step step : journey) { buf.writeUtf(step.id, 24); buf.writeUtf(step.title, 48); buf.writeUtf(step.hint, 160); buf.writeBoolean(step.done); buf.writeUtf(step.page, 16); }
        buf.writeUtf(outlet, 96);
        buf.writeVarInt(thief.level); buf.writeVarInt(thief.xp); buf.writeVarInt(thief.nextXp); buf.writeVarInt(thief.points);
        writeSkills(buf, thief.skills);
    }

    private static void writeSkills(FriendlyByteBuf buf, List<SkillRow> skills) {
        buf.writeVarInt(skills.size());
        for (SkillRow row : skills) {
            buf.writeUtf(row.id, 64); buf.writeUtf(row.branch, 16); buf.writeUtf(row.title, 48); buf.writeUtf(row.description, 180);
            buf.writeVarInt(row.cost); buf.writeVarInt(row.minLevel); buf.writeVarInt(row.state); buf.writeUtf(row.reason, 96);
            buf.writeVarInt(row.requires.size());
            for (String requirement : row.requires) buf.writeUtf(requirement, 48);
            buf.writeUtf(row.shortTitle, 48);
            buf.writeUtf(row.effects, 140);
        }
    }

    private static List<SkillRow> readSkills(FriendlyByteBuf buf) {
        int skillCount = buf.readVarInt();
        if (skillCount < 0 || skillCount > MAX_SKILLS) throw new IllegalArgumentException("Invalid skill count");
        List<SkillRow> skills = new ArrayList<>();
        for (int i = 0; i < skillCount; i++) {
            String id = buf.readUtf(64), branch = buf.readUtf(16), skillTitle = buf.readUtf(48), description = buf.readUtf(180);
            int cost = buf.readVarInt(), minLevel = buf.readVarInt(), state = buf.readVarInt(); String reason = buf.readUtf(96);
            int required = buf.readVarInt();
            if (required < 0 || required > 4) throw new IllegalArgumentException("Invalid prerequisite count");
            List<String> requires = new ArrayList<>();
            for (int r = 0; r < required; r++) requires.add(buf.readUtf(48));
            skills.add(new SkillRow(id, branch, skillTitle, description, cost, minLevel, state, reason, List.copyOf(requires), buf.readUtf(48), buf.readUtf(140)));
        }
        return List.copyOf(skills);
    }

    static TerminalData read(FriendlyByteBuf buf) {
        String city = buf.readUtf(32), career = buf.readUtf(32);
        int level = buf.readVarInt(), xp = buf.readVarInt(); double balance = buf.readDouble();
        boolean economy = buf.readBoolean(); UUID active = buf.readBoolean() ? buf.readUUID() : null;
        int visits = buf.readVarInt(); String region = buf.readUtf(32);
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ROWS) throw new IllegalArgumentException("Invalid job snapshot size");
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String id = buf.readUtf(128), title = buf.readUtf(80);
            int rowLevel = buf.readVarInt(), credits = buf.readVarInt(), rowXp = buf.readVarInt(), available = buf.readVarInt();
            boolean accepted = buf.readBoolean(); int rowVisits = buf.readVarInt();
            int reqCount = buf.readVarInt();
            if (reqCount < 0 || reqCount > MAX_REQS) throw new IllegalArgumentException("Invalid requirement count");
            List<Req> reqs = new ArrayList<>();
            for (int r = 0; r < reqCount; r++) reqs.add(new Req(buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readUtf(40)));
            boolean underground = buf.readBoolean(); String skill = buf.readUtf(48); boolean skillOk = buf.readBoolean();
            String buyer = buf.readUtf(48), buyerCity = buf.readUtf(32); int minTrust = buf.readVarInt(), trust = buf.readVarInt();
            int rewardCount = buf.readVarInt();
            if (rewardCount < 0 || rewardCount > MAX_REWARDS) throw new IllegalArgumentException("Invalid reward count");
            List<String> rewards = new ArrayList<>();
            for (int r = 0; r < rewardCount; r++) rewards.add(buf.readUtf(80));
            rows.add(new Row(id, title, rowLevel, credits, rowXp, available, accepted, rowVisits, List.copyOf(reqs),
                new Extra(underground, skill, skillOk, buyer, buyerCity, minTrust, trust, List.copyOf(rewards))));
        }
        int offerCount = buf.readVarInt();
        if (offerCount < 0 || offerCount > MAX_OFFERS) throw new IllegalArgumentException("Invalid offer count");
        List<Offer> offers = new ArrayList<>();
        for (int i = 0; i < offerCount; i++) {
            offers.add(new Offer(buf.readUtf(128), buf.readUtf(128), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        int points = buf.readVarInt();
        int skillCount = buf.readVarInt();
        if (skillCount < 0 || skillCount > MAX_SKILLS) throw new IllegalArgumentException("Invalid skill count");
        List<SkillRow> skills = new ArrayList<>();
        for (int i = 0; i < skillCount; i++) {
            String id = buf.readUtf(64), branch = buf.readUtf(16), skillTitle = buf.readUtf(48), description = buf.readUtf(180);
            int cost = buf.readVarInt(), minLevel = buf.readVarInt(), state = buf.readVarInt(); String reason = buf.readUtf(96);
            int required = buf.readVarInt();
            if (required < 0 || required > 4) throw new IllegalArgumentException("Invalid prerequisite count");
            List<String> requires = new ArrayList<>();
            for (int r = 0; r < required; r++) requires.add(buf.readUtf(48));
            skills.add(new SkillRow(id, branch, skillTitle, description, cost, minLevel, state, reason, List.copyOf(requires), buf.readUtf(48), buf.readUtf(140)));
        }
        int contactCount = buf.readVarInt();
        if (contactCount < 0 || contactCount > MAX_CONTACTS) throw new IllegalArgumentException("Invalid contact count");
        List<Contact> contacts = new ArrayList<>();
        for (int i = 0; i < contactCount; i++) {
            contacts.add(new Contact(buf.readUtf(48), buf.readUtf(56), buf.readUtf(32), buf.readBoolean(), buf.readUtf(170), buf.readVarInt()));
        }
        int lawCount = buf.readVarInt();
        if (lawCount < 0 || lawCount > MAX_LAWS) throw new IllegalArgumentException("Invalid law count");
        List<LawRow> laws = new ArrayList<>();
        for (int i = 0; i < lawCount; i++) laws.add(new LawRow(buf.readUtf(48), buf.readUtf(56), buf.readUtf(16), buf.readBoolean()));
        int heat = buf.readVarInt(); String lawSummary = buf.readUtf(210);
        int stepCount = buf.readVarInt();
        if (stepCount < 0 || stepCount > MAX_STEPS) throw new IllegalArgumentException("Invalid journey count");
        List<Step> journey = new ArrayList<>();
        for (int i = 0; i < stepCount; i++) journey.add(new Step(buf.readUtf(24), buf.readUtf(48), buf.readUtf(160), buf.readBoolean(), buf.readUtf(16)));
        String outlet = buf.readUtf(96);
        int thiefLevel = buf.readVarInt(), thiefXp = buf.readVarInt(), thiefNext = buf.readVarInt(), thiefPoints = buf.readVarInt();
        Thief thief = new Thief(Math.max(1, Math.min(ProgressionRules.THIEF_MAX_LEVEL, thiefLevel)), thiefXp, thiefNext, thiefPoints, readSkills(buf));
        return new TerminalData(city, career, level, xp, balance, economy, active, visits, region, List.copyOf(rows), List.copyOf(offers),
            points, List.copyOf(skills), List.copyOf(contacts), List.copyOf(laws), heat, lawSummary, List.copyOf(journey), outlet, thief);
    }
}
