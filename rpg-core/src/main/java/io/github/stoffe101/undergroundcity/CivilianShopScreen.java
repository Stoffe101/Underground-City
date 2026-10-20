package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The civilian counter (the common shops of the cities: seeds, rations, a workbench, the things a citizen delivers for a few credits) in the Underground City style. Q Shop owns the shop, its
 * limits, the wallet and every transaction; this screen only replaces the picture of it. A press sends Q Shop's own trade request ({@link Trader}), the server validates it exactly as it does for
 * Q Shop's native screen, and the refreshed shop arrives as a new screen that keeps this one's tab and scroll. Nothing the screen shows is used to decide a price or a balance.
 *
 * <p>Layout rules, like the terminal's Trade page: the rows are {@link TradeLayout} (proved disjoint at every width), a status text replaces the limit text instead of sharing its place, the
 * name is cut with an ellipsis and carried whole in the tooltip, and a list longer than the page scrolls with the wheel and a bar.
 */
@OnlyIn(Dist.CLIENT)
public final class CivilianShopScreen extends UcScreen {
    /** Sends Q Shop's trade request; the production implementation is {@link CivilianShopBridge}, a capture or a test passes its own. */
    interface Trader { void trade(String shopId, int tabIndex, int entryIndex, int units); }

    /** What the player last asked for, so the refreshed shop can say whether it worked. */
    private record Pending(String shopId, String name, int units, double balanceBefore, long at) {}

    private static Pending pending;
    private static String carriedShop = "";
    private static int carriedTab, carriedScroll;

    private static final int LOT = 4, MAX_ROWS = 24, RAIL_WIDE = 112, RAIL_MID = 84, RAIL_NARROW = 34, GAP = 8, NOTE_MILLIS = 8000;

    private final CivilianShopData data;
    private final Trader trader;
    private int tab, scroll, cooldown;
    private int railW, cx, cy, cw, ch;
    private String note = "";
    private int noteColor = UcUi.DIM;
    private UcButton[] rail = new UcButton[0];
    private final UcButton[] buyOne = new UcButton[MAX_ROWS], buyLot = new UcButton[MAX_ROWS];

    CivilianShopScreen(CivilianShopData data, Trader trader) {
        super(Component.literal(data.name()));
        this.data = data;
        this.trader = trader;
        if (carriedShop.equals(data.id())) { tab = Mth.clamp(carriedTab, 0, Math.max(0, data.tabs().size() - 1)); scroll = Math.max(0, carriedScroll); }
        resolvePending();
    }

    @Override protected int accent() { return UcUi.LEGAL; }
    @Override protected String titleText() { return data.name().isEmpty() ? "Civilian shop" : data.name(); }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.SHOP; }
    @Override protected boolean showFooter() { return true; }

    private CivilianShopData.Tab current() { return data.tabs().isEmpty() ? null : data.tabs().get(Mth.clamp(tab, 0, data.tabs().size() - 1)); }
    private List<CivilianShopData.Entry> entries() { var t = current(); return t == null ? List.of() : t.entries(); }

    /** After a purchase the server sends the shop again; the new screen compares the wallet with what it was and says what happened. */
    private void resolvePending() {
        Pending p = pending;
        pending = null;
        if (p == null || !p.shopId().equals(data.id()) || System.currentTimeMillis() - p.at() > NOTE_MILLIS) return;
        if (data.balance() < p.balanceBefore() - 1e-6) { note = p.name() + (p.units() > 1 ? " x" + p.units() : "") + ": done"; noteColor = UcUi.OK; }
        else if (data.balance() > p.balanceBefore() + 1e-6) { note = p.name() + (p.units() > 1 ? " x" + p.units() : "") + ": sold"; noteColor = UcUi.OK; }
        else { note = "Nothing changed. The reason is in the chat"; noteColor = UcUi.WARN; }
    }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(300, 190, 560, 440);
        railW = data.tabs().size() > 1 ? (bodyW() >= 520 ? RAIL_WIDE : bodyW() >= 340 ? RAIL_MID : RAIL_NARROW) : 0;
        cx = bodyX() + (railW == 0 ? 0 : railW + GAP);
        cy = bodyY();
        cw = bodyW() - (railW == 0 ? 0 : railW + GAP);
        ch = bodyH();
        rail = new UcButton[data.tabs().size()];
        for (int i = 0; i < rail.length; i++) {
            final int index = i;
            CivilianShopData.Tab t = data.tabs().get(i);
            rail[i] = add(new UcButton(bodyX(), bodyY() + i * 26, railW, 24, railW >= 50 ? UcUi.fit(font, t.name(), railW - (railW >= RAIL_WIDE ? 56 : 34)) : "", button -> select(index))
                .kind(UcButton.Kind.RAIL).icon(UcUi.Icon.EMPTY).accent(UcUi.LEGAL).tooltip(t.name(), t.entries().size() + (t.entries().size() == 1 ? " thing here" : " things here")));
            rail[i].visible = railW > 0;
            rail[i].selected(i == tab);
            rail[i].badge(railW >= RAIL_WIDE ? t.entries().size() : -1);
        }
        for (int i = 0; i < MAX_ROWS; i++) {
            final int row = i;
            buyOne[i] = add(new UcButton(0, 0, TradeLayout.BUY_ONE_W, TradeLayout.BUTTON_H, "Buy 1", button -> press(row, false)).kind(UcButton.Kind.PRIMARY));
            buyLot[i] = add(new UcButton(0, 0, TradeLayout.BUY_LOT_W, TradeLayout.BUTTON_H, "Buy " + LOT, button -> press(row, true)));
        }
        layoutRows();
    }

    private void select(int index) {
        tab = index;
        scroll = 0;
        for (int i = 0; i < rail.length; i++) rail[i].selected(i == tab);
        layoutRows();
    }

    // ------------------------------------------------------------------------------------------------ rows
    private int listTop() { return cy + 24; }
    private int rows() { return Math.max(1, Math.min(MAX_ROWS, (ch - 24 - 6) / TradeLayout.ROW_H)); }
    private TradeLayout layout() { return TradeLayout.of(cw); }
    private int maxScroll() { return Math.max(0, entries().size() - rows()); }

    private int unitsAvailable(CivilianShopData.Entry entry) {
        int left = entry.left();
        return left < 0 ? 64 : left;
    }

    /** What the client can tell about an attempt before sending it; the server's answer is the one that counts. Empty when the attempt looks fine. */
    private String refusal(CivilianShopData.Entry entry, int units) {
        if (unitsAvailable(entry) < units) return unitsAvailable(entry) == 0 ? "Nothing left" : "Only " + unitsAvailable(entry) + " left";
        if (entry.sell()) {
            int have = held(entry.stack());
            int need = entry.count() * units;
            return have >= need ? "" : "You have " + have + " of the " + need + " wanted";
        }
        double cost = entry.price() * units;
        return data.balance() + 1e-9 >= cost ? "" : "Needs " + fmt(cost) + "; you have " + fmt(data.balance());
    }

    private static int held(ItemStack wanted) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        int total = 0;
        for (ItemStack stack : mc.player.getInventory().items) if (ItemStack.isSameItemSameTags(stack, wanted)) total += stack.getCount();
        for (ItemStack stack : mc.player.getInventory().offhand) if (ItemStack.isSameItemSameTags(stack, wanted)) total += stack.getCount();
        return total;
    }

    /** Review captures start every screen from nothing: the tab and scroll a closed screen left behind are forgotten. */
    static void forgetCarried() { carriedShop = ""; carriedTab = 0; carriedScroll = 0; pending = null; }

    static String fmt(double value) { return CivilianShopData.fmt(value); }

    private void layoutRows() {
        int rows = rows();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        TradeLayout layout = layout();
        for (int i = 0; i < MAX_ROWS; i++) {
            boolean present = i < rows && i + scroll < entries().size();
            buyOne[i].visible = buyLot[i].visible = present;
            if (!present) continue;
            CivilianShopData.Entry entry = entries().get(i + scroll);
            int lot = Math.min(LOT, Math.max(1, unitsAvailable(entry)));
            buyLot[i].visible = lot > 1;
            int y = listTop() + i * TradeLayout.ROW_H + (TradeLayout.ROW_H - TradeLayout.BUTTON_H) / 2;
            buyOne[i].setX(cx + layout.buyOne.x()); buyOne[i].setY(y); buyOne[i].setWidth(layout.buyOne.w()); buyOne[i].setHeight(TradeLayout.BUTTON_H);
            buyLot[i].setX(cx + layout.buyLot.x()); buyLot[i].setY(y); buyLot[i].setWidth(layout.buyLot.w()); buyLot[i].setHeight(TradeLayout.BUTTON_H);
            String verb = entry.sell() ? "Sell " : "Buy ";
            buyOne[i].label(verb + 1);
            buyLot[i].label(verb + lot);
            apply(buyOne[i], entry, 1, verb);
            apply(buyLot[i], entry, lot, verb);
        }
    }

    private void apply(UcButton button, CivilianShopData.Entry entry, int units, String verb) {
        String reason = refusal(entry, units);
        button.active = cooldown == 0 && reason.isEmpty();
        int items = entry.count() * units;
        button.tooltip(reason.isEmpty()
            ? verb.trim() + " " + items + " x " + entry.name() + (entry.sell() ? " for " : " for ") + fmt(entry.price() * units) + " " + data.currency()
            : reason);
        button.kind(units == 1 ? UcButton.Kind.PRIMARY : UcButton.Kind.PLATE);
    }

    private void press(int row, boolean lot) {
        int index = row + scroll;
        if (index < 0 || index >= entries().size() || cooldown > 0) return;
        CivilianShopData.Entry entry = entries().get(index);
        int units = lot ? Math.min(LOT, Math.max(1, unitsAvailable(entry))) : 1;
        if (!refusal(entry, units).isEmpty()) return;
        pending = new Pending(data.id(), entry.name(), units, data.balance(), System.currentTimeMillis());
        carriedShop = data.id(); carriedTab = tab; carriedScroll = scroll;
        cooldown = 8; // the refreshed shop arrives within a few ticks; until then a second press would be a second purchase
        layoutRows();
        trader.trade(data.id(), current().serverIndex(), entry.serverIndex(), units);
    }

    @Override public void tick() {
        if (cooldown > 0 && --cooldown == 0) layoutRows();
    }

    @Override public void removed() { carriedShop = data.id(); carriedTab = tab; carriedScroll = scroll; }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        if (maxScroll() == 0) return super.mouseScrolled(x, y, delta);
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, maxScroll());
        layoutRows();
        return true;
    }

    // ------------------------------------------------------------------------------------------------ drawing
    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String text = "Fixed prices";
        int w = UcUi.displayWidth(font, text) + 22;
        UcUi.chip(g, font, text, right - w, py + 9, UcUi.AMBER, UcUi.Icon.TAG);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        int ty = y + 8;
        int right = px + pw - PAD;
        String wallet = fmt(data.balance()) + " " + data.currency();
        int w = font.width(wallet) + 20;
        UcUi.icon(g, UcUi.Icon.CREDITS, right - w, y + 4, UcUi.AMBER);
        UcUi.text(g, font, wallet, right - w + 19, ty, UcUi.AMBER);
        int left = px + PAD;
        String text = !note.isEmpty() ? note : "Wheel scrolls. Prices are fixed; limits come back each day.";
        UcUi.text(g, font, UcUi.fit(font, text, Math.max(20, right - w - 12 - left)), left, ty, note.isEmpty() ? UcUi.DIM : noteColor);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        for (int i = 0; i < rail.length && railW > 0; i++) {
            ItemStack icon = data.tabs().get(i).icon();
            if (!icon.isEmpty()) g.renderItem(icon, rail[i].getX() + (railW < 50 ? (railW - 16) / 2 : 8), rail[i].getY() + 4);
        }
        CivilianShopData.Tab t = current();
        String heading = t == null || t.name().isEmpty() ? data.name() : t.name();
        UcUi.display(g, font, heading, cx, cy + 4, UcUi.LEGAL);
        String sub = entries().stream().anyMatch(CivilianShopData.Entry::sell) && entries().stream().anyMatch(e -> !e.sell()) ? "Goods for sale and goods wanted"
            : entries().stream().allMatch(CivilianShopData.Entry::sell) && !entries().isEmpty() ? "Goods the shop buys from you" : "Goods for sale";
        int subX = cx + UcUi.displayWidth(font, heading) + 8;
        UcUi.text(g, font, UcUi.fit(font, sub, cx + cw - subX), subX, cy + 4, UcUi.DIM);
        UcUi.well(g, cx, cy + 20, cw, ch - 20);
        if (entries().isEmpty()) {
            int ty = cy + 32;
            UcUi.icon(g, UcUi.Icon.INFO, cx + 8, ty - 4, UcUi.AMBER);
            UcUi.wrapped(g, font, data.empty() ? "Nothing is on this counter right now. Daily limits come back at dawn."
                : "Nothing in this section is left today. Try another.", cx + 28, ty, cw - 40, UcUi.SOFT, 4);
            return;
        }
        TradeLayout layout = layout();
        int rows = rows();
        for (int i = 0; i < rows && i + scroll < entries().size(); i++) {
            CivilianShopData.Entry entry = entries().get(i + scroll);
            int ry = listTop() + i * TradeLayout.ROW_H;
            if (i % 2 == 0) g.fill(cx + 2, ry, cx + cw - 2, ry + TradeLayout.ROW_H, 0xff111c27);
            ItemStack shown = entry.stack().copy();
            g.renderItem(shown, cx + layout.icon.x(), ry + (TradeLayout.ROW_H - 16) / 2);
            if (shown.getCount() > 1) g.renderItemDecorations(font, shown, cx + layout.icon.x(), ry + (TradeLayout.ROW_H - 16) / 2);
            String problem = refusal(entry, 1);
            boolean blocked = !problem.isEmpty();
            g.drawString(font, UcUi.fit(font, entry.name(), layout.name.w()), cx + layout.name.x(), ry + 5, blocked && entry.left() == 0 ? UcUi.FAINT : UcUi.TEXT, false);
            boolean poor = !entry.sell() && blocked && entry.left() != 0;
            int priceColor = poor ? UcUi.UNDERGROUND_TEXT : UcUi.AMBER;
            UcUi.icon(g, UcUi.Icon.CREDITS, cx + layout.price.x() - 3, ry + 13, priceColor);
            g.drawString(font, UcUi.fit(font, fmt(entry.price()) + (entry.sell() ? " paid" : " each"), layout.price.w() - 12), cx + layout.price.x() + 12, ry + 17, priceColor, false);
            int sx = cx + layout.stockOrStatus.x(), sw = layout.stockOrStatus.w();
            String status = blocked ? shortStatus(entry) : entry.limitText();
            if (!status.isEmpty()) g.drawString(font, UcUi.fit(font, status, sw), sx, ry + 17, blocked ? UcUi.WARN : UcUi.DIM, false);
        }
        int more = entries().size() - rows;
        if (more > 0) {
            int trackTop = listTop(), trackH = rows * TradeLayout.ROW_H, trackX = cx + cw - 5;
            g.fill(trackX, trackTop, trackX + 3, trackTop + trackH, 0xff0a1017);
            int thumbH = Math.max(14, trackH * rows / entries().size());
            int thumbY = trackTop + (trackH - thumbH) * scroll / Math.max(1, more);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, UcUi.LEGAL);
        }
    }

    /** The one short text that replaces the limit text on a row that cannot be used right now. */
    private String shortStatus(CivilianShopData.Entry entry) {
        if (unitsAvailable(entry) < 1) return "Sold out";
        if (entry.sell()) {
            int missing = entry.count() - held(entry.stack());
            return missing > 0 ? "Bring " + missing + " more" : "";
        }
        return "Need " + fmt(Math.ceil(entry.price() - data.balance())) + " more";
    }

    @Override protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partial) {
        TradeLayout layout = layout();
        for (int i = 0; i < rows() && i + scroll < entries().size(); i++) {
            int ry = listTop() + i * TradeLayout.ROW_H;
            if (mouseX < cx + 2 || mouseX >= cx + layout.buyOne.x() - 4 || mouseY < ry || mouseY >= ry + TradeLayout.ROW_H) continue;
            CivilianShopData.Entry entry = entries().get(i + scroll);
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(entry.name()));
            lines.add(Component.literal((entry.sell() ? "The shop pays " : "Costs ") + fmt(entry.price()) + " " + data.currency() + " for " + entry.count() + " x " + entry.stack().getHoverName().getString()));
            if (!entry.limitText().isEmpty()) lines.add(Component.literal(entry.limitText()));
            String reason = refusal(entry, 1);
            if (!reason.isEmpty()) lines.add(Component.literal(reason));
            UcUi.tooltip(g, font, lines, mouseX, mouseY, width, height);
            return;
        }
    }

    // ------------------------------------------------------------------------------------------------ journey and capture hooks
    /** A real mouse click at the centre of a row's first button. */
    boolean clickBuy(int row, boolean lot) {
        UcButton button = (lot ? buyLot : buyOne)[row];
        return mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0);
    }

    /** Journey hook: scrolls the current section to the entry with Q Shop's index and clicks its first button as the mouse would. False when the entry is not listed or cannot be used right now. */
    boolean clickEntry(int serverIndex) {
        for (int i = 0; i < entries().size(); i++) {
            if (entries().get(i).serverIndex() != serverIndex) continue;
            scrollTo(i);
            int row = i - scroll;
            return row >= 0 && row < rows() && buyOne[row].visible && buyOne[row].active && clickBuy(row, false);
        }
        return false;
    }

    /** Scrolls as the wheel would, for captures of a long list. */
    void scrollTo(int firstRow) { scroll = Mth.clamp(firstRow, 0, maxScroll()); layoutRows(); }

    /** Selects a section as a click on its rail button would. */
    void showTab(int index) { if (index >= 0 && index < data.tabs().size()) select(index); }

    int visibleRowCount() { return Math.min(rows(), entries().size() - scroll); }
}
