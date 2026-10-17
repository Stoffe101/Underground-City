package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * A corner notice in the Underground City look: a dark plate with an accent edge and an icon, a display-font title and one or two lines of
 * words. It slides in, rests for a few seconds (longer for a longer message) and slides out; a sound marks it, pitched to its kind.
 */
@OnlyIn(Dist.CLIENT)
public final class UcToast implements Toast {
    private static final long BASE_MS = 4200L;
    private final Notice.Kind kind;
    private final String title, body;
    private boolean played;

    public UcToast(Notice.Kind kind, String title, String body) { this.kind = kind; this.title = title; this.body = body; }

    static UcUi.Icon icon(Notice.Kind kind) {
        return switch (kind) {
            case ORDER -> UcUi.Icon.CRATE; case DELIVERED -> UcUi.Icon.CHECK; case SKILL -> UcUi.Icon.POINT; case STEP -> UcUi.Icon.PIN;
            case SEARCH -> UcUi.Icon.SCALES; case HEAT -> UcUi.Icon.HEAT; case LOOT -> UcUi.Icon.KEY; case TRUST -> UcUi.Icon.TRUST;
            case WARN -> UcUi.Icon.WARNING; default -> UcUi.Icon.INFO;
        };
    }

    static int color(Notice.Kind kind) {
        return switch (kind) {
            case ORDER, SKILL, STEP -> UcUi.LEGAL; case DELIVERED -> UcUi.OK; case LOOT -> UcUi.AMBER;
            case HEAT, WARN -> UcUi.UNDERGROUND; case SEARCH -> UcUi.WARN; case TRUST -> UcUi.AMBER; default -> UcUi.DIM;
        };
    }

    private float pitch() {
        return switch (kind) { case DELIVERED -> 1.4f; case LOOT -> 1.6f; case SKILL -> 1.25f; case HEAT, WARN -> 0.7f; case SEARCH -> 0.85f; default -> 1.05f; };
    }

    @Override public int width() { return 190; }
    /** A plate grows with its words, up to three lines of them (still two of the game's 32-pixel toast slots). */
    @Override public int height() { return body.isEmpty() ? 30 : 22 + 10 * lines().size(); }

    private List<String> lines() {
        if (lines == null) {
            List<String> wrapped = UcUi.wrapLines(Minecraft.getInstance().font, body, width() - 32 - 6);
            lines = wrapped.size() > 3 ? List.of(wrapped.get(0), wrapped.get(1), UcUi.fit(Minecraft.getInstance().font, String.join(" ", wrapped.subList(2, wrapped.size())), width() - 38)) : wrapped;
        }
        return lines;
    }

    private List<String> lines;

    @Override public Visibility render(GuiGraphics g, ToastComponent toasts, long visibleMs) {
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (!played) {
            played = true;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), pitch(), 0.6f));
        }
        int w = width(), h = height(), accent = color(kind);
        g.fill(0, 0, w, h, 0xff05080c);
        g.fillGradient(1, 1, w - 1, h - 1, 0xff1b2d40, 0xff0f1a26);
        g.fill(1, 1, w - 1, 2, 0xff4f7088);
        g.fill(1, 1, 5, h - 1, accent);
        g.fill(5, 1, 6, h - 1, UcUi.shade(accent, 0.5f));
        UcUi.icon(g, icon(kind), 11, (h - 16) / 2, accent);
        int tx = 32;
        UcUi.display(g, font, title, tx, body.isEmpty() ? (h - 8) / 2 : 6, UcUi.TEXT);
        if (!body.isEmpty()) {
            List<String> shown = lines();
            for (int i = 0; i < shown.size(); i++) g.drawString(font, shown.get(i), tx, 18 + i * 10, UcUi.SOFT, false);
        }
        return visibleMs < BASE_MS + (body.length() > 60 ? 1800L : 0L) ? Visibility.SHOW : Visibility.HIDE;
    }
}
