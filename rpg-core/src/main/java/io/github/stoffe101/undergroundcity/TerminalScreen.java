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
        CONTRACTS("Work", UcUi.Icon.CRATE), SKILLS("Skills", UcUi.Icon.POINT), IMPORTS("Trade", UcUi.Icon.SHOP),
        BLACK_MARKET("Law", UcUi.Icon.SCALES), GUIDE("Guide", UcUi.Icon.BOOK);
        final String label;
        final UcUi.Icon icon;
        Page(String label, UcUi.Icon icon) { this.label = label; this.icon = icon; }
    }

    /** What survives a server refresh of an open terminal. */
    public record Carry(Page page, boolean underground, String jobId, String skillId) {}

    private static final int LOT = 4, ROW_H = 28, MAX_OFFERS = 16, NODE_H = 33;

    private Page current = Page.CONTRACTS;
    private boolean undergroundView;
    private final BlockPos terminalPosition;
    private final TerminalData data;
    private int selected, jobScroll, selectedSkill, offerScroll;
    private int railW, cx, cy, cw, ch;
    private UcButton shop, accept, cancel, unlock, legalChip, undergroundChip, register, showMe;
    private final UcButton[] rail = new UcButton[Page.values().length];
    private final UcButton[] buyOne = new UcButton[MAX_OFFERS], buyLot = new UcButton[MAX_OFFERS];
    // the skill tree, laid out once per snapshot and size: node cards by index
    private int[] nodeX = new int[0], nodeY = new int[0], nodeTier = new int[0], nodeCol = new int[0];
    private int nodeW, nodeH = NODE_H;

    public TerminalScreen() { this(null, TerminalData.offline(), null); }
    public TerminalScreen(BlockPos position, TerminalData data) { this(position, data, null); }
    public TerminalScreen(BlockPos position, TerminalData data, Carry carry) {
        super(Component.literal("Underground City | Operations"));
        this.terminalPosition = position;
        this.data = data;
        TerminalData.Row accepted = data.jobs().stream().filter(TerminalData.Row::accepted).findFirst().orElse(null);
        if (carry != null) { current = carry.page(); undergroundView = carry.underground(); }
        if (accepted != null) undergroundView = accepted.extra().underground();
        List<TerminalData.Row> list = jobs();
        selected = 0;
        if (accepted != null) selected = Math.max(0, indexOf(list, accepted.id()));
        else if (carry != null && carry.jobId() != null) selected = Math.max(0, indexOf(list, carry.jobId()));
        if (carry != null && carry.skillId() != null) {
            for (int i = 0; i < data.skills().size(); i++) if (data.skills().get(i).id().equals(carry.skillId())) selectedSkill = i;
        }
    }

    private static int indexOf(List<TerminalData.Row> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id().equals(id)) return i;
        return -1;
    }

    BlockPos position() { return terminalPosition; }
    Carry carry() {
        String job = row() == null ? null : row().id();
        String skill = data.skills().isEmpty() ? null : data.skills().get(Math.min(selectedSkill, data.skills().size() - 1)).id();
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
        if (registered) {
            String career = capitalize(data.career()).toUpperCase(Locale.ROOT);
            int w = UcUi.displayWidth(font, career) + 22;
            x -= w;
            UcUi.chip(g, font, career, x, py + 9, UcUi.careerColor(data.career()), UcUi.careerIcon(data.career()));
            x -= 4;
            String city = capitalize(data.city()).toUpperCase(Locale.ROOT);
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
        if (registered) {
            UcUi.icon(g, UcUi.careerIcon(data.career()), x, y + 4, UcUi.careerColor(data.career()));
            String level = capitalize(data.career()).toUpperCase(Locale.ROOT) + " L" + data.level();
            UcUi.display(g, font, level, x + 19, ty, UcUi.TEXT);
            int bx = x + 19 + UcUi.displayWidth(font, level) + 8;
            UcUi.bar(g, bx, ty, 64, 7, ProgressionRules.levelProgress(data.xp()), UcUi.LEGAL);
            String xp = data.xp() + " XP";
            UcUi.text(g, font, xp, bx + 70, ty, UcUi.DIM);
            if (data.skillPoints() > 0) {
                int sx = bx + 70 + font.width(xp) + 10;
                UcUi.icon(g, UcUi.Icon.POINT, sx, y + 4, UcUi.AMBER);
                UcUi.text(g, font, data.skillPoints() + (data.skillPoints() == 1 ? " skill point" : " skill points"), sx + 18, ty, UcUi.AMBER);
            }
        } else {
            UcUi.text(g, font, "Register to see orders, skills and trade.", x, ty, UcUi.DIM);
        }
        int right = px + pw - PAD;
        String credits = data.economy() ? String.format(Locale.ROOT, "%.0f", data.balance()) : "OFFLINE";
        int cw2 = UcUi.displayWidth(font, credits);
        UcUi.displayRight(g, font, credits, right - 0, ty, data.economy() ? UcUi.AMBER : UcUi.UNDERGROUND_TEXT);
        UcUi.icon(g, UcUi.Icon.CREDITS, right - cw2 - 19, y + 4, data.economy() ? UcUi.AMBER : UcUi.UNDERGROUND_TEXT);
        String where = data.region().isEmpty() ? "Outside a city" : "In " + capitalize(data.region());
        int wx = right - cw2 - 19 - 14 - font.width(where);
        if (wx > px + pw / 2) {
            UcUi.text(g, font, where, wx, ty, UcUi.DIM);
            UcUi.icon(g, UcUi.Icon.PIN, wx - 17, y + 4, UcUi.DIM);
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
        register = add(new UcButton(cx, cy + ch - 24, cw, 20, "Register: choose your city and trade", b -> TerminalNetwork.requestRegistrationScreen()).kind(UcButton.Kind.PRIMARY).icon(UcUi.Icon.CITY));
        unlock = add(new UcButton(0, 0, 120, 20, "Unlock", b -> {
            if (terminalPosition != null && selectedSkill < data.skills().size()) { unlock.active = false; TerminalNetwork.requestSkill(terminalPosition, data.skills().get(selectedSkill).id()); }
        }).kind(UcButton.Kind.PRIMARY));
        showMe = add(new UcButton(0, 0, 100, 18, "Show me", b -> { Page page = pageOf(nextStep() == null ? "" : nextStep().page()); if (page != null) showPage(page); }).icon(UcUi.Icon.ARROW_RIGHT));
        for (int i = 0; i < MAX_OFFERS; i++) {
            int index = i;
            buyOne[i] = add(new UcButton(0, 0, 44, 18, "Buy 1", b -> buy(index, 1)));
            buyLot[i] = add(new UcButton(0, 0, 50, 18, "Buy " + LOT, b -> buy(index, LOT)));
        }
        layoutTree();
        refresh();
    }

    // ------------------------------------------------------------------------------------------------ state
    private List<TerminalData.Row> jobs() { return data.jobs().stream().filter(job -> job.extra().underground() == undergroundView).toList(); }
    private TerminalData.Row row() { List<TerminalData.Row> list = jobs(); return list.isEmpty() ? null : list.get(Math.max(0, Math.min(selected, list.size() - 1))); }
    private boolean exchangeOpen() { return !data.region().isEmpty() && !data.offers().isEmpty(); }
    private void setView(boolean underground) { undergroundView = underground; selected = 0; jobScroll = 0; refresh(); }
    private void send(int action) {
        if (terminalPosition == null || row() == null) return;
        accept.active = false; cancel.active = false;
        TerminalNetwork.requestWork(terminalPosition, action, row().id(), data.active());
    }
    private void buy(int index, int quantity) {
        if (terminalPosition == null || index + offerScroll >= data.offers().size()) return;
        for (UcButton button : buyOne) button.active = false;
        for (UcButton button : buyLot) button.active = false;
        TerminalNetwork.requestRegionalBuy(terminalPosition, data.offers().get(index + offerScroll).id(), quantity);
    }
    public void showPage(Page page) { current = page; refresh(); }
    public Page currentPage() { return current; }
    /** QA hook: choose the legal or the underground contract list. */
    public void showView(boolean underground) { setView(underground); }
    /** QA hook: pick the n-th contract of the current list. */
    public void selectJob(int index) { selected = index; ensureVisible(); refresh(); }
    /** QA hook: pick a skill node by id. */
    public void selectSkill(String id) {
        for (int i = 0; i < data.skills().size(); i++) if (data.skills().get(i).id().equals(id)) selectedSkill = i;
        refresh();
    }
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
            if (page == Page.GUIDE) rail[i].badge(registered ? (int) data.journey().stream().filter(step -> !step.done()).count() : -1);
        }
        boolean contracts = current == Page.CONTRACTS, trade = current == Page.IMPORTS;
        // trade page
        shop.visible = trade;
        shop.active = terminalPosition != null && data.economy();
        shop.tooltip(data.economy() ? "Buy and sell common goods using credits." : "Q Shop 1.8.4 and citizenship are required.");
        layoutOffers();
        // work page
        register.visible = !registered && (contracts || current == Page.SKILLS);
        legalChip.visible = undergroundChip.visible = contracts && registered;
        legalChip.label("Legal " + count(false)).selected(!undergroundView);
        undergroundChip.label("Underground " + count(true)).selected(undergroundView);
        boolean work = contracts && row() != null;
        accept.visible = cancel.visible = work;
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
            accept.setX(dx + 8); accept.setY(dy); accept.setWidth(detailW() - 16 - (cancellable ? 76 : 0));
            cancel.setX(dx + detailW() - 8 - 70); cancel.setY(dy);
        }
        // guide page
        showMe.visible = current == Page.GUIDE && nextStep() != null && !nextStep().page().isEmpty();
        // skills page
        boolean skills = current == Page.SKILLS && !data.skills().isEmpty();
        unlock.visible = skills;
        if (skills) {
            selectedSkill = Math.max(0, Math.min(selectedSkill, data.skills().size() - 1));
            TerminalData.SkillRow row = data.skills().get(selectedSkill);
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
    private int offerRows() { return Math.max(1, (ch - 56) / 26); }

    private void layoutOffers() {
        boolean exchange = current == Page.IMPORTS && exchangeOpen();
        int rows = offerRows();
        offerScroll = Math.max(0, Math.min(offerScroll, Math.max(0, data.offers().size() - rows)));
        for (int i = 0; i < MAX_OFFERS; i++) {
            boolean present = exchange && i < rows && i + offerScroll < data.offers().size();
            buyOne[i].visible = buyLot[i].visible = present;
            if (!present) continue;
            TerminalData.Offer offer = data.offers().get(i + offerScroll);
            int y = cy + 56 + i * 26;
            buyOne[i].setX(cx + cw - 100); buyOne[i].setY(y + 2);
            buyLot[i].setX(cx + cw - 52); buyLot[i].setY(y + 2);
            int lot = Math.min(LOT, offer.maxOrder());
            buyLot[i].label("Buy " + lot);
            applyBuy(buyOne[i], offer, 1);
            applyBuy(buyLot[i], offer, lot);
        }
    }

    private void applyBuy(UcButton button, TerminalData.Offer offer, int quantity) {
        String reason = !data.economy() ? "Q Shop 1.8.4 required" : offer.stock() < quantity ? (offer.stock() == 0 ? "Sold out; stock replenishes during active play" : "Only " + offer.stock() + " left")
            : data.balance() < (double) offer.price() * quantity ? "Needs " + (offer.price() * quantity) + " credits" : "";
        button.active = terminalPosition != null && reason.isEmpty();
        button.tooltip(reason.isEmpty() ? quantity + " x " + itemName(offer.item()) + " for " + offer.price() * quantity + " credits" : reason);
    }

    static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        Item value = location == null ? null : ForgeRegistries.ITEMS.getValue(location);
        return value == null ? Items.BARRIER : value;
    }
    static String itemName(String id) { return item(id).getDescription().getString(); }
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

    // ------------------------------------------------------------------------------------------------ skill tree layout
    private void layoutTree() {
        int n = data.skills().size();
        nodeX = new int[n]; nodeY = new int[n]; nodeTier = new int[n]; nodeCol = new int[n];
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) index.put(data.skills().get(i).id(), i);
        int gap = 6;
        int colW = (cw - 2 * gap) / 3;
        nodeW = colW;
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
            TerminalData.SkillRow skill = data.skills().get(i);
            int col = skill.branch().equals("legal") ? 0 : skill.branch().equals("underground") ? 2 : 1;
            nodeCol[i] = col;
            int r = 0;
            for (String id : skill.requires()) { Integer parent = index.get(id); if (parent != null) r = Math.max(r, row[parent] + 1); }
            r = Math.max(r, lastInColumn[col] + 1);
            row[i] = r;
            lastInColumn[col] = r;
            maxRow = Math.max(maxRow, r);
        }
        int treeTop = cy + 16, treeH = ch - 16 - 68;
        // Up to five rows keep the full node (two title lines and the state); a deeper tree gets shorter nodes with one title line rather than a scroll.
        nodeH = Math.max(24, Math.min(NODE_H, (treeH - 3 * maxRow) / (maxRow + 1)));
        int step = Math.max(nodeH + 3, Math.min(42, (treeH - nodeH) / Math.max(1, maxRow)));
        for (int i = 0; i < n; i++) {
            nodeX[i] = cx + nodeCol[i] * (colW + gap);
            nodeY[i] = treeTop + row[i] * step;
        }
    }

    private int depth(int i, Map<String, Integer> index, int guard) {
        if (guard > 8) return 0;
        int best = 0;
        for (String id : data.skills().get(i).requires()) {
            Integer parent = index.get(id);
            if (parent != null) best = Math.max(best, 1 + depth(parent, index, guard + 1));
        }
        return best;
    }

    private int branchColor(TerminalData.SkillRow row) { return row.branch().equals("underground") ? UcUi.UNDERGROUND : row.branch().equals("legal") ? UcUi.LEGAL : UcUi.SOFT; }

    private int skillAt(double mouseX, double mouseY) {
        if (current != Page.SKILLS) return -1;
        for (int i = 0; i < nodeX.length; i++) {
            if (mouseX >= nodeX[i] && mouseX < nodeX[i] + nodeW && mouseY >= nodeY[i] && mouseY < nodeY[i] + nodeH) return i;
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
            if (skill >= 0) { selectedSkill = skill; refresh(); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (current == Page.CONTRACTS) { jobScroll = Math.max(0, Math.min(jobScroll - (int) Math.signum(delta), Math.max(0, jobs().size() - visibleRows()))); return true; }
        if (current == Page.IMPORTS) { offerScroll = Math.max(0, offerScroll - (int) Math.signum(delta)); layoutOffers(); return true; }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= 49 && key <= 53) { showPage(Page.values()[key - 49]); return true; }
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
            case SKILLS -> renderSkills(g, mouseX, mouseY);
            case IMPORTS -> renderTrade(g);
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
            UcUi.wrapped(g, font, undergroundView ? "No underground contracts yet. Learn an underground skill on the SKILLS tab to open the trade."
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
            if (first != null) g.renderItem(new ItemStack(item(first.item())), lx + 9, ry + 5);
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
            g.renderItem(new ItemStack(item(req.item())), tx, ty - 3);
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
        if (!extra.rewards().isEmpty()) {
            g.drawString(font, UcUi.fit(font, "Also: " + String.join(", ", extra.rewards()), w - 20), tx, ty, darkTeal, false);
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
        int stateY = y + h - 26 - 6 - UcUi.wrappedHeight(font, state, w - 36, 3);
        if (stateY < ty) stateY = ty;
        UcUi.icon(g, stateIcon, tx - 2, stateY - 4, stateColor);
        UcUi.wrapped(g, font, state, tx + 16, stateY, w - 36, stateColor, 3);
    }

    // ---- SKILLS
    private void renderSkills(GuiGraphics g, int mouseX, int mouseY) {
        if (data.skills().isEmpty()) {
            emptyState(g, UcUi.Icon.POINT, "No skill tree yet", "Register to open your trade's skill tree: a legal branch, an underground branch and the foundations between them. None locks out another.");
            return;
        }
        // column headings
        int gap = 6, colW = nodeW;
        String[] titles = {"Legal", "Foundation", "Underground"};
        int[] colors = {UcUi.LEGAL, UcUi.SOFT, UcUi.UNDERGROUND};
        UcUi.Icon[] icons = {UcUi.Icon.LEGAL, null, UcUi.Icon.UNDERGROUND};
        for (int c = 0; c < 3; c++) {
            int x = cx + c * (colW + gap);
            g.fill(x, cy + 12, x + colW, cy + 13, UcUi.shade(colors[c], 0.5f));
            UcUi.display(g, font, titles[c], x + 2 + (icons[c] != null ? 14 : 0), cy + 2, colors[c]);
            if (icons[c] != null) UcUi.icon(g, icons[c], x - 2, cy - 2, colors[c]);
        }
        // links first, so nodes sit on top: thin and muted, brighter when the parent is owned, amber on the way to the selected skill
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < data.skills().size(); i++) index.put(data.skills().get(i).id(), i);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < data.skills().size(); i++) {
                boolean selectedLink = i == selectedSkill;
                if ((pass == 1) != selectedLink) continue;
                for (String parent : data.skills().get(i).requires()) {
                    Integer p = index.get(parent);
                    if (p == null) continue;
                    boolean lit = data.skills().get(p).state() == 2;
                    int color = selectedLink ? UcUi.AMBER : lit ? UcUi.shade(branchColor(data.skills().get(i)), 0.7f) : 0xff2c4358;
                    int t = selectedLink ? 2 : 1;
                    int x0 = nodeX[p] + nodeW / 2, y0 = nodeY[p] + nodeH, x1 = nodeX[i] + nodeW / 2, y1 = nodeY[i];
                    int mid = y1 - 5;
                    g.fill(x0, y0, x0 + t, mid + t, color);
                    g.fill(Math.min(x0, x1), mid, Math.max(x0, x1) + t, mid + t, color);
                    g.fill(x1, mid, x1 + t, y1, color);
                }
            }
        }
        int hover = skillAt(mouseX, mouseY);
        for (int i = 0; i < data.skills().size(); i++) renderNode(g, i, i == selectedSkill, i == hover);
        renderSkillDetail(g);
    }

    private void renderNode(GuiGraphics g, int i, boolean sel, boolean over) {
        TerminalData.SkillRow row = data.skills().get(i);
        int x = nodeX[i], y = nodeY[i], w = nodeW, h = nodeH;
        int color = branchColor(row);
        int fill = row.state() == 2 ? UcUi.mix(0xff0d1620, color, 0.26f) : row.state() == 1 ? 0xff182b3b : 0xff111a24;
        int border = row.state() == 2 ? UcUi.shade(color, 0.9f) : row.state() == 1 ? color : 0xff22323f;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, sel ? UcUi.AMBER : over ? UcUi.TEXT : 0xff05080c);
        g.fill(x, y, x + w, y + h, border);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y + 1, x + 25, y + h - 1, 0xff0a121a);
        UcUi.Icon icon = row.branch().equals("underground") ? UcUi.Icon.UNDERGROUND : row.branch().equals("legal") ? UcUi.Icon.LEGAL : UcUi.careerIcon(data.career());
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
        TerminalData.SkillRow row = data.skills().get(Math.min(selectedSkill, data.skills().size() - 1));
        int y = cy + ch - 64, color = branchColor(row);
        UcUi.well(g, cx, y, cw, 64);
        g.fill(cx + 1, y + 1, cx + 4, y + 63, color);
        UcUi.display(g, font, UcUi.fit(font, row.title(), cw - 160), cx + 10, y + 6, color);
        String meta = row.state() == 2 ? "Unlocked" : row.cost() + (row.cost() == 1 ? " point" : " points") + "  |  level " + row.minLevel();
        UcUi.text(g, font, meta, cx + 10, y + 17, row.state() == 2 ? UcUi.OK : UcUi.AMBER);
        UcUi.wrapped(g, font, row.description(), cx + 10, y + 29, cw - 156, UcUi.SOFT, 3);
        String note = row.state() == 2 ? "" : !row.reason().isEmpty() ? row.reason() : "Ready to unlock";
        if (!note.isEmpty()) {
            int nx = cx + cw - 8 - 130;
            UcUi.wrapped(g, font, note, nx, y + 6, 130, row.state() == 1 ? UcUi.LEGAL : UcUi.AMBER, 2);
        }
    }

    // ---- TRADE
    private void renderTrade(GuiGraphics g) {
        boolean exchange = exchangeOpen();
        int y = cy + 26;
        String heading = exchange ? capitalize(data.region()).toUpperCase(Locale.ROOT) + " EXCHANGE" : "REGIONAL EXCHANGE";
        UcUi.display(g, font, heading, cx, y, UcUi.LEGAL);
        UcUi.text(g, font, "Shared stock, refilled during active play", cx + UcUi.displayWidth(font, heading) + 8, y, UcUi.DIM);
        UcUi.well(g, cx, cy + 40, cw, ch - 40);
        if (!exchange) {
            int ty = cy + 52;
            UcUi.icon(g, UcUi.Icon.INFO, cx + 8, ty - 4, UcUi.AMBER);
            UcUi.wrapped(g, font, data.region().isEmpty() ? "This terminal is outside every city exchange. Use one inside Ironhaven, Valewood or Blackwater to see local goods."
                : "No regional stock is listed for this city.", cx + 28, ty, cw - 40, UcUi.SOFT, 4);
            return;
        }
        int rows = offerRows();
        for (int i = 0; i < rows && i + offerScroll < data.offers().size(); i++) {
            TerminalData.Offer offer = data.offers().get(i + offerScroll);
            int ry = cy + 56 + i * 26;
            if (i % 2 == 0) g.fill(cx + 2, ry - 2, cx + cw - 2, ry + 22, 0xff111c27);
            g.renderItem(new ItemStack(item(offer.item())), cx + 8, ry + 1);
            g.drawString(font, UcUi.fit(font, itemName(offer.item()), cw - 250), cx + 30, ry, UcUi.TEXT, false);
            boolean soldOut = offer.stock() == 0, poor = data.economy() && data.balance() < offer.price();
            UcUi.icon(g, UcUi.Icon.CREDITS, cx + 28, ry + 7, poor ? UcUi.UNDERGROUND_TEXT : UcUi.AMBER);
            g.drawString(font, offer.price() + " each", cx + 44, ry + 11, poor ? UcUi.UNDERGROUND_TEXT : UcUi.AMBER, false);
            int bx = cx + cw - 250 + 90;
            UcUi.bar(g, bx, ry + 4, 70, 5, offer.capacity() == 0 ? 0f : (float) offer.stock() / offer.capacity(), soldOut ? UcUi.UNDERGROUND : UcUi.LEGAL);
            String stock = soldOut ? "SOLD OUT" : offer.stock() + "/" + offer.capacity();
            g.drawString(font, stock, bx, ry + 11, soldOut ? UcUi.UNDERGROUND_TEXT : UcUi.DIM, false);
            if (!soldOut && poor) g.drawString(font, "need " + (int) Math.ceil(offer.price() - data.balance()) + " more", bx + 52, ry + 11, UcUi.WARN, false);
        }
        if (data.offers().size() > rows) UcUi.text(g, font, "Scroll for " + (data.offers().size() - rows) + " more lines", cx + 8, cy + ch - 12, UcUi.DIM);
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
