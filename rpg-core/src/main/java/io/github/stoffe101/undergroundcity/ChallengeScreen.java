package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The Technician's trace (D-050): the terminal flashes a five-step signal across four labelled pads, then the
 * player repeats it from memory before the lock resets. The server owns the sequence, the timing and the verdict;
 * this screen only displays the signal and relays one answer. Every pad has a number and a distinct colour, so the
 * puzzle never relies on colour alone, and the keys 1-4 work as well as the mouse.
 */
@OnlyIn(Dist.CLIENT)
public final class ChallengeScreen extends UcScreen {
    private static final int[] PAD_COLOUR = {0xff55d3bc, 0xffe1af6c, 0xff6cb4e1, 0xffd36cd0};
    private static final String[] PAD_NAME = {"1", "2", "3", "4"};
    private static final int LIT_TICKS = 9;

    private final BlockPos pos;
    private final byte[] sequence;
    private final byte[] entered;
    /** What is being worked: a secured terminal (the Technician's trace) or an alarm panel (D-128), and how fast its signal runs. */
    private final Kind kind;
    private final int perSymbol, inputTicks;

    /** The two things a trace opens. */
    public enum Kind { TERMINAL, ALARM }
    private int count, age, flashPad = -1, flashAge;
    private boolean sent, frozen, qa;
    private int padSize, padGap, padTop;

    ChallengeScreen(BlockPos pos, byte[] sequence) { this(pos, sequence, Kind.TERMINAL, SecureTerminalService.SHOW_TICKS_PER_SYMBOL, SecureTerminalService.INPUT_TICKS); }

    ChallengeScreen(BlockPos pos, byte[] sequence, Kind kind, int perSymbol, int inputTicks) {
        super(Component.literal(kind == Kind.ALARM ? "Underground City | Alarm panel" : "Underground City | Secure terminal"));
        this.pos = pos;
        this.sequence = sequence.clone();
        this.entered = new byte[this.sequence.length];
        this.kind = kind;
        this.perSymbol = Math.max(6, perSymbol);
        this.inputTicks = Math.max(40, inputTicks);
    }

    /** Capture harness: a fixed pose that never advances or talks to a server. */
    static ChallengeScreen forCapture(byte[] sequence) {
        ChallengeScreen screen = new ChallengeScreen(BlockPos.ZERO, sequence);
        screen.qa = screen.frozen = true;
        return screen;
    }

    /** Freezes the screen mid-sequence: {@code lit} is the pad currently flashing (-1 none), {@code step} how far in. */
    void poseShowing(int step, boolean lit) {
        age = step * perSymbol + (lit ? 2 : LIT_TICKS + 2);
    }

    /** Freezes the screen in the input phase with {@code taken} answers already entered and a fraction of time spent. */
    void poseInput(int taken, double spent) {
        count = Math.max(0, Math.min(sequence.length - 1, taken));
        System.arraycopy(sequence, 0, entered, 0, count);
        age = sequence.length * perSymbol + (int) (spent * inputTicks);
        flashPad = -1;
    }

    private int showTicks() { return sequence.length * perSymbol; }
    private boolean showing() { return age < showTicks(); }
    private int litPad() {
        if (!showing()) return -1;
        int step = age / perSymbol;
        return age % perSymbol < Math.min(LIT_TICKS, perSymbol - 3) ? sequence[step] : -1;
    }

    @Override protected int accent() { return UcUi.UNDERGROUND; }
    @Override protected String titleText() { return kind == Kind.ALARM ? "Alarm panel" : "Secured terminal"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TECHNICIAN; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(318, 236, 390, 280);
        padGap = 8;
        padSize = Math.max(40, Math.min(68, (pw - 2 * PAD - 3 * padGap - 12) / 4));
        padTop = py + HEADER + 50;
    }

    @Override public void tick() {
        if (frozen) return;
        age++;
        if (flashPad >= 0 && ++flashAge > 4) flashPad = -1;
        // The server's deadline is final; once this timer is spent the answer would be refused, so stop waiting.
        if (!sent && age > showTicks() + inputTicks) finish(true);
    }

    private int padX(int index) {
        int row = padSize * 4 + padGap * 3;
        return px + (pw - row) / 2 + index * (padSize + padGap);
    }

    private void press(int pad) {
        if (sent || showing() || pad < 0 || pad > 3 || count >= entered.length) return;
        entered[count++] = (byte) pad;
        flashPad = pad;
        flashAge = 0;
        minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.value(), 0.8f + pad * 0.2f, 0.5f));
        if (count == entered.length) finish(false);
    }

    private void finish(boolean timedOut) {
        if (sent) return;
        sent = true;
        if (!qa) TerminalNetwork.sendChallengeAnswer(pos, timedOut ? new byte[0] : entered);
        if (minecraft != null && minecraft.screen == this) onClose();
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !showing() && !sent) {
            for (int i = 0; i < 4; i++) {
                int x = padX(i);
                if (mouseX >= x && mouseX < x + padSize && mouseY >= padTop && mouseY < padTop + padSize) { press(i); return true; }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= 49 && key <= 52) { press(key - 49); return true; }       // 1-4
        if (key >= 321 && key <= 324) { press(key - 321); return true; }    // keypad 1-4
        return super.keyPressed(key, scan, modifiers);
    }

    /** Leaving the screen early counts as walking away from the trace: the server trips the alarm at once. */
    @Override public void removed() {
        if (!sent) {
            sent = true;
            if (!qa) TerminalNetwork.sendChallengeAnswer(pos, new byte[0]);
        }
        super.removed();
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String chip = kind == Kind.ALARM ? "Bypass" : "Technician";
        int w = UcUi.chip(g, font, chip, right - UcUi.displayWidth(font, chip) - 22, py + 9, UcUi.careerColor("technician"), UcUi.Icon.TECHNICIAN);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        boolean show = showing();
        int lit = litPad();
        int cxm = px + pw / 2, y = py + HEADER + 12;
        String status = show ? "Watch the signal" : count >= entered.length ? "Sending" : "Repeat the sequence";
        UcUi.displayCentre(g, font, status, cxm, y, show ? UcUi.AMBER : UcUi.LEGAL);
        String help = show ? (kind == Kind.ALARM ? "Find the panel's code: " : "") + sequence.length + " signals, four pads. Do not press yet." : "Press the pads in the same order.";
        UcUi.text(g, font, help, cxm - font.width(help) / 2, y + 14, UcUi.DIM);

        for (int i = 0; i < 4; i++) {
            int x = padX(i), colour = PAD_COLOUR[i];
            boolean on = i == lit || (i == flashPad && !show);
            boolean hover = !show && !sent && mouseX >= x && mouseX < x + padSize && mouseY >= padTop && mouseY < padTop + padSize;
            // a seated key: dark well, a rim in the pad's colour, a face that lights up and a lower edge
            g.fill(x - 3, padTop - 3, x + padSize + 3, padTop + padSize + 4, 0xff05080c);
            g.fill(x - 2, padTop - 2, x + padSize + 2, padTop + padSize + 3, on ? 0xffffffff : UcUi.shade(colour, hover ? 0.9f : 0.5f));
            int face = on ? colour : UcUi.mix(0xff0d1620, colour, hover ? 0.34f : 0.18f);
            g.fillGradient(x, padTop, x + padSize, padTop + padSize, on ? UcUi.shade(colour, 1.15f) : face, on ? colour : UcUi.shade(face, 0.8f));
            g.fill(x, padTop + padSize, x + padSize, padTop + padSize + 2, on ? UcUi.shade(colour, 0.7f) : UcUi.shade(face, 0.5f));
            if (on) { g.fill(x - 6, padTop - 6, x + padSize + 6, padTop - 5, UcUi.shade(colour, 0.6f)); }
            g.pose().pushPose();
            g.pose().translate(x + padSize / 2f, padTop + padSize / 2f - 8, 0);
            g.pose().scale(2f, 2f, 1f);
            // No drop shadow: a dark digit with a dark shadow on a lit pad smudges into a blob.
            g.drawString(font, PAD_NAME[i], -font.width(PAD_NAME[i]) / 2, 0, on ? 0xff101c2a : colour, false);
            g.pose().popPose();
        }

        // progress: five lamps, one per signal
        int lampW = 14, gap = 5, lampsW = entered.length * (lampW + gap) - gap;
        int lampY = padTop + padSize + 16;
        int shown = show ? Math.min(entered.length, age / perSymbol + (lit >= 0 ? 1 : 0)) : count;
        for (int i = 0; i < entered.length; i++) {
            int x = cxm - lampsW / 2 + i * (lampW + gap);
            int color = show ? UcUi.AMBER : UcUi.LEGAL;
            g.fill(x, lampY, x + lampW, lampY + 8, 0xff05080c);
            g.fill(x + 1, lampY + 1, x + lampW - 1, lampY + 7, i < shown ? UcUi.shade(color, 0.8f) : 0xff1b2a38);
            if (i < shown) g.fill(x + 1, lampY + 1, x + lampW - 1, lampY + 2, color);
        }
        int barW = Math.min(pw - 2 * PAD - 40, 260), barX = cxm - barW / 2, barY = lampY + 18;
        double remaining = show ? 1.0 : Math.max(0, 1.0 - (age - showTicks()) / (double) inputTicks);
        UcUi.bar(g, barX, barY, barW, 7, (float) remaining, remaining > 0.5 ? UcUi.LEGAL : remaining > 0.25 ? UcUi.AMBER : UcUi.UNDERGROUND);
        String timer = show ? (kind == Kind.ALARM ? "The panel resets after the signal" : "The lock resets after the signal")
            : String.format(kind == Kind.ALARM ? "Panel resets in %.0fs" : "Lock resets in %.0fs", remaining * inputTicks / 20.0);
        UcUi.text(g, font, timer, cxm - font.width(timer) / 2, barY + 11, remaining <= 0.25 && !show ? UcUi.UNDERGROUND_TEXT : UcUi.DIM);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        UcUi.icon(g, UcUi.Icon.KEY, px + PAD, y + 4, UcUi.DIM);
        UcUi.text(g, font, "Keys 1-4 or click", px + PAD + 20, y + 9, UcUi.DIM);
        String abandon = "Esc trips the alarm";
        int w = font.width(abandon) + 20;
        UcUi.icon(g, UcUi.Icon.WARNING, px + pw - PAD - w, y + 4, UcUi.UNDERGROUND);
        UcUi.text(g, font, abandon, px + pw - PAD - w + 20, y + 9, UcUi.UNDERGROUND_TEXT);
    }
}
