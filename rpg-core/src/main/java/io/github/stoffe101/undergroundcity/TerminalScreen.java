package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Operations Terminal, redesigned as one panel with a navigation rail: WORK (orders, legal and underground side by side), SKILLS (the
 * career tree), TRADE (the regional exchange and the civilian shop), LAW (the city's law, buyers and your heat) and GUIDE (the first half hour).
 * It shows a read-only server snapshot ({@link TerminalData}) and sends bounded requests; it decides nothing. Teal is legal or unlocked, red
 * is underground or prohibited, amber is money and attention, and every state also has an icon or a word. Legal and underground work sit side
 * by side on every trade (D-049); nothing here locks a character into one of them.
 */
@OnlyIn(Dist.CLIENT)
public final class TerminalScreen extends UcScreen {
    public enum Page {
        CONTRACTS("Work", UcUi.Icon.CRATE), SKILLS("Skills", UcUi.Icon.POINT), THIEF("Thief", UcUi.Icon.KEY), IMPORTS("Trade", UcUi.Icon.SHOP),
        BLACK_MARKET("Law", UcUi.Icon.SCALES), GUIDE("Guide", UcUi.Icon.BOOK);
        final String label;
        final UcUi.Icon icon;
        Page(String label, UcUi.Icon icon) { this.label = label; this.icon = icon; }
    }

    /** What survives a server refresh of an open terminal. */
    public record Carry(Page page, boolean underground, String jobId, String skillId) {}

    private static final int LOT = 4, ROW_H = 28, MAX_OFFERS = 24, NODE_H = 33;

    private Page current = Page.CONTRACTS;
    private boolean undergroundView;
    private final BlockPos terminalPosition;
    private final TerminalData data;
    private int selected, jobScroll, selectedSkill, offerScroll;
    private int railW, cx, cy, cw, ch;
    private UcButton shop, accept, cancel, supplier, track, unlock, legalChip, undergroundChip, register, showMe;
    private final UcButton[] rail = new UcButton[Page.values().length];
    private final UcButton[] buyOne = new UcButton[MAX_OFFERS], buyLot = new UcButton[MAX_OFFERS];
    // the skill tree, laid out once per snapshot and size: node cards by index
    private int[] nodeX = new int[0], nodeY = new int[0], nodeTier = new int[0], nodeCol = new int[0];
    private int nodeW, nodeH = NODE_H;
    // the tree scrolls when it is deeper than the page: the viewport, how far it is scrolled and the end of the content, all in GUI pixels
    private static final int SCROLLBAR_ROOM = 7;
    private int treeTop, treeBottom, skillScroll, skillMaxScroll, skillStep = NODE_H + 7;
    private boolean draggingTree;
    private double dragFromY;
    private int dragFromScroll;

    public TerminalScreen() { this(null, TerminalData.offline(), null); }
    public TerminalScreen(BlockPos position, TerminalData data) { this(position, data, null); }
    public TerminalScreen(BlockPos position, TerminalData data, Carry carry) {
        super(Component.literal("Underground City | Operations"));
        this.terminalPosition = position;
        this.data = data;
        TerminalData.Row accepted = data.jobs().stream().filter(TerminalData.Row::accepted).findFirst().orElse(null);
        if (carry != null) { current = carry.page(); undergroundView = carry.underground(); }
        else if (data.counter()) current = Page.IMPORTS;   // a shopkeeper's counter opens on what the shop sells
        if (accepted != null) undergroundView = accepted.extra().underground();
        List<TerminalData.Row> list = jobs();
        selected = 0;
        if (accepted != null) selected = Math.max(0, indexOf(list, accepted.id()));
        else if (carry != null && carry.jobId() != null) selected = Math.max(0, indexOf(list, carry.jobId()));
        if (carry != null && carry.skillId() != null) {
            for (int i = 0; i < tree().size(); i++) if (tree().get(i).id().equals(carry.skillId())) selectedSkill = i;
        }
    }

    private static int indexOf(List<TerminalData.Row> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        return -1;
    }

    BlockPos position() { return terminalPosition; }
    /** What the server sent (read-only); the scripted player reads it the way a person reads the screen. */
    TerminalData data() { return data; }
    /** The orders of the current view (legal or underground) in their on-screen order. */
    List<TerminalData.Row> visibleJobs() { return jobs(); }
    /** The middle of the order row {@code index} of the current view, or null when it is scrolled out of sight (a scripted pointer clicks here). */
    int[] jobCentre(int index) {
        int rel = index - jobScroll;
        if (current != Page.CONTRACTS || rel < 0 || rel >= visibleRows() || index >= jobs().size()) return null;
        return new int[]{cx + (listW() - 6) / 2, listY() + rel * ROW_H + ROW_H / 2};
    }
    /** The middle of a skill card, or null when the card is not inside the scrolled viewport. */
    int[] skillCentre(String id) {
        for (int i = 0; i < tree().size() && i < nodeX.length; i++) {
            if (!tree().get(i).id().equals(id)) continue;
            int y = nodeY[i] - skillScroll + nodeH / 2;
            return y < treeTop || y >= treeBottom ? null : new int[]{nodeX[i] + nodeW / 2, y};
        }
        return null;
    }
    /** Where a skill card is against the scrolled viewport: -1 above it, 1 below it, 0 inside, 2 when the tree has no such card (a scripted player turns the wheel the right way). */
    int skillBeyond(String id) {
        for (int i = 0; i < tree().size() && i < nodeX.length; i++) {
            if (!tree().get(i).id().equals(id)) continue;
            int y = nodeY[i] - skillScroll + nodeH / 2;
            return y < treeTop ? -1 : y >= treeBottom ? 1 : 0;
        }
        return 2;
    }
    Carry carry() {
        String job = row() == null ? null : row().id();
        String skill = tree().isEmpty() ? null : tree().get(Math.min(selectedSkill, tree().size() - 1)).id();
        return new Carry(current, undergroundView, job, skill);
    }

    // ------------------------------------------------------------------------------------------------ chrome
    @Override protected int accent() {
        return current == Page.BLACK_MARKET ? UcUi.AMBER : current == Page.CONTRACTS && undergroundView ? UcUi.UNDERGROUND : UcUi.LEGAL;
    }
    @Override protected String titleText() { return "Operations"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.SHOP; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        boolean registered = !data.city().equals("UNREGISTERED");
        int x = right;
        if (registered && thiefView()) {
            TerminalData.Thief thief = data.thief();
            String level = "THIEF L" + thief.level();
            int w = UcUi.displayWidth(font, level) + 22;
            x -= w;
            UcUi.chip(g, font, level, x, py + 9, UcUi.AMBER, UcUi.Icon.KEY);
            String xp = thief.level() >= ProgressionRules.THIEF_MAX_LEVEL ? thief.xp() + " XP (top level)" : thief.xp() + " / " + thief.nextXp() + " XP";
            x -= 8 + font.width(xp);
            UcUi.text(g, font, xp, x, py + 12, UcUi.SOFT);
            return;
        }
        if (registered) {
            String career = capitalize(data.career()).toUpperCase(Locale.ROOT);
            int w = UcUi.displayWidth(font, career) + 22;
            x -= w;
            UcUi.chip(g, font, career, x, py + 9, UcUi.careerColor(data.career()), UcUi.careerIcon(data.career()));
            x -= 4;
            // the header says whose citizen you are; the footer says where you are standing (the two used to read as one unlabelled pair: VALEWOOD above, "In Ironhaven" below)
            String city = "HOME " + capitalize(data.city()).toUpperCase(Locale.ROOT);
            w = UcUi.displayWidth(font, city) + 10;
            x -= w;
            UcUi.chip(g, font, city, x, py + 9, UcUi.cityColor(data.city()), null);
        } else {
            String text = "Unregistered";
            int w = UcUi.displayWidth(font, text) + 22;
            UcUi.chip(g, font, text, right - w, py + 9, UcUi.UNDERGROUND, UcUi.Icon.WARNING);
        }
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        int ty = y + 8;
        boolean registered = !data.city().equals("UNREGISTERED");
        int x = px + PAD;
        int leftEnd = x;   // where the left-hand group ends: the place on the right gives way to it (a 1280x720 journey frame showed "1 skill poin" run into "Now in Ironhaven")
        if (registered && thiefView()) {
            // the Thief page's footer is the Thief track's: its level, the way to the next one and the points to spend
            TerminalData.Thief thief = data.thief();
            UcUi.icon(g, UcUi.Icon.KEY, x, y + 4, UcUi.AMBER);
            String level = "THIEF L" + thief.level();
            UcUi.display(g, font, level, x + 19, ty, UcUi.TEXT);
            int bx = x + 19 + UcUi.displayWidth(font, level) + 8;
            int from = ProgressionRules.thiefXpAtLevel(thief.level());
            float share = thief.level() >= ProgressionRules.THIEF_MAX_LEVEL ? 1f : (thief.xp() - from) / (float) Math.max(1, thief.nextXp() - from);
            UcUi.bar(g, bx, ty, 64, 7, Math.max(0f, Math.min(1f, share)), UcUi.AMBER);
            String xp = thief.xp() + " XP";
            UcUi.text(g, font, xp, bx + 70, ty, UcUi.DIM);
            leftEnd = bx + 70 + font.width(xp);
            if (thief.points() > 0) {
                int sx = leftEnd + 10;
                String points = thief.points() + (thief.points() == 1 ? " Thief point" : " Thief points");
                UcUi.icon(g, UcUi.Icon.POINT, sx, y + 4, UcUi.AMBER);
                UcUi.text(g, font, points, sx + 18, ty, UcUi.AMBER);
                leftEnd = sx + 18 + font.width(points);
            }
        } else if (registered) {
            UcUi.icon(g, UcUi.careerIcon(data.career()), x, y + 4, UcUi.careerColor(data.career()));
            String level = capitalize(data.career()).toUpperCase(Locale.ROOT) + " L" + data.level();
            UcUi.display(g, font, level, x + 19, ty, UcUi.TEXT);
            int bx = x + 19 + UcUi.displayWidth(font, level) + 8;
            UcUi.bar(g, bx, ty, 64, 7, ProgressionRules.levelProgress(data.xp()), UcUi.LEGAL);
            String xp = data.xp() + " XP";
            UcUi.text(g, font, xp, bx + 70, ty, UcUi.DIM);
            leftEnd = bx + 70 + font.width(xp);
            if (data.skillPoints() > 0) {
                int sx = bx + 70 + font.width(xp) + 10;
                String points = data.skillPoints() + (data.skillPoints() == 1 ? " skill point" : " skill points");
                UcUi.icon(g, UcUi.Icon.POINT, sx, y + 4, UcUi.AMBER);
                UcUi.text(g, font, points, sx + 18, ty, UcUi.AMBER);
                leftEnd = sx + 18 + font.width(points);
            }
        } else {
            String hint = "Register to see orders, skills and trade.";
            UcUi.text(g, font, hint, x, ty, UcUi.DIM);
            leftEnd = x + font.width(hint);
        }
        int right = px + pw - PAD;
        String credits = data.economy() ? String.format(Locale.ROOT, "%.0f", data.balance()) : "OFFLINE";
        int cw2 = UcUi.displayWidth(font, credits);
        UcUi.displayRight(g, font, credits, right - 0, ty, data.economy() ? UcUi.AMBER : UcUi.UNDERGROUND_TEXT);
        UcUi.icon(g, UcUi.Icon.CREDITS, right - cw2 - 19, y + 4, data.economy() ? UcUi.AMBER : UcUi.UNDERGROUND_TEXT);
        String where = data.counter() ? "At the counter" : data.wayside() ? "Now at a wayside desk" : data.region().isEmpty() ? "Now outside a city" : "Now in " + capitalize(data.region());
        String shortWhere = data.counter() ? "Counter" : data.wayside() ? "Wayside desk" : data.region().isEmpty() ? "Outside" : capitalize(data.region());
        for (String text : new String[] {where, shortWhere}) {
            int wx = right - cw2 - 19 - 14 - font.width(text);
            if (wx - 17 >= leftEnd + 12) {
                UcUi.text(g, font, text, wx, ty, UcUi.DIM);
                UcUi.icon(g, UcUi.Icon.PIN, wx - 17, y + 4, UcUi.DIM);
                break;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ widgets
    @Override protected void init() {
        layoutPanel(440, 286, 640, 410);
        clearWidgets();
        railW = pw >= 440 ? 86 : 34;
        cx = px + PAD + railW + 8;
        cw = px + pw - PAD - cx;
        cy = bodyY();
        ch = bodyBottom() - cy;
        for (int i = 0; i < rail.length; i++) {
            Page page = Page.values()[i];
            rail[i] = add(new UcButton(px + PAD, cy + i * 30, railW, 26, page.label, b -> showPage(page)).kind(UcButton.Kind.RAIL).icon(page.icon)
                .tooltip(railW < 50 ? page.label : ""));
        }
        shop = add(new UcButton(cx, cy, cw, 20, "Open civilian shop", b -> {
            if (terminalPosition != null) { shop.active = false; TerminalNetwork.requestStarterShop(terminalPosition); }
        }).icon(UcUi.Icon.SHOP));
        legalChip = add(new UcButton(cx, cy, 96, 16, "Legal", b -> setView(false)).kind(UcButton.Kind.CHIP).accent(UcUi.LEGAL).icon(UcUi.Icon.LEGAL)
            .tooltip("Dependable, licensed work. Always available and never a crime."));
        undergroundChip = add(new UcButton(cx + 100, cy, 128, 16, "Underground", b -> setView(true)).kind(UcButton.Kind.CHIP).accent(UcUi.UNDERGROUND).icon(UcUi.Icon.UNDERGROUND)
            .tooltip("Higher pay for restricted goods. It needs an underground skill, and carrying the goods past a checkpoint is a risk."));
        accept = add(new UcButton(0, 0, 100, 20, "Accept order", b -> send(row().accepted() ? 1 : 0)).kind(UcButton.Kind.PRIMARY));
        cancel = add(new UcButton(0, 0, 70, 20, "Cancel", b -> send(2)).kind(UcButton.Kind.DANGER));
        track = add(new UcButton(0, 0, 70, 16, "Track", b -> {
            if (row() == null) return;
            if (ClientNavigation.tracking("order", row().id())) NavigationNetwork.stop(); else NavigationNetwork.track("order", row().id());   // the chip stops what it started
        }).kind(UcButton.Kind.CHIP).icon(UcUi.Icon.ARROW_RIGHT)
            .tooltip("Show the way on the status display (and on Xaero's minimap when it is installed): the buyer's city, or where the job is."));
        supplier = add(new UcButton(0, 0, 90, 20, "Supplier", b -> send(3))
            .tooltip("Pay a supplier to fill this part of the Crew contract from your wallet. A filled part earns nobody XP or a share of the bonus."));
        register = add(new UcButton(cx, cy + ch - 24, cw, 20, "Register: choose your city and trade", b -> TerminalNetwork.requestRegistrationScreen()).kind(UcButton.Kind.PRIMARY).icon(UcUi.Icon.CITY));
        unlock = add(new UcButton(0, 0, 120, 20, "Unlock", b -> {
            if (terminalPosition != null && selectedSkill < tree().size()) { unlock.active = false; TerminalNetwork.requestSkill(terminalPosition, tree().get(selectedSkill).id()); }
        }).kind(UcButton.Kind.PRIMARY));
        showMe = add(new UcButton(0, 0, 100, 18, "Show me", b -> { Page page = pageOf(nextStep() == null ? "" : nextStep().page()); if (page != null) showPage(page); }).icon(UcUi.Icon.ARROW_RIGHT));
        for (int i = 0; i < MAX_OFFERS; i++) {
            int index = i;
            buyOne[i] = add(new UcButton(0, 0, 44, 18, "Buy 1", b -> buy(index, 1)));
            buyLot[i] = add(new UcButton(0, 0, 50, 18, "Buy " + LOT, b -> lotPressed(index)));
        }
        layoutTree();
        refresh();
    }

    // ------------------------------------------------------------------------------------------------ state
    /** The Skills page draws the career's tree; the Thief page (D-143) the Thief tree every career can follow, with its own points. */
    private boolean skillsPage() { return current == Page.SKILLS || current == Page.THIEF; }
    private boolean thiefView() { return current == Page.THIEF; }
    private List<TerminalData.SkillRow> tree() { return thiefView() ? data.thief().skills() : data.skills(); }
    private List<TerminalData.Row> jobs() { return data.jobs().stream().filter(job -> job.extra().underground() == undergroundView).toList(); }
    private TerminalData.Row row() { List<TerminalData.Row> list = jobs(); return list.isEmpty() ? null : list.get(Math.max(0, Math.min(selected, list.size() - 1))); }
    private boolean exchangeOpen() { return (!data.region().isEmpty() || data.shopOnly()) && !data.offers().isEmpty(); }
    private void setView(boolean underground) { undergroundView = underground; selected = 0; jobScroll = 0; refresh(); }
    private void send(int action) {
        if (terminalPosition == null || row() == null) return;
        accept.active = false; cancel.active = false;
        TerminalNetwork.requestWork(terminalPosition, action, row().id(), data.active());
    }
    /** How many of a line the player could sell to the shop now (D-164): what they carry, the order limit and the shelf's room; 0 when the shop buys none of it. */
    private int sellable(TerminalData.Offer offer) {
        if (!data.counter() || offer.pays() <= 0) return 0;
        return Math.max(0, Math.min(Math.min(offer.carried(), offer.maxOrder()), offer.capacity() - offer.stock()));
    }

    /** The second button of a row: Sell when the shop buys the line and the player carries some (D-164), otherwise Buy a lot. */
    private void lotPressed(int index) {
        if (terminalPosition == null || index + offerScroll >= data.offers().size()) return;
        TerminalData.Offer offer = data.offers().get(index + offerScroll);
        if (data.counter() && offer.pays() > 0 && offer.carried() > 0) {
            int n = sellable(offer);
            if (n <= 0) return;
            for (UcButton button : buyOne) button.active = false;
            for (UcButton button : buyLot) button.active = false;
            TerminalNetwork.requestShopSell(terminalPosition, offer.id(), n);
            return;
        }
        buy(index, Math.min(LOT, offer.maxOrder()));
    }

    /** Journey/QA hook: a real click on the Sell button of a line (scrolled into view first), or false when it is not offered. */
    boolean clickSellOffer(String id) {
        for (int i = 0; i < data.offers().size(); i++) {
            if (!data.offers().get(i).id().equals(id)) continue;
            int rows = Math.min(MAX_OFFERS, offerRows());
            if (i < offerScroll || i >= offerScroll + rows) { offerScroll = Math.max(0, i - rows + 1); layoutOffers(); }
            UcButton b = buyLot[i - offerScroll];
            if (!b.visible || !b.active) return false;
            return mouseClicked(b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0, 0);
        }
        return false;
    }

    private void buy(int index, int quantity) {
        if (terminalPosition == null || index + offerScroll >= data.offers().size()) return;
        for (UcButton button : buyOne) button.active = false;
        for (UcButton button : buyLot) button.active = false;
        TerminalNetwork.requestRegionalBuy(terminalPosition, data.offers().get(index + offerScroll).id(), quantity);
    }
    public void showPage(Page page) {
        boolean treeChanged = (page == Page.THIEF) != (current == Page.THIEF);
        current = page;
        if (treeChanged) { selectedSkill = 0; skillScroll = 0; layoutTree(); }
        refresh();
    }
    public Page currentPage() { return current; }
    /** QA hook: choose the legal or the underground contract list. */
    public void showView(boolean underground) { setView(underground); }
    /** QA hook: pick the n-th contract of the current list. */
    public void selectJob(int index) { selected = index; ensureVisible(); refresh(); }
    /** QA hook: pick a skill node by id. */
    public void selectSkill(String id) {
        for (int i = 0; i < tree().size(); i++) if (tree().get(i).id().equals(id)) selectedSkill = i;
        revealSelectedSkill();
        refresh();
    }
    /** QA hook: scroll the skill tree to a pixel offset (clamped), as the wheel or a drag would. */
    public void scrollSkillsTo(int pixels) { skillScroll = snapScroll(pixels); }
    /** QA hook: how far the tree can scroll (0 when it fits). */
    public int skillScrollRange() { return skillMaxScroll; }
    TerminalData snapshot() { return data; }

    private static Page pageOf(String name) {
        for (Page page : Page.values()) if (page.name().equals(name)) return page;
        return null;
    }
    private TerminalData.Step nextStep() { return data.journey().stream().filter(step -> !step.done()).findFirst().orElse(null); }

    private static boolean ready(TerminalData.Row job) { return job.reqs().stream().allMatch(req -> req.held() >= req.count()); }
    private static int missing(TerminalData.Row job) { return job.reqs().stream().mapToInt(req -> Math.max(0, req.count() - req.held())).sum(); }

    private String lockReason(TerminalData.Row job) {
        TerminalData.Extra extra = job.extra();
        if (!data.economy()) return "Q Shop 1.8.4 required";
        if (job.accepted()) {
            if (!ready(job)) return "Obtain the missing goods first";
            if (!extra.buyerCity().isEmpty() && !extra.buyerCity().equals(data.region())) return "Deliver to " + extra.buyer() + ": use a terminal inside " + capitalize(extra.buyerCity());
            return "";
        }
        if (data.active() != null) return "Finish or cancel your accepted order";
        if (!extra.skillOk()) return "Requires the skill " + extra.skill();
        if (data.level() < job.level()) return "Career level " + job.level() + " required";
        if (extra.minTrust() > extra.trust()) return extra.buyer() + " trusts you " + extra.trust() + "/" + extra.minTrust();
        if (data.foreignVisits() < job.foreignVisits()) return visitText(job);
        if (job.available() == 0) return "Employer demand replenishes during active play";
        return "";
    }
    private String visitText(TerminalData.Row job) {
        int more = job.foreignVisits() - data.foreignVisits();
        return "Use a terminal inside " + more + " more rival " + (more == 1 ? "city" : "cities") + " first";
    }
    private long count(boolean underground) { return data.jobs().stream().filter(job -> job.extra().underground() == underground).count(); }

    private void refresh() {
        if (shop == null) return;
        boolean registered = !data.city().equals("UNREGISTERED");
        for (int i = 0; i < rail.length; i++) {
            Page page = Page.values()[i];
            rail[i].selected(current == page).accent(page == Page.BLACK_MARKET ? UcUi.AMBER : UcUi.LEGAL);
            rail[i].active = true;
            if (page == Page.CONTRACTS) rail[i].badge(registered ? (int) data.jobs().stream().filter(job -> job.available() > 0).count() : -1);
            if (page == Page.SKILLS) rail[i].badge(registered && data.skillPoints() > 0 ? data.skillPoints() : -1);
            if (page == Page.THIEF) rail[i].badge(registered && data.thief().points() > 0 ? data.thief().points() : -1);
            if (page == Page.GUIDE) rail[i].badge(registered ? (int) data.journey().stream().filter(step -> !step.done()).count() : -1);
        }
        boolean contracts = current == Page.CONTRACTS, trade = current == Page.IMPORTS;
        // trade page
        shop.visible = trade && !data.counter();   // a shopkeeper's counter is its own shop; the civilian counter lives on the terminals
        shop.active = terminalPosition != null && data.economy();
        shop.tooltip(data.economy() ? "Buy and sell common goods using credits." : "Q Shop 1.8.4 and citizenship are required.");
        layoutOffers();
        // work page
        register.visible = !registered && (contracts || skillsPage());
        legalChip.visible = undergroundChip.visible = contracts && registered;
        legalChip.label("Legal " + count(false)).selected(!undergroundView);
        undergroundChip.label("Underground " + count(true)).selected(undergroundView);
        boolean work = contracts && row() != null;
        accept.visible = cancel.visible = work;
        supplier.visible = false;
        track.visible = false;
        if (work) {
            TerminalData.Row job = row();
            accept.label(job.accepted() ? "Deliver and collect" : "Accept order");
            String reason = lockReason(job);
            accept.active = terminalPosition != null && reason.isEmpty();
            accept.tooltip(reason);
            cancel.active = terminalPosition != null && job.accepted();
            cancel.tooltip(job.accepted() ? "Give the order up. Anything you carried stays with you." : "");
            int dx = detailX(), dy = listY() + detailH() - 26;
            boolean cancellable = job.accepted();
            cancel.visible = cancellable;
            // a Crew contract part nobody has brought in (D-170): a supplier can fill it, for the price its line states
            String crewLine = job.extra().rewards().isEmpty() ? "" : job.extra().rewards().get(0);
            boolean fillable = !job.accepted() && crewLine.startsWith("Crew:") && crewLine.contains("supplier ");
            supplier.visible = fillable;
            supplier.active = terminalPosition != null;
            if (fillable) supplier.label("Supplier " + crewLine.substring(crewLine.lastIndexOf("supplier ") + 9).trim());
            int right = (cancellable ? 76 : 0) + (fillable ? 96 : 0);
            accept.setX(dx + 8); accept.setY(dy); accept.setWidth(detailW() - 16 - right);
            cancel.setX(dx + detailW() - 8 - 70); cancel.setY(dy);
            supplier.setX(dx + detailW() - 8 - 90 - (cancellable ? 76 : 0)); supplier.setY(dy);
            // an accepted order with somewhere to go (a buyer's city, a job's target): the way there on the status display and the minimap (D-161, D-173)
            track.visible = job.accepted() && !job.extra().buyerCity().isEmpty();
            track.active = true;
            track.label(ClientNavigation.tracking("order", job.id()) ? "Stop" : "Track");
            track.setX(dx + detailW() - 8 - 70); track.setY(dy - 20);
        }
        // guide page
        showMe.visible = current == Page.GUIDE && nextStep() != null && !nextStep().page().isEmpty();
        // skills page
        boolean skills = skillsPage() && !tree().isEmpty();
        unlock.visible = skills;
        if (skills) {
            selectedSkill = Math.max(0, Math.min(selectedSkill, tree().size() - 1));
            TerminalData.SkillRow row = tree().get(selectedSkill);
            // the detail line beside it already says "Thief points"; with the word the label overran its button at GUI scale 3
            unlock.label(row.state() == 2 ? "Unlocked" : "Unlock: " + row.cost() + (row.cost() == 1 ? " point" : " points")).icon(row.state() == 2 ? UcUi.Icon.CHECK : UcUi.Icon.POINT);
            unlock.active = terminalPosition != null && row.state() == 1;
            unlock.tooltip(row.state() == 2 ? "" : row.reason());
            unlock.setX(cx + cw - 8 - 130); unlock.setY(cy + ch - 28); unlock.setWidth(130);
        }
    }

    private int listY() { return cy + 22; }
    private int listW() { return Math.max(130, Math.min(200, cw * 40 / 100)); }
    private int detailX() { return cx + listW() + 8; }
    private int detailW() { return cw - listW() - 8; }
    private int detailH() { return ch - 22; }
    private int visibleRows() { return Math.max(1, (ch - 22) / ROW_H); }
    private void ensureVisible() {
        int rows = visibleRows();
        if (selected < jobScroll) jobScroll = selected;
        if (selected >= jobScroll + rows) jobScroll = selected - rows + 1;
        jobScroll = Math.max(0, Math.min(jobScroll, Math.max(0, jobs().size() - rows)));
    }

    // ------------------------------------------------------------------------------------------------ trade rows
    /** The rows that fit under the heading; the well starts 40 pixels below the page top and the list 6 pixels inside it. */
    private int offerRows() { return Math.max(1, (ch - 46 - 6) / TradeLayout.ROW_H); }
    private int offerTop() { return cy + 46; }
    private TradeLayout tradeLayout() { return TradeLayout.of(cw); }

    private void layoutOffers() {
        boolean exchange = current == Page.IMPORTS && exchangeOpen();
        int rows = Math.min(MAX_OFFERS, offerRows());
        offerScroll = Math.max(0, Math.min(offerScroll, Math.max(0, data.offers().size() - rows)));
        TradeLayout layout = tradeLayout();
        for (int i = 0; i < MAX_OFFERS; i++) {
            boolean present = exchange && i < rows && i + offerScroll < data.offers().size();
            buyOne[i].visible = buyLot[i].visible = present;
            if (!present) continue;
            TerminalData.Offer offer = data.offers().get(i + offerScroll);
            buyLot[i].visible = Math.min(LOT, offer.maxOrder()) > 1;   // a line that sells one unit per order has one button, not two that say the same
            int y = offerTop() + i * TradeLayout.ROW_H + (TradeLayout.ROW_H - TradeLayout.BUTTON_H) / 2;
            buyOne[i].setX(cx + layout.buyOne.x()); buyOne[i].setY(y); buyOne[i].setWidth(layout.buyOne.w()); buyOne[i].setHeight(TradeLayout.BUTTON_H);
            buyLot[i].setX(cx + layout.buyLot.x()); buyLot[i].setY(y); buyLot[i].setWidth(layout.buyLot.w()); buyLot[i].setHeight(TradeLayout.BUTTON_H);
            int lot = Math.min(LOT, offer.maxOrder());
            buyLot[i].label("Buy " + lot);
            applyBuy(buyOne[i], offer, 1);
            applyBuy(buyLot[i], offer, lot);
            if (data.counter() && offer.pays() > 0 && offer.carried() > 0) {
                // the shop buys this line and the player carries some: the second button sells (D-164)
                int n = sellable(offer);
                buyLot[i].visible = true;
                buyLot[i].label("Sell " + Math.max(1, n));
                buyLot[i].active = terminalPosition != null && n > 0 && data.economy();
                buyLot[i].tooltip(n > 0 ? "Sell " + n + " x " + itemName(offer.item()) + " to the shop for " + offer.pays() * n + " credits (more for Fine or Reserve)"
                    : "The shelf is full. The shop buys again when its customers have bought some.");
            }
        }
    }

    /** The middle of the "Buy 1" button of an exchange line (scrolled into view first, as the wheel would), or null when the line is not listed. */
    int[] offerBuyCentre(String offerId) {
        for (int i = 0; i < data.offers().size(); i++) {
            if (!data.offers().get(i).id().equals(offerId)) continue;
            int rows = Math.min(MAX_OFFERS, offerRows());
            if (i < offerScroll || i >= offerScroll + rows) { offerScroll = Math.max(0, i - rows + 1); layoutOffers(); }
            UcButton b = buyOne[i - offerScroll];
            return b.visible ? new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2} : null;
        }
        return null;
    }

    /** Why this offer cannot be bought in this quantity right now, or "" when it can. */
    private String buyRefusal(TerminalData.Offer offer, int quantity) {
        if (!data.economy()) return "Q Shop 1.8.4 required";
        if (offer.stock() < quantity) return offer.stock() == 0 ? "Sold out; stock replenishes during active play" : "Only " + offer.stock() + " left";
        if (data.balance() < (double) offer.price() * quantity) return "Needs " + (offer.price() * quantity) + " credits; you have " + (int) data.balance();
        return "";
    }

    private void applyBuy(UcButton button, TerminalData.Offer offer, int quantity) {
        String reason = buyRefusal(offer, quantity);
        button.active = terminalPosition != null && reason.isEmpty();
        button.tooltip(reason.isEmpty() ? quantity + " x " + itemName(offer.item()) + " for " + offer.price() * quantity + " credits" : reason);
    }

    static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        Item value = location == null ? null : ForgeRegistries.ITEMS.getValue(location);
        return value == null ? Items.BARRIER : value;
    }
    /** A requirement's icon: the item, and for a TaCZ gun its first accepted model (a bare gun item draws as a missing texture). */
    static ItemStack icon(TerminalData.Req req) {
        ItemStack stack = new ItemStack(item(req.item()));
        if (!req.gun().isEmpty()) stack.getOrCreateTag().putString("GunId", req.gun());
        return stack;
    }

    static String itemName(String id) { return new ItemStack(item(id)).getHoverName().getString(); }
    static String capitalize(String id) { return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1); }

    /** Journey/QA hook: a real mouse click at the centre of the work page's primary button (Accept order, or Deliver once the goods are in hand). */
    boolean clickPrimary() { return mouseClicked(accept.getX() + accept.getWidth() / 2.0, accept.getY() + accept.getHeight() / 2.0, 0); }

    /** Journey/QA hook: a real mouse click at the centre of the trade page's "Open civilian shop" button. */
    boolean clickShop() { return mouseClicked(shop.getX() + shop.getWidth() / 2.0, shop.getY() + shop.getHeight() / 2.0, 0); }

    /** Journey/QA hook: a real mouse click at the centre of an offer's buy button. */
    boolean clickBuy(int row, boolean lot) {
        UcButton button = (lot ? buyLot : buyOne)[row];
        return mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
    }

    /** Journey/QA hook: turns the mouse wheel over the exchange list until the offer is on screen, then clicks its BUY 1 button. False when the offer is not listed or its button is not active. */
    boolean clickBuyOffer(String id) {
        int index = -1;
        for (int i = 0; i < data.offers().size(); i++) if (data.offers().get(i).id().equals(id)) index = i;
        if (index < 0 || current != Page.IMPORTS) return false;
        for (int guard = 0; guard < data.offers().size() && index >= offerScroll + offerRows(); guard++) mouseScrolled(0, 0, -1);
        layoutOffers();
        return index >= offerScroll && index - offerScroll < MAX_OFFERS && clickBuy(index - offerScroll, false);
    }

    // ------------------------------------------------------------------------------------------------ skill tree layout
    private void layoutTree() {
        int n = tree().size();
        nodeX = new int[n]; nodeY = new int[n]; nodeTier = new int[n]; nodeCol = new int[n];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(tree().get(i).id(), i);
        int gap = 6;
        for (int i = 0; i < n; i++) nodeTier[i] = depth(i, index, 0);
        // A node sits one row below its lowest parent and never on the row of another node in its own column, so two skills that follow the same
        // parent are stacked instead of drawn on top of each other (the second one used to be hidden).
        int[] row = new int[n];
        int[] lastInColumn = {-1, -1, -1};
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, java.util.Comparator.<Integer>comparingInt(i -> nodeTier[i]).thenComparingInt(i -> i));
        int maxRow = 0;
        for (int i : order) {
            TerminalData.SkillRow skill = tree().get(i);
            int col = SkillNode.column(skill.branch());
            nodeCol[i] = col;
            int r = 0;
            for (String id : skill.requires()) { Integer parent = index.get(id); if (parent != null) r = Math.max(r, row[parent] + 1); }
            r = Math.max(r, lastInColumn[col] + 1);
            row[i] = r;
            lastInColumn[col] = r;
            maxRow = Math.max(maxRow, r);
        }
        treeTop = cy + 16; treeBottom = cy + ch - 68;
        int viewH = treeBottom - treeTop;
        // Nodes keep their full height (two title lines and the state). A tree that is deeper than the page is scrolled, never squeezed: the viewport clips it.
        nodeH = NODE_H;
        int fitStep = maxRow == 0 ? NODE_H + 7 : (viewH - nodeH) / maxRow;
        skillStep = Math.max(NODE_H + 6, Math.min(42, fitStep));
        int contentH = maxRow * skillStep + nodeH;
        // the tree scrolls one row at a time, so a card is never cut in half at the top; the last stop leaves a little room below the final row
        int rawScroll = Math.max(0, contentH - viewH);
        skillMaxScroll = (rawScroll + skillStep - 1) / skillStep * skillStep;
        // a scroll bar takes the right edge of the page: the columns then stop short of it instead of running under it
        int colW = (cw - (skillMaxScroll > 0 ? SCROLLBAR_ROOM : 0) - 2 * gap) / 3;
        nodeW = colW;
        for (int i = 0; i < n; i++) {
            nodeX[i] = cx + nodeCol[i] * (colW + gap);
            nodeY[i] = treeTop + row[i] * skillStep;
        }
        skillScroll = snapScroll(skillScroll);
        revealSelectedSkill();
    }

    /** The scroll offset nearest to the pixels asked for that lies on a row boundary, within the tree's range. */
    private int snapScroll(int pixels) {
        return Math.max(0, Math.min(skillMaxScroll, Math.round(pixels / (float) skillStep) * skillStep));
    }

    /** Scrolls the tree, a whole row at a time, just far enough that the chosen node is fully inside the viewport. */
    private void revealSelectedSkill() {
        if (nodeY.length == 0 || selectedSkill < 0 || selectedSkill >= nodeY.length) return;
        int top = nodeY[selectedSkill] - skillScroll, bottom = top + nodeH;
        if (top < treeTop) skillScroll = snapScroll(nodeY[selectedSkill] - treeTop);
        else if (bottom > treeBottom) {
            int needed = nodeY[selectedSkill] + nodeH - treeBottom;
            skillScroll = Math.min(skillMaxScroll, (needed + skillStep - 1) / skillStep * skillStep);
        }
        skillScroll = Math.max(0, Math.min(skillScroll, skillMaxScroll));
    }

    private void scrollSkills(int pixels) { skillScroll = snapScroll(skillScroll + pixels); }

    private boolean inTree(double mouseX, double mouseY) { return skillsPage() && mouseX >= cx && mouseX < cx + cw && mouseY >= treeTop && mouseY < treeBottom; }

    private int depth(int i, Map<String, Integer> index, int guard) {
        if (guard > 8) return 0;
        int best = 0;
        for (String id : tree().get(i).requires()) {
            Integer parent = index.get(id);
            if (parent != null) best = Math.max(best, 1 + depth(parent, index, guard + 1));
        }
        return best;
    }

    /** The Thief tree's stealth column: a cool violet, apart from the career colours. */
    private static final int STEALTH = 0xff9d8cf0;

    private int branchColor(TerminalData.SkillRow row) {
        return switch (row.branch()) {
            case "underground", "network" -> UcUi.UNDERGROUND;
            case "legal" -> UcUi.LEGAL;
            case "crossover" -> UcUi.mix(UcUi.LEGAL, UcUi.UNDERGROUND, 0.5f);
            case "entry" -> UcUi.AMBER;
            case "stealth" -> STEALTH;
            default -> UcUi.SOFT;
        };
    }

    private int skillAt(double mouseX, double mouseY) {
        if (!inTree(mouseX, mouseY)) return -1;
        for (int i = 0; i < nodeX.length; i++) {
            int y = nodeY[i] - skillScroll;
            if (mouseX >= nodeX[i] && mouseX < nodeX[i] + nodeW && mouseY >= y && mouseY < y + nodeH) return i;
        }
        return -1;
    }

    private int jobAt(double mouseX, double mouseY) {
        if (current != Page.CONTRACTS) return -1;
        int x = cx, y = listY();
        if (mouseX < x || mouseX >= x + listW() - 6) return -1;
        int rel = (int) ((mouseY - y) / ROW_H);
        if (mouseY < y || rel < 0 || rel >= visibleRows()) return -1;
        int index = jobScroll + rel;
        return index < jobs().size() ? index : -1;
    }

    // ------------------------------------------------------------------------------------------------ input
    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int job = jobAt(mouseX, mouseY);
            if (job >= 0) { selected = job; refresh(); return true; }
            int skill = skillAt(mouseX, mouseY);
            if (skill >= 0) { selectedSkill = skill; revealSelectedSkill(); refresh(); return true; }
            if (inTree(mouseX, mouseY) && skillMaxScroll > 0) { draggingTree = true; dragFromY = mouseY; dragFromScroll = skillScroll; return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingTree) { skillScroll = Math.max(0, Math.min(skillMaxScroll, dragFromScroll - (int) Math.round(mouseY - dragFromY))); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingTree) { draggingTree = false; skillScroll = snapScroll(skillScroll); return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (current == Page.CONTRACTS) { jobScroll = Math.max(0, Math.min(jobScroll - (int) Math.signum(delta), Math.max(0, jobs().size() - visibleRows()))); return true; }
        if (current == Page.IMPORTS) { offerScroll = Math.max(0, offerScroll - (int) Math.signum(delta)); layoutOffers(); return true; }
        if (skillsPage() && skillMaxScroll > 0) { scrollSkills(-(int) Math.signum(delta) * skillStep); return true; }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= 49 && key <= 53) { showPage(Page.values()[key - 49]); return true; }
        if (skillsPage() && (key == 264 || key == 265)) { scrollSkills((key == 264 ? 1 : -1) * skillStep); return true; }
        if (current == Page.CONTRACTS && (key == 264 || key == 265)) {
            int size = jobs().size();
            if (size > 0) { selected = Math.max(0, Math.min(size - 1, selected + (key == 264 ? 1 : -1))); ensureVisible(); refresh(); }
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    // ------------------------------------------------------------------------------------------------ drawing
    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // the rail's backing well
        UcUi.well(g, px + PAD - 2, cy - 2, railW + 4, ch + 4);
        switch (current) {
            case CONTRACTS -> renderWork(g, mouseX, mouseY);
            case SKILLS, THIEF -> renderSkills(g, mouseX, mouseY);
            case IMPORTS -> renderTrade(g, mouseX, mouseY);
            case BLACK_MARKET -> renderLaw(g);
            case GUIDE -> renderGuide(g);
        }
    }

    private boolean registered() { return !data.city().equals("UNREGISTERED"); }

    private void emptyState(GuiGraphics g, UcUi.Icon icon, String title, String text) {
        int w = Math.min(cw - 30, 260), x = cx + (cw - w) / 2, y = cy + Math.max(10, ch / 2 - 56);
        UcUi.icon(g, icon, x + w / 2 - 8, y, UcUi.AMBER);
        UcUi.displayCentre(g, font, title, x + w / 2, y + 22, UcUi.TEXT);
        UcUi.wrapped(g, font, text, x, y + 38, w, UcUi.SOFT, 5);
    }

    // ---- WORK
    private void renderWork(GuiGraphics g, int mouseX, int mouseY) {
        if (!registered()) {
            emptyState(g, UcUi.Icon.CITY, "Welcome, citizen", "Register to see paid orders for your trade. Choose a home city and a profession; both legal and underground work stay open to you.");
            return;
        }
        List<TerminalData.Row> list = jobs();
        int lx = cx, ly = listY(), lw = listW(), lh = ch - 22;
        UcUi.well(g, lx, ly, lw, lh);
        if (list.isEmpty()) {
            UcUi.wrapped(g, font, data.counter() ? "This is a shop counter. Orders are taken at an Operations Terminal; this shop sells on the TRADE tab."
                : data.wayside() ? "Orders are posted in the cities. This desk only sells supplies: open the TRADE tab."
                : undergroundView ? "No underground contracts yet. Learn an underground skill on the SKILLS tab to open the trade."
                : "No legal contracts are listed for this trade.", lx + 8, ly + 10, lw - 16, UcUi.SOFT, 6);
            return;
        }
        int rows = visibleRows();
        int hover = jobAt(mouseX, mouseY);
        for (int r = 0; r < rows && jobScroll + r < list.size(); r++) {
            int index = jobScroll + r;
            TerminalData.Row job = list.get(index);
            int ry = ly + 2 + r * ROW_H;
            boolean sel = index == selected, over = index == hover;
            g.fill(lx + 2, ry, lx + lw - 8, ry + ROW_H - 2, sel ? 0xff1f3a52 : over ? UcUi.ROW_HOVER : UcUi.ROW);
            if (sel) g.fill(lx + 2, ry, lx + 5, ry + ROW_H - 2, accent());
            TerminalData.Req first = job.reqs().isEmpty() ? null : job.reqs().get(0);
            if (first != null) g.renderItem(icon(first), lx + 9, ry + 5);
            boolean locked = !job.accepted() && lockReason(job).length() > 0 && data.active() == null;
            UcUi.Icon status = job.accepted() ? (ready(job) ? UcUi.Icon.CHECK : UcUi.Icon.TRUCK) : locked ? UcUi.Icon.LOCK : null;
            g.drawString(font, UcUi.fit(font, job.title(), lw - 44 - (status != null ? 22 : 0)), lx + 30, ry + 4, sel ? UcUi.TEXT : UcUi.SOFT, false);
            UcUi.icon(g, UcUi.Icon.CREDITS, lx + 28, ry + 11, UcUi.AMBER);
            g.drawString(font, job.credits() + "   +" + job.xp() + " XP", lx + 44, ry + 15, UcUi.AMBER, false);
            if (status != null) UcUi.icon(g, status, lx + lw - 26, ry + 5, job.accepted() ? (ready(job) ? UcUi.OK : UcUi.AMBER) : UcUi.FAINT);
        }
        if (list.size() > rows) {
            int track = lh - 4, thumb = Math.max(10, track * rows / list.size());
            int ty = ly + 2 + (track - thumb) * jobScroll / Math.max(1, list.size() - rows);
            g.fill(lx + lw - 6, ly + 2, lx + lw - 3, ly + 2 + track, 0xff070b10);
            g.fill(lx + lw - 6, ty, lx + lw - 3, ty + thumb, UcUi.shade(accent(), 0.8f));
        }
        renderDetail(g);
    }

    private void renderDetail(GuiGraphics g) {
        TerminalData.Row job = row();
        if (job == null) return;
        TerminalData.Extra extra = job.extra();
        int x = detailX(), y = listY(), w = detailW(), h = detailH();
        UcUi.paper(g, x, y, w, h);
        int tx = x + 9, ty = y + 8;
        UcUi.display(g, font, UcUi.fit(font, job.title().toUpperCase(Locale.ROOT), w - 18), tx, ty, UcUi.INK);
        g.fill(tx, ty + 11, x + w - 9, ty + 12, 0xff8a7c5e);
        ty += 18;
        int darkTeal = 0xff1d7a69, darkRed = 0xff9a2f27, darkAmber = 0xff8a5a10, darkGreen = 0xff2d7a3a;
        String buyer = extra.buyer().isEmpty() ? "Open order, licensed work" : "Buyer: " + extra.buyer() + " (" + capitalize(extra.buyerCity()) + ")";
        UcUi.icon(g, extra.underground() ? UcUi.Icon.UNDERGROUND : UcUi.Icon.LEGAL, tx - 2, ty - 4, extra.underground() ? darkRed : darkTeal);
        boolean showTrust = extra.minTrust() > 0 || extra.underground();
        String trust = "trust " + extra.trust() + (extra.minTrust() > 0 ? "/" + extra.minTrust() : "");
        // the buyer's name gives way to the trust figure on the right, so the two never print over each other
        g.drawString(font, UcUi.fit(font, buyer, w - 40 - (showTrust ? font.width(trust) + 8 : 0)), tx + 16, ty, extra.underground() ? darkRed : darkTeal, false);
        if (showTrust) g.drawString(font, trust, x + w - 9 - font.width(trust), ty, UcUi.PAPER_DIM, false);
        ty += 16;
        for (TerminalData.Req req : job.reqs()) {
            boolean enough = req.held() >= req.count();
            g.renderItem(icon(req), tx, ty - 3);
            String name = req.label().isEmpty() ? itemName(req.item()) : req.label();
            g.drawString(font, UcUi.fit(font, req.count() + " x " + name, w - 100), tx + 21, ty, UcUi.INK, false);
            String have = req.held() + "/" + req.count();
            g.drawString(font, have, x + w - 9 - font.width(have), ty, enough ? darkGreen : darkRed, false);
            UcUi.bar(g, tx + 21, ty + 10, w - 40, 3, req.count() == 0 ? 1f : Math.min(1f, (float) req.held() / req.count()), enough ? darkGreen : darkAmber);
            ty += 20;
        }
        ty += 2;
        UcUi.icon(g, UcUi.Icon.CREDITS, tx - 2, ty - 4, darkAmber);
        g.drawString(font, "+" + job.credits() + " credits", tx + 16, ty, darkAmber, false);
        int xx = tx + 16 + font.width("+" + job.credits() + " credits") + 12;
        UcUi.icon(g, UcUi.Icon.XP, xx - 2, ty - 4, darkTeal);
        g.drawString(font, "+" + job.xp() + " XP  L" + job.level(), xx + 16, ty, darkTeal, false);
        ty += 14;
        java.util.List<String> also = new java.util.ArrayList<>(extra.rewards());
        if (!also.isEmpty() && also.get(0).startsWith("Crew:")) {
            // a Crew contract part (D-170): its progress on a line of its own
            UcUi.icon(g, UcUi.Icon.TRUST, tx - 2, ty - 4, darkTeal);
            String crew = also.remove(0).substring(5).trim();
            int price = crew.lastIndexOf("; supplier ");
            if (price >= 0) crew = crew.substring(0, price);   // the Supplier button shows the price (1280x720 cut it off the end of this line)
            g.drawString(font, UcUi.fit(font, crew, w - 36), tx + 16, ty, darkTeal, false);
            ty += 12;
        }
        if (!also.isEmpty()) {
            g.drawString(font, UcUi.fit(font, "Also: " + String.join(", ", also), w - 20), tx, ty, darkTeal, false);
            ty += 12;
        }
        String state;
        int stateColor = darkAmber;
        UcUi.Icon stateIcon = UcUi.Icon.INFO;
        if (job.accepted()) {
            boolean elsewhere = ready(job) && !extra.buyerCity().isEmpty() && !extra.buyerCity().equals(data.region());
            state = elsewhere ? "Ready: carry it to " + extra.buyer() + " in " + capitalize(extra.buyerCity()) + " and deliver at a terminal there"
                : ready(job) ? "Ready: deliver plain, undamaged goods" : "Missing " + missing(job) + " goods: produce them before delivering";
            stateColor = ready(job) ? darkGreen : darkAmber;
            stateIcon = ready(job) ? UcUi.Icon.CHECK : UcUi.Icon.WARNING;
        } else if (data.active() != null) { state = "Finish your accepted order first"; stateIcon = UcUi.Icon.LOCK; }
        else if (!extra.skillOk()) { state = "Locked: requires the skill " + extra.skill(); stateColor = darkRed; stateIcon = UcUi.Icon.LOCK; }
        else if (data.level() < job.level()) { state = "Locked: career level " + job.level() + " required"; stateColor = darkRed; stateIcon = UcUi.Icon.LOCK; }
        else if (extra.minTrust() > extra.trust()) { state = "Locked: " + extra.buyer() + " trusts you " + extra.trust() + "/" + extra.minTrust(); stateColor = darkRed; stateIcon = UcUi.Icon.LOCK; }
        else if (data.foreignVisits() < job.foreignVisits()) { state = "Locked: " + visitText(job); stateColor = darkRed; stateIcon = UcUi.Icon.LOCK; }
        else if (job.available() == 0) { state = "Employer demand is spent; it refills during active play"; stateColor = darkAmber; stateIcon = UcUi.Icon.CLOCK; }
        else { state = "Employer demand: " + job.available() + (job.available() == 1 ? " order" : " orders") + " left"; stateColor = darkTeal; stateIcon = UcUi.Icon.INFO; }
        // the Track chip sits at the right of this line's last row: the line wraps short of it (raid capture: "produce them" ran under the chip)
        int stateW = w - 36 - (track.visible ? 76 : 0);
        int stateY = y + h - 26 - 6 - UcUi.wrappedHeight(font, state, stateW, 3);
        if (stateY < ty) stateY = ty;
        UcUi.icon(g, stateIcon, tx - 2, stateY - 4, stateColor);
        UcUi.wrapped(g, font, state, tx + 16, stateY, stateW, stateColor, 3);
    }

    // ---- SKILLS
    private void renderSkills(GuiGraphics g, int mouseX, int mouseY) {
        if (tree().isEmpty()) {
            if (thiefView()) emptyState(g, UcUi.Icon.KEY, "No Thief tree yet", "Register to open the Thief tree. Any trade can follow it: Thief XP comes from getting into places, cracking safes and selling what you took.");
            else emptyState(g, UcUi.Icon.POINT, "No skill tree yet", "Register to open your trade's skill tree: a legal branch, an underground branch and the foundations between them. None locks out another.");
            return;
        }
        // column headings: the career's legal and underground lines, or the Thief's getting in, staying unseen and selling on
        int gap = 6, colW = nodeW;
        String[] titles = thiefView() ? new String[] {"Locks", "Stealth", "Fencing"} : new String[] {"Legal", "Trade", "Underground"};
        int[] colors = thiefView() ? new int[] {UcUi.AMBER, STEALTH, UcUi.UNDERGROUND} : new int[] {UcUi.LEGAL, UcUi.SOFT, UcUi.UNDERGROUND};
        UcUi.Icon[] icons = thiefView() ? new UcUi.Icon[] {UcUi.Icon.LOCK, UcUi.Icon.TARGET, UcUi.Icon.TAG} : new UcUi.Icon[] {UcUi.Icon.LEGAL, null, UcUi.Icon.UNDERGROUND};
        int[] headingEnd = new int[3];
        for (int c = 0; c < 3; c++) {
            int x = cx + c * (colW + gap);
            g.fill(x, cy + 12, x + colW, cy + 13, UcUi.shade(colors[c], 0.5f));
            int textX = x + 2 + (icons[c] != null ? 14 : 0);
            UcUi.display(g, font, titles[c], textX, cy + 2, colors[c]);
            headingEnd[c] = textX + UcUi.displayWidth(font, titles[c]);
            if (icons[c] != null) UcUi.icon(g, icons[c], x - 2, cy - 2, colors[c]);
        }
        // the tree is clipped to its viewport and scrolled; the headings above and the detail panel below stay put
        g.enableScissor(cx - 2, treeTop - 1, cx + cw + 2, treeBottom + 1);
        // links first, so nodes sit on top: thin and muted, brighter when the parent is owned, amber on the way to the selected skill
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < tree().size(); i++) index.put(tree().get(i).id(), i);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < tree().size(); i++) {
                boolean selectedLink = i == selectedSkill;
                if ((pass == 1) != selectedLink) continue;
                for (String parent : tree().get(i).requires()) {
                    Integer p = index.get(parent);
                    if (p == null) continue;
                    boolean lit = tree().get(p).state() == 2;
                    int color = selectedLink ? UcUi.AMBER : lit ? UcUi.shade(branchColor(tree().get(i)), 0.7f) : 0xff2c4358;
                    int t = selectedLink ? 2 : 1;
                    int x0 = nodeX[p] + nodeW / 2, y0 = nodeY[p] - skillScroll + nodeH, x1 = nodeX[i] + nodeW / 2, y1 = nodeY[i] - skillScroll;
                    int mid = y1 - 5;
                    g.fill(x0, y0, x0 + t, mid + t, color);
                    g.fill(Math.min(x0, x1), mid, Math.max(x0, x1) + t, mid + t, color);
                    g.fill(x1, mid, x1 + t, y1, color);
                }
            }
        }
        int hover = skillAt(mouseX, mouseY);
        for (int i = 0; i < tree().size(); i++) {
            int top = nodeY[i] - skillScroll;
            if (top + nodeH < treeTop || top > treeBottom) continue;   // not in view
            renderNode(g, i, i == selectedSkill, i == hover);
        }
        g.disableScissor();
        if (skillMaxScroll > 0) {
            // a thin scroll bar on the right edge and a hint while there is more below
            int track = treeBottom - treeTop, thumb = Math.max(14, track * track / (track + skillMaxScroll));
            int thumbY = treeTop + (int) ((long) (track - thumb) * skillScroll / skillMaxScroll);
            g.fill(cx + cw - 3, treeTop, cx + cw - 1, treeBottom, 0xff16222d);
            g.fill(cx + cw - 3, thumbY, cx + cw - 1, thumbY + thumb, draggingTree ? UcUi.AMBER : UcUi.SOFT);
            // the hint sits in the empty end of a heading (the middle one first), never over a heading or a card; with no room it is left out (the bar says the same).
            // The Thief page's "Stealth" and its icon left no room in the middle at GUI scale 2 (the real client, Thief run 11)
            String hint = skillScroll < skillMaxScroll ? "More below" : "More above";
            for (int c : new int[] {1, 2, 0}) {
                int hintRight = cx + c * (colW + gap) + colW - 4;
                if (hintRight - font.width(hint) < headingEnd[c] + 6) continue;
                UcUi.text(g, font, hint, hintRight - font.width(hint), cy + 3, UcUi.DIM);
                break;
            }
        }
        renderSkillDetail(g);
    }

    private void renderNode(GuiGraphics g, int i, boolean sel, boolean over) {
        TerminalData.SkillRow row = tree().get(i);
        int x = nodeX[i], y = nodeY[i] - skillScroll, w = nodeW, h = nodeH;
        int color = branchColor(row);
        int fill = row.state() == 2 ? UcUi.mix(0xff0d1620, color, 0.26f) : row.state() == 1 ? 0xff182b3b : 0xff111a24;
        int border = row.state() == 2 ? UcUi.shade(color, 0.9f) : row.state() == 1 ? color : 0xff22323f;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, sel ? UcUi.AMBER : over ? UcUi.TEXT : 0xff05080c);
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y + 1, x + 25, y + h - 1, 0xff0a121a);
        UcUi.Icon icon = switch (row.branch()) {
            case "underground" -> UcUi.Icon.UNDERGROUND; case "legal" -> UcUi.Icon.LEGAL; case "crossover" -> UcUi.Icon.SCALES;
            case "entry" -> UcUi.Icon.LOCK; case "stealth" -> UcUi.Icon.TARGET; case "network" -> UcUi.Icon.TAG;
            default -> thiefView() ? UcUi.Icon.KEY : UcUi.careerIcon(data.career());
        };
        UcUi.icon(g, icon, x + 5, y + 7, row.state() == 0 ? UcUi.FAINT : color);
        // the full name on up to two lines, then the state: never a truncated skill name
        List<String> lines = UcUi.wrapLines(font, row.title(), w - 32);
        int maxLines = h >= 29 ? 2 : 1;
        if (lines.size() > maxLines) lines = maxLines == 1 ? List.of(UcUi.fit(font, String.join(" ", lines), w - 32))
            : List.of(lines.get(0), UcUi.fit(font, String.join(" ", lines.subList(1, lines.size())), w - 32));
        int ty = lines.size() == 2 ? y + 1 : y + (h >= 29 ? 6 : 3);
        for (String line : lines) { g.drawString(font, line, x + 28, ty, row.state() == 0 ? UcUi.DIM : UcUi.TEXT, false); ty += 9; }
        String sub = row.state() == 2 ? "OWNED" : row.state() == 1 ? "READY  " + row.cost() + " pt" : "LOCKED  L" + row.minLevel();
        UcUi.display(g, font, sub, x + 28, y + h - (lines.size() == 2 ? 9 : 10), row.state() == 2 ? UcUi.OK : row.state() == 1 ? UcUi.AMBER : UcUi.FAINT);
    }

    private void renderSkillDetail(GuiGraphics g) {
        TerminalData.SkillRow row = tree().get(Math.min(selectedSkill, tree().size() - 1));
        int y = cy + ch - 64, color = branchColor(row);
        UcUi.well(g, cx, y, cw, 64);
        g.fill(cx + 1, y + 1, cx + 4, y + 63, color);
        UcUi.display(g, font, UcUi.fit(font, row.title(), cw - 160), cx + 10, y + 6, color);
        String meta = row.state() == 2 ? "Unlocked" : row.cost() + (thiefView() ? " Thief" : "") + (row.cost() == 1 ? " point" : " points") + "  |  " + (thiefView() ? "Thief level " : "level ") + row.minLevel();
        UcUi.text(g, font, meta, cx + 10, y + 17, row.state() == 2 ? UcUi.OK : UcUi.AMBER);
        boolean hasEffects = !row.effects().isEmpty();
        UcUi.wrapped(g, font, row.description(), cx + 10, y + 29, cw - 156, UcUi.SOFT, hasEffects ? 2 : 3);
        if (hasEffects) UcUi.wrapped(g, font, row.effects(), cx + 10, y + 49, cw - 156, row.state() == 2 ? UcUi.OK : UcUi.AMBER, 1);
        String note = row.state() == 2 ? "" : !row.reason().isEmpty() ? row.reason() : "Ready to unlock";
        if (!note.isEmpty()) {
            int nx = cx + cw - 8 - 130;
            UcUi.wrapped(g, font, note, nx, y + 6, 130, row.state() == 1 ? UcUi.LEGAL : UcUi.AMBER, 2);
        }
    }

    // ---- TRADE
    private void renderTrade(GuiGraphics g, int mouseX, int mouseY) {
        boolean exchange = exchangeOpen();
        int y = cy + 26;
        String heading = data.counter() ? data.shopTitle().toUpperCase(Locale.ROOT) : data.wayside() ? "WAYSIDE PROVISIONS" : exchange ? capitalize(data.region()).toUpperCase(Locale.ROOT) + " EXCHANGE" : "REGIONAL EXCHANGE";
        UcUi.display(g, font, heading, cx, y, UcUi.LEGAL);
        int subtitleX = cx + UcUi.displayWidth(font, heading) + 8;
        // true at every counter, a city's or a roadside place's (the roadside lines are their place's alone, and the longer line was cut off at 1280 x 720: journey run 65)
        String subtitle = data.counter() ? "Open while the shopkeeper is in"
            : data.wayside() ? "A few basics, shared with " + capitalize(data.city().isEmpty() ? "the city" : data.city()) + "'s desks" : "Shared stock, refilled during active play";
        UcUi.text(g, font, UcUi.fit(font, subtitle, cx + cw - subtitleX), subtitleX, y, UcUi.DIM);
        UcUi.well(g, cx, cy + 40, cw, ch - 40);
        if (!exchange) {
            int ty = cy + 52;
            UcUi.icon(g, UcUi.Icon.INFO, cx + 8, ty - 4, UcUi.AMBER);
            UcUi.wrapped(g, font, data.counter() ? "The shelves behind this counter are empty for now. Stock comes back during active play."
                : data.wayside() ? "This desk has nothing for sale right now."
                : data.region().isEmpty() ? "This terminal is outside every city exchange. Use one inside Ironhaven, Valewood or Blackwater to see local goods."
                : "No regional stock is listed for this city.", cx + 28, ty, cw - 40, UcUi.SOFT, 4);
            return;
        }
        TradeLayout layout = tradeLayout();
        int rows = Math.min(MAX_OFFERS, offerRows());
        for (int i = 0; i < rows && i + offerScroll < data.offers().size(); i++) {
            TerminalData.Offer offer = data.offers().get(i + offerScroll);
            int ry = offerTop() + i * TradeLayout.ROW_H;
            if (i % 2 == 0) g.fill(cx + 2, ry, cx + cw - 2, ry + TradeLayout.ROW_H, 0xff111c27);
            g.renderItem(new ItemStack(item(offer.item())), cx + layout.icon.x(), ry + (TradeLayout.ROW_H - 16) / 2);
            boolean soldOut = offer.stock() == 0, poor = data.economy() && data.balance() < offer.price();
            // line one: the name, cut to its own place
            g.drawString(font, UcUi.fit(font, itemName(offer.item()), layout.name.w()), cx + layout.name.x(), ry + 5, soldOut ? UcUi.FAINT : UcUi.TEXT, false);
            // line two, left: the price; right: the stock, or one status in its place
            int priceColor = poor ? UcUi.UNDERGROUND_TEXT : UcUi.AMBER;
            UcUi.icon(g, UcUi.Icon.CREDITS, cx + layout.price.x() - 3, ry + 13, priceColor);
            g.drawString(font, UcUi.fit(font, offer.price() + " each", layout.price.w() - 12), cx + layout.price.x() + 12, ry + 17, priceColor, false);
            int sx = cx + layout.stockOrStatus.x(), sw = layout.stockOrStatus.w();
            String status = soldOut ? "Sold out" : data.economy() && poor ? "Need " + (int) Math.ceil(offer.price() - data.balance()) + " more" : "";
            if (!status.isEmpty()) {
                g.drawString(font, UcUi.fit(font, status, sw), sx, ry + 17, soldOut ? UcUi.UNDERGROUND_TEXT : UcUi.WARN, false);
            } else {
                int barW = Math.min(TradeLayout.BAR_W, Math.max(0, sw - 40));
                if (barW >= 12) UcUi.bar(g, sx, ry + 19, barW, 5, offer.capacity() == 0 ? 0f : (float) offer.stock() / offer.capacity(), UcUi.LEGAL);
                String count = offer.stock() + "/" + offer.capacity() + (data.counter() && offer.pays() > 0 ? "  buys at " + offer.pays() : "");
                g.drawString(font, UcUi.fit(font, count, Math.max(0, sw - (barW >= 12 ? barW + 4 : 0))), sx + (barW >= 12 ? barW + 4 : 0), ry + 17, data.counter() && offer.pays() > 0 && offer.carried() > 0 ? UcUi.OK : UcUi.DIM, false);
            }
        }
        int more = data.offers().size() - rows;
        if (more > 0) {
            // a real scroll bar in the well's right edge, and the count in the heading row where nothing else is drawn
            int trackTop = offerTop(), trackH = rows * TradeLayout.ROW_H, trackX = cx + cw - 5;
            g.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0xff0a1017);
            int thumbH = Math.max(14, trackH * rows / data.offers().size());
            int thumbY = trackTop + (trackH - thumbH) * offerScroll / Math.max(1, more);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, UcUi.LEGAL);
        }
    }

    /** The goods under the mouse get the whole name, the price and why they cannot be bought, in one tooltip; the Buy buttons keep their own. */
    @Override protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (current != Page.IMPORTS || !exchangeOpen()) return;
        for (int i = 0; i < Math.min(MAX_OFFERS, offerRows()) && i + offerScroll < data.offers().size(); i++) {
            int ry = offerTop() + i * TradeLayout.ROW_H;
            TradeLayout layout = tradeLayout();
            if (mouseX < cx + 2 || mouseX >= cx + layout.buyOne.x() - 4 || mouseY < ry || mouseY >= ry + TradeLayout.ROW_H) continue;
            TerminalData.Offer offer = data.offers().get(i + offerScroll);
            java.util.List<net.minecraft.network.chat.Component> lines = new java.util.ArrayList<>();
            lines.add(net.minecraft.network.chat.Component.literal(itemName(offer.item())));
            lines.add(net.minecraft.network.chat.Component.literal(offer.price() + " credits each, " + offer.stock() + " of " + offer.capacity() + " in stock"));
            if (data.counter() && offer.pays() > 0)
                lines.add(net.minecraft.network.chat.Component.literal("The shop buys it at " + offer.pays() + " a unit while the shelf has room" + (offer.carried() > 0 ? "; you carry " + offer.carried() : "")));
            String reason = buyRefusal(offer, 1);
            if (!reason.isEmpty()) lines.add(net.minecraft.network.chat.Component.literal(reason));
            UcUi.tooltip(g, font, lines, mouseX, mouseY, width, height);
            return;
        }
    }

    // ---- LAW
    private void renderLaw(GuiGraphics g) {
        if (data.region().isEmpty() || data.laws().isEmpty()) {
            emptyState(g, UcUi.Icon.SCALES, "Law and contacts", "Use a terminal inside a city to see its law, its buyers and how closely you are watched. Possession is never detected by itself; searches happen at road checkpoints and hand-offs can be seen.");
            return;
        }
        int half = (cw - 8) * 62 / 100;
        UcUi.display(g, font, capitalize(data.region()) + " law", cx, cy + 2, UcUi.AMBER);
        UcUi.well(g, cx, cy + 16, half, ch - 16);
        // heat: five pips and what they mean
        UcUi.icon(g, UcUi.Icon.HEAT, cx + 6, cy + 20, data.heat() == 0 ? UcUi.DIM : UcUi.UNDERGROUND);
        for (int p = 0; p < 5; p++) {
            int hx = cx + 26 + p * 11;
            g.fill(hx, cy + 27, hx + 9, cy + 32, p < data.heat() ? UcUi.UNDERGROUND : 0xff2a1a1a);
            g.fill(hx, cy + 27, hx + 9, cy + 28, p < data.heat() ? UcUi.UNDERGROUND_TEXT : 0xff3a2424);
        }
        UcUi.text(g, font, UcUi.fit(font, data.heat() == 0 ? "Quiet: no extra attention" : "Heat " + data.heat() + ": more searches", half - 92), cx + 86, cy + 26, data.heat() == 0 ? UcUi.DIM : UcUi.UNDERGROUND_TEXT);
        g.fill(cx + 4, cy + 40, cx + half - 4, cy + 41, 0xff1d2f40);
        int y = UcUi.wrapped(g, font, data.lawSummary(), cx + 8, cy + 46, half - 16, UcUi.SOFT, 4) + 5;
        UcUi.display(g, font, "Controlled goods here", cx + 8, y, UcUi.DIM);
        y += 13;
        for (TerminalData.LawRow law : data.laws()) {
            if (y + 12 > cy + ch - 4) break;
            String status = law.status().equals("permit") ? (law.licensed() ? "LICENSED" : "PERMIT") : law.status().toUpperCase(Locale.ROOT);
            int color = law.status().equals("legal") || law.licensed() ? UcUi.LEGAL : law.status().equals("permit") ? UcUi.AMBER : UcUi.UNDERGROUND;
            int chipW = UcUi.displayWidth(font, status) + 10;
            g.drawString(font, UcUi.fit(font, law.title(), half - 24 - chipW), cx + 8, y, UcUi.SOFT, false);
            UcUi.chip(g, font, status, cx + half - 8 - chipW, y - 3, color, null);
            y += 15;
        }
        int rx = cx + half + 8, rw = cw - half - 8;
        UcUi.display(g, font, "Buyers and contacts", rx, cy + 2, UcUi.AMBER);
        UcUi.well(g, rx, cy + 16, rw, ch - 16);
        int cyy = cy + 24;
        if (data.contacts().isEmpty()) {
            UcUi.wrapped(g, font, "No contacts yet. Learn an underground skill to be introduced.", rx + 8, cyy, rw - 16, UcUi.DIM, 3);
        }
        for (TerminalData.Contact contact : data.contacts()) {
            if (cyy + 26 > cy + ch - 4) break;
            int color = contact.underground() ? UcUi.UNDERGROUND_TEXT : UcUi.LEGAL;
            UcUi.icon(g, contact.underground() ? UcUi.Icon.UNDERGROUND : UcUi.Icon.LEGAL, rx + 6, cyy - 3, contact.underground() ? UcUi.UNDERGROUND : UcUi.LEGAL);
            g.drawString(font, UcUi.fit(font, contact.title(), rw - 36), rx + 26, cyy, color, false);
            if (contact.underground()) {
                for (int p = 0; p < BuyerCatalog.MAX_TRUST; p++) {
                    int px2 = rx + 26 + p * 10;
                    g.fill(px2, cyy + 11, px2 + 8, cyy + 16, p < contact.trust() ? UcUi.UNDERGROUND : 0xff2a1a1a);
                    g.fill(px2, cyy + 11, px2 + 8, cyy + 12, p < contact.trust() ? UcUi.UNDERGROUND_TEXT : 0xff3a2424);
                }
                UcUi.text(g, font, UcUi.fit(font, "trust " + contact.trust() + "/" + BuyerCatalog.MAX_TRUST, rw - 26 - BuyerCatalog.MAX_TRUST * 10 - 8), rx + 26 + BuyerCatalog.MAX_TRUST * 10 + 4, cyy + 10, UcUi.DIM);
            } else {
                UcUi.text(g, font, UcUi.fit(font, "licensed, open to all", rw - 32), rx + 26, cyy + 10, UcUi.DIM);
            }
            cyy += 26;
        }
    }

    // ---- GUIDE
    private void renderGuide(GuiGraphics g) {
        if (!registered() || data.journey().isEmpty()) {
            emptyState(g, UcUi.Icon.BOOK, "Your first half hour", "Register, meet your employer, take an order, deliver it, learn a skill, buy something, travel. A short signpost, not a tutorial: ignore it whenever you like.");
            return;
        }
        UcUi.display(g, font, "First half hour", cx, cy + 2, UcUi.AMBER);
        long done = data.journey().stream().filter(TerminalData.Step::done).count();
        UcUi.displayRight(g, font, done + " of " + data.journey().size(), cx + cw, cy + 2, UcUi.DIM);
        UcUi.bar(g, cx, cy + 14, cw, 4, (float) done / data.journey().size(), UcUi.LEGAL);
        TerminalData.Step next = nextStep();
        int y = cy + 24;
        int rowH = Math.max(16, Math.min(28, (ch - 28 - 18) / data.journey().size()));
        for (TerminalData.Step step : data.journey()) {
            boolean isNext = step == next;
            int h = isNext ? Math.max(rowH, 42) : rowH;
            g.fill(cx, y, cx + cw, y + h - 2, isNext ? 0xff22384c : 0xff111c27);
            if (isNext) g.fill(cx, y, cx + 3, y + h - 2, UcUi.AMBER);
            UcUi.icon(g, step.done() ? UcUi.Icon.CHECK : isNext ? UcUi.Icon.ARROW_RIGHT : UcUi.Icon.CLOCK, cx + 6, y + (h - 2) / 2 - 8, step.done() ? UcUi.OK : isNext ? UcUi.AMBER : UcUi.FAINT);
            UcUi.display(g, font, step.title(), cx + 28, y + 4, step.done() ? UcUi.DIM : isNext ? UcUi.TEXT : UcUi.SOFT);
            if (isNext) {
                UcUi.wrapped(g, font, step.hint(), cx + 28, y + 15, cw - 150, UcUi.SOFT, 2);
                showMe.setX(cx + cw - 104); showMe.setY(y + (h - 2) / 2 - 9); showMe.setWidth(98);
            }
            y += h;
        }
    }
}
