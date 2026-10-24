package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * An install or a network upgrade at a utility panel (D-158): a patch grid whose tiles turn a quarter with each click. The feed comes in from the left; every device on the right edge must be
 * reached through matching connections. Lit tiles carry the feed. Escape closes the panel and changes nothing.
 */
@OnlyIn(Dist.CLIENT)
public final class PatchScreen extends UcScreen {
    private TechNetwork.PatchView v;
    private int cell, gx, gy;

    PatchScreen(TechNetwork.PatchView view) {
        super(Component.literal("Underground City | Patch panel"));
        this.v = view;
    }

    void update(TechNetwork.PatchView view) { this.v = view; }
    /** Scenario hooks. */
    TechNetwork.PatchView view() { return v; }
    int[] cellCentre(int index) { return new int[] {gx + (index % v.w()) * cell + cell / 2, gy + (index / v.w()) * cell + cell / 2}; }

    @Override protected int accent() { return UcUi.LEGAL; }
    @Override protected String titleText() { return v.kind().equals("install") ? "Install: wire the alarm" : "Upgrade: route the network"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.GEAR; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(300, 220, 440, 300);
        int availW = bodyW() - 60, availH = bodyBottom() - bodyY() - 20;
        cell = Math.max(16, Math.min(32, Math.min(availW / v.w(), availH / v.h())));
        gx = bodyX() + (bodyW() - v.w() * cell) / 2;
        gy = bodyY() + 16 + (availH - v.h() * cell) / 2;
        add(new UcButton(px + pw - PAD - 64, py + ph - FOOTER + 2, 64, 18, "Close", b -> onClose()));
    }

    @Override public void onClose() { TechNetwork.move(TechNetwork.CLOSE, 0); super.onClose(); }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= gx && mx < gx + v.w() * cell && my >= gy && my < gy + v.h() * cell) {
            int cx = (int) ((mx - gx) / cell), cy = (int) ((my - gy) / cell);
            TechNetwork.move(TechNetwork.TURN, cy * v.w() + cx);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String time = v.secondsLeft() + " s";
        UcUi.chip(g, font, time, right - UcUi.displayWidth(font, time) - 10, py + 9, v.secondsLeft() < 20 ? UcUi.UNDERGROUND : UcUi.AMBER, UcUi.Icon.CLOCK);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        UcUi.text(g, font, UcUi.fit(font, "Click a tile to turn it. Feed every device on the right.", bodyW()), bodyX(), bodyY(), UcUi.DIM);
        TechPuzzles.Patch p = new TechPuzzles.Patch(v.w(), v.h(), v.source(), v.outputs(), v.tiles(), v.tiles().clone());
        boolean[] on = p.powered();
        UcUi.well(g, gx - 4, gy - 4, v.w() * cell + 8, v.h() * cell + 8);
        // the feed and the devices
        int sy = gy + v.source() * cell + cell / 2;
        g.fill(gx - 20, sy - 4, gx - 8, sy + 4, UcUi.AMBER);
        g.fill(gx - 8, sy - 1, gx, sy + 1, UcUi.AMBER);
        for (int row : v.outputs()) {
            int oy = gy + row * cell + cell / 2;
            int cellIndex = row * v.w() + v.w() - 1;
            boolean fed = on[cellIndex] && (v.tiles()[cellIndex] & TechPuzzles.E) != 0;
            g.fill(gx + v.w() * cell, oy - 1, gx + v.w() * cell + 8, oy + 1, fed ? UcUi.LEGAL : 0xff4a5f73);
            g.fill(gx + v.w() * cell + 8, oy - 5, gx + v.w() * cell + 20, oy + 5, fed ? UcUi.LEGAL : 0xff2a3440);
        }
        for (int i = 0; i < v.tiles().length; i++) {
            int x = gx + (i % v.w()) * cell, y = gy + (i / v.w()) * cell;
            boolean hover = mouseX >= x && mouseX < x + cell && mouseY >= y && mouseY < y + cell;
            g.fill(x + 1, y + 1, x + cell - 1, y + cell - 1, hover ? 0xff22384c : 0xff15222f);
            int colour = on[i] ? UcUi.LEGAL : 0xff6f8293;
            int c = cell / 2, t = Math.max(1, cell / 10);
            g.fill(x + c - t, y + c - t, x + c + t, y + c + t, colour);
            int m = v.tiles()[i];
            if ((m & TechPuzzles.N) != 0) g.fill(x + c - t, y + 1, x + c + t, y + c, colour);
            if ((m & TechPuzzles.S) != 0) g.fill(x + c - t, y + c, x + c + t, y + cell - 1, colour);
            if ((m & TechPuzzles.W) != 0) g.fill(x + 1, y + c - t, x + c, y + c + t, colour);
            if ((m & TechPuzzles.E) != 0) g.fill(x + c, y + c - t, x + cell - 1, y + c + t, colour);
        }
        int fed = 0;
        for (int row : v.outputs()) { int ci = row * v.w() + v.w() - 1; if (on[ci] && (v.tiles()[ci] & TechPuzzles.E) != 0) fed++; }
        UcUi.text(g, font, fed + " of " + v.outputs().length + " devices fed", bodyX(), bodyBottom() - 10, fed == v.outputs().length ? UcUi.OK : UcUi.SOFT);
    }
}
