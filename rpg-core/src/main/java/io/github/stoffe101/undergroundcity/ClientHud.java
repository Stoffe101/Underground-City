package io.github.stoffe101.undergroundcity;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The persistent on-screen status (D-109): where you are, the time, your wallet and Crew bank, your home and when its rent is next due, your trade and how closely the city is watching you,
 * and below it, in the same stack, the accepted order and the next step of the guided first hours. Everything is the server's number ({@link HudState}); the client only draws it, with the
 * game's own clock. Where it stands, its size and which lines it shows are the player's ({@link HudConfig}, {@link HudSettingsScreen}); the placement arithmetic is {@link HudLayout}, which
 * keeps the stack off the corners that the game and common mods use and drops the least important card on a small window instead of overlapping anything.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientHud {
    private static final KeyMapping TOGGLE = new KeyMapping("key.underground_city.hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.underground_city");
    private static final KeyMapping SETTINGS = new KeyMapping("key.underground_city.hud_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.underground_city");
    private static volatile HudState state = HudState.EMPTY;
    private static boolean visible = true;

    private ClientHud() {}

    /** Adds the status settings to the mod list's Config button. */
    static void registerSettingsScreen() {
        net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new HudSettingsScreen(parent)));
    }

    static void update(HudState next) { state = next; }
    static HudState state() { return state; }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) { event.register(TOGGLE); event.register(SETTINGS); }

    @SubscribeEvent
    public static void overlay(RegisterGuiOverlaysEvent event) {
        // above the chat: on a small window (1280x720 at GUI scale 3) the default 320-pixel chat reaches under a right-hand stack, and its lines were drawn over the cards; the chat screen
        // (T) shows every line in full, and the stack is not drawn while it is open
        event.registerAbove(VanillaGuiOverlay.CHAT_PANEL.id(), "status_hud", (gui, graphics, partialTick, width, height) -> draw(graphics, width, height));
    }

    @Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID, value = Dist.CLIENT)
    public static final class Keys {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (TOGGLE.consumeClick()) visible = !visible;
            while (SETTINGS.consumeClick()) { Minecraft mc = Minecraft.getInstance(); if (mc.screen == null) mc.setScreen(new HudSettingsScreen(null)); }
        }
    }

    /** Where the stack was drawn in the last frame, {x, y, w, h} in GUI pixels, or null when it was not (the corner notices move out of its way: {@link ToastPlacement}). */
    private static volatile int[] drawn;

    static int[] drawn() { return drawn; }

    private static void draw(GuiGraphics g, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        drawn = null;
        if (!visible || mc.screen != null || mc.options.hideGui || mc.player == null) return;
        HudLayout.Plan plan = render(g, mc, width, height, state, ClientOrderTracker.current(), ClientOrderTracker.objective(), mc.level == null ? 0 : mc.level.getDayTime(), HudConfig.Settings.fromConfig());
        if (plan == null || !plan.any()) return;
        int top = Integer.MAX_VALUE, left = 0;
        for (int i = 0; i < plan.shown().length; i++) if (plan.shown()[i]) { top = Math.min(top, plan.y()[i]); left = plan.x()[i]; }
        drawn = new int[] {left, top, plan.width(), plan.height()};
    }

    // ------------------------------------------------------------------------------------------------ drawing
    /** One line of the status card. */
    private record Row(UcUi.Icon icon, int iconColor, String left, int leftColor, String right, int rightColor, float bar, int barColor, boolean display) {
        static Row text(UcUi.Icon icon, int iconColor, String left, int leftColor, String right, int rightColor) { return new Row(icon, iconColor, left, leftColor, right, rightColor, -1f, 0, false); }
    }

    private static final int ROW_H = 12, CARD_PAD = 4;

    /** Draws the stack for a screen of the given size (the overlay, and the settings screen's preview). Does nothing when the status is switched off. */
    static HudLayout.Plan render(GuiGraphics g, Minecraft mc, int width, int height, HudState hud, OrderTracker.Tracked order, Journey.Objective objective, long dayTime, HudConfig.Settings settings) {
        if (!settings.enabled) return null;
        Font font = mc.font;
        List<Row> rows = rows(hud, dayTime, settings);
        boolean guidance = settings.guidance && ClientOrderTracker.guidanceVisible();
        int statusH = rows.isEmpty() ? 0 : CARD_PAD * 2 + 3 + rows.size() * ROW_H;
        int orderH = guidance && order != null ? ClientOrderTracker.orderHeight(order) : 0;
        int objectiveH = guidance && objective != null ? ClientOrderTracker.objectiveHeight(font, objective) : 0;
        // top to bottom: the status, the accepted order, the next step; when there is no room the order goes first, then the status, and the next step stays longest
        HudLayout.Plan plan = HudLayout.plan(settings.anchor, width, height,
            new HudLayout.Card[] {new HudLayout.Card(statusH, 2), new HudLayout.Card(orderH, 1), new HudLayout.Card(objectiveH, 3)}, (float) settings.scale);
        if (!plan.any()) return plan;
        for (int i = 0; i < 3; i++) {
            if (!plan.shown()[i]) continue;
            g.pose().pushPose();
            g.pose().translate(plan.x()[i], plan.y()[i], 0);
            g.pose().scale(plan.scale(), plan.scale(), 1f);
            switch (i) {
                case 0 -> drawStatus(g, font, hud, rows, statusH, settings.opacity);
                case 1 -> ClientOrderTracker.drawOrder(g, font, order, 0, 0);
                default -> ClientOrderTracker.drawObjective(g, mc, font, objective, 0, 0);
            }
            g.pose().popPose();
        }
        return plan;
    }

    private static int alpha(int argb, double opacity) { return ((int) (((argb >>> 24) & 255) * opacity)) << 24 | (argb & 0xffffff); }

    private static void drawStatus(GuiGraphics g, Font font, HudState hud, List<Row> rows, int h, double opacity) {
        int w = HudLayout.CARD_WIDTH;
        int accent = hud.placeKind() == 0 ? UcUi.cityColor(hud.place()) : UcUi.DIM;
        g.fill(-1, -1, w + 1, h + 1, alpha(0xff05080c, opacity));
        g.fillGradient(0, 0, w, h, alpha(0xe6182736, opacity), alpha(0xe60f1823, opacity));
        g.fill(0, 0, w, 2, accent);
        g.fill(0, 2, w, 3, UcUi.shade(accent, 0.45f));
        int y = 3 + CARD_PAD;
        for (Row row : rows) {
            int rightW = row.right().isEmpty() ? 0 : font.width(row.right()) + 6;
            g.pose().pushPose();
            g.pose().translate(3, y - 1, 0);
            g.pose().scale(0.75f, 0.75f, 1f);
            UcUi.icon(g, row.icon(), 0, 0, row.iconColor());
            g.pose().popPose();
            int textX = 19;
            if (row.bar() >= 0) {
                int labelW = row.display() ? UcUi.displayWidth(font, row.left()) : font.width(row.left());
                if (row.display()) UcUi.display(g, font, row.left(), textX, y, row.leftColor()); else g.drawString(font, row.left(), textX, y, row.leftColor(), false);
                int barX = textX + labelW + 6;
                int barW = Math.max(8, w - barX - rightW - 6);
                UcUi.bar(g, barX, y + 1, barW, 5, row.bar(), row.barColor());
            } else {
                String text = UcUi.fit(font, row.left(), Math.max(10, w - textX - rightW - 6));
                if (row.display()) UcUi.display(g, font, text, textX, y, row.leftColor()); else g.drawString(font, text, textX, y, row.leftColor(), false);
            }
            if (!row.right().isEmpty()) g.drawString(font, row.right(), w - 5 - font.width(row.right()), y, row.rightColor(), false);
            y += ROW_H;
        }
    }

    /** The lines the player's settings and the current numbers call for. */
    private static List<Row> rows(HudState hud, long dayTime, HudConfig.Settings settings) {
        List<Row> rows = new ArrayList<>();
        boolean away = hud.registered() && hud.placeKind() == 0 && !hud.place().equals(hud.homeCity());
        if (settings.location || settings.clock) {
            String place = !settings.location ? "" : hud.placeKind() == 0 ? capitalize(hud.place()) : hud.placeKind() == 1 ? "Near " + capitalize(hud.place()) : "Open road";
            if (away && settings.location) place += " (visiting)";
            int color = hud.placeKind() == 0 ? UcUi.cityColor(hud.place()) : UcUi.SOFT;
            rows.add(Row.text(UcUi.Icon.CITY, color, place, UcUi.TEXT, settings.clock ? clock(dayTime) : "", UcUi.SOFT));
        }
        if (!hud.registered()) return rows;
        boolean wallet = settings.wallet && hud.credits() >= 0, crew = settings.crew && hud.hasCrew();
        if (wallet || crew) {
            String left = wallet ? number(hud.credits()) + " credits" : "Crew bank";
            String right = wallet && crew ? "Crew " + number(hud.crewBank()) : crew ? number(hud.crewBank()) : "";
            rows.add(Row.text(UcUi.Icon.CREDITS, UcUi.AMBER, left, UcUi.AMBER, right, UcUi.SOFT));
        }
        if (settings.home) {
            if (!hud.hasHome()) {
                rows.add(Row.text(UcUi.Icon.KEY, UcUi.FAINT, "No home yet", UcUi.DIM, "K", UcUi.FAINT));
            } else {
                boolean late = hud.homeOwed() > 0;
                rows.add(Row.text(UcUi.Icon.KEY, late ? UcUi.WARN : UcUi.LEGAL, hud.homeTitle(), UcUi.TEXT, hud.homeCharge() > 0 ? hud.homeCharge() + "/h" : "", UcUi.SOFT));
                if (hud.homeCharge() > 0) {
                    String status; int color;
                    if (late) { status = (hud.homeStanding().equals("GRACE") ? "Rent overdue: owe " : "Rent late: owe ") + hud.homeOwed(); color = hud.homeStanding().equals("GRACE") ? UcUi.UNDERGROUND_TEXT : UcUi.WARN; }
                    else if (hud.homePrepaid() > 0) { status = "Paid ahead " + hud.homePrepaid() + " h; next " + minutes(hud.homeMinutes()); color = UcUi.DIM; }
                    else { status = "Next rent: " + minutes(hud.homeMinutes()) + " of play"; color = hud.homeMinutes() <= 10 ? UcUi.AMBER : UcUi.DIM; }
                    rows.add(Row.text(UcUi.Icon.EMPTY, UcUi.DIM, status, color, "", UcUi.DIM));
                }
            }
        }
        if (settings.career && !hud.career().isEmpty()) {
            String label = capitalize(hud.career()).toUpperCase(Locale.ROOT) + " L" + hud.level();
            String right = hud.skillPoints() > 0 ? "+" + hud.skillPoints() + (hud.skillPoints() == 1 ? " point" : " points") : "";
            rows.add(new Row(UcUi.careerIcon(hud.career()), UcUi.careerColor(hud.career()), label, UcUi.TEXT, right, UcUi.AMBER, hud.xpFraction(), UcUi.LEGAL, true));
        }
        if (settings.heat && hud.heat() > 0) {
            rows.add(Row.text(UcUi.Icon.HEAT, UcUi.UNDERGROUND, "Heat " + hud.heat() + " in " + capitalize(hud.heatCity()), UcUi.UNDERGROUND_TEXT, "", UcUi.DIM));
        }
        return rows;
    }

    static String clock(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        int hour = (int) ((t / 1000 + 6) % 24), minute = (int) (t % 1000 * 60 / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute);
    }

    static String minutes(int minutes) { return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min"; }
    static String number(double value) { return value == Math.rint(value) ? String.format(Locale.ROOT, "%,d", (long) value) : String.format(Locale.ROOT, "%,.2f", value); }
    static String capitalize(String id) { return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1); }
}
