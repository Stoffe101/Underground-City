package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/**
 * Picking a lock (D-105). A pick slides along a bar; the lock answers with how close it feels (nothing, a faint catch, close) and, under a firm press, whether the pin gives. The player
 * finds the spot by sliding the pick and reading the feel, then presses on it and holds. Pressing anywhere else strains the pick and a strained pick snaps. Everything that decides the
 * outcome runs on the server ({@link LockpickSession}); this screen reports the pick's place and the button, and draws what it is told. The sweet spots are never sent here, and the feel is
 * shown as words and brightness, never as colour alone.
 *
 * <p>Mouse: slide, hold the left button. Keyboard: left and right arrows slide, space presses. Escape stops (the pick is not lost).
 */
@OnlyIn(Dist.CLIENT)
public final class LockpickScreen extends UcScreen {
    private static final String[] FEEL = {"Nothing yet", "A faint catch", "Close", "It gives"};
    private static final int[] FEEL_COLOUR = {0xff3a4f63, 0xff8a6a2c, 0xffe8b04a, 0xff7bd88f};

    private final String label;
    private final int pins, timeLimit, holdTicks;
    private double position = 0.5;
    private boolean mouseDown, spaceDown, left, right, keyboardMode;
    private int feel, pinsSet, strain, timeLeft, age, closeIn = -1, lastSentTick = -100, pulse;
    private float lastSentPosition = -1;
    private boolean lastSentPress;
    private LockpickSession.State result;
    private String note = "";
    private boolean qa;
    private int barX, barY, barW;

    LockpickScreen(String label, int pins, int timeLimit, int holdTicks) {
        super(Component.literal("Underground City | Lock"));
        this.label = label;
        this.pins = pins;
        this.timeLimit = Math.max(1, timeLimit);
        this.holdTicks = Math.max(1, holdTicks);
        this.timeLeft = timeLimit;
    }

    /** Capture harness: a fixed pose that never talks to a server. */
    static LockpickScreen forCapture(String label, int pins, int set, int feel, int strain, double timeLeftShare, double position, boolean pressing) {
        LockpickScreen screen = new LockpickScreen(label, pins, 900, 10);
        screen.qa = true;
        screen.pinsSet = set; screen.feel = feel; screen.strain = strain; screen.timeLeft = (int) (900 * timeLeftShare);
        screen.position = position; screen.mouseDown = pressing;
        return screen;
    }

    /** Capture harness: the screen after the server ended the pick. */
    LockpickScreen poseResult(LockpickSession.State state, String words) { result = state; note = words; return this; }

    void update(int feel, int pinsSet, int strain, int timeLeft) {
        if (pinsSet > this.pinsSet && minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 0.7f + 0.15f * pinsSet, 0.9f));
        }
        this.feel = Mth.clamp(feel, 0, 3); this.pinsSet = pinsSet; this.strain = Mth.clamp(strain, 0, 100); this.timeLeft = timeLeft;
    }

    void finish(LockpickSession.State state, String words) {
        result = state; note = words; closeIn = state == LockpickSession.State.OPENED ? 14 : state == LockpickSession.State.ABORTED ? 0 : 30;
        if (minecraft != null && state == LockpickSession.State.BROKEN) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_BREAK, 1.0f, 1.0f));
        if (closeIn == 0) onClose();
    }

    @Override protected int accent() { return UcUi.UNDERGROUND; }
    @Override protected String titleText() { return "Pick the lock"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.LOCK; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(320, 214, 420, 250);
        barW = Math.min(pw - 2 * PAD - 24, 340);
        barX = px + (pw - barW) / 2;
        barY = py + HEADER + 62;
    }

    @Override public void tick() {
        age++; pulse++;
        if (closeIn > 0 && --closeIn == 0) { onClose(); return; }
        if (qa || result != null) return;
        if (keyboardMode) position = Mth.clamp(position + ((right ? 1 : 0) - (left ? 1 : 0)) * 0.012, 0.0, 1.0);
        boolean pressing = mouseDown || spaceDown;
        // a report whenever something changed, and a heartbeat so the server never works on an old position
        if (Math.abs(position - lastSentPosition) > 0.0009 || pressing != lastSentPress || age - lastSentTick >= 4) {
            LockNetwork.sendInput((float) position, pressing);
            lastSentPosition = (float) position; lastSentPress = pressing; lastSentTick = age;
        }
    }

    @Override public void mouseMoved(double mouseX, double mouseY) {
        if (result == null && barW > 0) { keyboardMode = false; position = Mth.clamp((mouseX - barX) / (double) barW, 0.0, 1.0); }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && result == null) { keyboardMode = false; mouseDown = true; position = Mth.clamp((mouseX - barX) / (double) barW, 0.0, 1.0); return true; }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) { mouseDown = false; return true; }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 0 && result == null) { position = Mth.clamp((mouseX - barX) / (double) barW, 0.0, 1.0); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) { left = true; keyboardMode = true; return true; }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) { right = true; keyboardMode = true; return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { spaceDown = true; return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) { left = false; return true; }
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) { right = false; return true; }
        if (key == GLFW.GLFW_KEY_SPACE) { spaceDown = false; return true; }
        return super.keyReleased(key, scan, modifiers);
    }

    /** Leaving the screen stops the pick; the server keeps the lock as it was and nothing is lost. */
    @Override public void removed() {
        if (!qa && result == null) { result = LockpickSession.State.ABORTED; LockNetwork.sendAbort(); }
        super.removed();
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        UcUi.chip(g, font, label, right - UcUi.displayWidth(font, label) - 22, py + 9, UcUi.UNDERGROUND, UcUi.Icon.LOCK);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int cx = px + pw / 2, y = py + HEADER + 10;
        boolean pressing = mouseDown || spaceDown;
        // pins: one lamp each
        int lampW = 18, gap = 6, row = pins * (lampW + gap) - gap;
        for (int i = 0; i < pins; i++) {
            int x = cx - row / 2 + i * (lampW + gap);
            boolean set = i < pinsSet, current = i == pinsSet && result == null;
            g.fill(x, y, x + lampW, y + 10, 0xff05080c);
            g.fill(x + 1, y + 1, x + lampW - 1, y + 9, set ? UcUi.shade(UcUi.OK, 0.8f) : current ? UcUi.mix(0xff1b2a38, UcUi.AMBER, pressing ? 0.55f : 0.25f) : 0xff1b2a38);
            if (set) g.fill(x + 1, y + 1, x + lampW - 1, y + 2, UcUi.OK);
        }
        String status = result != null ? note : pinsSet >= pins ? "Open" : "Pin " + (pinsSet + 1) + " of " + pins;
        UcUi.displayCentre(g, font, result != null ? resultTitle() : status, cx, y + 16, result == null ? UcUi.TEXT : resultColour());
        if (result != null && !note.isEmpty()) UcUi.text(g, font, UcUi.fit(font, note, pw - 2 * PAD), cx - font.width(UcUi.fit(font, note, pw - 2 * PAD)) / 2, y + 30, UcUi.SOFT);

        // the bar: a slot, tick marks, the glow of the feel, the pick
        int by = barY + 4, bh = 26;
        g.fill(barX - 3, by - 3, barX + barW + 3, by + bh + 3, 0xff05080c);
        g.fill(barX - 2, by - 2, barX + barW + 2, by + bh + 2, 0xff2a4259);
        g.fill(barX, by, barX + barW, by + bh, 0xff0d1620);
        for (int t = 1; t < 10; t++) g.fill(barX + barW * t / 10, by + bh - 5, barX + barW * t / 10 + 1, by + bh, 0xff22364a);
        int px0 = barX + Math.round((float) (position * barW));
        int glow = FEEL_COLOUR[feel];
        if (feel > 0 && result == null) {
            int spread = feel == 1 ? 34 : feel == 2 ? 22 : 14;
            g.fillGradient(Math.max(barX, px0 - spread), by, px0, by + bh, 0x00000000, UcUi.mix(0xff0d1620, glow, feel == 3 ? 0.7f : 0.45f));
            g.fillGradient(px0, by, Math.min(barX + barW, px0 + spread), by + bh, UcUi.mix(0xff0d1620, glow, feel == 3 ? 0.7f : 0.45f), 0x00000000);
        }
        // the pick: a needle with a head; it presses down when the button is held
        int tip = by + (pressing ? 8 : 4);
        g.fill(px0 - 1, tip, px0 + 2, by + bh + 3, pressing ? 0xffffffff : 0xffd5dde5);
        g.fill(px0 - 4, tip - 4, px0 + 5, tip, pressing ? UcUi.AMBER : 0xffb9cbd9);
        g.fill(px0 - 3, tip - 5, px0 + 4, tip - 4, pressing ? UcUi.shade(UcUi.AMBER, 1.2f) : 0xffeaf2f8);

        // what it feels like, in words
        String feelText = FEEL[feel];
        UcUi.display(g, font, feelText, cx - UcUi.displayWidth(font, feelText) / 2, by + bh + 10, result == null ? (feel == 0 ? UcUi.DIM : glow) : UcUi.FAINT);

        // strain and time
        int meterW = Math.min(barW, 280), meterX = cx - meterW / 2, my = by + bh + 28;
        UcUi.text(g, font, "Pick strain", meterX, my, UcUi.DIM);
        UcUi.bar(g, meterX + 70, my + 1, meterW - 70, 7, strain / 100f, strain > 70 ? UcUi.UNDERGROUND : strain > 40 ? UcUi.WARN : UcUi.AMBER);
        UcUi.text(g, font, "Time", meterX, my + 13, UcUi.DIM);
        float share = timeLeft / (float) timeLimit;
        UcUi.bar(g, meterX + 70, my + 14, meterW - 70, 7, share, share > 0.5f ? UcUi.LEGAL : share > 0.25f ? UcUi.AMBER : UcUi.UNDERGROUND);
        if (strain >= 70 && result == null && (pulse / 4) % 2 == 0) UcUi.icon(g, UcUi.Icon.WARNING, meterX + meterW + 4, my - 4, UcUi.UNDERGROUND);
    }

    private String resultTitle() {
        return switch (result) {
            case OPENED -> "Open";
            case BROKEN -> "Pick snapped";
            case TIMED_OUT -> "Out of time";
            default -> "Stopped";
        };
    }

    private int resultColour() {
        return switch (result) {
            case OPENED -> UcUi.OK;
            case BROKEN -> UcUi.UNDERGROUND_TEXT;
            case TIMED_OUT -> UcUi.AMBER;
            default -> UcUi.DIM;
        };
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String how = "Slide: mouse or arrows   Press: hold left button or Space";
        UcUi.icon(g, UcUi.Icon.KEY, px + PAD, y + 4, UcUi.DIM);
        UcUi.text(g, font, UcUi.fit(font, how, pw - 2 * PAD - 100), px + PAD + 20, y + 9, UcUi.DIM);
        String stop = "Esc stops";
        UcUi.text(g, font, stop, px + pw - PAD - font.width(stop), y + 9, UcUi.SOFT);
    }
}
