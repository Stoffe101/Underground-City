package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * A repair at a utility panel (D-158): the circuit drawn from the supply on the left along the trunk, splitting (in a better-off building) into branch A above and branch B below, each to its
 * load's lamp. In Probe mode a click on a part reads the test point just after it (a green dot: the signal gets there; red: it does not); in Replace mode a click swaps the part. The dead part lies
 * between the last green point and the first red one. A Diagnostic Scanner outlines the stretch that holds it from the start. Escape closes the panel and changes nothing.
 */
@OnlyIn(Dist.CLIENT)
public final class TraceScreen extends UcScreen {
    private static final String[] LETTERS = {"F", "R", "C", "K", "U", "D"};
    private static final String[] NAMES = {"Fuse", "Resistor", "Capacitor", "Relay", "Chip", "Diode"};
    private TechNetwork.TraceView v;
    private boolean replacing;
    private String note = "";
    private long noteAt;
    private UcButton probeB, replaceB;
    private int cell, gx, gy;

    TraceScreen(TechNetwork.TraceView view) {
        super(Component.literal("Underground City | Repair"));
        this.v = view;
    }

    void update(TechNetwork.TraceView view) { this.v = view; labels(); }
    void note(String text) { note = text; noteAt = System.currentTimeMillis(); }
    /** Scenario hooks. */
    TechNetwork.TraceView view() { return v; }
    int[] partCentre(int part) { int[] r = rect(part); return new int[] {r[0] + r[2] / 2, r[1] + r[3] / 2}; }
    boolean replacing() { return replacing; }

    @Override protected int accent() { return UcUi.LEGAL; }
    @Override protected String titleText() { return "Repair: find the dead part"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.GEAR; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(330, 210, 460, 260);
        int columns = 2 + v.trunk() + Math.max(0, v.branch()) + 1;
        cell = Math.max(22, Math.min(34, (bodyW() - 10) / columns));
        gx = bodyX() + (bodyW() - columns * cell) / 2 + cell;
        gy = bodyY() + 34 + (v.split() ? cell : 0);
        int by = py + ph - FOOTER + 2;
        probeB = add(new UcButton(px + PAD, by, 70, 18, "Probe", b -> { replacing = false; labels(); }).kind(UcButton.Kind.CHIP));
        replaceB = add(new UcButton(px + PAD + 74, by, 70, 18, "Replace", b -> { replacing = true; labels(); }).kind(UcButton.Kind.CHIP));
        add(new UcButton(px + pw - PAD - 64, by, 64, 18, "Close", b -> onClose()));
        labels();
    }

    private void labels() {
        if (probeB == null) return;
        probeB.selected(!replacing);
        replaceB.selected(replacing);
    }

    @Override public void onClose() { TechNetwork.move(TechNetwork.CLOSE, 0); super.onClose(); }

    /** The rectangle of a part: trunk parts on the middle row, branch A one row up, branch B one row down. */
    private int[] rect(int part) {
        int size = cell - 6;
        int col, row;
        if (part < v.trunk()) { col = part; row = 0; }
        else if (part < v.trunk() + v.branch()) { col = part; row = -1; }
        else { col = part - v.branch(); row = 1; }
        return new int[] {gx + col * cell + 3, gy + row * cell + 3, size, size};
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < v.parts().length; i++) {
            int[] r = rect(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                TechNetwork.move(replacing ? TechNetwork.REPLACE : TechNetwork.PROBE, i);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 80) { replacing = false; labels(); return true; }   // P
        if (key == 82) { replacing = true; labels(); return true; }    // R
        return super.keyPressed(key, scan, modifiers);
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String time = v.secondsLeft() + " s";
        UcUi.chip(g, font, time, right - UcUi.displayWidth(font, time) - 10, py + 9, v.secondsLeft() < 20 ? UcUi.UNDERGROUND : UcUi.AMBER, UcUi.Icon.CLOCK);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), w = bodyW();
        String head = "Tries left: " + v.tries() + "   Probes: " + v.probes() + (v.scanLo() >= 0 ? "   Scanner: the fault is in the outlined stretch" : "");
        UcUi.text(g, font, UcUi.fit(font, head, w), x, bodyY(), UcUi.SOFT);
        String help = replacing ? "Replace mode: click the part you think is dead." : "Probe mode: click a part to test the point after it.";
        UcUi.text(g, font, UcUi.fit(font, help, w), x, bodyY() + 11, replacing ? UcUi.AMBER : UcUi.DIM);
        UcUi.well(g, x, bodyY() + 24, w, bodyBottom() - bodyY() - 24 - 12);
        // the supply and the wires
        int mid = gy + cell / 2;
        int supplyX = gx - cell + 4;
        g.fill(supplyX, mid - 6, supplyX + 12, mid + 6, UcUi.AMBER);
        UcUi.text(g, font, "+", supplyX + 3, mid - 4, 0xff101a24);
        int trunkEnd = gx + v.trunk() * cell;
        g.fill(supplyX + 12, mid - 1, trunkEnd, mid + 1, 0xff4a5f73);
        if (v.split()) {
            int aEnd = trunkEnd + v.branch() * cell;
            g.fill(trunkEnd - 1, mid - cell, trunkEnd + 1, mid + cell, 0xff4a5f73);
            g.fill(trunkEnd, mid - cell - 1, aEnd, mid - cell + 1, 0xff4a5f73);
            g.fill(trunkEnd, mid + cell - 1, aEnd, mid + cell + 1, 0xff4a5f73);
            lamp(g, aEnd + 4, mid - cell, v.loadA(), "A");
            lamp(g, aEnd + 4, mid + cell, v.loadB(), "B");
        } else {
            lamp(g, trunkEnd + 4, mid, v.loadA(), "");
        }
        // the parts and their test points
        for (int i = 0; i < v.parts().length; i++) {
            int[] r = rect(i);
            boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
            boolean scanned = v.scanLo() >= 0 && i >= v.scanLo() && i <= v.scanHi();
            g.fill(r[0] - 1, r[1] - 1, r[0] + r[2] + 1, r[1] + r[3] + 1, scanned ? UcUi.AMBER : hover ? UcUi.TEXT : 0xff05080c);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xff2a4259 : 0xff1a2c3d);
            String letter = LETTERS[Math.max(0, Math.min(5, v.parts()[i]))];
            UcUi.text(g, font, letter, r[0] + r[2] / 2 - font.width(letter) / 2, r[1] + r[3] / 2 - 4, UcUi.TEXT);
            int reading = v.readings()[i];
            int dot = reading < 0 ? 0xff4a5f73 : reading == 1 ? UcUi.OK : UcUi.UNDERGROUND;
            int dx = r[0] + r[2] + 1, dy = r[1] + r[3] / 2 - 2;
            g.fill(dx, dy, dx + 4, dy + 4, dot);
        }
        // what the hovered part is
        for (int i = 0; i < v.parts().length; i++) {
            int[] r = rect(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                String what = NAMES[Math.max(0, Math.min(5, v.parts()[i]))] + (v.readings()[i] < 0 ? ", not probed" : v.readings()[i] == 1 ? ": signal after it" : ": no signal after it");
                UcUi.text(g, font, UcUi.fit(font, what, w), x, bodyBottom() - 10, UcUi.SOFT);
            }
        }
        if (!note.isEmpty() && System.currentTimeMillis() - noteAt < 4000) UcUi.text(g, font, UcUi.fit(font, note, w), x, bodyBottom() - 10, UcUi.AMBER);
    }

    private void lamp(GuiGraphics g, int x, int y, boolean lit, String label) {
        g.fill(x, y - 6, x + 12, y + 6, 0xff05080c);
        g.fill(x + 1, y - 5, x + 11, y + 5, lit ? UcUi.AMBER : 0xff2a3440);
        if (!label.isEmpty()) UcUi.text(g, font, label, x + 15, y - 4, lit ? UcUi.AMBER : UcUi.DIM);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        UcUi.text(g, font, "P / R", px + PAD + 150, y + 9, UcUi.FAINT);
    }
}
