package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Public Ledger: every trade's legal postings in this city on one board, filtered by trade, with what each asks and pays and how much
 * demand is left. Read-only: work is taken at an Operations Terminal, and underground work is never posted here.
 */
@OnlyIn(Dist.CLIENT)
public final class LedgerScreen extends UcScreen {
    private static final String[] TRADES = {"", "farmer", "smith", "gunsmith", "technician"};
    private static final int ROW = 22;

    private final LedgerData data;
    private String filter = "";
    private int scroll;
    private final List<UcButton> chips = new ArrayList<>();

    LedgerScreen(LedgerData data) {
        super(Component.literal("Underground City | Public Ledger"));
        this.data = data;
        this.filter = data.career();
    }

    @Override protected int accent() { return data.region().isEmpty() ? UcUi.AMBER : UcUi.cityColor(data.region()); }
    @Override protected String titleText() { return "Public ledger"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TAG; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        chips.clear();
        layoutPanel(420, 260, 600, 400);
        int x = bodyX();
        for (String trade : TRADES) {
            String label = trade.isEmpty() ? "All" : capitalize(trade);
            int w = UcUi.displayWidth(font, label) + (trade.isEmpty() ? 18 : 30);
            UcButton chip = new UcButton(x, bodyY() + 2, w, 15, label, b -> { filter = trade; scroll = 0; refresh(); })
                .kind(UcButton.Kind.CHIP).accent(trade.isEmpty() ? UcUi.AMBER : UcUi.careerColor(trade));
            if (!trade.isEmpty()) chip.icon(UcUi.careerIcon(trade));
            chips.add(add(chip));
            x += w + 4;
        }
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < chips.size(); i++) chips.get(i).selected(TRADES[i].equals(filter));
    }

    private List<LedgerData.Posting> shown() {
        return data.postings().stream().filter(p -> filter.isEmpty() || p.career().equals(filter)).toList();
    }

    private int listTop() { return bodyY() + 24; }
    private int rows() { return Math.max(1, (bodyBottom() - listTop() - 2) / ROW); }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, shown().size() - rows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        if (data.region().isEmpty()) return;
        String name = data.region().toUpperCase(Locale.ROOT);
        UcUi.chip(g, font, name, right - UcUi.displayWidth(font, name) - 10, py + 9, UcUi.cityColor(data.region()), null);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), w = bodyW(), top = listTop(), bottom = bodyBottom();
        UcUi.well(g, x, top - 2, w, bottom - top + 2);
        List<LedgerData.Posting> list = shown();
        if (list.isEmpty()) {
            UcUi.text(g, font, "Nothing is posted for this trade today.", x + 10, top + 8, UcUi.DIM);
            return;
        }
        int rows = rows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - rows)));
        for (int i = 0; i < rows && i + scroll < list.size(); i++) {
            LedgerData.Posting p = list.get(i + scroll);
            int ry = top + i * ROW;
            boolean mine = p.career().equals(data.career());
            boolean locked = mine && data.level() < p.level();
            g.fill(x + 2, ry, x + w - 2, ry + ROW - 2, i % 2 == 0 ? UcUi.ROW : 0xff111c27);
            if (mine) g.fill(x + 2, ry, x + 5, ry + ROW - 2, UcUi.careerColor(p.career()));
            UcUi.icon(g, UcUi.careerIcon(p.career()), x + 9, ry + 3, UcUi.careerColor(p.career()));
            int right = x + w - 10;
            // demand: how much of the order is still open
            int barW = 54;
            UcUi.bar(g, right - barW, ry + 4, barW, 4, p.capacity() == 0 ? 0f : (float) p.demand() / p.capacity(), p.demand() == 0 ? UcUi.UNDERGROUND : UcUi.LEGAL);
            UcUi.text(g, font, p.demand() == 0 ? "Full" : p.demand() + " open", right - barW, ry + 10, p.demand() == 0 ? UcUi.UNDERGROUND_TEXT : UcUi.DIM);
            int rewardX = right - barW - 8 - 92;
            UcUi.icon(g, UcUi.Icon.CREDITS, rewardX - 2, ry + 2, UcUi.AMBER);
            UcUi.text(g, font, p.credits() + "  +" + p.xp() + " XP", rewardX + 14, ry + 6, UcUi.AMBER);
            String level = "L" + p.level();
            int levelW = UcUi.displayWidth(font, level) + 4;
            UcUi.display(g, font, level, rewardX - 10 - levelW, ry + 7, locked ? UcUi.WARN : UcUi.DIM);
            int titleW = rewardX - 10 - levelW - (x + 30) - 6;
            g.drawString(font, UcUi.fit(font, p.title(), titleW), x + 30, ry + 3, locked ? UcUi.DIM : UcUi.TEXT, false);
            if (p.visits()) UcUi.text(g, font, "needs a rival-city visit", x + 30, ry + 12, UcUi.FAINT);
            else if (locked) UcUi.text(g, font, "needs level " + p.level(), x + 30, ry + 12, UcUi.WARN);
        }
        if (list.size() > rows) {
            int track = bottom - top - 2, thumb = Math.max(10, track * rows / list.size());
            int ty = top + (track - thumb) * scroll / Math.max(1, list.size() - rows);
            g.fill(x + w - 5, top, x + w - 2, top + track, 0xff070b10);
            g.fill(x + w - 5, ty, x + w - 2, ty + thumb, UcUi.shade(accent(), 0.8f));
        }
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        UcUi.icon(g, UcUi.Icon.INFO, px + PAD, y + 4, UcUi.DIM);
        UcUi.text(g, font, UcUi.fit(font, "Take work at an Operations Terminal. Only honest work is posted here.", pw - 2 * PAD - 22), px + PAD + 20, y + 9, UcUi.DIM);
    }

    private static String capitalize(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
