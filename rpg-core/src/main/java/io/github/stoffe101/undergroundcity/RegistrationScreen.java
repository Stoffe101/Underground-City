package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * First-login registration: choose a home city, then a profession, then confirm. The screen only proposes; the server validates both
 * identifiers, creates the citizen once and grants the welcome pack (see {@link RegistrationService}). Closing it with ESC postpones the
 * choice: {@code /uc start} or the REGISTER button on any Operations Terminal reopens it. Keys work as well as the mouse (1-3 and 1-4 select,
 * Enter confirms, Backspace goes back).
 */
@OnlyIn(Dist.CLIENT)
public final class RegistrationScreen extends UcScreen {
    private record City(String id, String title, int color, String blurb, String industry, String law, String travel) {}
    private record Career(String id, String title, UcUi.Icon icon, int color, String legal, String underground) {}

    private static final List<City> CITIES = List.of(
        new City("ironhaven", "IRONHAVEN", UcUi.IRONHAVEN, "Heavy industry under a haze of smoke: foundries, rail yards and the guard's armoury.",
            "Smiths, engineers, arms makers", "Strict gates, goods searched", "Valewood, Blackwater: about 4 min"),
        new City("valewood", "VALEWOOD", UcUi.VALEWOOD, "A green low-rise town of farms, vineyards and a busy market square.",
            "Farmers, vintners, traders", "Modest patrols, arms licensed", "Ironhaven, Blackwater: about 4 min"),
        new City("blackwater", "BLACKWATER", UcUi.BLACKWATER, "Glass towers, a working harbour and nightlife on the water.",
            "Technicians, shippers, brokers", "Uneven enforcement", "Ironhaven, Valewood: about 4 min"));
    private static final List<Career> CAREERS = List.of(
        new Career("farmer", "FARMER", UcUi.Icon.FARMER, 0xff86c45a, "Crops, greenhouses and estate supply", "Restricted cultivation, quiet bales"),
        new Career("vintner", "VINTNER", UcUi.Icon.VINTNER, 0xffc279d6, "Wine, cellars and a licensed still", "An unlicensed still: moonshine"),
        new Career("smith", "SMITH / ENGINEER", UcUi.Icon.SMITH, 0xffe07b39, "Steel and plating for the guard", "Unstamped plate, contraband armour"),
        new Career("gunsmith", "GUNSMITH", UcUi.Icon.GUNSMITH, 0xffd9b455, "Licensed repairs and a dealer licence", "Unregistered builds, arms brokerage"),
        new Career("technician", "TECHNICIAN", UcUi.Icon.TECHNICIAN, 0xff46c9e0, "Municipal repairs and security systems", "Bypass tooling, data recovery"));

    private int step, cityIndex = -1, careerIndex = -1;
    private boolean sending, qa;
    private String message = "";
    private final Map<String, List<String>> kits;
    private UcButton back, confirm;

    RegistrationScreen(Map<String, List<String>> kits) {
        super(Component.literal("Underground City | New citizen"));
        this.kits = kits;
    }

    /** Capture harness: a fixed pose that never talks to a server. */
    static RegistrationScreen forCapture(int step, int city, int career, String message) {
        RegistrationScreen screen = new RegistrationScreen(Map.of(
            "farmer", List.of("minecraft:wooden_hoe", "minecraft:wheat_seeds", "minecraft:bread"),
            "vintner", List.of("minecraft:wooden_hoe", "minecraft:sweet_berries", "minecraft:glass_bottle", "minecraft:barrel", "minecraft:bread"),
            "smith", List.of("minecraft:stone_pickaxe", "minecraft:coal", "minecraft:bread"),
            "gunsmith", List.of("minecraft:bow", "minecraft:arrow", "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:bread"),
            "technician", List.of("minecraft:redstone", "minecraft:copper_ingot", "minecraft:bread")));
        screen.qa = true;
        screen.step = step;
        screen.cityIndex = city;
        screen.careerIndex = career;
        screen.message = message;
        return screen;
    }

    // ---------------------------------------------------------------- layout
    @Override protected void init() {
        layoutPanel(430, 290, 560, 372);
        clearWidgets();
        int by = py + ph - 8 - 20;
        back = add(new UcButton(px + PAD, by, 74, 20, "BACK", b -> goBack()).icon(UcUi.Icon.ARROW_LEFT));
        confirm = add(new UcButton(px + pw - PAD - 150, by, 150, 20, "CONFIRM", b -> confirm()).kind(UcButton.Kind.PRIMARY).icon(UcUi.Icon.CHECK));
        refresh();
    }

    private void refresh() {
        if (back == null) return;
        back.visible = step > 0;
        back.active = !sending;
        confirm.visible = step == 2;
        confirm.active = !sending && cityIndex >= 0 && careerIndex >= 0;
        confirm.label(sending ? "REGISTERING" : "CONFIRM");
    }

    @Override protected int accent() { return step > 0 && cityIndex >= 0 ? CITIES.get(cityIndex).color() : UcUi.AMBER; }
    @Override protected String titleText() { return "New citizen"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.CITY; }

    private int footerY() { return py + ph - 8 - 20; }
    private int introHeight() { return bodyH() >= 232 ? 30 : 0; }
    private int bannerHeight() { return bodyH() >= 236 ? 64 : 44; }

    /** Step 0: three columns of cities; step 1: a 3 x 2 grid (five trades and a legend). Returns {x, y, w, h}. */
    private int[] cardBounds(int index) {
        int gap = 8, top = bodyY() + (step == 0 ? introHeight() : 18), bottom = footerY() - (step == 0 ? 8 : 24);
        if (step == 0) {
            int w = (bodyW() - 2 * gap) / 3;
            return new int[]{bodyX() + index * (w + gap), top, w, bottom - top};
        }
        int w = (bodyW() - 2 * gap) / 3, h = (bottom - top - gap) / 2;
        return new int[]{bodyX() + (index % 3) * (w + gap), top + (index / 3) * (h + gap), w, h};
    }

    int[] cardCentre(int index) { int[] b = cardBounds(index); return new int[]{b[0] + b[2] / 2, b[1] + b[3] / 2}; }
    int[] confirmCentre() { return new int[]{confirm.getX() + confirm.getWidth() / 2, confirm.getY() + confirm.getHeight() / 2}; }
    int step() { return step; }

    // ---------------------------------------------------------------- behaviour
    private int cardCount() { return step == 0 ? CITIES.size() : CAREERS.size(); }

    private void choose(int index) {
        if (sending || step > 1 || index < 0 || index >= cardCount()) return;
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
        if (ok) {
            Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.2f, 0.6f));
            onClose();
        } else { step = 0; cityIndex = careerIndex = -1; refresh(); }
    }

    private int hovered(double mouseX, double mouseY) {
        if (step > 1) return -1;
        for (int i = 0; i < cardCount(); i++) {
            int[] b = cardBounds(i);
            if (mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) return i;
        }
        return -1;
    }

    @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && step < 2 && !sending) {
            int index = hovered(mouseX, mouseY);
            if (index >= 0) { choose(index); return true; }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= 49 && key <= 57) { choose(key - 49); return true; }
        if (key >= 321 && key <= 329) { choose(key - 321); return true; }
        if ((key == 257 || key == 335) && step == 2) { confirm(); return true; }
        if (key == 259) { goBack(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    // ---------------------------------------------------------------- drawing
    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String[] names = {"CITY", "TRADE", "CONFIRM"};
        int x = right;
        for (int i = 2; i >= 0; i--) {
            boolean done = i < step, now = i == step;
            int color = now ? accent() : done ? UcUi.OK : UcUi.FAINT;
            int w = UcUi.displayWidth(font, (i + 1) + " " + names[i]) + 10 + (done ? 12 : 0);
            x -= w;
            UcUi.chip(g, font, (i + 1) + " " + names[i], x, py + 9, color, done ? UcUi.Icon.CHECK : null);
            x -= 4;
        }
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        if (step == 0) renderCities(g, mouseX, mouseY);
        else if (step == 1) renderCareers(g, mouseX, mouseY);
        else renderConfirm(g);
        if (sending) UcUi.display(g, font, "Registering...", px + pw / 2 - 40, footerY() + 6, UcUi.AMBER);
        if (!message.isEmpty()) UcUi.wrapped(g, font, message, px + 100, footerY() - 2, pw - 280, UcUi.UNDERGROUND_TEXT, 2);
        String hint = step == 2 ? "ENTER CONFIRMS  |  BACKSPACE GOES BACK  |  ESC LATER" : "KEYS 1-" + cardCount() + " OR CLICK  |  ESC LATER";
        UcUi.text(g, font, hint, step == 2 ? bodyX() + 82 : bodyX() + (back.visible ? 82 : 0), footerY() + 6, UcUi.FAINT);
    }

    private static final ResourceLocation[] SKYLINES = {
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "textures/gui/skyline_ironhaven.png"),
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "textures/gui/skyline_valewood.png"),
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "textures/gui/skyline_blackwater.png")};

    private void renderCities(GuiGraphics g, int mouseX, int mouseY) {
        if (introHeight() > 0) {
            UcUi.wrapped(g, font, "Three rival cities, four trades, two ways to earn: legal work and the underground. Choose where your story starts. Nothing here locks you in.",
                bodyX(), bodyY() + 2, bodyW(), UcUi.SOFT, 3);
        }
        int hover = hovered(mouseX, mouseY);
        for (int i = 0; i < CITIES.size(); i++) {
            City city = CITIES.get(i);
            int[] b = cardBounds(i);
            boolean over = i == hover && !sending, selected = i == cityIndex;
            UcUi.card(g, b[0], b[1], b[2], b[3], over ? 1 : selected ? 2 : 0);
            // banner, centred and clipped to the card
            int bw = Math.min(b[2] - 4, 176), bh = bannerHeight();
            g.blit(SKYLINES[i], b[0] + 2, b[1] + 2, bw, bh, (176 - bw) / 2, (64 - bh) / 2, bw, bh, 176, 64);
            g.fill(b[0] + 2, b[1] + 2 + bh, b[0] + b[2] - 2, b[1] + 4 + bh, city.color());
            int y = b[1] + bh + 8;
            UcUi.display(g, font, (i + 1) + "  " + city.title(), b[0] + 6, y, over ? UcUi.TEXT : city.color());
            y += 13;
            y = UcUi.wrapped(g, font, city.blurb(), b[0] + 6, y, b[2] - 12, UcUi.SOFT, 3) + 4;
            UcUi.Icon[] icons = {UcUi.Icon.GEAR, UcUi.Icon.SCALES, UcUi.Icon.TRUCK};
            String[] rows = {city.industry(), city.law(), city.travel()};
            int[] tints = {UcUi.DIM, UcUi.AMBER, UcUi.DIM};
            for (int r = 0; r < 3; r++) {
                if (y + 12 > b[1] + b[3]) break;
                UcUi.icon(g, icons[r], b[0] + 5, y - 3, tints[r]);
                int next = UcUi.wrapped(g, font, rows[r], b[0] + 23, y, b[2] - 29, r == 1 ? 0xffd9bd85 : UcUi.SOFT, 2);
                y = Math.max(next, y + 12) + 4;
            }
        }
    }

    private void renderStarterKit(GuiGraphics g, String career, int x, int y, int maxItems, boolean label) {
        if (label) UcUi.display(g, font, "Starter kit", x, y + 4, UcUi.FAINT);
        int ix = x + (label ? UcUi.displayWidth(font, "Starter kit") + 8 : 0);
        List<String> ids = kits.getOrDefault(career, List.of());
        for (int i = 0; i < Math.min(maxItems, ids.size()); i++) {
            g.renderItem(new ItemStack(TerminalScreen.item(ids.get(i))), ix + i * 18, y);
        }
    }

    private void renderCareers(GuiGraphics g, int mouseX, int mouseY) {
        City city = CITIES.get(Math.max(0, cityIndex));
        UcUi.display(g, font, "How will you earn your living in " + city.title() + "?", bodyX(), bodyY() + 4, UcUi.SOFT);
        int hover = hovered(mouseX, mouseY);
        for (int i = 0; i < CAREERS.size(); i++) {
            Career career = CAREERS.get(i);
            int[] b = cardBounds(i);
            boolean over = i == hover && !sending, selected = i == careerIndex;
            UcUi.card(g, b[0], b[1], b[2], b[3], over ? 1 : selected ? 2 : 0);
            // the trade's badge: its colour, its icon
            g.fill(b[0] + 6, b[1] + 6, b[0] + 34, b[1] + 34, 0xff05080c);
            g.fill(b[0] + 7, b[1] + 7, b[0] + 33, b[1] + 33, UcUi.mix(0xff0d1620, career.color(), 0.38f));
            g.fill(b[0] + 7, b[1] + 7, b[0] + 33, b[1] + 8, UcUi.shade(career.color(), 1.0f));
            UcUi.icon(g, career.icon(), b[0] + 12, b[1] + 12, 0xfff4f8fb);
            UcUi.display(g, font, (i + 1) + "  " + career.title(), b[0] + 41, b[1] + 10, over ? UcUi.TEXT : career.color());
            int ty = b[1] + 24;
            UcUi.icon(g, UcUi.Icon.LEGAL, b[0] + 38, ty - 4, UcUi.LEGAL);
            ty = Math.max(UcUi.wrapped(g, font, career.legal(), b[0] + 56, ty, b[2] - 62, UcUi.SOFT, 2), ty + 12) + 3;
            UcUi.icon(g, UcUi.Icon.UNDERGROUND, b[0] + 38, ty - 4, UcUi.UNDERGROUND);
            UcUi.wrapped(g, font, career.underground(), b[0] + 56, ty, b[2] - 62, UcUi.UNDERGROUND_TEXT, 2);
            int rule = b[1] + b[3] - 28;
            g.fill(b[0] + 6, rule, b[0] + b[2] - 6, rule + 1, 0xff243a4e);
            renderStarterKit(g, career.id(), b[0] + 8, b[1] + b[3] - 22, 5, true);
        }
        // the sixth cell is not a choice: it explains the two kinds of work every card names
        int[] n = cardBounds(CAREERS.size());
        UcUi.well(g, n[0], n[1], n[2], n[3]);
        int y = n[1] + 9;
        UcUi.display(g, font, "Two sides to every trade", n[0] + 8, y, UcUi.AMBER);
        y += 18;
        UcUi.icon(g, UcUi.Icon.LEGAL, n[0] + 6, y - 4, UcUi.LEGAL);
        y = Math.max(UcUi.wrapped(g, font, "Honest work: licensed, taxed and safe.", n[0] + 24, y, n[2] - 30, UcUi.SOFT, 2), y + 12) + 4;
        UcUi.icon(g, UcUi.Icon.UNDERGROUND, n[0] + 6, y - 4, UcUi.UNDERGROUND);
        y = Math.max(UcUi.wrapped(g, font, "Off the books: pays more, can be seized.", n[0] + 24, y, n[2] - 30, UcUi.UNDERGROUND_TEXT, 2), y + 12) + 4;
        UcUi.icon(g, UcUi.Icon.INFO, n[0] + 6, y - 4, UcUi.AMBER);
        UcUi.wrapped(g, font, "Both stay open to every trade, always.", n[0] + 24, y, n[2] - 30, 0xffd9bd85, 2);
    }

    private void renderConfirm(GuiGraphics g) {
        City city = CITIES.get(Math.max(0, cityIndex));
        Career career = CAREERS.get(Math.max(0, careerIndex));
        int top = bodyY() + 4, bottom = Math.min(footerY() - 24, bodyY() + 4 + 190);
        int sheetW = Math.min(bodyW() * 55 / 100, 270);
        UcUi.paper(g, bodyX(), top, sheetW, bottom - top);
        int x = bodyX() + 10, y = top + 9;
        UcUi.display(g, font, "Citizen registration", x, y, UcUi.INK);
        g.fill(x, y + 11, bodyX() + sheetW - 10, y + 12, 0xff8a7c5e);
        y += 20;
        String name = Minecraft.getInstance().player == null ? "Citizen" : Minecraft.getInstance().player.getName().getString();
        UcUi.display(g, font, "Name", x, y, UcUi.PAPER_DIM);
        UcUi.text(g, font, UcUi.fit(font, name, sheetW - 80), x + 52, y, UcUi.INK);
        y += 17;
        UcUi.display(g, font, "Home", x, y, UcUi.PAPER_DIM);
        UcUi.display(g, font, city.title(), x + 52, y, UcUi.shade(city.color(), 0.55f));
        y += 17;
        UcUi.display(g, font, "Trade", x, y, UcUi.PAPER_DIM);
        UcUi.icon(g, career.icon(), x + 50, y - 4, UcUi.shade(career.color(), 0.55f));
        UcUi.display(g, font, career.title(), x + 68, y, UcUi.shade(career.color(), 0.55f));
        y += 17;
        UcUi.display(g, font, "Pack", x, y, UcUi.PAPER_DIM);
        g.renderItem(new ItemStack(UndergroundCity.TERMINAL_ITEM.get()), x + 52, y - 5);
        renderStarterKit(g, career.id(), x + 70, y - 5, 4, false);
        y += 22;
        UcUi.text(g, font, "Filed with the registry of " + capital(city.id()) + ".", x, y, UcUi.PAPER_DIM);
        // a stamp box, because a registry would have one
        int sx = bodyX() + sheetW - 84, sy = bottom - 26;
        g.fill(sx, sy, sx + 74, sy + 16, 0x00000000);
        for (int k = 0; k < 74; k++) { g.fill(sx + k, sy, sx + k + 1, sy + 1, 0xff9a4a3e); g.fill(sx + k, sy + 15, sx + k + 1, sy + 16, 0xff9a4a3e); }
        g.fill(sx, sy, sx + 1, sy + 16, 0xff9a4a3e); g.fill(sx + 73, sy, sx + 74, sy + 16, 0xff9a4a3e);
        UcUi.displayCentre(g, font, sending ? "Filing" : "Not filed", sx + 37, sy + 4, 0xff9a4a3e);

        // what happens next
        int rx = bodyX() + sheetW + 14, rw = bodyW() - sheetW - 14, ry = top + 2;
        UcUi.display(g, font, "What happens next", rx, ry, UcUi.AMBER);
        ry += 16;
        UcUi.Icon[] icons = {UcUi.Icon.CRATE, UcUi.Icon.PIN, UcUi.Icon.SCALES};
        String[] steps = {"You receive an Operations Terminal and your starter tools.",
            "Find your employer in Founders Row and take a first order.",
            "Legal or underground: both stay open to every trade, always."};
        for (int i = 0; i < 3; i++) {
            g.fill(rx, ry, rx + 18, ry + 18, 0xff0d1620);
            UcUi.icon(g, icons[i], rx + 1, ry + 1, i == 2 ? UcUi.AMBER : UcUi.LEGAL);
            int next = UcUi.wrapped(g, font, steps[i], rx + 24, ry + 1, rw - 24, UcUi.SOFT, 3);
            ry = Math.max(next, ry + 18) + 8;
        }
    }

    private static String capital(String id) { return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1).toLowerCase(Locale.ROOT); }
}
