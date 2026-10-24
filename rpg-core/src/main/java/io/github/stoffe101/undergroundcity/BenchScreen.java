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
 * The Electronics Bench (D-158): its designs on the left (what each makes, and whether the player may make it now), the selected design's parts on the right with how many they carry, and
 * Assemble. The server makes it only from parts the player carries and says why not.
 */
@OnlyIn(Dist.CLIENT)
public final class BenchScreen extends UcScreen {
    private List<TechNetwork.DesignRow> designs;
    private String note;
    private int selected, scroll;
    private UcButton assemble;
    private int listX, listY, listW, listH;
    private static final int ROW = 22;

    BenchScreen(List<TechNetwork.DesignRow> designs, String note) {
        super(Component.literal("Underground City | Electronics Bench"));
        this.designs = designs;
        this.note = note;
    }

    void update(List<TechNetwork.DesignRow> next, String text) { designs = next; note = text; labels(); }
    /** Scenario hooks. */
    List<TechNetwork.DesignRow> designs() { return designs; }
    String note() { return note; }
    void select(String id) { for (int i = 0; i < designs.size(); i++) if (designs.get(i).id().equals(id)) selected = i; labels(); }

    @Override protected int accent() { return UcUi.LEGAL; }
    @Override protected String titleText() { return "Electronics Bench"; }
    @Override protected UcUi.Icon titleIcon() { return UcUi.Icon.TECHNICIAN; }
    @Override protected boolean showFooter() { return true; }

    @Override protected void init() {
        clearWidgets();
        layoutPanel(340, 220, 560, 330);
        listX = bodyX(); listY = bodyY(); listW = (int) (bodyW() * 0.44); listH = bodyBottom() - listY;
        assemble = add(new UcButton(px + pw - PAD - 90, py + ph - FOOTER + 2, 90, 18, "Assemble", b -> {
            if (selected < designs.size()) TechNetwork.assemble(designs.get(selected).id());
        }).kind(UcButton.Kind.PRIMARY));
        labels();
    }

    private boolean ready(TechNetwork.DesignRow d) {
        if (!d.allowed()) return false;
        for (TechNetwork.Part p : d.parts()) if (p.have() < p.need()) return false;
        return true;
    }

    private void labels() {
        if (assemble == null) return;
        assemble.active = selected < designs.size() && ready(designs.get(selected));
    }

    private static Item item(String id) { Item i = ForgeRegistries.ITEMS.getValue(ResourceLocation.tryParse(id)); return i == null ? net.minecraft.world.item.Items.BARRIER : i; }

    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int index = scroll + (int) ((my - listY - 2) / ROW);
            if (index >= 0 && index < designs.size()) { selected = index; labels(); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), Math.max(0, designs.size() - listH / ROW)));
        return true;
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        UcUi.well(g, listX, listY, listW, listH);
        for (int i = 0; i < listH / ROW && scroll + i < designs.size(); i++) {
            TechNetwork.DesignRow d = designs.get(scroll + i);
            int y = listY + 2 + i * ROW, x = listX + 3, w = listW - 6;
            boolean sel = scroll + i == selected;
            g.fill(x, y, x + w, y + ROW - 2, sel ? UcUi.ROW_HOVER : UcUi.ROW);
            if (sel) g.fill(x, y, x + 2, y + ROW - 2, UcUi.LEGAL);
            g.renderItem(new ItemStack(item(d.output())), x + 3, y + 2);
            UcUi.text(g, font, UcUi.fit(font, d.title(), w - 40), x + 23, y + 2, d.allowed() ? UcUi.TEXT : UcUi.DIM);
            String state = !d.allowed() ? "Needs a skill" : ready(d) ? "Ready" : "Missing parts";
            UcUi.text(g, font, UcUi.fit(font, state, w - 40), x + 23, y + 11, !d.allowed() ? UcUi.FAINT : ready(d) ? UcUi.OK : UcUi.AMBER);
        }
        if (selected >= designs.size()) return;
        TechNetwork.DesignRow d = designs.get(selected);
        int x = listX + listW + 8, w = bodyX() + bodyW() - x, y = listY;
        UcUi.display(g, font, UcUi.fit(font, d.title(), w), x, y, UcUi.TEXT);
        UcUi.text(g, font, UcUi.fit(font, "Makes " + d.count() + " " + item(d.output()).getDescription().getString(), w), x, y + 11, UcUi.SOFT);
        int ly = UcUi.wrapped(g, font, d.note(), x, y + 24, w, UcUi.DIM, 3) + 4;
        for (TechNetwork.Part p : d.parts()) {
            g.renderItem(new ItemStack(item(p.item())), x, ly - 4);
            boolean enough = p.have() >= p.need();
            UcUi.text(g, font, UcUi.fit(font, item(p.item()).getDescription().getString(), w - 52), x + 20, ly, UcUi.SOFT);
            UcUi.textRight(g, font, Math.min(p.have(), 99) + " / " + p.need(), x + w, ly, enough ? UcUi.OK : UcUi.AMBER);
            ly += 18;
        }
        if (!d.allowed()) UcUi.wrapped(g, font, "Learn " + d.skill() + " to make this.", x, ly + 2, w, UcUi.UNDERGROUND_TEXT, 2);
    }

    @Override protected void renderFooter(GuiGraphics g, int y) {
        UcUi.text(g, font, UcUi.fit(font, note == null || note.isEmpty() ? "Parts: the exchange, salvage and offices." : note, pw - 2 * PAD - 100), px + PAD, y + 9,
            note != null && note.startsWith("Made") ? UcUi.OK : note == null || note.isEmpty() ? UcUi.DIM : UcUi.AMBER);
    }
}
