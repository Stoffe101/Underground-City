package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * A machine's drive stripped at a service call (eighth pass): the motor on the left, then the drive part by part to the output. A worn part is red; click it to fit a new one from your stock. Under
 * each open gear stage, step the driving and the driven gear up or down and fit or take out an idler; the output's speed and direction are worked out as you go, against the machine's plate. Run
 * tests it. Close changes nothing.
 */
@OnlyIn(Dist.CLIENT)
public final class DriveScreen extends UcScreen {
    private static final String[] KINDS = {"Coupling", "Bearing", "Stage", "Tensioner"};
    private SmithNetwork.DriveView v;
    private UcButton runB;
    private final List<UcButton> gearButtons = new ArrayList<>();
    private int tile, gx, gy;
    /** Below a stage's tile: its two stepper rows and its idler button (tighter on a short screen). */
    private int rowA = 16, rowB = 38, idlerOff = 60;

    DriveScreen(SmithNetwork.DriveView view) {
        super(Component.literal("Underground City | Drive"));
        this.v = view;
    }

    void update(SmithNetwork.DriveView view) {
        boolean shape = view.parts().length != v.parts().length;
        this.v = view;
        if (shape) init(); else labels();
    }

    /** Scenario hooks. */
    SmithNetwork.DriveView view() { return v; }
    int[] partCentre(int part) { int[] r = rect(part); return new int[] {r[0] + r[2] / 2, r[1] + r[3] / 2}; }
    /** The button that steps stage {@code stage}'s driving ({@code driving}) or driven gear up ({@code up}) or down, or its idler ({@code id} "idler"). */
    int[] centreOf(String id) {
        for (UcButton b : gearButtons) if (id.equals(idOf(b))) return new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        if (id.equals("run") && runB != null) return new int[] {runB.getX() + runB.getWidth() / 2, runB.getY() + runB.getHeight() / 2};
        return null;
    }
    private final java.util.Map<UcButton, String> ids = new java.util.HashMap<>();
    private String idOf(UcButton b) { return ids.getOrDefault(b, ""); }

    @Override protected int accent() { return UcUi.careerColor("smith"); }
    @Override protected String titleText() { return "Drive: " + v.machine(); }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.GEAR; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        gearButtons.clear();
        ids.clear();
        layoutPanel(440, 250, 640, 330);
        int columns = v.parts().length + 2;
        tile = Math.max(30, Math.min(52, (bodyW() - 16) / columns));
        gy = bodyY() + 40;
        // the steppers and the idler must end above the note line: 1280x720 at GUI scale 3 (426 by 240) put the idlers over it (engineer run 7)
        int room = bodyBottom() - 20 - gy;   // the well round the drive ends 14 above the body's foot, over the note line
        rowA = 16; rowB = 38; idlerOff = 60;
        if (tile - 4 + idlerOff + 14 > room) { rowA = 13; rowB = 31; idlerOff = 49; }
        if (tile - 4 + idlerOff + 14 > room) tile = Math.max(30, room - idlerOff - 14 + 4);
        gx = bodyX() + (bodyW() - columns * tile) / 2;
        // the gear steppers under each stage
        for (int i = 0; i < v.parts().length; i++) {
            if (v.parts()[i] != 2) continue;
            int stage = (i - 2) / 2;
            int[] r = rect(i);
            int bx = r[0], by = r[1] + r[3] + rowA;
            gear(stage, true, true, bx, by, "+");
            gear(stage, true, false, bx + 11, by, "-");
            gear(stage, false, true, bx, by + rowB - rowA, "+");
            gear(stage, false, false, bx + 11, by + rowB - rowA, "-");
            UcButton idler = add(new UcButton(bx - 2, r[1] + r[3] + idlerOff, Math.max(26, tile - 2), 14, "idler", b -> SmithNetwork.move(SmithNetwork.IDLER, stage, 0, 0, 0)).kind(UcButton.Kind.CHIP)
                .tooltip("Fit or take out an idler gear:", "it keeps this stage's direction"));
            ids.put(idler, "idler_" + stage);
            gearButtons.add(idler);
        }
        int by = py + ph - FOOTER + 2;
        runB = add(new UcButton(px + PAD, by, 80, 18, "Run test", b -> SmithNetwork.move(SmithNetwork.RUN, 0, 0, 0, 0)).kind(UcButton.Kind.PRIMARY).icon(UcUi.Icon.GEAR)
            .tooltip("Run the machine to see if it meets its plate.", "Nothing is used until it does."));
        add(new UcButton(px + pw - PAD - 52, by, 52, 18, "Close", b -> onClose()));
        labels();
    }

    private void gear(int stage, boolean driving, boolean up, int x, int y, String label) {
        UcButton b = add(new UcButton(x, y, 10, 10, label, btn -> SmithNetwork.move(SmithNetwork.GEAR, stage, driving ? 1 : 0, up ? 1 : -1, 0))
            .tooltip((driving ? "Driving" : "Driven") + " gear of stage " + (stage + 1) + ": " + (up ? "a size up" : "a size down")));
        ids.put(b, (driving ? "driver_" : "driven_") + stage + (up ? "_up" : "_down"));
        gearButtons.add(b);
    }

    private void labels() {
        for (UcButton b : gearButtons) {
            String id = idOf(b);
            int stage = Integer.parseInt(id.replaceAll("[^0-9]", ""));
            boolean open = stage < v.open().length && v.open()[stage];
            b.active = open;
            if (id.startsWith("idler")) b.selected(stage < v.idler().length && v.idler()[stage]);
        }
        if (runB != null) runB.active = v.tests() > 0;
    }

    @Override public void onClose() { SmithNetwork.move(SmithNetwork.CLOSE, 0, 0, 0, 0); super.onClose(); }

    /** The tile of a drive part: the motor is column 0, the parts follow, the output last. */
    private int[] rect(int part) { return new int[] {gx + (part + 1) * tile + 2, gy, tile - 4, tile - 4}; }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < v.parts().length; i++) {
            int[] r = rect(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (v.state()[i] == 1) SmithNetwork.move(SmithNetwork.REPLACE, i, 0, 0, 0);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String tests = v.tests() + (v.tests() == 1 ? " run" : " runs");
        UcUi.chip(g, font, tests, right - UcUi.displayWidth(font, tests) - 30, py + 9, v.tests() <= 1 ? UcUi.WARN : UcUi.AMBER, UcUi.Icon.CLOCK);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), w = bodyW();
        String plate = "Plate: " + v.lo() + " to " + v.hi() + " rpm, " + (v.clockwise() ? "clockwise" : "anticlockwise") + ".   Motor: " + v.motor() + " rpm.";
        UcUi.text(g, font, UcUi.fit(font, plate, w), x, bodyY(), UcUi.SOFT);
        boolean speedOk = v.output() >= v.lo() && v.output() <= v.hi(), dirOk = v.outClockwise() == v.clockwise();
        String out = "Output now: " + v.output() + " rpm " + (v.outClockwise() ? "clockwise" : "anticlockwise");
        UcUi.text(g, font, UcUi.fit(font, out, w), x, bodyY() + 11, speedOk && dirOk ? UcUi.OK : UcUi.WARN);
        UcUi.text(g, font, UcUi.fit(font, "Uses: " + (v.needs().isEmpty() ? "nothing" : v.needs()), w), x, bodyY() + 22, UcUi.DIM);
        UcUi.well(g, x, gy - 6, w, bodyBottom() - gy - 8);   // down to just above the note line, round the idlers too
        int mid = gy + (tile - 4) / 2;
        // the shaft running through the drive
        g.fill(gx + 4, mid - 1, gx + (v.parts().length + 2) * tile - 4, mid + 1, 0xff4a5f73);
        // the motor and the output
        tileBox(g, gx + 2, gy, "Motor", v.motor() + "", 0xff3a5068, UcUi.SOFT);
        int ox = gx + (v.parts().length + 1) * tile + 2;
        tileBox(g, ox, gy, "Out", v.output() + "", speedOk && dirOk ? 0xff1f5a3a : 0xff5a2a24, speedOk && dirOk ? UcUi.OK : UcUi.WARN);
        for (int i = 0; i < v.parts().length; i++) {
            int[] r = rect(i);
            int state = v.state()[i];
            boolean hover = mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3];
            int fill = state == 1 ? (hover ? 0xff7a2f28 : 0xff5a2420) : state == 2 ? 0xff1f5a3a : 0xff22303f;
            String kind = KINDS[Math.max(0, Math.min(3, v.parts()[i]))];
            String sub = state == 1 ? "worn" : state == 2 ? "new" : "sound";
            if (v.parts()[i] == 2) {
                int stage = (i - 2) / 2;
                kind = "Stage " + (stage + 1);
                sub = SmithPuzzles.TEETH[v.driver()[stage]] + ":" + SmithPuzzles.TEETH[v.driven()[stage]];
            }
            tileBox(g, r[0], r[1], kind, sub, fill, state == 1 ? UcUi.UNDERGROUND_TEXT : state == 2 ? UcUi.OK : UcUi.SOFT);
            if (v.parts()[i] == 2) {
                int stage = (i - 2) / 2;
                boolean open = v.open()[stage];
                int bottom = r[1] + r[3], by = bottom + (rowA > 13 ? 4 : 3);
                UcUi.text(g, font, "drive " + SmithPuzzles.TEETH[v.driver()[stage]], r[0] + 22, bottom + rowA + 1, open ? UcUi.TEXT : UcUi.FAINT);
                UcUi.text(g, font, "driven " + SmithPuzzles.TEETH[v.driven()[stage]], r[0] + 22, bottom + rowB + 1, open ? UcUi.TEXT : UcUi.FAINT);
                if (!open) UcUi.text(g, font, "geared right", r[0], by, UcUi.FAINT);
                else UcUi.text(g, font, "open", r[0], by, UcUi.AMBER);
            }
        }
        UcUi.text(g, font, UcUi.fit(font, v.note(), w), x, bodyBottom() - 10, v.note().startsWith("It ") || v.note().startsWith("Too") || v.note().startsWith("You carry") ? UcUi.WARN : UcUi.DIM);
    }

    private void tileBox(GuiGraphics g, int x, int y, String title, String sub, int fill, int color) {
        int s = tile - 4;
        g.fill(x - 1, y - 1, x + s + 1, y + s + 1, 0xff05080c);
        g.fill(x, y, x + s, y + s, fill);
        int tw = font.width(title);
        if (tw > s - 2 && tw * 0.75f <= s - 2) {
            // a name a little too long for its tile ("Tensioner" at 1280x720) is drawn smaller rather than cut
            float k = (s - 2) / (float) tw;
            g.pose().pushPose();
            g.pose().translate(x + s / 2f - tw * k / 2f, y + 4 + (1 - k) * 4, 0);
            g.pose().scale(k, k, 1f);
            UcUi.text(g, font, title, 0, 0, UcUi.TEXT);
            g.pose().popPose();
        } else {
            UcUi.text(g, font, UcUi.fit(font, title, s - 2), x + s / 2 - Math.min(s - 2, tw) / 2, y + 4, UcUi.TEXT);
        }
        UcUi.text(g, font, UcUi.fit(font, sub, s - 2), x + s / 2 - Math.min(s - 2, font.width(sub)) / 2, y + s - 12, color);
    }
}
