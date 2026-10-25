package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Turning a precision part to its drawing at the Machinist's Bench (eighth pass): the bar drawn as a shaft about its centre line, each section with its line dashed in amber. Click a section to
 * start a stretch, Shift-click (or right-click) another to stretch it; set where the tool runs with the arrows or the wheel; choose Roughing (deep, leaves tool marks: hatched) or Finishing (one
 * step, clean); Cut. A section turned too far, or roughed down onto its line, turns red: select it and Sleeve it back up a step (an iron nugget). Close changes nothing.
 */
@OnlyIn(Dist.CLIENT)
public final class TurningScreen extends UcScreen {
    private SmithNetwork.TurnView v;
    private int selA = -1, selB = -1, line = SmithPuzzles.Turning.STOCK - 1;
    private boolean finish;
    private UcButton roughB, finishB, upB, downB, cutB, sleeveB;
    private int cell, gx, mid, unit;

    TurningScreen(SmithNetwork.TurnView view) {
        super(Component.literal("Underground City | Turning"));
        this.v = view;
    }

    void update(SmithNetwork.TurnView view) { this.v = view; labels(); }

    /** Scenario hooks. */
    SmithNetwork.TurnView view() { return v; }
    int[] sectionCentre(int i) { int[] r = rect(i); return new int[] {r[0] + r[2] / 2, mid}; }
    int line() { return line; }
    boolean finishing() { return finish; }
    int[] selection() { return new int[] {Math.min(selA, selB), Math.max(selA, selB)}; }
    int[] centreOf(String id) {
        UcButton b = switch (id) { case "rough" -> roughB; case "finish" -> finishB; case "up" -> upB; case "down" -> downB; case "cut" -> cutB; case "sleeve" -> sleeveB; default -> null; };
        return b == null || !b.visible ? null : new int[] {b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
    }

    @Override protected int accent() { return UcUi.careerColor("smith"); }
    @Override protected String titleText() { return "Turning: " + v.title(); }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.SMITH; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(420, 250, 620, 330);
        int n = Math.max(1, v.target().length);
        cell = Math.max(20, Math.min(44, (bodyW() - 24) / n));
        gx = bodyX() + (bodyW() - n * cell) / 2;
        int wellTop = bodyY() + 26, wellBottom = bodyBottom() - 16;
        // the bar and, under it, the section numbers inside the well (the numbers ran into the note line below it in engineer run 1's pictures)
        unit = Math.max(3, Math.min(9, (wellBottom - wellTop - 22) / (2 * SmithPuzzles.Turning.STOCK)));
        mid = wellTop + 4 + SmithPuzzles.Turning.STOCK * unit + Math.max(0, (wellBottom - wellTop - 22 - 2 * SmithPuzzles.Turning.STOCK * unit) / 2);
        int by = py + ph - FOOTER + 2;
        roughB = add(new UcButton(px + PAD, by, 64, 18, "Roughing", b -> { finish = false; labels(); }).kind(UcButton.Kind.CHIP)
            .tooltip("Roughing: cuts up to " + v.rough() + " steps deep in one pass", "and leaves tool marks (hatched). Shortcut: R"));
        finishB = add(new UcButton(px + PAD + 68, by, 64, 18, "Finishing", b -> { finish = true; labels(); }).kind(UcButton.Kind.CHIP)
            .tooltip("Finishing: one step at most,", "and leaves a clean surface. Shortcut: F"));
        downB = add(new UcButton(px + PAD + 140, by, 18, 18, "-", b -> setLine(line - 1)).tooltip("Run the tool one step deeper (wheel down)"));
        upB = add(new UcButton(px + PAD + 200, by, 18, 18, "+", b -> setLine(line + 1)).tooltip("Run the tool one step higher (wheel up)"));
        cutB = add(new UcButton(px + PAD + 226, by, 52, 18, "Cut", b -> cut()).kind(UcButton.Kind.PRIMARY).tooltip("One pass along the stretch. Shortcut: Enter"));
        sleeveB = add(new UcButton(px + PAD + 282, by, 56, 18, "Sleeve", b -> sleeve()).tooltip("Build a spoiled section back up a step", "(one iron nugget; the part is Fine at best)"));
        add(new UcButton(px + pw - PAD - 52, by, 52, 18, "Close", b -> onClose()));
        labels();
    }

    private void labels() {
        if (roughB == null) return;
        roughB.selected(!finish);
        finishB.selected(finish);
        boolean picked = selA >= 0;
        cutB.active = picked;
        int[] s = selection();
        sleeveB.active = picked && s[0] == s[1] && spoiled(s[0]);
    }

    private boolean spoiled(int i) { return i >= 0 && i < v.target().length && (v.radius()[i] < v.target()[i] || v.radius()[i] == v.target()[i] && v.marked()[i]); }

    void setLine(int next) { line = Math.max(1, Math.min(SmithPuzzles.Turning.STOCK - 1, next)); }
    void select(int a, int b) { selA = a; selB = b; labels(); }

    private void cut() {
        if (selA < 0) return;
        int[] s = selection();
        SmithNetwork.move(SmithNetwork.CUT, s[0], s[1], line, finish ? 1 : 0);
    }

    private void sleeve() {
        if (selA < 0) return;
        SmithNetwork.move(SmithNetwork.SLEEVE, selection()[0], 0, 0, 0);
    }

    @Override public void onClose() { SmithNetwork.move(SmithNetwork.CLOSE, 0, 0, 0, 0); super.onClose(); }

    /** The rectangle of a section's column (the whole height of the well). */
    private int[] rect(int i) { return new int[] {gx + i * cell + 2, mid - SmithPuzzles.Turning.STOCK * unit - 2, cell - 4, 2 * SmithPuzzles.Turning.STOCK * unit + 4}; }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        for (int i = 0; i < v.target().length; i++) {
            int[] r = rect(i);
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                if (selA >= 0 && (button == 1 || Screen.hasShiftDown())) selB = i;
                else { selA = i; selB = i; line = Math.max(1, v.radius()[i] - 1); }
                labels();
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) { setLine(line + (delta > 0 ? 1 : -1)); return true; }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == 82) { finish = false; labels(); return true; }     // R
        if (key == 70) { finish = true; labels(); return true; }      // F
        if (key == 265) { setLine(line + 1); return true; }           // up
        if (key == 264) { setLine(line - 1); return true; }           // down
        if (key == 257 || key == 335) { cut(); return true; }         // Enter
        return super.keyPressed(key, scan, modifiers);
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String passes = v.passes() + " / " + v.standard();
        UcUi.chip(g, font, passes, right - UcUi.displayWidth(font, passes) - 10, py + 9, v.passes() <= v.standard() ? UcUi.OK : v.passes() <= v.standard() + 2 ? UcUi.AMBER : UcUi.DIM, UcUi.Icon.GEAR);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), w = bodyW();
        String head = "Passes " + v.passes() + ", the shop's standard " + v.standard() + ". Roughing up to " + v.rough() + " steps." + (v.sleeves() > 0 ? " Sleeves: " + v.sleeves() + "." : "");
        UcUi.text(g, font, UcUi.fit(font, head, w), x, bodyY(), UcUi.SOFT);
        String help = selA < 0 ? "Click a section to start a stretch; Shift-click another to stretch it." : (finish ? "Finishing" : "Roughing") + " sections " + (selection()[0] + 1) + " to " + (selection()[1] + 1) + " at line " + line + ".";
        UcUi.text(g, font, UcUi.fit(font, help, w), x, bodyY() + 11, finish ? UcUi.LEGAL : UcUi.AMBER);
        UcUi.well(g, x, bodyY() + 22, w, bodyBottom() - bodyY() - 22 - 12);
        int n = v.target().length;
        // the centre line
        g.fill(gx - 6, mid, gx + n * cell + 6, mid + 1, 0xff2a4259);
        int[] s = selection();
        for (int i = 0; i < n; i++) {
            int cx0 = gx + i * cell + 2, cx1 = gx + (i + 1) * cell - 2;
            boolean chosen = selA >= 0 && i >= s[0] && i <= s[1];
            if (chosen) g.fill(cx0 - 1, mid - SmithPuzzles.Turning.STOCK * unit - 3, cx1 + 1, mid + SmithPuzzles.Turning.STOCK * unit + 3, 0x3348c0ff);
            int r = v.radius()[i], t = v.target()[i];
            boolean bad = spoiled(i), done = r == t && !v.marked()[i];
            int metal = bad ? 0xffb85a4e : done ? 0xffd7e2ec : 0xff9fb0c2;
            g.fill(cx0, mid - r * unit, cx1, mid + r * unit, metal);
            g.fill(cx0, mid - r * unit, cx1, mid - r * unit + 1, UcUi.shade(metal, 1.25f));
            g.fill(cx0, mid + r * unit - 1, cx1, mid + r * unit, UcUi.shade(metal, 0.6f));
            if (v.marked()[i]) for (int y = mid - r * unit + 2; y < mid + r * unit - 1; y += 3) g.fill(cx0 + 1, y, cx1 - 1, y + 1, UcUi.shade(metal, 0.72f));   // tool marks
            for (int dx = cx0; dx < cx1; dx += 4) { g.fill(dx, mid - t * unit, Math.min(cx1, dx + 2), mid - t * unit + 1, UcUi.AMBER); g.fill(dx, mid + t * unit - 1, Math.min(cx1, dx + 2), mid + t * unit, UcUi.AMBER); }
            if (done) UcUi.text(g, font, "ok", cx0 + (cx1 - cx0) / 2 - font.width("ok") / 2, mid - 4, 0xff1f6f47);
            String no = Integer.toString(i + 1);
            UcUi.text(g, font, no, cx0 + (cx1 - cx0) / 2 - font.width(no) / 2, mid + SmithPuzzles.Turning.STOCK * unit + 4, chosen ? UcUi.TEXT : UcUi.FAINT);
        }
        // where the tool will run
        if (selA >= 0) {
            int lx0 = gx + s[0] * cell, lx1 = gx + (s[1] + 1) * cell;
            int tool = finish ? UcUi.LEGAL : UcUi.AMBER;
            g.fill(lx0, mid - line * unit - 1, lx1, mid - line * unit + 1, tool);
            g.fill(lx0, mid + line * unit - 1, lx1, mid + line * unit + 1, tool);
        }
        UcUi.text(g, font, UcUi.fit(font, v.note(), w), x, bodyBottom() - 10, v.note().startsWith("Too") || v.note().startsWith("A finishing") || v.note().contains("would not") ? UcUi.WARN : UcUi.DIM);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String depth = "line " + line;
        UcUi.text(g, font, depth, px + PAD + 162 + (36 - font.width(depth)) / 2, y + 8, finish ? UcUi.LEGAL : UcUi.AMBER);
    }
}
