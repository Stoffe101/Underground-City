package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Places screen (D-157): the buildings a player has glanced at or cased, as they remember them. A list on the left (tier, what and where, how far, whether they could try it), filters
 * across the top (city, kind, tier, what they know), the selected place's details on the right (what the last casing told and when) with Track, which points the status display's arrow at it,
 * Mark and Forget. Nothing here is more than the player learned at the door; it never shows what is inside.
 */
@OnlyIn(Dist.CLIENT)
public final class PlacesScreen extends UcScreen {
    private static final String[] CITIES = {"", "ironhaven", "valewood", "blackwater"};
    private static final String[] KINDS = {"", "residential", "commercial", "industrial", "office"};
    private static final String[] TIERS = {"Any tier", "Tiers I-II", "Tiers III-IV", "Tiers V-VI"};
    private static final String[] SHOW = {"All", "Cased", "Not cased", "Marked", "You could try"};
    private static final String[] SORTS = {"Nearest", "Tier", "Latest"};

    private int city, kind, tier, show, sort, scroll, selected = -1;
    private long seenVersion = -1;
    private List<PropertyIntel.Row> shown = List.of();
    private UcButton cityB, kindB, tierB, showB, sortB, trackB, markB, forgetB;
    private int listX, listY, listW, listH, detailX, detailW;
    private static final int ROW = 24;

    PlacesScreen() { super(Component.literal("Underground City | Places")); }

    @Override protected int accent() { return UcUi.AMBER; }
    @Override protected String titleText() { return "Places"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.PIN; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(360, 220, 640, 380);
        int x = bodyX(), y = bodyY(), w = bodyW();
        int bw = Math.max(56, Math.min(96, (w - 4 * 4) / 5));
        cityB = add(new UcButton(x, y, bw, 16, "", b -> { city = (city + 1) % CITIES.length; refilter(); }).kind(UcButton.Kind.CHIP));
        kindB = add(new UcButton(x + (bw + 4), y, bw, 16, "", b -> { kind = (kind + 1) % KINDS.length; refilter(); }).kind(UcButton.Kind.CHIP));
        tierB = add(new UcButton(x + 2 * (bw + 4), y, bw, 16, "", b -> { tier = (tier + 1) % TIERS.length; refilter(); }).kind(UcButton.Kind.CHIP));
        showB = add(new UcButton(x + 3 * (bw + 4), y, bw, 16, "", b -> { show = (show + 1) % SHOW.length; refilter(); }).kind(UcButton.Kind.CHIP));
        sortB = add(new UcButton(x + 4 * (bw + 4), y, bw, 16, "", b -> { sort = (sort + 1) % SORTS.length; refilter(); }).kind(UcButton.Kind.CHIP));
        listX = x;
        listY = y + 22;
        listW = (int) (w * 0.52);
        listH = bodyBottom() - listY;
        detailX = listX + listW + 8;
        detailW = x + w - detailX;
        int by = bodyBottom() - 18, bwid = Math.max(40, (detailW - 8) / 3);
        trackB = add(new UcButton(detailX, by, bwid, 18, "Track", b -> track()).kind(UcButton.Kind.PRIMARY));
        markB = add(new UcButton(detailX + bwid + 4, by, bwid, 18, "Mark", b -> mark()));
        forgetB = add(new UcButton(detailX + 2 * (bwid + 4), by, detailW - 2 * (bwid + 4), 18, "Forget", b -> forget()).kind(UcButton.Kind.DANGER));
        refilter();
    }

    private void labels() {
        cityB.label(city == 0 ? "All cities" : TerminalScreenText.capitalize(CITIES[city]));
        kindB.label(kind == 0 ? "All kinds" : PropertyIntel.categoryWord(KINDS[kind]));
        tierB.label(TIERS[tier]);
        showB.label(SHOW[show]);
        sortB.label("By: " + SORTS[sort]);
        PropertyIntel.Row row = selectedRow();
        trackB.active = row != null;
        markB.active = row != null;
        forgetB.active = row != null;
        PropertyIntel.Entry tracked = ClientPlaces.tracked();
        trackB.label(row != null && tracked != null && tracked.key() == row.entry().key() ? "Stop" : "Track");
        markB.label(row != null && row.entry().marked() ? "Unmark" : "Mark");
    }

    private PropertyIntel.Row selectedRow() { return selected >= 0 && selected < shown.size() ? shown.get(selected) : null; }

    private double distance(PropertyIntel.Entry e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        return Math.hypot(e.door().getX() + 0.5 - mc.player.getX(), e.door().getZ() + 0.5 - mc.player.getZ());
    }

    private void refilter() {
        long key = selectedRow() == null ? Long.MIN_VALUE : selectedRow().entry().key();
        List<PropertyIntel.Row> out = new ArrayList<>();
        for (PropertyIntel.Row row : ClientPlaces.rows()) {
            PropertyIntel.Entry e = row.entry();
            if (city > 0 && !CITIES[city].equals(e.city())) continue;
            if (kind > 0 && !KINDS[kind].equals(e.category())) continue;
            if (tier == 1 && e.tier() > 2 || tier == 2 && (e.tier() < 3 || e.tier() > 4) || tier == 3 && e.tier() < 5) continue;
            if (show == 1 && e.layer() < 0 || show == 2 && e.layer() >= 0 || show == 3 && !e.marked() || show == 4 && !row.suitable()) continue;
            out.add(row);
        }
        Comparator<PropertyIntel.Row> order = switch (sort) {
            case 1 -> Comparator.comparingInt((PropertyIntel.Row r) -> r.entry().tier()).thenComparingDouble(r -> distance(r.entry()));
            case 2 -> Comparator.comparingLong((PropertyIntel.Row r) -> -r.entry().lastSeen());
            default -> Comparator.comparingDouble((PropertyIntel.Row r) -> distance(r.entry()));
        };
        out.sort(Comparator.comparing((PropertyIntel.Row r) -> !r.entry().marked()).thenComparing(order));
        shown = out;
        selected = -1;
        for (int i = 0; i < out.size(); i++) if (out.get(i).entry().key() == key) selected = i;
        if (selected < 0 && !out.isEmpty()) selected = 0;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, out.size() - visibleRows())));
        seenVersion = ClientPlaces.bookVersion();
        labels();
    }

    private int visibleRows() { return Math.max(1, listH / ROW); }

    private void track() {
        PropertyIntel.Row row = selectedRow();
        if (row == null) return;
        PropertyIntel.Entry tracked = ClientPlaces.tracked();
        if (tracked != null && tracked.key() == row.entry().key()) ClientPlaces.stopTracking();
        else { ClientPlaces.track(row.entry()); onClose(); return; }
        labels();
    }

    private void mark() { PropertyIntel.Row row = selectedRow(); if (row != null) PlacesNetwork.mark(row.entry().key(), !row.entry().marked()); }
    private void forget() { PropertyIntel.Row row = selectedRow(); if (row != null) PlacesNetwork.forget(row.entry().key()); }

    @Override public void tick() { if (seenVersion != ClientPlaces.bookVersion()) refilter(); }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + visibleRows() * ROW) {
            int index = scroll + (int) ((my - listY) / ROW);
            if (index < shown.size()) { selected = index; labels(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), Math.max(0, shown.size() - visibleRows())));
        return true;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (ClientPlaces.PLACES.matches(key, scan)) { onClose(); return true; }
        if (key == 264 && selected < shown.size() - 1) { selected++; if (selected >= scroll + visibleRows()) scroll++; labels(); return true; }
        if (key == 265 && selected > 0) { selected--; if (selected < scroll) scroll--; labels(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        String count = ClientPlaces.rows().size() + " known";
        UcUi.chip(g, font, count, right - UcUi.displayWidth(font, count) - 10, py + 9, UcUi.AMBER, null);
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        UcUi.well(g, listX, listY, listW, listH);
        if (shown.isEmpty()) {
            String empty = ClientPlaces.rows().isEmpty() ? "No places yet. Look at a building's street door for a moment to remember it, or press I there to case it."
                : "Nothing matches these filters.";
            UcUi.wrapped(g, font, empty, listX + 8, listY + 8, listW - 16, UcUi.DIM, 4);
        }
        for (int i = 0; i < visibleRows() && scroll + i < shown.size(); i++) {
            PropertyIntel.Row row = shown.get(scroll + i);
            PropertyIntel.Entry e = row.entry();
            int y = listY + 2 + i * ROW, x = listX + 3, w = listW - 6;
            boolean sel = scroll + i == selected;
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW - 2;
            g.fill(x, y, x + w, y + ROW - 2, sel ? UcUi.ROW_HOVER : hover ? 0xff182838 : UcUi.ROW);
            if (sel) g.fill(x, y, x + 2, y + ROW - 2, UcUi.AMBER);
            int colour = ClientPlaces.TIER_COLOUR[Math.max(0, Math.min(6, e.tier()))];
            String numeral = PropertyIntel.roman(e.tier());
            UcUi.text(g, font, numeral, x + 6, y + 7, colour);
            int tx = x + 26;
            String title = (e.marked() ? "* " : "") + Casing.capitalise(e.label()) + "  " + e.address();
            UcUi.text(g, font, UcUi.fit(font, title, w - 30 - 44), tx, y + 2, UcUi.TEXT);
            String dist = (int) Math.round(distance(e)) + " m";
            UcUi.textRight(g, font, dist, x + w - 4, y + 2, UcUi.DIM);
            String state = TerminalScreenText.capitalize(e.city()) + " · " + (e.layer() >= 0 ? "cased" : "seen") + (row.beenIn() ? " · been in" : "");
            UcUi.text(g, font, UcUi.fit(font, state, w - 30 - 20), tx, y + 12, UcUi.DIM);
            UcUi.icon(g, row.suitable() ? UcUi.Icon.CHECK : UcUi.Icon.LOCK, x + w - 18, y + 8, row.suitable() ? UcUi.OK : UcUi.FAINT);
        }
        if (shown.size() > visibleRows()) {
            int trackH = listH - 4, thumb = Math.max(12, trackH * visibleRows() / shown.size());
            int ty = listY + 2 + (trackH - thumb) * scroll / Math.max(1, shown.size() - visibleRows());
            g.fill(listX + listW - 3, ty, listX + listW - 1, ty + thumb, 0xff4a5f73);
        }
        renderDetail(g);
    }

    private void renderDetail(GuiGraphics g) {
        PropertyIntel.Row row = selectedRow();
        int x = detailX, y = listY, w = detailW;
        if (row == null) { UcUi.wrapped(g, font, "Choose a place on the left.", x, y + 4, w, UcUi.DIM, 3); return; }
        PropertyIntel.Entry e = row.entry();
        int colour = ClientPlaces.TIER_COLOUR[Math.max(0, Math.min(6, e.tier()))];
        UcUi.display(g, font, UcUi.fit(font, Casing.capitalise(e.label()), w), x, y, UcUi.TEXT);
        UcUi.text(g, font, UcUi.fit(font, e.address(), w), x, y + 11, UcUi.SOFT);
        for (int i = 0; i < 6; i++) {
            int px0 = x + i * 8;
            if (i < e.tier()) g.fill(px0, y + 25, px0 + 6, y + 31, colour);
            else { g.fill(px0, y + 25, px0 + 6, y + 31, 0xff4a5f73); g.fill(px0 + 1, y + 26, px0 + 5, y + 30, 0xff101a24); }
        }
        UcUi.text(g, font, Thievery.tierWord(e.tier()) + "  " + PropertyIntel.roman(e.tier()) + " / VI", x + 54, y + 24, colour);
        int ly = y + 38;
        ly = line(g, x, ly, w, "Kind", PropertyIntel.categoryWord(e.category()), UcUi.SOFT);
        ly = line(g, x, ly, w, "City", TerminalScreenText.capitalize(e.city()), UcUi.SOFT);
        ly = line(g, x, ly, w, "Cased", e.layer() < 0 ? "Not yet" : layerWord(e.layer()) + ", " + ago(e.casedAt()), e.layer() < 0 ? UcUi.DIM : UcUi.SOFT);
        if (!e.lock().isEmpty()) ly = line(g, x, ly, w, "Door", shortLock(e.lock()), UcUi.SOFT);
        if (e.alarm() >= 0) ly = line(g, x, ly, w, "Alarm", switch (e.alarm()) { case 0 -> "None"; case 1 -> "Not armed"; case 2 -> "Armed"; default -> "Armed, panel"; }, e.alarm() >= 2 ? UcUi.UNDERGROUND_TEXT : UcUi.SOFT);
        if (e.cameras() >= 0) ly = line(g, x, ly, w, "Cameras", e.cameras() == 0 ? "None seen" : String.valueOf(e.cameras()), e.cameras() > 0 ? UcUi.AMBER : UcUi.SOFT);
        if (!e.risk().isEmpty()) ly = line(g, x, ly, w, "Risk then", e.risk(), UcUi.SOFT);
        if (e.watchful()) ly = line(g, x, ly, w, "Lately", "Broken into: watchful", UcUi.UNDERGROUND_TEXT);
        ly = line(g, x, ly, w, "You", (row.suitable() ? "Could try it" : "Can't try yet") + (row.beenIn() ? ", been in" : ""), row.suitable() ? UcUi.OK : UcUi.DIM);
        ly = line(g, x, ly, w, "Seen", ago(e.lastSeen()), UcUi.DIM);
    }

    private int line(GuiGraphics g, int x, int y, int w, String label, String value, int colour) {
        if (y > bodyBottom() - 32) return y;
        UcUi.text(g, font, label, x, y, UcUi.DIM);
        UcUi.text(g, font, UcUi.fit(font, value, w - 44), x + 42, y, colour);
        return y + 11;
    }

    private static String layerWord(int layer) {
        return switch (layer) { case 0 -> "Street"; case 1 -> "Casing"; case 2 -> "Routines"; default -> "Expert"; };
    }

    /** "a secured lock with a spool pin" as a short value: "Secured lock". */
    static String shortLock(String lock) {
        String s = lock.startsWith("a ") ? lock.substring(2) : lock;
        int with = s.indexOf(" with ");
        return Casing.capitalise(with > 0 ? s.substring(0, with) : s);
    }

    private static String ago(long tick) {
        long minutes = Math.max(0, (ClientPlaces.clock() - tick) / 1200);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60;
        return hours < 48 ? hours + " h ago" : hours / 24 + " days ago";
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String hint = "Look at a door to remember it; " + key(ClientPlaces.INSPECT) + " cases it.";
        UcUi.text(g, font, UcUi.fit(font, hint, pw - 2 * PAD), px + PAD, y + 9, UcUi.DIM);
    }

    private static String key(net.minecraft.client.KeyMapping mapping) { return mapping.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT); }
}
