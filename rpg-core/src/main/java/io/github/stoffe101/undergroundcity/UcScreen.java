package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Base of every Underground City screen: one enamel panel sized from the free GUI space (it grows on large GUI areas and never
 * shrinks below what its content needs), a header with the title and an accent stripe, a body the subclass draws, widgets, an
 * optional footer and our own tooltips. Layout rules instead of a fixed size are what keep the screens legible from a 1280x720
 * window at GUI scale 2 to a 3440x1440 monitor at GUI scale 2 or 3.
 */
@OnlyIn(Dist.CLIENT)
public abstract class UcScreen extends Screen {
    public static final int HEADER = 30, FOOTER = 24, PAD = 10;
    protected int px, py, pw, ph;
    private final List<UcButton> buttons = new ArrayList<>();

    protected UcScreen(Component title) { super(title); }

    /** Sizes and centres the panel: about 3/4 of the free width and 85% of the free height, clamped to the given limits and to the screen. */
    protected void layoutPanel(int minW, int minH, int maxW, int maxH) {
        pw = Math.min(Mth.clamp((int) (width * 0.74), minW, maxW), width - 8);
        ph = Math.min(Mth.clamp((int) (height * 0.88), minH, maxH), height - 8);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    protected int bodyX() { return px + PAD; }
    protected int bodyY() { return py + HEADER + 6; }
    protected int bodyW() { return pw - 2 * PAD; }
    protected int bodyBottom() { return py + ph - (showFooter() ? FOOTER : 0) - 8; }
    protected int bodyH() { return bodyBottom() - bodyY(); }

    protected UcButton add(UcButton button) {
        addRenderableWidget(button);
        buttons.add(button);
        return button;
    }

    @Override protected void clearWidgets() { super.clearWidgets(); buttons.clear(); }

    /** The colour of the stripe under the header (teal legal, red underground, a city colour...). */
    protected abstract int accent();
    protected abstract String titleText();
    protected UcUi.Icon titleIcon() { return UcUi.Icon.CITY; }
    protected boolean showFooter() { return false; }

    /** Chips and status at the right end of the header; return nothing, draw leftwards from {@code right}. */
    protected void renderHeaderRight(GuiGraphics g, int right) {}
    protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {}
    /** Drawn after the widgets: markers that must sit on top of them. */
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partial) {}
    protected void renderFooter(GuiGraphics g, int y) {}

    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g);
        UcUi.panel(g, px, py, pw, ph);
        // header: lit band, display-font title, accent stripe
        g.fillGradient(px + 3, py + 3, px + pw - 3, py + HEADER - 1, 0xff263b50, 0xff172637);
        g.fill(px + 3, py + HEADER - 1, px + pw - 3, py + HEADER, 0xff070b10);
        g.fill(px + 3, py + HEADER, px + pw - 3, py + HEADER + 2, accent());
        g.fill(px + 3, py + HEADER + 2, px + pw - 3, py + HEADER + 3, UcUi.shade(accent(), 0.45f));
        UcUi.icon(g, titleIcon(), px + PAD, py + 7, accent());
        UcUi.display(g, font, titleText(), px + PAD + 20, py + 11, UcUi.TEXT);
        renderHeaderRight(g, px + pw - PAD);
        if (showFooter()) {
            int fy = py + ph - FOOTER - 3;
            g.fill(px + 3, fy, px + pw - 3, py + ph - 3, 0xff0d1620);
            g.fill(px + 3, fy, px + pw - 3, fy + 1, 0xff2a4259);
            renderFooter(g, fy);
        }
        renderBody(g, mouseX, mouseY, partial);
        super.render(g, mouseX, mouseY, partial);
        renderOverlay(g, mouseX, mouseY, partial);
        for (UcButton button : buttons) {
            if (button.visible && button.isHoveredOrFocused() && !button.tooltipLines().isEmpty() && button.isMouseOver(mouseX, mouseY)) {
                UcUi.tooltip(g, font, button.tooltipLines(), mouseX, mouseY, width, height);
                break;
            }
        }
    }
}
