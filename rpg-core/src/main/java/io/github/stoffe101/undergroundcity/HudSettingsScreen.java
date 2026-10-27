package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Where the status stands and what it shows (D-109), with a live preview of the real stack at the real place, drawn beside the panel (the panel moves to the other side of the screen so that
 * the two never overlap). Reached from the mod list (Mods, Underground City, Config) or the HUD settings key. The screen works on its own copy of the choices and writes them to the client
 * config file ({@code underground_city-client.toml}) at once; nothing here touches the server.
 */
@OnlyIn(Dist.CLIENT)
public final class HudSettingsScreen extends UcScreen {
    /** What the preview shows: a farmer in Valewood with a rented flat, a Crew and an order to deliver. */
    static final HudState PREVIEW = new HudState("valewood", 0, "valewood", "farmer", 3, 0.45f, 520, 1, 340, "The Row", 1200, "Row Flat 2", 24, 0, 38, "CURRENT", 0, 1, "valewood");
    private static final OrderTracker.Tracked SAMPLE_ORDER = new OrderTracker.Tracked("Bake the canteen bread order", 38, 180, "Valewood", false, List.of(new OrderTracker.Line("minecraft:bread", "", 5, 8)));
    private static final Journey.Objective SAMPLE_STEP = new Journey.Objective("Learn a skill", "Spend your first skill point on the SKILLS tab.", "SKILLS", null, "");

    private final Screen parent;
    private final HudConfig.Settings settings;
    private final boolean persist;
    private UcButton enabled, anchor, smaller, bigger, fainter, solider, done;
    private final UcButton[] toggles = new UcButton[8];

    HudSettingsScreen(Screen parent) {
        super(Component.literal("Status display"));
        this.parent = parent;
        this.settings = HudConfig.Settings.fromConfig();
        this.persist = true;
    }

    private HudSettingsScreen(HudConfig.Settings settings) {
        super(Component.literal("Status display"));
        this.parent = null;
        this.settings = settings;
        this.persist = false;
    }

    /** A review capture: the given choices, never read from or written to the config file. */
    static HudSettingsScreen forCapture(HudConfig.Settings settings) { return new HudSettingsScreen(settings); }

    @Override protected int accent() { return UcUi.AMBER; }
    @Override protected String titleText() { return "Status display"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.GEAR; }
    @Override protected boolean showFooter() { return true; }

    private record Toggle(String label, Function<HudConfig.Settings, Boolean> get, BiConsumer<HudConfig.Settings, Boolean> set, String hint) {}
    private static final List<Toggle> TOGGLES = List.of(
        new Toggle("Location", s -> s.location, (s, v) -> s.location = v, "The city you are in, or the road between cities"),
        new Toggle("Clock", s -> s.clock, (s, v) -> s.clock = v, "The time of day"),
        new Toggle("Wallet", s -> s.wallet, (s, v) -> s.wallet = v, "Your credits"),
        new Toggle("Crew bank", s -> s.crew, (s, v) -> s.crew = v, "Your Crew's shared credits, when you have a Crew"),
        new Toggle("Home", s -> s.home, (s, v) -> s.home = v, "Your home, its rent and when the next payment is due"),
        new Toggle("Trade", s -> s.career, (s, v) -> s.career = v, "Your trade, level and progress"),
        new Toggle("Heat", s -> s.heat, (s, v) -> s.heat = v, "How closely the city is watching you, when it is at all"),
        new Toggle("Orders", s -> s.guidance, (s, v) -> s.guidance = v, "The accepted order and the next step of the first hours, in the same stack"));

    @Override protected void init() {
        clearWidgets();
        layoutPanel(250, 200, 330, 310);
        // the preview stack stands at its anchor; the panel goes to the other side of the screen when there is room, so that nothing overlaps
        int stackW = Math.round(HudLayout.CARD_WIDTH * (float) Mth.clamp(settings.scale, HudLayout.MIN_SCALE, HudLayout.MAX_SCALE)) + 2 * HudLayout.MARGIN;
        int free = width - stackW - 8;
        if (free >= 230) {
            pw = Math.min(pw, free);
            px = settings.anchor.right() ? 4 + (free - pw) / 2 : stackW + 4 + (free - pw) / 2;
        }
        int x = bodyX(), y = bodyY() + 2, w = bodyW();
        enabled = add(new UcButton(x, y, w, 20, "", button -> { settings.enabled = !settings.enabled; changed(); }).kind(UcButton.Kind.PRIMARY));
        y += 24;
        int half = (w - 4) / 2;
        anchor = add(new UcButton(x, y, w, 20, "", button -> { settings.anchor = hasShift() ? settings.anchor.previous() : settings.anchor.next(); changed(); })
            .tooltip("Click for the next place, Shift-click for the previous", "Xaero's minimap is at the top left unless you move it; the status effects are at the top right, so the stack starts below them"));
        y += 24;
        smaller = add(new UcButton(x, y, 22, 20, "-", button -> { settings.scale = round(settings.scale - 0.1, HudLayout.MIN_SCALE); changed(); }));
        bigger = add(new UcButton(x + half - 22, y, 22, 20, "+", button -> { settings.scale = Math.min(HudLayout.MAX_SCALE, round(settings.scale + 0.1, HudLayout.MIN_SCALE)); changed(); }));
        fainter = add(new UcButton(x + half + 4, y, 22, 20, "-", button -> { settings.opacity = round(settings.opacity - 0.1, 0.3); changed(); }));
        solider = add(new UcButton(x + w - 22, y, 22, 20, "+", button -> { settings.opacity = Math.min(1.0, round(settings.opacity + 0.1, 0.3)); changed(); }));
        y += 36; // the "Lines" label sits in the gap
        for (int i = 0; i < TOGGLES.size(); i++) {
            Toggle toggle = TOGGLES.get(i);
            int column = i % 2, row = i / 2;
            toggles[i] = add(new UcButton(x + column * (half + 4), y + row * 22, half, 20, "", button -> { toggle.set().accept(settings, !toggle.get().apply(settings)); changed(); }).kind(UcButton.Kind.CHIP).tooltip(toggle.hint()));
        }
        done = add(new UcButton(x + w - 80, py + ph - FOOTER - 3 + 2, 76, 20, "Done", button -> onClose()).kind(UcButton.Kind.PRIMARY));
        refresh();
    }

    private static boolean hasShift() { return Screen.hasShiftDown(); }
    private static double round(double value, double minimum) { return Math.max(minimum, Math.round(value * 10.0) / 10.0); }

    /** A choice was made: keep it, and lay the screen out again (the panel moves when the stack changes side). */
    private void changed() {
        if (persist) settings.store();
        init();
    }

    private void refresh() {
        enabled.label(settings.enabled ? "Status display: ON" : "Status display: OFF");
        anchor.label("Position: " + settings.anchor.label);
        smaller.active = settings.scale > HudLayout.MIN_SCALE + 0.01;
        bigger.active = settings.scale < HudLayout.MAX_SCALE - 0.01;
        fainter.active = settings.opacity > 0.31;
        solider.active = settings.opacity < 0.99;
        for (int i = 0; i < TOGGLES.size(); i++) {
            boolean on = TOGGLES.get(i).get().apply(settings);
            toggles[i].label((on ? "[x] " : "[ ] ") + TOGGLES.get(i).label());
            toggles[i].selected(on);
        }
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int x = bodyX(), y = bodyY() + 2 + 48, w = bodyW(), half = (w - 4) / 2;
        UcUi.text(g, font, "Size " + Math.round(settings.scale * 100) + "%", x + 28, y + 6, UcUi.SOFT);
        UcUi.text(g, font, "Solidity " + Math.round(settings.opacity * 100) + "%", x + half + 32, y + 6, UcUi.SOFT);
        UcUi.display(g, font, "Lines", x, y + 26, UcUi.DIM);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        UcUi.text(g, font, UcUi.fit(font, "H hides the status in play.", bodyW() - 90), px + PAD, y + 8, UcUi.DIM);
    }

    /** The real stack, at the real place (the panel is on the other side). */
    @Override protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partial) {
        ClientHud.render(g, Minecraft.getInstance(), width, height, PREVIEW, SAMPLE_ORDER, SAMPLE_STEP, 6000, settings);
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
