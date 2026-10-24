package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;
import java.util.Locale;

/**
 * A conversation with a named citizen: their portrait, name and occupation, what they say on a sheet of paper and the answers you can give. The
 * server wrote every line and validates every choice ({@link Dialogue}); this screen only draws and reports the choice. Number keys pick an answer,
 * Esc leaves, and a conversation never pauses the world.
 */
@OnlyIn(Dist.CLIENT)
public final class DialogueScreen extends UcScreen {
    public record Choice(String id, String label, boolean enabled, String hint) {}

    private final int entityId;
    private final String name, subtitle, city, text;
    private final List<Choice> choices;
    private final boolean qa;

    /** The id of the entity this conversation is with (a scripted playtest checks it spoke to the person it meant to). */
    int entityId() { return entityId; }
    /** What the person said (for the scripted scenarios). */
    String text() { return text; }

    DialogueScreen(int entityId, String name, String subtitle, String city, String text, List<Choice> choices, boolean qa) {
        super(Component.literal("Underground City | " + name));
        this.entityId = entityId; this.name = name; this.subtitle = subtitle; this.city = city; this.text = text; this.choices = choices; this.qa = qa;
    }

    @Override protected int accent() { return city.isEmpty() ? UcUi.AMBER : UcUi.cityColor(city); }
    @Override protected String titleText() { return name; }
    @Override protected UcUi.Icon titleIcon() { return subtitle.startsWith("Employer") ? UcUi.Icon.CRATE : subtitle.equals("Gate guard") ? UcUi.Icon.SCALES : subtitle.equals("Registrar") ? UcUi.Icon.BOOK : UcUi.Icon.CITY; }

    private int portraitW() { return 92; }
    private int textW() { return pw - 2 * PAD - portraitW() - 10 - 20; }

    @Override protected void init() {
        clearWidgets();
        choiceButtons.clear();
        pw = Math.min(520, width - 16);
        int lines = Math.min(7, UcUi.wrapLines(font, text, textW()).size());
        int bodyHeight = Math.max(112, 12 + lines * 10 + 12 + 8 + choices.size() * 21);
        ph = HEADER + 8 + bodyHeight + 10;
        px = (width - pw) / 2;
        py = Math.max(8, height - ph - 12);
        int bx = px + PAD + portraitW() + 10;
        int by = py + HEADER + 8 + 12 + lines * 10 + 12 + 8;
        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            UcButton button = new UcButton(bx, by + i * 21, pw - 2 * PAD - portraitW() - 10, 19, (i + 1) + "  " + choice.label(), b -> pick(choice))
                .kind(choice.id().equals("bye") ? UcButton.Kind.PLATE : UcButton.Kind.PLATE).tooltip(choice.hint());
            button.active = choice.enabled();
            add(button);
            choiceButtons.add(button);
        }
    }

    @Override public void removed() {
        // however the screen closed (goodbye, Escape, a new screen), tell the server so the citizen can go back to their day
        if (!qa && !released) { released = true; TerminalNetwork.sendDialogueChoice(entityId, "end"); }
        super.removed();
    }

    private boolean released;

    private final java.util.List<UcButton> choiceButtons = new java.util.ArrayList<>();

    /** The centre of the n-th answer in GUI coordinates (scripted-player runs click it with the real mouse). */
    int[] choiceCentre(int index) {
        UcButton button = choiceButtons.get(index);
        return new int[]{button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
    }

    /** The label of the n-th answer, or null past the last (scripted-player runs read it the way a person reads the screen). */
    String choiceLabel(int index) { return index < 0 || index >= choiceButtons.size() ? null : choiceButtons.get(index).getMessage().getString(); }

    private void pick(Choice choice) {
        if (Playtest.enabled()) Playtest.log("dialogue pick '" + choice.id() + "'");
        if (qa) return;
        if (choice.id().equals("bye")) { onClose(); return; }
        TerminalNetwork.sendDialogueChoice(entityId, choice.id());
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        boolean handled = super.mouseClicked(x, y, button);
        if (Playtest.enabled()) Playtest.log("dialogue click at " + (int) x + "," + (int) y + " button " + button + " handled=" + handled);
        return handled;
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key >= 49 && key <= 57) { int i = key - 49; if (i < choices.size() && choices.get(i).enabled()) { pick(choices.get(i)); return true; } }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override protected void renderHeaderRight(GuiGraphics g, int right) {
        int w = UcUi.displayWidth(font, subtitle) + 10;
        UcUi.chip(g, font, subtitle, right - w, py + 9, accent(), null);
        if (!city.isEmpty()) {
            String c = city.toUpperCase(Locale.ROOT);
            UcUi.chip(g, font, c, right - w - 6 - UcUi.displayWidth(font, c) - 10, py + 9, UcUi.cityColor(city), null);
        }
    }

    @Override protected void renderBody(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int top = py + HEADER + 8, bottom = py + ph - 10;
        // portrait well
        UcUi.well(g, px + PAD, top, portraitW(), bottom - top);
        var mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getEntity(entityId) instanceof LivingEntity entity) {
            // the citizen's floating name tag would be drawn across the portrait well (the game shows it for a named entity that is under the
            // crosshair, which this citizen is when you speak to them): this screen shows the name in its own header
            boolean tag = entity.isCustomNameVisible();
            var dispatcher = mc.getEntityRenderDispatcher();
            var looked = dispatcher.crosshairPickEntity;
            entity.setCustomNameVisible(false);
            dispatcher.crosshairPickEntity = null;
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, px + PAD + portraitW() / 2, bottom - 8, 40, (px + PAD + portraitW() / 2) - mouseX, (bottom - 60) - mouseY, entity);
            } finally { entity.setCustomNameVisible(tag); dispatcher.crosshairPickEntity = looked; }
        } else {
            UcUi.icon(g, titleIcon(), px + PAD + portraitW() / 2 - 8, top + 30, accent());
        }
        // speech on paper
        int sx = px + PAD + portraitW() + 10, sw = pw - 2 * PAD - portraitW() - 10;
        int lines = Math.min(7, UcUi.wrapLines(font, text, textW()).size());
        int sheetH = 12 + lines * 10 + 12;
        UcUi.paper(g, sx, top, sw, sheetH, false);
        UcUi.wrapped(g, font, text, sx + 10, top + 10, sw - 20, UcUi.INK, 7);
    }
}
