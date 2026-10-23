package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * What a thief reads at a door (D-144): how well off the place is (six pips and a word), what holds the door and whether they could try it, and, as their Thief skills allow, the alarm, the
 * cameras, who is in, when it will be empty, the safe and whether it was broken into lately. What they cannot tell yet says so, and names the skill that would tell them. The server wrote every word
 * ({@link Casing}); this screen only lays it out. Escape or the button closes it.
 */
@OnlyIn(Dist.CLIENT)
public final class CasingScreen extends UcScreen {
    private final Casing.Report r;
    private static final int[] TIER_COLOUR = {0xff5b7083, 0xff8aa2b5, 0xff86c45a, 0xff4fd1b8, 0xffe8b04a, 0xffe07b39, 0xffe2574c};

    CasingScreen(Casing.Report report) {
        super(Component.literal("Underground City | Casing"));
        this.r = report;
    }

    @Override protected int accent() { return TIER_COLOUR[Math.max(0, Math.min(6, r.tier()))]; }
    @Override protected String titleText() { return "Casing the place"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TARGET; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(300, 214, 400, 250);
        add(new UcButton(px + pw - PAD - 80, py + ph - FOOTER + 2, 80, 18, "Done", b -> onClose()).kind(UcButton.Kind.PRIMARY));
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String city = r.city().toUpperCase(java.util.Locale.ROOT);
        UcUi.chip(g, font, city, right - UcUi.displayWidth(font, city) - 10, py + 9, UcUi.cityColor(r.city()), null);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), y = bodyY(), w = bodyW();
        // the place and how well off it looks: six pips, the word
        UcUi.display(g, font, UcUi.fit(font, r.title(), w - 120), x, y + 2, UcUi.TEXT);
        int colour = accent();
        for (int i = 0; i < 6; i++) {
            int px0 = x + w - 6 * 12 + i * 12;
            g.fill(px0, y + 2, px0 + 9, y + 9, i < r.tier() ? colour : 0xff1b2a38);
        }
        String word = Thievery.tierWord(r.tier());
        UcUi.text(g, font, word, x + w - 6 * 12 - 6 - font.width(word), y + 2, colour);
        if (r.beenIn()) UcUi.text(g, font, "You have been inside before.", x, y + 13, UcUi.DIM);
        // the door
        int dy = y + 26;
        UcUi.well(g, x, dy, w, 34);
        UcUi.icon(g, UcUi.Icon.LOCK, x + 4, dy + 4, r.canTry() ? UcUi.LEGAL : UcUi.UNDERGROUND);
        UcUi.text(g, font, UcUi.fit(font, "The door: " + r.lock(), w - 30), x + 24, dy + 5, UcUi.TEXT);
        UcUi.wrapped(g, font, r.lockNote(), x + 24, dy + 17, w - 30, r.canTry() ? UcUi.SOFT : UcUi.UNDERGROUND_TEXT, 1);
        // what the thief can read, two columns
        int ry = dy + 42, colW = (w - 8) / 2;
        String unknownCasing = "Learn Casing to tell";
        String unknownRoutines = "Learn Know Their Routines";
        row(g, x, ry, colW, UcUi.Icon.WARNING, "Alarm", r.alarm() < 0 ? null : switch (r.alarm()) { case 0 -> "None"; case 1 -> "Fitted, not armed"; case 2 -> "Armed"; default -> "Armed, panel inside"; },
            r.alarm() >= 2 ? UcUi.UNDERGROUND_TEXT : UcUi.SOFT, unknownCasing);
        row(g, x + colW + 8, ry, colW, UcUi.Icon.TARGET, "Cameras", r.cameras() < 0 ? null : r.cameras() == 0 ? "None seen" : String.valueOf(r.cameras()), r.cameras() > 0 ? UcUi.AMBER : UcUi.SOFT, unknownCasing);
        row(g, x, ry + 24, colW, UcUi.Icon.CITY, "Inside now", r.inside() < 0 ? null : r.inside() == 0 ? "Nobody" : r.inside() + (r.inside() == 1 ? " person" : " people"),
            r.inside() > 0 ? UcUi.AMBER : UcUi.OK, unknownCasing);
        row(g, x + colW + 8, ry + 24, colW, UcUi.Icon.CLOCK, "Empty", r.empty().isEmpty() ? null : r.empty(), UcUi.SOFT, unknownRoutines);
        row(g, x, ry + 48, colW, UcUi.Icon.KEY, "Safe", r.safe() < 0 ? null : switch (r.safe()) { case 0 -> "None"; case 1 -> "Household safe"; case 2 -> "Wall safe"; default -> "Vault safe"; },
            r.safe() >= 2 ? UcUi.AMBER : UcUi.SOFT, unknownRoutines);
        row(g, x + colW + 8, ry + 48, colW, UcUi.Icon.HEAT, "Lately", r.recent() < 0 ? null : r.recent() == 1 ? "Broken into: watchful" : "Quiet", r.recent() == 1 ? UcUi.UNDERGROUND_TEXT : UcUi.SOFT, unknownRoutines);
    }

    private void row(GuiGraphics g, int x, int y, int w, UcUi.Icon icon, String label, String value, int colour, String unknown) {
        UcUi.icon(g, icon, x, y - 2, value == null ? UcUi.FAINT : colour);
        UcUi.text(g, font, label, x + 18, y, UcUi.DIM);
        String shown = value == null ? unknown : value;
        UcUi.text(g, font, UcUi.fit(font, shown, w - 20), x + 18, y + 10, value == null ? UcUi.FAINT : colour);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String hint = r.skill() == 0 ? "Thief skills read more of a place: Casing first." : r.skill() == 1 ? "Know Their Routines reads when it is empty." : "A careful thief comes back when it is empty.";
        UcUi.text(g, font, UcUi.fit(font, hint, pw - 2 * PAD - 90), px + PAD, y + 9, UcUi.DIM);
    }
}
