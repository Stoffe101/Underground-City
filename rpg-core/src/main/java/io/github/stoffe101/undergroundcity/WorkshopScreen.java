package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * A workshop station (D-163): its recipes on the left (what each makes, and whether the player may make it now), the selected recipe on the right (what goes in against what the player carries,
 * how long it takes at this tier, the grade it can reach), the batch at work with its time left, and the station's tier with the parts that fit the next one. Start, Take and Fit; the server does each
 * and says what happened in the footer.
 */
@OnlyIn(Dist.CLIENT)
public final class WorkshopScreen extends UcScreen {
    private WorkshopNetwork.View view;
    private int selected, scroll, batches = 1;
    private UcButton start, take, fit, more;
    private int listX, listY, listW, listH;
    private static final int ROW = 22, STATUS = 34;

    WorkshopScreen(WorkshopNetwork.View view) {
        super(Component.literal("Underground City | " + view.title()));
        this.view = view;
    }

    BlockPos pos() { return view.pos(); }
    WorkshopNetwork.View view() { return view; }
    void update(WorkshopNetwork.View next) { view = next; if (selected >= view.rows().size()) selected = 0; batches = Math.min(batches, view.batch()); labels(); }
    /** Scenario hook. */
    void select(String id) { for (int i = 0; i < view.rows().size(); i++) if (view.rows().get(i).id().equals(id)) selected = i; labels(); }
    /** The middle of a recipe's row in the list, where a click selects it, or null when it is scrolled out of view (for a scenario's real click). */
    int[] rowCentre(String id) {
        for (int i = 0; i < view.rows().size(); i++) if (view.rows().get(i).id().equals(id)) {
            int y = listY + 2 + (i - scroll) * ROW + ROW / 2;
            return i < scroll || y > listY + listH - ROW / 2 ? null : new int[]{listX + listW / 2, y};
        }
        return null;
    }
    String selectedId() { return selected < view.rows().size() ? view.rows().get(selected).id() : ""; }

    @Override protected int accent() { return UcUi.LEGAL; }
    @Override protected String titleText() { return view.title() + ", tier " + view.tier() + " of " + view.maxTier(); }   // the display font has no middle dot (workshop run 2)
    @Override protected UcUi.Icon titleIcon() { return UcUi.careerIcon(view.career()); }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(360, 240, 600, 360);
        listX = bodyX(); listY = bodyY() + STATUS; listW = (int) (bodyW() * 0.44); listH = bodyBottom() - listY;
        int by = py + ph - FOOTER + 2;
        start = add(new UcButton(px + pw - PAD - 70, by, 70, 18, "Start", b -> {
            if (selected < view.rows().size()) WorkshopNetwork.start(view.pos(), view.rows().get(selected).id(), batches);
        }).kind(UcButton.Kind.PRIMARY));
        more = add(new UcButton(px + pw - PAD - 70 - 4 - 44, by, 44, 18, "x1", b -> { batches = batches % Math.max(1, view.batch()) + 1; labels(); }));
        take = add(new UcButton(px + pw - PAD - 70 - 4 - 44 - 4 - 56, by, 56, 18, "Take", b -> WorkshopNetwork.collect(view.pos())));
        fit = add(new UcButton(bodyX() + bodyW() - 96, bodyY() + 4, 96, 18, "Fit tier", b -> WorkshopNetwork.upgrade(view.pos())));
        labels();
    }

    private boolean ready(WorkshopNetwork.Row r) {
        if (!r.allowed()) return false;
        for (WorkshopNetwork.Part p : r.parts()) if (p.have() < p.need() * batches) return false;
        return true;
    }

    private void labels() {
        if (start == null) return;
        boolean busy = !view.running().isEmpty();
        start.active = !busy && selected < view.rows().size() && ready(view.rows().get(selected));
        more.label("x" + batches);
        more.active = view.batch() > 1;
        take.active = view.readyCount() > 0;
        boolean canFit = !view.nextName().isEmpty() && view.nextSkillOk();
        for (WorkshopNetwork.Part p : view.nextParts()) canFit &= p.have() >= p.need();
        fit.visible = !view.nextName().isEmpty();
        fit.active = canFit;
        fit.label("Fit tier " + (view.tier() + 1));
    }

    private static Item item(String id) { Item i = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id)); return i == null ? net.minecraft.world.item.Items.BARRIER : i; }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int index = scroll + (int) ((my - listY - 2) / ROW);
            if (index >= 0 && index < view.rows().size()) { selected = index; labels(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), Math.max(0, view.rows().size() - listH / ROW)));
        return true;
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // the station: its tier, what fits the next one, the batch at work and what is ready to take
        int sx = bodyX(), sy = bodyY(), sw = bodyW() - 104;
        UcUi.text(g, font, UcUi.fit(font, view.tierName() + ": " + view.batch() + " at a time, " + view.speedPercent() + "% speed", sw), sx, sy, UcUi.SOFT);
        if (!view.running().isEmpty()) {
            float done = view.secondsTotal() == 0 ? 1f : 1f - view.secondsLeft() / (float) view.secondsTotal();
            String left = view.secondsLeft() < 90 ? view.secondsLeft() + " s" : (view.secondsLeft() + 59) / 60 + " min";
            UcUi.text(g, font, UcUi.fit(font, "At work: " + view.running() + (view.grade() > 0 ? " (" + Workshops.GRADES[view.grade()] + ")" : "") + ", " + left + " left", sw), sx, sy + 11, UcUi.AMBER);
            UcUi.bar(g, sx, sy + 22, sw, 3, done, UcUi.AMBER);
        } else if (view.readyCount() > 0) {
            UcUi.text(g, font, UcUi.fit(font, "Ready: " + view.readyCount() + " " + item(view.readyItem()).getDescription().getString() + ". Take it.", sw), sx, sy + 11, UcUi.OK);
        } else if (!view.nextName().isEmpty()) {
            // what is still missing for the next tier, so the line says what to fetch (the full list of parts cut off at 1280x720: workshop run 2)
            StringBuilder missing = new StringBuilder();
            for (WorkshopNetwork.Part p : view.nextParts()) {
                if (p.have() >= p.need()) continue;
                missing.append(missing.length() == 0 ? "" : ", ").append(p.need() - p.have()).append(" ").append(item(p.item()).getDescription().getString());
            }
            if (!view.nextSkill().isEmpty() && !view.nextSkillOk()) missing.append(missing.length() == 0 ? "" : ", ").append(view.nextSkill());
            String need = "Next: " + view.nextName() + (missing.length() == 0 ? ", everything to fit it is here" : ", needs " + missing);
            UcUi.text(g, font, UcUi.fit(font, need, sw), sx, sy + 11, missing.length() == 0 ? UcUi.OK : UcUi.DIM);
        } else {
            UcUi.text(g, font, "At its best tier.", sx, sy + 11, UcUi.DIM);
        }
        // the recipes
        UcUi.well(g, listX, listY, listW, listH);
        for (int i = 0; i < listH / ROW && scroll + i < view.rows().size(); i++) {
            WorkshopNetwork.Row r = view.rows().get(scroll + i);
            int y = listY + 2 + i * ROW, x = listX + 3, w = listW - 6;
            boolean sel = scroll + i == selected;
            g.fill(x, y, x + w, y + ROW - 2, sel ? UcUi.ROW_HOVER : UcUi.ROW);
            if (sel) g.fill(x, y, x + 2, y + ROW - 2, r.underground() ? UcUi.UNDERGROUND : UcUi.LEGAL);
            g.renderItem(new ItemStack(item(r.output())), x + 3, y + 2);
            UcUi.text(g, font, UcUi.fit(font, r.title(), w - 26), x + 23, y + 2, r.allowed() ? UcUi.TEXT : UcUi.DIM);
            String state = !r.allowed() ? (view.tier() < r.tier() ? "Needs tier " + r.tier() : "Needs a skill") : ready(r) ? "Ready" : "Missing inputs";
            UcUi.text(g, font, UcUi.fit(font, state, w - 26), x + 23, y + 11, !r.allowed() ? UcUi.FAINT : ready(r) ? UcUi.OK : UcUi.AMBER);
        }
        if (selected >= view.rows().size()) return;
        WorkshopNetwork.Row r = view.rows().get(selected);
        int x = listX + listW + 8, w = bodyX() + bodyW() - x, y = listY;
        UcUi.display(g, font, UcUi.fit(font, r.title(), w), x, y, r.underground() ? UcUi.UNDERGROUND_TEXT : UcUi.TEXT);
        String time = r.seconds() == 0 ? "at once" : r.seconds() < 90 ? r.seconds() + " s of play" : (r.seconds() + 59) / 60 + " min of play";
        UcUi.text(g, font, UcUi.fit(font, "Makes " + r.count() * batches + " " + item(r.output()).getDescription().getString() + ", " + time, w), x, y + 11, UcUi.SOFT);
        int ly = y + 23;
        if (r.graded()) { UcUi.text(g, font, UcUi.fit(font, "Graded: Standard, Fine or Reserve", w), x, ly, UcUi.AMBER); ly += 11; }
        ly = UcUi.wrapped(g, font, r.note(), x, ly, w, UcUi.DIM, 3) + 4;
        for (WorkshopNetwork.Part p : r.parts()) {
            g.renderItem(new ItemStack(item(p.item())), x, ly - 4);
            boolean enough = p.have() >= p.need() * batches;
            UcUi.text(g, font, UcUi.fit(font, item(p.item()).getDescription().getString(), w - 52), x + 20, ly, UcUi.SOFT);
            UcUi.textRight(g, font, Math.min(p.have(), 999) + " / " + p.need() * batches, x + w, ly, enough ? UcUi.OK : UcUi.AMBER);
            ly += 18;
        }
        if (!r.allowed() && !r.reason().isEmpty()) UcUi.wrapped(g, font, r.reason(), x, ly + 2, w, UcUi.UNDERGROUND_TEXT, 2);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        String note = view.note();
        boolean good = note.startsWith("Made") || note.startsWith("Started") || note.startsWith("Took") || note.startsWith("Fitted");
        UcUi.text(g, font, UcUi.fit(font, note.isEmpty() ? "Takes what you carry." : note, pw - 2 * PAD - 190), px + PAD, y + 9, note.isEmpty() ? UcUi.DIM : good ? UcUi.OK : UcUi.AMBER);
    }
}
