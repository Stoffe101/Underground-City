package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * A personal computer (D-158): a desktop with two apps. <b>Service</b> is the city's board of service calls and jobs (where, what, the pay, how long it has been open, and whether this player
 * may take it and if not why), with Track, which points the status display's arrow at the building's panel. <b>Designs</b> lists what an Electronics Bench can make and what the player still
 * lacks. What the computer shows was sent by the server when it was switched on.
 */
@OnlyIn(Dist.CLIENT)
public final class ComputerScreen extends UcScreen {
    private final List<TechWork.Call> calls;
    private final List<TechNetwork.DesignRow> designs;
    private final boolean technician;
    private final int level;
    private int app, selected, scroll;
    private UcButton serviceB, designsB, trackB;
    private int listX, listY, listW, listH;
    private static final int ROW = 24;

    ComputerScreen(List<TechWork.Call> calls, List<TechNetwork.DesignRow> designs, boolean technician, int level) {
        super(Component.literal("Underground City | Computer"));
        this.calls = calls; this.designs = designs; this.technician = technician; this.level = level;
    }

    /** Scenario hooks. */
    List<TechWork.Call> calls() { return calls; }
    void select(int index) { selected = index; labels(); }
    /** Capture pose: the Designs app with a row selected. */
    void showDesigns(int index) { app = 1; selected = index; labels(); }

    @Override protected int accent() { return UcUi.LEGAL; }
    @Override protected String titleText() { return "Computer"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TECHNICIAN; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(360, 220, 600, 340);
        int x = bodyX(), y = bodyY();
        serviceB = add(new UcButton(x, y, 76, 16, "Service", b -> { app = 0; selected = 0; scroll = 0; labels(); }).kind(UcButton.Kind.CHIP));
        designsB = add(new UcButton(x + 80, y, 76, 16, "Designs", b -> { app = 1; selected = 0; scroll = 0; labels(); }).kind(UcButton.Kind.CHIP));
        listX = x; listY = y + 22; listW = (int) (bodyW() * 0.55); listH = bodyBottom() - listY;
        trackB = add(new UcButton(px + pw - PAD - 80, py + ph - FOOTER + 2, 80, 18, "Track", b -> track()).kind(UcButton.Kind.PRIMARY));
        labels();
    }

    private void labels() {
        if (serviceB == null) return;
        serviceB.selected(app == 0);
        designsB.selected(app == 1);
        trackB.visible = app == 0;
        trackB.active = app == 0 && selected < calls.size();
    }

    private void track() {
        if (app != 0 || selected >= calls.size()) return;
        TechWork.Call c = calls.get(selected);
        if (ClientNavigation.tracking("tech", c.id().toString())) NavigationNetwork.stop();
        else NavigationNetwork.track("tech", c.id().toString());
        onClose();
    }

    private static String kindWord(String kind) {
        return switch (kind) { case "repair_shop" -> "Till repair"; case "repair_security" -> "Alarm repair"; case "install" -> "Alarm install"; case "quiet" -> "Back channel: loop the feeds"; default -> "Network upgrade"; };
    }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int index = scroll + (int) ((my - listY - 2) / ROW);
            int size = app == 0 ? calls.size() : designs.size();
            if (index >= 0 && index < size) { selected = index; labels(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        int size = app == 0 ? calls.size() : designs.size();
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), Math.max(0, size - listH / ROW)));
        return true;
    }

    private static Item item(String id) { Item i = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id)); return i == null ? net.minecraft.world.item.Items.BARRIER : i; }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String who = technician ? "TECHNICIAN L" + level : "NOT A TECHNICIAN";
        UcUi.chip(g, font, who, right - UcUi.displayWidth(font, who) - 10, py + 9, technician ? UcUi.LEGAL : UcUi.DIM, null);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        UcUi.well(g, listX, listY, listW, listH);
        if (app == 0) renderService(g); else renderDesigns(g);
    }

    private void renderService(GuiGraphics g) {
        if (calls.isEmpty()) UcUi.wrapped(g, font, "No service calls right now. Faults come now and then to buildings of a city somebody is in.", listX + 8, listY + 8, listW - 16, UcUi.DIM, 4);
        for (int i = 0; i < listH / ROW && scroll + i < calls.size(); i++) {
            TechWork.Call c = calls.get(scroll + i);
            int y = listY + 2 + i * ROW, x = listX + 3, w = listW - 6;
            boolean sel = scroll + i == selected;
            g.fill(x, y, x + w, y + ROW - 2, sel ? UcUi.ROW_HOVER : UcUi.ROW);
            if (sel) g.fill(x, y, x + 2, y + ROW - 2, c.kind().equals("quiet") ? UcUi.UNDERGROUND : UcUi.LEGAL);
            UcUi.text(g, font, PropertyIntel.roman(c.tier()), x + 6, y + 7, ClientPlaces.TIER_COLOUR[Math.max(0, Math.min(6, c.tier()))]);
            UcUi.text(g, font, UcUi.fit(font, c.address(), w - 80), x + 26, y + 2, UcUi.TEXT);
            UcUi.textRight(g, font, c.credits() + " cr", x + w - 4, y + 2, UcUi.AMBER);
            UcUi.text(g, font, UcUi.fit(font, TerminalScreenText.capitalize(c.city()) + " · " + kindWord(c.kind()), w - 34), x + 26, y + 12, c.blocked().isEmpty() ? UcUi.DIM : UcUi.FAINT);
        }
        if (selected >= calls.size()) return;
        TechWork.Call c = calls.get(selected);
        int x = listX + listW + 8, w = bodyX() + bodyW() - x, y = listY;
        UcUi.display(g, font, UcUi.fit(font, Casing.capitalise(c.label()), w), x, y, UcUi.TEXT);
        UcUi.text(g, font, UcUi.fit(font, c.address() + ", " + TerminalScreenText.capitalize(c.city()), w), x, y + 11, UcUi.SOFT);
        int ly = y + 26;
        ly = line(g, x, ly, w, "Job", kindWord(c.kind()), UcUi.SOFT);
        ly = line(g, x, ly, w, "Pays", c.credits() + " credits, " + c.xp() + " XP", UcUi.AMBER);
        ly = line(g, x, ly, w, "Tier", Thievery.tierWord(c.tier()) + " " + PropertyIntel.roman(c.tier()), UcUi.SOFT);
        ly = line(g, x, ly, w, c.kind().startsWith("repair") ? "Open" : "Left", c.minutesOpen() + " min", UcUi.DIM);
        UcUi.text(g, font, "You", x, ly, UcUi.DIM);
        ly = UcUi.wrapped(g, font, c.blocked().isEmpty() ? "Can take it" : c.blocked(), x + 44, ly, w - 44, c.blocked().isEmpty() ? UcUi.OK : UcUi.UNDERGROUND_TEXT, 2);
        String what = c.kind().equals("repair_shop") ? "The till and card network are down." : c.kind().equals("repair_security") ? "The cameras are down (the alarm still works)."
            : c.kind().equals("quiet") ? "Off the books: loop its cameras and quiet its alarm line for ten minutes. Anybody who sees you at the panel may call the police." : "";
        if (!what.isEmpty()) ly = UcUi.wrapped(g, font, what, x, ly + 4, w, UcUi.SOFT, 3);
        int room = Math.min(3, (listY + listH - (ly + 4)) / (font.lineHeight + 1));   // only what fits above the footer (1280x720 at GUI scale 2 has room for two lines)
        if (room > 0) UcUi.wrapped(g, font, c.kind().startsWith("repair") ? "Go to its utility panel by the street door with your toolkit." : c.kind().equals("install")
            ? "Bring a Municipal Security Array to its utility panel." : c.kind().equals("quiet") ? "Crouch at its utility panel with your toolkit and a Bypass Kit, unseen."
            : "Bring your toolkit to its utility panel.", x, ly + 4, w, UcUi.DIM, room);
    }

    private void renderDesigns(GuiGraphics g) {
        for (int i = 0; i < listH / ROW && scroll + i < designs.size(); i++) {
            TechNetwork.DesignRow d = designs.get(scroll + i);
            int y = listY + 2 + i * ROW, x = listX + 3, w = listW - 6;
            boolean sel = scroll + i == selected;
            g.fill(x, y, x + w, y + ROW - 2, sel ? UcUi.ROW_HOVER : UcUi.ROW);
            g.renderItem(new ItemStack(item(d.output())), x + 3, y + 3);
            UcUi.text(g, font, UcUi.fit(font, d.title(), w - 26), x + 23, y + 3, d.allowed() ? UcUi.TEXT : UcUi.DIM);
            UcUi.text(g, font, UcUi.fit(font, d.allowed() ? "At an Electronics Bench" : "Needs " + d.skill(), w - 26), x + 23, y + 12, d.allowed() ? UcUi.DIM : UcUi.FAINT);
        }
        if (selected >= designs.size()) return;
        TechNetwork.DesignRow d = designs.get(selected);
        int x = listX + listW + 8, w = bodyX() + bodyW() - x;
        UcUi.display(g, font, UcUi.fit(font, d.title(), w), x, listY, UcUi.TEXT);
        int ly = UcUi.wrapped(g, font, d.note(), x, listY + 12, w, UcUi.DIM, 3) + 4;
        for (TechNetwork.Part p : d.parts()) {
            g.renderItem(new ItemStack(item(p.item())), x, ly - 4);
            UcUi.text(g, font, UcUi.fit(font, item(p.item()).getDescription().getString(), w - 56), x + 20, ly, UcUi.SOFT);
            UcUi.textRight(g, font, Math.min(p.have(), 99) + " / " + p.need(), x + w, ly, p.have() >= p.need() ? UcUi.OK : UcUi.AMBER);
            ly += 18;
        }
    }

    private int line(GuiGraphics g, int x, int y, int w, String label, String value, int colour) {
        UcUi.text(g, font, label, x, y, UcUi.DIM);
        UcUi.text(g, font, UcUi.fit(font, value, w - 46), x + 44, y, colour);
        return y + 11;
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String hint = app == 0 ? calls.size() + (calls.size() == 1 ? " job open" : " jobs open") : "Designs are made at an Electronics Bench.";
        UcUi.text(g, font, UcUi.fit(font, hint, pw - 2 * PAD - 90), px + PAD, y + 9, UcUi.DIM);
    }
}
