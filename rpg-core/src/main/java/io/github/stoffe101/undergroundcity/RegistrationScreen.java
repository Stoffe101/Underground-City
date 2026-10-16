package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;

/**
 * First-login registration: choose a home city, then a profession, then confirm. The screen only proposes; the server
 * validates both identifiers, creates the citizen once and grants the welcome pack (see {@link RegistrationService}).
 * Closing it with ESC postpones the choice: {@code /uc start} or the REGISTER button on any Operations Terminal reopens it.
 * Keys work as well as the mouse (1-3 and 1-4 select, Enter confirms, Backspace goes back).
 */
@OnlyIn(Dist.CLIENT)
public final class RegistrationScreen extends Screen {
    private static final int TEXT = 0xffebf5fb, DIM = 0xff9bb6ca, TEAL = 0xff55d3bc, AMBER = 0xffe1af6c, RED = 0xffe1736c, SOFT = 0xffbcd0de;

    private record Card(String id, String title, String line1, String line2) {}

    private static final List<Card> CITIES = List.of(
        new Card("ironhaven", "IRONHAVEN", "Dense industrial city: smiths, engineers and the guard's armoury.", "Strict gates: controlled goods are searched."),
        new Card("valewood", "VALEWOOD", "Green low-rise town of farms, vineyards and trade.", "Modest patrols. Hunting arms are licensed."),
        new Card("blackwater", "BLACKWATER", "Commerce, technology and nightlife on the water.", "Uneven enforcement. Discretion is paid for."));
    private static final List<Card> CAREERS = List.of(
        new Card("farmer", "FARMER / VINTNER", "Legal: crops, seed stock, licensed cultivars.", "Underground: restricted cultivation, quiet bales."),
        new Card("smith", "SMITH / ENGINEER", "Legal: structural steel, plating for the guard.", "Underground: unstamped plate, contraband armour."),
        new Card("gunsmith", "GUNSMITH / ARMS DEALER", "Legal: licensed repairs, a dealer licence, security work.", "Underground: unregistered builds, arms brokerage."),
        new Card("technician", "HACKER / TECHNICIAN", "Legal: municipal repairs, automation, security systems.", "Underground: bypass tooling, fictional data recovery."));

    private int step, cityIndex = -1, careerIndex = -1;
    private boolean sending, qa;
    private String message = "";
    private int panelW, panelH, left, top;
    private Button back, confirm;

    RegistrationScreen() { super(Component.literal("Underground City | New citizen")); }

    /** Capture harness: a fixed pose that never talks to a server. */
    static RegistrationScreen forCapture(int step, int city, int career, String message) {
        RegistrationScreen screen = new RegistrationScreen();
        screen.qa = true;
        screen.step = step;
        screen.cityIndex = city;
        screen.careerIndex = career;
        screen.message = message;
        return screen;
    }

    @Override protected void init() {
        panelW = Math.min(420, width - 16);
        panelH = Math.min(268, height - 16);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        back = addRenderableWidget(Button.builder(Component.literal("BACK"), b -> goBack()).bounds(left + 18, top + panelH - 44, 70, 20).build());
        confirm = addRenderableWidget(Button.builder(Component.literal("CONFIRM"), b -> confirm()).bounds(left + panelW - 18 - 110, top + panelH - 44, 110, 20).build());
        refresh();
    }

    private void refresh() {
        if (back == null) return;
        back.visible = step > 0;
        back.active = !sending;
        confirm.visible = step == 2;
        confirm.active = !sending && cityIndex >= 0 && careerIndex >= 0;
    }

    @Override public boolean isPauseScreen() { return false; }

    private List<Card> cards() { return step == 0 ? CITIES : CAREERS; }

    private int cardTop() { return top + 62; }
    private int cardGap() { return step == 0 ? 8 : 5; }
    /** Step 0: three columns; step 1: four rows. */
    private int[] cardBounds(int index) {
        int inner = panelW - 36;
        if (step == 0) {
            int w = (inner - 2 * cardGap()) / 3, h = panelH - 62 - 54;
            return new int[]{left + 18 + index * (w + cardGap()), cardTop(), w, h};
        }
        int h = (panelH - 62 - 54 - 3 * cardGap()) / 4;
        return new int[]{left + 18, cardTop() + index * (h + cardGap()), inner, h};
    }

    /** Test hooks: GUI-space centre of a card and of the confirm button, so a scripted mouse can click like a player. */
    int[] cardCentre(int index) { int[] b = cardBounds(index); return new int[]{b[0] + b[2] / 2, b[1] + b[3] / 2}; }
    int[] confirmCentre() { return new int[]{confirm.getX() + confirm.getWidth() / 2, confirm.getY() + confirm.getHeight() / 2}; }
    int step() { return step; }

    private void choose(int index) {
        if (sending || step > 1 || index < 0 || index >= cards().size()) return;
        if (step == 0) { cityIndex = index; step = 1; }
        else { careerIndex = index; step = 2; }
        message = "";
        refresh();
    }

    private void goBack() {
        if (sending || step == 0) return;
        step--;
        message = "";
        refresh();
    }

    private void confirm() {
        if (sending || step != 2 || cityIndex < 0 || careerIndex < 0) return;
        sending = true;
        message = "";
        refresh();
        if (!qa) TerminalNetwork.sendRegistration(CITIES.get(cityIndex).id(), CAREERS.get(careerIndex).id());
    }

    /** The server's verdict. Success closes the screen; a refusal keeps it open with the reason. */
    void result(boolean ok, String text) {
        sending = false;
        message = text;
        if (ok) onClose();
        else { step = 0; cityIndex = careerIndex = -1; refresh(); }
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && step < 2) {
            for (int i = 0; i < cards().size(); i++) {
                int[] b = cardBounds(i);
                if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) { choose(i); return true; }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= 49 && key <= 57) { choose(key - 49); return true; }
        if (key >= 321 && key <= 329) { choose(key - 321); return true; }
        if (key == 257 || key == 335) { if (step == 2) { confirm(); return true; } }
        if (key == 259) { goBack(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    private void wrapped(GuiGraphics g, String text, int x, int y, int width, int colour, int maxLines) {
        int line = 0;
        for (FormattedCharSequence sequence : font.split(Component.literal(text), width)) {
            if (line++ >= maxLines) break;
            g.drawString(font, sequence, x, y + (line - 1) * 10, colour, false);
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left - 2, top - 2, left + panelW + 2, top + panelH + 2, 0xff38546b);
        g.fill(left, top, left + panelW, top + panelH, 0xff101c2a);
        g.fill(left, top, left + panelW, top + 33, 0xff182d42);
        g.fill(left + 12, top + 10, left + 16, top + 24, TEAL);
        g.drawString(font, "UNDERGROUND // NEW CITIZEN", left + 24, top + 10, TEXT, false);
        String[] subtitle = {"STEP 1 OF 3 | CHOOSE YOUR HOME CITY", "STEP 2 OF 3 | CHOOSE YOUR PROFESSION", "STEP 3 OF 3 | CONFIRM"};
        g.drawString(font, subtitle[Math.min(step, 2)], left + 24, top + 21, DIM, false);

        if (step < 2) {
            g.drawString(font, step == 0 ? "Where will you live and work?" : "How will you earn your living in " + CITIES.get(cityIndex).title().charAt(0) + CITIES.get(cityIndex).title().substring(1).toLowerCase(Locale.ROOT) + "?", left + 18, top + 42, SOFT, false);
            for (int i = 0; i < cards().size(); i++) {
                int[] b = cardBounds(i);
                Card card = cards().get(i);
                boolean hover = !sending && mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
                boolean selected = (step == 0 ? cityIndex : careerIndex) == i;
                g.fill(b[0] - 1, b[1] - 1, b[0] + b[2] + 1, b[1] + b[3] + 1, hover ? TEAL : selected ? AMBER : 0xff2a4259);
                g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hover ? 0xff1d3650 : 0xff15263a);
                g.drawString(font, (i + 1) + "  " + card.title(), b[0] + 6, b[1] + (step == 0 ? 6 : 4), hover ? TEXT : TEAL, false);
                if (step == 0) {
                    wrapped(g, card.line1(), b[0] + 6, b[1] + 22, b[2] - 12, SOFT, 5);
                    wrapped(g, card.line2(), b[0] + 6, b[1] + b[3] - 34, b[2] - 12, AMBER, 3);
                } else {
                    g.drawString(font, font.plainSubstrByWidth(card.line1(), b[2] - 12), b[0] + 6, b[1] + 14, SOFT, false);
                    g.drawString(font, font.plainSubstrByWidth(card.line2(), b[2] - 12), b[0] + 6, b[1] + 24, RED, false);
                }
            }
        } else {
            Card city = CITIES.get(Math.max(0, cityIndex)), career = CAREERS.get(Math.max(0, careerIndex));
            g.drawString(font, "You are about to register as", left + 18, top + 48, SOFT, false);
            g.drawString(font, career.title(), left + 18, top + 64, TEAL, false);
            g.drawString(font, "of " + city.title(), left + 18, top + 76, TEAL, false);
            wrapped(g, "Your welcome pack: an Operations Terminal and starter tools for your trade.", left + 18, top + 98, panelW - 36, SOFT, 2);
            wrapped(g, "Legal and underground work both stay open to you at all times. Nothing about this choice locks you out of either.", left + 18, top + 124, panelW - 36, AMBER, 3);
            wrapped(g, "Your home city and profession are recorded on the server when you confirm.", left + 18, top + 160, panelW - 36, DIM, 2);
        }
        if (sending) g.drawString(font, "REGISTERING...", left + 100, top + panelH - 38, AMBER, false);
        if (!message.isEmpty()) wrapped(g, message, left + 18, top + panelH - 70, panelW - 36, RED, 2);
        String hint = step == 2 ? "ENTER CONFIRMS | BACKSPACE GOES BACK | ESC LATER" : "KEYS 1-" + cards().size() + " OR CLICK | ESC LATER";
        g.drawString(font, hint, left + 18, top + panelH - 14, DIM, false);
        super.render(g, mouseX, mouseY, partialTick);
    }
}
