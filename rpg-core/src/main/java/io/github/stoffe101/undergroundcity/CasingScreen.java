package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * What a thief reads at a door (D-144, layered by D-157): what the place is, its address and how well off it is (six pips, a word and a numeral), what holds the door and whether they could try
 * it, and, as their Thief skills allow, the alarm, the cameras, who is in, when it will be empty, the safe, whether it was broken into lately and the risk of a break-in now, with the reasons; an
 * expert also reads the risk as a number, how long the police would take and how much is worth searching. What they cannot tell yet says so and names the skill. The server wrote every word
 * ({@link Casing}); this screen only lays it out. Escape or the button closes it; Places opens the book where the place is now remembered.
 */
@OnlyIn(Dist.CLIENT)
public final class CasingScreen extends UcScreen {
    private final Casing.Report r;
    private static final int[] TIER_COLOUR = {0xff5b7083, 0xff8aa2b5, 0xff86c45a, 0xff4fd1b8, 0xffe8b04a, 0xffe07b39, 0xffe2574c};

    CasingScreen(Casing.Report report) {
        super(Component.literal("Underground City | Casing"));
        this.r = report;
    }

    /** Scripted-player hook: what the card shows, as the server wrote it. */
    Casing.Report report() { return r; }

    @Override protected int accent() { return TIER_COLOUR[Math.max(0, Math.min(6, r.tier()))]; }
    @Override protected String titleText() { return "Casing the place"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TARGET; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(300, 228, 420, 290);
        add(new UcButton(px + pw - PAD - 64, py + ph - FOOTER + 2, 64, 18, "Done", b -> onClose()).kind(UcButton.Kind.PRIMARY));
        add(new UcButton(px + pw - PAD - 64 - 4 - 64, py + ph - FOOTER + 2, 64, 18, "Places", b -> { minecraft.setScreen(new PlacesScreen()); PlacesNetwork.askBook(); }));
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String city = r.city().toUpperCase(java.util.Locale.ROOT);
        UcUi.chip(g, font, city, right - UcUi.displayWidth(font, city) - 10, py + 9, UcUi.cityColor(r.city()), null);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), y = bodyY(), w = bodyW();
        // the place and how well off it looks: six pips, the word and the numeral (never colour alone)
        UcUi.display(g, font, UcUi.fit(font, r.title(), w - 150), x, y + 2, UcUi.TEXT);
        int colour = accent();
        for (int i = 0; i < 6; i++) {
            int px0 = x + w - 6 * 10 + i * 10;
            // an empty pip is an outline: filled dark, three of six looked like three of three (the real client at 1280x720)
            if (i < r.tier()) g.fill(px0, y + 2, px0 + 8, y + 9, colour);
            else { g.fill(px0, y + 2, px0 + 8, y + 9, 0xff4a5f73); g.fill(px0 + 1, y + 3, px0 + 7, y + 8, 0xff101a24); }
        }
        String word = Thievery.tierWord(r.tier()) + " " + PropertyIntel.roman(r.tier());
        UcUi.text(g, font, word, x + w - 6 * 10 - 6 - font.width(word), y + 2, colour);
        String sub = (r.category().isEmpty() ? "" : r.category() + "  ·  ") + (r.address().isEmpty() ? "" : r.address()) + (r.beenIn() ? "  ·  you have been inside" : "");
        UcUi.text(g, font, UcUi.fit(font, sub, w), x, y + 13, UcUi.DIM);
        // the door
        int dy = y + 25;
        UcUi.well(g, x, dy, w, 30);
        UcUi.icon(g, UcUi.Icon.LOCK, x + 4, dy + 3, r.canTry() ? UcUi.LEGAL : UcUi.UNDERGROUND);
        UcUi.text(g, font, UcUi.fit(font, "The door: " + r.lock(), w - 30), x + 24, dy + 4, UcUi.TEXT);
        UcUi.wrapped(g, font, r.lockNote(), x + 24, dy + 16, w - 30, r.canTry() ? UcUi.SOFT : UcUi.UNDERGROUND_TEXT, 1);
        // what the thief can read, two columns; the row height shrinks on a short window
        int riskH = r.skill() >= 3 ? 40 : 30;
        int ry = dy + 36, colW = (w - 8) / 2;
        int rowH = Math.max(19, Math.min(23, (bodyBottom() - riskH - 4 - ry) / 3));
        String unknownCasing = "Learn Casing to tell";
        String unknownRoutines = "Learn Routines";   // the skill's short title, as the tree's cards show it (the full one did not fit a column at GUI scale 2)
        row(g, x, ry, colW, UcUi.Icon.WARNING, "Alarm", r.alarm() < 0 ? null : switch (r.alarm()) { case 0 -> "None"; case 1 -> "Fitted, not armed"; case 2 -> "Armed"; default -> "Armed, panel inside"; },
            r.alarm() >= 2 ? UcUi.UNDERGROUND_TEXT : UcUi.SOFT, unknownCasing);
        row(g, x + colW + 8, ry, colW, UcUi.Icon.TARGET, "Cameras", r.cameras() < 0 ? null : r.cameras() == 0 ? "None seen" : String.valueOf(r.cameras()), r.cameras() > 0 ? UcUi.AMBER : UcUi.SOFT, unknownCasing);
        row(g, x, ry + rowH, colW, UcUi.Icon.CITY, "Inside now", r.inside() < 0 ? null : r.inside() == 0 ? "Nobody" : r.inside() + (r.inside() == 1 ? " person" : " people"),
            r.inside() > 0 ? UcUi.AMBER : UcUi.OK, unknownCasing);
        row(g, x + colW + 8, ry + rowH, colW, UcUi.Icon.CLOCK, "Empty", r.empty().isEmpty() ? null : r.empty(), UcUi.SOFT, unknownRoutines);
        row(g, x, ry + 2 * rowH, colW, UcUi.Icon.KEY, "Safe", r.safe() < 0 ? null : switch (r.safe()) { case 0 -> "None"; case 1 -> "Household safe"; case 2 -> "Wall safe"; default -> "Vault safe"; },
            r.safe() >= 2 ? UcUi.AMBER : UcUi.SOFT, unknownRoutines);
        row(g, x + colW + 8, ry + 2 * rowH, colW, UcUi.Icon.HEAT, "Lately", r.recent() < 0 ? null : r.recent() == 1 ? "Broken into: watchful" : "Quiet", r.recent() == 1 ? UcUi.UNDERGROUND_TEXT : UcUi.SOFT, unknownRoutines);
        // the risk now, apart from the tier: what would notice a break-in at this moment
        int ky = ry + 3 * rowH + 2;
        UcUi.well(g, x, ky, w, riskH);
        if (r.risk().isEmpty()) {
            UcUi.icon(g, UcUi.Icon.HEAT, x + 4, ky + 6, UcUi.FAINT);
            UcUi.text(g, font, "Risk now", x + 24, ky + 5, UcUi.DIM);
            UcUi.text(g, font, UcUi.fit(font, "Learn Casing to judge the risk of going in now", w - 30), x + 24, ky + 16, UcUi.FAINT);
        } else {
            int riskColour = switch (r.risk()) { case "Low" -> UcUi.OK; case "Moderate" -> UcUi.AMBER; case "High" -> UcUi.WARN; default -> UcUi.UNDERGROUND_TEXT; };
            UcUi.icon(g, UcUi.Icon.HEAT, x + 4, ky + 6, riskColour);
            String head = "Risk now: " + r.risk() + (r.riskPercent() >= 0 ? " (~" + r.riskPercent() + "% to be noticed)" : "");
            UcUi.text(g, font, UcUi.fit(font, head, w - 30), x + 24, ky + 5, riskColour);
            UcUi.text(g, font, UcUi.fit(font, r.reasons(), w - 30), x + 24, ky + 16, UcUi.SOFT);
            if (r.skill() >= 3) {
                String expert = "Police ~" + r.policeSeconds() + " s" + (r.patrolMinutes() > 0 ? "  ·  patrol ~" + r.patrolMinutes() + " min" : "")
                    + (r.searchable() >= 0 ? "  ·  " + r.searchable() + " to search" : "");
                UcUi.text(g, font, UcUi.fit(font, expert, w - 30), x + 24, ky + 27, UcUi.DIM);
            }
        }
    }

    private void row(GuiGraphics g, int x, int y, int w, UcUi.Icon icon, String label, String value, int colour, String unknown) {
        UcUi.icon(g, icon, x, y - 2, value == null ? UcUi.FAINT : colour);
        UcUi.text(g, font, label, x + 18, y, UcUi.DIM);
        String shown = value == null ? unknown : value;
        UcUi.text(g, font, UcUi.fit(font, shown, w - 20), x + 18, y + 10, value == null ? UcUi.FAINT : colour);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String hint = r.playerHomes() ? "Players' homes here are safe."
            : r.skill() == 0 ? "Casing reads more." : r.skill() == 1 ? "Routines: when it empties." : r.skill() == 2 ? "Experts see numbers."
            : "Come back when it empties.";
        UcUi.text(g, font, UcUi.fit(font, hint, pw - 2 * PAD - 140), px + PAD, y + 9, UcUi.DIM);
    }
}
