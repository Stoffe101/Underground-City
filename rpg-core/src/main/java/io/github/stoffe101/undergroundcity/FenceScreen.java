package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * The fence's screen (D-125): the stolen goods the player carries that this fence buys, what each line fetches, what the network can still pay, and a Sell button per line (and one for everything).
 * Every number on it came from the server; a press asks the server to sell and the server's fresh offer replaces the screen. Underground red, the same panel as the terminal.
 */
public final class FenceScreen extends UcScreen {
    private static final int ROW_H = 24, MAX_VISIBLE = 9;
    private final int entityId;
    private final FenceService.Quote quote;
    private final UcButton[] sell = new UcButton[MAX_VISIBLE];
    private UcButton sellAll;
    private int scroll;
    private long sentAt;

    FenceScreen(int entityId, FenceService.Quote quote) {
        super(Component.literal(quote.title()));
        this.entityId = entityId;
        this.quote = quote;
    }

    /** Opens the screen, or refreshes it in place after a sale (the scroll position is kept). */
    static void show(int entityId, FenceService.Quote quote) {
        Minecraft mc = Minecraft.getInstance();
        int keep = mc.screen instanceof FenceScreen open && open.entityId == entityId ? open.scroll : 0;
        FenceScreen next = new FenceScreen(entityId, quote);
        next.scroll = keep;
        mc.setScreen(next);
    }

    FenceService.Quote quote() { return quote; }

    /** Capture harness: the screen for a made-up quote, scrolled as asked, never talking to a server. */
    static FenceScreen forCapture(FenceService.Quote quote, int scroll) {
        FenceScreen screen = new FenceScreen(-1, quote);
        screen.scroll = scroll;
        return screen;
    }

    @Override protected int accent() { return UcUi.UNDERGROUND; }
    @Override protected String titleText() { return quote.title().toUpperCase(Locale.ROOT); }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.UNDERGROUND; }
    @Override protected boolean showFooter() { return true; }

    private int listTop() { return bodyY() + 34; }
    private int visible() { return Math.max(1, Math.min(MAX_VISIBLE, (bodyBottom() - listTop()) / ROW_H)); }

    @Override protected void init() {
        layoutPanel(360, 240, 560, 360);
        clearWidgets();
        for (int i = 0; i < MAX_VISIBLE; i++) {
            final int index = i;
            sell[i] = add(new UcButton(0, 0, 52, 18, "Sell", b -> press(index)).kind(UcButton.Kind.DANGER));
        }
        sellAll = add(new UcButton(px + pw - PAD - 100, py + ph - FOOTER + 1, 100, 18, "Sell all", b -> pressAll()).kind(UcButton.Kind.PRIMARY));
        layoutRows();
    }

    private void layoutRows() {
        int rows = visible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, quote.rows().size() - rows)));
        for (int i = 0; i < MAX_VISIBLE; i++) {
            int index = i + scroll;
            boolean present = i < rows && index < quote.rows().size();
            sell[i].visible = present;
            if (!present) continue;
            FenceService.Row row = quote.rows().get(index);
            sell[i].setX(px + pw - PAD - 56); sell[i].setY(listTop() + i * ROW_H + 3);
            sell[i].active = quote.about() && quote.budget() >= row.unit();
            sell[i].tooltip(!quote.about() ? "Not dealing at this hour" : quote.budget() < row.unit() ? "The fence cannot pay for even one right now" : "Sell " + row.count() + " for " + row.total() + " credits"
                + (row.total() > quote.budget() ? " (only " + quote.budget() / row.unit() + " can be paid for now)" : ""));
        }
        sellAll.visible = !quote.rows().isEmpty();
        sellAll.active = quote.about() && quote.rows().stream().anyMatch(r -> r.unit() <= quote.budget());
    }

    private boolean recentlySent() { return System.currentTimeMillis() - sentAt < 400; }

    private void press(int visibleIndex) {
        int index = visibleIndex + scroll;
        if (recentlySent() || index >= quote.rows().size()) return;
        sentAt = System.currentTimeMillis();
        FenceService.Row row = quote.rows().get(index);
        for (UcButton b : sell) b.active = false;
        FenceNetwork.sell(entityId, row.item(), row.fromCity());
    }

    private void pressAll() {
        if (recentlySent()) return;
        sentAt = System.currentTimeMillis();
        sellAll.active = false;
        FenceNetwork.sell(entityId, "*", "");
    }

    /** Scripted-player hook: the middle of the Sell button of the first line of this item, or of Sell all for "*". Null when it is not on screen. */
    int[] sellCentre(String item) {
        if (item.equals("*")) return sellAll.visible ? new int[]{sellAll.getX() + sellAll.getWidth() / 2, sellAll.getY() + sellAll.getHeight() / 2} : null;
        for (int i = 0; i < visible(); i++) {
            int index = i + scroll;
            if (index < quote.rows().size() && quote.rows().get(index).item().equals(item)) return new int[]{sell[i].getX() + sell[i].getWidth() / 2, sell[i].getY() + sell[i].getHeight() / 2};
        }
        return null;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        layoutRows();
        return true;
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String city = TerminalScreenText.capitalize(quote.city());
        UcUi.textRight(g, font, city, right, py + 11, UcUi.DIM);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), y = bodyY(), w = bodyW();
        String terms = quote.about()
            ? "Pays " + quote.ratePercent() + "% of what goods are worth; " + quote.sameCityRatePercent() + "% for goods taken in " + TerminalScreenText.capitalize(quote.city()) + ", and that gets noticed."
            : "Not dealing at this hour. Come back after dark.";
        UcUi.wrapped(g, font, terms, x, y, w, quote.about() ? UcUi.SOFT : UcUi.WARN, 2);
        if (!quote.blurb().isEmpty()) UcUi.wrapped(g, font, quote.blurb(), x, y + 20, w, UcUi.DIM, 1);
        int top = listTop();
        if (quote.rows().isEmpty()) {
            UcUi.wrapped(g, font, "You carry nothing this fence buys. They take only goods marked stolen, from your pockets (not from a backpack), and only things worth something.", x, top + 4, w, UcUi.DIM, 3);
            return;
        }
        int rows = visible();
        for (int i = 0; i < rows && i + scroll < quote.rows().size(); i++) {
            FenceService.Row row = quote.rows().get(i + scroll);
            int ry = top + i * ROW_H;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H - 2;
            g.fill(x, ry, x + w, ry + ROW_H - 2, hover ? UcUi.ROW_HOVER : UcUi.ROW);
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(row.item())), 1);
            g.renderItem(stack, x + 4, ry + 3);
            int nameRight = px + pw - PAD - 64 - 92;
            String name = UcUi.fit(font, row.count() + " x " + stack.getHoverName().getString(), nameRight - (x + 26));
            UcUi.text(g, font, name, x + 26, ry + 3, UcUi.TEXT);
            boolean home = row.fromCity().equals(quote.city());
            UcUi.text(g, font, UcUi.fit(font, "taken in " + TerminalScreenText.capitalize(row.fromCity()) + (home ? ", here" : ""), nameRight - (x + 26)), x + 26, ry + 13, home ? UcUi.UNDERGROUND_TEXT : UcUi.DIM);
            UcUi.textRight(g, font, row.unit() + " each", px + pw - PAD - 64, ry + 3, UcUi.SOFT);
            UcUi.textRight(g, font, row.total() + " cr", px + pw - PAD - 64, ry + 13, UcUi.AMBER);
        }
        if (quote.rows().size() > rows) UcUi.textRight(g, font, (scroll + 1) + "-" + Math.min(quote.rows().size(), scroll + rows) + " of " + quote.rows().size(), x + w, top - 10, UcUi.DIM);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        UcUi.icon(g, UcUi.Icon.CREDITS, px + PAD, y + 4, UcUi.AMBER);
        UcUi.text(g, font, UcUi.fit(font, "Can pay now: " + quote.budget() + " cr   All of it: " + quote.total() + " cr", pw - 2 * PAD - 120), px + PAD + 18, y + 8, UcUi.SOFT);
    }
}
