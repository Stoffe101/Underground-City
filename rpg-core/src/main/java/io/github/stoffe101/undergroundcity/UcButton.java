package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * A raised-plate button in the Underground City style. It is a vanilla {@link Button} underneath (focus, keyboard activation and
 * narration keep working) with its own drawing: three looks (normal, primary amber, danger red), a rail look for navigation, and a
 * flat chip look for filters. Tooltips are drawn by the owning {@link UcScreen}, so they match the rest of the interface.
 */
@OnlyIn(Dist.CLIENT)
public final class UcButton extends Button {
    public enum Kind { PLATE, PRIMARY, DANGER, RAIL, CHIP }

    private Kind kind = Kind.PLATE;
    private UcUi.Icon icon;
    private int accent = UcUi.LEGAL;
    private boolean selected;
    private int badge = -1;
    private List<Component> tooltip = List.of();
    private String display = "";

    public UcButton(int x, int y, int w, int h, String label, OnPress onPress) {
        super(x, y, w, h, Component.literal(label), onPress, DEFAULT_NARRATION);
        this.display = label;
    }

    public UcButton kind(Kind kind) { this.kind = kind; return this; }
    public UcButton icon(UcUi.Icon icon) { this.icon = icon; return this; }
    public UcButton accent(int accent) { this.accent = accent; return this; }
    public UcButton selected(boolean selected) { this.selected = selected; return this; }
    public UcButton badge(int badge) { this.badge = badge; return this; }
    public UcButton label(String label) { this.display = label; setMessage(Component.literal(label)); return this; }
    public UcButton tooltip(String... lines) {
        this.tooltip = java.util.Arrays.stream(lines).filter(line -> line != null && !line.isEmpty()).map(Component::literal).map(c -> (Component) c).toList();
        return this;
    }
    public List<Component> tooltipLines() { return tooltip; }
    public boolean isSelected() { return selected; }

    @Override public void playDownSound(net.minecraft.client.sounds.SoundManager sounds) {
        sounds.play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), active ? 1.15f : 0.8f, 0.55f));
    }

    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        Font font = Minecraft.getInstance().font;
        boolean hover = isHoveredOrFocused() && active;
        switch (kind) {
            case RAIL -> renderRail(g, font, hover);
            case CHIP -> renderChip(g, font, hover);
            default -> {
                UcUi.Look look = kind == Kind.PRIMARY ? UcUi.Look.PRIMARY : kind == Kind.DANGER ? UcUi.Look.DANGER : UcUi.Look.NORMAL;
                UcUi.State state = !active ? UcUi.State.DISABLED : (hover && Minecraft.getInstance().mouseHandler.isLeftPressed() ? UcUi.State.PRESSED : hover ? UcUi.State.HOVER : UcUi.State.NORMAL);
                UcUi.button(g, getX(), getY(), width, height, look, state);
                int textColor = !active ? UcUi.FAINT : kind == Kind.PRIMARY ? 0xff241806 : UcUi.TEXT;
                int tw = UcUi.displayWidth(font, display) + (icon != null ? 18 : 0);
                int tx = getX() + (width - tw) / 2, ty = getY() + (height - 8) / 2 + (state == UcUi.State.PRESSED ? 1 : 0);
                if (icon != null) { UcUi.icon(g, icon, tx - 1, ty - 4, textColor); tx += 18; }
                UcUi.display(g, font, display, tx, ty, textColor);
            }
        }
    }

    private void renderRail(GuiGraphics g, Font font, boolean hover) {
        int kindIndex = selected ? 2 : hover ? 1 : active ? 0 : 3;
        UcUi.card(g, getX(), getY(), width, height, kindIndex);
        if (selected) { g.fill(getX() + 1, getY() + 2, getX() + 4, getY() + height - 2, accent); }
        int color = !active ? UcUi.FAINT : selected ? UcUi.TEXT : hover ? UcUi.TEXT : UcUi.SOFT;
        int ix = getX() + (width < 50 ? (width - 16) / 2 : 8);
        if (icon != null) UcUi.icon(g, icon, ix, getY() + (height - 16) / 2, !active ? UcUi.FAINT : selected ? accent : hover ? UcUi.TEXT : UcUi.DIM);
        if (width >= 50) UcUi.display(g, font, display, getX() + 28, getY() + (height - 8) / 2, color);
        if (badge >= 0 && width >= 50) {
            String number = String.valueOf(badge);
            int bw = font.width(number) + 6;
            g.fill(getX() + width - bw - 5, getY() + height / 2 - 6, getX() + width - 5, getY() + height / 2 + 6, selected ? accent : 0xff2a4259);
            g.drawString(font, number, getX() + width - bw - 2, getY() + height / 2 - 4, selected ? 0xff0d1620 : UcUi.SOFT, false);
        }
    }

    private void renderChip(GuiGraphics g, Font font, boolean hover) {
        int color = selected ? accent : hover ? UcUi.TEXT : UcUi.DIM;
        g.fill(getX(), getY(), getX() + width, getY() + height, selected ? UcUi.mix(0xff0d1620, accent, 0.22f) : hover ? 0xff1c2f40 : 0xff111c27);
        g.fill(getX(), getY(), getX() + width, getY() + 1, selected ? accent : 0xff2a4259);
        g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, selected ? accent : 0xff0a1017);
        g.fill(getX(), getY(), getX() + 1, getY() + height, selected ? UcUi.shade(accent, 0.7f) : 0xff233649);
        g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, selected ? UcUi.shade(accent, 0.7f) : 0xff233649);
        int tw = UcUi.displayWidth(font, display) + (icon != null ? 18 : 0);
        int tx = getX() + (width - tw) / 2;
        if (icon != null) { UcUi.icon(g, icon, tx - 1, getY() + (height - 16) / 2, color); tx += 18; }
        UcUi.display(g, font, display, tx, getY() + (height - 8) / 2, !active ? UcUi.FAINT : color);
    }
}
