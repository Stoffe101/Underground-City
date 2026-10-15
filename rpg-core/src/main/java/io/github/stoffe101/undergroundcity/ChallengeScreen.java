package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
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
public final class ChallengeScreen extends Screen {
    private static final int[] PAD_COLOUR = {0xff55d3bc, 0xffe1af6c, 0xff6cb4e1, 0xffd36cd0};
    private static final String[] PAD_NAME = {"1", "2", "3", "4"};
    private static final int LIT_TICKS = 9;
    private static final int TEXT = 0xffebf5fb, DIM = 0xff9bb6ca, TEAL = 0xff55d3bc, AMBER = 0xffe1af6c, RED = 0xffe1736c;

    private final BlockPos pos;
    private final byte[] sequence;
    private final byte[] entered = new byte[SecureTerminalService.LENGTH];
    private int count, age, flashPad = -1, flashAge;
    private boolean sent, frozen, qa;
    private int panelW, panelH, left, top, padSize, padGap, padTop;

    ChallengeScreen(BlockPos pos, byte[] sequence) {
        super(Component.literal("Underground City | Secure terminal"));
        this.pos = pos;
        this.sequence = sequence.clone();
    }

    /** Capture harness: a fixed pose that never advances or talks to a server. */
    static ChallengeScreen forCapture(byte[] sequence) {
        ChallengeScreen screen = new ChallengeScreen(BlockPos.ZERO, sequence);
        screen.qa = screen.frozen = true;
        return screen;
    }

    /** Freezes the screen mid-sequence: {@code lit} is the pad currently flashing (-1 none), {@code step} how far in. */
    void poseShowing(int step, boolean lit) {
        age = step * SecureTerminalService.SHOW_TICKS_PER_SYMBOL + (lit ? 2 : LIT_TICKS + 2);
    }

    /** Freezes the screen in the input phase with {@code taken} answers already entered and a fraction of time spent. */
    void poseInput(int taken, double spent) {
        count = Math.max(0, Math.min(SecureTerminalService.LENGTH - 1, taken));
        System.arraycopy(sequence, 0, entered, 0, count);
        age = SecureTerminalService.LENGTH * SecureTerminalService.SHOW_TICKS_PER_SYMBOL + (int) (spent * SecureTerminalService.INPUT_TICKS);
        flashPad = -1;
    }

    private int showTicks() { return SecureTerminalService.LENGTH * SecureTerminalService.SHOW_TICKS_PER_SYMBOL; }
    private boolean showing() { return age < showTicks(); }
    private int litPad() {
        if (!showing()) return -1;
        int step = age / SecureTerminalService.SHOW_TICKS_PER_SYMBOL;
        return age % SecureTerminalService.SHOW_TICKS_PER_SYMBOL < LIT_TICKS ? sequence[step] : -1;
    }

    @Override protected void init() {
        panelW = Math.min(340, width - 16);
        panelH = Math.min(236, height - 16);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        padGap = 8;
        padSize = Math.min(64, (panelW - 36 - 3 * padGap) / 4);
        padTop = top + 76;
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void tick() {
        if (frozen) return;
        age++;
        if (flashPad >= 0 && ++flashAge > 4) flashPad = -1;
        // The server's deadline is final; once this timer is spent the answer would be refused, so stop waiting.
        if (!sent && age > showTicks() + SecureTerminalService.INPUT_TICKS) finish(true);
    }

    private int padX(int index) {
        int row = padSize * 4 + padGap * 3;
        return left + (panelW - row) / 2 + index * (padSize + padGap);
    }

    private void press(int pad) {
        if (sent || showing() || pad < 0 || pad > 3 || count >= entered.length) return;
        entered[count++] = (byte) pad;
        flashPad = pad;
        flashAge = 0;
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

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, 0xff38546b);
        g.fill(left, top, left + panelW, top + panelH, 0xff101c2a);
        g.fill(left, top, left + panelW, top + 33, 0xff182d42);
        g.fill(left + 12, top + 10, left + 16, top + 24, AMBER);
        g.drawString(font, "UNDERGROUND // SECURED TERMINAL", left + 24, top + 10, TEXT, false);
        g.drawString(font, "SIGNAL TRACE", left + 24, top + 21, DIM, false);

        boolean show = showing();
        int lit = litPad();
        String status = show ? "WATCH THE SIGNAL" : count >= entered.length ? "SENDING" : "REPEAT THE SEQUENCE";
        g.drawCenteredString(font, status, left + panelW / 2, top + 44, show ? AMBER : TEAL);
        g.drawCenteredString(font, show ? "Five signals, four pads. Do not click yet." : "Press the pads in the same order.", left + panelW / 2, top + 58, DIM);

        for (int i = 0; i < 4; i++) {
            int x = padX(i), colour = PAD_COLOUR[i];
            boolean on = i == lit || (i == flashPad && !show);
            boolean hover = !show && !sent && mouseX >= x && mouseX < x + padSize && mouseY >= padTop && mouseY < padTop + padSize;
            g.fill(x - 2, padTop - 2, x + padSize + 2, padTop + padSize + 2, on ? TEXT : hover ? colour : 0xff2a4259);
            g.fill(x, padTop, x + padSize, padTop + padSize, on ? colour : dim(colour, hover ? 0.45 : 0.22));
            g.pose().pushPose();
            g.pose().translate(x + padSize / 2f, padTop + padSize / 2f - 8, 0);
            g.pose().scale(2f, 2f, 1f);
            g.drawCenteredString(font, PAD_NAME[i], 0, 0, on ? 0xff101c2a : colour);
            g.pose().popPose();
        }

        int dotsY = padTop + padSize + 14, step = 14, dotsW = entered.length * step - 4;
        int shown = show ? Math.min(entered.length, age / SecureTerminalService.SHOW_TICKS_PER_SYMBOL + (lit >= 0 ? 1 : 0)) : count;
        for (int i = 0; i < entered.length; i++) {
            int x = left + (panelW - dotsW) / 2 + i * step;
            g.fill(x, dotsY, x + 10, dotsY + 10, i < shown ? (show ? AMBER : TEAL) : 0xff2a4259);
        }
        int barW = panelW - 60, barX = left + 30, barY = dotsY + 20;
        double remaining = show ? 1.0 : Math.max(0, 1.0 - (age - showTicks()) / (double) SecureTerminalService.INPUT_TICKS);
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 7, 0xff2a4259);
        g.fill(barX, barY, barX + (int) (barW * remaining), barY + 6, remaining > 0.5 ? TEAL : remaining > 0.25 ? AMBER : RED);
        g.drawString(font, show ? "LOCK RESETS AFTER THE SIGNAL" : String.format("LOCK RESETS IN %.0fs", remaining * SecureTerminalService.INPUT_TICKS / 20.0), barX, barY + 11, DIM, false);

        g.drawString(font, "KEYS 1-4 OR CLICK", left + 12, top + panelH - 15, DIM, false);
        String abandon = "ESC TRIPS THE ALARM";
        g.drawString(font, abandon, left + panelW - 12 - font.width(abandon), top + panelH - 15, RED, false);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private static int dim(int colour, double factor) {
        int r = (int) (((colour >> 16) & 0xff) * factor), gr = (int) (((colour >> 8) & 0xff) * factor), b = (int) ((colour & 0xff) * factor);
        return 0xff000000 | r << 16 | gr << 8 | b;
    }
}
